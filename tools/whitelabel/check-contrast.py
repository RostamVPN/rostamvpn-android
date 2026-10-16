#!/usr/bin/env python3
# Copyright 2026 Internet Mastering & Company, Inc.
# SPDX-License-Identifier: Apache-2.0
"""Check every brand palette against the SHARED UI that renders it.

    python3 tools/whitelabel/check-contrast.py

The white-label system gives four brands one screen design. That design was drawn
around Rostam's teal, so a palette can be perfectly good on its own mockup and
still produce unreadable text here -- swapping the accent does not re-lay-out the
screen around it. This is the check that catches that.

Thresholds are WCAG 2.1 and depend on TEXT SIZE, which is why each pairing names
the element it comes from rather than being checked generically:

    4.5   normal text (< 18.66sp regular, < 14sp bold)
    3.0   large text, and non-text UI components

Getting that distinction wrong in either direction is expensive: too strict and
you repaint an approved brand identity for no reason; too loose and you ship a
button nobody can read.
"""
from __future__ import annotations
import glob
import json
import pathlib
import sys
ROOT = pathlib.Path(__file__).resolve().parents[2]


def _lin(c: float) -> float:
    return c / 12.92 if c <= 0.03928 else ((c + 0.055) / 1.055) ** 2.4


def luminance(hex_colour: str) -> float:
    h = hex_colour.lstrip("#")
    if len(h) == 8:
        h = h[2:]
    r, g, b = (int(h[i:i + 2], 16) / 255 for i in (0, 2, 4))
    return 0.2126 * _lin(r) + 0.7152 * _lin(g) + 0.0722 * _lin(b)


def ratio(fg: str, bg: str) -> float:
    a, b = luminance(fg), luminance(bg)
    hi, lo = max(a, b), min(a, b)
    return (hi + 0.05) / (lo + 0.05)
CHECKS = [
    ("button label",  "#FFFFFF",       "primary",    4.5, "Get started / connect button, 15sp"),
    ("body text",     "ink",           "background", 4.5, "welcome body, settings, 13-14sp"),
    ("error text",    "#FFFFFF",       "danger",     4.5, "error states, 14sp"),
    ("headline accent", "accentOnLight", "background", 3.0, "welcome title accent word, 30sp = LARGE"),
    ("ornament",      "accent",        "background", 3.0, "divider rule / non-text component"),
]
EXEMPT = {("rostam", "ornament")}


def main() -> int:
    failures = 0
    for f in sorted(glob.glob(str(ROOT / "brands" / "*.json"))):
        if f.endswith("LOCKED.json"):
            continue
        m = json.loads(pathlib.Path(f).read_text())
        t = m.get("branding", {}).get("theme")
        if not t:
            print(f"  {m['id']}: no theme (uses defaults) -- skipped")
            continue
        print(f"  === {m['id']} ===")
        for label, fg_key, bg_key, need, where in CHECKS:
            fg = fg_key if fg_key.startswith("#") else t[fg_key]
            bg = t[bg_key]
            r = ratio(fg, bg)
            exempt = (m["id"], label) in EXEMPT
            if r >= need:
                status = "OK  "
            elif exempt:
                status = "n/a "
            else:
                status = "FAIL"
                failures += 1
            note = "  (design forbids this pairing)" if exempt else ""
            print(f"    {status} {label:16} {r:5.2f} / {need}   {where}{note}")
    print(f"\n  {failures} real failure(s)")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
