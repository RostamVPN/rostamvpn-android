// Copyright 2026 Internet Mastering & Company, Inc.
// SPDX-License-Identifier: Apache-2.0

package com.rostamvpn.android

import android.content.Context
import android.util.Log

/** This device's WireGuard identity. */
object WgtcpIdentity {
    private const val TAG = "WgtcpIdentity"

    private const val PREFS = "wgtcp_identity"
    private const val KEY_PRIV = "wgtcp_private_key"
    private const val KEY_PUB = "wgtcp_public_key"

    data class Keypair(val privateKey: String, val publicKey: String)

    /** Load the keypair, generating it on first use. */
    fun ensure(context: Context): Keypair? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val priv = prefs.getString(KEY_PRIV, "").orEmpty()
        val pub = prefs.getString(KEY_PUB, "").orEmpty()
        if (priv.isNotEmpty() && pub.isNotEmpty()) return Keypair(priv, pub)

        val raw = WgtcpBridge.generateKeypair()
        if (raw.startsWith("err:") || !raw.contains(':')) {
            Log.e(TAG, "keypair generation failed: $raw")
            return null
        }
        val (p, u) = raw.split(':', limit = 2).let { it[0] to it[1] }
        if (p.isEmpty() || u.isEmpty()) {
            Log.e(TAG, "keypair generation returned an empty half")
            return null
        }

        prefs.edit().putString(KEY_PRIV, p).putString(KEY_PUB, u).commit()
        Log.i(TAG, "generated a new device keypair (public=$u)")
        return Keypair(p, u)
    }

    /** The public half, for anything that needs to identify this device. */
    fun publicKey(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_PUB, "").orEmpty()

    /**
     * Discard the keypair so the next connect generates and provisions a fresh
     * one. The server treats a new key under the same cid as a re-key: it revokes
     * the old peer and returns its address, so this is safe to call.
     */
    fun reset(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().remove(KEY_PRIV).remove(KEY_PUB).apply()
        Log.w(TAG, "device keypair cleared; a new one will be provisioned")
    }
}
