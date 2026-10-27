package com.rostamvpn.android

import com.rostamvpn.android.RemoteConfig.Outcome
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The per-cid OTA subset (`/api/v3/config`), client side.
 *
 * Everything here is the LOGIC, not the transport: the query a device builds,
 * the order of requests across the base list, the retry counter's state
 * machine, and the assign line. None of it touches `android.util.Log` or a
 * `Context`, because this module has no Robolectric and no
 * `unitTests.returnDefaultValues`, so a single `Log.i` in a function under test
 * fails the whole class with "not mocked".
 *
 * Fixtures are real server bytes, not hand-written JSON:
 *  - `rostam.gen2.json`  — what `/api/v2/config` serves today (237 entries,
 *                          no `assign` block). The static answer.
 *  - `rostam.v3.assign.json` — one real `/api/v3/config` answer, unsealed
 *                          (8 entries, `assign` block), cid 857fdd9fc51f3e00.
 */
class RemoteConfigV3Test {
    private fun res(name: String) = javaClass.classLoader!!.getResource(name)!!.readText()

    private val v2 = res("rostam.gen2.json")
    private val v3 = res("rostam.v3.assign.json")

    // ── (a) the URL the device asks for ─────────────────────────────────────

    @Test fun mobileDataSendsTheOperator() {
        val carrier = RemoteConfig.configCarrier(onWifi = false, networkOperator = "43211")
        assertEquals("43211", carrier)
        val q = RemoteConfig.v3Query("857fdd9fc51f3e00", carrier, "IR", "", 0)
        assertEquals("cid=857fdd9fc51f3e00&carrier=43211&cc=IR&last=&retry=0", q)
    }

    /** On Wi-Fi the SIM names an MNO that is carrying none of these bytes. */
    @Test fun wifiSendsAnEmptyCarrier() {
        assertEquals("", RemoteConfig.configCarrier(onWifi = true, networkOperator = "43235"))
        assertEquals("", RemoteConfig.configCarrier(onWifi = true, networkOperator = null))
        val q = RemoteConfig.v3Query("857fdd9fc51f3e00", "", "IR", "", 0)
        assertTrue(q.contains("&carrier=&"))
    }

    @Test fun noSimOnMobileIsStillEmpty() {
        assertEquals("", RemoteConfig.configCarrier(onWifi = false, networkOperator = "  "))
    }

    @Test fun countryPrefersTheSim() {
        assertEquals("IR", RemoteConfig.configCountry("ir", "de", "Europe/Berlin"))
    }

    @Test fun countryFallsBackToTheNetwork() {
        assertEquals("DE", RemoteConfig.configCountry("", "de", "Europe/Berlin"))
        assertEquals("DE", RemoteConfig.configCountry(null, "DE", "Europe/Berlin"))
    }

    /** No SIM, no network country: the timezone is all the phone can say. */
    @Test fun countryFallsBackToTheTimezone() {
        assertEquals("IR", RemoteConfig.configCountry(null, null, "Asia/Tehran"))
        assertEquals("IR", RemoteConfig.configCountry("", "", "Iran"))
        assertEquals("", RemoteConfig.configCountry(null, null, "Europe/Berlin"))
    }

    /** The map is the config's own `freedom_regions`, so a publish can add one. */
    @Test fun countryUsesTheSuppliedFreedomMap() {
        val m = mapOf("IR" to listOf("Asia/Tehran"), "RU" to listOf("Europe/Moscow"))
        assertEquals("RU", RemoteConfig.configCountry(null, null, "Europe/Moscow", m))
    }

    @Test fun lastIsTheHostWithoutThePort() {
        assertEquals("192.0.2.25", RemoteConfig.lastOkHost("192.0.2.25:80"))
    }

    /**
     * IPv6 is two of the eight entries in an Iranian subset, and configd's own
     * parse refuses to split a label whose left half still has a colon, so the
     * client has to hand it the bare host.
     */
    @Test fun lastStripsThePortFromAnIpv6Label() {
        assertEquals("2a01:4f8:10b:23a1:f8d:4e52:6123:b28d",
            RemoteConfig.lastOkHost("2a01:4f8:10b:23a1:f8d:4e52:6123:b28d:80"))
        assertEquals("2001:db8:5::6", RemoteConfig.lastOkHost("2001:db8:5::11"))
    }

    @Test fun noLastYetIsEmpty() {
        assertEquals("", RemoteConfig.lastOkHost(""))
        assertEquals("", RemoteConfig.lastOkHost("   "))
    }

    /** A v6 `last` must survive encoding, and `retry` carries the counter. */
    @Test fun theWholeQueryWithEveryFieldSet() {
        val q = RemoteConfig.v3Query("145436cc5a8efd66", "43235", "IR", "2001:db8:5::6", 2)
        assertEquals(
            "cid=145436cc5a8efd66&carrier=43235&cc=IR&last=2602%3Af7d0%3A5%3A%3A14&retry=2", q)
    }

    @Test fun retryInTheQueryIsClampedToWhatTheServerAccepts() {
        assertTrue(RemoteConfig.v3Query("c", "", "", "", 9).endsWith("retry=4"))
        assertTrue(RemoteConfig.v3Query("c", "", "", "", -1).endsWith("retry=0"))
    }

    // ── (b) 404 on v3 falls back to v2 on the SAME base ──────────────────────

    private val bases = listOf("https://a.example", "https://b.example")

    private fun walk(hasCid: Boolean, answer: (String, String) -> Outcome): Pair<Outcome, List<String>> {
        val seen = mutableListOf<String>()
        val out = RemoteConfig.walkBases(bases, hasCid) { base, path ->
            seen += "$base$path"
            answer(base, path)
        }
        return out to seen
    }

    @Test fun a404OnV3AsksTheSameBaseForV2AndStopsThere() {
        val (out, seen) = walk(hasCid = true) { _, path ->
            if (path == RemoteConfig.PATH_V3) Outcome.Absent else Outcome.Updated
        }
        assertEquals(Outcome.Updated, out)
        // The SAME base, and the walk never reaches b.example.
        assertEquals(
            listOf("https://a.example/api/v3/config", "https://a.example/api/v2/config"), seen)
    }

    @Test fun v3AnsweringMeansV2IsNeverAsked() {
        val (out, seen) = walk(hasCid = true) { _, _ -> Outcome.Updated }
        assertEquals(Outcome.Updated, out)
        assertEquals(listOf("https://a.example/api/v3/config"), seen)
    }

    /** "Unchanged" is a successful answer; it must not walk the fallbacks. */
    @Test fun a304OnV3StopsTheWalk() {
        val (out, seen) = walk(hasCid = true) { _, _ -> Outcome.Current }
        assertEquals(Outcome.Current, out)
        assertEquals(1, seen.size)
    }

    /** A host that does not answer is a host problem: next base, both paths. */
    @Test fun anUnreachableBaseMovesOnWithoutAskingItForV2() {
        val (out, seen) = walk(hasCid = true) { base, path ->
            if (base == "https://a.example") Outcome.Unreachable
            else if (path == RemoteConfig.PATH_V3) Outcome.Absent else Outcome.Updated
        }
        assertEquals(Outcome.Updated, out)
        assertEquals(
            listOf(
                "https://a.example/api/v3/config",
                "https://b.example/api/v3/config",
                "https://b.example/api/v2/config",
            ), seen)
    }

    /** Neither path anywhere: the mirrors are the caller's next move. */
    @Test fun everyBaseMissingBothPathsIsUnreachable() {
        val (out, seen) = walk(hasCid = true) { _, _ -> Outcome.Absent }
        assertEquals(Outcome.Unreachable, out)
        assertEquals(4, seen.size)
    }

    /** No cid: byte for byte the v2-only walk this has always been. */
    @Test fun withoutACidV3IsNeverAsked() {
        val (_, seen) = walk(hasCid = false) { _, _ -> Outcome.Unreachable }
        assertEquals(
            listOf("https://a.example/api/v2/config", "https://b.example/api/v2/config"), seen)
    }

    // ── (c) the retry counter ───────────────────────────────────────────────

    /** The counter as the connect path drives it, with no Context in the way. */
    private class Counter {
        var k = 0; private set
        /** The core's verdict for one connect attempt. */
        fun attempt(coreResult: String) {
            if (RemoteConfig.isPlanFailure(coreResult)) k = RemoteConfig.nextRetry(k)
        }
        fun passedHealthCheck() { k = 0 }
    }

    @Test fun oneWholePlanFailureBumpsItOnce() {
        val c = Counter()
        c.attempt("err:${RemoteConfig.PLAN_FAILED}")
        assertEquals(1, c.k)
    }

    @Test fun itCapsAtFour() {
        val c = Counter()
        repeat(10) { c.attempt("err:${RemoteConfig.PLAN_FAILED}") }
        assertEquals(RemoteConfig.MAX_RETRY, c.k)
        assertEquals(4, c.k)
    }

    /**
     * One endpoint failing inside a plan is the bad-set's job. So is a verdict
     * timeout, which can fire with a handshake still in flight.
     */
    @Test fun singleEndpointAndOtherFailuresLeaveItAlone() {
        val c = Counter()
        listOf(
            "connection check failed",
            "err:connection check failed",
            "err:endpoint selection timed out",
            "err:bind 127.0.0.1:1080: Address already in use",
            "err:tokio runtime: oh no",
            "err:already running",
            "err:no config loaded — call loadConfig() first",
            "ok:http:192.0.2.25:80",
        ).forEach { c.attempt(it) }
        assertEquals(0, c.k)
        assertFalse(RemoteConfig.isPlanFailure("err:endpoint selection timed out"))
        assertTrue(RemoteConfig.isPlanFailure("err:${RemoteConfig.PLAN_FAILED}"))
        // The connect loop strips `err:` before comparing; both forms must work.
        assertTrue(RemoteConfig.isPlanFailure(RemoteConfig.PLAN_FAILED))
    }

    /** Only the post-connect health check resets it; a handshake does not. */
    @Test fun theHealthCheckResetsItAndNothingElseDoes() {
        val c = Counter()
        repeat(3) { c.attempt("err:${RemoteConfig.PLAN_FAILED}") }
        assertEquals(3, c.k)
        c.attempt("ok:http:192.0.2.25:80")   // handshake only
        assertEquals(3, c.k)
        c.passedHealthCheck()
        assertEquals(0, c.k)
    }

    @Test fun nextRetryNeverGoesBackwardsOrWraps() {
        assertEquals(1, RemoteConfig.nextRetry(0))
        assertEquals(4, RemoteConfig.nextRetry(4))
        assertEquals(4, RemoteConfig.nextRetry(99))
        assertEquals(1, RemoteConfig.nextRetry(-3))
    }

    // ── (d) a static answer is normal ───────────────────────────────────────

    /**
     * A cid registered less than ~10 min ago, the first minute after a configd
     * restart, or any server trouble gets the v2 bytes. The app plans on them
     * and says nothing.
     */
    @Test fun aStaticBodyCarriesNoAssignLineAndNoRetryBump() {
        assertNull(RemoteConfig.assignLine(v2))
        assertFalse(JSONObject(v2).has("assign"))
        assertTrue(JSONObject(v2).getJSONObject("transport")
            .getJSONObject("redsocket").getJSONArray("entries").length() > 100)
        // Planning on it succeeds, so nothing in the connect path bumps `k`.
        val c = Counter()
        c.attempt("ok:http:192.0.2.25:80")
        assertEquals(0, c.k)
    }

    @Test fun garbageIsNotAnAssignment() {
        assertNull(RemoteConfig.assignLine("not json"))
        assertNull(RemoteConfig.assignLine("{}"))
    }

    // ── (4) the assign line ─────────────────────────────────────────────────

    @Test fun anAssignedBodyLogsEveryFieldTheServerLogs() {
        val line = RemoteConfig.assignLine(v3)!!
        assertEquals(
            "[nc] assign bucket=310 epoch=2980674742153426270 retry=0 reach=cc:IR n=8", line)
        assertEquals(8, JSONObject(v3).getJSONObject("transport")
            .getJSONObject("redsocket").getJSONArray("entries").length())
    }

    /**
     * The epoch is a u64 pool hash and about half of them have the top bit set.
     * This is a REAL value an emulator was served on 2026-10-27, which the
     * first cut of this logged as 9223372036854775807 because `optLong`
     * saturates. It is the join key to `cfg_assign`, so a saturated one is
     * worse than none: it points at a row that does not exist.
     */
    @Test fun anEpochAboveLongMaxValueSurvivesIntact() {
        val body = """{"version":"57","assign":{"bucket":808,""" +
            """"epoch":16581088215364782296,"retry":0,"n":8,"reach":"global"}}"""
        assertEquals(
            "[nc] assign bucket=808 epoch=16581088215364782296 retry=0 reach=global n=8",
            RemoteConfig.assignLine(body))
        // What the parsed value gives, for the record. The two org.json
        // implementations do not even agree on HOW they ruin it: Android's
        // saturates to Long.MAX_VALUE (what the device logged), the JVM's
        // wraps to -1865655858344769320. Neither is the number.
        assertNotEquals(
            "16581088215364782296",
            JSONObject(body).getJSONObject("assign").optLong("epoch").toString())
    }

    /** Pretty-printed and key-reordered bodies must read the same. */
    @Test fun theEpochIsFoundWhateverTheWhitespaceAndKeyOrder() {
        val body = "{\n  \"assign\" : {\n    \"reach\": \"MCI\",\n" +
            "    \"epoch\" : 16581088215364782296,\n    \"bucket\": 310,\n" +
            "    \"retry\": 2,\n    \"n\": 8\n  }\n}"
        assertEquals(
            "[nc] assign bucket=310 epoch=16581088215364782296 retry=2 reach=MCI n=8",
            RemoteConfig.assignLine(body))
    }

    /** An epoch that is not a number at all must not take the line down. */
    @Test fun anUnreadableEpochStillLogsTheRest() {
        val line = RemoteConfig.assignLine(
            """{"assign":{"bucket":1,"epoch":"x","retry":0,"n":8,"reach":"global"}}""")!!
        assertTrue(line.startsWith("[nc] assign bucket=1 epoch=x "))
    }

    // ── (e) the ETag prefs are independent ──────────────────────────────────

    /**
     * v2 and v3 are different bytes behind independent counters, and configd's
     * etags are per cid. One shared key would earn a 304 for a body this device
     * does not have, and a 304 is believed.
     */
    @Test fun v2AndV3EtagsCannotCollide() {
        val v2Key = RemoteConfig.etagKey(RemoteConfig.PATH_V2)
        val v3Key = RemoteConfig.etagKey(RemoteConfig.PATH_V3)
        val v1Key = RemoteConfig.etagKey("/api/v1/config")
        assertNotEquals(v2Key, v3Key)
        assertNotEquals(v1Key, v3Key)
        assertNotEquals(v1Key, v2Key)
        // The v2 key must not move: an install that already holds one would
        // otherwise refetch the whole list once for nothing.
        assertEquals("config_etag_v2", v2Key)
        assertEquals("config_etag_v3", v3Key)
    }
}
