# redsocket-http-core

Client core for the RedSocket HTTP-Upgrade transport, built as one Rust
library (`redsocket_core`) that ships as a `cdylib` for Android, a `staticlib`
for Apple platforms and an `rlib` for the desktop CLI and the tests.

What it does, end to end:

- Dials an endpoint over plain HTTP, upgrades the connection, then runs a
  Noise NK handshake on top of it (`tunnel.rs`, `noise.rs`).
- Multiplexes many client flows over one carrier with yamux (`mux.rs`) and
  speaks the MUX2 control stream (`ctl.rs`).
- Serves SOCKS5 on `127.0.0.1:<port>` for the tun bridge (`socks5.rs`).
- Chooses which endpoint to try, in what order, from the OTA config
  (`config.rs`, `select.rs`), with an optional on-device blocklist
  (`blocklist.rs`), a free-tier rate limit (`throttle.rs`) and a reachability
  sampler (`edge.rs`).
- Opens the signed and sealed OTA config (`cfgseal.rs`).
- Optionally runs the probe / peer relay (`peer.rs`), see below.

## Surfaces

### JNI (Android)

`src/lib.rs` exports `extern "system"` functions named
`Java_<app package>_RedSocketBridge_<method>`; the package path is part of the
symbol and is fixed in that file. Methods:

```
start, stop, isRunning, getSocksPort, getConnectionStatus, getActiveCarrier,
getRaceStatus, rsCarriers, markActiveCarrier, runDiagnostics, drainNotes,
loadConfig, nativeUnsealConfig,
setClientId, setRegion, setPremium, setRateLimitKbps, setCdnAllowed,
setProtocolFilter,
setBlockingEnabled, setBlocklist, getBlockStats, clearBlockHistory,
setUserRule, setUserRules, getUserRules,
enablePeer, disablePeer, startPeer, setPeerApp, setPeerRoute, setPeerTag,
setPeerZone
```

`JNI_OnLoad` caches the JavaVM so outbound sockets can be protected through the
app's `VpnService` before they connect.

### C ABI (iOS / macOS)

`src/ffi.rs` is compiled on Apple targets and exports the same surface with
`rs_` names. Strings returned by the library are owned by it and must be
released with `rs_free_string`:

```
rs_start, rs_stop, rs_is_running, rs_socks_port, rs_connection_status,
rs_get_active_carrier, rs_get_race_status, rs_carriers, rs_run_diagnostics,
rs_drain_notes, rs_load_config, rs_unseal_config, rs_free_string,
rs_set_client_id, rs_set_region, rs_set_premium, rs_set_rate_limit_kbps,
rs_set_cdn_allowed, rs_set_protocol_filter, rs_set_app,
rs_set_blocking_enabled, rs_set_blocklist, rs_get_block_stats,
rs_clear_block_history, rs_set_user_rule, rs_set_user_rules,
rs_get_user_rules,
rs_enable_peer, rs_disable_peer, rs_start_peer, rs_set_peer_route,
rs_set_peer_tag, rs_set_peer_zone
```

Every function that takes a `*const c_char` accepts null and treats it as
"absent"; the pointer only has to stay valid for the duration of the call.

## Building

### Android

```bash
cargo ndk -t arm64-v8a build --release
# target/aarch64-linux-android/release/libredsocket_core.so
```

`.cargo/config.toml` adds `--cfg chacha20_force_neon` for `aarch64-linux-android`
and remaps the build path on every target.

### iOS

```bash
cargo build --release --target aarch64-apple-ios
cargo build --release --target aarch64-apple-ios-sim
xcodebuild -create-xcframework \
  -library target/aarch64-apple-ios/release/libredsocket_core.a \
  -library target/aarch64-apple-ios-sim/release/libredsocket_core.a \
  -output RedSocketCore.xcframework
```

The crate ships no C header; declare the `rs_*` functions above by hand or
generate a header with `cbindgen` and pass it to `-headers`.

### Desktop CLI

```bash
cargo run --release --features cli --bin redsocket-cli -- <config.json> [socks-port]
```

A SOCKS5 proxy over the tunnel, for development on a workstation.

### Offline

All crates.io dependencies are vendored under `vendor/` and
`.cargo/config.toml` points cargo at them, so the crate builds without network
access:

```bash
cargo build --offline --release
cargo test  --offline
```

## Sealed OTA config

The endpoint list reaches the device as a sealed blob, opened by
`cfgseal::unseal` (`rs_unseal_config` / `nativeUnsealConfig`). The format is

```
[12-byte nonce][ChaCha20-Poly1305( [64-byte Ed25519 signature][config JSON] )]
```

The symmetric key is recombined at run time from fragments that are never
contiguous in the binary and, when the caller passes the SHA-256 of its own
signing certificate, is bound to that certificate as well. After decryption the
signature is verified against the verifying key compiled into `cfgseal.rs`; a
config that does not verify is refused, not merely logged. The verifying key is
public by construction: it can only check signatures.

`examples/cfgkeygen.rs` generates the signing seed and the matching verifying
key; `examples/unseal-config.rs` opens a sealed file and prints the JSON.

## Probe / peer relay

`peer.rs` can relay a small, rate-limited number of streams through the local
SOCKS5 port on behalf of a gateway named in the config. It runs only when both
of these hold, and both are off unless set:

1. The OTA config enables it (`probe.enabled = true` with a `gateway_host`).
2. The app has opted the user in (`enablePeer` / `rs_enable_peer`).

Either one alone does nothing; `disablePeer` / `rs_disable_peer` stops it
immediately and `stop` clears every probe counter.

## Tests

```bash
cargo test
```

Live tests (`handshakes_*`, `relays_*`, `live_*`, `print_dial_order`) are
`#[ignore]`d and need a reachable endpoint. The config tests read the shipped
bundle from `../android/gen/android/app/src/main/assets/default-config.json`
and the OTA config from `../brands/configs/rostam.json`, so they expect the
surrounding tree to be present.
