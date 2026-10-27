/*
 * Copyright 2026 Internet Mastering & Company, Inc. <sina@redteam.net>
 * SPDX-License-Identifier: Apache-2.0
 */
package com.rostamvpn.android

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import android.webkit.WebView

/**
 * Pays WebView's once-per-process chromium engine init at app launch instead of
 * when the free-tier ad card opens.
 *
 * The first WebView a process ever creates runs chromium's cold-start
 * synchronously on the calling (main) thread: native library load, browser
 * process spin-up, and — the expensive part here — AwContents' first-creation
 * hook registering a ConnectivityManager default-network callback
 * (onFirstWebViewCreated → registerDefaultNetworkCallback). Captured on-device,
 * that first creation blocked the main thread for ~10s ("Skipped ~900 frames",
 * "Davey! duration=10117ms"), which is what froze the ad card's skip countdown
 * mid-tick.
 *
 * The 10s is NOT intrinsic to WebView init — it is the network-callback
 * registration blocking on ConnectivityManager while the VPN tunnel is up and
 * its network is still validating. The same init at app launch, before any
 * tunnel exists, returns effectively instantly. So we do it here, at launch:
 *
 *   1. Cheap, because there is no tunnel yet to block the network callback.
 *   2. Off the ad's critical path — the countdown never shares a frame with it.
 *   3. Sticky — we hold the instance in a static field so it is never GC'd and
 *      the live-WebView count never drops back to zero. That keeps the chromium
 *      engine warm for the process lifetime, so the ad card's WebView is a cheap
 *      second-or-later creation rather than the blocking first one.
 *
 * The warm instance is never attached to any view hierarchy; it exists only to
 * keep the engine loaded. Idempotent: safe to call more than once.
 *
 * Why it also loads a page: creating the WebView spins up the browser process,
 * but the sandboxed renderer/GPU child (com.google.android.webview:sandboxed_process0)
 * only finishes its chromium content init — native library load, GPU setup, IPC
 * with the browser process — when real web content actually renders. Captured
 * on-device, that renderer content-init is what cost the ~10s when it happened
 * on the ad's video load with the tunnel up (a second "Successfully loaded native
 * library" on the sandbox pid, ~10s before the frame landed). So the warm-up
 * loads a trivial data: page to force that renderer init to complete now, at
 * launch, while there is no tunnel to slow the cross-process/network setup.
 */
object WebViewWarmer {
    private const val TAG = "WebViewWarmer"

    @SuppressLint("StaticFieldLeak")   // deliberately process-lifetime; uses app context, not an Activity
    @Volatile
    private var warm: WebView? = null

    /**
     * Create the warm-up WebView if it does not exist yet and load a trivial page
     * through it, so both the browser process AND the sandboxed renderer complete
     * their one-time init now rather than on the ad card. Must run on the main
     * thread (WebView requires it). Uses the application context so the retained
     * instance does not leak an Activity. Swallows failure — a device without a
     * usable WebView provider simply falls back to the old behaviour (the ad card
     * pays init itself) rather than crashing the app.
     */
    fun warmUp(context: Context) {
        if (warm != null) return
        try {
            warm = WebView(context.applicationContext).apply {
                // Drive the sandboxed renderer through a real content load so its
                // chromium/GPU init happens here, tunnel-free, not on the ad's
                // video render. A bare data: page is enough to trigger it and
                // costs nothing to fetch (no network).
                loadData("<html><body></body></html>", "text/html", "utf-8")
            }
            Log.i(TAG, "WebView engine + renderer warmed at launch (ad card init cost paid early)")
        } catch (t: Throwable) {
            Log.w(TAG, "WebView warm-up skipped: ${t.message}")
        }
    }
}
