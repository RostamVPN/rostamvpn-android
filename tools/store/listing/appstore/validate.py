#!/usr/bin/env python3
"""Validate the proposed App Store listing copy before anyone uploads it.

    python3 tools/store/listing/appstore/validate.py

Sibling of `tools/store/listing/validate.py`, which does the same job for Play.
The §0 infrastructure rules, the server-count patterns and the
contradicts-the-app claims are IDENTICAL across both stores and are imported
from that file rather than copied, so a leak pattern learned on one store is
caught on the other the same day.

What differs between the two stores, and why this file exists at all:

  FIELDS      Apple has six, Play has three, and none of the limits match.
              name 30, subtitle 30, promotional text 170, keywords 100,
              description 4000, what's new 4000.

  KEYWORDS    Apple has a dedicated 100-character comma-separated field that
              Play does not have. Rules that only make sense here:
                - no spaces after the commas (a space costs a character and
                  Apple splits on the comma alone)
                - never repeat a word already in the name or the subtitle;
                  Apple indexes all three together and a duplicate is a
                  wasted character. RedVPN's live field spends 3 characters
                  re-listing "vpn", which is already in its own app name.
                - singular only; Apple stems.
                - no competitor names. Guideline 2.3.7 forbids using another
                  app's trademark in metadata, and it is a common rejection.

  PRICE       Play's Metadata policy PROHIBITS price text in the listing.
              Apple's guideline 3.1.2 REQUIRES it: an auto-renewing
              subscription must disclose title, length and price in the
              metadata as well as in the binary. So the Play validator's
              PRICE_TEXT check is deliberately NOT applied here, and a
              subscription block is required instead.

  PROFANITY   Play errors on it. Apple's guideline **2.3.8** says metadata
              "should be appropriate for all audiences, so make sure your app
              and in-app purchase icons, screenshots, and previews adhere to a
              4+ age rating even if your app is rated higher" (verified
              verbatim 2026-09-09 against the guidelines page, last updated
              June 8 2026). RedVPN's own brand line ("No bullshit, just
              amazing VPN") is therefore a real 2.3.8 risk in a description
              even though the app itself is rated 17+/18+. That is the
              owner's call, not this script's, so it warns and cites the rule
              rather than failing the file. See README.

  URLS        Apple validates the support URL at submission and a dead
              privacy policy URL is a 5.1.1 rejection on its own. Any http(s)
              URL in the copy is checked for reachability with --urls.

Layout is `appstore/<brand>/<locale>.md`, locales named the way App Store
Connect names them (en-US, ar-SA, ru, tr, fr-FR, es-MX, de-DE) — NOT the way
Play names them. Uploading `ru-RU` to Apple is an error; the locale is `ru`.
"""
import pathlib
import re
import sys

HERE = pathlib.Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent))
from validate import (  # noqa: E402  the shared, store-independent rules
    BANNED,
    BANNED_ALLOW,
    COUNTS,
    FALSE_CLAIMS,
    JURISDICTION,
    PROFANITY,
    TARGETING,
    check_product_facts,
)

# `check_product_facts` is imported rather than reimplemented so a product fact
# learned on one store is enforced on the other the same day. It is scoped to
# files that raise the subject, which matters here more than on Play: RedVPN on
# the App Store is TRIAL-gated and has no free allowance at all (the binary
# attaching to the 4.1.1 slot is the legacy tree, which carries no quota and no
# throttle), so its files must not be made to claim one. RostamVPN on iOS does
# have both — `UsageStatsView.overQuota` and `ConfigPolicy.freeKbps` — so its
# files must say both. One check, two correct answers, decided by the copy.

# ⚠️ `fields()` is deliberately NOT imported from the Play validator, even
# though the file format is identical. That function anchors its regex on the
# Play `LIMITS` names ("Title", "Short description", "Full description") so
# that prose headings in the notes are not mistaken for fields. It is the
# right fix there, but it makes the function silently depend on the caller's
# store: importing it here made every App Store field parse as missing, since
# none of Apple's six field names appear in Play's three. Same idea, own copy.


def fields(text, names=None):
    """Pull the fenced block under each '## <Field>' heading.

    Only headings that name a REAL App Store field are matched, so the notes
    above and below the copy can carry as many `##` headings as they like.
    """
    names = names or list(LIMITS) + ["Support URL", "Marketing URL"]
    alt = "|".join(re.escape(k) for k in names)
    out = {}
    for m in re.finditer(rf"^## ({alt})\s*\n(.*?)^```\n(.*?)^```",
                         text, re.S | re.M):
        out[m.group(1).strip()] = m.group(3).rstrip("\n")
    return out

# App Store Connect field limits. Apple truncates silently in some fields and
# rejects the upload in others; neither is a thing to discover at submission.
LIMITS = {
    "Name": 30,
    "Subtitle": 30,
    "Promotional text": 170,
    "Keywords": 100,
    "Description": 4000,
    "What's New": 4000,
}
# Fields Apple indexes for search. A word here is worth a character; a word
# repeated across two of them is worth nothing.
INDEXED = ("Name", "Subtitle", "Keywords")

# Locales App Store Connect actually offers. Persian is NOT among them, which
# is why no fa.md exists in this tree: Apple runs no Iranian storefront and
# has no Farsi App Store localization, so Persian reaches its readers only
# inside another locale's description or through the Play listing.
ASC_LOCALES = {
    "ar-SA", "ca", "cs", "da", "de-DE", "el", "en-AU", "en-CA", "en-GB",
    "en-US", "es-ES", "es-MX", "fi", "fr-CA", "fr-FR", "he", "hi", "hr", "hu",
    "id", "it", "ja", "ko", "ms", "nl-NL", "no", "pl", "pt-BR", "pt-PT", "ro",
    "ru", "sk", "sv", "th", "tr", "uk", "vi", "zh-Hans", "zh-Hant",
}

# Guideline 3.1.2: an auto-renewing subscription must disclose, in the App
# Store metadata as well as in the app, the title, the length and the price of
# the subscription, plus functional Terms of Use and Privacy Policy links.
# Missing this is the cheapest possible rejection to prevent and one of the
# most common to hit. Checked only on files that mention a subscription.
#
# ⚠️ Every pattern here must match in EVERY language the copy ships in, or the
# check fires on correct Arabic and Turkish text and trains people to ignore
# it. Three traps this file has already hit:
#   - decimal separators. "3,99" in ru/tr/fr/es, "3.99" in en, and Arabic
#     "٣٫٩٩" in Arabic-Indic digits with U+066B as the separator.
#   - "auto-renew" is one hyphenated word in English and a participle in
#     Turkish ("kendini otomatik yenileyen") and Russian ("автопродлением").
#   - Russian declines, so match the stem: "отмен", never "отмена".
#   - German compounds and umlauts: "kündigen", "Verlängerung".
#   - Arabic hamza changes shape when the word takes a suffix: "إلغاء"
#     (cancellation) becomes "إلغاؤه" (cancelling it). Match "إلغا".
SUB_REQUIRED = [
    (r"auto[- ]?renew|otomatik\s+yenile|автопродлен|自动续订|"
     r"يُجدَّد\s+تلقائي|يجدد\s+تلقائي|renouvellement\s+automatique|"
     r"renovación\s+automática|se\s+renueva\s+automáticamente|"
     r"renouvelle\s+automatiquement|automatische[rnm]?\s+Verlängerung|"
     r"verlängert\s+sich\s+automatisch",
     "must say the subscription auto-renews"),
    (r"\b(?:month|year|week)\w*\b|ayl[ıi]k|y[ıi]ll[ıi]k|месяц|год|года|"
     r"شهري|سنوي|mensuel|annuel|mensual|anual|"
     r"[Mm]onatlich|[Jj]ährlich|\bMonat\b|\bJahr\b",
     "must state the subscription length"),
    # Latin decimal point or comma, or Arabic-Indic digits with U+066B.
    (r"[$€£]\s?\d|\d+[.,]\d{2}|[٠-٩]+[٫.,][٠-٩]{2}",
     "must state the price"),
    (r"apple\.com/legal/internet-services/itunes/dev/stdeula|terms of use|"
     r"conditions|kullanım koşulları|условия использования|شروط الاستخدام|"
     r"términos", "must link Terms of Use (EULA)"),
    (r"privacy|gizlilik|конфиденциальност|خصوصية|confidentialité|privacidad",
     "must link the Privacy Policy"),
    (r"cancel|iptal|отмен|إلغا|annul|cancelar|kündig",
     "must say how to cancel"),
]
SUB_MENTIONED = re.compile(
    r"subscription|abonelik|подписк|اشتراك|abonnement|suscripción", re.I)

# Guideline 2.3.7 — third-party marks in metadata. These are the ones a VPN
# listing reaches for without thinking.
TRADEMARKS = [
    r"\bnetflix\b", r"\bhulu\b", r"\bdisney\+?\b", r"\bbbc iplayer\b",
    r"\bhotspot shield\b", r"\bnordvpn\b", r"\bexpressvpn\b", r"\bsurfshark\b",
    r"\bproton ?vpn\b", r"\btunnelbear\b", r"\bwindscribe\b",
    r"\bwhatsapp\b", r"\btelegram\b", r"\binstagram\b", r"\byoutube\b",
    r"\btiktok\b", r"\bfacebook\b",
]

URL_RE = re.compile(r"https?://[^\s<>\)\]\"']+")


def check(path, brand, check_urls=False):
    errs, warns = [], []
    text = path.read_text(encoding="utf-8")
    got = fields(text)

    locale = path.stem
    if locale not in ASC_LOCALES:
        errs.append(f"{locale!r} is not an App Store Connect locale "
                    f"(Play names differ; Apple uses e.g. 'ru' not 'ru-RU')")

    for name, limit in LIMITS.items():
        if name not in got:
            # What's New only applies to an update, so it is optional.
            if name != "What's New":
                errs.append(f"missing field: {name}")
            continue
        n = len(got[name])
        if n > limit:
            errs.append(f"{name}: {n} chars, limit {limit} (over by {n - limit})")

    # Keyword field mechanics.
    kw = got.get("Keywords", "")
    if kw:
        if ", " in kw:
            errs.append("Keywords: spaces after commas waste characters — "
                        "Apple splits on the comma alone")
        # 🚨 Apple documents this field as "up to 100 bytes" on the platform
        # version information page and "limited to 100 characters" on the
        # Creating Your Product Page article. Those are not the same budget:
        # Arabic and Cyrillic cost 2 bytes per character in UTF-8, so a
        # 90-character Russian field is 170 bytes. A silently truncated
        # keyword field cannot be detected after the fact, so keep under both.
        #
        # NAME and SUBTITLE are CHARACTER-limited, verified empirically rather
        # than from the docs: NordVPN's live Arabic name on the UAE storefront
        # is "NordVPN: VPN سريع وغير محدود", 28 characters and 41 bytes
        # (itunes.apple.com/lookup?id=905953485&country=ae&lang=ar_SA,
        # 2026-09-09). A byte limit would make that impossible. So only the
        # keyword field gets the belt-and-braces treatment.
        nbytes = len(kw.encode("utf-8"))
        if nbytes > LIMITS["Keywords"]:
            warns.append(
                f"Keywords: {nbytes} bytes ({len(kw)} chars). Under Apple's "
                "'100 characters' wording this is fine, under its '100 bytes' "
                "wording it is truncated. Trim to be safe under both")
        surface = (got.get("Name", "") + " " + got.get("Subtitle", "")).lower()
        surface_words = set(re.findall(r"[\w؀-ۿЀ-ӿ]+", surface))
        for k in [x.strip().lower() for x in kw.split(",") if x.strip()]:
            for word in k.split():
                if word in surface_words:
                    warns.append(
                        f"Keywords: {k!r} repeats {word!r} from the name or "
                        "subtitle — Apple indexes all three together")

    body = "\n".join(got.values())
    low = body.lower()

    for pat in BANNED:
        for m in re.finditer(pat, low, re.I):
            if BANNED_ALLOW.search(m.group(0)):
                continue
            errs.append(f"§0 infrastructure leak: {m.group(0)!r}")
    for pat in COUNTS:
        for m in re.finditer(pat, body, re.I):
            errs.append(f"§0 server count: {m.group(0)!r}")
    for pat, why in FALSE_CLAIMS:
        for m in re.finditer(pat, body, re.I):
            errs.append(f"contradicts the app: {m.group(0)!r} — {why}")
    for pat in JURISDICTION:
        for m in re.finditer(pat, body, re.I):
            errs.append(f"names a jurisdiction: {m.group(0)!r} "
                        "— makes the listing that country's problem")
    for pat in TRADEMARKS:
        for m in re.finditer(pat, body, re.I):
            errs.append(f"guideline 2.3.7, third-party mark: {m.group(0)!r}")

    desc = got.get("Description", "")
    # 🚨 The brand argument is LOAD-BEARING and was missing until 2026-09-21.
    # Without it every App Store file was checked against the DEFAULT
    # (monthly-allowance) rules, so DollarVPN — whose allowance is 1 GB ONCE
    # with a hard stop — was being told to claim a monthly refill and a slower
    # free tier, which are both the opposite of its product. The Play validator
    # has passed `brand` since the DollarVPN rules were written; this call did
    # not, and a rule that fires on correct copy is a rule people learn to
    # ignore.
    errs += check_product_facts(desc, brand)
    if SUB_MENTIONED.search(desc):
        for pat, why in SUB_REQUIRED:
            if not re.search(pat, desc, re.I):
                errs.append(f"guideline 3.1.2: {why}")

    for pat in PROFANITY:
        for m in re.finditer(pat, body, re.I):
            warns.append(
                f"guideline 2.3.8, metadata must suit a 4+ rating even when "
                f"the app is rated higher: {m.group(0)!r} — owner's call, "
                "see README")
    for pat in TARGETING:
        for m in re.finditer(pat, body, re.I):
            warns.append(f"targeting phrase, prefer a plain category noun: "
                         f"{m.group(0)!r}")

    if check_urls:
        import urllib.error
        import urllib.request
        for u in sorted(set(URL_RE.findall(body))):
            req = urllib.request.Request(u, method="HEAD",
                                         headers={"User-Agent": "Mozilla/5.0"})
            try:
                with urllib.request.urlopen(req, timeout=15) as r:
                    if r.status >= 400:
                        errs.append(f"dead URL ({r.status}): {u}")
            except urllib.error.HTTPError as e:
                errs.append(f"dead URL ({e.code}): {u} — a dead privacy or "
                            "support URL is a 5.1.1 rejection on its own")
            except Exception as e:                     # noqa: BLE001
                warns.append(f"URL unreachable from here: {u} ({e})")

    return errs, warns


def main():
    check_urls = "--urls" in sys.argv
    files = sorted(HERE.glob("*/*.md"))
    if not files:
        print("no listing files under appstore/<brand>/", file=sys.stderr)
        return 1
    bad, brand = 0, None
    for f in files:
        if f.parent.name != brand:
            brand = f.parent.name
            print(f"\n── {brand} " + "─" * (52 - len(brand)))
        errs, warns = check(f, brand, check_urls)
        got = fields(f.read_text(encoding="utf-8"))
        sizes = " ".join(
            f"{k.split()[0].lower().strip(chr(39))}={len(v)}" for k, v in got.items())
        print(f"{'FAIL' if errs else 'ok  '} {f.name:<10} {sizes}")
        for e in errs:
            print(f"       ERROR {e}")
        for w in warns:
            print(f"       warn  {w}")
        bad += bool(errs)
    print(f"\n{len(files)} listings, {bad} failing")
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
