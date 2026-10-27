package com.rostamvpn.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WebLinksTest {
    private val hosts = listOf("vpn.red", "www.vpn.red")

    private fun link(url: String, host: String = "vpn.red", scheme: String = "https"): WebLink? {
        val path = url.substringBefore('?')
        val q = url.substringAfter('?', "").split('&').filter { '=' in it }
            .associate { it.substringBefore('=') to it.substringAfter('=') }
        return webLinkFor(scheme, host, path.split('/').filter { it.isNotEmpty() }, { q[it] }, hosts)
    }

    @Test fun paths() {
        assertEquals(WebLink.Esim(null), link("/esim"))
        assertEquals(WebLink.Esim("jp"), link("/esim/JP"))
        assertEquals(WebLink.Esim("europe"), link("/esim/europe"))
        assertEquals(WebLink.Number(null), link("/number"))
        assertEquals(WebLink.Number("us"), link("/number/us"))
        assertEquals(WebLink.Account, link("/me"))
        assertEquals(WebLink.Account, link("/login"))
        assertEquals(WebLink.Account, link("/account"))
        assertEquals(WebLink.Buy, link("/buy"))
        assertEquals(WebLink.Claim("RV-ABCD-EFGH-IJKL-MNOP"), link("/c/claim?code=RV-ABCD-EFGH-IJKL-MNOP"))
        assertEquals(WebLink.Open("tok_0123456789abcdef"), link("/app/open?t=tok_0123456789abcdef"))
        assertEquals(WebLink.Esim("jp"), link("/esim/jp", host = "www.vpn.red"))
    }

    @Test fun malformedFallsBackToAPageNeverToAnAction() {
        assertEquals(WebLink.Home, link("/"))
        assertEquals(WebLink.Home, link("/pricing"))
        assertEquals(WebLink.Home, link("/c/claim"))
        assertEquals(WebLink.Home, link("/c/claim?code="))
        assertEquals(WebLink.Home, link("/c/claim?code=a%20b"))
        assertEquals(WebLink.Home, link("/app/open"))
        assertEquals(WebLink.Home, link("/app/open?t=short"))
        assertEquals(WebLink.Home, link("/me/extra"))
        assertEquals(WebLink.Esim(null), link("/esim/jp/guide"))
        assertEquals(WebLink.Esim(null), link("/esim/x"))
    }

    @Test fun notOurs() {
        assertNull(link("/esim/jp", host = "e-sim.net"))
        assertNull(link("/esim/jp", host = "evil.example"))
        assertNull(link("/esim/jp", scheme = "http"))
        assertNull(webLinkFor("https", "vpn.red", listOf("esim"), { null }, emptyList()))
    }
}
