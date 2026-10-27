package com.rostamvpn.android

import android.content.Context
import android.content.Intent

/**
 * RedShield sample data, DEBUG builds only: this file is in src/debug and the
 * release twin (src/release) returns null everywhere, so none of it reaches a
 * release APK (CLAUDE.md "DEV TOOLS SHIPPED IN EVERY ANDROID RELEASE": a
 * runtime flag is not an exclusion). Mirrors iOS GuardianDemo / the
 * `-demo-protected` launch argument:
 *
 *     adb shell am start -n <pkg>/com.rostamvpn.android.MainActivity \
 *         --ez demo_protected true [--es demo_open traffic|report|scan|profiles|rules|blocked]
 *
 * Home draws the card as connected with notices; the sheet, the report and
 * the profile catalogue draw from the tables below; Scan answers with the
 * fixture report. Nothing is sent anywhere. App attribution uses packages
 * that exist on a stock emulator, so the icons are real.
 */
object RedShieldDemo {
    @Volatile var active: Boolean = false
        private set

    /** Sheet segment or screen to open once at launch. */
    @Volatile var openOn: String? = null

    fun enableFrom(intent: Intent?) {
        intent?.getStringExtra("stub_score")?.let { stubScore(it) }
        if (intent?.getBooleanExtra("demo_protected", false) != true) return
        active = true
        openOn = intent.getStringExtra("demo_open")
    }

    /**
     * Debug only: fold a canned /score response into the REAL cache, as a round
     * would, then rebuild the core's automatic rules. Proves a verdict for a
     * first-party host is dropped (FirstParty) while a third-party one is kept:
     *
     *     adb shell am start -n <pkg>/com.rostamvpn.android.MainActivity --es stub_score \
     *         '{"ttl_secs":600,"scores":[{"host":"192.0.2.20","score":99,"verdict":"blocked","label":"stub"}]}'
     */
    private fun stubScore(json: String) = Thread {
        var c: Context? = null
        for (i in 0 until 100) { c = MainActivity.instance?.applicationContext; if (c != null) break; Thread.sleep(100) }
        val ctx = c ?: return@Thread
        runCatching {
            FirstParty.refresh(ctx)
            val o = org.json.JSONObject(json)
            val a = o.optJSONArray("scores")
            val sent = (0 until (a?.length() ?: 0)).map { a!!.getJSONObject(it).optString("host").lowercase() }
            val before = FirstParty.verdictsDropped.get()
            val kept = ShieldScoreCache.update(ctx) { cache -> cache.merge(o); sent.filter { cache.score(it) != null } }
            RedShieldEngine.resync(ctx)
            android.util.Log.i("Shield", "stub score: ${sent.size} in, kept $kept, dropped ${FirstParty.verdictsDropped.get() - before} first-party; " +
                "auto rules ${ShieldScoreCache.load(ctx).autoRulesJson(emptyMap())}")
        }.onFailure { android.util.Log.w("Shield", "stub score failed: $it") }
    }.start()

    private const val MB = 1_048_576L
    @Volatile private var cleared = false

    private val table = listOf(
        f("video.cdn-apple.com", 443, 38, 2 * MB / 3, 412 * MB, 3_100, 40),
        f("icloud.com", 443, 64, 21 * MB, 96 * MB, 3_400, 12),
        f("scontent.cdninstagram.com", 443, 51, 3 * MB, 88 * MB, 2_900, 75),
        f("www.google.com", 443, 22, MB / 2, 31 * MB, 3_300, 300),
        f("cloudflare.com", 443, 9, MB / 5, 12 * MB, 2_000, 600),
        f("fonts.gstatic.com", 443, 14, MB / 10, 6 * MB, 1_800, 900),
        f("d1a2b3c4.cloudfront.net", 443, 7, MB / 8, 5 * MB, 1_500, 1_100),
        ShieldFlow("api.whatsapp.net", listOf(443, 5222), 30, 2 * MB, 4 * MB, 3_500, 20, portBytes = mapOf(443 to 4 * MB, 5222 to 2 * MB)),
        f("17.57.144.84", 5223, 6, MB / 20, MB / 4, 3_500, 60),
        f("metrics.adnet-sync.xyz", 443, 58, MB / 3, MB / 6, 3_000, 30).copy(unusual = true),
        f("cdn-update.secure-login.top", 443, 1, 0, 0, 540, 540).copy(blocked = 3),
        f("time.android.com", 123, 4, 2_048, 2_048, 3_500, 1_700),
        f("ads.tracker.example", 443, 0, 0, 0, 2_200, 1_200).copy(blocked = 2),
        // Protocol detail (core 2026-10-25): QUIC (UDP 443), plain HTTP, an
        // upload-heavy sync, a literal IPv6 address and a long-lived stream.
        ShieldFlow("rr3---sn-a5mekn.googlevideo.com", emptyList(), 0, 0, 0, 2_600, 5,
            udpConnections = 9, udpBytes = 186 * MB, udpPortBytes = mapOf(443 to 186 * MB)),
        f("news.example-daily.com", 80, 12, MB / 4, 3 * MB, 1_400, 200),
        f("backup.sync-cloud.io", 443, 18, 46 * MB, 2 * MB, 2_400, 15),
        f("2a03:2880:f12f:83:face:b00c:0:25de", 443, 5, MB, 9 * MB, 3_000, 30),
        f("push.music-stream.net", 443, 2, MB / 10, 2 * MB, 3_400, 10),
    )

    private fun f(h: String, port: Int, n: Int, up: Long, down: Long, first: Long, last: Long) =
        ShieldFlow(h, listOf(port), n, up, down, first, last, portBytes = mapOf(port to up + down))

    /** host -> the app that opened it (packages present on a stock emulator image). */
    private val owners = mapOf(
        "video.cdn-apple.com" to "com.google.android.youtube",
        "www.google.com" to "com.android.chrome",
        "fonts.gstatic.com" to "com.android.chrome",
        "cloudflare.com" to "com.android.chrome",
        "scontent.cdninstagram.com" to "com.android.chrome",
        "metrics.adnet-sync.xyz" to "com.google.android.apps.photos",
        "cdn-update.secure-login.top" to "com.google.android.gm",
        "d1a2b3c4.cloudfront.net" to "com.android.vending",
        "icloud.com" to "com.google.android.gm",
        "api.whatsapp.net" to "com.google.android.apps.messaging",
        "rr3---sn-a5mekn.googlevideo.com" to "com.google.android.youtube",
        "news.example-daily.com" to "com.android.chrome",
        "backup.sync-cloud.io" to "com.google.android.apps.photos",
        "2a03:2880:f12f:83:face:b00c:0:25de" to "com.android.chrome",
        "push.music-stream.net" to "com.google.android.apps.youtube.music",
        "17.57.144.84" to "com.google.android.apps.maps",
    )

    fun uidFor(ctx: Context, host: String): Int? {
        if (!active) return null
        val pkg = owners[host] ?: return null
        return runCatching { ctx.packageManager.getApplicationInfo(pkg, 0).uid }.getOrNull()
    }

    fun flows(ctx: Context): ShieldFlows? {
        if (!active) return null
        if (cleared) return ShieldFlows(30, emptyList())
        return ShieldFlows(3_600, table.map { it.copy(uid = uidFor(ctx, it.host)) })
    }

    fun clear() { cleared = true }

    fun scores(): ShieldScoreCache? {
        if (!active) return null
        if (cleared) return ShieldScoreCache()
        val now = System.currentTimeMillis()
        fun s(h: String, score: Int, v: String, label: String?, service: String?, cc: String?,
              org: String? = null, cls: String? = null, provider: String? = null, seen: String = "common", ctx: ShieldCtx? = null) =
            ShieldScore(h, score, v, label, service, cc, "2026-10-24", now + 20_000_000,
                now - if (v == "clear" || v == "known") 900_000 else 120_000, org, cls, provider, seen, ctx)
        val all = listOf(
            s("video.cdn-apple.com", 10, "known", "CDN", "Apple", "US", "Apple Inc.", "cdn"),
            s("icloud.com", 10, "known", "Cloud", "Apple", "US", "Apple Inc.", "cloud"),
            s("scontent.cdninstagram.com", 12, "known", "CDN", "Meta", "US", "Meta Platforms", "cdn"),
            s("www.google.com", 10, "known", "Top site", "Google", "US", "Google LLC", "cdn", "gcp"),
            s("cloudflare.com", 15, "known", "CDN", "Cloudflare CDN", "US", "Cloudflare", "cdn", "cloudflare"),
            s("fonts.gstatic.com", 10, "known", "CDN", "Google", "US", "Google LLC", "cdn", "gcp"),
            s("d1a2b3c4.cloudfront.net", 18, "known", "Cloud", "Amazon cloud", "US", "Amazon.com", "cloud", "aws"),
            s("api.whatsapp.net", 12, "known", "Messaging", "WhatsApp", "US", "Meta Platforms", "cloud"),
            s("metrics.adnet-sync.xyz", 64, "suspicious", "Scanner", null, "SC", "OFFSHORE-VPS-NET", "hosting", seen = "rare",
                ctx = ShieldCtx(64511, "Offshore VPS Net Ltd", "SC", "vps-23.offshore-vps.example", "scanner", "Scans the internet", "scanner")),
            s("cdn-update.secure-login.top", 96, "blocked", "Phishing", null, "NL", "EXAMPLE-HOSTING", "hosting", seen = "rare",
                ctx = ShieldCtx(64500, "Example Hosting B.V.", "NL", "vps-203-0-113-9.example-hosting.nl", "phishing", "Phishing page", "phishing")),
            s("time.android.com", 0, "clear", null, null, "US", "Google LLC", "cloud"),
            s("rr3---sn-a5mekn.googlevideo.com", 10, "known", "CDN", "Google", "IE", "Google LLC", "cdn", "gcp"),
            s("news.example-daily.com", 0, "clear", null, null, "DE", "EXAMPLE-MEDIA-HOSTING", "hosting"),
            s("backup.sync-cloud.io", 0, "clear", null, null, "SG", "DigitalOcean", "cloud", "digitalocean"),
            s("2a03:2880:f12f:83:face:b00c:0:25de", 12, "known", "CDN", "Meta", "NL", "Meta Platforms", "cdn"),
        )
        return ShieldScoreCache("2026-10-24", now - 240_000, all.associateByTo(LinkedHashMap()) { it.host })
    }

    fun report(): ShieldScanReport? {
        if (!active) return null
        fun i(h: String, v: String, label: String? = null, service: String? = null, cc: String? = null,
              asn: Int? = null, asname: String? = null, ips: List<String> = emptyList(), insights: List<String> = emptyList()) =
            ShieldScanItem(h, ips, v, label, service, cc, asn, asname, insights)
        return ShieldScanReport("2026-10-24", 12, 1, 1, 9, 1, listOf(
            i("cdn-update.secure-login.top", "blocked", "Phishing", null, "NL", 64500, "EXAMPLE-HOSTING, NL", listOf("203.0.113.9"),
                listOf("203.0.113.9 has been implicated in hosting a malicious phishing page.")),
            i("metrics.adnet-sync.xyz", "suspicious", "Scanner", null, "SC", 64511, "OFFSHORE-VPS-NET", listOf("198.51.100.23"),
                listOf("198.51.100.23 has been observed scanning the internet for open ports.")),
            i("video.cdn-apple.com", "known", "CDN", "Apple", "US", 714, "APPLE-ENGINEERING"),
            i("icloud.com", "known", "Cloud", "Apple", "US", 714, "APPLE-ENGINEERING"),
            i("scontent.cdninstagram.com", "known", "CDN", "Meta", "US", 32934, "FACEBOOK"),
            i("www.google.com", "known", "Top site", "Google", "US", 15169),
            i("cloudflare.com", "known", "CDN", "Cloudflare CDN", "US", 13335),
            i("fonts.gstatic.com", "known", "CDN", "Google", "US", 15169),
            i("d1a2b3c4.cloudfront.net", "known", "Cloud", "Amazon cloud", "US", 16509),
            i("api.whatsapp.net", "known", "Messaging", "WhatsApp", "US", 32934),
            i("17.57.144.84", "known", "Messaging", "Apple", "US", 714, ips = listOf("17.57.144.84")),
            i("time.android.com", "clear"),
        ))
    }

    fun profiles(): List<ShieldProfile>? {
        if (!active) return null
        fun p(id: String, name: String, kind: String, n: Int) = ShieldProfile(id, name, kind, n)
        return listOf(
            p("ads", "Ads & trackers", "domains", 61_000),
            p("ads:de", "German ad list", "domains", 4_200),
            p("ads:ru", "Russian ad list", "domains", 6_800),
            p("ads:ir", "Persian ad list", "domains", 1_300),
            p("adult", "Adult content", "domains", 84_000),
            p("social", "Social media", "domains", 152),
            p("gambling", "Gambling", "domains", 9_400),
            p("provider:aws", "Connections to Amazon Web Services", "ranges", 7_900),
            p("provider:cloudflare", "Connections to Cloudflare", "ranges", 22),
            p("class:hosting", "Servers in hosting centres, not home or mobile networks", "ranges", 31_000),
            p("region:eu", "European Union", "ranges", 41_000),
            p("region:cn", "China", "ranges", 8_700),
            p("region:ru", "Russia", "ranges", 12_300),
            p("region:five-eyes", "Five Eyes", "ranges", 96_000),
            p("country:CN", "Connections to China", "country", 8_700),
            p("country:RU", "Connections to Russia", "country", 12_300),
            p("country:KP", "Connections to North Korea", "country", 4),
            p("country:IR", "Connections to Iran", "country", 2_600),
            p("country:DE", "Connections to Germany", "country", 15_800),
            p("country:NL", "Connections to Netherlands", "country", 9_900),
            p("country:US", "Connections to United States", "country", 61_000),
        )
    }

    /** The Blocked list (fixed wall-clock times today, like iOS). */
    fun blockedRows(): List<GuardStore.Row>? {
        if (!active) return null
        val midnight = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
        }.timeInMillis
        val now = System.currentTimeMillis()
        fun at(minAgo: Long) = maxOf(midnight, now - minAgo * 60_000)
        return listOf(
            // ctx as the server sends it (asn, org, cc, rdns, reason_key, reason),
            // one with the country only (the regional map's), one with none (a
            // record from before ctx existed).
            //
            // 🚨 EVERY ADDRESS, ASN, ORG AND HOSTNAME HERE MUST BE FICTIONAL.
            // These rows are published: they are what the website and the store
            // screenshots show. Two of them were not, and both shipped into the
            // 477 captures before anyone read the images:
            //   - 2a01:4f8:c17:1a2b::1 / AS24940 / "Hetzner Online GmbH" with
            //     their real your-server.de rDNS. 2a01:4f8::/29 is genuinely
            //     Hetzner's, so that was a real, large, reputable host publicly
            //     named as a malware host in a sales screenshot.
            //   - 185.220.101.47 labelled "Controls infected devices". That /24
            //     is one of the best known Tor EXIT ranges there is. It was the
            //     one claim on the screen a security-literate reader could check
            //     independently, and it was wrong -- in front of exactly the
            //     audience that recognises Tor ranges and uses circumvention
            //     tools.
            // Use only: RFC 3849 2001:db8::/32, RFC 5737 TEST-NET
            // (192.0.2.0/24, 198.51.100.0/24, 203.0.113.0/24), RFC 5398
            // documentation ASNs 64496-64511, and invented org names. Reason
            // lines stay vivid -- they are the most useful part of this screen
            // to a reader -- but a vivid reason about a real network is a
            // defamation problem, not a design choice.
            GuardStore.Row("2001:db8:4f8:c17::1", 443, 0, "malware", null, at(1), true,
                ShieldCtx(64498, "Example Datacentre Ltd", "DE", "static-2001-db8-4f8-c17.clients.example-datacentre.de",
                    "malware_host", "Serves a banking trojan installer", "malware")),
            GuardStore.Row("cdn-update.secure-login.top", 443, 0, "Phishing", null, at(2), true,
                ShieldCtx(64500, "Example Hosting B.V.", "NL", "vps-203-0-113-9.example-hosting.nl", "phishing", "Phishing page", "phishing")),
            GuardStore.Row("files.dl-free-apk.icu", 443, 0, "malware", null, at(40), true),
            // 192.0.2.44, not 198.51.100.23: that one is already the address behind
            // metrics.adnet-sync.xyz in scores()/report() above, and one fixture
            // address wearing two different roles reads as a bug if both are on
            // screen. Country-only ctx on purpose -- this is the regional map's shape.
            GuardStore.Row("192.0.2.44", 8080, 0, "controller", null, at(480), true, ShieldCtx(cc = "DE")),
            GuardStore.Row("videos.adult-site.example", 443, 0, "profile:adult", null, at(95), true),
            GuardStore.Row("ads.tracker.example", 443, 0, "user", null, at(130), true),
        ).sortedByDescending { it.ts }
    }
}
