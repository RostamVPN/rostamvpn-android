// Copyright 2026 Internet Mastering & Company, Inc.
// SPDX-License-Identifier: Apache-2.0

//! C ABI for Apple platforms.

use std::ffi::{c_char, CStr, CString};
use std::sync::atomic::Ordering;

use crate::{blocklist, client_id, config, probe, reach, select, selected_region, set_client_id,
            start_http, stop_http, throttle,
            ACTIVE_LABEL, CONFIG_JSON, PREMIUM, RACE_STATUS, RUNNING, SELECTED_REGION};

/// Hand a Rust-owned C string to the caller. Freed by `rs_free_string` ONLY.
fn out(s: String) -> *mut c_char {
    match CString::new(s) {
        Ok(c) => c.into_raw(),

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
            log::error!("[RS] PANIC in {what}: {m}");
            out(format!("err:panic:{m}"))
        }
    }
}

/// Start the tunnel and serve SOCKS5 on `socks_port`.
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
/// this for its tun bridge instead of the constant it passed to `rs_start`.
#[no_mangle]
pub extern "C" fn rs_socks_port() -> u16 {
    crate::socks_port()
}

/// Install the config JSON. Must be called BEFORE `rs_start`: the endpoint list
/// is read once when selection begins, so a later call has no effect on the
/// connection already in flight.
///
/// # Safety
/// `json` must be null or point at a NUL-terminated string that stays
/// valid for the duration of the call.
#[no_mangle]
pub unsafe extern "C" fn rs_load_config(json: *const c_char) {
    let Some(s) = borrow(json) else { return };
    log::info!("[RS] loadConfig: {} bytes", s.len());
    crate::ctl::configure(s);
    if let Ok(mut g) = CONFIG_JSON.lock() {
        *g = Some(s.to_string());
    }
}

/// The brand id: the probe tag prefix and, on a capped tunnel, the MUX2 `app`
/// field. Same as Android's `setPeerApp`. Null clears it.
///
/// # Safety
/// `app` must be null or point at a NUL-terminated string that stays
/// valid for the duration of the call.
#[no_mangle]
pub unsafe extern "C" fn rs_set_app(app: *const c_char) {
    let a = borrow(app).unwrap_or("");
    probe::set_app(a);
    crate::ctl::set_app(a);
}

/// Queued control-stream messages as a JSON array, oldest first. Drains. Free
/// with `rs_free_string`.
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
///
/// # Safety
/// `sealed_b64` must be null or point at a NUL-terminated string that stays
/// valid for the duration of the call.
#[no_mangle]
pub unsafe extern "C" fn rs_unseal_config(
    sealed_b64: *const c_char,
    cert_sha256_hex: *const c_char,
) -> *mut c_char {
    guarded("rs_unseal_config", || {
        let (Some(b64), Some(cert)) =
            (borrow(sealed_b64), borrow(cert_sha256_hex))
        else {
            return "err:null-argument".into();
        };

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
/// # Safety
/// `p` must be null or a pointer returned by this module that has not been
/// freed yet.
#[no_mangle]
pub unsafe extern "C" fn rs_free_string(p: *mut c_char) {
    if p.is_null() {
        return;
    }
    drop(CString::from_raw(p));
}

/// Pick a server region, or clear it with an empty string / null.
///
/// # Safety
/// `region` must be null or point at a NUL-terminated string that stays
/// valid for the duration of the call.
#[no_mangle]
pub unsafe extern "C" fn rs_set_region(region: *const c_char) {
    let r = borrow(region).unwrap_or("").to_string();
    log::info!("[RS] region set to '{r}'");
    if let Ok(mut g) = SELECTED_REGION.lock() {
        *g = if r.is_empty() { None } else { Some(r) };
    }
}

/// Tell the core whether this install is premium.
#[no_mangle]
pub extern "C" fn rs_set_premium(premium: i32) {
    PREMIUM.store(premium != 0, Ordering::SeqCst);
    log::info!("[RS] tier set to {}", if premium != 0 { "premium" } else { "free" });
}

/// Free-tier speed limit in kbps; 0 = unlimited.
#[no_mangle]
pub extern "C" fn rs_set_rate_limit_kbps(kbps: i32) {
    throttle::set_kbps(kbps);
    log::info!("[RS] rate limit set to {} kbps", kbps);
}

/// Inject the app-owned client id. MUST be called before start.
///
/// # Safety
/// `cid` must be null or point at a NUL-terminated string that stays
/// valid for the duration of the call.
#[no_mangle]
pub unsafe extern "C" fn rs_set_client_id(cid: *const c_char) {
    if let Some(s) = borrow(cid) {
        if !s.is_empty() {
            set_client_id(s);
            log::info!("[RS] client id injected ({} chars)", s.len());
        }
    }
}

/// Allow or deny the CDN fallback tier. Denied for free users by policy.
#[no_mangle]
pub extern "C" fn rs_set_cdn_allowed(allowed: i32) {
    let on = allowed != 0;
    select::CDN_ALLOWED.store(on, Ordering::SeqCst);
    log::info!("[RS] cdn fallback {}", if on { "allowed" } else { "denied (free tier)" });
}

/// Restrict selection to one protocol ("auto"/"http" clear the filter).
///
/// # Safety
/// `proto` must be null or point at a NUL-terminated string that stays
/// valid for the duration of the call.
#[no_mangle]
pub unsafe extern "C" fn rs_set_protocol_filter(proto: *const c_char) {
    let p = borrow(proto).unwrap_or("");
    if !p.is_empty() && p != "auto" && p != "http" {
        log::warn!("[RS] protocol filter '{p}' unknown to this core; ignoring");
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

/// # Safety
/// `tag` must be null or point at a NUL-terminated string that stays
/// valid for the duration of the call.
#[no_mangle]
pub unsafe extern "C" fn rs_set_peer_tag(tag: *const c_char) {
    probe::set_probe_id(borrow(tag).unwrap_or(""));
}

/// # Safety
/// `host` must be null or point at a NUL-terminated string that stays
/// valid for the duration of the call.
#[no_mangle]
pub unsafe extern "C" fn rs_set_peer_route(host: *const c_char, port: u16) {
    if let Some(h) = borrow(host) {
        probe::set_gateway(h, port);
    }
}

/// # Safety
/// `tz` must be null or point at a NUL-terminated string that stays
/// valid for the duration of the call.
#[no_mangle]
pub unsafe extern "C" fn rs_set_peer_zone(tz: *const c_char) {
    probe::set_timezone(borrow(tz).unwrap_or(""));
}

#[no_mangle]
pub extern "C" fn rs_enable_peer() {
    probe::enable();
}

#[no_mangle]
pub extern "C" fn rs_disable_peer() {
    probe::disable();
}

/// `socks_port` is where the relay reaches the running tunnel, so its traffic
/// goes out through the VPN like everything else rather than the clear network.
#[no_mangle]
pub extern "C" fn rs_start_peer(socks_port: u16) {
    probe::start(socks_port);
}

/// Carriers this core can actually offer, as JSON. Drives the protocol picker.
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
                                       &probe::get_timezone()).ok())
            .is_some_and(|c| !c.endpoints.is_empty());
        if configured {
            serde_json::json!([{
                "id": "http", "name": "HTTP",
                "region": region.unwrap_or_default(),
                "transport": "http", "desc": "Web (port 80)",
            }]).to_string()
        } else {
            log::warn!("[RS] rs_carriers: no usable config — empty carrier list");
            "[]".to_string()
        }
    })
}

/// Endpoint labels the current config would actually try, as JSON.
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
                                       &probe::get_timezone()).ok())
            .map(|c| c.endpoints.iter().map(|e| e.label()).collect::<Vec<_>>())
            .unwrap_or_default();
        serde_json::json!({
            "running": RUNNING.load(Ordering::SeqCst),
            "region": region.unwrap_or_default(),
            "premium": PREMIUM.load(Ordering::SeqCst),
            "endpoints": endpoints,

            "lm": reach::stats(),
        }).to_string()
    })
}

/// Master gate: premium **or** freedom region. Defaults to off.
#[no_mangle]
pub extern "C" fn rs_set_blocking_enabled(enabled: i32) {
    let _ = std::panic::catch_unwind(|| blocklist::set_enabled(enabled != 0));
}

/// Install a blocklist document directly (the app fetched it, e.g. over TLS).
/// Returns `ok:<domains>:<networks>:<skipped>` or `err:<reason>`.
///
/// # Safety
/// `json` must be null or point at a NUL-terminated string that stays
/// valid for the duration of the call.
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
/// `host` must be null or point at a NUL-terminated string that stays
/// valid for the duration of the call.
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
/// `json` must be null or point at a NUL-terminated string that stays
/// valid for the duration of the call.
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

/// Forget which destinations were blocked. User rules are untouched.
#[no_mangle]
pub extern "C" fn rs_clear_block_history() {
    let _ = std::panic::catch_unwind(blocklist::clear_history);
}
