// Copyright 2026 Internet Mastering & Company, Inc.
// SPDX-License-Identifier: Apache-2.0

//! MUX2 — yamux multiplexing over one Noise carrier.

use std::io;
use std::sync::atomic::{AtomicBool, AtomicUsize, Ordering};
use std::sync::Arc;

use tokio::io::{AsyncReadExt, AsyncWriteExt, DuplexStream};
use tokio::sync::{mpsc, oneshot, Mutex};

use tokio_util::compat::{Compat, FuturesAsyncReadCompatExt, TokioAsyncReadCompatExt};

use crate::config::Endpoint;
use crate::noise::{self, NoiseSession, MAX_PLAINTEXT};

/// Carriers held open per device.
const POOL_SIZE: usize = 3;

/// Concurrent streams per carrier, matching the server's cap.
const MAX_STREAMS: usize = 256;

/// Live streams a carrier takes before the pool opens another.
const STREAMS_PER_CARRIER: usize = 8;

/// Hard ceiling per carrier, below yamux's own [`MAX_STREAMS`].
const STREAM_CEILING: usize = 200;

/// A muxed stream is only useful once the server has accepted the destination,
/// and unlike the legacy path it answers. Bound that wait: without it a POP
/// that opens the stream and then stalls hangs one request forever.
const CONNECT_TIMEOUT: std::time::Duration = std::time::Duration::from_secs(10);

/// Buffer between the yamux side and the Noise side of the bridge.
const BRIDGE_BUF: usize = 64 * 1024;

/// SOCKS5 success, which is what the muxed server replies with.
const REPLY_OK: [u8; 10] = [0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0];

/// The UDP association request: a fixed 2-byte marker instead of a dest header.
const UDP_ASSOCIATE: [u8; 2] = [0x05, 0xFD];

/// Turn a frame-oriented Noise session into the byte stream yamux needs.
fn noise_bytestream(
    reader: tokio::net::tcp::OwnedReadHalf,
    writer: tokio::net::tcp::OwnedWriteHalf,
    session: NoiseSession,
) -> DuplexStream {
    let (caller, internal) = tokio::io::duplex(BRIDGE_BUF);
    let (mut pt_r, mut pt_w) = tokio::io::split(internal);
    let session = Arc::new(Mutex::new(session));
    let (mut tcp_r, mut tcp_w) = (reader, writer);

    let sess_d = session.clone();
    tokio::spawn(async move {
        loop {
            let mut len = [0u8; 2];
            if tcp_r.read_exact(&mut len).await.is_err() {
                break;
            }
            let n = u16::from_be_bytes(len) as usize;
            if n == 0 {
                break;
            }
            let mut ct = vec![0u8; n];
            if tcp_r.read_exact(&mut ct).await.is_err() {
                break;
            }
            let pt = {
                let mut s = sess_d.lock().await;
                match s.decrypt(&ct) {
                    Ok(p) => p,
                    Err(_) => break,
                }
            };
            if pt.is_empty() {
                continue;
            }
            if pt_w.write_all(&pt).await.is_err() {
                break;
            }
        }

        let _ = pt_w.shutdown().await;
    });

    let sess_e = session;
    tokio::spawn(async move {
        let mut buf = vec![0u8; BRIDGE_BUF];
        'outer: loop {
            let n = match pt_r.read(&mut buf).await {
                Ok(0) | Err(_) => break,
                Ok(n) => n,
            };
            for chunk in buf[..n].chunks(MAX_PLAINTEXT) {
                let ct = {
                    let mut s = sess_e.lock().await;
                    match s.encrypt(chunk) {
                        Ok(ct) => ct,
                        Err(_) => break 'outer,
                    }
                };
                if noise::write_frame(&mut tcp_w, &ct).await.is_err() {
                    break 'outer;
                }
            }
        }
        let _ = tcp_w.shutdown().await;
    });

    caller
}

type OpenReq = oneshot::Sender<io::Result<yamux::Stream>>;

/// One live muxed carrier.
struct MuxCarrier {
    open: mpsc::Sender<OpenReq>,
    /// LIVE streams, not a lifetime total. It has to fall when a stream closes or
    /// "least loaded" degenerates into "newest", and the pool then spreads load by
    /// age instead of by use.
    live: Arc<AtomicUsize>,
    dead: Arc<AtomicBool>,
    /// This POP answered a UDP ASSOCIATE by closing the stream, so it predates the
    /// UDP patch. Remembered per CARRIER, which is the right granularity.
    no_udp: AtomicBool,
    /// Caps this carrier announced in its MUX2 hello (ctl.rs). Fixed for the
    /// carrier's life: the exit decides per tunnel, from that hello.
    blk: bool,
}

/// Decrements a carrier's live-stream count when the relay finishes.
pub struct StreamGuard(Arc<AtomicUsize>);

impl Drop for StreamGuard {
    fn drop(&mut self) {
        self.0.fetch_sub(1, Ordering::Relaxed);
    }
}

impl MuxCarrier {
    fn healthy(&self) -> bool {
        !self.dead.load(Ordering::Relaxed) && !self.open.is_closed()
    }

    async fn open_stream(&self) -> io::Result<yamux::Stream> {
        let (tx, rx) = oneshot::channel();
        self.open
            .send(tx)
            .await
            .map_err(|_| io::Error::new(io::ErrorKind::BrokenPipe, "mux carrier gone"))?;
        rx.await
            .map_err(|_| io::Error::new(io::ErrorKind::BrokenPipe, "mux driver dropped"))?
    }
}

/// Drive one yamux connection: serve open requests, and keep the connection
/// polled so its frames actually move.
fn spawn_driver(
    mut conn: yamux::Connection<Compat<DuplexStream>>,
    mut rx: mpsc::Receiver<OpenReq>,
    dead: Arc<AtomicBool>,
    gate: Option<Arc<crate::ctl::CapsGate>>,
) {
    tokio::spawn(async move {
        use futures::future::poll_fn;
        loop {
            tokio::select! {
                req = rx.recv() => {
                    let Some(reply) = req else { break };
                    let r = poll_fn(|cx| conn.poll_new_outbound(cx)).await;
                    let out = r.map_err(|e| io::Error::other(format!("open stream: {e}")));
                    let failed = out.is_err();
                    let _ = reply.send(out);
                    if failed { break; }
                }

                inbound = poll_fn(|cx| conn.poll_next_inbound(cx)) => {
                    match inbound {
                        Some(Ok(stream)) if gate.is_some() => {
                            tokio::spawn(crate::ctl::serve(stream.compat(), gate.clone()));
                        }
                        Some(Ok(_stream)) => {
                            log::warn!("[mux] server opened a stream; ignoring");
                        }
                        Some(Err(e)) => {
                            log::info!("[mux] carrier error: {e}");
                            break;
                        }
                        None => break,
                    }
                }
            }
        }
        dead.store(true, Ordering::Relaxed);

        if let Some(g) = &gate {
            g.retire();
        }
        log::info!("[mux] carrier closed");
    });
}

/// Open a carrier and put a yamux client on it.
async fn dial_carrier(
    ep: &Endpoint,
    server_pubkey: &[u8; 32],
    tz: &str,
    cid: &str,
) -> io::Result<MuxCarrier> {
    let (blk, msg) = crate::ctl::caps();
    let t = crate::tunnel::open_with_timeout(ep, server_pubkey, tz, cid, true).await?;
    let crate::tunnel::Tunnel { reader, writer, session } = t;

    let bytes = noise_bytestream(reader, writer, session);
    let mut cfg = yamux::Config::default();
    cfg.set_max_num_streams(MAX_STREAMS);
    let conn = yamux::Connection::new(bytes.compat(), cfg, yamux::Mode::Client);

    let (tx, rx) = mpsc::channel::<OpenReq>(64);
    let dead = Arc::new(AtomicBool::new(false));
    let gate = (blk || msg).then(|| crate::ctl::CapsGate::new(dead.clone()));
    spawn_driver(conn, rx, dead.clone(), gate.clone());

    let acked = match &gate {
        Some(g) => {
            let a = g.decide(crate::ctl::CAPS_WAIT).await;
            crate::ctl::note_carrier_caps(a);
            a
        }
        None => false,
    };
    log::info!("[mux] carrier up via {} (caps acked: {acked})", ep.label());
    crate::set_active_carrier(&ep.label());
    Ok(MuxCarrier {
        open: tx,
        live: Arc::new(AtomicUsize::new(0)),
        dead,
        no_udp: AtomicBool::new(false),
        blk: blk && acked,
    })
}

/// Does this error mean the POP has no UDP support, or merely that something
/// went wrong?
fn means_no_udp(kind: io::ErrorKind) -> bool {
    matches!(kind, io::ErrorKind::UnexpectedEof | io::ErrorKind::ConnectionRefused)
}

/// The device's carriers. Lazily filled, self-healing: a carrier that dies is
/// dropped and the next request replaces it.
pub struct MuxPool {
    carriers: Mutex<Vec<Arc<MuxCarrier>>>,
}

impl MuxPool {
    pub fn new() -> Self {
        Self { carriers: Mutex::new(Vec::new()) }
    }

    /// A stream to `host:port`, ready to relay.
    pub async fn connect(
        &self,
        ep: &Endpoint,
        server_pubkey: &[u8; 32],
        tz: &str,
        cid: &str,
        host: &str,
        port: u16,
    ) -> io::Result<(Compat<yamux::Stream>, StreamGuard)> {
        let carrier = self.pick(ep, server_pubkey, tz, cid).await?;
        let stream = carrier.open_stream().await?;
        carrier.live.fetch_add(1, Ordering::Relaxed);
        let guard = StreamGuard(carrier.live.clone());

        let mut s = stream.compat();
        let mut hdr = dest_header(host, port)?;

        if carrier.blk {
            hdr.push(crate::ctl::stream_flags(host));
        }

        let sent = async {
            s.write_all(&(hdr.len() as u16).to_be_bytes()).await?;
            s.write_all(&hdr).await?;
            s.flush().await?;

            let reply = read_preamble(&mut s, carrier.blk, host, port).await?;
            if reply[..2] != REPLY_OK[..2] {
                return Err(io::Error::new(
                    io::ErrorKind::ConnectionRefused,
                    format!("mux CONNECT refused: {:02x?}", reply),
                ));
            }
            Ok::<_, io::Error>(())
        };
        match tokio::time::timeout(CONNECT_TIMEOUT, sent).await {
            Ok(Ok(())) => Ok((s, guard)),
            Ok(Err(e)) => Err(e),
            Err(_) => Err(io::Error::new(io::ErrorKind::TimedOut, "mux CONNECT timed out")),
        }
    }

    /// A stream carrying a UDP association, ready to frame datagrams.
    pub async fn connect_udp(
        &self,
        ep: &Endpoint,
        server_pubkey: &[u8; 32],
        tz: &str,
        cid: &str,
    ) -> io::Result<(Compat<yamux::Stream>, StreamGuard)> {
        let carrier = self.pick(ep, server_pubkey, tz, cid).await?;

        if carrier.no_udp.load(Ordering::Relaxed) {
            return Err(io::Error::new(
                io::ErrorKind::Unsupported,
                "carrier does not speak UDP (cached)",
            ));
        }
        let stream = carrier.open_stream().await?;
        carrier.live.fetch_add(1, Ordering::Relaxed);
        let guard = StreamGuard(carrier.live.clone());

        let mut s = stream.compat();
        let sent = async {
            s.write_all(&(UDP_ASSOCIATE.len() as u16).to_be_bytes()).await?;
            s.write_all(&UDP_ASSOCIATE).await?;
            s.flush().await?;
            if carrier.blk {
                let v = crate::ctl::read_verdict(&mut s).await?;
                crate::ctl::apply("udp", 0, v)?;
            }

            let mut reply = [0u8; 10];
            s.read_exact(&mut reply).await?;
            if reply[..2] != REPLY_OK[..2] {
                return Err(io::Error::new(
                    io::ErrorKind::ConnectionRefused,
                    format!("mux UDP ASSOCIATE refused: {:02x?}", reply),
                ));
            }
            Ok::<_, io::Error>(())
        };
        match tokio::time::timeout(CONNECT_TIMEOUT, sent).await {
            Ok(Ok(())) => Ok((s, guard)),
            Ok(Err(e)) => {
                if means_no_udp(e.kind()) {
                    carrier.no_udp.store(true, Ordering::Relaxed);
                    log::info!("[mux] carrier has no UDP support; not probing it again");
                }
                Err(e)
            }
            Err(_) => Err(io::Error::new(io::ErrorKind::TimedOut, "mux UDP ASSOCIATE timed out")),
        }
    }

    /// Least-loaded healthy carrier, growing the pool up to [`POOL_SIZE`].
    async fn pick(
        &self,
        ep: &Endpoint,
        server_pubkey: &[u8; 32],
        tz: &str,
        cid: &str,
    ) -> io::Result<Arc<MuxCarrier>> {
        let protected = crate::ctl::protected_session();
        let existing = {
            let mut pool = self.carriers.lock().await;
            pool.retain(|c| c.healthy());
            let view: Vec<(usize, bool)> = pool.iter().map(|c| (c.live.load(Ordering::Relaxed), c.blk)).collect();
            match choose(&view, protected) {
                Choose::Use(i) => Some(pool[i].clone()),
                Choose::Full => {
                    return Err(io::Error::new(
                        io::ErrorKind::WouldBlock,
                        "every mux carrier is at its stream ceiling",
                    ))
                }
                Choose::Dial => None,
            }
        };
        if let Some(c) = existing {
            return Ok(c);
        }

        let c = Arc::new(dial_carrier(ep, server_pubkey, tz, cid).await?);
        let mut pool = self.carriers.lock().await;
        pool.retain(|p| p.healthy());

        let view: Vec<(usize, bool)> = pool.iter().map(|p| (p.live.load(Ordering::Relaxed), p.blk)).collect();
        if eligible_count(&view, protected) >= POOL_SIZE {
            if let Choose::Use(i) = choose(&view, protected) {
                return Ok(pool[i].clone());
            }
        }
        pool.push(c.clone());
        if protected && !c.blk {
            return Err(io::Error::other("redial was legacy on a protected session"));
        }
        Ok(c)
    }
}

/// Pool decision, pure so it is tested. Each entry is (live streams, acked).
#[derive(Debug, PartialEq)]
enum Choose {
    Use(usize),
    Dial,
    Full,
}

fn eligible_count(pool: &[(usize, bool)], protected: bool) -> usize {
    pool.iter().filter(|(_, acked)| !protected || *acked).count()
}

/// The least-loaded ELIGIBLE carrier (only acked ones on a protected session),
/// with the existing ceiling and pool-size rules applied to the eligible set.
fn choose(pool: &[(usize, bool)], protected: bool) -> Choose {
    let eligible: Vec<(usize, usize)> = pool
        .iter()
        .enumerate()
        .filter(|(_, (_, acked))| !protected || *acked)
        .map(|(i, (live, _))| (i, *live))
        .collect();
    let n = eligible.len();
    match eligible.iter().min_by_key(|(_, live)| *live) {
        Some(&(_, live)) if live >= STREAM_CEILING => {
            if n >= POOL_SIZE { Choose::Full } else { Choose::Dial }
        }
        Some(&(i, live)) if live < STREAMS_PER_CARRIER => Choose::Use(i),
        Some(&(i, _)) if n >= POOL_SIZE => Choose::Use(i),
        _ => Choose::Dial,
    }
}

/// Stripped-SOCKS5 destination header: the same bytes the legacy path sends,
/// minus the framing, because here yamux provides it. Everything the exit
/// writes on a stream before relayed data. On a carrier whose caps were
/// acknowledged.
async fn read_preamble<R: tokio::io::AsyncRead + Unpin>(
    s: &mut R,
    acked_blk: bool,
    host: &str,
    port: u16,
) -> io::Result<[u8; 10]> {
    let mut reply = [0u8; 10];
    if acked_blk {
        let v = match crate::ctl::read_verdict(s).await {
            Err(e) if e.kind() == io::ErrorKind::UnexpectedEof => return Err(io::Error::other(crate::ctl::DialFailed)),
            other => other?,
        };
        crate::ctl::apply(host, port, v)?;
        crate::ctl::read_reply_after_verdict(s, &mut reply).await?;
    } else {
        s.read_exact(&mut reply).await?;
    }
    Ok(reply)
}

fn dest_header(host: &str, port: u16) -> io::Result<Vec<u8>> {
    use std::net::{Ipv4Addr, Ipv6Addr};
    let mut h = Vec::with_capacity(host.len() + 8);
    h.push(0x05);
    if let Ok(ip) = host.parse::<Ipv4Addr>() {
        h.push(0x01);
        h.extend_from_slice(&ip.octets());
    } else if let Ok(ip) = host.parse::<Ipv6Addr>() {
        h.push(0x04);
        h.extend_from_slice(&ip.octets());
    } else {
        if host.is_empty() || host.len() > 255 {
            return Err(io::Error::new(
                io::ErrorKind::InvalidInput,
                "destination hostname length out of range",
            ));
        }
        h.push(0x03);
        h.push(host.len() as u8);
        h.extend_from_slice(host.as_bytes());
    }
    h.extend_from_slice(&port.to_be_bytes());
    Ok(h)
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Real MUX2 session against a production POP.
    #[test]
    fn only_a_closed_or_refused_stream_means_no_udp() {
        assert!(means_no_udp(io::ErrorKind::UnexpectedEof), "a closed stream IS the negative reply");
        assert!(means_no_udp(io::ErrorKind::ConnectionRefused));

        assert!(!means_no_udp(io::ErrorKind::TimedOut), "a slow link is not a missing feature");
        assert!(!means_no_udp(io::ErrorKind::BrokenPipe));
        assert!(!means_no_udp(io::ErrorKind::ConnectionReset));
        assert!(!means_no_udp(io::ErrorKind::Other));
    }

    #[tokio::test]
    #[ignore]
    async fn live_mux_relays_two_streams_over_one_carrier() {
        use tokio::io::{AsyncReadExt, AsyncWriteExt};

        let target = std::env::var("RS_POP").unwrap_or_else(|_| "192.0.2.160:80".into());
        let pubkey = std::env::var("RS_PUB").unwrap_or_else(|_| {
            "e644c8a4dbc44c56b2e2a1f2587a785fc5813aba74674e151455d51cf86dc405".into()
        });
        let (h, p) = target.rsplit_once(':').expect("RS_POP must be host:port");
        let ep = Endpoint {
            host: h.to_string(),
            port: p.parse().expect("port"),

            upgrade_host: std::env::var("RS_HOST")
                .unwrap_or_else(|_| "www.bing.com".to_string()),
            upgrade_path: "/noise".to_string(),
            priority: 0,
            weight: 1,
            fallback: false,
            premium: false,
        };
        let mut key = [0u8; 32];
        key.copy_from_slice(&hex::decode(pubkey.trim()).expect("hex"));

        let pool = MuxPool::new();
        let cid = "cafe0000cafe0003";

        for i in 0..2 {
            let (mut s, _guard) = pool
                .connect(&ep, &key, "", cid, "rostam.app", 80)
                .await
                .unwrap_or_else(|e| panic!("stream {i} against {target}: {e}"));

            let req = "GET / HTTP/1.1\r\nHost: rostam.app\r\nConnection: close\r\n\r\n";
            s.write_all(req.as_bytes()).await.unwrap();
            s.flush().await.unwrap();
            let mut buf = [0u8; 64];
            let n = tokio::time::timeout(std::time::Duration::from_secs(15), s.read(&mut buf))
                .await
                .unwrap_or_else(|_| panic!("stream {i} read timed out"))
                .unwrap();
            let head = String::from_utf8_lossy(&buf[..n]);
            assert!(head.starts_with("HTTP/1."), "stream {i} got: {head:?}");
            println!("[live-mux] stream {i} relayed: {}", head.lines().next().unwrap_or(""));
        }

        let carriers = pool.carriers.lock().await.len();
        println!("[live-mux] carriers opened: {carriers}");
        assert_eq!(carriers, 1, "two streams must share ONE carrier; that is the feature");
    }

    #[tokio::test]
    async fn caps_on_against_a_legacy_exit_eats_nothing() {
        let dead = Arc::new(AtomicBool::new(false));
        let gate = crate::ctl::CapsGate::new(dead);
        let acked = gate.decide(std::time::Duration::from_millis(50)).await;
        assert!(!acked);
        let mut wire: &[u8] = &[0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0, 0x02, 0xAA, 0xBB];
        let reply = read_preamble(&mut wire, acked, "example.com", 443).await.unwrap();
        assert_eq!(reply[..2], REPLY_OK[..2]);
        assert_eq!(wire, &[0x02, 0xAA, 0xBB], "application data starting 0x02 must not be read as a verdict");
    }

    #[tokio::test]
    async fn zero_byte_eof_on_a_capable_stream_is_connect_failed() {
        let mut wire: &[u8] = &[];
        let e = read_preamble(&mut wire, true, "nx.example", 443).await.unwrap_err();
        assert!(crate::ctl::is_dial_failed(&e), "{e}");
    }

    #[test]
    fn a_protected_session_never_picks_a_legacy_carrier() {
        let pool = [(STREAMS_PER_CARRIER + 5, true), (0, false)];
        assert_eq!(choose(&pool, false), Choose::Use(1), "unprotected: least loaded, as before");

        assert_ne!(choose(&pool, true), Choose::Use(1));
        let only_legacy = [(0, false), (0, false)];
        assert_eq!(choose(&only_legacy, true), Choose::Dial, "no acked carrier: dial, never use legacy");
        let acked_idle = [(0, false), (3, true)];
        assert_eq!(choose(&acked_idle, true), Choose::Use(1));

        let full: Vec<(usize, bool)> = (0..POOL_SIZE).map(|_| (STREAM_CEILING, true)).collect();
        assert_eq!(choose(&full, true), Choose::Full);
    }

    #[tokio::test]
    async fn acked_carrier_reads_verdict_then_reply() {
        let mut wire: &[u8] = &[0x00, 0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0, 0x7F];
        let reply = read_preamble(&mut wire, true, "example.com", 443).await.unwrap();
        assert_eq!(reply[..2], REPLY_OK[..2]);
        assert_eq!(wire, &[0x7F]);
    }

    #[test]
    fn dest_header_matches_the_legacy_bytes() {
        assert_eq!(
            dest_header("1.2.3.4", 443).unwrap(),
            vec![0x05, 0x01, 1, 2, 3, 4, 0x01, 0xbb]
        );

        assert_eq!(
            dest_header("a.io", 80).unwrap(),
            vec![0x05, 0x03, 4, b'a', b'.', b'i', b'o', 0x00, 0x50]
        );

        let v6 = dest_header("::1", 8080).unwrap();
        assert_eq!(v6[0], 0x05);
        assert_eq!(v6[1], 0x04);
        assert_eq!(v6.len(), 2 + 16 + 2);
        assert_eq!(&v6[18..], &[0x1f, 0x90]);
    }

    #[test]
    fn an_unusable_hostname_is_refused_rather_than_truncated() {
        assert!(dest_header("", 80).is_err());
        assert!(dest_header(&"a".repeat(256), 80).is_err());

        assert!(dest_header(&"a".repeat(255), 80).is_ok());
    }
}
