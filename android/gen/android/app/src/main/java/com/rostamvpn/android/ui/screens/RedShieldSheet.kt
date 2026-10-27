package com.rostamvpn.android.ui.screens

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.rostamvpn.android.ConfigManager
import com.rostamvpn.android.GuardStore
import com.rostamvpn.android.R
import com.rostamvpn.android.RedShield
import com.rostamvpn.android.RedShieldDemo
import com.rostamvpn.android.RedShieldEngine
import com.rostamvpn.android.RedShieldProfiles
import app.netcore.NetCoreBridge
import com.rostamvpn.android.ShieldFlow
import com.rostamvpn.android.ShieldFlows
import com.rostamvpn.android.ShieldHttp
import com.rostamvpn.android.ShieldProfile
import com.rostamvpn.android.ShieldReportStore
import com.rostamvpn.android.ShieldScanItem
import com.rostamvpn.android.ShieldScanReport
import com.rostamvpn.android.ShieldScoreCache
import com.rostamvpn.android.VpnViewModel
import com.rostamvpn.android.ui.theme.Brand1
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.NumberFormat
import java.util.Locale

/**
 * The RedShield sheet (iOS GuardianSheet + GuardianTraffic + Profiles), full
 * screen over the tabs. Segments Blocked | Traffic; behind Blocked, Rules and
 * Profiles. Traffic shows this session's destinations with the cached score,
 * the app that opened each one where Android knows it, Block / Allow, and the
 * detailed Scan (only while connected; it lists exactly what it sends first).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RedShieldSheet(viewModel: VpnViewModel, connected: Boolean, startOnTraffic: Boolean, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val pages = remember { mutableStateListOf<String>() }
    var traffic by remember { mutableStateOf(startOnTraffic) }
    val model = remember { TrafficModel(ctx) }
    val profiles = remember { ProfilesModel(ctx.applicationContext) }
    val live = connected || ShieldData.demo()

    // Debug fixture: open a given screen once (src/debug RedShieldDemo.openOn).
    LaunchedEffect(Unit) {
        // Before any page reads it: the scan list and the report's app grouping
        // come from the flow table, whichever page opens first.
        model.reload()
        when (RedShieldDemo.openOn.also { RedShieldDemo.openOn = null }) {
            "traffic" -> traffic = true
            "scan" -> { traffic = true; pages += "confirm" }
            "report" -> { traffic = true; model.loadFixtureReport(); pages += "report" }
            "profiles" -> pages += "profiles"
            "rules" -> pages += "rules"
            "places" -> { pages += "profiles"; pages += "places" }
            "ask" -> { traffic = true; pages += "ask" }
            "app" -> { traffic = true; model.flows?.flows?.firstOrNull { it.uid != null && it.uid >= 10_000 }?.uid?.let { pages += "app:$it" } }
        }
        // The home card's Ask button.
        when (ShieldSheetNav.open.also { ShieldSheetNav.open = null }) {
            "ask" -> { traffic = true; pages += "ask" }
        }
    }
    var ruleTick by remember { mutableIntStateOf(0) }
    fun rule(v: String, host: String) {
        if (NetCoreBridge.isLoaded) runCatching { NetCoreBridge.setUserRule(host, v) }
        ruleTick++
        RedShieldEngine.scoreNow(ctx)
    }

    BackHandler { if (pages.isNotEmpty()) pages.removeAt(pages.lastIndex) else onClose() }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        when (pages.lastOrNull()) {
            null -> MainPage(viewModel, model, live, traffic, { traffic = it }, onClose, push = { pages += it })
            "rules" -> RulesPage(viewModel, live) { pages.removeAt(pages.lastIndex) }
            "profiles" -> ProfilesPage(viewModel, profiles, push = { pages += it }) { pages.removeAt(pages.lastIndex) }
            "places" -> PlacesPage(profiles) { pages.removeAt(pages.lastIndex) }
            "moreads" -> MoreAdsPage(viewModel, profiles) { pages.removeAt(pages.lastIndex) }
            "custom" -> CustomListPage(profiles) { pages.removeAt(pages.lastIndex) }
            "confirm" -> ScanConfirmPage(model, onCancel = { pages.removeAt(pages.lastIndex) }) {
                pages.removeAt(pages.lastIndex)
                scope.launch { if (model.scan()) { if (pages.lastOrNull() != "report") pages += "report" } }
            }
            "report" -> ReportPage(model, live, onRescan = { pages += "confirm" }) { pages.removeAt(pages.lastIndex) }
            "ask" -> AskPage(model, live) { pages.removeAt(pages.lastIndex) }
            else -> pages.lastOrNull()?.removePrefix("app:")?.toIntOrNull()?.let { uid ->
                AppPage(viewModel, model, uid, ::rule, ruleTick) { pages.removeAt(pages.lastIndex) }
            }
        }
    }
}

/** The Traffic segment's state, shared with the scan confirmation and the report. */
internal class TrafficModel(ctx: Context) {
    var flows by mutableStateOf<ShieldFlows?>(null)
    var cache by mutableStateOf(ShieldScoreCache())
    var scanning by mutableStateOf(false)
    var error by mutableStateOf<ShieldHttp.Failure?>(null)
    var report by mutableStateOf<ShieldScanReport?>(null)
    var reportAt by mutableStateOf(0L)
    private val app = ctx.applicationContext

    init { ShieldReportStore.load(app)?.let { report = it.first; reportAt = it.second } }

    /** Rare and Unusual together (the strongest local signal) on top, then busiest first. */
    val rows: List<ShieldFlow>
        get() {
            val all = flows?.flows.orEmpty()
            val strong = all.filter { it.unusual && cache.score(it.host)?.isRare == true }
            return strong + all.filter { f -> strong.none { it.host == f.host } }
        }

    /** What a Scan sends: the busiest 100, never our own API. */
    val scanList: List<ShieldFlow> get() = rows.filter { !RedShield.isOwn(it.host) }.take(100)

    suspend fun reload() = withContext(Dispatchers.IO) {
        val f = ShieldData.flows(app); val c = ShieldData.scores(app)
        val n = f?.flows?.let { fl -> com.rostamvpn.android.ShieldInsights.newToday(app, fl.map { it.host }) }.orEmpty()
        withContext(Dispatchers.Main) { flows = f; cache = c; newToday = n }
    }

    suspend fun refresh() {
        if (!ShieldData.demo()) RedShieldEngine.scoreNowAndWait(app)
        reload()
    }

    suspend fun clear() {
        if (ShieldData.demo()) RedShieldDemo.clear() else RedShieldEngine.clearAll(app)
        reload()
    }

    fun loadFixtureReport() {
        RedShieldDemo.report()?.let { report = it; reportAt = System.currentTimeMillis() - 300_000 }
    }

    /** True when a report arrived. */
    suspend fun scan(): Boolean {
        if (scanning) return false
        scanning = true; error = null
        val (rep, err) = if (ShieldData.demo()) { delay(900); RedShieldDemo.report() to null }
            else withContext(Dispatchers.IO) { ShieldHttp.scan(app, scanList) }
        scanning = false
        if (rep != null) { report = rep; reportAt = System.currentTimeMillis(); return true }
        error = err ?: ShieldHttp.Failure.FAILED
        return false
    }

    fun uidFor(host: String): Int? = flows?.flows?.firstOrNull { it.host == host }?.uid

    // ── Traffic view state (RedShieldTraffic.kt) ──
    /** Apps first where Android can tell them apart; services otherwise. */
    var byApp by mutableStateOf(com.rostamvpn.android.BuildConfig.APP_ATTRIBUTION || ShieldData.demo())
    var filter by mutableStateOf(TrafficFilter.ALL)
    var query by mutableStateOf("")
    var country by mutableStateOf<String?>(null)
    var proto by mutableStateOf<com.rostamvpn.android.ShieldInsights.Proto?>(null)
    var focus by mutableStateOf<String?>(null)
    val expanded = androidx.compose.runtime.mutableStateMapOf<String, Boolean>()
    var newToday by mutableStateOf<Set<String>>(emptySet())
}

internal enum class TrafficFilter { ALL, ATTENTION, KNOWN }

@Composable
internal fun errorText(f: ShieldHttp.Failure): String = stringResource(when (f) {
    ShieldHttp.Failure.OFFLINE -> R.string.rs_err_offline
    ShieldHttp.Failure.BUSY -> R.string.rs_err_busy
    ShieldHttp.Failure.TIMED_OUT -> R.string.rs_err_timeout
    ShieldHttp.Failure.QUOTA -> R.string.rs_err_quota
    ShieldHttp.Failure.FAILED -> R.string.rs_err_failed
})

// ── Main page ───────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainPage(
    viewModel: VpnViewModel, model: TrafficModel, live: Boolean, traffic: Boolean, onSegment: (Boolean) -> Unit,
    onClose: () -> Unit, push: (String) -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val on by viewModel.guardianOn.collectAsState()
    val rev by RedShieldEngine.revision.collectAsState()
    var rows by remember { mutableStateOf(emptyList<GuardStore.Row>()) }
    var today by remember { mutableIntStateOf(0) }
    var ruleTick by remember { mutableIntStateOf(0) }
    var refreshing by remember { mutableStateOf(false) }
    val lists = remember(ruleTick, rev) { readRules() }
    val isOn = live && on && RedShield.enabledByConfig

    LaunchedEffect(live, rev) {
        while (true) {
            val r = withContext(Dispatchers.IO) { ShieldData.blocked(ctx) to ShieldData.blockedToday(ctx) }
            rows = r.first; today = r.second
            model.reload()
            if (!live) break
            delay(5_000)
        }
    }

    fun rule(v: String, host: String) {
        if (NetCoreBridge.isLoaded) runCatching { NetCoreBridge.setUserRule(host, v) }
        ruleTick++
        RedShieldEngine.scoreNow(ctx)
    }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) {
                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.rs_done), tint = MaterialTheme.colorScheme.onBackground)
            }
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                // Brand-tinted, not the theme's secondary (purple, low contrast with its label).
                val seg = SegmentedButtonDefaults.colors(
                    activeContainerColor = Brand1.copy(alpha = 0.14f),
                    activeContentColor = MaterialTheme.colorScheme.onSurface,
                    activeBorderColor = MaterialTheme.colorScheme.outline,
                    inactiveContainerColor = Color.Transparent,
                    inactiveContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SingleChoiceSegmentedButtonRow {
                    SegmentedButton(selected = !traffic, onClick = { onSegment(false) }, shape = SegmentedButtonDefaults.itemShape(0, 2), colors = seg) {
                        Text(stringResource(R.string.rs_kind_blocked), maxLines = 1)
                    }
                    SegmentedButton(selected = traffic, onClick = { onSegment(true) }, shape = SegmentedButtonDefaults.itemShape(1, 2), colors = seg) {
                        Text(stringResource(R.string.rs_traffic), maxLines = 1)
                    }
                }
            }
            Box(Modifier.width(72.dp), contentAlignment = Alignment.Center) {
                if (traffic) TextButton(
                    onClick = { scope.launch { model.clear() } },
                    enabled = live && model.rows.isNotEmpty(),
                ) { Text(stringResource(R.string.rs_clear), color = if (live && model.rows.isNotEmpty()) ShopColors.accent() else MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }

        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = {
                scope.launch {
                    refreshing = true
                    if (traffic) model.refresh()
                    else withContext(Dispatchers.IO) { rows = ShieldData.blocked(ctx); today = ShieldData.blockedToday(ctx) }
                    refreshing = false
                }
            },
            modifier = Modifier.fillMaxSize(),
        ) {
            LazyColumn(Modifier.fillMaxSize().navigationBarsPadding()) {
                item { SummaryCard(isOn, live, on, today, model.cache) }
                if (traffic) trafficSections(ctx, model, live, lists, onRule = ::rule, onScan = { push("confirm") },
                    onReport = { push("report") }, onAsk = { push("ask") }, onApp = { push("app:$it") })
                else blockedSections(rows, lists.first.toSet(), onAllow = { rule("allow", it) }, push = push)
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}

@Composable
private fun SummaryCard(isOn: Boolean, live: Boolean, userOn: Boolean, today: Int, cache: ShieldScoreCache) {
    val scheme = MaterialTheme.colorScheme
    ShopSection {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
            ShieldTile(on = isOn, size = 40.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (isOn) RedShield.name + " · " + stringResource(R.string.rs_on) else RedShield.name,
                        style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = scheme.onSurface,
                        modifier = Modifier.semantics { heading() },
                    )
                    if (!isOn) { Spacer(Modifier.width(8.dp)); ShieldPill(stringResource(R.string.rs_off), scheme.onSurfaceVariant) }
                }
                if (isOn) Text(stringResource(R.string.rs_blocked_today, today), style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold, color = scheme.onSurface)
                Text(
                    when {
                        !live -> stringResource(R.string.rs_connect_to_turn_on, RedShield.name)
                        !userOn || !RedShield.enabledByConfig -> stringResource(R.string.rs_explain_off)
                        else -> stringResource(R.string.rs_explain_on)
                    },
                    style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant,
                )
                if (isOn) {
                    val n = cache.entries.size
                    val feed = cache.feedDate?.let { feedDate(it) }
                    Text(
                        if (feed != null) stringResource(R.string.rs_checked_list, n, feed) else stringResource(R.string.rs_checked, n),
                        style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant.copy(alpha = 0.85f),
                    )
                    // The regional map that decides country and region profiles on the phone.
                    Text(geoLine(LocalContext.current), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant.copy(alpha = 0.85f))
                }
            }
        }
    }
}

/** "2026-10-24" in the reader's own date style. */
/** "Regional data: 24 Oct 2026, 612,000 ranges", or that there is none yet (demo: fixture numbers). */
internal fun geoLine(ctx: android.content.Context): String {
    val info = if (ShieldData.demo()) RedShieldProfiles.GeoInfo("2026-10-24", 468_312, 143_905) else RedShieldProfiles.geoInfo(ctx)
        ?: return ctx.getString(R.string.rs_geo_none)
    val n = java.text.NumberFormat.getIntegerInstance().format(info.ranges)
    return ctx.getString(R.string.rs_geo_line, feedDate(info.date), n)
}

private fun feedDate(s: String): String = runCatching {
    val d = java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(s)!!
    java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM).format(d)
}.getOrDefault(s)

// ── Blocked segment ─────────────────────────────────────────────────────────

private fun LazyListScope.blockedSections(rows: List<GuardStore.Row>, allow: Set<String>, onAllow: (String) -> Unit, push: (String) -> Unit) {
    item { SectionHeader(R.string.rs_kind_blocked) }
    if (rows.isEmpty()) item {
        EmptyState(ShieldGlyph.CHECK, R.string.rs_empty_blocked_title, R.string.rs_empty_blocked)
    } else item {
        ShopSection {
            rows.forEachIndexed { i, r -> BlockedRow(r, r.host in allow, i < rows.lastIndex) { onAllow(r.host) } }
        }
    }
    item {
        ShopSection {
            ShopRow(onClick = { push("rules") }) {
                Text(stringResource(R.string.rs_rules), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
                Chevron()
            }
            ShopRow(onClick = { push("profiles") }, divider = false) {
                Text(stringResource(R.string.rs_profiles), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
                Chevron()
            }
        }
    }
}

@Composable
internal fun BlockedRow(r: GuardStore.Row, allowed: Boolean, divider: Boolean, onAllow: () -> Unit) {
    val ctx = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    val app = rememberApp(r.uid)
    ShopRow(divider = divider) {
        if (app != null) { AppIcon(app); Spacer(Modifier.width(10.dp)) }
        Column(Modifier.weight(1f)) {
            Text(isolate(r.host), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, color = scheme.onSurface,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            // Whose address it is (owner, AS, country, reverse name), then why and when.
            ShieldContextLines(r.ctx, r.host, scheme.onSurface.copy(alpha = 0.78f))
            Text(
                (ShieldContext.reason(ctx, r.ctx) ?: shieldReason(ctx, r.reason)) + " · " + r.port + " · " + ShieldWords.ago(r.ts),
                style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, maxLines = 2,
            )
        }
        Spacer(Modifier.width(8.dp))
        if (allowed) Text(stringResource(R.string.rs_allowed), style = MaterialTheme.typography.labelLarge, color = scheme.onSurfaceVariant)
        else SmallButton(stringResource(R.string.rs_allow), onAllow)
    }
}

@Composable
internal fun SmallButton(text: String, onClick: () -> Unit, enabled: Boolean = true) {
    val scheme = MaterialTheme.colorScheme
    Text(
        text,
        style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold,
        color = if (enabled) ShopColors.accentOnCard() else scheme.onSurfaceVariant,
        maxLines = 1,
        modifier = Modifier.clip(RoundedCornerShape(50))
            .background(scheme.onSurface.copy(alpha = 0.06f))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
    )
}

@Composable
internal fun SectionHeader(res: Int, vararg args: Any) {
    Text(
        stringResource(res, *args),
        style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp).semantics { heading() },
    )
}

@Composable
internal fun EmptyState(glyph: ShieldGlyph, title: Int, line: Int) {
    val scheme = MaterialTheme.colorScheme
    ShopSection {
        Column(Modifier.fillMaxWidth().padding(vertical = 20.dp, horizontal = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            ShieldIcon(glyph, scheme.onSurfaceVariant, 28.dp)
            Spacer(Modifier.height(8.dp))
            Text(stringResource(title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = scheme.onSurface)
            Spacer(Modifier.height(4.dp))
            Text(stringResource(line), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, textAlign = TextAlign.Center)
        }
    }
}

// ── Traffic segment ─────────────────────────────────────────────────────────

// The segment itself, the per-app page and Ask: RedShieldTraffic.kt.

// ── Scan confirmation ───────────────────────────────────────────────────────

@Composable
private fun ScanConfirmPage(model: TrafficModel, onCancel: () -> Unit, onSend: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val list = model.scanList
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onCancel) { Text(stringResource(R.string.rs_cancel), color = scheme.onBackground) }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onSend, enabled = list.isNotEmpty()) {
                Text(stringResource(R.string.rs_send), fontWeight = FontWeight.Bold, color = ShopColors.accent())
            }
        }
        LazyColumn(Modifier.fillMaxSize().navigationBarsPadding()) {
            item {
                ShopSection {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.rs_scan_confirm_title), style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold, color = scheme.onSurface, modifier = Modifier.semantics { heading() })
                        Text(stringResource(R.string.rs_scan_background, RedShield.name), style = MaterialTheme.typography.bodyMedium, color = scheme.onSurface)
                        Text(stringResource(R.string.rs_scan_sends, list.size), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                    }
                }
            }
            item {
                ShopSection {
                    list.forEachIndexed { i, f ->
                        ShopRow(divider = i < list.lastIndex, minHeight = 40.dp) {
                            Text(isolate(f.host), style = MaterialTheme.typography.bodyMedium, color = scheme.onSurface,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}

// ── Report ──────────────────────────────────────────────────────────────────

@Composable
private fun ReportPage(model: TrafficModel, live: Boolean, onRescan: () -> Unit, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    val rep = model.report
    var tick by remember { mutableIntStateOf(0) }
    val deny = remember(tick) { readRules().second.toSet() }
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        ShopTopBar(stringResource(R.string.rs_report), onBack)
        LazyColumn(Modifier.fillMaxSize().navigationBarsPadding()) {
            if (rep != null) {
                item {
                    ShopSection {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            val parts = mutableListOf(
                                stringResource(R.string.rs_summary_destinations, rep.total),
                                stringResource(R.string.rs_summary_flagged, rep.flagged),
                            )
                            if (rep.suspicious > 0) parts += stringResource(R.string.rs_summary_suspicious, rep.suspicious)
                            parts += stringResource(R.string.rs_summary_known, rep.known)
                            Text(parts.joinToString(" · "), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                                color = scheme.onSurface, modifier = Modifier.semantics { heading() })
                            if (model.reportAt > 0) Text(stringResource(R.string.rs_scanned_ago, ShieldWords.ago(model.reportAt)),
                                style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                SmallButton(stringResource(R.string.rs_rescan), onRescan, enabled = live && !model.scanning)
                                if (model.scanning) { Spacer(Modifier.width(10.dp)); CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) }
                                if (!live) { Spacer(Modifier.width(10.dp)); Text(stringResource(R.string.rs_scan_connected_only), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant) }
                            }
                            model.error?.let { Text(errorText(it), style = MaterialTheme.typography.bodySmall, color = Color(0xFFE08A2E)) }
                        }
                    }
                }
                for (v in listOf("blocked", "suspicious", "known", "clear")) {
                    val group = rep.items.filter { ShieldWords.rank(it.verdict) == ShieldWords.rank(v) }
                    if (group.isEmpty()) continue
                    item {
                        SectionHeader(when (v) {
                            "blocked" -> R.string.rs_kind_flagged
                            "suspicious" -> R.string.rs_suspicious
                            "known" -> R.string.rs_known_services
                            else -> R.string.rs_no_record
                        })
                    }
                    // Android knows the app behind a destination where iOS
                    // cannot: inside each verdict, group by app.
                    val byApp = group.groupBy { model.uidFor(it.host)?.let { u -> AppLabels.of(ctx, u) } }
                    val attributed = byApp.keys.any { it != null }
                    item {
                        ShopSection {
                            val keys = byApp.keys.sortedWith(compareBy({ it == null }, { it?.name }))
                            keys.forEach { appKey ->
                                if (attributed) AppGroupHeader(appKey)
                                val items = byApp.getValue(appKey)
                                items.forEachIndexed { i, item ->
                                    ReportRow(item, item.host in deny, i < items.lastIndex) {
                                        if (NetCoreBridge.isLoaded) runCatching { NetCoreBridge.setUserRule(item.host, "deny") }
                                        tick++
                                    }
                                }
                            }
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun AppGroupHeader(app: AppLabel?) {
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().background(scheme.onSurface.copy(alpha = 0.04f)).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (app != null) { AppIcon(app, 22.dp); Spacer(Modifier.width(8.dp)) }
        Text(app?.name ?: stringResource(R.string.rs_other_apps), style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold, color = scheme.onSurfaceVariant)
    }
}

@Composable
private fun ReportRow(item: ShieldScanItem, blocked: Boolean, divider: Boolean, onBlock: () -> Unit) {
    val ctx = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    ShopRow(divider = divider) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(isolate(item.host), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, color = scheme.onSurface,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                Spacer(Modifier.width(6.dp))
                ShieldPill(ShieldWords.verdict(ctx, item.verdict), ShieldWords.verdictColor(item.verdict))
            }
            if (item.ctx != null) {
                (ShieldContext.reason(ctx, item.ctx) ?: shieldLabel(ctx, item.label))?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                }
                ShieldContextLines(item.context, item.host, scheme.onSurfaceVariant)
            } else ShieldWords.facts(shieldLabel(ctx, item.label), item.service, item.asname, item.cc).takeIf { it.isNotEmpty() }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
            }
            item.insights.firstOrNull()?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurface.copy(alpha = 0.85f))
            }
        }
        if (item.verdict == "blocked" || item.verdict == "suspicious") {
            Spacer(Modifier.width(8.dp))
            if (blocked) Text(stringResource(R.string.rs_kind_blocked), style = MaterialTheme.typography.labelLarge, color = scheme.onSurfaceVariant)
            else SmallButton(stringResource(R.string.rs_block), onBlock)
        }
    }
}

// ── Rules ───────────────────────────────────────────────────────────────────

@Composable
private fun RulesPage(viewModel: VpnViewModel, live: Boolean, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    val on by viewModel.guardianOn.collectAsState()
    var tick by remember { mutableIntStateOf(0) }
    val (allow, deny) = remember(tick) { readRules() }
    var offMask by remember { mutableIntStateOf(categoryOffBits(ctx)) }
    var input by remember { mutableStateOf("") }
    fun set(host: String, v: String) {
        val h = host.trim().lowercase().removePrefix("*.").trim('.')
        if (h.isEmpty() || !NetCoreBridge.isLoaded) return
        runCatching { NetCoreBridge.setUserRule(h, v) }
        tick++
    }
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        ShopTopBar(stringResource(R.string.rs_rules), onBack)
        LazyColumn(Modifier.fillMaxSize().navigationBarsPadding()) {
            item {
                ShopSection(footer = if (!live) stringResource(R.string.rs_apply_next) else null) {
                    ShopRow(divider = false) {
                        Column(Modifier.weight(1f)) {
                            Text(RedShield.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, color = scheme.onSurface)
                            Text(stringResource(R.string.rs_master_sub), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                        }
                        Spacer(Modifier.width(10.dp))
                        Switch(
                            checked = on,
                            onCheckedChange = { v -> viewModel.setGuardianOn(v); RedShieldEngine.resync(ctx); if (v) RedShieldEngine.scoreNow(ctx) },
                            colors = shieldSwitchColors(),
                        )
                    }
                }
            }
            // Only when the OTA table is published: the client never invents category names.
            val table = ConfigManager.protectionCategories
            if (table.isNotEmpty()) item {
                ShopSection(header = stringResource(R.string.rs_categories)) {
                    val sorted = table.entries.sortedBy { it.key }
                    sorted.forEachIndexed { i, (name, bit) ->
                        ShopRow(divider = i < sorted.lastIndex) {
                            Text(shieldReason(ctx, name), style = MaterialTheme.typography.bodyLarge, color = scheme.onSurface, modifier = Modifier.weight(1f))
                            Switch(
                                checked = offMask and (1 shl bit) == 0,
                                onCheckedChange = { v ->
                                    offMask = if (v) offMask and (1 shl bit).inv() else offMask or (1 shl bit)
                                    setCategoryOffBits(ctx, offMask)
                                },
                                colors = shieldSwitchColors(),
                            )
                        }
                    }
                }
            }
            item {
                ShopSection(header = stringResource(R.string.rs_always_allow)) {
                    if (allow.isEmpty()) ShopRow(divider = false) {
                        Text(stringResource(R.string.rs_allow_empty), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                    }
                    allow.forEachIndexed { i, h -> RuleRow(h, i < allow.lastIndex) { set(h, "clear") } }
                }
            }
            item {
                ShopSection(header = stringResource(R.string.rs_always_block)) {
                    deny.forEach { h -> RuleRow(h, true) { set(h, "clear") } }
                    ShopRow(divider = false) {
                        OutlinedTextField(
                            value = input, onValueChange = { input = it }, singleLine = true,
                            placeholder = { Text("example.com") },
                            modifier = Modifier.weight(1f), shape = RoundedCornerShape(12.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        SmallButton(stringResource(R.string.rs_add), { set(input, "deny"); input = "" }, enabled = input.isNotBlank())
                    }
                }
            }
        }
    }
}

@Composable
private fun RuleRow(host: String, divider: Boolean, onRemove: () -> Unit) {
    ShopRow(divider = divider) {
        Text(isolate(host), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        IconButton(onClick = onRemove) {
            Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.rs_delete), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ── Profiles ────────────────────────────────────────────────────────────────

/** The profiles screen's state: the catalogue, which are on, busy switches, the last refusal. */
private class ProfilesModel(private val ctx: Context) {
    var catalogue by mutableStateOf(ShieldData.catalogue(ctx))
    var custom by mutableStateOf(RedShieldProfiles.custom(ctx))
    var enabled by mutableStateOf(RedShieldProfiles.enabled(ctx))
    val busy = mutableStateMapOf<String, Boolean>()
    var message by mutableStateOf<Pair<String, Int>?>(null)

    val lists get() = catalogue.filter { !it.isCountry && !it.isRegion && !it.isAds && !it.isSpace }
    val ads get() = catalogue.firstOrNull { it.id == "ads" }
    val moreAds get() = catalogue.filter { it.id.startsWith("ads:") }
    val regions get() = catalogue.filter { it.isRegion }
    val countries get() = catalogue.filter { it.isCountry }.sortedBy { countryName(it) }
    val selectedPlaces get() = (regions + countries).filter { it.id in enabled }

    suspend fun load() {
        if (ShieldData.demo()) return
        val c = withContext(Dispatchers.IO) { if (RedShield.tunnelPort() != 0) RedShieldProfiles.fetchCatalogue(ctx) else null }
        if (c != null) catalogue = c
        custom = RedShieldProfiles.custom(ctx); enabled = RedShieldProfiles.enabled(ctx)
    }

    suspend fun set(id: String, on: Boolean) {
        busy[id] = true; message = null
        // A country or region is decided by code once the regional map is on
        // the phone: no list body to download, on immediately.
        val byCode = (id.startsWith("country:") || id.startsWith("region:")) && RedShieldProfiles.hasGeo(ctx)
        if (!ShieldData.demo() && on && !byCode && !RedShieldProfiles.hasBody(ctx, id) && !id.startsWith("custom:")) {
            val err = withContext(Dispatchers.IO) {
                if (RedShield.tunnelPort() == 0) RedShieldProfiles.FetchError.OFFLINE else RedShieldProfiles.fetchBody(ctx, id)
            }
            if (err != null) {
                busy.remove(id)
                message = id to when (err) {
                    RedShieldProfiles.FetchError.OFFLINE -> R.string.rs_p_connect
                    RedShieldProfiles.FetchError.BAD_SIGNATURE -> R.string.rs_p_badsig
                    RedShieldProfiles.FetchError.FAILED -> R.string.rs_p_failed
                }
                return
            }
        }
        RedShieldProfiles.setEnabled(ctx, if (on) enabled + id else enabled - id)
        enabled = RedShieldProfiles.enabled(ctx)
        if (!ShieldData.demo()) {
            RedShieldEngine.resyncNow(ctx)
            if (RedShieldProfiles.lastError(ctx, id)?.startsWith("err:limit") == true) message = id to R.string.rs_p_limit
        }
        busy.remove(id)
    }

    fun refreshCustom() { custom = RedShieldProfiles.custom(ctx); enabled = RedShieldProfiles.enabled(ctx) }
}

private fun countryName(p: ShieldProfile): String =
    p.countryCode?.let { Locale("", it).displayCountry.ifEmpty { null } } ?: p.name

@Composable
private fun ProfilesPage(viewModel: VpnViewModel, model: ProfilesModel, push: (String) -> Unit, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    LaunchedEffect(Unit) { model.load() }
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        ShopTopBar(stringResource(R.string.rs_profiles), onBack)
        LazyColumn(Modifier.fillMaxSize().navigationBarsPadding()) {
            item {
                ShopSection(footer = stringResource(R.string.rs_country_note)) {
                    ShopRow(onClick = { push("places") }, divider = false) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.rs_places), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, color = scheme.onSurface)
                            Text(
                                model.selectedPlaces.takeIf { it.isNotEmpty() }?.joinToString(", ") { if (it.isCountry) countryName(it) else it.name }
                                    ?: stringResource(R.string.rs_none_selected),
                                style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, maxLines = 2,
                            )
                        }
                        Chevron()
                    }
                }
            }
            model.ads?.let { ads ->
                item {
                    ShopSection(footer = stringResource(R.string.rs_ads_note) + "\n" + stringResource(R.string.rs_may_break)) {
                        ProfileToggle(viewModel, model, ads, premium = true, divider = model.moreAds.isNotEmpty())
                        if (model.moreAds.isNotEmpty()) ShopRow(onClick = { push("moreads") }, divider = false) {
                            Text(stringResource(R.string.rs_more_ads), style = MaterialTheme.typography.bodyLarge, color = scheme.onSurface, modifier = Modifier.weight(1f))
                            Chevron()
                        }
                    }
                }
            }
            if (model.lists.isNotEmpty()) item {
                ShopSection(header = stringResource(R.string.rs_lists)) {
                    model.lists.forEachIndexed { i, p -> ProfileToggle(viewModel, model, p, divider = i < model.lists.lastIndex) }
                }
            }
            item {
                ShopSection(header = stringResource(R.string.rs_your_lists)) {
                    model.custom.forEach { p -> ProfileToggle(viewModel, model, p, divider = true, onRemove = {
                        RedShieldProfiles.removeCustom(ctx, p.id); model.refreshCustom()
                        RedShieldEngine.resync(ctx)
                    }) }
                    ShopRow(onClick = { push("custom") }, divider = false) {
                        Icon(Icons.Filled.Add, null, tint = scheme.onSurfaceVariant)
                        Spacer(Modifier.width(10.dp))
                        Text(stringResource(R.string.rs_custom_list), style = MaterialTheme.typography.bodyLarge, color = scheme.onSurface, modifier = Modifier.weight(1f))
                        Chevron()
                    }
                }
            }
        }
    }
}

/** "Adult content · 84,000 sites" */
@Composable
private fun profileLine(p: ShieldProfile): String {
    val n = NumberFormat.getInstance().format(p.entries)
    return p.name + " · " + stringResource(if (p.coreKind == "domains") R.string.rs_profile_sites else R.string.rs_profile_ranges, n)
}

@Composable
private fun ProfileToggle(
    viewModel: VpnViewModel, model: ProfilesModel, p: ShieldProfile, premium: Boolean = false, divider: Boolean = true,
    onRemove: (() -> Unit)? = null,
) {
    val scheme = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    val paid by viewModel.isPaid.collectAsState()
    val busy = model.busy[p.id] == true
    Column {
        ShopRow(divider = divider && model.message?.first != p.id) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(profileLine(p), style = MaterialTheme.typography.bodyLarge, color = scheme.onSurface, modifier = Modifier.weight(1f, fill = false))
                    if (premium && !paid) { Spacer(Modifier.width(8.dp)); ShieldPill(stringResource(R.string.premium), Brand1) }
                }
            }
            if (onRemove != null) IconButton(onClick = onRemove) {
                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.rs_delete), tint = scheme.onSurfaceVariant)
            }
            if (busy) CircularProgressIndicator(Modifier.size(20.dp).padding(end = 4.dp), strokeWidth = 2.dp)
            // Explicit gap, not left to the label wrapping. In English
            // "Ads & trackers (Brave lists) · 94,745 sites" wraps to two short
            // lines and never reaches the switch; the Persian translation is
            // one long bidi line that ran right up against it and read as if
            // the last word were underneath (seen on the 476 Farsi screenshots).
            // A row whose spacing depends on where the text happens to wrap is
            // only accidentally correct, and only in the language it was built in.
            Spacer(Modifier.width(12.dp))
            Switch(
                checked = p.id in model.enabled,
                enabled = !busy,
                onCheckedChange = { v ->
                    // The free tier is ad-funded: ad blocking is Premium (owner, 2026-10-25).
                    if (v && premium && !paid) viewModel.openPaywallAndLoad()
                    else scope.launch { model.set(p.id, v) }
                },
                colors = shieldSwitchColors(),
            )
        }
        model.message?.takeIf { it.first == p.id }?.let {
            Text(stringResource(it.second), style = MaterialTheme.typography.bodySmall, color = Color(0xFFE08A2E),
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 10.dp))
        }
    }
}

@Composable
private fun MoreAdsPage(viewModel: VpnViewModel, model: ProfilesModel, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        ShopTopBar(stringResource(R.string.rs_more_ads), onBack)
        LazyColumn(Modifier.fillMaxSize().navigationBarsPadding()) {
            item {
                ShopSection(footer = stringResource(R.string.rs_may_break)) {
                    model.moreAds.forEachIndexed { i, p -> ProfileToggle(viewModel, model, p, premium = true, divider = i < model.moreAds.lastIndex) }
                }
            }
        }
    }
}

/** Regions first, then a searchable flag list; selected ones as chips on top. */
@Composable
private fun PlacesPage(model: ProfilesModel, onBack: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    val q = query.trim().lowercase()
    val filtered = if (q.isEmpty()) model.countries else model.countries.filter {
        countryName(it).lowercase().contains(q) || (it.countryCode ?: "").lowercase() == q
    }
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        ShopTopBar(stringResource(R.string.rs_places), onBack)
        LazyColumn(Modifier.fillMaxSize().navigationBarsPadding()) {
            if (model.selectedPlaces.isNotEmpty()) item {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    model.selectedPlaces.forEach { p ->
                        Row(
                            Modifier.clip(RoundedCornerShape(50)).background(Brand1.copy(alpha = 0.12f))
                                .clickable(role = Role.Button) { scope.launch { model.set(p.id, false) } }
                                .padding(horizontal = 12.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text((ShieldWords.flag(p.countryCode)?.let { "$it " } ?: "") + (if (p.isCountry) countryName(p) else p.name),
                                style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = scheme.onBackground)
                            Spacer(Modifier.width(4.dp))
                            Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.rs_delete), tint = scheme.onBackground, modifier = Modifier.size(14.dp))
                        }
                    }
                }
            }
            item {
                OutlinedTextField(
                    value = query, onValueChange = { query = it }, singleLine = true,
                    leadingIcon = { Icon(Icons.Filled.Search, null) },
                    placeholder = { Text(stringResource(R.string.rs_search_countries)) },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), shape = RoundedCornerShape(14.dp),
                )
            }
            if (model.regions.isNotEmpty() && q.isEmpty()) item {
                ShopSection(header = stringResource(R.string.rs_regions)) {
                    model.regions.forEachIndexed { i, p ->
                        ShopRow(divider = i < model.regions.lastIndex) {
                            Column(Modifier.weight(1f)) {
                                Text(p.name, style = MaterialTheme.typography.bodyLarge, color = scheme.onSurface)
                                Text(stringResource(R.string.rs_profile_ranges, NumberFormat.getInstance().format(p.entries)),
                                    style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                            }
                            Switch(
                                checked = p.id in model.enabled, enabled = model.busy[p.id] != true,
                                onCheckedChange = { v -> scope.launch { model.set(p.id, v) } },
                                colors = shieldSwitchColors(),
                            )
                        }
                    }
                }
            }
            item {
                val footer = stringResource(R.string.rs_country_note) + (model.message?.let { "\n" + stringResource(it.second) } ?: "")
                ShopSection(header = stringResource(R.string.rs_countries), footer = footer) {
                    filtered.forEachIndexed { i, p ->
                        ShopRow(onClick = { scope.launch { model.set(p.id, p.id !in model.enabled) } }, divider = i < filtered.lastIndex) {
                            Text(ShieldWords.flag(p.countryCode) ?: "🏳", style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.width(12.dp))
                            Text(countryName(p), style = MaterialTheme.typography.bodyLarge, color = scheme.onSurface, modifier = Modifier.weight(1f))
                            when {
                                model.busy[p.id] == true -> CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                p.id in model.enabled -> Icon(Icons.Filled.Check, contentDescription = null, tint = ShopColors.accentOnCard())
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Import from a link (through the tunnel) or paste: hosts-file or one domain per line, capped. */
@Composable
private fun CustomListPage(model: ProfilesModel, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var text by remember { mutableStateOf("") }
    var importing by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf<Int?>(null) }
    val hosts = remember(text) { RedShieldProfiles.parseCustom(text) }
    val nf = NumberFormat.getInstance()
    val defaultName = stringResource(R.string.rs_custom_list)
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        ShopTopBar(stringResource(R.string.rs_custom_list), onBack) {
            TextButton(enabled = hosts.isNotEmpty(), onClick = {
                RedShieldProfiles.addCustom(ctx, name.trim().ifEmpty { defaultName }, hosts)
                model.refreshCustom()
                RedShieldEngine.resync(ctx)
                onBack()
            }) { Text(stringResource(R.string.rs_save), fontWeight = FontWeight.Bold, color = if (hosts.isNotEmpty()) ShopColors.accent() else scheme.onSurfaceVariant) }
        }
        LazyColumn(Modifier.fillMaxSize().navigationBarsPadding()) {
            item {
                OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, label = { Text(stringResource(R.string.rs_name)) },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), shape = RoundedCornerShape(14.dp))
            }
            item { SectionHeader(R.string.rs_import_link) }
            item {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(value = url, onValueChange = { url = it }, singleLine = true,
                        placeholder = { Text("https://example.com/hosts.txt") }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(14.dp))
                    Spacer(Modifier.width(8.dp))
                    if (importing) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                    else SmallButton(stringResource(R.string.rs_import), enabled = url.trim().startsWith("http"), onClick = {
                        scope.launch {
                            importing = true; failed = null
                            // Through the tunnel only, like every RedShield request.
                            val r = withContext(Dispatchers.IO) { ShieldHttp.request(url.trim(), timeoutMs = 20_000, maxBytes = 8_000_000) }
                            importing = false
                            when {
                                r is ShieldHttp.Result.Ok && r.code == 200 -> {
                                    text = String(r.body)
                                    if (name.isEmpty()) name = runCatching { java.net.URI(url.trim()).host }.getOrNull().orEmpty()
                                }
                                r is ShieldHttp.Result.Err && r.why == ShieldHttp.Failure.OFFLINE -> failed = R.string.rs_p_connect
                                else -> failed = R.string.rs_import_failed
                            }
                        }
                    })
                }
                failed?.let { Text(stringResource(it), style = MaterialTheme.typography.bodySmall, color = Color(0xFFE08A2E), modifier = Modifier.padding(horizontal = 20.dp)) }
            }
            item { SectionHeader(R.string.rs_paste) }
            item {
                OutlinedTextField(
                    value = text, onValueChange = { text = it },
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp).padding(horizontal = 16.dp, vertical = 6.dp), shape = RoundedCornerShape(14.dp),
                )
                Text(stringResource(R.string.rs_custom_count, nf.format(hosts.size), nf.format(RedShieldProfiles.CUSTOM_CAP)),
                    style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
            }
        }
    }
}
