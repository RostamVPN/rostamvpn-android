#!/usr/bin/env python3
"""Validate the proposed Play store listing copy before anyone uploads it.

    python3 tools/store/listing/validate.py

Every check here exists because a LIVE listing failed it. This is the
store-text equivalent of tools/i18n/validate-translations.py: the failures it
catches do not break a build, they get an app with 691K installs removed, or
hand a censor a target.

Layout is `play/<brand>/<locale>.md`. Every brand is checked, because RedVPN's
live listing fails on a different axis from RostamVPN's and a brand nobody
looks at is exactly where a stale claim survives.

Checks
  1. Play field limits (title 30, short 80, full 4000). Play silently rejects
     an over-length field at upload; better to know here.
  2. CLAUDE.md §0 — no provider, protocol, endpoint, IP or server count. The
     live RostamVPN listing said "Direct (UDP)" in a screenshot and "multiple
     transport methods" in five languages; the live RedVPN listing says
     "hundreds of servers" in five.
  3. Claims that contradict the shipped app. RostamVPN promised "does not show
     ads. Ever." in six languages while shipping an interstitial, and "no
     subscriptions" while selling p1m/p1a. RedVPN claims "post-quantum
     encryption" and a "strict no-logs policy".

     This is Play's **Deceptive Behavior / Misleading Claims** policy, not
     Misrepresentation — verified 2026-09-09 against
     support.google.com/googleplay/android-developer/answer/9888077:
     "We don't allow apps that contain false or misleading information or
     claims, including in the description, title, icon, and screenshots."
     Misrepresentation (answer/9888689) is about identity and ownership, which
     is a different problem we do not have. It is also a Data Safety mismatch
     at the same time, which is a second, independent enforcement surface.

     RULE, and it holds for every brand including the ones with no AdMob id:
     no listing may ever claim the ABSENCE of ads or of subscriptions. Every
     brand config carries ads.enabled=true and rostam_adtech.enabled=true, so
     a brand with no AdMob unit still serves the cashier card.
  4. Keyword stuffing. Play's Metadata policy prohibits "repetitive or
     unrelated keywords"; the live RostamVPN listing ended five locales with
     a bare comma list.
  5. Naming a jurisdiction. RedVPN's Arabic listing is titled "VPN Super Proxy
     for UAE" and its short description promises to get past blocking in the
     UAE by name. Naming a country in store text is how a listing becomes that
     country's problem.
  6. PRODUCT FACTS — a claim that was true of an older build. This class was
     added 2026-09-10 after it escaped every other check.

     Twenty-one files in this directory, across four brands and eleven
     languages, said the free allowance "is given once, it is not monthly, and
     it does not expire". The allowance renews MONTHLY. All three clauses were
     false, and a renewing allowance is by definition one that does not carry
     over, so "does not expire" was false twice.

     Nothing above could see it. The sentence names no provider, no protocol
     and no server count; it claims no absence of ads or subscriptions; it is
     prose rather than a keyword list. It read as careful, specific,
     honest copy. It was simply out of date, and a §0 validator has no notion
     of a fact going stale.

     So this check is the mirror image of the others: instead of a list of
     things that must never appear, it is a list of things that must appear
     TOGETHER once a subject is raised. Two rules today, both scoped to files
     that actually mention the free allowance so the brands and locales that
     have no allowance (RedVPN on the App Store is trial-gated) do not fire:

       a. if you mention the allowance you must say it RENEWS MONTHLY
       b. if you mention the allowance you must say running out drops you to a
          LOWER SPEED, not that you are cut off

     Rule (b) is here because omitting it is the commercially expensive half.
     "When it is used up, Premium lifts the limit" is not false, but a reader
     takes it to mean a wall, and the product does not have one.

     ⚠️ When a product fact changes, ADD THE PATTERN HERE IN THE SAME CHANGE.
     A fact that is true today and unenforced is this class waiting to recur.
"""
import pathlib
import re
import sys

HERE = pathlib.Path(__file__).resolve().parent
LIMITS = {"Title": 30, "Short description": 80, "Full description": 4000}

# Published contact per brand. A listing with no way to reach the developer
# converts worse and reads as abandoned to a reviewer.
BRAND_SITE = {
    "rostam": "rostam.app",
    "redvpn": "vpn.red",
    "dollarvpn": "dollarvpn.app",
    "internetpro": "cashier.example.com",
    "goglobe": "goglobe.app",
}

# §0. Mechanism and infrastructure vocabulary. Category nouns (vpn, proxy,
# فیلترشکن) are deliberately NOT here — see README.md "What can be said".
BANNED = [
    # transports and protocols
    r"\bwireguard\b", r"\bopenvpn\b", r"\bikev2\b", r"\bl2tp\b", r"\bipsec\b",
    r"\bshadowsocks\b", r"\bv2ray\b", r"\bvmess\b", r"\bvless\b", r"\btrojan\b",
    r"\bxray\b", r"\breality\b", r"\bamnezia\b", r"\bnoise\b", r"\bsocks5?\b",
    r"\bdnstt\b", r"\bwgtcp\b", r"\bredsocket\b", r"\bobfusc", r"\bhysteria\b",
    r"\bquic\b", r"\btuic\b", r"\bnaive\b", r"\bmtproto\b",
    r"\((?:udp|tcp)\)", r"\b(?:udp|tcp)\b", r"\bhttp[- ]upgrade\b",
    r"\bdomain fronting\b", r"\bport 443\b",
    # providers and infrastructure
    r"\bcloudfront\b", r"\bcloudflare\b", r"\bamazon\b", r"\baws\b",
    r"\bdigitalocean\b", r"\bvultr\b", r"\blinode\b", r"\bhetzner\b",
    r"\bovh\b", r"\bixroute\b", r"\bgcore\b", r"\bakamai\b", r"\bfastly\b",
    # endpoints
    r"\b\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}\b",          # IPv4 literal
    r"\b[a-z0-9-]+\.(?:net|io|workers\.dev)\b/?",        # infra hostnames
]
# rostam.app and feedback@rostam.app are the published contact details.
BANNED_ALLOW = re.compile(r"rostam\.app|internetpro\.io", re.I)

# Server counts, in every script the listing ships.
COUNTS = [
    r"\b\d[\d,\. ]*\+?\s*(?:servers?|serveur|servidores|sunucu|серверов|сервера|خوادم|سرور)",
    r"\b(?:hundreds|thousands|dozens)\s+of\s+servers\b",
    r"(?:数百|数千|上百|数十)\s*(?:台)?\s*服务器",
    r"(?:صدها|هزاران)\s*سرور",
    # Russian and Belarusian DECLINE, so match the stem not the nominative.
    # The live RedVPN ru listing says "сотням серверов" (dative) and slipped
    # past a `сотни|тысячи` pattern that only knew the dictionary form.
    r"(?:сотн|тысяч|дзесятк|сотняў)\w*\s+сервер\w*",
]

# Claims the shipped app contradicts. Grounded in the code, not in taste:
#   ads      → AdMobInterstitial.kt + 10 mediation adapters in build.gradle.kts
#   subs     → brands/rostam.json subProductIds p1m / p1a
#   no-logs  → the app reports cumulative used_mb and sends a GAID to the
#              ad exchange; an absolute "we never track you" is unsupportable.
FALSE_CLAIMS = [
    (r"(?:does not|doesn't|no)\s+show\s+ads|no ads at all|ad[- ]free forever",
     "app ships AdMob + mediation; it shows an interstitial on connect"),
    (r"تبلیغ(?:ی)?\s+نشان\s+نمی[‌ ]?ده", "same, fa"),
    (r"لا\s+نعرض\s+إعلانات", "same, ar"),
    (r"не\s+показыва\w+\s+рекламу", "same, ru"),
    (r"reklam\s+göstermez", "same, tr"),
    (r"不展示广告|不显示广告", "same, zh"),
    (r"\bno subscriptions?\b", "app sells p1m / p1a through Play Billing"),
    (r"بدون\s+اشتراک(?:‌ها)?\b", "same, fa"),
    (r"بدون\s+اشتراكات", "same, ar"),
    (r"без\s+подписок", "same, ru"),
    (r"[Aa]bonelik\s+yok", "same, tr"),
    (r"无订阅", "same, zh"),
    (r"\b(?:zero|no)[- ]logs?\b|strict no[- ]log",
     "unverifiable absolute; say what is never asked for instead"),
    (r"无日志", "same, zh"),
    (r"sin registros|kayıt tutmaz|без журналов|بدون سجلات",
     "same no-logs absolute, es/tr/ru/ar"),
    # Same declension trap: Belarusian is "постквантавым", not "постквантовый".
    (r"post[- ]quantum|постквант\w*|كمي\b|量子|cuántic|kuantum",
     "not what the app does — live on RedVPN in five locales"),
    # DollarVPN, InternetPro and GoGlobe carry no AdMob unit, which is NOT the
    # same as carrying no ads: the cashier ad card is a WebView, needs no AdMob
    # unit id, and `ConfigManager.kt:663` treats a config with no `ads` block as
    # "adtech for everyone — never silently ad-free".
    #
    # ⚠️ 2026-09-21: `brands/configs/dollarvpn.json` now genuinely sets
    # ads.enabled=false, and the cashier has ads_mode=off on all four
    # dollarvpn RegionOffering rows. The rule STILL HOLDS, because the brand
    # config cannot reach a device: `publish-config` on cashier-01 has 0
    # occurrences of `client_config:brand` (re-verified today, binary dated
    # Aug 27), so nothing is ever written to the per-brand slot and a
    # DollarVPN install falls back to the SHARED config — which carries
    # ads.enabled=true and rostam_adtech.enabled=true. Claiming no ads today
    # would be the §22 failure again. Revisit when the publisher is rebuilt.
    (r"\bad[- ]free\b(?!\s+(?:premium|plan|subscription))",
     "every brand serves the cashier ad card even with no AdMob unit"),
    (r"tanpa iklan|बिना विज्ञापन|không quảng cáo|sem an[úu]ncios|sin anuncios",
     "same, id/hi/vi/pt/es"),
    # A FREE TRIAL IS PER APPLE ID PER SUBSCRIPTION GROUP, NOT PER INSTALL.
    # "Every install starts with a 7-day free trial" was in all six RedVPN App
    # Store locales. Delete and reinstall does not restore eligibility, so a
    # returning user reads that line, taps, and is charged immediately. The
    # legacy binary already does this in-app with a hardcoded headline, which
    # appstore/README.md records as a refund and one-star driver.
    # ⚠️ Must require the words "free trial", not merely "free". The free
    # ALLOWANCE genuinely is per install (the cid is regenerated on reinstall),
    # so "Every install starts free, at full speed" is true and correct copy.
    # A looser pattern flagged both RostamVPN files on that sentence, which is
    # exactly the kind of false positive that trains people to ignore a check.
    (r"(?:every|each)\s+install\s+\w+[^.]{0,60}free\s+trial",
     "a free trial is per Apple ID per subscription group, NOT per install — "
     "say 'new subscribers' or 'if you have not used one before'"),
    (r"كل\s*تثبيت\s*يبدأ\s*بتجربة", "same, ar"),
    (r"[CcĆ]ada\s*instalaci[óo]n\s*empieza\s*con[^.]*prueba", "same, es"),
    (r"[Cc]haque\s*installation\s*commence[^.]*essai", "same, fr"),
    (r"[Кк]аждая\s*установка\s*начинается\s*с\s*\d+\s*дн", "same, ru"),
    (r"[Hh]er\s*kurulum\s*\d+\s*g[üu]n\s*[üu]cretsiz", "same, tr"),
]

# ─────────────────────────────────────────────────────────────────────────────
# THE FREE ALLOWANCE RENEWS MONTHLY — **ON SOME BRANDS ONLY**, and that is why
# this list is no longer inside FALSE_CLAIMS.
#
# Until 2026-09-21 it was unconditional, because every brand's allowance really
# did refill every 30 days. DollarVPN's no longer does. Verified that day in
# the LIVE cashier database, not inferred from a config file:
#
#     dollarvpn                    free_quota_mb=1024  free_period_days=0
#     rostam/redvpn/internetpro    free_quota_mb=2048  free_period_days=30
#
# `quota.py:_period_for()` reads `free_period_days` and documents 0 as
# "None = never (one-time)". So for DollarVPN the sentences below are TRUE, and
# leaving the rule unconditional would have forced its listing to state the
# opposite of the product in every language — the exact failure check 6 exists
# to prevent, arriving from the other direction.
#
# These brands' allowance still renews monthly, so the rule still binds them.
MONTHLY_ALLOWANCE_BRANDS = {"rostam", "redvpn", "internetpro", "goglobe"}

MONTHLY_ALLOWANCE_CLAIMS = [
    (r"(?:given|allowance is given)\s+one\s+time|\bit is given once\b|"
     r"\bnot monthly\b|\bdoes not expire\b|\bdoesn't expire\b",
     "this brand's free allowance RENEWS MONTHLY — it is not one-time and it "
     "does not carry over"),
    (r"ماهانه\s*نیست|تاریخ\s*انقضا\s*ندارد", "same, fa"),
    (r"ليست\s*شهرية|لا\s*تنتهي\s*صلاحيتها|تُمنح\s*مرة\s*واحدة", "same, ar"),
    (r"не\s*месячный|не\s*сгорает|да[ёе]тся\s*один\s*раз", "same, ru"),
    (r"не\s*штомесячны|не\s*згарае|даецца\s*адзін\s*раз", "same, be"),
    (r"ayl[ıi]k\s*de[ğg]ildir|s[üu]resi\s*dolmaz|bir\s*kez\s*verilir", "same, tr"),
    (r"只发放一次|不是每月发放|不会过期", "same, zh"),
    (r"no\s*es\s*mensual|no\s*caduca|se\s*entrega\s*una\s*sola\s*vez", "same, es"),
    (r"n[ãa]o\s*[ée]\s*mensal|n[ãa]o\s*expira|[ée]\s*dada\s*uma\s*vez", "same, pt"),
    (r"मासिक\s*नहीं\s*है|अवधि\s*समाप्त\s*नहीं\s*होती", "same, hi"),
    (r"bukan\s*bulanan|tidak\s*kedaluwarsa|diberikan\s*sekali", "same, id"),
    (r"nicht\s*monatlich|verf[äa]llt\s*nicht", "same, de"),
]

# ─────────────────────────────────────────────────────────────────────────────
# PRODUCT FACTS — things that must appear TOGETHER, not things that must never
# appear. See check 6 in the module docstring for why this class exists.
#
# Structure mirrors the App Store validator's SUB_REQUIRED: a SUBJECT pattern
# decides whether the rule applies to this file at all, then every REQUIRED
# pattern must also match. Scoping on the subject is what keeps a rule from
# firing on a brand that genuinely lacks the feature — RedVPN on the App Store
# is trial-gated with no allowance at all, and must not be made to claim one.
#
# ⚠️ Every pattern must cover EVERY language the copy ships in, or the check
# fires on correct Arabic and Hindi and trains people to ignore it.
PRODUCT_FACTS_DEFAULT = [
    (
        # SUBJECT: does this file talk about the free data allowance?
        r"allowance of data|data allowance|free allowance|allowance you can see|"
        r"دادهٔ\s*رایگان|حصة\s*(?:من\s*)?(?:ال)?بيانات|برصيد|"
        r"об[ъь][ёе]м\s*(?:трафика|даных|данных)|"
        r"veri\s*(?:kotas|hakk)|流量额度|saldo\s*de\s*datos|franquia\s*de\s*dados|"
        r"रफ़्तार\s*पर\s*कुछ\s*डेटा|jatah\s*data|Kontingent",
        [
            (r"renews every month|renew\w*\s+monthly|refills every month|"
             r"هر\s*ماه|كل\s*شهر|каждый\s*месяц|кожны\s*месяц|her\s*ay|"
             r"每月(?:自动)?(?:重置|刷新|续)|每个月都会|cada\s*mes|todo\s*m[êe]s|हर\s*महीने|setiap\s*bulan|jeden\s*Monat",
             "mentions the free allowance but never says it RENEWS MONTHLY — "
             "that omission is how the one-time claim survived eleven languages"),
            (r"lower speed|slower|سرعت\s*کمتر|بسرعة\s*أقل|меньшей\s*скорости|"
             r"меншай\s*хуткасці|daha\s*düşük\s*hızda|较低的速度|降速|"
             r"menor\s*velocidad|velocidade\s*menor|कम\s*रफ़्तार|"
             r"kecepatan\s*lebih\s*rendah|geringerer\s*Geschwindigkeit",
             "mentions the free allowance but never says running out drops you "
             "to a LOWER SPEED — readers assume a wall, and there is no wall"),
        ],
    ),
    (
        # SUBJECT: does this file advertise a free trial?
        r"free trial|تجربة\s*مجانية|prueba\s*grat|essai\s*gratuit|"
        r"дн\w*\s*бесплатно|бесплатн\w*\s*период|[üu]cretsiz\s*deneme|免费试用",
        [
            (r"new subscriber|if you have not used|have not had|not already used|"
             r"المشتركون\s*الجدد|إن\s*لم\s*تكن\s*قد\s*استخدمت|"
             r"nuevos\s*suscriptores|no\s*has\s*usado|nunca\s*has\s*usado|"
             r"nouveaux\s*abonn[ée]s|n'en\s*avez\s*pas\s*d[ée]j[àa]|jamais\s*utilis[ée]|"
             r"[Нн]овые\s*подписчики|ещ[ёе]\s*не\s*пользовал|"
             r"[Yy]eni\s*abone|kullanmad[ıi]ysan[ıi]z|新用户|未使用过",
             "advertises a free trial but never says who is ELIGIBLE — Apple "
             "grants an introductory offer once per Apple ID per subscription "
             "group, so a returning user is charged on tap with no trial"),
        ],
    ),
]

# DOLLARVPN IS A DIFFERENT PRODUCT and its allowance rules are the INVERSE of
# the default set above. Verified 2026-09-21 in the live cashier DB:
#
#     Product(dollarvpn)  free_quota_mb=1024  free_period_days=0
#                         freedom_tier=False
#     RegionOffering      (*,IR,RU,CN | dollarvpn)  quota=1024  ads=off
#
# `free_period_days=0` → `_period_for()` returns None → the allowance NEVER
# refills. `_offering_for()` prefers the exact (region, brand) row, so the
# pre-existing IR/RU rows at -1 (unlimited) do NOT apply to this brand: Iran
# and Russia are metered at 1 GB like everywhere else.
#
# Entries here are (subject, required, forbidden). `forbidden` is new and is
# what stops the DEFAULT wording being pasted back in by someone copying
# rostam's file — the failure that started check 6 was a copy-paste of exactly
# that kind.
PRODUCT_FACTS_DOLLARVPN = [
    (
        # SUBJECT: does this file talk about the free data at all?
        #
        # ⚠️ Deliberately BROAD. A narrow subject is not a safe default here:
        # when this rule first ran, the six stale DollarVPN files PASSED,
        # because each described a monthly allowance in its own language and
        # none happened to contain the literal "1 GB" or the English word
        # "allowance". A rule that only fires on copy that already names the
        # figure cannot catch copy that omits it, which is the failure mode.
        # Every DollarVPN listing is expected to discuss the free data, so a
        # subject that nearly always matches is the correct bias.
        r"\d\s*(?:GB|Go|ГБ|МБ|MB)|[\d۱]\s*(?:گیگابایت|غيغابايت|जीबी)|"
        r"free allowance|data allowance|allowance of data|free data|"
        r"free to try|try it free|"
        r"دادهٔ?\s*رایگان|گیگابایت|"
        r"بيانات\s*مجان|غيغابايت|حصة|"
        r"трафик\w*|бесплатн\w*\s*\d|"
        r"трафік\w*|безкоштовн\w*\s*\d|"
        r"[üu]cretsiz\s*(?:veri|\d)|veri\s*(?:kotas|hakk)|"
        r"免费流量|流量额度|"
        r"datos\s*grat|cantidad\s*de\s*datos|"
        r"dados\s*gr[áa]tis|franquia\s*de\s*dados|"
        r"data\s*gratis|jatah\s*data|"
        r"मुफ़्त\s*डेटा|"
        r"donn[ée]es\s*(?:offertes|gratuites)|"
        r"Datenvolumen|kostenlos\s*\d|"
        r"dati\s*grat|"
        r"dữ\s*liệu\s*miễn\s*phí|"
        # Every alternative the DEFAULT subject already knows, unioned in so a
        # file translated from rostam's wording cannot land in the one gap
        # neither list covers. `saldo de datos` and `रफ़्तार पर कुछ डेटा` are
        # exactly how the stale es-419 and hi-IN files evaded the first draft.
        r"allowance you can see|saldo\s*de\s*datos|saldo\s*gratuito|"
        r"رفتار\s*پر\s*کچھ|रफ़्तार\s*पर\s*कुछ\s*डेटा|برصيد|Kontingent|"
        r"حصة\s*(?:من\s*)?(?:ال)?بيانات|об[ъь][ёе]м\s*(?:трафика|даных|данных)",
        [
            (r"given one time|does not renew|given once|one[- ]time|"
             r"یک[‌ ]?بار|تمدید\s*نمی[‌ ]?شود|"
             r"مرة\s*واحدة|لا\s*تتجدد|"
             r"один\s*раз|не\s*продлевается|не\s*поновлюється|"
             r"bir\s*kez|yenilenmez|"
             r"仅发放一次|只发放一次|不会每月|不再续发|"
             r"una\s*sola\s*vez|no\s*se\s*renueva|"
             r"uma\s*[úu]nica\s*vez|n[ãa]o\s*se\s*renova|"
             r"sekali|tidak\s*diperbarui|"
             r"एक\s*ही\s*बार|नवीनीकृत\s*नहीं|"
             r"einmalig|erneuert\s*sich\s*nicht|"
             r"une\s*seule\s*fois|ne\s*se\s*renouvelle\s*pas|"
             r"una\s*sola\s*volta|non\s*si\s*rinnova|"
             r"một\s*lần|không\s*gia\s*hạn",
             "mentions the free data but never says it is ONE-TIME and does "
             "NOT renew (cashier free_period_days=0)"),
            (r"[1۱]\s*(?:GB|Go|ГБ|گیگابایت|غيغابايت|जीबी)",
             "mentions the free data but never states the figure — it is "
             "1 GB (cashier free_quota_mb=1024)"),
        ],
        [
            (r"renews?\s+(?:every\s+month|monthly)|refills?\s+every\s+month|"
             r"هر\s*ماه\s*(?:تمدید|شارژ)|كل\s*شهر|"
             r"(?:продлевается|обновляется)\s*каждый\s*месяц|"
             r"щомісяц\w*\s*поновлю|her\s*ay\s*yenilen|"
             r"每月(?:自动)?(?:重置|刷新|续)|se\s*renueva\s*cada\s*mes|"
             r"renova\w*\s*todo\s*m[êe]s|हर\s*महीने\s*नवीनीकृत|"
             r"diperbarui\s*setiap\s*bulan|jeden\s*Monat\s*erneuert|"
             r"renouvel\w*\s*chaque\s*mois|si\s*rinnova\s*ogni\s*mese|"
             r"gia\s*hạn\s*hàng\s*tháng",
             "says the allowance renews monthly — DollarVPN's is ONE-TIME"),
            (r"lower speed|slower|سرعت\s*کمتر|بسرعة\s*أقل|"
             r"меньшей\s*скорости|меншій\s*швидкості|daha\s*d[üu][şs][üu]k\s*h[ıi]zda|"
             r"较低的速度|降速|menor\s*velocidad|velocidade\s*menor|"
             r"कम\s*रफ़्तार|kecepatan\s*lebih\s*rendah|"
             r"geringerer\s*Geschwindigkeit|vitesse\s*r[ée]duite|"
             r"velocit[àa]\s*ridotta|tốc\s*độ\s*thấp",
             "promises a slower free tier after the allowance — DollarVPN "
             "sets throttle.hard_stop=true and has no free tier past 1 GB"),
            (r"\b(?:[02-9]|\d{2,})\s*(?:GB|Go|ГБ|گیگابایت|غيغابايت|जीबी)",
             "states a free-data figure other than 1 GB"),
        ],
    ),
]

PRODUCT_FACTS_BY_BRAND = {"dollarvpn": PRODUCT_FACTS_DOLLARVPN}


def check_product_facts(desc, brand=None):
    """Return errors for product facts a description raises but leaves half-told.

    Shared with the App Store validator so a fact learned on one store is
    enforced on the other the same day. `brand` is optional so the App Store
    validator's existing one-argument call keeps working; omitting it selects
    the default (monthly-allowance) rules.
    """
    out = []
    for entry in PRODUCT_FACTS_BY_BRAND.get(brand, PRODUCT_FACTS_DEFAULT):
        subject, required = entry[0], entry[1]
        forbidden = entry[2] if len(entry) > 2 else []
        if not re.search(subject, desc, re.I):
            continue
        for pat, why in required:
            if not re.search(pat, desc, re.I):
                out.append(f"product fact: {why}")
        for pat, why in forbidden:
            m = re.search(pat, desc, re.I)
            if m:
                out.append(f"product fact: {m.group(0)!r} — {why}")
    return out

# Play Metadata policy, verified 2026-09-09 against answer/9898842.
#
# PRICE — "Images or text that indicate price and promotional information,
# such as '10% off,' '$50 cash back,' 'free for limited time only,' etc."
# This is why DollarVPN's copy never names a figure: the brand name carries
# the positioning and Play renders the real localised price itself. A number
# in the text would also be wrong in 172 of 173 regions.
PRICE_TEXT = [
    r"[$€£¥₹]\s?\d", r"\d+\s?(?:USD|EUR|GBP|INR|TRY|BRL)\b",
    r"\b\d+%\s*(?:off|discount|скидк|indirim|desconto)",
    r"free for a limited time|limited time only|cash ?back",
]

# "Using profane, vulgar, or other language that is inappropriate for a
# general audience in your app's Store listing." — same policy page.
# RedVPN's brand voice is "No bullshit, just amazing VPN"; the REGISTER is
# right and is kept, the word cannot go in a Play field. See README.
PROFANITY = [r"\bbullshit\b", r"\bshit\b", r"\bfuck", r"\bdamn\b", r"\bcrap\b"]

# Naming a jurisdiction in store text. RedVPN's live Arabic listing is titled
# "VPN Super Proxy for UAE" and promises to get past blocking in the UAE by
# name. That is an error, not a warning: it makes the listing that country's
# problem and it is the single most removable line on any of our listings.
JURISDICTION = [
    r"\bfor (?:the )?(?:UAE|Iran|Russia|China|Turkey|Saudi|Egypt|India)\b",
    r"in the UAE|في الإمارات|الإمارات",
    r"\b(?:UAE|Emirates)\b",
]


# Warn-level. See README.md "What can be said" for why these are not errors.
TARGETING = [
    r"VPN\s*(?:ایران|Россия|России|Turkey|Türkiye|UAE|الإمارات|China)",
    r"عبور از فیلتر|دور زدن فیلتر|رفع فیلتر",
    r"обход\s+(?:цензуры|блокировок)",
    r"翻墙|科学上网|梯子|突破封锁|破解封锁",
    r"كسر الحجب|تجاوز الحظر|فك الحظر",
    r"sansür\s+aşma|engel\s+kaldırma",
    r"bypass\s+(?:censorship|the\s+great\s+firewall|government)",
    r"unblock\s+(?:netflix|hulu|bbc|disney)",   # also a copyright-flavoured flag
]


def fields(text):
    """Pull the fenced block under each '## <Field>' heading.

    Only the three REAL Play fields are matched. It used to accept any `##`
    heading, which meant a prose heading in the notes above the copy (a
    "## The price cannot be written down" say) was parsed as a field, and the
    genuine "## Title" that followed it was then reported MISSING. The copy was
    fine; the parser was shadowing it. Anchoring on the known names means the
    notes can carry as many headings as they like.
    """
    out = {}
    names = "|".join(re.escape(k) for k in LIMITS)
    for m in re.finditer(rf"^## ({names})\s*\n(.*?)^```\n(.*?)^```",
                         text, re.S | re.M):
        out[m.group(1).strip()] = m.group(3).rstrip("\n")
    return out


def check(path, brand):
    errs, warns = [], []
    text = path.read_text(encoding="utf-8")
    got = fields(text)

    for name, limit in LIMITS.items():
        if name not in got:
            errs.append(f"missing field: {name}")
            continue
        n = len(got[name])
        if n > limit:
            errs.append(f"{name}: {n} chars, limit {limit} (over by {n - limit})")

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
    claims = list(FALSE_CLAIMS)
    if brand in MONTHLY_ALLOWANCE_BRANDS:
        claims += MONTHLY_ALLOWANCE_CLAIMS
    for pat, why in claims:
        for m in re.finditer(pat, body, re.I):
            errs.append(f"contradicts the app: {m.group(0)!r} — {why}")
    for pat in PRICE_TEXT:
        for m in re.finditer(pat, body, re.I):
            errs.append(f"Metadata policy, price/promo text: {m.group(0)!r}")
    for pat in PROFANITY:
        for m in re.finditer(pat, body, re.I):
            errs.append(f"Metadata policy, profanity: {m.group(0)!r} "
                        "— keep the register, drop the word")
    for pat in JURISDICTION:
        for m in re.finditer(pat, body, re.I):
            errs.append(f"names a jurisdiction: {m.group(0)!r} "
                        "— makes the listing that country's problem")

    errs += check_product_facts(got.get("Full description", ""), brand)

    # Keyword stuffing: a line that is a separator-delimited list and never
    # ends a sentence. Chinese lists use 、 rather than a comma.
    for line in got.get("Full description", "").splitlines():
        line = line.strip()
        seps = sum(line.count(c) for c in "،,，、")
        if seps >= 5 and not re.search(r"[.。!?！？]\s*$", line) \
                and not line.startswith("-"):
            errs.append(f"keyword stuffing: {line[:60]!r}…")

    # Targeting phrases. Not banned — the category noun is what the people who
    # need this app actually type, and a listing they cannot find protects
    # nobody. What earns a warning is naming the ACT or the COUNTRY, which adds
    # nothing a censor did not already know and everything a reviewer notices.
    for pat in TARGETING:
        for m in re.finditer(pat, body, re.I):
            warns.append(f"targeting phrase, prefer a plain category noun: {m.group(0)!r}")

    site = BRAND_SITE.get(brand)
    if site and site not in body:
        warns.append(f"no contact website in the description (expected {site})")
    return errs, warns


def main():
    files = sorted(HERE.glob("play/*/*.md"))
    if not files:
        print("no listing files under play/<brand>/", file=sys.stderr)
        return 1
    bad = 0
    brand = None
    for f in files:
        if f.parent.name != brand:
            brand = f.parent.name
            print(f"\n── {brand} " + "─" * (52 - len(brand)))
        errs, warns = check(f, brand)
        got = fields(f.read_text(encoding="utf-8"))
        sizes = " ".join(f"{k.split()[0].lower()}={len(v)}" for k, v in got.items())
        status = "FAIL" if errs else "ok  "
        print(f"{status} {f.name:<12} {sizes}")
        for e in errs:
            print(f"       ERROR {e}")
        for w in warns:
            print(f"       warn  {w}")
        bad += bool(errs)
    print(f"\n{len(files)} listings, {bad} failing")
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
