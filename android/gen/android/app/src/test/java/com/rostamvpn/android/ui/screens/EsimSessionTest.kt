package com.rostamvpn.android.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EsimSessionTest {
    @Test fun readsTheDecodedFragment() {
        // auth.py: "/me#session=" + quote("email|exp|hmac"); Uri.getFragment() decodes it once.
        val tok = "a@b.co|1800000000|" + "f".repeat(64)
        assertEquals(tok, sessionFromFragment("session=$tok"))
        assertEquals(tok, sessionFromFragment("x=1&session=$tok"))
    }

    @Test fun refusesAnythingElse() {
        assertNull(sessionFromFragment(null))
        assertNull(sessionFromFragment(""))
        assertNull(sessionFromFragment("session="))
        assertNull(sessionFromFragment("session=not-a-session"))
        assertNull(sessionFromFragment("token=a@b.co|1|" + "f".repeat(64)))
    }

    @Test fun parsesMe() {
        val m = parseMe("""{"signed_in":true,"email":"a@b.co","apple":true,"google":false}""")
        assertTrue(m.signedIn); assertEquals("a@b.co", m.email); assertTrue(m.apple); assertFalse(m.google)
        assertFalse(parseMe("""{"signed_in":false,"email":"","apple":false,"google":true}""").signedIn)
    }
}
