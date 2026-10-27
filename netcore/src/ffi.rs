//! C ABI for Apple platforms.
//!
//! The Android build reaches this crate through JNI. A Network Extension on iOS
//! cannot: it links the transport statically and calls it as plain C. This module
//! is the second doorway onto the SAME internals -- `start_http`, `stop_http`,
//! `CONFIG_JSON` and friends -- so the two platforms cannot drift into different
//! behaviour. Nothing here implements transport logic; if a function does more
//! than marshal strings, it belongs on the other side of this boundary.
//!
//! MEMORY. Every `*const c_char` returned is a `CString` this module leaked, and
//! the caller MUST hand it back to `rs_free_string`. Swift will not free it: the
//! allocation came from Rust's allocator and freeing it with `free(3)` is
//! undefined behaviour, not a leak. The Swift wrapper does this in one place so
//! call sites cannot forget.
//!
//! PANICS. Every entry point catches unwind. A panic crossing an FFI boundary is
//! undefined behaviour, and in a Network Extension it takes down the tunnel
//! process -- which the user sees as the VPN dropping for no reason.

use std::ffi::{c_char, CStr, CString};
use std::sync::atomic::Ordering;

use crate::{blocklist, client_id, config, select, selected_region, set_client_id,
            start_http, stop_http, throttle,
            ACTIVE_LABEL, CONFIG_JSON, PREMIUM, RACE_STATUS, RUNNING, SELECTED_REGION};

/// Hand a Rust-owned C string to the caller. Freed by `rs_free_string` ONLY.
fn out(s: String) -> *mut c_char {
    match CString::new(s) {
        Ok(c) => c.into_raw(),
        // A NUL byte in the middle cannot be represented; an empty string is a
        // safer answer than a null pointer the caller may not check.
        Err(_) => CString::new("").unwrap().into_raw(),
    }
}

/// Borrow a caller-owned C string. Returns None for null or non-UTF8.
unsafe fn borrow<'a>(p: *const c_char) -> Option<&'a str> {
    if p.is_null() {
        return None;
    }
    CStr::from_ptr(p).to_str().ok()
}

fn guarded<F: FnOnce() -> String>(what: &str, f: F) -> *mut c_char {
    match std::panic::catch_unwind(std::panic::AssertUnwindSafe(f)) {
        Ok(s) => out(s),
        Err(p) => {
            let m = p
                .downcast_ref::<&str>()
                .map(|s| s.to_string())
                .or_else(|| p.downcast_ref::<String>().cloned())
                .unwrap_or_else(|| "unknown panic".into());
            log::error!("[nc] PANIC in {what}: {m}");
            out(format!("err:panic:{m}"))
        }
    }
}

/// Start the tunnel and serve SOCKS5 on `socks_port`.
///
/// Returns `ok:<entry>` or `err:<reason>` -- the same strings the Android bridge
/// returns, so a log line means the same thing on both platforms.
#[no_mangle]
pub extern "C" fn rs_start(socks_port: u16) -> *mut c_char {
    guarded("rs_start", || match start_http(socks_port) {
        Ok(entry) => format!("ok:{entry}"),
        Err(e) => format!("err:{e}"),
    })
}

#[no_mangle]
pub extern "C" fn rs_stop() {
    let _ = std::panic::catch_unwind(stop_http);
}

#[no_mangle]
pub extern "C" fn rs_is_running() -> bool {
    RUNNING.load(Ordering::SeqCst)
}

/// The SOCKS port actually bound, or 0 when not running. The extension must use
/// this for its tun bridge instead of the constant it passed to `rs_start`: the
/// two differ whenever the preferred port was already taken by another app.
#[no_mangle]
pub extern "C" fn rs_socks_port() -> u16 {
    crate::socks_port()
}

/// Install the config JSON. Must be called BEFORE `rs_start`: the endpoint list
/// is read once when selection begins, so a later call has no effect on the
/// connection already in flight.
#[no_mangle]
pub extern "C" fn rs_load_config(json: *const c_char) {
    let Some(s) = (unsafe { borrow(json) }) else { return };
    log::info!("[nc] loadConfig: {} bytes", s.len());
    crate::ctl::configure(s);
    if let Ok(mut g) = CONFIG_JSON.lock() {
        *g = Some(s.to_string());
    }
}

/// The brand id: on a capped tunnel, the MUX2 `app`
/// field. Same as Android's `setAppTag`. Null clears it.
///
/// # Safety
/// `app` must be null or a NUL-terminated C string valid for this call.
#[no_mangle]
pub unsafe extern "C" fn rs_set_app(app: *const c_char) {
    let a = borrow(app).unwrap_or("");
    crate::ctl::set_app(a);
}

/// Device timezone (IANA id, e.g. "Asia/Tehran"). Config selection reads it for
/// the CDN-first and v6-first region tables, and MUX2 sends it in its header.
/// Same as Android's `setTimezone`. Null or "" clears it.
///
/// # Safety
/// `tz` must be null or a NUL-terminated C string valid for this call.
#[no_mangle]
pub unsafe extern "C" fn rs_set_timezone(tz: *const c_char) {
    crate::set_timezone(borrow(tz).unwrap_or(""));
}

/// Queued control-stream messages as a JSON array, oldest first. Drains.
/// Free with `rs_free_string`.
#[no_mangle]
pub extern "C" fn rs_drain_notes() -> *mut c_char {
    guarded("rs_drain_notes", crate::ctl::drain_notes)
}

/// Connection status as JSON. Same shape as the Android bridge returns.
#[no_mangle]
pub extern "C" fn rs_connection_status() -> *mut c_char {
    guarded("rs_connection_status", || {
        let running = RUNNING.load(Ordering::SeqCst);
        let carrier = crate::ACTIVE_LABEL
            .lock()
            .ok()
            .and_then(|g| g.clone())
            .unwrap_or_default();
        serde_json::json!({ "running": running, "carrier": carrier }).to_string()
    })
}

/// Open a sealed config. Returns the JSON, or `err:<reason>` if it does not
/// verify. The signature check is the whole point -- see `cfgseal`.
#[no_mangle]
pub extern "C" fn rs_unseal_config(
    sealed_b64: *const c_char,
    cert_sha256_hex: *const c_char,
) -> *mut c_char {
    guarded("rs_unseal_config", || {
        let (Some(b64), Some(cert)) =
            (unsafe { borrow(sealed_b64) }, unsafe { borrow(cert_sha256_hex) })
        else {
            return "err:null-argument".into();
        };
        // unseal() takes BYTES: base64 for the payload, raw bytes for the cert
        // hash. Decoding here keeps the Swift side passing the same strings the
        // Kotlin side does, so both platforms hand the library identical input.
        use base64::Engine;
        let Ok(raw) = base64::engine::general_purpose::STANDARD.decode(b64.trim()) else {
            return "err:bad-base64".into();
        };
        let cert_bytes: Vec<u8> = (0..cert.len())
            .step_by(2)
            .filter_map(|i| cert.get(i..i + 2).and_then(|h| u8::from_str_radix(h, 16).ok()))
            .collect();
        match crate::unseal_config_for_test(&raw, &cert_bytes) {
            Ok(json) => json,
            Err(e) => format!("err:{e}"),
        }
    })
}

/// Free a string returned by any function in this module.
///
/// Passing a pointer this module did not return, or freeing twice, is undefined
/// behaviour. Null is accepted and ignored so callers need no special case.
#[no_mangle]
pub extern "C" fn rs_free_string(p: *mut c_char) {
    if p.is_null() {
        return;
    }
    unsafe { drop(CString::from_raw(p)) };
}

/// Pick a server region, or clear it with an empty string / null.
///
/// Region choice is a PAID feature and the gate lives in the app, but it must
/// also be applied HERE rather than only hidden in the UI: the selection is
/// persisted, so a user who chose a region before losing entitlement would keep
/// it forever and the lock would be decoration. The app clears this on connect
/// when the user is not entitled.
///
/// The Android side reaches the same static through
/// `NetCoreBridge.setRegion`; this is the second doorway, not a second
/// implementation.
#[no_mangle]
pub unsafe extern "C" fn rs_set_region(region: *const c_char) {
    let r = borrow(region).unwrap_or("").to_string();
    log::info!("[nc] region set to '{r}'");
    if let Ok(mut g) = SELECTED_REGION.lock() {
        *g = if r.is_empty() { None } else { Some(r) };
    }
}

/// Tell the core whether this install is premium.
///
/// Mirrors the Android bridge's setPremium. Endpoint tiers differ by entitlement
/// (the premium pool, and CDN access), so the core cannot infer it.
#[no_mangle]
pub extern "C" fn rs_set_premium(premium: i32) {
    PREMIUM.store(premium != 0, Ordering::SeqCst);
    log::info!("[nc] tier set to {}", if premium != 0 { "premium" } else { "free" });
}

/// Free-tier speed limit in kbps; 0 = unlimited.
///
/// The APP decides WHO is limited — paid users and freedom regions never are —
/// and the core only enforces it, on every byte forwarded through SOCKS5.
///
/// This existed on the JNI side from the start and was simply never exposed
/// here, so iOS free users have had no rate limit at all: the enforcement was
/// compiled in and sitting idle because nothing could switch it on.
#[no_mangle]
pub extern "C" fn rs_set_rate_limit_kbps(kbps: i32) {
    throttle::set_kbps(kbps);
    log::info!("[nc] rate limit set to {} kbps", kbps);
}

// ── Parity with the JNI surface ─────────────────────────────────────────────
//
// These existed on Android from the start. They were missing here because the
// C ABI was grown function by function as the connect path needed one, rather
// than mirrored from the JNI surface in full — so a feature was only noticed
// missing when somebody tripped over it. The set is now diffed symbol by symbol.

/// Inject the app-owned client id. MUST be called before start.
///
/// The cid is the identity for entitlement, and it travels inside the encrypted
/// META frame. Without this the core has no id from the app at all, so the id
/// the cashier knows and the id the server sees are different values, and
/// entitlement cannot match a session.
#[no_mangle]
pub unsafe extern "C" fn rs_set_client_id(cid: *const c_char) {
    if let Some(s) = borrow(cid) {
        if !s.is_empty() {
            set_client_id(s);
            log::info!("[nc] client id injected ({} chars)", s.len());
        }
    }
}

/// Allow or deny the CDN fallback tier. Denied for free users by policy.
#[no_mangle]
pub extern "C" fn rs_set_cdn_allowed(allowed: i32) {
    let on = allowed != 0;
    select::CDN_ALLOWED.store(on, Ordering::SeqCst);
    log::info!("[nc] cdn fallback {}", if on { "allowed" } else { "denied (free tier)" });
}

/// Restrict selection to one protocol ("auto"/"http" clear the filter).
#[no_mangle]
pub unsafe extern "C" fn rs_set_protocol_filter(proto: *const c_char) {
    let p = borrow(proto).unwrap_or("");
    if !p.is_empty() && p != "auto" && p != "http" {
        log::warn!("[nc] protocol filter '{p}' unknown to this core; ignoring");
    }
}

/// Label of the endpoint currently carrying traffic, or "".
#[no_mangle]
pub extern "C" fn rs_get_active_carrier() -> *mut c_char {
    guarded("rs_get_active_carrier", || {
        ACTIVE_LABEL.lock().ok().and_then(|g| g.clone()).unwrap_or_default()
    })
}

/// What selection is doing right now ("trying:x", "racing:x|y"), or "".
#[no_mangle]
pub extern "C" fn rs_get_race_status() -> *mut c_char {
    guarded("rs_get_race_status", || {
        RACE_STATUS.lock().ok().and_then(|g| g.clone()).unwrap_or_default()
    })
}

/// Carriers this core can actually offer, as JSON. Drives the protocol picker.
///
/// Derived from the ACTIVE CONFIG, so it can never advertise a carrier the
/// config does not populate — an empty list means "nothing usable", which the
/// UI should show as unavailable rather than as a choice that will fail.
#[no_mangle]
pub extern "C" fn rs_carriers() -> *mut c_char {
    guarded("rs_carriers", || {
        let region = selected_region();
        let configured = CONFIG_JSON
            .lock()
            .ok()
            .and_then(|g| g.clone())
            .and_then(|j| config::load(&j, region.as_deref(), &client_id(),
                                       PREMIUM.load(Ordering::SeqCst),
                                       &crate::get_timezone()).ok())
            .is_some_and(|c| !c.endpoints.is_empty());
        if configured {
            serde_json::json!([{
                "id": "http", "name": "HTTP",
                "region": region.unwrap_or_default(),
                "transport": "http", "desc": "Web (port 80)",
            }]).to_string()
        } else {
            log::warn!("[nc] rs_carriers: no usable config — empty carrier list");
            "[]".to_string()
        }
    })
}

/// Endpoint labels the current config would actually try, as JSON.
///
/// Diagnostics only. Reports what selection WOULD do without connecting, which
/// is the difference between "the config is wrong" and "the network is blocked"
/// — two failures that look identical from the outside.
#[no_mangle]
pub extern "C" fn rs_run_diagnostics() -> *mut c_char {
    guarded("rs_run_diagnostics", || {
        let region = selected_region();
        let endpoints = CONFIG_JSON
            .lock()
            .ok()
            .and_then(|g| g.clone())
            .and_then(|j| config::load(&j, region.as_deref(), &client_id(),
                                       PREMIUM.load(Ordering::SeqCst),
                                       &crate::get_timezone()).ok())
            .map(|c| c.endpoints.iter().map(|e| e.label()).collect::<Vec<_>>())
            .unwrap_or_default();
        let d = serde_json::json!({
            "running": RUNNING.load(Ordering::SeqCst),
            "region": region.unwrap_or_default(),
            "premium": PREMIUM.load(Ordering::SeqCst),
            "endpoints": endpoints,
        });
        d.to_string()
    })
}

// ── On-device filtering. Mirrors the JNI surface symbol for symbol. ─────────
//
// iOS links the SAME core, so a JNI-only feature does not exist on iOS at all —
// which is exactly how the rate limiter sat compiled-in and unreachable for a
// year. Every function here marshals strings onto `crate::blocklist`; none of
// them implements policy.
//
// NOTE for the Swift side: `rs_get_user_rules` is the persistence hook. A
// Network Extension has no SharedPreferences, so the core cannot store the
// user's rules itself there — the app must read them back after any change and
// restore them with `rs_set_user_rules` at launch, BEFORE `rs_start`. Skip that
// and a user's false-positive fixes evaporate on every app restart.

/// Master gate: premium **or** freedom region. Defaults to off.
#[no_mangle]
pub extern "C" fn rs_set_blocking_enabled(enabled: i32) {
    let _ = std::panic::catch_unwind(|| blocklist::set_enabled(enabled != 0));
}

/// Install a blocklist document directly (the app fetched it, e.g. over TLS).
/// Returns `ok:<domains>:<networks>:<skipped>` or `err:<reason>`.
///
/// # Safety
/// `json` must be null or a NUL-terminated C string valid for this call.
/// The returned string must be freed with `rs_free_string`.
#[no_mangle]
pub unsafe extern "C" fn rs_set_blocklist(json: *const c_char) -> *mut c_char {
    let j = borrow(json).unwrap_or("").to_string();
    guarded("rs_set_blocklist", move || match blocklist::install_json(&j, None) {
        Ok(m) => m,
        Err(e) => format!("err:{e}"),
    })
}

/// Counts and the recent blocks with their category, as JSON.
#[no_mangle]
pub extern "C" fn rs_get_block_stats() -> *mut c_char {
    guarded("rs_get_block_stats", blocklist::stats_json)
}

/// Add or clear one user rule. `verdict` is `allow`, `deny` or `clear`.
/// Effective on the next connection: no reconnect, no refetch.
///
/// # Safety
/// `host` and `verdict` must each be null or a NUL-terminated C string valid
/// for this call. The returned string must be freed with `rs_free_string`.
#[no_mangle]
pub unsafe extern "C" fn rs_set_user_rule(
    host: *const c_char,
    verdict: *const c_char,
) -> *mut c_char {
    let h = borrow(host).unwrap_or("").to_string();
    let v = borrow(verdict).unwrap_or("").to_string();
    guarded("rs_set_user_rule", move || match blocklist::set_user_rule(&h, &v) {
        Ok(m) => m,
        Err(e) => format!("err:{e}"),
    })
}

/// Replace both user lists: `{"allow":[…],"deny":[…]}`.
///
/// # Safety
/// `json` must be null or a NUL-terminated C string valid for this call.
/// The returned string must be freed with `rs_free_string`.
#[no_mangle]
pub unsafe extern "C" fn rs_set_user_rules(json: *const c_char) -> *mut c_char {
    let j = borrow(json).unwrap_or("").to_string();
    guarded("rs_set_user_rules", move || match blocklist::set_user_lists_json(&j) {
        Ok(m) => m,
        Err(e) => format!("err:{e}"),
    })
}

/// The user's own rules as authored. Persist this; restore with
/// `rs_set_user_rules`.
#[no_mangle]
pub extern "C" fn rs_get_user_rules() -> *mut c_char {
    guarded("rs_get_user_rules", blocklist::user_lists_json)
}

/// The per-destination traffic table of this session:
/// `{"since_secs": N, "flows": [{"host","ports","connections","bytes_up",
/// "bytes_down","first_secs_ago","last_secs_ago","blocked","unusual"}]}`,
/// sorted by bytes up + down, descending. At most 400 destinations.
/// Free with `rs_free_string`.
#[no_mangle]
pub extern "C" fn rs_get_flows() -> *mut c_char {
    guarded("rs_get_flows", crate::flows::json)
}

/// Replace the AUTOMATIC deny rules (reputation scores rated `blocked`):
/// `{"rules":[{"host","label","ttl_secs"}]}`. Exact host match, apart from the
/// user's lists (their Allow overrides these), never persisted or sent
/// upstream, gone on `rs_stop`. Refusals land in the recent ring with category
/// `auto` and the label as the reason. Returns `ok:<n>` or `err:<reason>`.
///
/// # Safety
/// `json` must be null or a NUL-terminated C string valid for this call.
/// The returned string must be freed with `rs_free_string`.
#[no_mangle]
pub unsafe extern "C" fn rs_set_auto_rules(json: *const c_char) -> *mut c_char {
    let j = borrow(json).unwrap_or("").to_string();
    guarded("rs_set_auto_rules", move || match blocklist::set_auto_rules_json(&j) {
        Ok(m) => m,
        Err(e) => format!("err:{e}"),
    })
}

/// Install or replace block profile `id` (REDSHIELD-TRAFFIC Amendment C).
/// `kind` = `domains` (suffix list) or `ranges` (CIDR list, literal-IP
/// CONNECTs only); `body` = plain text, one entry per line (comments and
/// hosts-file lines accepted). Last in the precedence ladder; refusals record
/// category `profile` with the id as reason. Gone on `rs_stop`.
/// Returns `ok:<entries>:<skipped>`, `err:limit:<total>:<max>` (nothing
/// changed) or `err:<reason>`.
///
/// # Safety
/// `id`, `kind` and `body` must each be null or a NUL-terminated C string
/// valid for this call. The returned string must be freed with `rs_free_string`.
#[no_mangle]
pub unsafe extern "C" fn rs_set_profile(
    id: *const c_char,
    kind: *const c_char,
    body: *const c_char,
) -> *mut c_char {
    let i = borrow(id).unwrap_or("").to_string();
    let k = borrow(kind).unwrap_or("").to_string();
    let b = borrow(body).unwrap_or("").to_string();
    guarded("rs_set_profile", move || match blocklist::set_profile(&i, &k, &b) {
        Ok(m) => m,
        Err(e) => format!("err:{e}"),
    })
}

/// Remove block profile `id`.
///
/// # Safety
/// `id` must be null or a NUL-terminated C string valid for this call.
#[no_mangle]
pub unsafe extern "C" fn rs_clear_profile(id: *const c_char) {
    let i = borrow(id).unwrap_or("").to_string();
    let _ = std::panic::catch_unwind(move || blocklist::clear_profile(&i));
}

/// Install the regional address map (the `geo` bundle, decompressed): every
/// prefix with its country, so `country:XX` / `region:*` profiles are
/// decided here by code (`rs_set_profile(id, "cc", codes)`) with no list
/// body, and literal-IP rows of `rs_get_flows` carry `"cc"`. Validated whole
/// (magic, version, sizes, order); a bad body is refused and the previous
/// map stays. The bytes are copied: the caller frees its buffer. Not dropped
/// by `rs_stop`. `ptr` null or `len` 0 clears the map.
/// Returns 0 on success, -1 for a refused body, -2 for a panic.
///
/// # Safety
/// `ptr` must be null or valid for reads of `len` bytes for this call.
#[no_mangle]
pub unsafe extern "C" fn rs_set_geo(ptr: *const u8, len: usize) -> i32 {
    if ptr.is_null() || len == 0 {
        let _ = std::panic::catch_unwind(crate::geo::clear);
        return 0;
    }
    let bytes = std::slice::from_raw_parts(ptr, len);
    match std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| crate::geo::install(bytes))) {
        Ok(Ok(_)) => 0,
        Ok(Err(e)) => {
            log::warn!("[geo] refused: {e}");
            -1
        }
        Err(_) => -2,
    }
}

/// The installed map, for the UI's one line: `{"loaded":true,"built_day":N,
/// "date":"YYYY-MM-DD","v4":N,"v6":N,"ranges":N,"coarsened":bool,"bytes":N}`
/// or `{"loaded":false}`. Free with `rs_free_string`.
#[no_mangle]
pub extern "C" fn rs_geo_info() -> *mut c_char {
    guarded("rs_geo_info", crate::geo::info_json)
}

/// Forget the traffic table and restart its clock. Also done by `rs_stop`.
#[no_mangle]
pub extern "C" fn rs_clear_flows() {
    let _ = std::panic::catch_unwind(crate::flows::clear);
}

/// Forget which destinations were blocked. User rules are untouched.
#[no_mangle]
pub extern "C" fn rs_clear_block_history() {
    let _ = std::panic::catch_unwind(blocklist::clear_history);
}
