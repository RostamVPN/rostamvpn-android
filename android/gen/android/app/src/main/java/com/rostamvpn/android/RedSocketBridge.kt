// Copyright 2026 Internet Mastering & Company, Inc.
// SPDX-License-Identifier: Apache-2.0

package com.rostamvpn.android

/** JNI bridge to libredsocket_core.so. */
object RedSocketBridge {
    /** True only if libredsocket_core.so loaded successfully. */
    @JvmStatic
    var isLoaded: Boolean = false
        private set

    init {
        try {
            System.loadLibrary("redsocket_core")
            isLoaded = true
        } catch (t: Throwable) {
            android.util.Log.e("RedSocketBridge", "loadLibrary failed: $t")
        }
    }

    /**
     * Carrier list for display, computed in the lib from the loaded config — the
     * carriers redsocket actually supports + is configured for (NOT a hardcoded
     * host list). JSON array of {id,name,region,transport,desc}.
     */
    external fun rsCarriers(): String

    /**
     * Inject the app-owned client id (cid) so redsocket uses it instead of minting
     * its own. Call once at startup, before start().
     */
    external fun setClientId(id: String)

    /**
     * Inject an opaque per-session tag for the relay subsystem. Independent of
     * [setClientId]. Pass empty to clear. Call before [startPeer].
     */
    external fun setPeerTag(id: String)

    /** Start RedSocket tunnel. Returns "ok:<entry_name>" or "err:<message>". */
    /** Verify and decrypt a sealed config. Returns the JSON, or "err:...". */
    fun unsealConfig(sealedBase64: String, certSha256Hex: String): String {
        if (!isLoaded) return "err:not-loaded"
        return try {
            nativeUnsealConfig(sealedBase64, certSha256Hex)
        } catch (t: Throwable) {
            "err:${t.javaClass.simpleName}"
        }
    }

    private external fun nativeUnsealConfig(sealedBase64: String, certSha256Hex: String): String

    external fun start(socksPort: Int): String

    /** Stop the tunnel. */
    external fun stop()

    /** Check if tunnel is running. */
    external fun isRunning(): Int

    /** The SOCKS port the core ACTUALLY bound, or 0 when it is not running. */
    external fun getSocksPort(): Int

    /**
     * Get connection status as JSON: {"status":"connected","carrier":"Tls:ga1-tls-
     * ds"}.
     */
    external fun getConnectionStatus(): String

    /** Get the active carrier name, e.g. "http" for redsocket. */
    external fun getActiveCarrier(): String

    /** Report whether the endpoint now carrying traffic actually works. */
    external fun markActiveCarrier(ok: Boolean)

    /** Load config JSON into the Rust backend (for legacy protocol support). */
    external fun loadConfig(configJson: String)

    /** Get current race tier status: "tier:0" through "tier:5", or empty. */
    external fun getRaceStatus(): String

    /** Run full network diagnostics. Returns human-readable report string. */
    external fun runDiagnostics(): String

    /** Set the session zone hint (IANA tz string). */
    external fun setPeerZone(tz: String)

    /** Set the app tag prefixed onto the relay id. Empty = no prefix. */
    external fun setPeerApp(app: String)

    /** Set the relay route endpoint from config (host + port). Empty host = none. */
    external fun setPeerRoute(host: String, port: Int)

    /** Start the relay on demand (after gating; call enablePeer() first). */
    external fun startPeer(socksPort: Int)

    /** Enable the relay subsystem. */
    external fun enablePeer()

    /** Disable the relay subsystem. */
    external fun disablePeer()

    /** Set protocol filter before start(). Pass "auto" or "" to race all. */
    external fun setProtocolFilter(protocol: String)

    /**
     * Set geographic server region before start() (e.g. "fra", "lax"). "auto" / ""
     * = race all regions. The user picks a region; redsocket decides which
     * protocol to use within it.
     */
    external fun setRegion(region: String)

    /**
     * Entitlement tier. `true` unlocks the config's `premium_entries` POP pool
     * (tried first, shared pool kept as fallback); `false` restricts this install
     * to the shared pool.
     */
    external fun setPremium(premium: Boolean)

    /**
     * Free-tier speed limit in kbps; 0 = unlimited. Applies to the whole tunnel,
     * both directions. The APP decides who is limited — paying users and freedom
     * regions never are — the core only enforces what it is told.
     */
    external fun setRateLimitKbps(kbps: Int)

    /**
     * May this install use the CDN/name fallback tier? CloudFront egress is paid
     * per byte, so metered free users are held to the direct POPs.
     */
    external fun setCdnAllowed(allowed: Boolean)

    /**
     * Master gate for destination filtering: premium **or** freedom region. The
     * core cannot determine either, so this is the whole gate; it defaults to off
     * there, and a missing call can never start filtering someone's traffic.
     */
    external fun setBlockingEnabled(enabled: Boolean)

    /** Replace both user lists at once: {"allow":[...],"deny":[...]}. */
    external fun setUserRules(json: String): String

    /** The user's own rules as authored, for the settings screen. */
    external fun getUserRules(): String

    /**
     * Downstream control-stream messages ({seq, ts, type, payload}) queued by the
     * core since the last call, as a JSON array, oldest first. Drains.
     */
    external fun drainNotes(): String
}
