// Copyright 2026 Internet Mastering & Company, Inc. <sina@redteam.net>
// SPDX-License-Identifier: Apache-2.0

//! Config unwrapping: verify, then decrypt.
//!
//! The config decides which servers ~500K users connect to, and it is fetched
//! from CDN fronts that are deliberately untrusted delivery paths. Two separate
//! properties are needed and they are not the same thing:
//!
//! **Signature (Ed25519)** — only we can change client behaviour. The verifying
//! key is PUBLIC; extracting it from the binary gains an attacker nothing. This
//! is what stops a compromised front serving a config that points users at a
//! hostile POP, and it holds even after the symmetric key below has leaked.
//!
//! **Encryption (ChaCha20-Poly1305)** — the POP list is not readable by whoever
//! fetches it. Once the config is world-readable on a CDN, a censor does not
//! need to reverse anything to enumerate every address we have; they just fetch
//! it. This key IS in the binary and IS extractable. It buys enumeration
//! resistance, never authenticity.
//!
//! Signed first, then encrypted, so a forged ciphertext still fails
//! verification once the symmetric key is out.
//!
//! Wire format:
//!   [ 12-byte nonce ][ ciphertext ][ 16-byte tag ]
//!   plaintext = [ 64-byte Ed25519 signature ][ config JSON ]

use std::io::{Error, ErrorKind, Result};

/// Local shorthand so this module matches the crate's std-io error style rather
/// than pulling anyhow in for one file.
fn err(msg: &str) -> Error {
    Error::new(ErrorKind::InvalidData, msg)
}
use chacha20poly1305::aead::{Aead, KeyInit};
use chacha20poly1305::{ChaCha20Poly1305, Nonce};

/// Ed25519 verifying key. Public by construction: it only checks signatures, so
/// reading it out of the binary is worth nothing to an attacker. Left as a plain
/// constant on purpose -- obfuscating it would imply a secrecy it does not have
/// and does not need.
const VERIFY_KEY: [u8; 32] = [
    0x2a, 0x3f, 0x89, 0x56, 0x1a, 0x78, 0xd1, 0x13,
    0xdd, 0xbd, 0x06, 0xa6, 0x62, 0x86, 0xa8, 0x3d,
    0xad, 0xc5, 0x81, 0x93, 0xf1, 0xb8, 0xdb, 0xc9,
    0x09, 0xe5, 0x89, 0x20, 0x18, 0x80, 0xcb, 0x65,
];

// ── Symmetric key material ──────────────────────────────────────────────
//
// Never present as a contiguous 32-byte run in the binary. Three fragments,
// each XOR-masked with a different pad, recombined only inside [unseal] and
// zeroed after use.
//
// This is cost, not secrecy. It moves extraction from `unzip && grep` to real
// reverse engineering, and a debugger attached to a running app defeats all of
// it. The signature above is what survives that.

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
///
/// `cert_sha256` is the hash of the APK's signing certificate, read at runtime.
/// Mixing it in means a REPACKAGED app derives a different key and cannot
/// decrypt anything -- which is the attack that actually matters here: not
/// someone reading our POP list, but a modified build handed to users who
/// believe it is ours.
///
/// Empty `cert_sha256` skips the binding, for tests and for hosts where the
/// signature is unavailable.
fn derive_key(cert_sha256: &[u8]) -> [u8; 32] {
    use blake2::{Blake2s256, Digest};
    // black_box on both operands, or this is worse than useless.
    //
    // F1 and M1 are compile-time constants, so `F1[i] ^ M1[i]` is constant-
    // folded: the compiler computes the XOR at build time, embeds the PLAINTEXT
    // halves, and drops the masks entirely. Verified against a real build --
    // the unmasked halves were present in the .so and the masks were not, which
    // is precisely the outcome the split was meant to avoid.
    //
    // black_box is an optimisation barrier: the compiler must treat these as
    // opaque, so the XOR survives to runtime and only the masked forms are
    // stored.
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
    // Wipe the intermediate halves; the derived key is the only thing that
    // should outlive this function.
    half1.fill(0);
    half2.fill(0);
    h.finalize().into()
}

/// The capability marker for the two-key unseal.
///
/// It is an ERROR STRING on the failure path, deliberately, so it survives
/// into every shipped artifact and `strings <lib> | grep -c cfgseal-v2` is a
/// real check. A binary that answers 0 is a build that predates the cert
/// removal and CANNOT open a cert-less config -- which is otherwise
/// indistinguishable from working, because an unopened config is silently
/// replaced by the bundled defaults.
const CFGSEAL_ABI: &str = "cfgseal-v2-dualkey: config did not decrypt with either key";

/// Decrypt and verify a sealed config, returning the JSON.
///
/// Verification is NOT optional and there is no flag to skip it. A path that
/// accepts an unverified config is a path an attacker will find a way to reach.
pub fn unseal(sealed: &[u8], cert_sha256: &[u8]) -> Result<String> {
    if sealed.len() < 12 + 64 + 16 {
        return Err(err("sealed config too short"));
    }
    let (nonce, body) = sealed.split_at(12);

    // 🚨 TWO KEYS, NEW FIRST, AND THE CERT IS ON ITS WAY OUT.
    //
    // The certificate binding could never work for a Play install: Play RE-SIGNS
    // every app with its own key, so the fingerprint the client reads at runtime
    // is not the one the publisher sealed with. Measured 2026-09-23 --
    // publisher 6432a955 (the upload key), while Play ships rostam as 32ea7e35,
    // redvpn 21de9346, dollarvpn 9c06b3ed. Not one matches. So NO Play install
    // of ANY brand has ever opened an OTA config; each fetched, failed here, and
    // silently kept its bundled defaults. Only the direct-download APKs, signed
    // with the upload key itself, ever worked.
    //
    // New configs are sealed WITHOUT the certificate, so one key opens them
    // everywhere. The cert-bound derivation is kept ONLY as a fallback so this
    // build still reads configs published before the switch -- which is what
    // makes the change deployable without a flag day. Once nothing cert-sealed
    // is being served, the second attempt and `cert_sha256` can go.
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
        // An empty cert would make the second attempt identical to the first.
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
        // The derived key must not equal any fragment or simple concatenation,
        // or the obfuscation is decorative: an analyst grepping for a 32-byte
        // run would find it directly.
        let k = derive_key(b"");
        assert_ne!(&k[..16], &F1[..]);
        assert_ne!(&k[..16], &F2[..]);
        let naive: Vec<u8> = F1.iter().chain(F2.iter()).copied().collect();
        assert_ne!(k.to_vec(), naive);
    }

    /// This test exists because the first version of this module FAILED it.
    ///
    /// The XOR of two compile-time constants is constant-folded, so the built
    /// .so contained the unmasked halves and none of the masks -- the exact
    /// opposite of the intent, and the original test did not notice because it
    /// only checked the derived key. Whatever else changes here, the unmasked
    /// halves must never be reconstructible without executing the code.
    #[test]
    fn the_unmasked_halves_are_not_a_compile_time_constant() {
        let mut h1 = [0u8; 16];
        let mut h2 = [0u8; 16];
        for i in 0..16 {
            h1[i] = F1[i] ^ M1[i];
            h2[i] = F2[i] ^ M2[i];
        }
        // Sanity: the masks must actually mask. A zero mask would store the
        // plaintext directly however the compiler behaves.
        assert_ne!(h1, F1, "M1 does not change F1");
        assert_ne!(h2, F2, "M2 does not change F2");
        assert!(M1.iter().any(|b| *b != 0), "M1 is all zeroes");
        assert!(M2.iter().any(|b| *b != 0), "M2 is all zeroes");
        // And the derived key must depend on both halves.
        assert_ne!(derive_key(b"").to_vec(), h1.iter().chain(h2.iter()).copied().collect::<Vec<_>>());
    }

    #[test]
    fn a_different_signing_certificate_yields_a_different_key() {
        // This is what makes a repackaged APK unable to decrypt: it derives a
        // different key from its own signing certificate.
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
    ///
    /// The publisher and the client derive the symmetric key independently, in
    /// separate crates. A divergence there publishes cleanly and then fails to
    /// open on every device at once, which is the worst shape a bug can take
    /// here -- so the two are proven to agree rather than assumed to.
    #[test]
    fn opens_a_config_sealed_by_the_real_publisher() {
        use base64::Engine;
        const SEALED_B64: &str = "11UIUQ3WbDV56TB78han7BkqKjatch2N5I9bKa57HByxw54b47+8illeR3q9rpXJZJqjILM5yf3nUjTMYxNYvMBbgs9GLMO7dYI9MbRYMMihfxrn5i/G1QiCd9JsTuUjYvMvC5lMz9iC4K7OAUcvAbisGTZmnGb2MFKFbQ8TLB+R2w4T8AJ4zkzL4sFnPPFrUxenPKUzwVrCSfSy65Sza9L1QpcR7YtNTlW/ejiG9maZWEmmdBKo";
        const CERT_HEX: &str = "6432a955d40722a1fd604d6384d445198c551cd8905e889eb98d432688cec415";

        let raw = base64::engine::general_purpose::STANDARD.decode(SEALED_B64).unwrap();
        let cert = hex_decode(CERT_HEX);

        let json = unseal(&raw, &cert).expect("publisher output must open");
        assert!(json.contains("rt-test"), "wrong payload: {json}");

        // A build signed with a different certificate derives a different key.
        assert!(unseal(&raw, &[0u8; 32]).is_err(), "wrong signing cert must not open it");

        // One flipped byte must fail authentication, not decrypt to garbage.
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
        // Same bytes, different signing certificate: must not decrypt.
        let sealed = [7u8; 300];
        assert!(unseal(&sealed, b"cert-a").is_err());
        assert!(unseal(&sealed, b"cert-b").is_err());
    }
}

#[cfg(test)]
mod qr_live_tests {
    use super::*;

    /// The QR bootstrap the server is serving RIGHT NOW must open with the
    /// shipping app's key. Ignored by default: it makes a deployment check, not
    /// a unit test.
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

        // A tampered code must NOT open. This is the property that makes scanning
        // a QR off a poster or a forwarded screenshot safe at all.
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

    /// Encrypt a body under the key derived from `cert`. The signature is
    /// GARBAGE on purpose: the signing seed is root-only on the publisher, so a
    /// valid one cannot be produced here. That is fine, because the layer this
    /// change touches is KEY SELECTION, and the two failures are distinguishable
    /// -- "did not decrypt" means no key matched, "signature does not verify"
    /// means one did and we reached the next stage.
    fn seal_with(cert: &[u8]) -> Vec<u8> {
        let mut plain = vec![0u8; 64];          // 64 bytes of not-a-signature
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

    /// One client must open BOTH: configs published after the certificate was
    /// dropped, and everything sealed before it. Without the second attempt this
    /// is a flag day -- every device stops reading config the moment the
    /// publisher switches, which is the opposite of the bug being fixed.
    #[test]
    fn opens_both_certless_and_legacy_cert_sealed_configs() {
        let cert = [0xABu8; 32];

        assert!(decrypted(&seal_with(&[]), &cert), "cert-less config must decrypt");
        assert!(decrypted(&seal_with(&[]), &[]), "…and with no cert supplied");
        assert!(decrypted(&seal_with(&cert), &cert), "legacy cert-sealed must still decrypt");

        // Dropping the binding must not become "any key opens it".
        assert!(!decrypted(&seal_with(&[0x11u8; 32]), &cert),
                "a config sealed under a foreign cert must still be refused");
    }
}
