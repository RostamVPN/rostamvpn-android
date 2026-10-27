package com.rostamvpn.android.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Server: 200 {"topups":[...]} or [] (not reloadable); 503 {"topups": null} on an upstream failure. */
class TopupsUnknownTest {
    private val now = 1_800_000_000_000L
    private val day = 24L * 60 * 60 * 1000

    @Test fun nullAndMissingAreUnknownEmptyIsNone() {
        assertNull(parseTopups("""{"topups": null}"""))
        assertNull(parseTopups("""{}"""))
        assertTrue(parseTopups("""{"topups": []}""")!!.isEmpty())
        assertEquals(1, parseTopups("""{"topups":[{"slug":"t1","data_mb":1024,"days":7,"price_cents":450}]}""")!!.size)
    }

    @Test fun unknownMakesNoClaim() {
        // Live line, top-ups unknown: no reload button, no "cannot be reloaded".
        assertEquals(ReloadState.Unknown, reloadState("ACTIVE", now + 5 * day, null, 180, false, null, now))
        // Expired inside grace, top-ups unknown: no "ended" either.
        assertEquals(ReloadState.Unknown, reloadState("EXPIRED", now - day, null, 180, false, null, now))
        // [] is a real answer.
        assertEquals(ReloadState.NotReloadable(now + 5 * day), reloadState("ACTIVE", now + 5 * day, null, 180, false, 0, now))
    }
}
