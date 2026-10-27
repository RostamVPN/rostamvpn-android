package com.rostamvpn.android.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.rostamvpn.android.BuildConfig
import com.rostamvpn.android.ProtectConsent
import com.rostamvpn.android.R
import com.rostamvpn.android.RedShield
import com.rostamvpn.android.VpnDisclosure
import com.rostamvpn.android.VpnViewModel
import com.rostamvpn.android.ui.theme.Brand1

/*
 * The two prominent disclosures Play's VpnService and User Data policies ask
 * for (2026-10-26). They are SEPARATE on purpose: the VPN one explains the
 * VpnService and what our servers keep; the Protect Mode one explains what
 * Protect Mode sends. Both take an affirmative tap; backing out is never consent.
 * Brand and feature names arrive as placeholders (app_name, PROTECTION_NAME).
 */

/** Full screen, over everything: drawn last in MainActivity.EntryGate. */
@Composable
internal fun VpnDisclosureHost() {
    val pending by VpnDisclosure.pending.collectAsState()
    if (pending != null) VpnDisclosureScreen(
        onContinue = { VpnDisclosure.answer(true) },
        onNotNow = { VpnDisclosure.answer(false) },
    )
}

@Composable
internal fun VpnDisclosureScreen(onContinue: () -> Unit, onNotNow: () -> Unit) {
    val ctx = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    val app = stringResource(R.string.app_name)
    BackHandler { onNotNow() }
    Column(
        Modifier.fillMaxSize().background(scheme.background)
            // Swallow taps so nothing underneath can be reached.
            .clickable(remember { MutableInteractionSource() }, indication = null) {}
            .statusBarsPadding().navigationBarsPadding().padding(horizontal = 24.dp),
    ) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Spacer(Modifier.height(40.dp))
            Image(painterResource(R.drawable.brand_mark), contentDescription = null, modifier = Modifier.size(56.dp))
            Spacer(Modifier.height(20.dp))
            Text(
                stringResource(R.string.vpn_disclosure_title),
                style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold,
                color = scheme.onBackground, modifier = Modifier.semantics { heading() },
            )
            Spacer(Modifier.height(16.dp))
            DisclosureParagraph(stringResource(R.string.vpn_disclosure_what, app))
            DisclosureParagraph(stringResource(R.string.vpn_disclosure_data, app))
            DisclosureParagraph(stringResource(R.string.vpn_disclosure_next))
            PrivacyLink(ctx)
            Spacer(Modifier.height(16.dp))
        }
        Button(
            onClick = onContinue,
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
            shape = RoundedCornerShape(26.dp),
            colors = ButtonDefaults.buttonColors(containerColor = liftText(Color.White, Brand1, 4.5f), contentColor = Color.White),
        ) { Text(stringResource(R.string.continue_label), fontWeight = FontWeight.SemiBold) }
        TextButton(onClick = onNotNow, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Text(stringResource(R.string.disclosure_not_now), color = scheme.onSurfaceVariant, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(8.dp))
    }
}

/**
 * The Protect Mode sheet. Shown (a) on launch while the feature is on by
 * default and nobody has answered, and (b) when the switch is turned on
 * without consent. Drawn in EntryGate above home and the Protect Mode screen,
 * under the launch animation, the first-run offer and the VPN disclosure, so
 * it surfaces once those are gone.
 */
@Composable
internal fun ProtectModeDisclosureHost(viewModel: VpnViewModel) {
    if (!RedShield.available) return
    val ctx = LocalContext.current
    val asked by ProtectConsent.asked.collectAsState()
    val rev by ProtectConsent.rev.collectAsState()
    val vpnPending by VpnDisclosure.pending.collectAsState()
    // Backed out this session: not consent, and not asked again until the
    // next launch or the next time the switch is turned on.
    var dismissed by rememberSaveable { mutableStateOf(false) }
    val firstRun = remember(rev) { ProtectConsent.needsAnswer(ctx) }
    val show = asked || (firstRun && !dismissed)
    if (!show || vpnPending != null) return
    ProtectModeSheet(
        onContinue = { viewModel.answerProtectConsent(true) },
        onTurnOff = { viewModel.answerProtectConsent(false) },
        onDismiss = { dismissed = true; ProtectConsent.dismiss() },
    )
}

@Composable
internal fun ProtectModeSheet(onContinue: () -> Unit, onTurnOff: () -> Unit, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    val feature = RedShield.name
    val app = stringResource(R.string.app_name)
    val maxH = (LocalConfiguration.current.screenHeightDp * 0.88f).dp
    BackHandler { onDismiss() }
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f))
            // A tap on the scrim is not an answer and does not close it.
            .clickable(remember { MutableInteractionSource() }, indication = null) {},
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(
            Modifier.fillMaxWidth().heightIn(max = maxH)
                .background(scheme.surface, RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                .navigationBarsPadding().padding(horizontal = 24.dp),
        ) {
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                Spacer(Modifier.height(24.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ShieldTile(on = true, size = 40.dp)
                    Spacer(Modifier.width(12.dp))
                    Text(
                        stringResource(R.string.pm_disclosure_title, feature),
                        style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                        color = scheme.onSurface, modifier = Modifier.semantics { heading() },
                    )
                }
                Spacer(Modifier.height(16.dp))
                DisclosureParagraph(stringResource(R.string.pm_disclosure_what, feature))
                DisclosureParagraph(stringResource(R.string.pm_disclosure_data, app))
                DisclosureParagraph(stringResource(R.string.pm_disclosure_off, feature))
                PrivacyLink(ctx)
                Spacer(Modifier.height(12.dp))
            }
            Row(Modifier.fillMaxWidth().padding(bottom = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(
                    onClick = onTurnOff,
                    modifier = Modifier.weight(1f).heightIn(min = 52.dp),
                    shape = RoundedCornerShape(26.dp),
                ) { Text(stringResource(R.string.pm_turn_off), color = scheme.onSurface, fontWeight = FontWeight.SemiBold) }
                Button(
                    onClick = onContinue,
                    modifier = Modifier.weight(1f).heightIn(min = 52.dp),
                    shape = RoundedCornerShape(26.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = liftText(Color.White, Brand1, 4.5f), contentColor = Color.White),
                ) { Text(stringResource(R.string.continue_label), fontWeight = FontWeight.SemiBold) }
            }
        }
    }
}

@Composable
private fun DisclosureParagraph(text: String) {
    Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(12.dp))
}

@Composable
private fun PrivacyLink(ctx: android.content.Context) {
    val url = BuildConfig.PRIVACY_URL
    if (url.isEmpty()) return
    Text(
        stringResource(R.string.privacy_policy),
        style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold,
        color = ShopColors.accentOnCard(),
        modifier = Modifier.clickable { openWeb(ctx, url) }.padding(vertical = 8.dp),
    )
}
