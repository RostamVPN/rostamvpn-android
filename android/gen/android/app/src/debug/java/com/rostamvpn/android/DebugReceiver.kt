package com.rostamvpn.android

import app.netcore.NetCoreBridge
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.util.Log

/**
 * Debug-only BroadcastReceiver to trigger VPN start from ADB.
 *
 * Start VPN with SOCKS5 port already listening:
 *   adb shell am broadcast -a com.rostamvpn.android.DEBUG_START_VPN \
 *     --ei port 1080 -n com.rostamvpn.android/.DebugReceiver
 *
 * Stop VPN:
 *   adb shell am broadcast -a com.rostamvpn.android.STOP_VPN \
 *     -n com.rostamvpn.android/.DebugReceiver
 */
class DebugReceiver : BroadcastReceiver() {
    companion object {
        const val TAG = "DebugReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            "com.rostamvpn.android.DEBUG_START_VPN" -> handleStartVpn(context, intent)
            "com.rostamvpn.android.STOP_VPN" -> handleStopVpn(context)
            else -> Log.w(TAG, "Unknown action: ${intent.action}")
        }
    }

    private fun handleStartVpn(context: Context, intent: Intent) {
        val port = intent.getIntExtra("port", 1080)
        val supportsUdp = intent.getBooleanExtra("udp", true)
        Log.i(TAG, "DEBUG_START_VPN received, port=$port, udp=$supportsUdp")

        val prepareIntent = VpnService.prepare(context)
        if (prepareIntent != null) {
            Log.e(TAG, "VPN permission NOT granted. Cannot start from broadcast.")
            return
        }
        Log.i(TAG, "VPN permission already granted, starting service")

        val vpnIntent = Intent(context, TunnelVpnService::class.java).apply {
            action = TunnelVpnService.ACTION_START
            putExtra(TunnelVpnService.EXTRA_SOCKS_PORT, port)
            putStringArrayListExtra(TunnelVpnService.EXTRA_ALLOWED_APPS,
                arrayListOf<String>())
            putExtra(TunnelVpnService.EXTRA_SUPPORTS_UDP, supportsUdp)
        }
        context.startForegroundService(vpnIntent)
        Log.i(TAG, "VPN service start intent sent")
    }

    private fun handleStopVpn(context: Context) {
        Log.i(TAG, "STOP_VPN received — stopping VPN services")

        // Stop NetCore (guard isLoaded + Throwable: native call must not crash)
        if (NetCoreBridge.isLoaded) {
            try {
                NetCoreBridge.stop()
                Log.i(TAG, "NetCore stopped")
            } catch (t: Throwable) {
                Log.w(TAG, "NetCore stop: $t")
            }
        }

        // Stop TunnelVpnService
        try {
            context.startService(Intent(context, TunnelVpnService::class.java).apply {
                action = TunnelVpnService.ACTION_STOP
            })
            Log.i(TAG, "TunnelVpnService ACTION_STOP sent")
        } catch (e: Exception) {
            Log.w(TAG, "TunnelVpnService stop failed (not running?): ${e.message}")
        }
    }
}
