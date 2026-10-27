package com.rostamvpn.android.ui.screens

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A returned esimnet://me#session= is stored only for a sign-in this app started, within 15 min, once. */
class SignInGateTest {
    private val t0 = 1_800_000_000_000L
    private val min = 60_000L

    @Test fun coldLinkIsRefused() {
        // Never started a sign-in (a link from a message, a page, another app).
        assertFalse(SignInGate(null).offer(t0))
        assertFalse(acceptReturnedSession(null, t0))
    }

    @Test fun inWindowIsAcceptedOnce() {
        val g = SignInGate(t0)
        assertTrue(g.offer(t0 + 3 * min))
        // A second link after the real return: refused.
        assertFalse(g.offer(t0 + 4 * min))
    }

    @Test fun windowEdges() {
        assertTrue(SignInGate(t0).offer(t0 + 15 * min))
        assertFalse(SignInGate(t0).offer(t0 + 15 * min + 1))
        assertFalse(SignInGate(t0).offer(t0 - 1))          // clock moved back: refuse
    }
}
