# RostamVPN Android

The RostamVPN client for Android, written in Kotlin with Jetpack Compose.
The transport is a Rust core (`redsocket-http-core/`) loaded over JNI as
`libredsocket_core.so`; an optional second protocol, WireGuard over a TCP
carrier, comes from `wgtcp-android/` as `libwgtcp_core.so`. Packets from the
`VpnService` TUN interface reach the core's local SOCKS5 port through
`hev-socks5-tunnel` (`libhev-socks5-tunnel.so`, built separately from its
upstream source).

    android/gen/android/     Gradle project (app module)
    brands/                  the brand manifest; the only place brand facts live
    tools/                   release, white-label, store-listing and i18n scripts
    redsocket-http-core/     Rust transport core (JNI + C ABI)
    wgtcp-android/           Rust WireGuard-over-TCP core (JNI)

## Flavours

Two flavour dimensions, `brand` x `store`:

| brand    | store    |
|----------|----------|
| `rostam` | `play`   |
|          | `direct` |

`brand` is read from `brands/rostam.json` at configure time (application id,
version, product ids), so the manifest plus the `src/rostam/` resource overlay
is the whole brand. `store` decides whether purchases go through Play Billing
(`play`) or the cashier's hosted checkout (`direct`).

A task name is `<verb>Rostam<Store><BuildType>`, e.g.
`assembleRostamPlayRelease`, `bundleRostamDirectRelease`.

The app has no ad SDK, no embedded-SIM store and no third-party paywall
service; those were removed along with the brands that used them.

## Placeholders to fill

| Where | Placeholder | Replace with |
|---|---|---|
| `brands/rostam.json` → `cashier.base`; `brands/configs/rostam.json` and `app/src/main/assets/default-config.json` → `api.base` | `https://cashier.example.com` | the cashier API host |
| `brands/rostam.json` → `wgtcpApiKey`; the two configs → `api.key` | `REPLACE_WITH_WGTCP_API_KEY` | the API key the cashier issued |
| `brands/rostam.json` → `legal.operator`; `app/src/rostam/res/values/brand_strings.xml` → `legal_operator` | `REPLACE_WITH_OPERATOR` | the legal entity named in the privacy policy |
| the two configs → `health.probe_url` | `https://ip-geo.example.com/` | the approved echo endpoint |

The two configs also carry placeholder endpoints and sealing material; replace
them with real values before shipping. `brand_strings.xml`, `launch_colors.xml`
and `BrandPalette.kt` are generated from the manifest by
`tools/whitelabel/gen-branding.py`; edit the manifest and regenerate rather
than editing them by hand.

## Building

1. Build the two native libraries with [cargo-ndk](https://github.com/bbqsrc/cargo-ndk)
   into the app's `jniLibs`:

       export ANDROID_HOME=$HOME/Library/Android/sdk   # or wherever the SDK lives
       JNI=$PWD/android/gen/android/app/src/main/jniLibs
       (cd redsocket-http-core && cargo ndk -t arm64-v8a -t armeabi-v7a -o $JNI build --release)
       (cd wgtcp-android && cargo ndk -t arm64-v8a -t armeabi-v7a -o $JNI build --release)

   Run cargo from each crate's directory: only there does its
   `.cargo/config.toml` (vendored sources, path remapping) apply.

   The build expects NDK `28.0.13004108` and compiles for `arm64-v8a` and
   `armeabi-v7a`. `libhev-socks5-tunnel.so` for the same ABIs goes in the same
   directory.

2. Build the app:

       cd android/gen/android
       ./gradlew :app:assembleRostamPlayRelease      # or assembleRostamDirectRelease

   Create `local.properties` with `sdk.dir=<path to the Android SDK>` if the
   SDK is not found through `ANDROID_HOME`.

### Offline builds

Every Gradle dependency, plugin and build tool is vendored under
`android/gen/android/local-maven/` in Maven layout, and that directory is the
first repository in `settings.gradle` and `build.gradle.kts`. With the Gradle
distribution already present, `./gradlew --offline ...` builds without network.
The mirror holds native build tools (`aapt2`) for the host OS that ran the
vendor task, so refresh it on each OS you build from. After changing a
dependency, refresh it once online:

    ./gradlew -I gradle/vendor.init.gradle.kts vendorDependencies

### Signing

`key.properties` and the keystore are not part of the repository. A release
build looks for `android/gen/android/key.properties`:

    storeFile=<path to .jks>
    storePassword=...
    keyAlias=...
    keyPassword=...

A key named after the brand is optional and takes precedence when present:

    rostam.storeFile=<path to .jks>
    rostam.keyAlias=...
    rostam.storePassword=...          # or rostam.passwordFile=<file holding it>
    rostam.keyPassword=...
    rostam.storeType=pkcs12           # optional

Without `key.properties` the release variants still compile; they are simply
unsigned.

## Configuration

The app bundles `app/src/main/assets/default-config.json`, a copy of
`brands/configs/rostam.json`; keep the two in step. Everything in it except the
`api` bootstrap block is replaced by whatever the cashier serves at
`GET {base}/api/v1/config`.

## Peer relay

The app contains an optional peer relay. It is off by default and only runs
when both of the following hold:

* the over-the-air config sets `probe.enabled` to `true`, and
* the user has opted in from the settings sheet.

Either one alone does nothing.

## Tooling

    python3 tools/whitelabel/validate.py        # brand manifest: schema, permanence, lineage
    python3 tools/whitelabel/gen-branding.py    # brand strings, colours, icons
    python3 tools/i18n/validate-translations.py # translated resources vs. English
    python3 tools/brand/check-brand-leaks.py    # no brand identity hardcoded in shared UI

Scripts under `tools/apple/` and `tools/play/` talk to the store APIs and need
credentials that are not included; fill in the placeholders they document.

## License

Apache-2.0. Third-party components keep their own licences
(`app/src/main/java/hev/` is from hev-socks5-tunnel).

## Related repositories

| Repository | Contents |
|---|---|
| `redsocket_http` | The exit server (Rust). |
| `rostamvpn-android` | The Android app, the shared Rust client core (`redsocket-http-core/`) and the WireGuard-over-TCP core. |
| `rostamvpn-ios` | The iOS app and its Network Extension; its core frameworks are built from the cores in `rostamvpn-android`. |
| `redsocket_pfe` | AF_XDP triangle-routing front-end. |
| `redsocket_wgtcp` | The userspace WireGuard-over-TCP client library and tools; vendored into `rostamvpn-android` for the Android core. |
| `redsocket_proteus` | The server wrapped in Proteus programmable protocols; intended to replace `redsocket_http` once proven. |

## Removed for publication

Deployment and infrastructure documents, exporter scripts, release artifacts,
signing material and API keys (replaced by `REPLACE_WITH_*` placeholders),
server telemetry other than the cid stats, other brands, narrative comments,
and dead code. All third-party dependencies are vendored in the tree and keep
their own license files.
