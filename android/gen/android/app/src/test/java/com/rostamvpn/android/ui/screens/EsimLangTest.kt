package com.rostamvpn.android.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Locale

/** The checkout's `lang=` must be a code e-sim.net knows (app/i18n.py LANGS). */
class EsimLangTest {
    private fun tag(t: String) = esimLang(Locale.forLanguageTag(t))

    @Test fun mapsSupportedLanguages() {
        assertEquals("fa", tag("fa-IR"))
        assertEquals("fa", tag("fa"))
        assertEquals("ru", tag("ru"))
        assertEquals("en", tag("en-US"))
        assertEquals("pt", tag("pt-BR"))
    }

    @Test fun legacyAndroidCodes() {
        assertEquals("he", esimLang(Locale("iw")))
        assertEquals("id", esimLang(Locale("in")))
        assertEquals("he", tag("he"))
    }

    @Test fun chineseByScriptThenRegion() {
        assertEquals("zh-Hans", tag("zh-CN"))
        assertEquals("zh-Hant", tag("zh-TW"))
        assertEquals("zh-Hant", tag("zh-Hant"))
        assertEquals("zh-Hans", tag("zh-Hans-HK"))
        assertEquals("zh-Hant", tag("zh-HK"))
    }

    @Test fun unsupportedSendsNothing() {
        assertNull(tag("az"))
        assertNull(tag("ckb"))
        assertNull(tag("am"))
    }
}
