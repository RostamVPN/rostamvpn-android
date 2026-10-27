package com.rostamvpn.android

import app.netcore.NetCoreBridge
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

        /** Preferred SOCKS port. 1080 is the REGISTERED SOCKS port, which is exactly
         *  why it collides: any other proxy or dev tool may hold it. The core falls
         *  back to an OS-assigned port, so this is a preference, never an
         *  assumption. Read the live value with [activeSocksPort]. */
        const val DEFAULT_SOCKS_PORT = 1080
        /** Background config refresh while the app is alive. */
        const val CONFIG_REFRESH_MS = 6L * 60 * 60 * 1000

        /** Successful connects this install has made, for `ads.grace_connects`.
         *  Lives in `rostam_prefs`, which is a STORAGE KEY and never renamed —
         *  §23: renaming one loses the user's settings. */
        const val KEY_CONNECTS = "ad_grace_connects"

        private const val TAG = "VpnViewModel"

        /**
         * Inbound bytes in one health interval that count as proof of life,
         * short-circuiting the probe entirely.
         *
         * 4 KiB over a 45s interval is ~0.7 kbps: far below any real payload
         * (the quietest usable voice codec is ~6 kbps, i.e. ~33 KB per
         * interval) and far above the carrier's bare TCP ACK traffic, which
         * would otherwise let a tunnel that relays nothing look healthy.
         */
        private const val HEALTH_LIVE_RX_BYTES = 4096L

        /**
         * How many endpoints to try before giving up on a connect.
         *
         * Each failure marks that endpoint bad in the core, so these are three
         * DIFFERENT endpoints, not three tries at the same one. Deliberately
         * small: a fault below the endpoint (a torn-down tun, a captive portal,
         * no upstream at all) fails every endpoint the same way, and walking the
         * whole fleet to learn that would cost the user a minute of "Testing"
         * and pointlessly hammer the POPs. The watchdog retries later anyway.
         */
        private const val VERIFY_ATTEMPTS = 3

        /** AES-256-GCM key for encrypting debug reports before sharing. */
        private val DEBUG_REPORT_KEY = byteArrayOf(
            0x4a, 0x1f, 0x7b.toByte(), 0xe3.toByte(), 0x59, 0x0c, 0xa8.toByte(), 0x6d,
            0xf2.toByte(), 0x38, 0x91.toByte(), 0xc5.toByte(), 0x14, 0x7e, 0xd0.toByte(), 0x43,
            0xb6.toByte(), 0x28, 0x9f.toByte(), 0x5a, 0xe1.toByte(), 0x07, 0x8c.toByte(), 0x34,
            0xfa.toByte(), 0x69, 0xd7.toByte(), 0x15, 0xac.toByte(), 0x4e, 0x83.toByte(), 0x20
        )

    }

    /** Connection state machine */
    sealed class ConnectionState {
        data object Disconnected : ConnectionState()
        data class Connecting(val transport: String = "auto") : ConnectionState()

        /**
         * Tunnel is up and being checked before it is called connected.
         *
         * The endpoint completed a handshake, which only proves the POP
         * answered. Until one request has actually come back through it the app
         * has no business saying "Connected" -- that claim is what sent users
         * to a dead tunnel with a green dial.
         */
        data object Verifying : ConnectionState()
        data class Connected(val transport: String) : ConnectionState()
        data class Failed(val error: String) : ConnectionState()
    }

    /** Traffic stats for display */
    data class TrafficInfo(
        val rxRate: String = "",
        val txRate: String = "",
        val totalRx: String = "",
        val totalTx: String = ""
    )

    // -- State flows --
    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    // Transport preference (Advanced): "auto" = AWG-first then NetCore;
    private val _selectedProtocol = MutableStateFlow(
        (MainActivity.instance?.applicationContext
            ?.getSharedPreferences("rostam_prefs", android.content.Context.MODE_PRIVATE)
            ?.getString("selected_protocol", "auto") ?: "auto")
            // Repair a stored "http". An earlier build rewrote the saved choice
            // from "redsocket" to "http" on load, but availableProtocols() offers
            // the id "redsocket" -- so after one restart the stored value matched
            // NO chip and Settings showed nothing selected, while connect quietly
            // used NetCore anyway. Migrate the other way and the two agree.
            .let { if (it == "http") "redsocket" else it }
    )
    val selectedProtocol: StateFlow<String> = _selectedProtocol.asStateFlow()

    // Geographic server region the user picked ("auto" = race all regions).
    // The protocol is NOT user-selectable — netcore decides it within the region.
    private val _selectedRegion = MutableStateFlow(
        MainActivity.instance?.applicationContext
            ?.getSharedPreferences("rostam_prefs", android.content.Context.MODE_PRIVATE)
            ?.getString("selected_region", "auto") ?: "auto"
    )
    val selectedRegion: StateFlow<String> = _selectedRegion.asStateFlow()

    /** Available regions for the picker: "auto" + whatever the config ships. */
    val availableRegions: List<String> get() = listOf("auto") + ConfigManager.serverRegions


    /** Human label for a region id. Labels are OTA-driven (ConfigManager.regionLabels);
     *  nothing is hardcoded — a new region added via OTA shows its OTA label, or its
     *  id as a fallback. "Automatic" is the only built-in (it's the auto mode, not a region). */
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
        if (NetCoreBridge.isLoaded) {
            try { NetCoreBridge.setRegion(region) } catch (t: Throwable) { Log.w(TAG, "setRegion: $t") }
        }
        // Reconnect on the new region if currently connected.
        if (_connectionState.value is ConnectionState.Connected ||
            _connectionState.value is ConnectionState.Connecting) {
            disconnect()
            connect()
        }
    }

    private val _activeTransport = MutableStateFlow<String?>(null)
    val activeTransport: StateFlow<String?> = _activeTransport.asStateFlow()

    /** Live auto-connect progress text (null when not in auto-connect) */
    private val _autoConnectStatus = MutableStateFlow<String?>(null)
    val autoConnectStatus: StateFlow<String?> = _autoConnectStatus.asStateFlow()


    // Theme: "auto", "light", "dark"
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

    /** Server location entry from dynamic config */
    data class ServerLocationData(
        val id: String,
        val name: String,
        val region: String,
        val transport: String,
        val desc: String
    )

    // Dynamic server locations from config
    private val _serverLocations = MutableStateFlow<List<ServerLocationData>>(emptyList())
    val serverLocations: StateFlow<List<ServerLocationData>> = _serverLocations.asStateFlow()

    // OTA config version (e.g. "v1.23.1")
    private val _configVersion = MutableStateFlow<String>("")
    val configVersion: StateFlow<String> = _configVersion.asStateFlow()

    // App selection state
    data class AppInfoUi(val packageName: String, val appName: String)

    private val _showAppSelection = MutableStateFlow(false)
    val showAppSelection: StateFlow<Boolean> = _showAppSelection.asStateFlow()

    private val _showAbout = MutableStateFlow(false)
    val showAbout: StateFlow<Boolean> = _showAbout.asStateFlow()

    private val _showPrivacyPolicy = MutableStateFlow(false)
    val showPrivacyPolicy: StateFlow<Boolean> = _showPrivacyPolicy.asStateFlow()

    private val _showOpenSource = MutableStateFlow(false)
    val showOpenSource: StateFlow<Boolean> = _showOpenSource.asStateFlow()

    /** eSIM store overlay. Only reachable where BuildConfig.FEATURE_ESIM is true. */
    private val _showEsim = MutableStateFlow(false)
    val showEsim: StateFlow<Boolean> = _showEsim.asStateFlow()

    /** The e-sim.net path the eSIM screen opens on (`/esim/jp`); null = the store's home. */
    private val _esimPath = MutableStateFlow<String?>(null)
    val esimPath: StateFlow<String?> = _esimPath.asStateFlow()

    /** The web-account screen (direct flavour of a brand with `deepLinkHosts`; see WebAccount). */
    private val _showWebAccount = MutableStateFlow(false)
    val showWebAccount: StateFlow<Boolean> = _showWebAccount.asStateFlow()

    /**
     * The instant a purchase lands. Drives a one-shot overlay on the home screen.
     *
     * Separate from `isPaid` because paid is a LASTING condition and this is a
     * MOMENT: re-reading a persisted flag on the next launch must not replay it.
     */
    /**
     * 🚨 Was this purchase started by a TAP, in this session?
     *
     * `handlePlayPurchase` turns a confirmed purchase into the premium moment
     * AND a connect. It is wired to Play's `onPurchasesUpdated`, which does not
     * only fire for `launchBillingFlow` -- Play also delivers purchases
     * resolved OUTSIDE the app (a promo code, an Ask-to-Buy approval, a pending
     * purchase that finally cleared) the next time the billing client connects,
     * which is during launch. Without this flag those arrive looking exactly
     * like a tap and start a tunnel nobody asked for.
     *
     * NEVER persisted, deliberately: a flag that survived the process would
     * replay on the launch after a purchase, which is the same bug one restart
     * later. It is set by `purchaseSubscription` and cleared as soon as it is
     * read, so it can only ever authorise the one purchase it was set for.
     */
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
     * [TunnelVpnService.MODE_EXCLUDE]. Restored from prefs at startup; see
     * [TunnelVpnService.savedMode] for the migration rule that keeps existing
     * include-mode users on include.
     */
    private val _splitTunnelMode = MutableStateFlow(TunnelVpnService.MODE_INCLUDE)
    val splitTunnelMode: StateFlow<String> = _splitTunnelMode.asStateFlow()

    /**
     * Destinations that skip the tunnel, as canonical CIDR text.
     *
     * Independent of [allowedApps] and its mode, and deliberately so: "which
     * apps" and "which destinations" are different questions and the common
     * Iranian case needs both answers at once — the browser stays in the tunnel
     * while the domestic bank it opens stays out of it.
     */
    private val _bypassCidrs = MutableStateFlow<List<String>>(emptyList())
    val bypassCidrs: StateFlow<List<String>> = _bypassCidrs.asStateFlow()

    // Auto-failover
    private var autoConnectJob: Job? = null

    // Traffic monitoring
    private var trafficJob: Job? = null
    private var baseRx: Long = 0L
    private var baseTx: Long = 0L
    @Volatile private var sessionRx: Long = 0L   // bytes this connect (rx)
    @Volatile private var sessionTx: Long = 0L   // bytes this connect (tx)
    /** `sessionRx` as of the previous health tick — see [HEALTH_LIVE_RX_BYTES]. */
    private var lastHealthRx: Long = 0L

    // ── Removed fields ──
    // isProbeEnabled, scanState, dnsResolverSetting, systemDns — no longer needed

    private var coreConfigFingerprint: Int? = null

    init {
        // Load embedded config from APK assets
        try {
            val ctx = MainActivity.instance?.applicationContext
            if (ctx != null) {
                // Cached server config if we have one, else the bundled asset.
                // The bundle is the bootstrap, not the policy: it only has to be
                // good enough to reach the network once. Never blocks -- the app
                // starts on what it already has and refreshes behind itself.
                val json = RemoteConfig.current(ctx)

                // Feed config to Rust backend (for legacy protocol candidates).
                // Guarded: if libnetcore.so failed to load (missing ABI /
                // symbol) these native calls would throw UnsatisfiedLinkError and
                // crash app startup. Degrade gracefully instead — the tunnel just
                // won't be available until a working .so ships.
                // 467 shipped an IPv4-first preferred list, and the core resumes
                // the last endpoint that worked, so a phone that took the slow
                // AWS->LAX path stays on it. Forget the remembered endpoint once
                // per build, so the first connect follows the current order.
                ctx.getSharedPreferences("rostam_prefs", android.content.Context.MODE_PRIVATE).let { rp ->
                    if (rp.getInt("last_ok_reset_vc", 0) < BuildConfig.VERSION_CODE) {
                        ctx.getSharedPreferences("redsocket", android.content.Context.MODE_PRIVATE)
                            .edit().remove("redsocket_last_ok").apply()
                        rp.edit().putInt("last_ok_reset_vc", BuildConfig.VERSION_CODE).apply()
                    }
                }
                if (NetCoreBridge.isLoaded) {
                    NetCoreBridge.loadConfig(withCtlCursor(ctx, json))
                    coreConfigFingerprint = json.hashCode()
                }

                // Parse config on Kotlin side for UI data
                ConfigManager.loadFromJson(json)

                Log.i(TAG, "Config loaded: ${ConfigManager.configVersion}")

                // Session zone hint (used for gating + geo).
                if (NetCoreBridge.isLoaded) {
                    NetCoreBridge.setTimezone(java.util.TimeZone.getDefault().id)
                }

                Log.i(TAG, "init tz=${java.util.TimeZone.getDefault().id}")
        // Provision WGTCP off the main thread so the protocol becomes available
        // on a later launch even if the first attempt had no connectivity.
        provisionWgtcpInBackground()
        refreshConfigInBackground()
        refreshConfigPeriodically()
            } else {
                Log.w(TAG, "No application context for embedded config")
            }
        } catch (t: Throwable) {
            // Catch Throwable, NOT Exception: UnsatisfiedLinkError/LinkageError are
            // java.lang.Error subclasses. A native-symbol miss here must never crash
            // ViewModel construction (this was the 47K-crash root cause).
            Log.e(TAG, "Failed to load embedded config: $t")
        }

        // Populate UI state from ConfigManager
        viewModelScope.launch(Dispatchers.IO) {
            loadServerLocations()
            loadConfigVersion()
            restoreAllowedApps()
        }

        // Feed the rating prompt its two "this user is having a bad time"
        // signals. Collected centrally rather than called at each site on
        // purpose: `ConnectionState.Failed` is assigned from four places today
        // and the paywall is opened from two, and a signal that a future edit
        // silently forgets to send is a five-star card shown on top of
        // "Connection failed". A collector cannot be forgotten.
        viewModelScope.launch {
            _connectionState.collect {
                if (it is ConnectionState.Failed) ReviewManager.onConnectionFailed()
                // Register through the tunnel once it exists.
                //
                // syncEntitlement() otherwise only runs at launch and on resume
                // (MainScreen's LaunchedEffect and MainActivity.onResume), i.e.
                // only ever BEFORE a tunnel exists. Every cashier hostname
                // resolves to one IP, so on a network that blocks it the launch
                // call fails, silently -- and a user who then connects and never
                // backgrounds the app is never asked again for the whole
                // session. That is precisely the user this backend is for.
                //
                // Gated on `cashierRegistered` so a healthy device sends nothing
                // extra: the flag is already true by the time the tunnel is up,
                // and only a device whose cashier is unreachable off-tunnel
                // spends a request here. Watchdog reconnects are covered for
                // free and cost nothing once one attempt has succeeded.
                if (it is ConnectionState.Connected && !cashierRegistered) {
                    Log.i(TAG, "[PAY] tunnel up and cid unregistered — retrying through it")
                    syncEntitlement()
                }
            }
        }
        viewModelScope.launch {
            _showPaywall.collect { if (it) ReviewManager.onPaywallShown() }
        }
    }

    /**
     * Load purchase options ahead of the paywall opening.
     *
     * 🚨 **Prices are CACHED, never SHIPPED.** They cannot be compiled in: Play
     * sets them per country across 173 regions, they change (RedVPN's monthly
     * went $4.99 -> $3.99), and Billing 8 requires a live `offerToken` that only
     * `queryProductDetails` can produce. A baked-in price that disagrees with
     * what Play charges is a policy problem and a refund driver — §22b already
     * has this family advertising three different yearly prices across surfaces.
     * So the fix for slowness is to ask EARLIER, not to stop asking.
     */
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

    // ── Health watchdog ───────────────────────────────────────────────────────
    // Nothing used to notice when a working tunnel died: no re-race on the Rust
    // side (v2/health.rs is a stub) and no watchdog here, so a POP going down
    // mid-session left the UI reading "Connected" forever, with no traffic,
    // until the user manually reconnected.
    //
    // The probe MUST go down the real data path, and that path differs per
    // transport: TunnelVpnService calls addDisallowedApplication(packageName),
    // so our own traffic bypasses the tun entirely and a naive request would
    // "succeed" against a completely dead netcore, so the probe goes
    // through the SOCKS front end instead of the app's excluded route.
    private var healthJob: Job? = null

    /** Seconds between probes, and how many consecutive misses force a reconnect.
     *  Two misses (~90s) rather than one: a single failure is usually a handover
     *  or a transient, and tearing down a working tunnel is worse than waiting. */
    private val healthIntervalMs: Long get() = ConfigManager.healthConfig.intervalSecs * 1000L
    private val healthFailuresBeforeReconnect: Int get() = ConfigManager.healthConfig.failuresBeforeReconnect
    /** Give up after this many automatic reconnects with no success in between.
     *  On a genuinely blocked network, looping forever burns battery and hammers
     *  the POPs with ~500K clients doing the same thing. */
    private val maxAutoReconnects: Int get() = ConfigManager.healthConfig.maxAutoReconnects
    /** Wait after burning the reconnect budget, then try again indefinitely. */
    private val healthCooldownMs: Long get() = ConfigManager.healthConfig.cooldownSecs * 1000L

    /**
     * One probe down the live data path. Any HTTP response counts as alive — we
     * are testing whether bytes flow, not whether the service is healthy, so a
     * 4xx/5xx is still proof the tunnel carried the request.
     *
     * Uses the only approved echo endpoint (CLAUDE.md §0 — no web bugs).
     */
    /**
     * The SOCKS port to dial right now.
     *
     * Asks the core, which knows what it actually bound, and falls back to the
     * preferred port only when nothing is running yet. `isLoaded` is checked
     * because an unguarded native call on a device where the .so failed to load
     * is the 47K-crash pattern.
     */
    private fun activeSocksPort(): Int {
        val p = if (NetCoreBridge.isLoaded) {
            try { NetCoreBridge.getSocksPort() } catch (_: Throwable) { 0 }
        } else 0
        return if (p > 0) p else DEFAULT_SOCKS_PORT
    }

    private fun tunnelAlive(): Boolean {
        return try {
            val url = java.net.URL(ConfigManager.healthConfig.probeUrl)
            // ALWAYS through SOCKS. Both shipping transports serve SOCKS5, and
            // TunnelVpnService excludes this app from the tun, so a direct
            // connection would bypass the tunnel entirely and report healthy
            // against a dead one. The direct arm existed only for AmneziaWG.
            //
            // The PORT IS ASKED FOR, NOT ASSUMED. The core falls back off 1080
            // when another app holds it, so a hardcoded 1080 here would probe a
            // port nothing is listening on and the watchdog would tear down a
            // perfectly healthy tunnel every two misses.
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
            // Same pool crash as CashierApi.open -- see the long note there.
            // This probe is the worst offender by frequency: it runs every 45s
            // through SOCKS and is the one call deliberately made WHILE the
            // tunnel may be dead, which is precisely when the socket has no
            // InetAddress for the pool to dereference.
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

    /**
     * One request through the tunnel before it is called connected.
     *
     * Reuses [tunnelAlive], so the connect gate and the watchdog judge a tunnel
     * by exactly the same evidence and cannot disagree about what "working"
     * means.
     *
     * ⚠️ This probes through SOCKS, so it tests the ENDPOINT -- which is what
     * makes "try the next IP" meaningful. It cannot see a broken tun: the app
     * is excluded from its own tunnel (addDisallowedApplication), and a tun
     * whose routes were torn down still leaves SOCKS answering 200. Measured
     * while chasing exactly that bug. A tun-routing check is a separate thing
     * and is NOT what this gate is for.
     */
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
            // while(true), like the traffic monitor: delay() is cancellable, so
            // stopHealthWatchdog()/cancel() still ends this promptly.
            while (true) {
                delay(healthIntervalMs)
                val st = _connectionState.value
                // The user turned it off — nothing to watch. (disconnect() also
                // cancels this job; this is belt-and-braces.)
                if (st is ConnectionState.Disconnected) { misses = 0; continue }
                // A connect is in flight; never tear that down from here.
                if (st is ConnectionState.Connecting) continue

                // A tunnel that is CARRYING TRAFFIC is alive, and no external
                // probe gets to overrule that.
                //
                // This is the fix for "calls drop every ~2 minutes". The probe
                // has a 6s connect + 6s read timeout, so two misses at a 45s
                // interval reach a reconnect at ~102-114s — and
                // disconnectThenReconnect() kills every live stream. On a lossy
                // Iranian mobile link a 6s budget is easily blown by ordinary
                // TCP retransmits (RTO 1s, 2s, 4s), so the probe failed while
                // the tunnel was demonstrably fine. Measured server-side on
                // lax00-03: a 6-minute idle session and a 6-minute low-rate
                // stream both survived with no drop, and 30/30 probe fetches
                // succeeded — so the reconnects were never the tunnel dying.
                //
                // rx, not tx: bytes ARRIVING prove the round trip completed.
                // Counting tx would call a tunnel healthy for shouting into a
                // void. The threshold is above bare TCP ACK traffic, because
                // ACKs from the POP's own stack prove the carrier is up but not
                // that it is relaying; a real call is ~6kbps minimum, which is
                // ~33KB per interval and clears this by an order of magnitude.
                val rxNow = sessionRx
                val rxDelta = rxNow - lastHealthRx
                lastHealthRx = rxNow
                if (rxDelta >= HEALTH_LIVE_RX_BYTES) {
                    if (misses > 0) Log.i(TAG, "[HEALTH] recovered — $rxDelta rx bytes since last tick")
                    misses = 0
                    reconnects = 0
                    continue
                }
                // Failed counts as dead, so a reconnect that failed because the
                // network was down still gets retried once it returns. Without
                // this the app sat in Failed forever — observed on-device: radios
                // off -> reconnect failed -> radios back on -> nothing.
                val alive = st is ConnectionState.Connected && tunnelAlive()
                if (alive) {
                    if (misses > 0) Log.i(TAG, "[HEALTH] recovered after $misses miss(es)")
                    misses = 0
                    reconnects = 0          // a good probe clears the budget
                    continue
                }
                misses++
                Log.w(TAG, "[HEALTH] probe $misses/$healthFailuresBeforeReconnect failed " +
                    "(transport=${_activeTransport.value})")
                if (misses < healthFailuresBeforeReconnect) continue

                if (reconnects >= maxAutoReconnects) {
                    // Cool off rather than give up. Giving up stranded the app in
                    // Failed until the user noticed; retrying flat-out would burn
                    // battery and have ~500K clients hammer the POPs together.
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

    /**
     * This device's WGTCP config, from the provisioning API.
     *
     * Was: read a private key straight out of the bundled config. That key was
     * identical in every install and bound to one /32 on the server, so exactly
     * one device could use the tunnel at a time and nothing could be revoked.
     * Now the device holds its own key and asks for an address.
     *
     * Returns null when this device has no usable profile, which leaves WGTCP
     * out of the running and lets the connect fall through to NetCore.
     */
    private fun wgtcpConfigJson(): String? {
        val ctx = MainActivity.instance?.applicationContext ?: return null
        // Blocking: only ever called from the connect path, which is already on
        // an IO dispatcher. The UI asks wgtcpAvailable() instead.
        return WgtcpProvisioner.config(ctx, socksPort = null)
    }

    /**
     * Whether WGTCP can be offered, without touching the network.
     *
     * availableProtocols() runs on the main thread every time the settings sheet
     * draws. Asking it to provision there threw NetworkOnMainThreadException,
     * which surfaced as WGTCP quietly missing from the protocol list -- no error,
     * no explanation, just an option that was never there.
     */
    private fun wgtcpAvailable(): Boolean {
        val ctx = MainActivity.instance?.applicationContext ?: return false
        return WgtcpProvisioner.isProvisioned(ctx)
    }

    /**
     * Provision in the background so the protocol is offered on a later launch
     * even if the first attempt happened with no connectivity.
     */
    /**
     * Pull the current config from the server.
     *
     * Applied on the NEXT launch rather than mid-session: swapping endpoints,
     * ad policy and region rules underneath a running tunnel would change
     * behaviour while a user is connected, and the failure would look like the
     * tunnel breaking rather than a config change.
     */
    private fun refreshConfigInBackground(viaTunnel: Boolean = false) {
        val ctx = MainActivity.instance?.applicationContext ?: return
        viewModelScope.launch(Dispatchers.IO) {
            // Direct first; through the tunnel only if no base answered and a
            // tunnel is up (RemoteConfig.refresh decides, serialised there).
            val socks = if (viaTunnel && NetCoreBridge.isLoaded) activeSocksPort() else null
            if (RemoteConfig.refresh(ctx, socksPort = socks)) {
                Log.i(TAG, "[CONFIG] newer config stored; endpoints apply on the next connect, the rest on the next launch")
                loadConfigVersion()
            }
        }
    }

    /**
     * One fetch per launch was the only refresh, so a phone that keeps the app
     * alive for days never saw a rotated address until it was restarted twice
     * (fetch, then apply). Every [CONFIG_REFRESH_MS] while the app is alive,
     * and through the tunnel when it is up.
     */
    private fun refreshConfigPeriodically() {
        viewModelScope.launch {
            while (true) {
                kotlinx.coroutines.delay(CONFIG_REFRESH_MS)
                refreshConfigInBackground(viaTunnel = _connectionState.value is ConnectionState.Connected)
            }
        }
    }

    /** What the core was last handed by [NetCoreBridge.loadConfig]. */

    /**
     * Before a connect: if a newer config was stored since the core loaded its
     * own (a background refresh or a scanned QR bootstrap), hand it to the core
     * now. The core is stopped here, so nothing running changes; only the
     * endpoint list and transport settings move to the new config. Kotlin-side
     * policy (ads, regions UI) still switches on the next launch, as before.
     */
    private fun reloadCoreConfigIfChanged(ctx: android.content.Context) {
        if (!NetCoreBridge.isLoaded) return
        val json = RemoteConfig.current(ctx)
        val fp = json.hashCode()
        if (fp == coreConfigFingerprint) return
        try {
            NetCoreBridge.loadConfig(withCtlCursor(ctx, json))
            coreConfigFingerprint = fp
            Log.i(TAG, "[CONFIG] core reloaded with the stored config before connecting")
        } catch (t: Throwable) {
            Log.w(TAG, "[CONFIG] core reload failed, keeping the loaded config: ${t.message}")
        }
    }

    /**
     * One line naming the subset this device was given, logged immediately
     * before each `start()`.
     *
     * Here and not in [reloadCoreConfigIfChanged], which returns early when the
     * config has not changed — the interesting case for this line is exactly a
     * second attempt on the SAME config. Null on a static answer, which is
     * normal and says nothing is wrong.
     */
    private fun logAssignment() {
        val ctx = MainActivity.instance?.applicationContext ?: return
        RemoteConfig.assignLine(RemoteConfig.current(ctx))?.let { Log.i(TAG, it) }
    }

    private fun provisionWgtcpInBackground() {
        val ctx = MainActivity.instance?.applicationContext ?: return
        viewModelScope.launch(Dispatchers.IO) {
            if (!WgtcpBridge.isLoaded) return@launch
            // 🚨 GATED ON THE CONFIG. This ran on EVERY LAUNCH, gated only on
            // the library being loaded, with no reference to `transport_order`
            // at all -- so every install asked the API for a WGTCP profile
            // whether or not its config would ever select WGTCP. Visible in the
            // cashier log as a steady stream of `wgtcp_api: profile ...
            // status="cached"`, and invisible from the client because nothing
            // fails: the profile is issued, cached, and never used. The cost is
            // a WireGuard keypair and a 10.x address per device, server side.
            //
            // `transportOrderFor` is the same call the connect path makes at
            // :1019 to choose a transport, so provisioning now happens only
            // where WGTCP can actually be selected -- and turning it off
            // anywhere becomes a config publish rather than a release.
            if (!ConfigManager.transportOrderFor(adPolicyRegion()).contains("wgtcp")) {
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
        // Paired here rather than at each call site: there are four connect
        // paths, and one forgetting the watchdog is a silently unmonitored
        // tunnel.
        startHealthWatchdog()
        // sessionRx restarts at 0 for this connect, so the watchdog's baseline
        // has to as well. Carrying the previous session's total over would make
        // the first tick see a hugely negative delta and probe when it need not,
        // or — after a reconnect that moved fewer bytes — a positive one and
        // skip a probe it should have run.
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

                // Bank a good session the moment it has genuinely held, rather
                // than at a disconnect that for this product may never come.
                // Idempotent per connection; the ASK is unchanged and still
                // only happens at the conservative moment it always did.
                tick++
                ReviewManager.onSessionTick(tick * 1000L)

                if (BuildConfig.FEATURE_LIVE_STATUS) {
                    LiveStatus.publish(null, LiveStatus.flow.value.copy(rxBytes = sessionRx, txBytes = sessionTx))
                }
                _trafficInfo.value = TrafficInfo(
                    rxRate = formatRate(rxDelta),
                    txRate = formatRate(txDelta),
                    totalRx = formatBytes(sessionRx),
                    totalTx = formatBytes(sessionTx)
                )

                // Every 60s: persist the lifetime total + report it (app is the meter).
                if (++tick % 60L == 0L) {
                    persistDataTotal(dataTotalBase + sessionRx + sessionTx)
                    reportUsageTotal()
                }
            }
        }
    }

    private fun stopTrafficMonitor() {
        trafficJob?.cancel()
        trafficJob = null
        // Fold this session into the persisted lifetime total + report final usage.
        dataTotalBase += sessionRx + sessionTx
        persistDataTotal(dataTotalBase)
        _dataTotal.value = dataTotalBase
        sessionRx = 0L; sessionTx = 0L
        // Races the teardown below it and may well be refused by the cashier
        // gate -- which costs nothing, because `used_mb` is CUMULATIVE: the
        // next session's first 60s report carries this session's bytes too.
        // Deliberately not reordered ahead of the transport stop; a blocking
        // network call on the disconnect path is the worse trade.
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

    /** Get formatted connection log for sharing and debugging */
    fun getConnectionLog(): String {
        return try {
            val statusJson = NetCoreBridge.getConnectionStatus()
            val sb = StringBuilder()
            sb.append("=== CONNECTION LOG ===\n")
            sb.append("Status: $statusJson\n")
            sb.append("Active carrier: ${NetCoreBridge.getActiveCarrier()}\n")
            sb.append("Is running: ${NetCoreBridge.isRunning()}\n")
            sb.toString()
        } catch (t: Throwable) {
            "Error formatting connection log: $t"
        }
    }

    /** Load server locations from ConfigManager */
    fun loadServerLocations() {
        // Carrier list is driven by the LIB (NetCoreBridge.listCarriers) — the
        // carriers netcore actually supports AND is configured for — not a
        // hardcoded host list. This guarantees the menu can never show a carrier
        // the .so doesn't build (no more ftp/mysql/gdocs/etc. dead entries).
        val fromLib = try {
            val arr = org.json.JSONArray(NetCoreBridge.listCarriers())
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
            Log.i(TAG, "Loaded ${fromLib.size} carriers from the core: ${fromLib.map { it.id }}")
            return
        }
        // Fallback only if the lib returned nothing (lib unavailable / no config):
        // config locations, never the old hardcoded carrier list.
        val disabled = ConfigManager.disabledTransports
        val locations = ConfigManager.serverLocations
            .filter { it.transport !in disabled }
            .map { loc -> ServerLocationData(loc.id, loc.name, loc.region, loc.transport, loc.desc) }
        _serverLocations.value = locations
        Log.i(TAG, "rsCarriers empty; fell back to ${locations.size} config locations")
    }

    /** Load config version from ConfigManager */
    /**
     * What config this device is running, for the footer.
     *
     * Prefers the SERVER's publish version and date over the JSON's own `version`
     * field. The latter is a schema version -- it reads the same across every
     * publish, so a footer showing it could not tell you whether a rollout had
     * reached the device, which is the only question it is ever asked.
     */
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
            // No server config has ever landed -- say so rather than showing the
            // bundled schema version as if it were a live one.
            else -> ConfigManager.configVersion.takeIf { it.isNotBlank() }
                ?.let { "$it (bundled)" }.orEmpty()
        }
        Log.i(TAG, "Config stamp: ${_configVersion.value}")
    }

    /** Toggle connection on/off */
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
     * guess. [TunnelVpnService.isRunning] flips true the instant establish() and
     * tun2socks succeed, so this returns as soon as the tunnel is real —
     * usually a few hundred ms rather than the flat 2s the old delay() always
     * paid regardless of device speed. Bounded: if the service never comes up we
     * return false after [timeoutMs] and let the caller proceed (the race then
     * fails cleanly) rather than hanging the connect. Cheap poll, no blocking IO,
     * so it is safe on any dispatcher.
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
     * rebinds it. Replaces a blind teardown sleep: a fresh connect must not bind
     * :1080 while a previous netcore/wgtcp carrier still holds it, or the new
     * tunnel races the old one for the port. Returns the instant the old listener
     * is gone (connection refused), so a clean teardown costs a few ms instead of
     * a flat wait; bounded so a stuck listener can never stall the reconnect.
     * Runs its own blocking probe on Dispatchers.IO, so it is safe to call from
     * any dispatcher.
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
                    false   // refused / no route → nobody is holding the port
                }
                if (!listening) return@withContext
                delay(30)
            }
        }
    }

    private fun connect() {
        Log.i(TAG, "========================================")
        // Named by what was actually selected. This said "NetCore"
        // unconditionally, so the log claimed NetCore on every WGTCP connect.
        Log.i(TAG, "[CONNECT] Starting connection (selected=${_selectedProtocol.value})")
        Log.i(TAG, "========================================")

        // paid_only: refuse BEFORE asking for the system VPN permission, or an
        // unpaid first run sees the OS consent dialog and then a paywall.
        // connectAuto() keeps its own check for the watchdog's reconnects.
        if (paidOnlyBlocked()) {
            Log.i(TAG, "[paid_only] not paid — paywall before the permission prompt")
            openPaywallAndLoad()
            return
        }
        if (paidOnlyStale() && System.currentTimeMillis() - staleCheckedAt > 10 * 60_000L) {
            viewModelScope.launch {
                // Play first (cheap), then the cashier directly, which sees web
                // and eSIM renewals. Only a definite "not paid" from the cashier
                // is persisted; anything unknown refuses this connect without
                // recording a verdict, and the paywall's Restore retries.
                val verdict = if (playSubOrUnknown() == true) true else askCashierNow()
                when (verdict) {
                    true -> {
                        staleCheckedAt = System.currentTimeMillis()
                        Log.i(TAG, "[paid_only] paid_until long past but entitlement confirmed — connecting")
                        connect()
                    }
                    // Connect runs with the tunnel down, so askCashierNow() is on
                    // the direct path here and upgrade-only: it never yields a
                    // persisted "not paid". Either way, refuse without recording.
                    false, null -> {
                        Log.i(TAG, "[paid_only] paid_until long past, entitlement unknown — paywall, nothing recorded")
                        openPaywallAndLoad()
                    }
                }
            }
            return
        }
        // Check VPN permission — must be done on main thread before starting service
        val activity = MainActivity.instance ?: return
        val vpnIntent = android.net.VpnService.prepare(activity)
        if (vpnIntent != null) {
            Log.i(TAG, "[CONNECT] VPN permission needed, launching dialog")
            // Store pending connect and request permission
            pendingConnect = true
            activity.requestVpnPermission(vpnIntent)
            return
        }

        connectAuto()
    }

    /** Called from MainActivity when VPN permission is granted */
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

    /**
     * Auto-connect: NetCore parallel race.
     * Races all entry points (NetCore carriers + legacy protocols from config).
     */
    private fun connectAuto() {
        // Refuse rather than connect-then-throttle. Placed HERE, not in the UI
        // tap, so the watchdog's unattended reconnect is covered too — otherwise
        // a user over quota would be silently reconnected every 45s.
        // paid_only: no free allowance exists, so an unpaid cid never connects.
        // Reads the PERSISTED paid flag, so a paying user connects before
        // /usage or register answers; the server's free quota is ignored.
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

        // The brand id for the MUX2 `app` field. ctl only puts it in the hello
        // when caps are on, so production (caps off) is unchanged; but it must
        // be set before any carrier dials.
        if (NetCoreBridge.isLoaded) runCatching { NetCoreBridge.setAppTag(BuildConfig.CASHIER_BRAND) }
        Log.i(TAG, "[AUTO] Starting ${if (_selectedProtocol.value == "wgtcp") "WGTCP" else "NetCore"}")
        _connectionState.value = ConnectionState.Connecting("auto")
        _autoConnectStatus.value = localizedStatus(R.string.status_finding_path)

        // Tracked so disconnect() can actually interrupt a connect in flight —
        // without this the job ran unmanaged and a disconnect during endpoint
        // selection could be overwritten by the connect finishing afterwards.
        autoConnectJob = viewModelScope.launch(Dispatchers.IO) {
            // CONFIG decides the transport, not a stored preference.
            //
            // `transport_order` was parsed and logged since it shipped and read
            // by nothing — the choice came from `selected_protocol`, a pref set
            // by a Settings picker that is now gone. That picker was never a
            // user-meaningful choice: netcore and wgtcp differ only in which
            // filtering they survive, which a user cannot know, and a wrong pick
            // is a tunnel that never connects.
            //
            // The pref is still honoured when a human deliberately set one (the
            // Dev menu), so on-device protocol testing keeps working; everyone
            // else follows the ladder for their region.
            val order = ConfigManager.transportOrderFor(adPolicyRegion())
            val pref = _selectedProtocol.value
            val rustProto = when {
                pref != "auto" && pref in order -> pref
                else -> order.firstOrNull() ?: "redsocket"
            }
            Log.i(TAG, "[AUTO] transport=$rustProto (order=$order pref=$pref)")

            // Tear down any stale VPN tun BEFORE Phase-0 AWG. A live tun's hev
            // Reconnects must start from a clean slate: a stale tun left behind by
            // a previous session collides with the new one.
            run {
                if (NetCoreBridge.isLoaded) { try { NetCoreBridge.stop() } catch (_: Throwable) {} }
                WgtcpBridge.stop()
                stopTunnelService()
                // Wait for the old carrier to actually release :1080 rather than
                // sleeping a flat 700ms and hoping. Returns in a few ms on a clean
                // teardown; bounded so a stuck listener can't stall the connect.
                awaitSocksPortFree()
            }

            // AmneziaWG removed. Its datapath was libawg.so and never passed
            // through our relay, so free-tier traffic on AWG could not be rate
            // limited — and it shipped 7.3 MB of native code for a second
            // transport. NetCore is the verified path and is now the only one;
            // an AWG-over-TCP client speaking netcore replaces it.

            // From here down is the NetCore family (the verified safety net).
            _connectionState.value = ConnectionState.Connecting(
                if (rustProto == "wgtcp") "WGTCP" else "NetCore"
            )

            // Start the VPN BEFORE the carrier, so protect() works for the
            // carrier's sockets. The alternative order existed only for the DNSTT
            // and GDocs carriers, which are gone -- their UDP/Google-frontend
            // sockets had to predate the tun. Nothing left needs it.

            _autoConnectStatus.value = localizedStatus(R.string.status_starting_vpn)
            val allowedApps = _allowedApps.value.toList()
            MainActivity.instance?.applicationContext?.let { ctx ->
                val intent = android.content.Intent(ctx, TunnelVpnService::class.java).apply {
                    action = TunnelVpnService.ACTION_START
                    // The port the core ACTUALLY bound, not the one we asked for.
                    // If 1080 was taken the core moved, and hev bridging to 1080
                    // would relay the whole tun into a closed port: the VPN comes
                    // up, the icon is on, and nothing loads. Discovered after
                    // start(), which is why this intent is built here and not
                    // before the transport is up.
                    putExtra("socks_port", activeSocksPort())
                    // OTA tunnel.udp (absent = false): QUIC/HTTP3 over the
                    // tunnel only once the fleet's exits carry UDP ASSOCIATE.
                    putExtra("supports_udp", ConfigManager.tunnelUdp)
                    // The list is meaningless without its direction, so the two
                    // always travel together. Sent even when empty, so the
                    // service is never left inferring the mode from the payload.
                    putExtra(TunnelVpnService.EXTRA_APP_LIST_MODE, _splitTunnelMode.value)
                    if (allowedApps.isNotEmpty()) {
                        putStringArrayListExtra("allowed_apps", ArrayList(allowedApps))
                    }
                    // Always attached, empty included: an absent extra means
                    // "fall back to prefs", and the two must not be able to say
                    // different things about a list the user just cleared.
                    putStringArrayListExtra(
                        TunnelVpnService.EXTRA_BYPASS_CIDRS,
                        ArrayList(_bypassCidrs.value)
                    )
                }
                // Same platform restriction as stopTunnelService() below, and the
                // same caller: the health watchdog reconnects with the app in the
                // background by design. A refusal here throws
                // ForegroundServiceStartNotAllowedException, and connectAuto's
                // coroutine body has no handler, so it would take the process
                // down instead of failing a connect.
                //
                // Reported as Failed rather than swallowed. §11's watchdog treats
                // Failed as dead and retries, which is the correct outcome: the
                // reconnect could not start now and should be attempted again,
                // and the next attempt after the user opens the app is in the
                // foreground and permitted.
                try {
                    ctx.startForegroundService(intent)
                } catch (t: Throwable) {
                    Log.e(TAG, "[VPN] foreground service start refused: $t")
                    _autoConnectStatus.value = null
                    _connectionState.value = ConnectionState.Failed("service-start-refused")
                    return@launch
                }
            }
            // Wait for the tun to actually come up instead of a flat 2s guess.
            // Returns as soon as TunnelVpnService.isRunning flips true (a few
            // hundred ms on most devices); bounded so a service that never
            // starts fails the race cleanly instead of hanging here.
            awaitTunReady()

            _autoConnectStatus.value = localizedStatus(R.string.connecting)

            // ── WGTCP: WireGuard over a TCP carrier ────────────────────────
            // Tried before NetCore when selected, and only when explicitly
            // selected — never on "auto". NetCore is the verified path for
            // ~691K installs and stays the default and the fallback; a new
            // transport earns traffic by being chosen, not by pre-empting the
            // one that works.
            //
            // Drop-in at the SOCKS5 level: it serves 127.0.0.1:1080 exactly as
            // netcore does, so TunnelVpnService and hev-socks5-tunnel above
            // it are unchanged. The tun was already started above, so
            // VpnService.protect() works for the carrier socket.
            if (rustProto == "wgtcp") {
                if (!WgtcpBridge.isLoaded) {
                    Log.w(TAG, "[WGTCP] libwgtcp_core not loaded — falling through to NetCore")
                } else {
                    val cfg = wgtcpConfigJson()
                    if (cfg == null) {
                        Log.w(TAG, "[WGTCP] no wgtcp config present — falling through to NetCore")
                    } else {
                        Log.i(TAG, "[WGTCP] starting")
                        val r = WgtcpBridge.start(cfg, DEFAULT_SOCKS_PORT)
                        Log.i(TAG, "[WGTCP] start returned: $r (loaded=${WgtcpBridge.isLoaded})")
                        // "ok" only means the TCP carrier came up. It does NOT
                        // mean the WireGuard session is usable: a carrier can be
                        // ESTABLISHED while the peer never completes a handshake,
                        // and then every packet is black-holed. Observed on-device
                        // 2026-08-26 -- the UI said Connected for 15 minutes while
                        // the browser returned ERR_CONNECTION_RESET on every page.
                        //
                        // So prove the data path before claiming success, using the
                        // same probe the watchdog uses. Without this the watchdog is
                        // the only detection and it needs 2 misses at 45s, i.e. up
                        // to 90s of a tunnel that looks up and carries nothing.
                        // Failing here falls through to NetCore, which is the
                        // safety net that already exists below.
                        val usable = r == "ok" && tunnelAlive()
                        if (usable) {
                            _autoConnectStatus.value = null
                            // Report the transport that actually carried the
                            // tunnel. This used to hardcode "NetCore", which
                            // made a WGTCP session indistinguishable from a
                            // NetCore one in the UI, in _activeTransport and in
                            // every log line -- so "wgtcp selected but NetCore
                            // connects" was impossible to tell from a real
                            // fallthrough. Hiding the transport made sense while
                            // it was undisclosed infrastructure; the settings
                            // sheet now names WGTCP to the user, so the label is
                            // already public and lying about it only costs us
                            // debuggability.
                            _activeTransport.value = "WGTCP"
                            _connectionState.value = ConnectionState.Connected(
                                transport = "WGTCP"
                            )
                            startTrafficMonitor()
                            Log.i(TAG, "[WGTCP] connected (data path verified)")
                            // A WGTCP session is a successful tunnel like any
                            // other. This was missing: only the three NetCore
                            // arms below reported one, so a user who always won
                            // on WGTCP could never reach the rating gate.
                            ReviewManager.onConnected()
                            // The cashier is reachable NOW and generally was not
                            // before: CashierApi defers every call once a tunnel
                            // profile exists, so this is where register + usage
                            // actually happen. Not an auto-connect -- it runs
                            // because a connect already succeeded.
                            syncEntitlement()
                            return@launch
                        }
                        // One fall-through for both shapes of failure, so the log
                        // distinguishes "never started" from "started but carries
                        // nothing" -- they have completely different causes and
                        // conflating them is what made this hard to diagnose.
                        Log.w(TAG, if (r == "ok")
                            "[WGTCP] carrier up but no data path — falling through to NetCore"
                        else
                            "[WGTCP] failed ($r) — falling through to NetCore")
                        // The toast deliberately names NEITHER transport. It used
                        // to name both transports by name, which put two protocol
                        // names on screen in 39 languages -- §0 forbids a protocol
                        // name on any surface a censor can read, and a store listing
                        // or a screenshot is exactly such a surface. The distinction
                        // that matters for diagnosis is in the log line above, which
                        // is where it was always actually read from.
                        showToast(localizedStatus(R.string.status_finding_path))
                        WgtcpBridge.stop()
                    }
                }
            }

            val statusJob = viewModelScope.launch(Dispatchers.IO) {
                while (true) {
                    delay(400)
                    try {
                        val status = NetCoreBridge.getRaceStatus()
                        if (status.isNotEmpty()) {
                            val msg = localizedStatus(R.string.connecting)
                            withContext(Dispatchers.Main) {
                                _autoConnectStatus.value = msg
                            }
                        }
                    } catch (_: Throwable) { break }
                }
            }

            // The netcore tunnel needs libnetcore.so. If it failed to
            // load (missing ABI/symbol), don't call into it — that would throw
            // UnsatisfiedLinkError and crash. Fail the connect cleanly instead.
            if (!NetCoreBridge.isLoaded) {
                statusJob.cancel()
                _autoConnectStatus.value = null
                Log.e(TAG, "[nc] libnetcore not loaded — cannot start tunnel")
                _connectionState.value = ConnectionState.Failed("tunnel_unavailable")
                showToast(localizedStatus(R.string.status_failed, "tunnel unavailable"))
                return@launch
            }

            // The user picks a REGION; netcore decides the protocol within it,
            // so the protocol filter is always "auto".
            // Endpoint pool follows entitlement: premium_entries (paid) vs the
            // shared pool. Must precede start() — it is read when the list is built.
            MainActivity.instance?.applicationContext?.let { reloadCoreConfigIfChanged(it) }
            NetCoreBridge.setPremium(_isPaid.value)
            applyRateLimit()
            NetCoreBridge.setRegion(entitledRegion())
            NetCoreBridge.setProtocolFilter("auto")
            Log.i(TAG, "[nc] Region: ${_selectedRegion.value} (protocol: auto — netcore decides)")

            // CONNECTING -> TESTING -> CONNECTED.
            //
            // A handshake only proves the POP answered. The app used to call
            // that "Connected", which is how a user ends up staring at a green
            // dial while nothing resolves. One real request has to come back
            // before the claim is made.
            //
            // A failed check marks THAT endpoint bad in the core, so the next
            // attempt plans around it. The core decides when to start over:
            // its bad-set filter never empties the plan, so once every endpoint
            // has been burned the full list returns rather than nothing.
            //
            // The cap is here, not in the core, because a fault BELOW the
            // endpoint -- a torn-down tun, a captive portal -- fails every
            // endpoint identically, and walking the whole fleet to discover
            // that costs the user a minute per connect and hammers the POPs.
            var displayName: String? = null
            var lastError = "connection check failed"
            // One re-plan per connect. A second would mean asking for a third
            // subset while the user watches a spinner, and the first re-plan
            // already costs a full select budget; the next connect picks up
            // where this one left off because `k` is persisted.
            var replanned = false

            for (attempt in 0 until VERIFY_ATTEMPTS) {
                if (attempt > 0) {
                    Log.i(TAG, "[nc] endpoint failed its check — trying the next one " +
                        "(${attempt + 1}/$VERIFY_ATTEMPTS)")
                    try { NetCoreBridge.stop() } catch (t: Throwable) {
                        Log.w(TAG, "[nc] stop before retry: $t")
                    }
                    // The core rebinds :1080; racing it leaves the retry with no
                    // SOCKS port and a check that fails for the wrong reason.
                    awaitSocksPortFree()
                }

                // What this device was ASSIGNED, logged at plan time so a user
                // report can be joined to the server's cfg_assign row. Absent
                // on a static answer, which is normal and says nothing is wrong.
                logAssignment()

                val result = NetCoreBridge.start(DEFAULT_SOCKS_PORT)  // already on Dispatchers.IO
                Log.i(TAG, "[nc] Result: $result")

                // ok: / dns: / legacy: are all "a carrier came up" — they differ
                // only in how it was reached, which is not a user-visible fact.
                val up = result.startsWith("ok:") ||
                    result.startsWith("dns:") ||
                    result.startsWith("legacy:")
                if (!up) {
                    lastError = result.removePrefix("err:")
                    Log.e(TAG, "[nc] Failed: $lastError")
                    // THE WHOLE PLAN failed — every endpoint in this device's
                    // subset, not one of them. That is the only evidence worth
                    // a new assignment, so it is the only thing that bumps `k`.
                    // A single endpoint failing inside a plan is already
                    // handled by the bad-set and the next-endpoint loop.
                    val ctx = MainActivity.instance?.applicationContext
                    if (RemoteConfig.isPlanFailure(lastError) && !replanned && ctx != null) {
                        replanned = true
                        // Through the tunnel is not an option here: there is no
                        // tunnel. Direct only, and a failure just fails the
                        // connect the way it did before v3 existed.
                        if (RemoteConfig.onPlanFailed(ctx)) {
                            reloadCoreConfigIfChanged(ctx)
                            continue
                        }
                    }
                    break   // nothing came up at all; retrying endpoints is not the fix
                }

                _autoConnectStatus.value = null
                _connectionState.value = ConnectionState.Verifying

                if (tunnelCarriesTraffic()) {
                    // Clears any previous bad mark, so an endpoint that recovers
                    // comes back on its own.
                    try { NetCoreBridge.markActiveCarrier(true) } catch (_: Throwable) {}
                    // The same evidence clears the subset-retry counter: this
                    // assignment carries traffic, so the next connect starts
                    // from k=0 instead of walking the fleet it already escaped.
                    MainActivity.instance?.applicationContext?.let { RemoteConfig.resetRetry(it) }
                    // We deliberately do NOT surface the winning carrier/protocol or
                    // its server IP (e.g. "http-2001:db8:5::10") — that leaks infra
                    // detail. Everything on this path is one neutral label, and
                    // that label is deliberately not a protocol or product name
                    // (§0: no protocol name on any surface a censor can read).
                    displayName = "Standard"
                    Log.i(TAG, "[nc] verified (carrier hidden from UI)")
                    // A connect is the best moment to refresh: we know a path
                    // works, and if the direct fetch is blocked the tunnel is up
                    // to carry it.
                    refreshConfigInBackground(viaTunnel = true)
                    break
                }

                try { NetCoreBridge.markActiveCarrier(false) } catch (_: Throwable) {}
                lastError = "connection check failed"
            }

            statusJob.cancel()

            if (displayName != null) {
                _autoConnectStatus.value = null
                _activeTransport.value = displayName
                _connectionState.value = ConnectionState.Connected(transport = displayName)
                startTrafficMonitor()
                showToast(localizedStatus(R.string.vpn_on))
                ReviewManager.onConnected()
                // See the WGTCP arm: the deferred cashier calls land here.
                syncEntitlement()
            } else {
                _autoConnectStatus.value = null
                // Leave the tunnel stopped rather than connected-but-useless: the
                // whole point of the gate is not to claim a tunnel that is not one.
                try { NetCoreBridge.stop() } catch (_: Throwable) {}
                Log.e(TAG, "[nc] no endpoint passed the check: $lastError")
                _connectionState.value = ConnectionState.Failed(lastError)
                showToast(localizedStatus(R.string.status_failed, lastError))
            }
        }
    }

    private fun disconnect() {
        autoConnectJob?.cancel()
        autoConnectJob = null

        Log.i(TAG, "[DISCONNECT] Stopping NetCore + VPN")

        // Only a USER disconnect stops the watchdog. stopTrafficMonitor() is
        // also called by disconnectThenReconnect(), so stopping it there meant a
        // failed reconnect killed the thing meant to retry it — observed
        // on-device: radios off, reconnect failed, radios back, nothing.
        stopHealthWatchdog()
        stopTrafficMonitor()
        // Clear any interstitial that's showing OR still resolving — an ad must
        // never pop up once the user has disconnected (it's a connect-time ad).
        dismissAd()

        // Trigger review prompt if the session was meaningful. Deferred, not
        // fired here — see maybeAskForReview().
        maybeAskForReview()

        _connectionState.value = ConnectionState.Disconnected
        _activeTransport.value = null
        _autoConnectStatus.value = null

        // Stop NetCore (guarded: native call must not crash if .so absent)
        if (NetCoreBridge.isLoaded) {
            try { NetCoreBridge.stop() } catch (t: Throwable) { Log.w(TAG, "[nc] stop failed: $t") }
        }

        // Stop WGTCP unconditionally, not only when it was the winner: a
        // fall-through leaves a partially started tunnel, and a reconnect that
        // left it holding :1080 would block NetCore from binding.
        WgtcpBridge.stop()

        // Stop the tunnel service.
        stopTunnelService()

        showToast(localizedStatus(R.string.status_disconnected))
    }

    /**
     * Send TunnelVpnService its stop intent, and DO NOT CRASH doing it.
     *
     * `startService` from a backgrounded app throws
     * `BackgroundServiceStartNotAllowedException` on Android 12+. Both callers
     * run on Dispatchers.IO inside viewModelScope, and the health watchdog
     * drives one of them with the app in the background by design (§11: only a
     * user disconnect stops it, and a failed reconnect must keep retrying). So
     * the throw landed on a coroutine with no handler and took the process
     * down. Seen four times in dropbox across three days on this device, on
     * com.redvpn.android v23 and vpn.dollarvpn.app v1, both 6.7.2, always
     * `act=…STOP_VPN … caps=---------`. VpnBridge.stopVpn already caught it;
     * these two sites never did.
     *
     * The isRunning check is the real fix and the catch is the backstop. When
     * the service is up the app holds a foreground service and the start is
     * permitted; when it is down there is nothing to stop and the intent was
     * only ever a no-op that could throw. The race between the two is exactly
     * what the catch covers.
     */
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

    /** Fetch exit IP via SOCKS5 proxy using pure Kotlin */
    /** Select protocol/server — auto-reconnects if currently connected */
    /**
     * Top-level protocols the user may pick, as (id, label). "auto" is rendered
     * separately by the settings sheet, so it is not in this list.
     *
     * WGTCP is offered ONLY when it could actually carry a tunnel: the .so is
     * loaded AND the config yields a complete peer (enabled + all five of
     * private_key / peer_public_key / endpoint / address / gateway). Offering a
     * protocol that cannot connect strands the user on a dead choice, which is
     * the same reason the carrier chips are driven by rsCarriers() rather than a
     * hardcoded list. An empty private_key therefore hides the chip rather than
     * showing one that always fails.
     */
    fun availableProtocols(): List<Pair<String, String>> {
        val out = mutableListOf("redsocket" to "Standard")
        val wgtcpUsable = WgtcpBridge.isLoaded && wgtcpAvailable()
        if (wgtcpUsable) out.add("wgtcp" to "WGTCP")
        Log.i(TAG, "[protocol] choices=${out.map { it.first }} " +
            "(wgtcpLoaded=${WgtcpBridge.isLoaded} wgtcpConfigured=${wgtcpAvailable()})")
        return out
    }

    fun selectProtocol(protocol: String) {
        val previousProtocol = _selectedProtocol.value
        Log.i(TAG, "[SELECT] User selected protocol: $protocol (was: $previousProtocol)")
        _selectedProtocol.value = protocol
        MainActivity.instance?.applicationContext
            ?.getSharedPreferences("rostam_prefs", android.content.Context.MODE_PRIVATE)
            ?.edit()?.putString("selected_protocol", protocol)?.apply()

        // If already connected and protocol changed, disconnect then reconnect
        viewModelScope.launch(Dispatchers.Main) {
            val currentState = _connectionState.value
            if (protocol != previousProtocol) {
                if (currentState is ConnectionState.Connected) {
                    Log.i(TAG, "[SWITCH] Protocol changed while connected, reconnecting...")
                    showToast("Switching to ${protocolDisplayName(protocol)}...")
                    disconnectThenReconnect()
                } else if (currentState is ConnectionState.Connecting) {
                    val connectingFor = currentState.transport
                    if (connectingFor != protocol) {
                        Log.i(TAG, "[SWITCH] Protocol changed while connecting, reconnecting...")
                        showToast("Switching to ${protocolDisplayName(protocol)}...")
                        disconnectThenReconnect()
                    }
                }
            }
        }
    }

    /** Disconnect current tunnel and reconnect with the newly selected protocol */
    /**
     * Ask for a rating, but only once the user has actually settled on a
     * disconnected app.
     *
     * Two things make firing straight from disconnect() wrong. First,
     * selectRegion() calls disconnect() then connect() as the two halves of a
     * region switch, so a rating card would land on top of a reconnect the user
     * just asked for. Second, ActivityHolder.currentActivity is set in onCreate
     * and cleared in onDestroy, so it is a liveness signal, not a foreground
     * one — a disconnect from outside the UI would otherwise spend our one ask
     * on a card nobody is looking at. The short wait settles the first and
     * hasWindowFocus() answers the second.
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
        // The watchdog decided a live tunnel was dead and is tearing it down
        // under the user. That is a bad time, and it never passes through
        // ConnectionState.Failed, so the collector in init would miss it.
        ReviewManager.onConnectionFailed()
        stopTrafficMonitor()

        _connectionState.value = ConnectionState.Connecting(_selectedProtocol.value)
        _activeTransport.value = null

        // Stop NetCore (guarded: native call must not crash if .so absent)
        if (NetCoreBridge.isLoaded) {
            try { NetCoreBridge.stop() } catch (t: Throwable) { Log.w(TAG, "[nc] stop failed: $t") }
        }

        // Stop WGTCP unconditionally, not only when it was the winner: a
        // fall-through leaves a partially started tunnel, and a reconnect that
        // left it holding :1080 would block NetCore from binding.
        WgtcpBridge.stop()

        viewModelScope.launch {
            // Wait for the just-stopped carrier to release :1080 before the
            // reconnect races to rebind it, instead of a flat 500ms. connect()'s
            // own teardown re-checks this and returns instantly when already free.
            awaitSocksPortFree()
            connect()
        }
    }

    private fun protocolDisplayName(protocol: String): String {
        val loc = _serverLocations.value.find { it.id == protocol }
        if (loc != null) return "${loc.name} (${loc.desc})"
        return when (protocol) {
            "auto" -> "Auto Select"
            "stealth" -> "Stealth"
            else -> protocol
        }
    }

    /** Set app theme: "auto", "light", "dark" */
    fun setTheme(mode: String) {
        _themeSetting.value = mode
        ActivityHolder.currentActivity
            ?.getSharedPreferences("rostam_settings", android.content.Context.MODE_PRIVATE)
            ?.edit()?.putString("theme", mode)?.apply()
    }

    // ── UI language (Settings → Language). "auto" = follow timezone (Option B). ──
    private val _languageSetting = MutableStateFlow(
        MainActivity.instance
            ?.getSharedPreferences("rostam_prefs", android.content.Context.MODE_PRIVATE)
            ?.getString("ui_lang", "auto") ?: "auto"
    )
    val languageSetting: StateFlow<String> = _languageSetting.asStateFlow()

    /** Set the UI language. Writes the pref and recreates the activity so
     *  attachBaseContext re-applies the locale app-wide. "auto" → follow timezone. */
    fun setUiLanguage(code: String) {
        _languageSetting.value = code
        val ctx = MainActivity.instance ?: return
        ctx.getSharedPreferences("rostam_prefs", android.content.Context.MODE_PRIVATE)
            .edit().putString("ui_lang", code).apply()
        ctx.recreate()
    }

    /** Collect debug log (blocking — call from IO thread or coroutine) */
    fun collectDebugLog(): String {
        return try {
            if (NetCoreBridge.isLoaded) NetCoreBridge.getConnectionStatus() else """{"error":"lib-unloaded"}"""
        } catch (t: Throwable) {
            """{"error":"$t"}"""
        }
    }

    /** Capture recent logcat output for our process */
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

    /** Get app version string */
    private fun getAppVersion(): String {
        return try {
            val ctx = MainActivity.instance?.applicationContext ?: return "unknown"
            val pInfo = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
            // getLongVersionCode() is API 28; minSdk 23. On API 23-27 it throws
            // NoSuchMethodError (an Error), which the old catch(Exception) missed
            // -> crash (issue ae2a163..., ~104 users). Gate it, and catch Throwable.
            val code = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P)
                pInfo.longVersionCode
            else @Suppress("DEPRECATION") pInfo.versionCode.toLong()
            "${pInfo.versionName} ($code)"
        } catch (t: Throwable) { "unknown" }
    }

    /** Get network type description */
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

    /** Build human-readable debug report */
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
        sb.appendLine("NetCore status: $stateJson")
        sb.appendLine("Active carrier: ${if (NetCoreBridge.isLoaded) try { NetCoreBridge.getActiveCarrier() } catch (t: Throwable) { "n/a" } else "lib-unloaded"}")
        sb.appendLine("Config version: ${ConfigManager.configVersion}")
        if (RedShield.available) sb.appendLine("Protect Mode: ${FirstParty.summary()}")
        sb.appendLine()

        // Network diagnostics (probes DHCP, public DNS, server IPs, DNSTT)
        sb.appendLine("--- Network Reachability ---")
        try {
            sb.appendLine(if (NetCoreBridge.isLoaded) NetCoreBridge.runDiagnostics() else "lib-unloaded")
        } catch (t: Throwable) {
            sb.appendLine("Diagnostics failed: $t")
        }
        sb.appendLine()

        sb.appendLine("--- Recent Logs (logcat) ---")
        sb.appendLine(logcat)

        return sb.toString()
    }

    /**
     * Encrypt debug report with AES-256-GCM.
     */
    private fun encryptReport(plaintext: String): ByteArray {
        val key = javax.crypto.spec.SecretKeySpec(DEBUG_REPORT_KEY, "AES")
        val iv = ByteArray(12).also { java.security.SecureRandom().nextBytes(it) }
        val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, key, javax.crypto.spec.GCMParameterSpec(128, iv))
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return iv + ciphertext
    }

    /** Post debug log to diagnostic API (fire-and-forget) */
    /** Share a file via Android share intent using FileProvider */
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

    /** Share debug log — captures logcat, builds encrypted report, shares as file */
    fun shareDebugLog() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val logcat = captureLogcat()
                val stateJson = collectDebugLog()
                val report = buildDebugReport(logcat, stateJson)
                val ctx = MainActivity.instance?.applicationContext ?: return@launch

                // Primary: post the log straight to our diagnostic server, routed
                // through the tunnel (the app is excluded from the VPN, and direct
                // egress is censored in Iran — the tunnel is the reliable path).
                if (sendDebugToServer(report)) {
                    withContext(Dispatchers.Main) { showToast("Debug log sent to support ✓") }
                    return@launch
                }

                // Fallback (Telegram not configured or send failed): encrypted-file share sheet.
                val encrypted = encryptReport(report)
                val timestamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
                    .format(java.util.Date())
                val file = java.io.File(ctx.cacheDir, "rostam-debug-$timestamp.enc")
                file.writeBytes(encrypted)
                ctx.cacheDir.listFiles()?.filter {
                    it.name.startsWith("rostam-debug-") &&
                    System.currentTimeMillis() - it.lastModified() > 3600_000
                }?.forEach { it.delete() }

                withContext(Dispatchers.Main) { shareFile(ctx, file) }
            } catch (e: Exception) {
                Log.e(TAG, "shareDebugLog error: ${e.message}")
            }
        }
    }

    /**
     * Post the debug report straight to our diagnostic server. Routed through the
     * netcore SOCKS5 (127.0.0.1:1080) when the tunnel is up — the app is excluded
     * from the VPN and direct egress is censored in Iran, so the tunnel is the
     * reliable path. Returns true on a 2xx.
     */
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
            // Same host as the rest of the API, so the report follows the fronts the
            // config fetch already uses; cashier-01 routes the path to the ingest.
            val url = java.net.URL("${com.rostamvpn.android.billing.CashierApi.BASE}/api/diagnostic")
            val tunnelUp = NetCoreBridge.isLoaded && (try { NetCoreBridge.isRunning() != 0 } catch (_: Throwable) { false })
            val conn = (if (tunnelUp) {
                url.openConnection(java.net.Proxy(java.net.Proxy.Type.SOCKS,
                    java.net.InetSocketAddress("127.0.0.1", activeSocksPort())))
            } else url.openConnection()) as java.net.HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 15000
            conn.readTimeout = 25000
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("X-Api-Key", "rstm-c-7e3b1a9f5d2c")
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

    // What the split-tunnel config looked like when the sheet was opened. Both
    // halves matter: flipping the mode with the same list inverts the routing
    // just as completely as editing the list does.
    private var appsOnSheetOpen: Set<String> = emptySet()
    private var modeOnSheetOpen: String = TunnelVpnService.MODE_INCLUDE
    private var cidrsOnSheetOpen: List<String> = emptyList()

    /** Open app selection sheet — loads apps in background */
    fun openAppSelection() {
        appsOnSheetOpen = _allowedApps.value.toSet()
        modeOnSheetOpen = _splitTunnelMode.value
        cidrsOnSheetOpen = _bypassCidrs.value
        _showAppSelection.value = true
        viewModelScope.launch {
            loadInstalledApps()
            loadAllowedApps()
            // Only safe once installedApps is populated — see pruneUninstalledApps.
            pruneUninstalledApps()
        }
    }

    fun dismissAppSelection() {
        _showAppSelection.value = false
        val changed = _allowedApps.value != appsOnSheetOpen ||
            _splitTunnelMode.value != modeOnSheetOpen ||
            _bypassCidrs.value != cidrsOnSheetOpen
        if (changed && _connectionState.value is ConnectionState.Connected) {
            // Routing is fixed at VpnService.Builder.establish() time, so a live
            // tunnel keeps the old rules until it is rebuilt. Without this the
            // change appears to do nothing until the next connect, and the user
            // concludes the feature is broken — which is how we got here.
            Log.i(TAG, "Split tunnel config changed while connected — reconnecting to apply")
            disconnectThenReconnect()
        }
    }

    /**
     * Switch between "route only these" and "route all but these".
     *
     * The list is deliberately kept across the switch: the apps a user picked are
     * the apps they care about, and re-picking them after every flip is the kind
     * of friction that makes people give up on the feature. The include-only
     * [TunnelVpnService.NONE_SENTINEL] is dropped on the way into exclude mode,
     * where an empty list already means "exclude nothing".
     */
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

    /**
     * Drop packages the user has since uninstalled.
     *
     * Without this a stale entry degrades the config forever: in exclude mode it
     * is a silent no-op, and in include mode every one of them is a
     * NameNotFoundException at establish() time. Membership is tested against
     * PackageManager directly, NOT against [installedApps] — that list holds only
     * launcher-category apps, so pruning against it would delete perfectly valid
     * entries for installed apps that have no launcher icon.
     */
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

                // Pruning an include list down to empty would flip its meaning
                // from "only these apps" to "all apps" — an uninstall must not
                // silently start tunnelling everything. The sentinel preserves
                // the user's intent. In exclude mode empty is already correct.
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

    /** Load installed apps via PackageManager (pure Kotlin) */
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
            // Exclude mode is literal: a checked app is an app that bypasses the
            // tunnel, and an empty set means nothing bypasses it. No sentinel and
            // no "empty means all" inversion — that ambiguity is what the include
            // list needed the sentinel for, and it does not exist here.
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

    // ── RedShield per-app detail (Route outside / Pause) ────────────────────
    // Both reuse the split-tunnel plumbing: the list above for "outside", and
    // a RedShield list (ShieldAppControls) the service applies only while the
    // system "Block connections without VPN" is on. Routing is fixed at
    // establish() time, so a change while connected rebuilds the tunnel.

    /** Does [pkg] bypass the tunnel under the current split-tunnel settings? */
    fun appOutsideTunnel(pkg: String): Boolean {
        val apps = _allowedApps.value
        return if (_splitTunnelMode.value == TunnelVpnService.MODE_EXCLUDE) pkg in apps
        else apps.contains(TunnelVpnService.NONE_SENTINEL) || (apps.isNotEmpty() && pkg !in apps)
    }

    fun setAppOutsideTunnel(pkg: String, outside: Boolean) {
        if (appOutsideTunnel(pkg) == outside) return
        val apps = _allowedApps.value
        val next: Set<String> = when {
            _splitTunnelMode.value == TunnelVpnService.MODE_EXCLUDE -> if (outside) apps + pkg else apps - pkg
            // Include mode with no list is the full tunnel, which is exactly
            // exclude mode with nothing listed: switch, then list this one.
            apps.isEmpty() -> { _splitTunnelMode.value = TunnelVpnService.MODE_EXCLUDE; setOf(pkg) }
            outside -> (apps - pkg).ifEmpty { setOf(TunnelVpnService.NONE_SENTINEL) }
            else -> (apps - TunnelVpnService.NONE_SENTINEL) + pkg
        }
        _allowedApps.value = next
        saveAllowedApps(next)
        applyRoutingNow()
    }

    /** Rebuild a live tunnel so per-app routing changes take effect. */
    fun applyRoutingNow() {
        if (_connectionState.value is ConnectionState.Connected) disconnectThenReconnect()
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
     * Pre-select only essential apps (browsers + messaging) for low-bandwidth modes.
     *
     * Include-mode only. The same list under exclude mode would push the browser
     * and Telegram OUT of the tunnel — precisely the apps a censored user needs
     * inside it — so the UI hides the button there and this refuses the call.
     * An exclude-mode equivalent would be a curated list of domestic banking and
     * government apps; that is per-region data and belongs in the OTA config, not
     * in Kotlin (CLAUDE.md §6b bans region conditionals here).
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
    /** "None" — uncheck everything. Include: nothing tunnelled. Exclude: everything is. */
    fun deselectAllApps() {
        if (_splitTunnelMode.value == TunnelVpnService.MODE_EXCLUDE) {
            // Empty exclude list == no split tunnelling at all. It must NOT
            // become the sentinel, which would mean the opposite.
            _allowedApps.value = emptySet()
            saveAllowedApps(emptySet())
            return
        }
        val none = setOf(TunnelVpnService.NONE_SENTINEL)
        _allowedApps.value = none
        saveAllowedApps(none)
    }

    /**
     * Persist the list AND the direction it is read in, in one edit.
     *
     * Writing the mode only when the user *changes* it is not enough, and the
     * failure is silent and exactly the bug this feature exists to fix. A new
     * install starts in exclude mode by default; if the user accepts that default
     * and simply checks an app, nothing ever writes the mode pref. The next cold
     * start then finds a non-empty list with no stored mode — indistinguishable
     * from a legacy include-mode install — and [TunnelVpnService.savedMode]
     * correctly-but-disastrously answers INCLUDE. The user's one excluded banking
     * app becomes the only app in the tunnel. Caught on-device, not in review.
     *
     * Storing both together means the migration heuristic only ever sees a
     * genuinely legacy install: a list written by a build that had no modes.
     */
    /**
     * Add a destination prefix to the bypass list.
     *
     * Returns the canonical form on success, or null when the text is not a
     * literal address/prefix — the caller shows the error, because a rule that
     * silently did not take is worse than no rule at all. Stored canonical
     * (`1.2.3.4/24` becomes `1.2.3.0/24`) so the user sees the network that will
     * actually be routed rather than what they typed.
     */
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

    fun clearBypassCidrs() {
        if (_bypassCidrs.value.isEmpty()) return
        _bypassCidrs.value = emptyList()
        saveBypassCidrs(emptyList())
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

    /** Restore the split-tunnel list AND its direction from SharedPreferences on startup */
    private fun restoreAllowedApps() {
        try {
            val ctx = MainActivity.instance?.applicationContext ?: return
            // Read the mode through the service helper so the ViewModel and the
            // service can never disagree about the migration rule.
            _splitTunnelMode.value = TunnelVpnService.savedMode(ctx)
            val json = ctx.getSharedPreferences("rostam_settings", android.content.Context.MODE_PRIVATE)
                .getString("allowed_apps_json", "[]") ?: "[]"
            val arr = JSONArray(json)
            val apps = mutableSetOf<String>()
            for (i in 0 until arr.length()) {
                apps.add(arr.getString(i))
            }
            _allowedApps.value = apps
            // Read through the same service helper as the mode and the app list,
            // so no start path can disagree about what the user configured.
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

    fun openEsim() = openEsimAt(null)

    /** The eSIM store on one page of the site, e.g. `/esim/jp`; null = its home. */
    fun openEsimAt(path: String?) {
        if (!com.rostamvpn.android.BuildConfig.FEATURE_ESIM) return
        _esimPath.value = path
        _showEsim.value = true
    }

    /**
     * Where "account" goes. The direct build of a brand with a web account has a
     * sign-in screen; every Play build (and China traffic, HANDOFF decision #3)
     * gets the paywall, which already carries the account code and Restore.
     * The Play flavour's WebAccount has no screen at all (src/play).
     */
    fun openAccount() {
        val ctx = MainActivity.instance?.applicationContext
        if (ctx != null && WebAccount.available(ctx)) _showWebAccount.value = true else openPaywallAndLoad()
    }

    fun dismissWebAccount() {
        _showWebAccount.value = false
    }

    /**
     * A verified web link (WebLinks.kt). Whatever overlay was up is closed
     * first, so the link lands on its own screen and not underneath another.
     */
    fun openWebLink(link: WebLink) {
        _showEsim.value = false
        _showWebAccount.value = false
        _showPaywall.value = false
        _showAbout.value = false
        _showPrivacyPolicy.value = false
        _showOpenSource.value = false
        _showDataUsage.value = false
        _showSettings.value = false
        when (link) {
            is WebLink.Esim -> {
                // The tabs treatment reads EsimNav; the drawer brands read esimPath.
                com.rostamvpn.android.ui.screens.EsimNav.open(com.rostamvpn.android.ui.screens.EsimRoute(dest = link.dest))
                openEsimAt(link.dest?.let { "/esim/$it" })
            }
            is WebLink.Number -> {
                com.rostamvpn.android.ui.screens.EsimNav.open(
                    com.rostamvpn.android.ui.screens.EsimRoute(dest = link.dest, number = link.dest != null))
                // e-sim.net has no number index (/number is a 404 there): without
                // an id, the destination list, where each country leads to its plans.
                openEsimAt(link.dest?.let { "/number/$it" } ?: "/esim")
            }
            WebLink.Account -> openAccount()
            WebLink.Buy -> openPaywallAndLoad()
            // Both change which plan this device runs on, and a link can come
            // from anyone (a message, another app, a web page), so the user
            // confirms first. A cold link must never switch accounts silently.
            is WebLink.Claim, is WebLink.Open -> _linkToConfirm.value = link
            WebLink.Home -> Unit
        }
    }

    /** A `/c/claim` or `/app/open` link waiting for the user's Continue. */
    private val _linkToConfirm = MutableStateFlow<WebLink?>(null)
    val linkToConfirm: StateFlow<WebLink?> = _linkToConfirm.asStateFlow()

    fun confirmLink() {
        when (val l = _linkToConfirm.value) {
            is WebLink.Claim -> claimAccountCode(l.code)
            is WebLink.Open -> claimHandoff(l.token)
            else -> Unit
        }
        _linkToConfirm.value = null
    }

    fun cancelLink() {
        _linkToConfirm.value = null
    }

    /**
     * `/app/open?t=`: the website, signed in, handed this device a one-time
     * token. Claiming it links the cid to that web account exactly like the
     * email code does. Not a sign-in field, so it exists on Play too, like the
     * `rostam://c/claim` link.
     */
    fun claimHandoff(token: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val r = com.rostamvpn.android.billing.CashierApi.handoffClaim(token, cid())
            val link = r.link
            if (link == null) {
                Log.i(TAG, "[ACCOUNT] hand-off claim refused or unreachable: ${r.status}")
                // A refused token (expired, used, forged) is not a network
                // problem; saying "check your connection" would send the user
                // looking for the wrong fault.
                _toastMessage.value = localizedStatus(
                    if (r.status in 400..499) R.string.restore_invalid else R.string.tabs_load_failed)
                return@launch
            }
            onAccountLinked(link)
        }
    }

    /**
     * Account deletion (cashier `POST /api/v1/account/delete`). Only on the
     * server's yes (or a 404, nothing left to erase) is anything forgotten
     * here: the account email and the cid, with a fresh cid minted at once so
     * nothing re-registers the erased one. A Play subscription belongs to Play
     * and is NOT cancelled; the entitlement sync re-derives it under the new cid.
     */
    fun deleteAccount(done: (Boolean) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val ctx = MainActivity.instance?.applicationContext
            val st = com.rostamvpn.android.billing.CashierApi.accountDelete(cid())
            if (ctx == null || !(st in 200..299 || st == 404)) {
                _toastMessage.value = localizedStatus(R.string.tabs_load_failed)
                withContext(Dispatchers.Main) { done(false) }
                return@launch
            }
            WebAccountPrefs.setEmail(ctx, null)
            com.rostamvpn.android.billing.CashierIdentity.resetIdentity(ctx)
            com.rostamvpn.android.billing.CashierIdentity.ensure(ctx)
            _toastMessage.value = localizedStatus(R.string.acct_deleted)
            syncEntitlement()
            withContext(Dispatchers.Main) { done(true) }
        }
    }

    /** This device was just linked to a web account (email code or website hand-off). */
    fun onAccountLinked(link: com.rostamvpn.android.billing.CashierApi.AccountLink) {
        MainActivity.instance?.applicationContext?.let { WebAccountPrefs.setEmail(it, link.email) }
        // Grant only, as claimAccountCode does: an account without a
        // subscription must not take away what this device bought itself.
        if (link.entitled) {
            setPaid(true)
            _showPaywall.value = false
        }
        // The email, always when there is one: it is the only way the user can
        // tell WHICH account a link signed this device into.
        _toastMessage.value = if (link.email.isNotEmpty()) localizedStatus(R.string.tabs_signed_in_as, link.email)
        else localizedStatus(R.string.restore_ok)
        // Server truth (tier, paid_until, usage) for the newly linked cid.
        syncEntitlement()
    }

    fun dismissEsim() {
        _showEsim.value = false
        _esimPath.value = null
    }

    fun openPaywall() {
        _showPaywall.value = true
    }

    fun dismissPaywall() {
        _showPaywall.value = false
    }

    /** Called when a QR config deep link is received */
    /**
     * Apply a scanned QR config code.
     *
     * The scanned payload is a signed BOOTSTRAP -- endpoints only -- because a QR
     * cannot hold the ~17 KB sealed config. RemoteConfig verifies the signature
     * and merges it; this only reports the outcome. Endpoints take effect on the
     * next connect, so an already-connected user is not dropped by scanning.
     */
    fun applyQrConfig(payload: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val ctx = MainActivity.instance?.applicationContext ?: return@launch
            val failure = RemoteConfig.applyQrBootstrap(ctx, payload)
            _toastMessage.value = if (failure == null) {
                ConfigManager.loadFromJson(RemoteConfig.current(ctx))
                // This is the one mid-session config mutation, and it can change
                // `freedom_regions` under a running app — so every tier gate has
                // to be re-derived from the new table, not the one we booted on.
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

    // ── Localized status messages ──────────────────────────────────
    /**
     * Status text for toasts and the connect button.
     *
     * These used to be a `when (Locale.getDefault().language)` per string, in
     * Kotlin. That meant every new language needed a code change, and any it
     * missed fell back to English silently -- so the app was never fully
     * translated in a locale nobody had remembered to add here. They are string
     * resources now, which is the one place translations already live.
     *
     * Read through the ACTIVITY, not the application: MainActivity.attachBaseContext
     * applies the user's language override, and application resources do not carry it.
     */
    private fun localizedStatus(resId: Int, arg: String? = null): String {
        val ctx = MainActivity.instance ?: return ""
        return if (arg != null) ctx.getString(resId, arg) else ctx.getString(resId)
    }

    // ────────────────────────────────────────────────────────────────────────
    // Monetization — Play Billing (outside CN) + cashier Alipay/WeChat (CN) +
    // free-tier interstitial ads. `cid` is the entitlement identity. No secrets
    // ship in the app; all verification/brokering is server-side.
    // ────────────────────────────────────────────────────────────────────────

    /** Play subscription product ids — must match the ACTIVE products in that
     *  brand's Play Console exactly. Supplied by the `brand` flavor
     *  (BuildConfig.SUB_PRODUCT_IDS) because each brand is its own Console app with
     *  its own SKUs. The cashier verifies whatever product_id the purchase carries
     *  (expiry comes from Play), so these need not equal its web plan slugs. */
    private val subProductIds: List<String> =
        BuildConfig.SUB_PRODUCT_IDS.split(",").map { it.trim() }.filter { it.isNotEmpty() }

    private val billing: com.rostamvpn.android.billing.PlayBilling? by lazy {
        MainActivity.instance?.applicationContext?.let {
            com.rostamvpn.android.billing.PlayBilling(it).apply { onPurchase = { p -> handlePlayPurchase(p) } }
        }
    }

    private val _isPaid = MutableStateFlow(loadPaidFlag())
    val isPaid: StateFlow<Boolean> = _isPaid.asStateFlow()

    /**
     * Server-truth subscription detail, for the Account screen.
     *
     * `isPaid` alone answers "may this device use Premium", which is all the
     * connect path needs. It cannot answer "what did I buy and when does it
     * renew" -- the question a subscriber actually opens the app to ask, and the
     * one a support request starts with. The cashier already returns all three
     * fields on /cid/register; they were being parsed and dropped.
     */
    data class SubscriptionInfo(val tier: String, val product: String?, val paidUntil: String?)

    private val _subscription = MutableStateFlow<SubscriptionInfo?>(null)
    val subscription: StateFlow<SubscriptionInfo?> = _subscription.asStateFlow()

    private val _subProducts = MutableStateFlow<List<com.android.billingclient.api.ProductDetails>>(emptyList())
    val subProducts: StateFlow<List<com.android.billingclient.api.ProductDetails>> = _subProducts.asStateFlow()

    private val _cnPlans = MutableStateFlow<List<com.rostamvpn.android.billing.CashierApi.Plan>>(emptyList())
    val cnPlans: StateFlow<List<com.rostamvpn.android.billing.CashierApi.Plan>> = _cnPlans.asStateFlow()

    // USD plans for the direct-build Paymentwall rail.
    private val _webPlans = MutableStateFlow<List<com.rostamvpn.android.billing.CashierApi.Plan>>(emptyList())
    val webPlans: StateFlow<List<com.rostamvpn.android.billing.CashierApi.Plan>> = _webPlans.asStateFlow()

    private val _payRail = MutableStateFlow(com.rostamvpn.android.billing.PayRail.GOOGLE_PLAY)
    val payRail: StateFlow<com.rostamvpn.android.billing.PayRail> = _payRail.asStateFlow()

    // True while the paywall is fetching plans; lets the UI show a real
    // "unavailable" state instead of an endless "Loading plans…" spinner.
    private val _plansLoading = MutableStateFlow(false)
    val plansLoading: StateFlow<Boolean> = _plansLoading.asStateFlow()

    private val _cnPayOrder = MutableStateFlow<com.rostamvpn.android.billing.CashierApi.PayOrder?>(null)
    val cnPayOrder: StateFlow<com.rostamvpn.android.billing.CashierApi.PayOrder?> = _cnPayOrder.asStateFlow()

    private val _adUrl = MutableStateFlow<String?>(null)
    val adUrl: StateFlow<String?> = _adUrl.asStateFlow()

    private val _adDebug = MutableStateFlow<String?>(null)
    val adDebug: StateFlow<String?> = _adDebug.asStateFlow()

    private val _payMethods = MutableStateFlow<com.rostamvpn.android.billing.CashierApi.PayMethods?>(null)
    val payMethods: StateFlow<com.rostamvpn.android.billing.CashierApi.PayMethods?> = _payMethods.asStateFlow()


    /** Free-data status for this cid (read-only; the node meters, the app reads). */
    private val _usage = MutableStateFlow<com.rostamvpn.android.billing.CashierApi.UsageStatus?>(null)
    val usage: StateFlow<com.rostamvpn.android.billing.CashierApi.UsageStatus?> = _usage.asStateFlow()

    /** Pull free-data status; nudge the paywall when over quota (not paid, not Iran). */
    /**
     * The cashier's usage answer, corrected for a freedom region.
     *
     * The app and the cashier do NOT decide freedom the same way and cannot
     * today: `/cid/register` sends `cid/brand/tz/ifa/lmt` and no country, so the
     * server decides from the TIMEZONE alone while the app now weighs SIM,
     * network, timezone and keyboard. An Iranian on a foreign SIM abroad, or any
     * device the keyboard rescued, therefore comes back metered — and the main
     * screen dutifully drew "1.02 GB remaining" at a user the app had already
     * decided owes nothing. A freedom user must see no meter at all.
     *
     * Local policy wins here on purpose: this is the side that knows every
     * signal. It only ever grants access, never withdraws it, so a cashier that
     * says `unlimited` is still believed. The real fix is for both sides to
     * agree on one owner — tracked on the cashier handoff board.
     */
    private fun asFreedom(u: com.rostamvpn.android.billing.CashierApi.UsageStatus) =
        if (isFreedomRegion()) u.copy(overQuota = false, unlimited = true) else u

    fun refreshUsage() {
        viewModelScope.launch(Dispatchers.IO) {
            val u = asFreedom(com.rostamvpn.android.billing.CashierApi.usage(cid()) ?: return@launch)
            _usage.value = u
            applyRateLimit()
            // `!_isPaid.value` as well as the server's `paid`: between a
            // successful purchase and the cashier reflecting it (a queued
            // /iap/verify retry, plain replication lag) the server still says
            // free, and this popped "Free data used up — upgrade for more" plus
            // the full paywall at a user who had already been charged and whose
            // own UI said Premium was active. The local flag is the one that
            // survives a cashier outage and the one every other gate uses.
            if (u.overQuota && !u.paid && !_isPaid.value && !isFreedomRegion() && !ConfigManager.paidOnly) {
                _toastMessage.value = localizedStatus(R.string.usage_used_up)
                openPaywallAndLoad()
            }
        }
    }

    /** Report cumulative used_mb to the cashier (app-meter model, last-write-wins),
     *  then refresh status. Skipped for Iran (free/unlimited — no metering). */
    private fun reportUsageTotal() {
        if (isFreedomRegion()) return
        val usedMb = (dataTotalBase + sessionRx + sessionTx) / 1_000_000L
        viewModelScope.launch(Dispatchers.IO) {
            // The POST's own response IS the fresh status -- no second GET.
            val reported = com.rostamvpn.android.billing.CashierApi.reportUsage(cid(), usedMb)
            val u = asFreedom(reported ?: return@launch)
            _usage.value = u
            applyRateLimit()
            // See refreshUsage(): the local paid flag has to agree before we nag.
            if (u.overQuota && !u.paid && !_isPaid.value && !ConfigManager.paidOnly) {
                _toastMessage.value = localizedStatus(R.string.usage_used_up)
                openPaywallAndLoad()
                // A hard-stop brand also ENDS the session, not just the speed.
                // Without this the allowance is advisory: the tunnel it was
                // metering keeps running until the user happens to disconnect.
                if (quotaHardStopped()) {
                    Log.i(TAG, "[quota] hard stop — ending the live session")
                    disconnect()
                }
            }
        }
    }

    private fun prefs() = MainActivity.instance?.applicationContext
        ?.getSharedPreferences("rostam_prefs", android.content.Context.MODE_PRIVATE)

    // ── Lifetime data-usage total (local, persisted; works in every region) ──
    private var dataTotalBase: Long = prefs()?.getLong("data_total", 0L) ?: 0L
    private val _dataTotal = MutableStateFlow(dataTotalBase)   // bytes rx+tx, all sessions
    val dataTotal: StateFlow<Long> = _dataTotal.asStateFlow()
    private fun persistDataTotal(v: Long) { prefs()?.edit()?.putLong("data_total", v)?.apply() }


    private val _showDataUsage = MutableStateFlow(false)
    val showDataUsage: StateFlow<Boolean> = _showDataUsage.asStateFlow()
    fun openDataUsage() { _showDataUsage.value = true }
    fun dismissDataUsage() { _showDataUsage.value = false }

    private fun loadPaidFlag(): Boolean = prefs()?.getBoolean("paid", false) ?: false

    /**
     * Push the free-tier speed limit into the core.
     *
     * Limited only when ALL of: throttling enabled in config, the user is not
     * paying, this is not a freedom region (those are donor-funded and never
     * metered), and the server says they are over quota. Anything unknown or
     * unreachable resolves to UNLIMITED — throttling someone by accident is far
     * worse than failing to throttle them.
     */
    /**
     * Is this user in the reduced-speed tier right now?
     *
     * One predicate, used by BOTH the rate limiter and the transport gate — if
     * they could disagree we would either throttle someone who kept AWG (so the
     * limit does nothing) or strip AWG from someone we never throttle.
     *
     * Freedom regions are excluded here, deliberately and permanently: Iran and
     * Russia are donor-funded, never metered, and must keep the full transport
     * ladder. Unknown state resolves to NOT throttled.
     */
    private fun inReducedSpeedTier(): Boolean {
        val cfg = ConfigManager.throttleConfig
        return cfg.enabled &&
            cfg.freeKbps > 0 &&
            !_isPaid.value &&
            !isFreedomRegion() &&
            (_usage.value?.overQuota == true)
    }

    /**
     * Push the tier policy into the core: speed limit + CDN access.
     *
     * CDN is allowed for paying users and for freedom regions. It is denied to
     * METERED FREE users only — CloudFront egress costs per byte, and that
     * cohort is the one we are cost-controlling. Freedom regions must keep it:
     * when Iran blocks the direct IPs the CDN fronts are the only path that
     * works, and that is exactly who the fallback exists for.
     */
    /** True when this user is actually being slowed right now. Mirrors the real
     *  decision rather than re-deriving it from over-quota: throttling also
     *  needs the config switch, so over-quota alone would be a false claim. */
    private val _reducedSpeed = MutableStateFlow(false)
    val reducedSpeed: StateFlow<Boolean> = _reducedSpeed.asStateFlow()

    /**
     * The free tier's speed ceiling, in kbps — the SAME number `applyRateLimit`
     * pushes into the core, read from the same place.
     *
     * Exposed so the UI can NAME it. "Reduced speed" is a label a user cannot
     * evaluate; "Limited to 2 Mbps" is a fact they can, and it is the difference
     * between the free and paid tiers being legible or merely asserted. It is
     * read from config rather than written into a string so that changing the
     * throttle in OTA config can never leave the UI quoting a stale figure —
     * a number the app states about itself has to come from the thing that
     * enforces it.
     *
     * 0 when throttling is off, in which case no speed claim is made at all.
     */
    val freeTierKbps: Int
        get() = ConfigManager.throttleConfig.let { if (it.enabled) it.freeKbps else 0 }

    /**
     * True when the free tier STOPS at the allowance instead of slowing down.
     *
     * A hard-stop brand still carries `free_kbps`, because one config block
     * feeds both, so anything reading the speed alone will happily promise a
     * slow lane that does not exist. Read this first.
     */
    val quotaStops: Boolean
        get() = ConfigManager.throttleConfig.hardStop

    /**
     * True when an ad can ever reach this user: ads on for the brand AND the
     * region not "off". Both halves matter — a no-ads brand and a donor region
     * with ads off arrive at the same answer by different routes, and "No ads"
     * is not a thing to sell to either of them.
     */
    val adsEverShown: Boolean
        get() = ConfigManager.adsConfig.modeFor(adPolicyRegion()) !=
            ConfigManager.AdsConfig.MODE_OFF

    private fun applyRateLimit() {
        // Re-derive the UI gate from the SAME inputs as the policy gates, here,
        // because this function is already called from every site where a tier
        // input can change: connect, setPaid, and both usage refreshes.
        //
        // `premiumAvailable` used to be computed once in a property initialiser,
        // and the only write in the tree was the Dev Tools override — so in a
        // RELEASE build it was frozen for the whole process lifetime while every
        // policy gate re-read isFreedomRegion() fresh. A SIM swap, a roaming
        // handover or a flight therefore started metering, throttling and
        // ad-serving someone while the upgrade banner and the drawer's Premium
        // entry stayed hidden and mayPickRegion() kept handing them the paid
        // region picker. Mirrored: a user who had just become entitled to the
        // product for free was still shown the upgrade banner and was locked out
        // of the region picker.
        //
        // Recomputed BEFORE the isLoaded guard — the UI gate has to be right
        // even when there is no core to push a rate limit into.
        _premiumAvailable.value = premiumAvailableNow()
        applyBlockingEntitlement()
        if (!NetCoreBridge.isLoaded) return
        val cfg = ConfigManager.throttleConfig
        val limited = inReducedSpeedTier()
        _reducedSpeed.value = limited
        val cdnAllowed = _isPaid.value || isFreedomRegion()
        try {
            NetCoreBridge.setCdnAllowed(cdnAllowed)
        } catch (t: Throwable) {
            Log.w(TAG, "setCdnAllowed failed: ${t.message}")
        }
        Log.i(TAG, "[throttle] decide limited=$limited (cfgEnabled=${cfg.enabled} kbps=${cfg.freeKbps} " +
            "paid=${_isPaid.value} freedom=${isFreedomRegion()} over=${_usage.value?.overQuota})")
        try {
            NetCoreBridge.setRateLimitKbps(if (limited) cfg.freeKbps else 0)
        } catch (t: Throwable) {
            Log.w(TAG, "setRateLimitKbps failed: ${t.message}")
        }
    }

    /**
     * Turn destination filtering on or off in the core.
     *
     * Entitlement: **premium OR a freedom region**. Iran and Russia get the
     * full product free by policy (CLAUDE.md §9), and this is part of the full
     * product, so withholding it there would charge them in features what the
     * project decided not to charge them in money. It is also the population
     * that needs it most: a residential-proxy SDK sells an Iranian user's own
     * IP as an exit for somebody else's traffic, on a connection they pay for
     * by the megabyte and under a legal regime where a stranger's traffic
     * leaving their address is not a small problem.
     *
     * ⚠️ Filtering stays OFF regardless until a signed list exists. The fetch
     * is plain HTTP inside the tunnel, so the origin hop is unauthenticated and
     * a tampered list is censorship delivered by us to 500,000 people. Ship the
     * list sealed (`cfgseal`) before enabling `blocklist.url`.
     */
    /**
     * The user's Guardian master switch (Rules screen). Default on, so a brand
     * that never shows the control keeps today's behaviour exactly.
     */
    // EFFECTIVE state: for a Protect Mode brand the switch reads on only once
    // the disclosure was accepted (ProtectConsent), so the UI never shows "on"
    // while nothing is being checked.
    private fun consentOk(): Boolean = !RedShield.available ||
        (MainActivity.instance?.applicationContext?.let { ProtectConsent.consented(it) } ?: false)
    private val _guardianOn = MutableStateFlow((prefs()?.getBoolean("guardian_on", true) ?: true) && consentOk())
    val guardianOn: StateFlow<Boolean> = _guardianOn.asStateFlow()

    fun setGuardianOn(on: Boolean) {
        // Turning Protect Mode on without consent opens the disclosure instead;
        // the switch stays off until Continue (answerProtectConsent).
        if (on && !consentOk()) { ProtectConsent.ask(); return }
        prefs()?.edit()?.putBoolean("guardian_on", on)?.apply()
        _guardianOn.value = on
        applyBlockingEntitlement()
    }

    /** The Protect Mode disclosure's answer: Continue = consent and on, Turn off = off. */
    fun answerProtectConsent(accepted: Boolean) {
        val ctx = MainActivity.instance?.applicationContext ?: return
        ProtectConsent.record(ctx, accepted)
        prefs()?.edit()?.putBoolean("guardian_on", accepted)?.apply()
        _guardianOn.value = accepted
        applyBlockingEntitlement()
        RedShieldEngine.resync(ctx)
        if (accepted) RedShieldEngine.scoreNow(ctx)
    }

    /** "Not now" on the VPN disclosure: no system dialog, stay disconnected. */
    fun onVpnDisclosureDeclined() {
        pendingConnect = false
        if (_connectionState.value !is ConnectionState.Connected) _connectionState.value = ConnectionState.Disconnected
    }

    // ── Guardian store + control-stream messages (tabs treatment, any brand
    // whose OTA turns protection or messaging on) ─────────────────────────────

    /** Refused since local midnight, from GuardStore. */
    private val _blockedToday = MutableStateFlow(0)
    val blockedToday: StateFlow<Int> = _blockedToday.asStateFlow()

    /** Last `protection` message: {enabled, list_version, feed_date}. */
    data class ProtectionInfo(val enabled: Boolean, val listVersion: String, val feedDate: String)
    private val _protectionInfo = MutableStateFlow<ProtectionInfo?>(null)
    val protectionInfo: StateFlow<ProtectionInfo?> = _protectionInfo.asStateFlow()

    /** Last operator/brand notice for the quiet row on Connect. */
    data class Notice(val title: String, val body: String, val url: String?)
    private val _notice = MutableStateFlow<Notice?>(null)
    val notice: StateFlow<Notice?> = _notice.asStateFlow()
    fun dismissNotice() { _notice.value = null }

    private var guardJob: kotlinx.coroutines.Job? = null

    /**
     * The exit acknowledged the caps on THIS tunnel (the core's `caps` note
     * when the 0xC0 stream opened, or a `protection` message). Reset on every
     * connect. The status line says "Protected" only with this, never from the
     * OTA flag alone (contract review 2026-10-12).
     */
    private val _protectionAcked = MutableStateFlow(false)
    val protectionAcked: StateFlow<Boolean> = _protectionAcked.asStateFlow()

    // The two halves of "Protected" (contract addendum 2026-10-13): the exit
    // acknowledged the caps on this tunnel, AND its most recent `protection`
    // message says a bundle is loaded (enabled:true). The exit sends
    // enabled:false while it has no bundle. A caps ack alone is "Connected".
    @Volatile private var capsAcked = false
    @Volatile private var exitProtecting = false
    private fun recomputeProtected() { _protectionAcked.value = capsAcked && exitProtecting }
    private fun resetProtected() {
        capsAcked = false; exitProtecting = false; recomputeProtected()
        // The list version and feed date belong to the exit of the tunnel that
        // just ended; the next one reports its own.
        _protectionInfo.value = null
    }

    /**
     * While connected: copy the core's recent ring into GuardStore (so the
     * timeline survives restarts) and handle queued control-stream messages.
     * Both are no-ops on a tunnel without the caps.
     */
    private fun runGuardPoller(connected: Boolean) {
        guardJob?.cancel()
        if (!connected) {
            // Process what the core queued in the last poll window (a notice or
            // an entitlement change must not be lost), THEN reset: a stale
            // `caps` note must not mark the NEXT tunnel protected (possibly
            // against a legacy exit), so the reset comes after, not before.
            if (NetCoreBridge.isLoaded && (ConfigManager.protectionEnabled || ConfigManager.messagingEnabled))
                runCatching { handleNotes(NetCoreBridge.drainNotes()) }
            resetProtected()
        }
        // The Guardian store and the rules rewrite exist only for a brand that
        // ships protection AND has it switched on remotely. Every other brand
        // never creates guard.db, never rewrites user rules on connect and
        // never stores attempted hostnames on disk (review 2026-10-13).
        val guarded = BuildConfig.FEATURE_PROTECTION && ConfigManager.protectionEnabled
        // Control-stream messages flow on any brand whose OTA turns messaging
        // (or protection) on; with both off the core never opens the stream.
        val messaging = ConfigManager.protectionEnabled || ConfigManager.messagingEnabled
        if (!connected || !(guarded || messaging)) return
        val ctx = MainActivity.instance?.applicationContext ?: return
        guardJob = viewModelScope.launch(Dispatchers.IO) {
            val store = if (guarded) GuardStore.get(ctx) else null
            if (store != null) {
                _blockedToday.value = runCatching { store.refusedToday() }.getOrDefault(0)
                // The core keeps categories_off in memory only; give it the
                // stored mask on every connect so the first {rules} frame
                // carries it.
                com.rostamvpn.android.ui.screens.sendCategoriesOff(
                    com.rostamvpn.android.ui.screens.categoryOffBits(ctx))
            }
            while (NetCoreBridge.isLoaded) {
                if (store != null) runCatching {
                    val now = System.currentTimeMillis()
                    val o = org.json.JSONObject(NetCoreBridge.getBlockStats())
                    val arr = o.optJSONArray("recent")
                    val rows = (0 until (arr?.length() ?: 0)).map {
                        val e = arr!!.getJSONObject(it)
                        GuardStore.Row(
                            host = e.optString("host"), port = e.optInt("port"),
                            bits = e.optInt("bits"),
                            reason = e.optString("reason").ifEmpty { e.optString("category") },
                            // Per-app attribution needs the original uid, which
                            // is lost at hev (Stage 0 finding); null until then.
                            uid = null,
                            ts = now - e.optLong("secs_ago") * 1000,
                            refused = e.optBoolean("refused", true),
                        )
                    }
                    store.addAll(rows)
                    _blockedToday.value = store.refusedToday()
                }
                if (messaging) runCatching { handleNotes(NetCoreBridge.drainNotes()) }
                delay(5000)
            }
        }
    }

    /**
     * The config the core loads, plus the persisted mailbox cursor as
     * `ctl_cursor` ({since, broadcast_seq}). Only present once a control stream
     * has delivered something, so for every brand without messaging the core
     * receives exactly the config it did before.
     */
    private fun withCtlCursor(ctx: android.content.Context, raw: String): String {
        val json = withPreferredHosts(ctx, raw)
        val c = ctx.getSharedPreferences("rostam_prefs", android.content.Context.MODE_PRIVATE)
            .getString("ctl_cursor", null) ?: return json
        return runCatching { org.json.JSONObject(json).put("ctl_cursor", org.json.JSONObject(c)).toString() }
            .getOrDefault(json)
    }

    /**
     * Pin by ISP: give matching entries an explicit priority (lower dials
     * first) so the addresses netflow shows working on this mobile network
     * are tried first. Two sources, the config winning per host:
     *  - OTA `carrier_priorities`: {"43211": {"192.0.2.10": 4, ...}}, keyed
     *    by MCC+MNC (43211 MCI, 43235 Irancell, 43220 RighTel), used only when
     *    the phone is on mobile data (on Wi-Fi the SIM says nothing about the ISP);
     *  - OTA `wifi_priorities`: {"IR": {"host": 15, ...}}, keyed by ISO country,
     *    used only on Wi-Fi/Ethernet (fixed-line ISPs, which the SIM cannot name);
     *  - BuildConfig.PREFER_HOSTS ("host=prio,..."), for carrier-tuned tester builds.
     * The config still decides which entries exist; this only reorders them.
     */
    private fun withPreferredHosts(ctx: android.content.Context, json: String): String {
        // The build-time list is measured on Iranian networks; elsewhere (Russia:
        // v6 dead) its v4/v6 alternation would cost attempts, so it is Iran-only.
        val tmIr = ctx.getSystemService(android.content.Context.TELEPHONY_SERVICE) as? android.telephony.TelephonyManager
        val inIran = tmIr?.simCountryIso.equals("ir", true) || tmIr?.networkCountryIso.equals("ir", true) ||
            java.util.TimeZone.getDefault().id.let { it == "Asia/Tehran" || it == "Iran" }
        val want = BuildConfig.PREFER_HOSTS.takeIf { inIran }.orEmpty().split(',').mapNotNull {
            val kv = it.trim().split('=')
            if (kv.size == 2) kv[1].toIntOrNull()?.let { p -> kv[0].trim() to p } else null
        }.toMap().toMutableMap()
        runCatching {
            // Same onWifi/carrier/country derivation the v3 config query uses,
            // from one definition: the two must not be able to disagree about
            // which network this phone is on.
            val onWifi = RemoteConfig.onWifi(ctx)
            val op = RemoteConfig.configCarrier(
                onWifi,
                (ctx.getSystemService(android.content.Context.TELEPHONY_SERVICE) as? android.telephony.TelephonyManager)
                    ?.networkOperator,
            )
            val m = org.json.JSONObject(json).optJSONObject("carrier_priorities")?.optJSONObject(op)
            if (!onWifi && m != null) for (h in m.keys()) want[h] = m.getInt(h)
            val cc = RemoteConfig.configCountry(
                tmIr?.simCountryIso, tmIr?.networkCountryIso, java.util.TimeZone.getDefault().id,
            )
            want.putAll(wifiPins(json, onWifi, cc))
        }
        if (want.isEmpty()) return json
        val (out, n) = applyPins(json, want) ?: return json
        Log.i(TAG, "[nc] preferred hosts applied to $n entries")
        return out
    }

    /** Downstream control messages (PROTECTION-DESIGN section 9). */
    private fun handleNotes(json: String) {
        val arr = org.json.JSONArray(json)
        for (i in 0 until arr.length()) {
            val m = arr.optJSONObject(i) ?: continue
            val p = m.optJSONObject("payload") ?: org.json.JSONObject()
            when (m.optString("type")) {
                // acked:false = the pool redialled onto a POP that did not
                // acknowledge the caps (legacy for that carrier).
                "caps" -> {
                    capsAcked = p.optBoolean("acked", false)
                    if (!capsAcked) exitProtecting = false
                    recomputeProtected()
                }
                "entitlement" -> if (!BuildConfig.PAID_ONLY) {
                    // Every other brand: exactly the 3b1d83a behaviour.
                    val tier = p.optString("tier")
                    val state = p.optString("state")
                    val until = parsePaidUntil(p.optString("paid_until"))
                    val paidState = if (state.isNotEmpty()) state.equals("paid", true)
                        else tier.isNotEmpty() && !tier.equals("free", true)
                    val active = paidState && (until == null || until > System.currentTimeMillis())
                    _subscription.value = SubscriptionInfo(tier, _subscription.value?.product, p.optString("paid_until"))
                    viewModelScope.launch(Dispatchers.Main) { setPaid(active) }
                } else {
                    // PAID_ONLY. {tier, paid_until, state}: the ledger's state is
                    // the truth (paid | free | throttle | banned); tier is a plan
                    // name ("pro") and is never compared to "paid".
                    val tier = p.optString("tier")
                    val state = p.optString("state")
                    // No state = no statement about entitlement. Never a revoke.
                    if (state.isEmpty()) continue
                    entitlementAnswered = true
                    val until = parsePaidUntil(p.optString("paid_until"))
                    val active = state.equals("paid", true) && (until == null || until > System.currentTimeMillis())
                    // The subscription (paid_until) BEFORE setPaid, so the lapse
                    // timer setPaid arms reads this message's date, not the old one.
                    _subscription.value = SubscriptionInfo(tier, _subscription.value?.product, p.optString("paid_until"))
                    persistPaidUntil(p.optString("paid_until"))
                    viewModelScope.launch(Dispatchers.IO) {
                        // A downgrade needs a second opinion: every non-paid state
                        // (free, throttle, banned) keeps paid while Play shows a sub
                        // or cannot answer (unknown, never "no sub").
                        val keep = !active && _isPaid.value && playSubOrUnknown() != false
                        if (keep) Log.i(TAG, "[PAY] entitlement note says $state but Play shows an active sub or could not answer — keeping paid")
                        else kotlinx.coroutines.withContext(Dispatchers.Main) { setPaid(active) }
                    }
                }
                // The core's mailbox cursor; handed back via loadConfig on the
                // next launch so a restart does not replay every broadcast.
                "cursor" -> MainActivity.instance?.applicationContext
                    ?.getSharedPreferences("rostam_prefs", android.content.Context.MODE_PRIVATE)
                    ?.edit()?.putString("ctl_cursor", p.toString())?.apply()
                "protection" -> {
                    exitProtecting = p.optBoolean("enabled", false)
                    recomputeProtected()
                    _protectionInfo.value = ProtectionInfo(
                        exitProtecting, p.optString("list_version"), p.optString("feed_date"),
                    )
                }
                "notice" -> {
                    val n = Notice(p.optString("title"), p.optString("body"), p.optString("url").ifEmpty { null })
                    _notice.value = n
                    postNotice(n)
                }
                "rules" -> {
                    // An echo from another device. Applied only when it differs;
                    // the core also refuses to push an identical frame back.
                    val incoming = org.json.JSONObject().put("allow", p.optJSONArray("allow") ?: org.json.JSONArray())
                        .put("deny", p.optJSONArray("deny") ?: org.json.JSONArray()).toString()
                    if (incoming != NetCoreBridge.getUserRules()) NetCoreBridge.setUserRules(incoming)
                }
            }
        }
    }

    /** A system notification only where notifications are already allowed;
     *  no new permission. The quiet row on Connect shows it regardless. */
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

    /** Protection is on for this session: fleet flag AND the user's switch. */
    val protectionActive: Boolean get() = ConfigManager.protectionEnabled && _guardianOn.value

    private fun applyBlockingEntitlement() {
        if (!NetCoreBridge.isLoaded) return
        // The pref, not _guardianOn: this can run from init, before that
        // property (declared later in the class) is initialised.
        // RedShield (BuildConfig.PROTECTION_NAME): the core's filter is always
        // on so the user's own Block rules apply to everyone; the master switch
        // governs scoring, automatic denies and profiles (RedShieldEngine), as
        // on iOS. Every other brand keeps the entitlement gate.
        val entitled = com.rostamvpn.android.RedShield.available || ((_isPaid.value || isFreedomRegion()) &&
            (prefs()?.getBoolean("guardian_on", true) ?: true))
        try {
            NetCoreBridge.setBlockingEnabled(entitled)
        } catch (e: Throwable) {
            // Declared in Kotlin but not exported by the installed .so until the
            // core is rebuilt and copied into jniLibs. Caught so the mismatch is
            // a log line rather than an UnsatisfiedLinkError on the connect path.
            Log.w(TAG, "setBlockingEnabled unavailable: ${e.message}")
        }
    }

    private fun setPaid(paid: Boolean) {
        // DEBUG assume-paid: never downgrade, so a local exit's register/usage
        // answer cannot revoke during the end-to-end run. Release: no-op.
        if (!paid && debugAssumePaid()) { _isPaid.value = true; return }
        val wasPaid = _isPaid.value
        prefs()?.edit()?.putBoolean("paid", paid)?.apply()
        _isPaid.value = paid
        // paid_only: losing entitlement ends the session, whoever decided it
        // (register, the lapse timer, a refund). Other brands keep a free tier.
        if (wasPaid && !paid && ConfigManager.paidOnly &&
            _connectionState.value !is ConnectionState.Disconnected) {
            Log.i(TAG, "[paid_only] entitlement lost while connected — disconnecting")
            disconnect()
            openPaywallAndLoad()
        }
        schedulePaidLapse()
        // Premium is ad-free from the instant it is granted, including an
        // interstitial that is already ON SCREEN. Without this, a card that was
        // presented a moment before the purchase completed stayed up over a user
        // who had just paid never to see one — dismissAd() was only ever called
        // from a user disconnect.
        if (paid) dismissAd()
        applyRateLimit()
        applyBlockingEntitlement()
        // Takes effect on the next connect (the endpoint list is built in start()).
        if (NetCoreBridge.isLoaded) {
            try { NetCoreBridge.setPremium(paid) } catch (e: Throwable) {
                Log.w(TAG, "setPremium failed: ${e.message}")
            }
        }
        pushPaywallSignals()
    }

    /**
     * Hand Adapty the cid, the brand, the region and whether this user has paid.
     *
     * Called from here because this is where the entitlement is decided, and
     * again from the head of [syncEntitlement] — a cashier that cannot be
     * reached never reaches [setPaid], and that is the population whose profile
     * most needs the region on it.
     *
     * Region and freedom come from THIS class on purpose; see
     * `AdaptyService.sendSignals` for why reading them there would be wrong.
     * No-op on a brand with no remote paywall (src/noAdapty).
     */
    private fun pushPaywallSignals() {
        val ctx = MainActivity.instance?.applicationContext ?: return
        val region = try {
            com.rostamvpn.android.billing.RegionDetector.detectCountry(ctx, null)
        } catch (_: Throwable) {
            null
        }
        AdaptyService.sendSignals(ctx, region, isFreedomRegion(), _isPaid.value)
    }


    private fun cid(): String = MainActivity.instance?.applicationContext
        ?.let { com.rostamvpn.android.billing.CashierIdentity.cid(it) } ?: ""

    /**
     * "Freedom regions" get 100% free, unmonetized access: no metering, no
     * paywall, no commercial ads / GAID (sponsor "brought to you by" cards still
     * show — that's the funding model). Today the group is just Iran; the set is
     * here so more regions can be added in one place (keep it in sync with the
     * cashier's CASHIER_FREEDOM_REGIONS; an OTA-driven list is a later upgrade).
     *
     * Monetizing these regions is an OFAC/sanctions problem and a GAID-tracked ad
     * on a surveilled user is exactly the risk we won't take.
     *
     * Detection is deliberately GENEROUS — the region timezone OR SIM/network
     * counts. Erring toward free is safe: we never wrongly monetize, and the worst
     * case (a traveler on that timezone) is negligible lost revenue.
     */
    /**
     * Which freedom region this device is in, or null. Delegates to the ONE
     * derivation in `RegionDetector.freedomRegion` — see its doc for the
     * precedence and why both the SIM and the timezone count. Config-driven;
     * nothing here knows a region by name.
     *
     * Callers that need the CODE (ad policy) and callers that need the BOOLEAN
     * (metering, throttle, CDN, paywall) now read the same answer. They used to
     * disagree: `isFreedomRegion()` decided *whether* from the SIM while
     * `adPolicyRegion()` decided *which* from the timezone and fell back to the
     * first key of the config map, so a Russian SIM roaming outside Russia got
     * IR's "off" policy, and reordering two apparently-cosmetic config keys
     * would have flipped every Iranian SIM abroad to RU's "always".
     */
    private fun freedomRegion(): String? {
        // A brand with no free tier has no freedom region, by definition. One
        // choke point, so the paywall, metering, ads and asFreedom() all inherit
        // it and cannot disagree. NOT done by emptying `freedom_regions`: that
        // table is also the timezone map the transport uses (see
        // ConfigManager.freedomTier).
        if (!ConfigManager.freedomTier) return null
        val ctx = MainActivity.instance?.applicationContext ?: return null
        return com.rostamvpn.android.billing.RegionDetector.freedomRegion(ctx)
    }

    private fun isFreedomRegion(): Boolean = freedomRegion() != null

    /**
     * May this device be SOLD an eSIM or a number? iOS `ESimStore.canPurchase`:
     * no purchase UI in a freedom region (the same derivation as the paywall)
     * nor for China traffic. Owned eSIMs still show and install either way.
     */
    fun esimPurchaseAllowed(): Boolean {
        val ctx = MainActivity.instance?.applicationContext ?: return false
        return !isFreedomRegion() && !com.rostamvpn.android.ui.screens.ShopStore.chinaTraffic(ctx)
    }

    /**
     * The server has metered this cid and cut it off. Evidence that OUTRANKS our
     * own region guess, because the cashier is the side that actually enforces.
     *
     * The two sides key off different signals: we decide from the SIM first, the
     * cashier from the timezone we reported (`quota._is_freedom`), and
     * `/cid/register` sends no country. So an Iranian SIM abroad on a foreign
     * timezone is "free" to us and metered by them — a user with no in-app way
     * to pay, because every purchase entry point routes through
     * `openPaywallAndLoad()` which hard-returned with a "you have free unlimited
     * access" toast. This unblocks exactly that user and nobody else.
     *
     * It can never fire for a user in a freedom region: the cashier forces
     * `unlimited` (and therefore `over_quota = false`) whenever the reported tz
     * is a freedom zone (cashier `quota.status`), so an Iranian in Iran is
     * untouched by this. Iran stays free.
     */
    /**
     * The allowance is gone and this brand does not keep serving afterwards.
     *
     * The normal model is "free forever, just slower" — over quota the tunnel
     * keeps running at `throttle.free_kbps`. A brand with `throttle.hard_stop`
     * has no free tier past the allowance at all: DollarVPN is commercial, and
     * everyone pays. Freedom regions are exempt by construction, because
     * `isFreedomRegion()` is already false for a brand with no free tier.
     */
    private fun quotaHardStopped(): Boolean =
        ConfigManager.throttleConfig.hardStop &&
            !_isPaid.value &&
            !isFreedomRegion() &&
            (_usage.value?.let { it.overQuota && !it.paid } == true)

    /**
     * DEBUG-ONLY assume-paid, for the local protection/messaging end-to-end run
     * (the exit synthesises entitlement only AFTER connect, and paid_only
     * refuses connect before any stream opens). Read live from a system
     * property so there is no construction-time race:
     *   adb shell setprop debug.ip.assume_paid 1
     * BuildConfig.DEBUG gates it, so release is byte-for-byte unchanged, and it
     * is not a brand check.
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
     * clock, without waiting for the server. Before revoking it asks Play
     * locally (no server), because an auto-renewed sub has a newer paid_until
     * that this process has not fetched yet; then re-syncs.
     *
     * ponytail: one timer per (subscription, connection) change; a device clock
     * set far ahead ends the session early, which is the safe direction.
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
     * PAID_ONLY build, locally "paid", but the last paid_until we ever saw is
     * more than [PAID_GRACE_MS] behind us. The cashier may be unreachable, so
     * register never revokes; this is the client half of enforcing the lapse
     * (server-side enforcement is an owner gate). Play is asked before refusing.
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
        // Only a date the cashier gave THIS process arms the timer. A persisted
        // one may predate a renewal made while the app was closed.
        if (!entitlementAnswered) return
        val until = parsePaidUntil(_subscription.value?.paidUntil) ?: return
        paidLapseJob = viewModelScope.launch {
            delay((until - System.currentTimeMillis()).coerceAtLeast(0L))
            if (playSubOrUnknown() != false) {
                Log.i(TAG, "[paid_only] paid_until passed; Play has a sub or could not answer — re-syncing")
                syncEntitlement()
                return@launch
            }
            // Play says no. Ask the cashier itself before ending anything: a web
            // or eSIM renewal is invisible to Play. Unreachable = keep.
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

    /**
     * Fetch the plans BEFORE the tap, not on it.
     *
     * `openPaywallAndLoad` used to do everything on open and serially: a cashier
     * round-trip for payMethods, then the Play client CONNECTION, then the
     * product query. The cashier is one IP (§16) reached through the tunnel, so
     * on the network this product exists for that first hop is the slow one —
     * and the user stares at a spinner for all of it.
     *
     * Keyed on `premiumAvailable` because that is exactly "a paywall is
     * reachable for this user": false in freedom regions and for paid users, so
     * no device that will never see a paywall spends a request.
     *
     * 🚨 **A SECOND `init` BLOCK, and it has to be here — after the property.**
     * Kotlin runs initialisers in DECLARATION order, so collecting this flow
     * from the init block at the top of the class read it while it was still
     * null and crashed every launch with a bare NPE on `MutableStateFlow.collect`.
     * It compiles cleanly, because the type is non-null as far as the compiler
     * is concerned. Found by installing it, not by building it.
     */
    init {
        // paid_only lapse timer: re-armed whenever paid_until or the connection
        // changes. A no-op on every brand without `paid_only`.
        viewModelScope.launch {
            kotlinx.coroutines.flow.combine(_subscription, _connectionState) { a, b -> a to b }
                .collect { schedulePaidLapse() }
        }
        viewModelScope.launch {
            _connectionState.collect { runGuardPoller(it is ConnectionState.Connected) }
        }
        // Live surfaces (FEATURE_LIVE_STATUS): the notification, tile and widget
        // read LiveStatus. Every value here already drives the Connect tab.
        if (BuildConfig.FEATURE_LIVE_STATUS) viewModelScope.launch {
            kotlinx.coroutines.flow.combine(
                _connectionState, _protectionAcked, _blockedToday, _selectedRegion, _isPaid,
            ) { c, ack, blocked, region, paid ->
                val ctx = MainActivity.instance?.applicationContext
                val route = if (region == "auto") ctx?.getString(R.string.tabs_route_auto).orEmpty() else regionLabel(region)
                LiveStatus.flow.value.copy(state = LiveStatus.stateOf(c, ack), route = route, blockedToday = blocked, paid = paid)
            }.collect { LiveStatus.publish(MainActivity.instance?.applicationContext, it) }
        }
        viewModelScope.launch {
            _premiumAvailable.collect { available ->
                if (available && _subProducts.value.isEmpty()) prewarmPlans()
            }
        }
        // One place counts connects, rather than the four sites that assign
        // ConnectionState.Connected — those include reconnects and in-place
        // updates, and a counter spread over four assignments is one refactor
        // away from being wrong.
        //
        // ⚠️ This collect MUST stay in the SECOND init block: Kotlin initialises
        // in DECLARATION order, and a collect placed above the property it reads
        // is a bare NPE on every launch that compiles cleanly. That shipped once.
        viewModelScope.launch {
            _connectionState.collect { st ->
                when (st) {
                    is ConnectionState.Connected -> noteSuccessfulConnect()
                    is ConnectionState.Disconnected, is ConnectionState.Failed ->
                        countedThisSession = false
                    else -> {}
                }
            }
        }
    }
    val premiumAvailable: StateFlow<Boolean> = _premiumAvailable.asStateFlow()

    // Dev Tools were REMOVED (2026-09-18). `showDev = BuildConfig.DEBUG` hid the
    // drawer entry but R8 never stripped the screen, so "Region override" and
    // "Force over-quota" shipped as string literals in every RELEASE build —
    // a decompiler's map of the entitlement and region logic, in a product whose
    // threat model is someone decompiling it. Verified: 3 hits in both release
    // APKs. Do not reintroduce behind a BuildConfig flag; that is what failed.

    /** Open the paywall and load the region-appropriate purchase options. */
    fun openPaywallAndLoad() {
        // Iran is free — never show a paywall. The one exception is a user the
        // CASHIER has metered and cut off (see serverCutOff): it is the side that
        // enforces, and refusing the paywall there left that user with no way to
        // pay at all. It cannot fire inside a freedom region, because the cashier
        // forces `unlimited` for a freedom timezone.
        if (isFreedomRegion() && !serverCutOff()) {
            _toastMessage.value = "You have free unlimited access ✓"
            return
        }
        _showPaywall.value = true
        Analytics.paywallViewed("main_paywall")
        // Only spin when there is nothing to paint. A prewarmed paywall opens
        // with prices already on it and refreshes underneath, so the refresh is
        // invisible instead of being the whole experience.
        _plansLoading.value = _subProducts.value.isEmpty()
        viewModelScope.launch(Dispatchers.IO) {
          try {
            val ctx = MainActivity.instance?.applicationContext ?: return@launch
            // Gate rails on what the server has enabled — never hardcode. Paymentwall
            // / Apple light up automatically once ops sets their keys.
            //
            // 🚨 NEVER AWAITED. Owner, 2026-10-02: "keep it if it's not blocking
            // purchase / information only / never block."
            //
            // It was awaited, and it is the single most expensive thing the
            // cashier serves that nobody reads: 809,340 calls in 24 hours
            // against 240 iap/verify, measured on the box. Every branch that
            // consumes it is gated on WEB_PAY_ENABLED -- false on the play
            // flavour -- and an ADAPTY_ONLY brand never renders this paywall at
            // all, so on the overwhelming majority of those calls the answer was
            // fetched, waited for, and discarded.
            //
            // Waiting is the part that hurt. This sits on the paywall-open path,
            // BEFORE the tunnel exists, on a censored link, so the plan ladder
            // could not resolve until a request that was usually pointless either
            // returned or timed out -- at the exact moment somebody is deciding
            // whether to pay.
            //
            // Launched on its own coroutine. `_payMethods` is a StateFlow, so a
            // rail that depends on it still updates when the answer lands; it
            // just no longer holds the prices hostage. Only fired where it can
            // mean something, so a Play build and an adapty-only brand make no
            // request at all.
            val railsCouldApply = BuildConfig.WEB_PAY_ENABLED && !BuildConfig.ADAPTY_ONLY
            if (railsCouldApply) {
                viewModelScope.launch(Dispatchers.IO) {
                    _payMethods.value =
                        com.rostamvpn.android.billing.CashierApi.payMethods()
                }
            }
            // The rail decision below reads whatever has landed so far. `null`
            // is the honest "not known yet", and every branch treats it as
            // "fall through to the store", which is the right default on every
            // build and the only possible answer on most of them.
            val methods = _payMethods.value
            val storeCountry = billing?.billingCountry()
            // China detected GENEROUSLY — China timezone OR SIM/network/store country.
            // The CN rail is gated to the non-Play build below (Play-policy safe), so
            // honoring a China timezone here is fine, and it matches the cashier's
            // tz→CN map. A CN-timezone device now reaches Alipay/WeChat even on a
            // foreign SIM (the strict SIM-first cascade would otherwise miss it).
            val cnTz = java.util.TimeZone.getDefault().id in setOf(
                "Asia/Shanghai", "Asia/Urumqi", "Asia/Chongqing", "Asia/Harbin", "Asia/Kashgar", "PRC")
            val country = com.rostamvpn.android.billing.RegionDetector.detectCountry(ctx, storeCountry).ifEmpty { "US" }
            val cn = cnTz || country == "CN"
            when {
                // China (Alipay/WeChat via Shepherd). MUST be gated to the non-Play
                // build: routing any user to an alternative payment from a
                // Play-distributed app violates Google Play's Payments policy (a
                // reviewer on a CN-spoofed locale would otherwise reach this). CN
                // users never have Play anyway — they install the `direct` build via
                // a Chinese app store / APK. In the `play` build, CN falls through to
                // Google Play. See docs/PLAY-POLICY.md §0.
                BuildConfig.WEB_PAY_ENABLED && cn && methods?.shepherdCn == true -> {
                    _payRail.value = com.rostamvpn.android.billing.PayRail.CHINA
                    _cnPlans.value = com.rostamvpn.android.billing.CashierApi.plans("CN")
                }
                // Direct-APK/web build, in preference order: Stripe (cards) → crypto
                // → Paymentwall. Paymentwall is only a safety net so the direct build
                // is never left without a card rail; switch it off in /pay/methods
                // once Stripe handles subscriptions and it drops out on its own.
                BuildConfig.WEB_PAY_ENABLED && methods?.stripe == true -> {
                    _payRail.value = com.rostamvpn.android.billing.PayRail.STRIPE
                    _webPlans.value = com.rostamvpn.android.billing.CashierApi.plans(country)
                }
                BuildConfig.WEB_PAY_ENABLED && methods?.anyCrypto == true -> {
                    _payRail.value = com.rostamvpn.android.billing.PayRail.CRYPTO
                    _webPlans.value = com.rostamvpn.android.billing.CashierApi.plans(country)
                }
                BuildConfig.WEB_PAY_ENABLED && methods?.paymentwall == true -> {
                    _payRail.value = com.rostamvpn.android.billing.PayRail.PAYMENTWALL
                    _webPlans.value = com.rostamvpn.android.billing.CashierApi.plans(country)
                }
                else -> {
                    _payRail.value = com.rostamvpn.android.billing.PayRail.GOOGLE_PLAY
                    if (methods?.googleIap != false) {   // null (offline) → still try Play
                        _subProducts.value = billing?.queryProducts(subProductIds) ?: emptyList()
                    }
                }
            }
          } finally {
            _plansLoading.value = false
          }
        }
    }

    /**
     * The region we may actually connect to.
     *
     * Region choice is a PAID feature: the cashier says who may pick one via
     * `region_select` (off / until_quota / pro_only / on) -> `usage.canSelectRegion`.
     * Hiding the picker was never enough — `selected_region` is persisted, so a user
     * who chose Frankfurt before going over quota kept it forever and the "lock" was
     * decoration. Enforce it where it actually matters: at connect.
     *
     * Paid users choose a region — and so do FREEDOM regions (Iran), which get
     * the full product for free by policy. `premiumAvailable` is derived from
     * the one freedom decision (`premiumAvailableNow`) and re-derived on every
     * tier change, so it is the single source of truth rather than a second
     * timezone check that could drift from it. It used to be computed once per
     * process, which left a now-metered free user holding the paid picker for
     * the rest of the session.
     *
     * `usage.canSelectRegion` IS consulted now, but only behind an explicit
     * `!overQuota`. The reason it was dropped stands — the live IR offering
     * returns it `true` unconditionally, so trusting it alone hands the picker
     * to metered free users, the one case this gate exists to prevent. The
     * over-quota guard closes that: even a `region_select: "on"` offering
     * cannot unlock someone who has spent their allowance.
     *
     * What it buys is the `until_quota` offering doing what its name says —
     * DollarVPN's free gigabyte lets you try BOTH locations on your own
     * connection, which is the thing that tells you whether it is worth paying
     * for, and it re-locks the moment the allowance runs out.
     *
     * Null usage (pre-fetch, or a cashier outage) fails CLOSED. Paid state is
     * local and persisted, so an outage can never downgrade someone who paid.
     */
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

    /** Launch the Play purchase flow for a subscription. Must be called from the UI thread. */
    fun purchaseSubscription(productId: String) {
        // The tap. The ONLY thing that may authorise a post-purchase connect.
        purchaseTapInFlight = true
        val activity = MainActivity.instance ?: return
        val pd = _subProducts.value.firstOrNull { it.productId == productId } ?: return
        Analytics.checkoutStarted(productId)
        // If the user already holds a DIFFERENT active subscription this is a plan
        // change, not a new purchase — Play must be told, or they get billed for both.
        viewModelScope.launch {
            val oldToken = withContext(Dispatchers.IO) {
                billing?.queryActivePurchases().orEmpty()
                    .firstOrNull {
                        it.purchaseState == com.android.billingclient.api.Purchase.PurchaseState.PURCHASED &&
                            !it.products.contains(productId)
                    }?.purchaseToken
            }
            if (oldToken != null) Log.i(TAG, "[PAY] plan change to $productId (replacing existing sub)")
            // launchBillingFlow must run on the main thread.
            billing?.launchPurchase(activity, pd, oldToken, cid())
        }
    }

    /** Play purchase succeeded → verify with the cashier, then acknowledge. */
    private fun handlePlayPurchase(purchase: com.android.billingclient.api.Purchase) {
        viewModelScope.launch(Dispatchers.IO) {
            val productId = purchase.products.firstOrNull() ?: return@launch
            val ok = com.rostamvpn.android.billing.CashierApi.iapVerify(cid(), productId, purchase.purchaseToken)
            if (ok) {
                // Acknowledge ONLY after the cashier grants — Play auto-refunds
                // unacknowledged purchases after 3 days.
                if (!purchase.isAcknowledged) billing?.acknowledge(purchase.purchaseToken)
                clearPendingAck()
                setPaid(true)
                // ── THE MOMENT THEY PAY ──
                //
                // It used to be a toast, in a hardcoded English string, on a
                // screen still showing the paywall they had just bought their
                // way past. Now: close the paywall, land on home, celebrate,
                // and turn the tunnel on — which is what they actually came for.
                _showPaywall.value = false
                _premiumCelebration.value = true
                // THE conversion. Price comes from Play's own ProductDetails in
                // micros, never from a parsed localised string.
                _subProducts.value.firstOrNull { it.productId == purchase.products.firstOrNull() }
                    ?.subscriptionOfferDetails?.firstOrNull()
                    ?.pricingPhases?.pricingPhaseList?.firstOrNull()
                    ?.let { Analytics.purchased(purchase.products.firstOrNull().orEmpty(),
                                                it.priceAmountMicros, it.priceCurrencyCode) }
                // Drop the allowance meter now rather than at the next 60s
                // report. The home gate already keys on the client's isPaid, so
                // this only reconciles the server's view.
                refreshUsage()
                // 🚨 The celebration plays OVER the connect, never before it —
                // the same rule the ad gate follows. A paying user must not wait
                // on an animation to reach the tunnel.
                // Read-and-clear: this may only ever authorise the one
                // purchase the tap was for.
                val fromTap = purchaseTapInFlight
                purchaseTapInFlight = false
                val st = _connectionState.value
                if (!fromTap) {
                    // Play handed us a purchase we did not start -- a promo
                    // code, an Ask-to-Buy approval, or a pending purchase that
                    // cleared, all of which arrive at LAUNCH. Entitle them, and
                    // let them press Connect.
                    Log.i(TAG, "[PAY] purchase arrived without a tap; entitled, NOT connecting")
                } else if (st !is ConnectionState.Connected && st !is ConnectionState.Connecting) {
                    Log.i(TAG, "[PAY] premium activated — connecting")
                    connectAuto()
                }
            } else {
                // The user has PAID but we couldn't verify (cashier unreachable — the
                // normal condition for a lot of our users). Persist the token so a
                // later launch retries verify+acknowledge; otherwise Play silently
                // auto-refunds after 3 days and the user got nothing.
                savePendingAck(productId, purchase.purchaseToken)
                Log.w(TAG, "[PAY] verify failed — queued for retry (product=$productId)")
                _toastMessage.value = "Payment received — activating, please reopen the app shortly"
            }
        }
    }

    // ── Pending-acknowledge queue ───────────────────────────────────────────
    // A purchase that Play accepted but the cashier hasn't confirmed yet. Kept in
    // prefs so it survives process death; retried on launch.

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
            // Still failing → leave it queued for the next launch. Never clear on
            // failure: dropping it is what causes the silent auto-refund.
        }
    }

    /**
     * Does Play still hold an active PURCHASED subscription on this device?
     *
     * The second opinion syncEntitlement() takes before it revokes premium. A
     * null `billing` (no Play Services, or the `direct` flavour whose rails are
     * web) yields false, which is the honest answer: with no second opinion
     * available the cashier is the only truth we have.
     */
    /** Play's answer, or null when Play could not answer (an error is
     *  "unknown", never "no subscription"). */
    private suspend fun playSubOrUnknown(): Boolean? =
        billing?.queryActivePurchasesOrNull()?.any {
            it.purchaseState == com.android.billingclient.api.Purchase.PurchaseState.PURCHASED
        }

    /**
     * Has the cashier (register or an entitlement note) answered in THIS
     * process? Until it has, a persisted paid_until is a hint, not a verdict:
     * the lapse timer never arms from it (review 2026-10-13, blocker 1).
     */
    @Volatile private var entitlementAnswered = false

    /**
     * Ask the cashier now, directly (a PAID_ONLY build may: see CashierApi.open).
     * true = paid with a live paid_until, false = the cashier says not paid,
     * null = unreachable. Updates _subscription and the persisted date.
     */
    private suspend fun askCashierNow(): Boolean? = withContext(Dispatchers.IO) {
        // Decided BEFORE the call: which path this answer travels on.
        val direct = !com.rostamvpn.android.billing.CashierApi.tunnelUp()
        val reg = runCatching {
            com.rostamvpn.android.billing.CashierApi.registerCid(cid = cid(), tz = java.util.TimeZone.getDefault().id)
        }.getOrNull() ?: return@withContext null
        val until = parsePaidUntil(reg.paidUntil)
        val paid = reg.isPaid && (until == null || until > System.currentTimeMillis())
        // UPGRADE-ONLY over the direct (unpinned, tunnel-down) path: it may set
        // paid and paid_until, but its "not paid" is treated as unknown, never
        // recorded and never ends a session. Downgrades come only through the
        // tunnel, Play or an entitlement note.
        if (!paid && direct) return@withContext null
        entitlementAnswered = true
        _subscription.value = SubscriptionInfo(reg.tier, reg.product, reg.paidUntil)
        persistPaidUntil(reg.paidUntil)
        paid
    }

    /** The paywall's Restore on a PAID_ONLY build also asks the cashier
     *  directly, so a web or eSIM renewal reaches the device. */
    fun recheckEntitlement() {
        viewModelScope.launch {
            when (askCashierNow()) {
                true -> { setPaid(true); _showPaywall.value = false; _toastMessage.value = localizedStatus(R.string.restore_ok) }
                false -> _toastMessage.value = localizedStatus(R.string.restore_none)
                null -> _toastMessage.value = localizedStatus(R.string.tabs_load_failed)
            }
        }
    }

    private suspend fun playHasActiveSubscription(): Boolean =
        billing?.queryActivePurchases().orEmpty().any {
            it.purchaseState == com.android.billingclient.api.Purchase.PurchaseState.PURCHASED
        }

    /**
     * Restore entitlement — re-verify any active store subscription.
     *
     * Runs unprompted at launch (Play requirement) AND from the Restore button,
     * which is the only restore path left now that in-app sign-in is gone.
     * `notify` separates the two: a toast on every launch would be noise, and a
     * button that answers with silence reads as broken.
     */
    fun restoreEntitlement(notify: Boolean = false) {
        // Also drain anything Play took but the cashier never confirmed.
        retryPendingAck()
        viewModelScope.launch(Dispatchers.IO) {
            val active = billing?.queryActivePurchases().orEmpty()
            val purchase = active.firstOrNull {
                it.purchaseState == com.android.billingclient.api.Purchase.PurchaseState.PURCHASED
            } ?: run {
                // PAID_ONLY: Restore is the paywall's retry. With no Play purchase
                // it asks the cashier directly, so a web or eSIM renewal (which
                // Play never sees) can bring the device back.
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
                // NEVER downgrade here. A failure means "couldn't reach the cashier",
                // not "not entitled" — and this runs on every launch, so treating it
                // as a revocation logged real subscribers out (ads + banner returning)
                // on any network blip. Server truth arrives via syncEntitlement()
                // (/cid/register), which is also what downgrades after an RTDN
                // cancel/refund.
                Log.i(TAG, "[PAY] restore: verify failed — keeping cached tier")
            }
        }
    }

    /** Server-truth entitlement on launch: register the cid and adopt its tier.
     * Covers every rail (crypto/paymentwall/CN/IAP) + restored account codes.
     * Also resolves the GAID once and sends it to our cashier/adtech as ifa —
     * without ever keying entitlement off it. */
    /**
     * Whether the cashier has answered /cid/register at least once this process.
     *
     * Not persisted on purpose: it exists to decide whether the tunnel is worth
     * retrying through, and that question is per-session.
     */
    @Volatile
    private var cashierRegistered = false

    fun syncEntitlement() {
        // Synchronously, before any network call: this runs on every resume
        // (MainActivity.onResume) precisely so a region change is picked up
        // without a relaunch, and the UI gate has to move even when the cashier
        // is unreachable — registerCid returning null aborts the coroutine below
        // before anything else recomputes.
        applyRateLimit()
        pushPaywallSignals()
        viewModelScope.launch(Dispatchers.IO) {
            val ctx = MainActivity.instance?.applicationContext ?: return@launch
            val tz = java.util.TimeZone.getDefault().id
            val adId = try {
                com.rostamvpn.android.billing.Gaid.get(ctx)
            } catch (_: Throwable) {
                com.rostamvpn.android.billing.AdId(null, true)
            }
            val lmt = if (adId.limited || adId.gaid.isNullOrBlank()) 1 else 0
            // Ad attribution: the Play Install Referrer, read from the Play Store
            // app once per install and sent on the register that first has it
            // (docs/ATTRIBUTION-2026-10-25.md). No permission, no advertising
            // id; a direct-APK install simply has none.
            val referrer = com.rostamvpn.android.billing.InstallReferrer.pending(ctx)
            // PAID_ONLY: which path this answer travels on (see below).
            val directPath = BuildConfig.PAID_ONLY && !com.rostamvpn.android.billing.CashierApi.tunnelUp()
            val reg = com.rostamvpn.android.billing.CashierApi.registerCid(
                cid = cid(),
                tz = tz,
                ifa = adId.gaid,
                lmt = lmt,
                referrer = referrer,
            ) ?: return@launch
            cashierRegistered = true
            if (referrer != null) com.rostamvpn.android.billing.InstallReferrer.markSent(ctx)
            // A DOWNGRADE needs a second opinion; a grant does not.
            //
            // registerCid builds a Registration for any 2xx and defaults tier to
            // "free", so a 200 that simply does not know about this purchase —
            // the ixroute-era subscribers of CLAUDE.md §15, a cid whose
            // transaction link was never stored by /iap/verify, a wrong-tenant
            // answer — used to persist paid=false and revoke a real subscriber
            // for the rest of the process: ads back, banner back, region picker
            // locked, CDN denied. Nothing re-verified against Play until the
            // process was killed, because onResume calls this function ALONE
            // while restoreEntitlement() sits behind a LaunchedEffect(Unit).
            //
            // Asking Play before revoking also removes the launch-time race
            // between this and restoreEntitlement(): the two can no longer
            // contradict each other, whichever lands last.
            if (!BuildConfig.PAID_ONLY) {
                // Every other brand: exactly the 3b1d83a order and behaviour.
                if (reg.isPaid) {
                    setPaid(true)
                } else if (_isPaid.value && playHasActiveSubscription()) {
                    Log.i(TAG, "[PAY] register says free but Play still shows an active sub — keeping paid")
                } else {
                    setPaid(false)
                }
                _subscription.value = SubscriptionInfo(reg.tier, reg.product, reg.paidUntil)
            } else if (!reg.isPaid && directPath) {
                // PAID_ONLY over the direct (unpinned, tunnel-down) path is
                // UPGRADE-ONLY: a "not paid" here is never recorded and never
                // ends a session. Downgrades come only through the tunnel, Play
                // or an entitlement note.
                Log.i(TAG, "[PAY] direct register says ${reg.tier}; not applied (upgrade-only path)")
            } else {
                entitlementAnswered = true
                // The subscription (paid_until) BEFORE setPaid: setPaid arms the
                // paid_only lapse timer from _subscription, and with the previous
                // value a paid_until already in the past looped syncEntitlement or
                // disconnected a paid user (review 2026-10-13).
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
            // INSIDE the coroutine, AFTER register — it used to sit outside and
            // raced it.
            //
            // On a FRESH install the cid does not exist server-side yet, and
            // /usage for an unknown cid falls back to the ("*", "") offering —
            // 2048 MB, region_select pro_only. Nothing re-fetches until the user
            // opens Usage stats or connects, so a new DollarVPN install showed
            // rostam's 2 GB and a locked region picker for its whole first
            // session instead of its own 1 GB with the picker open.
            //
            // Invisible on rostam and redvpn because the fallback happens to BE
            // their values, which is exactly why it survived this long.
            refreshUsage()
        }
    }


    /** Restore entitlement from an account code the user entered. */
    fun claimAccountCode(code: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val ok = com.rostamvpn.android.billing.CashierApi.claim(code.trim(), cid())
            // Only ever grant here. `setPaid(ok)` meant a paying user who mistyped a
            // restore code — or hit an unreachable cashier — was downgraded on the spot.
            if (ok) setPaid(true)
            // Was two English literals, shown to users in all 38 locales at the
            // one moment a paying customer most needs to understand what happened.
            _toastMessage.value = localizedStatus(
                if (ok) R.string.restore_ok else R.string.restore_invalid
            )
            if (ok) _showPaywall.value = false
        }
    }

    /** China rail: create an Alipay/WeChat order, then poll until paid. */
    fun startCnPayment(planSlug: String, payMethod: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val order = com.rostamvpn.android.billing.CashierApi.payCreate(planSlug, cid(), "shepherd_cn", payMethod)
            if (order == null) { _toastMessage.value = "Could not start payment"; return@launch }
            _cnPayOrder.value = order
            // Poll until paid, or until the user leaves the screen (clears the order).
            while (_cnPayOrder.value?.orderNo == order.orderNo) {
                delay(3000)
                if (com.rostamvpn.android.billing.CashierApi.payStatus(order.orderNo)) {
                    setPaid(true)
                    _toastMessage.value = "Premium activated ✓"
                    _cnPayOrder.value = null
                    _showPaywall.value = false
                    break
                }
            }
        }
    }

    fun dismissCnPayment() { _cnPayOrder.value = null }

    // ── Paymentwall (direct/web build only) — hosted card/local-method widget ──
    private val _webPayUrl = MutableStateFlow<String?>(null)
    val webPayUrl: StateFlow<String?> = _webPayUrl.asStateFlow()
    private var webPayOrderNo: String? = null

    /** Open the Paymentwall hosted widget for a plan (fallback card rail). */
    fun startPaymentwallPayment(planSlug: String) = startWebPayment(planSlug, "paymentwall")

    /**
     * Stripe card checkout. Uses `stripe_hosted`, not `stripe`: the `stripe` channel
     * returns only a `client_secret` for mounting Stripe Elements, and payCreate
     * requires a `pay_url`, so `stripe` would silently return null here.
     * ⚠️ `stripe_hosted` is currently one-time-payment oriented on the cashier
     * (written for eSIM passes) — it needs subscription mode before this rail is
     * switched on in /pay/methods. See the HANDOFF board.
     */
    fun startStripePayment(planSlug: String) = startWebPayment(planSlug, "stripe_hosted")

    /** Crypto invoice (self-sovereign rail). */
    fun startCryptoPayment(planSlug: String) = startWebPayment(planSlug, "crypto")

    /** Card-or-crypto on a hosted invoice that settles crypto to us. */
    fun startNowPaymentsPayment(planSlug: String) = startWebPayment(planSlug, "nowpayments")

    /** Shared web-rail flow: create an order, open its hosted pay_url in the
     *  in-app WebView (WebPayScreen — stays in the app), then poll until paid. */
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

    /** Seconds between interstitials, from the config (`ads.interval_secs`). */
    val adIntervalSecs: Int get() = ConfigManager.adsConfig.intervalSecs

    /** How long the ad loop should wait before its next attempt. */
    private val _nextAdDelaySecs = MutableStateFlow(ConfigManager.adsConfig.intervalSecs)
    val nextAdDelaySecs: StateFlow<Int> = _nextAdDelaySecs.asStateFlow()

    private val _adsDisabled = MutableStateFlow(
        prefs()?.getBoolean("ads_disabled", false) ?: false
    )
    val adsDisabled: StateFlow<Boolean> = _adsDisabled.asStateFlow()

    fun setAdsDisabled(disabled: Boolean) {
        prefs()?.edit()?.putBoolean("ads_disabled", disabled)?.apply()
        _adsDisabled.value = disabled
        if (disabled) dismissAd()
    }

    /**
     * Cheap, synchronous gate for whether ANY interstitial (AdMob or cashier)
     * may show right now. Only the universal, always-true policies:
     *   - premium users are ad-free (hard policy)
     *   - don't stack an ad while the cashier card is already up
     * Region mode / opt-out / frequency-cap are cashier-specific and stay inside
     * maybeShowInterstitial(); AdMob is allowed wherever an ad is allowed at all,
     * per the "AdMob first, every region" decision.
     */
    fun adsAllowed(): Boolean = BuildConfig.ADS &&
        !_isPaid.value && _adUrl.value == null && !inAdGracePeriod()

    /**
     * True while this install still owes the user an ad-free first impression.
     *
     * `ads.grace_connects` is 0 by default, so this is false everywhere until a
     * config says otherwise and cannot change any brand's behaviour by landing.
     *
     * Placed HERE, in the one predicate both sources AND on, so AdMob
     * (admobAllowedForCurrentRegion) and our own exchange (adtechAllowed) are
     * covered by one condition and cannot drift apart — the same reason
     * freedom_tier and hard_stop each have exactly one choke point per platform.
     */
    private fun inAdGracePeriod(): Boolean {
        val grace = ConfigManager.adsConfig.graceConnects
        if (grace <= 0) return false
        return successfulConnects() < grace
    }

    private fun adGracePrefs() = MainActivity.instance?.applicationContext
        ?.getSharedPreferences("rostam_prefs", android.content.Context.MODE_PRIVATE)

    private fun successfulConnects(): Int =
        adGracePrefs()?.getInt(KEY_CONNECTS, 0) ?: 0

    /**
     * Count one SUCCESSFUL connect. Idempotent within a session: the state flow
     * can re-emit Connected (a region label arriving, a throughput update) and
     * the health watchdog reconnects unattended, neither of which is a user
     * deciding to connect. `countedThisSession` is cleared on disconnect.
     */
    private fun noteSuccessfulConnect() {
        if (countedThisSession) return
        countedThisSession = true
        val n = successfulConnects()
        // Stop counting once past the largest grace anyone could set, so this
        // is not an unbounded integer growing on every connect forever.
        if (n >= 1000) return
        adGracePrefs()?.edit()?.putInt(KEY_CONNECTS, n + 1)?.apply()
    }

    @Volatile private var countedThisSession = false

    /**
     * Resolve the region key used for ad policy (the same value modeFor() keys
     * on): a freedom-region code when in a freedom region, else the detected SIM/
     * network country. Cheap; safe to call on the main thread.
     */
    fun adPolicyRegion(): String {
        val ctx = MainActivity.instance?.applicationContext ?: return ""
        // The freedom code that ACTUALLY MATCHED, not a second derivation from a
        // different signal. This used to ask isFreedomRegion() (SIM) whether the
        // user is freedom and then freedomRegionForTz() (timezone) which region
        // they are in, falling back to `freedomRegions.firstOrNull()` when the tz
        // matched nothing. So a Russian SIM roaming outside Russia inherited IR's
        // "off" instead of RU's "always" (silent revenue loss), and because that
        // fallback is the first key of a JSONObject-ordered map, reordering two
        // keys in `freedom_regions` — an apparently cosmetic config edit — would
        // have flipped every Iranian SIM abroad to RU's "always" and put
        // commercial demand in front of exactly the users who must never see it.
        return com.rostamvpn.android.billing.RegionDetector.freedomRegion(ctx)
            ?: com.rostamvpn.android.billing.RegionDetector.detectCountry(ctx, null)
    }

    /**
     * The ads MODE for the current region, from config (`ads.regions[REGION]`
     * else `ads.default_mode`, or "off" when ads are globally disabled).
     *   - "off"  → no ad at all this region: both sources skipped.
     *   - other  → an ad is allowed; AdMob first, adtech container as fallback.
     */
    fun adModeForCurrentRegion(): String = ConfigManager.adsConfig.modeFor(adPolicyRegion())

    /**
     * Whether THIS impression asks AdMob first, per `ads.admob_share_pct` and
     * the per-region override. Rolled per impression, not per install: a
     * per-install assignment would let a few heavy users skew the mix badly at
     * low volume, which is exactly the volume this is meant to steer.
     */
    fun admobFirstForThisImpression(): Boolean =
        ConfigManager.adsConfig.admobFirst(adPolicyRegion())

    /**
     * Whether the AdMob source may run now: an ad is allowed at all (adsAllowed)
     * AND config's `admob_ad` toggle permits it for this region. Turn AdMob off
     * globally or per-region via `ads.admob_ad` — OTA/config, no app update.
     */
    fun admobAllowedForCurrentRegion(): Boolean =
        adsAllowed() && ConfigManager.adsConfig.admobAllowed(adPolicyRegion())

    /**
     * Whether the rostam adtech container may run now: an ad is allowed at all
     * AND config's `rostam_adtech` toggle permits it for this region. Turn the
     * adtech container off via `ads.rostam_adtech`. When it is off and AdMob
     * no-fills, the slot simply shows nothing (both sources can be independently
     * disabled).
     */
    fun adtechAllowedForCurrentRegion(): Boolean =
        adsAllowed() && ConfigManager.adsConfig.adtechAllowed(adPolicyRegion())

    /**
     * Free-tier interstitial on connect (paid users skip). The cashier/adtech
     * decides the creative by region: freedom regions get a server-chosen
     * SPONSOR pop-up; everywhere else gets commercial demand with the GAID when
     * available. Creatives are 100% server-side — the app just renders the URL.
     *
     * This is the FALLBACK rung of the waterfall: the UI tries AdMob first and
     * only calls this when AdMob has no ad. It re-applies its own region/mode/
     * cap gates independently, so calling it directly (as the cadence loop does)
     * is always safe.
     */
    /**
     * Runs when the CURRENT cashier attempt yields nothing, whether it declined
     * silently (region/mode/quota/cap) or its card came back a no-fill. Set per
     * attempt and cleared the moment it fires, so a later no-fill can never
     * replay a stale rung of the waterfall.
     */
    private var adExhausted: (() -> Unit)? = null

    private fun fireAdExhausted() {
        val next = adExhausted
        adExhausted = null
        next?.invoke()
    }

    fun maybeShowInterstitial(allowHouse: Boolean = true, onExhausted: (() -> Unit)? = null) {
        // A no-ads brand never reaches the cashier's interstitial (BuildConfig.ADS).
        if (!BuildConfig.ADS) return
        // Paid users get no ad from ANY source, and a card already on screen must
        // not be stacked — neither hands the slot on, so onSkip does not fire.
        if (_isPaid.value) return
        if (_adUrl.value != null) return
        adExhausted = onExhausted
        viewModelScope.launch(Dispatchers.IO) {
            // Declining must HAND THE SLOT to the next source in the waterfall
            // rather than silently dropping it. That matters now that the cashier
            // goes first: its region/mode/quota/cap gates are the common path, and
            // before this they ended the slot outright instead of falling through
            // to AdMob. Hops to Main because the caller presents UI.
            suspend fun skip(reason: String) {
                Log.i("CashierAd", "interstitial skipped — $reason")
                withContext(Dispatchers.Main) { fireAdExhausted() }
            }
            val ctx = MainActivity.instance?.applicationContext
                ?: run { skip("no application context"); return@launch }
            val info = com.rostamvpn.android.billing.RegionDetector.detect(ctx, null)
            // Same single source as adPolicyRegion() — see the comment there for
            // why re-deriving the freedom code from the timezone (and falling
            // back to the first config key) was wrong.
            val region0 = adPolicyRegion().ifEmpty { info.country }
            val ads = ConfigManager.adsConfig
            val mode = ads.modeFor(region0)

            // The rostam adtech container is an independently switchable source.
            if (!ads.rostamAdtech.allowedIn(region0)) {
                skip("rostam_adtech off for region=$region0 (config)")
                return@launch
            }
            if (mode == ConfigManager.AdsConfig.MODE_OFF) {
                skip("ads off for region=$region0 (config)")
                return@launch
            }
            if (ads.mayOptOut(region0) && _adsDisabled.value) {
                skip("user opted out (region=$region0)")
                return@launch
            }
            if (mode == ConfigManager.AdsConfig.MODE_AFTER_QUOTA &&
                _usage.value?.overQuota != true
            ) {
                skip("under free quota (region=$region0)")
                return@launch
            }
            val region = region0
            val retryAfter = com.rostamvpn.android.billing.CashierApi
                .adRetryAfterSecs(cid(), region, declineBackoffSecs = adIntervalSecs.coerceAtLeast(0))
            if (retryAfter > 0) {
                // Only meaningful when a mid-session loop is running.
                if (adIntervalSecs > 0) {
                    _nextAdDelaySecs.value = retryAfter + 2
                }
                // A cashier-side frequency cap governs OUR adtech only — AdMob
                // is a separate source and still gets the slot.
                skip("capped (region=$region) — retry in ${retryAfter}s")
                return@launch
            }
            val ad = com.rostamvpn.android.billing.Gaid.get(ctx)
            val sponsorOnly = mode == ConfigManager.AdsConfig.MODE_SPONSOR
            val url = com.rostamvpn.android.billing.CashierApi
                .adInterstitialUrl(cid(), ad, region, sponsorOnly,
                    baseOverride = ads.adtechUrl, allowHouse = allowHouse)
            val param = if (ad.limited || ad.gaid.isNullOrEmpty()) "lmt=1" else "ifa"
            val debug = "region=$region · via=${info.source} · tz=${info.timezone} · " +
                "tier=FREE · mode=$mode · $param · cid=${cid().take(6)}…"
            // Never log the raw advertising id.
            Log.i("CashierAd", "$debug · url=${url.replace(Regex("ifa=[^&]*"), "ifa=<redacted>")}")
            // Re-check PAID as well as connected. The paid check at the top of
            // this function is ~two cashier round trips old by now (adRetryAfterSecs
            // + Gaid.get, 15s connect / 25s read each on a censored network), and
            // the user can complete a Play purchase or an account-code restore
            // inside that window: the paywall closed with "Premium activated ✓"
            // and then this slammed an interstitial over someone who had just
            // paid never to see one.
            if (_isPaid.value) {
                Log.i("CashierAd", "interstitial dropped — user is now paid")
                return@launch
            }
            if (_connectionState.value !is ConnectionState.Connected) {
                Log.i("CashierAd", "interstitial dropped — no longer connected")
                return@launch
            }
            // On-screen context is a testing aid only — shipping it puts region,
            // timezone and a cid prefix over the creative for every free user.
            // Logcat still carries it in release for on-device verification.
            _adDebug.value = if (BuildConfig.DEBUG) debug else null
            _adUrl.value = url
            _nextAdDelaySecs.value = adIntervalSecs
        }
    }

    private var adNoFillStreak = 0

    fun noteAdNoFill() {
        // VpnViewModel is -keep'd whole (proguard-rules.pro), so only this
        // constant guard lets R8 drop the body in a no-ads build.
        if (!BuildConfig.ADS) return
        _adUrl.value = null
        _adDebug.value = null
        // Nobody bid on this rung: hand the slot to the next demand source.
        fireAdExhausted()
        adNoFillStreak++
        // No mid-session loop when interval is 0; backoff only applies if the
        // config asked for a repeating timer.
        if (adIntervalSecs > 0) {
            val backoff = adIntervalSecs.toLong() shl minOf(adNoFillStreak, 3)
            _nextAdDelaySecs.value = minOf(backoff, 1800L).toInt()
            Log.i("CashierAd", "no fill (streak=$adNoFillStreak) — next attempt in ${_nextAdDelaySecs.value}s")
        } else {
            Log.i("CashierAd", "no fill (streak=$adNoFillStreak) — next attempt on next connect")
        }
    }

    fun dismissAd() {
        // A creative was actually shown, so the waterfall is finished for this
        // slot. Drop any pending rung rather than stacking a second ad on top.
        adExhausted = null
        adNoFillStreak = 0
        if (adIntervalSecs > 0) {
            _nextAdDelaySecs.value = adIntervalSecs
        }
        _adUrl.value = null
        _adDebug.value = null
    }
}

/** OTA `wifi_priorities[country]` ({"host": prio}) on Wi-Fi/Ethernet; empty otherwise. Pure, for tests. */
internal fun wifiPins(json: String, onWifi: Boolean, country: String): Map<String, Int> = if (!onWifi) emptyMap() else
    runCatching {
        val m = org.json.JSONObject(json).optJSONObject("wifi_priorities")?.optJSONObject(country)
            ?: return@runCatching emptyMap()
        m.keys().asSequence().associateWith { m.getInt(it) }
    }.getOrDefault(emptyMap())

/** Sets `priority` on every netcore entry whose host is in [want]; the new JSON and the count, or null. */
internal fun applyPins(json: String, want: Map<String, Int>): Pair<String, Int>? = runCatching {
    val o = org.json.JSONObject(json)
    val rs = o.optJSONObject("transport")?.optJSONObject("redsocket") ?: return null
    var n = 0
    for (key in listOf("entries", "premium_entries")) {
        val a = rs.optJSONArray(key) ?: continue
        for (i in 0 until a.length()) {
            val e = a.optJSONObject(i) ?: continue
            want[e.optString("host")]?.let { e.put("priority", it); n++ }
        }
    }
    o.toString() to n
}.getOrNull()
