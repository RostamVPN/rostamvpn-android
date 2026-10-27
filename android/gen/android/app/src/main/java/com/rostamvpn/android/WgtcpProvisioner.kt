package com.rostamvpn.android

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL

/**
 * Fetches this device's WGTCP profile: the tunnel address and the endpoints
 * that will accept its key.
 *
 * The response is cached to disk and the cache is authoritative when the network
 * is unavailable. That is not an optimisation. Provisioning needs a network, and
 * WGTCP is one of the ways this app *gets* a network -- a client that could only
 * connect after a successful API call would be unable to connect on exactly the
 * networks it exists to defeat.
 *
 * So: fetch when we can, remember what we were told, and connect from memory
 * when we cannot. A cached profile stays valid until the server revokes the
 * peer, at which point the handshake fails and the connect falls through to
 * NetCore like any other failure.
 */
object WgtcpProvisioner {
    private const val TAG = "WgtcpProvisioner"
    internal const val PREFS = "wgtcp_identity"
    internal const val KEY_PROFILE = "profile_json"
    private const val KEY_FETCHED = "profile_fetched_at"

    /**
     * The public key the cached profile was issued FOR.
     *
     * Without this the cache is just "some profile": if the keypair is ever
     * replaced while a profile survives -- a partial restore, a half-cleared
     * prefs file -- the client builds the old profile around the new private key
     * and hands the server a peer it has never heard of. WireGuard drops an
     * unknown peer SILENTLY, so the symptom is a TCP carrier that connects, a
     * handshake that is never answered, and a fallback to NetCore with no
     * error anywhere. Captured on the wire exactly once, and it would have been
     * permanent for that install.
     */
    private const val KEY_PROFILE_FOR = "profile_for_pubkey"

    /** Re-fetch this often, to pick up new POPs and a moved endpoint. */
    private const val REFRESH_AFTER_MS = 12L * 60 * 60 * 1000

    /**
     * The config `WgtcpBridge.start()` expects, or null if this device has no
     * usable profile and could not obtain one.
     *
     * @param socksPort when non-null, the request goes through the running
     *   tunnel. Refreshing over an established tunnel keeps the provisioning
     *   call off the clear network, where it is one more distinctive request to
     *   fingerprint.
     */
    /**
     * The cached config, without ever touching the network.
     *
     * For callers on the main thread -- the settings sheet asks whether WGTCP is
     * available every time it draws, and a blocking HTTP call there is a
     * NetworkOnMainThreadException, which is exactly how this first shipped:
     * provisioning threw, the config came back null, and WGTCP silently vanished
     * from the protocol list with no user-visible reason.
     */
    fun cachedConfig(context: Context): String? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val cached = prefs.getString(KEY_PROFILE, "").orEmpty()
        if (cached.isEmpty()) return null
        val priv = prefs.getString("wgtcp_private_key", "").orEmpty()
        if (priv.isEmpty()) return null
        val issuedFor = prefs.getString(KEY_PROFILE_FOR, "").orEmpty()
        val mine = prefs.getString("wgtcp_public_key", "").orEmpty()
        // Only reject on a KNOWN mismatch. A profile cached before this field
        // existed has no record, and refusing those would drop every existing
        // install off WGTCP on upgrade for no reason -- config() re-fetches and
        // stamps them on the next refresh.
        if (issuedFor.isNotEmpty() && mine.isNotEmpty() && issuedFor != mine) {
            Log.w(TAG, "cached profile belongs to a previous key; ignoring it")
            return null
        }
        return build(priv, cached)
    }

    /** True when this device already has a usable profile. Never blocks. */
    fun isProvisioned(context: Context): Boolean = cachedConfig(context) != null

    /**
     * Fetch and cache a profile. BLOCKS on the network -- callers must be on an
     * IO dispatcher.
     */
    fun config(context: Context, socksPort: Int? = null): String? {
        val keys = WgtcpIdentity.ensure(context) ?: return null
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        val cached = prefs.getString(KEY_PROFILE, "").orEmpty()
        val age = System.currentTimeMillis() - prefs.getLong(KEY_FETCHED, 0)
        val issuedFor = prefs.getString(KEY_PROFILE_FOR, "").orEmpty()
        // A profile issued for a different key is not merely old, it is WRONG:
        // the server will silently ignore anything we send with it.
        val wrongKey = issuedFor.isNotEmpty() && issuedFor != keys.publicKey
        if (wrongKey) Log.w(TAG, "cached profile was issued for another key; re-provisioning")
        val stale = cached.isEmpty() || wrongKey || age > REFRESH_AFTER_MS

        if (stale) {
            val fresh = fetch(context, keys.publicKey, socksPort)
            if (fresh != null) {
                prefs.edit()
                    .putString(KEY_PROFILE, fresh)
                    .putString(KEY_PROFILE_FOR, keys.publicKey)
                    .putLong(KEY_FETCHED, System.currentTimeMillis())
                    .apply()
                FirstParty.onWgtcpProfile(fresh)
                return build(keys.privateKey, fresh)
            }
            // Fetch failed. A stale profile still describes a peer the server
            // installed, so it is worth trying -- failing here would refuse to
            // connect over a tunnel that would have worked.
            if (cached.isEmpty() || wrongKey) {
                // A profile for the wrong key is worse than none: it produces a
                // tunnel that connects and carries nothing.
                Log.w(TAG, "no usable profile and provisioning failed; WGTCP unavailable")
                return null
            }
            Log.i(TAG, "provisioning failed; using the cached profile")
        }
        return build(keys.privateKey, cached)
    }

    /** Merge the server's answer with the private key that never left the device. */
    private fun build(privateKey: String, profileJson: String): String? = try {
        val p = JSONObject(profileJson)
        val endpoints = p.optJSONArray("endpoints")
        if (endpoints == null || endpoints.length() == 0) {
            Log.w(TAG, "profile carries no endpoints")
            null
        } else {
            JSONObject().apply {
                put("private_key", privateKey)
                put("peer_public_key", p.getString("server_public_key"))
                // The core takes one endpoint. Picking at random spreads a fleet
                // across a POP's addresses instead of every device hammering
                // whichever one happens to be listed first.
                put("endpoint", endpoints.getString((0 until endpoints.length()).random()))
                put("address", p.getString("address"))
                put("gateway", p.getString("gateway"))
                put("prefix_len", p.optInt("prefix_len", 24))
                put("mtu", p.optInt("mtu", 1380))
                put("persistent_keepalive", p.optInt("persistent_keepalive", 25))
            }.toString()
        }
    } catch (t: Throwable) {
        Log.w(TAG, "cached profile is unusable: ${t.message}")
        null
    }

    private fun fetch(context: Context, publicKey: String, socksPort: Int?): String? {
        val cid = com.rostamvpn.android.billing.CashierIdentity.cid(context)
        if (cid.isEmpty()) {
            Log.w(TAG, "no cid; cannot provision")
            return null
        }
        return try {
            // First base only: unlike the config fetch this is not something a
            // client can proceed without, and WGTCP falling through to NetCore
            // is a better answer than walking a list while the user waits.
            val base = ConfigManager.apiConfig.bases.firstOrNull() ?: return null
            val url = URL("$base/api/v1/wgtcp/profile")
            val conn = (if (socksPort != null)
                url.openConnection(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", socksPort)))
            else url.openConnection()) as HttpURLConnection

            conn.requestMethod = "POST"
            // Keep this socket out of the platform connection pool -- see the
            // long note in CashierApi.open. A SOCKS connect to a dead tunnel
            // leaves a socket with a null InetAddress, and okhttp's pool
            // cleanup thread dereferences it and takes the process down. 144K
            // reports, no frame of ours in the stack, uncatchable at the call
            // site; not pooling it is the only lever the caller has.
            conn.setRequestProperty("Connection", "close")
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("X-Api-Key", ConfigManager.apiConfig.key)
            conn.outputStream.use {
                it.write(JSONObject().put("pubkey", publicKey).put("cid", cid).toString().toByteArray())
            }

            val code = conn.responseCode
            if (code != 200) {
                // 409 is the server saying this identity already holds a key --
                // which should not happen, since a new key re-keys. Logged
                // distinctly because it would mean the cid is shared across
                // devices, and that is a different bug than a network failure.
                val body = runCatching { conn.errorStream?.bufferedReader()?.readText() }.getOrNull()
                Log.w(TAG, "provisioning returned $code: ${body?.take(120)}")
                null
            } else {
                conn.inputStream.bufferedReader().readText().also {
                    Log.i(TAG, "provisioned: ${JSONObject(it).optString("address")}")
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "provisioning request failed: ${t.javaClass.simpleName}: ${t.message}")
            null
        }
    }

    /** Drop the cached profile, forcing a fetch on the next connect. */
    fun invalidate(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().remove(KEY_PROFILE).remove(KEY_FETCHED).apply()
    }
}
