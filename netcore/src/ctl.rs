//! Capability-gated additions to MUX2 (silo/internetpro/PROTECTION-DESIGN-
//! 2026-10-12.md sections 8 and 9). NOTHING here touches the wire unless the
//! OTA config turns a capability on: with both flags off the MUX2 header, the
//! per-stream exchange and the yamux session are byte-identical to before.
//!
//! - `blk1`: the exit writes one verdict byte first on every stream.
//! - `msg1`: the exit opens one control stream (first byte 0xC0, then
//!   `u16 LE` length-prefixed JSON frames both ways).
//!
//! Caps are announced on MUX2 only, at header index 6, with the brand id in
//! the `app` field: `MUX2|<tz>|<app>||<cid>||blk1,msg1` (settled with the
//! server agent 2026-10-12).

use std::collections::VecDeque;
use std::io;
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::Mutex;

use tokio::io::{AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt};
use tokio::sync::mpsc;

static BLK: AtomicBool = AtomicBool::new(false);
static MSG: AtomicBool = AtomicBool::new(false);
static APP: Mutex<String> = Mutex::new(String::new());
/// Highest `ts` seen on the control stream; sent as `{since}` on reconnect.
static SINCE: AtomicU64 = AtomicU64::new(0);
/// Downstream messages waiting for the app to drain them. Bounded.
static NOTES: Mutex<VecDeque<String>> = Mutex::new(VecDeque::new());
const NOTES_CAP: usize = 200;
/// One sender per live control stream, for upstream `{rules}` frames.
static UP: Mutex<Vec<mpsc::UnboundedSender<Vec<u8>>>> = Mutex::new(Vec::new());

/// First byte the exit writes on the control stream.
pub const CONTROL_MAGIC: u8 = 0xC0;
const MAX_FRAME: usize = 64 * 1024;

/// Read the two OTA switches from the app's config document. Absent = off.
/// `protection.enabled` implies both caps; `messaging.enabled` alone gives msg1.
pub fn configure(config_json: &str) {
    let v: serde_json::Value = match serde_json::from_str(config_json) {
        Ok(v) => v,
        Err(_) => return,
    };
    let on = |k: &str| v.get(k).and_then(|o| o.get("enabled")).and_then(|b| b.as_bool()).unwrap_or(false);
    // Always both together (contract review 2026-10-12): the exit opens the
    // 0xC0 stream on any cap tunnel, and that stream is the only proof it
    // understood the caps.
    let blk = on("protection") || on("messaging");
    let msg = blk;
    BLK.store(blk, Ordering::SeqCst);
    MSG.store(msg, Ordering::SeqCst);
    restore_cursor(&v);
    log::info!("[ctl] caps blk1={blk} msg1={msg}");
}

pub fn set_app(app: &str) {
    let clean: String = app.chars().filter(|c| c.is_ascii_alphanumeric() || *c == '-').take(24).collect();
    if let Ok(mut g) = APP.lock() {
        *g = clean;
    }
}

/// The caps this process would announce right now: "", "msg1" or "blk1,msg1".
pub fn caps() -> (bool, bool) {
    (BLK.load(Ordering::SeqCst), MSG.load(Ordering::SeqCst))
}

/// The MUX2 hello. Unchanged (`MUX2|tz|||cid|`) when no cap is on.
pub fn mux_hello(tz: &str, cid: &str, blk: bool, msg: bool) -> String {
    if !blk && !msg {
        return format!("MUX2|{}|||{}|", tz, cid);
    }
    let caps = match (blk, msg) {
        (true, true) => "blk1,msg1",
        (true, false) => "blk1",
        _ => "msg1",
    };
    let app = APP.lock().map(|g| g.clone()).unwrap_or_default();
    format!("MUX2|{}|{}||{}||{}", tz, app, cid, caps)
}

// ── Verdict byte (blk1) ──────────────────────────────────────────────────────

#[derive(Debug, PartialEq)]
pub enum Verdict {
    Clear,
    Notify { bits: u16, reason: String },
    Blocked { bits: u16, reason: String },
}

/// Read the one verdict the exit writes first on a `blk1` stream.
pub async fn read_verdict<R: AsyncRead + Unpin>(r: &mut R) -> io::Result<Verdict> {
    let mut b = [0u8; 1];
    r.read_exact(&mut b).await?;
    match b[0] {
        0x00 => Ok(Verdict::Clear),
        0x01 => read_detail(r).await.map(|(bits, reason)| Verdict::Notify { bits, reason }),
        0x02 => read_detail(r).await.map(|(bits, reason)| Verdict::Blocked { bits, reason }),
        x => Err(io::Error::new(io::ErrorKind::InvalidData, format!("verdict byte {x:#04x}"))),
    }
}

/// `[u16 LE bits][u8 n][n bytes UTF-8 reason]`.
async fn read_detail<R: AsyncRead + Unpin>(r: &mut R) -> io::Result<(u16, String)> {
    let mut h = [0u8; 3];
    r.read_exact(&mut h).await?;
    let bits = u16::from_le_bytes([h[0], h[1]]);
    let mut reason = vec![0u8; h[2] as usize];
    r.read_exact(&mut reason).await?;
    Ok((bits, String::from_utf8_lossy(&reason).into_owned()))
}

/// The exit judged the stream (verdict first, before it dials) and then could
/// not reach the destination: verdict, then EOF instead of the SOCKS OK.
/// Answered as a plain failure; retrying on the legacy path would only repeat
/// the same dial.
#[derive(Debug)]
pub struct DialFailed;
impl std::fmt::Display for DialFailed {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str("exit could not reach the destination")
    }
}
impl std::error::Error for DialFailed {}

pub fn is_dial_failed(e: &io::Error) -> bool {
    e.get_ref().is_some_and(|r| r.is::<DialFailed>())
}

/// Read the 10-byte SOCKS reply that follows a non-blocking verdict. EOF here
/// is the exit's dial failure, not a protocol mismatch.
pub async fn read_reply_after_verdict<R: AsyncRead + Unpin>(r: &mut R, reply: &mut [u8; 10]) -> io::Result<()> {
    match r.read_exact(reply).await {
        Err(e) if e.kind() == io::ErrorKind::UnexpectedEof => Err(io::Error::other(DialFailed)),
        other => other.map(|_| ()),
    }
}

/// The error a blocked stream returns. `PermissionDenied` is reserved for this
/// so the SOCKS layer answers REPLY_BLOCKED instead of retrying the legacy
/// path, which would route around the exit's decision.
pub fn blocked_error() -> io::Error {
    io::Error::new(io::ErrorKind::PermissionDenied, "refused by exit")
}

/// Apply a verdict for `host:port`: record notify/blocked entries in the
/// recent ring, and turn Blocked into [`blocked_error`].
pub fn apply(host: &str, port: u16, v: Verdict) -> io::Result<()> {
    match v {
        Verdict::Clear => Ok(()),
        Verdict::Notify { bits, reason } => {
            crate::blocklist::record_remote(host, port, bits, &reason, false);
            Ok(())
        }
        Verdict::Blocked { bits, reason } => {
            crate::blocklist::record_remote(host, port, bits, &reason, true);
            Err(blocked_error())
        }
    }
}

// ── Control stream (msg1) ────────────────────────────────────────────────────

fn push_note(json: String) {
    if let Ok(mut q) = NOTES.lock() {
        if q.len() >= NOTES_CAP {
            q.pop_front();
        }
        q.push_back(json);
    }
}

/// Every queued downstream message as a JSON array, oldest first. Drains.
pub fn drain_notes() -> String {
    let items: Vec<String> = NOTES.lock().map(|mut q| q.drain(..).collect()).unwrap_or_default();
    format!("[{}]", items.join(","))
}

async fn write_frame<W: AsyncWrite + Unpin>(w: &mut W, json: &[u8]) -> io::Result<()> {
    if json.len() > u16::MAX as usize {
        return Err(io::Error::new(io::ErrorKind::InvalidInput, "control frame too large"));
    }
    w.write_all(&(json.len() as u16).to_le_bytes()).await?;
    w.write_all(json).await?;
    w.flush().await
}

/// Queue `{rules:{allow,deny}}` for every live control stream. Called when the
/// user changes a rule. A no-op when no msg1 tunnel is up.
pub fn push_rules() {
    let frame = rules_frame();
    // The exit echoes `rules` to every device of this cid, and the app applies
    // an echo through set_user_lists_json, which calls this again. Skipping an
    // identical frame is what stops that from becoming a loop.
    if let Ok(mut last) = LAST_RULES.lock() {
        if last.as_deref() == Some(frame.as_slice()) {
            return;
        }
        *last = Some(frame.clone());
    }
    if let Ok(mut g) = UP.lock() {
        g.retain(|tx| tx.send(frame.clone()).is_ok());
    }
}

fn rules_frame() -> Vec<u8> {
    let mut rules: serde_json::Value =
        serde_json::from_str(&crate::blocklist::user_lists_json()).unwrap_or_default();
    if let Some(o) = rules.as_object_mut() {
        o.insert("categories_off".into(), CATEGORIES_OFF.load(Ordering::SeqCst).into());
    }
    serde_json::json!({ "rules": rules }).to_string().into_bytes()
}

static LAST_RULES: Mutex<Option<Vec<u8>>> = Mutex::new(None);
static CATEGORIES_OFF: std::sync::atomic::AtomicU16 = std::sync::atomic::AtomicU16::new(0);

/// Category bits the user switched off. Sent upstream in every {rules} frame
/// as `categories_off`; pushes immediately when it changes.
/// Store the mask. Does NOT push: the caller (set_user_lists_json) installs
/// the lists first and then pushes ONE {rules} frame carrying both, instead of
/// a first frame with the new mask and the old lists (§12 re-run, finding 2).
pub fn set_categories_off(mask: u16) {
    CATEGORIES_OFF.store(mask, Ordering::SeqCst);
}

/// The trailing flags byte of a blk1 stream header (contract 2026-10-12):
/// bit 0 = the user allowed this destination; skip protection.
pub fn stream_flags(host: &str) -> u8 {
    u8::from(crate::blocklist::user_allows(host))
}

/// The newest carrier's caps decision: true while the pool's latest carrier was
/// acknowledged by its exit. While true, socks5 never falls back to the
/// legacy single-stream path, which carries no verdict (review A).
/// LIVE acked carriers. Rule A (no legacy fallback) holds only while one is
/// alive: a dead acked carrier is retired, the pool redials through the normal
/// order, and a legacy result is reported honestly (§12 re-run, finding 1). A
/// sticky flag here pinned the pool to a dead exit.
static LIVE_ACKED: std::sync::atomic::AtomicUsize = std::sync::atomic::AtomicUsize::new(0);

pub fn protected_session() -> bool {
    LIVE_ACKED.load(Ordering::SeqCst) > 0
}

/// Called once per capped carrier after its decision. Tells the app too, so a
/// redial onto a legacy POP clears "Protected" mid-session (review #8).
pub fn note_carrier_caps(acked: bool) {
    if !acked {
        push_note(serde_json::json!({ "seq": 0, "ts": 0, "type": "caps", "payload": { "acked": false } }).to_string());
    }
}

/// The tunnel stopped: nothing is protected any more.
pub fn reset_session() {
    // New generation FIRST: an ack from a carrier of the ended session that
    // lands after this point is stale and must not count (review 7).
    SESSION.fetch_add(1, Ordering::SeqCst);
    LIVE_ACKED.store(0, Ordering::SeqCst);
}

/// Tunnel session generation. Bumped by reset_session (stop). A CapsGate
/// remembers the generation it was created in; its ack and retire only touch
/// LIVE_ACKED while that generation is current. Otherwise an in-flight 0xC0
/// landing after stop left LIVE_ACKED=1 into the next session, where every mux
/// error became REPLY_FAIL with no legacy fallback.
static SESSION: AtomicU64 = AtomicU64::new(0);

/// Highest seq of messages the exit marked as broadcasts (`scope:"broadcast"`).
static BROADCAST_SEQ: AtomicU64 = AtomicU64::new(0);

/// Advance the {since, broadcast_seq} cursor from one delivered message.
/// `since` counts BROADCASTS only: a per-cid message carries the exit's own
/// wall clock and must not hide a broadcast from the same second (review B).
/// Until the exit marks scope, an unmarked message counts as before. A ts far
/// in the future (clock skew, a bad writer) is ignored rather than blacking
/// out every broadcast after it.
fn note_cursor(v: &serde_json::Value, seq: u64) -> bool {
    let before = (SINCE.load(Ordering::Relaxed), BROADCAST_SEQ.load(Ordering::Relaxed));
    let scope = v.get("scope").and_then(|s| s.as_str());
    if scope == Some("cid") {
        return false;
    }
    if scope == Some("broadcast") && plausible(seq) {
        BROADCAST_SEQ.fetch_max(seq, Ordering::Relaxed);
    }
    if let Some(ts) = v.get("ts").and_then(|t| t.as_u64()) {
        let now_ms = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .map(|d| d.as_millis() as u64)
            .unwrap_or(0);
        // ts is epoch ms on the wire; tolerate seconds from an older writer.
        let horizon = if ts < 100_000_000_000 { now_ms / 1000 + 86_400 } else { now_ms + 86_400_000 };
        if ts <= horizon {
            SINCE.fetch_max(ts, Ordering::Relaxed);
        }
    }
    before != (SINCE.load(Ordering::Relaxed), BROADCAST_SEQ.load(Ordering::Relaxed))
}

/// Queue the cursor for the host to persist (Android prefs, iOS app-group
/// defaults); it comes back as `ctl_cursor` in the next loadConfig.
fn push_cursor() {
    push_note(serde_json::json!({ "seq": 0, "ts": 0, "type": "cursor", "payload": {
        "since": SINCE.load(Ordering::Relaxed),
        "broadcast_seq": BROADCAST_SEQ.load(Ordering::Relaxed),
    }}).to_string());
}

/// A counter or timestamp more than a day ahead of now is a bad value (clock
/// skew, a broken writer), not progress. Broadcast seqs are epoch ms on the
/// wire; small per-cid counters are always plausible. Without this, fetch_max
/// lets one bad value pin the cursor forever (review 9).
fn plausible(v: u64) -> bool {
    let now_ms = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_millis() as u64)
        .unwrap_or(0);
    if v < 100_000_000_000 {
        // Small counter, or epoch seconds: compare as seconds.
        v <= now_ms / 1000 + 86_400 || v < 1_000_000_000
    } else {
        v <= now_ms + 86_400_000
    }
}

/// Restore a persisted cursor (the host stores it from drained notes and
/// passes it back inside the config as `ctl_cursor`), so a restart does not
/// replay the whole brand broadcast mailbox.
fn restore_cursor(v: &serde_json::Value) {
    if let Some(c) = v.get("ctl_cursor") {
        if let Some(s) = c.get("since").and_then(|x| x.as_u64()).filter(|&s| plausible(s)) {
            SINCE.fetch_max(s, Ordering::Relaxed);
        }
        if let Some(b) = c.get("broadcast_seq").and_then(|x| x.as_u64()).filter(|&b| plausible(b)) {
            BROADCAST_SEQ.fetch_max(b, Ordering::Relaxed);
        }
    }
}

/// Has the exit acknowledged this carrier's caps? Decided ONCE per carrier.
///
/// A capped client must not read a verdict byte from an exit that never
/// understood the caps, or it eats the first byte of real data on every
/// stream. The only acknowledgement is the 0xC0 control stream: until it
/// arrives the carrier is pending, and the dialler waits [`CAPS_WAIT`] for it;
/// without it the carrier is legacy for its whole life. A control stream that
/// arrives after that decision means the two sides disagree, so the carrier
/// is killed and the next dial decides afresh.
pub struct CapsGate {
    state: std::sync::atomic::AtomicU8, // 0 pending, 1 acked, 2 legacy, 3 retired
    notify: tokio::sync::Notify,
    dead: std::sync::Arc<AtomicBool>,
    /// The session this carrier belongs to (see SESSION).
    generation: u64,
    /// Whether this carrier's ack was counted in LIVE_ACKED.
    counted: AtomicBool,
}

pub const CAPS_WAIT: std::time::Duration = std::time::Duration::from_secs(3);

impl CapsGate {
    pub fn new(dead: std::sync::Arc<AtomicBool>) -> std::sync::Arc<Self> {
        std::sync::Arc::new(Self {
            state: 0.into(),
            notify: tokio::sync::Notify::new(),
            dead,
            generation: SESSION.load(Ordering::SeqCst),
            counted: AtomicBool::new(false),
        })
    }

    /// Wait up to `wait` for the acknowledgement. true = acked, false = legacy.
    pub async fn decide(&self, wait: std::time::Duration) -> bool {
        let acked = tokio::time::timeout(wait, async {
            loop {
                // Register interest BEFORE re-checking the state: a wake that
                // lands between the check and the first poll would otherwise be
                // lost and cost the whole CAPS_WAIT (review C).
                let n = self.notify.notified();
                tokio::pin!(n);
                n.as_mut().enable();
                if self.state.load(Ordering::SeqCst) == 1 {
                    return;
                }
                n.await;
            }
        })
        .await
        .is_ok();
        if acked {
            return true;
        }
        // Lost the race to a just-arriving stream? Then it is acked after all.
        self.state.compare_exchange(0, 2, Ordering::SeqCst, Ordering::SeqCst).is_err()
            && self.state.load(Ordering::SeqCst) == 1
    }

    /// The carrier's driver ended. An acked carrier stops counting as
    /// protection; when it was the last one the app is told at once (caps
    /// acked:false) so "Protected" drops before the next redial decides.
    pub fn retire(&self) {
        self.state.swap(3, Ordering::SeqCst);
        // Only an ack that was counted, in a session that is still current.
        if self.counted.swap(false, Ordering::SeqCst) && self.generation == SESSION.load(Ordering::SeqCst) {
            let left = LIVE_ACKED
                .fetch_update(Ordering::SeqCst, Ordering::SeqCst, |n| Some(n.saturating_sub(1)))
                .map(|n| n.saturating_sub(1))
                .unwrap_or(0);
            if left == 0 {
                note_carrier_caps(false);
            }
        }
    }

    /// The control stream arrived. false = too late: the carrier was already
    /// legacy, and is killed.
    fn ack(&self) -> bool {
        match self.state.compare_exchange(0, 1, Ordering::SeqCst, Ordering::SeqCst) {
            Ok(_) => {
                // A stale ack (the session ended while this stream was in
                // flight) still completes the handshake for this carrier, but
                // never counts toward the next session's protection.
                if self.generation == SESSION.load(Ordering::SeqCst) {
                    self.counted.store(true, Ordering::SeqCst);
                    LIVE_ACKED.fetch_add(1, Ordering::SeqCst);
                }
                self.notify.notify_waiters();
                true
            }
            Err(1) => true,
            Err(_) => {
                log::warn!("[ctl] control stream after the legacy decision; dropping carrier");
                self.dead.store(true, Ordering::SeqCst);
                false
            }
        }
    }
}

/// Serve one server-opened stream. Anything that does not start with 0xC0 is
/// not ours and is dropped, exactly as the driver dropped every inbound stream
/// before.
pub async fn serve<S: AsyncRead + AsyncWrite + Unpin>(mut s: S, gate: Option<std::sync::Arc<CapsGate>>) {
    let mut magic = [0u8; 1];
    if s.read_exact(&mut magic).await.is_err() || magic[0] != CONTROL_MAGIC {
        log::info!("[ctl] inbound stream without control magic; dropped");
        return;
    }
    if let Some(g) = &gate {
        if !g.ack() {
            return;
        }
    }
    // Tell the app the exit acknowledged the caps on this tunnel: the only
    // thing allowed to make the status line say "Protected".
    push_note(serde_json::json!({ "seq": 0, "ts": 0, "type": "caps", "payload": { "acked": true } }).to_string());
    let since = SINCE.load(Ordering::Relaxed);
    let bseq = BROADCAST_SEQ.load(Ordering::Relaxed);
    if write_frame(&mut s, serde_json::json!({ "since": since, "broadcast_seq": bseq }).to_string().as_bytes()).await.is_err() {
        return;
    }
    let (tx, mut rx) = mpsc::unbounded_channel::<Vec<u8>>();
    // The current rules once on every new control stream, whatever was sent
    // before, so a second device converges on connect.
    let _ = tx.send(rules_frame());
    if let Ok(mut g) = UP.lock() {
        g.push(tx);
    }
    let (mut r, mut w) = tokio::io::split(s);
    loop {
        tokio::select! {
            up = rx.recv() => {
                let Some(f) = up else { break };
                if write_frame(&mut w, &f).await.is_err() { break; }
            }
            hdr = async { let mut h = [0u8; 2]; r.read_exact(&mut h).await.map(|_| h) } => {
                let Ok(h) = hdr else { break };
                let n = u16::from_le_bytes(h) as usize;
                if n > MAX_FRAME { break; }
                let mut body = vec![0u8; n];
                if r.read_exact(&mut body).await.is_err() { break; }
                let Ok(v) = serde_json::from_slice::<serde_json::Value>(&body) else { continue };
                let seq = v.get("seq").and_then(|s| s.as_u64()).unwrap_or(0);
                // seq 0 = synthesised by the exit (entitlement, protection):
                // nothing to ack, and its ts is "now", which as {since} would
                // skip broadcasts this device never received.
                if seq != 0 && note_cursor(&v, seq) {
                    push_cursor();
                }
                push_note(v.to_string());
                if seq != 0 {
                    let ack = serde_json::json!({ "ack": seq }).to_string();
                    if write_frame(&mut w, ack.as_bytes()).await.is_err() { break; }
                }
            }
        }
    }
    log::info!("[ctl] control stream closed");
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn hello_is_unchanged_without_caps() {
        assert_eq!(mux_hello("Asia/Tehran", "abc", false, false), "MUX2|Asia/Tehran|||abc|");
    }

    #[test]
    fn hello_puts_caps_at_index_six_and_the_brand_in_app() {
        set_app("internetpro");
        let h = mux_hello("UTC", "cid1", true, true);
        let parts: Vec<&str> = h.splitn(7, '|').collect();
        assert_eq!(parts, ["MUX2", "UTC", "internetpro", "", "cid1", "", "blk1,msg1"]);
        assert!(mux_hello("UTC", "c", false, true).ends_with("||msg1"));
    }

    #[test]
    fn configure_reads_both_switches() {
        configure(r#"{"protection":{"enabled":true}}"#);
        assert_eq!(caps(), (true, true));
        configure(r#"{"messaging":{"enabled":true}}"#);
        assert_eq!(caps(), (true, true), "either flag announces both caps");
        configure(r#"{"protection":{"enabled":false}}"#);
        assert_eq!(caps(), (false, false));
    }

    #[test]
    fn rules_frame_carries_categories_off() {
        let rt = tokio::runtime::Builder::new_current_thread().build().unwrap();
        let _serial = rt.block_on(SERIAL.lock());
        CATEGORIES_OFF.store(0b101, Ordering::SeqCst);
        let f: serde_json::Value = serde_json::from_slice(&rules_frame()).unwrap();
        assert_eq!(f["rules"]["categories_off"], 5);
        assert!(f["rules"]["allow"].is_array() && f["rules"]["deny"].is_array());
    }

    #[test]
    fn flags_byte_marks_user_allowed_hosts() {
        let rt = tokio::runtime::Builder::new_current_thread().build().unwrap();
        let _serial = rt.block_on(SERIAL.lock());
        crate::blocklist::set_user_lists_json(r#"{"allow":["ok.example"],"deny":[],"categories_off":3}"#).unwrap();
        assert_eq!(stream_flags("ok.example"), 1);
        assert_eq!(stream_flags("sub.ok.example"), 1, "parent-label match, like every user rule");
        assert_eq!(stream_flags("other.example"), 0);
        assert_eq!(CATEGORIES_OFF.load(Ordering::SeqCst), 3, "categories_off rides setUserRules");
    }

    /// The control-stream tests share SINCE and the upstream list.
    static SERIAL: tokio::sync::Mutex<()> = tokio::sync::Mutex::const_new(());

    async fn next_frame(o: &mut tokio::io::DuplexStream) -> String {
        loop {
            let mut h = [0u8; 2];
            o.read_exact(&mut h).await.unwrap();
            let mut b = vec![0u8; u16::from_le_bytes(h) as usize];
            o.read_exact(&mut b).await.unwrap();
            let f = String::from_utf8(b).unwrap();
            // Other tests share the global upstream list and may push rules.
            if !f.contains("\"rules\"") {
                return f;
            }
        }
    }

    fn frame(m: &str) -> Vec<u8> {
        let mut f = (m.len() as u16).to_le_bytes().to_vec();
        f.extend_from_slice(m.as_bytes());
        f
    }

    #[tokio::test]
    async fn seq_zero_is_neither_acked_nor_counted_for_since() {
        let _serial = SERIAL.lock().await;
        SINCE.store(0, Ordering::Relaxed);
        let (mut ours, theirs) = tokio::io::duplex(8192);
        let srv = tokio::spawn(serve(theirs, None));
        ours.write_all(&[CONTROL_MAGIC]).await.unwrap();
        assert!(next_frame(&mut ours).await.contains("since"));
        // Synthesised entitlement (seq 0, ts "now"), then a broadcast at ts 200.
        ours.write_all(&frame(r#"{"seq":0,"ts":1999999999,"type":"entitlement","payload":{"tier":"pro","paid_until":"","state":"paid"}}"#)).await.unwrap();
        ours.write_all(&frame(r#"{"seq":2,"ts":200,"type":"notice","payload":{"title":"b"}}"#)).await.unwrap();
        // The only non-rules frame back is the ack for seq 2: none for seq 0.
        assert_eq!(next_frame(&mut ours).await, r#"{"ack":2}"#);
        assert_eq!(SINCE.load(Ordering::Relaxed), 200, "seq 0's ts must not become since");
        drop(ours);
        let _ = srv.await;
    }

    #[tokio::test]
    async fn eof_after_a_clear_verdict_is_a_dial_failure() {
        let mut after_clear: &[u8] = &[0x05, 0x00];
        let mut reply = [0u8; 10];
        let e = read_reply_after_verdict(&mut after_clear, &mut reply).await.unwrap_err();
        assert!(is_dial_failed(&e));
        let mut ok: &[u8] = &[0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0];
        read_reply_after_verdict(&mut ok, &mut reply).await.unwrap();
    }

    #[tokio::test]
    async fn caps_gate_acks_or_falls_back_to_legacy() {
        let dead = std::sync::Arc::new(AtomicBool::new(false));
        // No control stream: legacy after the wait.
        let g = CapsGate::new(dead.clone());
        assert!(!g.decide(std::time::Duration::from_millis(50)).await);
        // A control stream after that decision kills the carrier.
        assert!(!g.ack());
        assert!(dead.load(Ordering::SeqCst));
        // Control stream in time: acked.
        let dead = std::sync::Arc::new(AtomicBool::new(false));
        let g = CapsGate::new(dead.clone());
        let g2 = g.clone();
        tokio::spawn(async move {
            tokio::time::sleep(std::time::Duration::from_millis(20)).await;
            g2.ack();
        });
        assert!(g.decide(std::time::Duration::from_secs(2)).await);
        assert!(!dead.load(Ordering::SeqCst));
        g.retire();
    }

    #[test]
    fn cursor_counts_broadcasts_not_per_cid_and_ignores_the_far_future() {
        let rt = tokio::runtime::Builder::new_current_thread().build().unwrap();
        let _serial = rt.block_on(SERIAL.lock());
        SINCE.store(0, Ordering::Relaxed);
        BROADCAST_SEQ.store(0, Ordering::Relaxed);
        note_cursor(&serde_json::json!({"seq":4,"ts":1791836705266u64,"scope":"broadcast"}), 4);
        note_cursor(&serde_json::json!({"seq":9,"ts":1791836799999u64,"scope":"cid"}), 9);
        assert_eq!(SINCE.load(Ordering::Relaxed), 1791836705266, "a per-cid ts must not advance since");
        assert_eq!(BROADCAST_SEQ.load(Ordering::Relaxed), 4);
        note_cursor(&serde_json::json!({"seq":5,"ts":9_999_999_999_999u64,"scope":"broadcast"}), 5);
        assert_eq!(SINCE.load(Ordering::Relaxed), 1791836705266, "a far-future ts is ignored");
        restore_cursor(&serde_json::json!({"ctl_cursor":{"since":1791836800000u64,"broadcast_seq":7}}));
        assert_eq!((SINCE.load(Ordering::Relaxed), BROADCAST_SEQ.load(Ordering::Relaxed)), (1791836800000, 7));
    }

    #[tokio::test]
    async fn caps_gate_wakes_promptly_when_acked_during_decide() {
        let g = CapsGate::new(std::sync::Arc::new(AtomicBool::new(false)));
        let g2 = g.clone();
        tokio::spawn(async move { tokio::task::yield_now().await; g2.ack(); });
        let t = std::time::Instant::now();
        assert!(g.decide(CAPS_WAIT).await);
        assert!(t.elapsed() < std::time::Duration::from_millis(500), "took {:?}", t.elapsed());
        g.retire();
    }

    #[tokio::test]
    async fn acked_carrier_dies_then_the_pool_may_redial() {
        let _serial = SERIAL.lock().await;
        reset_session();
        let dead = std::sync::Arc::new(AtomicBool::new(false));
        let g = CapsGate::new(dead);
        assert!(g.ack());
        assert!(protected_session(), "a live acked carrier forbids the legacy fallback");
        let _ = drain_notes();
        // The exit process dies: the driver ends and retires the carrier.
        g.retire();
        assert!(!protected_session(), "a dead acked carrier must not pin the pool");
        assert!(drain_notes().contains("\"acked\":false"), "the app is told at once");
        // So a mux error now takes the normal redial / legacy path.
        let e = io::Error::new(io::ErrorKind::ConnectionRefused, "exit down");
        assert!(!crate::ctl::is_dial_failed(&e));
        g.retire(); // idempotent
        assert!(!protected_session());
    }

    #[test]
    fn one_categories_off_edit_is_one_rules_frame() {
        let rt = tokio::runtime::Builder::new_current_thread().build().unwrap();
        let _serial = rt.block_on(SERIAL.lock());
        let (tx, mut rx) = mpsc::unbounded_channel::<Vec<u8>>();
        if let Ok(mut g) = UP.lock() { g.clear(); g.push(tx); }
        if let Ok(mut l) = LAST_RULES.lock() { *l = None; }
        crate::blocklist::set_user_lists_json(r#"{"allow":["a.example"],"deny":[],"categories_off":32}"#).unwrap();
        let mut frames = Vec::new();
        while let Ok(f) = rx.try_recv() { frames.push(String::from_utf8(f).unwrap()); }
        assert_eq!(frames.len(), 1, "{frames:?}");
        assert!(frames[0].contains("\"categories_off\":32") && frames[0].contains("a.example"), "{}", frames[0]);
        if let Ok(mut g) = UP.lock() { g.clear(); }
    }

    #[tokio::test]
    async fn a_stale_ack_after_stop_does_not_protect_the_next_session() {
        let _serial = SERIAL.lock().await;
        reset_session();
        let old = CapsGate::new(std::sync::Arc::new(AtomicBool::new(false)));
        reset_session(); // the tunnel stopped while old's 0xC0 was in flight
        assert!(old.ack(), "the handshake itself still completes");
        assert!(!protected_session(), "a stale ack must not count");
        old.retire();
        assert!(!protected_session());
        // A carrier of the current session still counts.
        let cur = CapsGate::new(std::sync::Arc::new(AtomicBool::new(false)));
        assert!(cur.ack());
        assert!(protected_session());
        cur.retire();
        assert!(!protected_session());
        let _ = drain_notes();
    }

    #[test]
    fn a_far_future_seq_does_not_pin_the_cursor() {
        let rt = tokio::runtime::Builder::new_current_thread().build().unwrap();
        let _serial = rt.block_on(SERIAL.lock());
        BROADCAST_SEQ.store(0, Ordering::Relaxed);
        SINCE.store(0, Ordering::Relaxed);
        note_cursor(&serde_json::json!({"seq":9_999_999_999_999u64,"ts":1791836705266u64,"scope":"broadcast"}), 9_999_999_999_999);
        assert_eq!(BROADCAST_SEQ.load(Ordering::Relaxed), 0, "a seq a century ahead is ignored");
        note_cursor(&serde_json::json!({"seq":1791836705266u64,"ts":1791836705266u64,"scope":"broadcast"}), 1791836705266);
        assert_eq!(BROADCAST_SEQ.load(Ordering::Relaxed), 1791836705266);
        restore_cursor(&serde_json::json!({"ctl_cursor":{"since":9_999_999_999_999u64,"broadcast_seq":9_999_999_999_999u64}}));
        assert_eq!(BROADCAST_SEQ.load(Ordering::Relaxed), 1791836705266, "restore ignores it too");
        assert_eq!(SINCE.load(Ordering::Relaxed), 1791836705266);
    }

    #[tokio::test]
    async fn verdicts_parse() {
        let mut clear: &[u8] = &[0x00, 0x05];
        assert_eq!(read_verdict(&mut clear).await.unwrap(), Verdict::Clear);
        assert_eq!(clear, &[0x05], "a clear verdict consumes exactly one byte");
        let mut blk: &[u8] = &[0x02, 0x03, 0x00, 7, b'm', b'a', b'l', b'w', b'a', b'r', b'e'];
        assert_eq!(
            read_verdict(&mut blk).await.unwrap(),
            Verdict::Blocked { bits: 3, reason: "malware".into() }
        );
        let mut bad: &[u8] = &[0x09];
        assert!(read_verdict(&mut bad).await.is_err());
    }

    #[tokio::test]
    async fn control_stream_sends_since_and_acks() {
        let _serial = SERIAL.lock().await;
        let (mut ours, theirs) = tokio::io::duplex(4096);
        let srv = tokio::spawn(serve(theirs, None));
        ours.write_all(&[CONTROL_MAGIC]).await.unwrap();
        let mut h = [0u8; 2];
        ours.read_exact(&mut h).await.unwrap();
        let mut b = vec![0u8; u16::from_le_bytes(h) as usize];
        ours.read_exact(&mut b).await.unwrap();
        assert!(String::from_utf8_lossy(&b).contains("\"since\""));
        let msg = br#"{"seq":7,"ts":1760000000,"type":"notice","payload":{"title":"t","body":"b"}}"#;
        ours.write_all(&(msg.len() as u16).to_le_bytes()).await.unwrap();
        ours.write_all(msg).await.unwrap();
        // Frames from us now: possibly {rules}, then {ack:7}.
        let mut saw_ack = false;
        for _ in 0..3 {
            ours.read_exact(&mut h).await.unwrap();
            let mut b = vec![0u8; u16::from_le_bytes(h) as usize];
            ours.read_exact(&mut b).await.unwrap();
            if String::from_utf8_lossy(&b).contains("\"ack\":7") { saw_ack = true; break; }
        }
        assert!(saw_ack);
        assert!(drain_notes().contains("\"seq\":7"));
        drop(ours);
        let _ = srv.await;
    }
}
