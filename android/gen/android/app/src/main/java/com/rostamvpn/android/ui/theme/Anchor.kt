package com.rostamvpn.android.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * "Anchor" — the approved brand identity, from the design project.
 *
 * These are deliberately SEPARATE from the app-wide palette in Color.kt. The
 * identity was approved for the entry flow (splash, welcome, sign-in) and
 * restyling every existing screen to match is a much larger change with real
 * regression risk on screens that are working today. Keeping the tokens apart
 * means the new flow is faithful to the design without touching the rest, and
 * whoever migrates the remaining screens has the values already named.
 *
 * Roles matter more than the hex here. `saffron` is the accent on DARK grounds;
 * `accentOnLight` is the accent that still carries on the light surface -- using
 * saffron for a headline on #F4F4F4 fails contrast, which is why they differ.
 */
object Anchor {
    // Surfaces
    val Surface = BrandPalette.background
    val SurfaceAlt = Color(0xFFFFFFFF)
    val Ink = BrandPalette.ink
    val InkDim = Color(0xFF3D4650)
    val Mute = Color(0xFF69717A)

    // Brand
    val Primary = BrandPalette.primary      // dark teal
    val PrimaryDeep = BrandPalette.primaryDeep
    val Success = BrandPalette.success      // green-teal — "connected", the wordmark's VPN

    // Accents
    val Saffron = BrandPalette.accent      // on dark / tinted grounds only
    val AccentOnLight = BrandPalette.accentOnLight
}
