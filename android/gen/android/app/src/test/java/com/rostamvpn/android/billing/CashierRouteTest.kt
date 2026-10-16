// Copyright 2026 Internet Mastering & Company, Inc.
// SPDX-License-Identifier: Apache-2.0

package com.rostamvpn.android.billing

import com.rostamvpn.android.billing.CashierApi.Route
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which cashier calls may leave the device while the tunnel is down. */
class CashierRouteTest {
    @Test
    fun registrationWaitsForTheTunnelEvenOnFirstRun() {
        assertTrue(CashierApi.deferred(Route.TUNNEL, paidOnly = false, tunnelUp = false) { false })
        assertTrue(CashierApi.deferred(Route.TUNNEL, paidOnly = false, tunnelUp = false) { true })
        assertFalse(CashierApi.deferred(Route.TUNNEL, paidOnly = false, tunnelUp = true) { false })
    }

    @Test
    fun theOtherGatesAreUnchanged() {
        assertFalse(CashierApi.deferred(Route.UNTIL_PROFILE, paidOnly = false, tunnelUp = false) { false })
        assertTrue(CashierApi.deferred(Route.UNTIL_PROFILE, paidOnly = false, tunnelUp = false) { true })
        assertFalse(CashierApi.deferred(Route.ANY, paidOnly = false, tunnelUp = false) { true })
        assertFalse(CashierApi.deferred(Route.TUNNEL, paidOnly = true, tunnelUp = false) { true })
    }
}
