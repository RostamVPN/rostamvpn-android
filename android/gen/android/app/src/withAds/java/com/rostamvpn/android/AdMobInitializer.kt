/*
 * Copyright 2026 Internet Mastering & Company, Inc. <sina@redteam.net>
 * SPDX-License-Identifier: Apache-2.0
 */
package com.rostamvpn.android

import android.content.Context
import android.util.Log
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.RequestConfiguration

/**
 * One-time AdMob SDK init, called at app launch.
 *
 * AdMob is the FIRST demand source in the interstitial waterfall (every region);
 * the cashier/SmartyAds WebView card is the no-fill fallback. Initializing here,
 * off the manifest's automatic path (OPTIMIZE_INITIALIZATION in the manifest
 * defers the SDK's own init), keeps cold start cheap and lets us control ordering
 * against the WebView warm-up.
 *
 * initialize() is idempotent and safe to call from the main thread — the SDK
 * fans the mediation adapters' work out to its own background threads and calls
 * back on the main thread. We don't block on the callback; the first ad load can
 * begin before every adapter finishes.
 */
object AdMobInitializer {

    /**
     * Whether AdMob may run at all on this device, from the paid flag and the
     * region's ad policy.
     *
     * Deliberately checked BEFORE MobileAds.initialize(), not just before show():
     * initialising and preloading both put a request on the wire, and in an
     * ads-off region that request is the thing we are trying not to send. Reads
     * prefs and the config only, so it is safe this early in onCreate.
     */
    fun allowedHere(ctx: android.content.Context): Boolean = try {
        // Premium is ALWAYS ad-free, and "ad-free" has to mean no request either
        // — the same argument the region gate below already won. This had no paid
        // check at all, so a paying subscriber's device initialised the SDK and
        // its mediation adapters and put an interstitial request (carrying the
        // GAID) on the wire to googleads/doubleclick at every launch, for an ad
        // adsAllowed() would then always refuse to show. It is the one way a
        // subscriber could actually detect the broken promise: on the network.
        //
        // Same prefs file/key as VpnViewModel.setPaid, and persisted, so it is
        // readable before any ViewModel exists.
        val paid = ctx.getSharedPreferences("rostam_prefs", android.content.Context.MODE_PRIVATE)
            .getBoolean("paid", false)
        if (paid) {
            android.util.Log.i(TAG, "user is premium; AdMob not initialised")
            false
        } else {
            // The freedom region that actually matched, else the detected
            // country — the SAME single derivation VpnViewModel.adPolicyRegion()
            // uses, so this gate cannot key on a different region than the one
            // the policy gates do. It used to call detectCountry() with no
            // freedom awareness, so a foreign SIM inside Iran was "DE"/"TR" here
            // and `ads.regions.IR = "off"` never applied to it.
            val region = com.rostamvpn.android.billing.RegionDetector.freedomRegion(ctx)
                ?: com.rostamvpn.android.billing.RegionDetector.detectCountry(ctx, null)
            ConfigManager.adsConfig.let { it.enabled && it.admobAllowed(region) }
        }
    } catch (t: Throwable) {
        // Fail CLOSED. A config we could not read is not permission to advertise.
        android.util.Log.w("AdMobInitializer", "region check failed: ${t.javaClass.simpleName}")
        false
    }

    /**
     * May we put an ad REQUEST on the wire right now?
     *
     * [allowedHere] answers "may AdMob run on this device at all" -- paid, and
     * the region's switch -- and is the right gate for [init], which starts the
     * SDK and makes no ad request. It is the WRONG gate for a request, because
     * it does not know about `ads.grace_connects`.
     *
     * The consequence was measurable in the AdMob console rather than in any
     * log: during grace every launch asked for an interstitial that
     * `adsAllowed()` would then always refuse to show. Those are requests with
     * a guaranteed-zero impression -- they depress match rate (5.82% on
     * 2.32K requests when this was found) and they are precisely the shape
     * AdMob treats as invalid traffic, on a publisher shared with AdSense and
     * every other brand, where a strike is account-wide.
     *
     * Reads the same prefs file and key as `VpnViewModel.noteSuccessfulConnect`
     * and mirrors `inAdGracePeriod()`, deliberately duplicated rather than
     * shared: this runs in `onCreate`, before any ViewModel exists. The paid
     * flag above is read the same way for the same reason.
     */
    fun mayRequestNow(ctx: Context): Boolean = try {
        if (!allowedHere(ctx)) false
        else {
            val grace = ConfigManager.adsConfig.graceConnects
            if (grace <= 0) true
            else {
                val n = ctx.getSharedPreferences("rostam_prefs", Context.MODE_PRIVATE)
                    .getInt("ad_grace_connects", 0)
                if (n < grace) {
                    android.util.Log.i(TAG, "in ad grace ($n/$grace connects); no request")
                    false
                } else true
            }
        }
    } catch (t: Throwable) {
        // Fail CLOSED, like allowedHere: a gate we could not evaluate is not
        // permission to spend a request.
        android.util.Log.w("AdMobInitializer", "grace check failed: ${t.javaClass.simpleName}")
        false
    }

    private const val TAG = "AdMob"

    @Volatile private var started = false

    fun init(context: Context) {
        if (started) return
        started = true
        try {
            // Test devices. Two independent safety mechanisms:
            //  1. Real ad units must never serve real ads to a dev handset unless
            //     that device is a registered TEST device — a real click otherwise
            //     is invalid traffic against the shared pub-3666521938553232
            //     account (AdMob + AdSense).
            //  2. Debug builds already use Google's TEST ad-unit ids (build.gradle),
            //     so they are safe regardless; we still register the emulator so
            //     mediation networks also serve test ads.
            // ADMOB_TEST_DEVICE_IDS (comma-separated hashes) makes the REAL units
            // return real ads flagged "Test Ad" — this is how the RELEASE build is
            // verified against live inventory without risking the account.
            val testIds = ArrayList<String>()
            if (BuildConfig.DEBUG) {
                testIds.add("TEST_EMULATOR")
            }
            BuildConfig.ADMOB_TEST_DEVICE_IDS
                .split(",")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .forEach { testIds.add(it) }
            if (testIds.isNotEmpty()) {
                val cfg = RequestConfiguration.Builder()
                    .setTestDeviceIds(testIds)
                    .build()
                MobileAds.setRequestConfiguration(cfg)
                Log.i(TAG, "test devices registered: ${testIds.size}")
            }
            MobileAds.initialize(context.applicationContext) { status ->
                // Log each adapter's readiness once, so a misconfigured mediation
                // network is visible in logcat instead of silently never filling.
                status.adapterStatusMap.forEach { (adapter, s) ->
                    Log.i(TAG, "adapter $adapter: ${s.initializationState} (${s.description})")
                }
                Log.i(TAG, "MobileAds initialized")
            }
        } catch (t: Throwable) {
            // Never let an ad-SDK problem take down launch. Worst case: AdMob is
            // simply unavailable and the waterfall falls through to the cashier.
            Log.w(TAG, "MobileAds init skipped: ${t.message}")
        }
    }
}
