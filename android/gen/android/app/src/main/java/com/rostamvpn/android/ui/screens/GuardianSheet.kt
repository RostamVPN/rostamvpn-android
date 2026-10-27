package com.rostamvpn.android.ui.screens

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rostamvpn.android.R
import app.netcore.NetCoreBridge
import com.rostamvpn.android.VpnViewModel
import kotlinx.coroutines.delay
import org.json.JSONObject

/**
 * The Guardian: what was refused or flagged this session, one Allow per row,
 * and the Rules screen behind it. Reads the core's recent-blocks ring over the
 * existing JNI (`getBlockStats`) and writes the user's own rules
 * (`setUserRule`). PRIVACY: the timeline is displayed only, never logged or
 * sent anywhere (NetCoreBridge.getBlockStats doc).
 *
 * The exit-side verdicts (`blk1`) and the mailbox feed the same ring when the
 * client core lands; this screen does not change for them.
 */
@Composable
internal fun GuardianSheet(viewModel: VpnViewModel, initial: GuardStats, onClose: () -> Unit) {
    var rules by remember { mutableStateOf(false) }
    if (rules) { RulesScreen(viewModel, onClose = { rules = false }); return }

    val scheme = MaterialTheme.colorScheme
    val guardianOn by viewModel.guardianOn.collectAsState()
    val info by viewModel.protectionInfo.collectAsState()
    val acked by viewModel.protectionAcked.collectAsState()
    val ctx = androidx.compose.ui.platform.LocalContext.current
    // Same gate as the VM's collector: the database is created only when the
    // brand ships protection AND it is on remotely. A history that already
    // exists (protection switched off later) is still shown, never created.
    val store = remember {
        val guarded = com.rostamvpn.android.BuildConfig.FEATURE_PROTECTION &&
            com.rostamvpn.android.ConfigManager.protectionEnabled
        if (guarded || ctx.getDatabasePath("guard.db").exists()) com.rostamvpn.android.GuardStore.get(ctx) else null
    }
    // The stored timeline (30 days, 2,000 rows) so it survives restarts. The
    // VM's poller copies the core's ring into it while connected.
    var rows by remember { mutableStateOf(emptyList<com.rostamvpn.android.GuardStore.Row>()) }
    var allowed by remember { mutableStateOf(readRules().first.toSet()) }
    val offBits = remember { categoryOffBits(ctx) }
    LaunchedEffect(Unit) {
        while (true) {
            rows = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { store?.recent().orEmpty() }
                // A notify row whose every category the user switched off is hidden.
                .filter { it.refused || it.bits == 0 || (it.bits and offBits.inv()) != 0 }
            delay(3000)
        }
    }

    TabsSheet(stringResource(R.string.tabs_guardian_title), onClose) {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(9.dp)) {
            item {
                Text(
                    stringResource(if (acked && guardianOn) R.string.tabs_guardian_on else R.string.tabs_guardian_off),
                    fontSize = 13.5.sp, color = scheme.onSurfaceVariant,
                )
                info?.takeIf { it.feedDate.isNotBlank() }?.let {
                    Text(stringResource(R.string.tabs_guardian_updated, it.feedDate), fontSize = 12.5.sp, color = scheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(R.string.tabs_rules), fontSize = 14.sp, fontWeight = FontWeight.Bold, color = scheme.primary,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { rules = true }.padding(vertical = 8.dp),
                )
            }
            if (rows.isEmpty()) item {
                Text(stringResource(R.string.tabs_guardian_empty), fontSize = 14.sp, color = scheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 24.dp))
            }
            items(rows, key = { it.host + ":" + it.port + ":" + it.ts + ":" + it.refused }) { e ->
                val isAllowed = e.host in allowed
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(scheme.surface)
                        .border(1.dp, scheme.outlineVariant, RoundedCornerShape(14.dp)).padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(e.host, fontSize = 14.5.sp, fontWeight = FontWeight.Bold, color = scheme.onSurface, maxLines = 1)
                        Spacer(Modifier.height(2.dp))
                        val ago = DateUtils.getRelativeTimeSpanString(
                            e.ts, System.currentTimeMillis(), DateUtils.SECOND_IN_MILLIS,
                        ).toString()
                        val label = if (e.refused) e.reason else stringResource(R.string.tabs_notified) + " · " + e.reason
                        Text(
                            stringResource(R.string.tabs_guardian_row, e.port, label, ago),
                            fontSize = 12.sp, color = scheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        stringResource(if (isAllowed) R.string.tabs_allowed else R.string.tabs_allow),
                        fontSize = 13.sp, fontWeight = FontWeight.Bold,
                        color = if (isAllowed) scheme.onSurfaceVariant else scheme.primary,
                        modifier = Modifier.clip(RoundedCornerShape(10.dp))
                            .border(1.dp, if (isAllowed) scheme.outlineVariant else scheme.primary.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
                            .clickable(enabled = !isAllowed) {
                                // Takes effect on the next connection; no reconnect (bridge doc).
                                if (NetCoreBridge.isLoaded) runCatching { NetCoreBridge.setUserRule(e.host, "allow") }
                                allowed = readRules().first.toSet()
                            }
                            .padding(horizontal = 14.dp, vertical = 7.dp),
                    )
                }
            }
            item { Spacer(Modifier.height(16.dp)) }
        }
    }
}

/** (allow, deny) as authored. Empty when the core is not loaded. */
internal fun readRules(): Pair<List<String>, List<String>> {
    if (!NetCoreBridge.isLoaded) return emptyList<String>() to emptyList()
    return runCatching {
        val o = JSONObject(NetCoreBridge.getUserRules())
        val a = o.optJSONArray("allow"); val d = o.optJSONArray("deny")
        (0 until (a?.length() ?: 0)).map { a!!.getString(it) } to (0 until (d?.length() ?: 0)).map { d!!.getString(it) }
    }.getOrDefault(emptyList<String>() to emptyList())
}

@Composable
private fun RulesScreen(viewModel: VpnViewModel, onClose: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val on by viewModel.guardianOn.collectAsState()
    var version by remember { mutableIntStateOf(0) }
    val (allow, deny) = remember(version) { readRules() }
    val cats = remember(version) { readGuardStats().byCategory }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var offMask by remember { mutableIntStateOf(categoryOffBits(ctx)) }

    fun set(host: String, verdict: String) {
        if (host.isBlank() || !NetCoreBridge.isLoaded) return
        runCatching { NetCoreBridge.setUserRule(host.trim().lowercase(), verdict) }
        version++
    }

    TabsSheet(stringResource(R.string.tabs_rules), onClose) {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(scheme.surface)
                        .border(1.dp, scheme.outlineVariant, RoundedCornerShape(14.dp)).padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.tabs_rules_master), fontSize = 15.sp, fontWeight = FontWeight.Bold, color = scheme.onSurface)
                        Text(stringResource(R.string.tabs_rules_master_sub), fontSize = 12.5.sp, color = scheme.onSurfaceVariant)
                    }
                    Spacer(Modifier.width(10.dp))
                    Switch(
                        checked = on, onCheckedChange = { viewModel.setGuardianOn(it) },
                        colors = SwitchDefaults.colors(checkedTrackColor = scheme.primary, checkedThumbColor = scheme.onPrimary),
                    )
                }
            }
            if (cats.isNotEmpty()) item {
                SectionLabel(stringResource(R.string.tabs_rules_categories))
                cats.forEach { (c, n) ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Text(c, fontSize = 14.sp, color = scheme.onSurface, modifier = Modifier.weight(1f))
                        Text("$n", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = scheme.onSurfaceVariant)
                    }
                }
            }
            val table = com.rostamvpn.android.ConfigManager.protectionCategories
            if (table.isNotEmpty()) item {
                SectionLabel(stringResource(R.string.tabs_rules_toggles))
                table.entries.sortedBy { it.key }.forEach { (name, bit) ->
                    val on = offMask and (1 shl bit) == 0
                    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(name, fontSize = 14.sp, color = scheme.onSurface, modifier = Modifier.weight(1f))
                        Switch(
                            checked = on,
                            onCheckedChange = { v ->
                                offMask = if (v) offMask and (1 shl bit).inv() else offMask or (1 shl bit)
                                setCategoryOffBits(ctx, offMask)
                            },
                            colors = SwitchDefaults.colors(checkedTrackColor = scheme.primary, checkedThumbColor = scheme.onPrimary),
                        )
                    }
                }
            }
            item { RuleList(stringResource(R.string.tabs_rules_allow), allow, onAdd = { set(it, "allow") }, onRemove = { set(it, "clear") }) }
            item { RuleList(stringResource(R.string.tabs_rules_deny), deny, onAdd = { set(it, "deny") }, onRemove = { set(it, "clear") }) }
            item { Spacer(Modifier.height(16.dp)) }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
}

@Composable
private fun RuleList(title: String, entries: List<String>, onAdd: (String) -> Unit, onRemove: (String) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    var input by remember { mutableStateOf("") }
    Column {
        SectionLabel(title)
        if (entries.isEmpty()) Text(stringResource(R.string.tabs_rules_empty), fontSize = 13.sp, color = scheme.onSurfaceVariant)
        entries.forEach { h ->
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(h, fontSize = 14.sp, color = scheme.onSurface, modifier = Modifier.weight(1f))
                Text(
                    stringResource(R.string.tabs_rules_remove), fontSize = 13.sp, color = scheme.primary, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { onRemove(h) }.padding(6.dp),
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
            OutlinedTextField(
                value = input, onValueChange = { input = it }, singleLine = true,
                placeholder = { Text(stringResource(R.string.tabs_rules_hint), fontSize = 14.sp) },
                modifier = Modifier.weight(1f), shape = RoundedCornerShape(12.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                stringResource(R.string.tabs_rules_add), fontSize = 14.sp, fontWeight = FontWeight.Bold, color = scheme.onPrimary,
                modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(scheme.primary)
                    .clickable { onAdd(input); input = "" }.padding(horizontal = 16.dp, vertical = 12.dp),
            )
        }
    }
}

/**
 * Categories the user switched off, as verdict bits. Stored on the device.
 * NOTE: the exit decides blocking; until the control-stream contract carries
 * this mask upstream it only hides matching NOTIFY rows here.
 */
internal fun categoryOffBits(ctx: android.content.Context): Int =
    ctx.getSharedPreferences("rostam_prefs", android.content.Context.MODE_PRIVATE).getInt("guard_off_bits", 0)

internal fun setCategoryOffBits(ctx: android.content.Context, bits: Int) {
    ctx.getSharedPreferences("rostam_prefs", android.content.Context.MODE_PRIVATE).edit().putInt("guard_off_bits", bits).apply()
    sendCategoriesOff(bits)
}

/**
 * Hand the mask to the core, which sends it upstream as `categories_off` in
 * the {rules} frame (contract 2026-10-12). Rides setUserRules with the
 * current lists, so no new export. Device-side hiding stays as well.
 */
internal fun sendCategoriesOff(bits: Int) {
    if (!NetCoreBridge.isLoaded) return
    runCatching {
        val o = JSONObject(NetCoreBridge.getUserRules()).put("categories_off", bits and 0xFFFF)
        NetCoreBridge.setUserRules(o.toString())
    }
}
