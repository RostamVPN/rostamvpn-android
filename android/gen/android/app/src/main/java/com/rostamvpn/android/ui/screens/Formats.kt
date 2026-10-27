package com.rostamvpn.android.ui.screens

import java.text.NumberFormat

/**
 * Number formatting shared by the home meter and the paywall.
 *
 * Two rules, both learned on this app's own screens:
 *
 * 1. Every number goes through [NumberFormat] with the DEFAULT locale rather
 *    than `"%d".format(...)`. The largest audience for this app reads Persian,
 *    where the count is ۱۵٬۶۰۰ and the decimal separator is ٫ — a screen that
 *    mixes those with Latin digits reads as a bug, not as a translation.
 *
 * 2. Every result is bidi-ISOLATED, because all of them are dropped into
 *    translated sentences. See [isolate].
 *
 * The unit symbols ("Mbps", "GB") are deliberately literal, matching `formatMb`
 * in MainScreen.kt: they are unit abbreviations rather than copy, and in most of
 * the 38 locales "translating" them would change what they mean.
 */

private const val FSI = '\u2068'   // FIRST STRONG ISOLATE
private const val PDI = '\u2069'   // POP DIRECTIONAL ISOLATE

/**
 * Wrap a formatted value so the bidi algorithm cannot let it reorder the
 * sentence it is dropped into.
 *
 * Every value here lands inside a TRANSLATED sentence — "%1$s left", "Limited to
 * %1$s", "Over %1$s downloads" — and a third of the shipped locales set those
 * sentences right to left. An un-isolated run like "2 Mbps" (Persian digits then
 * a Latin word) pulls the neighbouring neutral characters into itself and throws
 * the sentence's own punctuation to the wrong side. Same mechanism and same fix
 * as `bidi()` in PaywallScreen.kt, which exists because it had already happened
 * to every Persian sentence on that screen.
 */
internal fun isolate(s: String): String = if (s.isEmpty()) s else "$FSI$s$PDI"

/**
 * A link speed the user can evaluate, from the kbps the core is actually
 * rate-limited to.
 *
 * "Reduced speed" is a label; "2 Mbps" is a fact — and that is the whole reason
 * this exists. The free tier is not cut off when the allowance ends, it is held
 * at this number, and a user who is told the number can decide whether it
 * matters to them. One who is only told "reduced" concludes the VPN is broken.
 *
 * Returns null for a non-positive value, so no caller can print "0 Mbps" or make
 * a speed claim the config does not support.
 */
internal fun formatSpeed(kbps: Int): String? {
    if (kbps <= 0) return null
    val nf = NumberFormat.getInstance()
    return isolate(
        if (kbps >= 1000) {
            nf.maximumFractionDigits = 1
            "${nf.format(kbps / 1000.0)} Mbps"
        } else {
            nf.maximumFractionDigits = 0
            "${nf.format(kbps)} kbps"
        }
    )
}

/** A whole count (downloads, reviews) in the device's own numbering system. */
internal fun formatCount(n: Int): String =
    isolate(NumberFormat.getInstance().apply { maximumFractionDigits = 0 }.format(n))

/** A star rating held as tenths, so the decimal separator follows the locale
 *  (Persian uses ٫, not "."). 41 -> "4.1" / "۴٫۱". */
internal fun formatRating(tenths: Int): String = isolate(
    NumberFormat.getInstance().apply {
        minimumFractionDigits = 1
        maximumFractionDigits = 1
    }.format(tenths / 10.0)
)
