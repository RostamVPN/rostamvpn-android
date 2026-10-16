#!/usr/bin/env python3
# Copyright 2026 Internet Mastering & Company, Inc.
# SPDX-License-Identifier: Apache-2.0
"""Validate translated Android string resources against the English source.

Run before any release that touched res/values*/strings.xml:

    python3 tools/i18n/validate-translations.py

Why this exists: a bad translation does not fail the build. It fails on one
user's phone, in one language, in a country where nobody on this team can read
the screen. Each check below corresponds to a way that has actually happened or
was one edit away:

  PLACEHOLDERS  An extra %1$s that English does not have raises
                IllegalFormatException — a hard crash, in that language only.
                A missing one silently drops the brand name.
  BRAND STRINGS app_name in a locale file OVERRIDES the per-flavor brand,
                because a locale qualifier beats a source-set override, so a
                translated app name would replace the flavour's on every device
                in that language.
  APOSTROPHES   An unescaped ' is the single most common aapt2 failure.
  ACCENT        welcome_title_accent is highlighted by substring match inside
                welcome_title; a translator rendering them independently
                breaks the highlight with no error anywhere.
  UNTRANSLATED  A locale value byte-identical to the English source. Every
                check above passes on an English string pasted into
                values-fa/, so 26 paywall strings shipped to Iran in English
                while this script printed ALL GOOD. Structure is not meaning.

The UNTRANSLATED check has two escape hatches, both deliberately unclever:

  1. An entry in IDENTICAL_OK below — an explicit (key, locales, reason)
     triple. A fuzzy heuristic ("looks like a brand", "shorter than N") would
     silently absolve the next 26-string paste; a list that a reviewer reads
     in the diff cannot. Entries are locale-scoped on purpose: "Alipay stays
     Latin in German" must not also excuse it in Persian. This lives in the
     script rather than as a translatable="false" attribute or an XML comment
     because the exceptions would otherwise be restated in 38 files and drift
     apart, and because translatable="false" already means something else to
     aapt2 (the string must then not appear in a locale file at all).
  2. Values with no translatable content at all — nothing alphabetic left
     once format specifiers, \\uXXXX escapes, digits and punctuation are
     removed ("%1$s", "%1$d%%", "v%s"). That one is mechanical, not a
     judgement call, so it needs no list.

A stale IDENTICAL_OK entry (one that no longer matches anything) is reported
so the list stays honest instead of accumulating permanent excuses.
"""
import re
import sys
import pathlib
import xml.etree.ElementTree as ET
RES = (pathlib.Path(__file__).resolve().parents[2]
       / "android/gen/android/app/src/main/res")
BRAND = {"app_name", "main_activity_title", "app_tagline"}
PH = re.compile(r'%(?:\d+\$)?[sd]')
_PREMIUM_IS_THE_LOCAL_WORD = {
    "az", "be", "bo", "de", "el", "es", "fi", "fr", "hr", "in", "it", "kk",
    "km", "ky", "my", "nb", "nl", "pt", "sw", "tg", "tk", "tr", "uk", "uz",
    "vi",
}
IDENTICAL_OK = {
    "premium": (_PREMIUM_IS_THE_LOCAL_WORD,
                "product tier noun; the loanword is the local word"),
    "paywall_eyebrow": (_PREMIUM_IS_THE_LOCAL_WORD,
                        "'%1$s PREMIUM' = brand placeholder + that same tier "
                        "noun; must agree with `premium` above"),
    "cd_menu": ({"fr", "in", "it", "nl", "pt", "vi"},
                "'Menu' is the word these languages use"),
    "region_label": ({"az", "de", "nb"}, "'Region' is the local word"),

    "theme_auto": ({"fr", "nb", "nl"},
                   "'Auto' is the accepted UI shorthand; also the widest the "
                   "segmented control fits"),
    "version_format": ({"de", "fr"}, "'Version %s' is already correct"),
    "made_in_usa": ({"de"}, "country-of-origin mark, kept in English"),
}


def translatable(v):
    """True if the value contains anything a translator could change."""
    rest = re.sub(r'%(?:\d+\$)?[sd]|%%|\\u[0-9a-fA-F]{4}', '', v)
    return any(ch.isalpha() for ch in rest)


def strings(path):
    return {s.get("name"): "".join(s.itertext())
            for s in ET.parse(path).getroot().findall("string")}


def main():
    res = pathlib.Path(sys.argv[1]) if len(sys.argv) > 1 else RES
    en = strings(res / "values" / "strings.xml")
    expected = {k: v for k, v in en.items() if k not in BRAND}
    bad = 0
    used = set()
    for d in sorted(res.glob("values-*")):
        f = d / "strings.xml"
        if not f.exists():
            continue
        loc = d.name.replace("values-", "")
        try:
            got = strings(f)
        except ET.ParseError as e:
            print(f"  {loc:<10} XML PARSE ERROR: {e}")
            bad += 1
            continue
        problems = []
        if BRAND & set(got):
            problems.append(f"brand strings present (overrides the flavor!): "
                            f"{sorted(BRAND & set(got))}")
        missing = set(expected) - set(got)
        extra = set(got) - set(expected) - BRAND
        if missing:
            problems.append(f"missing {len(missing)}: {sorted(missing)[:4]}")
        if extra:
            problems.append(f"unknown {len(extra)}: {sorted(extra)[:4]}")
        english = []
        for k, v in got.items():
            if k not in expected:
                continue
            want, have = sorted(PH.findall(expected[k])), sorted(PH.findall(v))
            if want != have:
                problems.append(f"placeholder {k}: en={want} {loc}={have}")
            if re.search(r"(?<!\\)'", v):
                problems.append(f"unescaped apostrophe in {k}")
            if v == expected[k] and translatable(v):
                allowed = IDENTICAL_OK.get(k)
                if allowed and loc in allowed[0]:
                    used.add((k, loc))
                else:
                    english.append(k)
        if english:
            problems.append(
                f"UNTRANSLATED {len(english)} value(s) byte-identical to "
                f"English: {sorted(english)[:6]}"
                + (" …" if len(english) > 6 else ""))
        t, a = got.get("welcome_title"), got.get("welcome_title_accent")
        if t and a and a not in t:
            problems.append("welcome_title_accent is not a substring of "
                            "welcome_title (highlight will not render)")
        if problems:
            bad += 1
            print(f"  {loc:<10} FAIL")
            for p in problems[:6]:
                print(f"             - {p}")
        else:
            print(f"  {loc:<10} ok  ({len(got)} strings)")
    stale = sorted((k, loc) for k, (locs, _) in IDENTICAL_OK.items()
                   for loc in locs if (k, loc) not in used)
    if stale:
        print("\n  note: IDENTICAL_OK entries that no longer match anything "
              "(the string was translated, or the key/locale is gone) — "
              "delete them so the list stays honest:")
        for k, loc in stale:
            print(f"             - {k} / {loc}")
    print(f"\n{'ALL GOOD' if not bad else f'{bad} locale(s) with problems'}")
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
