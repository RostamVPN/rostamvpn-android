package com.rostamvpn.android.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rostamvpn.android.R
import com.rostamvpn.android.VpnViewModel
import com.rostamvpn.android.VpnViewModel.ConnectionState
import com.rostamvpn.android.ui.components.CoinDial
import com.rostamvpn.android.ui.components.RegionSelectionCard
import com.rostamvpn.android.ui.theme.BrandPalette
import com.rostamvpn.android.ui.theme.DarkBase
import com.rostamvpn.android.ui.theme.liftOnto
import com.rostamvpn.android.ui.theme.mostLegibleOn

/**
 * THE `value` HOME — DollarVPN.
 *
 * NO DESIGN PACKAGE EXISTS for this brand. Unlike RedVPN there is nothing in
 * ~/code/appdev to reconcile against, so nothing here is claimed to be the
 * designer's intent; it is derived from three things that are true about the
 * product, and each is named where it decides something:
 *
 *   the MARK is a gold coin        -> the milled tick dial (see CoinDial)
 *   the PALETTE is forest green,
 *     bronze and cream             -> green carries state, gold carries the
 *                                     offer, and the two never trade places
 *   the POSITION is the value tier -> the offer is the second-largest object
 *     at $0.99 in 47 markets          on the screen, and it is a CARD rather
 *     (CLAUDE.md §22)                 than a row
 *
 * ── THE ONE STRUCTURAL IDEA ─────────────────────────────────────────────
 *
 * The classic layout deliberately separates three things of three kinds — a
 * promotion, a status readout and a control — into three free-floating objects,
 * on the argument that a rule drawn inside a container is a container doing two
 * jobs. That argument is right and is NOT reversed here.
 *
 * What changes is which things are one kind. On a value brand "how much have I
 * got left" and "what does more cost" are not two subjects, they are one: the
 * free/paid proposition. So the meter and the offer share a single bordered
 * card and are separated by a gold hairline, while the REGION — a genuinely
 * different kind of thing, and the only operable control down here — stays its
 * own separate card exactly as it is everywhere else.
 *
 * ── WHAT THIS DELIBERATELY DOES NOT DO ──────────────────────────────────
 *
 * It does not print a price, and the reason is not that there is none.
 *
 * I first wrote here that Play returns HTTP 204 for vpn.dollarvpn.app so no
 * price exists. That is what CLAUDE.md §6b records and it is now STALE: the
 * products were created on 2026-09-08, and the paywall on this device renders
 * $0.99 and $9.99 from Play, live, on a sideloaded build. Checking beat
 * trusting the file, again.
 *
 * So the price is available — to the PAYWALL, which reads it from Play's own
 * `ProductDetails` and formats it with Play's currency. This row still must not
 * repeat it, because the only way to get it here is a Kotlin constant, and a
 * constant would be an invented claim in shipped UI: wrong in every one of the
 * 47 markets the moment local pricing moves, and a brand literal in shared code
 * besides. The honest options are to plumb `subProducts` into the home screen
 * or to say the value plainly and let the paywall quote the number. This says
 * the value. The slot is the value line, so plumbing it later moves nothing
 * around it.
 */
@Composable
fun ValueHome(
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
    val isConnecting = connectionState is ConnectionState.Connecting ||
        connectionState is ConnectionState.Verifying
    val scheme = MaterialTheme.colorScheme
    val onPage = mostLegibleOn(scheme.background, Color.White, DarkBase)
    // The brand's gold, held to 4.5:1 on whatever page it lands on. #AD8510 on
    // the cream #F8F4EC measures 3.4:1 — fine for the dial's ticks, which are a
    // non-text graphic at 3:1, and NOT fine for the text this uses it for.
    val gold = liftOnto(
        scheme.background, colorResource(R.color.brand_ring), min = 4.5f, toward = onPage,
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxWidth().weight(1f),
        ) {
            CoinDial(state = connectionState, onClick = onConnectTap)

            Spacer(modifier = Modifier.height(18.dp))

            val cs = connectionState
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                // See the note on the same line in MainScreen.kt: min = 104dp
                // was measured in English and Persian overflows it, so the
                // block has to be able to report its real height.
                modifier = Modifier.fillMaxWidth().heightIn(min = 104.dp)
                    .wrapContentHeight(align = Alignment.Top, unbounded = true),
            ) {
                Text(
                    text = when (cs) {
                        is ConnectionState.Connected -> stringResource(R.string.vpn_on)
                        is ConnectionState.Connecting -> stringResource(R.string.connecting)
                        // The tunnel is up and being checked. Reuses an existing,
                        // fully translated string rather than inventing a 39th
                        // locale set for a status line that shows for ~1 s.
                        is ConnectionState.Verifying -> stringResource(R.string.status_finding_path)
                        is ConnectionState.Failed -> stringResource(R.string.connection_failed)
                        is ConnectionState.Disconnected -> stringResource(R.string.vpn_off)
                    },
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.Bold,
                    // GREEN carries state, GOLD carries the offer, and they do
                    // not trade places anywhere on this screen. That split is
                    // the whole reason the dial can be gold without the page
                    // turning into one undifferentiated metallic plane: the eye
                    // has exactly one place to look for "am I protected" and
                    // one for "what does more cost".
                    //
                    // liftOnto because `primary` is the raw palette green
                    // (#294C1D) — 9.9:1 on the cream page and 1.6:1 on a dark
                    // one, so the dark theme needs it lifted or the status
                    // disappears at the moment it matters most.
                    color = when (cs) {
                        is ConnectionState.Connected ->
                            liftOnto(scheme.background, scheme.primary, min = 4.5f, toward = onPage)
                        is ConnectionState.Failed -> scheme.error
                        // The RESTING state is dimmed, and only the resting one.
                        // At full onSurface "VPN Off" was pure white on #121212
                        // (18.7:1) -- the loudest thing on the screen, shouting
                        // the one state the user is not meant to sit in.
                        // DARKER IN BOTH THEMES, which alpha cannot do. An
                        // alpha composites toward the GROUND, so 0.75 dimmed
                        // white on a dark page (darker, right) and dimmed dark
                        // ink on a cream page (LIGHTER, wrong). Blending 25%
                        // toward black always moves the same direction, which
                        // is what was actually asked for.
                        // CONNECTING stays full strength: it is an active
                        // state and the user is waiting on it.
                        is ConnectionState.Disconnected ->
                            androidx.compose.ui.graphics.lerp(scheme.onSurface, Color.Black, 0.25f)
                        else -> scheme.onSurface
                    },
                    textAlign = TextAlign.Center,
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
                        // The toggle CANCELS during the check, so the helper must not
                        // invite a tap that would do the opposite of what it says.
                        is ConnectionState.Verifying -> stringResource(R.string.tap_to_cancel)
                        else -> stringResource(R.string.tap_to_connect)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )

                // Transport FAMILY only — never the carrier or front (§0).
                // The transport name is NOT rendered. §0 forbids a protocol
                // name on any surface a censor can read, and "NetCore" is one.
                // The old comment here said "family only, never the carrier",
                // which missed that the FAMILY name is the protocol. It means
                // nothing to a user either — the dial already says VPN On.

                if (isConnecting && autoConnectStatus != null) {
                    Spacer(modifier = Modifier.height(3.dp))
                    Text(
                        text = autoConnectStatus!!,
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant.copy(alpha = 0.75f),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 32.dp),
                    )
                }

                if (isConnected && trafficInfo.rxRate.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(5.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(20.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = isolate("↓ ${trafficInfo.rxRate}"),
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                            color = liftOnto(scheme.background, scheme.primary, min = 4.5f, toward = onPage),
                        )
                        Text(
                            text = isolate("↑ ${trafficInfo.txRate}"),
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                            color = liftOnto(scheme.background, scheme.primary, min = 4.5f, toward = onPage),
                        )
                    }
                }

                if (cs is ConnectionState.Failed) {
                    Spacer(modifier = Modifier.height(5.dp))
                    Text(
                        text = cs.error,
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.error.copy(alpha = 0.8f),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 32.dp),
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(scheme.errorContainer.copy(alpha = 0.3f))
                            .clickable { viewModel.shareDebugLog() }
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(text = "📩", fontSize = 14.sp)
                        Text(
                            text = stringResource(R.string.share_debug_log),
                            style = MaterialTheme.typography.labelSmall,
                            color = scheme.onErrorContainer,
                        )
                    }
                }
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(bottom = 12.dp),
        ) {
            // THE LEDGER. Drawn only when there is something in it: in a
            // FREEDOM REGION both gates are false and this vanishes entirely,
            // leaving connect + region, exactly as the other two layouts do.
            // The gates arrive resolved so the three cannot disagree.
            if (gates.sellingPremium || gates.showMeter) {
                Spacer(modifier = Modifier.height(20.dp))
                OfferLedger(
                    gates = gates,
                    gold = gold,
                    onUpgrade = { viewModel.openPaywallAndLoad() },
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
                color = scheme.onSurfaceVariant.copy(alpha = 0.8f),
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                text = stringResource(R.string.made_in_usa),
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onSurfaceVariant.copy(alpha = 0.6f),
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                text = "v${com.rostamvpn.android.BuildConfig.VERSION_NAME}$configSuffix",
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onSurfaceVariant.copy(alpha = 0.4f),
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * ONE CARD: what is left, and what more costs.
 *
 * The gold hairline between the two halves is the only rule on the screen, and
 * it is load-bearing rather than decorative — it says these are two readings of
 * one subject. The card carries a gold border for the same reason the region
 * card carries none: this one is an OFFER and is allowed to advertise, while
 * that one is a control and should look like a control.
 *
 * Every colour here is measured against the page it lands on rather than
 * assumed. `gold` arrives already lifted to 4.5:1 by the caller; the meter's
 * escalation colours keep the classic layout's exact semantics (neutral, then
 * amber at 75%, then `error` when the allowance is spent) because that copy and
 * its thresholds are a product promise, not a look — the free tier is never cut
 * off, only rate-limited, and every string here says so.
 */
@Composable
private fun OfferLedger(
    gates: HomeGates,
    gold: Color,
    onUpgrade: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(16.dp)
    val onPage = mostLegibleOn(scheme.background, Color.White, DarkBase)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(scheme.surface)
            .border(1.dp, gold.copy(alpha = 0.35f), shape)
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        if (gates.showMeter) {
            val low = gates.fraction >= 0.75f || gates.overQuota
            val target = when {
                gates.overQuota -> scheme.error
                low -> liftOnto(scheme.surface, BrandPalette.warning, min = 4.5f, toward = onPage)
                else -> gold
            }
            val accent by animateColorAsState(
                targetValue = target,
                animationSpec = tween(600, easing = FastOutSlowInEasing),
                label = "ledgerAccent",
            )
            val shown by animateFloatAsState(
                targetValue = gates.fraction.coerceIn(0f, 1f),
                animationSpec = tween(900, easing = FastOutSlowInEasing),
                label = "ledgerFraction",
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = when {
                        gates.reducedSpeed && gates.freeSpeed != null ->
                            stringResource(R.string.speed_state_limited, gates.freeSpeed)
                        gates.reducedSpeed -> stringResource(R.string.speed_state_reduced)
                        else -> stringResource(R.string.speed_state_full)
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (gates.reducedSpeed) accent
                            else scheme.onSurfaceVariant.copy(alpha = 0.8f),
                )
                Text(
                    // isolate(): Latin digits and a Latin unit inside a
                    // translated sentence that is RTL in a third of the locales.
                    text = if (gates.overQuota) stringResource(R.string.data_free_out)
                           else stringResource(
                               R.string.data_free_remaining, isolate(formatMbShort(gates.remainingMb))
                           ),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = accent,
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            LinearProgressIndicator(
                progress = { shown },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp)),
                color = accent,
                trackColor = scheme.onSurfaceVariant.copy(alpha = 0.15f),
            )
            Spacer(modifier = Modifier.height(6.dp))
            // EXACTLY ONE caption line in every state, so the block never
            // changes height as the tier changes. None of these threatens a
            // cutoff, because there is no cutoff.
            Text(
                text = when {
                    gates.overQuota && gates.freeSpeed != null ->
                        stringResource(R.string.data_free_out_note, gates.freeSpeed)
                    // A hard-stop brand has no slow lane to get "back" from, so
                    // data_free_out_cta ("Want full speed back?") is the wrong
                    // sentence — it describes a tunnel that is still running.
                    // Reuses a paywall perk body rather than adding a key: §17,
                    // a brand res/ overlay CANNOT fix a string (a locale
                    // qualifier outranks a source-set override), so a new one
                    // costs 38 translations. This one is already in all 39 and
                    // says exactly the true thing.
                    gates.overQuota && gates.quotaStops ->
                        stringResource(R.string.paywall_perk_data_body_once)
                    gates.overQuota -> stringResource(R.string.data_free_out_cta)
                    low && gates.freeSpeed != null ->
                        stringResource(R.string.data_free_low_note, gates.freeSpeed)
                    low -> stringResource(R.string.data_free_low_cta)
                    gates.quotaRenews -> stringResource(R.string.data_free_one_time)
                    else -> stringResource(R.string.usage_free_allowance)
                },
                style = MaterialTheme.typography.labelSmall,
                color = if (low) accent else scheme.onSurfaceVariant.copy(alpha = 0.7f),
            )
        }

        if (gates.sellingPremium && gates.showMeter) {
            Spacer(modifier = Modifier.height(13.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(gold.copy(alpha = 0.28f)),
            )
            Spacer(modifier = Modifier.height(13.dp))
        }

        if (gates.sellingPremium) {
            val label = stringResource(R.string.upgrade)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .clickable(onClickLabel = label, role = Role.Button) { onUpgrade() },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(gold.copy(alpha = 0.16f).compositeOver(scheme.surface)),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Star,
                        contentDescription = null,
                        tint = gold,
                        modifier = Modifier.size(17.dp),
                    )
                }
                Spacer(modifier = Modifier.width(11.dp))
                Text(
                    // THE PRICE SLOT. `premium_tagline` today; when
                    // vpn.dollarvpn.app has real Play products this is where the
                    // formatted local price goes, and nothing around it moves.
                    text = stringResource(
                        if (gates.reducedSpeed) R.string.premium_title_throttled
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
                    fontWeight = FontWeight.Bold,
                    // Measured, not assumed: whichever of white/ink actually
                    // reads on this brand's own primary.
                    color = mostLegibleOn(scheme.primary, Color.White, DarkBase),
                    modifier = Modifier
                        .clearAndSetSemantics { }
                        .clip(RoundedCornerShape(50))
                        .background(scheme.primary)
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }
    }
}

/** MB as a short human string. Same decimal units and same shape as the
 *  classic layout's `formatMb`, so the home meter and the Usage stats screen
 *  can never render one server number two different ways. */
private fun formatMbShort(mb: Long): String {
    val gb = mb / 1000.0
    return when {
        gb >= 1.0 -> "%.2f GB".format(gb)
        mb >= 1L -> "%.1f MB".format(mb.toDouble())
        else -> "0 MB"
    }
}
