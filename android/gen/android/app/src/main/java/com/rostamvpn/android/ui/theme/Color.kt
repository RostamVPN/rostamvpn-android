package com.rostamvpn.android.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

// ── Contrast, computed rather than assumed ────────────────────────────────
//
// Five brands share every screen and their palettes are not comparable. The
// same literal pairing is fine in one and illegible in another, which this
// repo has now hit three times:
//
//   secondaryContainer/onSecondaryContainer  6.15:1 Rostam · 1.24:1 RedVPN
//   outline as a button border               1.12:1 on the page, both
//   dark-scheme `primary` on a dark surface  5.17:1 Rostam · 1.63:1 RedVPN
//
// The pattern in all three is a foreground CHOSEN ONCE against one brand's
// background. These helpers pick it per brand instead, at composition time,
// from the colours the theme actually resolved — so a brand added tomorrow
// gets a legible answer without anyone re-measuring.
//
// WCAG 2.1: (Llighter + 0.05) / (Ldarker + 0.05), where L is the relative
// luminance Compose's `Color.luminance()` already returns. Thresholds: 4.5
// for body text, 3.0 for large text and for non-text graphics (icons,
// borders, the dial's ring) per SC 1.4.11.

/**
 * [c]'s HUE, re-lit at [s] saturation and [l] lightness.
 *
 * For transplanting one brand role across a whole surface: a screen built from
 * two brand hues at a dozen different weights stays that brand's screen when
 * the manifest changes, where the same screen written as a dozen literals is
 * one brand's screen wearing every brand's name. See `ClassicPaywall`.
 *
 * Hue survives and everything else is imposed, deliberately: the point is a
 * controlled ramp, so the source's own saturation and lightness must NOT leak
 * into it or a brand with a pale role would produce a pale ground.
 */
fun tone(c: Color, s: Float, l: Float): Color = Color.hsl(hueOf(c), s, l)

/** [c]'s hue in degrees, 0f when it has none (any grey). */
fun hueOf(c: Color): Float {
    val r = c.red; val g = c.green; val b = c.blue
    val max = maxOf(r, g, b); val min = minOf(r, g, b)
    val d = max - min
    if (d == 0f) return 0f
    val h = when (max) {
        r -> (g - b) / d + (if (g < b) 6f else 0f)
        g -> (b - r) / d + 2f
        else -> (r - g) / d + 4f
    }
    return h * 60f
}

/** WCAG 2.1 contrast ratio, 1.0 (identical) to 21.0 (black on white). */
fun contrastRatio(a: Color, b: Color): Float {
    val la = a.luminance() + 0.05f
    val lb = b.luminance() + 0.05f
    return if (la > lb) la / lb else lb / la
}

/**
 * The more legible of two candidates on [bg]. Use when BOTH options are
 * acceptable brand-wise and only legibility decides — e.g. the glyph drawn on
 * a disc filled with the brand's own `primary`, which is near-black for
 * Rostam's teal and white for RedVPN's red.
 */
fun mostLegibleOn(bg: Color, a: Color, b: Color): Color =
    if (contrastRatio(a, bg) >= contrastRatio(b, bg)) a else b

/**
 * [preferred] when it clears [min] against [bg], otherwise [fallback]. Use
 * when one candidate is the DESIRED one (the brand colour) and the other is
 * only a safety net: RedVPN's #69108C measures 1.83:1 on its own dark page,
 * so the ring it would draw is not dim — it is absent.
 */
fun legibleOn(bg: Color, preferred: Color, fallback: Color, min: Float = 4.5f): Color =
    if (contrastRatio(preferred, bg) >= min) preferred else fallback

/**
 * [c] blended toward [toward] by the SMALLEST amount that clears [min] against
 * [bg]. Returns [c] untouched when it already clears.
 *
 * The third helper, and it exists because the other two cannot solve the dark
 * theme for a brand colour that has no legible twin. [legibleOn] needs a
 * fallback that is already acceptable and [mostLegibleOn] needs two candidates;
 * when the answer is "this exact hue, only lighter", both give up and hand back
 * a neutral, which throws the brand away precisely where it was the point.
 *
 * Concretely: RedVPN's accent #69108C measures 2.06:1 on a #121212 page — not
 * dim, ILLEGIBLE, and below even the 3:1 that large text gets. Its primary
 * #E2232B is 4.16:1, which passes for large text and fails for body. Neither
 * has a "lighter brand tone" defined anywhere, and inventing two more hex
 * values in the manifest would fix RedVPN and leave the sixth brand with the
 * same bug. Lifting toward white keeps the HUE — the thing that is actually
 * the brand — and spends only as much saturation as legibility costs.
 *
 * Stepped rather than solved because luminance is not linear in the blend
 * factor; 20 steps is finer than the eye resolves and terminates. If even
 * [toward] cannot clear [min] (a white-on-white request), [toward] is returned:
 * the caller asked for the most legible thing available and that is it.
 */
fun liftOnto(bg: Color, c: Color, min: Float = 4.5f, toward: Color = Color.White): Color {
    if (contrastRatio(c, bg) >= min) return c
    for (i in 1..20) {
        val t = i / 20f
        val blended = Color(
            red = c.red + (toward.red - c.red) * t,
            green = c.green + (toward.green - c.green) * t,
            blue = c.blue + (toward.blue - c.blue) * t,
            alpha = c.alpha,
        )
        if (contrastRatio(blended, bg) >= min) return blended
    }
    return toward
}

// Text Colors
val DarkBase = BrandPalette.ink
// `onSurfaceVariant` in both light schemes, so it is EVERY caption, helper line
// and secondary label in the app. It was #4A6572, a blue-grey belonging to no
// brand, which made all five products' quiet text the same colour. Now it is the
// brand's own hue held at the same lightness, so contrast is unchanged and the
// text is finally the product's. The two names below it were rostam's by name
// and dead by reference; they are gone.
val LightText = tone(BrandPalette.primary, 0.21f, 0.37f)

// Backgrounds
val BaseBg = BrandPalette.background
val LightGray1 = Color(0xFFEDEDED)
val LightGray2 = Color(0xFFE0E0E0)
val LightGray3 = Color(0xFFD5D5D5)
val LightGray4 = Color(0xFFC0C0C0)

// Brand Colors — from the flavor's generated BrandPalette (brands/<id>.json).
//
// These stay `val`s with the same names so the ~100 call sites are untouched;
// only their SOURCE moved. Swapping brand swaps BrandPalette.kt, which is the
// whole point: no `if (brand == …)` anywhere, and a new brand is a JSON edit.
val Brand1 = BrandPalette.primary
val Brand2 = BrandPalette.secondary
// ⚠️ THESE WERE HARDCODED AND BELONGED TO NOBODY.
//
// Brand1, Brand2, Accent3 and Danger all come from BrandPalette, so a brand gets
// its own. Accent1 was an olive green and Accent2 a brown, shared by all five
// brands, matching no brand's manifest. Accent1 is `tertiary` in every colour
// scheme, which is why a RedVPN surface could come out GREEN on a red brand.
//
// They now derive. A brand that wants a distinct third colour sets `success` in
// its manifest; nothing here invents one.
val Accent1 = BrandPalette.success
// Was #8B7355, a brown nobody owned, and it is `secondary` in the dark schemes,
// so a gradient written from these roles rendered mud on every brand.
val Accent2 = BrandPalette.secondary
val Accent3 = BrandPalette.accent

// The INFO toast. A fixed #2E86C1 blue here was the one colour on that toast
// that no brand owned; `secondary` is the informational role every manifest
// already carries. The two unused shades beside it are deleted.
val Interactive1 = BrandPalette.secondary

// Success Colors
// Success is a brand colour like any other: RedVPN's is a teal, DollarVPN's is
// its own deep green. A fixed Material green here put the same green in five
// different products.
val Success1 = BrandPalette.success

// The WARNING toast, now the same `warning` role the data meter uses, so
// "running low" is one colour across the product instead of two oranges that
// happened to look alike. The unused shades beside it are deleted.
val Warning1 = BrandPalette.warning

// Danger Colors
val Danger = BrandPalette.danger

// The "legacy aliases for backward compatibility" block that stood here was 14
// names with ZERO readers between them, two of which (#1A2B2D, #243B3E) were
// unowned dark teals waiting to be picked up by whoever needed a dark surface
// next. Nothing is compatible with an alias nobody calls.
