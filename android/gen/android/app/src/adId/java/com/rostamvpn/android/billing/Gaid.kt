/*
 * Copyright 2026 Internet Mastering & Company, Inc. <sina@redteam.net>
 * SPDX-License-Identifier: Apache-2.0
 */
package com.rostamvpn.android.billing

import android.content.Context
import android.util.Log
import com.google.android.gms.ads.identifier.AdvertisingIdClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Google Advertising ID + the user's Limit-Ad-Tracking choice. */
data class AdId(val gaid: String?, val limited: Boolean)

/**
 * Resolves the GAID for our own adtech/cashier attribution (`ifa`).
 *
 * Blocking under the hood (Play Services binder call), so it MUST run off the
 * main thread — exposed as a suspend fn on IO.
 *
 * Google Play policy: when Limit-Ad-Tracking is on we must not use the
 * advertising id for ads. [AdId.gaid] is null and [AdId.limited] is true in that
 * case; callers send `lmt=1` and omit `ifa` to commercial demand. Our own
 * cashier/adtech still learns `lmt` so fill policy can honor it.
 */
object Gaid {
    private const val TAG = "Gaid"
    private const val ZERO_UUID = "00000000-0000-0000-0000-000000000000"

    suspend fun get(context: Context): AdId = withContext(Dispatchers.IO) {
        try {
            val info = AdvertisingIdClient.getAdvertisingIdInfo(context)
            when {
                info.isLimitAdTrackingEnabled -> {
                    // User opted out in Settings → Google → Ads. Policy: no ifa.
                    Log.i(TAG, "no ifa — Limit-Ad-Tracking is ON (user opt-out)")
                    AdId(null, true)
                }
                info.id.isNullOrBlank() -> {
                    // Play Services returned an empty id without setting LAT.
                    Log.w(TAG, "no ifa — Play Services returned a blank id")
                    AdId(null, true)
                }
                info.id == ZERO_UUID -> {
                    // All-zero id: what a managed/enterprise device or a reset
                    // returns. Distinct from LAT — the user did not opt out.
                    Log.w(TAG, "no ifa — zeroed id (managed device or ad-id reset)")
                    AdId(null, true)
                }
                else -> AdId(info.id, false)
            }
        } catch (t: Throwable) {
            // Play Services missing / not consented / transient — never crash.
            // Logged distinctly: an exception here is NOT a user opt-out, and
            // conflating the two hid why a device never sent an ifa.
            Log.w(TAG, "no ifa — AdvertisingIdClient failed: ${t.javaClass.simpleName}: ${t.message}")
            AdId(null, true)
        }
    }
}
