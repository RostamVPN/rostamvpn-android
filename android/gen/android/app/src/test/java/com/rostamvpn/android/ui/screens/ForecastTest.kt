package com.rostamvpn.android.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ForecastTest {
    private val day = 24L * 60 * 60 * 1000
    private val now = 1_800_000_000_000L

    private fun plan(slug: String, mb: Long, cents: Long, unlimited: Boolean = false) =
        PassPlan(slug, "JP", mb, unlimited, 7, cents, 0, 0)

    @Test fun burnsTooFast() {
        // 10-day plan, 4 days in, 3 GB of 5 GB used, 6 days left: pace 768 MB/day.
        val f = forecast(5120, 2048, 10, now + 6 * day, now)
        assertNotNull(f)
        // Need 6 * 768 = 4608, have 2048: short 2560 MB. Runs out in 2048/768 days.
        assertEquals(2560L, f!!.shortfallMb)
        assertEquals((now + 2048.0 / 768 * day).toDouble(), f.runOutMs.toDouble(), 2_000.0)
    }

    @Test fun lastsOrUnknown() {
        assertNull(forecast(5120, 4096, 10, now + 6 * day, now))       // 1 GB in 4 days, 4 GB for 6: fine
        assertNull(forecast(5120, 2048, 10, null, now))                // not attached yet
        assertNull(forecast(0, 0, 10, now + 6 * day, now))             // unlimited
        assertNull(forecast(5120, 2048, null, now + 6 * day, now))     // old server: no days
        assertNull(forecast(5120, 4000, 10, now + 9 * day + day / 2, now)) // under a day of history
        assertNull(forecast(5120, 0, 10, now + 6 * day, now))          // already used up: the Top up button says it
        assertNull(forecast(5120, 2048, 10, now - day, now))           // expired
    }

    @Test fun cheapestTopupThatCovers() {
        val t = listOf(plan("a1", 1024, 450), plan("a3", 3072, 900), plan("a5", 5120, 1300), plan("u", 0, 2000, true))
        assertEquals("a3", suggestTopup(t, 2560)!!.slug)
        assertEquals("a1", suggestTopup(t, 100)!!.slug)
        assertEquals("u", suggestTopup(t, 9000)!!.slug)
        assertNull(suggestTopup(t.take(2), 9000))
        assertNull(suggestTopup(emptyList(), 10))
    }
}
