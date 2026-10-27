package com.rostamvpn.android

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Protect Mode's first-party exemption: the matcher, its sources, refresh, and every place a verdict could land. */
class FirstPartyTest {
    private val build = FirstParty.BuildHosts(
        brandHosts = listOf("vpn.red", "https://cashier.example.com", "https://e-sim.net", "https://vpn.red/privacy/", "www.vpn.red"),
        exactHosts = listOf("https://api.rostam.app/health-probe"),
    )

    /** The shape of the live redvpn config (gen2), trimmed. */
    private val config = """{
      "api": {"base": "https://cashier.example.com", "key": "x",
              "fallback_bases": ["https://checkout.example.com", "https://dexample000000.cloudfront.net", "https://example.global.ssl.fastly.net"],
              "mirrors": ["https://mirror.example/?u={url}"]},
      "transport": {"redsocket": {
        "entries": [{"host": "192.0.2.20", "port": 80}, {"host": "2a0e:8f02:21d0::10", "port": 443}],
        "premium_entries": [{"host": "pop-b-p-v4.example.net", "port": 443}],
        "backup_entries": [{"host": "52.9.249.82", "port": 80}],
        "upgrade_hosts": ["login.microsoftonline.com", "outlook.office365.com"]}},
      "regions": {"fra": ["pop-b-f-v4.example.net"], "lax": {"ips": ["pop-a-f-v4.example.net"]}},
      "premium_regions": {"lax": {"ips": ["pop-a-p-v4.example.net"]}},
      "health": {"probe_url": "https://api.rostam.app/health-probe"},
      "redshield": {"first_party": ["192.0.2.26/16", "2001:db8:aa::/48", ".ourcdn.example"]}
    }"""
    private val wgtcp = """{"endpoints": ["198.51.100.7:443", "[2001:db8:77::1]:443"], "server_public_key": "k", "address": "10.0.0.2", "gateway": "10.0.0.1"}"""

    private fun set() = FirstParty.build(listOf(config), wgtcp, build)

    /**
     * The build's own hosts must come from the BRAND, not from a literal shared
     * with another brand. 475 shipped with the eSIM store's origin and a sibling
     * brand's probe host compiled into a build that uses neither; both were off
     * at runtime and both were still readable in `strings`. These two assertions
     * are the regression guard: they fail at compile/test time if either value
     * goes back to being unconditional.
     */
    @Test fun buildHostsComeFromTheBrand() {
        if (!BuildConfig.FEATURE_ESIM) {
            assertTrue("a brand that sells no eSIM must not carry the store origin",
                BuildConfig.ESIM_BASE.isEmpty())
        }
        assertTrue("the probe host must be the brand's own",
            BuildConfig.HEALTH_PROBE_URL.isNotEmpty())
        // The live exemption follows BuildConfig, so Protect Mode never scores
        // our own health probe whatever host the brand points it at.
        assertTrue(FirstParty.BuildHosts.fromBuildConfig().exactHosts
            .contains(BuildConfig.HEALTH_PROBE_URL))
    }

    @Test fun exactHostsFromConfig() {
        val s = set()
        assertTrue(s.matches("dexample000000.cloudfront.net"))
        assertTrue(s.matches("FRA00-P-V4.IXROUTE.NET."))
        assertTrue(s.matches("checkout.example.com"))
        assertTrue(s.matches("api.rostam.app"))
        // Exact means exact: never the platform, never a sibling.
        assertFalse(s.matches("cloudfront.net"))
        assertFalse(s.matches("evil.dexample000000.cloudfront.net"))
        assertFalse(s.matches("d2other.cloudfront.net"))
        assertFalse(s.matches("other.rostam.app"))
        assertFalse(s.matches("example.com"))
        assertFalse(s.matches("example.net"))
    }

    @Test fun parentDomainOnlyForBrandHosts() {
        val s = set()
        assertTrue(s.matches("vpn.red"))
        assertTrue(s.matches("www.vpn.red"))
        assertTrue(s.matches("a.b.vpn.red"))
        assertTrue(s.matches("e-sim.net"))
        assertTrue(s.matches("api.e-sim.net"))
        assertTrue(s.matches("cashier.example.com"))
        assertTrue(s.matches("cdn.ourcdn.example"), "operator .domain entry")
        assertFalse(s.matches("evilvpn.red"))
        assertFalse(s.matches("vpn.red.evil.com"))
        assertFalse(s.matches("red"))
        assertFalse(s.matches("e-sim.network"))
    }

    @Test fun coverNamesAndMirrorsAreNeverOurs() {
        val s = set()
        assertFalse(s.matches("login.microsoftonline.com"))
        assertFalse(s.matches("outlook.office365.com"))
        assertFalse(s.matches("mirror.example"))
    }

    @Test fun ipv4() {
        val s = set()
        assertTrue(s.matches("192.0.2.20"))
        assertTrue(s.matches("52.9.249.82"))
        assertTrue(s.matches("198.51.100.7"), "wgtcp endpoint, port stripped")
        assertTrue(s.matches("::ffff:192.0.2.20"), "v4-mapped spelling")
        assertFalse(s.matches("192.0.2.27"))
        assertFalse(s.matches("192.0.2.28"))
    }

    @Test fun ipv6() {
        val s = set()
        assertTrue(s.matches("2a0e:8f02:21d0::10"))
        assertTrue(s.matches("[2a0e:8f02:21d0:0:0:0:0:10]"), "bracketed, uncompressed")
        assertTrue(s.matches("2001:db8:77::1"), "wgtcp [v6]:port")
        assertTrue(s.matches("2001:db8:77::1%wlan0"), "zone id")
        assertFalse(s.matches("2a0e:8f02:21d0::11"))
    }

    @Test fun cidr() {
        val s = set()
        assertTrue(s.matches("192.0.2.29"))
        assertTrue(s.matches("192.0.2.30"))
        assertFalse(s.matches("192.0.2.31"))
        assertFalse(s.matches("192.0.2.32"))
        assertTrue(s.matches("2001:db8:aa:ffff::1"))
        assertFalse(s.matches("2001:db8:ab::1"))
        val odd = FirstParty.cidr("10.0.0.0/9")!!
        assertTrue(odd.contains(FirstParty.ipBytes("10.127.255.255")!!))
        assertFalse(odd.contains(FirstParty.ipBytes("10.128.0.0")!!))
        assertNull(FirstParty.cidr("10.0.0.0/33"))
        assertNull(FirstParty.cidr("not-an-ip/8"))
    }

    @Test fun namesAreNeverTreatedAsAddresses() {
        assertNull(FirstParty.ipBytes("vpn.red"))
        assertNull(FirstParty.ipBytes("1.2.3"))
        assertNull(FirstParty.ipBytes("cafe:bad"))
        assertNull(FirstParty.ipBytes("dead:beef::zz"))
    }

    @Test fun sourcesAreCounted() {
        val src = set().sources
        assertEquals(4, src["config.api"]) // cashier.example.com counts here as an exact host too
        assertEquals(4, src["config.endpoints"])
        assertEquals(3, src["config.regions"])
        assertEquals(2, src["wgtcp.endpoints"])
        assertEquals(3, src["config.first_party"])
        assertTrue((src["build.brand"] ?: 0) >= 3)
    }

    @Test fun configRefreshUpdatesTheSet() {
        FirstParty.onConfig(config)
        assertTrue(FirstParty.matches("192.0.2.20"))
        assertFalse(FirstParty.matches("203.0.113.9"))
        // A refresh stored a newer config: its hosts count at once, the running ones still do.
        val newer = """{"transport":{"redsocket":{"entries":[{"host":"203.0.113.9","port":80}]}}}"""
        FirstParty.onStoredConfig(newer)
        assertTrue(FirstParty.matches("203.0.113.9"))
        assertTrue(FirstParty.matches("192.0.2.20"))
        // The next launch parses the newer one only.
        FirstParty.onConfig(newer)
        assertTrue(FirstParty.matches("203.0.113.9"))
        assertFalse(FirstParty.matches("192.0.2.20"))
        // A WGTCP profile adds its endpoints to whatever config is running.
        FirstParty.onWgtcpProfile(wgtcp)
        assertTrue(FirstParty.matches("198.51.100.7"))
        assertTrue(FirstParty.matches("203.0.113.9"))
    }

    /** This build's Protect Mode API host (per brand: vpn.red, rostam.app, ...). */
    private val apiHost: String = java.net.URI(RedShield.API_BASE).host

    @Test fun aBlockedVerdictForOneOfOursIsNeverStoredOrApplied() {
        FirstParty.onConfig(config)
        val cache = ShieldScoreCache()
        val flagged = cache.merge(JSONObject("""{"ttl_secs":600,"scores":[
            {"host":"192.0.2.20","score":99,"verdict":"blocked","label":"stub"},
            {"host":"www.$apiHost","score":99,"verdict":"blocked","label":"stub"},
            {"host":"bad.example","score":99,"verdict":"blocked","label":"stub"}]}"""))
        assertEquals(listOf("bad.example"), flagged.map { it.host })
        assertNull(cache.score("192.0.2.20"))
        assertNull(cache.score("www.$apiHost"))
        // A score cached before the exemption never becomes an automatic deny.
        cache.entries["192.0.2.20"] = ShieldScore.fromJson(JSONObject(
            """{"host":"192.0.2.20","score":99,"verdict":"blocked","label":"old","expires":${System.currentTimeMillis() + 600_000}}"""))
        val rules = JSONObject(cache.autoRulesJson(emptyMap())).getJSONArray("rules")
        assertEquals(1, rules.length())
        assertEquals("bad.example", rules.getJSONObject(0).getString("host"))
    }

    @Test fun ourDestinationsAreNeverBatchedOrFlaggedForAttention() {
        FirstParty.onConfig(config)
        fun flow(h: String) = ShieldFlow(h, listOf(443), 1, 10, 10, 5, 1, unusual = true)
        val flows = listOf(flow(apiHost), flow("192.0.2.20"), flow("192.0.2.33"), flow("news.example"))
        assertEquals(listOf("news.example"), RedShieldEngine.batch(flows, ShieldScoreCache(), 100).map { it.host })
        val facts = flows.map { ShieldInsights.Facts(it, null, denied = false, newToday = false) }
        assertEquals(listOf("news.example"), facts.filter { it.needsAttention }.map { it.flow.host })
    }

    @Test fun profileBodiesLoseOnlyLinesThatAreOurs() {
        val (body, dropped) = set().filterProfileBody("vpn.red\n*.e-sim.net\n163.5.199.160\n163.5.0.0/16\nads.example\n# c\nred\n")
        assertEquals(3, dropped)
        assertEquals("163.5.0.0/16\nads.example\n# c\nred\n\n", body)
    }

    private fun assertTrue(v: Boolean, msg: String) = org.junit.Assert.assertTrue(msg, v)
}
