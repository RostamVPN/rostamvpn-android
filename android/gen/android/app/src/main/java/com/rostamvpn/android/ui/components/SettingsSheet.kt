// Copyright 2026 Internet Mastering & Company, Inc.
// SPDX-License-Identifier: Apache-2.0

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
import com.rostamvpn.android.RedSocketBridge
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.rostamvpn.android.R
import com.rostamvpn.android.ui.theme.DarkBase
import com.rostamvpn.android.ui.theme.liftOnto
import com.rostamvpn.android.ui.theme.mostLegibleOn
import com.rostamvpn.android.ui.theme.Brand1
import com.rostamvpn.android.ui.theme.Brand2

/**
 * Modal bottom sheet with app settings: - DNS Resolver selection (for rescue
 * transports) - Ad opt-out (freedom regions only) - Clear Cache button - Share
 * Debug Log button.
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
    /**
     * The Protected Apps icon. Default is today's padlock; a treatment without
     * padlocks passes its own.
     */
    appsIcon: androidx.compose.ui.graphics.vector.ImageVector = Icons.Outlined.Lock,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val scheme = MaterialTheme.colorScheme
    val brandOnPill = liftOnto(
        scheme.surfaceVariant, Brand1, min = 3f,
        toward = mostLegibleOn(scheme.surfaceVariant, Color.White, DarkBase),
    )

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
            Text(
                text = stringResource(R.string.settings),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )

            Spacer(modifier = Modifier.height(24.dp))

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

            Spacer(modifier = Modifier.height(20.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outline, thickness = 1.dp)
            Spacer(modifier = Modifier.height(20.dp))

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

            val ctx = LocalContext.current
            val peerPrefs = remember {
                ctx.getSharedPreferences("rostam_prefs", android.content.Context.MODE_PRIVATE)
            }
            var peerOn by remember { mutableStateOf(peerPrefs.getBoolean("probe_enabled", false)) }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.help_improve, stringResource(R.string.app_name)),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = stringResource(R.string.help_improve_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Switch(
                    checked = peerOn,
                    onCheckedChange = { on ->
                        peerOn = on
                        peerPrefs.edit().putBoolean("probe_enabled", on).apply()

                        if (!on && RedSocketBridge.isLoaded) {
                            try {
                                RedSocketBridge.disablePeer()
                            } catch (t: Throwable) {
                                android.util.Log.w("SettingsSheet", "disablePeer failed", t)
                            }
                        }
                    }
                )
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
