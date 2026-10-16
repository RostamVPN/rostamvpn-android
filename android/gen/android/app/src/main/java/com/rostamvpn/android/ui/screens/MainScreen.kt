// Copyright 2026 Internet Mastering & Company, Inc.
// SPDX-License-Identifier: Apache-2.0

package com.rostamvpn.android.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.material.icons.filled.Check
import androidx.compose.animation.animateColorAsState
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
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.collectAsState
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
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

import kotlinx.coroutines.launch

/**
 * Main screen with premium on-connect animations: - Animated background
 * gradient shift (neutral -> teal-green on connect) - Ambient particle system
 * (connecting: energetic, connected.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(viewModel: VpnViewModel) {
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
    val activeTransport by viewModel.activeTransport.collectAsState()
    val themeSetting by viewModel.themeSetting.collectAsState()
    val autoConnectStatus by viewModel.autoConnectStatus.collectAsState()
    val configVersion by viewModel.configVersion.collectAsState()
    val showPaywall by viewModel.showPaywall.collectAsState()
    val webPayUrl by viewModel.webPayUrl.collectAsState()
    val usage by viewModel.usage.collectAsState()
    val showDataUsage by viewModel.showDataUsage.collectAsState()
    val premiumAvailable by viewModel.premiumAvailable.collectAsState()
    val reducedSpeed by viewModel.reducedSpeed.collectAsState()
    val isPaid by viewModel.isPaid.collectAsState()
    val premiumCelebration by viewModel.premiumCelebration.collectAsState()
    val dataTotal by viewModel.dataTotal.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.syncEntitlement()
        viewModel.restoreEntitlement()
    }

    val activity = LocalContext.current as? android.app.Activity
    val consentBusy = remember { mutableStateOf(false) }

    val onConnectTap: () -> Unit = tap@{
        val cs = connectionState

        if (cs is ConnectionState.Connected || cs is ConnectionState.Connecting) {
            viewModel.toggleConnection()
            return@tap
        }
        if (consentBusy.value) return@tap

        if (activity != null && android.net.VpnService.prepare(activity) != null) {
            consentBusy.value = true
            (activity as? com.rostamvpn.android.MainActivity)?.requestVpnConsent { granted ->
                consentBusy.value = false
                if (granted) {
                    viewModel.toggleConnection()
                } else {
                    viewModel.onVpnPermissionDenied()
                }
            } ?: run {
                consentBusy.value = false
                viewModel.toggleConnection()
            }
            return@tap
        }
        viewModel.toggleConnection()
    }
    val context = LocalContext.current

    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    val isConnected = connectionState is ConnectionState.Connected
    val isConnecting = connectionState is ConnectionState.Connecting

    val baseBg = MaterialTheme.colorScheme.background
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val connectedBgTop = classicTint(isDark, 0.055f)
    val connectedBgBottom = classicTint(isDark, 0.105f)

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

    val particleAlpha by animateFloatAsState(
        targetValue = if (isConnected || isConnecting) 1f else 0f,
        animationSpec = tween(600),
        label = "particleAlpha"
    )

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

                showUsageStats = !viewModel.paidOnly,
                onRate = {
                    val a = context as? android.app.Activity
                    if (a != null) ReviewManager.askNow(a)
                    else android.util.Log.w("MainScreen", "Rate tapped but LocalContext is not an Activity")
                },
            )
        }
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(bgTop, bgBottom)
                        )
                    )
            )

            if (particleAlpha > 0.01f) {
                ParticleAnimation(
                    connectionState = connectionState,
                    modifier = Modifier
                        .fillMaxSize()
                        .alpha(particleAlpha)
                )
            }

            ConnectShockwave(
                connectionState = connectionState,
                modifier = Modifier.fillMaxSize()
            )

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
            ) {
                val onBar = mostLegibleOn(bgTop, Color.White, DarkBase)
                val brandOnBar = liftOnto(bgTop, Brand1, min = 3f, toward = onBar)

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
                ClassicHome(viewModel, gates, onConnectTap)
            }

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

            if (premiumCelebration) {
                PremiumCelebration(onDone = { viewModel.dismissPremiumCelebration() })
            }

            if (showAbout) {
                AboutScreen(onBack = { viewModel.dismissAbout() })
            }

            if (showPrivacyPolicy) {
                PrivacyPolicyScreen(onBack = { viewModel.dismissPrivacyPolicy() })
            }

            if (showOpenSource) {
                OpenSourceScreen(onBack = { viewModel.dismissOpenSource() })
            }

            if (showDataUsage) {
                DataUsageScreen(viewModel = viewModel, onBack = { viewModel.dismissDataUsage() })
            }

            if (showPaywall) {
                PaywallScreen(viewModel = viewModel, onBack = { viewModel.dismissPaywall() })
            }

            webPayUrl?.let { url ->
                WebPayScreen(url = url, onClose = { viewModel.dismissWebPay() })
            }
        }
    }
}

/** THE HOME. */
@Composable
private fun ClassicHome(
    viewModel: VpnViewModel,
    gates: HomeGates,
    onConnectTap: () -> Unit,
) {
    val connectionState by viewModel.connectionState.collectAsState()
    val autoConnectStatus by viewModel.autoConnectStatus.collectAsState()
    val trafficInfo by viewModel.trafficInfo.collectAsState()
    val configVersion by viewModel.configVersion.collectAsState()
    val selectedRegion by viewModel.selectedRegion.collectAsState()
    val isConnected = connectionState is ConnectionState.Connected
    val isConnecting = connectionState is ConnectionState.Connecting

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxWidth().weight(1f)
        ) {
            PowerButton(
                state = connectionState,
                onClick = onConnectTap
            )

            Spacer(modifier = Modifier.height(18.dp))

            val cs = connectionState
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .fillMaxWidth()

                    .heightIn(min = 104.dp)
                    .wrapContentHeight(align = Alignment.Top, unbounded = true),
            ) {
                Text(
                    text = when (cs) {
                        is ConnectionState.Connected -> stringResource(R.string.vpn_on)
                        is ConnectionState.Connecting -> stringResource(R.string.connecting)

                        is ConnectionState.Verifying ->
                            stringResource(R.string.status_finding_path)
                        is ConnectionState.Failed -> stringResource(R.string.connection_failed)
                        is ConnectionState.Disconnected -> stringResource(R.string.vpn_off)
                    },
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.Bold,

                    color = when (cs) {
                        is ConnectionState.Connected -> MaterialTheme.colorScheme.primary
                        is ConnectionState.Connecting -> MaterialTheme.colorScheme.onSurface

                        is ConnectionState.Verifying -> MaterialTheme.colorScheme.onSurface
                        is ConnectionState.Failed -> MaterialTheme.colorScheme.error
                        is ConnectionState.Disconnected -> MaterialTheme.colorScheme.onSurface
                    },
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = when (cs) {
                        is ConnectionState.Connected -> stringResource(R.string.tap_to_disconnect)
                        is ConnectionState.Connecting -> {
                            if (autoConnectStatus != null) stringResource(R.string.tap_to_cancel)
                            else if (cs.transport != "auto") stringResource(R.string.trying_server)
                            else stringResource(R.string.connecting_to_server)
                        }

                        is ConnectionState.Verifying -> stringResource(R.string.tap_to_cancel)
                        else -> stringResource(R.string.tap_to_connect)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )

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

                if (isConnected && trafficInfo.rxRate.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(5.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(20.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(

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

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(bottom = 12.dp)
        ) {
            if (gates.sellingPremium) {
                Spacer(modifier = Modifier.height(20.dp))
                UpgradeCta(
                    reducedSpeed = gates.reducedSpeed,
                    onClick = { viewModel.openPaywallAndLoad() },
                )
            }

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

/** The entitlement and metering gates, RESOLVED ONCE per frame and handed to the home. */
@Immutable
private data class HomeGates(
    val regionLocked: Boolean,
    /**
     * This user is being SOLD to: premium exists for them and they have not bought
     * it.
     */
    val sellingPremium: Boolean,
    /** Does the free allowance come back? Server-owned (`period_days`), per brand. */
    val quotaRenews: Boolean,
    val showMeter: Boolean,
    val fraction: Float,
    val remainingMb: Long,
    val overQuota: Boolean,
    val reducedSpeed: Boolean,
    val freeSpeed: String?,
)

@Composable
private fun rememberHomeGates(
    usage: com.rostamvpn.android.billing.CashierApi.UsageStatus?,
    dataTotal: Long,
    premiumAvailable: Boolean,
    isPaid: Boolean,
    reducedSpeed: Boolean,
    freeTierKbps: Int,
    /**
     * `throttle.hard_stop` — the brand has no free tier past the allowance at all.
     * Anything that names the free speed must read this FIRST.
     */
    quotaStops: Boolean,
    /** `paid_only`: no free allowance exists, so no meter and no free speed. */
    paidOnly: Boolean = false,
): HomeGates {
    val meterUsage = usage?.takeIf { !paidOnly && !it.unlimited && !it.paid && !isPaid }
    val usedMb = meterUsage?.let { maxOf(it.usedMb, dataTotal / 1_000_000L) } ?: 0L
    val quotaMb = meterUsage?.freeQuotaMb ?: 0L
    val fraction =
        if (quotaMb > 0) (usedMb.toDouble() / quotaMb).coerceIn(0.0, 1.0).toFloat() else 0f
    return HomeGates(

        regionLocked = premiumAvailable && !isPaid &&
            !(usage?.canSelectRegion == true && usage.overQuota != true),
        sellingPremium = premiumAvailable && !isPaid,
        quotaRenews = usage?.renews ?: true,
        showMeter = meterUsage != null,
        fraction = fraction,
        remainingMb = (quotaMb - usedMb).coerceAtLeast(0L),
        overQuota = meterUsage?.overQuota == true,
        reducedSpeed = reducedSpeed,

        freeSpeed = if (quotaStops || paidOnly) null else formatSpeed(freeTierKbps),
    )
}

/** MB as a short human string. */
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
 */
@Composable
private fun UpgradeCta(
    reducedSpeed: Boolean,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme

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

            text = stringResource(
                if (reducedSpeed) R.string.premium_title_throttled
                else R.string.premium_tagline
            ),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = scheme.onSurface,

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
 */
/** The CLASSIC connected wash, at [amount] strength. */
@Composable
private fun classicTint(isDark: Boolean, amount: Float): Color {
    val scheme = MaterialTheme.colorScheme
    val base = if (isDark) tone(BrandPalette.primary, 0.34f, 0.055f) else scheme.background
    return scheme.primary.copy(alpha = if (isDark) amount * 2.2f else amount)
        .compositeOver(base)
}

/** The three seconds after someone pays. */
@Composable
private fun PremiumCelebration(onDone: () -> Unit) {
    val scheme = MaterialTheme.colorScheme

    val ground = scheme.background
    val mark = liftOnto(ground, Brand1, min = 4.5f)

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

            listOf(
                R.string.paywall_perk_data_title,
                R.string.paywall_perk_speed_title,
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
    /**
     * Does the allowance come back? Default true keeps every refilling brand on
     * the wording it has always had.
     */
    quotaRenews: Boolean = true,
) {
    val low = fraction >= 0.75f || overQuota

    val scheme = MaterialTheme.colorScheme
    val onPage = mostLegibleOn(scheme.background, Color.White, DarkBase)
    val target = when {
        overQuota -> scheme.error

        low -> liftOnto(scheme.background, BrandPalette.warning, min = 4.5f, toward = onPage)
        else -> Brand1
    }

    val accent by animateColorAsState(
        targetValue = target,
        animationSpec = tween(600, easing = FastOutSlowInEasing),
        label = "meterAccent",
    )

    val shownFraction by animateFloatAsState(
        targetValue = fraction.coerceIn(0f, 1f),
        animationSpec = tween(900, easing = FastOutSlowInEasing),
        label = "meterFraction",
    )

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
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

                text = if (overQuota) stringResource(R.string.data_free_out)
                       else stringResource(
                           R.string.data_free_remaining, isolate(formatMb(remainingMb))
                       ),
                style = MaterialTheme.typography.labelSmall,
                color = accent,
            )
        }
        Spacer(modifier = Modifier.height(5.dp))

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

        Text(
            text = when {
                overQuota && freeSpeed != null ->
                    stringResource(R.string.data_free_out_note, freeSpeed)
                overQuota -> stringResource(R.string.data_free_out_cta)
                low && freeSpeed != null ->
                    stringResource(R.string.data_free_low_note, freeSpeed)
                low -> stringResource(R.string.data_free_low_cta)

                quotaRenews -> stringResource(R.string.data_free_one_time)
                else -> stringResource(R.string.usage_free_allowance)
            },
            style = MaterialTheme.typography.labelSmall,
            color = if (low) accent
                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
        )
    }
}
