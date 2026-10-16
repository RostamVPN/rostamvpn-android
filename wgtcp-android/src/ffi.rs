// Copyright 2026 Internet Mastering & Company, Inc.
// SPDX-License-Identifier: Apache-2.0

//! C ABI for Apple platforms.

use std::ffi::{c_char, CStr, CString};
use std::sync::atomic::Ordering;

use crate::{set_status, start_tunnel, status, stop_tunnel, RUNNING};

/// Hand a Rust-owned C string to the caller. Freed by `wgtcp_free_string` ONLY.
fn out(s: String) -> *mut c_char {
    match CString::new(s) {
        Ok(c) => c.into_raw(),

        Err(_) => CString::new("").unwrap().into_raw(),
    }
}

/// Borrow a caller-owned C string. None for null or non-UTF8.
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
            log::error!("[WGTCP] PANIC in {what}: {m}");
            out(format!("err:panic:{m}"))
        }
    }
}

/// Start the tunnel and serve SOCKS5 on `socks_port`.
///
/// # Safety
/// `config_json` must be null or point at a NUL-terminated string that stays
/// valid for the duration of the call.
#[no_mangle]
pub unsafe extern "C" fn wgtcp_start(config_json: *const c_char, socks_port: u16) -> *mut c_char {
    let cfg = match borrow(config_json) {
        Some(s) => s.to_string(),
        None => return out("err:config-string:null-or-invalid-utf8".into()),
    };
    guarded("wgtcp_start", move || {
        if RUNNING.load(Ordering::SeqCst) {
            return "err:already-running".to_string();
        }
        match start_tunnel(&cfg, socks_port) {
            Ok(()) => {
                RUNNING.store(true, Ordering::SeqCst);
                set_status("connected");
                "ok".to_string()
            }
            Err(e) => {
                set_status(format!("failed: {e}"));
                format!("err:{e}")
            }
        }
    })
}

#[no_mangle]
pub extern "C" fn wgtcp_stop() {
    let _ = std::panic::catch_unwind(stop_tunnel);
}

#[no_mangle]
pub extern "C" fn wgtcp_is_running() -> i32 {
    i32::from(RUNNING.load(Ordering::SeqCst))
}

#[no_mangle]
pub extern "C" fn wgtcp_status() -> *mut c_char {
    guarded("wgtcp_status", status)
}

/// Generate a WireGuard keypair. Returns `"<private_b64>:<public_b64>"`.
#[no_mangle]
pub extern "C" fn wgtcp_generate_keypair() -> *mut c_char {
    guarded("wgtcp_generate_keypair", || {
        let (sk, pk) = redsocket_wgtcp::generate_keypair();
        format!("{sk}:{pk}")
    })
}

/// Free a string returned by any function in this module. Null is a no-op.
///
/// # Safety
/// `p` must be null or a pointer previously returned by this module and not
/// yet freed.
#[no_mangle]
pub unsafe extern "C" fn wgtcp_free_string(p: *mut c_char) {
    if !p.is_null() {
        drop(CString::from_raw(p));
    }
}
