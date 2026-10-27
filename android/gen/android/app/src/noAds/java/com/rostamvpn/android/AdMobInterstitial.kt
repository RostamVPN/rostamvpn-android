package com.rostamvpn.android

import android.app.Activity
import android.content.Context

/**
 * No-adtech build: there is no ad SDK in this variant at all.
 *
 * DollarVPN is a paid-only brand — 1 GB free, then a subscription, and no ads
 * anywhere. Turning ads OFF in the OTA config was not enough: the AdMob SDK was
 * still linked in, and the SDK is what contributes
 * `com.google.android.gms.permission.AD_ID`. Play asks about the PERMISSION,
 * not about whether an ad ever renders, so a brand that shows none still had to
 * declare that it uses an advertising id.
 *
 * Every member mirrors the real object's signature exactly, so the shared UI
 * compiles unchanged against either variant and there is no `if (brand == …)`
 * anywhere (§6b).
 *
 * `isReady` is permanently false, which is what makes the call sites take their
 * existing "no ad available" path: `MainScreen`'s connect gate only arms on
 * `isReady`, so a connect here is never gated on an ad.
 */
object AdMobInterstitial {
    val isReady: Boolean get() = false
    val isShowing: Boolean get() = false

    fun preload(context: Context) { /* no adtech in this variant */ }

    fun showOrFallback(
        activity: Activity,
        onFallback: () -> Unit,
        onClosed: () -> Unit = {},
        onShown: () -> Unit = {},
    ) {
        // Straight to the fallback, which for a no-ads brand is simply "carry on
        // and connect". Never onShown: nothing was shown.
        onFallback()
    }
}
