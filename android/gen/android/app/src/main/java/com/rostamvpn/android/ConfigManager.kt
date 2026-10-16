// Copyright 2026 Internet Mastering & Company, Inc.
// SPDX-License-Identifier: Apache-2.0

package com.rostamvpn.android

import android.util.Log
import org.json.JSONObject

/** Kotlin-side config manager. */
object ConfigManager {
    private const val TAG = "ConfigManager"

    var configVersion: String = ""
        private set

    var serverLocations: List<ServerLocation> = emptyList()
        private set

    /**
     * Geographic server regions from the top-level `regions` block (e.g.
     * ["fra","lax"]). The user picks one (or Auto); redsocket decides the protocol
     * within it.
     */
    var serverRegions: List<String> = emptyList()
        private set

    /**
     * Display labels for regions, OTA-driven (e.g. {"fra":"🇩🇪 Frankfurt"}). No
     * region names are hardcoded in the app — adding a region is OTA-only.
     */
    var regionLabels: Map<String, String> = emptyMap()
        private set

    var disabledTransports: Set<String> = emptySet()
        private set

    /** OTA probe config (null = no probe block → probe stays off). */
    var probeConfig: ProbeConfig? = null
        private set

    /** Smart-probe policy from OTA. */
    data class ProbeConfig(
        val enabled: Boolean,
        val gatewayHost: String,
        val gatewayPort: Int,
        val minTunBytes: Long,
        val regions: Set<String>,
        val keyboardLangs: Set<String>,
        val deviceTypes: Set<String>,
        val excludeEmulator: Boolean,
        val samplePct: Int
    )

    /**
     * Protocol priority: which top-level transport is tried first. Listed order is
     * try order; omitting one skips it entirely. `disabled_transports` in the
     * matched region policy still overrides this per region.
     */

    var transportOrder: List<String> = listOf("redsocket")
        private set

    /** Per-region transport order, overriding [transportOrder]. */
    var transportOrderRegions: Map<String, List<String>> = emptyMap()
        private set

    /** The transport ladder for [region], falling back to the global order. */
    fun transportOrderFor(region: String?): List<String> =
        transportOrderRegions[region?.uppercase().orEmpty()] ?: transportOrder

    /**
     * Regions that get the product free because it is donor-funded there — not a
     * paid tier and never a trial (you can't withdraw it).
     */
    /** Does THIS BRAND grant the free tier in freedom regions? */
    var freedomTier: Boolean = !BuildConfig.PAID_ONLY

    /**
     * `paid_only`: the brand has NO free allowance at all, from the first connect.
     * Absent = false. Distinct from `throttle.hard_stop` (stop AFTER an allowance)
     * and deliberately not inferred from `free_kbps: 0`.
     */
    var paidOnly: Boolean = BuildConfig.PAID_ONLY

    /**
     * OTA `protection.enabled`: the fleet switch for exit-side protection. Absent
     * = false. Drives the Connect status line ("Protected") and, once the client
     * core announces `blk1`, whether the cap is sent.
     */
    var protectionEnabled: Boolean = false

    /**
     * OTA `messaging.enabled`: the client announces msg1 (control stream). Read by
     * the core from the same document; kept here for the UI.
     */
    var messagingEnabled: Boolean = false

    var freedomRegions: Map<String, List<String>> = mapOf("IR" to listOf("Asia/Tehran", "Iran"))

    /** Keyboard languages that prove membership of a freedom region, per region. */
    var freedomKeyboards: Map<String, List<String>> = mapOf("IR" to listOf("fa", "ckb"))

    /** Should a keyboard-only claim be refused when the HARDWARE disagrees? */
    var strictKeyboard: Boolean = true
        private set

    /** Free-tier speed limit (see `throttle` in the config). */
    data class ThrottleConfig(val enabled: Boolean, val freeKbps: Int, val hardStop: Boolean = false) {
        companion object { val DEFAULT = ThrottleConfig(false, 0) }
    }

    var throttleConfig: ThrottleConfig = ThrottleConfig.DEFAULT

    /**
     * When the store's rating card may be asked for. Server-owned, like every
     * other policy in this file (§7b) — these were compile-time constants in
     * ReviewManager, which meant tuning them cost a release on every brand.
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
     * language tag.
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
            /** Matches the shipped config; used when the block is absent. */
            val DEFAULT = HealthConfig(true, 45, 2, 3, 300, "https://ip-geo.example.com/")
        }
    }

    var healthConfig: HealthConfig = HealthConfig.DEFAULT
        private set

    /** Raw config JSON for reference. */
    var rawJson: String = "{}"

    /** How to reach the backend. */
    data class ApiConfig(
        val base: String,
        val key: String,
        val fallbacks: List<String>,
        /** LAST-RESORT config paths that are not our hosts at all. */
        val mirrors: List<String> = emptyList(),
    ) {
        /** Every base to try, in order, without duplicates or blanks. */
        val bases: List<String>
            get() = (listOf(base) + fallbacks).map { it.trimEnd('/') }.filter { it.isNotEmpty() }.distinct()

        companion object {
            /**
             * Falls back to the values compiled in, so a config that predates this block
             * still works rather than leaving the app unable to reach anything.
             */
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

    /** Parse the config JSON (from assets or OTA) and populate all fields. */
    fun loadFromJson(json: String) {
        rawJson = json
        try {
            val obj = JSONObject(json)

            configVersion = obj.optString("version", "")

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

            val probeObj = obj.optJSONObject("probe")
            probeConfig = if (probeObj != null) {
                fun strSet(k: String): Set<String> {
                    val a = probeObj.optJSONArray(k) ?: return emptySet()
                    return (0 until a.length()).map { a.getString(it) }.toSet()
                }
                ProbeConfig(
                    enabled = probeObj.optBoolean("enabled", false),
                    gatewayHost = probeObj.optString("gateway_host", ""),
                    gatewayPort = probeObj.optInt("gateway_port", 9080),
                    minTunBytes = probeObj.optLong("min_tun_bytes", 512000L),
                    regions = strSet("regions"),
                    keyboardLangs = strSet("keyboard_langs"),
                    deviceTypes = strSet("device_types"),
                    excludeEmulator = probeObj.optBoolean("exclude_emulator", true),
                    samplePct = probeObj.optInt("sample_pct", 100)
                ).also { Log.i(TAG, "Probe policy: enabled=${it.enabled} host=${it.gatewayHost} minBytes=${it.minTunBytes} regions=${it.regions} samplePct=${it.samplePct}") }
            } else {
                Log.i(TAG, "Probe policy: none in config (probe off)")
                null
            }

            obj.optJSONObject("freedom_regions")?.let { fr ->
                val m = mutableMapOf<String, List<String>>()
                fr.keys().forEach { k ->
                    val tzs = fr.optJSONArray(k)
                    m[k.uppercase()] = if (tzs == null) emptyList()
                        else (0 until tzs.length()).map { tzs.getString(it) }
                }

                if (m.isNotEmpty()) {
                    freedomRegions = m
                    Log.i(TAG, "Freedom regions: ${m.keys} (tz signals: ${m.values.sumOf { it.size }})")
                }
            }

            paidOnly = BuildConfig.PAID_ONLY
            if (obj.has("paid_only") && obj.optBoolean("paid_only") != paidOnly)
                Log.w(TAG, "remote paid_only=${obj.optBoolean("paid_only")} ignored; build says $paidOnly")

            freedomTier = !paidOnly && obj.optBoolean("freedom_tier", true)
            protectionEnabled = obj.optJSONObject("protection")?.optBoolean("enabled", false) ?: false
            messagingEnabled = obj.optJSONObject("messaging")?.optBoolean("enabled", false) ?: false
            if (paidOnly) Log.i(TAG, "paid_only: no free allowance for this brand")
            if (!freedomTier) Log.i(TAG, "Freedom tier DISABLED for this brand — commercial only")

            obj.optJSONObject("freedom_keyboards")?.let { fk ->
                val m = mutableMapOf<String, List<String>>()
                fk.keys().forEach { k ->
                    val langs = fk.optJSONArray(k)
                    m[k.uppercase()] = if (langs == null) emptyList()
                        else (0 until langs.length()).map { langs.getString(it).lowercase() }
                }

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

                    freeKbps = t.optInt("free_kbps", 0).let { k -> if (k in 1..255) 256 else k },
                    hardStop = t.optBoolean("hard_stop", false),
                )
                Log.i(TAG, "Throttle: enabled=${throttleConfig.enabled} freeKbps=${throttleConfig.freeKbps}")

                val rv = obj.optJSONObject("review")
                val rd = ReviewConfig.DEFAULT
                reviewConfig = if (rv == null) rd else ReviewConfig(
                    enabled = rv.optBoolean("enabled", rd.enabled),

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

                val mirrors = a.optJSONArray("mirrors")
                    ?.let { arr -> (0 until arr.length()).map { arr.getString(it).trim() } }
                    ?.filter { it.contains("{url}") } ?: emptyList()
                apiConfig = ApiConfig(base, key, fb, mirrors)
                Log.i(TAG, "API: ${apiConfig.bases.joinToString(", ")} " +
                    "(key ${key.length} chars, ${mirrors.size} mirror(s))")
            }

            Log.i(TAG, "Config loaded: version=$configVersion, " +
                "${serverLocations.size} locations")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse config JSON: ${e.message}")
        }
    }
}
