package com.rostamvpn.android.ui.screens

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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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
import com.rostamvpn.android.ui.theme.Accent3
import com.rostamvpn.android.ui.theme.Brand1
import com.rostamvpn.android.ui.theme.liftOnto
import com.rostamvpn.android.ui.components.RegionSelectionCard
import com.rostamvpn.android.ui.components.RingsDial

/**
 * THE `rings` HOME — RedVPN.
 *
 * Reconciled against the brand's own design package
 * (~/code/appdev/graphics-redvpn), which is a real designer's intent and
 * therefore a decision until someone overrides it. What is followed, what is
 * deviated from and why is recorded at each site below rather than summarised
 * here, but the three deviations in one place, because they are the parts a
 * reviewer should argue with:
 *
 *   1. THE CORE DISC STAYS WHITE when disconnected, where the design file has
 *      dark charcoal. Measured contrast failure against the real mark — see
 *      RingsDial's header for the numbers. This is the only place the design
 *      is overruled on looks rather than on plumbing.
 *   2. THE STATUS KEEPS THE EXISTING TRANSLATED STRINGS. The design draws
 *      "VPN:" and "On" as separately coloured runs, and a two-line helper
 *      ("Your connection is secured! / To turn off, tap the shield."). No such
 *      keys exist, and RedVPN's 63.5K installs are largely Persian, Arabic,
 *      Russian, Turkish and Chinese (§17) — a new key ships English to 37
 *      locales, and 30 strings are already out with the translator. So
 *      `vpn_on` / `vpn_off` render WHOLE, with the gradient carrying the
 *      emphasis the colour split was there to provide. Nothing regresses in
 *      any locale and the screen still reads as the design.
 *   3. THE REGION CARD IS THE SHARED ONE. The design draws a two-row "Server
 *      Location" card with a chevron. RegionSelectionCard is the only
 *      genuinely operable control on this half of the screen and it owns a
 *      bottom sheet, the locked/paywall branch and three translated strings;
 *      forking it to change a label would be duplicating behaviour to gain a
 *      row. The identity is carried by the dial, the status and the pill.
 */
@Composable
fun RingsHome(
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // ── THE HERO ──
        // weight(1f) + Center, for the same reason the classic layout does it:
        // letting the hero own the free space centres the primary action
        // optically instead of pinning it to the top with a void beneath. It
        // matters more here, because in a FREEDOM REGION the lower stack is a
        // single card and the dial has to hold most of the page.
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxWidth().weight(1f),
        ) {
            RingsDial(state = connectionState, onClick = onConnectTap)

            Spacer(modifier = Modifier.height(18.dp))

            // A FIXED MINIMUM HEIGHT with the content top-aligned, carried over
            // from the classic layout because the bug it fixes is not
            // layout-specific: the line count changes with state (connected
            // adds a transport label and a traffic row, connecting adds a step
            // line, failed adds an error and a report affordance) and the hero
            // is vertically centred, so without a reservation the dial slides
            // up and down under the user's finger at exactly the moment they
            // are most likely to tap it again.
            val cs = connectionState
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                // See the note on the same line in MainScreen.kt: min = 104dp
                // was measured in English and Persian overflows it, so the
                // block has to be able to report its real height.
                modifier = Modifier.fillMaxWidth().heightIn(min = 104.dp)
                    .wrapContentHeight(align = Alignment.Top, unbounded = true),
            ) {
                // ── THE STATUS, and the one piece of real ornament on this
                // screen ──
                //
                // The design fills the state word with the purple -> red
                // gradient it also uses on the upgrade pill; sampling the file
                // shows the word is itself a VERTICAL gradient (#A91B62 at the
                // cap height down to #DE243F at the baseline) rather than a
                // flat colour, which is why a single-pixel sample of it reads
                // as an unexplainable magenta.
                //
                // Both stops are palette tokens — `secondaryContainer`, which
                // IS BrandPalette.accent in this theme (#69108C), and `primary`
                // (#E2232B) — so this is the brand's own two colours
                // and not an invented third, and a sixth brand on `rings` gets
                // its own gradient with nothing to author. Measured on white:
                // 9.42:1 at the purple end and 4.54:1 at the red, so the whole
                // run clears 4.5:1 for text at every point.
                //
                // ONLY the connected state gets it. Off is flat
                // `onSurfaceVariant`, which means the gradient is a second
                // state channel rather than decoration: saturated-and-shifting
                // versus flat-and-grey, on top of the dial's filled-versus-
                // outline. Colour is never the only cue.
                // ── THE STOPS ARE RAW BRAND COLOURS, NOT COLOUR ROLES ──
                //
                // `scheme.primary` FLIPS in the dark theme: Theme.kt maps it to
                // Brand2, which for RedVPN is its purple secondary. So a
                // gradient written as secondaryContainer -> primary rendered
                // purple -> purple in dark, i.e. a flat purple pill and a flat
                // purple status word. Verified on device before this fix; the
                // light build looked perfect throughout.
                //
                // Accent3 and Brand1 ARE BrandPalette.accent and .primary — the
                // brand's own two colours, identical in both themes — so the
                // gradient is the brand's rather than the scheme's.
                //
                // Then LIFTED for the page it sits on. On white both stops pass
                // untouched (9.42:1 and 4.54:1). On #121212 the purple measures
                // 2.06:1, which is not dim but illegible, so liftOnto blends it
                // toward white just far enough to clear 4.5:1 while keeping the
                // hue. Text, so 4.5 rather than 3.
                val onGradient = Brush.verticalGradient(
                    listOf(
                        liftOnto(scheme.background, Accent3, min = 4.5f),
                        liftOnto(scheme.background, Brand1, min = 4.5f),
                    ),
                )
                val statusText = when (cs) {
                    is ConnectionState.Connected -> stringResource(R.string.vpn_on)
                    is ConnectionState.Connecting -> stringResource(R.string.connecting)
                    // The tunnel is up and being checked. Reuses an existing,
                    // fully translated string rather than inventing a 39th
                    // locale set for a status line that shows for ~1 s.
                    is ConnectionState.Verifying -> stringResource(R.string.status_finding_path)
                    is ConnectionState.Failed -> stringResource(R.string.connection_failed)
                    is ConnectionState.Disconnected -> stringResource(R.string.vpn_off)
                }
                if (isConnected) {
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.headlineLarge.copy(brush = onGradient),
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                    )
                } else {
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.headlineLarge,
                        fontWeight = FontWeight.Bold,
                        color = when (cs) {
                            is ConnectionState.Failed -> scheme.error
                            // Dimmed at rest -- see the note in ValueHome. Both
                            // home screens must move together; this one was
                            // missed once already on the transport-name fix.
                            is ConnectionState.Disconnected ->
                                androidx.compose.ui.graphics.lerp(scheme.onSurface, Color.Black, 0.25f)
                            else -> scheme.onSurface
                        },
                        textAlign = TextAlign.Center,
                    )
                }

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

                // The transport name is NOT rendered. §0 forbids a protocol
                // name on any surface a censor can read, and "NetCore" is one.
                // The old comment here said "family only, never the carrier",
                // which missed that the FAMILY name is the protocol. It means
                // nothing to a user either — the dial already says VPN On.
                //
                // 🚨 ValueHome was fixed for this on 2026-09-21 and THIS COPY
                // WAS NOT, so the leak stayed live on rostam and redvpn — the
                // two brands that use RINGS — while the brand nobody had
                // shipped publicly was clean. Found by recording the shipped
                // 6.7.2 for a website GIF and LOOKING at the frame (§18c).
                // Both home screens must move together.

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
                        // isolate(): a Latin number and unit behind a neutral
                        // arrow, dropped into an RTL paragraph. Without it the
                        // bidi algorithm throws the arrow to the far side and a
                        // Persian reader gets "KB/s 9 ↓".
                        Text(
                            text = isolate("↓ ${trafficInfo.rxRate}"),
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                            color = scheme.primary,
                        )
                        Text(
                            text = isolate("↑ ${trafficInfo.txRate}"),
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                            color = scheme.primary,
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

        // ── THE LOWER STACK ──
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(bottom = 12.dp),
        ) {
            // The gates arrive RESOLVED from rememberHomeGates, so this layout
            // cannot disagree with the classic one about who is being sold to
            // or whether a meter is owed. In a FREEDOM REGION both are false
            // and this whole block collapses to the region card.
            if (gates.sellingPremium) {
                Spacer(modifier = Modifier.height(20.dp))
                UpgradePill(onClick = { viewModel.openPaywallAndLoad() })
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

            // Footer. One line per direction, never concatenated: mixing a
            // TRANSLATED phrase with a Latin version string in one paragraph
            // makes an RTL locale reorder the runs and land the version on the
            // wrong side of the provenance.
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
 * THE UPGRADE PILL — the design's own, and the loudest thing on the screen
 * after the dial.
 *
 * This is the sharpest departure from the classic layout, which deliberately
 * makes its upgrade block SUBORDINATE: a 10% wash, an ink label, one small
 * accent badge. The reasoning there is sound and is documented at UpgradeCta —
 * the offer sits directly under the connect button, which is where a
 * competitor for attention does the most damage.
 *
 * RedVPN's designer drew the opposite: a full-width saturated gradient pill.
 * It is followed because it is this brand's stated intent, and because the
 * argument that forced the classic treatment is weaker here — the rings dial
 * is 248dp of saturated red rising to full opacity at its centre, so a 56dp
 * pill is not competing with a white puck the way it would on rostam.
 *
 * WHAT IT COSTS, said plainly: the classic row carries `premium_tagline`
 * ("Full speed always · choose any region"), and this carries only the verb.
 * The value proposition moves to the paywall, one tap away. That is the
 * design's call and it is a conversion question rather than a visual one, so
 * it is flagged rather than quietly reversed.
 *
 * COLOUR. Both gradient stops are palette tokens — `secondary` and `primary` —
 * so this is derived, not authored, and any brand on `rings` gets its own. The
 * label is white, measured against BOTH ends: 9.42:1 on #69108C and 4.54:1 on
 * #E2232B, so it clears 4.5:1 across the entire sweep rather than only at the
 * ends someone happened to check.
 *
 * The WHOLE pill is one target (Role.Button, labelled); the badge is
 * decorative and semantics-cleared so TalkBack does not announce it.
 */
@Composable
internal fun UpgradePill(onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val label = stringResource(R.string.upgrade)
    val shape = RoundedCornerShape(50)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .clip(shape)
            // RAW brand colours, for the reason documented at the status
            // gradient: `scheme.primary` flips to purple in dark and collapses
            // this to a flat fill. NOT lifted, unlike the status: this is a
            // saturated FILL carrying white text, so it wants the deep stops in
            // both themes. White measures 9.42:1 on #69108C and 4.54:1 on
            // #E2232B, so the label clears 4.5:1 across the whole sweep rather
            // than only at the ends someone happened to check.
            .background(Brush.horizontalGradient(listOf(Accent3, Brand1)))
            .clickable(onClickLabel = label, role = Role.Button) { onClick() }
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                // A white scrim rather than a fill, so the badge reads on every
                // point of the gradient it might land on.
                .background(Color.White.copy(alpha = 0.22f))
                .clearAndSetSemantics { },
        ) {
            Icon(
                imageVector = Icons.Filled.Star,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(16.dp),
            )
        }
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = Color.White,
        )
    }
}
