// Copyright 2026 Internet Mastering & Company, Inc.
// SPDX-License-Identifier: Apache-2.0

//! Open a sealed OTA config and print the JSON.
use base64::Engine;

fn main() -> std::io::Result<()> {
    let mut args = std::env::args().skip(1);
    let path = args.next().unwrap_or_else(|| {
        eprintln!("usage: unseal-config <sealed-file> [cert-sha256-hex]");
        std::process::exit(2);
    });
    let cert_hex = args.next().unwrap_or_default();

    let raw = std::fs::read(&path)?;

    let sealed = match base64::engine::general_purpose::STANDARD.decode(
        String::from_utf8_lossy(&raw).trim(),
    ) {
        Ok(v) => v,
        Err(_) => raw,
    };
    let cert = hex::decode(cert_hex.trim()).unwrap_or_default();

    match redsocket_core::unseal_config_for_test(&sealed, &cert) {
        Ok(json) => {
            println!("{json}");
            Ok(())
        }
        Err(e) => {
            eprintln!("unseal failed: {e}");
            std::process::exit(1);
        }
    }
}
