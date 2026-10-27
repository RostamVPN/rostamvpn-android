package com.rostamvpn.android

import com.rostamvpn.android.LiveStatus.Snapshot
import com.rostamvpn.android.LiveStatus.State
import com.rostamvpn.android.ui.screens.EsimGlance
import org.junit.Assert.assertEquals
import org.junit.Test

class LiveNotificationTextTest {
    private val now = 1_800_000_000_000L
    private val day = 24L * 60 * 60 * 1000
    private val on = Snapshot(State.PROTECTED, "Automatic · fastest", 12, 10, 20, true)

    @Test fun travellerOrder() {
        val g = EsimGlance("🇯🇵 Japan", 2048, false, now + 3 * day - 1000, false, null, null, null)
        val l = LiveNotificationText.lines(on, g, "2 GB", true, "1 MB", "2 MB", now)
        assertEquals(listOf(R.string.tabs_live_esim_left, R.string.tabs_live_days_left, R.string.tabs_live_route,
            R.string.tabs_widget_blocked, R.string.tabs_live_session), l.map { it.res })
        assertEquals(listOf<Any>(3), l[1].args)
    }

    @Test fun numberLineAndGrace() {
        val n = EsimGlance("🇺🇸 US", 500, false, now + day, true, 40, 9, null)
        assertEquals(R.string.tabs_voice_sms_left, LiveNotificationText.lines(on, n, "500 MB", false, "", "", now)[2].res)
        val grace = n.copy(graceDaysLeft = 30)
        assertEquals(listOf(R.string.tabs_reload_within, R.string.tabs_live_route, R.string.tabs_live_session),
            LiveNotificationText.lines(on, grace, "500 MB", false, "", "", now).map { it.res })
    }

    @Test fun noEsimNoProtectionConnecting() {
        val l = LiveNotificationText.lines(on.copy(state = State.CONNECTING), null, null, false, "", "", now)
        assertEquals(listOf(R.string.tabs_live_route), l.map { it.res })
    }
}
