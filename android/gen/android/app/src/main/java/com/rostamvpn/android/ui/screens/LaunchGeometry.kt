// Copyright 2026 Internet Mastering & Company, Inc.
// SPDX-License-Identifier: Apache-2.0

package com.rostamvpn.android.ui.screens

import androidx.compose.ui.unit.dp

/** Numbers shared by the splash THEME and [LaunchOverlay]. */
object LaunchGeometry {
    /** The system splash's icon slot. NOT a guess. */
    val SLOT = 288.dp

    /** The visible aperture, as a fraction of [SLOT]: 192dp of 288dp. */
    const val KEYLINE = 2f / 3f

    /** The specular pass, in units of the slot's side. */
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

    /** Beats, in ms. ~1.6s total against iOS's ~3.0s. */
    const val SETTLE = 150
    const val SWEEP = 900
    const val HOLD = 300
    const val DEPART = 250

    /** Departure scale, from iOS: a 3.5% lift as it goes. */
    const val DEPART_SCALE = 1.035f
}
