package com.rostamvpn.android.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Test

class ExpiryLineTest {
    private fun e(row: String, usage: String, exp: String? = null) = Esim("1", "", row, "", "", "", null, null, usage, expiredAt = exp)

    @Test fun usageMissingFallsBackToTheRow() {
        assertEquals(ExpiryLine.Unavailable, expiryLine(e("active", "")))
        assertEquals(ExpiryLine.Unavailable, expiryLine(e("active", "UNKNOWN")))
        assertEquals(ExpiryLine.StartsOnConnect, expiryLine(e("provisioned", "")))
        assertEquals(ExpiryLine.None, expiryLine(e("expired", "")))
        assertEquals(ExpiryLine.None, expiryLine(e("depleted", "")))
    }

    @Test fun usageWins() {
        assertEquals(ExpiryLine.StartsOnConnect, expiryLine(e("active", "NOT_ACTIVE")))
        assertEquals(ExpiryLine.On(parseAiraloTime("2026-11-01 10:00:00")!!), expiryLine(e("provisioned", "ACTIVE", "2026-11-01 10:00:00")))
    }

    @Test fun rowStatusFeedsTheReloadState() {
        assertEquals("EXPIRED", effectiveStatus(e("expired", "")))
        assertEquals("FINISHED", effectiveStatus(e("depleted", "UNKNOWN")))
        assertEquals("ACTIVE", effectiveStatus(e("provisioned", "ACTIVE")))
    }
}
