package app.netcore

/**
 * JNI bridge to libnetcore.so
 *
 * One function: start(port) -> races 16 AWS entry points via Noise NK,
 * returns SOCKS5 proxy on the given port.
 */
object NetCoreBridge {
    /**
     * True only if libnetcore.so loaded successfully. Callers MUST check
     * this before invoking any `external fun` — a native call into an unloaded
     * (or symbol-incomplete) library throws UnsatisfiedLinkError, which is a
     * java.lang.Error (NOT an Exception) and will crash the process if it
     * escapes. This guard is the contract that keeps the app alive on devices
     * where the .so is missing an ABI or a symbol. Mirrors RustBridge.isLoaded.
     */
    @JvmStatic
    var isLoaded: Boolean = false
        private set

    init {
        // Guard the load: a LinkageError (java.lang.Error, not Exception) here
        // would otherwise crash app init. The .so exports a superset of these
        // symbols, so this normally succeeds.
        try {
            System.loadLibrary("netcore")
            isLoaded = true
        } catch (t: Throwable) {
            android.util.Log.e("NetCore", "loadLibrary failed: $t")
        }
    }

    /**
     * Carrier list for display, computed in the lib from the loaded config —
     * the carriers the core actually supports + is configured for (NOT a
     * hardcoded host list). JSON array of {id,name,region,transport,desc}.
     */
    external fun listCarriers(): String

    /** Inject the app-owned client id (cid) so the core uses it instead of
     *  minting its own. Call once at startup, before start(). */
    external fun setClientId(id: String)

    /** Start the tunnel. Returns "ok:<entry_name>" or "err:<message>" */
    /**
     * Verify and decrypt a sealed config. Returns the JSON, or "err:...".
     *
     * The unwrapping lives in the native library rather than here: dex
     * decompiles cleanly and R8 leaves string literals intact, so a key in
     * Kotlin is one `jadx` run away. The key never crosses into managed code.
     */
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

    /** Stop the tunnel */
    external fun stop()

    /** Check if tunnel is running */
    external fun isRunning(): Int

    /**
     * The SOCKS port the core ACTUALLY bound, or 0 when it is not running.
     *
     * NOT the port we asked for. 1080 is the registered SOCKS port, so another
     * proxy or dev tool on the device can already hold it; the core falls back
     * to one the OS picks rather than failing to start. Everything that dials
     * the tunnel -- hev's tun bridge, the health probe -- must
     * read this AFTER a successful start. Using the constant instead is a
     * connection to a port nothing is listening on, on exactly the crowded
     * devices most likely to belong to someone who needs this app.
     */
    external fun getSocksPort(): Int

    /** Get connection status as JSON: {"status":"connected","carrier":"Tls:ga1-tls-ds"} */
    external fun getConnectionStatus(): String

    /** Get the active carrier name, e.g. "http" for the http carrier. */
    external fun getActiveCarrier(): String

    /**
     * Report whether the endpoint now carrying traffic actually works.
     *
     * A handshake proves the POP answered; it does not prove a byte reaches the
     * internet. The connect path runs one HTTP check through the tunnel and
     * reports the verdict here, so selection can skip an endpoint that looked
     * perfect right up until traffic had to move through it.
     *
     * `false` marks the CURRENT endpoint bad and it is skipped next time.
     * `true` clears any previous mark, so one that recovers comes back on its
     * own. The core reads its own active label -- nothing is passed in, because
     * a reconnect can change the winner underneath the caller.
     */
    external fun markActiveCarrier(ok: Boolean)

    /** Load config JSON into the Rust backend (for legacy protocol support) */
    external fun loadConfig(configJson: String)

    /** Get current race tier status: "tier:0" through "tier:5", or empty */
    external fun getRaceStatus(): String




    /** Run full network diagnostics. Returns human-readable report string. */
    external fun runDiagnostics(): String

    /** The device timezone (IANA id); the config's region policy reads it. */
    external fun setTimezone(tz: String)

    /** The brand tag the MUX2 hello carries. Empty = none. */
    external fun setAppTag(app: String)


    /** Set protocol filter before start(). Pass "auto" or "" to race all. */
    external fun setProtocolFilter(protocol: String)

    /** Set geographic server region before start() (e.g. "fra", "lax").
     *  "auto" / "" = race all regions. The user picks a region; the core
     *  decides which protocol to use within it. */
    external fun setRegion(region: String)

    /**
     * Entitlement tier. `true` unlocks the config's `premium_entries` POP pool
     * (tried first, shared pool kept as fallback); `false` restricts this install
     * to the shared pool. Read when start() builds the endpoint list, so call it
     * before start(). Defaults to false in the core — a missing call can never
     * hand free users the premium POPs.
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
     * per byte, so metered free users are held to the direct POPs. Paying users
     * and freedom regions keep it — for the latter it is the only thing that
     * works when the direct IPs are blocked.
     */
    external fun setCdnAllowed(allowed: Boolean)

    // ── On-device filtering (malware / bandwidth-consuming SDKs) ────────────
    //
    // ⚠️ These require a libnetcore.so built from the netcore crate at
    // or after the change that added them. The committed jniLibs copy is NOT
    // rebuilt automatically (see CLAUDE.md §6) — a declaration with no matching
    // export throws UnsatisfiedLinkError, which is a java.lang.Error and will
    // crash the process. Nothing calls these yet; guard every call site with
    // `isLoaded` and a try/catch, exactly as SettingsSheet does for the peer
    // switch.

    /**
     * Master gate for destination filtering: premium **or** freedom region.
     * The core cannot determine either, so this is the whole gate; it defaults
     * to off there, and a missing call can never start filtering someone's
     * traffic. Call it whenever entitlement or region changes.
     */
    external fun setBlockingEnabled(enabled: Boolean)

    /**
     * Install a blocklist document the app fetched itself (e.g. over TLS).
     * Optional: the core fetches its own list through the tunnel when
     * `blocklist.url` is set in the config. Returns
     * "ok:<domains>:<networks>:<skipped>" or "err:...".
     */
    external fun setBlocklist(json: String): String

    /**
     * Filtering stats as JSON: counts, per-category totals, and the recent
     * blocks (host, port, category, secs_ago) so the UI can alert the user and
     * offer "allow this" in place.
     *
     * PRIVACY: `recent` is a list of destinations this user tried to reach. It
     * is memory-only, capped, and dropped at disconnect. Display it — never log
     * it, never send it to the cashier or any other feed.
     */
    external fun getBlockStats(): String

    /**
     * Add or clear one of the user's own rules. `verdict` is "allow", "deny" or
     * "clear". Takes effect on the very next connection — no reconnect — which
     * is what makes an "Allow this" button on the block notice work.
     *
     * User allow beats everything, including the shipped list: it is the
     * false-positive escape hatch and it is deliberately absolute.
     */
    external fun setUserRule(host: String, verdict: String): String

    /** Replace both user lists at once: {"allow":[...],"deny":[...]}. */
    external fun setUserRules(json: String): String

    /** The user's own rules as authored, for the settings screen. */
    external fun getUserRules(): String

    /** Forget which destinations were blocked. User rules are untouched. */
    external fun clearBlockHistory()

    // ── RedShield (REDSHIELD-TRAFFIC-2026-10-25): flow table, automatic
    // denies, block profiles. Same isLoaded + try/catch contract as above.

    /**
     * The per-destination flow table: {"since_secs":N,"flows":[{host, ports,
     * connections, bytes_up, bytes_down, first_secs_ago, last_secs_ago,
     * blocked, unusual}]}, busiest first. PRIVACY: places this user went;
     * display it, never log it.
     */
    external fun getFlows(): String

    /** Forget the flow table and restart its clock. */
    external fun clearFlows()

    /**
     * Replace the AUTOMATIC deny rules (blocked scores, country profiles):
     * {"rules":[{"host","label","ttl_secs"}]}. The user's Allow wins over
     * them; gone on stop. Returns "ok:<n>" or "err:...".
     */
    external fun setAutoRules(json: String): String

    /**
     * Install or replace block profile [id]: [kind] "domains" (suffixes) or
     * "ranges" (CIDRs), [body] one entry per line. Returns
     * "ok:<entries>:<skipped>", "err:limit:<total>:<max>" or "err:...".
     */
    external fun setProfile(id: String, kind: String, body: String): String

    /** Remove block profile [id]; no-op when it is not loaded. */
    external fun clearProfile(id: String)

    /**
     * Install the regional address map (the `geo` bundle, decompressed) or
     * clear it with an empty array; copied and validated whole, a refused
     * body leaves the previous map. Not dropped by stop. With it loaded,
     * setProfile(id, "cc", codes) installs a country-code profile (no rule
     * slots) and getFlows rows carry "cc" for literal-IP hosts.
     * Returns "ok:<v4>:<v6>" or "err:...".
     */
    external fun setGeo(body: ByteArray): String

    /** {"loaded":true,"date":"YYYY-MM-DD","v4":N,"v6":N,"ranges":N,...} or {"loaded":false}. */
    external fun geoInfo(): String

    /**
     * Downstream control-stream messages ({seq, ts, type, payload}) queued by
     * the core since the last call, as a JSON array, oldest first. Drains.
     * Only a msg1 tunnel (OTA protection.enabled or messaging.enabled) ever
     * fills it; otherwise "[]".
     */
    external fun drainNotes(): String
}
