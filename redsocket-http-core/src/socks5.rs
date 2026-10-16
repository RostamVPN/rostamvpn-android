// Copyright 2026 Internet Mastering & Company, Inc.
// SPDX-License-Identifier: Apache-2.0

//! Local SOCKS5 server on 127.0.0.1:<port> — what hev-socks5-tunnel dials.

use std::io;
use std::net::{Ipv4Addr, Ipv6Addr};
use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::{Arc, Mutex};
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::{TcpListener, TcpStream, UdpSocket};

use crate::config::Endpoint;
use crate::noise::{self, MAX_PLAINTEXT};
use crate::tunnel::{self, Tunnel};

const RELAY_BUF: usize = 32 * 1024;

/// Endpoints to try per CONNECT before giving up. Bounded so that a total
/// outage costs a connection ~15s instead of walking every POP in the config
/// (hev opens one connection per flow — unbounded dialing piles up fast).
const MAX_DIAL_ATTEMPTS: usize = 3;

const REPLY_OK: [u8; 10] = [0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0];
const REPLY_FAIL: [u8; 10] = [0x05, 0x01, 0x00, 0x01, 0, 0, 0, 0, 0, 0];
const REPLY_CMD_UNSUPPORTED: [u8; 10] = [0x05, 0x07, 0x00, 0x01, 0, 0, 0, 0, 0, 0];
/// 0x02 — "connection not allowed by ruleset". The semantically correct refusal
/// for a filtered destination, and the same code the peer relay already uses
/// for one. It must be a REPLY and not a drop.
#[derive(Debug, PartialEq)]
enum MuxFailure {
    /// The exit refused the destination (blk1 verdict 0x02): REPLY_BLOCKED.
    Blocked,
    /// Fail the local connect; never retry on the legacy path.
    Fail,
    /// Retry on the legacy single-stream path (today's behaviour).
    Legacy,
}

/// The decision, pure so it is tested. A refusal is a block. The exit's own
/// dial failure after a verdict is a failure. On a PROTECTED session every
/// other error is a failure too.
fn mux_failure(e: &std::io::Error, protected: bool) -> MuxFailure {
    if e.kind() == std::io::ErrorKind::PermissionDenied {
        MuxFailure::Blocked
    } else if crate::ctl::is_dial_failed(e) || protected {
        MuxFailure::Fail
    } else {
        MuxFailure::Legacy
    }
}

const REPLY_BLOCKED: [u8; 10] = [0x05, 0x02, 0x00, 0x01, 0, 0, 0, 0, 0, 0];

pub struct Upstream {
    pub endpoints: Vec<Endpoint>,
    pub server_pubkey: [u8; 32],
    /// Index of the endpoint that last worked. Tried first; promoted on success so
    /// one dead POP doesn't tax every subsequent connection.
    pub active: AtomicUsize,
    /// MUX2 on, from the OTA config. See [`crate::mux`].
    pub mux: bool,
    /// The device's muxed carriers. Empty and idle when `mux` is false.
    pub pool: crate::mux::MuxPool,
}

impl Upstream {
    pub fn new(endpoints: Vec<Endpoint>, server_pubkey: [u8; 32], active: usize, mux: bool) -> Self {
        Self {
            endpoints,
            server_pubkey,
            active: AtomicUsize::new(active),
            mux,
            pool: crate::mux::MuxPool::new(),
        }
    }

    async fn dial(&self, tz: &str, cid: &str) -> io::Result<Tunnel> {
        let n = self.endpoints.len();
        if n == 0 {
            return Err(io::Error::new(io::ErrorKind::NotConnected, "no endpoints"));
        }
        let start = self.active.load(Ordering::Relaxed) % n;
        let mut last_err = None;
        for i in 0..n.min(MAX_DIAL_ATTEMPTS) {
            let idx = (start + i) % n;
            match tunnel::open_with_timeout(&self.endpoints[idx], &self.server_pubkey, tz, cid, false).await {
                Ok(t) => {
                    if idx != start {
                        log::info!("[socks5] endpoint promoted to {}", self.endpoints[idx].label());
                        self.active.store(idx, Ordering::Relaxed);
                    }
                    crate::set_active_carrier(&self.endpoints[idx].label());
                    return Ok(t);
                }
                Err(e) => {
                    log::debug!("[socks5] {} failed: {}", self.endpoints[idx].label(), e);
                    last_err = Some(e);
                }
            }
        }
        Err(last_err.unwrap_or_else(|| io::Error::new(io::ErrorKind::NotConnected, "dial failed")))
    }
}

/// Parse a SOCKS5 CONNECT request. Returns (host, port, header_len) where
/// `header_len` is how many bytes the request itself occupied — anything past
/// it in the same segment is already-sent payload that must be relayed, not
/// dropped.
fn parse_connect(d: &[u8]) -> io::Result<(String, u16, usize)> {
    let bad = |m: &str| io::Error::new(io::ErrorKind::InvalidData, m.to_string());
    if d.len() < 4 {
        return Err(bad("CONNECT too short"));
    }
    if d[0] != 0x05 {
        return Err(bad("bad SOCKS5 version in request"));
    }
    match d[3] {
        0x01 => {
            if d.len() < 10 {
                return Err(bad("IPv4 CONNECT truncated"));
            }
            let ip = Ipv4Addr::new(d[4], d[5], d[6], d[7]);
            Ok((ip.to_string(), u16::from_be_bytes([d[8], d[9]]), 10))
        }
        0x03 => {
            let dlen = *d.get(4).ok_or_else(|| bad("missing domain length"))? as usize;
            if dlen == 0 {
                return Err(bad("empty domain"));
            }
            let end = 5 + dlen;
            if d.len() < end + 2 {
                return Err(bad("domain CONNECT truncated"));
            }
            let host = String::from_utf8_lossy(&d[5..end]).to_string();
            Ok((host, u16::from_be_bytes([d[end], d[end + 1]]), end + 2))
        }
        0x04 => {
            if d.len() < 22 {
                return Err(bad("IPv6 CONNECT truncated"));
            }
            let mut o = [0u8; 16];
            o.copy_from_slice(&d[4..20]);
            Ok((
                Ipv6Addr::from(o).to_string(),
                u16::from_be_bytes([d[20], d[21]]),
                22,
            ))
        }
        t => Err(bad(&format!("unsupported address type 0x{:02x}", t))),
    }
}

/// Bind the preferred port, and fall back to one the OS picks if it is taken.
pub async fn bind_preferring(port: u16) -> io::Result<(TcpListener, u16)> {
    match TcpListener::bind(("127.0.0.1", port)).await {
        Ok(l) => {
            log::info!("[socks5] listening on 127.0.0.1:{}", port);
            Ok((l, port))
        }
        Err(e) if e.kind() == io::ErrorKind::AddrInUse => {
            let l = TcpListener::bind(("127.0.0.1", 0)).await?;
            let actual = l.local_addr()?.port();
            log::warn!(
                "[socks5] 127.0.0.1:{} is taken by something else; using {} instead",
                port, actual
            );
            Ok((l, actual))
        }
        Err(e) => Err(e),
    }
}

pub async fn serve(
    listener: TcpListener,
    up: Arc<Upstream>,
    mut shutdown: tokio::sync::watch::Receiver<bool>,
) -> io::Result<()> {
    loop {
        tokio::select! {
            _ = shutdown.changed() => {
                log::info!("[socks5] shutdown");
                break;
            }
            accepted = listener.accept() => {
                let stream = match accepted {
                    Ok((s, _)) => s,
                    Err(e) => {
                        log::debug!("[socks5] accept failed: {}", e);
                        continue;
                    }
                };
                let up = up.clone();
                tokio::spawn(async move {
                    if let Err(e) = handle(stream, up).await {
                        log::debug!("[socks5] connection ended: {}", e);
                    }
                });
            }
        }
    }
    Ok(())
}

async fn handle(mut stream: TcpStream, up: Arc<Upstream>) -> io::Result<()> {
    let eof = || io::Error::new(io::ErrorKind::UnexpectedEof, "client closed");

    let mut buf = [0u8; 512];
    let n = stream.read(&mut buf).await?;
    if n == 0 {
        return Err(eof());
    }
    if buf[0] != 0x05 {
        return Err(io::Error::new(
            io::ErrorKind::InvalidData,
            format!("not SOCKS5: version byte 0x{:02x}", buf[0]),
        ));
    }
    if n < 2 {
        return Err(io::Error::new(io::ErrorKind::InvalidData, "greeting too short"));
    }
    let greeting_len = 2 + buf[1] as usize;
    if n < greeting_len {
        return Err(io::Error::new(io::ErrorKind::InvalidData, "greeting incomplete"));
    }
    stream.write_all(&[0x05, 0x00]).await?;

    let leftover = &buf[greeting_len..n];
    let connect_data = if leftover.is_empty() {
        let n2 = stream.read(&mut buf).await?;
        if n2 == 0 {
            return Err(eof());
        }
        buf[..n2].to_vec()
    } else {
        leftover.to_vec()
    };

    if connect_data.len() < 2 {
        return Err(io::Error::new(io::ErrorKind::InvalidData, "CONNECT too short"));
    }

    if connect_data[1] == 0x03 {
        return handle_udp_associate(stream, up).await;
    }

    if connect_data[1] != 0x01 {
        log::debug!("[socks5] declining command 0x{:02x} (TCP-only)", connect_data[1]);
        let _ = stream.write_all(&REPLY_CMD_UNSUPPORTED).await;
        return Ok(());
    }

    let (dest_host, dest_port, header_len) = parse_connect(&connect_data)?;

    if crate::blocklist::blocked(&dest_host, dest_port) {
        let _ = stream.write_all(&REPLY_BLOCKED).await;
        return Ok(());
    }

    crate::CONNECTIONS.fetch_add(1, Ordering::Relaxed);

    let tz = crate::probe::get_timezone();
    let cid = crate::client_id();

    if up.mux {
        let i = up.active.load(Ordering::Relaxed);
        if let Some(ep) = up.endpoints.get(i) {
            match up
                .pool
                .connect(ep, &up.server_pubkey, &tz, &cid, &dest_host, dest_port)
                .await
            {
                Ok((muxed, guard)) => {
                    stream.write_all(&REPLY_OK).await?;
                    return relay_mux(stream, muxed, &connect_data[header_len..], guard).await;
                }
                Err(e) => match mux_failure(&e, crate::ctl::protected_session()) {
                    MuxFailure::Blocked => {
                        let _ = stream.write_all(&REPLY_BLOCKED).await;
                        return Ok(());
                    }
                    MuxFailure::Fail => {
                        log::info!("[socks5] mux failed ({e}); refusing, no legacy");
                        let _ = stream.write_all(&REPLY_FAIL).await;
                        return Ok(());
                    }
                    MuxFailure::Legacy => log::info!("[socks5] mux unavailable ({e}); using single-stream"),
                },
            }
        }
    }

    let mut t = match up.dial(&tz, &cid).await {
        Ok(t) => t,
        Err(e) => {
            let _ = stream.write_all(&REPLY_FAIL).await;
            return Err(e);
        }
    };
    if let Err(e) = tunnel::send_dest(&mut t, &dest_host, dest_port).await {
        let _ = stream.write_all(&REPLY_FAIL).await;
        return Err(e);
    }

    stream.write_all(&REPLY_OK).await?;

    if header_len < connect_data.len() {
        for chunk in connect_data[header_len..].chunks(MAX_PLAINTEXT) {
            let ct = t.session.encrypt(chunk)?;
            noise::write_frame(&mut t.writer, &ct).await?;
        }
    }

    relay(stream, t).await
}

/// SOCKS5 UDP ASSOCIATE, carried over a MUX2 stream.
async fn handle_udp_associate(mut stream: TcpStream, up: Arc<Upstream>) -> io::Result<()> {
    if !up.mux {
        let _ = stream.write_all(&REPLY_CMD_UNSUPPORTED).await;
        return Ok(());
    }
    let i = up.active.load(Ordering::Relaxed);
    let Some(ep) = up.endpoints.get(i) else {
        let _ = stream.write_all(&REPLY_CMD_UNSUPPORTED).await;
        return Ok(());
    };

    let sock = match UdpSocket::bind((Ipv4Addr::LOCALHOST, 0)).await {
        Ok(s) => s,
        Err(e) => {
            log::info!("[socks5] udp bind failed ({e})");
            let _ = stream.write_all(&REPLY_CMD_UNSUPPORTED).await;
            return Ok(());
        }
    };
    let port = sock.local_addr()?.port();

    let tz = crate::probe::get_timezone();
    let cid = crate::client_id();

    let (muxed, _guard) = match up.pool.connect_udp(ep, &up.server_pubkey, &tz, &cid).await {
        Ok(v) => v,
        Err(e) => {
            log::info!("[socks5] udp associate unavailable ({e}); declining");
            let _ = stream.write_all(&REPLY_CMD_UNSUPPORTED).await;
            return Ok(());
        }
    };

    let p = port.to_be_bytes();
    stream.write_all(&[0x05, 0x00, 0x00, 0x01, 127, 0, 0, 1, p[0], p[1]]).await?;

    let (mut ctrl_r, _ctrl_w) = stream.into_split();
    let (mut mr, mut mw) = tokio::io::split(muxed);

    let mut dbuf = vec![0u8; u16::MAX as usize];
    let n = tokio::select! {
        r = sock.recv_from(&mut dbuf) => {
            let (n, peer) = r?;
            sock.connect(peer).await?;
            n
        }
        _ = ctrl_closed(&mut ctrl_r) => return Ok(()),
    };

    tokio::select! {
        _ = ctrl_closed(&mut ctrl_r) => Ok(()),
        r = udp_up(&sock, &mut mw, &mut dbuf, n) => r,
        r = udp_down(&mut mr, &sock) => r,
    }
}

/// Resolves when the app's TCP control connection ends.
async fn ctrl_closed<R: tokio::io::AsyncRead + Unpin>(mut r: R) {
    let mut b = [0u8; 256];
    loop {
        match r.read(&mut b).await {
            Ok(0) | Err(_) => return,
            Ok(_) => {}
        }
    }
}

/// App → server. `buf` already holds `n` bytes: the datagram that opened the
/// association, which is sent BEFORE the first recv.
async fn udp_up<W: tokio::io::AsyncWrite + Unpin>(
    sock: &UdpSocket,
    mut mw: W,
    buf: &mut [u8],
    mut n: usize,
) -> io::Result<()> {
    loop {
        if n > 0 {
            crate::throttle::consume(n).await;
            mw.write_all(&(n as u16).to_be_bytes()).await?;
            mw.write_all(&buf[..n]).await?;
            mw.flush().await?;
        }
        n = sock.recv(buf).await?;
    }
}

/// Server → app. Handed over untouched.
async fn udp_down<R: tokio::io::AsyncRead + Unpin>(mut mr: R, sock: &UdpSocket) -> io::Result<()> {
    let mut len = [0u8; 2];

    let mut buf = vec![0u8; 2048];
    loop {
        mr.read_exact(&mut len).await?;
        let n = u16::from_be_bytes(len) as usize;
        if buf.len() < n {
            buf.resize(n, 0);
        }
        mr.read_exact(&mut buf[..n]).await?;
        crate::throttle::consume(n).await;

        let _ = sock.send(&buf[..n]).await;
    }
}

/// Relay between the local SOCKS5 client and a muxed stream.
async fn relay_mux(
    stream: TcpStream,
    mut muxed: tokio_util::compat::Compat<yamux::Stream>,
    pending: &[u8],

    _guard: crate::mux::StreamGuard,
) -> io::Result<()> {
    if !pending.is_empty() {
        muxed.write_all(pending).await?;
        muxed.flush().await?;
    }
    let (mut lr, mut lw) = stream.into_split();
    let (mut mr, mut mw) = tokio::io::split(muxed);

    async fn pump<R, W>(mut r: R, mut w: W) -> io::Result<()>
    where
        R: tokio::io::AsyncRead + Unpin,
        W: tokio::io::AsyncWrite + Unpin,
    {
        let mut buf = vec![0u8; 32 * 1024];
        loop {
            let n = r.read(&mut buf).await?;
            if n == 0 {
                let _ = w.shutdown().await;
                return Ok(());
            }
            crate::throttle::consume(n).await;
            w.write_all(&buf[..n]).await?;
        }
    }

    let up = pump(&mut lr, &mut mw);
    let down = pump(&mut mr, &mut lw);

    tokio::select! {
        r = up => r,
        r = down => r,
    }
}

async fn relay(stream: TcpStream, t: Tunnel) -> io::Result<()> {
    let Tunnel { mut reader, mut writer, session } = t;
    let (mut local_r, mut local_w) = stream.into_split();
    let session = Arc::new(Mutex::new(session));

    let s_up = session.clone();
    let up = tokio::spawn(async move {
        let mut buf = vec![0u8; RELAY_BUF];
        loop {
            let n = match local_r.read(&mut buf).await {
                Ok(0) | Err(_) => break,
                Ok(n) => n,
            };

            crate::throttle::consume(n).await;
            let ct = {
                let Ok(mut g) = s_up.lock() else { break };
                match g.encrypt(&buf[..n]) {
                    Ok(c) => c,
                    Err(_) => break,
                }
            };
            if noise::write_frame(&mut writer, &ct).await.is_err() {
                break;
            }
        }
        let _ = writer.shutdown().await;
    });

    let s_down = session.clone();
    let down = tokio::spawn(async move {
        loop {
            let frame = match noise::read_frame(&mut reader).await {
                Ok(f) => f,
                Err(_) => break,
            };
            let pt = {
                let Ok(mut g) = s_down.lock() else { break };
                match g.decrypt(&frame) {
                    Ok(p) => p,
                    Err(_) => break,
                }
            };
            if pt.is_empty() {
                continue;
            }
            crate::throttle::consume(pt.len()).await;
            if local_w.write_all(&pt).await.is_err() {
                break;
            }
        }
        let _ = local_w.shutdown().await;
    });

    let _ = tokio::join!(up, down);
    Ok(())
}

#[cfg(test)]
mod tests {
    #[test]
    fn protected_session_never_falls_back_to_legacy() {
        use std::io::{Error, ErrorKind};
        let ceiling = Error::new(ErrorKind::WouldBlock, "every mux carrier is at its stream ceiling");
        let timeout = Error::new(ErrorKind::TimedOut, "mux CONNECT timed out");
        let closed = Error::new(ErrorKind::UnexpectedEof, "early eof");
        for e in [&ceiling, &timeout, &closed] {
            assert_eq!(mux_failure(e, true), MuxFailure::Fail, "{e}");
            assert_eq!(mux_failure(e, false), MuxFailure::Legacy, "unprotected keeps today's fallback: {e}");
        }
        let refused = Error::new(ErrorKind::PermissionDenied, "refused by exit");
        assert_eq!(mux_failure(&refused, true), MuxFailure::Blocked);
        let dial = Error::other(crate::ctl::DialFailed);
        assert_eq!(mux_failure(&dial, false), MuxFailure::Fail);
    }

    use super::*;

    #[test]
    fn parses_ipv4_connect() {
        let req = [0x05, 0x01, 0x00, 0x01, 1, 2, 3, 4, 0x01, 0xbb];
        assert_eq!(parse_connect(&req).unwrap(), ("1.2.3.4".into(), 443, 10));
    }

    #[test]
    fn parses_domain_connect() {
        let mut req = vec![0x05, 0x01, 0x00, 0x03, 9];
        req.extend_from_slice(b"gmail.com");
        req.extend_from_slice(&443u16.to_be_bytes());
        assert_eq!(parse_connect(&req).unwrap(), ("gmail.com".into(), 443, 16));
    }

    #[test]
    fn parses_ipv6_connect() {
        let mut req = vec![0x05, 0x01, 0x00, 0x04];
        req.extend_from_slice(&Ipv6Addr::LOCALHOST.octets());
        req.extend_from_slice(&80u16.to_be_bytes());
        let (h, p, len) = parse_connect(&req).unwrap();
        assert_eq!((h.as_str(), p, len), ("::1", 80, 22));
    }

    #[test]
    fn header_len_exposes_coalesced_payload() {
        let mut req = vec![0x05, 0x01, 0x00, 0x03, 9];
        req.extend_from_slice(b"gmail.com");
        req.extend_from_slice(&443u16.to_be_bytes());
        req.extend_from_slice(b"GET / HTTP/1.1\r\n");
        let (_, _, len) = parse_connect(&req).unwrap();
        assert_eq!(&req[len..], b"GET / HTTP/1.1\r\n");
    }

    #[test]
    fn rejects_truncated_and_bad_requests() {
        assert!(parse_connect(&[0x05, 0x01, 0x00]).is_err());
        assert!(parse_connect(&[0x04, 0x01, 0x00, 0x01, 1, 2, 3, 4, 0, 80]).is_err());
        assert!(parse_connect(&[0x05, 0x01, 0x00, 0x09, 1]).is_err());
        assert!(parse_connect(&[0x05, 0x01, 0x00, 0x01, 1, 2]).is_err());
        assert!(parse_connect(&[0x05, 0x01, 0x00, 0x03, 0]).is_err());
        let mut short_domain = vec![0x05, 0x01, 0x00, 0x03, 200];
        short_domain.extend_from_slice(b"nope");
        assert!(parse_connect(&short_domain).is_err());
    }

    /// A filtered destination must get a well-formed SOCKS5 REFUSAL, never a silent
    /// close: dropping instead of declining is what made hev hammer reconnects (one
    /// TCP connection per flow) until the app OOMed.
    #[test]
    fn a_blocked_destination_is_declined_not_dropped() {
        assert_eq!(REPLY_BLOCKED.len(), REPLY_CMD_UNSUPPORTED.len(), "reply must be 10 bytes");
        assert_eq!(REPLY_BLOCKED[0], 0x05, "SOCKS5 version");

        assert_eq!(REPLY_BLOCKED[1], 0x02);
        assert_eq!(REPLY_BLOCKED[2], 0x00, "reserved");
        assert_eq!(REPLY_BLOCKED[3], 0x01, "atyp must be IPv4, as in every other reply");
        assert_ne!(REPLY_BLOCKED[1], REPLY_OK[1], "a block must not read as success");
    }

    #[test]
    fn relay_buffer_fits_one_noise_frame() {
        const _: () = assert!(RELAY_BUF <= MAX_PLAINTEXT);
    }

    /// End to end through the real front end: greeting, CONNECT, reply.
    #[test]
    fn a_filtered_destination_is_refused_before_any_dial() {
        let _g = crate::blocklist::TEST_GUARD
            .lock()
            .unwrap_or_else(|e| e.into_inner());
        let rt = tokio::runtime::Builder::new_current_thread()
            .enable_all()
            .build()
            .unwrap();
        rt.block_on(async { filtered_destination_case().await });
    }

    async fn filtered_destination_case() {
        crate::blocklist::install_json(
            r#"{"version":1,"block":{"ads":{"domains":["ads.example"]}}}"#,
            None,
        )
        .unwrap();
        crate::blocklist::set_enabled(true);

        let (listener, _) = bind_preferring(0).await.unwrap();
        let port = listener.local_addr().unwrap().port();
        let up = Arc::new(Upstream::new(Vec::new(), [0u8; 32], 0, false));
        let (_tx, rx) = tokio::sync::watch::channel(false);
        tokio::spawn(serve(listener, up, rx));

        async fn connect_to(port: u16, host: &str) -> u8 {
            let mut c = TcpStream::connect(("127.0.0.1", port)).await.unwrap();
            c.write_all(&[0x05, 0x01, 0x00]).await.unwrap();
            let mut greet = [0u8; 2];
            c.read_exact(&mut greet).await.unwrap();
            let mut req = vec![0x05, 0x01, 0x00, 0x03, host.len() as u8];
            req.extend_from_slice(host.as_bytes());
            req.extend_from_slice(&443u16.to_be_bytes());
            c.write_all(&req).await.unwrap();
            let mut reply = [0u8; 10];
            c.read_exact(&mut reply).await.unwrap();
            assert_eq!(reply[0], 0x05, "reply must be SOCKS5");
            reply[1]
        }

        assert_eq!(
            connect_to(port, "tracker.ads.example").await,
            0x02,
            "a listed destination must be refused with 'not allowed by ruleset'"
        );

        assert_eq!(
            connect_to(port, "example.com").await,
            0x01,
            "an unlisted destination must still be dialled"
        );

        crate::blocklist::set_user_rule("ads.example", "allow").unwrap();
        assert_eq!(
            connect_to(port, "tracker.ads.example").await,
            0x01,
            "user allow must reach dial on the very next connection"
        );

        crate::blocklist::set_user_rule("ads.example", "clear").unwrap();
        crate::blocklist::set_enabled(false);
        crate::blocklist::reset();
    }
    /// An empty datagram must NOT become a zero-length frame.
    #[tokio::test]
    async fn an_empty_datagram_never_closes_the_association() {
        let relay = UdpSocket::bind((Ipv4Addr::LOCALHOST, 0)).await.unwrap();
        let app = UdpSocket::bind((Ipv4Addr::LOCALHOST, 0)).await.unwrap();
        relay.connect(app.local_addr().unwrap()).await.unwrap();

        let relay_addr = relay.local_addr().unwrap();
        let (w, mut r) = tokio::io::duplex(4096);

        tokio::spawn(async move {
            let mut buf = vec![0u8; 1024];
            udp_up(&relay, w, &mut buf, 0).await
        });

        app.send_to(b"real", relay_addr).await.unwrap();

        let mut got = [0u8; 6];
        tokio::time::timeout(std::time::Duration::from_secs(5), r.read_exact(&mut got))
            .await
            .expect("upstream saw nothing: the empty datagram was forwarded and closed the stream")
            .unwrap();

        assert_eq!(got, [0x00, 0x04, b'r', b'e', b'a', b'l'], "empty datagram leaked upstream");
    }
}

#[cfg(test)]
mod relay_parity_tests {
    /// Both relays must rate-limit.
    #[test]
    fn both_relays_go_through_the_throttle() {
        let src = include_str!("socks5.rs");
        let mux = src
            .split("async fn relay_mux(")
            .nth(1)
            .expect("relay_mux must exist");
        let mux_body = &mux[..mux.find("\nasync fn relay(").unwrap_or(mux.len())];
        assert!(
            mux_body.contains("throttle::consume"),
            "relay_mux must rate-limit like the legacy relay"
        );

        assert!(
            !mux_body.contains("tokio::io::copy("),
            "tokio::io::copy() bypasses the rate limiter; use the throttled pump"
        );
    }
}

#[cfg(test)]
mod port_fallback_tests {
    use super::*;

    /// The whole point: a taken port must not be a hard failure.
    #[tokio::test]
    async fn falls_back_when_the_preferred_port_is_taken() {
        let squatter = TcpListener::bind(("127.0.0.1", 0)).await.unwrap();
        let taken = squatter.local_addr().unwrap().port();

        let (listener, got) = bind_preferring(taken).await.unwrap();
        assert_ne!(got, taken, "must not claim to have bound a port someone else holds");
        assert_eq!(got, listener.local_addr().unwrap().port(),
                   "the reported port must be the one actually bound, or callers dial nothing");
        assert!(got > 0);
        drop(squatter);
    }

    /// And when it is free, we still get what we asked for. A fallback that always
    /// falls back would move every device off 1080 for no reason.
    #[tokio::test]
    async fn keeps_the_preferred_port_when_it_is_free() {
        let probe = TcpListener::bind(("127.0.0.1", 0)).await.unwrap();
        let free = probe.local_addr().unwrap().port();
        drop(probe);

        let (listener, got) = bind_preferring(free).await.unwrap();
        assert_eq!(got, free);
        assert_eq!(listener.local_addr().unwrap().port(), free);
    }
}
