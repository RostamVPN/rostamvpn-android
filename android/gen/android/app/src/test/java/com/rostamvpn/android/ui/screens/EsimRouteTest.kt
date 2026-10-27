package com.rostamvpn.android.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EsimRouteTest {
    private fun web(path: String) = esimRouteFor("https", "e-sim.net", path.trim('/').split('/').filter { it.isNotEmpty() }, siteHost = "e-sim.net")
    private fun app(host: String, vararg segs: String) = esimRouteFor("esimnet", host, segs.toList())

    @Test fun sitePaths() {
        assertEquals(EsimRoute(dest = "jp"), web("/esim/jp"))
        assertEquals(EsimRoute(dest = "europe"), web("/go/europe"))
        assertEquals(EsimRoute(dest = "us", number = true), web("/number/us"))
        assertEquals(EsimRoute(collection = "winter-markets"), web("/c/winter-markets"))
        assertEquals(EsimRoute(iccid = "8901", reload = true), web("/topup/8901"))
        assertEquals(EsimRoute(mine = true, orderId = "ord1"), web("/install/ord1"))
        assertEquals(EsimRoute(mine = true), web("/me"))
        assertEquals(EsimRoute(), web("/esim"))
    }

    @Test fun localePrefixIsDropped() {
        assertEquals(EsimRoute(mine = true, orderId = "ord1"), web("/fa/install/ord1"))
        assertEquals(EsimRoute(dest = "jp"), web("/zh-Hans/esim/jp"))
        assertEquals(EsimRoute(mine = true), web("/de/me"))
        // "go" looks like a locale code; it is a route word, never a prefix.
        assertEquals(EsimRoute(dest = "europe"), web("/go/europe"))
    }

    @Test fun appScheme() {
        assertEquals(EsimRoute(mine = true, orderId = "ord9"), app("install", "ord9"))
        assertEquals(EsimRoute(mine = true), app("me"))
        assertEquals(EsimRoute(dest = "GB", number = true), app("number", "GB"))
    }

    @Test fun notOurs() {
        assertNull(esimRouteFor("https", "evil.example", listOf("install", "x")))
        assertNull(esimRouteFor("http", "e-sim.net", listOf("me"), siteHost = "e-sim.net"))
        assertNull(esimRouteFor("rostam", "c", listOf("claim")))
        assertNull(esimRouteFor(null, null, emptyList()))
    }
}
