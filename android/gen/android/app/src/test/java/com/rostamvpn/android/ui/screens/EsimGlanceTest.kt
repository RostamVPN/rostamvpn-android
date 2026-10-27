package com.rostamvpn.android.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EsimGlanceTest {
    private val now = 1_800_000_000_000L
    private val day = 24L * 60 * 60 * 1000
    private fun fmt(ms: Long) = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
        .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.format(java.util.Date(ms))
    private fun e(iccid: String, usage: String, exp: Long?, number: Boolean = false, total: Long = 5120, rem: Long = 2048) =
        Esim(iccid, "🇯🇵 Japan", "active", "LPA:1\$smdp\$SECRET", "smdp", "SECRET", rem, total, usage, expiredAt = exp?.let(::fmt),
            level = if (number) "data-voice-text" else "data", topupGraceDays = if (number) 180 else 0,
            remainingVoice = if (number) 40 else null, remainingText = if (number) 9 else null)

    @Test fun activeLineFirst() {
        val g = esimGlance(listOf(e("a", "NOT_ACTIVE", null), e("b", "ACTIVE", now + 3 * day)), { null }, now)!!
        assertEquals(2048L, g.dataLeftMb); assertEquals(now + 3 * day, g.endsAtMs); assertNull(g.graceDaysLeft)
    }

    @Test fun numberInGraceGetsTheWarningOnlyWithReloads() {
        val n = e("n", "EXPIRED", now - 10 * day, number = true)
        assertEquals(170, esimGlance(listOf(n), { 3 }, now)!!.graceDaysLeft)
        assertNull(esimGlance(listOf(n), { null }, now))        // unknown: say nothing
        assertNull(esimGlance(listOf(n), { 0 }, now))           // cannot be reloaded: gone
    }

    @Test fun nothingWorthShowing() {
        assertNull(esimGlance(listOf(e("a", "NOT_ACTIVE", null), e("b", "FINISHED", now + day)), { 2 }, now))
        assertNull(esimGlance(emptyList(), { null }, now))
    }

    @Test fun storedFormHasNoCodes() {
        val g = esimGlance(listOf(e("b", "ACTIVE", now + 3 * day, number = true)), { null }, now)!!
        val json = EsimGlanceStore.toJson(g)
        assertEquals(g, EsimGlanceStore.fromJson(json))
        org.junit.Assert.assertFalse(json.contains("SECRET") || json.contains("LPA") || json.contains("\"b\""))
    }
}
