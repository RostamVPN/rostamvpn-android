#!/usr/bin/env python3
# Copyright 2026 Internet Mastering & Company, Inc.
# SPDX-License-Identifier: Apache-2.0
"""Fail if shared UI code hardcodes one brand's identity.

WHY THIS EXISTS: shared code once hardcoded the RostamVPN wordmark and the
Rostam mascot in five separate places, and every one of them compiled, passed
tests and looked perfect. Brand identity that bypasses the resource system is
invisible until the resources change underneath it.

Everything brand-specific must reach the UI through a resource that a brand
source set can override (`app_name`, `brand_splash`, `brand_mascot`, ...) or
through BuildConfig. Project rule: there must never be an `if (brand == ...)`.

Identifier-shaped literals -- storage keys, log tags, URL schemes, file names,
protocol magic -- carry no whitespace and are exempt: they are not displayed,
and renaming one loses the user's settings. A displayed name goes through a
resource, and displayed copy always has a space in it.

    tools/brand/check-brand-leaks.py        # exits non-zero on a leak
"""
import re, sys, pathlib
ROOT = pathlib.Path(__file__).resolve().parents[2]
SHARED = ROOT / "android/gen/android/app/src/main/java/com/rostamvpn/android"
BRANDS = ["Rostam"]
BRAND_RES = ["rostam_logo", "rostam_mark"]
LITERAL = re.compile(r'"([^"\\]*(?:\\.[^"\\]*)*)"')


def leaks():
    out = []
    for f in sorted(SHARED.rglob("*.kt")):
        for n, line in enumerate(f.read_text(encoding="utf-8").splitlines(), 1):
            stripped = line.strip()
            if stripped.startswith(("//", "*", "/*")):
                continue
            for lit in LITERAL.findall(line):
                for b in BRANDS:
                    if b.lower() in lit.lower():
                        if "com.rostamvpn" in lit or not any(ch.isspace() for ch in lit):
                            continue
                        out.append((f, n, f'literal "{lit[:52]}" contains "{b}"'))
            for r in BRAND_RES:
                if r in line and "R.drawable." + r in line.replace(" ", ""):
                    out.append((f, n, f"references brand-specific resource {r}"))
    return out


def main():
    found = leaks()
    for f, n, why in found:
        print(f"  {f.relative_to(ROOT)}:{n}  {why}")
    if found:
        print(f"\n{len(found)} brand leak(s) in shared UI code.")
        print("Use stringResource(R.string.app_name) or a brand-overridable drawable.")
        return 1
    print("no brand leaks in shared UI code")
    return 0


if __name__ == "__main__":
    sys.exit(main())
