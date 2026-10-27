/*
 * Copyright 2026 Internet Mastering & Company, Inc. <sina@redteam.net>
 * SPDX-License-Identifier: Apache-2.0
 */
package com.rostamvpn.android.ads

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.TextView
import com.google.android.gms.ads.mediation.MediationInterstitialAdCallback
import com.rostamvpn.android.billing.CashierApi

/**
 * Full-screen host for a creative won by [SmartyAdsCustomEvent].
 *
 * The markup is already in hand (the adapter fetched it to decide fill vs no
 * fill), so this only renders it and reports lifecycle back to AdMob. Handing it
 * over through statics is safe because AdMob shows one interstitial at a time
 * and both fields are cleared on finish.
 *
 * Unlike the in-app card, nothing else draws a frame around this, so the close
 * control has to live here. Without it the creative fills the screen with no way
 * out but the Back key, which traps users and breaks interstitial policy.
 */
@SuppressLint("SetJavaScriptEnabled")
class CustomEventAdActivity : Activity() {

    /** True between reportAdImpression and onAdClosed, so close fires exactly once. */
    private var open = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val html = markup
        val cb = callback
        if (html == null) { finish(); return }

        val web = WebView(this).apply {
            setBackgroundColor(Color.BLACK)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView?, request: WebResourceRequest?,
                ): Boolean {
                    val target = request?.url ?: return false
                    // Late no fill: the creative decided at runtime it has nothing.
                    if (target.scheme == "rostamvpn" && target.host == "ad-nofill") {
                        finish()
                        return true
                    }
                    if (target.scheme == "http" || target.scheme == "https") {
                        // A click leaves the app; AdMob must hear about it to bill.
                        cb?.reportAdClicked()
                        cb?.onAdLeftApplication()
                        try {
                            startActivity(Intent(Intent.ACTION_VIEW, target)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        } catch (t: Throwable) {
                            Log.w(TAG, "click-out failed: ${t.message}")
                        }
                        return true
                    }
                    return true   // swallow anything else rather than navigate
                }
            }
        }

        val close = TextView(this).apply {
            text = "✕"
            setTextColor(Color.WHITE)
            setShadowLayer(6f, 0f, 0f, Color.BLACK)   // stays legible on any creative
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            val pad = dp(14)
            setPadding(pad, pad, pad, pad)
            contentDescription = "Close ad"
            setOnClickListener { finish() }
        }

        setContentView(FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(web, FrameLayout.LayoutParams(MATCH, MATCH))
            addView(close, FrameLayout.LayoutParams(WRAP, WRAP).apply {
                gravity = Gravity.TOP or Gravity.END
                topMargin = dp(8)
            })
        })

        // Base URL matters: the cashier markup references relative paths such as
        // /static/ads/01/index.html, which resolve to nothing without it.
        web.loadDataWithBaseURL(CashierApi.BASE, html, "text/html", "utf-8", null)

        cb?.onAdOpened()
        cb?.reportAdImpression()
        open = true
    }

    /** Covers every exit: the X, Back, and the runtime no-fill path above. */
    override fun onDestroy() {
        if (open) {
            callback?.onAdClosed()
            open = false
        }
        markup = null
        callback = null
        super.onDestroy()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        private const val TAG = "RostamCE"
        private const val MATCH = FrameLayout.LayoutParams.MATCH_PARENT
        private const val WRAP = FrameLayout.LayoutParams.WRAP_CONTENT

        /** Handoff from the adapter; cleared on finish so nothing is retained. */
        @Volatile @JvmStatic var markup: String? = null
        @Volatile @JvmStatic var callback: MediationInterstitialAdCallback? = null
    }
}
