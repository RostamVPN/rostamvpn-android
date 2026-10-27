/*
 * Copyright 2026 Internet Mastering & Company, Inc. <sina@redteam.net>
 * SPDX-License-Identifier: Apache-2.0
 */
package com.rostamvpn.android.ui.screens

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color as AndroidColor
import android.os.SystemClock
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

/**
 * Free-tier interstitial — presented as a centered CARD over a dimmed scrim, not
 * a full-screen takeover (it should feel like part of the app, not a hijack).
 * Hosts the cashier's ad page in a WebView (the page self-renders the creative
 * and fires its own impression tracker). The app frames it: a "Sponsored" header,
 * a skip countdown that becomes a ✕ close, rounded corners, and breathing room
 * around the card.
 *
 * Deliberate ad-watch gate (requested): the header shows a [AD_SKIP_SECONDS]-second
 * countdown where the ✕ will be, and every dismiss path — ✕, back, tap-outside —
 * is blocked until it reaches 0, at which point the ✕ appears and all three work.
 * The countdown starts on first paint of the creative, NOT when the card opens, so
 * the seconds are real ad-view time rather than time spent behind the loading
 * spinner. This is distinct from the app-side forced pause removed in b0186a4:
 * that one gated a card that often had nothing to show; this one gates a card only
 * once the creative is actually on screen, and is what the product wants.
 *
 * While the creative loads (a real wait over the throttled free tunnel — a few
 * seconds of black box otherwise) a spinner shows over the ad area, cleared on
 * first paint. A soft stall that never errors and never finishes is bounded by
 * [AD_LOAD_TIMEOUT_MS] so the card can never sit black indefinitely — and because
 * that dismissal path bypasses the countdown gate, a no-fill is never trapped
 * behind the timer.
 *
 * [debug] prints the ad context bottom-centre; the caller only supplies it on
 * debug builds — a release user must never see their region/timezone/cid
 * printed over the creative.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun AdInterstitialScreen(
    url: String,
    debug: String?,
    onClose: () -> Unit,
    /** Closed because nobody bid, not because the user dismissed it. Lets the
     *  caller back off instead of re-opening a card that will vanish again. */
    onNoFill: () -> Unit = onClose,
) {
    // First paint of the creative. Drives the loading spinner: shown until the
    // page reports it is up, so the user sees "loading" instead of a dead black
    // box during the (real, tunnel-bound) fetch.
    var pageReady by remember(url) { mutableStateOf(false) }
    // Tracks whether the page ever rendered. A no-fill, a censored host, or a
    // WebView error otherwise leaves an empty card sitting on the user's screen
    // — the app commits the overlay before it knows there is anything to show.
    var loadFailed by remember(url) { mutableStateOf(false) }
    /** Distinguishes "nobody bid" from "the page broke". */
    var noFill by remember(url) { mutableStateOf(false) }

    // Skip gate. Seconds left before the user may close the card, derived from a
    // wall-clock deadline rather than counted down in a loop. The old
    // `while { delay(1000); remaining-- }` ran on the main-thread frame clock, so
    // when WebView jammed the UI thread (chromium cold-start / VAST decode:
    // "Skipped 900 frames") the ticks stalled and the number froze mid-count, then
    // replayed in a burst. Anchoring to elapsedRealtime() means a jank stall just
    // means the next recomposition shows the CORRECT remaining value — the clock
    // kept running even while the thread was busy. Ticks every 250ms so the number
    // is right within a frame of the thread freeing, and still costs almost nothing.
    // Starts on first paint (pageReady) so the seconds are real ad-view time.
    var skipRemaining by remember(url) { mutableIntStateOf(AD_SKIP_SECONDS) }
    val canClose = skipRemaining <= 0

    LaunchedEffect(pageReady) {
        if (pageReady) {
            val deadline = SystemClock.elapsedRealtime() + AD_SKIP_SECONDS * 1000L
            while (true) {
                val left = deadline - SystemClock.elapsedRealtime()
                skipRemaining = if (left <= 0) 0 else ((left + 999) / 1000).toInt()
                if (skipRemaining <= 0) break
                kotlinx.coroutines.delay(250)
            }
        }
    }

    // Single dismiss entry point for the user-initiated paths (✕, back,
    // tap-outside). Honours the skip gate: a tap before the countdown ends is
    // swallowed. The load-failure/no-fill path does NOT go through here — it
    // calls onNoFill/onClose directly so a card with nothing to show is never
    // trapped behind the timer.
    val dismissIfAllowed = { if (canClose) onClose() }

    // Soft-stall guard. onReceivedError only fires on a hard failure; a
    // connection that hangs with no response over the throttled tunnel neither
    // errors nor finishes, so without this the card shows a black box forever.
    // If nothing has painted by the deadline, treat it as a no-fill and let the
    // caller back off rather than stranding the user.
    LaunchedEffect(url) {
        kotlinx.coroutines.delay(AD_LOAD_TIMEOUT_MS)
        if (!pageReady && !loadFailed) {
            noFill = true
            loadFailed = true
        }
    }

    // Bail out early rather than framing a blank box.
    LaunchedEffect(loadFailed) {
        if (loadFailed) { if (noFill) onNoFill() else onClose() }
    }

    // Back must dismiss — but only once the skip gate opens, matching the ✕.
    // Kept enabled the whole time so a too-early back press is swallowed here
    // rather than falling through and finishing the Activity (which would leave
    // _adUrl set and re-fire the impression when the user returns).
    BackHandler(enabled = true) { dismissIfAllowed() }

    val noRipple = remember { MutableInteractionSource() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.55f))   // scrim dims the app behind
            // Tap-outside dismisses too — but only after the countdown, so a
            // stray tap during the ad can't skip it.
            .clickable(interactionSource = noRipple, indication = null) { dismissIfAllowed() },
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            // FULL SCREEN. This was a 92% x 80% rounded card floating on a
            // scrim, which is a dialog, not an interstitial: the creative was
            // letterboxed inside it and lost roughly a third of its area to
            // inset and corner radius. Ad demand is bought against a full
            // screen, and a VAST creative rendered into a small box measures as
            // a worse placement, so this cost fill rate as well as looking wrong.
            //
            // Insets are still respected: statusBarsPadding/navigationBarsPadding
            // keep the header and the close control clear of the system bars,
            // which is what actually matters. Edge to edge without them would
            // put the ✕ under the status bar and make the ad undismissable.
            shape = RectangleShape,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 0.dp,
            shadowElevation = 0.dp,
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                // Swallow taps so they don't fall through to the dismiss scrim.
                .clickable(interactionSource = noRipple, indication = null) {},
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Header: "Sponsored" on the left; on the right a skip countdown
                // that turns into the ✕ close once the gate opens. Same slot, so
                // the user watches the number tick down to where the close will be.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 18.dp, end = 10.dp, top = 12.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Sponsored",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    // ONE control, ONE position, ONE size, for the whole life of
                    // the card: a 44dp disc that holds the countdown and then
                    // becomes the close. It used to be two bare glyphs of
                    // different widths swapped in and out, which failed three
                    // ways at once.
                    //
                    // 1. TOO SMALL TO HIT. A titleMedium glyph with 12dp/6dp
                    //    padding measures about 40x32dp. Material's minimum
                    //    target is 48x48 and the ✕ was under it on BOTH axes,
                    //    worst on the short one. Now 44dp of disc inside a 48dp
                    //    box, so the visible shape is calm and the target is legal.
                    // 2. NOTHING SAID WHERE TO TAP. A bare glyph on the header
                    //    band gives no edge to aim at, and it sits directly above
                    //    an arbitrary third-party creative, so whatever contrast
                    //    it happened to have was the advertiser's choice. The
                    //    disc carries its own ground and is legible over anything.
                    // 3. THE SLOT MOVED. "5" and "✕" are different widths, so the
                    //    control shifted at the exact moment it became tappable —
                    //    the user aims at the number and the target jumps.
                    //
                    // The glyph is Icons.Default.Close, not the character "✕":
                    // that codepoint is a font-dependent dingbat that renders at
                    // wildly different weights across the 38 locales' fallback
                    // fonts, and in some it is missing and draws tofu.
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .then(
                                if (canClose) Modifier.clickable(
                                    onClickLabel = "Close ad",
                                    role = Role.Button,
                                    onClick = onClose,
                                ) else Modifier
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(CircleShape)
                                .background(
                                    MaterialTheme.colorScheme.onSurface.copy(
                                        alpha = if (canClose) 0.14f else 0.07f
                                    )
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (canClose) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    // The Box owns the click and its label, so a
                                    // second announcement here is just noise.
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.size(22.dp),
                                )
                            } else {
                                Text(
                                    text = "$skipRemaining",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }

                // The ad itself, filling the rest of the card (clipped to the rounded
                // bottom corners). Transparent WebView bg so the card surface shows.
                // Boxed so the loading spinner can sit centered over the ad area
                // until the creative paints.
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    // No corner clip: the surface is square now, and rounding
                    // the creative against a square screen would leave two
                    // slivers of surface colour at the bottom.
                    contentAlignment = Alignment.Center,
                ) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        WebView(ctx).apply {
                            // VAST video needs a WebChromeClient to create the
                            // <video> rendering surface. Without one the JS player
                            // requests fullscreen, gets no onCreateWindow callback,
                            // and the page freezes — the ad card hangs on a black
                            // box right before playback.
                            webChromeClient = object : WebChromeClient() {
                                // First real content signal. Clears the spinner as
                                // soon as the page has substantially loaded, so the
                                // creative is not hidden behind it once it paints.
                                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                    if (newProgress >= 80) pageReady = true
                                }
                            }
                            webViewClient = object : WebViewClient() {
                                // Clicks must leave the card. A bare WebViewClient loads
                                // the sponsor's landing page inside a 320x480 box, and
                                // market://intent: targets die as ERR_UNKNOWN_URL_SCHEME —
                                // i.e. every click was a dropped conversion.
                                override fun shouldOverrideUrlLoading(
                                    view: WebView?,
                                    request: WebResourceRequest?,
                                ): Boolean {
                                    val target = request?.url ?: return false
                                    // No-fill sentinel: nobody bid, so there is nothing
                                    // to show. Close the card instead of leaving an empty
                                    // box on screen. Must come BEFORE the startActivity
                                    // branch — no app handles this scheme, and the catch
                                    // there swallows the failure without closing.
                                    if (target.scheme == "rostamvpn" && target.host == "ad-nofill") {
                                        noFill = true
                                        loadFailed = true
                                        return true
                                    }
                                    // Let the ad page itself (and its frames) load in place;
                                    // only user-initiated navigation goes out.
                                    if (request.isForMainFrame != true) return false
                                    if (target.toString() == url) return false
                                    return try {
                                        ctx.startActivity(
                                            Intent(Intent.ACTION_VIEW, target)
                                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                        )
                                        onClose()
                                        true
                                    } catch (_: Throwable) {
                                        true   // swallow: never strand the user on a dead scheme
                                    }
                                }

                                override fun onReceivedError(
                                    view: WebView?,
                                    request: WebResourceRequest?,
                                    error: WebResourceError?,
                                ) {
                                    // Only the main document matters; a missing tracker pixel
                                    // is not a reason to tear the card down.
                                    if (request?.isForMainFrame == true) loadFailed = true
                                }

                                // Backstop for onProgressChanged: if the page finishes
                                // without ever crossing the progress threshold, still
                                // clear the spinner.
                                override fun onPageFinished(view: WebView?, finishedUrl: String?) {
                                    pageReady = true
                                }
                            }
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            // SmartyAds app inventory fills VAST *video* creatives; without
                            // this the WebView blocks autoplay and the ad renders blank.
                            settings.mediaPlaybackRequiresUserGesture = false
                            setBackgroundColor(AndroidColor.TRANSPARENT)
                            loadUrl(url)
                        }
                    },
                    // Without this the WebView is only detached, so an autoplaying VAST
                    // video keeps playing audio after the card is dismissed.
                    onRelease = { web ->
                        web.stopLoading()
                        web.loadUrl("about:blank")
                        web.destroy()
                    },
                )

                    // Loading feedback: a spinner over the ad area until the creative
                    // reports it is up (or the stall guard fires). Replaces the dead
                    // black box the user otherwise stared at during the fetch.
                    if (!pageReady) {
                        CircularProgressIndicator(
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }

        // Debug overlay (testing only) — region / ad-tracking / cid.
        if (debug != null) {
            Text(
                text = debug,
                color = Color.White,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(12.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.Black.copy(alpha = 0.6f))
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }
    }
}

/**
 * Upper bound on how long the ad card may sit loading before it is treated as a
 * no-fill and dismissed. onReceivedError covers hard failures; this covers the
 * soft stall — a request that hangs over the throttled free tunnel and neither
 * errors nor completes. Generous enough that a slow-but-real creative on a poor
 * connection still gets to paint, short enough that a dead card never lingers.
 */
private const val AD_LOAD_TIMEOUT_MS = 8000L

/**
 * How many seconds the user must watch the creative before the ✕ appears and the
 * card can be closed. The header shows this counting down in the ✕'s slot, and
 * every user dismiss path (✕, back, tap-outside) is blocked until it hits 0. The
 * countdown starts on first paint of the creative, not when the card opens, so
 * time spent loading behind the spinner does not eat into it.
 */
private const val AD_SKIP_SECONDS = 5
