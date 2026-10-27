package com.rostamvpn.android

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * Kotlin-side config manager. Parses the config JSON that was previously
 * handled by RustBridge, providing all data the UI needs.
 */
object ConfigManager {
    private const val TAG = "ConfigManager"

    // Parsed state
    var configVersion: String = ""
        private set

    var serverLocations: List<ServerLocation> = emptyList()
        private set

    /** Geographic server regions from the top-level `regions` block (e.g. ["fra","lax"]).
     *  The user picks one (or Auto); netcore decides the protocol within it. */
    var serverRegions: List<String> = emptyList()
        private set

    /** Display labels for regions, OTA-driven (e.g. {"fra":"🇩🇪 Frankfurt"}).
     *  No region names are hardcoded in the app — adding a region is OTA-only. */
    var regionLabels: Map<String, String> = emptyMap()
        private set


    var disabledTransports: Set<String> = emptySet()
        private set

    /**
     * Ad policy — fully config/OTA driven, per region. No region's ad behaviour is
     * hardcoded in the app; monetization can be retargeted without a release.
     *
     * `mode` uses the same vocabulary as the cashier's `RegionOffering.ads_mode`
     * so both sides read alike:
     *   - `always`      — show an adtech ad on every eligible connect
     *   - `after_quota` — only once the user is over their free quota
     *   - `sponsor`     — house/sponsor creative instead of commercial demand
     *   - `off`         — never show ads in this region
     *
     * `optOutRegions` lists regions where the user may switch ads off themselves
     * (free-access regions, where ads are the funding model rather than the price).
     * Premium is always ad-free regardless of any of this.
     */
    /**
     * On/off control for ONE ad source (ad type). Symmetric across the two
     * sources so each is governed the same way:
     *   - enabled     : master switch for this source, all regions.
     *   - offRegions  : regions where THIS source is suppressed even when enabled.
     * `allowedIn(region)` = enabled AND region not suppressed.
     */
    data class AdSource(
        val enabled: Boolean,
        val offRegions: Set<String>,
    ) {
        fun allowedIn(region: String?): Boolean =
            enabled && !offRegions.contains(region?.uppercase().orEmpty())

        companion object {
            val ON = AdSource(enabled = true, offRegions = emptySet())
            fun parse(o: org.json.JSONObject?): AdSource {
                if (o == null) return ON
                val off = o.optJSONArray("off_regions")
                    ?.let { a -> (0 until a.length()).map { a.getString(it).uppercase() }.toSet() }
                    ?: emptySet()
                return AdSource(enabled = o.optBoolean("enabled", true), offRegions = off)
            }
        }
    }

    data class AdsConfig(
        val enabled: Boolean,
        val defaultMode: String,
        val regionModes: Map<String, String>,
        val optOutRegions: Set<String>,
        /**
         * Seconds between interstitials while connected.
         * 0 = once per connect only (no mid-session loop).
         * Negative / nonsense values fall back to the default.
         */
        val intervalSecs: Int = DEFAULT_INTERVAL_SECS,
        /**
         * Show NO ads at all until the install has successfully connected this
         * many times. 0 (the default) keeps today's behaviour exactly.
         *
         * The point is the first impression: a user who has never seen the
         * tunnel work should not meet an interstitial before it does. It counts
         * SUCCESSFUL CONNECTS, not taps — counting taps would let someone on a
         * bad network burn the whole allowance on attempts that never produced
         * a working VPN, which is precisely backwards.
         *
         * Per install, not per person: a reinstall resets it, the same as
         * `sample_pct` and the cid.
         */
        val graceConnects: Int = 0,
        /**
         * Per-ad-type on/off. Two independent sources in the waterfall:
         *   - admobAd      : the Google AdMob (+ mediation) interstitial, tried first
         *   - rostamAdtech : our own adtech container (cashier /ad/interstitial),
         *                    the fallback / no-AdMob source
         * Each is a plain on/off (with an optional per-region off list). Turning a
         * source off means that source never runs; the other still can.
         */
        val admobAd: AdSource = AdSource.ON,
        val rostamAdtech: AdSource = AdSource.ON,
        /**
         * Base URL of our adtech container (the interstitial HTML host). The
         * static container/warm-up HTML is served from here rather than baked into
         * the app, so the shell can change server-side. Empty → fall back to the
         * cashier base + "/ad/interstitial".
         */
        val adtechUrl: String = "",
        /**
         * Share of impressions that try AdMob FIRST, 0-100, per region.
         *
         * Not an on/off: both sources stay in the waterfall, this only decides
         * which is asked first. Whichever loses the roll is still the fallback,
         * so a no-fill on one never costs the impression.
         *
         * The point is revenue mix, not availability. AdMob serves every region
         * we operate in, so this is never a workaround for a source that cannot
         * fill -- it is a choice about which demand gets the impression.
         *
         * Our own exchange needs volume before its demand partners will serve
         * (SmartyAds wants 100k impressions first), so the largest regions are
         * pointed at it to build that up. `IR: 0` sends every Iranian impression
         * to our exchange; an absent region uses [admobSharePct].
         */
        val admobSharePct: Int = 100,
        val admobShareRegions: Map<String, Int> = emptyMap(),
    ) {
        /** Share of impressions in [region] that ask AdMob first. */
        fun admobShareFor(region: String?): Int =
            admobShareRegions[region?.uppercase().orEmpty()] ?: admobSharePct

        /**
         * Whether THIS impression should ask AdMob first.
         *
         * Rolled per impression rather than assigned per install, so the split
         * holds for one heavy user as well as across many. A per-install
         * assignment would let a handful of users skew the mix badly at low
         * volume, which is exactly the volume this is meant to steer.
         */
        fun admobFirst(region: String?, roll: Int = (0..99).random()): Boolean {
            val pct = admobShareFor(region).coerceIn(0, 100)
            // If a source is not allowed here at all, order is moot; the caller
            // still checks each source before using it.
            return roll < pct
        }
        fun modeFor(region: String?): String {
            if (!enabled) return MODE_OFF
            val r = region?.uppercase().orEmpty()
            // The CONFIG decides, with no special case for freedom regions:
            // an explicit entry wins, otherwise defaultMode applies everywhere.
            // A client that silently disagrees with its config is worse than a
            // config that is wrong — the config is inspectable and publishable.
            // The guard against forgetting a region is in validate.py, loudly.
            return regionModes[r] ?: defaultMode
        }

        fun mayOptOut(region: String?) = optOutRegions.contains(region?.uppercase().orEmpty())

        /** AdMob source allowed for [region]: its toggle is on AND ads aren't
         *  globally off for the region (mode "off" still means fully ad-free). */
        fun admobAllowed(region: String?): Boolean =
            admobAd.allowedIn(region) && modeFor(region) != MODE_OFF

        /** Adtech source allowed for [region]: its toggle is on AND region mode
         *  isn't "off". (Mode/quota/opt-out still apply inside the cashier path.) */
        fun adtechAllowed(region: String?): Boolean =
            rostamAdtech.allowedIn(region) && modeFor(region) != MODE_OFF

        companion object {
            const val MODE_ALWAYS = "always"
            const val MODE_AFTER_QUOTA = "after_quota"
            const val MODE_SPONSOR = "sponsor"
            const val MODE_OFF = "off"
            private val VALID = setOf(MODE_ALWAYS, MODE_AFTER_QUOTA, MODE_SPONSOR, MODE_OFF)
            /** Fallback when the config omits interval_secs or sets a negative. */
            const val DEFAULT_INTERVAL_SECS = 0
            /**
             * 0 is valid: show on each connect, then stop until the next connect.
             * Negative is a typo. Positive values are the mid-session cadence.
             */
            fun sanitizeInterval(v: Int) = if (v < 0) DEFAULT_INTERVAL_SECS else v
            fun sanitize(mode: String) = if (mode in VALID) mode else MODE_ALWAYS
            /** Clamped, because a share outside 0-100 is a typo and silently
             *  sending every impression one way is an expensive one. */
            fun sanitizeShare(v: Int) = v.coerceIn(0, 100)
            // NAMED, not positional. This was positional and inserting one
            // parameter into the data class silently re-bound five of them —
            // it only failed to compile because the types happened to differ.
            /** Every source off, every region off: what a no-ads brand runs. */
            val OFF = AdsConfig(
                enabled = false,
                defaultMode = MODE_OFF,
                regionModes = emptyMap(),
                optOutRegions = emptySet(),
                admobAd = AdSource(enabled = false, offRegions = emptySet()),
                rostamAdtech = AdSource(enabled = false, offRegions = emptySet()),
            )
            val DEFAULT = AdsConfig(
                enabled = true,
                defaultMode = MODE_ALWAYS,
                regionModes = emptyMap(),
                optOutRegions = emptySet(),
                intervalSecs = DEFAULT_INTERVAL_SECS,
                graceConnects = 0,
                admobAd = AdSource.ON,
                rostamAdtech = AdSource.ON,
                adtechUrl = "",
                admobSharePct = 100,
                admobShareRegions = emptyMap(),
            )
        }
    }

    var adsConfig: AdsConfig = if (BuildConfig.ADS) AdsConfig.DEFAULT else AdsConfig.OFF
        private set

    /**
     * Protocol priority: which top-level transport is tried first. Listed order
     * is try order; omitting one skips it entirely. `disabled_transports` in the
     * matched region policy still overrides this per region.
     *
     * Config-driven so the ladder can be reordered (or a transport pulled) OTA
     * instead of by editing connectAuto.
     */
    // The FALLBACK, used only when no config parses at all -- no cached remote
    // and an unreadable bundled asset. It said ["redsocket", "wgtcp"], which was
    // the one place config could not reach: wgtcp is off in all six configs and
    // in every live slot, so a hardcoded default that still lists it is the only
    // way it could come back. The client code stays; this just stops it being
    // re-enabled by a parse failure. iOS's own fallback (ConfigPolicy:195) has
    // read ["redsocket"] for a while, so this also ends the asymmetry.
    //
    // Nothing is lost: WgtcpProvisioner only runs when the order CONTAINS wgtcp
    // (VpnViewModel:654), so an unprovisioned device could never have connected
    // over it anyway -- the entry was dead weight that read as live policy.
    var transportOrder: List<String> = listOf("redsocket")
        private set

    /**
     * Per-region transport order, overriding [transportOrder].
     *
     * Keyed by the SAME region code the ad policy and the free tier use, so all
     * three answer to one detection path rather than three. An absent region
     * gets [transportOrder].
     *
     * The shipped intent: netcore everywhere, and wgtcp additionally available
     * in the freedom regions. wgtcp exists to survive filtering that netcore
     * cannot, which is a problem the rest of the world does not have — offering
     * it globally only widens the surface a censor can fingerprint, for no gain.
     */
    var transportOrderRegions: Map<String, List<String>> = emptyMap()
        private set

    /** The transport ladder for [region], falling back to the global order. */
    fun transportOrderFor(region: String?): List<String> =
        transportOrderRegions[region?.uppercase().orEmpty()] ?: transportOrder

    /**
     * Regions that get the product free because it is donor-funded there — not a
     * paid tier and never a trial (you can't withdraw it).
     *
     * Key = ISO country, matched against the SIM/network country. Value = device
     * timezones that also count, so a foreign SIM inside the country is still
     * free. Both signals, because either alone is wrong: a diaspora SIM abroad
     * shouldn't get it, and a local user roaming on a foreign SIM should.
     *
     * Config-driven so adding a region (e.g. RU) is a config edit — nothing in
     * Kotlin knows any region by name.
     */
    /**
     * Does THIS BRAND grant the free tier in freedom regions?
     *
     * 🚨 This is a separate switch from [freedomRegions] ON PURPOSE, and the two
     * must not be collapsed. `freedom_regions` is also the TIMEZONE → ISO table
     * the Rust core uses to resolve `ipv4_first_regions` (`config.rs prefer_v6`),
     * so emptying it to disable a brand's free tier would send Iranian and
     * Russian users back to dialling IPv6 first — 0/50 from Russia, a full 32s
     * DIRECT_BUDGET burned before any working address (§12c). Silently, and in
     * the regions with the worst networks.
     *
     * DollarVPN is a commercial product with no donor-funded tier: everyone pays
     * after the free allowance. RedVPN has none either, from the build side:
     * manifest `features.freeRegions: false` -> BuildConfig.FREE_REGIONS.
     */
    var freedomTier: Boolean = !BuildConfig.PAID_ONLY && BuildConfig.FREE_REGIONS

    /**
     * `paid_only`: the brand has NO free allowance at all, from the first
     * connect. Absent = false. Distinct from `throttle.hard_stop` (stop AFTER
     * an allowance) and deliberately not inferred from `free_kbps: 0`: the
     * server still reports a free quota until the cashier is re-scoped, and
     * this switch must not depend on that. Internet Pro, 2026-10-12.
     */
    var paidOnly: Boolean = BuildConfig.PAID_ONLY

    /** OTA `protection.enabled`: the fleet switch for exit-side protection.
     *  Absent = false. Drives the Connect status line ("Protected") and, once
     *  the client core announces `blk1`, whether the cap is sent. */
    var protectionEnabled: Boolean = false

    /**
     * OTA `tunnel_ipv6`: hev bridges IPv6 packets from the tun (v6-literal
     * destinations, v6-only hosts) to the core as SOCKS ATYP 4, which the core
     * relays to the exit as ATYP 4. Absent = false: v6 stays captured by
     * `::/0` and dropped, so apps fall back to v4 exactly as before. Only for
     * a fleet whose exits accept ATYP 4 (owner 2026-10-17, exit IPv6 work).
     */
    var tunnelIpv6: Boolean = false

    /** OTA `tunnel.udp` (HevConfig.tunnelUdp): UDP over the tunnel. Absent = false. */
    var tunnelUdp: Boolean = false

    /** OTA `messaging.enabled`: the client announces msg1 (control stream).
     *  Read by the core from the same document; kept here for the UI. */
    var messagingEnabled: Boolean = false

    /**
     * OTA `protection.categories`: category name -> verdict bit, from the
     * pipeline's meta.json bit table. Drives the Rules toggles. Empty (the
     * default) hides them: the client never invents category names.
     */
    var protectionCategories: Map<String, Int> = emptyMap()

    /**
     * OTA `redshield.*`, the device-side protection loop (REDSHIELD-TRAFFIC
     * Amendments A/B), same defaults and clamps as iOS GuardianScoreSettings:
     * absent = on, every 600 s, 100 per batch. `enabled = false` means no
     * score requests and no automatic denies; the user's own rules still
     * apply. Only read by a brand with BuildConfig.PROTECTION_NAME.
     */
    var redshieldEnabled: Boolean = true
    var redshieldIntervalSecs: Int = 600
    var redshieldBatch: Int = 100
    /** OTA `redshield.ask_server`: show Ask RedShield's free-text field (a hook; nothing is sent yet). Absent = false. */
    var redshieldAskServer: Boolean = false

    var freedomRegions: Map<String, List<String>> = mapOf("IR" to listOf("Asia/Tehran", "Iran"))

    /**
     * Keyboard languages that prove membership of a freedom region, per region.
     *
     * An installed Persian or Kurdish keyboard is something a person set up
     * because they read the language. Unlike a SIM or a timezone it survives a
     * VPN, a reinstall and travel, and nobody installs one to get a discount —
     * which is why iOS made it the FIRST signal and Android should agree.
     *
     * Config-driven for the same reason as [freedomRegions]: no language and no
     * region is named in Kotlin. The default carries only IR, deliberately —
     * a Russian keyboard is ubiquitous across the former USSR and the diaspora,
     * so `ru` here would hand the product to a large population that is not in
     * a funded region. Add it in config if that is ever the intent.
     */
    var freedomKeyboards: Map<String, List<String>> = mapOf("IR" to listOf("fa", "ckb"))

    /**
     * Should a keyboard-only claim be refused when the HARDWARE disagrees?
     *
     * false — a Persian keyboard grants the free tier on its own.
     * true (default) — it still grants, unless a SIM or serving network names a
     *                  non-freedom country AND the timezone already declined.
     *
     * Ships TRUE. Persian and Kurdish are spoken well beyond Iran, so a keyboard
     * alone was handing the product to Qatar, the UAE, Turkey and the wider
     * diaspora — people who can pay and whom the donor funding was not raised
     * for. A carrier-issued SIM naming one of those countries is the evidence
     * that settles it.
     *
     * What this CANNOT do is reach anyone in Iran. To be refused you need a
     * non-freedom SIM *and* a non-freedom serving network *and* a non-freedom
     * timezone at once — a description of being somewhere else. An Iranian with
     * an English-only phone never reaches this code at all: an IR SIM grants at
     * the location step, before any language is considered.
     *
     * `lastFreedomSignal` records which signal decided, so the size of each
     * population is measurable rather than assumed.
     */
    var strictKeyboard: Boolean = true
        private set

    /** Free-tier speed limit (see `throttle` in the config). */
    /**
     * @param hardStop STOP instead of slowing down when the allowance runs out.
     *   The free tier normally stays usable forever at [freeKbps]; a brand with
     *   this on has no free tier at all past the allowance — DollarVPN is a
     *   commercial product and everyone pays. Config-driven so it is per brand
     *   with no `if (brand == …)` in Kotlin (§6b).
     */
    data class ThrottleConfig(val enabled: Boolean, val freeKbps: Int, val hardStop: Boolean = false) {
        companion object { val DEFAULT = ThrottleConfig(false, 0) }
    }

    var throttleConfig: ThrottleConfig = ThrottleConfig.DEFAULT

    /**
     * When the store's rating card may be asked for. Server-owned, like every
     * other policy in this file (§7b) — these were compile-time constants in
     * ReviewManager, which meant tuning them cost a release on every brand.
     *
     * Every value is floored on parse so a bad edit cannot become "ask on every
     * disconnect": that would burn Play's per-user quota, which we cannot read
     * and cannot reset.
     */
    data class ReviewConfig(
        val enabled: Boolean,
        /** Seconds a connection must HOLD before it counts as a real success. */
        val minSustainedSecs: Int,
        /** Sustained sessions required before we are willing to ask at all. */
        val minGoodSessions: Int,
        /** Days since first use before we are willing to ask at all. */
        val minDaysSinceFirstUse: Int,
        /** Days between two asks, enforced by us — Play's counter is unreadable. */
        val askCooldownDays: Int,
    ) {
        companion object {
            val DEFAULT = ReviewConfig(
                enabled = true,
                minSustainedSecs = 30,
                minGoodSessions = 3,
                minDaysSinceFirstUse = 2,
                askCooldownDays = 45,
            )
        }
    }

    var reviewConfig: ReviewConfig = ReviewConfig.DEFAULT

    /**
     * Server-supplied copy overrides, keyed by ANDROID RESOURCE NAME then by
     * language tag:
     *
     *     "paywall": { "strings": {
     *         "paywall_perk_regions_title": { "en": "Multiple Internet Exchanges" }
     *     } }
     *
     * Generic on purpose. Naming 30 individual config fields would mean a
     * release every time a new line wanted tuning; keying on the resource name
     * makes every string on the screen tunable, including ones added later.
     *
     * 🚨 AN OVERRIDE APPLIES ONLY TO THE LANGUAGE IT IS WRITTEN FOR, and there
     * is deliberately NO cross-locale fallback. §17: these strings exist in 38
     * locales, so resolving a missing `fa` to the supplied `en` would ship
     * English to every Persian, Arabic, Russian and Chinese device — which is
     * the exact failure `app_name` caused. Writing `en` tunes English only;
     * every other locale keeps its translation until someone supplies one.
     *
     * ⚠️ Copy pushed this way does NOT pass `tools/store/listing/validate.py`
     * or the i18n validator. §0 still applies: no protocol name, no server
     * count, no exit-country list. Nothing in the client can enforce that.
     */
    var copyOverrides: Map<String, Map<String, String>> = emptyMap()
        private set

    /** Tunnel health watchdog (see `health` in the config). */
    data class HealthConfig(
        val enabled: Boolean,
        val intervalSecs: Int,
        val failuresBeforeReconnect: Int,
        val maxAutoReconnects: Int,
        val cooldownSecs: Int,
        val probeUrl: String,
    ) {
        companion object {
            /** Matches the shipped config; used when the block is absent.
             *  The probe host is per brand (BuildConfig.HEALTH_PROBE_URL, set
             *  from `features.healthProbeUrl`). It was a shared literal, which
             *  put one brand's hostname in every other brand's dex even when
             *  the OTA config pointed somewhere else. */
            val DEFAULT = HealthConfig(true, 45, 2, 3, 300, BuildConfig.HEALTH_PROBE_URL)
        }
    }

    var healthConfig: HealthConfig = HealthConfig.DEFAULT
        private set

    /** Raw config JSON for reference */
    var rawJson: String = "{}"

    /**
     * How to reach the backend.
     *
     * The BOOTSTRAP block: the one thing that cannot itself come from the
     * server, because it is what says where the server is. Read from the bundled
     * config on first launch and from the cached server copy after, so bases and
     * the key can be rotated without a store release -- the bundled copy only
     * has to work once.
     *
     * [bases] is base + fallbacks in order. A single hostname is a single thing
     * to block, and this endpoint is now how a client learns everything else, so
     * losing it costs more than losing any one tunnel endpoint.
     */
    data class ApiConfig(
        val base: String,
        val key: String,
        val fallbacks: List<String>,
        /**
         * LAST-RESORT config paths that are not our hosts at all.
         *
         * Every base above resolves to ONE machine (192.0.2.24). Different
         * hostnames on one IP defeat DNS poisoning and nothing else, so a single
         * IP block removes every config path at once — and the config is how a
         * client learns where everything else is, so losing it costs more than
         * losing any tunnel endpoint.
         *
         * A mirror is a public image proxy. The client asks the PROXY, the proxy
         * fetches our origin, and our address never appears in the client's
         * connection. Each entry is a URL template containing `{url}`, replaced
         * with the percent-encoded origin URL.
         *
         * Trust costs nothing here: the config is signed and then encrypted and
         * verified on-device, so a hostile mirror can withhold it but cannot
         * forge one. That is the whole reason an untrusted third party is
         * acceptable as a carrier.
         */
        val mirrors: List<String> = emptyList(),
    ) {
        /** Every base to try, in order, without duplicates or blanks. */
        val bases: List<String>
            get() = (listOf(base) + fallbacks).map { it.trimEnd('/') }.filter { it.isNotEmpty() }.distinct()

        companion object {
            /** Falls back to the values compiled in, so a config that predates
             *  this block still works rather than leaving the app unable to
             *  reach anything. */
            val DEFAULT = ApiConfig(BuildConfig.CASHIER_BASE, BuildConfig.WGTCP_API_KEY, emptyList())
        }
    }

    var apiConfig: ApiConfig = ApiConfig.DEFAULT
        private set

    data class ServerLocation(
        val id: String,
        val name: String,
        val region: String,
        val transport: String,
        val desc: String
    )

    /**
     * Parse the config JSON (from assets or OTA) and populate all fields.
     */
    fun loadFromJson(json: String) {
        rawJson = json
        // Protect Mode's first-party set follows the config in force (launch,
        // OTA refresh, QR). Its own tolerant parse, before anything here can throw.
        FirstParty.onConfig(json)
        try {
            val obj = JSONObject(json)

            // Version
            configVersion = obj.optString("version", "")

            // Server locations
            val locArr = obj.optJSONArray("locations")
            if (locArr != null) {
                val locs = mutableListOf<ServerLocation>()
                for (i in 0 until locArr.length()) {
                    val loc = locArr.getJSONObject(i)
                    locs.add(
                        ServerLocation(
                            id = loc.getString("id"),
                            name = loc.getString("name"),
                            region = loc.getString("region"),
                            transport = loc.getString("transport"),
                            desc = loc.getString("desc")
                        )
                    )
                }
                serverLocations = locs
            }

            // Geographic server regions (top-level `regions`). Each value is either
            // a bare IP array or an object {"label":"🇩🇪 Frankfurt","ips":[...]}.
            // Region ids AND display labels are OTA-driven — nothing hardcoded here.
            val regionsObj = obj.optJSONObject("regions")
            if (regionsObj != null) {
                serverRegions = regionsObj.keys().asSequence().toList().sorted()
                val labels = mutableMapOf<String, String>()
                for (k in serverRegions) {
                    regionsObj.optJSONObject(k)?.optString("label", "")?.takeIf { it.isNotBlank() }
                        ?.let { labels[k] = it }
                }
                regionLabels = labels
                Log.i(TAG, "Server regions: $serverRegions labels=$regionLabels")
            }


            // Disabled transports from region_policies — a timezone-keyed map:
            //   { "_default": {disabled_transports:[...]}, "Asia/Tehran": {...} }
            // Match the device timezone exactly, else fall back to "_default".
            // (Mirrors the core's region_policy(tz) lookup so Kotlin UI and
            // the Rust race agree on what's enabled per region.)
            val policies = obj.optJSONObject("region_policies")
            if (policies != null) {
                val tz = java.util.TimeZone.getDefault().id
                val matched = policies.optJSONObject(tz) ?: policies.optJSONObject("_default")
                if (matched != null) {
                    val disabled = mutableSetOf<String>()
                    val dtArr = matched.optJSONArray("disabled_transports")
                    if (dtArr != null) {
                        for (i in 0 until dtArr.length()) {
                            disabled.add(dtArr.getString(i))
                        }
                    }
                    disabledTransports = disabled
                    Log.i(TAG, "Region policy: tz=$tz matched=${if (policies.has(tz)) tz else "_default"}, disabled=$disabled")
                }
            }

            // ── Ad policy (per-region, OTA-driven) ───────────────────────────
            // Freedom regions (donor-funded free access) — see the field doc.
            obj.optJSONObject("freedom_regions")?.let { fr ->
                val m = mutableMapOf<String, List<String>>()
                fr.keys().forEach { k ->
                    val tzs = fr.optJSONArray(k)
                    m[k.uppercase()] = if (tzs == null) emptyList()
                        else (0 until tzs.length()).map { tzs.getString(it) }
                }
                // An empty map would silently start charging a funded region, so
                // keep the previous value rather than accept one.
                if (m.isNotEmpty()) {
                    freedomRegions = m
                    Log.i(TAG, "Freedom regions: ${m.keys} (tz signals: ${m.values.sumOf { it.size }})")
                }
            }

            // Read OUTSIDE the block above on purpose: it is independent of
            // whether this config carries freedom_regions at all, and nesting it
            // there would leave a brand's tier switch silently unparsed.
            // Paid-only is a BUILD fact only (BuildConfig.PAID_ONLY, from
            // brands/configs/<id>.json, the source gen-ios.py also reads). The
            // remote value is logged, never applied, not even as a tightening:
            // a paid-only config landing in the SHARED slot would otherwise make
            // every brand paid-only (aligned with iOS, 2026-10-13).
            paidOnly = BuildConfig.PAID_ONLY
            if (obj.has("paid_only") && obj.optBoolean("paid_only") != paidOnly)
                Log.w(TAG, "remote paid_only=${obj.optBoolean("paid_only")} ignored; build says $paidOnly")
            // A paid-only build never has a free tier, whatever the config says.
            // ... and neither does a brand whose manifest says freeRegions: false
            // (RedVPN, 2026-10-25); the config can never turn it back on.
            freedomTier = !paidOnly && BuildConfig.FREE_REGIONS && obj.optBoolean("freedom_tier", true)
            protectionEnabled = obj.optJSONObject("protection")?.optBoolean("enabled", false) ?: false
            obj.optJSONObject("redshield").let { r ->
                redshieldEnabled = r?.optBoolean("enabled", true) ?: true
                redshieldIntervalSecs = (r?.optInt("score_interval_secs", 600) ?: 600).coerceIn(60, 86_400)
                redshieldBatch = (r?.optInt("score_batch", 100) ?: 100).coerceIn(1, 100)
                redshieldAskServer = r?.optBoolean("ask_server", false) ?: false
            }
            tunnelIpv6 = obj.optBoolean("tunnel_ipv6", false)
            tunnelUdp = HevConfig.tunnelUdp(obj)
            messagingEnabled = obj.optJSONObject("messaging")?.optBoolean("enabled", false) ?: false
            protectionCategories = obj.optJSONObject("protection")?.optJSONObject("categories")?.let { c ->
                c.keys().asSequence().mapNotNull { k -> c.optInt(k, -1).takeIf { it in 0..15 }?.let { k to it } }.toMap()
            } ?: emptyMap()
            if (paidOnly) Log.i(TAG, "paid_only: no free allowance for this brand")
            if (!freedomTier) Log.i(TAG, "Freedom tier DISABLED for this brand — commercial only")

            // Keyboard signals for those same regions — see freedomKeyboards.
            obj.optJSONObject("freedom_keyboards")?.let { fk ->
                val m = mutableMapOf<String, List<String>>()
                fk.keys().forEach { k ->
                    val langs = fk.optJSONArray(k)
                    m[k.uppercase()] = if (langs == null) emptyList()
                        else (0 until langs.length()).map { langs.getString(it).lowercase() }
                }
                // Unlike freedom_regions an EMPTY map is accepted here: it is the
                // only way to turn the keyboard signal off from config, and doing
                // so cannot start charging a region that its tz still covers.
                freedomKeyboards = m
                Log.i(TAG, "Freedom keyboards: " +
                    m.entries.joinToString { "${it.key}=${it.value}" }.ifEmpty { "(none)" })
            }

            if (obj.has("freedom_keyboard_strict")) {
                strictKeyboard = obj.optBoolean("freedom_keyboard_strict", true)
                Log.i(TAG, "Freedom keyboard strict: $strictKeyboard")
            }

            obj.optJSONObject("throttle")?.let { t ->
                throttleConfig = ThrottleConfig(
                    enabled = t.optBoolean("enabled", false),
                    // Floor it: a tiny value would be indistinguishable from a
                    // broken tunnel and generate support load, not upgrades.
                    freeKbps = t.optInt("free_kbps", 0).let { k -> if (k in 1..255) 256 else k },
                    hardStop = t.optBoolean("hard_stop", false),
                )
                Log.i(TAG, "Throttle: enabled=${throttleConfig.enabled} freeKbps=${throttleConfig.freeKbps}")

                val rv = obj.optJSONObject("review")
                val rd = ReviewConfig.DEFAULT
                reviewConfig = if (rv == null) rd else ReviewConfig(
                    enabled = rv.optBoolean("enabled", rd.enabled),
                    // Floors, not clamps to the default: an operator lowering a
                    // value deliberately must be honoured, but zero or negative
                    // would mean "no gate at all" on a quota we cannot see.
                    minSustainedSecs = rv.optInt("min_sustained_secs", rd.minSustainedSecs)
                        .coerceAtLeast(10),
                    minGoodSessions = rv.optInt("min_good_sessions", rd.minGoodSessions)
                        .coerceAtLeast(1),
                    minDaysSinceFirstUse = rv.optInt("min_days_since_first_use", rd.minDaysSinceFirstUse)
                        .coerceAtLeast(0),
                    askCooldownDays = rv.optInt("ask_cooldown_days", rd.askCooldownDays)
                        .coerceAtLeast(30),
                )
                obj.optJSONObject("paywall")?.optJSONObject("strings")?.let { ps ->
                    val out = HashMap<String, Map<String, String>>()
                    for (name in ps.keys()) {
                        val byLang = ps.optJSONObject(name) ?: continue
                        val m = HashMap<String, String>()
                        for (lang in byLang.keys()) {
                            val v = byLang.optString(lang, "")
                            // Blank is not an override: it would blank the line
                            // on screen rather than fall back to the resource.
                            if (v.isNotBlank()) m[lang.lowercase()] = v
                        }
                        if (m.isNotEmpty()) out[name] = m
                    }
                    copyOverrides = out
                    Log.i(TAG, "Copy overrides: ${out.size} keys ${out.keys.take(6)}")
                } ?: run { copyOverrides = emptyMap() }

                Log.i(TAG, "Review: enabled=${reviewConfig.enabled} " +
                    "sessions=${reviewConfig.minGoodSessions} " +
                    "days=${reviewConfig.minDaysSinceFirstUse} " +
                    "sustained=${reviewConfig.minSustainedSecs}s")
            }

            obj.optJSONObject("health")?.let { h ->
                healthConfig = HealthConfig(
                    enabled = h.optBoolean("enabled", true),
                    intervalSecs = h.optInt("interval_secs", 45).coerceAtLeast(15),
                    failuresBeforeReconnect = h.optInt("failures_before_reconnect", 2).coerceAtLeast(1),
                    maxAutoReconnects = h.optInt("max_auto_reconnects", 3).coerceAtLeast(1),
                    cooldownSecs = h.optInt("cooldown_secs", 300).coerceAtLeast(30),
                    probeUrl = h.optString("probe_url", HealthConfig.DEFAULT.probeUrl)
                        .ifBlank { HealthConfig.DEFAULT.probeUrl },
                )
                Log.i(TAG, "Health watchdog: enabled=${healthConfig.enabled} " +
                    "every=${healthConfig.intervalSecs}s misses=${healthConfig.failuresBeforeReconnect}")
            }

            obj.optJSONArray("transport_order")?.let { arr ->
                val order = (0 until arr.length()).map { arr.getString(it).lowercase() }
                // An empty list would leave nothing to connect with — ignore it
                // rather than ship a client that can never come up.
                if (order.isNotEmpty()) {
                    transportOrder = order
                    Log.i(TAG, "Transport order: $order")
                }
            }
            obj.optJSONObject("transport_order_regions")?.let { tr ->
                val m = mutableMapOf<String, List<String>>()
                tr.keys().forEach { k ->
                    val arr = tr.optJSONArray(k)
                    val list = if (arr == null) emptyList()
                        else (0 until arr.length()).map { arr.getString(it).lowercase() }
                    // An empty ladder would leave that region unable to connect at
                    // all, so it is dropped rather than honoured.
                    if (list.isNotEmpty()) m[k.uppercase()] = list
                }
                if (m.isNotEmpty()) {
                    transportOrderRegions = m
                    Log.i(TAG, "Transport order per region: " +
                        m.entries.joinToString { "${it.key}=${it.value}" })
                }
            }

            obj.optJSONObject("api")?.let { a ->
                val base = a.optString("base", "").trim().ifEmpty { BuildConfig.CASHIER_BASE }
                val key = a.optString("key", "").trim().ifEmpty { BuildConfig.WGTCP_API_KEY }
                val fb = a.optJSONArray("fallback_bases")
                    ?.let { arr -> (0 until arr.length()).map { arr.getString(it).trim() } }
                    ?.filter { it.isNotEmpty() } ?: emptyList()
                // Absent means none, never a baked-in default: a mirror is
                // somebody else's infrastructure and which one we lean on has to
                // stay a published decision, revocable without an app release.
                val mirrors = a.optJSONArray("mirrors")
                    ?.let { arr -> (0 until arr.length()).map { arr.getString(it).trim() } }
                    ?.filter { it.contains("{url}") } ?: emptyList()
                apiConfig = ApiConfig(base, key, fb, mirrors)
                Log.i(TAG, "API: ${apiConfig.bases.joinToString(", ")} " +
                    "(key ${key.length} chars, ${mirrors.size} mirror(s))")
            }

            val adsObj = obj.optJSONObject("ads")
            adsConfig = if (!BuildConfig.ADS) {
                // A no-ads brand (android.adtech false): the block is not even
                // read, so no published config can switch an ad on.
                AdsConfig.OFF
            } else if (adsObj != null) {
                val modes = mutableMapOf<String, String>()
                adsObj.optJSONObject("regions")?.let { r ->
                    r.keys().forEach { k ->
                        modes[k.uppercase()] = AdsConfig.sanitize(r.optString(k, AdsConfig.MODE_ALWAYS))
                    }
                }
                val optOut = adsObj.optJSONArray("opt_out_regions")
                    ?.let { a -> (0 until a.length()).map { a.getString(it).uppercase() }.toSet() }
                    ?: emptySet()
                // Per-ad-type on/off. `admob_ad` and `rostam_adtech` are objects:
                //   { "enabled": true|false, "off_regions": ["DE", ...] }
                // Missing block → that source ON everywhere (never silently ad-free).
                val admobAd = AdSource.parse(adsObj.optJSONObject("admob_ad"))
                val rostamAdtech = AdSource.parse(adsObj.optJSONObject("rostam_adtech"))
                val adtechUrl = adsObj.optString("adtech_url", "").trim()
                // Revenue mix: what share of impressions asks AdMob first, with
                // an optional per-region override. Absent → 100, i.e. exactly
                // the behaviour before this existed.
                val sharePct = AdsConfig.sanitizeShare(adsObj.optInt("admob_share_pct", 100))
                val shareRegions = mutableMapOf<String, Int>()
                adsObj.optJSONObject("admob_share_regions")?.let { r ->
                    r.keys().forEach { k ->
                        shareRegions[k.uppercase()] = AdsConfig.sanitizeShare(r.optInt(k, sharePct))
                    }
                }
                AdsConfig(
                    enabled = adsObj.optBoolean("enabled", true),
                    defaultMode = AdsConfig.sanitize(
                        adsObj.optString("default_mode", AdsConfig.MODE_ALWAYS)
                    ),
                    regionModes = modes,
                    optOutRegions = optOut,
                    intervalSecs = AdsConfig.sanitizeInterval(
                        adsObj.optInt("interval_secs", AdsConfig.DEFAULT_INTERVAL_SECS)
                    ),
                    // Floored at 0 so a negative can never mean "infinite grace"
                    // and silently turn ads off for the whole fleet.
                    graceConnects = adsObj.optInt("grace_connects", 0).coerceAtLeast(0),
                    admobAd = admobAd,
                    rostamAdtech = rostamAdtech,
                    adtechUrl = adtechUrl,
                    admobSharePct = sharePct,
                    admobShareRegions = shareRegions,
                ).also {
                    Log.i(TAG, "Ads policy: enabled=${it.enabled} default=${it.defaultMode} " +
                        "grace=${it.graceConnects} " +
                        "regions=${it.regionModes} optOut=${it.optOutRegions} " +
                        "admobAd=${it.admobAd.enabled}/off${it.admobAd.offRegions} " +
                        "adtech=${it.rostamAdtech.enabled}/off${it.rostamAdtech.offRegions} " +
                        "admobShare=${it.admobSharePct}%${it.admobShareRegions} " +
                        "adtechUrl=${it.adtechUrl.ifEmpty { "(cashier)" }} every=${it.intervalSecs}s")
                }
            } else {
                // No `ads` block → adtech for everyone. Never silently ad-free:
                // that would be invisible revenue loss.
                Log.i(TAG, "Ads policy: none in config → default (adtech for all regions)")
                AdsConfig.DEFAULT
            }

            Log.i(TAG, "Config loaded: version=$configVersion, " +
                "${serverLocations.size} locations")
            Log.i(TAG, "Protect Mode ${FirstParty.summary()}")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse config JSON: ${e.message}")
        }
    }
}
