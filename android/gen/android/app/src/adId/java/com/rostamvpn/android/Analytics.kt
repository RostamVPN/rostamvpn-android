package com.rostamvpn.android

import android.content.Context
import android.os.Bundle
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.analytics.FirebaseAnalytics

/**
 * Firebase Analytics, for ONE purpose: giving Google Ads a conversion signal it
 * can optimise on. Owner's call, 2026-09-27.
 *
 * 🚨 IT INITIALISES ONLY FOR A BRAND THAT CARRIES FIREBASE KEYS, and that is the
 * whole safety model. `BuildConfig.FIREBASE_APP_ID` is empty for rostam,
 * internetpro and goglobe, so the SDK is linked and never starts: no collection,
 * no network, nothing to declare. Adding a brand is a `firebase` block in its
 * manifest, never a code change.
 *
 * 🚨 CONFIGURED IN CODE, NOT BY THE google-services PLUGIN. The plugin requires a
 * google-services.json for every flavour and fails the build without one, so it
 * would break three brands to instrument two. FirebaseOptions needs exactly the
 * four values the plugin would have parsed out of that file.
 *
 * What is NOT sent, deliberately: no cid, no GAID, no region, no
 * endpoint, no transport. Google gets the event and the money, which is all an
 * ad conversion needs. Anything more would be a second identity graph for a
 * product whose users are the reason the first one is guarded.
 */
object Analytics {
    private const val TAG = "Analytics"

    @Volatile private var fa: FirebaseAnalytics? = null

    /** Safe to call on every launch; the second call is a no-op. */
    fun initIfConfigured(ctx: Context) {
        if (fa != null) return
        val appId = BuildConfig.FIREBASE_APP_ID
        val apiKey = BuildConfig.FIREBASE_API_KEY
        if (appId.isEmpty() || apiKey.isEmpty()) return
        try {
            if (FirebaseApp.getApps(ctx).isEmpty()) {
                FirebaseApp.initializeApp(
                    ctx,
                    FirebaseOptions.Builder()
                        .setApplicationId(appId)
                        .setApiKey(apiKey)
                        .setProjectId(BuildConfig.FIREBASE_PROJECT_ID)
                        .setGcmSenderId(BuildConfig.FIREBASE_SENDER_ID)
                        .build(),
                )
            }
            fa = FirebaseAnalytics.getInstance(ctx)
        } catch (t: Throwable) {
            // Swallowed: analytics must never be why the app fails to start.
            Log.w(TAG, "firebase init failed: ${t.message}")
        }
    }

    /** The paywall was shown. GA4's own name for it, so Ads recognises it. */
    fun paywallViewed(placement: String) {
        val a = fa ?: return
        try {
            a.logEvent(FirebaseAnalytics.Event.VIEW_ITEM, Bundle().apply {
                putString(FirebaseAnalytics.Param.ITEM_ID, placement)
                putString(FirebaseAnalytics.Param.ITEM_CATEGORY, "paywall")
            })
        } catch (t: Throwable) { Log.w(TAG, "paywallViewed: ${t.message}") }
    }

    /** A plan was tapped. The step between seeing a price and being charged. */
    fun checkoutStarted(productId: String) {
        val a = fa ?: return
        try {
            a.logEvent(FirebaseAnalytics.Event.BEGIN_CHECKOUT, Bundle().apply {
                putString(FirebaseAnalytics.Param.ITEM_ID, productId)
            })
        } catch (t: Throwable) { Log.w(TAG, "checkoutStarted: ${t.message}") }
    }

    /**
     * The purchase. THE conversion.
     *
     * `value` and `currency` must both be present or GA4 drops the revenue and
     * Ads cannot bid on it. Micros come straight from Play's ProductDetails, so
     * the number is the store's own rather than anything parsed out of a
     * localised price string.
     */
    fun purchased(productId: String, priceMicros: Long, currency: String) {
        val a = fa ?: return
        if (priceMicros <= 0L || currency.isEmpty()) return
        try {
            a.logEvent(FirebaseAnalytics.Event.PURCHASE, Bundle().apply {
                putString(FirebaseAnalytics.Param.TRANSACTION_ID, productId)
                putDouble(FirebaseAnalytics.Param.VALUE, priceMicros / 1_000_000.0)
                putString(FirebaseAnalytics.Param.CURRENCY, currency)
            })
        } catch (t: Throwable) { Log.w(TAG, "purchased: ${t.message}") }
    }
}
