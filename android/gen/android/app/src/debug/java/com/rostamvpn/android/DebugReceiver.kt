// Copyright 2026 Internet Mastering & Company, Inc.
// SPDX-License-Identifier: Apache-2.0

package com.rostamvpn.android

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.util.Log

/** Debug-only BroadcastReceiver to trigger VPN start from ADB. */
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
        val port = intent.getIntExtra("port", 0)
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
            putStringArrayListExtra(TunnelVpnService.EXTRA_ALLOWED_APPS,
                arrayListOf<String>())
        }
        context.startForegroundService(vpnIntent)
        if (port > 0) {
            context.startForegroundService(Intent(context, TunnelVpnService::class.java).apply {
                action = TunnelVpnService.ACTION_BRIDGE
                putExtra(TunnelVpnService.EXTRA_SOCKS_PORT, port)
                putExtra(TunnelVpnService.EXTRA_SUPPORTS_UDP, supportsUdp)
            })
        }
        Log.i(TAG, "VPN service start intent sent")
    }

    private fun handleStopVpn(context: Context) {
        Log.i(TAG, "STOP_VPN received — stopping VPN services")

        if (RedSocketBridge.isLoaded) {
            try {
                RedSocketBridge.stop()
                Log.i(TAG, "RedSocket stopped")
            } catch (t: Throwable) {
                Log.w(TAG, "RedSocket stop: $t")
            }
        }

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
