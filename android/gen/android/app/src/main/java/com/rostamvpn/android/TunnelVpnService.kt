// Copyright 2026 Internet Mastering & Company, Inc.
// SPDX-License-Identifier: Apache-2.0

package com.rostamvpn.android

import android.app.Notification
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
import kotlinx.coroutines.flow.MutableSharedFlow
import java.io.File
import java.net.InetAddress

class TunnelVpnService : VpnService() {
    companion object {
        const val TAG = "RostamVPN"
        const val ACTION_START = "com.rostamvpn.android.START_VPN"
        const val ACTION_STOP = "com.rostamvpn.android.STOP_VPN"

        /** Bridge the tun to [EXTRA_SOCKS_PORT], the port the core reported it bound. */
        const val ACTION_BRIDGE = "com.rostamvpn.android.BRIDGE"
        const val EXTRA_SOCKS_PORT = "socks_port"
        const val EXTRA_ALLOWED_APPS = "allowed_apps"
        const val EXTRA_SUPPORTS_UDP = "supports_udp"

        const val EXTRA_APP_LIST_MODE = "app_list_mode"

        /** Destination prefixes that skip the tunnel, as CIDR text. */
        const val EXTRA_BYPASS_CIDRS = "bypass_cidrs"

        /** Route ONLY the listed apps (the historical, allowlist behaviour). */
        const val MODE_INCLUDE = "include"

        /** Route everything EXCEPT the listed apps (bypass / "take it out"). */
        const val MODE_EXCLUDE = "exclude"

        /**
         * Include-mode sentinel for "no apps at all", which an empty list cannot
         * express there (an empty allowlist means "route everything"). Never appears
         * in an exclude list, where empty already means "exclude nothing".
         */
        const val NONE_SENTINEL = "_none_"

        private const val PREFS = "rostam_settings"
        private const val PREF_MODE = "split_tunnel_mode"
        private const val PREF_APPS = "allowed_apps_json"
        private const val PREF_CIDRS = "bypass_cidrs_json"

        /**
         * The persisted mode, and the fallback for any start path that did not attach
         * [EXTRA_APP_LIST_MODE]. Every caller reads it from here so the several start
         * paths cannot disagree about which way round the list is.
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

        /** The persisted destination-bypass list, for start paths that carry none. */
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
        @Volatile
        var isRunning = false
        @JvmField
        var instance: TunnelVpnService? = null

        /** Emits when the platform revokes the tun or it cannot be established. */
        val tunLost = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

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
                cancelPendingShutdown()
                val allowedApps = intent.getStringArrayListExtra(EXTRA_ALLOWED_APPS) ?: arrayListOf()

                val mode = intent.getStringExtra(EXTRA_APP_LIST_MODE) ?: savedMode(this)

                val bypass = intent.getStringArrayListExtra(EXTRA_BYPASS_CIDRS)
                    ?: savedBypassCidrs(this)
                startVpn(allowedApps, mode, bypass)
            }
            ACTION_BRIDGE -> {
                val tun = vpnInterface
                val port = intent.getIntExtra(EXTRA_SOCKS_PORT, 0)
                if (tun != null && port in 1..65535) {
                    startTun2Socks(tun.fd, port, intent.getBooleanExtra(EXTRA_SUPPORTS_UDP, false))
                } else {
                    Log.w(TAG, "Bridge not started: tun=${tun != null}, port=$port")
                }
            }
            ACTION_STOP -> {
                stopVpn()
            }
        }
        return START_NOT_STICKY
    }

    private fun startVpn(
        allowedApps: List<String>,
        appListMode: String = MODE_INCLUDE,
        bypassCidrs: List<String> = emptyList()
    ) {
        Log.i(
            TAG,
            "Starting VPN, mode: $appListMode, apps: $allowedApps, " +
                "bypass: ${bypassCidrs.size} prefix(es)"
        )

        try {
            startForeground(1, createNotification())
        } catch (e: Throwable) {
            Log.w(TAG, "startForeground failed: ${e.message}")
        }

        try {
            hev.htproxy.TProxyService.getInstance().TProxyStopService()
            Log.i(TAG, "Pre-start cleanup: TProxyStopService completed")
        } catch (e: Throwable) {
            Log.w(TAG, "Pre-start cleanup TProxyStop: ${e.message}")
        }

        try {
            vpnInterface?.close()
        } catch (_: Exception) { }
        vpnInterface = null

        val plan = planBypass(bypassCidrs)

        val generation = ++vpnGeneration

        try {
            vpnInterface = establishTunnel(allowedApps, appListMode, plan)

            if (vpnInterface == null && plan != null) {
                Log.w(TAG, "establish() refused the bypass route set — retrying as a full tunnel")
                vpnInterface = establishTunnel(allowedApps, appListMode, null)
            }

            if (vpnInterface == null) {
                Log.e(TAG, "Failed to establish VPN interface")
                stopVpn()
                tunLost.tryEmit(Unit)
                return
            }

            Log.i(TAG, "VPN interface established, fd=${vpnInterface?.fd}")

            isRunning = true

            Log.i(TAG, "VPN started; the bridge waits for the core's port")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start VPN: ${e.message}", e)
            stopVpn()
            tunLost.tryEmit(Unit)
        }
    }

    /** Parse and fit the user's destination-bypass list. */
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

            .setSession(getString(R.string.app_name))
            .setBlocking(false)
            .setMtu(1500)
            .addAddress("10.0.0.2", 32)
            .addAddress("fd00::2", 128)
            .addDnsServer("198.18.0.2")

        applyRoutes(builder, plan)
        applyAppRules(builder, allowedApps, appListMode)

        return try {
            builder.establish()
        } catch (e: Exception) {
            Log.w(TAG, "establish() threw: ${e.message}")
            null
        }
    }

    /** Install the tun's routes. */
    private fun applyRoutes(builder: Builder, plan: IpRouting.RoutePlan?) {
        if (plan == null) {
            builder.addRoute("0.0.0.0", 0)
            builder.addRoute("::", 0)
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            builder.addRoute("0.0.0.0", 0)
            builder.addRoute("::", 0)
            var n = 0
            for (p in plan.applied) {
                try {
                    builder.excludeRoute(IpPrefix(InetAddress.getByAddress(p.bytes), p.length))
                    n++
                } catch (e: Exception) {
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
     * Per-app routing. Unchanged behaviour, lifted out of [startVpn] so the bypass
     * fallback can rebuild an otherwise identical tunnel.
     */
    private fun applyAppRules(builder: Builder, allowedApps: List<String>, appListMode: String) {
            val requested = allowedApps.filter { it.isNotBlank() && it != NONE_SENTINEL }
            val wantsNone = allowedApps.contains(NONE_SENTINEL)
            var applied = 0

            if (appListMode == MODE_EXCLUDE) {
                try {
                    builder.addDisallowedApplication(packageName)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to exclude self: ${e.message}")
                }
                for (app in requested) {
                    if (app == packageName) continue
                    try {
                        builder.addDisallowedApplication(app)
                        applied++
                        Log.i(TAG, "Bypassing tunnel: $app")
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to bypass app $app: ${e.message}")
                    }
                }

                Log.i(TAG, "Split tunnel: exclude mode, $applied app(s) bypassing (0 = full tunnel)")
            } else if (requested.isNotEmpty()) {
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
                    Log.w(TAG, "Every allowed app is uninstalled — establishing an empty tunnel")
                    try {
                        builder.addAllowedApplication(packageName)
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to self-allow: ${e.message}")
                    }
                }
            } else if (wantsNone) {
                try {
                    builder.addAllowedApplication(packageName)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to self-allow: ${e.message}")
                }
                Log.i(TAG, "Split tunnel: include mode, no apps routed")
            } else {
                try {
                    builder.addDisallowedApplication(packageName)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to exclude self: ${e.message}")
                }
            }
    }

    private fun startTun2Socks(tunFd: Int, socksPort: Int, supportsUdp: Boolean) {
        Log.i(TAG, "Starting hev-socks5-tunnel via JNI with fd=$tunFd, port=$socksPort, udp=$supportsUdp")

        val logFile = File(filesDir, "hev-socks5-tunnel.log")
        try {
            if (BuildConfig.DEBUG) logFile.writeText("") else logFile.delete()
        } catch (_: Exception) { }

        val configFile = File(filesDir, "hev-socks5-tunnel.yml")
        configFile.writeText(hevConfig(socksPort, logFile.absolutePath.takeIf { BuildConfig.DEBUG }, supportsUdp))
        Log.i(TAG, "Config written: port=$socksPort, udp=$supportsUdp")

        try {
            hev.htproxy.TProxyService.getInstance().TProxyStartService(configFile.absolutePath, tunFd)
            Log.i(TAG, "TProxyStartService completed successfully")
        } catch (e: Throwable) {
            Log.e(TAG, "hev-socks5-tunnel error: ${e.message}", e)
        }
    }

    private fun stopVpn() {
        Log.i(TAG, "Stopping VPN")

        val tun = vpnInterface
        val generation = vpnGeneration
        vpnInterface = null
        isRunning = false

        Thread {
                try {
                hev.htproxy.TProxyService.getInstance().TProxyStopService()
                Log.i(TAG, "TProxyStopService called")
            } catch (e: Throwable) {
                Log.e(TAG, "Error stopping tunnel: ${e.message}")
            }

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

                getString(R.string.app_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "VPN connection status"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val intent = packageManager.getLaunchIntentForPackage(packageName)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, "rostam_vpn")
                .setContentTitle(getString(R.string.app_name))
                .setContentText("VPN connected")
                .setSmallIcon(android.R.drawable.ic_lock_lock)
                .setContentIntent(pendingIntent)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle(getString(R.string.app_name))
                .setContentText("VPN connected")
                .setSmallIcon(android.R.drawable.ic_lock_lock)
                .setContentIntent(pendingIntent)
                .build()
        }
    }

    override fun onDestroy() {
        cancelPendingShutdown()

        try {
            hev.htproxy.TProxyService.getInstance().TProxyStopService()
        } catch (_: Throwable) { }
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
        tunLost.tryEmit(Unit)
        super.onRevoke()
    }
}

/**
 * The hev-socks5-tunnel config that bridges the tun to the core's SOCKS5 [port].
 * Without a [logFile] the bridge logs warnings only, and nothing to disk.
 */
internal fun hevConfig(port: Int, logFile: String?, udp: Boolean): String {
    require(port in 1..65535) { "no SOCKS port to bridge to: $port" }
    val udpConfig = if (udp) "\n  udp: 'tcp'" else ""
    val logging = if (logFile != null) "log-file: $logFile\n  log-level: debug" else "log-level: warn"
    return """
misc:
  task-stack-size: 81920
  $logging

tunnel:
  mtu: 1500
  ipv4: '10.0.0.2'

socks5:
  address: '127.0.0.1'
  port: $port$udpConfig

mapdns:
  address: '198.18.0.2'
  port: 53
  network: '100.64.0.0'
  netmask: '255.192.0.0'
  cache-size: 10000
""".trimIndent()
}
