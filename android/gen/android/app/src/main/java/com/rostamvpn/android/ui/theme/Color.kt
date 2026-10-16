// Copyright 2026 Internet Mastering & Company, Inc.
// SPDX-License-Identifier: Apache-2.0

package com.rostamvpn.android.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

/** [c]'s HUE, re-lit at [s] saturation and [l] lightness. */
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
 * acceptable brand-wise and only legibility decides — e.g.
 */
fun mostLegibleOn(bg: Color, a: Color, b: Color): Color =
    if (contrastRatio(a, bg) >= contrastRatio(b, bg)) a else b

/**
 * [preferred] when it clears [min] against [bg], otherwise [fallback]. Use
 * when one candidate is the DESIRED one (the brand colour) and the other is
 * only a safety net.
 */
fun legibleOn(bg: Color, preferred: Color, fallback: Color, min: Float = 4.5f): Color =
    if (contrastRatio(preferred, bg) >= min) preferred else fallback

/**
 * [c] blended toward [toward] by the SMALLEST amount that clears [min] against
 * [bg]. Returns [c] untouched when it already clears.
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

val DarkBase = BrandPalette.ink

val LightText = tone(BrandPalette.primary, 0.21f, 0.37f)

val BaseBg = BrandPalette.background
val LightGray1 = Color(0xFFEDEDED)
val LightGray2 = Color(0xFFE0E0E0)
val LightGray3 = Color(0xFFD5D5D5)

val Brand1 = BrandPalette.primary
val Brand2 = BrandPalette.secondary

val Accent1 = BrandPalette.success

val Accent2 = BrandPalette.secondary
val Accent3 = BrandPalette.accent

val Interactive1 = BrandPalette.secondary

val Success1 = BrandPalette.success

val Warning1 = BrandPalette.warning

val Danger = BrandPalette.danger

