# RostamVPN Android (v3)

Production source for the RostamVPN Android client — a censorship-resistant tunnel
app for users in Iran. Native transport core in Rust (JNI), UI in Kotlin/Compose.

This repo is **self-contained**: it includes the full Rust crate closure and the
release signing material, so a clean checkout builds a signed release APK.

> **Sensitive repo.** It contains the Play Store signing key
> (`android/gen/android/keystore/rostamvpn_keystore.jks` + `key.properties`).
> Anyone with read access can sign updates that ship to users. Keep it on
> `silo.redteam.net` only — never mirror to a public host.

## Layout

| Path | What |
|------|------|
| `android/` | Android JNI lib (`src/`, Rust) → `librostam_core.so`; the Gradle app lives under `android/gen/android/` |
| `android/gen/android/app/src/main/java/com/rostamvpn/android/` | Kotlin/Compose UI + `NetCoreBridge` JNI bindings |
| `android/gen/android/app/src/main/assets/default-config.json` | Bundled OTA fallback config (transports, fronts, bootstrap domains) |
| `netcore/` | The tunnel client → `libnetcore.so` (HTTP-Upgrade + Noise NK, MUX2, SOCKS5) |
| `rostam-transport/` | Shared transport crate — path dep of `android/` |
| `sush_core/` | SushMode Noise-NK micro-frame transport |

Two native libraries ship in the APK and do different jobs:
- **`librostam_core.so`** (crate `android/`) — the app's main JNI surface: legacy
  transports, AWG/AmneziaWG, DNSTT, OTA config fetch, UI state.
- **`libnetcore.so`** (crate `netcore/`) — the tunnel that carries
  production traffic: a single HTTP-Upgrade carrier wrapped in Noise NK. No TLS stack.
  (The old multi-carrier core was removed once the fleet went HTTP-Upgrade-only.)

## Transports

The client races multiple carriers and the first full handshake wins. **Direct
server IPs are tier 1** (raced first); CDN fronts (CloudFront) are tier 3. The
primary carrier is **HTTP-Upgrade + Noise NK** on port 80. Server list + front
pool come from the OTA config (`transport.redsocket`), fetched over DNS
(`_v6.f14.1e-100.net` + mirrors), with `default-config.json` as the boot fallback.

### Wire protocol (NetCore http carrier) — DO NOT change one side only

```
client → server:  GET /noise?cid=<16-hex> HTTP/1.1   (Upgrade: websocket)
server → client:  101 Switching Protocols
        ↕         Noise_NK_25519_ChaChaPoly_BLAKE2s handshake  ([u16 BE len][ciphertext])
client → server:  stripped-SOCKS5 dest header  [0x05][atyp][addr][port]   (Noise-encrypted)
        ↕         RAW bidirectional relay.  ** THE SERVER SENDS NO REPLY. **
```

The "no reply" is load-bearing: ~215k live production clients depend on it. The
client must **not** wait to read a SOCKS5 reply after `acquire()` — doing so
deadlocks (both sides block on read) and surfaces as `early eof` ~15s later.
See `CLAUDE.md` → "The early-eof deadlock" for the full story.

## Build

```bash
# 1. Android JNI lib (per ABI). chrome-tls is REQUIRED or TLS transports silently fail.
cd android
ANDROID_NDK_HOME=<ndk> cargo ndk -t arm64-v8a -t armeabi-v7a \
  -o gen/android/app/src/main/jniLibs build --lib --release --features chrome-tls

# 2. NetCore core lib (separate crate → libnetcore.so; no TLS stack at all)
cd ../netcore
ANDROID_NDK_HOME=<ndk> cargo ndk -t arm64-v8a -t armeabi-v7a \
  -o ../android/gen/android/app/src/main/jniLibs build --lib --release

# 3. Signed release APK. -x buildRust* skips Gradle's own cargo run so the libs
#    built above (esp. the netcore.so from step 2) are NOT overwritten.
cd ../android/gen/android
./gradlew :app:assembleRelease -x buildRustRelease -x buildRustDebug
# → app/build/outputs/apk/release/app-arm64-v8a-release.apk  (signed)
```

`ndk` here is e.g. `~/Library/Android/sdk/ndk/28.0.13004108`.

## Test the tunnel without a device (fastest loop)

`netcore` has an `#[ignore]`d live test that runs the real wire path
(HTTP Upgrade → 101 → Noise NK → META → dest header → relay) against the endpoints
in the shipped config, plus one that forces the CloudFront path:

```bash
cd netcore
cargo test --lib live -- --ignored --nocapture
```

It makes real connections to production and lands one META record in the server's
cid feed under the synthetic cid `deadbeefdeadbeef`.

Use `https://ip-geo.example.com/` for IP/echo checks — **never** httpbin/ipify/etc.

## Signing

Release signing material is committed (see the note at the top). `key.properties`
points at `../keystore/rostamvpn_keystore.jks` relative to the `app/` module.
