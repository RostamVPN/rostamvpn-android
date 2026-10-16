# wgtcp-android

Mobile client core for WireGuard over a TCP carrier (WGTCP). The protocol
implementation lives in the vendored library
[`redsocket-wgtcp`](../vendor/redsocket_wgtcp) (`../vendor/redsocket_wgtcp`,
a path dependency); this crate is the thin platform shell around it:

- parses the JSON config the app hands over and forces the SOCKS listener onto
  `127.0.0.1:<port>`,
- runs the tunnel on its own Tokio runtime and exposes start / stop / status,
- protects the carrier socket through the app's `VpnService` on Android so the
  tunnel does not route into itself.

WireGuard's crypto runs in userspace via boringtun, the tunnel's IP stack is
smoltcp, and client flows enter through the local SOCKS5 server. The TCP
carrier uses the WGTCP record framing as is: there is no obfuscation layer in
this crate, and the carrier is not disguised as anything else.

## Surfaces

### JNI (Android)

`src/lib.rs` exports `Java_<app package>_WgtcpBridge_<method>`; the package
path is part of the symbol and is fixed in that file. Methods:

```
nativeStart(configJson, socksPort) -> String   ("ok" or "err:<reason>")
nativeStop()
nativeIsRunning() -> Boolean
nativeGetConnectionStatus() -> String          (JSON)
nativeGenerateKeypair() -> String              ("<private>:<public>", base64)
```

`JNI_OnLoad` caches the JavaVM for socket protection.

### C ABI (iOS / macOS)

`src/ffi.rs` is compiled on Apple targets:

```
wgtcp_start, wgtcp_stop, wgtcp_is_running, wgtcp_status,
wgtcp_generate_keypair, wgtcp_free_string
```

Strings returned by the library must be released with `wgtcp_free_string`.
`examples/apple_e2e.rs` exercises this path end to end on a workstation.

## Building

```bash
cargo ndk -t arm64-v8a build --release
# target/aarch64-linux-android/release/libwgtcp_core.so
```

For Apple targets build the `staticlib` with
`cargo build --release --target aarch64-apple-ios` (and `aarch64-apple-ios-sim`)
and package it with `xcodebuild -create-xcframework`.

### Offline

crates.io dependencies are vendored under `vendor/` and `.cargo/config.toml`
points cargo at them; the library itself is a path dependency:

```bash
cargo build --offline --release
cargo test  --offline
```

## Tests

```bash
cargo test
```

The protocol tests (framing, checksum vectors, handshake, SOCKS5 state
machine) live in the vendored library and run with `cargo test` there.
