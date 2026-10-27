package com.rostamvpn.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveStatusTest {
    @Test fun states() {
        assertEquals(LiveStatus.State.PROTECTED, LiveStatus.stateOf(VpnViewModel.ConnectionState.Connected("http"), true))
        assertEquals(LiveStatus.State.CONNECTED, LiveStatus.stateOf(VpnViewModel.ConnectionState.Connected("http"), false))
        assertEquals(LiveStatus.State.CONNECTING, LiveStatus.stateOf(VpnViewModel.ConnectionState.Verifying, false))
        assertEquals(LiveStatus.State.CONNECTING, LiveStatus.stateOf(VpnViewModel.ConnectionState.Connecting(), false))
        assertEquals(LiveStatus.State.OFF, LiveStatus.stateOf(VpnViewModel.ConnectionState.Failed("x"), true))
    }

    @Test fun bytesAloneAreNotAVisibleChange() {
        val a = LiveStatus.Snapshot(LiveStatus.State.CONNECTED, "Auto", 3)
        assertFalse(LiveStatus.visibleChange(a, a.copy(rxBytes = 9_000, txBytes = 1)))
        assertTrue(LiveStatus.visibleChange(a, a.copy(blockedToday = 4)))
        assertTrue(LiveStatus.visibleChange(a, a.copy(state = LiveStatus.State.PROTECTED)))
    }
}
