//! Open a sealed OTA config and print the JSON.
//!
//!     cargo run --example unseal-config -- <sealed-file> [cert-sha256-hex]
//!
//! The sealed file is what `GET /api/v1/config` returns: base64 of
//! [12-byte nonce][ciphertext][16-byte tag], whose plaintext is
//! [64-byte Ed25519 signature][config JSON].
//!
//! This exists so the config an actual device would see can be read off the
//! wire and archived. It uses the SAME `cfgseal::unseal` the client uses, not a
//! reimplementation, so anything this cannot open is something the fleet cannot
//! open either. That is the useful property: a publish that would have bricked
//! clients fails here first.
//!
//! The cert hash is optional and defaults to empty, matching a config published
//! without `--cert-sha256`. It is mixed into the key derivation, so passing the
//! wrong one fails exactly like a tampered payload does.
use base64::Engine;

fn main() -> std::io::Result<()> {
    let mut args = std::env::args().skip(1);
    let path = args.next().unwrap_or_else(|| {
        eprintln!("usage: unseal-config <sealed-file> [cert-sha256-hex]");
        std::process::exit(2);
    });
    let cert_hex = args.next().unwrap_or_default();

    let raw = std::fs::read(&path)?;
    // Accept either base64 (what the API serves) or the raw sealed bytes.
    let sealed = match base64::engine::general_purpose::STANDARD.decode(
        String::from_utf8_lossy(&raw).trim(),
    ) {
        Ok(v) => v,
        Err(_) => raw,
    };
    let cert = hex::decode(cert_hex.trim()).unwrap_or_default();

    match netcore::unseal_config_for_test(&sealed, &cert) {
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
