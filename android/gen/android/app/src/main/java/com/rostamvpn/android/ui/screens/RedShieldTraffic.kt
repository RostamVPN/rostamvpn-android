package com.rostamvpn.android.ui.screens

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.rostamvpn.android.ConfigManager
import com.rostamvpn.android.GuardStore
import com.rostamvpn.android.R
import com.rostamvpn.android.RedShield
import com.rostamvpn.android.RedShieldProfiles
import com.rostamvpn.android.ShieldAppControls
import com.rostamvpn.android.ShieldFlows
import com.rostamvpn.android.ShieldInsights
import com.rostamvpn.android.ShieldInsights.Proto
import com.rostamvpn.android.ShieldScoreCache
import com.rostamvpn.android.TunnelVpnService
import com.rostamvpn.android.VpnViewModel
import com.rostamvpn.android.ui.theme.Brand1
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.NumberFormat
import java.util.Locale

// The Traffic segment of the RedShield sheet, the per-app page and Ask
// RedShield (iOS GuardianTraffic.swift + GuardianAsk.swift). Owner,
// 2026-10-25: "a much better design than just a long list." Top to bottom:
// header (Ask, Scan), a traffic map (squarified treemap by app on Android,
// by service as the alternative; area = bytes, colour = verdict), the rows
// that need attention, where in the world, protocols, then the destinations
// grouped, collapsible, filterable and searchable.

/** Opened from outside the sheet (the home card's Ask): consumed once by the sheet. */
internal object ShieldSheetNav {
    @Volatile var open: String? = null
}

// ── Words ───────────────────────────────────────────────────────────────────

internal fun protoTitle(ctx: Context, p: Proto): String = when (p) {
    Proto.HTTPS -> "HTTPS"
    Proto.QUIC -> "QUIC"
    Proto.HTTP -> "HTTP"
    Proto.DNS -> "DNS"
    Proto.SSH -> "SSH"
    Proto.MAIL -> ctx.getString(R.string.rs_proto_mail)
    Proto.MESSAGING -> ctx.getString(R.string.rs_proto_messaging)
    Proto.OTHER -> ctx.getString(R.string.rs_proto_other)
}

/** The group's display name: the app, "Android system", or "Not matched to an app". */
internal fun appGroupName(ctx: Context, uid: Int?): String = when {
    uid == null -> ctx.getString(R.string.rs_unattributed)
    uid < 10_000 -> ctx.getString(R.string.rs_system)
    else -> AppLabels.of(ctx, uid)?.name ?: runCatching { ctx.packageManager.getNameForUid(uid) }.getOrNull()
        ?: ctx.getString(R.string.rs_unattributed)
}

/** "Chrome, YouTube and Photos" in the reader's language (API 26+), else commas. */
internal fun joinList(items: List<String>): String =
    if (android.os.Build.VERSION.SDK_INT >= 26) android.icu.text.ListFormatter.getInstance().format(items)
    else items.joinToString(", ")

private fun tileColor(tone: String): Color = when (tone) {
    "blocked" -> Color(0xFFD64541)
    "suspicious" -> Color(0xFFDE7F1F)
    "known" -> Color(0xFF4D73A8)
    else -> Color(0xFF6E7F96)
}

private val Unusual = Color(0xFF9B59D0)

internal fun TrafficModel.view(ctx: Context, deny: Set<String>): ShieldInsights.View {
    val f = flows?.flows.orEmpty()
    val attributed = f.any { it.uid != null }
    return ShieldInsights.View(f, cache, deny, newToday, byApp && attributed) { appGroupName(ctx, it) }
}

// ── The segment ─────────────────────────────────────────────────────────────

internal fun LazyListScope.trafficSections(
    ctx: Context, model: TrafficModel, live: Boolean, lists: Pair<List<String>, List<String>>,
    onRule: (String, String) -> Unit, onScan: () -> Unit, onReport: () -> Unit, onAsk: () -> Unit, onApp: (Int) -> Unit,
) {
    val allow = lists.first.toSet()
    val deny = lists.second.toSet()
    val v = model.view(ctx, deny)
    item(key = "header") { TrafficHeaderCard(model, live, onScan, onReport, onAsk) }
    if (v.facts.isEmpty()) {
        item(key = "empty") { EmptyState(ShieldGlyph.PULSE, R.string.rs_no_traffic, R.string.rs_no_traffic_line) }
        return
    }
    item(key = "map") { MapCard(model, v) }
    item(key = "attention-h") { SectionHeader(R.string.rs_needs_attention) }
    item(key = "attention") { AttentionStrip(v, allow, deny, onRule) }
    if (v.countries.isNotEmpty()) {
        item(key = "world-h") { SectionHeader(R.string.rs_where_world) }
        item(key = "world") { WorldStrip(model, v) }
    }
    item(key = "protocols") { ProtocolCard(model, v) }
    item(key = "filter") { FilterBar(model) }
    val groups = filteredGroups(model, v)
    for (g in groups) {
        val open = model.expanded[g.key] ?: (g.needsAttention || model.focus == g.key)
        item(key = "g:${g.key}") {
            ShopSection {
                GroupHeader(g, v.byApp, open, onToggle = { model.expanded[g.key] = !open }, onApp = onApp)
                if (open) g.rows.forEachIndexed { i, f ->
                    TrafficRow(f, f.flow.host in allow, i < g.rows.lastIndex, showApp = !v.byApp, onRule = onRule)
                }
            }
        }
    }
}

private fun filteredGroups(model: TrafficModel, v: ShieldInsights.View): List<ShieldInsights.Group> {
    val q = model.query.trim().lowercase(Locale.ROOT)
    return v.groups.mapNotNull { g ->
        if (model.focus != null && g.key != model.focus) return@mapNotNull null
        val rows = g.rows.filter { f ->
            when (model.filter) {
                TrafficFilter.ATTENTION -> if (!f.needsAttention) return@filter false
                TrafficFilter.KNOWN -> if (!f.known) return@filter false
                TrafficFilter.ALL -> {}
            }
            if (model.proto != null && (f.protocols[model.proto] ?: 0) == 0L) return@filter false
            if (model.country != null && f.country != model.country) return@filter false
            if (q.isNotEmpty() && !f.flow.host.contains(q) && !g.name.lowercase().contains(q) && !f.service.lowercase().contains(q)) return@filter false
            true
        }
        if (rows.isEmpty()) null else ShieldInsights.Group(g.key, g.name, g.uid, rows)
    }
}

private fun TrafficModel.clearFocus() { focus = null; proto = null; country = null }

// ── Header ──────────────────────────────────────────────────────────────────

@Composable
private fun TrafficHeaderCard(model: TrafficModel, live: Boolean, onScan: () -> Unit, onReport: () -> Unit, onAsk: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    ShopSection {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.rs_traffic_since, model.rows.size), style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold, color = scheme.onSurface, modifier = Modifier.semantics { heading() })
            Text(stringResource(R.string.rs_traffic_privacy, RedShield.name, stringResource(R.string.app_name)),
                style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.weight(1f).heightIn(min = 44.dp).clip(RoundedCornerShape(12.dp))
                        .border(1.dp, scheme.outlineVariant, RoundedCornerShape(12.dp))
                        .clickable(role = Role.Button, onClick = onAsk).padding(horizontal = 10.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(stringResource(R.string.rs_ask_button, stringResource(R.string.app_name)), style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold, color = ShopColors.accentOnCard(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                ShopPrimaryButton(onClick = onScan, enabled = live && model.scanList.isNotEmpty() && !model.scanning,
                    busy = model.scanning, modifier = Modifier.weight(1f)) {
                    if (!model.scanning) { Icon(Icons.Filled.Search, null, modifier = Modifier.size(20.dp)); Spacer(Modifier.width(6.dp)) }
                    Text(stringResource(if (model.scanning) R.string.rs_scanning else R.string.rs_scan), maxLines = 1)
                }
            }
            if (!live) Text(stringResource(R.string.rs_scan_connected_only), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
            else model.error?.let { Text(errorText(it), style = MaterialTheme.typography.bodySmall, color = Color(0xFFE08A2E)) }
            if (model.report != null && model.reportAt > 0) {
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(role = Role.Button, onClick = onReport).padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.rs_last_scan), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = scheme.onSurface)
                        Text(stringResource(R.string.rs_scanned_ago, ShieldWords.ago(model.reportAt)), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                    }
                    Chevron()
                }
            }
        }
    }
}

// ── Traffic map ─────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MapCard(model: TrafficModel, v: ShieldInsights.View) {
    val ctx = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    val groups = v.groups.filter { it.bytes > 0 }.take(9)
    val total = maxOf(1.0, v.totalBytes.toDouble())
    ShopSection {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.rs_traffic_map), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                color = scheme.onSurface, modifier = Modifier.semantics { heading() })
            run {
                // By app only where Android told us the apps.
                if (v.attributed) {
                    val seg = SegmentedButtonDefaults.colors(
                        activeContainerColor = Brand1.copy(alpha = 0.14f), activeContentColor = scheme.onSurface,
                        activeBorderColor = scheme.outline, inactiveContainerColor = Color.Transparent,
                        inactiveContentColor = scheme.onSurfaceVariant,
                    )
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().height(36.dp)) {
                        SegmentedButton(selected = model.byApp, onClick = { model.byApp = true; model.clearFocus() },
                            shape = SegmentedButtonDefaults.itemShape(0, 2), colors = seg, icon = {}) {
                            Text(stringResource(R.string.rs_by_app), style = MaterialTheme.typography.labelMedium, maxLines = 1)
                        }
                        SegmentedButton(selected = !model.byApp, onClick = { model.byApp = false; model.clearFocus() },
                            shape = SegmentedButtonDefaults.itemShape(1, 2), colors = seg, icon = {}) {
                            Text(stringResource(R.string.rs_by_service), style = MaterialTheme.typography.labelMedium, maxLines = 1)
                        }
                    }
                }
            }
            BoxWithConstraints(Modifier.fillMaxWidth().height(184.dp)) {
                val density = LocalDensity.current
                val wPx = constraints.maxWidth.toFloat()
                val hPx = constraints.maxHeight.toFloat()
                val rects = remember(groups.map { it.key to it.bytes }, wPx, hPx) {
                    ShieldInsights.treemap(groups.map { it.bytes.toDouble() }, wPx, hPx)
                }
                val gap = with(density) { 1.5.dp.toPx() }
                groups.forEachIndexed { i, g ->
                    val r = rects[i]
                    val wDp = with(density) { maxOf(0f, r.w - 2 * gap).toDp() }
                    val hDp = with(density) { maxOf(0f, r.h - 2 * gap).toDp() }
                    val dim = model.focus != null && model.focus != g.key
                    val share = (g.bytes / total * 100).let { if (it < 1) "<1%" else "${it.toInt()}%" }
                    Box(
                        Modifier.offset { IntOffset((r.x + gap).toInt(), (r.y + gap).toInt()) }
                            .size(wDp, hDp).clip(RoundedCornerShape(8.dp))
                            .background(tileColor(g.tone).copy(alpha = if (dim) 0.35f else 0.92f))
                            .then(if (g.unusual) Modifier.border(3.dp, Unusual, RoundedCornerShape(8.dp)) else Modifier)
                            .clickable(role = Role.Button) {
                                val same = model.focus == g.key
                                model.clearFocus()
                                if (!same) { model.focus = g.key; model.expanded[g.key] = true }
                            }
                            .semantics { contentDescription = "${g.name}, $share" },
                    ) {
                        if (wDp > 46.dp && hDp > 28.dp) Column(Modifier.padding(6.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (v.byApp && wDp > 70.dp && hDp > 44.dp) rememberApp(g.uid)?.let { AppIcon(it, 16.dp); Spacer(Modifier.width(4.dp)) }
                                Text(if (v.byApp) g.name else isolate(g.name), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold,
                                    color = Color.White, maxLines = if (hDp > 50.dp && wDp > 96.dp) 2 else 1, overflow = TextOverflow.Ellipsis)
                            }
                            Text(share, style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.88f), maxLines = 1)
                        }
                    }
                }
            }
            Text(sentence(ctx, v), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
        }
    }
}

/** "Mostly Chrome, YouTube and Photos · Needs attention: 2" */
private fun sentence(ctx: Context, v: ShieldInsights.View): String {
    val top = v.groups.filter { !(v.byApp && it.uid == null) }.take(3).map { it.name }
    val parts = mutableListOf<String>()
    if (top.isNotEmpty()) parts += ctx.getString(R.string.rs_mostly, joinList(top))
    val n = v.attention.size
    parts += if (n == 0) ctx.getString(R.string.rs_nothing_attention) else ctx.getString(R.string.rs_attention_count, n)
    return parts.joinToString(" · ")
}

// ── Needs attention ─────────────────────────────────────────────────────────

@Composable
private fun AttentionStrip(v: ShieldInsights.View, allow: Set<String>, deny: Set<String>, onRule: (String, String) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val rows = v.attention
    if (rows.isEmpty()) {
        Row(Modifier.padding(horizontal = 20.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            ShieldIcon(ShieldGlyph.CHECK, liftText(scheme.background, Color(0xFF2E9E5B), 3f), 18.dp, cut = scheme.background)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.rs_nothing_attention), style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
        }
        return
    }
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        rows.forEach { f -> AttentionCard(f, f.flow.host in allow, f.flow.host in deny, onRule) }
    }
}

@Composable
private fun AttentionCard(f: ShieldInsights.Facts, allowed: Boolean, denied: Boolean, onRule: (String, String) -> Unit) {
    val ctx = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    val tone = when { f.blocked -> Color(0xFFD64541); f.flagged -> Color(0xFFDE7F1F); else -> Unusual }
    val app = rememberApp(f.flow.uid)
    Column(
        Modifier.width(248.dp).clip(RoundedCornerShape(14.dp)).background(scheme.surface)
            .background(tone.copy(alpha = 0.10f)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ShieldIcon(if (f.blocked) ShieldGlyph.HAND else if (f.flagged) ShieldGlyph.FLAG else ShieldGlyph.PULSE,
                liftText(scheme.surface, tone, 3f), 18.dp)
            Spacer(Modifier.width(6.dp))
            Text(isolate(f.flow.host), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold,
                color = scheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (app != null) { Spacer(Modifier.width(6.dp)); AppIcon(app, 20.dp) }
        }
        Text(
            ShieldWords.uniqueLine(listOf(
                ShieldContext.reason(ctx, f.score?.ctx) ?: shieldLabel(ctx, f.score?.label),
                if (f.rare) ctx.getString(R.string.rs_rare) else null,
                if (f.flow.unusual) ctx.getString(R.string.rs_kind_unusual) else null,
                if (f.blocked) ctx.getString(R.string.rs_kind_blocked) else null,
                app?.name,
            )),
            style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        ShieldContextLines(ShieldContext.of(f.score, f.flow), f.flow.host, scheme.onSurface.copy(alpha = 0.78f), maxLines = 1)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SmallButton(stringResource(if (allowed) R.string.rs_trusted else R.string.rs_trust), { onRule(if (allowed) "clear" else "allow", f.flow.host) })
            SmallButton(stringResource(if (denied) R.string.rs_kind_blocked else R.string.rs_block), { onRule(if (denied) "clear" else "deny", f.flow.host) })
        }
    }
}

// ── Where in the world ──────────────────────────────────────────────────────

@Composable
private fun WorldStrip(model: TrafficModel, v: ShieldInsights.View) {
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        v.countries.forEach { (cc, n) ->
            val on = model.country == cc
            Row(
                Modifier.clip(RoundedCornerShape(50))
                    .background(if (on) Brand1.copy(alpha = 0.18f) else scheme.onSurface.copy(alpha = 0.07f))
                    .clickable(role = Role.Button) { val same = on; model.clearFocus(); if (!same) model.country = cc }
                    .padding(horizontal = 10.dp, vertical = 6.dp)
                    .semantics { contentDescription = "${ShieldWords.country(cc) ?: cc}, $n" },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(ShieldWords.flag(cc) ?: cc, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.width(4.dp))
                Text(NumberFormat.getInstance().format(n), style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold, color = scheme.onSurface)
            }
        }
    }
}

// ── Protocols ───────────────────────────────────────────────────────────────

@Composable
private fun ProtocolCard(model: TrafficModel, v: ShieldInsights.View) {
    val ctx = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    val rows = v.protocols
    val total = maxOf(1.0, rows.sumOf { it.second }.toDouble())
    fun pick(p: Proto) { val same = model.proto == p; model.clearFocus(); if (!same) model.proto = p }
    ShopSection {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.rs_protocols), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                color = scheme.onSurface, modifier = Modifier.semantics { heading() })
            Row(Modifier.fillMaxWidth().height(14.dp).clip(RoundedCornerShape(50)), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                rows.forEach { (p, b, _) ->
                    val dim = model.proto != null && model.proto != p
                    Box(Modifier.weight(maxOf(0.02f, (b / total).toFloat())).fillMaxSize()
                        .background(Color(p.color).copy(alpha = if (dim) 0.35f else 1f)).clickable { pick(p) })
                }
            }
            // Legend, two columns: "■ HTTPS 81% · 212"
            rows.chunked(2).forEach { pair ->
                Row(Modifier.fillMaxWidth()) {
                    pair.forEach { (p, b, c) ->
                        val title = if (p == Proto.OTHER && v.otherPorts.isNotEmpty())
                            "${protoTitle(ctx, p)} (${v.otherPorts.take(3).joinToString(", ")})" else protoTitle(ctx, p)
                        val pct = (b / total * 100).let { if (it < 1 && b > 0) "<1%" else "${it.toInt()}%" }
                        Row(
                            Modifier.weight(1f).clip(RoundedCornerShape(6.dp)).clickable(role = Role.Button) { pick(p) }.padding(vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(Modifier.size(10.dp).clip(RoundedCornerShape(2.dp)).background(Color(p.color)))
                            Spacer(Modifier.width(6.dp))
                            Text(title, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold,
                                color = if (p == Proto.HTTP) liftText(scheme.surface, Color(p.color)) else scheme.onSurface,
                                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                            Spacer(Modifier.width(4.dp))
                            Text("$pct · ${NumberFormat.getInstance().format(c)}", style = MaterialTheme.typography.labelSmall,
                                color = scheme.onSurfaceVariant, maxLines = 1)
                        }
                    }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }
            if (rows.any { it.first == Proto.HTTP }) Text(
                stringResource(R.string.rs_http_line, stringResource(R.string.app_name)),
                style = MaterialTheme.typography.bodySmall, color = liftText(scheme.surface, Color(Proto.HTTP.color)),
            )
            if (v.ipv6Share > 0) Text(
                stringResource(R.string.rs_ipv6_share, NumberFormat.getPercentInstance().format(v.ipv6Share)),
                style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant,
            )
        }
    }
}

// ── Filter, search, groups, rows ────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FilterBar(model: TrafficModel) {
    val ctx = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val seg = SegmentedButtonDefaults.colors(
            activeContainerColor = Brand1.copy(alpha = 0.14f), activeContentColor = scheme.onSurface,
            activeBorderColor = scheme.outline, inactiveContainerColor = Color.Transparent, inactiveContentColor = scheme.onSurfaceVariant,
        )
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            listOf(TrafficFilter.ALL to R.string.rs_filter_all, TrafficFilter.ATTENTION to R.string.rs_filter_attention,
                TrafficFilter.KNOWN to R.string.rs_filter_known).forEachIndexed { i, (k, res) ->
                SegmentedButton(selected = model.filter == k, onClick = { model.filter = k },
                    shape = SegmentedButtonDefaults.itemShape(i, 3), colors = seg, icon = {}) {
                    Text(stringResource(res), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        OutlinedTextField(
            value = model.query, onValueChange = { model.query = it }, singleLine = true,
            placeholder = { Text(stringResource(R.string.rs_search_destinations)) },
            leadingIcon = { Icon(Icons.Filled.Search, null) },
            trailingIcon = if (model.query.isNotEmpty()) ({
                IconButton(onClick = { model.query = "" }) { Icon(Icons.Filled.Close, stringResource(R.string.rs_clear_filter)) }
            }) else null,
            shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth(),
        )
        val chip = model.focus?.let { k -> if (model.byApp) k.toIntOrNull()?.let { appGroupName(ctx, it) } ?: appGroupName(ctx, null) else k }
            ?: model.proto?.let { protoTitle(ctx, it) }
            ?: model.country?.let { "${ShieldWords.flag(it) ?: ""} ${ShieldWords.country(it) ?: it}" }
        if (chip != null) Row(
            Modifier.clip(RoundedCornerShape(50)).background(Brand1.copy(alpha = 0.14f))
                .clickable(role = Role.Button) { model.clearFocus() }.padding(horizontal = 10.dp, vertical = 5.dp)
                .semantics { contentDescription = ctx.getString(R.string.rs_clear_filter) + ": " + chip },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(chip, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = scheme.onSurface)
            Spacer(Modifier.width(4.dp))
            Icon(Icons.Filled.Close, null, modifier = Modifier.size(14.dp), tint = scheme.onSurface)
        }
    }
}

@Composable
private fun GroupHeader(g: ShieldInsights.Group, byApp: Boolean, open: Boolean, onToggle: () -> Unit, onApp: (Int) -> Unit) {
    val ctx = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    val app = if (byApp) rememberApp(g.uid) else null
    Row(
        Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onToggle)
            .padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp)
            .semantics { contentDescription = ctx.getString(if (open) R.string.rs_collapse else R.string.rs_expand) + ", " + g.name },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.KeyboardArrowDown, null, tint = scheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp).rotate(if (open) 0f else -90f))
        Spacer(Modifier.width(6.dp))
        if (app != null) { AppIcon(app, 26.dp); Spacer(Modifier.width(8.dp)) }
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (byApp) g.name else isolate(g.name), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold,
                    color = scheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                g.country?.let { ShieldWords.flag(it) }?.let { Spacer(Modifier.width(4.dp)); Text(it) }
                if (g.activeRecently) {
                    Spacer(Modifier.width(6.dp))
                    Box(Modifier.size(7.dp).clip(CircleShape).background(Color(0xFF2E9E5B))
                        .semantics { contentDescription = ctx.getString(R.string.rs_active_recently) })
                }
                if (g.attention > 0) { Spacer(Modifier.width(6.dp)); ShieldPill(NumberFormat.getInstance().format(g.attention), tileColor(g.tone)) }
            }
            Text(
                ShieldWords.uniqueLine(listOf(ctx.getString(R.string.rs_summary_destinations, g.rows.size),
                    g.dominant?.let { protoTitle(ctx, it) }, ShieldWords.bytes(ctx, g.bytes))),
                style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, maxLines = 1,
            )
        }
        if (byApp && g.uid != null && g.uid >= 10_000) {
            IconButton(onClick = { onApp(g.uid) }) { Chevron() }
        }
    }
    if (open) HorizontalDivider(thickness = 0.5.dp, color = scheme.outlineVariant)
}

@Composable
private fun tagPills(f: ShieldInsights.Facts): List<Pair<String, Color>> {
    val ctx = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    return buildList {
        if (f.denied) add(ctx.getString(R.string.rs_kind_blocked) to scheme.onSurfaceVariant)
        if (f.rare) add(ctx.getString(R.string.rs_rare) to Color(0xFF1E9FA8))
        if (f.flow.unusual) add(ctx.getString(R.string.rs_kind_unusual) to Unusual)
        if (f.unencrypted) add(ctx.getString(R.string.rs_tag_unencrypted) to Color(0xFFDE7F1F))
        if (f.uploadHeavy) add(ctx.getString(R.string.rs_tag_upload_heavy) to Color(0xFFD9669B))
        if (f.longLived) add(ctx.getString(R.string.rs_tag_long_lived) to Color(0xFF5B6CC9))
        if (f.rawIp) add(ctx.getString(R.string.rs_tag_raw_ip) to Color(0xFF8E9399))
        if (f.newToday) add(ctx.getString(R.string.rs_tag_new_today) to Color(0xFF2E9E7B))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun TrafficRow(f: ShieldInsights.Facts, allowed: Boolean, divider: Boolean, showApp: Boolean, onRule: (String, String) -> Unit) {
    val ctx = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    val score = f.score
    val app = if (showApp) rememberApp(f.flow.uid) else null
    val tags = tagPills(f)
    var menu by remember { mutableStateOf(false) }
    Column {
        Row(
            Modifier.fillMaxWidth().combinedClickable(onClick = { menu = true }, onLongClick = { menu = true })
                .padding(start = 16.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (app != null) { AppIcon(app, 28.dp); Spacer(Modifier.width(10.dp)) }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(isolate(f.flow.host), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold,
                        color = scheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    // A destination with no score shows no verdict (not "unknown").
                    score?.let { Spacer(Modifier.width(6.dp)); ShieldPill(ShieldWords.verdict(ctx, it.verdict), ShieldWords.verdictColor(it.verdict)) }
                }
                if (tags.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    tags.take(4).forEach { (t, c) -> ShieldPill(t, c) }
                }
                // TCP up/down as the core counts them; UDP (QUIC) has no direction split, so it follows as a total.
                Text(
                    stringResource(R.string.rs_flow_line, f.connections, ShieldWords.bytes(ctx, f.flow.bytesDown), ShieldWords.bytes(ctx, f.flow.bytesUp)) +
                        (if (f.flow.udpBytes > 0) " · UDP " + ShieldWords.bytes(ctx, f.flow.udpBytes) else ""),
                    style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, maxLines = 1,
                )
                Text(
                    ShieldWords.uniqueLine(listOf(
                        shieldLabel(ctx, score?.label), score?.let { ShieldWords.spaceFacts(ctx, it) },
                        f.dominant?.let { protoTitle(ctx, it) },
                        if (allowed) ctx.getString(R.string.rs_allowed) else null,
                        ShieldWords.ago(System.currentTimeMillis() - f.flow.lastSecsAgo * 1000),
                    )),
                    style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant.copy(alpha = 0.85f),
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            Box {
                IconButton(onClick = { menu = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = isolate(f.flow.host), tint = scheme.onSurfaceVariant)
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    if (!f.denied) DropdownMenuItem(text = { Text(stringResource(R.string.rs_block)) }, onClick = { menu = false; onRule("deny", f.flow.host) })
                    if (!allowed) DropdownMenuItem(text = { Text(stringResource(R.string.rs_allow)) }, onClick = { menu = false; onRule("allow", f.flow.host) })
                    if (f.denied || allowed) DropdownMenuItem(text = { Text(stringResource(R.string.rs_delete)) }, onClick = { menu = false; onRule("clear", f.flow.host) })
                }
            }
        }
        if (divider) HorizontalDivider(Modifier.padding(start = 16.dp), thickness = 0.5.dp, color = scheme.outlineVariant)
    }
}

// ── Per-app page ────────────────────────────────────────────────────────────

@Composable
internal fun AppPage(viewModel: VpnViewModel, model: TrafficModel, uid: Int, onRule: (String, String) -> Unit, rulesTick: Int, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    val app = rememberApp(uid)
    val name = appGroupName(ctx, uid)
    val pkg = remember(uid) { runCatching { ctx.packageManager.getPackagesForUid(uid)?.firstOrNull() }.getOrNull() }
    val lists = remember(rulesTick) { readRules() }
    val v = model.view(ctx, lists.second.toSet())
    val rows = v.facts.filter { it.flow.uid == uid }.sortedByDescending { it.flow.totalBytes }
    var week by remember { mutableStateOf(0L) }
    var paused by remember { mutableStateOf(pkg != null && ShieldAppControls.isPaused(ctx, pkg)) }
    var outside by remember { mutableStateOf(pkg != null && viewModel.appOutsideTunnel(pkg)) }
    LaunchedEffect(pkg) {
        week = withContext(Dispatchers.IO) { ShieldInsights.week(ctx).firstOrNull { it.first == pkg }?.second ?: 0L }
    }
    val lockdown = TunnelVpnService.lockdownState.collectAsState().value == true
    // Back from the system VPN settings with a pause waiting on lockdown: rebuild
    // the tunnel, which re-reads the setting and applies the pause if it is on now.
    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(owner, paused, lockdown) {
        var left = false
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
            if (e == androidx.lifecycle.Lifecycle.Event.ON_STOP) left = true
            if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME && left && paused && !lockdown) { left = false; viewModel.applyRoutingNow() }
        }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    val appName = stringResource(R.string.app_name)
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        ShopTopBar(name, onBack)
        LazyColumn(Modifier.fillMaxSize().navigationBarsPadding()) {
            item {
                ShopSection {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (app != null) { AppIcon(app, 48.dp); Spacer(Modifier.width(14.dp)) }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = scheme.onSurface,
                                modifier = Modifier.semantics { heading() })
                            if (rows.isEmpty()) Text(stringResource(R.string.rs_app_no_traffic), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                            else Text(stringResource(R.string.rs_app_since, ShieldWords.bytes(ctx, rows.sumOf { it.flow.bytesDown }),
                                ShieldWords.bytes(ctx, rows.sumOf { it.flow.bytesUp })) +
                                rows.sumOf { it.flow.udpBytes }.let { if (it > 0) " · UDP " + ShieldWords.bytes(ctx, it) else "" },
                                style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                            if (week > 0) Text(stringResource(R.string.rs_app_week, ShieldWords.bytes(ctx, week)),
                                style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                        }
                    }
                }
            }
            if (pkg != null) item {
                ShopSection(header = stringResource(R.string.rs_app_controls, appName)) {
                    // Pause: applied by the service only while lockdown is on.
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.rs_pause_app, appName), style = MaterialTheme.typography.bodyLarge,
                                color = scheme.onSurface, modifier = Modifier.weight(1f))
                            Switch(checked = paused, colors = shieldSwitchColors(), onCheckedChange = {
                                paused = it
                                ShieldAppControls.setPaused(ctx, pkg, it)
                                if (it && outside) { outside = false; viewModel.setAppOutsideTunnel(pkg, false) } else viewModel.applyRoutingNow()
                            })
                        }
                        Text(
                            when {
                                paused && lockdown -> stringResource(R.string.rs_pause_active, appName)
                                paused -> stringResource(R.string.rs_pause_waiting, appName)
                                else -> stringResource(R.string.rs_pause_explain, appName)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (paused && !lockdown) liftText(scheme.surface, Color(0xFFDE7F1F)) else scheme.onSurfaceVariant,
                        )
                        if (!lockdown) SmallButton(stringResource(R.string.rs_open_vpn_settings), {
                            runCatching { ctx.startActivity(Intent(Settings.ACTION_VPN_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                        })
                    }
                    HorizontalDivider(Modifier.padding(start = 16.dp), thickness = 0.5.dp, color = scheme.outlineVariant)
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.rs_route_outside), style = MaterialTheme.typography.bodyLarge,
                                color = scheme.onSurface, modifier = Modifier.weight(1f))
                            Switch(checked = outside, colors = shieldSwitchColors(), onCheckedChange = {
                                outside = it
                                if (it && paused) { paused = false; ShieldAppControls.setPaused(ctx, pkg, false) }
                                viewModel.setAppOutsideTunnel(pkg, it)
                            })
                        }
                        Text(stringResource(R.string.rs_route_outside_line, RedShield.name), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                        Text(stringResource(R.string.rs_reconnect_note), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                    }
                }
            }
            if (rows.isNotEmpty()) {
                item { SectionHeader(R.string.rs_destinations) }
                item {
                    ShopSection {
                        rows.forEachIndexed { i, f -> TrafficRow(f, f.flow.host in lists.first, i < rows.lastIndex, showApp = false, onRule = onRule) }
                    }
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

// ── Ask RedShield ───────────────────────────────────────────────────────────

internal enum class ShieldQuestion(val res: Int) {
    OVERVIEW(R.string.rs_q_overview), UNUSUAL(R.string.rs_q_unusual), BLOCKED(R.string.rs_q_blocked),
    WORLD(R.string.rs_q_world), DATA(R.string.rs_q_data),
}

internal data class ShieldAnswer(val id: Int, val q: ShieldQuestion, val sentences: List<String>, val lines: List<Line>) {
    data class Line(val host: String, val text: String, val tone: Color, val uid: Int? = null)
}

/** Every answer computed on the device from the flow table, the score cache and today's refusals. */
internal object ShieldAsk {
    private val Red = Color(0xFFD64541); private val Orange = Color(0xFFDE7F1F); private val Blue = Color(0xFF4D73A8)

    fun answer(ctx: Context, id: Int, q: ShieldQuestion, flows: ShieldFlows?, cache: ShieldScoreCache, refusedToday: List<GuardStore.Row>): ShieldAnswer {
        val fl = flows?.flows.orEmpty()
        if (fl.isEmpty()) return ShieldAnswer(id, q, listOf(ctx.getString(R.string.rs_ask_still_checking, RedShield.name)), emptyList())
        val v = ShieldInsights.View(fl, cache, emptySet(), emptySet(), byApp = false) { "" }
        val apps = ShieldInsights.View(fl, cache, emptySet(), emptySet(), byApp = true) { appGroupName(ctx, it) }
        val s = mutableListOf<String>(); val l = mutableListOf<ShieldAnswer.Line>()
        when (q) {
            ShieldQuestion.OVERVIEW -> {
                s += ctx.resources.getQuantityString(R.plurals.rs_ask_overview_count, fl.size, duration(flows!!.sinceSecs), fl.size)
                // Android knows the apps: lead with them.
                val topApps = apps.groups.filter { it.uid != null && it.bytes > 0 }.take(3).map { it.name }
                if (topApps.isNotEmpty()) s += ctx.getString(R.string.rs_ask_apps, joinList(topApps))
                val top = v.groups.filter { it.bytes > 0 }.take(4).map { g ->
                    val words = g.rows.mapNotNull { ShieldWords.classWord(ctx, it.score?.cls) }.distinct().take(2)
                    if (words.isEmpty()) g.name else "${g.name} (${joinList(words)})"
                }
                if (top.isNotEmpty()) s += ctx.getString(R.string.rs_ask_overview_mostly, joinList(top))
                unusual(ctx, v, s, l, standalone = false)
                val http = v.facts.filter { it.unencrypted }
                if (http.isNotEmpty()) http.sumOf { maxOf(1, it.flow.connections) }.let { n ->
                    s += ctx.resources.getQuantityString(R.plurals.rs_ask_http, n, n, ctx.getString(R.string.app_name))
                }
                v.facts.filter { it.uploadHeavy }.take(2).forEach {
                    s += ctx.getString(R.string.rs_ask_upload_heavy, isolate(it.flow.host), ShieldWords.bytes(ctx, it.flow.bytesUp))
                }
                blocked(ctx, refusedToday, s, l, standalone = false)
                world(ctx, v, s, l, standalone = false)
            }
            ShieldQuestion.UNUSUAL -> unusual(ctx, v, s, l, standalone = true)
            ShieldQuestion.BLOCKED -> blocked(ctx, refusedToday, s, l, standalone = true)
            ShieldQuestion.WORLD -> world(ctx, v, s, l, standalone = true)
            ShieldQuestion.DATA -> {
                s += ctx.getString(R.string.rs_ask_data)
                val src = if (apps.attributed) apps.groups.filter { it.uid != null } else v.groups
                src.filter { it.bytes > 0 }.take(6).forEach { g ->
                    l += ShieldAnswer.Line("", "${g.name} · ${ShieldWords.bytes(ctx, g.bytes)}", Blue, g.uid)
                }
            }
        }
        return ShieldAnswer(id, q, s, l)
    }

    private fun unusual(ctx: Context, v: ShieldInsights.View, s: MutableList<String>, l: MutableList<ShieldAnswer.Line>, standalone: Boolean) {
        val odd = v.facts.filter { it.flow.unusual || it.flagged }
        if (odd.isEmpty()) { if (standalone) s += ctx.getString(R.string.rs_ask_nothing_unusual); return }
        s += ctx.getString(R.string.rs_ask_unusual_count, odd.size)
        odd.take(5).forEach { f ->
            val parts = mutableListOf<String?>()
            if (f.rare) parts += ctx.getString(R.string.rs_rare)
            if (f.flow.unusual) parts += ctx.getString(R.string.rs_kind_unusual)
            parts += shieldLabel(ctx, f.score?.label)
            parts += ShieldWords.country(f.score?.cc)
            parts += ShieldWords.classWord(ctx, f.score?.cls)
            if (f.flow.ports.isNotEmpty() && !f.flow.ports.all { it == 80 || it == 443 })
                parts += ctx.getString(R.string.rs_ask_port, f.flow.ports.joinToString(", "))
            AppLabels.of(ctx, f.flow.uid)?.let { parts += it.name }
            parts += ctx.getString(if (f.blocked) R.string.rs_kind_blocked else R.string.rs_not_blocked)
            l += ShieldAnswer.Line(f.flow.host, ShieldWords.uniqueLine(parts), if (f.blocked) Red else if (f.flagged) Orange else Unusual, f.flow.uid)
        }
    }

    private fun blocked(ctx: Context, today: List<GuardStore.Row>, s: MutableList<String>, l: MutableList<ShieldAnswer.Line>, standalone: Boolean) {
        if (today.isEmpty()) { if (standalone) s += ctx.getString(R.string.rs_ask_nothing_blocked); return }
        val profile = today.filter { it.reason.startsWith("profile:") }
        val threats = today.size - profile.size
        if (threats > 0) s += ctx.resources.getQuantityString(R.plurals.rs_ask_blocked_threats, threats, threats)
        if (profile.isNotEmpty()) s += ctx.resources.getQuantityString(R.plurals.rs_ask_blocked_profiles, profile.size, profile.size)
        if (standalone) today.distinctBy { it.host }.take(8).forEach { r ->
            l += ShieldAnswer.Line(r.host, ShieldWords.uniqueLine(listOf(shieldReason(ctx, r.reason), ShieldWords.ago(r.ts))), Red, r.uid)
        }
    }

    private fun world(ctx: Context, v: ShieldInsights.View, s: MutableList<String>, l: MutableList<ShieldAnswer.Line>, standalone: Boolean) {
        val by = v.countries
        if (by.isEmpty()) { if (standalone) s += ctx.getString(R.string.rs_ask_still_checking, RedShield.name); return }
        val total = by.sumOf { it.second }.toDouble()
        val major = by.take(2).filter { it.second / total >= 0.1 }
        fun name(cc: String) = ShieldWords.country(cc) ?: cc
        // One sentence: "Mostly United States and Singapore, then Germany and Seychelles."
        val lead = (if (major.isNotEmpty()) major else by.take(1)).map { name(it.first) }
        val rest = by.drop(lead.size).take(if (standalone) 4 else 2).map { name(it.first) }
        s += if (rest.isEmpty()) ctx.getString(R.string.rs_mostly, joinList(lead))
            else ctx.getString(R.string.rs_ask_world_then, joinList(lead), joinList(rest))
        if (standalone) by.take(8).forEach { (cc, n) ->
            l += ShieldAnswer.Line("", "${ShieldWords.flag(cc) ?: ""} ${name(cc)} · ${ctx.getString(R.string.rs_connections, n)}", Blue)
        }
    }

    /** "2 hours", "35 minutes" in the reader's language. */
    private fun duration(secs: Long): String {
        val s = maxOf(60L, secs)
        if (android.os.Build.VERSION.SDK_INT < 24) return "${s / 60} min"
        val fmt = android.icu.text.MeasureFormat.getInstance(Locale.getDefault(), android.icu.text.MeasureFormat.FormatWidth.WIDE)
        val m = when {
            s >= 86_400 -> android.icu.util.Measure(s / 86_400, android.icu.util.MeasureUnit.DAY)
            s >= 3_600 -> android.icu.util.Measure(s / 3_600, android.icu.util.MeasureUnit.HOUR)
            else -> android.icu.util.Measure(s / 60, android.icu.util.MeasureUnit.MINUTE)
        }
        return fmt.format(m)
    }
}

@Composable
internal fun AskPage(model: TrafficModel, live: Boolean, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    val answers = remember { mutableStateListOf<ShieldAnswer>() }
    var next by remember { mutableIntStateOf(0) }
    var detail by remember { mutableStateOf<ShieldNotice?>(null) }
    var free by remember { mutableStateOf("") }
    val list = rememberLazyListState()

    fun ask(q: ShieldQuestion) {
        scope.launch {
            model.reload()
            val today = withContext(Dispatchers.IO) {
                val midnight = java.util.Calendar.getInstance().apply {
                    set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0)
                    set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
                }.timeInMillis
                ShieldData.blocked(ctx).filter { it.refused && it.ts >= midnight }
            }
            answers += ShieldAsk.answer(ctx, next++, q, model.flows, model.cache, today)
            list.animateScrollToItem(maxOf(0, answers.lastIndex))
        }
    }
    LaunchedEffect(Unit) { if (answers.isEmpty() && live) ask(ShieldQuestion.OVERVIEW) }

    Column(Modifier.fillMaxSize().statusBarsPadding().imePadding()) {
        ShopTopBar(stringResource(R.string.rs_ask_button, stringResource(R.string.app_name)), onBack)
        if (!live) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                EmptyState(ShieldGlyph.PULSE, R.string.rs_q_overview, R.string.rs_ask_offline)
            }
        } else {
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list) {
                answers.forEach { a -> item(key = a.id) { Exchange(a) { line -> detail = ShieldNotice(ShieldNotice.Kind.FLAGGED, line.host, line.text, System.currentTimeMillis(), line.uid) } } }
            }
        }
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ShieldQuestion.entries.filter { it != ShieldQuestion.OVERVIEW || answers.isNotEmpty() }.forEach { q ->
                    Text(
                        stringResource(q.res), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold,
                        color = if (live) ShopColors.accent() else scheme.onSurfaceVariant, maxLines = 1,
                        modifier = Modifier.clip(RoundedCornerShape(50)).border(1.dp, scheme.outlineVariant, RoundedCornerShape(50))
                            .clickable(enabled = live, role = Role.Button) { ask(q) }.padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
            }
            // Hook for a server-side question (OTA redshield.ask_server); submitting sends nothing yet.
            if (ConfigManager.redshieldAskServer) OutlinedTextField(
                value = free, onValueChange = { free = it }, singleLine = true,
                placeholder = { Text(stringResource(R.string.rs_ask_free_text)) },
                shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )
        }
    }
    detail?.let { n -> NoticeDetail(n, model.cache.score(n.host)) { detail = null } }
}

@Composable
private fun Exchange(a: ShieldAnswer, onLine: (ShieldAnswer.Line) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // The question: the user's bubble, trailing.
        Row(Modifier.fillMaxWidth()) {
            Spacer(Modifier.weight(1f).width(48.dp))
            Text(stringResource(a.q.res), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold,
                color = Color.White, modifier = Modifier.clip(RoundedCornerShape(18.dp)).background(Brand1).padding(horizontal = 14.dp, vertical = 9.dp))
        }
        // The answer: a solid card, leading.
        Row(
            Modifier.padding(end = 24.dp).clip(RoundedCornerShape(18.dp)).background(scheme.surface).padding(14.dp),
            verticalAlignment = Alignment.Top,
        ) {
            ShieldIcon(ShieldGlyph.CHECK, liftText(scheme.surface, Color(0xFF2E9E5B), 3f), 20.dp)
            Spacer(Modifier.width(10.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                a.sentences.forEach { Text(it, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurface) }
                a.lines.forEach { line ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                            .then(if (line.host.isNotEmpty()) Modifier.clickable(role = Role.Button) { onLine(line) } else Modifier)
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Box(Modifier.padding(top = 6.dp).size(7.dp).clip(CircleShape).background(line.tone))
                        Spacer(Modifier.width(8.dp))
                        Column {
                            if (line.host.isNotEmpty()) Text(isolate(line.host), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold,
                                color = scheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(line.text, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}
