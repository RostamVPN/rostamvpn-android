// Copyright 2026 Internet Mastering & Company, Inc.
// SPDX-License-Identifier: Apache-2.0

//! Generate config-sealing key material.
use ed25519_dalek::SigningKey;
use rand::RngCore;

fn arr(name: &str, b: &[u8]) -> String {
    let body: Vec<String> = b.iter().map(|x| format!("0x{x:02x}")).collect();
    let mut out = format!("const {name}: [u8; {}] = [\n", b.len());
    for chunk in body.chunks(8) {
        out.push_str("    ");
        out.push_str(&chunk.join(", "));
        out.push_str(",\n");
    }
    out.push_str("];\n");
    out
}

fn main() {
    let path = std::env::args().nth(1).expect("usage: cfgkeygen <seed-out>");
    let mut rng = rand::thread_rng();

    let mut seed = [0u8; 32];
    rng.fill_bytes(&mut seed);
    let sk = SigningKey::from_bytes(&seed);
    let vk = sk.verifying_key();
    std::fs::write(&path, seed).expect("write seed");

    let mut h1 = [0u8; 16];
    let mut h2 = [0u8; 16];
    let mut m1 = [0u8; 16];
    let mut m2 = [0u8; 16];
    for b in [&mut h1, &mut h2, &mut m1, &mut m2] {
        rng.fill_bytes(b);
    }
    let f1: Vec<u8> = h1.iter().zip(m1.iter()).map(|(a, b)| a ^ b).collect();
    let f2: Vec<u8> = h2.iter().zip(m2.iter()).map(|(a, b)| a ^ b).collect();

    println!("// ---- paste into src/cfgseal.rs ----");
    print!("{}", arr("VERIFY_KEY", vk.as_bytes()));
    print!("{}", arr("F1", &f1));
    print!("{}", arr("M1", &m1));
    print!("{}", arr("F2", &f2));
    print!("{}", arr("M2", &m2));
    println!("// ---- publisher needs these ----");
    println!("SEED_FILE={path}");
    println!("SYM_HALVES_HEX={}{}", hex::encode(h1), hex::encode(h2));
}
