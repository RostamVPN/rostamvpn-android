// Copyright 2026 Internet Mastering & Company, Inc.
// SPDX-License-Identifier: Apache-2.0

package com.rostamvpn.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import com.rostamvpn.android.IpRouting
import com.rostamvpn.android.R
import com.rostamvpn.android.TunnelVpnService
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rostamvpn.android.ui.theme.Brand1
import com.rostamvpn.android.ui.theme.Brand2
import com.rostamvpn.android.ui.theme.BrandPalette
import com.rostamvpn.android.ui.theme.liftOnto

data class AppInfo(
    val packageName: String,
    val appName: String
)

/** Split-tunnel picker. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppSelectionSheet(
    installedApps: List<AppInfo>,
    allowedApps: Set<String>,
    mode: String,
    isConnected: Boolean,
    bypassCidrs: List<String>,
    onSetMode: (String) -> Unit,
    onToggleApp: (String) -> Unit,
    onSelectAll: () -> Unit,
    onDeselectAll: () -> Unit,
    onRecommended: () -> Unit,
    onAddCidr: (String) -> Boolean,
    onRemoveCidr: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var searchQuery by remember { mutableStateOf("") }
    var showDestinations by remember { mutableStateOf(false) }

    val isExclude = mode == TunnelVpnService.MODE_EXCLUDE
    val isNoneSelected = !isExclude &&
        allowedApps.size == 1 && allowedApps.contains(TunnelVpnService.NONE_SENTINEL)

    val filteredApps = remember(installedApps, searchQuery) {
        if (searchQuery.isBlank()) {
            installedApps
        } else {
            val query = searchQuery.lowercase()
            installedApps.filter {
                it.appName.lowercase().contains(query) ||
                    it.packageName.lowercase().contains(query)
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp)
        ) {
          item {
            Text(
                text = stringResource(R.string.split_tunnel_title),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(horizontal = 24.dp)
            )

            Spacer(modifier = Modifier.height(12.dp))

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .selectableGroup()
            ) {
                ModeOption(
                    selected = isExclude,
                    title = stringResource(R.string.split_mode_exclude),
                    description = stringResource(R.string.split_mode_exclude_desc),
                    onSelect = { onSetMode(TunnelVpnService.MODE_EXCLUDE) }
                )
                Spacer(modifier = Modifier.height(8.dp))
                ModeOption(
                    selected = !isExclude,
                    title = stringResource(R.string.split_mode_include),
                    description = stringResource(R.string.only_selected_apps),
                    onSelect = { onSetMode(TunnelVpnService.MODE_INCLUDE) }
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            DestinationBypassSection(
                expanded = showDestinations,
                onToggleExpanded = { showDestinations = !showDestinations },
                cidrs = bypassCidrs,
                onAddCidr = onAddCidr,
                onRemoveCidr = onRemoveCidr
            )

            if (isConnected) {
                Spacer(modifier = Modifier.height(10.dp))

                Text(
                    text = stringResource(R.string.split_reconnect_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Start,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text(stringResource(R.string.search_apps), color = MaterialTheme.colorScheme.onSurfaceVariant) },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp),
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Brand1,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline
                )
            )

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val total = installedApps.size

                val checked = when {
                    isExclude -> allowedApps.size
                    isNoneSelected -> 0
                    allowedApps.isEmpty() -> total
                    else -> allowedApps.size
                }
                val summary = when {
                    isExclude && allowedApps.isEmpty() -> stringResource(R.string.split_state_all)
                    isExclude -> stringResource(R.string.split_state_bypass, checked, total)
                    isNoneSelected -> stringResource(R.string.split_state_none)
                    allowedApps.isEmpty() -> stringResource(R.string.split_state_all)
                    else -> stringResource(R.string.split_state_only, checked, total)
                }
                Text(
                    text = summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = Brand2,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f)
                )

                if (!isExclude) {
                    TextButton(onClick = onRecommended) {
                        Text(
                            stringResource(R.string.select_recommended),

                            color = liftOnto(
                                MaterialTheme.colorScheme.surface,
                                BrandPalette.success,
                                min = 4.5f,
                            ),
                            fontSize = 13.sp
                        )
                    }
                }
                TextButton(onClick = onSelectAll) {
                    Text(stringResource(R.string.select_all), color = Brand1, fontSize = 13.sp)
                }
                TextButton(onClick = onDeselectAll) {
                    Text(
                        stringResource(R.string.select_none),
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 13.sp
                    )
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outline, thickness = 1.dp)
          }

          items(filteredApps, key = { it.packageName }) { app ->

                    val isChecked = if (isExclude) {
                        allowedApps.contains(app.packageName)
                    } else {
                        !isNoneSelected &&
                            (allowedApps.isEmpty() || allowedApps.contains(app.packageName))
                    }
                    AppRow(
                        app = app,
                        isChecked = isChecked,
                        onToggle = { onToggleApp(app.packageName) }
                    )
          }
        }
    }
}

/** Editable list of destinations that skip the tunnel. */
@Composable
private fun DestinationBypassSection(
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    cidrs: List<String>,
    onAddCidr: (String) -> Boolean,
    onRemoveCidr: (String) -> Unit
) {
    var draft by remember { mutableStateOf("") }
    var showError by remember { mutableStateOf(false) }

    val overBudget = remember(cidrs) {
        try {
            IpRouting.planRoutes(cidrs.mapNotNull { IpRouting.parse(it) }).dropped.size
        } catch (_: Throwable) {
            0
        }
    }

    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onToggleExpanded() },
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.bypass_title),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = if (cidrs.isEmpty()) {
                        stringResource(R.string.bypass_none)
                    } else {
                        stringResource(R.string.bypass_count, cidrs.size)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (!expanded) return@Column

        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.bypass_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = draft,
                onValueChange = {
                    draft = it
                    showError = false
                },
                placeholder = {
                    Text(
                        stringResource(R.string.bypass_hint),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                },
                isError = showError,
                supportingText = if (showError) {
                    { Text(stringResource(R.string.bypass_invalid), color = MaterialTheme.colorScheme.error) }
                } else {
                    null
                },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Brand1,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline
                ),
                modifier = Modifier.weight(1f)
            )
            Spacer(modifier = Modifier.width(8.dp))
            TextButton(
                onClick = {
                    if (draft.isBlank()) return@TextButton
                    if (onAddCidr(draft)) {
                        draft = ""
                        showError = false
                    } else {
                        showError = true
                    }
                }
            ) {
                Text(stringResource(R.string.bypass_add), color = Brand1, fontSize = 13.sp)
            }
        }

        if (overBudget > 0) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.bypass_over_budget, overBudget),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }

        for (cidr in cidrs) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = cidr,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = { onRemoveCidr(cidr) }) {
                    Text(
                        stringResource(R.string.bypass_remove),
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 13.sp
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(4.dp))
    }
}

@Composable
private fun ModeOption(
    selected: Boolean,
    title: String,
    description: String,
    onSelect: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = if (selected) {
                    MaterialTheme.colorScheme.surfaceVariant
                } else {
                    Color.Transparent
                },
                shape = RoundedCornerShape(12.dp)
            )
            .border(
                width = 1.dp,
                color = if (selected) Brand1 else MaterialTheme.colorScheme.outline,
                shape = RoundedCornerShape(12.dp)
            )
            .selectable(
                selected = selected,
                onClick = onSelect,
                role = Role.RadioButton
            )
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(
            selected = selected,
            onClick = null,
            colors = RadioButtonDefaults.colors(selectedColor = Brand1)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                color = if (selected) Brand1 else MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun AppRow(
    app: AppInfo,
    isChecked: Boolean,
    onToggle: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(horizontal = 24.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(
            checked = isChecked,
            onCheckedChange = { onToggle() },
            colors = CheckboxDefaults.colors(
                checkedColor = Brand1,
                uncheckedColor = MaterialTheme.colorScheme.outline,
                checkmarkColor = Color.White
            )
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = app.appName,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (isChecked) FontWeight.Medium else FontWeight.Normal,
                color = if (isChecked) Brand1 else MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = app.packageName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp
            )
        }
    }
}
