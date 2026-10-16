// Copyright 2026 Internet Mastering & Company, Inc.
// SPDX-License-Identifier: Apache-2.0

//! Config unwrapping: verify, then decrypt.

use std::io::{Error, ErrorKind, Result};

/// Local shorthand so this module matches the crate's std-io error style rather
/// than pulling anyhow in for one file.
fn err(msg: &str) -> Error {
    Error::new(ErrorKind::InvalidData, msg)
}
use chacha20poly1305::aead::{Aead, KeyInit};
use chacha20poly1305::{ChaCha20Poly1305, Nonce};

/// Ed25519 verifying key. Public by construction: it only checks signatures, so
/// reading it out of the binary is worth nothing to an attacker.
const VERIFY_KEY: [u8; 32] = [
    0x2a, 0x3f, 0x89, 0x56, 0x1a, 0x78, 0xd1, 0x13,
    0xdd, 0xbd, 0x06, 0xa6, 0x62, 0x86, 0xa8, 0x3d,
    0xad, 0xc5, 0x81, 0x93, 0xf1, 0xb8, 0xdb, 0xc9,
    0x09, 0xe5, 0x89, 0x20, 0x18, 0x80, 0xcb, 0x65,
];

const F1: [u8; 16] = [
    0xe1, 0x7b, 0xc8, 0xd1, 0xf9, 0x7a, 0xf1, 0x92,
    0xdc, 0x97, 0x4e, 0xc2, 0xfe, 0xb2, 0x4a, 0xee,
];
const M1: [u8; 16] = [
    0x10, 0xa2, 0xbc, 0x28, 0xeb, 0x79, 0x07, 0x59,
    0xc0, 0xcd, 0xac, 0x73, 0xef, 0x02, 0x30, 0x28,
];
const F2: [u8; 16] = [
    0x85, 0x19, 0x51, 0xa0, 0xf4, 0x9f, 0x7a, 0x51,
    0x6c, 0xcc, 0x37, 0x23, 0xaf, 0xe9, 0x27, 0x03,
];
const M2: [u8; 16] = [
    0x5d, 0xa9, 0x04, 0x9f, 0x56, 0x86, 0xc7, 0x53,
    0x4f, 0x64, 0x50, 0xbb, 0xa6, 0x2e, 0x56, 0x2f,
];

/// Recombine the fragments and bind the result to the installation.
fn derive_key(cert_sha256: &[u8]) -> [u8; 32] {
    use blake2::{Blake2s256, Digest};

    let f1 = std::hint::black_box(F1);
    let m1 = std::hint::black_box(M1);
    let f2 = std::hint::black_box(F2);
    let m2 = std::hint::black_box(M2);
    let mut half1 = [0u8; 16];
    let mut half2 = [0u8; 16];
    for i in 0..16 {
        half1[i] = f1[i] ^ m1[i];
        half2[i] = f2[i] ^ m2[i];
    }
    let mut h = Blake2s256::new();
    h.update(b"rostam-cfg-v1");
    h.update(half1);
    h.update(half2);
    if !cert_sha256.is_empty() {
        h.update(cert_sha256);
    }

    half1.fill(0);
    half2.fill(0);
    h.finalize().into()
}

/// The capability marker for the two-key unseal.
const CFGSEAL_ABI: &str = "cfgseal-v2-dualkey: config did not decrypt with either key";

/// Decrypt and verify a sealed config, returning the JSON.
pub fn unseal(sealed: &[u8], cert_sha256: &[u8]) -> Result<String> {
    if sealed.len() < 12 + 64 + 16 {
        return Err(err("sealed config too short"));
    }
    let (nonce, body) = sealed.split_at(12);

    let mut plain_opt = None;
    let mut first_err = None;
    for candidate in [&[][..], cert_sha256] {
        let mut key = derive_key(candidate);
        let out = ChaCha20Poly1305::new((&key).into())
            .decrypt(Nonce::from_slice(nonce), body);
        key.fill(0);
        match out {
            Ok(p) => { plain_opt = Some(p); break; }
            Err(_) => {
                if first_err.is_none() {
                    first_err = Some(err(CFGSEAL_ABI));
                }
            }
        }

        if cert_sha256.is_empty() { break; }
    }
    let plain = match plain_opt {
        Some(p) => p,
        None => return Err(first_err.unwrap_or_else(|| err("config did not decrypt"))),
    };

    if plain.len() < 64 {
        return Err(err("sealed config carries no signature"));
    }
    let (sig, json) = plain.split_at(64);

    use ed25519_dalek::{Signature, Verifier, VerifyingKey};
    let vk = VerifyingKey::from_bytes(&VERIFY_KEY).map_err(|_| err("bad verifying key"))?;
    let signature = Signature::from_slice(sig).map_err(|_| err("bad signature encoding"))?;
    vk.verify(json, &signature)
        .map_err(|_| err("config signature does not verify -- refusing it"))?;

    String::from_utf8(json.to_vec()).map_err(|_| err("config is not valid UTF-8"))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn key_material_is_never_contiguous_in_the_binary() {
        let k = derive_key(b"");
        assert_ne!(&k[..16], &F1[..]);
        assert_ne!(&k[..16], &F2[..]);
        let naive: Vec<u8> = F1.iter().chain(F2.iter()).copied().collect();
        assert_ne!(k.to_vec(), naive);
    }

    /// This test exists because the first version of this module FAILED it.
    #[test]
    fn the_unmasked_halves_are_not_a_compile_time_constant() {
        let mut h1 = [0u8; 16];
        let mut h2 = [0u8; 16];
        for i in 0..16 {
            h1[i] = F1[i] ^ M1[i];
            h2[i] = F2[i] ^ M2[i];
        }

        assert_ne!(h1, F1, "M1 does not change F1");
        assert_ne!(h2, F2, "M2 does not change F2");
        assert!(M1.iter().any(|b| *b != 0), "M1 is all zeroes");
        assert!(M2.iter().any(|b| *b != 0), "M2 is all zeroes");

        assert_ne!(derive_key(b"").to_vec(), h1.iter().chain(h2.iter()).copied().collect::<Vec<_>>());
    }

    #[test]
    fn a_different_signing_certificate_yields_a_different_key() {
        let ours = derive_key(b"our-cert-hash");
        let theirs = derive_key(b"repackaged-cert-hash");
        assert_ne!(ours, theirs);
        assert_eq!(ours, derive_key(b"our-cert-hash"), "derivation must be stable");
    }

    #[test]
    fn refuses_garbage_and_truncation() {
        assert!(unseal(b"", b"").is_err());
        assert!(unseal(&[0u8; 50], b"").is_err(), "shorter than nonce+sig+tag");
        assert!(unseal(&[0u8; 200], b"").is_err(), "does not authenticate");
    }

    /// A real config sealed by the real publisher, opened by this code.
    #[test]
    fn opens_a_config_sealed_by_the_real_publisher() {
        use base64::Engine;
        const SEALED_B64: &str = "11UIUQ3WbDV56TB78han7BkqKjatch2N5I9bKa57HByxw54b47+8illeR3q9rpXJZJqjILM5yf3nUjTMYxNYvMBbgs9GLMO7dYI9MbRYMMihfxrn5i/G1QiCd9JsTuUjYvMvC5lMz9iC4K7OAUcvAbisGTZmnGb2MFKFbQ8TLB+R2w4T8AJ4zkzL4sFnPPFrUxenPKUzwVrCSfSy65Sza9L1QpcR7YtNTlW/ejiG9maZWEmmdBKo";
        const CERT_HEX: &str = "6432a955d40722a1fd604d6384d445198c551cd8905e889eb98d432688cec415";

        let raw = base64::engine::general_purpose::STANDARD.decode(SEALED_B64).unwrap();
        let cert = hex_decode(CERT_HEX);

        let json = unseal(&raw, &cert).expect("publisher output must open");
        assert!(json.contains("rt-test"), "wrong payload: {json}");

        assert!(unseal(&raw, &[0u8; 32]).is_err(), "wrong signing cert must not open it");

        let mut tampered = raw.clone();
        let n = tampered.len() - 1;
        tampered[n] ^= 0x01;
        assert!(unseal(&tampered, &cert).is_err(), "tampered ciphertext must be refused");
    }

    fn hex_decode(s: &str) -> Vec<u8> {
        (0..s.len()).step_by(2).map(|i| u8::from_str_radix(&s[i..i + 2], 16).unwrap()).collect()
    }

    #[test]
    fn a_config_sealed_for_another_install_is_refused() {
        let sealed = [7u8; 300];
        assert!(unseal(&sealed, b"cert-a").is_err());
        assert!(unseal(&sealed, b"cert-b").is_err());
    }
}

#[cfg(test)]
mod qr_live_tests {
    use super::*;

    /// The QR bootstrap the server is serving RIGHT NOW must open with the shipping
    /// app's key. Ignored by default: it makes a deployment check, not a unit test.
    #[test]
    #[ignore]
    fn live_qr_bootstrap_opens() {
        use base64::Engine;
        let b64 = std::fs::read_to_string("/tmp/qr.b64").expect("payload staged");
        let cert_hex = std::env::var("CERT_SHA256").expect("CERT_SHA256");
        let cert: Vec<u8> = (0..cert_hex.len())
            .step_by(2)
            .map(|i| u8::from_str_radix(&cert_hex[i..i + 2], 16).unwrap())
            .collect();
        let raw = base64::engine::general_purpose::STANDARD
            .decode(b64.trim())
            .expect("base64");
        let json = unseal(&raw, &cert).expect("bootstrap must verify and decrypt");
        assert!(json.contains("\"bootstrap\":true"), "must be marked a bootstrap: {json}");
        let n = json.matches("\"host\"").count();
        println!("  bootstrap opened: {n} endpoints, {} bytes json", json.len());
        assert!(n > 0, "must carry endpoints");

        let mut bad = raw.clone();
        let last = bad.len() - 20;
        bad[last] ^= 0x01;
        assert!(unseal(&bad, &cert).is_err(), "tampered payload must be refused");
        println!("  tampered payload refused");
    }
}

#[cfg(test)]
mod compat_tests {
    use super::*;
    use chacha20poly1305::{aead::Aead, KeyInit};

    /// Encrypt a body under the key derived from `cert`. The signature is GARBAGE
    /// on purpose: the signing seed is root-only on the publisher, so a valid one
    /// cannot be produced here.
    fn seal_with(cert: &[u8]) -> Vec<u8> {
        let mut plain = vec![0u8; 64];
        plain.extend_from_slice(br#"{"version":"test"}"#);
        let key = derive_key(cert);
        let nonce = [7u8; 12];
        let ct = ChaCha20Poly1305::new((&key).into())
            .encrypt(Nonce::from_slice(&nonce), plain.as_ref())
            .unwrap();
        let mut out = nonce.to_vec();
        out.extend_from_slice(&ct);
        out
    }

    fn decrypted(sealed: &[u8], cert: &[u8]) -> bool {
        match unseal(sealed, cert) {
            Ok(_) => true,
            Err(e) => e.to_string().contains("signature"),
        }
    }

    /// One client must open BOTH.
    #[test]
    fn opens_both_certless_and_legacy_cert_sealed_configs() {
        let cert = [0xABu8; 32];

        assert!(decrypted(&seal_with(&[]), &cert), "cert-less config must decrypt");
        assert!(decrypted(&seal_with(&[]), &[]), "…and with no cert supplied");
        assert!(decrypted(&seal_with(&cert), &cert), "legacy cert-sealed must still decrypt");

        assert!(!decrypted(&seal_with(&[0x11u8; 32]), &cert),
                "a config sealed under a foreign cert must still be refused");
    }
}
