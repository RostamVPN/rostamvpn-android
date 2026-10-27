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

/**
 * Split-tunnel picker.
 *
 * The list used to be an allowlist with no way to say so beyond one subtitle, and
 * users read it the other way round: they added a banking app expecting to take it
 * OUT of the tunnel, and instead made it the only thing in it — so the VPN looked
 * dead. The direction is now an explicit, always-visible choice, and the line above
 * the list states the resulting behaviour in plain words rather than leaving the
 * user to infer it from checkbox semantics.
 */
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
        // 🚨 ONE SCROLLER, HEADERS INCLUDED. Everything above the app list is an
        // `item {}` rather than a sibling, and that is the actual fix for
        // "you cannot select applications ... the list does not fit on the
        // screen" (2026-10-01).
        //
        // The first attempt -- `weight(1f)` in place of a fixed `height(400.dp)`
        // -- was a no-op in exactly the case reported, and the reasoning beside
        // it was wrong twice. Column measures a non-weighted child against the
        // REMAINING space, so the old `height(400.dp)` was already clamped and
        // never ran off the screen; and `heightIn(min=)` after a weight cannot
        // fire at all, because a filled weight hands the child a FIXED
        // constraint which `heightIn` is then coerced into. So the "floor" that
        // was meant to stop a long CIDR list eating the picker was inert in
        // precisely that scenario. Both proved from the androidx measure source,
        // not reasoned about.
        //
        // What was actually wrong: when the title, the two mode rows, the
        // destinations section and the search field together exceed the sheet
        // -- a short screen, a large display size, or the keyboard open, which
        // costs ~300dp -- the list is allocated ZERO and the rows below it are
        // measured at zero too. No scroll position can reach an app row,
        // because the list has no height to scroll.
        //
        // As items of one LazyColumn there is no competition for height: the
        // headers scroll away and the app rows are always reachable. Do NOT
        // "fix" a future version of this by wrapping a Column in
        // `verticalScroll` instead -- that makes the available height infinite
        // and a weight is then disregarded entirely.
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp)
        ) {
          item {
            // Title
            Text(
                text = stringResource(R.string.split_tunnel_title),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(horizontal = 24.dp)
            )

            Spacer(modifier = Modifier.height(12.dp))

            // ── Direction ─────────────────────────────────────────────────────
            // Exclude is listed first: it is the one the reviews are asking for
            // and the one most people mean by "take this app out of the VPN".
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

            // ── Destinations ──────────────────────────────────────────────────
            // A separate axis from the app list, not a mode of it: the common
            // case here is a browser that must stay in the tunnel opening a
            // domestic bank that must stay out of it, and no per-app rule can
            // express that. Collapsed by default so the app picker — which is
            // what most people came for — keeps its full height.
            DestinationBypassSection(
                expanded = showDestinations,
                onToggleExpanded = { showDestinations = !showDestinations },
                cidrs = bypassCidrs,
                onAddCidr = onAddCidr,
                onRemoveCidr = onRemoveCidr
            )

            if (isConnected) {
                Spacer(modifier = Modifier.height(10.dp))
                // Routing is fixed at establish() time, so any edit here rebuilds
                // the tunnel. Saying so up front stops a brief drop reading as a
                // crash.
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

            // Search bar
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

            // Effect summary + bulk actions
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val total = installedApps.size
                // Checked count, in the units the current mode actually uses.
                val checked = when {
                    isExclude -> allowedApps.size
                    isNoneSelected -> 0
                    allowedApps.isEmpty() -> total
                    else -> allowedApps.size
                }
                val summary = when {
                    // An empty exclude list is not an empty allowlist — it means
                    // no split tunnelling at all, i.e. every app is tunnelled.
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
                // The recommended set is browsers + messengers, which only makes
                // sense as "put these IN". Offering it in exclude mode would push
                // exactly the wrong apps out of the tunnel.
                if (!isExclude) {
                    TextButton(onClick = onRecommended) {
                        Text(
                            stringResource(R.string.select_recommended),
                            // Was #4CAF50, Material's green: the one control on
                            // this sheet that ignored the brand entirely.
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

          // The app rows themselves. `key` keeps checkbox state attached to the
          // package across a filter change.
          items(filteredApps, key = { it.packageName }) { app ->
                    // Exclude mode is literal: checked == bypasses the tunnel.
                    // Include mode keeps its historical "empty means all" reading.
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

/**
 * Editable list of destinations that skip the tunnel.
 *
 * Deliberately plain: a text field, an Add button, and a row per entry. The
 * validation is the interesting part — an entry that does not parse is refused
 * *in front of the user* rather than dropped later, because a bypass rule
 * someone believes is active but is not is what sends their banking traffic out
 * of a foreign IP.
 */
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

    // How many entries the platform cannot actually be given. Pure and cheap —
    // the same planner the service runs — so the warning appears while editing
    // rather than after a reconnect silently did less than the list claims.
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
            // Honest degradation: these entries parsed, were accepted, and still
            // are not being applied. Saying so is the whole point.
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
            onClick = null,   // the whole row is the target
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
