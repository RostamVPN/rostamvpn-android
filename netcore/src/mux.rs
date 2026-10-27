//! MUX2 — yamux multiplexing over one Noise carrier.
//!
//! # Why
//!
//! The legacy path opens a fresh carrier per SOCKS5 CONNECT: TCP, HTTP upgrade
//! and a Noise NK handshake for every destination a page touches. A single page
//! load can be thirty of those. Server-side that is one Noise session per
//! concurrent request rather than per user, which is what made the busy POPs
//! connection-bound (see docs/MUX_PLAN.md).
//!
//! Here one carrier carries many streams, so handshakes amortise and the
//! server's connection count falls to roughly requests divided by
//! streams-per-tunnel.
//!
//! # Wire
//!
//! ```text
//! TCP -> HTTP Upgrade -> 101 -> Noise NK
//!   first encrypted frame: MUX2|<tz>|||<cid>|      (vs META|... for legacy)
//!   then: yamux (Mode::Client) over the Noise transport
//!     per stream: [u16 BE len][stripped-SOCKS5 CONNECT] -> 10-byte SOCKS5 reply -> relay
//! ```
//!
//! ⚠️ **The muxed path DOES read a SOCKS5 reply; the legacy path does NOT.**
//! That asymmetry is real and is the single easiest way to break this: reading
//! a reply on the legacy path deadlocks both ends (the bug that made every
//! destination fail with `early eof` ~15s in), and NOT reading it here desyncs
//! the stream, because those ten bytes are then treated as payload.
//!
//! # Server support
//!
//! The deployed fleet already accepts MUX2; this is a client-only change and no
//! wire format is being altered. Verified live against 192.0.2.20:80 on
//! 2026-09-08: `101 OK`, `MUX2 header sent`, `CONNECT ... via yamux stream`.
//!
//! # Safety valve
//!
//! Off unless the OTA config turns it on (`transport.redsocket.mux`), and any
//! failure falls back to a legacy single-stream carrier for that request. So a
//! POP that does not speak MUX2 costs one retry, not a dead client, and the
//! whole feature reverts fleet-wide with a config publish and no app release.

use std::io;
use std::sync::atomic::{AtomicBool, AtomicUsize, Ordering};
use std::sync::Arc;

use tokio::io::{AsyncReadExt, AsyncWriteExt, DuplexStream};
use tokio::sync::{mpsc, oneshot, Mutex};
// Two directions, two traits, and mixing them up is the compile error you get
// first: the duplex pipe is a TOKIO stream that yamux needs as a FUTURES one
// (TokioAsyncReadCompatExt), while a yamux::Stream is a FUTURES stream we want
// back as a tokio one (FuturesAsyncReadCompatExt).
use tokio_util::compat::{Compat, FuturesAsyncReadCompatExt, TokioAsyncReadCompatExt};

use crate::config::Endpoint;
use crate::noise::{self, NoiseSession, MAX_PLAINTEXT};

/// Carriers held open per device.
///
/// THREE, not one, and the reason is head-of-line blocking rather than
/// throughput. yamux rides a single TCP connection, so one lost segment stalls
/// every stream sharing it until the retransmit lands. On a lossy Iranian
/// mobile link that turns one dropped packet into a whole-page stall. Three
/// carriers bound that blast radius while still collapsing the server's
/// connection count by an order of magnitude.
const POOL_SIZE: usize = 3;

/// Concurrent streams per carrier, matching the server's cap.
const MAX_STREAMS: usize = 256;

/// Live streams a carrier takes before the pool opens another.
///
/// The pool grows on LOAD, not on request count. Growing eagerly to
/// [`POOL_SIZE`] meant the first three requests each paid a full handshake,
/// which is exactly the cost MUX2 exists to remove: a light session would have
/// opened three carriers to serve three requests, no better than legacy. Now a
/// quiet device keeps one carrier and a busy one fans out to three.
///
/// Eight is a balance, not a measurement: high enough that ordinary browsing
/// stays on one carrier, low enough that a burst spreads before head-of-line
/// blocking on a lossy link starts to hurt.
const STREAMS_PER_CARRIER: usize = 8;

/// Hard ceiling per carrier, below yamux's own [`MAX_STREAMS`].
///
/// Exceeding the yamux cap is not a soft failure: `poll_new_outbound` returns
/// `TooManyStreams`, and yamux responds by dropping the connection and every
/// stream on it. One request over the line would kill 256 live user
/// connections. Refusing at 200 keeps that unreachable, and the refused request
/// simply takes the legacy path.
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
///
/// `0xFD` is not a valid ATYP and the request is shorter than any CONNECT, so a
/// POP without the UDP patch fails to parse it and closes the stream WITHOUT a
/// reply. That closure IS the capability probe — there is no version handshake
/// and none is wanted. See `connect_udp`.
const UDP_ASSOCIATE: [u8; 2] = [0x05, 0xFD];

/// Turn a frame-oriented Noise session into the byte stream yamux needs.
///
/// yamux wants AsyncRead + AsyncWrite; the carrier speaks `[u16 len][ct]`
/// frames. Two pump tasks bridge the two over a duplex pipe.
///
/// ⚠️ **Chunk at [`MAX_PLAINTEXT`], not at the buffer size.** Noise refuses a
/// plaintext longer than 65519 bytes (65535 minus the 16-byte tag), so reading
/// 64 KiB and encrypting it in one call fails on exactly the large writes that
/// only appear under load. The reference implementation on the server has
/// this bug; do not copy it back.
fn noise_bytestream(
    reader: tokio::net::tcp::OwnedReadHalf,
    writer: tokio::net::tcp::OwnedWriteHalf,
    session: NoiseSession,
) -> DuplexStream {
    let (caller, internal) = tokio::io::duplex(BRIDGE_BUF);
    let (mut pt_r, mut pt_w) = tokio::io::split(internal);
    let session = Arc::new(Mutex::new(session));
    let (mut tcp_r, mut tcp_w) = (reader, writer);

    // carrier -> decrypt -> yamux
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
        // THE CARRIER IS DEAD. TELL YAMUX, or nothing ever will.
        //
        // Dropping `pt_w` does NOT close a tokio duplex while the encrypt task
        // still holds `pt_r` from the same `split`, so yamux never sees EOF: it
        // waits forever for frames that cannot arrive, `poll_next_inbound` never
        // returns None, the driver never sets `dead`, and `healthy()` keeps
        // reporting true. The pool then hands that corpse to every subsequent
        // request, each paying the full CONNECT_TIMEOUT before falling back —
        // and every occurrence leaks a driver task, two pump tasks, a Noise
        // session and a socket.
        //
        // The server hit exactly this and fixed it the same way; see
        // the server's src/noise_stream.rs. One line, and without it the whole
        // failover story above is fiction.
        let _ = pt_w.shutdown().await;
    });

    // yamux -> encrypt -> carrier
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
///
/// A `yamux::Connection` must be polled by exactly one owner, and opening a
/// stream needs `&mut` on it. So the connection lives inside a driver task and
/// callers ask for streams over a channel. Dropping the last handle closes the
/// channel, which ends the driver, which tears the carrier down.
struct MuxCarrier {
    open: mpsc::Sender<OpenReq>,
    /// LIVE streams, not a lifetime total. It has to fall when a stream closes
    /// or "least loaded" degenerates into "newest", and the pool then spreads
    /// load by age instead of by use.
    live: Arc<AtomicUsize>,
    dead: Arc<AtomicBool>,
    /// This POP answered a UDP ASSOCIATE by closing the stream, so it predates
    /// the UDP patch. Remembered per CARRIER, which is the right granularity:
    /// a carrier is one connection to one POP, a POP cannot gain the feature
    /// mid-connection, and the next carrier probes again — so an upgraded POP
    /// is picked up with no app release and no config change.
    ///
    /// Without this the fleet being MIXED is expensive rather than merely
    /// mixed: the app opens one association per QUIC flow, so every flow on an
    /// unpatched POP would pay a stream open and a round trip to learn the same
    /// answer. One page can open dozens.
    no_udp: AtomicBool,
    /// Caps this carrier announced in its MUX2 hello (ctl.rs). Fixed for the
    /// carrier's life: the exit decides per tunnel, from that hello.
    blk: bool,
    msg: bool,
}

/// Decrements a carrier's live-stream count when the relay finishes.
///
/// Held by the relay for exactly as long as the stream is in use, so the count
/// is maintained without every caller having to remember to.
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
                // A caller wants a stream.
                req = rx.recv() => {
                    let Some(reply) = req else { break };
                    let r = poll_fn(|cx| conn.poll_new_outbound(cx)).await;
                    let out = r.map_err(|e| io::Error::other(format!("open stream: {e}")));
                    let failed = out.is_err();
                    let _ = reply.send(out);
                    if failed { break; }
                }
                // Keep the connection driven. The server never opens streams
                // toward us, so an inbound stream is a protocol error, and a
                // None/Err means the carrier is finished.
                inbound = poll_fn(|cx| conn.poll_next_inbound(cx)) => {
                    match inbound {
                        // Only a msg1 carrier accepts one: the control stream.
                        // Without the cap this stays exactly as before.
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
        // An acked carrier stops counting as protection the moment it dies,
        // so the next request redials instead of failing against a dead exit.
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
    // `true` announces MUX2 rather than META, which is what makes the server
    // put a yamux acceptor on this carrier instead of expecting one dest header.
    // Read the caps BEFORE the hello is written: tunnel::open announces
    // ctl::caps(), and this carrier must parse streams by what it announced.
    // The switches change only on loadConfig, before any carrier exists.
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

    // Caps count only once the exit has acknowledged them by opening the 0xC0
    // control stream. Without it this carrier is legacy for its whole life:
    // no flags byte, no verdict read, nothing eaten from an older exit.
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
        msg: msg && acked,
    })
}

/// Does this error mean the POP has no UDP support, or merely that something
/// went wrong?
///
/// Only a closed stream (the documented negative reply) and an explicit refusal
/// answer the capability question. Everything else — above all a TIMEOUT — is a
/// statement about the network or the carrier, and caching it would turn one
/// slow round trip into QUIC being off for the life of a carrier that supports
/// it. That failure is silent: traffic still flows, over TCP, a bit slower.
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
    ///
    /// Returns `Err` when mux cannot serve this request for any reason. Callers
    /// MUST treat that as "use the legacy path", never as a failed request: a
    /// POP that does not speak MUX2 has to cost one retry, not a broken app.
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
        // blk1 only: ONE trailing flags byte, bit 0 = user allowed (contract
        // 2026-10-12). Never on a tunnel without the cap.
        if carrier.blk {
            hdr.push(crate::ctl::stream_flags(host));
        }

        // [u16 BE len][stripped-SOCKS5 CONNECT], then the server's 10-byte reply.
        let sent = async {
            s.write_all(&(hdr.len() as u16).to_be_bytes()).await?;
            s.write_all(&hdr).await?;
            s.flush().await?;
            // blk1: the exit's verdict is the FIRST byte, before its reply.
            // Blocked -> PermissionDenied, which socks5 answers as
            // REPLY_BLOCKED instead of retrying the legacy path.
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
    ///
    /// Identical to [`connect`] except the request is the 2-byte marker rather
    /// than a dest header: the server opens ONE unconnected UDP socket for the
    /// association and reads each datagram's own RFC 1928 header for the
    /// target, so there is no destination to send here.
    ///
    /// 🚨 **This must never replace or mark the carrier DEAD when setup fails,
    /// and that is the whole reason it is a sibling of `connect` rather than a
    /// flag on it.** (It does set `no_udp` — that records what the POP answered
    /// and costs the carrier nothing; killing the carrier is the forbidden
    /// thing.) A POP that predates the patch closes this stream by design, and
    /// a tun opens ONE ASSOCIATION PER QUIC FLOW — so a helper that reacts to
    /// setup failure by discarding the carrier turns every QUIC attempt into a
    /// mass reset of the user's working TCP streams. Measured in the server
    /// repo: 0/3 live streams survived a retrying helper, 3/3 survived a plain
    /// open. `connect` already has the right property — it returns `Err` having
    /// touched nothing but this one stream — so this mirrors it line for line.
    ///
    /// ⚠️ It also mirrors the `live` accounting, which matters more here than
    /// for CONNECT. `pick` refuses a carrier at [`STREAM_CEILING`] precisely so
    /// asking for stream 257 never returns `TooManyStreams`, which yamux
    /// answers by tearing down the connection and every stream on it. An
    /// association is held open for the life of a QUIC flow (up to the server's
    /// 120s idle cut-off), and one page opens many, so associations that did
    /// not count toward `live` would walk a carrier past the ceiling and cause
    /// the exact mass reset this function exists to avoid.
    pub async fn connect_udp(
        &self,
        ep: &Endpoint,
        server_pubkey: &[u8; 32],
        tz: &str,
        cid: &str,
    ) -> io::Result<(Compat<yamux::Stream>, StreamGuard)> {
        let carrier = self.pick(ep, server_pubkey, tz, cid).await?;
        // Already asked this POP. Answering from the flag costs no stream and
        // no round trip, and the caller declines exactly as it would have.
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
            // A POP without the patch closes here instead of answering, so this
            // read fails with EOF. That is not an error to log loudly — it is
            // the documented negative answer to "do you speak UDP?".
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
                // ONLY these two answers mean "this POP has no UDP". A closed
                // stream is the documented negative reply and an explicit
                // refusal speaks for itself.
                //
                // ⚠️ A TIMEOUT must NOT land here. It says the network is slow
                // or the carrier is sick, not that the feature is absent, and
                // caching it would disable QUIC for the life of a carrier that
                // supports it perfectly well — on exactly the bad links where
                // one slow round trip is least surprising.
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
        // DECIDE UNDER THE LOCK, DIAL OUTSIDE IT.
        //
        // Holding the mutex across dial_carrier serialized every mux request
        // behind one 8-second handshake, so the "one retry" fallback this
        // design promises was really a queue measured in minutes on a bad
        // network — the exact conditions it exists for.
        // A protected session (an acked carrier is alive) may only use acked
        // carriers: the pool can hold a legacy one too (a redial onto a POP
        // without the caps), and a protected stream must never go over it
        // (review 8). Legacy carriers stay in the pool for unprotected use.
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
        // Another task may have dialled while we were: keep the pool bounded and
        // reuse theirs rather than stacking carriers under a burst. Same rule:
        // a protected session only reuses an acked carrier.
        let view: Vec<(usize, bool)> = pool.iter().map(|p| (p.live.load(Ordering::Relaxed), p.blk)).collect();
        if eligible_count(&view, protected) >= POOL_SIZE {
            if let Choose::Use(i) = choose(&view, protected) {
                return Ok(pool[i].clone());
            }
        }
        pool.push(c.clone());
        if protected && !c.blk {
            // The redial came back legacy while the session is protected: keep
            // it for later unprotected use, but never route this stream over it.
            return Err(io::Error::other("redial was legacy on a protected session"));
        }
        Ok(c)
    }

    /// Drop every carrier. Called on disconnect so nothing survives a session.
    pub async fn reset(&self) {
        self.carriers.lock().await.clear();
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

/// The least-loaded ELIGIBLE carrier (only acked ones on a protected
/// session), with the existing ceiling and pool-size rules applied to the
/// eligible set.
fn choose(pool: &[(usize, bool)], protected: bool) -> Choose {
    let eligible: Vec<(usize, usize)> = pool
        .iter()
        .enumerate()
        .filter(|(_, (_, acked))| !protected || *acked)
        .map(|(i, (live, _))| (i, *live))
        .collect();
    let n = eligible.len();
    match eligible.iter().min_by_key(|(_, live)| *live) {
        // A carrier at the yamux ceiling must NOT be handed out: asking for
        // stream 257 returns TooManyStreams, and yamux tears the connection
        // down with every live stream on it.
        Some(&(_, live)) if live >= STREAM_CEILING => {
            if n >= POOL_SIZE { Choose::Full } else { Choose::Dial }
        }
        Some(&(i, live)) if live < STREAMS_PER_CARRIER => Choose::Use(i),
        Some(&(i, _)) if n >= POOL_SIZE => Choose::Use(i),
        _ => Choose::Dial,
    }
}

/// Stripped-SOCKS5 destination header: the same bytes the legacy path sends,
/// minus the framing, because here yamux provides it.
/// Everything the exit writes on a stream before relayed data. On a carrier
/// whose caps were acknowledged: the verdict byte (before the exit dials),
/// then the SOCKS OK, or EOF when that dial failed. On every other carrier,
/// caps announced or not: exactly the 10-byte reply, as before.
async fn read_preamble<R: tokio::io::AsyncRead + Unpin>(
    s: &mut R,
    acked_blk: bool,
    host: &str,
    port: u16,
) -> io::Result<[u8; 10]> {
    let mut reply = [0u8; 10];
    if acked_blk {
        // EOF before the verdict byte is the exit's connect-failed path (DNS
        // failure and similar), not a protocol error (review D).
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
    ///
    /// `#[ignore]`d: it opens a live connection and lands one MUX2 record in the
    /// server's cid feed, under an obviously synthetic cid.
    ///
    ///   cargo test --lib mux::tests::live -- --ignored --nocapture
    ///
    /// The point is that a unit test cannot catch what breaks here. The two
    /// failure modes are both silent: announcing MUX2 to a server that expects
    /// META, and skipping the 10-byte SOCKS5 reply the muxed path DOES send.
    /// Neither errors; both just stall. Only a real handshake proves the mode
    /// was negotiated and the stream framing lines up.
    /// A slow carrier must not be remembered as a carrier without UDP.
    ///
    /// The two are indistinguishable at the call site unless this rule holds,
    /// and getting it wrong disables QUIC on a working POP while everything
    /// keeps working over TCP — so nothing would ever report it.
    #[test]
    fn only_a_closed_or_refused_stream_means_no_udp() {
        assert!(means_no_udp(io::ErrorKind::UnexpectedEof), "a closed stream IS the negative reply");
        assert!(means_no_udp(io::ErrorKind::ConnectionRefused));
        // The ones that must never poison the cache.
        assert!(!means_no_udp(io::ErrorKind::TimedOut), "a slow link is not a missing feature");
        assert!(!means_no_udp(io::ErrorKind::BrokenPipe));
        assert!(!means_no_udp(io::ErrorKind::ConnectionReset));
        assert!(!means_no_udp(io::ErrorKind::Other));
    }

    #[tokio::test]
    #[ignore]
    async fn live_mux_relays_two_streams_over_one_carrier() {
        use tokio::io::{AsyncReadExt, AsyncWriteExt};

        let target = std::env::var("RS_POP").unwrap_or_else(|_| "192.0.2.20:80".into());
        let pubkey = std::env::var("RS_PUB").unwrap_or_else(|_| {
            "e644c8a4dbc44c56b2e2a1f2587a785fc5813aba74674e151455d51cf86dc405".into()
        });
        let (h, p) = target.rsplit_once(':').expect("RS_POP must be host:port");
        let ep = Endpoint {
            host: h.to_string(),
            port: p.parse().expect("port"),
            // A decoy Host, as a real client sends on a direct-IP dial.
            //
            // Overridable because a CDN FRONT must carry its OWN domain here or
            // CloudFront cannot route to the distribution (config.rs sets
            // upgrade_host = the front's domain for exactly that reason). Left
            // hardcoded, this test reports the CDN tier as MUX2-incapable when
            // the real fault is the Host it sent, and the CDN tier is precisely
            // the path a censored user falls back to.
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

        // TWO streams, because one proves almost nothing: the whole point of
        // MUX2 is that the second costs no handshake. If the pool were quietly
        // opening a carrier per stream this would still pass on timing alone,
        // so the assertion that matters is the carrier count below.
        for i in 0..2 {
            let (mut s, _guard) = pool
                .connect(&ep, &key, "", cid, "ip-geo.example.com", 80)
                .await
                .unwrap_or_else(|e| panic!("stream {i} against {target}: {e}"));
            // PLAINTEXT HTTP on :80, not a TLS record on :443. A partial
            // ClientHello gets no reply at all, so a truncated handshake reads
            // as "the tunnel is broken" when the tunnel is fine and the test is
            // wrong. This asks a question the far end will actually answer.
            let req = "GET / HTTP/1.1\r\nHost: ip-geo.example.com\r\nConnection: close\r\n\r\n";
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
        // An older exit ignores the caps: no 0xC0 stream, so the gate decides
        // legacy, and the stream is read exactly as before. A legacy stub that
        // relays raw bytes must come through untouched, first byte included.
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
        // (live, acked): a busy acked carrier and an idle legacy one.
        let pool = [(STREAMS_PER_CARRIER + 5, true), (0, false)];
        assert_eq!(choose(&pool, false), Choose::Use(1), "unprotected: least loaded, as before");
        // Protected: only the acked one is eligible; below the pool size a
        // fresh carrier is dialled rather than using the legacy one.
        assert_ne!(choose(&pool, true), Choose::Use(1));
        let only_legacy = [(0, false), (0, false)];
        assert_eq!(choose(&only_legacy, true), Choose::Dial, "no acked carrier: dial, never use legacy");
        let acked_idle = [(0, false), (3, true)];
        assert_eq!(choose(&acked_idle, true), Choose::Use(1));
        // The ceiling and pool-size rules apply to the eligible set.
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
        // v4 literal: 0x05 0x01 <4 octets> <port BE>
        assert_eq!(
            dest_header("1.2.3.4", 443).unwrap(),
            vec![0x05, 0x01, 1, 2, 3, 4, 0x01, 0xbb]
        );
        // hostname: 0x05 0x03 <len> <bytes> <port BE>
        assert_eq!(
            dest_header("a.io", 80).unwrap(),
            vec![0x05, 0x03, 4, b'a', b'.', b'i', b'o', 0x00, 0x50]
        );
        // v6 literal: 0x05 0x04 <16 octets> <port BE>
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
        // 255 is the last length the single length byte can express.
        assert!(dest_header(&"a".repeat(255), 80).is_ok());
    }
}
