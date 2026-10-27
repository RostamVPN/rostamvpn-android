package com.rostamvpn.android.ui.screens

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Top up control follows the reload state: never under "This number has ended". */
class CardTopupTest {
    private val now = 1_800_000_000_000L
    private val day = 24L * 60 * 60 * 1000
    private fun fmt(ms: Long) = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
        .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.format(java.util.Date(ms))
    private fun e(status: String, expMs: Long?, recycled: Boolean = false, grace: Int? = 180) = Esim(
        "8901", "", "active", "", "", "", 100, 1024, status, expiredAt = expMs?.let(::fmt), recycled = recycled,
        level = "data-voice-text", topupGraceDays = grace,
    )

    @Test fun recycledOrPastGraceHasNoTopup() {
        assertFalse(canTopUp(cardReloadState(e("RECYCLED", now - 10 * day, recycled = true), null, 5, now)))
        assertFalse(canTopUp(cardReloadState(e("EXPIRED", now - 200 * day), null, 5, now)))
    }

    @Test fun reloadableAndInGraceHaveIt() {
        assertTrue(canTopUp(cardReloadState(e("ACTIVE", now + 3 * day), null, 2, now)))
        assertTrue(canTopUp(cardReloadState(e("EXPIRED", now - 3 * day), null, 2, now)))
    }

    @Test fun noTopupsOrUnknownHasNone() {
        assertFalse(canTopUp(cardReloadState(e("ACTIVE", now + 3 * day), null, 0, now)))
        assertFalse(canTopUp(cardReloadState(e("ACTIVE", now + 3 * day), null, null, now)))
        // A data line with no grace, expired: gone, no top-up.
        assertFalse(canTopUp(cardReloadState(e("EXPIRED", now - day, grace = 0), null, 3, now)))
        // A used-up data line still inside validity, network reloadable: offered.
        assertTrue(canTopUp(cardReloadState(e("FINISHED", now + 2 * day, grace = 0), null, 3, now)))
    }
}
