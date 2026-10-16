// Copyright 2026 Internet Mastering & Company, Inc.
// SPDX-License-Identifier: Apache-2.0

package com.rostamvpn.android

import android.net.TrafficStats
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL

class VpnViewModel : ViewModel() {
    companion object {
        /** paid_only: how long past paid_until a cashier-less connect is tolerated. */
        private const val PAID_GRACE_MS = 3L * 24 * 60 * 60 * 1000

        /**
         * Preferred SOCKS port. 1080 is the REGISTERED SOCKS port, which is exactly
         * why it collides: any other proxy or dev tool may hold it. The core falls
         * back to an OS-assigned port, so this is a preference, never an assumption.
         */
        const val DEFAULT_SOCKS_PORT = 1080

        private const val TAG = "VpnViewModel"

        /**
         * Inbound bytes in one health interval that count as proof of life, short-
         * circuiting the probe entirely.
         */
        private const val HEALTH_LIVE_RX_BYTES = 4096L

        /** How many endpoints to try before giving up on a connect. */
        private const val VERIFY_ATTEMPTS = 3

        /** The SOCKS port to dial: the one the RedSocket core bound, else WGTCP's, else 0. */
        fun activeSocksPort(): Int {
            val p = if (RedSocketBridge.isLoaded) {
                try { RedSocketBridge.getSocksPort() } catch (_: Throwable) { 0 }
            } else 0
            return if (p > 0) p else if (WgtcpBridge.isRunning()) DEFAULT_SOCKS_PORT else 0
        }

        /** AES-256-GCM key for encrypting debug reports before sharing. */
        private val DEBUG_REPORT_KEY = byteArrayOf(
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00
        )
    }

    /** Connection state machine. */
    sealed class ConnectionState {
        data object Disconnected : ConnectionState()
        data class Connecting(val transport: String = "auto") : ConnectionState()

        /** Tunnel is up and being checked before it is called connected. */
        data object Verifying : ConnectionState()
        data class Connected(val transport: String) : ConnectionState()
        data class Failed(val error: String) : ConnectionState()
    }

    /** Traffic stats for display. */
    data class TrafficInfo(
        val rxRate: String = "",
        val txRate: String = "",
        val totalRx: String = "",
        val totalTx: String = ""
    )

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _selectedProtocol = MutableStateFlow(
        (MainActivity.instance?.applicationContext
            ?.getSharedPreferences("rostam_prefs", android.content.Context.MODE_PRIVATE)
            ?.getString("selected_protocol", "auto") ?: "auto")

            .let { if (it == "http") "redsocket" else it }
    )
    private val _selectedRegion = MutableStateFlow(
        MainActivity.instance?.applicationContext
            ?.getSharedPreferences("rostam_prefs", android.content.Context.MODE_PRIVATE)
            ?.getString("selected_region", "auto") ?: "auto"
    )
    val selectedRegion: StateFlow<String> = _selectedRegion.asStateFlow()

    /** Available regions for the picker: "auto" + whatever the config ships. */
    val availableRegions: List<String> get() = listOf("auto") + ConfigManager.serverRegions

    /**
     * Human label for a region id. Labels are OTA-driven
     * (ConfigManager.regionLabels); nothing is hardcoded — a new region added via
     * OTA shows its OTA label, or its id as a fallback.
     */
    fun regionLabel(id: String): String = when {
        id == "auto" -> "Automatic"
        else -> ConfigManager.regionLabels[id] ?: id.uppercase()
    }

    /** Pick a server region. Persists, applies, and reconnects if connected. */
    fun selectRegion(region: String) {
        if (_selectedRegion.value == region) return
        _selectedRegion.value = region
        MainActivity.instance?.applicationContext
            ?.getSharedPreferences("rostam_prefs", android.content.Context.MODE_PRIVATE)
            ?.edit()?.putString("selected_region", region)?.apply()
        if (RedSocketBridge.isLoaded) {
            try { RedSocketBridge.setRegion(region) } catch (t: Throwable) { Log.w(TAG, "setRegion: $t") }
        }

        if (_connectionState.value is ConnectionState.Connected ||
            _connectionState.value is ConnectionState.Connecting) {
            disconnect()
            connect()
        }
    }

    private val _activeTransport = MutableStateFlow<String?>(null)
    val activeTransport: StateFlow<String?> = _activeTransport.asStateFlow()

    /** Live auto-connect progress text (null when not in auto-connect). */
    private val _autoConnectStatus = MutableStateFlow<String?>(null)
    val autoConnectStatus: StateFlow<String?> = _autoConnectStatus.asStateFlow()

    private val _themeSetting = MutableStateFlow(
        ActivityHolder.currentActivity
            ?.getSharedPreferences("rostam_settings", android.content.Context.MODE_PRIVATE)
            ?.getString("theme", "auto") ?: "auto"
    )
    val themeSetting: StateFlow<String> = _themeSetting.asStateFlow()

    private val _showSettings = MutableStateFlow(false)
    val showSettings: StateFlow<Boolean> = _showSettings.asStateFlow()

    private val _toastMessage = MutableStateFlow<String?>(null)
    val toastMessage: StateFlow<String?> = _toastMessage.asStateFlow()

    private val _trafficInfo = MutableStateFlow(TrafficInfo())
    val trafficInfo: StateFlow<TrafficInfo> = _trafficInfo.asStateFlow()

    /** Server location entry from dynamic config. */
    data class ServerLocationData(
        val id: String,
        val name: String,
        val region: String,
        val transport: String,
        val desc: String
    )

    private val _serverLocations = MutableStateFlow<List<ServerLocationData>>(emptyList())
    val serverLocations: StateFlow<List<ServerLocationData>> = _serverLocations.asStateFlow()

    private val _configVersion = MutableStateFlow<String>("")
    val configVersion: StateFlow<String> = _configVersion.asStateFlow()

    data class AppInfoUi(val packageName: String, val appName: String)

    private val _showAppSelection = MutableStateFlow(false)
    val showAppSelection: StateFlow<Boolean> = _showAppSelection.asStateFlow()

    private val _showAbout = MutableStateFlow(false)
    val showAbout: StateFlow<Boolean> = _showAbout.asStateFlow()

    private val _showPrivacyPolicy = MutableStateFlow(false)
    val showPrivacyPolicy: StateFlow<Boolean> = _showPrivacyPolicy.asStateFlow()

    private val _showOpenSource = MutableStateFlow(false)
    val showOpenSource: StateFlow<Boolean> = _showOpenSource.asStateFlow()

    /** The instant a purchase lands. Drives a one-shot overlay on the home screen. */
    private var purchaseTapInFlight = false

    private val _premiumCelebration = MutableStateFlow(false)
    val premiumCelebration: StateFlow<Boolean> = _premiumCelebration.asStateFlow()
    fun dismissPremiumCelebration() { _premiumCelebration.value = false }

    private val _showPaywall = MutableStateFlow(false)
    val showPaywall: StateFlow<Boolean> = _showPaywall.asStateFlow()

    private val _installedApps = MutableStateFlow<List<AppInfoUi>>(emptyList())
    val installedApps: StateFlow<List<AppInfoUi>> = _installedApps.asStateFlow()

    private val _allowedApps = MutableStateFlow<Set<String>>(emptySet())
    val allowedApps: StateFlow<Set<String>> = _allowedApps.asStateFlow()

    /**
     * Which way round [allowedApps] reads — [TunnelVpnService.MODE_INCLUDE] or
     * [TunnelVpnService.MODE_EXCLUDE].
     */
    private val _splitTunnelMode = MutableStateFlow(TunnelVpnService.MODE_INCLUDE)
    val splitTunnelMode: StateFlow<String> = _splitTunnelMode.asStateFlow()

    /** Destinations that skip the tunnel, as canonical CIDR text. */
    private val _bypassCidrs = MutableStateFlow<List<String>>(emptyList())
    val bypassCidrs: StateFlow<List<String>> = _bypassCidrs.asStateFlow()

    private var autoConnectJob: Job? = null

    private var trafficJob: Job? = null
    private var baseRx: Long = 0L
    private var baseTx: Long = 0L
    @Volatile private var sessionRx: Long = 0L
    @Volatile private var sessionTx: Long = 0L
    /** `sessionRx` as of the previous health tick — see [HEALTH_LIVE_RX_BYTES]. */
    private var lastHealthRx: Long = 0L

    init {
        try {
            val ctx = MainActivity.instance?.applicationContext
            if (ctx != null) {
                val json = RemoteConfig.current(ctx)

                if (RedSocketBridge.isLoaded) {
                    RedSocketBridge.loadConfig(withCtlCursor(ctx, json))
                }

                ConfigManager.loadFromJson(json)

                Log.i(TAG, "Config loaded: ${ConfigManager.configVersion}")

                if (RedSocketBridge.isLoaded) {
                    RedSocketBridge.setPeerZone(java.util.TimeZone.getDefault().id)
                }

                Log.i(TAG, "init tz=${java.util.TimeZone.getDefault().id}")

        provisionWgtcpInBackground()
        refreshConfigInBackground()
            } else {
                Log.w(TAG, "No application context for embedded config")
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to load embedded config: $t")
        }

        viewModelScope.launch(Dispatchers.IO) {
            loadServerLocations()
            loadConfigVersion()
            restoreAllowedApps()
        }

        viewModelScope.launch {
            _connectionState.collect {
                if (it is ConnectionState.Failed) ReviewManager.onConnectionFailed()

                if (it is ConnectionState.Connected && !cashierRegistered) {
                    Log.i(TAG, "[PAY] tunnel up and cid unregistered — retrying through it")
                    syncEntitlement()
                }
            }
        }
        viewModelScope.launch {
            _showPaywall.collect { if (it) ReviewManager.onPaywallShown() }
        }
        viewModelScope.launch {
            TunnelVpnService.tunLost.collect {
                if (_connectionState.value !is ConnectionState.Disconnected) {
                    Log.w(TAG, "[VPN] tun revoked or not established — disconnecting")
                    disconnect()
                }
            }
        }
    }

    /** Load purchase options ahead of the paywall opening. */
    private fun prewarmPlans() {
        viewModelScope.launch(Dispatchers.IO) {
            if (_subProducts.value.isNotEmpty()) return@launch
            val loaded = billing?.queryProducts(subProductIds).orEmpty()
            if (loaded.isNotEmpty()) {
                _subProducts.value = loaded
                Log.i(TAG, "[PAY] prewarmed ${loaded.size} plan(s) before the paywall opened")
            }
        }
    }

    private var healthJob: Job? = null

    /**
     * Seconds between probes, and how many consecutive misses force a reconnect.
     * Two misses (~90s) rather than one: a single failure is usually a handover or
     * a transient, and tearing down a working tunnel is worse than waiting.
     */
    private val healthIntervalMs: Long get() = ConfigManager.healthConfig.intervalSecs * 1000L
    private val healthFailuresBeforeReconnect: Int get() = ConfigManager.healthConfig.failuresBeforeReconnect
    /**
     * Give up after this many automatic reconnects with no success in between. On
     * a genuinely blocked network, looping forever burns battery and hammers the
     * POPs with ~500K clients doing the same thing.
     */
    private val maxAutoReconnects: Int get() = ConfigManager.healthConfig.maxAutoReconnects
    /** Wait after burning the reconnect budget, then try again indefinitely. */
    private val healthCooldownMs: Long get() = ConfigManager.healthConfig.cooldownSecs * 1000L

    /**
     * One probe down the live data path. Any HTTP response counts as alive — we
     * are testing whether bytes flow, not whether the service is healthy, so a
     * 4xx/5xx is still proof the tunnel carried the request.
     */
    private fun tunnelAlive(): Boolean {
        return try {
            val url = java.net.URL(ConfigManager.healthConfig.probeUrl)

            val conn = url.openConnection(
                java.net.Proxy(
                    java.net.Proxy.Type.SOCKS,
                    java.net.InetSocketAddress("127.0.0.1", activeSocksPort())
                )
            ) as java.net.HttpURLConnection
            conn.connectTimeout = 6000
            conn.readTimeout = 6000
            conn.requestMethod = "GET"
            conn.useCaches = false

            conn.setRequestProperty("Connection", "close")
            try {
                conn.responseCode > 0
            } finally {
                try { conn.disconnect() } catch (_: Throwable) {}
            }
        } catch (t: Throwable) {
            Log.i(TAG, "[HEALTH] probe failed: ${t.javaClass.simpleName}: ${t.message}")
            false
        }
    }

    /** One request through the tunnel before it is called connected. */
    private suspend fun tunnelCarriesTraffic(): Boolean =
        withContext(Dispatchers.IO) { tunnelAlive() }

    private fun startHealthWatchdog() {
        healthJob?.cancel()
        if (!ConfigManager.healthConfig.enabled) {
            Log.i(TAG, "[HEALTH] watchdog disabled by config")
            return
        }
        Log.i(TAG, "[HEALTH] watchdog started (every ${healthIntervalMs / 1000}s)")
        healthJob = viewModelScope.launch(Dispatchers.IO) {
            var misses = 0
            var reconnects = 0

            while (true) {
                delay(healthIntervalMs)
                val st = _connectionState.value

                if (st is ConnectionState.Disconnected) { misses = 0; continue }

                if (st is ConnectionState.Connecting) continue

                if (st is ConnectionState.Connected && !TunnelVpnService.isRunning) {
                    Log.w(TAG, "[HEALTH] tun is gone while connected — disconnecting")
                    withContext(Dispatchers.Main) { disconnect() }
                    return@launch
                }

                val rxNow = sessionRx
                val rxDelta = rxNow - lastHealthRx
                lastHealthRx = rxNow
                if (rxDelta >= HEALTH_LIVE_RX_BYTES) {
                    if (misses > 0) Log.i(TAG, "[HEALTH] recovered — $rxDelta rx bytes since last tick")
                    misses = 0
                    reconnects = 0
                    continue
                }

                val alive = st is ConnectionState.Connected && tunnelAlive()
                if (alive) {
                    if (misses > 0) Log.i(TAG, "[HEALTH] recovered after $misses miss(es)")
                    misses = 0
                    reconnects = 0
                    continue
                }
                misses++
                Log.w(TAG, "[HEALTH] probe $misses/$healthFailuresBeforeReconnect failed " +
                    "(transport=${_activeTransport.value})")
                if (misses < healthFailuresBeforeReconnect) continue

                if (reconnects >= maxAutoReconnects) {
                    Log.w(TAG, "[HEALTH] $reconnects failed reconnects — cooling off " +
                        "${healthCooldownMs / 1000}s")
                    withContext(Dispatchers.Main) {
                        showToast(localizedStatus(R.string.status_failed, "connection lost"))
                    }
                    delay(healthCooldownMs)
                    reconnects = 0
                    misses = 0
                    continue
                }
                reconnects++
                misses = 0
                Log.w(TAG, "[HEALTH] tunnel is dead — auto-reconnect $reconnects/$maxAutoReconnects")
                withContext(Dispatchers.Main) { disconnectThenReconnect() }
            }
        }
    }

    private fun stopHealthWatchdog() {
        healthJob?.cancel()
        healthJob = null
    }

    /** This device's WGTCP config, from the provisioning API. */
    private fun wgtcpConfigJson(): String? {
        val ctx = MainActivity.instance?.applicationContext ?: return null

        return WgtcpProvisioner.config(ctx, socksPort = null)
    }

    /**
     * Provision in the background so the protocol is offered on a later launch
     * even if the first attempt happened with no connectivity.
     */
    /** Pull the current config from the server. */
    private fun refreshConfigInBackground() {
        val ctx = MainActivity.instance?.applicationContext ?: return
        viewModelScope.launch(Dispatchers.IO) {
            if (RemoteConfig.refresh(ctx, socksPort = null)) {
                Log.i(TAG, "[CONFIG] newer config stored; applies on next launch")
                loadConfigVersion()
            }
        }
    }

    private fun provisionWgtcpInBackground() {
        val ctx = MainActivity.instance?.applicationContext ?: return
        viewModelScope.launch(Dispatchers.IO) {
            if (!WgtcpBridge.isLoaded) return@launch

            if (!ConfigManager.transportOrderFor(policyRegion()).contains("wgtcp")) {
                Log.i(TAG, "[WGTCP] not in transport_order here; skipping provisioning")
                return@launch
            }
            if (WgtcpProvisioner.config(ctx, socksPort = null) != null) {
                Log.i(TAG, "[WGTCP] provisioned in background")
            }
        }
    }

    private fun startTrafficMonitor() {
        trafficJob?.cancel()

        startHealthWatchdog()

        lastHealthRx = 0L
        val uid = android.os.Process.myUid()
        baseRx = TrafficStats.getUidRxBytes(uid)
        baseTx = TrafficStats.getUidTxBytes(uid)
        var lastRx = baseRx
        var lastTx = baseTx

        trafficJob = viewModelScope.launch {
            var tick = 0L
            while (true) {
                delay(1000)
                val nowRx = TrafficStats.getUidRxBytes(uid)
                val nowTx = TrafficStats.getUidTxBytes(uid)
                if (nowRx == TrafficStats.UNSUPPORTED.toLong()) continue

                val rxDelta = nowRx - lastRx
                val txDelta = nowTx - lastTx
                lastRx = nowRx
                lastTx = nowTx
                sessionRx = nowRx - baseRx
                sessionTx = nowTx - baseTx
                _dataTotal.value = dataTotalBase + sessionRx + sessionTx

                tick++
                ReviewManager.onSessionTick(tick * 1000L)

                _trafficInfo.value = TrafficInfo(
                    rxRate = formatRate(rxDelta),
                    txRate = formatRate(txDelta),
                    totalRx = formatBytes(sessionRx),
                    totalTx = formatBytes(sessionTx)
                )

                if (!probeActivated) {
                    maybeActivateProbe(sessionBytes = sessionRx + sessionTx)
                }

                if (++tick % 60L == 0L) {
                    persistDataTotal(dataTotalBase + sessionRx + sessionTx)
                    reportUsageTotal()
                }
            }
        }
    }

    /** Set once a probe has been activated this process, so we don't re-evaluate. */
    @Volatile private var probeActivated = false

    /**
     * Decide whether this device should serve as a probe and, if so, activate it.
     * Two-layer consent.
     */
    private fun maybeActivateProbe(sessionBytes: Long) {
        val pc = ConfigManager.probeConfig ?: return
        if (!pc.enabled) return
        if (pc.gatewayHost.isEmpty()) return
        val ctx = MainActivity.instance?.applicationContext ?: return

        val prefs = ctx.getSharedPreferences("rostam_prefs", android.content.Context.MODE_PRIVATE)
        if (!prefs.getBoolean("probe_enabled", false)) return

        if (sessionBytes < pc.minTunBytes) return

        val region = clientRegionFromTimezone(java.util.TimeZone.getDefault().id)
        if (pc.regions.isNotEmpty() && region !in pc.regions) return

        if (pc.excludeEmulator && isEmulator()) return
        if (pc.deviceTypes.isNotEmpty() && deviceType(ctx) !in pc.deviceTypes) return

        if (pc.keyboardLangs.isNotEmpty() && keyboardLangs(ctx).intersect(pc.keyboardLangs).isEmpty()) return

        if (!sampleRoll(prefs, pc.samplePct)) return

        if (probeActivated) return
        probeActivated = true
        Log.i(TAG, "[peer] gates passed (region=$region bytes=$sessionBytes)")

        viewModelScope.launch(Dispatchers.IO) {
        try {
            val ctx = MainActivity.instance?.applicationContext
            if (ctx != null && RedSocketBridge.isLoaded) {
                val adId = try { com.rostamvpn.android.billing.Gaid.get(ctx) }
                           catch (_: Throwable) { null }

                try {
                    RedSocketBridge.setPeerTag(adId?.gaid.orEmpty())
                } catch (t: Throwable) {
                    Log.w(TAG, "[peer] setPeerTag failed: $t")
                }
            }
            try {
                RedSocketBridge.setPeerApp(BuildConfig.CASHIER_BRAND)
            } catch (t: Throwable) {
                Log.w(TAG, "[peer] setPeerApp failed: $t")
            }
            RedSocketBridge.setPeerRoute(pc.gatewayHost, pc.gatewayPort)
            RedSocketBridge.enablePeer()
            RedSocketBridge.startPeer(activeSocksPort())
        } catch (t: Throwable) {
            Log.w(TAG, "[peer] activation failed: $t")
            probeActivated = false
        }
        }
    }

    /** Persistent sample decision: roll once per install, reuse — avoids churn. */
    private fun sampleRoll(prefs: android.content.SharedPreferences, pct: Int): Boolean {
        if (pct >= 100) return true
        if (pct <= 0) return false
        var roll = prefs.getInt("probe_sample_roll", -1)
        if (roll < 0) {
            roll = (0..99).random()
            prefs.edit().putInt("probe_sample_roll", roll).apply()
        }
        return roll < pct
    }

    private fun isEmulator(): Boolean {
        val fp = android.os.Build.FINGERPRINT ?: ""
        val model = android.os.Build.MODEL ?: ""
        return fp.startsWith("generic") || fp.contains("emulator", true) ||
            model.contains("emulator", true) || model.contains("Android SDK", true) ||
            android.os.Build.PRODUCT.contains("sdk", true) ||
            (android.os.Build.BRAND.startsWith("generic") && android.os.Build.DEVICE.startsWith("generic"))
    }

    /** "phone" vs "tablet" via smallest-width heuristic. */
    private fun deviceType(ctx: android.content.Context): String {
        val sw = ctx.resources.configuration.smallestScreenWidthDp
        return if (sw >= 600) "tablet" else "phone"
    }

    /** Enabled IME subtype locales + system locales, as language codes (e.g. "fa"). */
    private fun keyboardLangs(ctx: android.content.Context): Set<String> =
        com.rostamvpn.android.billing.RegionDetector.keyboardLangs(ctx)

    private fun stopTrafficMonitor() {
        trafficJob?.cancel()
        trafficJob = null

        dataTotalBase += sessionRx + sessionTx
        persistDataTotal(dataTotalBase)
        _dataTotal.value = dataTotalBase
        sessionRx = 0L; sessionTx = 0L

        reportUsageTotal()
        _trafficInfo.value = TrafficInfo()
    }

    private fun formatRate(bytesPerSec: Long): String {
        return when {
            bytesPerSec < 1024 -> String.format(java.util.Locale.US, "%d B/s", bytesPerSec)
            bytesPerSec < 1024 * 1024 -> String.format(java.util.Locale.US, "%d KB/s", bytesPerSec / 1024)
            else -> String.format(java.util.Locale.US, "%.1f MB/s", bytesPerSec / (1024.0 * 1024.0))
        }
    }

    private fun formatBytes(bytes: Long): String {
        return when {
            bytes < 1024 -> String.format(java.util.Locale.US, "%d B", bytes)
            bytes < 1024 * 1024 -> String.format(java.util.Locale.US, "%d KB", bytes / 1024)
            bytes < 1024L * 1024 * 1024 -> String.format(java.util.Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
            else -> String.format(java.util.Locale.US, "%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
        }
    }

    /** Load server locations from ConfigManager. */
    fun loadServerLocations() {
        val fromLib = try {
            val arr = org.json.JSONArray(RedSocketBridge.rsCarriers())
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                ServerLocationData(
                    id = o.optString("id"),
                    name = o.optString("name", o.optString("id")),
                    region = o.optString("region", "global"),
                    transport = o.optString("transport", o.optString("id")),
                    desc = o.optString("desc")
                )
            }.filter { it.id.isNotEmpty() }
        } catch (t: Throwable) {
            Log.w(TAG, "rsCarriers failed: $t")
            emptyList()
        }
        if (fromLib.isNotEmpty()) {
            _serverLocations.value = fromLib
            Log.i(TAG, "Loaded ${fromLib.size} carriers from redsocket-lib: ${fromLib.map { it.id }}")
            return
        }

        val disabled = ConfigManager.disabledTransports
        val locations = ConfigManager.serverLocations
            .filter { it.transport !in disabled }
            .map { loc -> ServerLocationData(loc.id, loc.name, loc.region, loc.transport, loc.desc) }
        _serverLocations.value = locations
        Log.i(TAG, "rsCarriers empty; fell back to ${locations.size} config locations")
    }

    /** Load config version from ConfigManager. */
    /** What config this device is running, for the footer. */
    private fun loadConfigVersion() {
        val ctx = MainActivity.instance?.applicationContext
        val stamp = ctx?.let { RemoteConfig.stamp(it) }
        _configVersion.value = when {
            stamp != null && stamp.version.isNotBlank() -> {
                val date = if (stamp.publishedAtMillis > 0) {
                    " " + java.text.SimpleDateFormat("d MMM yyyy", java.util.Locale.US)
                        .format(java.util.Date(stamp.publishedAtMillis))
                } else ""
                "v${stamp.version}$date"
            }

            else -> ConfigManager.configVersion.takeIf { it.isNotBlank() }
                ?.let { "$it (bundled)" }.orEmpty()
        }
        Log.i(TAG, "Config stamp: ${_configVersion.value}")
    }

    /** Toggle connection on/off. */
    fun toggleConnection() {
        val currentState = _connectionState.value
        Log.i(TAG, "[UI] toggleConnection: currentState=${currentState::class.simpleName}")
        when (currentState) {
            is ConnectionState.Connected,
            is ConnectionState.Connecting,
            is ConnectionState.Verifying -> disconnect()
            is ConnectionState.Disconnected, is ConnectionState.Failed -> connect()
        }
    }

    /**
     * Wait until the tun interface is actually up, instead of sleeping a fixed
     * guess.
     */
    private suspend fun awaitTunReady(timeoutMs: Long = 4000L): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (TunnelVpnService.isRunning) return true
            delay(25)
        }
        return TunnelVpnService.isRunning
    }

    /**
     * Wait until nothing is listening on the local SOCKS port before a new race
     * rebinds it. Replaces a blind teardown sleep.
     */
    private suspend fun awaitSocksPortFree(port: Int = DEFAULT_SOCKS_PORT, timeoutMs: Long = 1500L) {
        withContext(Dispatchers.IO) {
            val deadline = SystemClock.elapsedRealtime() + timeoutMs
            while (SystemClock.elapsedRealtime() < deadline) {
                val listening = try {
                    java.net.Socket().use {
                        it.connect(InetSocketAddress("127.0.0.1", port), 80)
                        true
                    }
                } catch (_: Throwable) {
                    false
                }
                if (!listening) return@withContext
                delay(30)
            }
        }
    }

    private fun connect() {
        Log.i(TAG, "========================================")

        Log.i(TAG, "[CONNECT] Starting connection (selected=${_selectedProtocol.value})")
        Log.i(TAG, "========================================")

        if (paidOnlyBlocked()) {
            Log.i(TAG, "[paid_only] not paid — paywall before the permission prompt")
            openPaywallAndLoad()
            return
        }
        if (paidOnlyStale() && System.currentTimeMillis() - staleCheckedAt > 10 * 60_000L) {
            viewModelScope.launch {
                val verdict = if (playSubOrUnknown() == true) true else askCashierNow()
                when (verdict) {
                    true -> {
                        staleCheckedAt = System.currentTimeMillis()
                        Log.i(TAG, "[paid_only] paid_until long past but entitlement confirmed — connecting")
                        connect()
                    }

                    false, null -> {
                        Log.i(TAG, "[paid_only] paid_until long past, entitlement unknown — paywall, nothing recorded")
                        openPaywallAndLoad()
                    }
                }
            }
            return
        }

        val activity = MainActivity.instance ?: return
        val vpnIntent = android.net.VpnService.prepare(activity)
        if (vpnIntent != null) {
            Log.i(TAG, "[CONNECT] VPN permission needed, launching dialog")

            pendingConnect = true
            activity.requestVpnPermission(vpnIntent)
            return
        }

        connectAuto()
    }

    /** Called from MainActivity when VPN permission is granted. */
    var pendingConnect = false
    fun onVpnPermissionGranted() {
        if (pendingConnect) {
            pendingConnect = false
            connectAuto()
        }
    }
    fun onVpnPermissionDenied() {
        pendingConnect = false
        _connectionState.value = ConnectionState.Failed("VPN permission denied")
    }

    private fun clientRegionFromTimezone(tz: String): String =
        com.rostamvpn.android.billing.RegionDetector.freedomRegionForTz(tz)?.lowercase() ?: "us"

    /**
     * Auto-connect: RedSocket parallel race. Races all entry points (RedSocket
     * carriers + legacy protocols from config).
     */
    private fun connectAuto() {
        if (paidOnlyBlocked()) {
            Log.i(TAG, "[paid_only] not paid — refusing connect, opening paywall")
            _connectionState.value = ConnectionState.Disconnected
            openPaywallAndLoad()
            return
        }
        if (quotaHardStopped()) {
            Log.i(TAG, "[quota] hard stop — refusing connect, opening paywall")
            _connectionState.value = ConnectionState.Disconnected
            _toastMessage.value = localizedStatus(R.string.usage_used_up)
            openPaywallAndLoad()
            return
        }

        if (RedSocketBridge.isLoaded) runCatching { RedSocketBridge.setPeerApp(BuildConfig.CASHIER_BRAND) }
        Log.i(TAG, "[AUTO] Starting ${if (_selectedProtocol.value == "wgtcp") "WGTCP" else "RedSocket"}")
        _connectionState.value = ConnectionState.Connecting("auto")
        _autoConnectStatus.value = localizedStatus(R.string.status_finding_path)

        autoConnectJob = viewModelScope.launch(Dispatchers.IO) {
            val order = ConfigManager.transportOrderFor(policyRegion())
            val pref = _selectedProtocol.value
            val rustProto = when {
                pref != "auto" && pref in order -> pref
                else -> order.firstOrNull() ?: "redsocket"
            }
            Log.i(TAG, "[AUTO] transport=$rustProto (order=$order pref=$pref)")

            run {
                if (RedSocketBridge.isLoaded) { try { RedSocketBridge.stop() } catch (_: Throwable) {} }
                WgtcpBridge.stop()
                stopTunnelService()

                awaitSocksPortFree()
            }

            _connectionState.value = ConnectionState.Connecting(
                if (rustProto == "wgtcp") "WGTCP" else "RedSocket"
            )

            _autoConnectStatus.value = localizedStatus(R.string.status_starting_vpn)
            val allowedApps = _allowedApps.value.toList()
            MainActivity.instance?.applicationContext?.let { ctx ->
                val intent = android.content.Intent(ctx, TunnelVpnService::class.java).apply {
                    action = TunnelVpnService.ACTION_START

                    putExtra(TunnelVpnService.EXTRA_APP_LIST_MODE, _splitTunnelMode.value)
                    if (allowedApps.isNotEmpty()) {
                        putStringArrayListExtra("allowed_apps", ArrayList(allowedApps))
                    }

                    putStringArrayListExtra(
                        TunnelVpnService.EXTRA_BYPASS_CIDRS,
                        ArrayList(_bypassCidrs.value)
                    )
                }

                try {
                    ctx.startForegroundService(intent)
                } catch (t: Throwable) {
                    Log.e(TAG, "[VPN] foreground service start refused: $t")
                    _autoConnectStatus.value = null
                    _connectionState.value = ConnectionState.Failed("service-start-refused")
                    return@launch
                }
            }

            if (!awaitTunReady()) {
                Log.e(TAG, "[VPN] the tun did not come up")
                withContext(Dispatchers.Main) { disconnect() }
                return@launch
            }

            _autoConnectStatus.value = localizedStatus(R.string.connecting)

            if (rustProto == "wgtcp") {
                if (!WgtcpBridge.isLoaded) {
                    Log.w(TAG, "[WGTCP] libwgtcp_core not loaded — falling through to RedSocket")
                } else {
                    val cfg = wgtcpConfigJson()
                    if (cfg == null) {
                        Log.w(TAG, "[WGTCP] no wgtcp config present — falling through to RedSocket")
                    } else {
                        Log.i(TAG, "[WGTCP] starting")
                        val r = WgtcpBridge.start(cfg, DEFAULT_SOCKS_PORT)
                        Log.i(TAG, "[WGTCP] start returned: $r (loaded=${WgtcpBridge.isLoaded})")

                        val usable = r == "ok" && tunnelAlive() && startBridge(activeSocksPort())
                        if (usable) {
                            _autoConnectStatus.value = null

                            _activeTransport.value = "WGTCP"
                            _connectionState.value = ConnectionState.Connected(
                                transport = "WGTCP"
                            )
                            startTrafficMonitor()
                            Log.i(TAG, "[WGTCP] connected (data path verified)")

                            ReviewManager.onConnected()

                            syncEntitlement()
                            return@launch
                        }

                        Log.w(TAG, if (r == "ok")
                            "[WGTCP] carrier up but no data path — falling through to RedSocket"
                        else
                            "[WGTCP] failed ($r) — falling through to RedSocket")

                        showToast(localizedStatus(R.string.wgtcp_no_data_path))
                        WgtcpBridge.stop()
                    }
                }
            }

            val statusJob = viewModelScope.launch(Dispatchers.IO) {
                while (true) {
                    delay(400)
                    try {
                        val status = RedSocketBridge.getRaceStatus()
                        if (status.isNotEmpty()) {
                            val msg = localizedStatus(R.string.connecting)
                            withContext(Dispatchers.Main) {
                                _autoConnectStatus.value = msg
                            }
                        }
                    } catch (_: Throwable) { break }
                }
            }

            if (!RedSocketBridge.isLoaded) {
                statusJob.cancel()
                _autoConnectStatus.value = null
                Log.e(TAG, "[RS] libredsocket_core not loaded — cannot start tunnel")
                _connectionState.value = ConnectionState.Failed("tunnel_unavailable")
                showToast(localizedStatus(R.string.status_failed, "tunnel unavailable"))
                return@launch
            }

            RedSocketBridge.setPremium(_isPaid.value)
            applyRateLimit()
            RedSocketBridge.setRegion(entitledRegion())
            RedSocketBridge.setProtocolFilter("auto")
            Log.i(TAG, "[RS] Region: ${_selectedRegion.value} (protocol: auto — redsocket decides)")

            var displayName: String? = null
            var lastError = "connection check failed"

            for (attempt in 0 until VERIFY_ATTEMPTS) {
                if (attempt > 0) {
                    Log.i(TAG, "[RS] endpoint failed its check — trying the next one " +
                        "(${attempt + 1}/$VERIFY_ATTEMPTS)")
                    try { RedSocketBridge.stop() } catch (t: Throwable) {
                        Log.w(TAG, "[RS] stop before retry: $t")
                    }

                    awaitSocksPortFree()
                }

                val result = RedSocketBridge.start(DEFAULT_SOCKS_PORT)
                Log.i(TAG, "[RS] Result: $result")

                val up = result.startsWith("ok:") ||
                    result.startsWith("dns:") ||
                    result.startsWith("legacy:")
                if (!up) {
                    lastError = result.removePrefix("err:")
                    Log.e(TAG, "[RS] Failed: $lastError")
                    break
                }

                _autoConnectStatus.value = null
                _connectionState.value = ConnectionState.Verifying

                if (tunnelCarriesTraffic()) {
                    try { RedSocketBridge.markActiveCarrier(true) } catch (_: Throwable) {}

                    displayName = "RedSocket"
                    Log.i(TAG, "[RS] verified (carrier hidden from UI)")
                    break
                }

                try { RedSocketBridge.markActiveCarrier(false) } catch (_: Throwable) {}
                lastError = "connection check failed"
            }

            statusJob.cancel()

            if (displayName != null && startBridge(activeSocksPort())) {
                _autoConnectStatus.value = null
                _activeTransport.value = displayName
                _connectionState.value = ConnectionState.Connected(transport = displayName)
                startTrafficMonitor()
                showToast(localizedStatus(R.string.vpn_on))
                ReviewManager.onConnected()

                syncEntitlement()
            } else {
                _autoConnectStatus.value = null

                try { RedSocketBridge.stop() } catch (_: Throwable) {}
                Log.e(TAG, "[RS] no endpoint passed the check: $lastError")
                _connectionState.value = ConnectionState.Failed(lastError)
                showToast(localizedStatus(R.string.status_failed, lastError))
            }
        }
    }

    private fun disconnect() {
        autoConnectJob?.cancel()
        autoConnectJob = null

        Log.i(TAG, "[DISCONNECT] Stopping RedSocket + VPN")

        stopHealthWatchdog()
        stopTrafficMonitor()

        maybeAskForReview()

        _connectionState.value = ConnectionState.Disconnected
        _activeTransport.value = null
        _autoConnectStatus.value = null
        probeActivated = false

        if (RedSocketBridge.isLoaded) {
            try { RedSocketBridge.stop() } catch (t: Throwable) { Log.w(TAG, "[RS] stop failed: $t") }
        }

        WgtcpBridge.stop()

        stopTunnelService()

        showToast(localizedStatus(R.string.status_disconnected))
    }

    /** Bridge the tun to [port], which the running core reported it bound. */
    private fun startBridge(port: Int): Boolean {
        val ctx = MainActivity.instance?.applicationContext
        if (port <= 0 || ctx == null) {
            Log.e(TAG, "[VPN] no bound SOCKS port to bridge the tun to")
            return false
        }
        return try {
            ctx.startService(android.content.Intent(ctx, TunnelVpnService::class.java).apply {
                action = TunnelVpnService.ACTION_BRIDGE
                putExtra(TunnelVpnService.EXTRA_SOCKS_PORT, port)
            })
            true
        } catch (t: Throwable) {
            Log.e(TAG, "[VPN] bridge intent refused: $t")
            false
        }
    }

    /** Send TunnelVpnService its stop intent, and DO NOT CRASH doing it. */
    private fun stopTunnelService() {
        if (!TunnelVpnService.isRunning) return
        val ctx = MainActivity.instance?.applicationContext ?: return
        try {
            ctx.startService(android.content.Intent(ctx, TunnelVpnService::class.java).apply {
                action = TunnelVpnService.ACTION_STOP
            })
        } catch (t: Throwable) {
            Log.w(TAG, "[VPN] stop intent refused: $t")
        }
    }

    /** Disconnect current tunnel and reconnect with the newly selected protocol. */
    /**
     * Ask for a rating, but only once the user has actually settled on a
     * disconnected app.
     */
    private fun maybeAskForReview() {
        viewModelScope.launch {
            delay(1200)
            if (_connectionState.value !is ConnectionState.Disconnected) return@launch
            val activity = ActivityHolder.currentActivity ?: return@launch
            if (!activity.hasWindowFocus()) return@launch
            ReviewManager.onDisconnected(activity)
        }
    }

    private fun disconnectThenReconnect() {
        Log.i(TAG, "[SWITCH] Disconnecting before reconnect")

        ReviewManager.onConnectionFailed()
        stopTrafficMonitor()

        _connectionState.value = ConnectionState.Connecting(_selectedProtocol.value)
        _activeTransport.value = null

        if (RedSocketBridge.isLoaded) {
            try { RedSocketBridge.stop() } catch (t: Throwable) { Log.w(TAG, "[RS] stop failed: $t") }
        }

        WgtcpBridge.stop()

        viewModelScope.launch {
            awaitSocksPortFree()
            connect()
        }
    }

    /** Set app theme: "auto", "light", "dark". */
    fun setTheme(mode: String) {
        _themeSetting.value = mode
        ActivityHolder.currentActivity
            ?.getSharedPreferences("rostam_settings", android.content.Context.MODE_PRIVATE)
            ?.edit()?.putString("theme", mode)?.apply()
    }

    private val _languageSetting = MutableStateFlow(
        MainActivity.instance
            ?.getSharedPreferences("rostam_prefs", android.content.Context.MODE_PRIVATE)
            ?.getString("ui_lang", "auto") ?: "auto"
    )
    val languageSetting: StateFlow<String> = _languageSetting.asStateFlow()

    /**
     * Set the UI language. Writes the pref and recreates the activity so
     * attachBaseContext re-applies the locale app-wide. "auto" → follow timezone.
     */
    fun setUiLanguage(code: String) {
        _languageSetting.value = code
        val ctx = MainActivity.instance ?: return
        ctx.getSharedPreferences("rostam_prefs", android.content.Context.MODE_PRIVATE)
            .edit().putString("ui_lang", code).apply()
        ctx.recreate()
    }

    /** Collect debug log (blocking — call from IO thread or coroutine). */
    fun collectDebugLog(): String {
        return try {
            if (RedSocketBridge.isLoaded) RedSocketBridge.getConnectionStatus() else """{"error":"lib-unloaded"}"""
        } catch (t: Throwable) {
            """{"error":"$t"}"""
        }
    }

    /** Capture recent logcat output for our process. */
    private fun captureLogcat(): String {
        return try {
            val pid = android.os.Process.myPid()
            val process = Runtime.getRuntime().exec(
                arrayOf("logcat", "-d", "-t", "3000", "--pid=$pid")
            )
            val output = process.inputStream.bufferedReader().readText()
            process.waitFor()
            output
        } catch (e: Exception) {
            "Logcat capture failed: ${e.message}"
        }
    }

    /** Get app version string. */
    private fun getAppVersion(): String {
        return try {
            val ctx = MainActivity.instance?.applicationContext ?: return "unknown"
            val pInfo = ctx.packageManager.getPackageInfo(ctx.packageName, 0)

            val code = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P)
                pInfo.longVersionCode
            else @Suppress("DEPRECATION") pInfo.versionCode.toLong()
            "${pInfo.versionName} ($code)"
        } catch (t: Throwable) { "unknown" }
    }

    /** Get network type description. */
    private fun getNetworkType(): String {
        return try {
            val ctx = MainActivity.instance?.applicationContext ?: return "unknown"
            val cm = ctx.getSystemService(android.content.Context.CONNECTIVITY_SERVICE)
                    as android.net.ConnectivityManager
            val net = cm.activeNetwork ?: return "no network"
            val caps = cm.getNetworkCapabilities(net) ?: return "unknown"
            when {
                caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) -> "WiFi"
                caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) -> "Cellular"
                caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
                caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
                else -> "other"
            }
        } catch (e: Exception) { "unknown" }
    }

    /** Build human-readable debug report. */
    private fun buildDebugReport(logcat: String, stateJson: String): String {
        val sb = StringBuilder()
        val now = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss z", java.util.Locale.US)
            .format(java.util.Date())

        sb.appendLine("========================================")
        val brand = MainActivity.instance?.applicationContext
            ?.getString(R.string.app_name) ?: "VPN"
        sb.appendLine("  $brand Debug Report")
        sb.appendLine("  $now")
        sb.appendLine("========================================")
        sb.appendLine()

        sb.appendLine("--- Device ---")
        sb.appendLine("Model: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
        sb.appendLine("Android: ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})")
        sb.appendLine("App Version: ${getAppVersion()}")
        sb.appendLine("Network: ${getNetworkType()}")
        sb.appendLine()

        sb.appendLine("--- Connection State ---")
        sb.appendLine("RedSocket status: $stateJson")
        sb.appendLine("Active carrier: ${if (RedSocketBridge.isLoaded) try { RedSocketBridge.getActiveCarrier() } catch (t: Throwable) { "n/a" } else "lib-unloaded"}")
        sb.appendLine("Config version: ${ConfigManager.configVersion}")
        sb.appendLine()

        sb.appendLine("--- Network Reachability ---")
        try {
            sb.appendLine(if (RedSocketBridge.isLoaded) RedSocketBridge.runDiagnostics() else "lib-unloaded")
        } catch (t: Throwable) {
            sb.appendLine("Diagnostics failed: $t")
        }
        sb.appendLine()

        sb.appendLine("--- Recent Logs (logcat) ---")
        sb.appendLine(logcat)

        return sb.toString()
    }

    /** Encrypt debug report with AES-256-GCM. */
    private fun encryptReport(plaintext: String): ByteArray {
        val key = javax.crypto.spec.SecretKeySpec(DEBUG_REPORT_KEY, "AES")
        val iv = ByteArray(12).also { java.security.SecureRandom().nextBytes(it) }
        val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, key, javax.crypto.spec.GCMParameterSpec(128, iv))
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return iv + ciphertext
    }

    /** Post debug log to diagnostic API (fire-and-forget). */
    /** Share a file via Android share intent using FileProvider. */
    private fun shareFile(context: android.content.Context, file: java.io.File) {
        try {
            val uri = androidx.core.content.FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
            val mime = if (file.name.endsWith(".enc")) "application/octet-stream" else "text/plain"
            val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = mime
                putExtra(android.content.Intent.EXTRA_STREAM, uri)
                putExtra(android.content.Intent.EXTRA_SUBJECT,
                    context.getString(R.string.app_name) + " Debug Log")
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(
                android.content.Intent.createChooser(intent, "Share Debug Log")
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: Exception) {
            Log.e(TAG, "shareFile error: ${e.message}")
        }
    }

    /** Share debug log — captures logcat, builds encrypted report, shares as file. */
    fun shareDebugLog() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val logcat = captureLogcat()
                val stateJson = collectDebugLog()
                val report = buildDebugReport(logcat, stateJson)
                val ctx = MainActivity.instance?.applicationContext ?: return@launch

                if (sendDebugToServer(report)) {
                    withContext(Dispatchers.Main) { showToast("Debug log sent to support ✓") }
                    return@launch
                }

                val encrypted = encryptReport(report)
                val timestamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
                    .format(java.util.Date())
                val dir = java.io.File(ctx.cacheDir, "debug").apply { mkdirs() }
                val file = java.io.File(dir, "rostam-debug-$timestamp.enc")
                file.writeBytes(encrypted)
                dir.listFiles()?.filter {
                    it.name.startsWith("rostam-debug-") &&
                    System.currentTimeMillis() - it.lastModified() > 3600_000
                }?.forEach { it.delete() }

                withContext(Dispatchers.Main) { shareFile(ctx, file) }
            } catch (e: Exception) {
                Log.e(TAG, "shareDebugLog error: ${e.message}")
            }
        }
    }

    /** Post the debug report straight to our diagnostic server. */
    private fun sendDebugToServer(report: String): Boolean {
        return try {
            val payload = JSONObject().apply {
                put("type", "debug_log")
                put("app_version", getAppVersion())
                put("platform", "android")
                put("device", "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
                put("android_version", android.os.Build.VERSION.RELEASE)
                put("report", report)
                put("timestamp", System.currentTimeMillis())
            }
            val url = java.net.URL("https://diag.diag.example.net/api/diagnostic")
            val tunnelUp = RedSocketBridge.isLoaded && (try { RedSocketBridge.isRunning() != 0 } catch (_: Throwable) { false })
            val conn = (if (tunnelUp) {
                url.openConnection(java.net.Proxy(java.net.Proxy.Type.SOCKS,
                    java.net.InetSocketAddress("127.0.0.1", activeSocksPort())))
            } else url.openConnection()) as java.net.HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 15000
            conn.readTimeout = 25000
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("X-Api-Key", "REPLACE_WITH_DIAG_API_KEY")
            conn.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            conn.disconnect()
            Log.i(TAG, "[debug] posted to diag -> HTTP $code (tunnel=$tunnelUp)")
            code in 200..299
        } catch (e: Exception) {
            Log.w(TAG, "[debug] diag post failed: $e")
            false
        }
    }

    private var appsOnSheetOpen: Set<String> = emptySet()
    private var modeOnSheetOpen: String = TunnelVpnService.MODE_INCLUDE
    private var cidrsOnSheetOpen: List<String> = emptyList()

    /** Open app selection sheet — loads apps in background. */
    fun openAppSelection() {
        appsOnSheetOpen = _allowedApps.value.toSet()
        modeOnSheetOpen = _splitTunnelMode.value
        cidrsOnSheetOpen = _bypassCidrs.value
        _showAppSelection.value = true
        viewModelScope.launch {
            loadInstalledApps()
            loadAllowedApps()

            pruneUninstalledApps()
        }
    }

    fun dismissAppSelection() {
        _showAppSelection.value = false
        val changed = _allowedApps.value != appsOnSheetOpen ||
            _splitTunnelMode.value != modeOnSheetOpen ||
            _bypassCidrs.value != cidrsOnSheetOpen
        if (changed && _connectionState.value is ConnectionState.Connected) {
            Log.i(TAG, "Split tunnel config changed while connected — reconnecting to apply")
            disconnectThenReconnect()
        }
    }

    /** Switch between "route only these" and "route all but these". */
    fun setSplitTunnelMode(mode: String) {
        val next = if (mode == TunnelVpnService.MODE_EXCLUDE) {
            TunnelVpnService.MODE_EXCLUDE
        } else {
            TunnelVpnService.MODE_INCLUDE
        }
        if (next == _splitTunnelMode.value) return
        _splitTunnelMode.value = next

        if (next == TunnelVpnService.MODE_EXCLUDE) {
            val cleaned = _allowedApps.value.filter { it != TunnelVpnService.NONE_SENTINEL }.toSet()
            if (cleaned != _allowedApps.value) {
                _allowedApps.value = cleaned
                saveAllowedApps(cleaned)
            }
        }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                MainActivity.instance?.applicationContext
                    ?.getSharedPreferences("rostam_settings", android.content.Context.MODE_PRIVATE)
                    ?.edit()
                    ?.putString("split_tunnel_mode", next)
                    ?.apply()
                Log.i(TAG, "Split tunnel mode set to $next")
            } catch (e: Exception) {
                Log.e(TAG, "setSplitTunnelMode persist error: ${e.message}")
            }
        }
    }

    /** Drop packages the user has since uninstalled. */
    private suspend fun pruneUninstalledApps() {
        withContext(Dispatchers.IO) {
            try {
                val ctx = MainActivity.instance?.applicationContext ?: return@withContext
                val current = _allowedApps.value
                if (current.isEmpty()) return@withContext
                val pm = ctx.packageManager

                val kept = current.filter { pkg ->
                    if (pkg == TunnelVpnService.NONE_SENTINEL) return@filter true
                    try {
                        pm.getPackageInfo(pkg, 0)
                        true
                    } catch (_: Exception) {
                        Log.i(TAG, "Pruning uninstalled package from split tunnel list: $pkg")
                        false
                    }
                }.toSet()
                if (kept == current) return@withContext

                val next = if (kept.isEmpty() &&
                    _splitTunnelMode.value == TunnelVpnService.MODE_INCLUDE
                ) {
                    setOf(TunnelVpnService.NONE_SENTINEL)
                } else {
                    kept
                }
                _allowedApps.value = next
                saveAllowedApps(next)
            } catch (e: Exception) {
                Log.w(TAG, "pruneUninstalledApps error: ${e.message}")
            }
            Unit
        }
    }

    /** Load installed apps via PackageManager (pure Kotlin). */
    private suspend fun loadInstalledApps() {
        withContext(Dispatchers.IO) {
            try {
                val ctx = MainActivity.instance?.applicationContext ?: return@withContext
                val pm = ctx.packageManager
                val intent = android.content.Intent(android.content.Intent.ACTION_MAIN, null)
                intent.addCategory(android.content.Intent.CATEGORY_LAUNCHER)
                val resolveInfos = pm.queryIntentActivities(intent, 0)

                val apps = resolveInfos.mapNotNull { ri ->
                    try {
                        AppInfoUi(
                            packageName = ri.activityInfo.packageName,
                            appName = ri.loadLabel(pm).toString()
                        )
                    } catch (_: Exception) { null }
                }.distinctBy { it.packageName }
                 .sortedBy { it.appName.lowercase() }

                _installedApps.value = apps
                Log.i(TAG, "Loaded ${apps.size} installed apps via PackageManager")
            } catch (e: Exception) {
                Log.e(TAG, "loadInstalledApps error: ${e.message}", e)
                _installedApps.value = emptyList()
            }
        }
    }

    private suspend fun loadAllowedApps() {
        withContext(Dispatchers.IO) {
            try {
                val ctx = MainActivity.instance?.applicationContext
                val json = if (ctx != null) {
                    ctx.getSharedPreferences("rostam_settings", android.content.Context.MODE_PRIVATE)
                        .getString("allowed_apps_json", null)
                } else {
                    null
                }

                if (json != null) {
                    val arr = JSONArray(json)
                    val set = mutableSetOf<String>()
                    for (i in 0 until arr.length()) {
                        set.add(arr.getString(i))
                    }
                    _allowedApps.value = set
                }
            } catch (e: Exception) {
                Log.e(TAG, "loadAllowedApps error: ${e.message}")
            }
            Unit
        }
    }

    fun toggleAppAllowed(packageName: String) {
        if (_splitTunnelMode.value == TunnelVpnService.MODE_EXCLUDE) {
            val current = _allowedApps.value.toMutableSet()
            if (!current.remove(packageName)) current.add(packageName)
            _allowedApps.value = current
            saveAllowedApps(current)
            return
        }

        val current = _allowedApps.value.toMutableSet()
        val isNone = current.size == 1 && current.contains(TunnelVpnService.NONE_SENTINEL)
        if (isNone) {
            val fresh = mutableSetOf(packageName)
            _allowedApps.value = fresh
            saveAllowedApps(fresh)
            return
        }
        if (current.isEmpty()) {
            val all = _installedApps.value.map { it.packageName }.toMutableSet()
            all.remove(packageName)
            _allowedApps.value = all
            saveAllowedApps(all)
        } else if (current.contains(packageName)) {
            current.remove(packageName)
            if (current.isEmpty()) {
                val none = setOf(TunnelVpnService.NONE_SENTINEL)
                _allowedApps.value = none
                saveAllowedApps(none)
            } else {
                _allowedApps.value = current
                saveAllowedApps(current)
            }
        } else {
            current.add(packageName)
            val allApps = _installedApps.value.map { it.packageName }.toSet()
            if (current.containsAll(allApps)) {
                _allowedApps.value = emptySet()
                saveAllowedApps(emptySet())
            } else {
                _allowedApps.value = current
                saveAllowedApps(current)
            }
        }
    }

    /** "All" — check every app. Include: everything tunnelled. Exclude: nothing is. */
    fun selectAllApps() {
        if (_splitTunnelMode.value == TunnelVpnService.MODE_EXCLUDE) {
            val all = _installedApps.value.map { it.packageName }.toSet()
            _allowedApps.value = all
            saveAllowedApps(all)
            return
        }
        _allowedApps.value = emptySet()
        saveAllowedApps(emptySet())
    }

    /**
     * Pre-select only essential apps (browsers + messaging) for low-bandwidth
     * modes.
     */
    fun selectRecommendedApps() {
        if (_splitTunnelMode.value == TunnelVpnService.MODE_EXCLUDE) {
            Log.w(TAG, "selectRecommendedApps ignored: the list is include-mode only")
            return
        }
        val recommended = setOf(
            "com.android.chrome",
            "org.mozilla.firefox",
            "com.sec.android.app.sbrowser",
            "com.opera.browser",
            "com.brave.browser",
            "com.microsoft.emmx",
            "org.telegram.messenger",
            "org.thunderdog.chalern",
            "org.thoughtcrime.securesms",
            "com.whatsapp",
            "com.instagram.android",
            "com.twitter.android",
            "com.Slack",
        )
        val installed = _installedApps.value.map { it.packageName }.toSet()
        val filtered = recommended.intersect(installed)
        if (filtered.isEmpty()) return
        _allowedApps.value = filtered
        saveAllowedApps(filtered)
    }
    /**
     * "None" — uncheck everything. Include: nothing tunnelled. Exclude: everything
     * is.
     */
    fun deselectAllApps() {
        if (_splitTunnelMode.value == TunnelVpnService.MODE_EXCLUDE) {
            _allowedApps.value = emptySet()
            saveAllowedApps(emptySet())
            return
        }
        val none = setOf(TunnelVpnService.NONE_SENTINEL)
        _allowedApps.value = none
        saveAllowedApps(none)
    }

    /** Persist the list AND the direction it is read in, in one edit. */
    /** Add a destination prefix to the bypass list. */
    fun addBypassCidr(text: String): String? {
        val parsed = IpRouting.parse(text) ?: return null
        val canonical = IpRouting.format(parsed)
        val current = _bypassCidrs.value
        if (current.contains(canonical)) return canonical
        if (current.size >= IpRouting.MAX_USER_PREFIXES) {
            Log.w(TAG, "Bypass list is at its ${IpRouting.MAX_USER_PREFIXES}-entry ceiling")
            return null
        }
        val next = current + canonical
        _bypassCidrs.value = next
        saveBypassCidrs(next)
        return canonical
    }

    fun removeBypassCidr(cidr: String) {
        val next = _bypassCidrs.value.filter { it != cidr }
        if (next.size == _bypassCidrs.value.size) return
        _bypassCidrs.value = next
        saveBypassCidrs(next)
    }

    private fun saveBypassCidrs(cidrs: List<String>) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val arr = JSONArray()
                cidrs.forEach { arr.put(it) }
                val ctx = MainActivity.instance?.applicationContext ?: return@launch
                ctx.getSharedPreferences("rostam_settings", android.content.Context.MODE_PRIVATE)
                    .edit()
                    .putString("bypass_cidrs_json", arr.toString())
                    .apply()
                Log.i(TAG, "Saved destination bypass: ${cidrs.size} prefix(es)")
            } catch (e: Exception) {
                Log.e(TAG, "saveBypassCidrs error: ${e.message}")
            }
        }
    }

    private fun saveAllowedApps(apps: Set<String>) {
        val mode = _splitTunnelMode.value
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val arr = JSONArray()
                apps.forEach { arr.put(it) }
                val json = arr.toString()

                val ctx = MainActivity.instance?.applicationContext ?: return@launch
                ctx.getSharedPreferences("rostam_settings", android.content.Context.MODE_PRIVATE)
                    .edit()
                    .putString("allowed_apps_json", json)
                    .putString("split_tunnel_mode", mode)
                    .apply()

                Log.i(TAG, "Saved split tunnel: mode=$mode, ${apps.size} apps (persisted)")
            } catch (e: Exception) {
                Log.e(TAG, "saveAllowedApps error: ${e.message}")
            }
        }
    }

    /**
     * Restore the split-tunnel list AND its direction from SharedPreferences on
     * startup.
     */
    private fun restoreAllowedApps() {
        try {
            val ctx = MainActivity.instance?.applicationContext ?: return

            _splitTunnelMode.value = TunnelVpnService.savedMode(ctx)
            val json = ctx.getSharedPreferences("rostam_settings", android.content.Context.MODE_PRIVATE)
                .getString("allowed_apps_json", "[]") ?: "[]"
            val arr = JSONArray(json)
            val apps = mutableSetOf<String>()
            for (i in 0 until arr.length()) {
                apps.add(arr.getString(i))
            }
            _allowedApps.value = apps

            _bypassCidrs.value = TunnelVpnService.savedBypassCidrs(ctx).toList()
            Log.i(
                TAG,
                "Restored split tunnel from prefs: mode=${_splitTunnelMode.value}, " +
                    "${apps.size} apps, ${_bypassCidrs.value.size} bypass prefix(es)"
            )
        } catch (e: Exception) {
            Log.w(TAG, "restoreAllowedApps error: ${e.message}")
        }
    }

    fun toggleSettings() {
        _showSettings.value = !_showSettings.value
    }

    fun dismissSettings() {
        _showSettings.value = false
    }

    fun openAbout() {
        _showAbout.value = true
    }

    fun dismissAbout() {
        _showAbout.value = false
    }

    fun openPrivacyPolicy() {
        _showPrivacyPolicy.value = true
    }

    fun dismissPrivacyPolicy() {
        _showPrivacyPolicy.value = false
    }

    fun openOpenSource() {
        _showOpenSource.value = true
    }

    fun dismissOpenSource() {
        _showOpenSource.value = false
    }

    fun dismissPaywall() {
        _showPaywall.value = false
    }

    /** Called when a QR config deep link is received. */
    /** Apply a scanned QR config code. */
    fun applyQrConfig(payload: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val ctx = MainActivity.instance?.applicationContext ?: return@launch
            val failure = RemoteConfig.applyQrBootstrap(ctx, payload)
            _toastMessage.value = if (failure == null) {
                ConfigManager.loadFromJson(RemoteConfig.current(ctx))

                applyRateLimit()
                localizedStatus(R.string.qr_config_applied_ok)
            } else {
                failure
            }
        }
    }

    fun showToast(message: String) {
        _toastMessage.value = message
    }

    fun dismissToast() {
        _toastMessage.value = null
    }

    /** Status text for toasts and the connect button. */
    private fun localizedStatus(resId: Int, arg: String? = null): String {
        val ctx = MainActivity.instance ?: return ""
        return if (arg != null) ctx.getString(resId, arg) else ctx.getString(resId)
    }

    /**
     * Play subscription product ids — must match the ACTIVE products in that
     * brand's Play Console exactly. Supplied by the `brand` flavor
     * (BuildConfig.SUB_PRODUCT_IDS) because each brand is its own Console app with
     * its own SKUs.
     */
    private val subProductIds: List<String> =
        BuildConfig.SUB_PRODUCT_IDS.split(",").map { it.trim() }.filter { it.isNotEmpty() }

    private val billing: com.rostamvpn.android.billing.PlayBilling? by lazy {
        MainActivity.instance?.applicationContext?.let {
            com.rostamvpn.android.billing.PlayBilling(it).apply { onPurchase = { p -> handlePlayPurchase(p) } }
        }
    }

    private val _isPaid = MutableStateFlow(loadPaidFlag())
    val isPaid: StateFlow<Boolean> = _isPaid.asStateFlow()

    /** Server-truth subscription detail, for the Account screen. */
    data class SubscriptionInfo(val tier: String, val product: String?, val paidUntil: String?)

    private val _subscription = MutableStateFlow<SubscriptionInfo?>(null)
    val subscription: StateFlow<SubscriptionInfo?> = _subscription.asStateFlow()

    private val _subProducts = MutableStateFlow<List<com.android.billingclient.api.ProductDetails>>(emptyList())
    val subProducts: StateFlow<List<com.android.billingclient.api.ProductDetails>> = _subProducts.asStateFlow()

    private val _webPlans = MutableStateFlow<List<com.rostamvpn.android.billing.CashierApi.Plan>>(emptyList())
    val webPlans: StateFlow<List<com.rostamvpn.android.billing.CashierApi.Plan>> = _webPlans.asStateFlow()

    private val _payRail = MutableStateFlow(com.rostamvpn.android.billing.PayRail.GOOGLE_PLAY)
    val payRail: StateFlow<com.rostamvpn.android.billing.PayRail> = _payRail.asStateFlow()

    private val _plansLoading = MutableStateFlow(false)
    val plansLoading: StateFlow<Boolean> = _plansLoading.asStateFlow()

    private val _payMethods = MutableStateFlow<com.rostamvpn.android.billing.CashierApi.PayMethods?>(null)

    /** Free-data status for this cid (read-only; the node meters, the app reads). */
    private val _usage = MutableStateFlow<com.rostamvpn.android.billing.CashierApi.UsageStatus?>(null)
    val usage: StateFlow<com.rostamvpn.android.billing.CashierApi.UsageStatus?> = _usage.asStateFlow()

    /**
     * Pull free-data status; nudge the paywall when over quota (not paid, not
     * Iran).
     */
    /** The cashier's usage answer, corrected for a freedom region. */
    private fun asFreedom(u: com.rostamvpn.android.billing.CashierApi.UsageStatus) =
        if (isFreedomRegion()) u.copy(overQuota = false, unlimited = true) else u

    fun refreshUsage() {
        viewModelScope.launch(Dispatchers.IO) {
            val u = asFreedom(com.rostamvpn.android.billing.CashierApi.usage(cid()) ?: return@launch)
            _usage.value = u
            applyRateLimit()

            if (u.overQuota && !u.paid && !_isPaid.value && !isFreedomRegion() && !ConfigManager.paidOnly) {
                _toastMessage.value = localizedStatus(R.string.usage_used_up)
                openPaywallAndLoad()
            }
        }
    }

    /**
     * Report cumulative used_mb to the cashier (app-meter model, last-write-wins),
     * then refresh status. Skipped for Iran (free/unlimited — no metering).
     */
    private fun reportUsageTotal() {
        if (isFreedomRegion()) return
        val usedMb = (dataTotalBase + sessionRx + sessionTx) / 1_000_000L
        viewModelScope.launch(Dispatchers.IO) {
            val reported = com.rostamvpn.android.billing.CashierApi.reportUsage(cid(), usedMb)
            val u = asFreedom(reported ?: return@launch)
            _usage.value = u
            applyRateLimit()

            if (u.overQuota && !u.paid && !_isPaid.value && !ConfigManager.paidOnly) {
                _toastMessage.value = localizedStatus(R.string.usage_used_up)
                openPaywallAndLoad()

                if (quotaHardStopped()) {
                    Log.i(TAG, "[quota] hard stop — ending the live session")
                    disconnect()
                }
            }
        }
    }

    private fun prefs() = MainActivity.instance?.applicationContext
        ?.getSharedPreferences("rostam_prefs", android.content.Context.MODE_PRIVATE)

    private var dataTotalBase: Long = prefs()?.getLong("data_total", 0L) ?: 0L
    private val _dataTotal = MutableStateFlow(dataTotalBase)
    val dataTotal: StateFlow<Long> = _dataTotal.asStateFlow()
    private fun persistDataTotal(v: Long) { prefs()?.edit()?.putLong("data_total", v)?.apply() }

    private val _showDataUsage = MutableStateFlow(false)
    val showDataUsage: StateFlow<Boolean> = _showDataUsage.asStateFlow()
    fun openDataUsage() { _showDataUsage.value = true }
    fun dismissDataUsage() { _showDataUsage.value = false }

    private fun loadPaidFlag(): Boolean = prefs()?.getBoolean("paid", false) ?: false

    /** Push the free-tier speed limit into the core. */
    /** Is this user in the reduced-speed tier right now? */
    private fun inReducedSpeedTier(): Boolean {
        val cfg = ConfigManager.throttleConfig
        return cfg.enabled &&
            cfg.freeKbps > 0 &&
            !_isPaid.value &&
            !isFreedomRegion() &&
            (_usage.value?.overQuota == true)
    }

    /** Push the tier policy into the core: speed limit + CDN access. */
    /**
     * True when this user is actually being slowed right now. Mirrors the real
     * decision rather than re-deriving it from over-quota: throttling also needs
     * the config switch, so over-quota alone would be a false claim.
     */
    private val _reducedSpeed = MutableStateFlow(false)
    val reducedSpeed: StateFlow<Boolean> = _reducedSpeed.asStateFlow()

    /**
     * The free tier's speed ceiling, in kbps — the SAME number `applyRateLimit`
     * pushes into the core, read from the same place.
     */
    val freeTierKbps: Int
        get() = ConfigManager.throttleConfig.let { if (it.enabled) it.freeKbps else 0 }

    /** True when the free tier STOPS at the allowance instead of slowing down. */
    val quotaStops: Boolean
        get() = ConfigManager.throttleConfig.hardStop

    private fun applyRateLimit() {
        _premiumAvailable.value = premiumAvailableNow()
        applyBlockingEntitlement()
        if (!RedSocketBridge.isLoaded) return
        val cfg = ConfigManager.throttleConfig
        val limited = inReducedSpeedTier()
        _reducedSpeed.value = limited
        val cdnAllowed = _isPaid.value || isFreedomRegion()
        try {
            RedSocketBridge.setCdnAllowed(cdnAllowed)
        } catch (t: Throwable) {
            Log.w(TAG, "setCdnAllowed failed: ${t.message}")
        }
        Log.i(TAG, "[throttle] decide limited=$limited (cfgEnabled=${cfg.enabled} kbps=${cfg.freeKbps} " +
            "paid=${_isPaid.value} freedom=${isFreedomRegion()} over=${_usage.value?.overQuota})")
        try {
            RedSocketBridge.setRateLimitKbps(if (limited) cfg.freeKbps else 0)
        } catch (t: Throwable) {
            Log.w(TAG, "setRateLimitKbps failed: ${t.message}")
        }
    }

    /** An operator notice from the control stream, surfaced as a system notification. */
    data class Notice(val title: String, val body: String)

    private var guardJob: kotlinx.coroutines.Job? = null

    /** While connected: handle queued control-stream messages. */
    private fun runGuardPoller(connected: Boolean) {
        guardJob?.cancel()
        val messaging = ConfigManager.protectionEnabled || ConfigManager.messagingEnabled
        if (!messaging) return
        if (!connected) {
            if (RedSocketBridge.isLoaded) runCatching { handleNotes(RedSocketBridge.drainNotes()) }
            return
        }
        guardJob = viewModelScope.launch(Dispatchers.IO) {
            while (RedSocketBridge.isLoaded) {
                runCatching { handleNotes(RedSocketBridge.drainNotes()) }
                delay(5000)
            }
        }
    }

    /**
     * The config the core loads, plus the persisted mailbox cursor as `ctl_cursor`
     * ({since, broadcast_seq}).
     */
    private fun withCtlCursor(ctx: android.content.Context, json: String): String {
        val c = ctx.getSharedPreferences("rostam_prefs", android.content.Context.MODE_PRIVATE)
            .getString("ctl_cursor", null) ?: return json
        return runCatching { org.json.JSONObject(json).put("ctl_cursor", org.json.JSONObject(c)).toString() }
            .getOrDefault(json)
    }

    /** Downstream control messages (PROTECTION-DESIGN section 9). */
    private fun handleNotes(json: String) {
        val arr = org.json.JSONArray(json)
        for (i in 0 until arr.length()) {
            val m = arr.optJSONObject(i) ?: continue
            val p = m.optJSONObject("payload") ?: org.json.JSONObject()
            when (m.optString("type")) {
                "entitlement" -> if (!BuildConfig.PAID_ONLY) {
                    val tier = p.optString("tier")
                    val state = p.optString("state")
                    val until = parsePaidUntil(p.optString("paid_until"))
                    val paidState = if (state.isNotEmpty()) state.equals("paid", true)
                        else tier.isNotEmpty() && !tier.equals("free", true)
                    val active = paidState && (until == null || until > System.currentTimeMillis())
                    _subscription.value = SubscriptionInfo(tier, _subscription.value?.product, p.optString("paid_until"))
                    viewModelScope.launch(Dispatchers.Main) { setPaid(active) }
                } else {
                    val tier = p.optString("tier")
                    val state = p.optString("state")

                    if (state.isEmpty()) continue
                    entitlementAnswered = true
                    val until = parsePaidUntil(p.optString("paid_until"))
                    val active = state.equals("paid", true) && (until == null || until > System.currentTimeMillis())

                    _subscription.value = SubscriptionInfo(tier, _subscription.value?.product, p.optString("paid_until"))
                    persistPaidUntil(p.optString("paid_until"))
                    viewModelScope.launch(Dispatchers.IO) {
                        val keep = !active && _isPaid.value && playSubOrUnknown() != false
                        if (keep) Log.i(TAG, "[PAY] entitlement note says $state but Play shows an active sub or could not answer — keeping paid")
                        else kotlinx.coroutines.withContext(Dispatchers.Main) { setPaid(active) }
                    }
                }

                "cursor" -> MainActivity.instance?.applicationContext
                    ?.getSharedPreferences("rostam_prefs", android.content.Context.MODE_PRIVATE)
                    ?.edit()?.putString("ctl_cursor", p.toString())?.apply()
                "notice" -> postNotice(Notice(p.optString("title"), p.optString("body")))
                "rules" -> {
                    val incoming = org.json.JSONObject().put("allow", p.optJSONArray("allow") ?: org.json.JSONArray())
                        .put("deny", p.optJSONArray("deny") ?: org.json.JSONArray()).toString()
                    if (incoming != RedSocketBridge.getUserRules()) RedSocketBridge.setUserRules(incoming)
                }
            }
        }
    }

    /**
     * A system notification only where notifications are already allowed; no new
     * permission.
     */
    private fun postNotice(n: Notice) {
        val ctx = MainActivity.instance?.applicationContext ?: return
        val nm = androidx.core.app.NotificationManagerCompat.from(ctx)
        if (!nm.areNotificationsEnabled()) return
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val mgr = ctx.getSystemService(android.app.NotificationManager::class.java)
            mgr?.createNotificationChannel(android.app.NotificationChannel(
                "notices", ctx.getString(R.string.tabs_notices), android.app.NotificationManager.IMPORTANCE_LOW,
            ))
        }
        val b = androidx.core.app.NotificationCompat.Builder(ctx, "notices")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(n.title).setContentText(n.body)
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_LOW)
            .setAutoCancel(true)
        runCatching { nm.notify(0x4E07, b.build()) }
    }

    private fun applyBlockingEntitlement() {
        if (!RedSocketBridge.isLoaded) return

        val entitled = _isPaid.value || isFreedomRegion()
        try {
            RedSocketBridge.setBlockingEnabled(entitled)
        } catch (e: Throwable) {
            Log.w(TAG, "setBlockingEnabled unavailable: ${e.message}")
        }
    }

    private fun setPaid(paid: Boolean) {
        if (!paid && debugAssumePaid()) { _isPaid.value = true; return }
        val wasPaid = _isPaid.value
        prefs()?.edit()?.putBoolean("paid", paid)?.apply()
        _isPaid.value = paid

        if (wasPaid && !paid && ConfigManager.paidOnly &&
            _connectionState.value !is ConnectionState.Disconnected) {
            Log.i(TAG, "[paid_only] entitlement lost while connected — disconnecting")
            disconnect()
            openPaywallAndLoad()
        }
        schedulePaidLapse()

        applyRateLimit()
        applyBlockingEntitlement()

        if (RedSocketBridge.isLoaded) {
            try { RedSocketBridge.setPremium(paid) } catch (e: Throwable) {
                Log.w(TAG, "setPremium failed: ${e.message}")
            }
        }
    }

    private fun cid(): String = MainActivity.instance?.applicationContext
        ?.let { com.rostamvpn.android.billing.CashierIdentity.cid(it) } ?: ""

    /**
     * "Freedom regions" get 100% free, unmonetized access: no metering, no
     * paywall, no commercial ads / GAID (sponsor "brought to you by" cards still
     * show — that's the funding model).
     */
    /**
     * Which freedom region this device is in, or null. Delegates to the ONE
     * derivation in `RegionDetector.freedomRegion` — see its doc for the
     * precedence and why both the SIM and the timezone count.
     */
    private fun freedomRegion(): String? {
        if (!ConfigManager.freedomTier) return null
        val ctx = MainActivity.instance?.applicationContext ?: return null
        return com.rostamvpn.android.billing.RegionDetector.freedomRegion(ctx)
    }

    private fun isFreedomRegion(): Boolean = freedomRegion() != null

    /**
     * The server has metered this cid and cut it off. Evidence that OUTRANKS our
     * own region guess, because the cashier is the side that actually enforces.
     */
    /** The allowance is gone and this brand does not keep serving afterwards. */
    private fun quotaHardStopped(): Boolean =
        ConfigManager.throttleConfig.hardStop &&
            !_isPaid.value &&
            !isFreedomRegion() &&
            (_usage.value?.let { it.overQuota && !it.paid } == true)

    /**
     * DEBUG-ONLY assume-paid, for the local protection/messaging end-to-end run
     * (the exit synthesises entitlement only AFTER connect, and paid_only refuses
     * connect before any stream opens).
     */
    private fun debugAssumePaid(): Boolean =
        BuildConfig.DEBUG && runCatching {
            @Suppress("PrivateApi")
            Class.forName("android.os.SystemProperties")
                .getMethod("get", String::class.java)
                .invoke(null, "debug.ip.assume_paid") == "1"
        }.getOrDefault(false)

    /** paid_only and this cid is not paid (the persisted flag, not /usage). */
    private fun paidOnlyBlocked(): Boolean = ConfigManager.paidOnly && !_isPaid.value && !debugAssumePaid()

    /** For the UI: hide every free-allowance surface on a paid_only brand. */
    val paidOnly: Boolean get() = ConfigManager.paidOnly

    /**
     * paid_only: end a live session when `paid_until` passes, on the client's
     * clock, without waiting for the server.
     */
    private var paidLapseJob: kotlinx.coroutines.Job? = null

    /** Python isoformat(): naive = UTC, optional fraction, optional +HH:MM / Z. */
    private fun parsePaidUntil(v: String?): Long? {
        val t = v?.trim()?.takeIf { it.length >= 19 } ?: return null
        return runCatching {
            val f = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US)
                .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
            val base = f.parse(t.substring(0, 19).replace(' ', 'T'))!!.time
            val off = Regex("([+-])(\\d{2}):?(\\d{2})$").find(t.substring(19))
            if (off == null) base else {
                val (sign, hh, mm) = off.destructured
                val ms = (hh.toLong() * 60 + mm.toLong()) * 60_000L
                if (sign == "+") base - ms else base + ms
            }
        }.getOrNull()
    }

    /** Last known paid_until, persisted so a cashier outage cannot erase it. */
    private fun persistPaidUntil(v: String?) {
        if (!v.isNullOrBlank()) prefs()?.edit()?.putString("paid_until", v)?.apply()
    }

    private fun paidUntilMillis(): Long? =
        parsePaidUntil(_subscription.value?.paidUntil?.takeIf { it.isNotBlank() }
            ?: prefs()?.getString("paid_until", null))

    /**
     * PAID_ONLY build, locally "paid", but the last paid_until we ever saw is more
     * than [PAID_GRACE_MS] behind us.
     */
    private fun paidOnlyStale(): Boolean {
        if (!BuildConfig.PAID_ONLY || !_isPaid.value || debugAssumePaid()) return false
        val until = paidUntilMillis() ?: return false
        return System.currentTimeMillis() - until > PAID_GRACE_MS
    }

    /** When Play last confirmed an active sub for a stale-paid_until connect. */
    @Volatile private var staleCheckedAt = 0L

    private fun schedulePaidLapse() {
        paidLapseJob?.cancel()
        if (!ConfigManager.paidOnly || !_isPaid.value) return
        if (_connectionState.value !is ConnectionState.Connected) return

        if (!entitlementAnswered) return
        val until = parsePaidUntil(_subscription.value?.paidUntil) ?: return
        paidLapseJob = viewModelScope.launch {
            delay((until - System.currentTimeMillis()).coerceAtLeast(0L))
            if (playSubOrUnknown() != false) {
                Log.i(TAG, "[paid_only] paid_until passed; Play has a sub or could not answer — re-syncing")
                syncEntitlement()
                return@launch
            }

            when (askCashierNow()) {
                true -> { schedulePaidLapse(); return@launch }
                null -> { Log.i(TAG, "[paid_only] paid_until passed, cashier unreachable — keeping"); return@launch }
                false -> Unit
            }
            Log.i(TAG, "[paid_only] paid_until passed — ending the session")
            setPaid(false)
            disconnect()
            openPaywallAndLoad()
        }
    }

    private fun serverCutOff(): Boolean =
        !_isPaid.value && (_usage.value?.let { it.overQuota && !it.paid } == true)

    /**
     * Single derivation of "may we offer Premium at all" — hidden for freedom
     * regions, because they already have the whole product.
     */
    private fun premiumAvailableNow(): Boolean = !isFreedomRegion() || serverCutOff()

    /** Whether to offer Premium at all (hidden for freedom regions — they're free). */
    private val _premiumAvailable = MutableStateFlow(premiumAvailableNow())

    /** Fetch the plans BEFORE the tap, not on it. */
    init {
        viewModelScope.launch {
            kotlinx.coroutines.flow.combine(_subscription, _connectionState) { a, b -> a to b }
                .collect { schedulePaidLapse() }
        }
        viewModelScope.launch {
            _connectionState.collect { runGuardPoller(it is ConnectionState.Connected) }
        }
        viewModelScope.launch {
            _premiumAvailable.collect { available ->
                if (available && _subProducts.value.isEmpty()) prewarmPlans()
            }
        }
    }
    val premiumAvailable: StateFlow<Boolean> = _premiumAvailable.asStateFlow()

    /** Open the paywall and load the region-appropriate purchase options. */
    fun openPaywallAndLoad() {
        if (isFreedomRegion() && !serverCutOff()) {
            _toastMessage.value = "You have free unlimited access ✓"
            return
        }
        _showPaywall.value = true

        _plansLoading.value = _subProducts.value.isEmpty()
        viewModelScope.launch(Dispatchers.IO) {
          try {
            val ctx = MainActivity.instance?.applicationContext ?: return@launch

            if (BuildConfig.WEB_PAY_ENABLED) {
                viewModelScope.launch(Dispatchers.IO) {
                    _payMethods.value =
                        com.rostamvpn.android.billing.CashierApi.payMethods()
                }
            }

            val methods = _payMethods.value
            val storeCountry = billing?.billingCountry()
            val country = com.rostamvpn.android.billing.RegionDetector.detectCountry(ctx, storeCountry).ifEmpty { "US" }
            when {
                BuildConfig.WEB_PAY_ENABLED && methods?.stripe == true -> {
                    _payRail.value = com.rostamvpn.android.billing.PayRail.STRIPE
                    _webPlans.value = com.rostamvpn.android.billing.CashierApi.plans(country)
                }
                BuildConfig.WEB_PAY_ENABLED && methods?.anyCrypto == true -> {
                    _payRail.value = com.rostamvpn.android.billing.PayRail.CRYPTO
                    _webPlans.value = com.rostamvpn.android.billing.CashierApi.plans(country)
                }
                else -> {
                    _payRail.value = com.rostamvpn.android.billing.PayRail.GOOGLE_PLAY
                    if (methods?.googleIap != false) {
                        _subProducts.value = billing?.queryProducts(subProductIds) ?: emptyList()
                    }
                }
            }
          } finally {
            _plansLoading.value = false
          }
        }
    }

    /** The region we may actually connect to. */
    fun mayPickRegion(): Boolean {
        if (_isPaid.value || !_premiumAvailable.value) return true
        val u = _usage.value ?: return false
        return u.canSelectRegion && !u.overQuota
    }

    private fun entitledRegion(): String {
        val chosen = _selectedRegion.value
        if (chosen.isEmpty() || chosen == "auto") return "auto"
        if (mayPickRegion()) return chosen
        Log.i(TAG, "[PAY] region '$chosen' is premium-only -> using auto")
        return "auto"
    }

    /**
     * Launch the Play purchase flow for a subscription. Must be called from the UI
     * thread.
     */
    fun purchaseSubscription(productId: String) {
        purchaseTapInFlight = true
        val activity = MainActivity.instance ?: return
        val pd = _subProducts.value.firstOrNull { it.productId == productId } ?: return

        viewModelScope.launch {
            val oldToken = withContext(Dispatchers.IO) {
                billing?.queryActivePurchases().orEmpty()
                    .firstOrNull {
                        it.purchaseState == com.android.billingclient.api.Purchase.PurchaseState.PURCHASED &&
                            !it.products.contains(productId)
                    }?.purchaseToken
            }
            if (oldToken != null) Log.i(TAG, "[PAY] plan change to $productId (replacing existing sub)")

            billing?.launchPurchase(activity, pd, oldToken, cid())
        }
    }

    /** Play purchase succeeded → verify with the cashier, then acknowledge. */
    private fun handlePlayPurchase(purchase: com.android.billingclient.api.Purchase) {
        viewModelScope.launch(Dispatchers.IO) {
            val productId = purchase.products.firstOrNull() ?: return@launch
            val ok = com.rostamvpn.android.billing.CashierApi.iapVerify(cid(), productId, purchase.purchaseToken)
            if (ok) {
                if (!purchase.isAcknowledged) billing?.acknowledge(purchase.purchaseToken)
                clearPendingAck()
                setPaid(true)

                _showPaywall.value = false
                _premiumCelebration.value = true

                refreshUsage()

                val fromTap = purchaseTapInFlight
                purchaseTapInFlight = false
                val st = _connectionState.value
                if (!fromTap) {
                    Log.i(TAG, "[PAY] purchase arrived without a tap; entitled, NOT connecting")
                } else if (st !is ConnectionState.Connected && st !is ConnectionState.Connecting) {
                    Log.i(TAG, "[PAY] premium activated — connecting")
                    connectAuto()
                }
            } else {
                savePendingAck(productId, purchase.purchaseToken)
                Log.w(TAG, "[PAY] verify failed — queued for retry (product=$productId)")
                _toastMessage.value = "Payment received — activating, please reopen the app shortly"
            }
        }
    }

    private fun savePendingAck(productId: String, token: String) {
        prefs()?.edit()
            ?.putString("pending_ack_product", productId)
            ?.putString("pending_ack_token", token)
            ?.apply()
    }

    private fun clearPendingAck() {
        prefs()?.edit()?.remove("pending_ack_product")?.remove("pending_ack_token")?.apply()
    }

    /** Retry a purchase that Play took but the cashier never confirmed. */
    fun retryPendingAck() {
        val p = prefs() ?: return
        val productId = p.getString("pending_ack_product", null) ?: return
        val token = p.getString("pending_ack_token", null) ?: return
        viewModelScope.launch(Dispatchers.IO) {
            Log.i(TAG, "[PAY] retrying queued purchase verify (product=$productId)")
            if (com.rostamvpn.android.billing.CashierApi.iapVerify(cid(), productId, token)) {
                billing?.acknowledge(token)
                clearPendingAck()
                setPaid(true)
                _toastMessage.value = "Premium activated ✓"
            }
        }
    }

    /** Does Play still hold an active PURCHASED subscription on this device? */
    /**
     * Play's answer, or null when Play could not answer (an error is "unknown",
     * never "no subscription").
     */
    private suspend fun playSubOrUnknown(): Boolean? =
        billing?.queryActivePurchasesOrNull()?.any {
            it.purchaseState == com.android.billingclient.api.Purchase.PurchaseState.PURCHASED
        }

    /**
     * Has the cashier (register or an entitlement note) answered in THIS process?
     * Until it has, a persisted paid_until is a hint, not a verdict.
     */
    @Volatile private var entitlementAnswered = false

    /**
     * Ask the cashier now, directly (a PAID_ONLY build may: see CashierApi.open).
     * true = paid with a live paid_until, false = the cashier says not paid, null
     * = unreachable. Updates _subscription and the persisted date.
     */
    private suspend fun askCashierNow(): Boolean? = withContext(Dispatchers.IO) {
        val direct = !com.rostamvpn.android.billing.CashierApi.tunnelUp()
        val reg = runCatching {
            com.rostamvpn.android.billing.CashierApi.registerCid(cid = cid(), tz = java.util.TimeZone.getDefault().id)
        }.getOrNull() ?: return@withContext null
        val until = parsePaidUntil(reg.paidUntil)
        val paid = reg.isPaid && (until == null || until > System.currentTimeMillis())

        if (!paid && direct) return@withContext null
        entitlementAnswered = true
        _subscription.value = SubscriptionInfo(reg.tier, reg.product, reg.paidUntil)
        persistPaidUntil(reg.paidUntil)
        paid
    }

    /**
     * The paywall's Restore on a PAID_ONLY build also asks the cashier directly,
     * so a web renewal reaches the device.
     */
    fun recheckEntitlement() {
        viewModelScope.launch {
            when (askCashierNow()) {
                true -> { setPaid(true); _showPaywall.value = false; _toastMessage.value = localizedStatus(R.string.restore_ok) }
                false -> _toastMessage.value = localizedStatus(R.string.restore_none)
                null -> _toastMessage.value = localizedStatus(R.string.connection_failed)
            }
        }
    }

    private suspend fun playHasActiveSubscription(): Boolean =
        billing?.queryActivePurchases().orEmpty().any {
            it.purchaseState == com.android.billingclient.api.Purchase.PurchaseState.PURCHASED
        }

    /** Restore entitlement — re-verify any active store subscription. */
    fun restoreEntitlement(notify: Boolean = false) {
        retryPendingAck()
        viewModelScope.launch(Dispatchers.IO) {
            val active = billing?.queryActivePurchases().orEmpty()
            val purchase = active.firstOrNull {
                it.purchaseState == com.android.billingclient.api.Purchase.PurchaseState.PURCHASED
            } ?: run {
                if (notify && BuildConfig.PAID_ONLY) { recheckEntitlement(); return@launch }
                if (notify) _toastMessage.value = localizedStatus(R.string.restore_none)
                return@launch
            }
            val productId = purchase.products.firstOrNull() ?: return@launch
            val ok = com.rostamvpn.android.billing.CashierApi.iapVerify(cid(), productId, purchase.purchaseToken)
            if (ok) {
                if (!purchase.isAcknowledged) billing?.acknowledge(purchase.purchaseToken)
                setPaid(true)
                if (notify) _toastMessage.value = localizedStatus(R.string.restore_ok)
            } else {
                if (notify) _toastMessage.value = localizedStatus(R.string.restore_none)

                Log.i(TAG, "[PAY] restore: verify failed — keeping cached tier")
            }
        }
    }

    /**
     * Server-truth entitlement on launch: register the cid and adopt its tier.
     * Covers every rail (card/crypto/IAP) + restored account codes.
     */
    /** Whether the cashier has answered /cid/register at least once this process. */
    @Volatile
    private var cashierRegistered = false

    fun syncEntitlement() {
        applyRateLimit()
        viewModelScope.launch(Dispatchers.IO) {
            val ctx = MainActivity.instance?.applicationContext ?: return@launch
            val tz = java.util.TimeZone.getDefault().id
            val adId = try {
                com.rostamvpn.android.billing.Gaid.get(ctx)
            } catch (_: Throwable) {
                com.rostamvpn.android.billing.AdId(null, true)
            }

            if (RedSocketBridge.isLoaded) {
                try {
                    RedSocketBridge.setPeerTag(adId.gaid.orEmpty())
                } catch (t: Throwable) {
                    Log.w(TAG, "[peer] setPeerTag failed: $t")
                }
            }
            val lmt = if (adId.limited || adId.gaid.isNullOrBlank()) 1 else 0

            val directPath = BuildConfig.PAID_ONLY && !com.rostamvpn.android.billing.CashierApi.tunnelUp()
            val reg = com.rostamvpn.android.billing.CashierApi.registerCid(
                cid = cid(),
                tz = tz,
                ifa = adId.gaid,
                lmt = lmt,
            ) ?: return@launch
            cashierRegistered = true

            if (!BuildConfig.PAID_ONLY) {
                if (reg.isPaid) {
                    setPaid(true)
                } else if (_isPaid.value && playHasActiveSubscription()) {
                    Log.i(TAG, "[PAY] register says free but Play still shows an active sub — keeping paid")
                } else {
                    setPaid(false)
                }
                _subscription.value = SubscriptionInfo(reg.tier, reg.product, reg.paidUntil)
            } else if (!reg.isPaid && directPath) {
                Log.i(TAG, "[PAY] direct register says ${reg.tier}; not applied (upgrade-only path)")
            } else {
                entitlementAnswered = true

                _subscription.value = SubscriptionInfo(reg.tier, reg.product, reg.paidUntil)
                persistPaidUntil(reg.paidUntil)
                if (reg.isPaid) {
                    setPaid(true)
                } else if (_isPaid.value && playHasActiveSubscription()) {
                    Log.i(TAG, "[PAY] register says free but Play still shows an active sub — keeping paid")
                } else {
                    setPaid(false)
                }
            }
            Log.i(
                TAG,
                "[PAY] register ok tier=${reg.tier} ifa=${if (adId.gaid != null) "yes" else "no"} lmt=$lmt",
            )

            refreshUsage()
        }
    }

    private val _pendingCode = MutableStateFlow<String?>(null)

    /** An account code from a link, held until the user confirms the claim. */
    val pendingCode: StateFlow<String?> = _pendingCode.asStateFlow()

    /** Hold a code that arrived from outside the app; nothing is claimed yet. */
    fun offerAccountCode(code: String) {
        _pendingCode.value = code.trim()
    }

    /** The user's answer to [pendingCode]. */
    fun answerAccountCode(claim: Boolean) {
        val code = _pendingCode.value ?: return
        _pendingCode.value = null
        if (claim) claimAccountCode(code)
    }

    /** Restore entitlement from an account code the user confirmed. */
    private fun claimAccountCode(code: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val ok = com.rostamvpn.android.billing.CashierApi.claim(code.trim(), cid())

            if (ok) setPaid(true)

            _toastMessage.value = localizedStatus(
                if (ok) R.string.restore_ok else R.string.restore_invalid
            )
            if (ok) _showPaywall.value = false
        }
    }

    private val _webPayUrl = MutableStateFlow<String?>(null)
    val webPayUrl: StateFlow<String?> = _webPayUrl.asStateFlow()
    private var webPayOrderNo: String? = null

    /**
     * Stripe card checkout. Uses `stripe_hosted`, not `stripe`: the `stripe`
     * channel returns only a `client_secret` for mounting Stripe Elements, and
     * payCreate requires a `pay_url`, so `stripe` would silently return null here.
     */
    fun startStripePayment(planSlug: String) = startWebPayment(planSlug, "stripe_hosted")

    /** Crypto invoice (self-sovereign rail). */
    fun startCryptoPayment(planSlug: String) = startWebPayment(planSlug, "crypto")

    /**
     * Shared web-rail flow: create an order, open its hosted pay_url in the in-app
     * WebView (WebPayScreen — stays in the app), then poll until paid.
     */
    private fun startWebPayment(planSlug: String, channel: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val order = com.rostamvpn.android.billing.CashierApi.payCreate(planSlug, cid(), channel)
            if (order == null) { _toastMessage.value = "Could not start payment"; return@launch }
            webPayOrderNo = order.orderNo
            _webPayUrl.value = order.payUrl
            while (webPayOrderNo == order.orderNo) {
                delay(3000)
                if (com.rostamvpn.android.billing.CashierApi.payStatus(order.orderNo)) {
                    setPaid(true)
                    _toastMessage.value = "Premium activated ✓"
                    _webPayUrl.value = null
                    webPayOrderNo = null
                    _showPaywall.value = false
                    break
                }
            }
        }
    }

    fun dismissWebPay() { _webPayUrl.value = null; webPayOrderNo = null }

    /**
     * The region key used for per-region policy: a freedom-region code when in a
     * freedom region, else the detected SIM/network country. Cheap; safe to call
     * on the main thread.
     */
    fun policyRegion(): String {
        val ctx = MainActivity.instance?.applicationContext ?: return ""

        return com.rostamvpn.android.billing.RegionDetector.freedomRegion(ctx)
            ?: com.rostamvpn.android.billing.RegionDetector.detectCountry(ctx, null)
    }
}
