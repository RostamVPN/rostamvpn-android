package com.rostamvpn.android.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import app.netcore.NetCoreBridge
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.rostamvpn.android.R
import com.rostamvpn.android.ui.theme.DarkBase
import com.rostamvpn.android.ui.theme.liftOnto
import com.rostamvpn.android.ui.theme.mostLegibleOn
import com.rostamvpn.android.ui.theme.Brand1
import com.rostamvpn.android.ui.theme.Brand2

/**
 * Modal bottom sheet with app settings:
 * - DNS Resolver selection (for rescue transports)
 * - Ad opt-out (freedom regions only)
 * - Clear Cache button
 * - Share Debug Log button
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsSheet(
    themeSetting: String,
    onSetTheme: (String) -> Unit,
    onShareDebugLog: () -> Unit,
    onSelectApps: () -> Unit,
    languageSetting: String = "auto",
    onSetLanguage: (String) -> Unit = {},
    onDismiss: () -> Unit,
    /** The Protected Apps icon. Default is today's padlock; a treatment
     *  without padlocks passes its own. */
    appsIcon: androidx.compose.ui.graphics.vector.ImageVector = Icons.Outlined.Lock,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // ── Brand icons on this sheet were under the legibility floor ──
    //
    // The two FilledTonalButton icons tint with raw Brand1 on
    // surfaceVariant. Measured from screencaps of this sheet: DollarVPN
    // #294C1D on #2C2C2C is 1.43:1 and RostamVPN #3D6B6F on the same pill is
    // 2.35:1, both under the 3:1 an icon gets. RedVPN's light sheet is 3.98:1
    // and does not move. Same fault and same remedy as the shared top bar:
    // liftOnto against the surface the icon actually lands on.
    val scheme = MaterialTheme.colorScheme
    val brandOnPill = liftOnto(
        scheme.surfaceVariant, Brand1, min = 3f,
        toward = mostLegibleOn(scheme.surfaceVariant, Color.White, DarkBase),
    )
    // ⚠️ This was first written as "a guard for the next brand, nothing moves
    // today", on a DollarVPN measurement of 4.98:1. Wrong: RedVPN's `secondary`
    // is its PURPLE #69108C, and on the dark sheet #292130 that is 1.52:1 —
    // invisible. Confirmed on-device by setting RedVPN to Dark: the icon now
    // renders #9658AF at 3.18:1. So this lift is load-bearing for RedVPN-dark,
    // not decorative.
    val brandOnSheet = liftOnto(
        scheme.surface, Brand2, min = 3f,
        toward = mostLegibleOn(scheme.surface, Color.White, DarkBase),
    )

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp)
        ) {
            // Title
            Text(
                text = stringResource(R.string.settings),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Theme section
            Text(
                text = stringResource(R.string.appearance),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )

            Spacer(modifier = Modifier.height(12.dp))

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                FilterChip(
                    selected = themeSetting == "auto",
                    onClick = { onSetTheme("auto") },
                    label = { Text(stringResource(R.string.theme_auto)) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = Brand1,
                        selectedLabelColor = Color.White
                    )
                )
                FilterChip(
                    selected = themeSetting == "light",
                    onClick = { onSetTheme("light") },
                    label = { Text(stringResource(R.string.theme_light)) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = Brand1,
                        selectedLabelColor = Color.White
                    )
                )
                FilterChip(
                    selected = themeSetting == "dark",
                    onClick = { onSetTheme("dark") },
                    label = { Text(stringResource(R.string.theme_dark)) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = Brand1,
                        selectedLabelColor = Color.White
                    )
                )
            }

            Spacer(modifier = Modifier.height(20.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outline, thickness = 1.dp)
            Spacer(modifier = Modifier.height(20.dp))

            // Language — manual override. "Auto" follows the timezone (Option B);
            // each option shows its own native name so it's readable in any UI lang.
            Text(
                text = stringResource(R.string.language),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(12.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                listOf(
                    "auto" to stringResource(R.string.language_auto),
                    "en" to "English", "zh" to "中文", "fa" to "فارسی",
                    "ar" to "العربية", "ru" to "Русский"
                ).forEach { (code, label) ->
                    FilterChip(
                        selected = languageSetting == code,
                        onClick = { onSetLanguage(code) },
                        label = { Text(label) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Brand1,
                            selectedLabelColor = Color.White
                        )
                    )
                }
            }

            // The transport picker was REMOVED. The app chooses its own protocol
            // from the config's `transport_order`, which is served OTA and can be
            // reordered per brand without a release.
            //
            // It was never a user-meaningful choice: netcore and wgtcp differ
            // only in how they survive a particular network's filtering, and a
            // user has no way to know which that is — but a wrong pick is a
            // tunnel that will not connect at all. Leaving it exposed meant one
            // stale tap could strand someone behind a national filter, which is
            // the one failure this app exists to prevent. `selectProtocol()` is
            // kept for the Dev menu.

            Spacer(modifier = Modifier.height(20.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outline, thickness = 1.dp)
            Spacer(modifier = Modifier.height(20.dp))

            // Protected Apps
            FilledTonalButton(
                onClick = onSelectApps,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = MaterialTheme.colorScheme.onSurface
                )
            ) {
                Icon(
                    imageVector = appsIcon,
                    contentDescription = null,
                    tint = brandOnPill
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(text = stringResource(R.string.protected_apps))
            }

            Spacer(modifier = Modifier.height(16.dp))

            FilledTonalButton(
                onClick = onShareDebugLog,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = MaterialTheme.colorScheme.onSurface
                )
            ) {
                Icon(
                    imageVector = Icons.Outlined.Share,
                    contentDescription = null,
                    tint = brandOnPill
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(text = stringResource(R.string.share_debug_log))
            }

            Spacer(modifier = Modifier.height(24.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outline, thickness = 1.dp)
            Spacer(modifier = Modifier.height(20.dp))

            // About section
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Outlined.Info,
                    contentDescription = null,
                    tint = brandOnSheet
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.about),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = stringResource(R.string.version_format, com.rostamvpn.android.BuildConfig.VERSION_NAME),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.app_tagline),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
