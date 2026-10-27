//! Local SOCKS5 server on 127.0.0.1:<port> — what hev-socks5-tunnel dials.
//!
//! One fresh tunnel per CONNECT (no mux): the carrier is single-stream, so each
//! accepted connection gets its own HTTP-Upgrade + Noise handshake.

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
// ponytail: fixed cap; make it adaptive only if real logs show 3 is too few.
const MAX_DIAL_ATTEMPTS: usize = 3;

const REPLY_OK: [u8; 10] = [0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0];
const REPLY_FAIL: [u8; 10] = [0x05, 0x01, 0x00, 0x01, 0, 0, 0, 0, 0, 0];
const REPLY_CMD_UNSUPPORTED: [u8; 10] = [0x05, 0x07, 0x00, 0x01, 0, 0, 0, 0, 0, 0];
/// 0x02 — "connection not allowed by ruleset". The semantically correct refusal
/// for a filtered destination, and the same code the tunnel already uses for
/// one. It must be a REPLY and not a drop: closing silently is what made hev
/// hammer reconnects until the app OOMed.
/// What to do when the mux path could not serve a CONNECT.
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
/// other error is a failure too: the legacy path carries no verdict, so the
/// stream ceiling, a timeout or an exit-side close would otherwise reach the
/// destination unprotected while the UI says Protected (review A). Without
/// protection, the legacy fallback is unchanged.
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
    /// Index of the endpoint that last worked. Tried first; promoted on success
    /// so one dead POP doesn't tax every subsequent connection.
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

    pub fn active_label(&self) -> String {
        let i = self.active.load(Ordering::Relaxed);
        self.endpoints.get(i).map(|e| e.label()).unwrap_or_default()
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
/// `header_len` is how many bytes the request itself occupied — anything past it
/// in the same segment is already-sent payload that must be relayed, not dropped.
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

/// Bind the local SOCKS5 port. Separate from [`serve`] so the caller can report
/// "connected" only once it is live.
pub async fn bind(port: u16) -> io::Result<TcpListener> {
    let listener = TcpListener::bind(("127.0.0.1", port)).await?;
    log::info!("[socks5] listening on 127.0.0.1:{}", port);
    Ok(listener)
}

/// Bind the preferred port, and fall back to one the OS picks if it is taken.
///
/// **The port is not ours.** 1080 is the registered SOCKS port, so any other
/// proxy, tunnel or dev tool on the device may already hold it, and the user
/// gets a tunnel that will not start with no way to understand why. That is a
/// hard failure caused by something we do not control, which is the wrong shape
/// for a censorship tool: it fails exactly on the crowded, heavily-tooled
/// devices most likely to belong to someone who needs it.
///
/// Port 0 asks the OS for any free port. The caller MUST use the returned port
/// rather than the one it asked for, which is why this returns both.
///
/// Only `AddrInUse` falls back. A permission error or an exhausted socket table
/// is a real fault and must surface, not be papered over with a different port.
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

    // hev often coalesces the greeting and the CONNECT into one segment, so read
    // once and keep whatever is left over rather than assuming a second read.
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
    // RFC 1929 username/password, offered only by our own tun bridge when it
    // is configured with the user "@origin": the username then carries the
    // app-side socket of this connection ("o:<src>:<port>><dst>:<port>") so the
    // app can ask the OS which app owns it (per-app attribution). Nothing
    // leaves the device; the password is ignored. Everyone else gets no-auth.
    let mut pending: Vec<u8> = buf[greeting_len..n].to_vec();
    let mut origin: Option<String> = None;
    if buf[2..greeting_len].contains(&0x02) && !buf[2..greeting_len].contains(&0x00) {
        stream.write_all(&[0x05, 0x02]).await?;
        // ver, ulen, user, plen, pass: read until complete (may already be here).
        loop {
            if pending.len() >= 2 {
                let ulen = pending[1] as usize;
                if pending.len() >= 3 + ulen {
                    let plen = pending[2 + ulen] as usize;
                    if pending.len() >= 3 + ulen + plen {
                        let user = String::from_utf8_lossy(&pending[2..2 + ulen]).to_string();
                        pending.drain(..3 + ulen + plen);
                        if let Some(o) = user.strip_prefix("o:") {
                            origin = Some(o.chars().take(128).collect());
                        }
                        break;
                    }
                }
            }
            let mut more = [0u8; 512];
            let m = stream.read(&mut more).await?;
            if m == 0 {
                return Err(eof());
            }
            pending.extend_from_slice(&more[..m]);
            if pending.len() > 1024 {
                return Err(io::Error::new(io::ErrorKind::InvalidData, "auth too long"));
            }
        }
        stream.write_all(&[0x01, 0x00]).await?;
    } else {
        stream.write_all(&[0x05, 0x00]).await?; // no auth
    }

    let leftover = &pending[..];
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
    // UDP ASSOCIATE (0x03) is carried over MUX2 — that is what lets QUIC and
    // HTTP/3 work instead of being forced to fall back to TCP. It has its own
    // handler because nothing below applies to it: no destination to parse, no
    // payload in this segment, and the relay is framed datagrams rather than a
    // byte stream.
    if connect_data[1] == 0x03 {
        return handle_udp_associate(stream, up).await;
    }
    // Everything else is still TCP-only — notably hev's FWD_UDP (0x05), which
    // is a different mechanism this server does not speak. Decline with a real
    // reply: closing silently makes hev hammer reconnects (one TCP conn per UDP
    // flow) until the app OOMs; a clean 0x07 makes it fall back QUIC→TCP.
    if connect_data[1] != 0x01 {
        log::debug!("[socks5] declining command 0x{:02x} (TCP-only)", connect_data[1]);
        let _ = stream.write_all(&REPLY_CMD_UNSUPPORTED).await;
        return Ok(());
    }

    let (dest_host, dest_port, header_len) = parse_connect(&connect_data)?;

    // On-device filtering. Before dial() on purpose: a blocked destination must
    // never reach a carrier, so nothing about the wire changes — this is the
    // only chokepoint every outbound connection passes through, and the host is
    // a real domain here because the app routes DNS through mapdns.
    //
    // DECLINE, never drop (see REPLY_BLOCKED). Off unless the app has both
    // enabled it and a list is loaded, and open on any doubt.
    if crate::blocklist::blocked(&dest_host, dest_port) {
        crate::flows::refused(&dest_host, dest_port);
        let _ = stream.write_all(&REPLY_BLOCKED).await;
        return Ok(());
    }

    crate::CONNECTIONS.fetch_add(1, Ordering::Relaxed);

    let tz = crate::get_timezone();
    let cid = crate::client_id();

    // ── MUX2 first, when the config allows it ────────────────────────────────
    //
    // One carrier serves many destinations, so a page load costs one handshake
    // instead of thirty. A failure here is NEVER fatal: it falls through to the
    // legacy path below, so a POP that does not speak MUX2 costs this request a
    // retry rather than breaking the app.
    if up.mux {
        let i = up.active.load(Ordering::Relaxed);
        if let Some(ep) = up.endpoints.get(i) {
            match up
                .pool
                .connect(ep, &up.server_pubkey, &tz, &cid, &dest_host, dest_port)
                .await
            {
                Ok((muxed, guard)) => {
                    // The muxed server DOES answer, and mux::connect has already
                    // consumed those ten bytes. What we send here is our own
                    // reply to the local SOCKS5 client.
                    stream.write_all(&REPLY_OK).await?;
                    // The flow table: one lookup per CONNECT, atomics after.
                    let bytes = crate::flows::open(&dest_host, dest_port);
                    if let Some(o) = &origin { crate::flows::origin(&dest_host, o); }
                    return relay_mux(stream, muxed, &connect_data[header_len..], guard, bytes).await;
                }
                Err(e) => match mux_failure(&e, crate::ctl::protected_session()) {
                    MuxFailure::Blocked => {
                        crate::flows::refused(&dest_host, dest_port);
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

    // The server sends no reply — we synthesize success once the dest header is
    // away. Reading for a reply here would deadlock both ends.
    stream.write_all(&REPLY_OK).await?;
    let bytes = crate::flows::open(&dest_host, dest_port);
    if let Some(o) = &origin { crate::flows::origin(&dest_host, o); }

    // Payload the client already sent in the CONNECT segment must go first, or
    // the request is silently truncated.
    if header_len < connect_data.len() {
        bytes.add_up(connect_data.len() - header_len);
        for chunk in connect_data[header_len..].chunks(MAX_PLAINTEXT) {
            let ct = t.session.encrypt(chunk)?;
            noise::write_frame(&mut t.writer, &ct).await?;
        }
    }

    relay(stream, t, bytes).await
}

/// SOCKS5 UDP ASSOCIATE, carried over a MUX2 stream.
///
/// The app (hev) opens ONE association per UDP flow and holds the TCP control
/// connection for its life. We bind a loopback UDP socket, tell the app its
/// port, and shuttle datagrams to a muxed stream as `[u16 BE len][datagram]`.
///
/// 🚨 **Every failure before the reply writes X'07' and returns Ok.** Dropping
/// the control connection instead is the reconnect storm described above, and
/// UDP is exactly where it bites hardest: hev would re-dial per QUIC flow.
///
/// The datagram is never modified. Its RFC 1928 header is the SERVER's
/// business — it is what says where the packet goes — so the blocklist does
/// not run here either. The server resolves the name and applies the policy;
/// this side is a framer. The one exception is a READ: the first datagram's
/// header names the destination for the on-device flow table (bytes and
/// associations per destination and port); the bytes go out untouched.
async fn handle_udp_associate(mut stream: TcpStream, up: Arc<Upstream>) -> io::Result<()> {
    // The request carries a DST.ADDR/DST.PORT that RFC 1928 lets the client
    // leave as zeroes, and it is advice about where it *expects* to send. We
    // have no use for it, and anything after the header is not payload — UDP
    // payload arrives on the socket below, never in this segment.

    // MUX2 only. There is no single-stream form of this: the legacy carrier is
    // one relay with no framing, so there is nowhere to put a second datagram.
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

    let tz = crate::get_timezone();
    let cid = crate::client_id();
    // A POP without the UDP patch closes this stream without answering, and
    // that closure is the whole capability probe — so an Err here is the
    // ORDINARY case against an older server, not a fault. Decline and let the
    // app fall back to TCP, exactly as it did before this feature existed.
    let (muxed, _guard) = match up.pool.connect_udp(ep, &up.server_pubkey, &tz, &cid).await {
        Ok(v) => v,
        Err(e) => {
            log::info!("[socks5] udp associate unavailable ({e}); declining");
            let _ = stream.write_all(&REPLY_CMD_UNSUPPORTED).await;
            return Ok(());
        }
    };

    // BND.ADDR/BND.PORT: where the app should send its datagrams.
    let p = port.to_be_bytes();
    stream.write_all(&[0x05, 0x00, 0x00, 0x01, 127, 0, 0, 1, p[0], p[1]]).await?;

    let (mut ctrl_r, _ctrl_w) = stream.into_split();
    let (mut mr, mut mw) = tokio::io::split(muxed);

    // hev picks an ephemeral source port and never announces it, so the first
    // datagram IS the announcement. Race the control connection so a client
    // that gives up before sending anything cannot strand this task.
    // Full-size, deliberately, unlike udp_down's: a short buffer makes recv
    // TRUNCATE the datagram with no error and no way to tell, which corrupts
    // traffic rather than failing it.
    let mut dbuf = vec![0u8; u16::MAX as usize];
    let n = tokio::select! {
        r = sock.recv_from(&mut dbuf) => {
            let (n, peer) = r?;
            sock.connect(peer).await?;
            n
        }
        _ = ctrl_closed(&mut ctrl_r) => return Ok(()),
    };

    // The flow table: one lookup per association (hev opens one per flow), by
    // the first datagram's destination; atomics per datagram after.
    let bytes = match udp_dest(&dbuf[..n]) {
        Some((host, port)) => crate::flows::open_udp(&host, port),
        None => Arc::default(),
    };

    // The control connection ending tears the association down, per RFC 1928.
    // That is the ONLY orderly end: neither direction of a datagram relay has
    // an EOF of its own.
    tokio::select! {
        _ = ctrl_closed(&mut ctrl_r) => Ok(()),
        r = udp_up(&sock, &mut mw, &mut dbuf, n, &bytes) => r,
        r = udp_down(&mut mr, &sock, &bytes) => r,
    }
}

/// DST.ADDR / DST.PORT of an RFC 1928 UDP request header (RSV RSV FRAG ATYP
/// ADDR PORT), read only for the flow table. None for anything malformed.
fn udp_dest(d: &[u8]) -> Option<(String, u16)> {
    if d.len() < 4 || d[2] != 0 {
        return None;
    }
    let (host, at) = match d[3] {
        0x01 => (std::net::Ipv4Addr::new(*d.get(4)?, d[5], d[6], d[7]).to_string(), 8),
        0x03 => {
            let len = *d.get(4)? as usize;
            let name = d.get(5..5 + len)?;
            (String::from_utf8_lossy(name).into_owned(), 5 + len)
        }
        0x04 => {
            let a: [u8; 16] = d.get(4..20)?.try_into().ok()?;
            (std::net::Ipv6Addr::from(a).to_string(), 20)
        }
        _ => return None,
    };
    let p = d.get(at..at + 2)?;
    Some((host, u16::from_be_bytes([p[0], p[1]])))
}

/// Resolves when the app's TCP control connection ends.
///
/// RFC 1928 sends nothing on it, so anything that arrives is ignored rather
/// than treated as the end — only a real EOF or error closes the association.
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
/// association, which is sent BEFORE the first recv. Dropping it would lose the
/// packet that started the flow — for QUIC the Initial, so the connection would
/// simply never happen.
async fn udp_up<W: tokio::io::AsyncWrite + Unpin>(
    sock: &UdpSocket,
    mut mw: W,
    buf: &mut [u8],
    mut n: usize,
    bytes: &crate::flows::Counters,
) -> io::Result<()> {
    loop {
        // 🚨 A zero-length frame is the association's CLOSE marker on this wire
        // — the server breaks its read loop on `n == 0` — so an empty datagram
        // must never be forwarded. An app sending one is doing something legal
        // and meaningless (RFC 1928 wants a 10-byte header at minimum, so there
        // is nothing to route), and relaying it would let any app tear down its
        // own association with a packet it is entitled to send. Dropping it
        // here is what a UDP relay does with a datagram it cannot parse.
        if n > 0 {
            // Free-tier speed limit. `relay_mux` explains why this is not
            // optional: a relay that skips it reopens the defect where MUX2
            // silently turned the limit into a no-op, and unmetered QUIC looks
            // exactly like working.
            crate::throttle::consume(n).await;
            mw.write_all(&(n as u16).to_be_bytes()).await?;
            mw.write_all(&buf[..n]).await?;
            mw.flush().await?;
            bytes.add_up(n);
        }
        n = sock.recv(buf).await?;
    }
}

/// Server → app. Handed over untouched: the datagram's own header is for the
/// server, and hev may use forms this code was not written to understand.
async fn udp_down<R: tokio::io::AsyncRead + Unpin>(
    mut mr: R,
    sock: &UdpSocket,
    bytes: &crate::flows::Counters,
) -> io::Result<()> {
    let mut len = [0u8; 2];
    // Grown to fit, never pre-sized to the 64 KiB ceiling: a QUIC datagram is
    // one MTU, and hev holds ONE ASSOCIATION PER FLOW — so a fixed 64 KiB here
    // is 64 KiB times every open flow, against a Network Extension memory cap
    // of about 50 MB on iOS. The server's own relay_udp grows the same way for
    // the same reason. The read length is known before the read, so unlike the
    // socket side this cannot truncate.
    let mut buf = vec![0u8; 2048];
    loop {
        mr.read_exact(&mut len).await?;
        let n = u16::from_be_bytes(len) as usize;
        if buf.len() < n {
            buf.resize(n, 0);
        }
        mr.read_exact(&mut buf[..n]).await?;
        crate::throttle::consume(n).await;
        bytes.add_down(n);
        // An app that stopped listening is not worth killing the association
        // for. The control connection is what decides when this ends.
        let _ = sock.send(&buf[..n]).await;
    }
}

/// Relay between the local SOCKS5 client and a muxed stream.
///
/// Simpler than the legacy relay because yamux already frames and the Noise
/// encryption happens in the bridge, so this is a plain bidirectional copy.
/// `pending` is payload the client packed into the CONNECT segment; it must go
/// first or the request is silently truncated.
async fn relay_mux(
    stream: TcpStream,
    mut muxed: tokio_util::compat::Compat<yamux::Stream>,
    pending: &[u8],
    // Held for the life of the relay: dropping it is what decrements the
    // carrier's live-stream count, which is how the pool knows when to grow.
    _guard: crate::mux::StreamGuard,
    // This destination's byte counters in the flow table.
    bytes: Arc<crate::flows::Counters>,
) -> io::Result<()> {
    if !pending.is_empty() {
        bytes.add_up(pending.len());
        muxed.write_all(pending).await?;
        muxed.flush().await?;
    }
    let (mut lr, mut lw) = stream.into_split();
    let (mut mr, mut mw) = tokio::io::split(muxed);

    // THROTTLED COPIES, not tokio::io::copy.
    //
    // `copy` moves bytes as fast as the socket allows and never consults the
    // rate limiter, so routing the default path through MUX2 silently turned
    // the free-tier speed limit into a no-op: every free user would have got
    // full speed and the paywall would have had nothing left to sell. The
    // legacy relay calls consume() on both directions (see `relay` below) and
    // this has to match it exactly.
    async fn pump<R, W>(mut r: R, mut w: W, count: impl Fn(usize)) -> io::Result<()>
    where
        R: tokio::io::AsyncRead + Unpin,
        W: tokio::io::AsyncWrite + Unpin,
    {
        let mut buf = vec![0u8; 32 * 1024];
        loop {
            let n = r.read(&mut buf).await?;
            if n == 0 {
                // Half-close rather than abandon: the peer may still have a
                // reply in flight, and dropping here resets it.
                let _ = w.shutdown().await;
                return Ok(());
            }
            crate::throttle::consume(n).await;
            count(n);
            w.write_all(&buf[..n]).await?;
        }
    }

    let up = pump(&mut lr, &mut mw, |n| bytes.add_up(n));
    let down = pump(&mut mr, &mut lw, |n| bytes.add_down(n));
    // Whichever half finishes first ends the pair, matching the legacy relay.
    tokio::select! {
        r = up => r,
        r = down => r,
    }
}

async fn relay(stream: TcpStream, t: Tunnel, bytes: Arc<crate::flows::Counters>) -> io::Result<()> {
    let Tunnel { mut reader, mut writer, session } = t;
    let (mut local_r, mut local_w) = stream.into_split();
    let session = Arc::new(Mutex::new(session));

    // Noise transport mode keeps independent send/recv nonces, so both directions
    // can share one session. The lock is never held across an await.
    let s_up = session.clone();
    let b_up = bytes.clone();
    let up = tokio::spawn(async move {
        let mut buf = vec![0u8; RELAY_BUF];
        loop {
            let n = match local_r.read(&mut buf).await {
                Ok(0) | Err(_) => break,
                Ok(n) => n,
            };
            // Free-tier speed limit (no-op when unlimited).
            crate::throttle::consume(n).await;
            b_up.add_up(n);
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
            bytes.add_down(pt.len());
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
        // A CONNECT with an HTTP request glued on: the payload must be recoverable,
        // otherwise the first request of every connection is silently dropped.
        let mut req = vec![0x05, 0x01, 0x00, 0x03, 9];
        req.extend_from_slice(b"gmail.com");
        req.extend_from_slice(&443u16.to_be_bytes());
        req.extend_from_slice(b"GET / HTTP/1.1\r\n");
        let (_, _, len) = parse_connect(&req).unwrap();
        assert_eq!(&req[len..], b"GET / HTTP/1.1\r\n");
    }

    #[test]
    fn rejects_truncated_and_bad_requests() {
        assert!(parse_connect(&[0x05, 0x01, 0x00]).is_err()); // too short
        assert!(parse_connect(&[0x04, 0x01, 0x00, 0x01, 1, 2, 3, 4, 0, 80]).is_err()); // v4 socks
        assert!(parse_connect(&[0x05, 0x01, 0x00, 0x09, 1]).is_err()); // bad atyp
        assert!(parse_connect(&[0x05, 0x01, 0x00, 0x01, 1, 2]).is_err()); // truncated v4
        assert!(parse_connect(&[0x05, 0x01, 0x00, 0x03, 0]).is_err()); // empty domain
        let mut short_domain = vec![0x05, 0x01, 0x00, 0x03, 200];
        short_domain.extend_from_slice(b"nope");
        assert!(parse_connect(&short_domain).is_err()); // length lies
    }

    /// A filtered destination must get a well-formed SOCKS5 REFUSAL, never a
    /// silent close: dropping instead of declining is what made hev hammer
    /// reconnects (one TCP connection per flow) until the app OOMed.
    #[test]
    fn a_blocked_destination_is_declined_not_dropped() {
        assert_eq!(REPLY_BLOCKED.len(), REPLY_CMD_UNSUPPORTED.len(), "reply must be 10 bytes");
        assert_eq!(REPLY_BLOCKED[0], 0x05, "SOCKS5 version");
        // 0x02 = connection not allowed by ruleset — the semantically correct
        // code for a policy refusal.
        assert_eq!(REPLY_BLOCKED[1], 0x02);
        assert_eq!(REPLY_BLOCKED[2], 0x00, "reserved");
        assert_eq!(REPLY_BLOCKED[3], 0x01, "atyp must be IPv4, as in every other reply");
        assert_ne!(REPLY_BLOCKED[1], REPLY_OK[1], "a block must not read as success");
    }

    #[test]
    fn relay_buffer_fits_one_noise_frame() {
        // A larger buffer than the frame limit would fail every write at runtime.
        assert!(RELAY_BUF <= MAX_PLAINTEXT);
    }

    /// End to end through the real front end: greeting, CONNECT, reply.
    ///
    /// The upstream here has ZERO endpoints, so `dial()` fails instantly with
    /// `REPLY_FAIL` (0x01). A blocked destination must therefore come back as
    /// 0x02 — which proves the filter ran, that it ran BEFORE dial, and that
    /// the client gets a real refusal rather than a dropped connection.
    #[test]
    fn a_filtered_destination_is_refused_before_any_dial() {
        // The guard is taken OUTSIDE the runtime: a std MutexGuard held across
        // an await is a deadlock waiting to happen, even in a test.
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

        let listener = bind(0).await.unwrap();
        let port = listener.local_addr().unwrap().port();
        let up = Arc::new(Upstream::new(Vec::new(), [0u8; 32], 0, false));
        let (_tx, rx) = tokio::sync::watch::channel(false);
        tokio::spawn(serve(listener, up, rx));

        // Returns the SOCKS5 reply code for one CONNECT to `host`.
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
        // Nothing else changes: an unlisted host still reaches dial, which fails
        // here only because this upstream has no endpoints.
        assert_eq!(
            connect_to(port, "example.com").await,
            0x01,
            "an unlisted destination must still be dialled"
        );

        // The user's escape hatch, over the same live front end.
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
    ///
    /// The server reads `n == 0` as the association's CLOSE marker, so
    /// forwarding an app's empty datagram tore the association down — a legal,
    /// meaningless UDP packet silently killing the app's own QUIC flow. Found
    /// by `spikes/udp-socks5/03_malformed.py`; nothing else notices, because a
    /// closed association looks exactly like a server that never had UDP.
    #[test]
    fn udp_dest_reads_the_header_only() {
        let mut v4 = vec![0, 0, 0, 1, 8, 8, 4, 4, 0x01, 0xBB];
        v4.extend_from_slice(b"payload");
        assert_eq!(udp_dest(&v4), Some(("8.8.4.4".into(), 443)));
        let mut name = vec![0, 0, 0, 3, 11];
        name.extend_from_slice(b"example.com");
        name.extend_from_slice(&[0x0D, 0x96]);
        assert_eq!(udp_dest(&name), Some(("example.com".into(), 3478)));
        let mut v6 = vec![0, 0, 0, 4];
        v6.extend_from_slice(&std::net::Ipv6Addr::LOCALHOST.octets());
        v6.extend_from_slice(&[0, 53]);
        assert_eq!(udp_dest(&v6), Some(("::1".into(), 53)));
        assert_eq!(udp_dest(&[0, 0, 1, 1, 8, 8, 4, 4, 0, 53]), None); // fragment
        assert_eq!(udp_dest(&[0, 0, 0, 3, 40, b'a']), None); // truncated name
        assert_eq!(udp_dest(&[0, 0, 0, 9]), None);
    }

    #[tokio::test]
    async fn an_empty_datagram_never_closes_the_association() {
        let relay = UdpSocket::bind((Ipv4Addr::LOCALHOST, 0)).await.unwrap();
        let app = UdpSocket::bind((Ipv4Addr::LOCALHOST, 0)).await.unwrap();
        relay.connect(app.local_addr().unwrap()).await.unwrap();

        let relay_addr = relay.local_addr().unwrap();
        let (w, mut r) = tokio::io::duplex(4096);
        // n = 0 is the opening datagram: empty, and therefore unsendable.
        tokio::spawn(async move {
            let mut buf = vec![0u8; 1024];
            let bytes = crate::flows::Counters::default();
            udp_up(&relay, w, &mut buf, 0, &bytes).await
        });

        app.send_to(b"real", relay_addr).await.unwrap();

        let mut got = [0u8; 6];
        tokio::time::timeout(std::time::Duration::from_secs(5), r.read_exact(&mut got))
            .await
            .expect("upstream saw nothing: the empty datagram was forwarded and closed the stream")
            .unwrap();
        // The first frame on the wire is the REAL datagram, not a 0-length one.
        assert_eq!(got, [0x00, 0x04, b'r', b'e', b'a', b'l'], "empty datagram leaked upstream");
    }
}

#[cfg(test)]
mod relay_parity_tests {
    /// Both relays must rate-limit. The muxed one did not, and because MUX2 is
    /// the DEFAULT path in the shipped config that silently gave every free
    /// user unlimited speed — a revenue bug that no test would have caught,
    /// because nothing fails: traffic just flows faster than policy allows.
    ///
    /// Source-level rather than behavioural on purpose. Measuring a rate needs
    /// a live tunnel and a clock, which makes for a slow, flaky test; the thing
    /// that actually regresses here is someone reaching for `tokio::io::copy`
    /// again because it is one line.
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
        // The CALL, with its paren. Matching the bare path also matched the
        // comment above that warns against it, so the test failed on its own
        // documentation.
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
        // Squat on a port the way another app would.
        let squatter = TcpListener::bind(("127.0.0.1", 0)).await.unwrap();
        let taken = squatter.local_addr().unwrap().port();

        let (listener, got) = bind_preferring(taken).await.unwrap();
        assert_ne!(got, taken, "must not claim to have bound a port someone else holds");
        assert_eq!(got, listener.local_addr().unwrap().port(),
                   "the reported port must be the one actually bound, or callers dial nothing");
        assert!(got > 0);
        drop(squatter);
    }

    /// And when it is free, we still get what we asked for. A fallback that
    /// always falls back would move every device off 1080 for no reason.
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
