// Copyright 2026 Internet Mastering & Company, Inc.
// SPDX-License-Identifier: Apache-2.0

package com.rostamvpn.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger

/**
 * The route complement is the one part of split-tunnelling-by-IP that can
 * silently do the opposite of what it says: too few routes leaks the excluded
 * traffic into the tunnel, too many blackholes traffic the tun will not carry.
 */
class IpRoutingTest {
    private fun p(s: String): IpRouting.Prefix =
        requireNotNull(IpRouting.parse(s)) { "should have parsed: $s" }

    private fun addrOf(prefix: IpRouting.Prefix): BigInteger = BigInteger(1, prefix.bytes)

    private fun covers(prefix: IpRouting.Prefix, addr: BigInteger): Boolean {
        val hostBits = prefix.totalBits - prefix.length
        val start = addrOf(prefix)
        val end = start.add(BigInteger.ONE.shiftLeft(hostBits)).subtract(BigInteger.ONE)
        return addr >= start && addr <= end
    }

    private fun coveredByAny(routes: List<IpRouting.Prefix>, addr: BigInteger) =
        routes.any { covers(it, addr) }

    @Test
    fun `parses ipv4 with and without a length`() {
        assertEquals("1.2.3.0/24", IpRouting.format(p("1.2.3.0/24")))
        assertEquals("1.2.3.4/32", IpRouting.format(p("1.2.3.4")))
        assertEquals("0.0.0.0/0", IpRouting.format(p("0.0.0.0/0")))
        assertEquals("255.255.255.255/32", IpRouting.format(p("255.255.255.255")))
    }

    @Test
    fun `host bits are masked off so one network has one spelling`() {
        assertEquals("1.2.3.0/24", IpRouting.format(p("1.2.3.4/24")))
        assertEquals("10.0.0.0/8", IpRouting.format(p("10.11.12.13/8")))
        assertEquals(p("1.2.3.0/24"), p("1.2.3.255/24"))
        assertEquals("2001:db8::/32", IpRouting.format(p("2001:db8:dead:beef::1/32")))
    }

    @Test
    fun `rejects everything that is not a literal prefix`() {
        for (bad in listOf(
            "", "   ", "example.com", "example.com/24", "abcd", "1.2.3", "1.2.3.4.5",
            "1.2.3.256", "1.2.3.4/33", "1.2.3.4/-1", "1.2.3.4/abc", "1.2.3.4/",
            "1.2.3.04", "01.2.3.4", "1.2.3.4 5", "::/129", "2001:db8::/x",
            "2001::db8::1", "1:2:3:4:5:6:7:8:9", "12345::", "2001:db8:::1",
            "1.2.3.4:80", "/24", "1.2.3.4/24/24", "gggg::1"
        )) {
            assertNull("should have been rejected: '$bad'", IpRouting.parse(bad))
        }
    }

    @Test
    fun `parses the ipv6 forms that actually get typed`() {
        assertEquals("2001:db8::/32", IpRouting.format(p("2001:db8::/32")))
        assertEquals("::/0", IpRouting.format(p("::/0")))
        assertEquals("::1/128", IpRouting.format(p("::1")))
        assertEquals("fd00::/8", IpRouting.format(p("fd00::/8")))

        assertEquals("2001:db8:1:2:3:4:5:6/128", IpRouting.format(p("2001:0db8:1:2:3:4:5:6")))

        assertEquals(p("::ffff:102:304"), p("::ffff:1.2.3.4"))

        assertEquals("2001:db8::/32", IpRouting.format(p("2001:DB8::/32")))
    }

    @Test
    fun `ipv6 formatting compresses the longest zero run only`() {
        assertEquals("1:0:2::/128", IpRouting.format(p("1:0:2:0:0:0:0:0/128")))
        assertEquals("::/128", IpRouting.format(p("::/128")))
    }

    @Test
    fun `families are told apart by address shape alone`() {
        assertFalse(p("1.2.3.0/24").isV6)
        assertTrue(p("2001:db8::/32").isV6)
        assertEquals(32, p("1.2.3.0/24").totalBits)
        assertEquals(128, p("2001:db8::/32").totalBits)
    }

    @Test
    fun `an empty exclude list produces exactly the default route and nothing else`() {
        val v4 = IpRouting.complement(emptyList(), v6 = false)
        assertEquals(listOf("0.0.0.0/0"), v4.map { IpRouting.format(it) })

        val v6 = IpRouting.complement(emptyList(), v6 = true)
        assertEquals(listOf("::/0"), v6.map { IpRouting.format(it) })
    }

    @Test
    fun `a prefix covering the whole space leaves no routes at all`() {
        assertEquals(emptyList<String>(), IpRouting.complement(listOf(p("0.0.0.0/0")), v6 = false).map { it.toString() })
        assertEquals(emptyList<String>(), IpRouting.complement(listOf(p("::/0")), v6 = true).map { it.toString() })
    }

    @Test
    fun `excluding one half leaves exactly the other half`() {
        val out = IpRouting.complement(listOf(p("0.0.0.0/1")), v6 = false)
        assertEquals(listOf("128.0.0.0/1"), out.map { IpRouting.format(it) })
    }

    @Test
    fun `a single host exclusion costs exactly one route per prefix bit`() {
        val out = IpRouting.complement(listOf(p("10.0.0.1/32")), v6 = false)
        assertEquals(32, out.size)
        assertFalse(coveredByAny(out, addrOf(p("10.0.0.1/32"))))
        assertTrue(coveredByAny(out, addrOf(p("10.0.0.0/32"))))
        assertTrue(coveredByAny(out, addrOf(p("10.0.0.2/32"))))
    }

    @Test
    fun `overlapping prefixes are merged rather than double counted`() {
        val nested = IpRouting.complement(listOf(p("10.0.0.0/8"), p("10.1.0.0/16")), v6 = false)
        val alone = IpRouting.complement(listOf(p("10.0.0.0/8")), v6 = false)
        assertEquals(
            "a prefix inside another must not change the result",
            alone.map { IpRouting.format(it) },
            nested.map { IpRouting.format(it) }
        )

        val reversed = IpRouting.complement(listOf(p("10.1.0.0/16"), p("10.0.0.0/8")), v6 = false)
        assertEquals(alone.map { IpRouting.format(it) }, reversed.map { IpRouting.format(it) })
    }

    @Test
    fun `adjacent prefixes are coalesced into one hole`() {
        val split = IpRouting.complement(listOf(p("10.0.0.0/9"), p("10.128.0.0/9")), v6 = false)
        val whole = IpRouting.complement(listOf(p("10.0.0.0/8")), v6 = false)
        assertEquals(whole.map { IpRouting.format(it) }, split.map { IpRouting.format(it) })
    }

    @Test
    fun `duplicate entries change nothing`() {
        val once = IpRouting.complement(listOf(p("192.168.0.0/16")), v6 = false)
        val thrice = IpRouting.complement(
            listOf(p("192.168.0.0/16"), p("192.168.0.0/16"), p("192.168.4.5/16")), v6 = false
        )
        assertEquals(once.map { IpRouting.format(it) }, thrice.map { IpRouting.format(it) })
    }

    @Test
    fun `the two families never contaminate each other`() {
        val mixed = listOf(p("10.0.0.0/8"), p("2001:db8::/32"))

        val v4 = IpRouting.complement(mixed, v6 = false)
        assertTrue("v4 complement must hold only v4", v4.none { it.isV6 })
        assertEquals(IpRouting.complement(listOf(p("10.0.0.0/8")), v6 = false).size, v4.size)

        val v6 = IpRouting.complement(mixed, v6 = true)
        assertTrue("v6 complement must hold only v6", v6.all { it.isV6 })
        assertEquals(IpRouting.complement(listOf(p("2001:db8::/32")), v6 = true).size, v6.size)

        assertEquals(
            listOf("::/0"),
            IpRouting.complement(listOf(p("10.0.0.0/8")), v6 = true).map { IpRouting.format(it) }
        )
    }

    @Test
    fun `edges of the address space are covered`() {
        val low = IpRouting.complement(listOf(p("0.0.0.0/32")), v6 = false)
        assertFalse(coveredByAny(low, BigInteger.ZERO))
        assertTrue(coveredByAny(low, BigInteger.ONE))

        val high = IpRouting.complement(listOf(p("255.255.255.255/32")), v6 = false)
        val max = BigInteger.ONE.shiftLeft(32).subtract(BigInteger.ONE)
        assertFalse(coveredByAny(high, max))
        assertTrue(coveredByAny(high, max.subtract(BigInteger.ONE)))
    }

    @Test
    fun `covered by a route is exactly not excluded, over a dense v4 sample`() {
        val excluded = listOf(
            p("10.0.0.0/8"),
            p("172.16.0.0/12"),
            p("192.168.1.0/24"),
            p("2.144.0.0/12"),
            p("5.22.192.0/19"),
            p("217.218.0.0/15"),
            p("8.8.8.8/32")
        )
        val routes = IpRouting.complement(excluded, v6 = false)

        val probes = ArrayList<BigInteger>()
        var a = BigInteger.ZERO
        val stride = BigInteger.valueOf(65_413L)
        val max = BigInteger.ONE.shiftLeft(32)
        while (a < max) { probes.add(a); a = a.add(stride) }
        for (e in excluded) {
            val start = addrOf(e)
            val size = BigInteger.ONE.shiftLeft(e.totalBits - e.length)
            val end = start.add(size).subtract(BigInteger.ONE)
            probes.add(start)
            probes.add(end)
            if (start > BigInteger.ZERO) probes.add(start.subtract(BigInteger.ONE))
            if (end < max.subtract(BigInteger.ONE)) probes.add(end.add(BigInteger.ONE))
        }

        for (addr in probes) {
            val isExcluded = excluded.any { covers(it, addr) }
            val isRouted = coveredByAny(routes, addr)
            assertEquals("address $addr", !isExcluded, isRouted)
        }
    }

    @Test
    fun `routes never overlap each other`() {
        val routes = IpRouting.complement(
            listOf(p("10.0.0.0/8"), p("192.168.1.0/24"), p("8.8.8.8/32")), v6 = false
        )
        val sorted = routes.sortedBy { addrOf(it) }
        for (i in 0 until sorted.size - 1) {
            val cur = sorted[i]
            val end = addrOf(cur).add(BigInteger.ONE.shiftLeft(cur.totalBits - cur.length)).subtract(BigInteger.ONE)
            assertTrue(
                "${IpRouting.format(cur)} overlaps ${IpRouting.format(sorted[i + 1])}",
                end < addrOf(sorted[i + 1])
            )
        }
    }

    @Test
    fun `v6 complement holds up too`() {
        val excluded = listOf(p("2001:db8::/32"), p("fd00::/8"), p("::1/128"))
        val routes = IpRouting.complement(excluded, v6 = true)

        val probes = listOf(
            "::", "::1", "::2", "2001:db8::", "2001:db8:ffff:ffff:ffff:ffff:ffff:ffff",
            "2001:db7:ffff::", "2001:db9::", "fd00::", "fdff:ffff:ffff:ffff:ffff:ffff:ffff:ffff",
            "fcff::", "fe00::", "2606:4700::1111"
        ).map { p("$it/128") }

        for (probe in probes) {
            val addr = addrOf(probe)
            assertEquals(
                IpRouting.format(probe),
                !excluded.any { covers(it, addr) },
                coveredByAny(routes, addr)
            )
        }
    }

    @Test
    fun `no exclusions means no plan at all, not a rebuilt default route`() {
        val plan = IpRouting.planRoutes(emptyList())
        assertFalse(plan.v4Requested)
        assertFalse(plan.v6Requested)
        assertTrue(plan.v4Routes.isEmpty())
        assertTrue(plan.v6Routes.isEmpty())
        assertTrue(plan.dropped.isEmpty())
    }

    @Test
    fun `a v4-only list leaves v6 untouched`() {
        val plan = IpRouting.planRoutes(listOf(p("10.0.0.0/8")))
        assertTrue(plan.v4Requested)
        assertFalse("listing no v6 prefix must not disturb the v6 capture", plan.v6Requested)
        assertTrue(plan.v6Routes.isEmpty())
    }

    @Test
    fun `entries that do not fit the cap are reported, not silently dropped`() {
        val wanted = listOf(p("1.1.1.1/32"), p("2.2.2.2/32"), p("3.3.3.3/32"))
        val plan = IpRouting.planRoutes(wanted, maxRoutesPerFamily = 70)

        assertEquals(2, plan.applied.size)
        assertEquals(1, plan.dropped.size)
        assertEquals("3.3.3.3/32", IpRouting.format(plan.dropped[0]))
        assertTrue(plan.v4Routes.size <= 70)

        assertFalse(coveredByAny(plan.v4Routes, addrOf(p("1.1.1.1/32"))))
        assertFalse(coveredByAny(plan.v4Routes, addrOf(p("2.2.2.2/32"))))

        assertTrue(coveredByAny(plan.v4Routes, addrOf(p("3.3.3.3/32"))))
    }

    @Test
    fun `a cheap entry after an expensive one is still tried`() {
        val plan = IpRouting.planRoutes(
            listOf(p("1.1.1.1/32"), p("2.2.2.2/32"), p("10.0.0.0/8")),
            maxRoutesPerFamily = 40
        )
        assertTrue(plan.applied.any { IpRouting.format(it) == "10.0.0.0/8" })
        assertEquals(listOf("2.2.2.2/32"), plan.dropped.map { IpRouting.format(it) })
    }

    @Test
    fun `duplicates are collapsed rather than reported as dropped`() {
        val plan = IpRouting.planRoutes(listOf(p("10.0.0.0/8"), p("10.0.0.0/8"), p("10.1.2.3/8")))
        assertEquals(1, plan.applied.size)
        assertTrue(plan.dropped.isEmpty())
    }

    @Test
    fun `an absurdly long list is bounded and the overflow is reported`() {
        val many = (0 until IpRouting.MAX_USER_PREFIXES + 10).map { p("10.0.${it / 256}.${it % 256}/32") }
        val plan = IpRouting.planRoutes(many)
        assertEquals(10, plan.dropped.size - (IpRouting.MAX_USER_PREFIXES - plan.applied.size))
        assertTrue(plan.applied.size <= IpRouting.MAX_USER_PREFIXES)
        assertTrue(plan.v4Routes.size <= IpRouting.MAX_ROUTES_PER_FAMILY)
    }

    @Test
    fun `the default cap absorbs a realistic domestic exclusion list`() {
        val domestic = listOf(
            "2.144.0.0/12", "5.22.192.0/19", "5.52.0.0/16", "5.56.128.0/17",
            "31.2.128.0/17", "37.32.0.0/19", "37.98.0.0/16", "46.209.0.0/16",
            "62.60.128.0/17", "78.38.0.0/15", "80.191.0.0/16", "81.12.0.0/17",
            "85.15.0.0/17", "91.98.0.0/15", "92.242.192.0/19", "164.138.128.0/17",
            "185.55.224.0/22", "188.121.96.0/19", "217.218.0.0/15", "213.176.0.0/19"
        ).map { p(it) }

        val plan = IpRouting.planRoutes(domestic)
        assertTrue(
            "dropped ${plan.dropped.size} of ${domestic.size} at the default cap",
            plan.dropped.isEmpty()
        )
        assertTrue(plan.v4Routes.size <= IpRouting.MAX_ROUTES_PER_FAMILY)
        for (d in domestic) {
            assertFalse(IpRouting.format(d), coveredByAny(plan.v4Routes, addrOf(d)))
        }
    }

    @Test
    fun `a whole-space exclusion is representable and yields no routes`() {
        val plan = IpRouting.planRoutes(listOf(p("0.0.0.0/0")))
        assertTrue(plan.v4Requested)
        assertTrue(plan.v4Routes.isEmpty())
        assertTrue(plan.dropped.isEmpty())
    }

    @Test
    fun `mixed families are planned independently`() {
        val plan = IpRouting.planRoutes(listOf(p("10.0.0.0/8"), p("2001:db8::/32")))
        assertTrue(plan.v4Requested)
        assertTrue(plan.v6Requested)
        assertTrue(plan.v4Routes.isNotEmpty() && plan.v4Routes.none { it.isV6 })
        assertTrue(plan.v6Routes.isNotEmpty() && plan.v6Routes.all { it.isV6 })
        assertNotNull(plan.applied)
    }
}
