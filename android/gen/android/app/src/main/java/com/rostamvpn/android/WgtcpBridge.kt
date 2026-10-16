// Copyright 2026 Internet Mastering & Company, Inc.
// SPDX-License-Identifier: Apache-2.0

package com.rostamvpn.android

import android.util.Log

/**
 * JNI bridge to `libwgtcp_core.so` — WGTCP (WireGuard over a TCP carrier), the
 * second protocol alongside redsocket_http.
 */
object WgtcpBridge {
    private const val TAG = "WgtcpBridge"

    /**
     * True when the native library loaded. A missing or incompatible `.so` must
     * degrade to "this protocol is unavailable", never to a crash.
     */
    @JvmStatic
    var isLoaded: Boolean = false
        private set

    init {
        isLoaded = try {
            System.loadLibrary("wgtcp_core")
            Log.i(TAG, "libwgtcp_core.so loaded")
            true
        } catch (t: Throwable) {
            Log.w(TAG, "libwgtcp_core.so not available: ${t.message}")
            false
        }
    }

    /**
     * Start the tunnel. [configJson] carries the WireGuard keys, the carrier
     * endpoint and the inner address; [socksPort] is where SOCKS5 is served.
     */
    fun start(configJson: String, socksPort: Int): String {
        if (!isLoaded) return "err:not-loaded"
        return try {
            nativeStart(configJson, socksPort)
        } catch (t: Throwable) {
            Log.w(TAG, "start failed: ${t.message}")
            "err:jni:${t.message}"
        }
    }

    /** Stop the tunnel. Safe to call when already stopped. */
    fun stop() {
        if (!isLoaded) return
        try {
            nativeStop()
        } catch (t: Throwable) {
            Log.w(TAG, "stop failed: ${t.message}")
        }
    }

    /** True while the tunnel is up. */
    fun isRunning(): Boolean {
        if (!isLoaded) return false
        return try {
            nativeIsRunning() == 1
        } catch (t: Throwable) {
            false
        }
    }

    /** Generate a WireGuard keypair: "<private_b64>:<public_b64>", or "err:...". */
    fun generateKeypair(): String {
        if (!isLoaded) return "err:not-loaded"
        return try {
            nativeGenerateKeypair()
        } catch (t: Throwable) {
            "err:${t.javaClass.simpleName}"
        }
    }

    private external fun nativeGenerateKeypair(): String

    private external fun nativeStart(configJson: String, socksPort: Int): String

    private external fun nativeStop()

    private external fun nativeIsRunning(): Int

}
