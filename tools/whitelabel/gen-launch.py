#!/usr/bin/env python3
"""Generate the two launch drawables each brand's splash needs.

    python3 tools/whitelabel/gen-launch.py            # all brands
    python3 tools/whitelabel/gen-launch.py rostam     # one brand
    python3 tools/whitelabel/gen-launch.py --check    # measure, write nothing

Source is the brand's OWN committed lockup, `<brand>/res/drawable/brand_splash.png`
-- not brands/assets/, which is empty. Output, per brand, into
`<brand>/res/drawable-xxhdpi/`:

    launch_mark.png       the mark alone, inset in a square canvas
    launch_wordmark.png   the wordmark as a WHITE ALPHA MASK, same square canvas

WHY TWO FILES ON ONE CANVAS
    `launch_mark.png` is what themes.xml hands to windowSplashScreenAnimatedIcon,
    so the system paints it. `LaunchOverlay` then draws the SAME drawable in a box
    of the same size at the same window centre, which is what makes the hand-off
    free of a jump -- not a shared number, the shared file. The wordmark shares
    that canvas so it needs no offset, gap or scale constant anywhere in Kotlin:
    stack the two drawables in one box and the artwork's own layout comes back
    exactly. Change the lockup, re-run this, and both layers move together.

WHY THE WORDMARK IS A MASK AND NOT ARTWORK
    Rostam's lockup bakes the name in near-black ink (#1A2026), which is correct
    on the light ground and INVISIBLE on the dark one -- caught by rendering both
    grounds before writing any code, and still true of the iOS lockup this is
    modelled on. The wordmark is typography, so it takes the ground's ink colour
    rather than carrying its own: this writes it as pure white with the source
    alpha preserved, and LaunchOverlay tints it with @color/launch_ink, which is
    night-qualified. The mark keeps its full colour -- a shield with a gold helmet
    is artwork, not type.

WHY THE MARK IS INSET, AND BY HOW MUCH
    The splash icon slot is 288dp and the art inside it must fit a 192dp circle
    (Google's guidance; core-splashscreen's own values.xml agrees --
    splashscreen_icon_size_no_background=288dp, and its compat mask is
    splashscreen_icon_mask_size_no_background 410dp minus 2x109dp stroke = a
    192dp aperture, which on API<31 is a REAL circle that really does clip).
    A bounding box of 2/3 is only safe for art that is itself round; the Rostam
    shield is 0.765:1 and its top corners reach 1.14x the inscribing radius, so
    at 2/3 a circular mask shears them off. The inset is therefore measured from
    each brand's own silhouette rather than assumed:

        fraction = (1/3) / max_radius        capped at 2/3

    where max_radius is the art's furthest opaque pixel from its centre, in units
    of the art's height. A disc gives exactly 2/3; the Rostam shield gives 0.585.
    That is ~7% smaller than the shield renders today, and complete instead of
    having 68px of its point cut off flat.

Requires Pillow.
"""
from __future__ import annotations

import pathlib
import sys

try:
    from PIL import Image
except ImportError:  # pragma: no cover - environment problem, not a code path
    print("  ERROR Pillow is required: python3 -m pip install pillow", file=sys.stderr)
    raise SystemExit(2)

ROOT = pathlib.Path(__file__).resolve().parents[2]
BRANDS = ROOT / "brands"
ANDROID_SRC = ROOT / "android/gen/android/app/src"

# 288dp at xxhdpi (3x). Density-qualified rather than nodpi so a low-density
# device decodes it pre-scaled instead of holding a 3MB bitmap it will shrink.
CANVAS = 864

# The splash icon keyline: art fits a 192dp circle inside a 288dp slot.
KEYLINE = 2.0 / 3.0

# A transparent run this tall (as a fraction of the lockup) counts as the seam
# between mark and wordmark. Rostam's is 88 of 1252 = 0.070; anti-aliasing noise
# inside the artwork never produces a fully clear run anywhere near that.
SEAM_MIN = 0.03

ALPHA_FLOOR = 8  # below this a pixel is transparent, not faint


def alpha_bbox(px, w: int, h: int, y0: int, y1: int):
    """Tight bbox of pixels with alpha > ALPHA_FLOOR in rows [y0, y1)."""
    left, right, top, bottom = w, -1, h, -1
    for y in range(y0, y1):
        row = None
        for x in range(w):
            if px[x, y][3] > ALPHA_FLOOR:
                if row is None:
                    row = x
                right = max(right, x)
        if row is not None:
            left = min(left, row)
            top = min(top, y)
            bottom = y
    if right < 0:
        return None
    return (left, top, right + 1, bottom + 1)


def clear_rows(px, w: int, y0: int, y1: int) -> list[bool]:
    out = []
    for y in range(y0, y1):
        clear = True
        for x in range(w):
            if px[x, y][3] > ALPHA_FLOOR:
                clear = False
                break
        out.append(clear)
    return out


def find_seam(px, w: int, y0: int, y1: int):
    """The tallest fully transparent run in the lower half of the lockup.

    Lower half only: a lockup is mark-above-wordmark, and a clear run in the
    upper half is a hole in the artwork, not a seam.
    """
    rows = clear_rows(px, w, y0, y1)
    span = y1 - y0
    best = None
    start = None
    for i, clear in enumerate(rows + [False]):
        if clear and start is None:
            start = i
        elif not clear and start is not None:
            run = (y0 + start, y0 + i)  # [start, end)
            if (i - start) >= SEAM_MIN * span and start > span * 0.5:
                if best is None or (i - start) > (best[1] - best[0]):
                    best = run
            start = None
    return best


def max_radius(px, box, cx: float, cy: float, unit: float) -> float:
    """Furthest opaque pixel from (cx, cy), in units of `unit`."""
    worst = 0.0
    for y in range(box[1], box[3]):
        first = None
        last = None
        for x in range(box[0], box[2]):
            if px[x, y][3] > ALPHA_FLOOR:
                if first is None:
                    first = x
                last = x
        if first is None:
            continue
        dy = (y + 0.5 - cy) / unit
        for x in (first, last + 1):
            dx = (x - cx) / unit
            worst = max(worst, (dx * dx + dy * dy) ** 0.5)
    return worst


def ink_report(px, box) -> str:
    """How many hues the wordmark actually uses.

    Flattening it to one tint is only honest for single-ink type. A brand whose
    wordmark is two-tone gets told here rather than discovering it on a device.
    """
    seen: dict[tuple, int] = {}
    for y in range(box[1], box[3]):
        for x in range(box[0], box[2]):
            r, g, b, a = px[x, y]
            if a > 200:
                key = (r // 24, g // 24, b // 24)
                seen[key] = seen.get(key, 0) + 1
    if not seen:
        return "none"
    top = max(seen.items(), key=lambda kv: kv[1])
    share = top[1] / sum(seen.values())
    tag = "single" if share > 0.97 else f"{len(seen)} HUES, tint flattens them"
    return f"#{top[0][0] * 24:02X}{top[0][1] * 24:02X}{top[0][2] * 24:02X} {tag}"


def gen(bid: str, check: bool) -> str:
    src = ANDROID_SRC / bid / "res/drawable/brand_splash.png"
    if not src.exists():
        return f"{bid}: no {src.relative_to(ROOT)} -- skipped"

    im = Image.open(src).convert("RGBA")
    w, h = im.size
    px = im.load()

    full = alpha_bbox(px, w, h, 0, h)
    if full is None:
        return f"{bid}: brand_splash.png is entirely transparent -- skipped"

    seam = find_seam(px, w, full[1], full[3])
    if seam:
        mark_box = alpha_bbox(px, w, h, full[1], seam[0])
        word_box = alpha_bbox(px, w, h, seam[1], full[3])
    else:
        mark_box, word_box = full, None
    if mark_box is None:
        return f"{bid}: could not find a mark in brand_splash.png -- skipped"

    mw = mark_box[2] - mark_box[0]
    mh = mark_box[3] - mark_box[1]
    mcx = (mark_box[0] + mark_box[2]) / 2.0
    mcy = (mark_box[1] + mark_box[3]) / 2.0
    mr = max_radius(px, mark_box, mcx, mcy, mh)

    # The inset that keeps the whole silhouette inside the 192dp keyline circle.
    fraction = min(KEYLINE, (1.0 / 3.0) / mr) if mr > 0 else KEYLINE
    art_h = int(round(fraction * CANVAS))
    scale = art_h / mh
    art_w = int(round(mw * scale))

    out_dir = ANDROID_SRC / bid / "res/drawable-xxhdpi"
    mark_dst = out_dir / "launch_mark.png"
    word_dst = out_dir / "launch_wordmark.png"

    note = (f"{bid}: mark {mw}x{mh} r={mr:.4f} -> {fraction:.4f} of canvas "
            f"({art_w}x{art_h} of {CANVAS})")

    if word_box:
        ww = word_box[2] - word_box[0]
        wh = word_box[3] - word_box[1]
        wcx = (word_box[0] + word_box[2]) / 2.0
        gap = word_box[1] - mark_box[3]
        note += (f"; wordmark {ww}x{wh}, gap {gap}px, "
                 f"dx {wcx - mcx:+.1f}px, ink {ink_report(px, word_box)}")
    else:
        note += "; no wordmark (single-layer artwork)"

    if check:
        return "would write -- " + note

    out_dir.mkdir(parents=True, exist_ok=True)

    mark = Image.new("RGBA", (CANVAS, CANVAS), (0, 0, 0, 0))
    art = im.crop(mark_box).resize((art_w, art_h), Image.LANCZOS)
    mark.paste(art, ((CANVAS - art_w) // 2, (CANVAS - art_h) // 2))
    mark.save(mark_dst, optimize=True)

    word = Image.new("RGBA", (CANVAS, CANVAS), (0, 0, 0, 0))
    if word_box:
        ww = word_box[2] - word_box[0]
        wh = word_box[3] - word_box[1]
        tw = max(1, int(round(ww * scale)))
        th = max(1, int(round(wh * scale)))
        wart = im.crop(word_box).resize((tw, th), Image.LANCZOS)
        # Keep the alpha, discard the ink: LaunchOverlay supplies the colour.
        wart = Image.merge("RGBA", (
            Image.new("L", wart.size, 255), Image.new("L", wart.size, 255),
            Image.new("L", wart.size, 255), wart.split()[3]))
        # Same coordinate space as the mark: the mark sits centred, so the
        # wordmark's top is the mark's bottom plus the artwork's own gap, and its
        # centre keeps the artwork's own horizontal offset from the mark.
        top = int(round((CANVAS + art_h) / 2.0 + (word_box[1] - mark_box[3]) * scale))
        wcx = (word_box[0] + word_box[2]) / 2.0
        left = int(round(CANVAS / 2.0 + (wcx - mcx) * scale - tw / 2.0))
        word.paste(wart, (left, top))
        if top + th > CANVAS:
            note += f"  WARNING wordmark overflows the canvas by {top + th - CANVAS}px"
    word.save(word_dst, optimize=True)

    return "wrote -- " + note


def main() -> int:
    check = "--check" in sys.argv
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    brands = args or sorted(
        f.stem for f in BRANDS.glob("*.json") if f.name != "LOCKED.json")
    for bid in brands:
        print("  " + gen(bid, check))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
