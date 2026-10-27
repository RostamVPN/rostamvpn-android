package com.rostamvpn.android.ui.screens

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The server keeps a row only when its name is in api.py EVENT_NAMES and
 * `source` is web|ios|android. Names copied from the deployed server.
 */
class EsimEventsTest {
    private val serverNames = setOf(
        "landing_view", "plan_recommended", "plan_changed", "see_all_plans", "pay_sheet_open", "pay_success",
        "install_step", "install_error", "first_attach", "topup_prompt_shown", "topup_accepted", "widget_added", "search", "asa_token",
    )

    @Test fun everyNameIsOneTheServerKeeps() {
        listOf(
            EsimEvents.SEARCH, EsimEvents.PAY_SHEET_OPEN, EsimEvents.PAY_SUCCESS, EsimEvents.INSTALL_STEP,
            EsimEvents.FIRST_ATTACH, EsimEvents.TOPUP_PROMPT_SHOWN, EsimEvents.TOPUP_ACCEPTED,
        ).forEach { assertTrue(it, it in serverNames) }
    }

    @Test fun bodyShape() {
        val o = JSONObject(eventBody("abc123", "search", "JP", mapOf("q" to "jap", "n" to 2, "skip" to null)))
        val e = o.getJSONArray("events").getJSONObject(0)
        assertEquals("search", e.getString("name"))
        assertEquals("abc123", e.getString("device_id"))
        assertEquals("JP", e.getString("dest"))
        assertEquals("android", e.getString("source"))
        assertEquals("jap", e.getJSONObject("props").getString("q"))
        assertTrue(!e.getJSONObject("props").has("skip"))
    }
}
