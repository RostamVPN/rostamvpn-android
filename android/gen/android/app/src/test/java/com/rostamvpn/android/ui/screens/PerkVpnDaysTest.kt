package com.rostamvpn.android.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** No "Includes N days of …" unless the server sends perk.vpn_days (grant configured). */
class PerkVpnDaysTest {
    private val plan = """{"slug":"jp-5gb-30d","destination_id":"JP","data_mb":5120,"days":30,"price_cents":1300}"""

    @Test fun absentMeansNoPromise() {
        assertTrue(parsePlans("""{"plans":[$plan]}""").all { it.vpnDays == null })
        assertNull(parsePick("""{"plan":$plan,"need_gb":3.5}""")!!.plan.vpnDays)
        // Present but empty, null, zero or negative: still no promise.
        listOf("""{}""", """{"vpn_days":null}""", """{"vpn_days":0}""", """{"vpn_days":-3}""").forEach { perk ->
            assertNull(perk, parsePlans("""{"perk":$perk,"plans":[$plan]}""").single().vpnDays)
        }
    }

    @Test fun presentUsesTheServersNumber() {
        // On the plan.
        val own = plan.dropLast(1) + ""","perk":{"vpn_days":30}}"""
        assertEquals(30, parsePlans("""{"plans":[$own]}""").single().vpnDays)
        // On the destination: applies to plans that carry none; a plan's own wins.
        val ownTen = plan.dropLast(1) + ""","perk":{"vpn_days":10}}"""
        assertEquals(listOf(7, 10), parsePlans("""{"perk":{"vpn_days":7},"plans":[$plan,$ownTen]}""").map { it.vpnDays })
        // /recommend: plan and alternatives.
        val pick = parsePick("""{"perk":{"vpn_days":30},"plan":$plan,"alternatives":{"short":$plan,"long":$plan}}""")!!
        assertEquals(listOf(30, 30, 30), listOf(pick.plan.vpnDays, pick.shorter?.vpnDays, pick.longer?.vpnDays))
    }
}
