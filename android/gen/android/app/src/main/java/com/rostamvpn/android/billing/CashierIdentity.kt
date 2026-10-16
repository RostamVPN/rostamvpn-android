// Copyright 2026 Internet Mastering & Company, Inc.
// SPDX-License-Identifier: Apache-2.0

package com.rostamvpn.android.billing

import android.content.Context
import android.provider.Settings
import android.util.Log
import com.rostamvpn.android.RedSocketBridge
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.random.Random

/** The cashier account identity — the cid. */
object CashierIdentity {
    private const val PREFS = "redsocket"
    private const val KEY = "redsocket_cid"

    private const val TAG = "CashierIdentity"
    private const val KEY_DERIVED = "cid_from_ssaid"

    /**
     * Not a secret. It shipped in the APK; it only stops the raw SSAID being
     * recoverable from a cid, and stops the two being trivially interchangeable.
     */
    private const val SALT = "rostam-cid-v1"

    /**
     * SSAIDs known to be shipped identically across many handsets. Treating one as
     * an identity would merge thousands of unrelated users into one account and
     * hand each of them the others' subscription.
     */
    private val BAD_SSAIDS = setOf(
        "9774d56d682e549c", "0000000000000000", "ffffffffffffffff",
    )

    /** Load or derive the app-owned cid. */
    fun cid(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val existing = prefs.getString(KEY, "").orEmpty()

        if (existing.length == 16) return existing

        val fromDevice = deriveFromSsaid(context)
        val id = fromDevice ?: buildString {
            repeat(8) { append("%02x".format(Random.nextInt(256))) }
        }
        val derived = fromDevice != null
        prefs.edit().putString(KEY, id).putBoolean(KEY_DERIVED, derived).apply()
        Log.i(TAG, if (derived) "cid derived from device (survives reinstall)"
                   else "cid generated randomly; no usable device anchor")
        return id
    }

    /**
     * HMAC-SHA256(ANDROID_ID, salt) as 16 hex, or null when the device has no
     * identifier we are willing to trust.
     */
    private fun deriveFromSsaid(context: Context): String? {
        val ssaid = try {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
                ?.trim()?.lowercase().orEmpty()
        } catch (t: Throwable) {
            Log.w(TAG, "ANDROID_ID unreadable: ${t.javaClass.simpleName}")
            return null
        }

        if (ssaid.length != 16 || !ssaid.all { it.isDigit() || it in 'a'..'f' } ||
            ssaid in BAD_SSAIDS
        ) {
            Log.w(TAG, "ANDROID_ID unusable as an anchor; falling back to random")
            return null
        }
        return try {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(SALT.toByteArray(), "HmacSHA256"))

            mac.doFinal("$ssaid|${com.rostamvpn.android.BuildConfig.APPLICATION_ID}".toByteArray())
                .take(8)
                .joinToString("") { "%02x".format(it) }
        } catch (t: Throwable) {
            Log.w(TAG, "HMAC failed: ${t.javaClass.simpleName}")
            null
        }
    }

    /** Get the cid AND inject it into redsocket. Call once at startup. */
    fun ensure(context: Context): String {
        val id = cid(context)
        if (RedSocketBridge.isLoaded) {
            try { RedSocketBridge.setClientId(id) } catch (_: Throwable) { }
        }
        return id
    }
}
