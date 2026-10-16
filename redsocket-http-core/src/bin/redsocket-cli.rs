// Copyright 2026 Internet Mastering & Company, Inc.
// SPDX-License-Identifier: Apache-2.0

//! A SOCKS5 proxy over the redsocket tunnel, for a desktop.

use std::ffi::CString;

fn main() {
    let mut args = std::env::args().skip(1);
    let Some(cfg_path) = args.next() else {
        eprintln!("usage: redsocket-cli <config.json> [socks_port]");
        std::process::exit(2);
    };
    let port: u16 = args.next().unwrap_or_else(|| "1080".into()).parse()
        .unwrap_or_else(|_| { eprintln!("port must be 1-65535"); std::process::exit(2) });

    env_logger::Builder::from_env(
        env_logger::Env::default().default_filter_or("info")).init();

    let json = std::fs::read_to_string(&cfg_path).unwrap_or_else(|e| {
        eprintln!("cannot read {cfg_path}: {e}"); std::process::exit(1)
    });

    let c = CString::new(json).expect("config has an interior NUL");
    unsafe { redsocket_core::ffi::rs_load_config(c.as_ptr()) };

    let res = redsocket_core::ffi::rs_start(port);
    let s = unsafe { std::ffi::CStr::from_ptr(res) }.to_string_lossy().into_owned();
    unsafe { redsocket_core::ffi::rs_free_string(res) };
    println!("{s}");
    if s.starts_with("err:") {
        std::process::exit(1);
    }
    println!("SOCKS5 on 127.0.0.1:{port} — ctrl-c to stop");
    println!("  curl -x socks5h://127.0.0.1:{port} https://example.com/");

    loop {
        std::thread::sleep(std::time::Duration::from_secs(3600));
    }
}
