package com.rostamvpn.android

import android.content.Context

/**
 * A brand with `android.advertisingId: false` links no Firebase Analytics (it
 * pulls play-services-ads-identifier and the AD_ID permission), so these are
 * no-ops. Same signatures as src/adId/Analytics.kt: shared code never branches.
 */
object Analytics {
    @Suppress("UNUSED_PARAMETER") fun initIfConfigured(ctx: Context) {}
    @Suppress("UNUSED_PARAMETER") fun paywallViewed(placement: String) {}
    @Suppress("UNUSED_PARAMETER") fun checkoutStarted(productId: String) {}
    @Suppress("UNUSED_PARAMETER") fun purchased(productId: String, priceMicros: Long, currency: String) {}
}
