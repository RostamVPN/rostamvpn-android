package com.rostamvpn.android

import android.app.Notification
import kotlinx.coroutines.launch
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.IpPrefix
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.File
import java.net.InetAddress

class TunnelVpnService : VpnService() {
    companion object {
        const val TAG = "RostamVPN"
        const val ACTION_START = "com.rostamvpn.android.START_VPN"
        const val ACTION_STOP = "com.rostamvpn.android.STOP_VPN"
        /** The live notification's Disconnect (FEATURE_LIVE_STATUS): the app's own disconnect. */
        const val ACTION_USER_DISCONNECT = "com.rostamvpn.android.USER_DISCONNECT"
        const val EXTRA_SOCKS_PORT = "socks_port"
        const val EXTRA_ALLOWED_APPS = "allowed_apps"
        /** The system "Block connections without VPN", as last read at establish(); null before any tunnel. */
        val lockdownState = kotlinx.coroutines.flow.MutableStateFlow<Boolean?>(null)
        var lockdown: Boolean?
            get() = lockdownState.value
            set(v) { lockdownState.value = v }
        const val EXTRA_SUPPORTS_UDP = "supports_udp"

        // ── Split tunnelling ──────────────────────────────────────────────────
        // The app list has always existed; until now it could only ever mean
        // "route ONLY these". Two Play reviews describe the same consequence:
        // add a banking app to the list and it becomes the only thing tunnelled,
        // so the browser goes direct and the VPN looks like it stopped working.
        // What Iranian users actually need is the opposite direction — domestic
        // banking/government apps refuse foreign IPs, and carriers meter VPN
        // traffic at a multiple — so the list now carries a direction.
        const val EXTRA_APP_LIST_MODE = "app_list_mode"

        /**
         * Destination prefixes that skip the tunnel, as CIDR text.
         *
         * The app list answers "which apps", never "which destinations", and the
         * two are not interchangeable: a browser has to stay in the tunnel while
         * the domestic bank it also opens has to stay out of it. Same reasoning
         * as [EXTRA_APP_LIST_MODE] — domestic services refuse foreign IPs and
         * carriers meter VPN traffic at a multiple.
         */
        const val EXTRA_BYPASS_CIDRS = "bypass_cidrs"

        /** Route ONLY the listed apps (the historical, allowlist behaviour). */
        const val MODE_INCLUDE = "include"

        /** Route everything EXCEPT the listed apps (bypass / "take it out"). */
        const val MODE_EXCLUDE = "exclude"

        /**
         * Include-mode sentinel for "no apps at all", which an empty list cannot
         * express there (an empty allowlist means "route everything"). Never
         * appears in an exclude list, where empty already means "exclude nothing".
         */
        const val NONE_SENTINEL = "_none_"


        private const val PREFS = "rostam_settings"
        private const val PREF_MODE = "split_tunnel_mode"
        private const val PREF_APPS = "allowed_apps_json"
        private const val PREF_CIDRS = "bypass_cidrs_json"

        /**
         * The persisted mode, and the fallback for any start path that did not
         * attach [EXTRA_APP_LIST_MODE]. Every caller reads it from here so the
         * several start paths cannot disagree about which way round the list is.
         *
         * Migration: a user who already configured a list did so under the old
         * include-only semantics, so an absent pref with a non-empty list stays
         * INCLUDE — their routing is unchanged by this upgrade. With no list
         * configured the two modes are indistinguishable (both route
         * everything), so a fresh install starts in EXCLUDE, which is the
         * direction the reviews are asking for.
         */
        @JvmStatic
        fun savedMode(ctx: android.content.Context): String {
            return try {
                val p = ctx.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
                val stored = p.getString(PREF_MODE, null)
                if (stored != null) {
                    if (stored == MODE_EXCLUDE) MODE_EXCLUDE else MODE_INCLUDE
                } else {
                    val json = p.getString(PREF_APPS, null)
                    val hadList = !json.isNullOrBlank() && json != "[]"
                    if (hadList) MODE_INCLUDE else MODE_EXCLUDE
                }
            } catch (e: Exception) {
                Log.w(TAG, "savedMode failed, defaulting to include: ${e.message}")
                MODE_INCLUDE
            }
        }

        /** The persisted app list, for start paths that carry none of their own. */
        @JvmStatic
        fun savedApps(ctx: android.content.Context): ArrayList<String> {
            val out = ArrayList<String>()
            try {
                val json = ctx.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
                    .getString(PREF_APPS, null) ?: return out
                val arr = org.json.JSONArray(json)
                for (i in 0 until arr.length()) out.add(arr.getString(i))
            } catch (e: Exception) {
                Log.w(TAG, "savedApps failed: ${e.message}")
            }
            return out
        }

        /**
         * The persisted destination-bypass list, for start paths that carry none.
         *
         * Same contract as [savedApps]: every start path reads the user's config
         * from one place, so `connectAuto()` and the restart-restore path in
         * `MainActivity` cannot route the device two different ways.
         */
        @JvmStatic
        fun savedBypassCidrs(ctx: android.content.Context): ArrayList<String> {
            val out = ArrayList<String>()
            try {
                val json = ctx.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
                    .getString(PREF_CIDRS, null) ?: return out
                val arr = org.json.JSONArray(json)
                for (i in 0 until arr.length()) out.add(arr.getString(i))
            } catch (e: Exception) {
                Log.w(TAG, "savedBypassCidrs failed: ${e.message}")
            }
            return out
        }

        @JvmField
        var isRunning = false
        @JvmField
        var instance: TunnelVpnService? = null

        init {
            try {
                System.loadLibrary("hev-socks5-tunnel")
                Log.i(TAG, "Loaded hev-socks5-tunnel native library")
            } catch (e: UnsatisfiedLinkError) {
                Log.e(TAG, "Failed to load hev-socks5-tunnel: ${e.message}")
            }
        }
    }

    private var vpnInterface: ParcelFileDescriptor? = null

    // 🚨 EVERY establish() BUMPS THIS, AND stopVpn()'s TEARDOWN THREAD CHECKS IT.
    //
    // stopVpn() hands the old ParcelFileDescriptor to a detached thread so the
    // blocking TProxyStopService() cannot ANR. That thread then closes the tun
    // -- with no ordering against a restart that arrives meanwhile, and the
    // whole point of schedulePendingShutdown() is that a restart DOES arrive
    // (a protocol switch, a watchdog reconnect). Measured on R5CY13EDP7A:
    //
    //   .557 Stopping VPN            .614 VPN interface established, fd=96
    //   .568 TProxyStopService       .620 VPN started successfully
    //   .575 restart cancels shutdown .633 old tun.close()  <- 19 ms TOO LATE
    //
    // Android reuses the SAME tun device across establish(), so that late close
    // tore down the interface the restart had just built. The addresses stayed
    // (the new establish set them) while the routes and the VPN NetworkAgent
    // went with the close -- so tun0 was up at 10.0.0.2 with NO default route,
    // RX 0 packets, while the SOCKS port underneath served 200 OK. The app says
    // connected, the carrier is perfect, and not one packet is tunnelled.
    //
    // Skipping a superseded close leaks nothing: establish() has already handed
    // that descriptor to the framework, which owns it once it is replaced.
    @Volatile private var vpnGeneration = 0
    private val handler = Handler(Looper.getMainLooper())
    private var pendingShutdown: Runnable? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                // Cancel any pending service shutdown (protocol switch scenario)
                cancelPendingShutdown()
                val socksPort = intent.getIntExtra(EXTRA_SOCKS_PORT, 1080)
                val allowedApps = intent.getStringArrayListExtra(EXTRA_ALLOWED_APPS) ?: arrayListOf()
                val supportsUdp = intent.getBooleanExtra(EXTRA_SUPPORTS_UDP, false)
                // Fall back to the persisted mode rather than to a hardcoded one:
                // a start path that forgets the extra then still routes the way
                // the user configured, instead of silently inverting the list.
                val mode = intent.getStringExtra(EXTRA_APP_LIST_MODE) ?: savedMode(this)
                // Same fallback rule as the mode: a start path that forgot the
                // extra must still honour what the user configured, or their
                // banking traffic silently goes back through a foreign IP.
                val bypass = intent.getStringArrayListExtra(EXTRA_BYPASS_CIDRS)
                    ?: savedBypassCidrs(this)
                startVpn(socksPort, allowedApps, supportsUdp, mode, bypass)
            }
            ACTION_STOP -> {
                stopVpn()
            }
            ACTION_USER_DISCONNECT -> {
                // Through the app's own disconnect so the carriers stop too; the
                // service alone only if the app's state is gone.
                val vm = MainActivity.viewModelRef
                if (vm != null) {
                    if (vm.connectionState.value !is VpnViewModel.ConnectionState.Disconnected) vm.toggleConnection()
                } else stopVpn()
            }
        }
        return START_NOT_STICKY
    }

    private fun startVpn(
        socksPort: Int,
        allowedApps: List<String>,
        supportsUdp: Boolean = false,
        appListMode: String = MODE_INCLUDE,
        bypassCidrs: List<String> = emptyList()
    ) {
        Log.i(
            TAG,
            "Starting VPN with SOCKS5 port $socksPort, mode: $appListMode, " +
                "apps: $allowedApps, udp: $supportsUdp, bypass: ${bypassCidrs.size} prefix(es)"
        )

        // startForeground FIRST, before any blocking work.
        //
        // Android gives ~5s from startForegroundService() to startForeground() and
        // kills the process otherwise. This used to sit ~60 lines further down,
        // after a blocking TProxyStopService, establish() and startTun2Socks — so
        // on a slow device the window closed first. Play Vitals for v133 shows
        // exactly that: ForegroundServiceDidNotStartInTimeException plus
        // "startForegroundService() did not then call Service.startForeground()".
        // Calling it up front is free: the notification does not depend on any of
        // the work below.
        try {
            startForeground(1, createNotification())
        } catch (e: Throwable) {
            Log.w(TAG, "startForeground failed: ${e.message}")
        }
        if (BuildConfig.FEATURE_LIVE_STATUS) startLiveUpdates()

        // Defensively clean up any existing tun2socks instance.
        // This handles edge cases where the previous tunnel wasn't properly cleaned up
        // (e.g., rapid protocol switching, service lifecycle timing issues).
        // TProxyStopService is a no-op if nothing is running (checks is_working flag).
        try {
            hev.htproxy.TProxyService.getInstance().TProxyStopService()
            Log.i(TAG, "Pre-start cleanup: TProxyStopService completed")
        } catch (e: Exception) {
            Log.w(TAG, "Pre-start cleanup TProxyStop: ${e.message}")
        }

        // Close any existing VPN interface from a previous session
        try {
            vpnInterface?.close()
        } catch (_: Exception) { }
        vpnInterface = null

        // ── Destination bypass (split tunnelling by IP) ──────────────────────
        // Planned before anything is built, so a malformed list is a log line
        // rather than a half-configured Builder. Returns null — meaning "route
        // exactly as this service always has" — for an empty list, an all-invalid
        // list, or any failure at all.
        val plan = planBypass(bypassCidrs)

        // Claim this generation BEFORE establishing: any teardown still in flight
        // for an earlier one must not touch what is about to be built.
        val generation = ++vpnGeneration

        try {
            vpnInterface = establishTunnel(allowedApps, appListMode, plan)

            if (vpnInterface == null && plan != null) {
                // FAIL OPEN. The bypass is a convenience; the tunnel is the
                // product, and ~500K people in Iran need it more than they need
                // their bank off it. If the platform refused this route set —
                // too many entries, or a shape some ROM dislikes — fall back to
                // the routing that has always worked instead of handing the user
                // no connection.
                Log.w(TAG, "establish() refused the bypass route set — retrying as a full tunnel")
                vpnInterface = establishTunnel(allowedApps, appListMode, null)
            }

            if (vpnInterface == null) {
                Log.e(TAG, "Failed to establish VPN interface")
                return
            }

            Log.i(TAG, "VPN interface established, fd=${vpnInterface?.fd}")

            // Start tun2socks
            startTun2Socks(vpnInterface!!.fd, socksPort, supportsUdp)

            isRunning = true
            // RedShield (brands with BuildConfig.PROTECTION_NAME): automatic
            // rules and profiles back into the core, then the score loop.
            RedShieldEngine.start(this)

            Log.i(TAG, "VPN started successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start VPN: ${e.message}", e)
            stopVpn()
        }
    }

    /**
     * Parse and fit the user's destination-bypass list.
     *
     * Returns null whenever the tunnel should be built exactly as it was before
     * this feature existed: no list, nothing in it parsed, or anything at all
     * went wrong. Never throws — a split-tunnel preference must not be able to
     * cost someone their connection.
     */
    private fun planBypass(cidrs: List<String>): IpRouting.RoutePlan? {
        if (cidrs.isEmpty()) return null
        return try {
            val parsed = ArrayList<IpRouting.Prefix>(cidrs.size)
            for (raw in cidrs) {
                val p = IpRouting.parse(raw)
                if (p == null) {
                    Log.w(TAG, "Bypass entry is not a valid prefix, ignoring: '$raw'")
                    continue
                }
                parsed.add(p)
            }
            if (parsed.isEmpty()) return null

            val plan = IpRouting.planRoutes(parsed)
            if (plan.dropped.isNotEmpty()) {
                // Loud on purpose: an entry the user believes is bypassing the
                // tunnel but is not sends their banking traffic out of a foreign
                // IP. The UI surfaces the same fact — see AppSelectionSheet.
                Log.w(
                    TAG,
                    "Bypass: ${plan.dropped.size} prefix(es) did not fit the route budget and " +
                        "REMAIN IN THE TUNNEL: " +
                        plan.dropped.joinToString(", ") { IpRouting.format(it) }
                )
            }
            Log.i(
                TAG,
                "Bypass: ${plan.applied.size} prefix(es) applied " +
                    "(v4 routes=${plan.v4Routes.size}, v6 routes=${plan.v6Routes.size})"
            )
            if (!plan.v4Requested && !plan.v6Requested) null else plan
        } catch (e: Throwable) {
            Log.e(TAG, "Bypass planning failed — building a full tunnel: ${e.message}")
            null
        }
    }

    /** Build and establish the tun. Returns null instead of throwing. */
    private fun establishTunnel(
        allowedApps: List<String>,
        appListMode: String,
        plan: IpRouting.RoutePlan?
    ): ParcelFileDescriptor? {
        val builder = Builder()
            // Android shows this in Settings > Network > VPN and in the system
            // consent dialog, so every brand was telling its own users it had
            // installed another product's tunnel.
            .setSession(getString(R.string.app_name))
            .setBlocking(false)                    // CRITICAL: non-blocking fd for hev-socks5-tunnel event loop
            .setMtu(1500)                          // Standard Ethernet MTU (8500 was too large for DNS tunneling)
            .addAddress("10.0.0.2", 32)
            .addAddress(HevConfig.TUN_V6, 128)               // IPv6 address for tunnel (ULA)
            .addDnsServer("198.18.0.2")            // mapdns fake DNS server

        applyRoutes(builder, plan)
        applyAppRules(builder, allowedApps, appListMode)

        return try {
            builder.establish()
        } catch (e: Exception) {
            // IllegalArgumentException for a route the platform will not take,
            // IllegalStateException if the builder is inconsistent. Both are the
            // caller's cue to retry without the bypass.
            Log.w(TAG, "establish() threw: ${e.message}")
            null
        }
    }

    /**
     * Install the tun's routes.
     *
     * With no plan this is the routing that has always shipped, call for call.
     *
     * ⚠️ `addRoute("::", 0)` captures ALL IPv6 **deliberately**: it forces IPv4
     * fallback so a v6-capable app cannot leak around a v4-only tunnel. A
     * destination bypass necessarily punches a hole in whichever family it names,
     * so that decision is only ever overridden when the user has explicitly
     * written a v6 prefix — a v4-only list leaves `::/0` exactly as it is.
     */
    private fun applyRoutes(builder: Builder, plan: IpRouting.RoutePlan?) {
        if (plan == null) {
            builder.addRoute("0.0.0.0", 0)         // Route all IPv4 traffic
            builder.addRoute("::", 0)              // Capture all IPv6 → forces IPv4 fallback (no leak)
            return
        }

        // API 33+ says it directly. Below 33 there is no such call at all, which
        // is the whole reason IpRouting computes a complement — minSdk here is 23
        // and cannot rise, so the older path is not a fallback, it is most of the
        // fleet.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            builder.addRoute("0.0.0.0", 0)
            builder.addRoute("::", 0)
            var n = 0
            for (p in plan.applied) {
                try {
                    builder.excludeRoute(IpPrefix(InetAddress.getByAddress(p.bytes), p.length))
                    n++
                } catch (e: Exception) {
                    // One refused prefix stays in the tunnel; the rest still work.
                    Log.w(TAG, "excludeRoute refused ${IpRouting.format(p)}: ${e.message}")
                }
            }
            Log.i(TAG, "Bypass via excludeRoute: $n prefix(es) (API ${Build.VERSION.SDK_INT})")
            return
        }

        if (plan.v4Requested) {
            addComplementRoutes(builder, plan.v4Routes, "v4")
        } else {
            builder.addRoute("0.0.0.0", 0)
        }
        if (plan.v6Requested) {
            addComplementRoutes(builder, plan.v6Routes, "v6")
        } else {
            builder.addRoute("::", 0)
        }
    }

    private fun addComplementRoutes(builder: Builder, routes: List<IpRouting.Prefix>, family: String) {
        // An empty list here is not a mistake: it is what "exclude the entire
        // address space" computes to, and the tun then simply claims nothing of
        // that family. Only reachable if the user wrote 0.0.0.0/0 or ::/0.
        var n = 0
        for (p in routes) {
            try {
                builder.addRoute(InetAddress.getByAddress(p.bytes), p.length)
                n++
            } catch (e: Exception) {
                Log.w(TAG, "addRoute refused ${IpRouting.format(p)}: ${e.message}")
            }
        }
        Log.i(TAG, "Bypass via route complement: $n $family route(s) installed")
    }

    /**
     * Per-app routing. Unchanged behaviour, lifted out of [startVpn] so the
     * bypass fallback can rebuild an otherwise identical tunnel.
     */
    private fun applyAppRules(builder: Builder, allowedApps: List<String>, appListMode: String) {
            // ── Per-app routing ───────────────────────────────────────────────
            // A package that has since been uninstalled throws
            // NameNotFoundException. Keep going rather than aborting the whole
            // tunnel over one stale entry — one dead package must never cost the
            // user their connection.
            // RedShield "Pause this app's internet": only while the system
            // "Block connections without VPN" is on, where a disallowed app has
            // no network at all. Without lockdown the same call would send the
            // app around the tunnel instead, so a pause is then not applied.
            val lockedDown = if (android.os.Build.VERSION.SDK_INT >= 29) runCatching { isLockdownEnabled }.getOrDefault(false) else false
            lockdown = lockedDown
            val paused = if (lockedDown) ShieldAppControls.paused(this) - packageName else emptySet()
            val requested = allowedApps.filter { it.isNotBlank() && it != NONE_SENTINEL && (appListMode == MODE_EXCLUDE || it !in paused) }
            val wantsNone = allowedApps.contains(NONE_SENTINEL)
            var applied = 0

            if (appListMode == MODE_EXCLUDE) {
                // Everything is routed except the listed apps. Self-exclusion is
                // NOT implicit here the way it is in the allowlist branch, and
                // omitting it is a routing loop — our own carrier socket would be
                // handed back to the tun it is carrying. It is applied first and
                // unconditionally, so it survives any failure in the list below.
                try {
                    builder.addDisallowedApplication(packageName)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to exclude self: ${e.message}")
                }
                for (app in requested) {
                    if (app == packageName) continue   // already excluded, and adding twice throws
                    try {
                        builder.addDisallowedApplication(app)
                        applied++
                        Log.i(TAG, "Bypassing tunnel: $app")
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to bypass app $app: ${e.message}")
                    }
                }
                excludePaused(builder, paused - requested.toSet())
                // An empty exclude list is not an empty allowlist: it means "no
                // split tunnelling", i.e. exactly the plain full-tunnel case.
                Log.i(TAG, "Split tunnel: exclude mode, $applied app(s) bypassing (0 = full tunnel)")
            } else if (requested.isNotEmpty()) {
                // Allowlist: ONLY these apps are routed. Our own package is
                // implicitly excluded because it is not in the list.
                for (app in requested) {
                    try {
                        builder.addAllowedApplication(app)
                        applied++
                        Log.i(TAG, "Added allowed app: $app")
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to add app $app: ${e.message}")
                    }
                }
                if (applied == 0) {
                    // Every listed package is gone. The builder now holds no
                    // allowlist at all, which means "route everything" — and with
                    // no self-exclusion that is a routing loop, the opposite of
                    // what the user asked for. Establish an empty tunnel instead.
                    Log.w(TAG, "Every allowed app is uninstalled — establishing an empty tunnel")
                    try {
                        builder.addAllowedApplication(packageName)
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to self-allow: ${e.message}")
                    }
                }
            } else if (wantsNone) {
                // Explicit "no apps": allow only ourselves, so the tun exists but
                // carries nothing. Our carrier sockets are protect()ed and bypass
                // the tun regardless, so this cannot loop. Falling through to the
                // full-tunnel branch here would route everything — the exact
                // inversion this change exists to remove.
                try {
                    builder.addAllowedApplication(packageName)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to self-allow: ${e.message}")
                }
                Log.i(TAG, "Split tunnel: include mode, no apps routed")
            } else {
                // No split tunnelling — route all apps, minus ourselves.
                try {
                    builder.addDisallowedApplication(packageName)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to exclude self: ${e.message}")
                }
                excludePaused(builder, paused)
            }
            if (paused.isNotEmpty()) Log.i(TAG, "RedShield: ${paused.size} app(s) paused (lockdown on)")
    }

    private fun excludePaused(builder: Builder, paused: Set<String>) {
        for (app in paused) {
            try { builder.addDisallowedApplication(app) } catch (e: Exception) { Log.w(TAG, "Failed to pause $app: ${e.message}") }
        }
    }

    private fun startTun2Socks(tunFd: Int, socksPort: Int, supportsUdp: Boolean = false) {
        Log.i(TAG, "Starting hev-socks5-tunnel via JNI with fd=$tunFd, port=$socksPort, udp=$supportsUdp")

        // Truncate old log file to prevent stale data
        try {
            File(filesDir, "hev-socks5-tunnel.log").writeText("")
        } catch (_: Exception) { }

        val configFile = File(filesDir, "hev-socks5-tunnel.yml")
        val configContent = HevConfig.text(filesDir.absolutePath, socksPort, supportsUdp, ConfigManager.tunnelIpv6, BuildConfig.APP_ATTRIBUTION)

        configFile.writeText(configContent)
        Log.i(TAG, "Config written: port=$socksPort, udp=$supportsUdp")

        try {
            hev.htproxy.TProxyService.getInstance().TProxyStartService(configFile.absolutePath, tunFd)
            Log.i(TAG, "TProxyStartService completed successfully")
        } catch (e: Exception) {
            Log.e(TAG, "hev-socks5-tunnel error: ${e.message}", e)
        }
    }

    private fun stopVpn() {
        Log.i(TAG, "Stopping VPN")
        stopLiveUpdates()
        RedShieldEngine.stop()

        val tun = vpnInterface
        val generation = vpnGeneration
        vpnInterface = null
        isRunning = false

        // Run blocking cleanup on background thread to avoid ANR
        Thread {
                try {
                hev.htproxy.TProxyService.getInstance().TProxyStopService()
                Log.i(TAG, "TProxyStopService called")
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping tunnel: ${e.message}")
            }

            // Only close what no later start has replaced. A mismatch means a
            // restart already built a new interface on the same tun device, and
            // closing now would tear ITS routes down (see vpnGeneration).
            if (vpnGeneration != generation) {
                Log.i(TAG, "stale teardown: gen $generation superseded by $vpnGeneration, tun left alone")
            } else {
                try {
                    tun?.close()
                } catch (e: Exception) {
                    Log.w(TAG, "Tun interface close: ${e.message}")
                }
            }
            Log.i(TAG, "VPN stopped (background)")
        }.start()

        // Defer service shutdown to allow protocol switch restarts.
        // If a new ACTION_START arrives within the window, cancelPendingShutdown()
        // prevents the service from being destroyed (avoiding the Android VPN
        // subsystem race where the old disconnect tears down the new interface).
        schedulePendingShutdown()

        Log.i(TAG, "VPN stopped (shutdown deferred)")
    }

    private fun schedulePendingShutdown() {
        cancelPendingShutdown()
        pendingShutdown = Runnable {
            Log.i(TAG, "Deferred shutdown executing")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        handler.postDelayed(pendingShutdown!!, 3000)
    }

    private fun cancelPendingShutdown() {
        pendingShutdown?.let {
            handler.removeCallbacks(it)
            Log.i(TAG, "Cancelled pending shutdown (protocol switch)")
        }
        pendingShutdown = null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "rostam_vpn",
                // The ID above stays "rostam_vpn" FOREVER: it is the key Android
                // files the user's own notification preferences under, so changing
                // it silently resets importance, sound and the block state on every
                // existing install. This NAME is the part a user actually reads.
                getString(R.string.app_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "VPN connection status"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    // ── Live stats notification (FEATURE_LIVE_STATUS) ───────────────────────
    // Same channel and id as before (IMPORTANCE_LOW: no sound). Posted at once
    // when what it shows changes (state, route, blocked count), otherwise on a
    // 60 s tick for the bytes and the eSIM line; setOnlyAlertOnce. On Android
    // 13+ without the notification permission (asked once, for eSIM alerts)
    // only the foreground start posts it; updates are skipped.
    private val liveScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main)
    private var liveJob: kotlinx.coroutines.Job? = null

    private fun startLiveUpdates() {
        liveJob?.cancel()
        liveJob = liveScope.launch {
            var last: LiveStatus.Snapshot? = null
            launch {
                while (true) {
                    kotlinx.coroutines.delay(60_000)
                    postLive()
                }
            }
            LiveStatus.flow.collect { s ->
                val l = last
                if (l == null || LiveStatus.visibleChange(l, s)) { last = s; postLive() }
            }
        }
    }

    private fun stopLiveUpdates() { liveJob?.cancel(); liveJob = null }

    private fun postLive() {
        if (!isRunning) return
        val nm = androidx.core.app.NotificationManagerCompat.from(this)
        if (!nm.areNotificationsEnabled()) return
        runCatching { nm.notify(1, liveNotification()) }
    }

    private fun liveNotification(): Notification {
        val s = LiveStatus.flow.value
        val g = com.rostamvpn.android.ui.screens.EsimGlanceStore.load(this)
        val dataLeft = g?.let {
            when {
                it.unlimited -> getString(R.string.tabs_unlimited)
                it.dataLeftMb != null -> com.rostamvpn.android.ui.screens.dataLabel(this, it.dataLeftMb)
                else -> null
            }
        }
        val fmt = { b: Long -> android.text.format.Formatter.formatShortFileSize(this, b) }
        val lines = LiveNotificationText.lines(s, g, dataLeft, BuildConfig.FEATURE_PROTECTION, fmt(s.rxBytes), fmt(s.txBytes),
            System.currentTimeMillis()).map { getString(it.res, *it.args.toTypedArray()) }
        val open = PendingIntent.getActivity(this, 0, packageManager.getLaunchIntentForPackage(packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 2, Intent(this, TunnelVpnService::class.java).setAction(ACTION_USER_DISCONNECT),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return androidx.core.app.NotificationCompat.Builder(this, "rostam_vpn")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(LiveNotificationText.titleRes(s.state)))
            .setContentText(lines.firstOrNull() ?: getString(R.string.app_name))
            .setStyle(androidx.core.app.NotificationCompat.BigTextStyle().bigText(lines.joinToString("\n")))
            .setContentIntent(open)
            .addAction(0, getString(R.string.tabs_disconnect), stop)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_LOW)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
    }

    private fun createNotification(): Notification {
        if (BuildConfig.FEATURE_LIVE_STATUS) return liveNotification()
        val intent = packageManager.getLaunchIntentForPackage(packageName)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, "rostam_vpn")
                .setContentTitle(getString(R.string.app_name))
                .setContentText("VPN connected")
                .setSmallIcon(R.drawable.ic_notification)
                .setContentIntent(pendingIntent)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle(getString(R.string.app_name))
                .setContentText("VPN connected")
                .setSmallIcon(R.drawable.ic_notification)
                .setContentIntent(pendingIntent)
                .build()
        }
    }

    override fun onDestroy() {
        cancelPendingShutdown()
        stopLiveUpdates()
        RedShieldEngine.stop()
        // Final cleanup — stop tun2socks if still running
        try {
            hev.htproxy.TProxyService.getInstance().TProxyStopService()
        } catch (_: Exception) { }
        try {
            vpnInterface?.close()
        } catch (_: Exception) { }
        vpnInterface = null
        isRunning = false
        instance = null
        super.onDestroy()
    }

    override fun onRevoke() {
        stopVpn()
        super.onRevoke()
    }
}
