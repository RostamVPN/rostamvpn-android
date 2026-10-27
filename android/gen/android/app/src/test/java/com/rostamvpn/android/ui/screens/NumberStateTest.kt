package com.rostamvpn.android.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NumberStateTest {
    private val day = 24L * 60 * 60 * 1000
    private val now = parseAiraloTime("2026-10-15 12:00:00")!!

    @Test fun parsesAiraloTime() {
        assertEquals(parseAiraloTime("2026-10-15 12:00:00"), parseAiraloTime("2026-10-15T12:00:00Z"))
        assertNull(parseAiraloTime(null))
        assertNull(parseAiraloTime("soon"))
    }

    @Test fun activeWithTopupsIsReloadable() =
        assertEquals(ReloadState.Reloadable, reloadState("ACTIVE", now + 5 * day, null, 180, false, 3, now))

    @Test fun notActiveBeforeFirstAttachUsesTopups() {
        assertEquals(ReloadState.Reloadable, reloadState("NOT_ACTIVE", null, null, 180, false, 2, now))
        assertEquals(ReloadState.NotReloadable(null), reloadState("NOT_ACTIVE", null, null, 180, false, 0, now))
    }

    @Test fun noTopupsEndsOnExpiry() =
        assertEquals(ReloadState.NotReloadable(now + 3 * day), reloadState("ACTIVE", now + 3 * day, null, 180, false, 0, now))

    @Test fun expiredInsideGraceCountsDaysLeft() {
        // Server's grace_until wins.
        assertEquals(ReloadState.InGrace(10), reloadState("EXPIRED", now - day, now + 10 * day, 180, false, 4, now))
        // Fallback: expired_at + topup_grace_days (NL: 90).
        assertEquals(ReloadState.InGrace(89), reloadState("EXPIRED", now - day, null, 90, false, 4, now))
    }

    @Test fun expiredPastGraceOrRecycledIsGone() {
        assertEquals(ReloadState.Gone, reloadState("EXPIRED", now - 200 * day, null, 180, false, 4, now))
        assertEquals(ReloadState.Gone, reloadState("ACTIVE", now + day, null, 180, true, 4, now))
        assertEquals(ReloadState.Gone, reloadState("RECYCLED", null, null, 180, false, null, now))
        assertEquals(ReloadState.Gone, reloadState("EXPIRED", now - day, now + 10 * day, 180, false, 0, now))
    }

    @Test fun topupsNotLoadedIsUnknown() {
        assertEquals(ReloadState.Unknown, reloadState("ACTIVE", now + day, null, 180, false, null, now))
        assertEquals(ReloadState.Unknown, reloadState("EXPIRED", now - day, null, 180, false, null, now))
    }

    @Test fun numberDestinationsPreferTheServerFieldElseTheSitemap() {
        val cat = PassCatalog(
            popular = emptyList(),
            regions = listOf(PassDest("global", "global", "Global", "", 8, 199)),
            countries = listOf(PassDest("US", "country", "United States", "", 23, 299), PassDest("NL", "country", "Netherlands", "", 6, 599)),
        )
        val sitemap = "<loc>https://e-sim.net/number/us</loc><loc>https://e-sim.net/number/nl</loc><loc>https://e-sim.net/number/global</loc>"
        assertEquals(setOf("US", "NL", "global"), numberDestinationIds(cat, sitemap))
        val flagged = cat.copy(countries = listOf(PassDest("US", "country", "United States", "", 23, 299, hasNumbers = true),
            PassDest("NL", "country", "Netherlands", "", 6, 599, hasNumbers = false)))
        assertEquals(setOf("US"), numberDestinationIds(flagged, sitemap))
    }
}
