package com.rostamvpn.android.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.rostamvpn.android.BuildConfig
import com.rostamvpn.android.R
import com.rostamvpn.android.VpnViewModel
import com.rostamvpn.android.VpnViewModel.ConnectionState
import com.rostamvpn.android.ui.components.AppInfo
import com.rostamvpn.android.ui.components.AppSelectionSheet
import com.rostamvpn.android.ui.components.RegionSelectionCard
import com.rostamvpn.android.ui.components.RingsDial
import com.rostamvpn.android.ui.components.RostamToast
import com.rostamvpn.android.ui.components.ToastType
import com.rostamvpn.android.ui.theme.Accent3
import com.rostamvpn.android.ui.theme.Brand1
import com.rostamvpn.android.ui.theme.liftOnto

/**
 * THE TABS NAVIGATION of a `navigation: tabs` brand (RedVPN): Home · eSIM ·
 * Account in a Material 3 bottom bar, the Android twin of iOS RingsTabsRoot.
 *
 * Chosen in MainScreen by `BuildConfig.NAV_TABS` (brands/<id>.json
 * `android.navigation`), AFTER MainScreen's effects (entitlement sync, the
 * connect gate, the ad slots) have run, exactly like the `tabs` STYLE. The
 * two are different things: internetpro's `branding.style: tabs` is a whole
 * other design; this keeps the brand's own style (rings) and only replaces
 * the drawer, as iOS does with `navigation: tabs` + a non-tabs style.
 *
 * Hosts every overlay MainScreen hosts (toast, settings, split tunnel, the
 * paywall and its celebration, the China and web-pay sheets, and the ad
 * interstitial only where BuildConfig.ADS is true; RedVPN has no ads), so links and
 * payment results land the same way. Colours come from the brand's own
 * scheme (rings light/dark); no new design tokens.
 *
 * The eSIM tab exists where eSIMs may be sold, or, where they may not
 * (freedom regions, China), only for someone who already owns one so they
 * can reach My eSIMs. Home is the selected tab on every cold start.
 */
@Composable
internal fun RingsTabsRoot(viewModel: VpnViewModel, onConnectTap: () -> Unit) {
    val ctx = LocalContext.current
    val toastMessage by viewModel.toastMessage.collectAsState()
    val showSettings by viewModel.showSettings.collectAsState()
    val showAppSelection by viewModel.showAppSelection.collectAsState()
    val installedApps by viewModel.installedApps.collectAsState()
    val allowedApps by viewModel.allowedApps.collectAsState()
    val connectionState by viewModel.connectionState.collectAsState()
    val showAbout by viewModel.showAbout.collectAsState()
    val showPrivacyPolicy by viewModel.showPrivacyPolicy.collectAsState()
    val showOpenSource by viewModel.showOpenSource.collectAsState()
    val showPaywall by viewModel.showPaywall.collectAsState()
    val cnPayOrder by viewModel.cnPayOrder.collectAsState()
    val webPayUrl by viewModel.webPayUrl.collectAsState()
    val adUrl by viewModel.adUrl.collectAsState()
    val adDebug by viewModel.adDebug.collectAsState()
    val premiumCelebration by viewModel.premiumCelebration.collectAsState()
    val themeSetting by viewModel.themeSetting.collectAsState()
    val showDataUsage by viewModel.showDataUsage.collectAsState()
    val showWebAccount by viewModel.showWebAccount.collectAsState()
    val showEsim by viewModel.showEsim.collectAsState()
    val esimPath by viewModel.esimPath.collectAsState()

    val canPurchase = remember(connectionState is ConnectionState.Disconnected) { viewModel.esimPurchaseAllowed() }
    val sims by ShopStore.sims.collectAsState()
    val showEsimTab = BuildConfig.FEATURE_ESIM && (canPurchase || ShopStore.hasOwned(ctx) || !sims.isNullOrEmpty())

    // 0 Home, 1 eSIM, 2 Account. Never restored across a cold start (iOS:
    // the launch lands on the home dial).
    var tab by rememberSaveable { mutableStateOf(0) }
    val shopStack = remember { mutableStateListOf<ShopPage>() }
    var shopSegment by rememberSaveable { mutableStateOf(0) }

    // Every route into the store lands here, natively: vpn.red/esim/<id> and
    // /number links (WebLinks -> openEsimAt), and any other openEsim() call.
    // The WebView store (EsimScreen) is not used by this navigation.
    LaunchedEffect(showEsim, esimPath) {
        if (!showEsim) return@LaunchedEffect
        val path = esimPath
        viewModel.dismissEsim()
        if (!showEsimTab) return@LaunchedEffect
        tab = 1
        shopSegment = 0
        shopStack.clear()
        shopPageForPath(path)?.let { shopStack.add(it) }
    }
    val esimRoute by EsimNav.route.collectAsState()
    LaunchedEffect(esimRoute) {
        val r = esimRoute ?: return@LaunchedEffect
        EsimNav.consumed()
        if (!showEsimTab) return@LaunchedEffect
        tab = 1
        shopStack.clear()
        when {
            r.mine -> shopSegment = 1
            r.number -> { shopSegment = 0; shopStack.add(ShopPage.Number) }
            r.dest != null -> { shopSegment = 0; shopStack.add(ShopPage.DestId(r.dest)) }
            else -> shopSegment = 0
        }
    }
    if (!showEsimTab && tab == 1) tab = 0

    // RedShield (BuildConfig.PROTECTION_NAME): null = closed, else opens on Traffic when true.
    var shieldSheet by rememberSaveable { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(Unit) {
        // Debug fixture only (src/debug RedShieldDemo; the release twin is inert).
        if (com.rostamvpn.android.RedShieldDemo.openOn != null && com.rostamvpn.android.RedShield.available) {
            shieldSheet = com.rostamvpn.android.RedShieldDemo.openOn != "blocked" &&
                com.rostamvpn.android.RedShieldDemo.openOn != "profiles" && com.rostamvpn.android.RedShieldDemo.openOn != "rules"
        }
    }

    val scheme = MaterialTheme.colorScheme
    // A checkout, an order or an install covers the bar, as the iOS sheets do.
    val barVisible = !(tab == 1 && shopStack.lastOrNull()?.coversBar == true)

    Box(Modifier.fillMaxSize().background(scheme.background)) {
        Scaffold(
            containerColor = scheme.background,
            // Pages pad their own status bar; the bar pads the navigation bar,
            // and a page that covers the bar pads it itself.
            contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0),
            bottomBar = {
                if (barVisible) {
                    RingsNavBar(
                        tab = tab,
                        showEsim = showEsimTab,
                        onSelect = { i ->
                            // Re-selecting the eSIM tab returns to its root, as a system tab bar does.
                            if (i == tab && i == 1) shopStack.clear()
                            tab = i
                        },
                    )
                }
            },
        ) { pad ->
            Box(Modifier.fillMaxSize().padding(pad)) {
                when (tab) {
                    0 -> RingsHomeTab(
                        viewModel = viewModel,
                        onConnectTap = onConnectTap,
                        onShield = { shieldSheet = it },
                        showOffers = showEsimTab && canPurchase,
                        onData = { tab = 1; shopSegment = 0; shopStack.clear() },
                        onNumber = { tab = 1; shopSegment = 0; shopStack.clear(); shopStack.add(ShopPage.Number) },
                    )
                    1 -> ShopTab(stack = shopStack, segment = shopSegment, onSegment = { shopSegment = it }, canPurchase = canPurchase)
                    else -> RingsAccountTab(viewModel)
                }
            }
        }

        // The connect/disconnect toasts say "VPN On/Off"; the home's status
        // already says it, so they are dropped here as on internetpro.
        val dupes = setOf(stringResource(R.string.vpn_on), stringResource(R.string.vpn_off))
        LaunchedEffect(toastMessage) { if (toastMessage in dupes) viewModel.dismissToast() }
        RostamToast(
            message = toastMessage?.takeIf { it !in dupes },
            onDismiss = { viewModel.dismissToast() },
            modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 64.dp),
            type = when {
                toastMessage?.contains("fail", ignoreCase = true) == true -> ToastType.ERROR
                toastMessage?.contains("error", ignoreCase = true) == true -> ToastType.ERROR
                else -> ToastType.INFO
            },
        )

        if (showSettings) {
            com.rostamvpn.android.ui.components.SettingsSheet(
                themeSetting = themeSetting,
                onSetTheme = { viewModel.setTheme(it) },
                onShareDebugLog = { viewModel.shareDebugLog() },
                onSelectApps = { viewModel.dismissSettings(); viewModel.openAppSelection() },
                languageSetting = viewModel.languageSetting.collectAsState().value,
                onSetLanguage = { viewModel.setUiLanguage(it) },
                onDismiss = { viewModel.dismissSettings() },
            )
        }
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
                onDismiss = { viewModel.dismissAppSelection() },
            )
        }
        shieldSheet?.let { onTraffic ->
            RedShieldSheet(viewModel, connectionState is ConnectionState.Connected, onTraffic) { shieldSheet = null }
        }
        if (premiumCelebration) PremiumCelebrationHost { viewModel.dismissPremiumCelebration() }
        if (showAbout) AboutScreen(onBack = { viewModel.dismissAbout() })
        if (showPrivacyPolicy) PrivacyPolicyScreen(onBack = { viewModel.dismissPrivacyPolicy() })
        if (showOpenSource) OpenSourceScreen(onBack = { viewModel.dismissOpenSource() })
        if (showWebAccount) com.rostamvpn.android.WebAccount.Screen(viewModel, onBack = { viewModel.dismissWebAccount() })
        if (showDataUsage) DataUsageScreen(viewModel = viewModel, onBack = { viewModel.dismissDataUsage() })
        if (showPaywall) AdaptyPaywallHost(viewModel = viewModel, onBack = { viewModel.dismissPaywall() })
        cnPayOrder?.let { CnPayScreen(order = it, onDismiss = { viewModel.dismissCnPayment() }) }
        if (BuildConfig.ADS) adUrl?.let { url ->
            AdInterstitialScreen(
                url = url, debug = adDebug,
                onClose = { viewModel.dismissAd() },
                onNoFill = { viewModel.noteAdNoFill() },
            )
        }
        webPayUrl?.let { WebPayScreen(url = it, onClose = { viewModel.dismissWebPay() }) }
    }
}

/** vpn.red / e-sim.net paths the store understands; null = its root. */
internal fun shopPageForPath(path: String?): ShopPage? {
    val seg = path.orEmpty().trim('/').split('/').filter { it.isNotBlank() }
    return when (seg.firstOrNull()) {
        "number" -> ShopPage.Number
        "esim" -> seg.getOrNull(1)?.let { ShopPage.DestId(it) }
        else -> null
    }
}

@Composable
private fun RingsNavBar(tab: Int, showEsim: Boolean, onSelect: (Int) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    // The selected label is TEXT, so the lifted brand role (4.5:1), never raw red.
    val selected = liftText(scheme.surfaceContainer, Brand1, min = 4.5f)
    NavigationBar(containerColor = scheme.surfaceContainer, tonalElevation = 0.dp) {
        val items = buildList {
            add(Triple(0, R.string.tab_home, R.drawable.ic_rv_home))
            if (showEsim) add(Triple(1, R.string.tab_esim, R.drawable.ic_rv_sim))
            add(Triple(2, R.string.tab_account, R.drawable.ic_rv_account))
        }
        items.forEach { (i, label, icon) ->
            NavigationBarItem(
                selected = tab == i,
                onClick = { onSelect(i) },
                icon = { Icon(painterResource(icon), contentDescription = null) },
                label = { Text(stringResource(label), fontWeight = if (tab == i) FontWeight.SemiBold else FontWeight.Medium) },
                colors = NavigationBarItemDefaults.colors(
                    indicatorColor = Brand1.copy(alpha = 0.16f),
                    selectedIconColor = selected,
                    selectedTextColor = selected,
                    unselectedIconColor = scheme.onSurfaceVariant,
                    unselectedTextColor = scheme.onSurfaceVariant,
                ),
            )
        }
    }
}

// ── Home ─────────────────────────────────────────────────────────────────────

/**
 * The Home tab, iOS RingsHome: wordmark + Upgrade chip in the bar, the dial
 * as the one loud object over the owner's dotted world map (static, 6% brand
 * red on the light page, 8% ink on the dark one), the status word, the
 * location card (with the free-data meter), the "Data" line with its two
 * tiles (Travel eSIM, US number) where eSIMs may be sold, and the full
 * upgrade banner only when the user is over quota.
 */
@Composable
private fun RingsHomeTab(
    viewModel: VpnViewModel,
    onConnectTap: () -> Unit,
    onShield: (Boolean) -> Unit,
    showOffers: Boolean,
    onData: () -> Unit,
    onNumber: () -> Unit,
) {
    val connectionState by viewModel.connectionState.collectAsState()
    val autoConnectStatus by viewModel.autoConnectStatus.collectAsState()
    val trafficInfo by viewModel.trafficInfo.collectAsState()
    val selectedRegion by viewModel.selectedRegion.collectAsState()
    val usage by viewModel.usage.collectAsState()
    val dataTotal by viewModel.dataTotal.collectAsState()
    val premiumAvailable by viewModel.premiumAvailable.collectAsState()
    val isPaid by viewModel.isPaid.collectAsState()
    val reducedSpeed by viewModel.reducedSpeed.collectAsState()
    val gates = rememberHomeGates(
        usage = usage, dataTotal = dataTotal, premiumAvailable = premiumAvailable, isPaid = isPaid,
        reducedSpeed = reducedSpeed, freeTierKbps = viewModel.freeTierKbps, quotaStops = viewModel.quotaStops,
        paidOnly = viewModel.paidOnly,
    )
    val scheme = MaterialTheme.colorScheme
    val dark = ShopColors.dark()
    val ctx = LocalContext.current

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        // ── The bar: wordmark (ink, not red: the dial is the only red above
        // the fold) and the chip.
        Row(
            Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.app_name),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = scheme.onBackground,
                modifier = Modifier.weight(1f).semantics { heading() },
            )
            when {
                gates.sellingPremium -> UpgradeChip { viewModel.openPaywallAndLoad() }
                isPaid -> ProChip { viewModel.openPaywallAndLoad() }
            }
        }

        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // The dial over the map. The map is full width at its own aspect,
            // centred on the dial, and takes no touches.
            var dialH by remember { mutableStateOf(0) }
            Box(
                Modifier.fillMaxWidth().padding(top = 8.dp).onSizeChanged { dialH = it.height },
                contentAlignment = Alignment.Center,
            ) {
                val data = remember { worldDots(ctx) }
                if (data != null) {
                    val tint = if (dark) scheme.onBackground.copy(alpha = 0.08f) else Brand1.copy(alpha = 0.06f)
                    Canvas(Modifier.matchParentSize()) {
                        val h = size.width / data.aspect
                        drawWorldDots(data, tint, top = (size.height - h) / 2f)
                    }
                }
                RingsDial(state = connectionState, onClick = onConnectTap)
            }

            HomeStatus(
                state = connectionState,
                autoConnectStatus = autoConnectStatus,
                rx = trafficInfo.rxRate,
                tx = trafficInfo.txRate,
                freeUser = gates.sellingPremium,
                onRetry = onConnectTap,
            )

            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(top = 24.dp, bottom = 24.dp)) {
                // The device-side protection, under the dial (iOS GuardianHomeCard).
                if (com.rostamvpn.android.RedShield.available) {
                    Box(Modifier.clip(RoundedCornerShape(18.dp)).clickable(role = Role.Button) { onShield(false) }) {
                        RedShieldCard(viewModel, connectionState is ConnectionState.Connected, onSeeAll = { onShield(true) },
                            onAsk = { ShieldSheetNav.open = "ask"; onShield(true) })
                    }
                    Spacer(Modifier.height(12.dp))
                }
                RegionSelectionCard(
                    selectedRegion = selectedRegion,
                    regions = viewModel.availableRegions,
                    regionLabel = { viewModel.regionLabel(it) },
                    onSelect = { viewModel.selectRegion(it) },
                    locked = gates.regionLocked,
                    onLockedClick = { viewModel.openPaywallAndLoad() },
                )
                if (gates.showMeter) {
                    Spacer(Modifier.height(12.dp))
                    FreeDataMeter(
                        quotaRenews = gates.quotaRenews, fraction = gates.fraction, remainingMb = gates.remainingMb,
                        overQuota = gates.overQuota, reducedSpeed = gates.reducedSpeed, freeSpeed = gates.freeSpeed,
                    )
                }
                if (showOffers) {
                    Spacer(Modifier.height(24.dp))
                    Text(
                        stringResource(R.string.home_data),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = scheme.onSurfaceVariant,
                        modifier = Modifier.semantics { heading() },
                    )
                    Text(
                        stringResource(R.string.home_data_line),
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OfferTile(
                            painterResource(R.drawable.ic_rv_sim), stringResource(R.string.home_travel_esim),
                            stringResource(R.string.home_countries_170), onData, Modifier.weight(1f),
                        )
                        OfferTile(
                            androidx.compose.ui.graphics.vector.rememberVectorPainter(Icons.Filled.Phone),
                            stringResource(R.string.home_us_number), stringResource(R.string.home_second_line), onNumber, Modifier.weight(1f),
                        )
                    }
                }
                if (gates.sellingPremium && gates.overQuota) {
                    Spacer(Modifier.height(16.dp))
                    UpgradePill(onClick = { viewModel.openPaywallAndLoad() })
                }
            }
        }
    }
}

@Composable
private fun HomeStatus(
    state: ConnectionState,
    autoConnectStatus: String?,
    rx: String,
    tx: String,
    freeUser: Boolean,
    onRetry: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val success = liftText(scheme.background, ShopColors.success, min = 4.5f)
    val title = when (state) {
        is ConnectionState.Connected -> stringResource(R.string.home_connected)
        is ConnectionState.Connecting, is ConnectionState.Verifying -> stringResource(R.string.home_connecting)
        is ConnectionState.Failed -> stringResource(R.string.home_failed)
        is ConnectionState.Disconnected -> stringResource(R.string.home_not_connected)
    }
    val titleColor = when (state) {
        is ConnectionState.Connected -> success
        is ConnectionState.Disconnected -> scheme.onSurfaceVariant
        // Ink, never `danger` as text: the glyph carries the alarm.
        else -> scheme.onBackground
    }
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(top = 16.dp).heightIn(min = 72.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            when (state) {
                is ConnectionState.Connected -> Icon(Icons.Filled.Lock, null, tint = success, modifier = Modifier.size(22.dp))
                is ConnectionState.Failed -> Icon(Icons.Filled.Warning, null, tint = liftText(scheme.background, Brand1, min = 3f), modifier = Modifier.size(22.dp))
                else -> Unit
            }
            if (state is ConnectionState.Connected || state is ConnectionState.Failed) Spacer(Modifier.width(8.dp))
            Text(
                title,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = titleColor,
                textAlign = TextAlign.Center,
                modifier = Modifier.semantics { heading() },
            )
        }
        Spacer(Modifier.height(6.dp))
        val helper = when (state) {
            is ConnectionState.Connected ->
                if (rx.isEmpty()) stringResource(R.string.tap_to_disconnect) else isolate("↓ $rx   ↑ $tx")
            is ConnectionState.Connecting ->
                autoConnectStatus ?: if (state.transport != "auto") stringResource(R.string.trying_server) else stringResource(R.string.connecting_to_server)
            is ConnectionState.Verifying -> stringResource(R.string.status_finding_path)
            is ConnectionState.Failed -> state.error.ifBlank { stringResource(R.string.home_try_again_long) }
            is ConnectionState.Disconnected ->
                if (freeUser) stringResource(R.string.home_free_helper) else stringResource(R.string.tap_to_connect)
        }
        Text(
            helper,
            style = MaterialTheme.typography.bodyMedium,
            color = scheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (state is ConnectionState.Failed) {
            Spacer(Modifier.height(12.dp))
            Text(
                stringResource(R.string.home_try_again),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(Brand1)
                    .clickable(role = Role.Button, onClick = onRetry)
                    .padding(horizontal = 20.dp, vertical = 9.dp),
            )
        }
    }
}

/** The bar's Upgrade chip: the rings gradient, white label (4.66:1 on the red end). */
@Composable
private fun UpgradeChip(onClick: () -> Unit) {
    val label = stringResource(R.string.home_upgrade)
    Row(
        Modifier
            .height(34.dp)
            .clip(RoundedCornerShape(50))
            .background(Brush.horizontalGradient(listOf(Accent3, Brand1)))
            .clickable(onClickLabel = label, role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Star, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(5.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = Color.White)
    }
}

@Composable
private fun ProChip(onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier
            .height(34.dp)
            .clip(RoundedCornerShape(50))
            .background(scheme.surface)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = ShopColors.accentOnCard(), modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(5.dp))
        Text(stringResource(R.string.home_pro).uppercase(), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = scheme.onSurface)
    }
}

/** One of the two Data offers: a 32dp icon tile, a title and one line, 96dp tall. */
@Composable
private fun OfferTile(icon: Painter, title: String, line: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier
            .heightIn(min = 96.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(scheme.surface)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(12.dp),
    ) {
        Box(
            Modifier.size(32.dp).clip(RoundedCornerShape(10.dp)).background(Brand1.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, contentDescription = null, tint = ShopColors.accentOnCard(), modifier = Modifier.size(18.dp)) }
        Spacer(Modifier.height(8.dp).weight(1f, fill = false))
        Spacer(Modifier.height(8.dp))
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = scheme.onSurface, maxLines = 2)
        Text(line, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, maxLines = 2)
    }
}

// ── eSIM tab ─────────────────────────────────────────────────────────────────

/** A page of the eSIM tab's own stack (iOS NavigationStack pushes and sheets). */
internal sealed class ShopPage {
    /** True for what iOS presents as a sheet over the tab bar. */
    open val coversBar: Boolean get() = false

    data class Dest(val dest: PassDest) : ShopPage()
    data class DestId(val id: String) : ShopPage()
    data class Collection(val c: PassCollection) : ShopPage()
    data object Number : ShopPage()
    data class Detail(val sim: Esim) : ShopPage()
    data class Checkout(val plan: PassPlan, val dest: PassDest?, val iccid: String? = null) : ShopPage() {
        override val coversBar get() = true
    }
    data class Order(val orderId: String, val isNumber: Boolean?) : ShopPage() {
        override val coversBar get() = true
    }
    data class Install(val sim: Esim, val isNumber: Boolean) : ShopPage() {
        override val coversBar get() = true
    }
    data object Restore : ShopPage() { override val coversBar get() = true }
    data object AccessCode : ShopPage() { override val coversBar get() = true }
}

/** The tab: the shop with a Shop | My eSIMs segment; only My eSIMs where nothing may be sold. */
@Composable
private fun ShopTab(stack: SnapshotStateList<ShopPage>, segment: Int, onSegment: (Int) -> Unit, canPurchase: Boolean) {
    val nav = remember(stack) { ShopNav(stack) }
    BackHandler(enabled = stack.isNotEmpty()) { nav.pop() }
    AnimatedContent(
        targetState = stack.lastOrNull(),
        transitionSpec = { fadeIn() togetherWith fadeOut() },
        label = "shop",
    ) { page ->
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            when (page) {
                null -> ShopRoot(nav, segment = if (canPurchase) segment else 1, onSegment = onSegment, canPurchase = canPurchase)
                is ShopPage.Dest -> DestinationPage(nav, page.dest.id, page.dest, canPurchase)
                is ShopPage.DestId -> DestinationPage(nav, page.id, null, canPurchase)
                is ShopPage.Collection -> CollectionPage(nav, page.c, canPurchase)
                ShopPage.Number -> NumberPage(nav, canPurchase)
                is ShopPage.Detail -> EsimDetailPage(nav, page.sim, canPurchase)
                is ShopPage.Checkout -> CheckoutPage(nav, page.plan, page.dest, page.iccid)
                is ShopPage.Order -> OrderPage(nav, page.orderId, page.isNumber)
                is ShopPage.Install -> InstallPage(nav, page.sim, page.isNumber, onDone = { nav.pop() })
                ShopPage.Restore -> RestorePage(nav)
                ShopPage.AccessCode -> AccessCodePage(nav)
            }
        }
    }
}

/** Push and pop for the shop's pages. */
internal class ShopNav(private val stack: SnapshotStateList<ShopPage>) {
    fun push(p: ShopPage) { stack.add(p) }
    fun pop() { if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex) }
    /** Swap the top page (checkout -> order progress), so Back leaves the flow. */
    fun replace(p: ShopPage) { pop(); push(p) }
    /** Close a purchase: drop every covering page and return to what was under it. */
    fun finishFlow() { while (stack.lastOrNull()?.coversBar == true) pop() }
}
