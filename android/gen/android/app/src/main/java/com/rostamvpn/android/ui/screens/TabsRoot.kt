package com.rostamvpn.android.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import com.rostamvpn.android.ui.theme.TabsLight
import com.rostamvpn.android.ui.theme.TabsDark
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rostamvpn.android.R
import app.netcore.NetCoreBridge
import com.rostamvpn.android.VpnViewModel
import com.rostamvpn.android.VpnViewModel.ConnectionState
import com.rostamvpn.android.ui.components.AppInfo
import com.rostamvpn.android.ui.components.AppSelectionSheet
import com.rostamvpn.android.ui.components.RostamToast
import com.rostamvpn.android.ui.components.SettingsSheet
import com.rostamvpn.android.ui.components.ToastType
import kotlinx.coroutines.delay
import org.json.JSONObject

/**
 * The `tabs` treatment: Connect, Passes, Account, from the locked Internet Pro
 * design (July fork 3cfd719) plus DESIGN-DIRECTION-2026-10-12. Selected by
 * `branding.style`, never by brand. Hosts every overlay MainScreen hosts, so
 * deep links, payment results and the paywall land the same way.
 *
 * Colours come from the TABS colour scheme only (primary = the teal accent),
 * so light and dark are the same design with different tokens.
 */
@Composable
internal fun TabsRoot(viewModel: VpnViewModel, onConnectTap: () -> Unit) {
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
    val premiumCelebration by viewModel.premiumCelebration.collectAsState()
    val themeSetting by viewModel.themeSetting.collectAsState()

    var tab by rememberSaveable { mutableIntStateOf(0) }
    // An eSIM route (alert notification now, deep links later) opens the tab.
    val esimRoute by EsimNav.route.collectAsState()
    LaunchedEffect(esimRoute) { if (esimRoute != null) tab = 1 }
    val scheme = MaterialTheme.colorScheme
    // A TabsSheet hides the bar while it is up, so sheets are full height
    // and nothing (the checkout form, the install codes) sits under it.
    val sheetsUp = remember { mutableIntStateOf(0) }
    val tabHairline = if (scheme.background.luminance() < 0.5f) TabsDark.hairline else TabsLight.hairline

    Box(Modifier.fillMaxSize().background(scheme.background)) {
        Scaffold(
            containerColor = scheme.background,
            bottomBar = {
                if (sheetsUp.intValue == 0) NavigationBar(
                    containerColor = scheme.surfaceContainerLow,
                    modifier = Modifier.drawBehind {
                        // The design's hairline above the tab bar.
                        drawLine(tabHairline, Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx())
                    },
                ) {
                    listOf(
                        Triple(R.string.tabs_connect, R.drawable.ic_tab_connect, 0),
                        Triple(R.string.tabs_passes, R.drawable.ic_tab_passes, 1),
                        Triple(R.string.tabs_account, R.drawable.ic_tab_account, 2),
                    ).forEach { (label, icon, i) ->
                        val text = stringResource(label)
                        NavigationBarItem(
                            selected = tab == i,
                            onClick = { tab = i },
                            icon = { Icon(painterResource(icon), contentDescription = null) },
                            label = {
                                Text(text, fontWeight = if (tab == i) FontWeight.Bold else FontWeight.SemiBold)
                            },
                            colors = NavigationBarItemDefaults.colors(
                                indicatorColor = scheme.primary.copy(alpha = 0.18f),
                                selectedIconColor = scheme.primary,
                                selectedTextColor = scheme.primary,
                                unselectedIconColor = scheme.onSurfaceVariant,
                                unselectedTextColor = scheme.onSurfaceVariant,
                            ),
                        )
                    }
                }
            },
        ) { pad ->
            Box(Modifier.fillMaxSize().padding(pad).statusBarsPadding()) {
                androidx.compose.runtime.CompositionLocalProvider(LocalSheetsUp provides sheetsUp) {
                    when (tab) {
                        0 -> ConnectTab(viewModel, onConnectTap)
                        1 -> PassesTab()
                        else -> AccountTab(viewModel)
                    }
                }
            }
        }

        // The shared connect/disconnect toasts say "VPN On/Off". The status
        // line already says it, in this treatment's words, so drop them.
        val dupes = setOf(stringResource(R.string.vpn_on), stringResource(R.string.vpn_off))
        LaunchedEffect(toastMessage) { if (toastMessage in dupes) viewModel.dismissToast() }
        RostamToast(
            message = toastMessage?.takeIf { it !in dupes },
            onDismiss = { viewModel.dismissToast() },
            modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 64.dp),
            type = ToastType.INFO,
        )

        if (showSettings) {
            SettingsSheet(
                themeSetting = themeSetting,
                onSetTheme = { viewModel.setTheme(it) },
                onShareDebugLog = { viewModel.shareDebugLog() },
                onSelectApps = { viewModel.dismissSettings(); viewModel.openAppSelection() },
                languageSetting = viewModel.languageSetting.collectAsState().value,
                onSetLanguage = { viewModel.setUiLanguage(it) },
                onDismiss = { viewModel.dismissSettings() },
                // No padlocks in this design.
                appsIcon = Icons.AutoMirrored.Filled.List,
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
        if (premiumCelebration) PremiumCelebrationHost { viewModel.dismissPremiumCelebration() }
        if (showAbout) AboutScreen(onBack = { viewModel.dismissAbout() })
        if (showPrivacyPolicy) PrivacyPolicyScreen(onBack = { viewModel.dismissPrivacyPolicy() })
        if (showOpenSource) OpenSourceScreen(onBack = { viewModel.dismissOpenSource() })
        if (showPaywall) AdaptyPaywallHost(viewModel = viewModel, onBack = { viewModel.dismissPaywall() })
        cnPayOrder?.let { CnPayScreen(order = it, onDismiss = { viewModel.dismissCnPayment() }) }
        webPayUrl?.let { WebPayScreen(url = it, onClose = { viewModel.dismissWebPay() }) }
        // No interstitial host: this treatment has no ads (design direction).
    }
}

// ── Connect ──────────────────────────────────────────────────────────────────

/** Recent refusals from the core, polled while visible. Memory only (bridge doc). */
internal data class GuardStats(
    val total: Long,
    val recent: List<GuardEvent>,
    val byCategory: Map<String, Long>,
)

internal data class GuardEvent(val host: String, val port: Int, val category: String, val secsAgo: Long)

internal fun parseGuardStats(json: String): GuardStats = runCatching {
    val o = JSONObject(json)
    val arr = o.optJSONArray("recent")
    val cats = o.optJSONObject("by_category")
    GuardStats(
        total = o.optLong("blocked_total"),
        recent = (0 until (arr?.length() ?: 0)).map {
            val e = arr!!.getJSONObject(it)
            GuardEvent(e.optString("host"), e.optInt("port"), e.optString("category"), e.optLong("secs_ago"))
        },
        byCategory = cats?.keys()?.asSequence()?.associateWith { cats.optLong(it) } ?: emptyMap(),
    )
}.getOrDefault(GuardStats(0, emptyList(), emptyMap()))

internal fun readGuardStats(): GuardStats =
    if (!NetCoreBridge.isLoaded) GuardStats(0, emptyList(), emptyMap())
    else runCatching { parseGuardStats(NetCoreBridge.getBlockStats()) }.getOrDefault(GuardStats(0, emptyList(), emptyMap()))

@Composable
private fun ConnectTab(viewModel: VpnViewModel, onConnectTap: () -> Unit) {
    val state by viewModel.connectionState.collectAsState()
    val traffic by viewModel.trafficInfo.collectAsState()
    val selectedRegion by viewModel.selectedRegion.collectAsState()
    val guardianOn by viewModel.guardianOn.collectAsState()
    val blockedToday by viewModel.blockedToday.collectAsState()
    val notice by viewModel.notice.collectAsState()
    val protectionAcked by viewModel.protectionAcked.collectAsState()
    val scheme = MaterialTheme.colorScheme
    val connected = state is ConnectionState.Connected
    val connecting = state is ConnectionState.Connecting || state is ConnectionState.Verifying

    var guardianOpen by remember { mutableStateOf(false) }
    var stats by remember { mutableStateOf(GuardStats(0, emptyList(), emptyMap())) }
    LaunchedEffect(connected) {
        while (connected) { stats = readGuardStats(); delay(3000) }
    }
    // Elapsed time from the moment THIS screen saw Connected. The VM has no
    // connect timestamp, and a clock that restarts on recomposition would lie.
    var connectedAt by remember { mutableLongStateOf(0L) }
    LaunchedEffect(connected) { connectedAt = if (connected) System.currentTimeMillis() else 0L }

    // Only once the exit has acknowledged the caps on this tunnel.
    val protectedNow = connected && protectionAcked && guardianOn

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Mark + wordmark. No plan chip: there is no free tier to contrast with.
        Row(Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Image(painterResource(R.drawable.brand_mark), contentDescription = null, modifier = Modifier.size(26.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                stringResource(R.string.app_name), fontSize = 17.sp, fontWeight = FontWeight.ExtraBold,
                letterSpacing = (-0.3).sp, color = scheme.onBackground,
            )
        }

        // Notices from the mailbox: one quiet row, never a push.
        notice?.let { n ->
            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp).clip(RoundedCornerShape(12.dp))
                    .background(scheme.surface).border(1.dp, scheme.outlineVariant, RoundedCornerShape(12.dp))
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(n.title, fontSize = 13.5.sp, fontWeight = FontWeight.Bold, color = scheme.onSurface)
                    if (n.body.isNotBlank()) Text(n.body, fontSize = 12.5.sp, color = scheme.onSurfaceVariant)
                }
                Icon(
                    Icons.Filled.Close, contentDescription = stringResource(R.string.cd_close),
                    tint = scheme.onSurfaceVariant,
                    modifier = Modifier.clip(CircleShape).clickable { viewModel.dismissNotice() }.padding(6.dp).size(18.dp),
                )
            }
        }
        Spacer(Modifier.height(if (notice == null) 44.dp else 20.dp))
        // An unpaid paid_only tap goes to the VM, whose gate opens the paywall
        // before the system permission prompt. MainScreen's connect gate asks
        // for consent first, which would show an unpaid user the OS dialog.
        val isPaid by viewModel.isPaid.collectAsState()
        RingDial(state) {
            if (viewModel.paidOnly && !isPaid && !connected && !connecting) viewModel.toggleConnection()
            else onConnectTap()
        }
        Spacer(Modifier.height(26.dp))

        // THE status line: always true, and the door to the Guardian.
        val status = when {
            protectedNow -> stringResource(R.string.tabs_status_protected, blockedToday)
            connected -> stringResource(R.string.tabs_status_connected)
            connecting -> stringResource(R.string.tabs_status_connecting)
            state is ConnectionState.Failed -> stringResource(R.string.tabs_status_failed)
            else -> stringResource(R.string.tabs_status_off)
        }
        Text(
            status,
            fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.4).sp,
            textAlign = TextAlign.Center,
            color = if (state is ConnectionState.Failed) scheme.error else scheme.onBackground,
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .clickable { guardianOpen = true }
                .padding(horizontal = 12.dp, vertical = 6.dp),
        )
        if (connected) {
            Text(
                stringResource(R.string.tabs_guardian_open), fontSize = 13.sp, color = scheme.primary,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { guardianOpen = true }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }

        Spacer(Modifier.height(24.dp))

        // Throughput only when the transport reports it. Absent, never "—".
        val real = { s: String -> s.isNotBlank() && !s.startsWith("0 ") && s != "0" }
        if (connected && (real(traffic.rxRate) || real(traffic.txRate))) {
            var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
            LaunchedEffect(connectedAt) { while (connectedAt > 0) { now = System.currentTimeMillis(); delay(1000) } }
            val s = ((now - connectedAt) / 1000).coerceAtLeast(0)
            val elapsed = String.format(java.util.Locale.US, "%02d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                TabsStat(stringResource(R.string.tabs_elapsed), elapsed, Modifier.weight(1f))
                TabsStat(stringResource(R.string.tabs_download), traffic.rxRate, Modifier.weight(1f), scheme.primary)
                TabsStat(stringResource(R.string.tabs_upload), traffic.txRate, Modifier.weight(1f))
            }
            Spacer(Modifier.height(9.dp))
        }

        // Route: read-only until the server has a region contract.
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(scheme.surface)
                .border(1.dp, scheme.outlineVariant, RoundedCornerShape(14.dp))
                .padding(horizontal = 14.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.tabs_route), fontSize = 13.5.sp, color = scheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            Text(
                if (selectedRegion == "auto") stringResource(R.string.tabs_route_auto) else viewModel.regionLabel(selectedRegion),
                fontSize = 13.5.sp, fontWeight = FontWeight.Bold, color = scheme.onSurface,
            )
        }

        Spacer(Modifier.weight(1f))
        if (connected) {
            Text(
                stringResource(R.string.tabs_disconnect),
                fontSize = 15.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, color = scheme.onBackground,
                modifier = Modifier.fillMaxWidth().padding(top = 18.dp).clip(RoundedCornerShape(24.dp))
                    .border(1.dp, scheme.outline, RoundedCornerShape(24.dp))
                    .clickable { viewModel.toggleConnection() }.padding(vertical = 13.dp),
            )
        }
        Spacer(Modifier.height(14.dp))
    }

    if (guardianOpen) GuardianSheet(viewModel, stats, onClose = { guardianOpen = false })
}

/**
 * The dial, as locked in the fork (iOS ConnectView.swift @43f3415 195-245,
 * Android ConnectTab.kt). Outer ring: a hairline in `border` when off, a
 * spinning 30% teal arc while connecting, the full teal ring with its glow
 * when connected. Centre: a 132dp disc, `surface` with a 1dp `border` stroke
 * (accent radial fill and a 40% accent stroke when connected), carrying the
 * power glyph in `dim` off, in `accent` connecting, and a check in `accent`
 * connected, with the glow behind it. The one motion on the screen.
 */
@Composable
private fun RingDial(state: ConnectionState, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val dark = scheme.background.luminance() < 0.5f
    val border = if (dark) TabsDark.border else TabsLight.border
    val dim = if (dark) TabsDark.dim else TabsLight.dim
    val accent = if (dark) TabsDark.accent else TabsLight.accent
    val surface = scheme.surface
    val connecting = state is ConnectionState.Connecting || state is ConnectionState.Verifying
    val connected = state is ConnectionState.Connected
    // rememberInfiniteTransition ignores Animator duration scale; at scale 0
    // the arc rests at 0 deg, which still reads as busy.
    val rotation = if (connecting) {
        val t = rememberInfiniteTransition(label = "spin")
        t.animateFloat(0f, 360f, infiniteRepeatable(tween(1600, easing = LinearEasing)), label = "a").value
    } else 0f
    val cd = stringResource(
        when {
            connected -> R.string.tabs_ring_disconnect
            connecting -> R.string.tabs_ring_cancel
            else -> R.string.tabs_ring_connect
        }
    )
    Box(
        Modifier.size(232.dp).clip(CircleShape).clickable(onClick = onClick)
            .semantics { contentDescription = cd; role = Role.Button },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(232.dp)) {
            val stroke = 10.dp.toPx()
            val r = 93.dp.toPx() - stroke / 2f            // 186dp ring
            val tl = Offset(center.x - r, center.y - r)
            val sz = Size(r * 2, r * 2)
            if (connected) {
                // shadow(radius 22) behind the ring, approximated by a soft
                // radial wash that peaks at the ring and fades outward.
                val glowR = r + 22.dp.toPx()
                drawCircle(
                    Brush.radialGradient(
                        0f to Color.Transparent, ((r - stroke) / glowR) to Color.Transparent,
                        (r / glowR) to accent.copy(alpha = 0.32f), 1f to Color.Transparent,
                        center = center, radius = glowR,
                    ),
                    glowR, center,
                )
            }
            drawCircle(border, r, center, style = Stroke(stroke))
            when {
                connected -> drawCircle(accent, r, center, style = Stroke(stroke, cap = StrokeCap.Round))
                connecting -> drawArc(accent, rotation - 90f, 108f, false, tl, sz, style = Stroke(stroke, cap = StrokeCap.Round))
            }
        }
        // The 132dp core, with the glow behind it when connected (radius 24).
        Box(
            Modifier.size(132.dp)
                .then(
                    if (connected) Modifier.drawBehind {
                        val gr = size.minDimension / 2f + 24.dp.toPx()
                        drawCircle(
                            Brush.radialGradient(
                                0f to accent.copy(alpha = 0.22f), (size.minDimension / 2f / gr) to accent.copy(alpha = 0.18f),
                                1f to Color.Transparent, center = center, radius = gr,
                            ),
                            gr, center,
                        )
                    } else Modifier
                )
                .clip(CircleShape)
                .background(
                    if (connected) Brush.radialGradient(listOf(accent.copy(alpha = if (dark) 0.26f else 0.20f).compositeOver(surface), surface))
                    else Brush.radialGradient(listOf(surface, surface))
                )
                .border(1.dp, if (connected) accent.copy(alpha = 0.40f) else border.compositeOver(surface), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (connected) Icon(Icons.Filled.Check, null, tint = accent, modifier = Modifier.size(52.dp))
            else Icon(
                painterResource(R.drawable.ic_power), null,
                tint = if (connecting) accent else dim,
                modifier = Modifier.size(48.dp),
            )
        }
    }
}

@Composable
private fun TabsStat(label: String, value: String, modifier: Modifier = Modifier, valueColor: Color = MaterialTheme.colorScheme.onSurface) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier.clip(RoundedCornerShape(14.dp)).background(scheme.surface)
            .border(1.dp, scheme.outlineVariant, RoundedCornerShape(14.dp)).padding(vertical = 11.dp, horizontal = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(label, fontSize = 11.sp, color = scheme.onSurfaceVariant)
        Spacer(Modifier.height(3.dp))
        Text(value, fontSize = 14.sp, fontWeight = FontWeight.ExtraBold, color = valueColor, maxLines = 1)
    }
}

/** A full-screen sheet in the treatment's own tokens, with Back closing it. */
internal val LocalSheetsUp = androidx.compose.runtime.staticCompositionLocalOf { androidx.compose.runtime.mutableIntStateOf(0) }

@Composable
internal fun TabsSheet(title: String, onClose: () -> Unit, content: @Composable () -> Unit) {
    BackHandler(onBack = onClose)
    val up = LocalSheetsUp.current
    androidx.compose.runtime.DisposableEffect(Unit) { up.intValue++; onDispose { up.intValue-- } }
    val scheme = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize().background(scheme.background).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_back),
                tint = scheme.onBackground,
                modifier = Modifier.clip(CircleShape).clickable(onClick = onClose).padding(12.dp),
            )
            Text(title, fontSize = 19.sp, fontWeight = FontWeight.ExtraBold, color = scheme.onBackground)
        }
        Box(Modifier.fillMaxSize().padding(horizontal = 20.dp)) { content() }
    }
}

@Composable
internal fun PremiumCelebrationHost(onDone: () -> Unit) {
    // The shared celebration is private to MainScreen; a short toast-free
    // dismissal keeps the flow state consistent here.
    LaunchedEffect(Unit) { delay(10); onDone() }
}
