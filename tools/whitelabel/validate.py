#!/usr/bin/env python3
"""Validate the brand manifests, and refuse the mistakes that have already happened.

Run before any build or release:

    python3 tools/whitelabel/validate.py            # validate
    python3 tools/whitelabel/validate.py --lock     # re-lock permanent identifiers

Exit code 0 = safe. Anything else = do not ship.

Three classes of check, in increasing order of how much they cost when skipped:

  SCHEMA     a manifest is missing a field, or has one it does not understand.
             Cheap: the build would fail anyway.

  PERMANENCE applicationId / bundleId changed since they were locked. These are
             externally owned and can NEVER change: a new one publishes a NEW app
             that cannot update the existing install base. Two were already
             invented once (app.goglobe.android, app.dollarvpn.android) and both
             were wrong.

  LINEAGE    this repo's versionCode is being pointed at a store listing built
             from DIFFERENT SOURCE. This is the expensive one, it is silent, and
             it is currently TRUE for redvpn -- see brands/README.md.
"""
from __future__ import annotations

import json
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parents[2]
BRANDS = ROOT / "brands"
LOCK = BRANDS / "LOCKED.json"
GRADLE = ROOT / "android/gen/android/app/build.gradle.kts"

REQUIRED = {"id", "displayName", "cashier", "wgtcpApiKey", "android", "ios", "live"}
# A reverse-DNS id, which is what both stores require. Deliberately strict: a
# typo here is not correctable after publication.
ID_RE = re.compile(r"^[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*)+$")

errors: list[str] = []
warnings: list[str] = []


def load_brands() -> dict[str, dict]:
    out = {}
    for f in sorted(BRANDS.glob("*.json")):
        if f.name == "LOCKED.json":
            continue
        try:
            out[f.stem] = json.loads(f.read_text())
        except json.JSONDecodeError as e:
            errors.append(f"{f.name}: invalid JSON -- {e}")
    return out


def check_schema(bid: str, m: dict) -> None:
    missing = REQUIRED - m.keys()
    if missing:
        errors.append(f"{bid}: missing {sorted(missing)}")
        return
    if m["id"] != bid:
        errors.append(f"{bid}: id field is {m['id']!r}, must match the filename")
    for plat, key in (("android", "applicationId"), ("ios", "bundleId")):
        v = m[plat].get(key)
        # null is legitimate and means "not yet assigned". A PLACEHOLDER is not:
        # it is indistinguishable from a real value at a glance and will be
        # published by someone in a hurry.
        if v is None:
            warnings.append(f"{bid}: no {plat} {key} yet -- that platform cannot be built")
        elif not ID_RE.match(v):
            errors.append(f"{bid}: {plat} {key} {v!r} is not a valid reverse-DNS id")
        elif any(t in v for t in ("example", "changeme", "todo", "placeholder", "yourapp")):
            errors.append(f"{bid}: {plat} {key} {v!r} looks like a placeholder")

    # A brand that CAN be built but has no store product ids ships a paywall that
    # queries the store for nothing and correctly gets nothing back -- the user
    # sees "Plans aren't available right now" and there is no error anywhere. That
    # has already happened once on iOS (§14). It is not an error, because a brand
    # legitimately reaches buildable before its SKUs are created; it must be loud.
    for plat, id_key, sku_key in (("android", "applicationId", "subProductIds"),
                                  ("ios", "bundleId", "storeKitProductIds")):
        if m[plat].get(id_key) and not m[plat].get(sku_key):
            warnings.append(
                f"{bid}: {plat} is buildable but {sku_key} is empty -- the paywall "
                f"will render with no plans and report no error. Read the ids from "
                f"the store; never invent them."
            )


def check_permanence(brands: dict[str, dict]) -> None:
    if not LOCK.exists():
        warnings.append("no LOCKED.json -- run with --lock once ids are confirmed")
        return
    locked = json.loads(LOCK.read_text())
    for bid, m in brands.items():
        for plat, key in (("android", "applicationId"), ("ios", "bundleId")):
            was = locked.get(bid, {}).get(plat, {}).get(key)
            now = m[plat].get(key)
            if was and now and was != now:
                errors.append(
                    f"{bid}: {plat} {key} changed {was!r} -> {now!r}. These are PERMANENT: "
                    f"shipping the new one publishes a separate app that cannot update the "
                    f"existing install base. If this is deliberate, --lock explicitly."
                )
            if was and now is None:
                errors.append(f"{bid}: {plat} {key} was {was!r} and is now null -- do not unset a live id")


def repo_version_code() -> int | None:
    if not GRADLE.exists():
        return None
    m = re.search(r"versionCode\s*=\s*(\d+)", GRADLE.read_text())
    return int(m.group(1)) if m else None


def check_lineage(brands: dict[str, dict]) -> None:
    """Refuse to point this repo at a listing built from different source.

    The signal is a versionCode far BELOW what this repo would upload while the
    store is on an unrelated version line. Play accepts any higher code as an
    upgrade, so nothing warns you -- the transport simply changes underneath the
    users.
    """
    repo_vc = repo_version_code()
    if repo_vc is None:
        warnings.append("could not read versionCode from build.gradle.kts")
        return
    for bid, m in brands.items():
        live = m.get("live", {})
        live_vc = live.get("androidVersionCode")
        if live_vc is None:
            continue
        if repo_vc > live_vc:
            gap = repo_vc - live_vc
            # A normal release is a handful of codes ahead. A gap this size means
            # the store listing is fed by a different codebase.
            if gap > 50:
                errors.append(
                    f"{bid}: LINEAGE MISMATCH. Store has versionCode {live_vc} "
                    f"({live.get('androidVersionName')}), this repo builds {repo_vc} -- "
                    f"a gap of {gap}. Play would accept it as an upgrade and silently "
                    f"replace the shipped app. Confirm this repo is the source for "
                    f"{m['android'].get('applicationId')} before releasing it."
                )
            else:
                warnings.append(f"{bid}: repo {repo_vc} vs store {live_vc} (normal release gap)")


def do_lock(brands: dict[str, dict]) -> None:
    lock = {
        bid: {
            "android": {"applicationId": m["android"].get("applicationId")},
            "ios": {"bundleId": m["ios"].get("bundleId")},
        }
        for bid, m in brands.items()
    }
    LOCK.write_text(json.dumps(lock, indent=2) + "\n")
    print(f"locked {len(lock)} brands -> {LOCK.relative_to(ROOT)}")


def main() -> int:
    brands = load_brands()
    if not brands:
        print("no brand manifests found", file=sys.stderr)
        return 2
    for bid, m in brands.items():
        check_schema(bid, m)
    if "--lock" in sys.argv:
        if errors:
            print("refusing to lock while there are errors:", file=sys.stderr)
            for e in errors:
                print(f"  ERROR {e}", file=sys.stderr)
            return 2
        do_lock(brands)
        return 0
    check_permanence(brands)
    check_lineage(brands)

    for w in warnings:
        print(f"  warn  {w}")
    for e in errors:
        print(f"  ERROR {e}")
    buildable = [b for b, m in brands.items() if m["android"].get("applicationId")]
    ios_ready = [b for b, m in brands.items() if m["ios"].get("bundleId")]
    print(f"\n  {len(brands)} brands | android-buildable: {', '.join(buildable)}")
    print(f"  ios-buildable: {', '.join(ios_ready) or '(none)'}")
    print("  OK" if not errors else f"\n  {len(errors)} error(s) -- DO NOT SHIP")
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main())
