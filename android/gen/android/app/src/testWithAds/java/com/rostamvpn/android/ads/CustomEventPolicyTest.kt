package com.rostamvpn.android.ads

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two decisions that decide whether an AdMob slot earns anything. Getting
 * either wrong fails silently: a no-fill reported as a fill starves every
 * network below us in the waterfall, and a house line misread as the paid line
 * serves an unpaid creative at a paid eCPM.
 */
class CustomEventPolicyTest {

    @Test fun `house line is selected only by the house parameter`() {
        assertTrue(CustomEventPolicy.isHouseLine("house"))
        assertTrue(CustomEventPolicy.isHouseLine("House"))
        assertTrue(CustomEventPolicy.isHouseLine("  house  "))
        // Blank or absent is the PAID line; defaulting to house would serve an
        // unpaid creative at the paid line's eCPM.
        assertFalse(CustomEventPolicy.isHouseLine(null))
        assertFalse(CustomEventPolicy.isHouseLine(""))
        assertFalse(CustomEventPolicy.isHouseLine("paid"))
    }

    @Test fun `the no-fill sentinel is not a fill`() {
        // Arrives as HTTP 200 with the sentinel in the body, so status can't decide.
        assertNull(CustomEventPolicy.markupOrNull(
            """<html><script>location.replace("rostamvpn://ad-nofill")</script></html>"""))
        assertNull(CustomEventPolicy.markupOrNull(null))
        assertNull(CustomEventPolicy.markupOrNull(""))
        assertNull(CustomEventPolicy.markupOrNull("   "))
    }

    @Test fun `real markup passes through unchanged`() {
        val creative = """<html><body><iframe src="/static/ads/01/index.html"></iframe></body></html>"""
        assertEquals(creative, CustomEventPolicy.markupOrNull(creative))
    }
}
