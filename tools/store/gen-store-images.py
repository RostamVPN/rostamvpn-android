#!/usr/bin/env python3
"""Generate App Store / Play store listing images from the brand manifest.

    python3 tools/store/gen-store-images.py rostam            # every locale
    python3 tools/store/gen-store-images.py rostam fa en      # just these

Regenerable on purpose. Store art that exists only as a designer's export drifts
from the product and cannot be reproduced for a new brand; this reads
brands/<id>.json, so a second brand is a command rather than a redesign.

STORE TEXT RULE (CLAUDE.md §0): nothing here may name a provider, protocol,
endpoint, IP or server count. Iran's NIN reads public store listings, and a
screenshot caption is public text like any other. `tools/store/listing/validate.py`
enforces the same rule on the description copy.

NO INVENTED NUMBERS. An earlier version of this file drew "48.2 Mbps / 21.7 Mbps"
and "1.4 GB / 2.0 GB" into panels. Both were made up. A fabricated speed test in
a store screenshot is an unverifiable performance claim under Play's Store
Listing policy, and the quota figure was wrong besides — the allowance is served
per region and there is no allowance at all in the free regions. Everything drawn
here is either true of every install or is unlabelled decoration.

WHY THE FONT TABLE EXISTS. The panels ship in Persian, Arabic and Chinese. SF has
no glyphs for any of those, and Pillow renders a missing glyph as a hollow box
without raising: the 2026-08 output drew the *languages* panel — the one whose
whole subject is language support — with فارسی, کوردی, العربية, ไทย and 中文 as
tofu. Fonts are resolved per script, and Arabic-script text is shaped and
reordered by Raqm (Pillow's HarfBuzz layout engine). Verify a rendered panel by
eye after any change; nothing here can detect a box for you.
"""
import json
import pathlib
import re
import sys

from PIL import Image, ImageDraw, ImageFont

ROOT = pathlib.Path(__file__).resolve().parents[2]
BRAND = sys.argv[1] if len(sys.argv) > 1 else "rostam"
MAN = json.loads((ROOT / "brands" / f"{BRAND}.json").read_text())
TH = MAN["branding"]["theme"]
NAME = MAN["branding"]["appName"]

# ── fonts ────────────────────────────────────────────────────────────────────
# (path, ttc index). Variable faces carry a real Bold instance; the old code
# asked SFNS.ttf for index=1, which is not a valid face, silently caught the
# error and fell back to Helvetica — so "bold" was a different family entirely.
FACES = {
    "latin":  ("/System/Library/Fonts/SFNS.ttf", 0),
    "arabic": ("/System/Library/Fonts/SFArabic.ttf", 0),
    "cjk":    ("/System/Library/Fonts/Hiragino Sans GB.ttc", 0),
    "cjkb":   ("/System/Library/Fonts/Hiragino Sans GB.ttc", 2),   # W6
    "thai":   ("/System/Library/Fonts/ThonburiUI.ttc", 0),
}
RTL = {"fa", "ar", "he", "ckb", "ur"}
_ARABIC = re.compile(r"[؀-ۿݐ-ݿﭐ-﷿]")
_CJK = re.compile(r"[　-ヿ一-鿿＀-￯]")
_THAI = re.compile(r"[฀-๿]")
_cache = {}


def font(size, bold=False, text=""):
    """Font for `text` at `size`. Script is detected from the string itself, so
    a mixed panel picks the right face per label without a locale argument."""
    if _CJK.search(text):
        key = "cjkb" if bold else "cjk"
    elif _ARABIC.search(text):
        key = "arabic"
    elif _THAI.search(text):
        key = "thai"
    else:
        key = "latin"
    ck = (key, size, bold)
    if ck in _cache:
        return _cache[ck]
    path, idx = FACES[key]
    try:
        f = ImageFont.truetype(path, size, index=idx,
                               layout_engine=ImageFont.Layout.RAQM)
    except Exception:
        f = ImageFont.truetype("/System/Library/Fonts/Supplemental/Arial Unicode.ttf",
                               size, layout_engine=ImageFont.Layout.RAQM)
    if bold and key in ("latin", "arabic"):
        try:
            f.set_variation_by_name("Bold")
        except Exception:
            pass
    _cache[ck] = f
    return f


def hx(h):
    h = h.lstrip("#")
    return tuple(int(h[i:i + 2], 16) for i in (0, 2, 4))


DEEP, PRIMARY, SEC = hx(TH["primaryDeep"]), hx(TH["primary"]), hx(TH["secondary"])
def _ratio(a, b):
    """WCAG 2.1 contrast between two RGB tuples."""
    def rel(x):
        f = lambda v: v / 12.92 if v <= 0.03928 else ((v + 0.055) / 1.055) ** 2.4
        r, g, bl = (f(k / 255) for k in x)
        return 0.2126 * r + 0.7152 * g + 0.0722 * bl
    la, lb = rel(a) + 0.05, rel(b) + 0.05
    return max(la, lb) / min(la, lb)


ACCENT, SUCCESS = hx(TH["accent"]), hx(TH["success"])

WHITE = (255, 255, 255)
# CARD was (28, 52, 58) and MUTED (168, 184, 188): a dark teal panel and a
# teal-grey caption, i.e. rostam's, on every brand's panels. CARD is the brand's
# own `ink` now, which is the colour it already uses for its darkest surface, and
# MUTED is white held back off the ground so a caption is quiet without being
# unreadable. Both are computed after the ground so they can never fight it.
CARD = hx(TH["ink"])
MUTED = tuple(int(DEEP[k] * 0.30 + 255 * 0.70) for k in range(3))

# ── THE "CONNECTED" MARK, and it has to be the brand's own ────────────────────
#
# Every panel drew `success` straight onto `primaryDeep`. That is rostam's
# screen: a teal disc on a near-black page. RedVPN's primaryDeep is #BD0000, so
# the same code put rostam's teal on a red page at 1.93:1 — barely visible, in a
# colour from another product, on the panels that sell the app. Blending it up
# for contrast only produced a mint disc on red, which measures fine and is still
# not that brand.
#
# The app already answers this: RingsHome keeps a WHITE core disc and draws the
# mark on it, precisely because the brand's own colour on the brand's own ground
# is unreadable. So `success` when it genuinely reads on the ground, otherwise
# the app's answer — white, with the brand's primary as the figure on it.
#
# Module scope on purpose. This lived inside body_connect and the two other
# panels that draw on the ground kept the raw value, so panel 01 was fixed and
# panels 03 and 04 were not. One brand, one answer.

# MARK   fills the disc and the "full speed" line: a graphic, so 3:1.
# FIGURE is drawn ON the mark (the power glyph), so it must contrast with it.
# MARK_TEXT is the same idea for the label beside the curve, which is TEXT at 4.5.
def lift(c, bg, min_ratio=3.0):
    """`c`, blended toward white until it clears `min_ratio` against `bg`.

    The dial used to be drawn in the brand's `success` on the brand's own deep
    ground, which silently assumed those two contrast. They do for a brand whose
    ground is near-black; RedVPN's ground is #BD0000 and its dial measured 1.93:1
    against it, i.e. a green disc that barely existed on a red page and matched
    nothing in the app. Same construction the app uses for exactly this (see
    `liftOnto` in ui/theme/Color.kt), so the two surfaces cannot disagree.
    """
    ratio = _ratio
    if ratio(c, bg) >= min_ratio:
        return c
    for i in range(1, 21):
        a = i / 20
        blend = tuple(int(c[k] * (1 - a) + 255 * a) for k in range(3))
        if ratio(blend, bg) >= min_ratio:
            return blend
    return (255, 255, 255)


MARK, FIGURE = (SUCCESS, WHITE) if _ratio(SUCCESS, DEEP) >= 3.0 else (WHITE, PRIMARY)
MARK_TEXT = MARK if _ratio(MARK, DEEP) >= 4.5 else lift(MARK, DEEP, 4.5)


# ── copy ─────────────────────────────────────────────────────────────────────
# Taken from the shipped app strings and tools/site/i18n so the store, the site
# and the app say the same words. Headlines use \n for an explicit line break.
COPY = {
    "en": {
        "tagline": "Your Digital Guardian",
        "connect_h": "One tap.\nThat is all.", "connect_s": "Nothing to set up.",
        "connect_state": "Connected",
        "zero_h": "Nothing to\nhand over.", "zero_s": "No account. No email. No name.",
        "zero_big": "0", "zero_cap": "things to sign up for",
        "free_h": "Free, and it\nstays free.", "free_s": "The allowance refills every month.",
        "curve_full": "Full speed", "curve_used": "Allowance used",
        "curve_after": "Still connected",
        "apps_h": "Your phone,\nyour rules.", "apps_s": "Choose what goes through.",
        "apps_rows": ["Browser", "Messages", "Banking"],
        "apps_states": ["On", "On", "Off"],
        "langs_h": "Speaks your\nlanguage.", "langs_s": "Including the ones most apps skip.",
        "langs_cap": "38 languages, set per app",
    },
    "fa": {
        "tagline": "نگهبان دیجیتال شما",
        "connect_h": "یک ضربه.\nهمین.", "connect_s": "چیزی برای تنظیم کردن نیست.",
        "connect_state": "متصل",
        "zero_h": "چیزی برای\nدادن نیست.", "zero_s": "بدون حساب. بدون ایمیل. بدون نام.",
        "zero_big": "0", "zero_cap": "فیلد برای پر کردن",
        "free_h": "رایگان است و\nرایگان می‌ماند.", "free_s": "سهمیهٔ ماهانه، هر ماه دوباره.",
        "curve_full": "سرعت کامل", "curve_used": "سهمیه تمام شد",
        "curve_after": "همچنان متصل",
        "apps_h": "گوشی شما،\nقاعدهٔ شما.", "apps_s": "انتخاب کنید چه چیزی عبور کند.",
        "apps_rows": ["مرورگر", "پیام‌رسان", "بانک"],
        "apps_states": ["روشن", "روشن", "خاموش"],
        "langs_h": "به زبان شما\nحرف می‌زند.", "langs_s": "از جمله زبان‌هایی که بقیه جا می‌اندازند.",
        "langs_cap": "۳۸ زبان، قابل انتخاب",
    },
    "ru": {
        "tagline": "Ваш цифровой страж",
        "connect_h": "Одно нажатие.\nИ всё.", "connect_s": "Настраивать нечего.",
        "connect_state": "Подключено",
        "zero_h": "Нечего\nотдавать.", "zero_s": "Ни аккаунта. Ни почты. Ни имени.",
        "zero_big": "0", "zero_cap": "полей для заполнения",
        "free_h": "Бесплатно\nи дальше.", "free_s": "Ежемесячный объём, каждый месяц заново.",
        "curve_full": "Полная скорость", "curve_used": "Объём израсходован",
        "curve_after": "Связь остаётся",
        "apps_h": "Ваш телефон,\nваши правила.", "apps_s": "Выберите, что идёт через VPN.",
        "apps_rows": ["Браузер", "Сообщения", "Банк"],
        "apps_states": ["Вкл", "Вкл", "Выкл"],
        "langs_h": "Говорит на\nвашем языке.", "langs_s": "В том числе на тех, что пропускают другие.",
        "langs_cap": "38 языков, отдельно для приложения",
    },
    "ar": {
        "tagline": "حارسك الرقمي",
        "connect_h": "نقرة واحدة.\nهذا كل شيء.", "connect_s": "لا شيء تضبطه.",
        "connect_state": "متصل",
        "zero_h": "لا شيء\nتعطيه.", "zero_s": "لا حساب. لا بريد. لا اسم.",
        "zero_big": "0", "zero_cap": "حقول تملؤها",
        "free_h": "مجاني،\nويبقى مجانياً.", "free_s": "حصة شهرية تتجدد كل شهر.",
        "curve_full": "السرعة الكاملة", "curve_used": "انتهت الحصة",
        "curve_after": "الاتصال مستمر",
        "apps_h": "هاتفك،\nوقواعدك.", "apps_s": "اختر ما الذي يمر.",
        "apps_rows": ["المتصفح", "الرسائل", "المصرف"],
        "apps_states": ["مفعّل", "مفعّل", "مطفأ"],
        "langs_h": "يتحدث\nلغتك.", "langs_s": "بما فيها ما يتجاهله الآخرون.",
        "langs_cap": "٣٨ لغة، تُختار للتطبيق",
    },
    "tr": {
        "tagline": "Dijital koruyucunuz",
        "connect_h": "Tek dokunuş.\nHepsi bu.", "connect_s": "Ayarlanacak bir şey yok.",
        "connect_state": "Bağlandı",
        "zero_h": "Verecek\nbir şey yok.", "zero_s": "Hesap yok. E-posta yok. İsim yok.",
        "zero_big": "0", "zero_cap": "doldurulacak alan",
        "free_h": "Ücretsiz ve\nöyle kalıyor.", "free_s": "Aylık kota, her ay yenilenir.",
        "curve_full": "Tam hız", "curve_used": "Kota bitti",
        "curve_after": "Bağlantı sürüyor",
        "apps_h": "Telefonunuz,\nkuralları siz koyun.", "apps_s": "Neyin geçeceğini seçin.",
        "apps_rows": ["Tarayıcı", "Mesajlar", "Banka"],
        "apps_states": ["Açık", "Açık", "Kapalı"],
        "langs_h": "Sizin dilinizi\nkonuşur.", "langs_s": "Çoğu uygulamanın atladığı diller dahil.",
        "langs_cap": "38 dil, uygulamaya özel",
    },
    "zh": {
        "tagline": "您的数字守护者",
        "connect_h": "一键连接。\n就这么简单。", "connect_s": "无需任何设置。",
        "connect_state": "已连接",
        "zero_h": "没有什么\n要交出去。", "zero_s": "不要账号。不要邮箱。不要姓名。",
        "zero_big": "0", "zero_cap": "个需要填写的字段",
        "free_h": "免费，\n并且一直免费。", "free_s": "每月额度，按月重置。",
        "curve_full": "全速", "curve_used": "额度用完",
        "curve_after": "连接依旧",
        "apps_h": "你的手机，\n你说了算。", "apps_s": "选择哪些流量走 VPN。",
        "apps_rows": ["浏览器", "消息", "网银"],
        "apps_states": ["开", "开", "关"],
        "langs_h": "说你的\n语言。", "langs_s": "包括大多数应用忽略的那些。",
        "langs_cap": "38 种语言，可单独设置",
    },
}

# Real endonyms, rendered with real glyphs. Five of these were boxes before.
LANGS = ["فارسی", "کوردی", "Türkçe", "Русский", "العربية", "中文",
         "Español", "Français", "Deutsch", "Azərbaycan", "ไทย", "Tiếng Việt"]


# ── drawing helpers ──────────────────────────────────────────────────────────
def vgrad(size, top, bottom):
    w, h = size
    g = Image.new("RGB", (1, h))
    d = ImageDraw.Draw(g)
    for y in range(h):
        t = y / max(h - 1, 1)
        d.point((0, y), tuple(int(top[i] + (bottom[i] - top[i]) * t) for i in range(3)))
    return g.resize(size)


def centred(d, y, text, f, fill, w):
    """Draw centred and return the y below the line. Height comes from the
    font's real bbox, which is what stops the headline colliding with the
    artwork the way it did when line spacing was a hard-coded constant."""
    box = d.textbbox((0, 0), text, font=f)
    d.text(((w - (box[2] - box[0])) / 2 - box[0], y - box[1]), text, font=f, fill=fill)
    return y + (box[3] - box[1])


def panel(size, loc, headline, sub, draw_body):
    w, h = size
    img = vgrad(size, DEEP, tuple(int(c * 0.55) for c in PRIMARY))
    d = ImageDraw.Draw(img)
    s = w / 1290.0                             # everything scales off the 6.9" width
    y = int(150 * s)
    for line in headline.split("\n"):
        f = font(int(78 * s), True, line)
        y = centred(d, y, line, f, WHITE, w) + int(30 * s)
    y += int(24 * s)
    for line in sub.split("\n"):
        f = font(int(42 * s), False, line)
        y = centred(d, y, line, f, MUTED, w) + int(20 * s)
    # The body owns everything below the text and is centred inside that band.
    # It is drawn onto its own layer first so its real extent can be measured
    # and then offset: no body has to know its own height, a two-line Turkish
    # headline cannot push artwork off the canvas, and a short Chinese headline
    # does not leave the bottom half of the panel empty — which is exactly how
    # the 2026-08 output looked, content crammed into the top third.
    top, bot = int(y + 70 * s), h - int(140 * s)
    layer = Image.new("RGBA", size, (0, 0, 0, 0))
    draw_body(layer, ImageDraw.Draw(layer), s, loc, 0, bot - top)
    box = layer.getbbox()
    if box:
        # 38% of the slack, not 50%. Optical centre: a panel reads as composed
        # when the artwork sits closer to the text it belongs to than to the
        # bottom edge, and store thumbnails crop from the bottom.
        off = top + max(0, int(((bot - top) - (box[3] - box[1])) * 0.38)) - box[1]
        shifted = Image.new("RGBA", size, (0, 0, 0, 0))
        shifted.paste(layer, (0, off))
        img = Image.alpha_composite(img.convert("RGBA"), shifted).convert("RGB")
    return img


def body_connect(img, d, s, loc, top, bottom):
    c = COPY[loc]
    w, cx = img.width, img.width // 2
    r = int(330 * s)
    cy = top + r + int(40 * s)
    for i, a in enumerate((0.10, 0.18, 0.30)):
        rr = r + int((3 - i) * 52 * s)
        d.ellipse([cx - rr, cy - rr, cx + rr, cy + rr],
                  outline=tuple(int(MARK[k] * a + DEEP[k] * (1 - a)) for k in range(3)),
                  width=int(5 * s))
    d.ellipse([cx - r, cy - r, cx + r, cy + r], fill=MARK)
    d.rounded_rectangle([cx - int(17 * s), cy - int(148 * s), cx + int(17 * s), cy - int(24 * s)],
                        radius=int(17 * s), fill=FIGURE)
    d.arc([cx - int(117 * s), cy - int(126 * s), cx + int(117 * s), cy + int(107 * s)],
          start=-58, end=238, fill=FIGURE, width=int(33 * s))
    y = cy + r + int(100 * s)
    centred(d, y, c["connect_state"], font(int(52 * s), True, c["connect_state"]), WHITE, w)


def body_zero(img, d, s, loc, top, bottom):
    """The site's 'fields to fill in' seal. One number, and it is a real one."""
    c = COPY[loc]
    w, cx = img.width, img.width // 2
    r = int(310 * s)
    cy = top + r + int(40 * s)
    d.ellipse([cx - r, cy - r, cx + r, cy + r], outline=SEC, width=int(6 * s))
    f = font(int(400 * s), True, c["zero_big"])
    box = d.textbbox((0, 0), c["zero_big"], font=f)
    d.text((cx - (box[2] - box[0]) / 2 - box[0], cy - (box[3] - box[1]) / 2 - box[1]),
           c["zero_big"], font=f, fill=ACCENT)
    centred(d, cy + r + int(90 * s), c["zero_cap"],
            font(int(44 * s), False, c["zero_cap"]), MUTED, w)


def body_curve(img, d, s, loc, top, bottom):
    """Full speed, then the step down, then a flat line that runs off the edge
    with no end mark — because the connection does not end. Deliberately has no
    axis numbers: the allowance differs by region and is zero in the free ones."""
    c = COPY[loc]
    w = img.width
    x0, x1 = int(120 * s), w - int(120 * s)
    hi, lo = top + int(150 * s), top + int(470 * s)
    knee = x0 + int((x1 - x0) * 0.42)
    d.line([(x0, hi), (knee, hi)], fill=MARK, width=int(20 * s))
    # RedVPN's accent is #69108C on a #BD0000 ground: 1.35:1, a purple line and
    # two purple labels that a reader cannot see at all. Lifted onto the ground
    # like everything else here, at 3:1 for the strokes and 4.5:1 for the text,
    # so the brand's hue survives and the chart stays readable on any ground.
    step = lift(ACCENT, DEEP, 3.0)
    tail = lift(SEC, DEEP, 3.0)
    d.line([(knee, hi), (knee + int(80 * s), lo)], fill=step, width=int(20 * s))
    d.line([(knee + int(80 * s), lo), (x1, lo)], fill=tail, width=int(20 * s))
    d.ellipse([knee - int(17 * s), hi - int(17 * s), knee + int(17 * s), hi + int(17 * s)],
              fill=step)
    for label, x, y, col in ((c["curve_full"], x0, hi - int(70 * s), MARK_TEXT),
                             (c["curve_used"], knee, lo + int(40 * s), lift(ACCENT, DEEP, 4.5)),
                             (c["curve_after"], x1, lo - int(70 * s), lift(SEC, DEEP, 4.5))):
        f = font(int(36 * s), False, label)
        tw = d.textlength(label, font=f)
        d.text((min(max(x - tw / 2, int(40 * s)), w - tw - int(40 * s)), y),
               label, font=f, fill=col)


def body_apps(img, d, s, loc, top, bottom):
    """Split tunnelling. Generic labels on purpose — drawing real app icons or
    names into store art puts somebody else's trademark in our screenshot.

    The row MIRRORS for Persian and Arabic: label at the right edge, switch at
    the left. Raqm shapes and reorders the text either way, but it cannot move a
    switch, and a left-anchored control in an RTL panel is the tell that the
    localisation was mechanical."""
    c = COPY[loc]
    rtl = loc in RTL
    x0, x1 = int(110 * s), img.width - int(110 * s)
    y = top
    for label, state in zip(c["apps_rows"], c["apps_states"]):
        d.rounded_rectangle([x0, y, x1, y + int(190 * s)], radius=int(40 * s), fill=CARD)
        mid = y + int(95 * s)
        f = font(int(44 * s), False, label)
        box = d.textbbox((0, 0), label, font=f)
        lx = (x1 - int(60 * s) - (box[2] - box[0])) if rtl else (x0 + int(60 * s))
        d.text((lx - box[0], mid - (box[3] - box[1]) / 2 - box[1]),
               label, font=f, fill=WHITE)
        on = state == c["apps_states"][0]
        tw, th = int(136 * s), int(72 * s)
        tx = (x0 + int(70 * s)) if rtl else (x1 - int(70 * s) - tw)
        ty = mid - th // 2
        d.rounded_rectangle([tx, ty, tx + tw, ty + th], radius=th // 2,
                            fill=MARK if on else (52, 74, 80))
        # The knob sits at the "on" end, which is also mirrored under RTL.
        at_end = on != rtl
        kx = tx + tw - th // 2 if at_end else tx + th // 2
        d.ellipse([kx - th // 2 + int(5 * s), ty + int(5 * s),
                   kx + th // 2 - int(5 * s), ty + th - int(5 * s)], fill=WHITE)
        y += int(226 * s)


def body_langs(img, d, s, loc, top, bottom):
    c = COPY[loc]
    w = img.width
    pad, gap, rowh = int(36 * s), int(24 * s), int(118 * s)
    margin = int(70 * s)
    rows, cur, curw = [], [], 0
    for L in LANGS:
        f = font(int(40 * s), False, L)
        tw = d.textlength(L, font=f) + pad * 2
        if cur and curw + tw + gap > w - margin * 2:
            rows.append((cur, curw))
            cur, curw = [], 0
        cur.append((L, tw, f))
        curw += tw + (gap if len(cur) > 1 else 0)
    rows.append((cur, curw))
    y = top
    for row, roww in rows:                     # centre each row: bidi-safe
        x = (w - roww) // 2
        for L, tw, f in row:
            d.rounded_rectangle([x, y, x + tw, y + int(92 * s)],
                                radius=int(46 * s), fill=CARD)
            box = d.textbbox((0, 0), L, font=f)
            d.text((x + pad - box[0], y + int(46 * s) - (box[3] - box[1]) / 2 - box[1]),
                   L, font=f, fill=WHITE)
            x += tw + gap
        y += rowh
    centred(d, y + int(60 * s), c["langs_cap"],
            font(int(38 * s), False, c["langs_cap"]), MUTED, w)


PANELS = [
    ("01-connect", "connect_h", "connect_s", body_connect),
    ("02-nothing", "zero_h", "zero_s", body_zero),
    ("03-free", "free_h", "free_s", body_curve),
    ("04-apps", "apps_h", "apps_s", body_apps),
    ("05-languages", "langs_h", "langs_s", body_langs),
]

# 1080x2400 is 20:9, the shape of the phones this actually runs on. The old
# 1080x1920 was 16:9 and letterboxed on every device made since about 2019.
SIZES = {"ios-6.9": (1290, 2796), "ios-6.5": (1242, 2688), "android": (1080, 2400)}


# ─────────────────────────────────────────────────────────────────────────────
# DOLLARVPN IS A DIFFERENT PRODUCT, AND THREE OF THE FIVE PANELS ABOVE ARE
# FALSE OF IT. Added 2026-09-21 while preparing its first App Store submission.
#
# The panels above were written for rostam/redvpn on Android and every one of
# them was generated, rendered and LOOKED AT for dollarvpn before this block
# was written — which is the only reason the problems were found at all:
#
#   03-free   "Free, and it stays free / The allowance refills every month" and
#             a curve ending in "Still connected". DollarVPN's allowance is
#             1 GB ONCE (`free_period_days=0`) and `throttle.hard_stop=true`,
#             so all three claims are the OPPOSITE of the product. This is
#             check 6 of tools/store/listing/validate.py arriving as a picture,
#             where no text validator can ever see it.
#   04-apps   per-app split tunnelling. IMPOSSIBLE on iOS — no public API gives
#             an App Store app per-app routing (CLAUDE.md §14). Android-only.
#   05-langs  "38 languages". The iOS catalog holds 47 keys and ~147 call sites
#             still take `String` instead of `LocalizedStringKey` (§17), so the
#             iOS app is very largely English. False on this platform.
#
# 01 and 02 are true of DollarVPN on iOS and are reused unchanged. Three honest
# panels beat five where two are wrong.
#
# ⚠️ These are OVERRIDES, deliberately additive and brand-keyed rather than
# edits to the tables above: another brand's store work may be in this file at
# the same time, and a shared table edited in place is how one brand's copy
# ends up on another's listing.

def body_quota(img, d, s, loc, top, bottom):
    """body_zero's seal with the allowance in it instead of a zero.

    The figure is REAL and comes from the cashier (`free_quota_mb=1024`), which
    is the whole rule this file already states: no invented numbers. It is also
    the one number that is the same in every region for this brand — there is
    no freedom tier, so unlike rostam there is no market where it is wrong.

    ⚠️ The font SHRINKS TO FIT. body_zero can hard-code 400*s because its string
    is one glyph; "1 GB" at that size is wider than the ring it sits in and
    would have run out over the edge exactly like RedVPN's splash shield did.
    """
    c = COPY[loc]
    w, cx = img.width, img.width // 2
    r = int(310 * s)
    cy = top + r + int(40 * s)
    d.ellipse([cx - r, cy - r, cx + r, cy + r], outline=SEC, width=int(6 * s))

    txt = c["quota_big"]
    size = int(400 * s)
    while size > int(60 * s):
        f = font(size, True, txt)
        box = d.textbbox((0, 0), txt, font=f)
        if (box[2] - box[0]) <= r * 1.56 and (box[3] - box[1]) <= r * 1.10:
            break
        size = int(size * 0.92)
    f = font(size, True, txt)
    box = d.textbbox((0, 0), txt, font=f)
    d.text((cx - (box[2] - box[0]) / 2 - box[0], cy - (box[3] - box[1]) / 2 - box[1]),
           txt, font=f, fill=ACCENT)
    centred(d, cy + r + int(90 * s), c["quota_cap"],
            font(int(44 * s), False, c["quota_cap"]), MUTED, w)


PANELS_DOLLARVPN = [
    ("01-connect", "connect_h", "connect_s", body_connect),
    ("02-nothing", "zero_h", "zero_s", body_zero),
    ("03-quota", "quota_h", "quota_s", body_quota),
]

# APP_IPAD_PRO_3GEN_129 is 2048x2732 and is the set already staged on this
# listing. The app's TARGETED_DEVICE_FAMILY is "1,2", so Apple requires iPad
# screenshots and will refuse the submission without them — that is why this
# size exists here and not in SIZES above, where no other brand needs it yet.
#
# ⚠️ 4:3 is a different SHAPE, not a bigger phone. Everything in panel() scales
# off width/1290, so at 2048 wide the canvas is proportionally much shorter and
# the body band shrinks. LOOK at the render; the centring maths copes, the
# composition is a judgement call.
SIZES_DOLLARVPN = {"ios-6.9": (1290, 2796), "ipad-12.9": (2048, 2732)}

COPY_DOLLARVPN = {
    "en": {
        # Verified in the live cashier, not in a config file:
        #   product(dollarvpn).free_quota_mb = 1024, free_period_days = 0
        # `quota.py:_period_for()` documents 0 as "None = never (one-time)".
        "quota_h": "Try it first.",
        "quota_s": "A gigabyte of data, on us.",
        "quota_big": "1 GB",
        "quota_cap": "given once, then one yearly plan",
    },
}

if BRAND == "dollarvpn":
    PANELS = PANELS_DOLLARVPN
    SIZES = SIZES_DOLLARVPN
    for _loc, _extra in COPY_DOLLARVPN.items():
        if _loc in COPY:
            COPY[_loc].update(_extra)


def feature_graphic(loc):
    """Play feature graphic, 1024x500: the brand mark, the name, the tagline.

    Play crops this asset and overlays a play button on it in several
    placements, so everything sits in the middle band with generous margins and
    nothing important goes near an edge. The live one carried a phone mock of a
    UI that no longer exists and a serif "ROSTAM VPN" wordmark that is not the
    app's name; the mark comes from the brand manifest here so it cannot drift
    from the launcher icon again."""
    c = COPY[loc]
    fg = vgrad((1024, 500), DEEP, tuple(int(x * 0.6) for x in PRIMARY))
    d = ImageDraw.Draw(fg)
    fn, ft = font(72, True, NAME), font(36, False, c["tagline"])
    nb = d.textbbox((0, 0), NAME, font=fn)
    tb = d.textbbox((0, 0), c["tagline"], font=ft)

    logo = ROOT / MAN["branding"]["logo"]
    mark = None
    if logo.exists():
        mark = Image.open(logo).convert("RGBA")
        mark.thumbnail((250, 250), Image.LANCZOS)

    textw = max(nb[2] - nb[0], tb[2] - tb[0])
    gap = 56 if mark else 0
    block = (mark.width if mark else 0) + gap + textw
    x = (1024 - block) // 2
    if mark:
        fg.paste(mark, (x, (500 - mark.height) // 2), mark)
        x += mark.width + gap

    total = (nb[3] - nb[1]) + 28 + (tb[3] - tb[1])
    y = (500 - total) // 2
    d.text((x - nb[0], y - nb[1]), NAME, font=fn, fill=WHITE)
    y += (nb[3] - nb[1]) + 28
    d.text((x - tb[0], y - tb[1]), c["tagline"], font=ft, fill=ACCENT)
    return fg


def main():
    locales = [a for a in sys.argv[2:]] or list(COPY)
    for loc in locales:
        if loc not in COPY:
            print(f"  ! no copy for {loc!r}, skipping")
            continue
        out = ROOT / "build" / "store" / BRAND / loc
        out.mkdir(parents=True, exist_ok=True)
        c = COPY[loc]
        for key, (w, h) in SIZES.items():
            for slug, hk, sk, body in PANELS:
                panel((w, h), loc, c[hk], c[sk], body).save(out / f"{key}-{slug}.png")
        feature_graphic(loc).save(out / "play-feature-graphic.png")
        rtl = " (RTL)" if loc in RTL else ""
        print(f"  {loc}{rtl}: {len(PANELS)} panels x {len(SIZES)} sizes + feature graphic")
    print(f"\nwritten to {ROOT / 'build' / 'store' / BRAND}")
    print("Check a Persian, Arabic and Chinese panel BY EYE before uploading — a\n"
          "missing glyph renders as a hollow box and raises nothing.")


if __name__ == "__main__":
    main()
