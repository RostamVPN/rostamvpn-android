// Copyright 2026 Internet Mastering & Company, Inc.
// SPDX-License-Identifier: Apache-2.0

package com.rostamvpn.android.ui.screens

import java.text.NumberFormat

/** Number formatting shared by the home meter and the paywall. */

private const val FSI = '\u2068'
private const val PDI = '\u2069'

/**
 * Wrap a formatted value so the bidi algorithm cannot let it reorder the
 * sentence it is dropped into.
 */
internal fun isolate(s: String): String = if (s.isEmpty()) s else "$FSI$s$PDI"

/**
 * A link speed the user can evaluate, from the kbps the core is actually rate-
 * limited to.
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

/**
 * A star rating held as tenths, so the decimal separator follows the locale
 * (Persian uses ٫, not "."). 41 -> "4.1" / "۴٫۱".
 */
internal fun formatRating(tenths: Int): String = isolate(
    NumberFormat.getInstance().apply {
        minimumFractionDigits = 1
        maximumFractionDigits = 1
    }.format(tenths / 10.0)
)
