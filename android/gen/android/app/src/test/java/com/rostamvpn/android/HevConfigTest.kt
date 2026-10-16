// Copyright 2026 Internet Mastering & Company, Inc.
// SPDX-License-Identifier: Apache-2.0

package com.rostamvpn.android

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The tun bridge's config carries the port the core bound, never a default. */
class HevConfigTest {
    @Test
    fun theBridgeDialsThePortItIsGiven() {
        val yml = hevConfig(33905, "/data/hev.log", udp = false)
        assertTrue(yml.contains("socks5:\n  address: '127.0.0.1'\n  port: 33905\n"))
        assertFalse(yml.contains("1080"))
    }

    @Test
    fun withoutALogFileTheBridgeKeepsNoLog() {
        val yml = hevConfig(33905, null, udp = false)
        assertTrue(yml.contains("misc:\n  task-stack-size: 81920\n  log-level: warn\n"))
        assertFalse(yml.contains("log-file"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun noPortIsRefused() {
        hevConfig(0, "/data/hev.log", udp = false)
    }
}
