# Brand manifest — one source of truth for both platforms

`rostam.json` fully describes the RostamVPN app. **Nothing brand-specific may
be written twice.** Android reads the manifest directly from `build.gradle.kts`;
iOS generates its `.xcconfig` and `BrandTheme.swift` from it via
`tools/whitelabel/gen-ios.py`.

The rule that makes this work:

> There must never be an `if (brand == …)` in application code. A value that
> belongs to the brand lives in the manifest, reaching code through `BuildConfig`
> (Android) or `Brand` (iOS).

## Files

    rostam.json              identity, store ids, versions, theme, legal
    configs/rostam.json      the bundled and published app config (endpoints, policy)
    configs/rostam.notes.md  the notes stripped out of that config
    assets/rostam/           the logo and dial mark the generators read
    LOCKED.json              the permanent ids, guarded by tools/whitelabel/validate.py

## Placeholders to fill before shipping

| Key | Value in this repository | Replace with |
|---|---|---|
| `cashier.base`, and `api.base` in `configs/rostam.json` | `https://cashier.example.com` | the real cashier API host |
| `wgtcpApiKey`, and `api.key` in `configs/rostam.json` | `REPLACE_WITH_WGTCP_API_KEY` | the API key the cashier issued |
| `legal.operator` | `REPLACE_WITH_OPERATOR` | the legal entity named in the privacy policy |
| `health.probe_url` in `configs/rostam.json` | `https://ip-geo.example.com/` | the approved echo endpoint |

`android/gen/android/app/src/main/assets/default-config.json` is the bundled
copy of `configs/rostam.json`; keep the two in step. After editing the manifest
run `python3 tools/whitelabel/gen-branding.py` so the generated resources
(`brand_strings.xml`, `launch_colors.xml`, `BrandPalette.kt`, the icons) follow.

## Why `null` and not a placeholder

`applicationId`, `bundleId` and store product ids are **permanent and externally
owned**. They come from Play Console and App Store Connect and cannot be derived,
guessed, or renamed later. An invented one publishes a NEW app that cannot update
the existing install base — and a wrong SKU fails *silently*: `queryProducts`
returns empty and the paywall just says plans are unavailable. `null` means
"not yet known" and the validator treats it as such. A placeholder string would be
indistinguishable from a real value.

## The `live` block is a SAFETY INTERLOCK, not documentation

It records what is actually published, read from the Play Developer API and App
Store Connect — not what this repo believes. `validate.py` compares this repo's
versionCode against `live.androidVersionCode` and refuses a manifest whose
lineage does not match, because Play accepts any higher versionCode as a routine
upgrade and would silently replace the shipped app for every existing user. Keep
the block current, or the interlock is worthless.

## Tooling

    python3 tools/whitelabel/validate.py            # schema, permanence, lineage
    python3 tools/whitelabel/gen-branding.py        # strings, colours, icons
    python3 tools/whitelabel/validate.py --lock     # once an id is confirmed
