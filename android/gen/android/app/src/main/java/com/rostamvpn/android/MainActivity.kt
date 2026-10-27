package com.rostamvpn.android

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.rostamvpn.android.ui.screens.WelcomeScreen
import com.rostamvpn.android.ui.screens.LaunchOverlay
import com.rostamvpn.android.ui.screens.LaunchTouch
import com.rostamvpn.android.ui.screens.LaunchGeometry
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rostamvpn.android.ui.screens.MainScreen
import com.rostamvpn.android.ui.screens.AdaptyPaywallHost
import com.rostamvpn.android.ui.theme.RostamTheme

class MainActivity : ComponentActivity() {

    // The DEVICE's language wins. This runs before onCreate so that string
    // resources (values-fa-rIR / zh-rCN / ru / ar) and the runtime strings, which
    // switch on Locale.getDefault().language, agree with each other.
    //
    // This used to be "Option B": the TIMEZONE overrode the device locale, so a
    // phone set to English showed a Persian UI merely for being on Asia/Tehran.
    // That is the wrong signal for language. A timezone says where a handset is,
    // not what its owner reads, and the two come apart for exactly the people
    // this app exists for: an English speaker travelling into Iran, a diaspora
    // user whose phone is deliberately in English, anyone on a shared or work
    // device. Guessing wrong makes the app unreadable, and a user who cannot
    // read the UI cannot find the language picker to fix it.
    //
    // The OS already carries this answer, the user set it deliberately, and
    // Android 13+ exposes a per-app override on top (locales_config.xml, which
    // this app ships). Settings > Language still overrides everything.
    override fun attachBaseContext(newBase: android.content.Context) {
        val loc = uiLocale(newBase)
        if (loc != null) {
            java.util.Locale.setDefault(loc)
            val cfg = android.content.res.Configuration(newBase.resources.configuration)
            cfg.setLocale(loc)
            // applyOverrideConfiguration (before super) is what actually rebinds the
            // activity's resources to the new locale — createConfigurationContext alone
            // doesn't propagate to a ComponentActivity.
            applyOverrideConfiguration(cfg)
        }
        super.attachBaseContext(newBase)
    }

    /** UI locale precedence: explicit user choice (Settings → Language) >
     *  the device's own language (null = leave the system locale untouched). */
    private fun uiLocale(ctx: android.content.Context): java.util.Locale? {
        val pref = ctx.getSharedPreferences("rostam_prefs", android.content.Context.MODE_PRIVATE)
            .getString("ui_lang", "auto") ?: "auto"
        return when (pref) {
            // "auto" now means the DEVICE's language, which is what a user who
            // never opens the picker expects. Returning null leaves the system
            // locale in place rather than substituting our own guess.
            "", "auto" -> null
            "fa" -> java.util.Locale("fa", "IR")
            "zh" -> java.util.Locale("zh", "CN")
            "en" -> java.util.Locale("en")
            else -> java.util.Locale(pref)
        }
    }


    /**
     * Welcome → app, with the launch animation OVER it rather than before it.
     *
     * Everything before the app is shown ONCE and is skippable. The welcome and
     * sign-in steps are recorded together under one flag: a user who declined an
     * account should not be asked again on every launch, and re-showing a welcome
     * slide to an existing user reads as the app having lost their data.
     *
     * Existing installs must not see this. The flag defaults to "already seen"
     * when the app has clearly run before (a cid is on disk), so an upgrade goes
     * straight to the app rather than greeting ~500K users with an onboarding
     * screen and a login prompt they never asked for.
     *
     * The splash is NOT a stage. It used to be `EntryStage.Splash`, which meant
     * two things: MainScreen was not composed at all while it played (so Connect
     * did not exist and MainScreen's entitlement sync could not start), and it
     * only ran for the ~0 users whose `entry_done` flag was unset -- every one of
     * the 691K existing installs went straight to `App` and got the bare system
     * splash and its flash. It is now a sibling drawn on top, on every launch,
     * over whichever screen is live underneath.
     */
    @Composable
    private fun EntryGate(vm: VpnViewModel) {
        val prefs = remember { getSharedPreferences("rostam_prefs", android.content.Context.MODE_PRIVATE) }
        // The `tabs` treatment has no onboarding slides and no first-run offer
        // (design direction): it opens on Connect, and the first connect of an
        // unpaid paid_only install is the paywall.
        val noEntry = com.rostamvpn.android.ui.screens.rememberBrandStyle() ==
            com.rostamvpn.android.ui.screens.BrandStyle.TABS
        var stage by remember {
            mutableStateOf(
                if (noEntry || prefs.getBoolean("entry_done", ranBefore)) EntryStage.App else EntryStage.Welcome
            )
        }
        // Only a genuinely fresh start. A restored process (or a config change
        // this activity did not declare) must not replay it over a screen the
        // user was already using.
        var launching by remember { mutableStateOf(freshStart) }

        fun finish() {
            prefs.edit().putBoolean("entry_done", true).apply()
            stage = EntryStage.App
        }

        Box(Modifier.fillMaxSize()) {
            when (stage) {
                // Welcome is the LAST step before the app. Sign-in used to sit here
                // and was moved to Settings -> Account: an account only carries a
                // paid subscription, so asking a first-run user for one costs a
                // screen before they can connect and, in the free regions that are
                // most of this install base, could never have applied to them at all.
                // `showPlans` hides the tiers slide where there are no tiers:
                // premiumAvailable is false in freedom regions and for paid
                // users, so it is the same predicate the paywall uses and the
                // two cannot describe different products.
                EntryStage.Welcome -> WelcomeScreen(
                    // paid_only: the slide is "Free, or Premium", and there is no free.
                    showPlans = vm.premiumAvailable.collectAsState().value && !vm.paidOnly,
                    freeTierSlowsWithAds = !vm.quotaStops && vm.adsEverShown,
                    onContinue = { finish() },
                )
                EntryStage.App -> MainScreen(vm)
            }
            // Protect Mode's consent sheet: above home and the Protect Mode
            // screen, under the launch animation and the first-run offer.
            if (stage == EntryStage.App) com.rostamvpn.android.ui.screens.ProtectModeDisclosureHost(vm)
            if (launching) LaunchOverlay(onFinish = { launching = false })
            if (stage == EntryStage.App && !launching && !noEntry) OnboardingOffer(vm, prefs)
            // The VPN disclosure answers a tap, so it is drawn over everything.
            com.rostamvpn.android.ui.screens.VpnDisclosureHost()
        }
    }

    /**
     * The first-run offer: Adapty's `onboarding` placement, once per install,
     * drawn OVER home rather than in front of it.
     *
     * Adapty's own guidance is that most subscriptions are taken during the
     * onboarding journey, and the pattern the owner asked for is the one where
     * the screen after the splash is the offer.
     *
     * 🚨 IT IS AN OVERLAY, NOT AN `EntryStage`, and that is the whole safety
     * argument. A stage would sit between the user and Connect, and for this
     * product Connect is the thing a censored user opened the app to reach. As
     * an overlay, home is already composed and running underneath: dismissing
     * costs one tap, and nothing about the tunnel waits on the offer.
     *
     * Four gates, and each one is a reason NOT to show it:
     *
     * - **The flow must already be loaded.** No spinner, no bounded wait, no
     *   blank screen: if `api.adapty.io` has not answered yet — the ordinary
     *   case on a censored link before the tunnel is up — this run simply does
     *   not show it and a later launch will. Waiting would put a dead screen in
     *   front of the one control that matters.
     * - **`premiumAvailable`.** False in a freedom region and false for a paid
     *   user. Selling a subscription to an Iranian user who is on the free tier
     *   BY POLICY is the worst thing this screen could do, and this is the same
     *   predicate the paywall and the welcome carousel already use, so the
     *   three cannot describe different products.
     * - **Once per install**, recorded BEFORE it renders — see below.
     * - **The brand must have created the placement.** No `if (brand == …)`:
     *   a brand without an `onboarding` placement gets an error from `getFlow`,
     *   caches nothing, and `flowFor` stays null forever. rostam has no Adapty
     *   key at all and never activates the SDK.
     */
    @Composable
    private fun OnboardingOffer(vm: VpnViewModel, prefs: android.content.SharedPreferences) {
        val premiumAvailable by vm.premiumAvailable.collectAsState()
        // 🚨 `premiumAvailable` IS NOT FALSE FOR A PAID USER, which both this
        // comment and the commit message used to claim. It is
        // `!isFreedomRegion() || serverCutOff()` and consults no paid flag, so
        // a subscriber in a paying region has it TRUE. Without `!isPaid` here
        // a paying customer got the subscription-status screen opened over
        // home unasked, once per install, burning the one-shot flag -- and in
        // the window where entitlement has not been restored yet, the SELLING
        // flow. `AdaptyPaywallHost` catches the latter, but only after it has
        // already been opened.
        val isPaid by vm.isPaid.collectAsState()
        var shown by remember { mutableStateOf(prefs.getBoolean(KEY_ONBOARDING_OFFER, false)) }
        var open by remember { mutableStateOf(false) }

        // COLLECTED, not read off the map. A ConcurrentHashMap read during
        // composition never recomposes when the flow lands, so this used to
        // depend on an unrelated recomposition happening to arrive after the
        // Adapty round trip.
        val readyPlacements by AdaptyService.ready.collectAsState()
        val ready = AdaptyService.PLACEMENT_ONBOARDING in readyPlacements

        // ⚠️ `premiumAvailable` is deliberately NOT a key. It can flip mid-run
        // when `serverCutOff()` changes on a usage refresh, and keying on it
        // let a "first-run offer" open over home at an arbitrary later moment,
        // possibly over a live tunnel. Readiness and the one-shot flag are the
        // only legitimate triggers; the value is still read inside.
        LaunchedEffect(ready, shown) {
            if (!shown && ready && premiumAvailable && !isPaid) {
                // 🚨 MARKED SPENT BEFORE IT OPENS, not after it closes. A user
                // who kills the app on this screen would otherwise see it again
                // on the next launch, and again, which is how a first-run offer
                // turns into a nag that earns a one-star review.
                prefs.edit().putBoolean(KEY_ONBOARDING_OFFER, true).apply()
                shown = true
                open = true
            }
        }

        if (open) {
            // Every other paywall entry goes through `_showPaywall`, which
            // `VpnViewModel` collects into `ReviewManager.onPaywallShown()`.
            // This one renders the host directly, so it has to poison the
            // rating session itself -- a five-star ask right after a money ask
            // reliably earns one star, and this surface opens unprompted.
            LaunchedEffect(Unit) { ReviewManager.onPaywallShown() }
            AdaptyPaywallHost(
                viewModel = vm,
                onBack = { open = false },
                placement = AdaptyService.PLACEMENT_ONBOARDING,
            )
        }
    }

    private enum class EntryStage { Welcome, App }

    companion object {
        const val TAG = "RostamMain"
        @JvmField
        var instance: MainActivity? = null

        /** A shared config is a few KB of text; anything bigger is not a config.
         *  Bounds the import so a mis-targeted "open with" cannot OOM the app. */
        /** Set the first time the onboarding offer opens, never cleared. */
        private const val KEY_ONBOARDING_OFFER = "onboarding_offer_shown"
        private const val MAX_CONFIG_CHARS = 256 * 1024

        // Pending VPN start params (for TunnelVpnService)
        @JvmField
        var pendingSocksPort: Int = 1080
        @JvmField
        var pendingAllowedApps: ArrayList<String> = arrayListOf()
        @JvmField
        var pendingSupportsUdp: Boolean = false
        @JvmField
        var hasPendingVpnRequest: Boolean = false

        @JvmField
        var viewModelRef: VpnViewModel? = null

        @JvmStatic
        fun getInstance(): MainActivity? = instance

        @JvmStatic
        fun setPendingVpnRequest(socksPort: Int, allowedApps: ArrayList<String>) {
            Log.i(TAG, "setPendingVpnRequest: port=$socksPort, apps=$allowedApps")
            pendingSocksPort = socksPort
            pendingAllowedApps = allowedApps
            hasPendingVpnRequest = true
        }
    }

    // Permission launcher for VPN
    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            Log.i(TAG, "VPN permission granted")
            viewModelRef?.onVpnPermissionGranted()
        } else {
            Log.e(TAG, "VPN permission denied")
            viewModelRef?.onVpnPermissionDenied()
        }
    }

    fun requestVpnPermission(intent: android.content.Intent) {
        // The in-app VPN disclosure always precedes Android's dialog.
        VpnDisclosure.request { ok ->
            if (ok) vpnPermissionLauncher.launch(intent) else viewModelRef?.onVpnDisclosureDeclined()
        }
    }

    /** Android's VPN dialog for the restore paths, behind the same disclosure. */
    private fun launchVpnDialogAfterDisclosure(intent: android.content.Intent) {
        VpnDisclosure.request { ok ->
            if (ok) vpnPermissionLauncher.launch(intent) else viewModelRef?.onVpnDisclosureDeclined()
        }
    }

    /**
     * The consent dialog on its own, answered by a callback.
     *
     * Deliberately NOT `vpnPermissionLauncher`: that one reports into
     * `VpnViewModel.onVpnPermissionGranted()`, which calls `connectAuto()` and
     * so starts the tunnel directly, skipping the ad gate in `MainScreen`. That
     * is the right behaviour for its own caller — `processPendingVpnRequest`,
     * the restore-after-process-death path — and the wrong behaviour for a tap.
     *
     * A tap needs consent to RESOLVE and then hand control back, so the gate can
     * run in its normal order. Hence a second launcher with a continuation.
     *
     * Consent is what a user sees as "install the VPN certificate", and on many
     * devices the system asks for the lock-screen PIN to confirm it. It is a
     * system dialog: it cannot be styled, skipped or pre-empted, and nothing of
     * ours may be on screen while it is up.
     */
    private var pendingConsentResult: ((Boolean) -> Unit)? = null

    private val vpnConsentLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        // Taken and cleared before it is invoked, so a callback that re-enters
        // this path (the connect gate does exactly that) cannot see a stale one.
        val cb = pendingConsentResult
        pendingConsentResult = null
        cb?.invoke(result.resultCode == Activity.RESULT_OK)
    }

    fun requestVpnConsent(onResult: (Boolean) -> Unit) {
        val intent = VpnService.prepare(this)
        if (intent == null) {
            // Already granted. Answer synchronously rather than bouncing through
            // the launcher, which would not fire at all for a null intent.
            onResult(true)
            return
        }
        // The in-app disclosure first; Not now answers false without ever
        // showing Android's dialog.
        VpnDisclosure.request { ok ->
            if (!ok) {
                onResult(false)
                // Not now is not a failure: back to Disconnected, no error.
                viewModelRef?.onVpnDisclosureDeclined()
                return@request
            }
            pendingConsentResult = onResult
            vpnConsentLauncher.launch(intent)
        }
    }

    /**
     * Whether this install had already run before THIS launch.
     *
     * Sampled at the very top of onCreate, before anything writes: the cid is
     * created a few lines below, so probing for it any later makes a first-ever
     * launch indistinguishable from an upgrade -- which silently skipped the
     * whole entry flow when this was read from the composable instead.
     */
    private var ranBefore: Boolean = false

    /** Whether this onCreate is a cold start rather than a restore. Gates the
     *  launch overlay: replaying it over a screen the user was already looking at
     *  is the one time it WOULD be in the way. */
    private var freshStart: Boolean = true

    /**
     * A tap on the way past ends the launch overlay.
     *
     * `super.dispatchTouchEvent` still runs, so the event reaches the app
     * underneath exactly as it already would -- the overlay is not a hit target.
     * This only stops it from sitting on top of a screen the user has started
     * using: a tap on Connect works either way, but a tap on the drawer would
     * otherwise open it invisibly behind the animation.
     */
    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        if (ev.actionMasked == android.view.MotionEvent.ACTION_DOWN) {
            LaunchTouch.onTouch?.invoke()
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Adapty: activates only for a brand that has a key, and swallows every
        // failure. See AdaptyService -- the built-in PaywallScreen is the
        // fallback, and that is the design rather than a safety net.
        AdaptyService.activateIfConfigured(applicationContext)
        // Only starts for a brand carrying Firebase keys; see Analytics.
        Analytics.initIfConfigured(applicationContext)
        ranBefore = getSharedPreferences("redsocket", android.content.Context.MODE_PRIVATE)
            .contains("redsocket_cid")
        freshStart = savedInstanceState == null
        // Hand the system splash off with NO exit animation. The platform's
        // default is a fade/scale of its own icon, which cross-fades against the
        // identical mark LaunchOverlay draws at the same size in the same place;
        // removing it instead makes the hand-off a single frame with nothing
        // moving. Deliberately NOT setKeepOnScreenCondition -- that delays the
        // first frame, which is exactly the gate this design exists to avoid.
        installSplashScreen().setOnExitAnimationListener { provider ->
            // The one number LaunchGeometry has to assert rather than share.
            // iconView is the KEYLINE aperture (192dp), not the 288dp canvas the
            // drawable is mapped onto, so that is what this compares against.
            // Logged so an OEM that sizes it differently is visible here instead
            // of showing up as an unexplained step at the hand-off.
            provider.iconView.let { icon ->
                val want = (LaunchGeometry.SLOT.value * LaunchGeometry.KEYLINE *
                    resources.displayMetrics.density).toInt()
                Log.i(TAG, "splash icon keyline: ${icon.width}x${icon.height}px (expected ${want}px)")
            }
            provider.remove()
        }
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // Debug-only sample data for the RedShield screens; the release twin ignores it.
        RedShieldDemo.enableFrom(intent)
        // setShowWhenLocked/setTurnScreenOn are Activity methods added in API 27
        // (O_MR1). minSdk is 23, so calling them unconditionally throws
        // NoSuchMethodError on API 23-26 devices — an Error, not an Exception, so
        // nothing here catches it and onCreate crashes. That was a top crash
        // (issue 31a76ff..., ~1030 users on rostam, shared with redvpn's migrated
        // build). Gate on the version that actually has the methods.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        instance = this
        ActivityHolder.currentActivity = this
        // App owns the cid: generate/persist it and INJECT it into netcore
        // (netcore no longer mints its own) before any connect runs.
        com.rostamvpn.android.billing.CashierIdentity.ensure(applicationContext)
        // Dev Tools region override (debug builds only) — restore before the VM
        // reads region, so premiumAvailable/paywall/ads honor it on launch.
        if (BuildConfig.DEBUG) {
            applicationContext.getSharedPreferences("rostam_prefs", MODE_PRIVATE)
                .getString("dev_region", "")?.ifEmpty { null }
                ?.let { com.rostamvpn.android.billing.RegionDetector.devOverride = it }
        }
        Log.i(TAG, "MainActivity created")

        // Warm the WebView engine now, while no VPN tunnel exists. The first
        // WebView creation in a process runs chromium cold-start on the main
        // thread, and its onFirstWebViewCreated network-callback registration
        // blocks on ConnectivityManager — ~10s when a tunnel is up (it froze the
        // ad card's skip countdown). Done here at launch it is cheap (no tunnel
        // to block on) and keeps the engine warm so the ad card is never the
        // process's first, blocking WebView. Eager, not posted: it must land
        // before any connect brings a tunnel up. See WebViewWarmer.
        WebViewWarmer.warmUp(this)

        // Initialize AdMob (first rung of the interstitial waterfall) and warm
        // one interstitial so the first ad slot after connect can show without a
        // load wait. No-fill/unavailable simply falls through to the cashier.
        // Load the config BEFORE the ad gate below. It used to be loaded inside
        // setContent (VpnViewModel.init), which runs AFTER this point -- so the
        // gate read AdsConfig.DEFAULT, which permits ads, and an ads-off region
        // still initialised AdMob and put a request on the wire. Observed with a
        // forced-IR build: 23 requests to googleads.g.doubleclick.net despite
        // ads.regions.IR = "off".
        //
        // It is a small file read and a JSON parse of ~13 KB. Doing it here costs
        // a few ms on a path that already touches disk, and buys the ad gate a
        // config it can actually trust. VpnViewModel loads it again; loadFromJson
        // is idempotent.
        runCatching { ConfigManager.loadFromJson(RemoteConfig.current(applicationContext)) }
            .onFailure { Log.w(TAG, "[ADS] early config load failed: ${it.javaClass.simpleName}") }

        // Both gated on the region's ad policy. Preloading unconditionally meant a
        // device in an ads-OFF region still sent an ad request to
        // googleads.g.doubleclick.net at every launch -- observed with
        // ads.regions.IR = "off" and gl=IR on the wire. "Off" has to mean no
        // request, not just no ad shown: for users in a censored country, traffic
        // to an ad exchange is exactly the kind of thing not to emit on their
        // behalf when we have already decided not to monetise them.
        if (BuildConfig.ADS && AdMobInitializer.allowedHere(this)) {
            // init() starts the SDK and makes no AD request, so it stays on
            // allowedHere: gating it on grace too would leave the SDK
            // uninitialised for the whole process when grace ends mid-session,
            // and this is its ONLY call site.
            AdMobInitializer.init(this)
            // The request is gated separately -- see mayRequestNow. Every other
            // preload (MainScreen:451,479) already goes through
            // admobAllowedForCurrentRegion(), which includes grace; this was the
            // one path that did not.
            if (AdMobInitializer.mayRequestNow(this)) AdMobInterstitial.preload(this)
        } else {
            Log.i(TAG, "[ADS] region is ads-off; AdMob not initialised")
        }

        setContent {
            val vpnViewModel: VpnViewModel = viewModel()
            viewModelRef = vpnViewModel
            val themeMode by vpnViewModel.themeSetting.collectAsState()
            RostamTheme(themeMode = themeMode) {
                EntryGate(vpnViewModel)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        handleDeepLink(intent)
        checkAndProcessPendingVpn()
        // Re-sync entitlement + usage on every resume so a region change (e.g. the
        // user switches the device timezone to Iran) re-registers the cid with the
        // new tz and the cashier re-classifies it (freedom → unlimited / region
        // unlocked / sponsor ads) without needing a full app relaunch.
        viewModelRef?.syncEntitlement()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        RedShieldDemo.enableFrom(intent)

        handleDeepLink(intent)

        if (intent.getBooleanExtra("request_vpn", false)) {
            Log.i(TAG, "onNewIntent - request_vpn flag detected")
            checkAndProcessPendingVpn()
        }
        intent.getStringExtra("select_protocol")?.let { proto ->
            Log.i(TAG, "onNewIntent - select_protocol=$proto")
            viewModelRef?.selectProtocol(proto)
        }
    }

    private fun handleDeepLink(intent: Intent) {
        // An eSIM alert's tap or Reload action (tabs treatment; inert elsewhere).
        com.rostamvpn.android.ui.screens.EsimNav.appCtx = applicationContext
        if (BuildConfig.FEATURE_ESIM) com.rostamvpn.android.ui.screens.EsimSession.init(applicationContext)
        if (com.rostamvpn.android.ui.screens.EsimNav.fromIntent(intent)) return
        // The Quick Settings tile (internetpro) opens the app to connect or to
        // the paywall. Connect goes through toggleConnection -> connect(), so
        // the paid-only gate and the VPN permission prompt both still run.
        intent.getStringExtra("live_action")?.let { a ->
            intent.removeExtra("live_action")
            val vm = viewModelRef
            when (a) {
                "paywall" -> vm?.openPaywallAndLoad()
                "connect" -> if (vm?.connectionState?.value.let { it is VpnViewModel.ConnectionState.Disconnected || it is VpnViewModel.ConnectionState.Failed }) vm?.toggleConnection()
            }
            return
        }
        val action = intent.action
        val uri = intent.data

        // Verified web App Links (brand manifest `deepLinkHosts`, WebLinks.kt).
        // BEFORE the .rostam file branch below, which takes every other VIEW
        // with a URI and would try to read an https link as a config file.
        if (action == Intent.ACTION_VIEW && uri != null) {
            val link = webLinkFor(uri.scheme, uri.host, uri.pathSegments.orEmpty(),
                { k -> runCatching { uri.getQueryParameter(k) }.getOrNull() }, webLinkHosts)
            if (link != null) {
                // The kind only: a token or a code in logcat is a credential.
                // Not ::class.simpleName, which R8 renames to "s2".
                val kind = when (link) {
                    is WebLink.Esim -> "esim"; is WebLink.Number -> "number"; WebLink.Account -> "account"
                    WebLink.Buy -> "buy"; is WebLink.Claim -> "claim"; is WebLink.Open -> "open"; WebLink.Home -> "home"
                }
                Log.i(TAG, "Web link: $kind")
                WebLinkNav.offer(link)
                intent.data = null
                return
            }
        }

        // Handle rostam://c/ deep links (QR config, shared configs)
        if (uri != null && uri.scheme == "rostam" && uri.host == "c") {
            // Account-code activation from the web store ("Open in app"):
            // rostam://c/claim?code=RV-XXXX  → restore entitlement on this device.
            val code = uri.getQueryParameter("code")
            if (!code.isNullOrBlank()) {
                Log.i(TAG, "Deep link account code received")
                // Same confirmation as the https /c/claim link: a link can come
                // from anyone, and a cold one must not switch the account
                // silently. WebLinkNav also survives a cold start, which a
                // direct viewModelRef call did not.
                WebLinkNav.offer(WebLink.Claim(code.trim()))
                intent.data = null
                return
            }
            val path = uri.path ?: return
            val payload = path.removePrefix("/v1/").removePrefix("/")
            if (payload.isNotEmpty()) {
                Log.i(TAG, "Deep link config received: ${payload.take(40)}...")
                viewModelRef?.applyQrConfig(payload)
                intent.data = null
            }
            return
        }

        // Handle .rostam file opened via ACTION_VIEW
        if (action == Intent.ACTION_VIEW && uri != null) {
            handleRostamFileUri(uri)
            intent.data = null
            return
        }

        // Handle .rostam file shared via ACTION_SEND
        if (action == Intent.ACTION_SEND) {
            val sharedUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(Intent.EXTRA_STREAM)
            }
            if (sharedUri != null) {
                handleRostamFileUri(sharedUri)
                intent.action = null
            }
        }
    }

    private fun handleRostamFileUri(uri: Uri) {
        try {
            val inputStream = contentResolver.openInputStream(uri) ?: run {
                Log.e(TAG, "Failed to open config file: null InputStream for $uri")
                viewModelRef?.showToast("Failed to open config file")
                return
            }
            // Bounded read. The manifest claims mimeType="*/*" on content: and
            // file:, so ANY file the user "opens with RostamVPN" lands here — a
            // video or a photo included. readText() pulled the whole thing into a
            // String and OOM'd; Play Vitals for v133 shows 373 users hitting
            // exactly this. A config is a few KB, so anything larger is not one.
            val content = inputStream.use { stream ->
                val buf = CharArray(MAX_CONFIG_CHARS)
                val reader = stream.bufferedReader()
                val n = reader.read(buf, 0, MAX_CONFIG_CHARS)
                if (n <= 0) "" else String(buf, 0, n)
            }.trim()

            if (content.isEmpty()) {
                Log.e(TAG, "Config file is empty: $uri")
                viewModelRef?.showToast("Config file is empty")
                return
            }

            val payload = if (content.startsWith("ROSTAM-CONFIG-V1")) {
                content.substringAfter("\n").trim()
            } else {
                content
            }

            if (payload.isNotEmpty()) {
                Log.i(TAG, "Config file imported: ${payload.take(40)}... (${payload.length} chars)")
                viewModelRef?.applyQrConfig(payload)
            } else {
                viewModelRef?.showToast("Invalid config file")
            }
        } catch (e: Throwable) {
            // Throwable, not Exception: OutOfMemoryError is an Error, so the old
            // catch could never have caught the very failure this function was
            // crashing on.
            Log.e(TAG, "Failed to read config file: ${e.message}", e)
            viewModelRef?.showToast("Failed to read config file")
        }
    }

    private fun checkAndProcessPendingVpn() {
        val prefs = getSharedPreferences("vpn_pending", MODE_PRIVATE)
        val hasPending = prefs.getBoolean("hasPending", false)
        Log.i(TAG, "checkAndProcessPendingVpn - hasPending=$hasPending")

        if (!hasPending) return

        prefs.edit().putBoolean("hasPending", false).apply()

        val port = prefs.getInt("socksPort", 1080)
        val appsStr = prefs.getString("allowedApps", "") ?: ""
        // This pending record predates the settings-backed split-tunnel config and
        // carries no direction, so an empty list here means "nothing was captured",
        // not "the user chose nothing". Fall back to the persisted config rather
        // than starting an unconfigured full tunnel — this is the restart-restore
        // path, and it has to restore what the user actually set.
        val apps = if (appsStr.isNotEmpty()) {
            ArrayList(appsStr.split(","))
        } else {
            TunnelVpnService.savedApps(this)
        }
        val supportsUdp = prefs.getBoolean("supportsUdp", false)
        Log.i(TAG, "Processing pending VPN request: port=$port, apps=$apps, udp=$supportsUdp")

        pendingSocksPort = port
        pendingAllowedApps = apps
        pendingSupportsUdp = supportsUdp

        val vpnIntent = VpnService.prepare(this)
        if (vpnIntent == null) {
            Log.i(TAG, "VPN permission granted, starting service")
            startVpnService(port, apps, supportsUdp)
        } else {
            Log.i(TAG, "VPN permission needed, launching dialog")
            launchVpnDialogAfterDisclosure(vpnIntent)
        }
    }

    override fun onDestroy() {
        instance = null
        ActivityHolder.currentActivity = null
        super.onDestroy()
    }

    fun requestVpnPermissionAndStart(socksPort: Int, allowedApps: ArrayList<String>) {
        Log.i(TAG, "requestVpnPermissionAndStart called: port=$socksPort, apps=$allowedApps")

        pendingSocksPort = socksPort
        pendingAllowedApps = allowedApps

        runOnUiThread {
            val intent = VpnService.prepare(this)
            if (intent != null) {
                launchVpnDialogAfterDisclosure(intent)
            } else {
                startVpnService(socksPort, allowedApps)
            }
        }
    }

    private fun startVpnService(socksPort: Int, allowedApps: ArrayList<String>, supportsUdp: Boolean = false) {
        // The list is meaningless without its direction. Read from the same
        // persisted setting the ViewModel writes, so this path and connectAuto()
        // cannot route the user's list opposite ways.
        val mode = TunnelVpnService.savedMode(this)
        val bypass = TunnelVpnService.savedBypassCidrs(this)
        Log.i(
            TAG,
            "Starting TunnelVpnService with port=$socksPort, mode=$mode, apps=$allowedApps, " +
                "udp=$supportsUdp, bypass=${bypass.size}"
        )

        val intent = Intent(this, TunnelVpnService::class.java).apply {
            action = TunnelVpnService.ACTION_START
            putExtra(TunnelVpnService.EXTRA_SOCKS_PORT, socksPort)
            putStringArrayListExtra(TunnelVpnService.EXTRA_ALLOWED_APPS, allowedApps)
            putExtra(TunnelVpnService.EXTRA_SUPPORTS_UDP, supportsUdp)
            putExtra(TunnelVpnService.EXTRA_APP_LIST_MODE, mode)
            putStringArrayListExtra(TunnelVpnService.EXTRA_BYPASS_CIDRS, bypass)
        }

        try {
            startForegroundService(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start TunnelVpnService: ${e.message}")
        }
    }

    fun stopVpnService() {
        Log.i(TAG, "stopVpnService called")

        runOnUiThread {
            try {
                val intent = Intent(this, TunnelVpnService::class.java)
                stopService(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to stop TunnelVpnService: ${e.message}")
            }
        }
    }
}
