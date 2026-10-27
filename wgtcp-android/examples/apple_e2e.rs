//! End-to-end check of the path the iOS app takes.
//!
//! Exercises the SAME C ABI the Network Extension calls -- not the Rust API --
//! against the LIVE provisioning service, then starts the tunnel and pushes a
//! real request through the SOCKS5 port. Run on macOS, where ffi.rs is compiled
//! in just as it is for iOS:
//!
//!     cargo run --release --example apple_e2e
//!
//! It provisions one real peer. That is the point: a mocked profile would prove
//! nothing about whether the deployed API's response actually drives this core.

use std::ffi::{c_char, CStr, CString};
use std::process::Command;

use wgtcp_core::ffi::{
    wgtcp_free_string, wgtcp_generate_keypair, wgtcp_is_running, wgtcp_start, wgtcp_status,
    wgtcp_stop,
};

fn take(p: *mut c_char) -> String {
    if p.is_null() {
        return String::new();
    }
    unsafe {
        let s = CStr::from_ptr(p).to_string_lossy().into_owned();
        wgtcp_free_string(p);
        s
    }
}

const API: &str = "https://cashier.example.com/api/v1/wgtcp/profile";
const API_KEY: &str = "REPLACE_WITH_WGTCP_API_KEY";
const SOCKS_PORT: u16 = 10808;

fn main() {
    // 1. Keypair, through the same entry point WgtcpIdentity.ensure() uses.
    let kp = take(wgtcp_generate_keypair());
    let (sk, pk) = kp.split_once(':').expect("keypair shape");
    assert!(!sk.is_empty() && !pk.is_empty(), "empty key half");
    println!("1. keypair ok, pub={}...", &pk[..12]);

    // 2. Provision against the live service.
    let body = format!(r#"{{"pubkey":"{pk}","cid":"apple-e2e-{}"}}"#, &pk[..8]);
    let out = Command::new("curl")
        .args(["-s", "--max-time", "20", "-X", "POST", API,
               "-H", "Content-Type: application/json",
               "-H", &format!("X-Api-Key: {API_KEY}"),
               "-d", &body])
        .output()
        .expect("curl");
    let profile = String::from_utf8_lossy(&out.stdout).into_owned();
    let p: serde_json::Value = serde_json::from_str(&profile)
        .unwrap_or_else(|e| panic!("profile is not JSON: {e}\n{profile}"));
    let endpoints = p["endpoints"].as_array().expect("endpoints");
    assert!(!endpoints.is_empty(), "no endpoints in profile");
    println!("2. provisioned: status={} address={} endpoints={}",
             p["status"], p["address"], endpoints.len());

    // 3. Build the config exactly as WgtcpProvisioner.build() does in Swift.
    let cfg = serde_json::json!({
        "private_key": sk,
        "peer_public_key": p["server_public_key"],
        "endpoint": endpoints[0],
        "address": p["address"],
        "gateway": p["gateway"],
        "prefix_len": p["prefix_len"],
        "mtu": p["mtu"],
        "persistent_keepalive": p["persistent_keepalive"],
    })
    .to_string();
    println!("3. config built ({} bytes)", cfg.len());

    // 4. Start, through the C ABI.
    let c = CString::new(cfg).unwrap();
    let res = take(unsafe { wgtcp_start(c.as_ptr(), SOCKS_PORT) });
    println!("4. wgtcp_start -> {res}");
    assert_eq!(res, "ok", "start failed");
    assert_eq!(wgtcp_is_running(), 1, "not running after ok");
    println!("   status={}", take(wgtcp_status()));

    // 5. Real traffic through the SOCKS5 port. ip-geo.example.com is the
    //    only approved echo endpoint (CLAUDE.md rule 0).
    std::thread::sleep(std::time::Duration::from_secs(3));
    let curl = Command::new("curl")
        .args(["-s", "--max-time", "25",
               "--socks5-hostname", &format!("127.0.0.1:{SOCKS_PORT}"),
               "https://ip-geo.example.com/"])
        .output()
        .expect("curl");
    let body = String::from_utf8_lossy(&curl.stdout).trim().to_string();
    println!("5. through the tunnel: {:?} (exit {})", body, curl.status);

    wgtcp_stop();
    assert_eq!(wgtcp_is_running(), 0, "still running after stop");
    println!("6. stopped cleanly");

    assert!(!body.is_empty(), "NO TRAFFIC through the tunnel");
    println!("\nPASS: provisioning -> C ABI -> tunnel -> traffic");
}
