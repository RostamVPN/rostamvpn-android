/*
 * Copyright 2026 Internet Mastering & Company, Inc. <sina@redteam.net>
 * SPDX-License-Identifier: Apache-2.0
 */
package com.rostamvpn.android

import android.app.Activity
import android.content.Context
import android.util.Log
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.admanager.AdManagerAdRequest
import com.google.android.gms.ads.admanager.AdManagerInterstitialAd
import com.google.android.gms.ads.admanager.AdManagerInterstitialAdLoadCallback
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback

/**
 * AdMob interstitial — the first rung of the interstitial waterfall.
 *
 * The whole waterfall, top to bottom:
 *   1. AdMob (this class), with mediation adapters (AppLovin/Meta/Unity/…),
 *      tried FIRST on every region.
 *   2. Cashier / SmartyAds WebView card (AdInterstitialScreen), shown only when
 *      AdMob has nothing to show (no fill, load error, or SDK unavailable).
 *
 * Usage from the UI:
 *   - AdMobInterstitial.preload(context)           // warm one ad
 *   - AdMobInterstitial.showOrFallback(activity,   // try to show; else fall back
 *         onFallback = { viewModel.maybeShowInterstitial() })
 *
 * The show call ALWAYS resolves exactly one way: either an AdMob ad is presented
 * (and the next one is preloaded on dismiss), or onFallback() runs. It never
 * leaves the slot empty, and never shows both.
 *
 * [onClosed] is the third, orthogonal signal: it fires ONLY when a presented ad
 * is dismissed. onFallback and onClosed are mutually exclusive — the slot either
 * passed to the next rung or ran to completion here — which is what lets the
 * connect gate resume on exactly one of them and never twice.
 */
object AdMobInterstitial {
    private const val TAG = "AdMob"

    private var ad: InterstitialAd? = null
    @Volatile private var loading = false
    @Volatile private var showing = false

    /** True once an ad is in hand and ready to present. */
    val isReady: Boolean get() = ad != null

    /**
     * True between onAdShowedFullScreenContent and onAdDismissedFullScreenContent.
     *
     * Exposed for the CONNECT GATE, which waits for an ad before starting the
     * tunnel and must be able to tell "the ad is on screen and the user is
     * looking at it" from "show() was called and nothing happened". The first
     * is worth waiting on; the second must never hold a connection open, and
     * without this flag the gate cannot distinguish them.
     */
    val isShowing: Boolean get() = showing

    private fun adUnitId(): String = BuildConfig.ADMOB_INTERSTITIAL_ID

    /**
     * Load one interstitial if none is loaded or loading. Cheap to call
     * repeatedly (e.g. on connect) — it no-ops when an ad is already warm or a
     * load is in flight. Safe from the main thread; the SDK loads off-thread and
     * calls back on the main thread.
     */
    /** Shared load outcome, so both demand paths report identically. */
    private fun onLoaded(loaded: InterstitialAd) {
        loading = false
        ad = loaded
        Log.i(TAG, "interstitial loaded (${loaded.responseInfo?.mediationAdapterClassName ?: "admob"})")
    }

    private fun onFailed(error: LoadAdError) {
        loading = false
        ad = null
        // Expected sometimes (no fill). Not an error state for the app — the
        // waterfall just falls through to the cashier.
        Log.i(TAG, "interstitial no-fill/err: code=${error.code} ${error.message}")
    }

    fun preload(context: Context) {
        if (ad != null || loading) return
        loading = true
        val id = adUnitId()
        val ctx = context.applicationContext
        try {
            // The id format alone selects the demand path, so the single string
            // in BuildConfig is the whole switch:
            //   "/23056298202/rostam-android-interstitial"  → Google Ad Manager
            //   "ca-app-pub-…/…"                            → AdMob
            // A GAM path MUST go through AdManagerInterstitialAd and an AdMob id
            // through InterstitialAd — the loaders are not interchangeable. But
            // AdManagerInterstitialAd extends InterstitialAd, so everything
            // downstream (show, fullScreenContentCallback, the field type) is
            // identical and nothing else in the waterfall changes.
            if (id.startsWith("/")) {
                AdManagerInterstitialAd.load(
                    ctx, id, AdManagerAdRequest.Builder().build(),
                    object : AdManagerInterstitialAdLoadCallback() {
                        override fun onAdLoaded(loaded: AdManagerInterstitialAd) = onLoaded(loaded)
                        override fun onAdFailedToLoad(error: LoadAdError) = onFailed(error)
                    },
                )
            } else {
                InterstitialAd.load(
                    ctx, id, AdRequest.Builder().build(),
                    object : InterstitialAdLoadCallback() {
                        override fun onAdLoaded(loaded: InterstitialAd) = onLoaded(loaded)
                        override fun onAdFailedToLoad(error: LoadAdError) = onFailed(error)
                    },
                )
            }
        } catch (t: Throwable) {
            loading = false
            ad = null
            Log.w(TAG, "interstitial load threw: ${t.message}")
        }
    }

    /**
     * Present the loaded AdMob interstitial if there is one; otherwise invoke
     * [onFallback] (the cashier card). Exactly one of the two happens.
     *
     * [onFallback] runs when: no ad is loaded, an ad is already showing, or the
     * present call throws. On successful show it does NOT run — the AdMob ad is
     * the impression for this slot. A fresh ad is preloaded when the current one
     * is dismissed, so the next trigger is warm.
     */
    /**
     * @param onShown fired the moment the interstitial is actually on screen.
     *   The connect gate uses this to start the tunnel UNDERNEATH the ad. The
     *   interstitial lives in its own AdActivity and the tunnel is a foreground
     *   service, so the two do not compete: the user watches the whole ad and
     *   the tunnel is up by the time they dismiss it. Before this existed the
     *   tunnel waited for the dismissal, which turned a ~300ms connect into the
     *   full length of an ad — the thing that made the ad feel enormous.
     */
    fun showOrFallback(
        activity: Activity,
        onFallback: () -> Unit,
        onClosed: () -> Unit = {},
        onShown: () -> Unit = {},
    ) {
        val current = ad
        if (current == null || showing) {
            // Nothing to show (or a show already in progress) → cashier fallback.
            onFallback()
            // Warm the next one so a later trigger can use AdMob.
            preload(activity.applicationContext)
            return
        }
        current.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() {
                showing = false
                ad = null
                // The connect gate resumes HERE, before the preload, so the
                // tunnel starts the instant the ad is gone rather than behind a
                // network request for the next creative.
                onClosed()
                // Preload the next interstitial for the following slot.
                preload(activity.applicationContext)
            }

            override fun onAdFailedToShowFullScreenContent(error: AdError) {
                // The ad was loaded but couldn't present. Don't waste the slot —
                // fall back to the cashier card, then rearm.
                showing = false
                ad = null
                Log.w(TAG, "interstitial show failed: ${error.message} — falling back")
                onFallback()
                preload(activity.applicationContext)
            }

            override fun onAdShowedFullScreenContent() {
                showing = true
                // The ad is up. Everything the user came for can start now.
                onShown()
            }
        }
        try {
            current.show(activity)
        } catch (t: Throwable) {
            showing = false
            ad = null
            Log.w(TAG, "interstitial show threw: ${t.message} — falling back")
            onFallback()
            preload(activity.applicationContext)
        }
    }
}
