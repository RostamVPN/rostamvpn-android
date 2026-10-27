package com.rostamvpn.android.ui.screens

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.webkit.RenderProcessGoneDetail
import android.webkit.CookieManager
import android.util.Log
import android.graphics.Bitmap
import android.content.Intent
import android.content.ActivityNotFoundException
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.rostamvpn.android.BuildConfig
import com.rostamvpn.android.R
import com.rostamvpn.android.billing.CashierIdentity
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Passes: travel data, native. Opens on "Where are you going?", then days x
 * usage, then ONE recommended plan with two alternatives (`/v1/recommend`),
 * the full list one tap deeper. My Passes lists this device's passes with a
 * usage meter, top-up and the in-app install. The only web surface is the
 * payment page itself: a Custom Tab where a browser offers one (Google Pay
 * renders there, C11), else [CheckoutSheet]; both behind the `play` gate.
 *
 * The product is called eSIM in copy (owner, 2026-10-15; "Pass" retired).
 * Kotlin names (PassesTab, PassApi) are kept for now to limit churn.
 */
private val TRIP_DAYS = listOf(3, 7, 14, 30)
private val USAGES = listOf("maps" to R.string.tabs_usage_maps, "socials" to R.string.tabs_usage_socials, "streaming" to R.string.tabs_usage_streaming)

@Composable
internal fun PassesTab() {
    val ctx = LocalContext.current
    val deviceId = remember { CashierIdentity.cid(ctx.applicationContext) }
    var section by rememberSaveable { mutableIntStateOf(0) }
    val route by EsimNav.route.collectAsState()
    var checkout by remember { mutableStateOf<String?>(null) }
    // C6: the eSIMs this device had when checkout opened, and the purchase
    // being waited for once payment finished.
    var known by remember { mutableStateOf<Set<String>?>(null) }
    var awaiting by remember { mutableStateOf<Awaiting?>(null) }
    // C11: checkout is open in a Custom Tab. On coming back, look (quietly)
    // for the new eSIM; the tab's own returns (esimnet://install/{id}, the
    // /install app link once verified) arrive through EsimNav instead.
    var inTab by remember { mutableStateOf<TabCheckout?>(null) }
    var tabReturn by remember { mutableIntStateOf(0) }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    androidx.compose.runtime.DisposableEffect(lifecycle) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, ev ->
            if (ev == androidx.lifecycle.Lifecycle.Event.ON_RESUME && inTab != null) tabReturn++
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }
    LaunchedEffect(tabReturn) {
        val t = inTab ?: return@LaunchedEffect
        if (tabReturn == 0) return@LaunchedEffect
        inTab = null
        if (t.topupOf != null) return@LaunchedEffect      // a top-up adds no eSIM; My eSIMs refreshes on open
        // The snapshot taken at Buy may still have been in flight then.
        val before = t.before ?: known ?: return@LaunchedEffect
        for (i in 0 until 10) {
            if (i > 0) delay(3_000)
            if (awaiting != null) return@LaunchedEffect    // a tab return link got there first
            val fresh = fetchEsims(deviceId)?.let { newPurchase(it, Awaiting(null, before)) } ?: continue
            awaiting = Awaiting(fresh.orderId.ifBlank { null }, before)
            section = 1
            return@LaunchedEffect
        }
    }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    LaunchedEffect(route) {
        val r = route ?: return@LaunchedEffect
        if (r.mine) section = 1
        // /install/{order}: wait for that order and open its install sheet (C6's poll).
        if (r.orderId != null) { awaiting = Awaiting(r.orderId, known); EsimNav.consumed() }
        if (!r.mine) section = 0     // store routes are consumed by StoreFlow
    }
    var mineReload by remember { mutableIntStateOf(0) }
    var blocked by remember { mutableStateOf(false) }
    val scheme = MaterialTheme.colorScheme

    PassCache.init(ctx)
    val online = rememberOnline()
    val stale by PassCache.stale.collectAsState()
    val buy: (String, String?) -> Unit = buy@{ slug, iccid ->
        // Offline pay guard (req C-2): the page would not load, and a saved price
        // may be out of date. The Buy controls are dimmed by LocalPayOffline.
        if (!online) return@buy
        // The play flavour sells eSIM by card only where the brand opted in
        // (features.esimCheckoutOnPlay): telecom services consumed outside the
        // app. Every other brand keeps the gate.
        if (!BuildConfig.WEB_PAY_ENABLED && !BuildConfig.ESIM_CHECKOUT_ON_PLAY) blocked = true
        else {
            if (known == null) scope.launch { fetchEsims(deviceId)?.let { l -> if (known == null) known = l.map { it.iccid }.toSet() } }
            val url = passCheckoutUrl(slug, deviceId, iccid, esimLang(ctx.resources.configuration.locales[0]))
            // C11: a Custom Tab when a browser offers one (Google Pay renders
            // there), the in-app sheet otherwise. Same URL, same gate above.
            val pkg = CustomTab.provider(ctx)
            if (pkg != null && CustomTab.open(ctx, url, pkg)) inTab = TabCheckout(known, iccid)
            else checkout = url
            EsimEvents.track(deviceId, EsimEvents.PAY_SHEET_OPEN, props = mapOf("plan" to slug, "topup" to (iccid != null)))
        }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Spacer(Modifier.height(14.dp))
        Text(stringResource(R.string.tabs_passes_title), fontSize = 23.sp, fontWeight = FontWeight.ExtraBold,
            letterSpacing = (-0.5).sp, color = scheme.onBackground)
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(scheme.surfaceVariant).padding(4.dp)) {
            listOf(R.string.tabs_store, R.string.tabs_my_passes).forEachIndexed { i, label ->
                val sel = section == i
                Text(
                    stringResource(label), fontSize = 13.5.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
                    color = if (sel) scheme.onPrimary else scheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f).clip(RoundedCornerShape(9.dp))
                        .background(if (sel) scheme.primary else Color.Transparent)
                        .clickable { section = i }.padding(vertical = 8.dp),
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        if (!online || (stale && section == 0)) {
            Text(
                stringResource(if (!online) R.string.tabs_offline_pay else R.string.tabs_offline_saved),
                fontSize = 12.5.sp, color = scheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(scheme.surfaceVariant)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            )
            Spacer(Modifier.height(10.dp))
        }
        androidx.compose.runtime.CompositionLocalProvider(LocalPayOffline provides !online) {
        if (section == 0) StoreFlow(onBuy = { buy(it, null) }, route = route?.takeIf { !it.mine })
        else MyPasses(deviceId, mineReload, onBrowse = { section = 0 }, onTopup = { slug, iccid -> buy(slug, iccid) },
            focus = route?.takeIf { it.mine }, onFocused = { EsimNav.consumed() },
            awaiting = awaiting, onAwaitDone = { awaiting = null }, onList = { l -> known = l.map { it.iccid }.toSet() })
        }
    }

    checkout?.let { url ->
        CheckoutSheet(url, onClose = { checkout = null }, onInstalled = { orderId ->
            // The sheet stops the /install page loading, so site.js never
            // beacons pay_success for an in-app purchase; this row is the only one.
            EsimEvents.track(deviceId, EsimEvents.PAY_SUCCESS, props = mapOf("via" to "sheet", "order" to orderId))
            val topupOf = Uri.parse(url).getQueryParameter("iccid")
            awaiting = Awaiting(orderId, known, topupOf)
            checkout = null; section = 1
        })
    }
    if (blocked) {
        AlertDialog(
            onDismissRequest = { blocked = false },
            text = { Text(stringResource(R.string.tabs_pay_unavailable)) },
            confirmButton = { TextButton(onClick = { blocked = false }) { Text(stringResource(android.R.string.ok)) } },
        )
    }
}

// ── Store ────────────────────────────────────────────────────────────────────

@Composable
private fun StoreFlow(onBuy: (String) -> Unit, route: EsimRoute? = null) {
    var dest by remember { mutableStateOf<PassDest?>(null) }
    var numberMode by rememberSaveable { mutableStateOf(false) }
    var collection by rememberSaveable { mutableStateOf<String?>(null) }
    // A deep link (C7): /esim/{id}, /go/{id}, /number/{id}. The site's slug is
    // the catalogue id in lower case; anything else goes through search.
    LaunchedEffect(route) {
        val r = route ?: return@LaunchedEffect
        val slug = r.dest
        if (slug != null) {
            val cat = fetchCatalog()
            val d = cat?.let { it.countries + it.regions + it.popular }?.firstOrNull { it.id.equals(slug, ignoreCase = true) }
                ?: fetchSearch(slug.replace('-', ' '))?.firstOrNull()
            if (d != null) { numberMode = r.number; dest = d }
        } else if (r.collection != null) {
            dest = null; numberMode = false; collection = r.collection
        } else {
            dest = null; numberMode = false; collection = null
        }
        EsimNav.consumed()
    }
    val d = dest
    when {
        d != null && numberMode -> NumberPlanner(d, onBack = { dest = null }, onBuy = onBuy)
        d != null -> TripPlanner(d, onBack = { dest = null }, onBuy = onBuy)
        numberMode -> NumberCountryPicker(onPick = { dest = it }, onBack = { numberMode = false })
        collection != null -> CollectionView(collection!!, onPick = { dest = it }, onBack = { collection = null })
        else -> DestinationPicker(onPick = { dest = it }, onSecondNumber = { numberMode = true }, onCollection = { collection = it })
    }
}

// ── Second number ────────────────────────────────────────────────────────────

/** The second-number entry: shown under "Where are you going?". */
@Composable
private fun SecondNumberEntry(onOpen: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(scheme.surface)
            .border(1.dp, scheme.primary.copy(alpha = 0.35f), RoundedCornerShape(14.dp))
            .clickable(onClick = onOpen).padding(horizontal = 15.dp, vertical = 13.dp),
    ) {
        Text(stringResource(R.string.tabs_second_number), fontSize = 15.sp, fontWeight = FontWeight.Bold, color = scheme.onSurface)
        Spacer(Modifier.height(2.dp))
        Text(stringResource(R.string.tabs_second_number_body), fontSize = 12.5.sp, color = scheme.onSurfaceVariant)
    }
}

/** Countries whose catalogue has number plans (never hard-coded). */
@Composable
private fun NumberCountryPicker(onPick: (PassDest) -> Unit, onBack: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    var dests by remember { mutableStateOf<List<PassDest>?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    LaunchedEffect(attempt) {
        val cat = fetchCatalog() ?: run { dests = emptyList(); return@LaunchedEffect }
        val ids = numberDestinationIds(cat, if (cat.countries.any { it.hasNumbers != null }) null else fetchSitemap())
        val all = (cat.countries + cat.regions).distinctBy { it.id }
        dests = all.filter { it.id in ids }.sortedWith(compareBy({ it.kind != "country" }, { it.name }))
    }
    BackLink(stringResource(R.string.tabs_all_destinations), onBack)
    Text(stringResource(R.string.tabs_number_where), fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, color = scheme.onBackground)
    Spacer(Modifier.height(10.dp))
    val d = dests
    when {
        d == null -> Loading()
        d.isEmpty() -> RetryLine { dests = null; attempt++ }
        else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(d, key = { it.id }) { r -> DestRow(r, onPick) }
            item { Spacer(Modifier.height(12.dp)) }
        }
    }
}

/** A destination's number plans: data, minutes and SMS side by side, by days. */
@Composable
private fun NumberPlanner(dest: PassDest, onBack: () -> Unit, onBuy: (String) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    var plans by remember { mutableStateOf<List<PassPlan>?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    LaunchedEffect(dest.id, attempt) { plans = fetchPlans(dest.id)?.filter { it.isNumber } }
    val p = plans
    val dayOptions = p.orEmpty().map { it.days }.distinct().sorted()
    var days by rememberSaveable(dest.id) { mutableIntStateOf(0) }
    val chosen = if (days in dayOptions) days else dayOptions.firstOrNull() ?: 0
    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            BackLink(stringResource(R.string.tabs_all_destinations), onBack)
            Text(if (dest.kind == "country") "${dest.flag} ${dest.name}" else dest.name, fontSize = 20.sp,
                fontWeight = FontWeight.ExtraBold, color = scheme.onBackground)
            Spacer(Modifier.height(6.dp))
            Text(stringResource(R.string.tabs_number_note), fontSize = 12.5.sp, color = scheme.onSurfaceVariant)
            if (dayOptions.size > 1) {
                Spacer(Modifier.height(10.dp))
                ChipRow(dayOptions.map { it to stringResource(R.string.tabs_days, it) }, chosen) { days = it }
            }
        }
        when {
            p == null -> item { Loading() }
            p.isEmpty() -> item { RetryLine { plans = null; attempt++ } }
            else -> items(p.filter { it.days == chosen }.sortedBy { it.priceCents }, key = { it.slug }) { pl ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(scheme.surface)
                        .border(1.dp, scheme.outlineVariant, RoundedCornerShape(14.dp)).padding(15.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.tabs_number_line, planData(pl), pl.voiceMin, pl.sms), fontSize = 15.sp,
                            fontWeight = FontWeight.Bold, color = scheme.onSurface)
                        Text(stringResource(R.string.tabs_days, pl.days), fontSize = 12.5.sp, color = scheme.onSurfaceVariant)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(usd(pl.priceCents), fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, color = scheme.onSurface)
                        Spacer(Modifier.height(6.dp))
                        OutlinePill(stringResource(R.string.tabs_buy), { onBuy(pl.slug) }, Modifier.payDim())
                    }
                }
            }
        }
        item { Spacer(Modifier.height(16.dp)) }
    }
}

@Composable
private fun BackLink(text: String, onBack: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onBack).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(androidx.compose.material.icons.Icons.AutoMirrored.Filled.KeyboardArrowLeft, null,
            tint = scheme.primary, modifier = Modifier.size(20.dp))
        Text(text, fontSize = 13.5.sp, fontWeight = FontWeight.Bold, color = scheme.primary)
    }
    Spacer(Modifier.height(4.dp))
}

@Composable
private fun DestinationPicker(onPick: (PassDest) -> Unit, onSecondNumber: () -> Unit, onCollection: (String) -> Unit = {}) {
    val scheme = MaterialTheme.colorScheme
    val ctx = LocalContext.current
    val deviceId = remember { CashierIdentity.cid(ctx.applicationContext) }
    var query by rememberSaveable { mutableStateOf("") }
    var catalog by remember { mutableStateOf<PassCatalog?>(null) }
    var failed by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf<List<PassDest>?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    LaunchedEffect(attempt) { failed = false; catalog = fetchCatalog(); if (catalog == null) failed = true }
    var seasonal by remember { mutableStateOf<PassCollection?>(null) }
    LaunchedEffect(Unit) { seasonal = fetchCollections(esimLang(ctx.resources.configuration.locales[0]))?.firstOrNull() }
    LaunchedEffect(query) {
        val q = query.trim()
        if (q.length < 2) { results = null; return@LaunchedEffect }
        delay(250)   // debounce typing
        results = fetchSearch(q)
        // A settled query only (2 s without typing), so one search is one row.
        delay(2_000)
        EsimEvents.track(deviceId, EsimEvents.SEARCH, props = mapOf("q" to q.take(40), "n" to (results?.size ?: -1)))
    }

    Text(stringResource(R.string.tabs_where), fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, color = scheme.onBackground)
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = query, onValueChange = { query = it }, singleLine = true,
        placeholder = { Text(stringResource(R.string.tabs_search_hint), fontSize = 14.sp) },
        leadingIcon = { Icon(Icons.Outlined.Search, null) },
        modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp),
    )
    Spacer(Modifier.height(10.dp))
    val c = catalog
    if (c != null && query.isBlank()) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            c.regions.sortedBy { if (it.kind == "global") "" else it.name }.forEach { r ->
                // Region names only: the server's region flag is a globe, and
                // the design has no globes.
                Text(
                    r.name, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = scheme.onSurface,
                    modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(scheme.surface)
                        .border(1.dp, scheme.outlineVariant, RoundedCornerShape(999.dp))
                        .clickable { onPick(r) }.padding(horizontal = 12.dp, vertical = 7.dp),
                )
            }
        }
        Spacer(Modifier.height(10.dp))
    }
    if (query.isBlank()) {
        seasonal?.let { col ->
            CollectionBanner(col) { onCollection(col.id) }
            Spacer(Modifier.height(10.dp))
        }
        SecondNumberEntry(onSecondNumber)
        Spacer(Modifier.height(10.dp))
    }
    val rows = if (query.isNotBlank()) results.orEmpty() else c?.popular.orEmpty()
    when {
        failed && c == null -> RetryLine { attempt++ }
        c == null && query.isBlank() -> Loading()
        else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(rows, key = { it.kind + it.id }) { r -> DestRow(r, onPick) }
            item { Spacer(Modifier.height(12.dp)) }
        }
    }
}

/** The season's collection (`/v1/collections`), title and subtitle in the site's language. */
@Composable
private fun CollectionBanner(c: PassCollection, onOpen: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
            .background(Brush.horizontalGradient(listOf(scheme.primary.copy(alpha = 0.16f), scheme.tertiary.copy(alpha = 0.10f))))
            .clickable(onClick = onOpen).padding(horizontal = 15.dp, vertical = 13.dp),
    ) {
        Text(c.title, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold, color = scheme.onSurface)
        if (c.subtitle.isNotBlank()) {
            Spacer(Modifier.height(2.dp))
            Text(c.subtitle, fontSize = 12.5.sp, color = scheme.onSurfaceVariant)
        }
    }
}

/** A collection's destinations (`/v1/collections/{id}`). */
@Composable
private fun CollectionView(id: String, onPick: (PassDest) -> Unit, onBack: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val ctx = LocalContext.current
    var col by remember(id) { mutableStateOf<PassCollection?>(null) }
    var failed by remember(id) { mutableStateOf(false) }
    var attempt by remember { mutableIntStateOf(0) }
    LaunchedEffect(id, attempt) {
        failed = false
        col = fetchCollection(id, esimLang(ctx.resources.configuration.locales[0]))
        failed = col == null
    }
    BackLink(stringResource(R.string.tabs_all_destinations), onBack)
    val c = col
    when {
        c == null && !failed -> Loading()
        c == null -> RetryLine { attempt++ }
        else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                Text(c.title, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, color = scheme.onBackground)
                if (c.subtitle.isNotBlank()) Text(c.subtitle, fontSize = 13.sp, color = scheme.onSurfaceVariant)
                Spacer(Modifier.height(6.dp))
            }
            items(c.destinations, key = { it.kind + it.id }) { r -> DestRow(r, onPick) }
            item { Spacer(Modifier.height(12.dp)) }
        }
    }
}

@Composable
private fun DestRow(r: PassDest, onPick: (PassDest) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(scheme.surface)
            .border(1.dp, scheme.outlineVariant, RoundedCornerShape(14.dp)).clickable { onPick(r) }
            .padding(horizontal = 15.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (r.kind == "country") {
            Text(r.flag, fontSize = 20.sp)
            Spacer(Modifier.width(10.dp))
        }
        Text(r.name, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = scheme.onSurface, modifier = Modifier.weight(1f))
        if (r.fromCents > 0) Text(stringResource(R.string.tabs_from, usd(r.fromCents)), fontSize = 12.5.sp, color = scheme.onSurfaceVariant)
    }
}

@Composable
private fun TripPlanner(dest: PassDest, onBack: () -> Unit, onBuy: (String) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    var days by rememberSaveable { mutableIntStateOf(7) }
    var usage by rememberSaveable { mutableStateOf("socials") }
    var pick by remember { mutableStateOf<PassPick?>(null) }
    var loading by remember { mutableStateOf(true) }
    var all by remember { mutableStateOf<List<PassPlan>?>(null) }
    var showAll by remember { mutableStateOf(false) }
    var sort by rememberSaveable { mutableStateOf(PlanSort.TOTAL) }
    var attempt by remember { mutableIntStateOf(0) }
    LaunchedEffect(dest.id, days, usage, attempt) { loading = true; pick = fetchPick(dest.id, days, usage); loading = false }
    LaunchedEffect(dest.id) { all = fetchPlans(dest.id) }

    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Row(
                Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onBack).padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    androidx.compose.material.icons.Icons.AutoMirrored.Filled.KeyboardArrowLeft, null,
                    tint = scheme.primary, modifier = Modifier.size(20.dp),
                )
                Text(stringResource(R.string.tabs_all_destinations), fontSize = 13.5.sp, fontWeight = FontWeight.Bold, color = scheme.primary)
            }
            Spacer(Modifier.height(4.dp))
            Text(if (dest.kind == "country") "${dest.flag} ${dest.name}" else dest.name, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, color = scheme.onBackground)
            Spacer(Modifier.height(12.dp))
            Text(stringResource(R.string.tabs_how_long), fontSize = 13.sp, fontWeight = FontWeight.Bold, color = scheme.onSurfaceVariant)
            Spacer(Modifier.height(6.dp))
            ChipRow(TRIP_DAYS.map { it to stringResource(R.string.tabs_days, it) }, days) { days = it }
            Spacer(Modifier.height(12.dp))
            Text(stringResource(R.string.tabs_how_much), fontSize = 13.sp, fontWeight = FontWeight.Bold, color = scheme.onSurfaceVariant)
            Spacer(Modifier.height(6.dp))
            ChipRow(USAGES.map { it.first to stringResource(it.second) }, usage) { usage = it }
        }
        val p = pick
        when {
            loading -> item { Loading() }
            p == null -> item { RetryLine { attempt++ } }
            else -> {
                item {
                    Text(
                        stringResource(R.string.tabs_need, String.format(java.util.Locale.getDefault(), "%.1f", p.needGb), days),
                        fontSize = 13.sp, color = scheme.onSurfaceVariant,
                    )
                }
                item { PlanCard(p.plan, dest, recommended = true, onBuy = onBuy) }
                // From the catalogue's own plan levels, not a country list.
                all?.filter { it.isNumber }?.minByOrNull { it.priceCents }?.let { n ->
                    item { NumberRow(n, onBuy) }
                }
                listOfNotNull(p.shorter, p.longer).filter { it.slug != p.plan.slug }.forEach { alt ->
                    item(key = alt.slug) { PlanCard(alt, dest, recommended = false, onBuy = onBuy) }
                }
                item {
                    Text(
                        stringResource(if (showAll) R.string.tabs_hide_all else R.string.tabs_see_all), fontSize = 14.sp,
                        fontWeight = FontWeight.Bold, color = scheme.primary,
                        modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { showAll = !showAll }.padding(vertical = 8.dp),
                    )
                }
                if (showAll) {
                    val plans = all.orEmpty()
                    // Number plans price in a phone number, so they never win "best value".
                    val best = bestValue(plans.filter { !it.isNumber })?.slug
                    item(key = "sort") {
                        ChipRow(
                            listOf(PlanSort.TOTAL to stringResource(R.string.tabs_sort_total),
                                PlanSort.PER_GB to stringResource(R.string.tabs_sort_per_gb),
                                PlanSort.PER_DAY to stringResource(R.string.tabs_sort_per_day)),
                            sort,
                        ) { sort = it }
                    }
                    sortPlans(plans, sort).forEach { pl ->
                        item(key = "all-" + pl.slug) { PlanCard(pl, dest, recommended = false, onBuy = onBuy, bestValue = pl.slug == best) }
                    }
                }
            }
        }
        item {
            Text(stringResource(R.string.tabs_refund), fontSize = 11.5.sp, color = scheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 16.dp))
        }
    }
}

@Composable
private fun <T> ChipRow(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        options.forEach { (v, label) ->
            val sel = v == selected
            Text(
                label, fontSize = 13.5.sp, fontWeight = FontWeight.Bold,
                color = if (sel) scheme.onPrimary else scheme.onSurface,
                modifier = Modifier.clip(RoundedCornerShape(10.dp))
                    .background(if (sel) scheme.primary else scheme.surface)
                    .border(1.dp, if (sel) scheme.primary else scheme.outlineVariant, RoundedCornerShape(10.dp))
                    .clickable { onSelect(v) }.padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun planData(p: PassPlan) = if (p.unlimited) stringResource(R.string.tabs_unlimited) else dataLabel(LocalContext.current, p.dataMb)

@Composable
private fun PlanCard(p: PassPlan, dest: PassDest, recommended: Boolean, onBuy: (String) -> Unit, bestValue: Boolean = false) {
    val scheme = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(scheme.surface)
            .border(if (recommended) 1.5.dp else 1.dp, if (recommended) scheme.primary.copy(alpha = 0.6f) else scheme.outlineVariant, RoundedCornerShape(16.dp))
            .padding(15.dp),
    ) {
        if (recommended) {
            Text(stringResource(R.string.tabs_recommended), fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, color = scheme.primary)
            Spacer(Modifier.height(4.dp))
        }
        if (bestValue) {
            Text(
                stringResource(R.string.tabs_best_value), fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, color = scheme.onPrimary,
                modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(scheme.primary).padding(horizontal = 8.dp, vertical = 2.dp),
            )
            Spacer(Modifier.height(6.dp))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.tabs_plan_line, planData(p), p.days), fontSize = 17.sp, fontWeight = FontWeight.ExtraBold, color = scheme.onSurface)
                Text(dest.name, fontSize = 12.5.sp, color = scheme.onSurfaceVariant)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(usd(p.priceCents), fontSize = 17.sp, fontWeight = FontWeight.ExtraBold, color = scheme.onSurface)
                Spacer(Modifier.height(6.dp))
                Text(
                    stringResource(R.string.tabs_buy), fontSize = 13.sp, fontWeight = FontWeight.Bold,
                    color = if (recommended) scheme.onPrimary else scheme.onSurface,
                    modifier = Modifier.payDim().clip(RoundedCornerShape(10.dp))
                        .then(if (recommended) Modifier.background(scheme.primary) else Modifier.border(1.dp, scheme.outline, RoundedCornerShape(10.dp)))
                        .clickable { onBuy(p.slug) }.padding(horizontal = 16.dp, vertical = 7.dp),
                )
            }
        }
        // Only what the server says the purchase grants: `perk.vpn_days` is
        // present only while the grant is configured. Absent = no promise.
        p.vpnDays?.let { days ->
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.tabs_includes_pro_days, days, stringResource(R.string.app_name)),
                fontSize = 12.sp, color = scheme.primary, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun NumberRow(p: PassPlan, onBuy: (String) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().payDim().clip(RoundedCornerShape(12.dp)).background(scheme.surfaceVariant)
            .clickable { onBuy(p.slug) }.padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(R.string.tabs_number_row, p.voiceMin, planData(p), p.days), fontSize = 13.sp,
            color = scheme.onSurface, modifier = Modifier.weight(1f))
        Text(usd(p.priceCents), fontSize = 13.sp, fontWeight = FontWeight.Bold, color = scheme.onSurface)
    }
}

// ── My Passes ────────────────────────────────────────────────────────────────

@Composable
private fun MyPasses(
    deviceId: String, reloadKey: Int, onBrowse: () -> Unit, onTopup: (String, String) -> Unit,
    focus: EsimRoute? = null, onFocused: () -> Unit = {},
    awaiting: Awaiting? = null, onAwaitDone: () -> Unit = {}, onList: (List<Esim>) -> Unit = {},
) {
    val ctx = LocalContext.current
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    // Notifications are asked for once, and only when the first eSIM exists
    // (API 33+). Without them the alerts simply do not show.
    val notifLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { }
    val scheme = MaterialTheme.colorScheme
    var list by remember { mutableStateOf<List<Esim>?>(null) }
    var failed by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    var installFor by remember { mutableStateOf<Esim?>(null) }
    var topupFor by remember { mutableStateOf<Esim?>(null) }
    val installer = rememberEsimInstaller(R.string.tabs_installed, R.string.tabs_install_failed) {
        EsimEvents.track(deviceId, EsimEvents.INSTALL_STEP, props = mapOf("step" to "installed"))
        reload++
    }
    LaunchedEffect(reloadKey, reload) {
        failed = false
        val r = fetchEsims(deviceId)
        if (r == null) failed = true else list = r
        if (r != null) {
            onList(r)
            EsimGlanceStore.save(ctx, r)
            firstAttach(ctx, deviceId, r)
            EsimAlerts.sync(ctx, r.isNotEmpty())
            val prefs = ctx.getSharedPreferences("rostam_prefs", Context.MODE_PRIVATE)
            if (r.isNotEmpty() && EsimAlerts.available && android.os.Build.VERSION.SDK_INT >= 33 &&
                !prefs.getBoolean("esim_notif_asked", false) &&
                androidx.core.content.ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.POST_NOTIFICATIONS)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                prefs.edit().putBoolean("esim_notif_asked", true).apply()
                notifLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
    // C6: after paying, the new eSIM takes a few seconds to provision. Poll
    // 10 x 3 s and open its install sheet the moment it appears: the order
    // id from the return URL when there is one, else an ICCID this device did
    // not have before checkout. A top-up adds no eSIM: one refresh is enough.
    // Nothing new after 30 s: the list simply stays, and reloads as usual.
    LaunchedEffect(awaiting) {
        val a = awaiting ?: return@LaunchedEffect
        for (i in 0 until 10) {
            if (i > 0) delay(3_000)
            val r = fetchEsims(deviceId) ?: continue
            list = r; failed = false
            firstAttach(ctx, deviceId, r)
            if (a.topupOf != null) break
            val fresh = newPurchase(r, a)
            if (fresh != null) {
                val idx = r.indexOf(fresh)
                if (idx >= 0) listState.animateScrollToItem(idx)
                if (fresh.installable) installFor = fresh
                break
            }
        }
        onAwaitDone()
    }
    // A routed card: scroll to it, and open its reload sheet for the Reload action.
    LaunchedEffect(focus, list) {
        val f = focus ?: return@LaunchedEffect
        val l = list ?: return@LaunchedEffect
        val i = l.indexOfFirst { it.iccid == f.iccid }
        if (i >= 0) {
            listState.animateScrollToItem(i)
            if (f.reload) topupFor = l[i]
        }
        onFocused()
    }
    val l = list
    when {
        l == null && !failed -> Loading()
        l == null -> RetryLine { reload++ }
        l.isEmpty() -> Column(Modifier.fillMaxWidth().padding(top = 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(stringResource(R.string.tabs_no_passes), fontSize = 16.sp, fontWeight = FontWeight.Bold, color = scheme.onSurface)
            Spacer(Modifier.height(6.dp))
            Text(stringResource(R.string.tabs_no_passes_body), fontSize = 13.5.sp, color = scheme.onSurfaceVariant, textAlign = TextAlign.Center)
            Spacer(Modifier.height(14.dp))
            PrimaryPill(stringResource(R.string.tabs_browse), onBrowse)
        }
        else -> LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(l, key = { it.iccid }) { e ->
                PassCard(e, busy = installer.busyKey == e.iccid, onInstall = { installFor = e }, onTopup = { topupFor = e },
                    deviceId = deviceId, onBuyTopup = { slug -> onTopup(slug, e.iccid) })
            }
            item { Spacer(Modifier.height(12.dp)) }
        }
    }
    installFor?.let { e ->
        LaunchedEffect(e.iccid) { EsimEvents.track(deviceId, EsimEvents.INSTALL_STEP, e.destinationId, mapOf("step" to "sheet")) }
        InstallSheet(e, installer, onClose = { installFor = null }, onHere = {
            EsimEvents.track(deviceId, EsimEvents.INSTALL_STEP, e.destinationId, mapOf("step" to "here"))
        })
    }
    topupFor?.let { e ->
        TopupSheet(e, onClose = { topupFor = null }, onPick = { slug -> topupFor = null; onTopup(slug, e.iccid) })
    }
}

@Composable
private fun PassCard(
    e: Esim, busy: Boolean, onInstall: () -> Unit, onTopup: () -> Unit,
    deviceId: String = "", onBuyTopup: (String) -> Unit = {},
) {
    val scheme = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(scheme.surface)
            .border(1.dp, if (e.active) scheme.primary.copy(alpha = 0.36f) else scheme.outlineVariant, RoundedCornerShape(18.dp))
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(e.title.ifBlank { e.iccid.takeLast(6) }, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold,
                color = scheme.onSurface, modifier = Modifier.weight(1f))
            Text(
                stringResource(
                    when {
                        e.usageStatus == "FINISHED" -> R.string.tabs_status_used
                        e.usageStatus == "EXPIRED" -> R.string.tabs_status_expired
                        e.active -> R.string.tabs_status_active
                        else -> R.string.tabs_status_ready
                    }
                ),
                fontSize = 11.5.sp, fontWeight = FontWeight.Bold, color = if (e.active) scheme.primary else scheme.onSurfaceVariant,
                modifier = Modifier.clip(RoundedCornerShape(999.dp))
                    .background(if (e.active) scheme.primary.copy(alpha = 0.14f) else scheme.surfaceVariant)
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }
        val total = e.totalMb; val left = e.remainingMb
        if (total != null && left != null && total > 0) {
            val used = (total - left).coerceAtLeast(0)
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.tabs_used, dataLabel(LocalContext.current, used)), fontSize = 12.sp, color = scheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                Text(stringResource(R.string.tabs_left, dataLabel(LocalContext.current, left)), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = scheme.onSurface)
            }
            Spacer(Modifier.height(7.dp))
            Meter((used.toFloat() / total).coerceIn(0f, 1f), 9.dp)
        }
        // One reload state per card, from data (ESIM-INTEGRATION §2 rule 3):
        // the number block's copy and every top-up control read the same one.
        var plan by remember(e.planSlug) { mutableStateOf<PassPlan?>(null) }
        LaunchedEffect(e.planSlug) { if (e.level == null && e.planSlug.isNotBlank()) plan = fetchPlan(e.planSlug) }
        var topups by remember(e.iccid) { mutableStateOf<List<PassPlan>?>(null) }
        val cardCtx = LocalContext.current
        LaunchedEffect(e.iccid) { topups = fetchTopups(e.iccid); EsimGlanceStore.noteTopups(cardCtx, e.iccid, topups?.size) }
        val isNumber = e.looksLikeNumber || plan?.isNumber == true
        val state = cardReloadState(e, plan?.topupGraceDays, topups?.size, System.currentTimeMillis())
        RunOut(e, deviceId, topups.takeIf { canTopUp(state) }, onBuyTopup)
        if (isNumber) NumberSection(e, state, onReload = onTopup)
        // A number's top-up is its own "Reload…" button above; a data line
        // gets "Top up" only when this network can still be topped up.
        val topupPill = canTopUp(state) && !isNumber
        if (e.finished && !e.installable) {
            if (topupPill) {
                Spacer(Modifier.height(12.dp))
                OutlinePill(stringResource(R.string.tabs_topup), onTopup, Modifier.fillMaxWidth())
            }
            return@Column
        }
        if (!e.installable && !topupPill) return@Column
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            if (e.installable) {
                if (busy) Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(22.dp), color = scheme.primary, strokeWidth = 2.dp)
                } else PrimaryPill(stringResource(R.string.tabs_install), onInstall, Modifier.weight(1f))
            }
            if (topupPill) OutlinePill(stringResource(R.string.tabs_topup), onTopup, Modifier.weight(1f))
        }
    }
}

/**
 * Req C-3: "At this pace, data runs out around <date>", and one tap to the
 * cheapest top-up of THIS eSIM that covers the shortfall ("Add 2 GB · $5"),
 * straight into checkout with &iccid=. Nothing when the data lasts.
 */
@Composable
private fun RunOut(e: Esim, deviceId: String, topups: List<PassPlan>?, onBuyTopup: (String) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val f = remember(e.iccid, e.remainingMb, e.expiredAt) {
        forecast(e.totalMb, e.remainingMb, e.days, parseAiraloTime(e.expiredAt), System.currentTimeMillis())
    } ?: return
    // [topups] is null unless this line can be topped up now (PassCard).
    val pick = remember(topups, f.shortfallMb) { topups?.let { suggestTopup(it, f.shortfallMb) } }
    LaunchedEffect(e.iccid, pick?.slug) {
        if (pick != null) EsimEvents.track(deviceId, EsimEvents.TOPUP_PROMPT_SHOWN, e.destinationId,
            mapOf("iccid" to e.iccid, "plan" to pick?.slug, "short_mb" to f.shortfallMb))
    }
    Spacer(Modifier.height(10.dp))
    Text(stringResource(R.string.tabs_runout, fmtDate(f.runOutMs)), fontSize = 12.5.sp, color = scheme.onSurfaceVariant)
    pick?.let { p ->
        Spacer(Modifier.height(8.dp))
        PrimaryPill(stringResource(R.string.tabs_add_topup, planData(p), usd(p.priceCents)), {
            EsimEvents.track(deviceId, EsimEvents.TOPUP_ACCEPTED, e.destinationId, mapOf("iccid" to e.iccid, "plan" to p.slug))
            onBuyTopup(p.slug)
        }, Modifier.fillMaxWidth().payDim())
    }
}

/**
 * A number eSIM's own block (ESIM-INTEGRATION §2 rules 2, 3, 6): expiry,
 * remaining minutes and SMS, the reload state, and the user's own label for
 * the number (the API never has the number itself). Renders nothing for a
 * data-only eSIM. Plan and topups are fetched lazily per card.
 */
@Composable
private fun NumberSection(e: Esim, state: ReloadState, onReload: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val ctx = LocalContext.current
    // Usage can be missing (Airalo down): then the row's own status decides,
    // so a live line never claims it "starts when you first connect".
    val line = when (val x = expiryLine(e)) {
        is ExpiryLine.On -> stringResource(R.string.tabs_expires_on, fmtDate(x.ms))
        ExpiryLine.StartsOnConnect -> stringResource(R.string.tabs_starts_on_connect)
        ExpiryLine.Unavailable -> stringResource(R.string.tabs_expiry_unavailable)
        ExpiryLine.None -> null
    }
    if (line != null) {
        Spacer(Modifier.height(10.dp))
        Text(line, fontSize = 12.5.sp, color = scheme.onSurfaceVariant)
    }
    val rv = e.remainingVoice; val rt = e.remainingText
    if (rv != null || rt != null) {
        Text(stringResource(R.string.tabs_voice_sms_left, rv ?: 0, rt ?: 0), fontSize = 12.5.sp, color = scheme.onSurfaceVariant)
    }
    Spacer(Modifier.height(10.dp))
    when (state) {
        ReloadState.Reloadable -> PrimaryPill(stringResource(R.string.tabs_reload_keep), onReload, Modifier.fillMaxWidth())
        is ReloadState.InGrace -> PrimaryPill(stringResource(R.string.tabs_reload_within, state.daysLeft), onReload, Modifier.fillMaxWidth())
        is ReloadState.NotReloadable -> Text(
            state.endsAtMs?.let { stringResource(R.string.tabs_not_reloadable, fmtDate(it)) }
                ?: stringResource(R.string.tabs_not_reloadable_nodate),
            fontSize = 12.5.sp, color = scheme.onSurfaceVariant,
        )
        ReloadState.Gone -> Text(stringResource(R.string.tabs_number_ended), fontSize = 12.5.sp,
            fontWeight = FontWeight.Bold, color = scheme.onSurfaceVariant)
        ReloadState.Unknown -> Unit
    }
    if (state != ReloadState.Gone) NumberLabel(e.iccid, ctx)
}

/** The user's own note of the number, stored on this device only. */
@Composable
private fun NumberLabel(iccid: String, ctx: Context) {
    val scheme = MaterialTheme.colorScheme
    val prefs = remember { ctx.getSharedPreferences("rostam_prefs", Context.MODE_PRIVATE) }
    var saved by remember(iccid) { mutableStateOf(prefs.getString("esim_label_$iccid", null)) }
    var editing by remember(iccid) { mutableStateOf(false) }
    var input by remember(iccid) { mutableStateOf(saved.orEmpty()) }
    Spacer(Modifier.height(10.dp))
    val s = saved
    if (s != null && !editing) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.tabs_number_label), fontSize = 11.sp, color = scheme.onSurfaceVariant)
                Text(s, fontSize = 14.sp, fontFamily = FontFamily.Monospace, color = scheme.onSurface)
            }
            Text(stringResource(R.string.esim_copy), fontSize = 13.sp, fontWeight = FontWeight.Bold, color = scheme.primary,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable {
                    (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("number", s))
                    Toast.makeText(ctx, ctx.getString(R.string.esim_copied), Toast.LENGTH_SHORT).show()
                }.padding(8.dp))
            Text(stringResource(R.string.tabs_edit), fontSize = 13.sp, color = scheme.onSurfaceVariant,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { editing = true }.padding(8.dp))
        }
    } else {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = input, onValueChange = { input = it.take(32) }, singleLine = true,
                label = { Text(stringResource(R.string.tabs_number_label), fontSize = 12.sp) },
                placeholder = { Text(stringResource(R.string.tabs_number_label_hint), fontSize = 12.sp) },
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Phone),
                modifier = Modifier.weight(1f), shape = RoundedCornerShape(12.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.tabs_save), fontSize = 13.sp, fontWeight = FontWeight.Bold, color = scheme.primary,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable {
                    val v = input.trim()
                    prefs.edit().apply { if (v.isEmpty()) remove("esim_label_$iccid") else putString("esim_label_$iccid", v) }.apply()
                    saved = v.ifEmpty { null }; editing = false
                }.padding(8.dp))
        }
        Text(stringResource(R.string.tabs_number_label_hint), fontSize = 11.5.sp, color = scheme.onSurfaceVariant)
    }
}

internal fun fmtDate(ms: Long): String = java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM).format(java.util.Date(ms))

@Composable
internal fun Meter(progress: Float, height: Dp) {
    val scheme = MaterialTheme.colorScheme
    Box(Modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(999.dp)).background(scheme.surfaceVariant)) {
        if (progress > 0.005f) Box(
            Modifier.fillMaxWidth(progress).height(height).clip(RoundedCornerShape(999.dp))
                .background(Brush.horizontalGradient(listOf(scheme.tertiary, scheme.primary))),
        )
    }
}

/** Install on this phone where the platform allows it; QR and codes always. */
@Composable
private fun InstallSheet(e: Esim, installer: EsimInstaller, onClose: () -> Unit, onHere: () -> Unit = {}) {
    val scheme = MaterialTheme.colorScheme
    val ctx = LocalContext.current
    TabsSheet(stringResource(R.string.tabs_install_title), onClose) {
        LazyColumn(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (installer.canInstall) item {
                PrimaryPill(stringResource(R.string.tabs_install_here), { onHere(); installer.install(e.lpa, e.iccid); onClose() }, Modifier.fillMaxWidth())
            }
            item {
                Text(stringResource(R.string.tabs_install_other), fontSize = 13.sp, color = scheme.onSurfaceVariant, textAlign = TextAlign.Center)
            }
            item {
                val qr = remember(e.lpa) { qrBitmap(e.lpa, 480) }
                if (qr != null) Box(Modifier.clip(RoundedCornerShape(12.dp)).background(Color.White).padding(8.dp)) {
                    Image(qr.asImageBitmap(), contentDescription = null, modifier = Modifier.size(210.dp))
                }
            }
            item { CopyRow(stringResource(R.string.esim_smdp_label), e.smdp, ctx) }
            item { CopyRow(stringResource(R.string.esim_activation_code), e.matchingId, ctx) }
            // Some networks need the APN typed by hand (row `apn`, EsimMine.parseEsims).
            // CopyRow hides itself when it is blank.
            item { CopyRow(stringResource(R.string.esim_apn_label), e.apn, ctx) }
            item { Spacer(Modifier.height(16.dp)) }
        }
    }
}

@Composable
private fun CopyRow(label: String, value: String, ctx: Context) {
    if (value.isBlank()) return
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(scheme.surface).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, fontSize = 11.sp, color = scheme.onSurfaceVariant)
            Text(value, fontSize = 13.sp, fontFamily = FontFamily.Monospace, color = scheme.onSurface)
        }
        Text(
            stringResource(R.string.esim_copy), fontSize = 13.sp, fontWeight = FontWeight.Bold, color = scheme.primary,
            modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable {
                (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText(label, value))
                Toast.makeText(ctx, ctx.getString(R.string.esim_copied), Toast.LENGTH_SHORT).show()
            }.padding(8.dp),
        )
    }
}

@Composable
private fun TopupSheet(e: Esim, onClose: () -> Unit, onPick: (String) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    var plans by remember { mutableStateOf<List<PassPlan>?>(null) }
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(e.iccid) { plans = fetchTopups(e.iccid); failed = plans == null }
    TabsSheet(stringResource(R.string.tabs_topup), onClose) {
        val p = plans
        when {
            p == null && !failed -> Loading()
            p == null -> Text(stringResource(R.string.tabs_load_failed), color = scheme.onSurfaceVariant)
            p.isEmpty() -> Text(stringResource(R.string.tabs_topup_none), color = scheme.onSurfaceVariant, modifier = Modifier.padding(top = 16.dp))
            else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(p, key = { it.slug }) { pl ->
                    Row(
                        Modifier.fillMaxWidth().payDim().clip(RoundedCornerShape(14.dp)).background(scheme.surface)
                            .border(1.dp, scheme.outlineVariant, RoundedCornerShape(14.dp)).clickable { onPick(pl.slug) }
                            .padding(15.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.tabs_plan_line, planData(pl), pl.days), fontSize = 15.sp,
                                fontWeight = FontWeight.Bold, color = scheme.onSurface)
                            // Same rule as the plan cards: only when the top-up carries the perk.
                            pl.vpnDays?.let { days ->
                                Text(stringResource(R.string.tabs_includes_pro_days, days, stringResource(R.string.app_name)),
                                    fontSize = 12.sp, color = scheme.primary, fontWeight = FontWeight.SemiBold)
                            }
                        }
                        Text(usd(pl.priceCents), fontSize = 15.sp, fontWeight = FontWeight.ExtraBold, color = scheme.onSurface)
                    }
                }
            }
        }
    }
}

/**
 * C8 first_attach and C9's rating ask. An eSIM this device saw NOT active
 * (bought or installed here, waiting for its network) that is now active has
 * just attached for the first time: send first_attach once per ICCID, and on
 * the first one ever, ask Play for a rating once (ReviewManager.askOnce). An
 * eSIM first seen already active (restored, signed in) is only recorded, so a
 * restore never counts as an attach.
 */
private fun firstAttach(ctx: Context, deviceId: String, list: List<Esim>) {
    val prefs = ctx.getSharedPreferences("rostam_prefs", Context.MODE_PRIVATE)
    val waiting = prefs.getStringSet("esim_seen_waiting", emptySet()).orEmpty().toMutableSet()
    val attached = prefs.getStringSet("esim_attached", emptySet()).orEmpty().toMutableSet()
    val fresh = attachedNow(list, waiting, attached)
    list.filter { !it.active && !it.finished && it.iccid !in attached }.forEach { waiting += it.iccid }
    list.filter { it.active || it.finished }.forEach { attached += it.iccid; waiting -= it.iccid }
    prefs.edit().putStringSet("esim_seen_waiting", waiting).putStringSet("esim_attached", attached).apply()
    if (fresh.isEmpty()) return
    fresh.forEach { EsimEvents.track(deviceId, EsimEvents.FIRST_ATTACH, it.destinationId, mapOf("iccid" to it.iccid)) }
    ctx.findActivity()?.let { com.rostamvpn.android.ReviewManager.askOnce(it, "esim_review_asked") }
}

/** The eSIMs that were seen waiting and are now active. Pure. */
internal fun attachedNow(list: List<Esim>, waiting: Set<String>, attached: Set<String>): List<Esim> =
    list.filter { it.active && it.iccid in waiting && it.iccid !in attached }

/**
 * A purchase being waited for after payment (C6). [orderId] from the return
 * URL when known; [before] the ICCIDs the device had at checkout; [topupOf]
 * the ICCID when it was a top-up (no new eSIM will appear).
 */
internal data class Awaiting(val orderId: String?, val before: Set<String>?, val topupOf: String? = null)

/** Checkout open in a Custom Tab: what the device had, and the top-up target if any. */
internal data class TabCheckout(val before: Set<String>?, val topupOf: String?)

/** The eSIM a finished purchase produced, if it is in [list] yet. Pure. */
internal fun newPurchase(list: List<Esim>, a: Awaiting): Esim? {
    a.orderId?.let { id -> list.firstOrNull { it.orderId == id }?.let { return it } }
    val before = a.before ?: return null
    return list.firstOrNull { it.iccid !in before }
}

// ── Payment: the one web surface ─────────────────────────────────────────────

/**
 * e-sim.net's own checkout in a minimal sheet. Stays on e-sim.net and the
 * payment providers it loads; returns on `esimnet://install/...` or
 * `/install/...` (isInstallUrl), which lands on My Passes.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun CheckoutSheet(url: String, onClose: () -> Unit, onInstalled: (orderId: String?) -> Unit) {
    // Only e-sim.net's own checkout can be opened here (passCheckoutUrl is the
    // single builder). e-sim.net sells data plans and numbers, never the VPN
    // subscription, which stays on Play Billing.
    val start = Uri.parse(url)
    if (ESIM_HOST.isEmpty() || start.scheme != "https" || start.host?.lowercase() != ESIM_HOST ||
        start.pathSegments.firstOrNull() != "checkout") {
        LaunchedEffect(url) { onClose() }
        return
    }
    TabsSheet(stringResource(R.string.tabs_checkout), onClose) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { c ->
                WebView(c).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.setSupportMultipleWindows(false)
                    // Stripe 3-D Secure fails silently without third-party
                    // cookies. This WebView only (same as EsimScreen).
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest): Boolean =
                            route(view, req.url, req.isForMainFrame)

                        // API 23 (internetpro's minSdk) only calls this overload,
                        // so without it the allow-list is dead there. It is only
                        // ever called for main-frame navigations.
                        @Deprecated("API < 24")
                        override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean =
                            route(view, Uri.parse(url), true)

                        private fun route(view: WebView, u: Uri, mainFrame: Boolean): Boolean {
                            // Subframes first: Stripe's payment element lives in
                            // them, and a subframe hitting /install must not count
                            // as the purchase completing.
                            if (!mainFrame) return false
                            if (isInstallUrl(u)) { onInstalled(installOrderId(u)); return true }
                            // The site sends a finished payment to /me as well;
                            // nothing in checkout links there before paying.
                            if (isMeUrl(u)) { onInstalled(null); return true }
                            // HTTPS to e-sim.net or Stripe stays here; anything
                            // else (bank redirects, mailto:, other hosts) goes to
                            // the system, never into this sheet.
                            if (u.scheme == "https" && hostAllowed(u.host)) return false
                            try {
                                view.context.startActivity(Intent(Intent.ACTION_VIEW, u).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                            } catch (_: ActivityNotFoundException) {
                                Log.w("PassesTab", "no handler for ${u.scheme}")
                            }
                            return true
                        }

                        // A server-side 30x to /install does not always pass
                        // through shouldOverrideUrlLoading. Catch it on the way in.
                        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                            val u = url?.let(Uri::parse) ?: return
                            if (isInstallUrl(u) || isMeUrl(u)) {
                                view.stopLoading()
                                onInstalled(installOrderId(u))
                            }
                        }

                        // A dead renderer must not take the VPN app down with it.
                        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                            Log.w("PassesTab", "renderer gone (crash=${detail.didCrash()})")
                            onClose()
                            return true
                        }
                    }
                    loadUrl(url)
                }
            },
            onRelease = { it.stopLoading(); it.destroy() },
        )
    }
}

// ── Small pieces ─────────────────────────────────────────────────────────────

/** True while the device has no internet: every Buy control is dimmed (req C-2). */
private val LocalPayOffline = androidx.compose.runtime.compositionLocalOf { false }

@Composable
private fun Modifier.payDim(): Modifier = if (LocalPayOffline.current) this.then(Modifier.alpha(0.4f)) else this

/**
 * Whether the network this app uses has internet. The app is excluded from
 * its own tunnel, so this is the real carrier network, never the VPN.
 */
@Composable
private fun rememberOnline(): Boolean {
    val ctx = LocalContext.current
    val cm = remember { ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager }
    fun now() = runCatching {
        cm.activeNetwork?.let { cm.getNetworkCapabilities(it) }
            ?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    }.getOrDefault(true)
    var online by remember { mutableStateOf(now()) }
    androidx.compose.runtime.DisposableEffect(cm) {
        val cb = object : android.net.ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: android.net.Network) { online = now() }
            override fun onLost(network: android.net.Network) { online = now() }
            override fun onCapabilitiesChanged(network: android.net.Network, caps: android.net.NetworkCapabilities) { online = now() }
        }
        val req = android.net.NetworkRequest.Builder().addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET).build()
        val ok = runCatching { cm.registerNetworkCallback(req, cb) }.isSuccess
        onDispose { if (ok) runCatching { cm.unregisterNetworkCallback(cb) } }
    }
    return online
}

@Composable
internal fun PrimaryPill(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    Text(
        text, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = scheme.onPrimary, textAlign = TextAlign.Center,
        modifier = modifier.clip(RoundedCornerShape(12.dp)).background(scheme.primary).clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 11.dp),
    )
}

@Composable
internal fun OutlinePill(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    Text(
        text, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = scheme.onSurface, textAlign = TextAlign.Center,
        modifier = modifier.clip(RoundedCornerShape(12.dp)).border(1.dp, scheme.outline, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 10.dp),
    )
}

@Composable
private fun Loading() {
    Box(Modifier.fillMaxWidth().padding(top = 40.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun RetryLine(onRetry: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().padding(top = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(stringResource(R.string.tabs_load_failed), fontSize = 13.5.sp, color = scheme.onSurfaceVariant, textAlign = TextAlign.Center)
        Spacer(Modifier.height(10.dp))
        OutlinePill(stringResource(R.string.tabs_retry), onRetry)
    }
}
