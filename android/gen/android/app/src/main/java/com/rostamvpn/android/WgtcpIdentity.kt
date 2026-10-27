package com.rostamvpn.android

import android.content.Context
import android.util.Log

/**
 * This device's WireGuard identity.
 *
 * Generated once and kept for the life of the install. The private key never
 * leaves the device; only the public half is sent to the provisioning API.
 *
 * This replaces a single private key that was baked into `default-config.json`
 * and therefore shipped identically to every install. That key was bound to one
 * `/32` on the server, so exactly one device could use the tunnel at a time and
 * nothing could ever be revoked.
 *
 * Kept in its own preferences file so backup rules can exclude it. The app has
 * `allowBackup="true"`, and Android excludes whole files rather than individual
 * keys -- so sharing the cid's file would mean either leaking this key to the
 * user's Google Drive or losing their entitlement on restore. Separate files let
 * both be right: see `backup_rules.xml` and `data_extraction_rules.xml`.
 *
 * A key syncing to Drive is silent, and this is a VPN used where being
 * identified as a circumvention user is dangerous.
 */
object WgtcpIdentity {
    private const val TAG = "WgtcpIdentity"
    // Its OWN preferences file, not the shared "redsocket" one that holds the
    // cid. Android backup rules exclude whole files, never individual keys, and
    // the two want opposite treatment: the cid SHOULD survive a restore so a
    // user keeps their entitlement, the private key must never leave the device.
    // Sharing a file would force one of those to be wrong.
    private const val PREFS = "wgtcp_identity"
    private const val KEY_PRIV = "wgtcp_private_key"
    private const val KEY_PUB = "wgtcp_public_key"

    data class Keypair(val privateKey: String, val publicKey: String)

    /**
     * Load the keypair, generating it on first use.
     *
     * Returns null when the native library is unavailable, rather than inventing
     * a key some other way: a keypair this function cannot vouch for would be
     * provisioned, installed on every node, and then fail every handshake.
     */
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
        // commit(), not apply(): if the process dies before this reaches disk the
        // device provisions a key it will not remember, stranding an address on
        // the server that nothing will ever release.
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
     * one. The server treats a new key under the same cid as a re-key: it
     * revokes the old peer and returns its address, so this is safe to call.
     */
    fun reset(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().remove(KEY_PRIV).remove(KEY_PUB).apply()
        Log.w(TAG, "device keypair cleared; a new one will be provisioned")
    }
}
