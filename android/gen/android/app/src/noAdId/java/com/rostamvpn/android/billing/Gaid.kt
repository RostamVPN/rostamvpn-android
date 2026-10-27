package com.rostamvpn.android.billing

import android.content.Context

/** Google Advertising ID + the user's Limit-Ad-Tracking choice. */
data class AdId(val gaid: String?, val limited: Boolean)

/**
 * A brand with `android.advertisingId: false` never reads the advertising id
 * and does not link the client library: always "no id, limited", which every
 * caller already handles (no ifa, lmt=1).
 */
object Gaid {
    @Suppress("UNUSED_PARAMETER")
    suspend fun get(context: Context): AdId = AdId(null, true)
}
