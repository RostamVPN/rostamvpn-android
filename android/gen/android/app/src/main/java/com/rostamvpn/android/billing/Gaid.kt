// Copyright 2026 Internet Mastering & Company, Inc.
// SPDX-License-Identifier: Apache-2.0

package com.rostamvpn.android.billing

import android.content.Context
import android.util.Log
import com.google.android.gms.ads.identifier.AdvertisingIdClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Google Advertising ID + the user's Limit-Ad-Tracking choice. */
data class AdId(val gaid: String?, val limited: Boolean)

/** Resolves the GAID for the peer relay's session tag and cashier registration. */
object Gaid {
    private const val TAG = "Gaid"
    private const val ZERO_UUID = "00000000-0000-0000-0000-000000000000"

    suspend fun get(context: Context): AdId = withContext(Dispatchers.IO) {
        try {
            val info = AdvertisingIdClient.getAdvertisingIdInfo(context)
            when {
                info.isLimitAdTrackingEnabled -> {
                    Log.i(TAG, "no ifa — Limit-Ad-Tracking is ON (user opt-out)")
                    AdId(null, true)
                }
                info.id.isNullOrBlank() -> {
                    Log.w(TAG, "no ifa — Play Services returned a blank id")
                    AdId(null, true)
                }
                info.id == ZERO_UUID -> {
                    Log.w(TAG, "no ifa — zeroed id (managed device or ad-id reset)")
                    AdId(null, true)
                }
                else -> AdId(info.id, false)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "no ifa — AdvertisingIdClient failed: ${t.javaClass.simpleName}: ${t.message}")
            AdId(null, true)
        }
    }
}
