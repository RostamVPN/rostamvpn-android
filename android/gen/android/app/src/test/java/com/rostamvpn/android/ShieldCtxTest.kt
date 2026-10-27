package com.rostamvpn.android

import com.rostamvpn.android.ui.screens.ShieldContext
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Locale

/** The optional `ctx` object of /redshield/v1/score and /scan: decoded tolerantly, kept, rendered. */
class ShieldCtxTest {
    private val full = """{"asn": 24940, "org": "Hetzner Online GmbH", "cc": "de", "rdns": "static.example.your-server.de.",
        "reason_key": "malware_host", "reason": "Known malware host on our threat list", "category": "malware"}"""

    @Test fun decodesEveryField() {
        val c = ShieldCtx.fromJson(JSONObject(full))!!
        assertEquals(24940, c.asn)
        assertEquals("Hetzner Online GmbH", c.org)
        assertEquals("DE", c.cc)
        assertEquals("static.example.your-server.de", c.rdns)
        assertEquals("malware_host", c.reasonKey)
        assertEquals("Known malware host on our threat list", c.reason)
        assertEquals("malware", c.category)
    }

    @Test fun absentNullOrJunkIsNull() {
        assertNull(ShieldCtx.fromJson(null))
        assertNull(ShieldCtx.fromJson(JSONObject("{}")))
        assertNull(ShieldCtx.fromJson(JSONObject("""{"asn":null,"org":null,"cc":null,"rdns":null,"reason_key":null,"reason":null,"category":null}""")))
        assertNull(ShieldCtx.fromJson(JSONObject("""{"asn":"x","org":"  ","cc":"DEU"}""")))
        assertNull(ShieldCtx.parse("not json"))
        assertNull(ShieldCtx.parse(null))
        // "AS24940" as a string is read; a partial object keeps what it has.
        val c = ShieldCtx.fromJson(JSONObject("""{"asn":"AS24940","unknown_field":1}"""))!!
        assertEquals(24940, c.asn); assertNull(c.org)
    }

    @Test fun scoreWithoutCtxIsTodaysBehaviour() {
        val r = JSONObject("""{"feed_date":"2026-10-25","ttl_secs":3600,"scores":[
            {"host":"a.example","score":96,"verdict":"blocked","label":"Phishing","cc":"NL","org":"EXAMPLE-HOSTING"}]}""")
        val cache = ShieldScoreCache(); cache.merge(r)
        val s = cache.score("a.example")!!
        assertNull(s.ctx)
        // The older fields still give a context line.
        assertEquals("EXAMPLE-HOSTING", s.context!!.org); assertEquals("NL", s.context!!.cc)
    }

    @Test fun scoreCtxSurvivesTheCacheFile() {
        val r = JSONObject("""{"feed_date":"2026-10-25","ttl_secs":3600,"scores":[
            {"host":"2a01:4f8::1","score":97,"verdict":"blocked","ctx":$full}]}""")
        val cache = ShieldScoreCache(); cache.merge(r)
        val back = ShieldScoreCache.parse(cache.toJson()).score("2a01:4f8::1")!!
        assertEquals(ShieldCtx.fromJson(JSONObject(full)), back.ctx)
    }

    @Test fun scanItemCtxAndFallback() {
        val rep = ShieldScanReport.parse("""{"feed_date":"2026-10-25","summary":{"total":2},"items":[
            {"host":"x.example","verdict":"blocked","ctx":$full},
            {"host":"y.example","verdict":"known","asn":13335,"asname":"CLOUDFLARENET","cc":"us"}]}""")!!
        assertEquals(24940, rep.items[0].ctx!!.asn)
        assertNull(rep.items[1].ctx)
        assertEquals(13335, rep.items[1].context!!.asn)
        assertEquals("US", rep.items[1].context!!.cc)
    }

    @Test fun ctxFillsFromTheFlowCountry() {
        val flow = ShieldFlow("185.220.101.47", listOf(8080), 1, 0, 0, 0, 0, cc = "DE")
        assertEquals("DE", ShieldContext.of(null, flow)!!.cc)
        assertNull(ShieldContext.of(null, flow.copy(cc = null)))
    }

    @Test fun compactLine() {
        val prev = Locale.getDefault()
        Locale.setDefault(Locale.US)
        try {
            val line = ShieldContext.line(ShieldCtx.fromJson(JSONObject(full)))!!
            // Each Latin run is isolated (FSI..PDI) so an RTL reader keeps the order.
            assertEquals("Hetzner Online GmbH · AS24940 · Germany", line.replace("⁨", "").replace("⁩", ""))
            assertNull(ShieldContext.line(ShieldCtx(rdns = "only.example")))
            assertNull(ShieldContext.rdns(ShieldCtx(rdns = "Host.Example"), "host.example"))
        } finally { Locale.setDefault(prev) }
    }

    @Test fun reasonKeysMapToKnownCategories() {
        assertEquals(R.string.rs_cat_malware, ShieldContext.reasonRes("malware_host"))
        assertEquals(R.string.rs_cat_malware, ShieldContext.reasonRes("malware"))
        assertEquals(R.string.rs_cat_phishing, ShieldContext.reasonRes("phishing-site"))
        assertEquals(R.string.rs_cat_controller, ShieldContext.reasonRes("controller"))
        assertNull(ShieldContext.reasonRes("something_new"))
        assertNotNull(ShieldContext.reasonRes("rating_malicious"))
    }
}
