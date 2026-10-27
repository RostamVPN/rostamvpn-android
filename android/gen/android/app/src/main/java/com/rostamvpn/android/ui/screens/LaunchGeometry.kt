package com.rostamvpn.android.ui.screens

import androidx.compose.ui.unit.dp

/**
 * Numbers shared by the splash THEME and [LaunchOverlay].
 *
 * iOS has a LaunchGeometry.swift for the same reason: "change one, change the
 * other -- if they disagree the logo visibly jumps at the hand-off, which is the
 * one thing a splash must never do."
 *
 * Android needs far less of it than iOS does, because the artwork itself carries
 * the geometry. `launch_mark` and `launch_wordmark` are generated into ONE shared
 * square canvas by tools/whitelabel/gen-launch.py, the theme hands `launch_mark`
 * to `windowSplashScreenAnimatedIcon`, and the overlay draws BOTH drawables in a
 * box of that same size at the same window centre. So the mark's inset, the
 * wordmark's gap, its width and its horizontal offset are not constants here or
 * anywhere in Kotlin -- they are pixels in two files that were cut from one
 * measurement. Only [SLOT] and the light's own shape are code.
 */
object LaunchGeometry {

    /**
     * The system splash's icon slot. NOT a guess:
     *
     *  - core-splashscreen's own values.xml sets
     *    `splashScreenIconSize` = `splashscreen_icon_size_no_background` = 288dp,
     *    which is what the compat path (API < 31) draws, with a mask aperture of
     *    `splashscreen_icon_mask_size_no_background` 410dp minus 2x109dp stroke =
     *    192dp, exactly 2/3 of the slot;
     *  - it is also Google's documented slot for an icon with no icon background;
     *  - measured on SM-A166U1 (Android 16, 450dpi): the platform's `iconView` is
     *    540px = 192dp (the KEYLINE, not the canvas), and the drawable is drawn
     *    into it at 1.5x, so a 288dp canvas maps onto that 192dp aperture. Art
     *    authored at fraction f of the canvas therefore renders at f * 288dp.
     *    Confirmed both ways: the old baked icon (art at 89.8% of its canvas)
     *    matched a 288dp render to 5px in 810, and this design's mark measures
     *    475px on screen against 0.5846 * 810px = 473.5px predicted.
     *
     * So the overlay draws the same drawable in a box of 288dp and the two sides
     * land on the same pixels. [KEYLINE] is only used to check that claim at
     * runtime: the library's dimen is not in its public.txt and resolves through
     * a theme attribute only the splash theme sets, so it cannot be read from the
     * app's own theme. A mismatch on some other OEM shows up as a size step at
     * hand-off 1 and as a line in logcat, never as a flash.
     */
    val SLOT = 288.dp

    /**
     * The visible aperture, as a fraction of [SLOT]: 192dp of 288dp.
     *
     * Read ONLY by MainActivity's log line. The generator
     * (tools/whitelabel/gen-launch.py) holds the same 2/3 as its own upper bound
     * on how much of the canvas a mark may occupy, and nothing in the overlay's
     * drawing uses it, because the drawable carries the inset itself.
     */
    const val KEYLINE = 2f / 3f

    /**
     * How much of the slot the MARK may occupy when it is sitting on the light
     * field disc ([RingsEntry]'s `markNeedsLightField`).
     *
     * The disc is a fixed `0.315 * SLOT` in radius, so 0.630 across. RedVPN's
     * shield fills 0.550 of the mark canvas, which put its bounding box at
     * 0.550 / 0.630 = **87% of the disc's diameter**. A square inscribed in a
     * circle cannot exceed 70.7%, so a mark with square-ish shoulders was
     * always going to cross the edge, and on device it did: the shield broke
     * out of the white disc on both sides and its point ran to the bottom rim.
     *
     * 0.825 lands the bounding box at 0.72 of the disc, which is exactly where
     * the connect dial already puts it (`brand_mark_scale` 0.725). The entry is
     * supposed to be a promise the home screen keeps, so the two now agree by
     * construction rather than by coincidence.
     *
     * ⚠️ It insets the MARK ONLY. The wordmark keeps the full slot: where the
     * brand name sits must not move because the shield needed breathing room.
     */
    const val LIGHT_FIELD_MARK = 0.825f

    /**
     * The specular pass, in units of the slot's side.
     *
     * A narrow, fast SPECULAR, not a key light -- the same reasoning as iOS: the
     * artwork's split lighting has inconsistent polarity (shield left-lit; face,
     * beard and gold faintly right-lit), so a broad directional gradient would
     * contradict the face it crosses and could not be mirrored for RTL without
     * explaining the split backwards. A moving specular makes no claim about
     * where the sun is.
     *
     * [SHEEN_TRAVEL] is 0.60 rather than iOS's 1.05-of-lockup-width because our
     * box is the 288dp slot while the art inside it is narrower. At 0.60 the
     * sheen's clear trailing edge starts just OUTSIDE the art and ends just
     * outside it, so t=0 and the final hold are both free of added light -- which
     * is what lets t=0 be identical to the system splash.
     */
    const val SHEEN_W = 0.70f
    const val SHEEN_H = 1.20f
    const val SHEEN_TRAVEL = 0.60f
    const val SHEEN_SHEAR = 18f

    /**
     * The wordmark's reveal window, as a fraction of [SWEEP]. Fractions rather
     * than milliseconds so the name can only ever arrive inside the pass that
     * writes it, whatever SWEEP becomes.
     */
    const val NAME_IN = 0.30f
    const val NAME_SPAN = 0.40f

    /**
     * Beats, in ms. ~1.6s total against iOS's ~3.0s.
     *
     * Deliberately shorter: this plays over a live, already-interactive screen on
     * a platform that is slower to first frame, and three seconds of decoration
     * over a working Connect button is the long kind of ugly. It is not a
     * promise about timing either way -- the overlay blocks nothing, and a touch
     * ends it immediately.
     */
    const val SETTLE = 150
    const val SWEEP = 900
    const val HOLD = 300
    const val DEPART = 250

    /** Departure scale, from iOS: a 3.5% lift as it goes. */
    const val DEPART_SCALE = 1.035f
}
