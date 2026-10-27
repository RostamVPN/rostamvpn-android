# White-label system

One codebase, one manifest per brand, both platforms.

    brands/<id>.json                the ONLY description of a brand
    brands/LOCKED.json              permanent ids, guarded against change
    tools/whitelabel/validate.py    run before every release
    tools/whitelabel/gen-ios.py     -> <IOS REPO>/Brands/<id>/  (--ios-repo is REQUIRED)
    tools/whitelabel/gen-branding.py-> per-brand strings, colours, icons

Android reads `brands/*.json` directly in `build.gradle.kts`, so a new brand
appears as `assemble<Brand><Store>Release` with no Gradle edit. iOS consumes the
generated `Brand.swift`, which is its `BuildConfig`.

## The rule

> There must never be an `if (brand == …)` in Kotlin or Swift.

A value that differs per brand goes in the manifest. If you cannot express it
there, the manifest needs a field, not the code a branch.

## Release checklist

    python3 tools/whitelabel/validate.py          # must exit 0
    python3 tools/whitelabel/gen-branding.py --check
    cd android/gen/android && ./gradlew assemble<Brand>PlayRelease

`validate.py` is not a linter. It refuses two mistakes that have already been
made here and are both SILENT:

* a changed `applicationId`/`bundleId` — publishes a new app that cannot update
  the existing install base;
* a **lineage mismatch** — this repo's versionCode pointed at a store listing
  built from different source. It currently fires for `redvpn`, and that is
  correct: Play's `com.redvpn.android` is 2.1.5/vc22 on the Reality transport
  from `RedVPN-apps/redvpn-android`, while this repo builds vc205 on the current core.
  Play would accept it as an upgrade and swap the transport under 60K users.

Do not silence it by editing the manifest. Either confirm this repo owns that
listing and update `live`, or do not release that brand from here.
