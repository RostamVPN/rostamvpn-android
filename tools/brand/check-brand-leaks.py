#!/usr/bin/env python3
"""Fail if shared UI code hardcodes one brand's identity.

WHY THIS EXISTS: DollarVPN and RedVPN shipped a welcome screen showing the
RostamVPN wordmark and Rostam's mascot. Five separate places in shared code
hardcoded the name or reached for `rostam_hero` directly, and every one of them
compiled, passed tests, and looked perfect on the brand it was written for.
Nothing catches that except looking at another brand's app, which is exactly
the thing nobody does often enough.

Everything brand-specific must reach the UI through a resource that a brand
source set can override (`app_name`, `brand_splash`, `brand_mascot`, ...) or
through BuildConfig. CLAUDE.md 6b: there must never be an `if (brand == ...)`.

    tools/brand/check-brand-leaks.py        # exits non-zero on a leak
"""
import re, sys, pathlib

ROOT = pathlib.Path(__file__).resolve().parents[2]
SHARED = ROOT / "android/gen/android/app/src/main/java/com/rostamvpn/android"

# Brand words that must never appear as a user-visible literal in shared code.
BRANDS = ["Rostam", "RedVPN", "DollarVPN", "InternetPro", "GoGlobe"]
# Resources named for one brand: shared code must not reference them directly.
BRAND_RES = ["rostam_hero", "rostam_logo", "rostam_mark"]

# The package is com.rostamvpn.android and the class is RostamVPNApp, so the
# word appears constantly in identifiers. Only STRING LITERALS and resource
# references can leak to a user, so that is all this looks at.
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
                        # A package name or a prefs file name is not user-visible.
                        if "com.rostamvpn" in lit or lit.endswith("_prefs") or lit == "rostam":
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
