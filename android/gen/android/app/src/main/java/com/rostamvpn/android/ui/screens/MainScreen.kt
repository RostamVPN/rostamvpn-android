package com.rostamvpn.android.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.material.icons.filled.Check
import androidx.compose.animation.animateColorAsState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.OutlinedTextField
import app.netcore.NetCoreBridge
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.rostamvpn.android.ui.theme.BrandPalette
import com.rostamvpn.android.ui.theme.liftOnto
import com.rostamvpn.android.ui.theme.tone
import kotlinx.coroutines.delay
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.rostamvpn.android.R
import com.rostamvpn.android.ReviewManager
import com.rostamvpn.android.VpnViewModel
import com.rostamvpn.android.VpnViewModel.ConnectionState
import com.rostamvpn.android.ui.components.AppInfo
import com.rostamvpn.android.ui.components.AppSelectionSheet
import com.rostamvpn.android.ui.components.ConnectShockwave
import com.rostamvpn.android.ui.components.DrawerMenu
import com.rostamvpn.android.ui.components.ParticleAnimation
import com.rostamvpn.android.ui.components.PowerButton
import com.rostamvpn.android.ui.components.RostamToast
import com.rostamvpn.android.ui.components.RegionSelectionCard
import com.rostamvpn.android.ui.components.SettingsSheet
import com.rostamvpn.android.ui.components.ToastType
import com.rostamvpn.android.ui.theme.Brand1
import com.rostamvpn.android.ui.theme.DarkBase
import com.rostamvpn.android.ui.theme.mostLegibleOn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.sp
// RustBridge removed — all transport via NetCoreBridge
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.CircularProgressIndicator
import kotlinx.coroutines.launch

/**
 * Main screen with premium on-connect animations:
 * - Animated background gradient shift (neutral -> teal-green on connect)
 * - Ambient particle system (connecting: energetic, connected: calm drift)
 * - Shockwave burst on successful connection
 * - Power button glow halo + spring bounce
 * - Smooth color transitions throughout
 */
/**
 * How long the user gets to actually USE the tunnel before an ad may interrupt.
 *
 * Measured on device, the connect path completes in ~309ms: library load, tun
 * rebuild, hev start, carrier dial and WireGuard verification. At 1200ms the ad
 * therefore landed barely a second after "VPN On" appeared -- the user taps
 * connect, sees it work, and is immediately taken over by a full-screen ad
 * before they can open anything. That is the worst possible moment for it: they
 * just asked for the tunnel and are waiting to use it.
 *
 * The impression is not lost by waiting, it is only moved. The ad is preloaded
 * long before the tap (measured ~8s earlier), so nothing is fetched during this
 * window; it is purely a hold on presenting.
 *
 * Waiting also gives the Mobile Ads SDK more time to finish building its own
 * WebView renderer, which is a separate main-thread cost at show time.
 */
// Long enough for MobileAds.initialize() to finish registering its foreground
// tracker (it is asynchronous and can land after onResume, which makes show()
// refuse even with the Activity topResumed), short enough that the ad still
// lands while the tunnel is coming up rather than after it. Was 4000 when the
// slot fired post-connect and had nothing to race.
private const val AD_CONNECT_GRACE_MS = 1200L

/**
 * The hard ceiling on how long a connect tap may wait for an ad that is not
 * actually on screen.
 *
 * The gate only ever ARMS when AdMob already has a creative in hand, so this is
 * not a load timeout — there is nothing to load and therefore nothing to wait
 * for. It exists solely for the case where show() is called and the SDK then
 * does nothing: no present, no error, no callback. Without it that path holds
 * the tunnel open forever on a tap from someone who may be in a hurry and may
 * be at risk.
 *
 * 1200ms is already four times a measured connect (~300ms), so it is the
 * outside of what is defensible. It is never reached on a healthy path: a real
 * present fires onAdShowedFullScreenContent within a frame or two, and the
 * watchdog checks that flag before it acts.
 */
private const val AD_GATE_CEILING_MS = 1200L

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(viewModel: VpnViewModel) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val connectionState by viewModel.connectionState.collectAsState()
    val selectedRegion by viewModel.selectedRegion.collectAsState()
    val showSettings by viewModel.showSettings.collectAsState()
    val toastMessage by viewModel.toastMessage.collectAsState()
    val trafficInfo by viewModel.trafficInfo.collectAsState()
    val showAppSelection by viewModel.showAppSelection.collectAsState()
    val installedApps by viewModel.installedApps.collectAsState()
    val allowedApps by viewModel.allowedApps.collectAsState()
    val showAbout by viewModel.showAbout.collectAsState()
    val showPrivacyPolicy by viewModel.showPrivacyPolicy.collectAsState()
    val showOpenSource by viewModel.showOpenSource.collectAsState()
    val showEsim by viewModel.showEsim.collectAsState()
    val activeTransport by viewModel.activeTransport.collectAsState()
    val themeSetting by viewModel.themeSetting.collectAsState()
    val autoConnectStatus by viewModel.autoConnectStatus.collectAsState()
    val configVersion by viewModel.configVersion.collectAsState()
    val showPaywall by viewModel.showPaywall.collectAsState()
    val cnPayOrder by viewModel.cnPayOrder.collectAsState()
    val adUrl by viewModel.adUrl.collectAsState()
    val adDebug by viewModel.adDebug.collectAsState()
    val webPayUrl by viewModel.webPayUrl.collectAsState()
    val usage by viewModel.usage.collectAsState()
    val showDataUsage by viewModel.showDataUsage.collectAsState()
    val premiumAvailable by viewModel.premiumAvailable.collectAsState()
    val reducedSpeed by viewModel.reducedSpeed.collectAsState()
    val isPaid by viewModel.isPaid.collectAsState()
    val premiumCelebration by viewModel.premiumCelebration.collectAsState()
    val dataTotal by viewModel.dataTotal.collectAsState()

    // Entitlement on launch: server-truth tier (all rails) + Play restore.
    LaunchedEffect(Unit) {
        viewModel.syncEntitlement()
        viewModel.restoreEntitlement()
    }
    // A verified web link (WebLinks.kt) parked by MainActivity, possibly before
    // this screen existed. Here, above the tabs branch, so every treatment gets it.
    val webLink by com.rostamvpn.android.WebLinkNav.pending.collectAsState()
    LaunchedEffect(webLink) {
        webLink?.let {
            com.rostamvpn.android.WebLinkNav.consumed()
            viewModel.openWebLink(it)
        }
    }
    val showWebAccount by viewModel.showWebAccount.collectAsState()
    // Confirm before a link claims an account or a code. Here, above the tabs
    // branch, so it is drawn for every treatment.
    val linkToConfirm by viewModel.linkToConfirm.collectAsState()
    linkToConfirm?.let { l ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { viewModel.cancelLink() },
            text = {
                Text(
                    if (l is com.rostamvpn.android.WebLink.Open)
                        stringResource(R.string.link_signin_confirm, com.rostamvpn.android.BuildConfig.WEB_ACCOUNT_HOST)
                    else stringResource(R.string.link_claim_confirm)
                )
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = { viewModel.confirmLink() }) {
                    Text(stringResource(R.string.continue_label))
                }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { viewModel.cancelLink() }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }
    val esimPath by viewModel.esimPath.collectAsState()
    // Free-tier interstitial WATERFALL: AdMob first (every region), cashier/
    // SmartyAds card as the no-fill fallback. Fires once when we enter Connected
    // (and app is foreground); interval_secs=0 means once per connect only, a
    // positive value re-asks on that cadence while still connected. Paid users
    // and region/mode gates live in adsAllowed()/maybeShowInterstitial().
    val adActivity = LocalContext.current as? android.app.Activity
    // ORDER: AdMob first, cashier second.
    //
    // The three-rung ordering (cashier -> AdMob -> house) is the one we want and
    // all of its plumbing is in place, but it is GATED ON A CASHIER DEPLOY. Rung
    // one only works if the backend honours `no_house=1` and answers a no-bid
    // with the no-fill sentinel. Production does not yet: FastAPI ignores the
    // unknown param and serves a house creative, which renders fine, so onNoFill
    // never fires and AdMob never runs. Verified on device -- every connect
    // served an unpaid house ad while a paid AdMob ad sat loaded and unused.
    //
    // So until `no_house` is live, AdMob goes first. It costs nothing: an AdMob
    // no-fill falls straight through to the cashier, which is where the house
    // creative belongs anyway.
    //
    // TO FLIP once the cashier is deployed, verify with:
    //   curl -s '<cashier>/ad/interstitial?...&no_house=1' | grep ad-nofill
    // then swap the two branches below so showInterstitial calls
    // maybeShowInterstitial(allowHouse = false, onExhausted = admobRung) first.
    // Nothing else needs to change; the continuation plumbing is already there.
    //
    // NOTE: SmartyAdsCustomEvent already calls with allowHouse = false, so the
    // no_house path is load-bearing for the AdMob custom event regardless of
    // which ordering is active here.
    val houseRung: () -> Unit = {
        if (viewModel.adtechAllowedForCurrentRegion()) {
            viewModel.maybeShowInterstitial(allowHouse = true)
        }
    }
    // Which source is asked FIRST is a config decision, rolled per impression:
    // `ads.admob_share_pct` with an optional `ads.admob_share_regions` override.
    // The loser of the roll is still the fallback, so steering the mix never
    // costs an impression when the preferred source has no fill.
    //
    // This exists because our own exchange needs volume before its demand
    // partners will serve, so a region can be pushed entirely to it to build
    // that up while other regions keep AdMob's reliable fill.
    val admobRung: () -> Unit = {
        val act = adActivity
        if (act != null && viewModel.admobAllowedForCurrentRegion()) {
            com.rostamvpn.android.AdMobInterstitial.showOrFallback(act, onFallback = houseRung)
        } else {
            houseRung()
        }
    }
    val showInterstitial: () -> Unit = show@{
        if (!com.rostamvpn.android.BuildConfig.ADS || !viewModel.adsAllowed()) return@show
        if (viewModel.admobFirstForThisImpression()) {
            admobRung()
        } else {
            // Our exchange first. It answers for every region and falls back to a
            // house creative rather than no-filling, so there is no automatic
            // return path to AdMob -- which is the point: this branch is chosen
            // when the impression is meant for our own demand.
            if (viewModel.adtechAllowedForCurrentRegion()) {
                viewModel.maybeShowInterstitial(allowHouse = true, onExhausted = admobRung)
            } else {
                admobRung()
            }
        }
    }
    // ── THE CONNECT TAP: ad first, then the tunnel ──
    //
    // The order is tap -> ad -> connect. It used to be tap -> connect -> ad,
    // and the old code's own comment is the argument for moving it: the slot
    // fired ~31ms after the tunnel came up and "failed every time", because the
    // network is being torn down and rebuilt at exactly that moment. The grace
    // delay below was a workaround for that. Firing before the tunnel starts
    // removes the cause rather than padding around it.
    //
    // (This has nothing to do with exit IPs. TunnelVpnService calls
    // addDisallowedApplication(packageName), so our own requests bypass the tun
    // and an ad call goes out direct either way.)
    //
    // THE SAFETY PROPERTY OUTRANKS THE REVENUE, ABSOLUTELY. This is
    // circumvention software; the person tapping connect may be in a hurry and
    // may be at risk. An ad may never prevent or delay a connection. Every
    // branch below therefore ends in toggleConnection(), and the gate is
    // written so that the DEFAULT is to connect and showing an ad is the
    // exception that has to earn itself:
    //
    //   * not eligible (no activity, ads off, premium, region says no, or —
    //     critically — no creative already in hand) -> connect NOW, no wait.
    //   * eligible -> show, and connect the instant it is dismissed.
    //   * show falls through to the next rung -> connect NOW.
    //   * SDK presents nothing and never calls back -> the watchdog connects.
    //
    // IT ONLY ARMS ON AdMobInterstitial.isReady, which is what makes "never
    // delays" true rather than merely intended: a creative in hand shows in the
    // same frame, so there is no load to wait on. When nothing is loaded the tap
    // goes straight through and the cashier/house rung still gets its impression
    // from the post-connect slot below, so the waterfall keeps its revenue and
    // the user keeps their connection.
    // Its own scope: the drawer's `scope` is declared further down, and the
    // watchdog must not outlive this screen.
    val adGateScope = rememberCoroutineScope()
    val connectGateBusy = remember { mutableStateOf(false) }
    val preConnectAdServed = remember { mutableStateOf(false) }
    // The gate proper — ad, then tunnel — split out of `onConnectTap` because a
    // `val` lambda cannot call itself, and the consent continuation below has to
    // re-enter it once the system dialog is answered.
    val runConnectGate: () -> Unit = gate@{
        val act = adActivity
        val ready = com.rostamvpn.android.AdMobInterstitial.isReady
        val admobOk = viewModel.admobAllowedForCurrentRegion()
        val eligible = com.rostamvpn.android.BuildConfig.ADS && act != null && admobOk && ready
        // One line, because "the ad did not show" has four possible causes and
        // guessing between them off a screen recording wasted a bench session.
        // adsAllowed() is implied by admobAllowedForCurrentRegion(), which is
        // adsAllowed() AND the region's admob switch — so it is not tested
        // twice here, it is reported separately only to name the culprit.
        if (com.rostamvpn.android.BuildConfig.ADS) android.util.Log.i(
            "AdGate",
            "tap: act=${act != null} ads=${viewModel.adsAllowed()} " +
                "admob=$admobOk ready=$ready -> ${if (eligible) "AD FIRST" else "CONNECT NOW"}",
        )
        if (!eligible) {
            preConnectAdServed.value = false
            viewModel.toggleConnection()
            return@gate
        }
        connectGateBusy.value = true
        preConnectAdServed.value = true
        // One latch, so however many of the three paths fire, the tunnel is
        // started exactly once. All of them are on the main thread (AdMob's
        // callbacks and this scope both), so a plain flag is sufficient.
        var started = false
        fun connectNow() {
            if (started) return
            started = true
            connectGateBusy.value = false
            viewModel.toggleConnection()
        }
        // CONSENT IS THE ONE THING THAT CANNOT RUN UNDER AN AD — it is a system
        // dialog and it would fight the interstitial for the screen. That is why
        // the block at the top of this tap resolves it before we ever get here,
        // which means by this line `VpnService.prepare()` is necessarily null and
        // the tunnel may always start under the ad. There used to be a
        // `consentGranted` flag guarding that; it is gone because there is no
        // longer a path that reaches this point without consent.
        adGateScope.launch {
            delay(AD_GATE_CEILING_MS)
            // Rescue the case where nothing was ever presented. Once onShown
            // fires the launch below has already connected, and connectNow() is
            // latched, so this is harmless either way.
            if (!com.rostamvpn.android.AdMobInterstitial.isShowing) connectNow()
        }
        com.rostamvpn.android.AdMobInterstitial.showOrFallback(
            act,
            onFallback = { connectNow() },
            onClosed = { connectNow() },
            // THE TUNNEL STARTS UNDER THE AD, not after it.
            //
            // The interstitial runs in AdMob's own AdActivity and the tunnel is
            // a foreground service, so neither needs the other to finish. The
            // user still watches the entire ad — the impression is untouched —
            // but the tunnel is up by the time they dismiss it instead of only
            // starting then.
            //
            // This is what "the ads are too large" actually was. A connect
            // measured in a few hundred ms became the full length of an ad,
            // five to fifteen seconds, and for a censorship tool that is the
            // most expensive place on earth to put a wait: the person tapping
            // connect is often in a hurry, and sometimes in danger.
            onShown = { connectNow() },
        )
    }

    val onConnectTap: () -> Unit = tap@{
        val cs = connectionState
        // Disconnect and cancel are NEVER gated. Only a fresh connect is, and
        // an ad in front of "stop" would be indefensible.
        if (cs is ConnectionState.Connected || cs is ConnectionState.Connecting) {
            viewModel.toggleConnection()
            return@tap
        }
        if (connectGateBusy.value) return@tap
        val act = adActivity
        // ── CONSENT FIRST, THEN THE AD ──
        //
        // On a first-ever connect Android has to ask for VPN consent — what the
        // user reads as installing the certificate, and what many devices gate
        // behind the lock-screen PIN. It used to happen AFTER the interstitial,
        // which meant a brand-new user was shown an ad before they had agreed to
        // anything, and an impression was burned on someone who might then
        // decline.
        //
        // Doing it first is also FASTER, which is the part that matters here.
        // The gate's whole design is that the tunnel starts UNDER the ad; that
        // could not apply before consent existed, so the old order made a first
        // connect wait for the ad to close — the single case where an ad really
        // did delay a connection, on the one run where the user is least sure
        // the app works. Once consent is resolved `prepare()` returns null, the
        // second pass takes the ordinary path, and the tunnel comes up under the
        // ad like everyone else's.
        //
        // `connectGateBusy` is held across the dialog so a second tap cannot
        // launch a second consent intent on top of the system one.
        if (act != null && android.net.VpnService.prepare(act) != null) {
            connectGateBusy.value = true
            (act as? com.rostamvpn.android.MainActivity)?.requestVpnConsent { granted ->
                connectGateBusy.value = false
                if (granted) {
                    runConnectGate()
                } else {
                    // No ad for someone who declined the VPN: there is nothing to
                    // connect to, and an interstitial on top of a refusal is the
                    // worst possible reading of "ads never get in the way".
                    viewModel.onVpnPermissionDenied()
                }
            } ?: run {
                // Not our Activity, so consent cannot be driven from here.
                // Fall through to connect and let the service path ask, exactly
                // as it did before any of this existed.
                connectGateBusy.value = false
                viewModel.toggleConnection()
            }
            return@tap
        }
        runConnectGate()
    }

    // ── Warm the creative BEFORE the tap ──
    //
    // Moving the ad in front of the tunnel made this necessary, and its absence
    // is a real bug I hit on the device: the gate deliberately only arms when a
    // creative is already in hand (that is what makes "never delays a connect"
    // true), so if nothing is warm while the user sits on the disconnected
    // screen, the pre-connect slot can essentially never fire. Measured: 25s
    // idle after a disconnect produced no load at all, because the only warm-up
    // sites were MainActivity.onCreate — already spent — and the post-connect
    // effect below.
    //
    // Disconnected and Failed are exactly the states where a tap is about to
    // happen and there is nothing else competing for the radio. preload() is a
    // no-op when an ad is warm or a load is already in flight, so re-entering
    // these states costs nothing.
    //
    // Still gated on admobAllowedForCurrentRegion(), which is "may AdMob run at
    // all right now": not paid, no card up, region switch on. Premium must not
    // put a request carrying the GAID on the wire for an ad that can never be
    // shown.
    LaunchedEffect(connectionState) {
        val idle = connectionState is ConnectionState.Disconnected ||
            connectionState is ConnectionState.Failed
        if (com.rostamvpn.android.BuildConfig.ADS && idle && viewModel.admobAllowedForCurrentRegion()) {
            adActivity?.let { com.rostamvpn.android.AdMobInterstitial.preload(it) }
        }
    }

    LaunchedEffect(connectionState) {
        // Fires on CONNECTING, not Connected: the user has just tapped and is
        // waiting anyway, so the ad fills the connect rather than being added to
        // it. It never delays the tunnel -- toggleConnection() has already
        // started the connect by the time this state is observed, so the two run
        // in parallel and the tunnel comes up behind the ad.
        //
        // The interval loop below still needs a live connection to be meaningful,
        // and is entered only once we are actually Connected.
        // A no-ads brand has no ad slot at all (BuildConfig.ADS).
        if (!com.rostamvpn.android.BuildConfig.ADS) return@LaunchedEffect
        val connecting = connectionState is ConnectionState.Connecting
        val connected = connectionState is ConnectionState.Connected
        if (!connecting && !connected) return@LaunchedEffect
        // Warm the next one immediately, so a second connect in this session does
        // not wait on a load.
        //
        // Gated. This was unconditional, and it sits BEFORE the adsAllowed()
        // check in showInterstitial, so a paying subscriber put an interstitial
        // request — carrying the GAID — on the wire to googleads/doubleclick at
        // every connect, for an ad that could never be shown. Premium is always
        // ad-free, and the launch path already argues (MainActivity.onCreate)
        // that off has to mean no request, not just no ad shown.
        // admobAllowedForCurrentRegion() is exactly "may AdMob run right now":
        // not paid, no card already up, and the region's admob_ad switch on.
        if (viewModel.admobAllowedForCurrentRegion()) {
            adActivity?.let { com.rostamvpn.android.AdMobInterstitial.preload(it) }
        }
        // One slot per connection. repeatOnLifecycle CANCELS AND RE-RUNS its block
        // on every re-entry to the gate state, and showing an interstitial is
        // itself a re-entry: AdMob presents in its own Activity, which backgrounds
        // MainActivity, so dismissing the ad brings us back and re-runs the block.
        // Reproduced on device: AdMob showed, the user dismissed it, the block
        // re-fired, AdMob had nothing loaded yet (its next preload was still in
        // flight) and the slot fell through to a second, cashier interstitial.
        // Same re-entry happens on any ordinary foreground return while connected
        // (app switch, unlock, notification tap), contradicting interval_secs=0
        // which documents "once per connect only".
        // This flag lives in the LaunchedEffect scope, OUTSIDE repeatOnLifecycle,
        // so it survives those restarts and only resets when connectionState
        // changes to a new connection.
        var slotUsed = false
        // RESUMED, not STARTED. AdMob refuses to present unless the app is in the
        // FOREGROUND, and STARTED only means visible -- an Activity that is
        // started-but-not-focused fails the SDK's check with "The ad can not be
        // shown when app is not in foreground", burning the paid impression and
        // dropping through to the unpaid house creative.
        //
        // This became reachable when the connect path stopped sleeping a flat 2s
        // (awaitTunReady now returns in ~100ms), so the slot fires ~31ms after
        // the tunnel comes up, while starting the VPN foreground service still
        // has focus. Verified on device: three consecutive connects failed this
        // way with the screen on and MainActivity as topResumedActivity.
        // RESUMED makes the block wait for exactly the state AdMob requires.
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            // Settle before the first attempt. RESUMED is necessary but not
            // sufficient: MobileAds.initialize() is asynchronous and can register
            // its ActivityLifecycleCallbacks after onResume has already fired, so
            // the SDK's own foreground tracker still reads "background" and show()
            // is refused even with the Activity topResumed and the screen on.
            // Measured on device: the slot fires ~31ms after the tunnel comes up
            // and failed every time; the old flat delay(2000) in the connect path
            // used to mask this until awaitTunReady replaced it.
            // A wasted first slot costs a paid impression and serves the unpaid
            // house creative instead, so the wait is worth far more than it costs.
            // preConnectAdServed: the tap already spent this connection's
            // slot on an AdMob creative, so this must not stack a second ad on
            // top of it. When the tap could NOT serve one (nothing loaded) the
            // flag is false and this runs as before — which is the fallback
            // that keeps the cashier/house rung earning.
            if (!slotUsed && !preConnectAdServed.value) {
                slotUsed = true
                // AD_CONNECT_GRACE_MS is KEPT, with a narrower job than it had.
                // It no longer covers the common path — that moved in front of
                // the tunnel — but this fallback still fires while the network
                // is being rebuilt, and it can still reach admobRung if a load
                // happened to land between the tap and now. That is exactly the
                // case the grace was measured for: MobileAds.initialize()
                // registers its foreground tracker asynchronously and can land
                // after onResume, so show() is refused on an Activity that is
                // genuinely topResumed. Deleting it would re-open that bug on
                // the one path that still has it.
                delay(AD_CONNECT_GRACE_MS)
                showInterstitial()
            }
            // The repeating slot is for a LIVE session, so it starts only once
            // connected. Running it off Connecting would keep asking for ads on a
            // connection that never came up.
            if (!connected) return@repeatOnLifecycle
            val interval = viewModel.adIntervalSecs
            if (interval <= 0) return@repeatOnLifecycle
            while (true) {
                delay(viewModel.nextAdDelaySecs.value.coerceAtLeast(interval) * 1000L)
                showInterstitial()
            }
        }
    }
    val context = LocalContext.current

    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    val isConnected = connectionState is ConnectionState.Connected
    val isConnecting = connectionState is ConnectionState.Connecting

    // Animated background: neutral -> subtle tint on connect.
    val baseBg = MaterialTheme.colorScheme.background
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val homeStyle = rememberBrandStyle()
    // The `tabs` treatment owns the whole screen: no drawer, no home stack.
    // Everything above this line (entitlement sync, the connect gate, the
    // lifecycle effects) still runs for it, which is why the branch is here
    // and not in MainActivity.
    if (homeStyle == BrandStyle.TABS) {
        TabsRoot(viewModel, onConnectTap)
        return
    }
    // `navigation: tabs` (brands/<id>.json android.navigation, mirrored from
    // iOS): the brand keeps its own style and gets a bottom bar Home · eSIM ·
    // Account instead of the drawer. Same place and same reason as the TABS
    // style above: every effect of this screen has already run.
    if (com.rostamvpn.android.BuildConfig.NAV_TABS) {
        RingsTabsRoot(viewModel, onConnectTap)
        return
    }
    // Protect Mode (BuildConfig.PROTECTION_NAME) on the drawer-style homes:
    // the same card and sheet RingsTabsRoot shows. null = closed, else the
    // sheet opens on Traffic when true. Inert when the brand has no
    // protection name (RedShield.available false): nothing composes.
    var shieldSheet by rememberSaveable { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(Unit) {
        // Debug fixture only (src/debug RedShieldDemo; the release twin is inert).
        if (com.rostamvpn.android.RedShieldDemo.openOn != null && com.rostamvpn.android.RedShield.available) {
            shieldSheet = com.rostamvpn.android.RedShieldDemo.openOn != "blocked" &&
                com.rostamvpn.android.RedShieldDemo.openOn != "profiles" && com.rostamvpn.android.RedShieldDemo.openOn != "rules"
        }
    }
    // ── A BRAND LEAK, FOUND BY LOOKING AT IT ──
    //
    // These four literals are TEAL. They are Rostam's colours, and they were
    // being painted under every brand: on device, RedVPN's connected screen
    // washed a red dial and a red-and-purple pill over a pale green page. It
    // compiles, it has always compiled, and nothing but a screenshot shows it.
    //
    // Not "fixed" by deriving a tint for everyone, because that would change
    // Rostam's connected screen, which is the one thing this work may not do.
    // CLASSIC therefore evaluates the IDENTICAL expression it always has —
    // same literals, same branch — and the tint becomes a property of the
    // composition instead of a global.
    //
    // RINGS takes NO tint at all: the design package's page is white in both
    // states and lets the dial carry the whole transition, which is the
    // stronger reading anyway. VALUE takes a faint wash of its own primary,
    // derived from the palette rather than written down.
    val connectedBgTop = when (homeStyle) {
        BrandStyle.CLASSIC -> classicTint(isDark, 0.055f)
        BrandStyle.RINGS -> baseBg
        BrandStyle.VALUE -> MaterialTheme.colorScheme.primary.copy(alpha = 0.05f)
        BrandStyle.TABS -> baseBg   // unreachable: TABS returned above
            .compositeOver(baseBg)
    }
    val connectedBgBottom = when (homeStyle) {
        BrandStyle.CLASSIC -> classicTint(isDark, 0.105f)
        BrandStyle.RINGS -> baseBg
        BrandStyle.VALUE -> MaterialTheme.colorScheme.primary.copy(alpha = 0.09f)
        BrandStyle.TABS -> baseBg   // unreachable: TABS returned above
            .compositeOver(baseBg)
    }

    val bgTop by animateColorAsState(
        targetValue = if (isConnected) connectedBgTop else baseBg,
        animationSpec = tween(800, easing = FastOutSlowInEasing),
        label = "bgTop"
    )
    val bgBottom by animateColorAsState(
        targetValue = if (isConnected) connectedBgBottom else baseBg,
        animationSpec = tween(1000, easing = FastOutSlowInEasing),
        label = "bgBottom"
    )

    // Particle visibility
    val particleAlpha by animateFloatAsState(
        targetValue = if (isConnected || isConnecting) 1f else 0f,
        animationSpec = tween(600),
        label = "particleAlpha"
    )

    // The mascot's own state animation used to live here, for a crest that sat
    // above the button. Both moved INTO PowerButton when the mascot became the
    // face of the dial — it is the button's own content now, so its state
    // belongs to the button, and MainScreen no longer knows the brand has one.

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            DrawerMenu(
                onClose = { scope.launch { drawerState.close() } },
                onAbout = { viewModel.openAbout() },
                onPrivacyPolicy = { viewModel.openPrivacyPolicy() },
                onOpenSource = { viewModel.openOpenSource() },
                onPremium = { viewModel.openPaywallAndLoad() },
                showPremium = premiumAvailable,
                onUsageStats = { viewModel.openDataUsage(); viewModel.refreshUsage() },
                // paid_only: there is no allowance to report.
                showUsageStats = !viewModel.paidOnly,
                showEsim = com.rostamvpn.android.BuildConfig.FEATURE_ESIM,
                onEsim = { viewModel.openEsim() },
                // Direct build of a brand with a web account only (WebAccount);
                // the Play flavour's WebAccount.available is a constant false.
                showAccount = remember { com.rostamvpn.android.WebAccount.available(context) },
                onAccount = { viewModel.openAccount() },
                // `?: this@MainScreen`-shaped fallback is not available here, so
                // log rather than silently do nothing: a dead tap is the exact
                // failure this row exists to remove.
                onRate = {
                    val a = context as? android.app.Activity
                    if (a != null) ReviewManager.askNow(a)
                    else android.util.Log.w("MainScreen", "Rate tapped but LocalContext is not an Activity")
                },
            )
        }
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // Animated gradient background
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(bgTop, bgBottom)
                        )
                    )
            )

            // Ambient particle system (behind content)
            if (particleAlpha > 0.01f) {
                ParticleAnimation(
                    connectionState = connectionState,
                    modifier = Modifier
                        .fillMaxSize()
                        .alpha(particleAlpha)
                )
            }

            // Shockwave burst overlay (fires on Connecting -> Connected)
            ConnectShockwave(
                connectionState = connectionState,
                modifier = Modifier.fillMaxSize()
            )

            // Main content
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
            ) {
                // ── THE HEADER IS THE ONE PLACE Brand1 WAS NEVER LIFTED ──
                //
                // RingsHome, ValueHome, LaunchOverlay and RingsDial all put the
                // brand colour through liftOnto before painting it on the page.
                // This bar, which every brand renders, used raw Brand1 — so a
                // brand whose primary is dark got a dark wordmark, a dark
                // hamburger and a dark gear on a near-black ground.
                //
                // Measured off the device, not guessed. DollarVPN's #294C1D on
                // the #121212 page is 1.91:1: the whole header was close to
                // invisible. Rostam 3.15:1, InternetPro 3.42:1, RedVPN 4.54:1.
                //
                // min = 3f, not 4.5f, on purpose. The title is headlineMedium
                // bold and the other two are icons, so 3:1 is the threshold that
                // applies to all three, and 3f keeps the lift to brands that are
                // genuinely under the floor.
                //
                // The ground is bgTop, the animated colour actually behind the
                // bar, not scheme.background, so the connected-state tint counts.
                // ⚠️ That is not cosmetic: measured on-device, RostamVPN's header
                // reads 3.15:1 on the disconnected #121212 and 2.71:1 once the
                // connect tint lifts the ground to #112426. So rostam WAS under
                // the floor too, on the connected screen only, and this lifts its
                // teal from 61,107,111 to 71,114,118 there — 3.02:1, and about
                // 10/255 per channel, which is why nobody had noticed. RedVPN
                // (4.54:1 light) and the disconnected screens are untouched.
                val onBar = mostLegibleOn(bgTop, Color.White, DarkBase)
                val brandOnBar = liftOnto(bgTop, Brand1, min = 3f, toward = onBar)
                // Top bar
                CenterAlignedTopAppBar(
                    title = {
                        Text(
                            text = stringResource(R.string.app_name),
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                            color = brandOnBar
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(
                                imageVector = Icons.Default.Menu,
                                contentDescription = stringResource(R.string.cd_menu),
                                tint = brandOnBar
                            )
                        }
                    },
                    actions = {
                        // Settings icon
                        IconButton(onClick = { viewModel.toggleSettings() }) {
                            Icon(
                                imageVector = Icons.Outlined.Settings,
                                contentDescription = stringResource(R.string.settings),
                                tint = brandOnBar
                            )
                        }
                    },
                    colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                        containerColor = Color.Transparent
                    )
                )

                // ── WHICH COMPOSITION RENDERS THIS BRAND'S HOME ──
                //
                // Five brands rendered ONE screen in five colours: same dial,
                // same upgrade banner, same meter, same region card, same
                // footer, same order. Recolouring a layout does not make it a
                // different product, and the owner was right that they read as
                // one app skinned five times.
                //
                // The brand names a COMPOSITION in its manifest and this
                // branches on the composition. There is still no
                // `if (brand == …)` here — CLAUDE.md §6b — and the property
                // that proves it is not a disguised brand check is that TWO
                // BRANDS CAN SELECT THE SAME STYLE: rostam, internetpro and
                // goglobe all resolve to CLASSIC, and a sixth brand gets
                // RedVPN's band stack by writing one string. See BrandStyle.
                //
                // The gates are resolved ONCE, above the branch, and handed
                // down. That is a correctness requirement rather than tidiness:
                // every variant must collapse to connect + region in a FREEDOM
                // REGION, and the only way to guarantee three layouts agree on
                // that is to not let any of them re-derive it.
                val gates = rememberHomeGates(
                    usage = usage,
                    dataTotal = dataTotal,
                    premiumAvailable = premiumAvailable,
                    isPaid = isPaid,
                    reducedSpeed = reducedSpeed,
                    freeTierKbps = viewModel.freeTierKbps,
                    quotaStops = viewModel.quotaStops,
                    paidOnly = viewModel.paidOnly,
                )
                when (homeStyle) {
                    BrandStyle.CLASSIC -> ClassicHome(viewModel, gates, onConnectTap, onShield = { shieldSheet = it })
                    BrandStyle.RINGS -> RingsHome(viewModel, gates, onConnectTap)
                    BrandStyle.VALUE -> ValueHome(viewModel, gates, onConnectTap)
                    BrandStyle.TABS -> Unit   // unreachable: TABS returned above
                }
            }

            // Toast overlay
            RostamToast(
                message = toastMessage,
                onDismiss = { viewModel.dismissToast() },
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(top = 64.dp),
                type = when {
                    toastMessage?.contains("connected", ignoreCase = true) == true -> ToastType.SUCCESS
                    toastMessage?.contains("fail", ignoreCase = true) == true -> ToastType.ERROR
                    toastMessage?.contains("error", ignoreCase = true) == true -> ToastType.ERROR
                    toastMessage?.contains("clear", ignoreCase = true) == true -> ToastType.INFO
                    else -> ToastType.INFO
                }
            )

            // Settings bottom sheet
            if (showSettings) {
                SettingsSheet(
                    themeSetting = themeSetting,
                    onSetTheme = { viewModel.setTheme(it) },
                    onShareDebugLog = { viewModel.shareDebugLog() },
                    onSelectApps = {
                        viewModel.dismissSettings()
                        viewModel.openAppSelection()
                    },
                    languageSetting = viewModel.languageSetting.collectAsState().value,
                    onSetLanguage = { viewModel.setUiLanguage(it) },
                    onDismiss = { viewModel.dismissSettings() },
                )
            }

            // App selection bottom sheet
            if (showAppSelection) {
                AppSelectionSheet(
                    installedApps = installedApps.map { AppInfo(it.packageName, it.appName) },
                    allowedApps = allowedApps,
                    mode = viewModel.splitTunnelMode.collectAsState().value,
                    isConnected = connectionState is ConnectionState.Connected,
                    bypassCidrs = viewModel.bypassCidrs.collectAsState().value,
                    onSetMode = { viewModel.setSplitTunnelMode(it) },
                    onToggleApp = { viewModel.toggleAppAllowed(it) },
                    onSelectAll = { viewModel.selectAllApps() },
                    onDeselectAll = { viewModel.deselectAllApps() },
                    onRecommended = { viewModel.selectRecommendedApps() },
                    onAddCidr = { viewModel.addBypassCidr(it) != null },
                    onRemoveCidr = { viewModel.removeBypassCidr(it) },
                    onDismiss = { viewModel.dismissAppSelection() }
                )
            }

            // The premium moment. Last in the stack so it sits over everything,
            // including a paywall still animating out.
            if (premiumCelebration) {
                PremiumCelebration(onDone = { viewModel.dismissPremiumCelebration() })
            }

            // About screen overlay
            if (showAbout) {
                AboutScreen(onBack = { viewModel.dismissAbout() })
            }

            // Privacy Policy screen overlay
            if (showPrivacyPolicy) {
                PrivacyPolicyScreen(onBack = { viewModel.dismissPrivacyPolicy() })
            }

            // Open Source Libraries screen overlay
            if (showOpenSource) {
                OpenSourceScreen(onBack = { viewModel.dismissOpenSource() })
            }

            // eSIM store overlay (FEATURE_ESIM brands only; openEsim() refuses otherwise).
            if (showEsim) {
                // Keyed on the path so a web link to another destination
                // reloads the store there instead of leaving it where it was.
                androidx.compose.runtime.key(esimPath) {
                    EsimScreen(startPath = esimPath, onBack = { viewModel.dismissEsim() })
                }
            }

            // Web account (direct flavour, brands with `deepLinkHosts`).
            if (showWebAccount) {
                com.rostamvpn.android.WebAccount.Screen(viewModel, onBack = { viewModel.dismissWebAccount() })
            }

            // No Account overlay: there is no account. The account code and
            // Restore both live on the paywall, which is the screen someone who
            // has paid before actually opens.

            // Usage stats overlay
            if (showDataUsage) {
                DataUsageScreen(viewModel = viewModel, onBack = { viewModel.dismissDataUsage() })
            }

            // Dev Tools overlay (debug builds only)

            // Protect Mode sheet (card tap = main page, "See all" = Traffic).
            if (com.rostamvpn.android.RedShield.available) shieldSheet?.let { onTraffic ->
                RedShieldSheet(viewModel, connectionState is ConnectionState.Connected, onTraffic) { shieldSheet = null }
            }

            // Premium paywall overlay
            if (showPaywall) {
                // Adapty's remote flow when one loaded AND priced itself,
                // otherwise PaywallScreen unchanged. See AdaptyPaywallHost.
                AdaptyPaywallHost(viewModel = viewModel, onBack = { viewModel.dismissPaywall() })
            }

            // China rail payment overlay (QR + poll); shown while an order is open.
            cnPayOrder?.let { order ->
                CnPayScreen(order = order, onDismiss = { viewModel.dismissCnPayment() })
            }

            // Free-tier interstitial ad overlay (WebView).
            if (com.rostamvpn.android.BuildConfig.ADS) adUrl?.let { url ->
                AdInterstitialScreen(
                    url = url, debug = adDebug,
                    onClose = { viewModel.dismissAd() },
                    onNoFill = { viewModel.noteAdNoFill() },
                )
            }

            // Paymentwall hosted checkout (direct build) overlay.
            webPayUrl?.let { url ->
                WebPayScreen(url = url, onClose = { viewModel.dismissWebPay() })
            }
        }
    }
}

/**
 * THE CLASSIC HOME — rostam, internetpro, goglobe.
 *
 * This is the screen exactly as it shipped, moved into a function and not
 * otherwise touched. That was the point of doing the extraction as its own
 * step: rostam has 691K installs and the owner's verdict on it is "looks
 * great", so the only acceptable diff for this brand is none. The body below
 * is the former inline `Column` verbatim apart from a 12-space dedent and the
 * gate hoist documented inside it; both were verified against an on-device
 * pixel diff before a single line of any other variant was written.
 *
 * The three-object rhythm (promotion, status readout, control, each separated
 * by whitespace and never by a rule) and the owner's deliberate UPGRADE ->
 * METER -> REGION ordering are described where they happen, further down.
 */
@Composable
private fun ClassicHome(
    viewModel: VpnViewModel,
    gates: HomeGates,
    onConnectTap: () -> Unit,
    onShield: (Boolean) -> Unit = {},
) {
    val connectionState by viewModel.connectionState.collectAsState()
    val autoConnectStatus by viewModel.autoConnectStatus.collectAsState()
    val trafficInfo by viewModel.trafficInfo.collectAsState()
    val configVersion by viewModel.configVersion.collectAsState()
    val selectedRegion by viewModel.selectedRegion.collectAsState()
    val isConnected = connectionState is ConnectionState.Connected
    val isConnecting = connectionState is ConnectionState.Connecting

    // Main content — non-scrolling layout, tightly packed to avoid scroll when connected
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // ── THE HERO: crest, then the action, then what it says ──
        //
        // ORDER IS THE WHOLE POINT, and it changed. It used to run
        // mascot -> status -> button -> hint, which put two blocks of
        // text between the user and the only control on the screen and
        // sandwiched the button so that neither text block clearly
        // belonged to it. Reading down the page the eye met a 150dp
        // illustration, then a 28sp headline, and only then a 120dp
        // hairline ring with LESS contrast than the cards below it.
        // Three things ahead of the product.
        //
        // Now: the dial is the largest and loudest object, the status
        // is its caption and the hint is the caption's footnote —
        // everything below the dial reads as belonging to it because
        // it is all on one side of it. (The reference build we were
        // pointed at orders it the same way, for the same reason.)
        //
        // weight(1f) + Center, NOT SpaceBetween on the parent. With
        // SpaceBetween the hero pinned to the top and the cards to the
        // bottom, leaving a dead void down the middle of the screen and
        // making the page read as two disconnected halves. Letting the
        // hero own the free space centres the primary action optically
        // and the gap disappears. That matters more now than it did:
        // in a FREEDOM REGION the lower stack is a single card, so the
        // hero owns most of the page and has to sit in it as a
        // composition rather than float at the top of it.
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxWidth().weight(1f)
        ) {

// ── NO CREST ──
            //
            // An 84dp mascot used to sit here, above the button.
            // It is gone because the mascot is now INSIDE the
            // dial, and two copies of the same mark 150dp apart
            // is a duplicate, not a hierarchy: the crest existed
            // only to keep the brand's face on screen while the
            // button was a power glyph, and that job no longer
            // exists.
            //
            // It also fixes what this layout got wrong. The crest
            // and the dial were two separate masses competing to
            // be the visual anchor, which is the same inversion
            // this redesign set out to remove, only smaller. One
            // object now owns the centre of the screen.
            //
            // The 86dp it freed went straight back into the dial
            // (216 -> 248dp), so the page did not get emptier —
            // and this is what closes the void on the FREEDOM
            // screen, where the lower stack is a single card and
            // one larger focal element has to hold the space that
            // the upgrade block and meter hold elsewhere.
            //
            // Rostam is not diminished by this. He renders at
            // 132dp in the disc against 84dp as a crest.

                                    // THE PRODUCT.
            PowerButton(
                state = connectionState,
                onClick = onConnectTap
            )

            Spacer(modifier = Modifier.height(18.dp))

            // ── The caption block ──
            //
            // A FIXED MINIMUM HEIGHT with its content top-aligned.
            // This is not cosmetic: the line count here changes with
            // state (connected adds a transport label and a traffic
            // row, connecting adds a step line, failed adds an error
            // and a report affordance) and the hero is vertically
            // centred — so without a reservation the dial SLID UP AND
            // DOWN under the user's finger at exactly the moment they
            // are most likely to tap it again. Sized for the tallest
            // ordinary state; the dial now holds still across every
            // transition.
            val cs = connectionState
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .fillMaxWidth()
                    // 104dp, MEASURED — not the 88 this first
                    // shipped with. Connected is the tallest
                    // ordinary state (36sp status + 20sp hint +
                    // 16sp transport + 16sp traffic + three
                    // gaps = 100dp) and 88 left it 12dp short,
                    // so the dial still slid ~11dp upward on
                    // connect — visible flipping between the
                    // two device captures. A floor that does
                    // not clear the tallest state is not a floor.
                    // ⚠️ min, not max — and it still CLIPPED in Persian.
                    // The 104dp was measured in English. Persian line boxes are
                    // taller, so on this device the connected caption wants more
                    // than the height this Column is offered by its weight(1f)
                    // parent, and Compose measures the overflow away: rostam lost
                    // the bottom half of the traffic row, RedVPN lost the row
                    // entirely and DollarVPN lost the row AND half of the
                    // transport label. Screenshotted on R5CY13EDP7A in fa.
                    //
                    // unbounded = true lets this block report the height it
                    // actually needs instead of accepting the parent's leftover.
                    // It then draws into the gap below, which is empty in every
                    // state (~40dp in English, less in Persian, and the upgrade
                    // block starts after it). Overflowing into empty space is
                    // strictly better than deleting a line of the UI, and the
                    // reservation above still stops the dial sliding.
                    .heightIn(min = 104.dp)
                    .wrapContentHeight(align = Alignment.Top, unbounded = true),
            ) {
                // STATUS — the caption of the object above it. Still
                // headlineLarge/Bold: weight here was never the
                // problem, RANK was. A heavy status is right as long
                // as the action outweighs it, and now it does.
                Text(
                    text = when (cs) {
                        is ConnectionState.Connected -> stringResource(R.string.vpn_on)
                        is ConnectionState.Connecting -> stringResource(R.string.connecting)
                        // Up, and being checked before it is called connected.
                        // Reuses a string already translated in all 39 locales.
                        is ConnectionState.Verifying ->
                            stringResource(R.string.status_finding_path)
                        is ConnectionState.Failed -> stringResource(R.string.connection_failed)
                        is ConnectionState.Disconnected -> stringResource(R.string.vpn_off)
                    },
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.Bold,
                    // onSurface for the resting states, not
                    // onSurfaceVariant. Grey status under a white dial
                    // made the entire hero recede into the page; the
                    // state of the tunnel is a fact about the user's
                    // safety and is never secondary text. `primary`
                    // (the ROLE, so dark mode gets the brand's lighter
                    // tone) is reserved for Connected, where it agrees
                    // with the filled disc directly above it.
                    color = when (cs) {
                        is ConnectionState.Connected -> MaterialTheme.colorScheme.primary
                        is ConnectionState.Connecting -> MaterialTheme.colorScheme.onSurface
                        // Same ink as Connecting: this is still the in-progress
                        // state, and `primary` stays reserved for Connected so
                        // the colour never claims a tunnel the check has not
                        // confirmed.
                        is ConnectionState.Verifying -> MaterialTheme.colorScheme.onSurface
                        is ConnectionState.Failed -> MaterialTheme.colorScheme.error
                        is ConnectionState.Disconnected -> MaterialTheme.colorScheme.onSurface
                    },
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(4.dp))

                // HINT — what a tap does right now.
                Text(
                    text = when (cs) {
                        is ConnectionState.Connected -> stringResource(R.string.tap_to_disconnect)
                        is ConnectionState.Connecting -> {
                            if (autoConnectStatus != null) stringResource(R.string.tap_to_cancel)
                            else if (cs.transport != "auto") stringResource(R.string.trying_server)
                            else stringResource(R.string.connecting_to_server)
                        }
                        // The toggle CANCELS during the check, so the helper must not
                        // invite a tap that would do the opposite of what it says.
                        is ConnectionState.Verifying -> stringResource(R.string.tap_to_cancel)
                        else -> stringResource(R.string.tap_to_connect)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )

                // Transport family on connect — small, light line (never bold like the
                // status). Connecting has its own step text (autoConnectStatus), so this
                // only labels the Connected state, and it stays for as long as we stay
                // connected (no artificial timed hide — it is a quiet one-line label).
                // The FAMILY only (NetCore / WGTCP); never the specific carrier or
                // front that won.
                // The transport name is NOT rendered. §0 forbids a protocol
                // name on any surface a censor can read, and "NetCore" is one.
                // The old comment here said "family only, never the carrier",
                // which missed that the FAMILY name is the protocol. It means
                // nothing to a user either — the dial already says VPN On.

                // Auto-connect step detail (e.g. "[2/5] …")
                if (isConnecting && autoConnectStatus != null) {
                    Spacer(modifier = Modifier.height(3.dp))
                    Text(
                        text = autoConnectStatus!!,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 32.dp)
                    )
                }

                // Traffic stats — compact single row
                if (isConnected && trafficInfo.rxRate.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(5.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(20.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            // isolate(): "9 KB/s" is a Latin number plus a
                            // Latin unit behind a neutral arrow, dropped into
                            // an RTL paragraph. Without the isolate the bidi
                            // algorithm throws the arrow to the far side and a
                            // Persian user reads "KB/s 9 ↓". Caught on device,
                            // and it is the same defect visible in the build we
                            // were handed as a reference.
                            text = isolate("↓ ${trafficInfo.rxRate}"),
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = isolate("↑ ${trafficInfo.txRate}"),
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                // Error detail + report affordance
                if (cs is ConnectionState.Failed) {
                    Spacer(modifier = Modifier.height(5.dp))
                    Text(
                        text = cs.error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error.copy(alpha = 0.8f),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 32.dp)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f))
                            .clickable { viewModel.shareDebugLog() }
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(text = "📩", fontSize = 14.sp)
                        Text(
                            text = stringResource(R.string.share_debug_log),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            }
        }

        // Bottom section: broadcast messages + server card + footer
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(bottom = 12.dp)
        ) {
            // ── The gates arrive RESOLVED ──
            //
            // These were computed here, inline, when there was one
            // layout. They are now hoisted into rememberHomeGates()
            // above the style branch, because three layouts that
            // each re-derive quota state is three chances for the
            // FREEDOM-REGION COLLAPSE to drift apart. Nothing below
            // may re-derive entitlement around them.

            // ── Three things of three different kinds ──────────
            //
            // A promotion, a status readout and a control. They used
            // to share ONE Card structured by two HorizontalDividers;
            // the dividers were the tell, because a rule drawn inside
            // a container is a container doing more than one job. They
            // are three separate objects now, separated by whitespace
            // and never by a rule, so they read as peers rather than
            // as rows of a list one of them happens to own.
            //
            // THE ORDER IS THE OWNER'S, and it is deliberately not the
            // textbook one. A previous pass ran control -> status ->
            // promotion, on the HIG reading that a promotion is the
            // least important thing on a screen and therefore goes
            // last and lightest. The owner reversed it:
            //
            //   1. UPGRADE  — directly under the connect button
            //   2. METER    — in the middle
            //   3. REGION   — at the bottom
            //
            // The argument for it is real, not merely commercial: the
            // instant after someone taps Connect is the instant they
            // care most about what Premium would change, because the
            // thing Premium changes is the thing they are doing. The
            // cost is that the loudest non-hero element now sits
            // closest to the hero, which is exactly where a competitor
            // does the most damage — so the whole burden falls on the
            // upgrade block being unmistakably SUBORDINATE. See
            // UpgradeCta: it takes a wash rather than a fill, its
            // label is ink rather than brand colour, and the only
            // saturated shape in it is a 34dp badge and a small pill.
            // The dial is roughly ten times its area.
            //
            // GATES ARE UNCHANGED, and there are still exactly two.
            // `regionLocked` is the single predicate for "this user is
            // being sold to": it locks the region row AND draws the
            // offer, so the two cannot drift apart. It is
            // `premiumAvailable && !isPaid`, so a FREEDOM REGION sees
            // neither — premiumAvailableNow() is `!isFreedomRegion() ||
            // serverCutOff()`, false for Iran and Russia — and neither
            // does a paid user. The meter keeps its own gate
            // (`meterUsage`). In Iran, therefore, this entire block
            // collapses to one card and the screen is connect + region.
            //
            // One 20dp gap before each, INCLUDING the first, so the
            // rhythm is identical whichever of the three happens to
            // lead — which changes with entitlement and region.

            // 1 ─ THE PROMOTION, adjacent to the action.
            if (gates.sellingPremium) {
                Spacer(modifier = Modifier.height(20.dp))
                UpgradeCta(
                    reducedSpeed = gates.reducedSpeed,
                    onClick = { viewModel.openPaywallAndLoad() },
                )
            }

            // 2 ─ THE STATUS. No card, no elevation, no border: it
            // sits on the page at the same start rail as the card
            // below it, so it reads as page content rather than as a
            // third panel. Whitespace does the work the divider used
            // to fake, and it is NOT tappable in any state — it used
            // to silently become a click target at >= 75% with no
            // visual change whatsoever. The Upgrade block above is
            // where that tap belongs and it is labelled.
            if (gates.showMeter) {
                Spacer(modifier = Modifier.height(20.dp))
                FreeDataMeter(
                    quotaRenews = gates.quotaRenews,
                    fraction = gates.fraction,
                    remainingMb = gates.remainingMb,
                    overQuota = gates.overQuota,
                    reducedSpeed = gates.reducedSpeed,
                    freeSpeed = gates.freeSpeed,
                )
            }

            // 3 ─ THE CONTROL, and the only genuinely operable thing
            // on this half of the screen — so it is the only raised
            // card: a control that looks like one. Same component in
            // both entitlement states; `locked` swaps the chevron for
            // a padlock and the tap for the paywall. It stays a real
            // target (48dp+ from its own 14dp padding around a 20sp
            // line) and it stays honest: what it opens is the only
            // thing that would unlock it.
            //
            // WHITE on the off-white page, deliberately, and that is
            // why `background` is #F5F5F5 rather than #FFFFFF: the gap
            // between the two is what lets this card lift without a
            // border. Do not "tidy" either value.
            // 3a ─ PROTECT MODE, the device-side protection (iOS
            // GuardianHomeCard; the RingsTabsRoot card). Only for a brand
            // with a protection name; absent, the rhythm above is unchanged.
            if (com.rostamvpn.android.RedShield.available) {
                Spacer(modifier = Modifier.height(20.dp))
                Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).clickable(role = Role.Button) { onShield(false) }) {
                    RedShieldCard(viewModel, isConnected, onSeeAll = { onShield(true) },
                        onAsk = { ShieldSheetNav.open = "ask"; onShield(true) })
                }
            }

            Spacer(modifier = Modifier.height(20.dp))
            RegionSelectionCard(
                selectedRegion = selectedRegion,
                regions = viewModel.availableRegions,
                regionLabel = { viewModel.regionLabel(it) },
                onSelect = { viewModel.selectRegion(it) },
                locked = gates.regionLocked,
                onLockedClick = { viewModel.openPaywallAndLoad() },
            )

            Spacer(modifier = Modifier.height(10.dp))

            // Footer — tagline, provenance, build. Three lines,
            // and the same three as iOS (RostamVPNApp.taglineView).
            //
            // `app_tagline` is "Your Digital Guardian" in all 38
            // locales. It also renders in SettingsSheet, so there is
            // one string and no second copy to drift.
            //
            // Splitting them also removes a real bidi fault. The old
            // single line concatenated a TRANSLATED phrase with a
            // Latin version: in an RTL locale "ساخت آمریکا · v6.7.1 ·
            // cfg 12" is a mixed-direction paragraph, so the runs and
            // the "·" separators reorder and the version lands on the
            // wrong side of the provenance. One line per direction is
            // the fix, and it needs no isolate characters.
            val configSuffix = if (configVersion.isNotBlank()) " · cfg $configVersion" else ""
            Text(
                text = stringResource(R.string.app_tagline),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                text = stringResource(R.string.made_in_usa),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                text = "v${com.rostamvpn.android.BuildConfig.VERSION_NAME}$configSuffix",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

/**
 * The metering and entitlement gates, resolved ONCE per frame for whichever
 * home composition renders.
 *
 * Lifted verbatim from the classic layout's own `Column`, where it lived while
 * there was only one layout. With three, computing it per variant would mean
 * three implementations of the FREEDOM-REGION COLLAPSE, and the whole reason
 * Iran and Russia see connect + region and nothing else is that
 * `VpnViewModel.asFreedom()` forces `unlimited = true` on every usage response
 * before it reaches this flow. Resolving it above the branch is what makes that
 * true for every brand by construction instead of by three coincidences.
 *
 * `showMeter` keeps the exact `!unlimited && !paid` predicate the two meter
 * sites already used; `regionLocked` is `premiumAvailable && !isPaid` MINUS
 * whatever the cashier's `region_select` opens up, so the offer and the region
 * lock cannot drift apart — nor from `mayPickRegion()`, which enforces it.
 *
 * Server truth is the FLOOR and live bytes fill in between its 60s reports:
 * `dataTotal` is the same lifetime rx+tx counter reported as `used_mb`, sampled
 * every second, so this is the identical quantity at a finer resolution and
 * never an inflation of it.
 */
@Composable
internal fun rememberHomeGates(
    usage: com.rostamvpn.android.billing.CashierApi.UsageStatus?,
    dataTotal: Long,
    premiumAvailable: Boolean,
    isPaid: Boolean,
    reducedSpeed: Boolean,
    freeTierKbps: Int,
    /** `throttle.hard_stop` — the brand has no free tier past the allowance at
     *  all. Anything that names the free speed must read this FIRST. */
    quotaStops: Boolean,
    /** `paid_only`: no free allowance exists, so no meter and no free speed. */
    paidOnly: Boolean = false,
): HomeGates {
    // `!isPaid` as well as `!it.paid`, and the client's flag is the load-bearing
    // one. `usage.paid` is the SERVER's answer from the last /usage report, which
    // lands every 60s at best and never at all when the cashier is unreachable —
    // the normal condition for a lot of these users. Without the client flag a
    // user who has just paid keeps staring at a monthly allowance bar, which is
    // the one thing their money just bought them out of.
    val meterUsage = usage?.takeIf { !paidOnly && !it.unlimited && !it.paid && !isPaid }
    val usedMb = meterUsage?.let { maxOf(it.usedMb, dataTotal / 1_000_000L) } ?: 0L
    val quotaMb = meterUsage?.freeQuotaMb ?: 0L
    val fraction =
        if (quotaMb > 0) (usedMb.toDouble() / quotaMb).coerceIn(0.0, 1.0).toFloat() else 0f
    return HomeGates(
        // Was `premiumAvailable && !isPaid`, which ignored the cashier's
        // `region_select` entirely and locked every free user. An `until_quota`
        // offering now unlocks the picker WHILE the allowance lasts and re-locks
        // when it runs out — DollarVPN's free gigabyte can try both locations,
        // which is the thing that tells someone whether to pay. Mirrors
        // VpnViewModel.mayPickRegion() exactly, so the row the user sees and the
        // region actually dialled at connect can never disagree. Null usage
        // fails CLOSED, as before.
        regionLocked = premiumAvailable && !isPaid &&
            !(usage?.canSelectRegion == true && usage.overQuota != true),
        sellingPremium = premiumAvailable && !isPaid,
        quotaRenews = usage?.renews ?: true,
        showMeter = meterUsage != null,
        fraction = fraction,
        remainingMb = (quotaMb - usedMb).coerceAtLeast(0L),
        overQuota = meterUsage?.overQuota == true,
        reducedSpeed = reducedSpeed,
        // null when throttling is off in config — in which case NO screen makes
        // a claim about a speed limit at all.
        //
        // 🚨 ALSO null on a hard-stop brand, and this was the bug. `free_kbps`
        // is still 2000 on DollarVPN because one config block configures both
        // the cap and the speed, so an over-quota user's home screen read
        // "Still connected, at 2 Mbps. Premium restores full speed." while
        // connectAuto() was REFUSING the connect. The same screen the user
        // reads immediately before opening the paywall, contradicting it.
        // PaywallScreen has had this guard since 2026-09-21; this copy did not.
        // Anything that names the free speed must read hardStop FIRST.
        freeSpeed = if (quotaStops || paidOnly) null else formatSpeed(freeTierKbps),
        quotaStops = quotaStops,
    )
}

/** MB as a short human string. Deliberately identical to DataUsageScreen's
 *  fmtBytes so the home meter and the Usage stats tab can never render the same
 *  server number two different ways — that reads as a mismatch even when the
 *  underlying values agree. Decimal units, as carriers quote allowances. */
private fun formatMb(mb: Long): String {
    val gb = mb / 1000.0
    return when {
        gb >= 1.0 -> "%.2f GB".format(gb)
        mb >= 1L -> "%.1f MB".format(mb.toDouble())
        else -> "0 MB"
    }
}

/**
 * THE UPGRADE OFFER — one row, and it now sits directly under the connect
 * button because that is where the owner put it.
 *
 * WHAT IT REPLACED, and why not simply restyling that. It was an
 * `OutlinedButton` reading "Upgrade" under a separate grey heading: a hairline
 * rectangle with a word in it, described accurately as soulless. But the
 * reason it was outlined is real and survives here — `FilledTonalButton` maps
 * to `secondaryContainer`/`onSecondaryContainer`, which this theme resolves to
 * the brand's accent under its ink: 6.15:1 for Rostam's gold and 1.24:1 for
 * RedVPN's purple. So "give it weight" could not mean "fill it with the
 * accent", and it does not.
 *
 * FOUR DECISIONS, each measured against BOTH brands:
 *
 * 1. A WASH, NOT A FILL. The container is this brand's own `primary` at 10%
 *    composited over the page. It is derived, so a sixth brand needs no new
 *    value, and it is guaranteed light because 10% of anything over a
 *    near-white is near-white. A wash has body — it is a shape on the page
 *    rather than a line — while staying far below a filled button, which is
 *    what keeps it subordinate to the dial 20dp above it.
 *
 * 2. THE LABEL IS INK, NOT THE BRAND COLOUR. This is the trap again and it
 *    was measured, not assumed: `primary` on its own 10% wash is 4.78:1 for
 *    Rostam but 3.88:1 for RedVPN — a fail, in the brand nobody develops
 *    against. `onSurface` on that same wash is 10.18:1 and 13.69:1. The value
 *    line is therefore ink, which has the side benefit of leaving brand
 *    colour to mean exactly one thing on this screen: the connect action.
 *
 * 3. THE ACCENT IS ONE NOTE. A single 34dp badge, filled with the brand's
 *    accent, carrying a star. Its foreground is chosen by measured contrast
 *    rather than fixed, which is the whole difference between 6.15:1 and
 *    1.24:1: Rostam's gold takes ink, RedVPN's purple takes white (10.23:1).
 *    Gold as a note reads premium; gold as a fill reads like a warning.
 *
 * 4. IT SAYS WHAT YOU GET, THEN WHAT TO DO. The value carries the row and the
 *    verb is a small filled pill at the end — the only saturated rectangle
 *    here, ~84x34dp against the dial's 216dp circle, so roughly a tenth of the
 *    area. `premium_tagline` / `premium_title_throttled`, both of which are
 *    translated in all 38 locales; the old heading used `premium_title_default`,
 *    which is not, and rendered English on the Persian screen.
 *
 * RTL: a plain `Row` with logical `start`/`end` padding, so the badge leads and
 * the pill trails in Persian and Arabic without a mirrored branch.
 *
 * The WHOLE ROW is the target (Role.Button, labelled "Upgrade"); the pill is
 * decorative and semantics-cleared, so there is no button inside a button and
 * TalkBack does not say "Upgrade" twice.
 */
@Composable
private fun UpgradeCta(
    reducedSpeed: Boolean,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    // `secondaryContainer` IS BrandPalette.accent in this theme (Theme.kt), so
    // this is the brand's accent through its colour role rather than the raw
    // `Accent3` val — the dark scheme is then free to differ.
    val accent = scheme.secondaryContainer
    val onAccent = mostLegibleOn(accent, Color.White, DarkBase)
    val onPill = mostLegibleOn(scheme.primary, Color.White, DarkBase)
    val wash = scheme.primary.copy(alpha = 0.10f).compositeOver(scheme.background)
    val shape = RoundedCornerShape(16.dp)
    val label = stringResource(R.string.upgrade)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(wash)
            // A hairline of the brand, not `outline` (#E0E0E0, 1.12:1 on the
            // page — a border you cannot see). It only has to define the edge
            // of the wash on brands whose wash is faint.
            .border(1.dp, scheme.primary.copy(alpha = 0.22f), shape)
            .clickable(onClickLabel = label, role = Role.Button) { onClick() }
            .padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(accent),
        ) {
            Icon(
                imageVector = Icons.Filled.Star,
                contentDescription = null,
                tint = onAccent,
                modifier = Modifier.size(19.dp),
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        Text(
            // Varies with `reducedSpeed`, the one thing about this offer that
            // is ever actually different about the user's day — and the
            // throttled copy can only appear when the core is really
            // rate-limiting, so "get your full speed back" can never be said
            // to someone who never lost it.
            text = stringResource(
                if (reducedSpeed) R.string.premium_title_throttled
                else R.string.premium_tagline
            ),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = scheme.onSurface,
            // No maxLines on purpose. A long translation wraps to a third line
            // and the block grows; nothing is pushed off-screen because the
            // hero above holds weight(1f) and gives the space back. Ellipsising
            // the value would be the worse failure.
            modifier = Modifier.weight(1f),
        )

        Spacer(modifier = Modifier.width(10.dp))

        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = onPill,
            modifier = Modifier
                .clearAndSetSemantics { }
                .clip(RoundedCornerShape(50))
                .background(scheme.primary)
                .padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}

/**
 * Free-data meter — the conversion surface for metered regions, and the only
 * thing on this screen that changes as the product is consumed.
 *
 * It is deliberately NOT a system setting rendered as a progress bar. Three
 * states, escalating only when escalation is true:
 *   < 75%   neutral, plus the one fact that makes the number mean anything —
 *           the allowance is ONE-TIME, not a monthly refill.
 *   >= 75%  amber, and it says what happens next INSTEAD of just warning.
 *   over    the real speed the user is now held at, by name.
 *
 * Three things it must never do, because the people reading it cannot easily
 * complain and the product does not actually treat them this way:
 *   - imply a cutoff. Nothing is cut off. `inReducedSpeedTier()` rate-limits;
 *     the tunnel stays up. Every string here says so.
 *   - overstate consumption. `fraction` comes from the same lifetime counter
 *     that is reported to the cashier as `used_mb`, sampled at 1 Hz instead of
 *     once a minute. Finer resolution, identical quantity.
 *   - appear at all for a freedom region. It cannot: the caller resolves
 *     `meterUsage` through the `!unlimited && !paid` gate, and asFreedom()
 *     forces `unlimited` for Iran and Russia before the flow is ever read.
 *
 * Shows REMAINING rather than consumed. "538 MB left" is a number a person can
 * act on; "462 MB used" is a fact about the past that suggests nothing. It is
 * also the honest framing of a monthly allowance: what is left is all there
 * is until it refills.
 *
 * @param fraction    0..1, already merged from server truth + live local bytes.
 * @param remainingMb what is left of the allowance, in MB.
 * @param freeSpeed   the free tier's ceiling as text ("2 Mbps"), or null when
 *                    throttling is off in config — in which case no claim about
 *                    a speed limit is made anywhere.
 */
/**
 * The CLASSIC connected wash, at [amount] strength.
 *
 * These were four literals (#152A2B / #F0F7F6 / #1A3533 / #E8F0ED) in the branch
 * whose own comment said VALUE derives its wash "from the palette rather than
 * written down". CLASSIC is rostam, internetpro AND goglobe, so the branch that
 * did write them down applied rostam's teal to three products. Same construction
 * as VALUE now: the brand's own primary washed over the resting page.
 */
@Composable
private fun classicTint(isDark: Boolean, amount: Float): Color {
    val scheme = MaterialTheme.colorScheme
    val base = if (isDark) tone(BrandPalette.primary, 0.34f, 0.055f) else scheme.background
    return scheme.primary.copy(alpha = if (isDark) amount * 2.2f else amount)
        .compositeOver(base)
}

/**
 * The three seconds after someone pays.
 *
 * It replaced a toast. A toast is what this app says when a config fetch fails;
 * using the same register for the one moment a user has just given us money
 * read as indifference — the owner's word was "dull".
 *
 * Deliberately NOT a dialog and NOT a new screen: it needs no decision and has
 * no content to read, so it must not need dismissing. The tunnel is already
 * coming up underneath it (see handlePlayPurchase), so this is the thing the
 * user watches while the connect they actually wanted completes. It leaves on
 * its own, and a tap skips it.
 *
 * Every string here already exists in all 38 locales — `premium_active` and
 * `premium_tagline` are the same sentences the Premium banner uses — so the one
 * moment that must never render in English to a Persian speaker costs no new
 * translation work.
 */
@Composable
private fun PremiumCelebration(onDone: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    // Brand roles, never literals: the ground is the brand's own, so all four
    // apps celebrate in their own colour (§23).
    val ground = scheme.background
    val mark = liftOnto(ground, Brand1, min = 4.5f)

    // One spring, driving everything. A stagger of separate animations reads as
    // fussy at this size; a single settle reads as confident.
    val appear = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        appear.animateTo(1f, animationSpec = spring(dampingRatio = 0.55f, stiffness = 140f))
        kotlinx.coroutines.delay(2200)
        onDone()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(ground.copy(alpha = 0.97f))
            // A tap skips it. No ripple: this is a scrim, not a control.
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
            ) { onDone() },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .padding(horizontal = 32.dp)
                .graphicsLayer {
                    val a = appear.value
                    scaleX = 0.88f + 0.12f * a
                    scaleY = 0.88f + 0.12f * a
                    alpha = a
                },
        ) {
            Icon(
                imageVector = Icons.Filled.Star,
                contentDescription = null,
                tint = mark,
                modifier = Modifier.size(72.dp),
            )
            Spacer(Modifier.height(20.dp))
            Text(
                text = stringResource(R.string.premium_active),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = mostLegibleOn(ground, Color.White, DarkBase),
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = stringResource(R.string.premium_tagline),
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(28.dp))
            // The four things they just bought, in the words the paywall used to
            // sell them — so the promise and the receipt cannot drift.
            listOf(
                R.string.paywall_perk_data_title,
                R.string.paywall_perk_speed_title,
                R.string.paywall_perk_ads_title,
                R.string.paywall_perk_regions_title,
            ).forEach { id ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(vertical = 5.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Check,
                        contentDescription = null,
                        tint = mark,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = stringResource(id),
                        style = MaterialTheme.typography.bodyMedium,
                        color = mostLegibleOn(ground, Color.White, DarkBase),
                    )
                }
            }
        }
    }
}

@Composable
internal fun FreeDataMeter(
    fraction: Float,
    remainingMb: Long,
    overQuota: Boolean,
    reducedSpeed: Boolean,
    freeSpeed: String?,
    /** Does the allowance come back? Default true keeps every refilling brand
     *  on the wording it has always had. */
    quotaRenews: Boolean = true,
) {
    val low = fraction >= 0.75f || overQuota

    val scheme = MaterialTheme.colorScheme
    val onPage = mostLegibleOn(scheme.background, Color.White, DarkBase)
    val target = when {
        overQuota -> scheme.error
        // The `warning` role the previous comment here asked for. It is a brand
        // role and not a literal because #D7A23E was hardcoded in three shared
        // files, so all five brands rendered rostam's gold. It cannot be `danger`
        // either: "running low" and "used up" have to read as two states, and
        // collapsing them loses the warning at the one moment it is worth
        // anything. liftOnto because this colour is TEXT as well as the bar fill,
        // so it owes 4.5:1 on the page, not the 3:1 a graphic would owe, and each
        // brand's own warning lands at a different contrast against its own
        // ground.
        low -> liftOnto(scheme.background, BrandPalette.warning, min = 4.5f, toward = onPage)
        else -> Brand1
    }
    // The transition into the reduced tier used to be instantaneous and
    // therefore invisible: one frame the bar was teal, the next it was red, and
    // a user who blinked simply found the VPN slow with no idea why. Animating
    // it makes the single highest-intent moment in the product legible as a
    // moment. 600ms is long enough to be noticed and short enough not to be a
    // performance.
    val accent by animateColorAsState(
        targetValue = target,
        animationSpec = tween(600, easing = FastOutSlowInEasing),
        label = "meterAccent",
    )
    // The bar glides between the 1 Hz samples instead of stepping. Consumption
    // is then something the user WATCHES rather than reads — the drain is the
    // whole argument for upgrading, and it was previously invisible because the
    // fraction only moved when the cashier answered, once a minute.
    val shownFraction by animateFloatAsState(
        targetValue = fraction.coerceIn(0f, 1f),
        animationSpec = tween(900, easing = FastOutSlowInEasing),
        label = "meterFraction",
    )

    // NOT clickable, in any state. This is the screen's one piece of pure
    // status, and status that is silently tappable is a control wearing a
    // disguise — nothing about a progress bar and two grey captions says
    // "double tap for the paywall". It used to become tappable at >= 75%
    // with no visual change whatsoever. The Upgrade button now sits 16dp
    // below it, visible and labelled, so the tap has a real home and this
    // can go back to being a readout.
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // The label IS the state, and it now NAMES the state. "Reduced
            // speed" is a verdict a user cannot evaluate; "Limited to 2 Mbps" is
            // a fact they can, and it is the difference between the free and paid
            // tiers being legible or merely asserted. The figure comes from the
            // same config value the core is rate-limited to, so the screen cannot
            // quote a limit the app is not actually applying.
            Text(
                text = when {
                    reducedSpeed && freeSpeed != null ->
                        stringResource(R.string.speed_state_limited, freeSpeed)
                    reducedSpeed -> stringResource(R.string.speed_state_reduced)
                    else -> stringResource(R.string.speed_state_full)
                },
                style = MaterialTheme.typography.labelSmall,
                color = if (reducedSpeed) accent
                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
            )
            Text(
                // isolate(): "538.0 MB" is Latin digits plus a Latin unit inside
                // a translated sentence that is right-to-left in a third of the
                // shipped locales. Without the isolate the bidi algorithm drags
                // the sentence's own neutrals into the number's run.
                text = if (overQuota) stringResource(R.string.data_free_out)
                       else stringResource(
                           R.string.data_free_remaining, isolate(formatMb(remainingMb))
                       ),
                style = MaterialTheme.typography.labelSmall,
                color = accent,
            )
        }
        Spacer(modifier = Modifier.height(5.dp))
        // 6dp, up from 4dp. The bar is the only object on the home screen that
        // represents the free tier's economics, and at 4dp it read as a hairline
        // divider rather than a gauge.
        LinearProgressIndicator(
            progress = { shownFraction },
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp)),
            color = accent,
            trackColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.15f),
        )
        Spacer(modifier = Modifier.height(5.dp))
        // EXACTLY ONE caption line in every state, so the block never changes
        // height as the tier changes — a layout that jumps under the user at the
        // moment they are being asked for money reads as a trick.
        //
        // What it says escalates, and none of it threatens a cutoff, because
        // there is no cutoff. The normal state discloses the one thing a user
        // cannot otherwise know (this allowance never refills), which is what
        // makes the remaining number worth looking at at all; the later states
        // name the speed the tunnel keeps running at.
        Text(
            text = when {
                overQuota && freeSpeed != null ->
                    stringResource(R.string.data_free_out_note, freeSpeed)
                overQuota -> stringResource(R.string.data_free_out_cta)
                low && freeSpeed != null ->
                    stringResource(R.string.data_free_low_note, freeSpeed)
                low -> stringResource(R.string.data_free_low_cta)
                // "Monthly allowance" is a promise of a refill. Only make it
                // when the server says the allowance actually renews.
                quotaRenews -> stringResource(R.string.data_free_one_time)
                else -> stringResource(R.string.usage_free_allowance)
            },
            style = MaterialTheme.typography.labelSmall,
            color = if (low) accent
                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
        )
    }
}
