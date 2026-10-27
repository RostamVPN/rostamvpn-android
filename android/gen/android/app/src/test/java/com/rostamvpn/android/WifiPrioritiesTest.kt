package com.rostamvpn.android

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** `wifi_priorities` against the live rostam config, gen2 v42 (2026-10-26). */
class WifiPrioritiesTest {
    private val live = javaClass.classLoader!!.getResource("rostam.gen2.v42.json")!!.readText()
    private val irHosts = JSONObject(live).getJSONObject("wifi_priorities").getJSONObject("IR").keys().asSequence().toSet()

    /** host -> priority (or null) for every netcore entry and premium entry. */
    private fun priorities(json: String): List<Pair<String, Int?>> {
        val rs = JSONObject(json).getJSONObject("transport").getJSONObject("redsocket")
        return listOf("entries", "premium_entries").flatMap { k ->
            val a = rs.optJSONArray(k) ?: return@flatMap emptyList()
            (0 until a.length()).map { a.getJSONObject(it).let { e -> e.optString("host") to (if (e.has("priority")) e.getInt("priority") else null) } }
        }
    }

    @Test fun wifiInIranPinsAll47At15() {
        val want = wifiPins(live, onWifi = true, country = "IR")
        assertEquals(47, want.size)
        assertTrue(want.values.all { it == 15 })
        val (out, n) = applyPins(live, want)!!
        assertEquals(47, n)
        val pinned = priorities(out).filter { it.first in irHosts }
        assertEquals(47, pinned.size)
        assertTrue(pinned.all { it.second == 15 })
        // Nothing else moved.
        assertEquals(priorities(live).filter { it.first !in irHosts }, priorities(out).filter { it.first !in irHosts })
    }

    @Test fun cellularAppliesNothing() {
        assertTrue(wifiPins(live, onWifi = false, country = "IR").isEmpty())
    }

    @Test fun wifiOutsideIranAppliesNothing() {
        assertTrue(wifiPins(live, onWifi = true, country = "DE").isEmpty())
        assertTrue(wifiPins(live, onWifi = true, country = "").isEmpty())
        // The live IR pins were not already 15, so the test above proves a change.
        assertTrue(priorities(live).filter { it.first in irHosts }.none { it.second == 15 })
    }
}
