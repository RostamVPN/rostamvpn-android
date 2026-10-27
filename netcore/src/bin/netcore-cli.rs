//! A SOCKS5 proxy over the tunnel, for a desktop.
//!
//! Same code path the apps use: `rs_load_config` then `rs_start`, so what this
//! proves is what a phone would do. Useful for checking a POP by hand and for
//! pointing a browser at a node without building an app.
//!
//!   netcore-cli <config.json> [socks_port]
//!
//! Prints `ok:<carrier>:<host>:<port>` on success -- the same string the
//! Android bridge logs -- then serves SOCKS5 until interrupted.

use std::ffi::CString;

fn main() {
    let mut args = std::env::args().skip(1);
    let Some(cfg_path) = args.next() else {
        eprintln!("usage: netcore-cli <config.json> [socks_port]");
        std::process::exit(2);
    };
    let port: u16 = args.next().unwrap_or_else(|| "1080".into()).parse()
        .unwrap_or_else(|_| { eprintln!("port must be 1-65535"); std::process::exit(2) });

    env_logger::Builder::from_env(
        env_logger::Env::default().default_filter_or("info")).init();

    let json = std::fs::read_to_string(&cfg_path).unwrap_or_else(|e| {
        eprintln!("cannot read {cfg_path}: {e}"); std::process::exit(1)
    });

    // Config must be installed before start: the endpoint list is read once
    // when selection begins.
    let c = CString::new(json).expect("config has an interior NUL");
    netcore::ffi::rs_load_config(c.as_ptr());

    let res = netcore::ffi::rs_start(port);
    let s = unsafe { std::ffi::CStr::from_ptr(res) }.to_string_lossy().into_owned();
    netcore::ffi::rs_free_string(res);
    println!("{s}");
    if s.starts_with("err:") {
        std::process::exit(1);
    }
    println!("SOCKS5 on 127.0.0.1:{port} — ctrl-c to stop");
    println!("  curl -x socks5h://127.0.0.1:{port} https://ip-geo.example.com/");

    // rs_start returns once the tunnel is up; the relay runs on its own
    // runtime, so park the main thread.
    loop {
        std::thread::sleep(std::time::Duration::from_secs(3600));
    }
}
