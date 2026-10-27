package com.rostamvpn.android.ui.screens

import android.content.Context
import android.telephony.euicc.EuiccManager
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rostamvpn.android.BuildConfig
import com.rostamvpn.android.R
import com.rostamvpn.android.ui.theme.Brand1
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The native eSIM store, iOS ESim/ESimMarketView + ESimDestinationView +
 * ESimCollectionView + ESimNumberView over the same e-sim.net /v1. Travel
 * first: the turning globe asking where you are going, search, seasonal
 * collections when any run, then Local · Regional · Global with a helper
 * line, continent chips ("Near me" first) over an A to Z list. No "Popular"
 * list anywhere (owner): the API's popular[] is ignored.
 */

// ── Root: eSIM | My eSIMs ────────────────────────────────────────────────────

@Composable
internal fun ShopRoot(nav: ShopNav, segment: Int, onSegment: (Int) -> Unit, canPurchase: Boolean) {
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Box(Modifier.fillMaxWidth().height(56.dp), contentAlignment = Alignment.Center) {
            if (canPurchase) {
                Segmented(
                    listOf(stringResource(R.string.tab_esim), stringResource(R.string.shop_my_esims)),
                    selected = segment, onSelect = onSegment,
                )
            } else {
                Text(stringResource(R.string.shop_my_esims), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onBackground)
            }
        }
        if (segment == 0 && canPurchase) MarketList(nav, canPurchase) else MineList(nav)
    }
}

/** The system-style segmented control: one solid track, the chosen one a brand capsule. */
@Composable
internal fun Segmented(items: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier, stretch: Boolean = false) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier
            .clip(RoundedCornerShape(50))
            .background(scheme.surface)
            .border(0.5.dp, scheme.onSurface.copy(alpha = 0.08f), RoundedCornerShape(50))
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        items.forEachIndexed { i, label ->
            val on = i == selected
            Box(
                (if (stretch) Modifier.weight(1f) else Modifier.widthIn(min = 96.dp))
                    .heightIn(min = 36.dp)
                    .clip(RoundedCornerShape(50))
                    .background(if (on) Brand1 else Color.Transparent)
                    .clickable(role = Role.Tab) { onSelect(i) }
                    .semantics { this.selected = on }
                    .padding(horizontal = 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    // White on brand red is 4.54:1.
                    color = if (on) Color.White else scheme.onSurface,
                    maxLines = 1,
                )
            }
        }
    }
}

// ── Market ───────────────────────────────────────────────────────────────────

internal enum class Category(val title: Int, val helper: Int) {
    LOCAL(R.string.shop_local, R.string.shop_local_helper),
    REGIONAL(R.string.shop_regional, R.string.shop_regional_helper),
    GLOBAL(R.string.shop_global, R.string.shop_global_helper),
}

private fun PassDest.isGlobal() = kind == "global" || id == "global" || id == "world"

@Composable
private fun MarketList(nav: ShopNav, canPurchase: Boolean) {
    val ctx = LocalContext.current
    val catalog by ShopStore.catalog.collectAsState()
    val failed by ShopStore.catalogFailed.collectAsState()
    val collections by ShopStore.collections.collectAsState()
    var query by rememberSaveable { mutableStateOf("") }
    var results by remember { mutableStateOf<List<PassDest>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var category by rememberSaveable { mutableStateOf(Category.LOCAL) }
    var filter by rememberSaveable { mutableStateOf<String?>(null) }   // null | "near" | Continent.name
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        ShopStore.track(ctx, EsimEvents.LANDING_VIEW, props = mapOf("screen" to "market"))
        ShopStore.loadCatalog()
    }
    LaunchedEffect(Unit) { ShopStore.loadCollections(esimLang(ctx.resources.configuration.locales[0])) }
    LaunchedEffect(query) {
        val q = query.trim()
        if (q.length < 2) { results = emptyList(); return@LaunchedEffect }
        delay(300)
        searching = true
        val r = ShopApi.search(q)
        searching = false
        if (r != null) {
            results = r.distinctBy { it.id }
            ShopStore.track(ctx, EsimEvents.SEARCH, props = mapOf("q" to q, "n" to r.size))
        }
    }

    val countries = remember(catalog) { catalog?.countries.orEmpty().sortedBy { it.name.lowercase() } }
    val regions = catalog?.regions.orEmpty()
    val globals = regions.filter { it.isGlobal() }
    val regionals = regions.filterNot { it.isGlobal() }
    val home = Continent.homeIso(ctx)
    val homeDest = countries.firstOrNull { it.id.equals(home, ignoreCase = true) }

    LazyColumn(Modifier.fillMaxSize()) {
        item("globe") { GlobeHeader() }
        item("search") {
            SearchField(query, onChange = { query = it })
        }
        val isSearching = query.isNotBlank()
        if (isSearching) {
            val ql = query.trim().lowercase()
            val local = (countries + regions).filter { it.name.lowercase().contains(ql) || it.id.lowercase() == ql }
            // The search answer may omit prices; the catalogue's own row has them.
            val byId = (countries + regions).associateBy { it.id }
            val shown = results.ifEmpty { local }.map { byId[it.id] ?: it }
            if (shown.isEmpty()) {
                item("empty") {
                    Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        if (searching) CircularProgressIndicator(Modifier.size(24.dp)) else {
                            Icon(Icons.Filled.Search, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.height(8.dp))
                            Text(stringResource(R.string.shop_no_match, query.trim()), color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                            Text(stringResource(R.string.shop_try_nearby), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                        }
                    }
                }
            } else {
                item("results") {
                    ShopSection(header = stringResource(R.string.shop_results)) {
                        shown.forEachIndexed { i, d -> DestRow(d, canPurchase, i < shown.lastIndex) { nav.push(ShopPage.Dest(d)) } }
                    }
                }
            }
            return@LazyColumn
        }
        if (catalog == null) {
            item("loading") {
                if (failed) ErrorState(stringResource(R.string.tabs_load_failed)) { scope.launch { ShopStore.loadCatalog(force = true) } }
                else ShopSection { repeat(8) { SkeletonRow(it < 7) } }
            }
            return@LazyColumn
        }
        if (collections.isNotEmpty()) {
            item("collections") {
                Column(Modifier.padding(vertical = 8.dp)) {
                    Text(
                        stringResource(R.string.shop_collections), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 20.dp, bottom = 6.dp),
                    )
                    Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        collections.forEach { c -> CollectionCard(c) { nav.push(ShopPage.Collection(c)) } }
                    }
                }
            }
        }
        item("categories") {
            val cats = Category.entries.filter { it != Category.GLOBAL || globals.isNotEmpty() }
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Segmented(cats.map { stringResource(it.title) }, cats.indexOf(category), { category = cats[it] }, Modifier.fillMaxWidth(), stretch = true)
                Text(
                    stringResource(category.helper), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 8.dp, top = 10.dp, end = 8.dp),
                )
                if (category == Category.LOCAL) {
                    Spacer(Modifier.height(10.dp))
                    ContinentChips(filter) { filter = if (filter == it) null else it }
                }
            }
        }
        when (category) {
            Category.REGIONAL -> item("regional") {
                ShopSection { regionals.forEachIndexed { i, d -> DestRow(d, canPurchase, i < regionals.lastIndex) { nav.push(ShopPage.Dest(d)) } } }
            }
            Category.GLOBAL -> item("global") {
                ShopSection { globals.forEachIndexed { i, d -> DestRow(d, canPurchase, i < globals.lastIndex) { nav.push(ShopPage.Dest(d)) } } }
            }
            Category.LOCAL -> {
                val list = when (val f = filter) {
                    null -> countries
                    "near" -> {
                        val near = home?.let { Continent.of(it) }.orEmpty()
                        if (near.isEmpty()) countries
                        else countries.filter { !it.id.equals(home, true) && Continent.of(it.id).any { c -> c in near } }
                    }
                    else -> countries.filter { Continent.valueOf(f) in Continent.of(it.id) }
                }
                if (filter == "near" && homeDest != null) {
                    item("home") {
                        ShopSection(header = stringResource(R.string.shop_your_country)) {
                            DestRow(homeDest, canPurchase, false) { nav.push(ShopPage.Dest(homeDest)) }
                        }
                    }
                }
                val groups = list.groupBy { it.name.take(1).uppercase() }.toSortedMap()
                groups.forEach { (letter, ds) ->
                    item("L$letter") {
                        ShopSection(header = letter) { ds.forEachIndexed { i, d -> DestRow(d, canPurchase, i < ds.lastIndex) { nav.push(ShopPage.Dest(d)) } } }
                    }
                }
                // The second-line buyer is not a traveller: the number sits at
                // the end of Local, where nobody shopping for a trip trips on it.
                item("number") {
                    ShopSection {
                        ShopRow(onClick = { nav.push(ShopPage.Number) }, divider = false) {
                            NumberBadge(36.dp)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(stringResource(R.string.shop_real_us_number), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
                                Text(stringResource(R.string.shop_us_number_sub), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Chevron()
                        }
                    }
                }
            }
        }
        item("bottom") { Spacer(Modifier.height(24.dp)) }
    }
}

/** The tab's header: the question over the turning globe, fading into the page at the bottom. */
@Composable
private fun GlobeHeader() {
    val scheme = MaterialTheme.colorScheme
    Box(Modifier.fillMaxWidth().height(180.dp).padding(start = 20.dp)) {
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithContent {
                    drawContent()
                    drawRect(
                        Brush.verticalGradient(0f to Color.Black, 0.62f to Color.Black, 1f to Color.Transparent),
                        blendMode = BlendMode.DstIn,
                    )
                },
            contentAlignment = Alignment.TopEnd,
        ) {
            TravelGlobe(
                ink = scheme.onBackground,
                accent = Brand1,
                dark = ShopColors.dark(),
                modifier = Modifier.size(236.dp).offset(x = 52.dp, y = 34.dp),
            )
        }
        Text(
            stringResource(R.string.shop_where),
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold,
            color = scheme.onBackground,
            maxLines = 3,
            modifier = Modifier.align(Alignment.CenterStart).widthIn(max = 210.dp).semantics { heading() },
        )
    }
}

@Composable
private fun SearchField(query: String, onChange: (String) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .fillMaxWidth()
            .heightIn(min = 50.dp)
            .clip(RoundedCornerShape(50))
            .background(scheme.surface)
            .border(0.5.dp, scheme.onSurface.copy(alpha = 0.08f), RoundedCornerShape(50))
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Search, null, tint = scheme.onSurfaceVariant)
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f)) {
            if (query.isEmpty()) Text(stringResource(R.string.shop_search), color = scheme.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge)
            BasicTextField(
                value = query,
                onValueChange = onChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = scheme.onSurface),
                cursorBrush = SolidColor(Brand1),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, autoCorrectEnabled = false, imeAction = ImeAction.Search),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (query.isNotEmpty()) {
            Icon(
                Icons.Filled.Clear, stringResource(R.string.shop_clear), tint = scheme.onSurfaceVariant,
                modifier = Modifier.clip(RoundedCornerShape(50)).clickable { onChange("") }.padding(4.dp),
            )
        }
    }
}

/** "Near me" first, then the six continents. Tapping the chosen chip again clears the filter. */
@Composable
private fun ContinentChips(selected: String?, onPick: (String) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        val chips = listOf("near" to stringResource(R.string.shop_near_me)) + Continent.entries.map { it.name to stringResource(it.title) }
        chips.forEach { (key, label) ->
            val on = selected == key
            Row(
                Modifier
                    .heightIn(min = 34.dp)
                    .clip(RoundedCornerShape(50))
                    .background(if (on) Brand1 else scheme.surface)
                    .border(0.5.dp, scheme.onSurface.copy(alpha = 0.08f), RoundedCornerShape(50))
                    .clickable(role = Role.Button) { onPick(key) }
                    .semantics { this.selected = on }
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (key == "near") {
                    Icon(Icons.Filled.LocationOn, null, tint = if (on) Color.White else scheme.onSurface, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(5.dp))
                }
                Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = if (on) Color.White else scheme.onSurface)
            }
        }
    }
}

/** Flag (or region tile), name, and the cheapest plan. */
@Composable
internal fun DestRow(d: PassDest, showPrice: Boolean, divider: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    ShopRow(onClick = onClick, divider = divider) {
        if (d.kind == "country") FlagTile(flagOf(d.id) ?: d.flag) else RegionTile(d.id)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(d.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, color = scheme.onSurface)
            when {
                d.kind != "country" && d.countries.isNotEmpty() ->
                    Text(pluralStringResource(R.plurals.shop_countries, d.countries.size, d.countries.size), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                d.has5g -> Text("5G", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
            }
        }
        if (showPrice && d.fromCents > 0) {
            Column(horizontalAlignment = Alignment.End) {
                Text(stringResource(R.string.shop_from), style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
                Text(usd(d.fromCents), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = scheme.onSurface)
            }
            Spacer(Modifier.width(4.dp))
        }
        Chevron()
    }
}

@Composable
internal fun NumberBadge(size: androidx.compose.ui.unit.Dp) {
    Box(
        Modifier.size(size).clip(RoundedCornerShape(size * 0.28f))
            .background(Brush.linearGradient(listOf(com.rostamvpn.android.ui.theme.Accent3, Brand1))),
        contentAlignment = Alignment.Center,
    ) { Icon(Icons.Filled.Phone, null, tint = Color.White, modifier = Modifier.size(size * 0.5f)) }
}

@Composable
internal fun ErrorState(message: String, onRetry: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        Spacer(Modifier.height(12.dp))
        Text(
            stringResource(R.string.shop_try_again),
            color = ShopColors.accent(), fontWeight = FontWeight.SemiBold,
            modifier = Modifier.clip(RoundedCornerShape(50)).border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(50))
                .clickable(role = Role.Button, onClick = onRetry).padding(horizontal = 18.dp, vertical = 8.dp),
        )
    }
}

/** A seasonal collection: hero photo, title, dates. */
@Composable
private fun CollectionCard(c: PassCollection, onClick: () -> Unit) {
    Box(Modifier.size(280.dp, 160.dp).clip(RoundedCornerShape(18.dp)).clickable(onClick = onClick)) {
        RemoteImage(c.destinations.firstOrNull()?.image.orEmpty(), Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.5f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.72f))))
        Column(Modifier.align(Alignment.BottomStart).padding(14.dp)) {
            Text(c.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = Color.White, maxLines = 1)
            if (c.subtitle.isNotBlank()) Text(c.subtitle, style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.85f), maxLines = 1)
        }
    }
}

// ── Remote images (destination heroes) ───────────────────────────────────────

private object ImageCache {
    val map = object : android.util.LruCache<String, ImageBitmap>(12) {}
}

/** A photo from e-sim.net, decoded off the main thread; the card colour until it lands. */
@Composable
internal fun RemoteImage(url: String, modifier: Modifier) {
    val bmp by produceState(ImageCache.map.get(url), url) {
        if (value != null || !url.startsWith("http")) return@produceState
        value = withContext(Dispatchers.IO) {
            runCatching {
                val c = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                c.connectTimeout = 10_000; c.readTimeout = 15_000
                try {
                    if (c.responseCode != 200) null
                    else android.graphics.BitmapFactory.decodeStream(c.inputStream)?.asImageBitmap()
                } finally { c.disconnect() }
            }.getOrNull()
        }?.also { ImageCache.map.put(url, it) }
    }
    Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant)) {
        bmp?.let { Image(it, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
    }
}

// ── Destination ──────────────────────────────────────────────────────────────

private val DAY_STEPS = listOf(3, 5, 7, 10, 15, 21, 30)
private val USAGES = listOf("maps" to R.string.shop_light, "socials" to R.string.shop_social, "streaming" to R.string.shop_heavy)

/** Smallest data first, unlimited last; then shorter, then cheaper. */
internal fun byData(list: List<PassPlan>): List<PassPlan> =
    list.sortedWith(compareBy<PassPlan>({ if (it.unlimited || it.dataMb < 0) Long.MAX_VALUE else it.dataMb }, { it.days }, { it.priceCents }))

@Composable
internal fun planDataLabel(p: PassPlan): String =
    if (p.unlimited) stringResource(R.string.tabs_unlimited) else dataLabel(LocalContext.current, p.dataMb)

@Composable
internal fun daysLabel(n: Int): String = pluralStringResource(R.plurals.shop_days, n, n)

/** Whether this phone can take an eSIM at all (EuiccManager present and enabled). */
internal fun deviceSupportsEsim(ctx: Context): Boolean =
    android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P &&
        (ctx.getSystemService(Context.EUICC_SERVICE) as? EuiccManager)?.isEnabled == true

@Composable
internal fun DestinationPage(nav: ShopNav, id: String, seed: PassDest?, canPurchase: Boolean) {
    val ctx = LocalContext.current
    var loaded by remember { mutableStateOf(ShopStore.cached(id)) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var selected by remember { mutableStateOf<PassPlan?>(null) }
    var dayIndex by rememberSaveable { mutableIntStateOf(2) }
    var usage by rememberSaveable { mutableStateOf("socials") }
    var rec by remember { mutableStateOf<ShopRecommendation?>(null) }

    LaunchedEffect(reload) {
        when (val r = ShopStore.destination(ctx, id, force = reload > 0)) {
            is Shop.Ok -> { loaded = r.value; error = null }
            is Shop.Err -> if (loaded == null) error = r.message
        }
    }
    LaunchedEffect(Unit) { ShopStore.track(ctx, EsimEvents.LANDING_VIEW, id, mapOf("screen" to "destination")) }
    val d = loaded?.first ?: seed
    val plans = loaded?.second.orEmpty()
    val dataPlans = remember(plans) { byData(plans.filterNot { it.isNumber }) }
    val numberPlans = remember(plans) { byData(plans.filter { it.isNumber }) }
    val chosen = selected ?: dataPlans.firstOrNull() ?: numberPlans.firstOrNull()
    val days = DAY_STEPS[dayIndex]
    LaunchedEffect(days, usage, loaded != null) {
        if (loaded == null || dataPlans.isEmpty()) return@LaunchedEffect
        delay(150)
        rec = when (val r = ShopApi.recommend(ctx, id, days, usage)) {
            is Shop.Ok -> r.value.let { x -> x.copy(plan = plans.firstOrNull { it.slug == x.plan.slug } ?: x.plan) }
            is Shop.Err -> null
        }
        rec?.let { ShopStore.track(ctx, "plan_recommended", id, mapOf("plan" to it.plan.slug, "days" to days, "usage" to usage)) }
    }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        ShopTopBar(d?.name ?: id, onBack = { nav.pop() })
        Box(Modifier.weight(1f)) {
            LazyColumn(Modifier.fillMaxSize()) {
                item("hero") { if (d != null) DestHero(d, plans, canPurchase) }
                when {
                    loaded == null && error != null -> item("err") { ErrorState(error!!) { reload++ } }
                    loaded == null -> item("sk") { ShopSection { repeat(5) { SkeletonRow(it < 4) } } }
                    canPurchase -> {
                        if (dataPlans.isNotEmpty()) item("data") {
                            ShopSection(stringResource(R.string.shop_data_plans), stringResource(R.string.shop_data_only)) {
                                dataPlans.forEachIndexed { i, p -> PlanRow(p, chosen?.slug == p.slug, i < dataPlans.lastIndex) { selected = p } }
                            }
                        }
                        if (numberPlans.isNotEmpty()) item("numbers") {
                            ShopSection(stringResource(R.string.shop_with_number), stringResource(R.string.shop_with_number_sub)) {
                                numberPlans.forEachIndexed { i, p -> PlanRow(p, chosen?.slug == p.slug, i < numberPlans.lastIndex) { selected = p } }
                            }
                        }
                        if (dataPlans.isNotEmpty()) item("trip") {
                            ShopSection(stringResource(R.string.shop_your_trip)) {
                                ShopRow {
                                    Icon(Icons.Filled.DateRange, null, tint = ShopColors.accentOnCard())
                                    Spacer(Modifier.width(10.dp))
                                    Text(stringResource(R.string.shop_trip_length), Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurface)
                                    Stepper(daysLabel(days), dayIndex > 0, dayIndex < DAY_STEPS.lastIndex,
                                        onMinus = { dayIndex--; ShopStore.track(ctx, "plan_changed", id, mapOf("days" to DAY_STEPS[dayIndex])) },
                                        onPlus = { dayIndex++; ShopStore.track(ctx, "plan_changed", id, mapOf("days" to DAY_STEPS[dayIndex])) })
                                }
                                ShopRow {
                                    Column(Modifier.fillMaxWidth()) {
                                        Text(stringResource(R.string.shop_how_you_use), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Spacer(Modifier.height(6.dp))
                                        Segmented(USAGES.map { stringResource(it.second) }, USAGES.indexOfFirst { it.first == usage }, { usage = USAGES[it].first }, Modifier.fillMaxWidth(), stretch = true)
                                    }
                                }
                                val r = rec
                                if (r != null) {
                                    ShopRow(onClick = { selected = r.plan }, divider = false) {
                                        Column(Modifier.fillMaxWidth()) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Icon(Icons.Filled.Star, null, tint = ShopColors.accentOnCard(), modifier = Modifier.size(14.dp))
                                                Spacer(Modifier.width(4.dp))
                                                Text(stringResource(R.string.shop_recommended_for, daysLabel(days)), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = ShopColors.accentOnCard())
                                            }
                                            Spacer(Modifier.height(6.dp))
                                            PlanLine(r.plan, chosen?.slug == r.plan.slug, featured = true)
                                        }
                                    }
                                } else SkeletonRow(false)
                            }
                        }
                        item("info") {
                            ShopSection(footer = stringResource(R.string.shop_delivered)) {
                                InfoLine(Icons.Filled.Info, stringResource(if (deviceSupportsEsim(ctx)) R.string.shop_supports_esim else R.string.shop_no_esim_support))
                                InfoLine(Icons.Filled.LocationOn, stringResource(R.string.shop_install_now), divider = false)
                            }
                        }
                    }
                }
                item("pad") { Spacer(Modifier.height(96.dp)) }
            }
            // The chosen package and its price, pinned over the list.
            if (canPurchase && loaded != null && chosen != null) {
                Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Transparent, MaterialTheme.colorScheme.background))).padding(horizontal = 16.dp, vertical = 10.dp)) {
                    ShopPrimaryButton(onClick = { nav.push(ShopPage.Checkout(chosen, d)) }) {
                        Text(stringResource(R.string.shop_buy_now))
                        Spacer(Modifier.weight(1f))
                        Text("${planDataLabel(chosen)} · ${daysLabel(chosen.days)} · ${usd(chosen.priceCents)}", maxLines = 1, style = MaterialTheme.typography.titleSmall.copy(color = Color.White))
                    }
                }
            }
        }
    }
}

@Composable
private fun DestHero(d: PassDest, plans: List<PassPlan>, canPurchase: Boolean) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Box(Modifier.fillMaxWidth().height(190.dp).clip(RoundedCornerShape(16.dp))) {
            RemoteImage(d.image, Modifier.fillMaxSize())
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.5f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.7f))))
            Row(Modifier.align(Alignment.BottomStart).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (d.kind == "country") flagOf(d.id) ?: d.flag else d.flag, fontSize = 34.sp)
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(d.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = Color.White)
                    val from = plans.minOfOrNull { it.priceCents } ?: d.fromCents
                    if (canPurchase && from > 0) Text(stringResource(R.string.shop_plans_from, usd(from)), style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.85f))
                }
            }
        }
        if (d.networks.isNotEmpty() || d.has5g || d.kind != "country") {
            Row(Modifier.padding(top = 10.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (d.has5g) Badge("5G", strong = true)
                if (d.kind != "country" && d.countries.isNotEmpty()) Badge(pluralStringResource(R.plurals.shop_countries, d.countries.size, d.countries.size), false)
                d.networks.take(8).forEach { (n, t) -> Badge(n + if (t.isEmpty()) "" else " · " + t.joinToString("/"), false) }
            }
        }
    }
}

@Composable
private fun Badge(text: String, strong: Boolean) {
    Text(
        text, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold,
        color = if (strong) Color.White else MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(if (strong) Brand1 else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)).padding(horizontal = 10.dp, vertical = 5.dp),
    )
}

@Composable
private fun Stepper(value: String, canMinus: Boolean, canPlus: Boolean, onMinus: () -> Unit, onPlus: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically) {
        StepButton("−", canMinus, onMinus)
        Text(value, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, color = scheme.onSurface, modifier = Modifier.padding(horizontal = 10.dp))
        StepButton("+", canPlus, onPlus)
    }
}

@Composable
private fun StepButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(34.dp).clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 0.08f else 0.03f))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(label, fontSize = 20.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else 0.35f)) }
}

@Composable
private fun InfoLine(icon: ImageVector, text: String, divider: Boolean = true) {
    ShopRow(divider = divider, minHeight = 44.dp) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** A plan as a choice row. */
@Composable
internal fun PlanRow(p: PassPlan, selected: Boolean, divider: Boolean, onClick: () -> Unit) {
    ShopRow(onClick = onClick, divider = divider) { PlanLine(p, selected, featured = false) }
}

@Composable
internal fun PlanLine(p: PassPlan, selected: Boolean?, featured: Boolean) {
    val scheme = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(planDataLabel(p), style = if (featured) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = scheme.onSurface)
                Text("  ·  ", color = scheme.onSurfaceVariant)
                Text(daysLabel(p.days), style = MaterialTheme.typography.bodyLarge, color = scheme.onSurfaceVariant)
            }
            if (p.isNumber) {
                Text(stringResource(R.string.shop_number_plan, p.voiceMin, p.sms), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
            }
            val perDay = stringResource(R.string.shop_per_day, usdExact(Math.round(p.priceCents.toDouble() / p.days.coerceAtLeast(1))))
            val perGb = perGbCents(p)?.let { stringResource(R.string.shop_per_gb, usdExact(Math.round(it))) }
            Text(listOfNotNull(perDay, perGb).joinToString(" · "), style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(8.dp))
        Text(usd(p.priceCents), style = if (featured) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = scheme.onSurface)
        if (selected != null) {
            Spacer(Modifier.width(10.dp))
            RadioMark(selected)
        }
    }
}

// ── Collection ───────────────────────────────────────────────────────────────

@Composable
internal fun CollectionPage(nav: ShopNav, seed: PassCollection, canPurchase: Boolean) {
    val ctx = LocalContext.current
    val full by produceState<PassCollection?>(null, seed.id) { value = fetchCollection(seed.id, esimLang(ctx.resources.configuration.locales[0])) }
    val c = full ?: seed
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        ShopTopBar(c.title, onBack = { nav.pop() })
        LazyColumn(Modifier.fillMaxSize()) {
            item {
                if (c.subtitle.isNotBlank()) Text(c.subtitle, Modifier.padding(horizontal = 20.dp, vertical = 8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            item {
                val list = full?.destinations
                if (list == null) ShopSection { repeat(4) { SkeletonRow(it < 3) } }
                else ShopSection { list.forEachIndexed { i, d -> DestRow(d, canPurchase, i < list.lastIndex) { nav.push(ShopPage.Dest(d)) } } }
            }
        }
    }
}

// ── Second number ────────────────────────────────────────────────────────────

private const val NUMBER_DEST = "US"

@Composable
internal fun NumberPage(nav: ShopNav, canPurchase: Boolean) {
    val ctx = LocalContext.current
    var loaded by remember { mutableStateOf(ShopStore.cached(NUMBER_DEST)) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    LaunchedEffect(reload) {
        when (val r = ShopStore.destination(ctx, NUMBER_DEST, force = reload > 0)) {
            is Shop.Ok -> { loaded = r.value; error = null }
            is Shop.Err -> if (loaded == null) error = r.message
        }
    }
    LaunchedEffect(Unit) { ShopStore.track(ctx, EsimEvents.LANDING_VIEW, NUMBER_DEST, mapOf("screen" to "number")) }
    val plans = loaded?.second.orEmpty().filter { it.isNumber }.sortedWith(compareBy({ it.days }, { it.priceCents }))
    val scheme = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        ShopTopBar(stringResource(R.string.shop_second_number), onBack = { nav.pop() })
        LazyColumn(Modifier.fillMaxSize()) {
            item {
                ShopSection {
                    ShopRow {
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                NumberBadge(56.dp)
                                Spacer(Modifier.width(14.dp))
                                Column {
                                    Text(stringResource(R.string.shop_real_us_number), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = scheme.onSurface)
                                    val from = plans.minOfOrNull { it.priceCents }
                                    if (canPurchase && from != null) Text(stringResource(R.string.shop_from_price, usd(from)), style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
                                }
                            }
                            Spacer(Modifier.height(12.dp))
                            Text(stringResource(R.string.shop_number_body), style = MaterialTheme.typography.bodyLarge, color = scheme.onSurface)
                        }
                    }
                    Point(painterResource(R.drawable.ic_rv_sim), R.string.shop_number_p1, R.string.shop_number_p1_sub)
                    Point(androidx.compose.ui.graphics.vector.rememberVectorPainter(Icons.Filled.Email), R.string.shop_number_p2, R.string.tabs_number_label_hint)
                    Point(painterResource(R.drawable.ic_rv_account), R.string.shop_number_p3, R.string.shop_number_p3_sub)
                    Point(painterResource(R.drawable.ic_rv_chart), R.string.shop_number_p4, R.string.shop_number_p4_sub, divider = false)
                }
            }
            if (canPurchase) item {
                when {
                    loaded == null && error != null -> ErrorState(error!!) { reload++ }
                    loaded == null -> ShopSection { repeat(4) { SkeletonRow(it < 3) } }
                    else -> ShopSection(stringResource(R.string.shop_choose_plan), stringResource(if (deviceSupportsEsim(ctx)) R.string.shop_supports_esim else R.string.shop_no_esim_support)) {
                        plans.forEachIndexed { i, p ->
                            ShopRow(onClick = { nav.push(ShopPage.Checkout(p, loaded?.first)) }, divider = i < plans.lastIndex) {
                                Column(Modifier.weight(1f)) {
                                    Text(daysLabel(p.days), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = scheme.onSurface)
                                    Text(
                                        listOf(pluralStringResource(R.plurals.shop_min, p.voiceMin, p.voiceMin), pluralStringResource(R.plurals.shop_texts, p.sms, p.sms), planDataLabel(p)).joinToString("  ·  "),
                                        style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant,
                                    )
                                }
                                Text(usd(p.priceCents), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = scheme.onSurface)
                                Chevron()
                            }
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(24.dp).navigationBarsPadding()) }
        }
    }
}

@Composable
private fun Point(icon: androidx.compose.ui.graphics.painter.Painter, title: Int, sub: Int, divider: Boolean = true) {
    ShopRow(divider = divider) {
        Icon(icon, null, tint = ShopColors.accentOnCard(), modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(14.dp))
        Column {
            Text(stringResource(title), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
            Text(stringResource(sub), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ── Continents ───────────────────────────────────────────────────────────────

/**
 * The continent chips on the Local list: iOS ESimContinents.swift's offline
 * ISO-2 table (every code plus XK). Crossroads sit in two on purpose (Turkey
 * and Egypt also in the Middle East; Russia and the Caucasus in Europe and Asia).
 */
internal enum class Continent(val title: Int, codes: String) {
    EUROPE(R.string.shop_europe, "AD AL AT AX BA BE BG BY CH CY CZ DE DK EE ES FI FO FR GB GG GI GR HR HU IE IM IS IT JE LI LT LU LV MC MD ME MK MT NL NO PL PT RO RS RU SE SI SJ SK SM UA VA XK GE AM AZ TR"),
    ASIA(R.string.shop_asia, "AF BD BN BT CN HK ID IN IO JP KG KH KP KR KZ LA LK MM MN MO MV MY NP PH PK SG TH TJ TL TM TW UZ VN RU GE AM AZ"),
    AMERICAS(R.string.shop_americas, "AG AI AR AW BB BL BM BO BQ BR BS BZ CA CL CO CR CU CW DM DO EC FK GD GF GL GP GS GT GY HN HT JM KN KY LC MF MQ MS MX NI PA PE PM PR PY SR SV SX TC TT US UY VC VE VG VI"),
    MIDDLE_EAST(R.string.shop_middle_east, "AE BH IL IQ IR JO KW LB OM PS QA SA SY YE TR EG"),
    AFRICA(R.string.shop_africa, "AO BF BI BJ BW CD CF CG CI CM CV DJ DZ EG EH ER ET GA GH GM GN GQ GW KE KM LR LS LY MA MG ML MR MU MW MZ NA NE NG RE RW SC SD SH SL SN SO SS ST SZ TD TG TN TZ UG YT ZA ZM ZW TF BV"),
    OCEANIA(R.string.shop_oceania, "AS AU CC CK CX FJ FM GU HM KI MH MP NC NF NR NU NZ PF PG PN PW SB TK TO TV UM VU WF WS AQ");

    val members: Set<String> = codes.split(' ').toSet()

    companion object {
        fun of(iso: String): Set<Continent> = entries.filter { iso.uppercase() in it.members }.toSet()

        /** The device's own country, for "Near me": the SIM's, else the locale's. */
        fun homeIso(ctx: Context): String? {
            val tm = ctx.getSystemService(Context.TELEPHONY_SERVICE) as? android.telephony.TelephonyManager
            return listOfNotNull(tm?.simCountryIso, ctx.resources.configuration.locales[0].country)
                .firstOrNull { it.length == 2 }?.uppercase()
        }
    }
}

