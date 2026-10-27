/*
 * Copyright 2026 Internet Mastering & Company, Inc. <sina@redteam.net>
 * SPDX-License-Identifier: Apache-2.0
 */
package com.rostamvpn.android

import app.netcore.NetCoreBridge
import android.util.Log

/**
 * JNI bridge to `libwgtcp_core.so` — WGTCP (WireGuard over a TCP carrier),
 * the second protocol alongside the server.
 *
 * Deliberately shaped like [NetCoreBridge]: both serve SOCKS5 on
 * 127.0.0.1:<port> so `hev-socks5-tunnel` and `TunnelVpnService` are reused
 * unchanged, and nothing above the SOCKS5 boundary needs to know which
 * protocol won.
 *
 * Every native call is guarded by [isLoaded] and wrapped in try/catch. An
 * unguarded native call into an unloaded library is the crash pattern that
 * produced ~47K crashes historically — do not remove the guards.
 */
object WgtcpBridge {

    private const val TAG = "WgtcpBridge"

    /**
     * True when the native library loaded. A missing or incompatible `.so`
     * must degrade to "this protocol is unavailable", never to a crash.
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
            // UnsatisfiedLinkError on an ABI without the lib, or any linker
            // failure. Caught deliberately: WGTCP simply won't be offered.
            Log.w(TAG, "libwgtcp_core.so not available: ${t.message}")
            false
        }
    }

    /**
     * Start the tunnel. [configJson] carries the WireGuard keys, the carrier
     * endpoint and the inner address; [socksPort] is where SOCKS5 is served.
     *
     * Returns `"ok"` on success or `"err:<reason>"` on failure. The reason is
     * diagnostic only — callers must branch on the `ok` prefix and never parse
     * the remainder, so the native side stays free to change its wording.
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

    /** Last status or failure reason, for diagnostics and logs. */
    fun connectionStatus(): String {
        if (!isLoaded) return "not-loaded"
        return try {
            nativeGetConnectionStatus()
        } catch (t: Throwable) {
            "err:jni:${t.message}"
        }
    }

    /**
     * Generate a WireGuard keypair: "<private_b64>:<public_b64>", or "err:...".
     *
     * Native rather than Kotlin because Android's JCA only gained X25519 at API
     * 31 and this app ships to minSdk 23 -- a Kotlin implementation would leave
     * older devices unable to provision at all, and hand-rolling curve25519 to
     * avoid that is not a trade worth making when the crypto is already linked.
     */
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

    private external fun nativeGetConnectionStatus(): String
}
