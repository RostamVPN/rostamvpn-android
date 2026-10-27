# Brand manifests — one source of truth for both platforms

Each `<brand>.json` fully describes a white-label app. **Nothing brand-specific may
be written twice.** Android reads these files directly from `build.gradle.kts`;
iOS generates its `.xcconfig` and `Brand.swift` from them via
`tools/whitelabel/gen-ios.py`.

The rule that makes this work is the one already enforced in Kotlin and now
extended to Swift:

> There must never be an `if (brand == …)` in application code. A value that
> differs per brand belongs in the manifest, reaching code through `BuildConfig`
> (Android) or `Brand` (iOS).

## Adding a brand

1. `cp rostam.json <new>.json` and edit. Leave unknown ids `null` — do NOT guess.
2. `python3 tools/whitelabel/validate.py` — it refuses invented or changed ids.
3. Android: `src/<brand>/res/` + `src/<brand>/assets/default-config.json` overlays.
4. iOS: `python3 tools/whitelabel/gen-ios.py <brand>`.
5. Cashier: a `BrandDomain` row and the DNS record.

## Why `null` and not a placeholder

`applicationId`, `bundleId` and store product ids are **permanent and externally
owned**. They come from Play Console and App Store Connect and cannot be derived,
guessed, or renamed later. An invented one publishes a NEW app that cannot update
the existing install base — and a wrong SKU fails *silently*: `queryProducts`
returns empty and the paywall just says plans are unavailable.

Both mistakes have already happened here. `app.goglobe.android` and
`app.dollarvpn.android` were invented and were wrong; assuming every brand reused
`p1m`/`p1a` would have killed the paywall for RedVPN's 60K installs. `null` means
"not yet known" and the validator treats it as such. A placeholder string would be
indistinguishable from a real value.

## The `live` block is a SAFETY INTERLOCK, not documentation

It records what is actually published, read from the Play Developer API and App
Store Connect — not what this repo believes.

It exists because of a live hazard discovered 2026-08-27: this repo's `redvpn`
flavor and the RedVPN app on Play are **different products that share an
applicationId**. Play's `com.redvpn.android` is version 2.1.5 / versionCode 22,
built from `RedVPN-apps/redvpn-android` on the **Reality** transport. This repo's
flavor is versionCode 205 on the current core. Because 205 > 22, Play would accept it as
a routine upgrade and silently replace the transport for every existing user.

`validate.py` compares this repo's versionCode against `live.androidVersionCode`
and refuses a brand whose lineage does not match. Keep the block current, or the
interlock is worthless.
