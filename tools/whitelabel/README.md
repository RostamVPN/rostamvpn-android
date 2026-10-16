# White-label system

One codebase, one manifest, both platforms.

    brands/rostam.json                  the ONLY description of the brand
    brands/LOCKED.json                  permanent ids, guarded against change
    tools/whitelabel/validate.py        run before every release
    tools/whitelabel/gen-ios.py         -> <IOS REPO>/Brands/<id>/  (--ios-repo is REQUIRED)
    tools/whitelabel/gen-branding.py    -> per-brand strings, colours, icons
    tools/whitelabel/gen-launch.py      -> the splash drawables
    tools/whitelabel/check-contrast.py  the theme against WCAG

Android reads `brands/*.json` directly in `build.gradle.kts`, so the brand
appears as `assembleRostam<Store>Release` with no Gradle edit. iOS consumes the
generated `Brand.xcconfig`, which is its `BuildConfig`.

## The rule

> There must never be an `if (brand == …)` in Kotlin or Swift.

A value that belongs to the brand goes in the manifest. If you cannot express it
there, the manifest needs a field, not the code a branch.

## Release checklist

    python3 tools/whitelabel/validate.py          # must exit 0
    python3 tools/whitelabel/gen-branding.py --check
    cd android/gen/android && ./gradlew assembleRostamPlayRelease

`validate.py` is not a linter. It refuses two mistakes that are both SILENT:

* a changed `applicationId`/`bundleId` — publishes a new app that cannot update
  the existing install base;
* a **lineage mismatch** — this repo's versionCode pointed at a store listing
  built from different source. Play would accept it as an upgrade and swap the
  app under every existing user.

Do not silence it by editing the manifest. Either confirm this repo owns that
listing and update `live`, or do not release from here.
