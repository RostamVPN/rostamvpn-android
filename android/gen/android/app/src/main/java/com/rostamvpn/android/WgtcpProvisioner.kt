// Copyright 2026 Internet Mastering & Company, Inc.
// SPDX-License-Identifier: Apache-2.0

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
 */
object WgtcpProvisioner {
    private const val TAG = "WgtcpProvisioner"
    private const val PREFS = "wgtcp_identity"
    private const val KEY_PROFILE = "profile_json"
    private const val KEY_FETCHED = "profile_fetched_at"

    private const val KEY_PROFILE_FOR = "profile_for_pubkey"

    /** Re-fetch this often, to pick up new POPs and a moved endpoint. */
    private const val REFRESH_AFTER_MS = 12L * 60 * 60 * 1000

    /**
     * Fetch and cache a profile: the config `WgtcpBridge.start()` expects, or null
     * if this device has no usable profile and could not obtain one. BLOCKS on the
     * network -- callers must be on an IO dispatcher.
     */
    fun config(context: Context, socksPort: Int? = null): String? {
        val keys = WgtcpIdentity.ensure(context) ?: return null
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        val cached = prefs.getString(KEY_PROFILE, "").orEmpty()
        val age = System.currentTimeMillis() - prefs.getLong(KEY_FETCHED, 0)
        val issuedFor = prefs.getString(KEY_PROFILE_FOR, "").orEmpty()

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
                return build(keys.privateKey, fresh)
            }

            if (cached.isEmpty() || wrongKey) {
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
            val base = ConfigManager.apiConfig.bases.firstOrNull() ?: return null
            val url = URL("$base/api/v1/wgtcp/profile")
            val conn = (if (socksPort != null)
                url.openConnection(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", socksPort)))
            else url.openConnection()) as HttpURLConnection

            conn.requestMethod = "POST"

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

}
