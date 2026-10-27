#!/usr/bin/env python3
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
                because a locale qualifier beats a source-set override. That
                shipped RedVPN displaying "RostamVPN" to every Persian,
                Arabic, Russian, Turkish and Chinese device.
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

RES = pathlib.Path(__file__).resolve().parents[2] / \
    "android/gen/android/app/src/main/res"
# app_tagline joined 2026-10-12: rostam's translated tagline sat in every
# main/res/values-* file, so all four other brands showed "Your Digital
# Guardian" in every language. Translations of a brand string live in that
# brand's own source set (src/rostam/res/values-*/brand_strings.xml).
BRAND = {"app_name", "main_activity_title", "app_tagline"}
PH = re.compile(r'%(?:\d+\$)?[sd]')

# Locales where "Premium" is the word the language itself uses for the tier.
# Everywhere else the app already translates it (fa نسخه ویژه, ru Премиум,
# zh-rCN 高级会员, ko 프리미엄, ar النسخة المميزة, hi प्रीमियम, th พรีเมียม, iw פרימיום,
# am/ti ፕሪሚየም, ckb پرێمیۆم, azb اؤزل نۆسخه) and an English "Premium" there is a bug.
_PREMIUM_IS_THE_LOCAL_WORD = {
    "az", "be", "bo", "de", "el", "es", "fi", "fr", "hr", "in", "it", "kk",
    "km", "ky", "my", "nb", "nl", "pt", "sw", "tg", "tk", "tr", "uk", "uz",
    "vi",
}
# Locales that write the two Chinese wallet brands in Latin. The ones missing
# from this set transliterate, because a Latin run inside their script reads as
# an untranslated string: fa/fa-rIR الی‌پی, ar علي باي, ckb ئەلی‌پەی, azb, ko 알리페이,
# th อาลีเพย์, zh-rCN 支付宝 (the actual name).
_LATIN_WALLET_BRANDS = {
    "am", "az", "be", "bo", "de", "el", "es", "fi", "fr", "hi", "hr", "in",
    "it", "iw", "kk", "km", "ky", "my", "nb", "nl", "pt", "ru", "sw", "tg",
    "ti", "tk", "tr", "uk", "uz", "vi",
}

# Every locale with a values-*/strings.xml.
_ALL_LOCALES = {
    "am", "ar", "az", "b+azb", "be", "bo", "ckb", "de", "el", "es", "fa",
    "fa-rIR", "fi", "fr", "hi", "hr", "in", "it", "iw", "kk", "km", "ko",
    "ky", "my", "nb", "nl", "pt", "ru", "sw", "tg", "th", "ti", "tk", "tr",
    "uk", "uz", "vi", "zh-rCN",
}

# key -> (locales it may equal English in, why)
IDENTICAL_OK = {
    "rs_ask_port": ({"fr", "hr", "in"},
                    "'port %1$s': 'port' is the native word in French, Croatian and Indonesian"),
    "premium": (_PREMIUM_IS_THE_LOCAL_WORD,
                "product tier noun; the loanword is the local word"),
    "paywall_eyebrow": (_PREMIUM_IS_THE_LOCAL_WORD,
                        "'%1$s PREMIUM' = brand placeholder + that same tier "
                        "noun; must agree with `premium` above"),
    "tabs_pro": (_PREMIUM_IS_THE_LOCAL_WORD,
                 "plan name (Internet Pro tabs); kept Latin exactly where "
                 "`premium` is, transliterated everywhere else (ru Про, "
                 "fa/azb پرو, ckb پرۆ, ar برو, iw פרו, hi प्रो, th โปร, ko 프로, "
                 "zh-rCN 专业版, am/ti ፕሮ)"),
    "pay_alipay": (_LATIN_WALLET_BRANDS, "brand name, written in Latin here"),
    "pay_wechat": (_LATIN_WALLET_BRANDS, "brand name, written in Latin here"),
    "cd_menu": ({"fr", "in", "it", "nl", "pt", "vi"},
                "'Menu' is the word these languages use"),
    "region_label": ({"az", "de", "nb"}, "'Region' is the local word"),
    "account_plan": ({"az", "es", "tr"}, "'Plan' is the local word"),
    "account": ({"it", "nl"}, "'Account' is the local word"),
    "theme_auto": ({"fr", "nb", "nl"},
                   "'Auto' is the accepted UI shorthand; also the widest the "
                   "segmented control fits"),
    "version_format": ({"de", "fr"}, "'Version %s' is already correct"),
    "made_in_usa": ({"de"}, "country-of-origin mark, kept in English"),
    # Owner decision 2026-10-15: the travel-data product is "eSIM", not
    # "Pass". eSIM stays Latin in every locale's esim_* strings, so the bare
    # tab label is the same word everywhere.
    "tabs_passes": (_ALL_LOCALES,
                    "bottom tab label 'eSIM'; the technical term every "
                    "locale's esim_* strings already keep in Latin"),
    "tabs_passes_title": ({"de", "pt"},
                          "'eSIMs' is the plural these languages already use "
                          "(de esim_mine 'Meine eSIMs', pt 'Meus eSIMs')"),
    "tabs_sort_per_gb": ({"fi", "in", "it", "nb", "nl"},
                         "'Per GB' is how these languages write it: Latin 'per' "
                         "and the unit GB, as in their stores' own sort labels"),
    "tabs_number_line": ({"es", "fi", "fr", "hr", "it", "nb", "pt", "tk"},
                         "'%1$s · %2$d min · %3$d SMS': 'min' and 'SMS' are "
                         "the units these locales write (same 'min' as their "
                         "tabs_number_row)"),
    # RedShield (redshield_strings.xml), from the iOS catalogue: words these
    # languages share with English, checked against iOS's own translations.
    "rs_class_cdn": (_ALL_LOCALES, "network class acronym, Latin everywhere"),
    "rs_class_cloud": ({"de", "fr", "in", "it", "nl"}, "'Cloud' is the local word"),
    "rs_class_hosting": ({"de", "es", "fi", "hr", "in", "it", "nb", "nl", "tk", "uz", "vi"},
                         "'Hosting' is the local word"),
    "rs_name": ({"de"}, "'Name' is German"),
    "rs_cat_malware": ({"de", "es", "fr", "in", "it", "nl", "pt"}, "'Malware' is the local word"),
    "rs_cat_phishing": ({"de", "es", "hr", "in", "it", "nl", "pt"}, "'Phishing' is the local word"),
    "rs_cat_scanner": ({"de", "fr", "it", "nl", "pt"}, "'Scanner' is the local word"),
    "rs_cat_proxy": ({"nl"}, "'Open proxy' is Dutch"),
    "rs_destinations": ({"fr"}, "French spelling is identical"),
    "rs_rare": ({"fr"}, "French spelling is identical"),
    "rs_summary_destinations": ({"fr"}, "French spelling is identical"),
    "rs_profile_sites": ({"fr", "nl", "pt"}, "'%1$s sites' is correct here"),
    "rs_send": ({"nb"}, "'Send' is Norwegian"),
}


def translatable(v):
    """True if the value contains anything a translator could change."""
    rest = re.sub(r'%(?:\d+\$)?[sd]|%%|\\u[0-9a-fA-F]{4}', '', v)
    return any(ch.isalpha() for ch in rest)


# Files checked per locale, merged. A feature may keep its strings in its own
# file (redshield_strings.xml) so its keys move together; the rules are the same.
FILES = ("strings.xml", "redshield_strings.xml")


def strings(path):
    """One file, or the merge of FILES when given a values* directory."""
    if path.is_dir():
        out = {}
        for name in FILES:
            if (path / name).exists():
                out.update(strings(path / name))
        return out
    return {s.get("name"): "".join(s.itertext())
            for s in ET.parse(path).getroot().findall("string")}


def main():
    # A second res root can be validated by passing it as argv[1]. That is how
    # an OVERRIDE source set gets covered: src/noEsim/res carries the four
    # strings that named the eSIM store's domain, reworded, in all 39
    # qualifiers, and without this it would be validated by nothing at all.
    #
    # --identical-ok turns the UNTRANSLATED check off for that run ONLY.
    # Those four strings are deliberately the same English in every locale:
    # they are unreachable when features.esim is false (the dialog is behind
    # the FEATURE_ESIM gate and the shop screens behind openEsim()), so
    # inventing 38 translations of dead copy would be worse than not. The flag
    # exists instead of IDENTICAL_OK entries because IDENTICAL_OK is global:
    # excusing `shop_delivered` there would also excuse an English paste into
    # main/res/values-fa, where that string IS live and IS translated. The
    # placeholder, apostrophe and key-parity checks still run, which is the
    # point -- shop_payment_safe carries a %1$s and a literal apostrophe.
    args = [a for a in sys.argv[1:] if not a.startswith("-")]
    identical_ok = "--identical-ok" in sys.argv[1:]
    res = pathlib.Path(args[0]) if args else RES
    en = strings(res / "values")
    expected = {k: v for k, v in en.items() if k not in BRAND}

    bad = 0
    used = set()          # IDENTICAL_OK entries actually exercised
    for d in sorted(res.glob("values-*")):
        f = d / "strings.xml"
        if not f.exists():
            continue
        loc = d.name.replace("values-", "")
        try:
            got = strings(d)
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
            if v == expected[k] and translatable(v) and not identical_ok:
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

    # Only meaningful for the root the list was written against.
    stale = [] if identical_ok else sorted(
        (k, loc) for k, (locs, _) in IDENTICAL_OK.items()
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
