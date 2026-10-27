/*
 * Copyright 2026 Internet Mastering & Company, Inc. <sina@redteam.net>
 * SPDX-License-Identifier: Apache-2.0
 */
package com.rostamvpn.android.ads

import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.VersionInfo
import com.google.android.gms.ads.mediation.Adapter
import com.google.android.gms.ads.mediation.InitializationCompleteCallback
import com.google.android.gms.ads.mediation.MediationAdLoadCallback
import com.google.android.gms.ads.mediation.MediationConfiguration
import com.google.android.gms.ads.mediation.MediationInterstitialAd
import com.google.android.gms.ads.mediation.MediationInterstitialAdCallback
import com.google.android.gms.ads.mediation.MediationInterstitialAdConfiguration
import com.rostamvpn.android.ConfigManager
import com.rostamvpn.android.MainActivity
import com.rostamvpn.android.billing.CashierApi
import com.rostamvpn.android.billing.CashierIdentity
import com.rostamvpn.android.billing.Gaid
import com.rostamvpn.android.billing.RegionDetector
import kotlinx.coroutines.runBlocking

/**
 * The pure decisions this adapter makes, split out so they are unit-testable
 * without an Android runtime. See CustomEventPolicyTest.
 */
internal object CustomEventPolicy {
    /** The cashier serves this as a normal 200 page whose only job is to navigate. */
    const val NO_FILL_SENTINEL = "rostamvpn://ad-nofill"

    /** Console Parameter "house" marks the bottom-of-waterfall line. */
    fun isHouseLine(parameter: String?): Boolean =
        parameter?.trim().equals("house", ignoreCase = true)

    /**
     * The creative, or null for a no bid. A no-fill arrives as HTTP 200 with the
     * sentinel in the body, not as an error status, so status alone cannot decide.
     */
    fun markupOrNull(body: String?): String? = when {
        body.isNullOrBlank() -> null
        body.contains(NO_FILL_SENTINEL) -> null
        else -> body
    }
}

/**
 * AdMob custom event exposing OUR OWN server-side exchange as a mediation
 * partner, so it competes inside AdMob's waterfall instead of being a hardcoded
 * fallback in the app.
 *
 * Register in the AdMob console under Mediation -> your group -> Custom Event,
 * TWICE, as two lines distinguished by Parameter:
 *
 *   Class Name : com.rostamvpn.android.ads.SmartyAdsCustomEvent   (both lines)
 *   Parameter (blank) -> paid demand only. Asks the cashier with no_house=1 so a
 *                        no bid returns the sentinel and maps to
 *                        ERROR_CODE_NO_FILL, letting AdMob try the next network.
 *                        Reporting a house ad as a fill here would starve every
 *                        network below us. eCPM = measured paid eCPM.
 *   Parameter "house" -> house creative allowed. eCPM 0 so it sits at the BOTTOM
 *                        and only serves when nothing else filled.
 *
 * It deliberately calls the CASHIER, not SmartyAds directly. The cashier already
 * owns macro filling across three dialects, the deliberate stripping of
 * lat/lon/city/mac, IR `ifa` suppression, region ad-modes and frequency caps.
 * Calling the SSP from here would duplicate all of that inside the APK and make
 * every policy change an app release. It also means demand added server-side
 * later competes inside AdMob with no app update.
 */
class SmartyAdsCustomEvent : Adapter() {

    override fun getVersionInfo() = VersionInfo(1, 0, 0)
    override fun getSDKVersionInfo() = VersionInfo(1, 0, 0)

    /** Nothing to boot: this adapter is plain HTTP against our own backend. */
    override fun initialize(
        context: Context,
        callback: InitializationCompleteCallback,
        configs: MutableList<MediationConfiguration>,
    ) = callback.onInitializationSucceeded()

    override fun loadInterstitialAd(
        config: MediationInterstitialAdConfiguration,
        callback: MediationAdLoadCallback<MediationInterstitialAd, MediationInterstitialAdCallback>,
    ) {
        val ctx = config.context.applicationContext
        val houseLine = CustomEventPolicy.isHouseLine(
            config.serverParameters.getString("parameter")
        )
        // The SDK may call this on the main thread; never block it. Results go
        // back through the callback, which is safe from any thread.
        Thread({
            try {
                val markup = fetchMarkup(ctx, allowHouse = houseLine)
                if (markup == null) {
                    // ERROR_CODE_NO_FILL (3) is what tells AdMob to try the next
                    // line rather than abandon the slot.
                    callback.onFailure(AdError(3, "No fill", DOMAIN))
                    return@Thread
                }
                // onSuccess RETURNS the callback we report show/click/close on, so
                // the ad object references it lazily: the lambda only runs at show
                // time, by which point adCb is assigned.
                var adCb: MediationInterstitialAdCallback? = null
                val ad = MediationInterstitialAd { showContext ->
                    CustomEventAdActivity.markup = markup
                    CustomEventAdActivity.callback = adCb
                    showContext.startActivity(
                        Intent(showContext, CustomEventAdActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
                adCb = callback.onSuccess(ad)
            } catch (t: Throwable) {
                Log.w(TAG, "load failed: ${t.message}")
                callback.onFailure(AdError(0, "Internal: ${t.message}", DOMAIN))
            }
        }, "rostam-ce").start()
    }

    /** The creative for this line, or null for a no bid. Blocking; off-main only. */
    private fun fetchMarkup(ctx: Context, allowHouse: Boolean): String? {
        // FAIL CLOSED until the app config is loaded. MainActivity calls
        // AdMobInterstitial.preload() in onCreate, BEFORE setContent creates the
        // ViewModel that loads default-config.json — so an early request would
        // otherwise run against AdsConfig.DEFAULT with an empty region, i.e. the
        // wrong ad policy for this user. Skipping costs nothing: the real slot
        // fires on connect, long after the config is up.
        if (ConfigManager.configVersion.isEmpty()) return null

        val cid = CashierIdentity.ensure(ctx)
        if (cid.isEmpty()) return null

        // adPolicyRegion() applies the freedom-region mapping; fall back to plain
        // detection when the ViewModel does not exist yet rather than sending an
        // empty region, which the cashier would treat as "no region policy".
        val region = MainActivity.viewModelRef?.adPolicyRegion()
            ?.takeIf { it.isNotEmpty() }
            ?: RegionDetector.detectCountry(ctx, null)

        val ads = ConfigManager.adsConfig
        if (!ads.adtechAllowed(region)) return null

        val adId = runBlocking { Gaid.get(ctx) }   // already off the main thread
        val url = CashierApi.adInterstitialUrl(
            cid, adId, region,
            sponsorOnly = ads.modeFor(region) == ConfigManager.AdsConfig.MODE_SPONSOR,
            baseOverride = ads.adtechUrl,
            allowHouse = allowHouse,
        )
        return CustomEventPolicy.markupOrNull(CashierApi.fetchText(url))
    }

    private companion object {
        const val TAG = "RostamCE"
        const val DOMAIN = "com.rostamvpn.android.ads"
    }
}
