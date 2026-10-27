package com.rostamvpn.android.ui.screens

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rostamvpn.android.BuildConfig
import com.rostamvpn.android.CardResult
import com.rostamvpn.android.CardSheet
import com.rostamvpn.android.R
import com.rostamvpn.android.ui.theme.Brand1
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Checkout, order progress and install: iOS ESimCheckoutView,
 * ESimOrderProgressView and ESimInstallView.
 *
 * Two rails. CARD: Stripe PaymentSheet (src/cardSheet) over the PaymentIntent
 * `POST /v1/orders` creates. CRYPTO: the inline stablecoin panel of
 * `POST /v1/orders/{id}/onchain` (USDT on Tron, USDC on Base): exact amount,
 * address, QR, copy, and polling until the watcher marks it paid. The App
 * Store row of iOS has no Android equivalent (the API sends apple_product_id
 * for iOS only) and is not here. No order exists until Pay is tapped (the
 * server rate-limits creation); card and crypto share one order, as on the
 * site, and a new code means a new order.
 *
 * Play: the flavour may sell eSIMs only where the brand opts in
 * (`features.esimCheckoutOnPlay`); "Other coins via Coinbase" is a hosted web
 * page and stays in the direct flavour (`WEB_PAY_ENABLED`).
 */
private const val RAIL_CARD = "card"
private const val RAIL_CRYPTO = "crypto"

internal val checkoutAllowed: Boolean get() = BuildConfig.WEB_PAY_ENABLED || BuildConfig.ESIM_CHECKOUT_ON_PLAY

@Composable
internal fun CheckoutPage(nav: ShopNav, plan: PassPlan, dest: PassDest?, iccid: String?) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val deviceId = remember { ShopStore.deviceId(ctx) }
    var rail by remember { mutableStateOf(RAIL_CARD) }
    var code by remember { mutableStateOf("") }
    var showCode by remember { mutableStateOf(false) }
    var codeCheck by remember { mutableStateOf<DiscountCheck?>(null) }
    var codeBusy by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var order by remember { mutableStateOf<ShopOrder?>(null) }
    var orderCode by remember { mutableStateOf("") }
    var onchain by remember { mutableStateOf<OnchainPanel?>(null) }
    val listState = rememberLazyListState()
    val merchant = stringResource(R.string.app_name)
    val sheetFailed = stringResource(R.string.shop_sheet_failed)
    val unavailable = stringResource(R.string.tabs_pay_unavailable)

    val appliedCode = if (codeCheck?.ok == true) codeCheck?.code ?: code.trim().uppercase() else ""
    val webTotal = if (codeCheck?.ok == true) codeCheck?.totalCents ?: plan.priceCents else plan.priceCents

    fun succeeded(orderId: String) {
        ShopStore.track(ctx, EsimEvents.PAY_SUCCESS, plan.destId, mapOf("plan" to plan.slug, "rail" to rail, "order" to orderId))
        nav.replace(ShopPage.Order(orderId, plan.isNumber))
    }

    val present = CardSheet.rememberPresenter { r ->
        busy = false
        when (r) {
            CardResult.Completed -> order?.let { succeeded(it.orderId) }
            CardResult.Cancelled -> Unit
            is CardResult.Failed -> error = r.message ?: sheetFailed
        }
    }

    /** The order card and crypto share; a new one only when the code changed. */
    suspend fun webOrder(): ShopOrder? {
        order?.takeIf { orderCode == appliedCode }?.let { return it }
        return when (val r = ShopApi.createOrder(ctx, plan, deviceId, iccid, appliedCode.ifBlank { null })) {
            is Shop.Ok -> { order = r.value; orderCode = appliedCode; r.value }
            is Shop.Err -> { error = r.message; null }
        }
    }

    suspend fun openOnchain(r: String) {
        val o = webOrder() ?: return
        var res = ShopApi.onchain(ctx, o.orderId, r)
        // Tron not configured on this server: Base is the other rail.
        if (res is Shop.Err && res.code == 409 && r == "usdt-tron") res = ShopApi.onchain(ctx, o.orderId, "usdc-base")
        when (res) {
            is Shop.Ok -> { onchain = res.value; error = null }
            is Shop.Err -> error = res.message
        }
    }

    fun pay() {
        if (busy || !checkoutAllowed) return
        busy = true
        error = null
        ShopStore.track(ctx, EsimEvents.PAY_SHEET_OPEN, plan.destId, mapOf("plan" to plan.slug, "rail" to rail, "topup" to (iccid != null)))
        scope.launch {
            if (rail == RAIL_CRYPTO) { openOnchain(onchain?.rail ?: "usdt-tron"); busy = false; return@launch }
            val o = webOrder() ?: run { busy = false; return@launch }
            val secret = o.clientSecret
            val key = o.publishableKey
            if (o.fake || secret == null || key == null) {
                // A fake-mode server (no Stripe keys): the site's "Simulate
                // payment". Constant-false in release, so R8 drops the call.
                if (BuildConfig.DEBUG && o.fake) {
                    when (val p = ShopApi.devPay(ctx, o.orderId)) {
                        is Shop.Ok -> succeeded(o.orderId)
                        is Shop.Err -> error = p.message
                    }
                } else error = unavailable
                busy = false
                return@launch
            }
            if (!CardSheet.available) { error = unavailable; busy = false; return@launch }
            runCatching { present(secret, key, merchant) }.onFailure { busy = false; error = sheetFailed }
        }
    }

    // The inline rail: poll the order until the watcher sees the transfer.
    LaunchedEffect(onchain?.orderId) {
        val id = onchain?.orderId ?: return@LaunchedEffect
        listState.animateScrollToItem(3)
        while (true) {
            delay(3000)
            val s = ShopApi.order(ctx, id)
            if (s is Shop.Ok && s.value.isPaid) { succeeded(id); return@LaunchedEffect }
        }
    }

    val scheme = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        ShopTopBar(stringResource(if (iccid == null) R.string.shop_checkout else R.string.shop_top_up), onBack = { if (!busy) nav.pop() })
        Box(Modifier.weight(1f)) {
            LazyColumn(Modifier.fillMaxSize(), state = listState) {
                item("summary") {
                    ShopSection(stringResource(R.string.shop_your_plan)) {
                        ShopRow {
                            FlagTile(dest?.let { if (it.kind == "country") flagOf(it.id) ?: it.flag else it.flag } ?: flagOf(plan.destId) ?: "🌍", 44.dp)
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text(dest?.name ?: plan.destId, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = scheme.onSurface)
                                Text(stringResource(if (plan.isNumber) R.string.shop_esim_with_number else R.string.shop_data_esim), style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
                            }
                        }
                        Detail(stringResource(R.string.home_data), planDataLabel(plan))
                        Detail(stringResource(R.string.shop_valid_for), daysLabel(plan.days))
                        if (plan.isNumber) Detail(stringResource(R.string.shop_calls_texts), stringResource(R.string.shop_min_texts, plan.voiceMin, plan.sms))
                        if (plan.operator.isNotBlank()) Detail(stringResource(R.string.shop_network), plan.operator)
                        if (iccid != null) Detail(stringResource(R.string.shop_adds_to), "…" + iccid.takeLast(6))
                    }
                }
                item("rails") {
                    ShopSection(stringResource(R.string.shop_pay_with)) {
                        RailRow(RAIL_CARD, rail, stringResource(R.string.shop_card), stringResource(R.string.shop_card_brands), usdExact(webTotal), !busy) { rail = it; error = null }
                        RailRow(RAIL_CRYPTO, rail, stringResource(R.string.shop_crypto), stringResource(R.string.shop_stablecoins), usdExact(webTotal), !busy, divider = false) { rail = it; error = null }
                    }
                }
                item("discount") {
                    ShopSection {
                        if (showCode || code.isNotEmpty()) {
                            ShopRow(divider = codeCheck != null) {
                                Box(Modifier.weight(1f)) {
                                    if (code.isEmpty()) Text(stringResource(R.string.shop_discount_code), color = scheme.onSurfaceVariant)
                                    BasicTextField(
                                        code, { code = it.uppercase(); codeCheck = null }, singleLine = true,
                                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = scheme.onSurface),
                                        cursorBrush = SolidColor(Brand1),
                                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, autoCorrectEnabled = false),
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                }
                                if (codeBusy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                else Text(
                                    stringResource(R.string.shop_apply), color = ShopColors.accentOnCard(), fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.clickable(enabled = code.isNotBlank(), role = Role.Button) {
                                        codeBusy = true
                                        scope.launch {
                                            codeCheck = when (val r = ShopApi.checkDiscount(ctx, code.trim().uppercase(), plan, deviceId)) {
                                                is Shop.Ok -> r.value
                                                is Shop.Err -> DiscountCheck(false, r.message, null, null, null)
                                            }
                                            // A new price needs a new order.
                                            if (codeCheck?.ok == true && onchain != null) onchain = null
                                            codeBusy = false
                                        }
                                    }.padding(6.dp),
                                )
                            }
                            codeCheck?.let { c ->
                                ShopRow(divider = false, minHeight = 36.dp) {
                                    if (c.ok && c.discountCents != null) {
                                        Text(stringResource(R.string.shop_code_applied, c.code ?: code, usdExact(c.discountCents)), style = MaterialTheme.typography.bodySmall, color = ShopColors.successText())
                                    } else if (c.reason != null) {
                                        Text(c.reason, style = MaterialTheme.typography.bodySmall, color = Color(0xFFE08A2E))
                                    }
                                }
                            }
                        } else {
                            ShopRow(onClick = { showCode = true }, divider = false) {
                                Text(stringResource(R.string.shop_add_code), color = ShopColors.accentOnCard(), fontWeight = FontWeight.Medium)
                            }
                        }
                    }
                }
                val panel = onchain
                if (rail == RAIL_CRYPTO && panel != null) item("crypto") {
                    CryptoPanel(panel, onRail = { r -> scope.launch { openOnchain(r) } }, onCoinbase = {
                        scope.launch {
                            val o = webOrder() ?: return@launch
                            when (val c = ShopApi.coinbase(ctx, o.orderId)) {
                                is Shop.Ok -> if (!c.value.second) openWeb(ctx, c.value.first)
                                is Shop.Err -> error = c.message
                            }
                        }
                    })
                }
                item("totals") {
                    ShopSection(footer = stringResource(R.string.shop_one_payment) + "\n" + stringResource(R.string.shop_delivered)) {
                        if (codeCheck?.ok == true && codeCheck?.discountCents != null) {
                            Detail(stringResource(R.string.shop_price), usdExact(plan.priceCents))
                            Detail(stringResource(R.string.shop_discount), "−" + usdExact(codeCheck!!.discountCents!!))
                        }
                        ShopRow(divider = false) {
                            Text(stringResource(R.string.shop_total), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = scheme.onSurface)
                            Text(usdExact(webTotal), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = scheme.onSurface)
                        }
                    }
                }
                item("pad") { Spacer(Modifier.height(120.dp)) }
            }
            // The pay bar, over the list. Gone while the crypto panel waits.
            if (!(rail == RAIL_CRYPTO && onchain != null)) {
                Column(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(scheme.background)
                        .padding(horizontal = 20.dp).padding(top = 10.dp, bottom = 8.dp).navigationBarsPadding(),
                ) {
                    error?.let {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 8.dp)) {
                            Icon(Icons.Filled.Warning, null, tint = Color(0xFFE08A2E), modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(it, style = MaterialTheme.typography.bodySmall, color = scheme.onSurface)
                        }
                    }
                    if (!checkoutAllowed) {
                        Text(unavailable, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                    }
                    ShopPrimaryButton(onClick = { pay() }, enabled = checkoutAllowed, busy = busy) {
                        Text(stringResource(if (rail == RAIL_CRYPTO) R.string.shop_pay_in_crypto else R.string.shop_pay, usdExact(webTotal)))
                    }
                }
            }
        }
    }
}

@Composable
private fun Detail(k: String, v: String) {
    ShopRow(minHeight = 44.dp) {
        Text(k, Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(v, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun RailRow(id: String, selected: String, title: String, sub: String, price: String, enabled: Boolean, divider: Boolean = true, onPick: (String) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    ShopRow(onClick = if (enabled) ({ onPick(id) }) else null, divider = divider) {
        if (id == RAIL_CARD) IconTile(androidx.compose.ui.graphics.vector.ImageVector.vectorResource(R.drawable.ic_rv_card), Color(0xFF615CF5), Color.White)
        else Box(Modifier.size(34.dp).clip(RoundedCornerShape(9.dp)).background(Color(0xFF26A17B)), contentAlignment = Alignment.Center) {
            Text("$", color = Color.White, fontWeight = FontWeight.Black, fontSize = 18.sp)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, color = scheme.onSurface)
            Text(sub, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
        }
        Text(price, fontWeight = FontWeight.SemiBold, color = scheme.onSurface)
        Spacer(Modifier.width(10.dp))
        RadioMark(id == selected)
    }
}

/** "Send exactly 12.994217 USDC on Base": QR, copy rows, wallet link, the wait. */
@Composable
private fun CryptoPanel(p: OnchainPanel, onRail: (String) -> Unit, onCoinbase: () -> Unit) {
    val ctx = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    ShopSection(stringResource(R.string.shop_pay_crypto_title)) {
        if (p.rails.size > 1) {
            ShopRow {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    p.rails.sortedBy { if (it == "usdt-tron") 0 else 1 }.forEach { r ->
                        val on = r == p.rail
                        val (token, net, c) = if (r == "usdt-tron") Triple("USDT", "Tron", Color(0xFF26A17B)) else Triple("USDC", "Base", Color(0xFF2775CA))
                        Row(
                            Modifier.clip(RoundedCornerShape(50))
                                .background(if (on) Brand1.copy(alpha = 0.16f) else scheme.onSurface.copy(alpha = 0.06f))
                                .border(1.5.dp, if (on) Brand1 else Color.Transparent, RoundedCornerShape(50))
                                .clickable(role = Role.Button) { onRail(r) }
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(Modifier.size(18.dp).clip(CircleShape).background(c), contentAlignment = Alignment.Center) {
                                Text("$", fontSize = 11.sp, fontWeight = FontWeight.Black, color = Color.White)
                            }
                            Spacer(Modifier.width(6.dp))
                            Text(token, fontWeight = FontWeight.Bold, color = scheme.onSurface, style = MaterialTheme.typography.labelLarge)
                            Spacer(Modifier.width(4.dp))
                            Text(net, style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
        ShopRow {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(stringResource(R.string.shop_send_exactly, p.amount, p.token, p.network), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = scheme.onSurface, textAlign = TextAlign.Center)
                Spacer(Modifier.height(12.dp))
                QrImage(p.qrPayload, 180.dp, stringResource(R.string.shop_wallet_code))
                Spacer(Modifier.height(12.dp))
                Text(stringResource(R.string.shop_amount_unique), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, textAlign = TextAlign.Center)
            }
        }
        CopyValueRow(stringResource(R.string.shop_amount, p.token), p.amount)
        CopyValueRow(stringResource(R.string.shop_address, p.network), p.address)
        if (p.uri.isNotBlank()) {
            ShopRow(onClick = { openWeb(ctx, p.uri) }) {
                Text(stringResource(R.string.shop_open_wallet), color = ShopColors.accentOnCard(), fontWeight = FontWeight.Medium)
            }
        }
        ShopRow(divider = BuildConfig.WEB_PAY_ENABLED) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(12.dp))
            Column {
                Text(stringResource(R.string.shop_waiting_payment), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = scheme.onSurface)
                isoTime(ctx, p.expiresAt)?.let { Text(stringResource(R.string.shop_send_before, it), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant) }
            }
        }
        // A hosted web page: direct (website) builds only.
        if (BuildConfig.WEB_PAY_ENABLED) {
            ShopRow(onClick = onCoinbase, divider = false) {
                Text(stringResource(R.string.shop_coinbase), color = ShopColors.accentOnCard(), fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                Icon(painterResource(R.drawable.ic_rv_open), null, tint = ShopColors.accentOnCard(), modifier = Modifier.size(18.dp))
            }
        }
    }
}

/** "10:15" from the server's ISO timestamp (always UTC, "+00:00"). minSdk 23 has no java.time. */
internal fun isoTime(ctx: Context, iso: String): String? = runCatching {
    val f = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
    android.text.format.DateFormat.getTimeFormat(ctx).format(f.parse(iso.take(19))!!)
}.getOrNull()

internal fun openWeb(ctx: Context, url: String) {
    try {
        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply { if (ctx.findActivity() == null) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
    } catch (_: ActivityNotFoundException) {
    }
}

// ── Order progress ───────────────────────────────────────────────────────────

/** After payment: paid, then setting up, then ready. Polls every 3 s, slowing after a minute. */
@Composable
internal fun OrderPage(nav: ShopNav, orderId: String, isNumber: Boolean?) {
    val ctx = LocalContext.current
    var state by remember { mutableStateOf<ShopOrderState?>(null) }
    var lastError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(orderId) {
        val started = System.currentTimeMillis()
        var failures = 0
        while (true) {
            when (val r = ShopApi.order(ctx, orderId)) {
                is Shop.Ok -> {
                    state = r.value; lastError = null; failures = 0
                    if (r.value.status == "provisioned" || r.value.isFailed) {
                        if (r.value.status == "provisioned") ShopStore.loadSims(ctx)
                        return@LaunchedEffect
                    }
                }
                is Shop.Err -> { failures++; if (failures >= 3) lastError = r.message }
            }
            val elapsed = System.currentTimeMillis() - started
            val wait = if (elapsed < 60_000) 3000L else if (elapsed < 300_000) 6000L else 10_000L
            delay(wait * (1 + minOf(failures, 3)))
        }
    }
    val s = state
    val sim = s?.esims?.firstOrNull()
    if (s != null && s.status == "provisioned" && sim != null) {
        InstallPage(nav, sim, isNumber ?: s.planIsNumber ?: sim.looksLikeNumber, onDone = { nav.finishFlow() })
        return
    }
    val scheme = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        ShopTopBar(stringResource(R.string.shop_order), onBack = null) {
            Text(stringResource(R.string.shop_close), color = ShopColors.accent(), fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clickable(role = Role.Button) { nav.finishFlow() }.padding(12.dp))
        }
        if (s != null && s.isFailed) {
            Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Icon(Icons.Filled.Warning, null, tint = Color(0xFFE08A2E), modifier = Modifier.size(52.dp))
                Spacer(Modifier.height(12.dp))
                Text(stringResource(if (s.status == "refunded") R.string.shop_refunded else R.string.shop_setup_failed), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, color = scheme.onBackground, textAlign = TextAlign.Center)
                if (s.error.isNotBlank()) Text(s.error, color = scheme.onSurfaceVariant, textAlign = TextAlign.Center)
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.shop_payment_safe, orderId), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, textAlign = TextAlign.Center)
                Spacer(Modifier.height(12.dp))
                ShopPrimaryButton(onClick = { openWeb(ctx, "mailto:support@e-sim.net?subject=Order%20$orderId") }) {
                    Icon(Icons.Filled.Email, null, tint = Color.White, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.shop_email_support))
                }
            }
            return@Column
        }
        val doneCount = when { s?.status == "provisioned" -> 3; s?.isPaid == true -> 1; else -> 0 }
        Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.weight(1f))
            Box(Modifier.size(120.dp).clip(CircleShape).background(Brand1.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(40.dp), color = ShopColors.accent())
            }
            Spacer(Modifier.height(24.dp))
            Text(stringResource(if (doneCount == 0) R.string.shop_confirming else R.string.shop_setting_up), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, color = scheme.onBackground)
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.shop_under_minute), color = scheme.onSurfaceVariant, textAlign = TextAlign.Center)
            Spacer(Modifier.height(24.dp))
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(scheme.surface).padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                listOf(R.string.shop_payment, R.string.shop_setting_up, R.string.shop_ready_install).forEachIndexed { i, t ->
                    val done = i < doneCount
                    val current = i == doneCount
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(26.dp).clip(CircleShape).background(if (done) ShopColors.success else scheme.onSurface.copy(alpha = 0.10f)), contentAlignment = Alignment.Center) {
                            if (done) Icon(Icons.Filled.Check, null, tint = Color.White, modifier = Modifier.size(16.dp))
                            else if (current) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                        }
                        Spacer(Modifier.width(12.dp))
                        Text(stringResource(t), fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal, color = if (done || current) scheme.onSurface else scheme.onSurfaceVariant)
                    }
                }
            }
            lastError?.let { Spacer(Modifier.height(12.dp)); Text(it, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant) }
            Spacer(Modifier.weight(1f))
            Text(stringResource(R.string.shop_order_id, orderId), style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace, color = scheme.onSurfaceVariant)
        }
    }
}

// ── Install ──────────────────────────────────────────────────────────────────

/**
 * The eSIM is ready. Android's own install first: `EuiccManager.downloadSubscription`
 * with the LPA string (rememberEsimInstaller: API 28+, an enabled eUICC; the
 * system shows its own consent sheet because this app holds no carrier
 * privileges). Where that is not available, an `LPA:` VIEW intent (some OEM
 * setup apps take it), then the SIM settings. Always: the QR, drawn here from
 * the LPA, and the codes with Copy, for another phone or manual entry.
 */
@Composable
internal fun InstallPage(nav: ShopNav, sim: Esim, isNumber: Boolean, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    val installer = rememberEsimInstaller(R.string.esim_installed, R.string.esim_install_failed) { scope.launch { ShopStore.loadSims(ctx) } }
    LaunchedEffect(sim.iccid) { ShopStore.track(ctx, EsimEvents.INSTALL_STEP, sim.destinationId, mapOf("step" to "screen")) }
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        ShopTopBar(stringResource(R.string.shop_your_esim), onBack = null) {
            Text(stringResource(R.string.shop_done), color = ShopColors.accent(), fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clickable(role = Role.Button, onClick = onDone).padding(12.dp))
        }
        LazyColumn(Modifier.fillMaxSize()) {
            item {
                Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.size(84.dp).clip(CircleShape).background(ShopColors.success.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
                        Icon(Icons.Filled.CheckCircle, null, tint = ShopColors.success, modifier = Modifier.size(48.dp))
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(stringResource(R.string.shop_ready), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = scheme.onBackground)
                    Text(sim.title.ifBlank { sim.destinationId }, style = MaterialTheme.typography.titleMedium, color = scheme.onBackground)
                    if (sim.line.isNotBlank()) Text(sim.line, color = scheme.onSurfaceVariant)
                }
            }
            if (isNumber) item {
                ShopSection(stringResource(R.string.shop_your_number)) {
                    if (sim.msisdn.isNotBlank()) CopyValueRow(stringResource(R.string.shop_your_number), "+" + sim.msisdn.trimStart('+'), mono = false, divider = false)
                    else ShopRow(divider = false) { Text(stringResource(R.string.tabs_number_label_hint), color = scheme.onSurface) }
                }
            }
            if (sim.installable || sim.lpa.startsWith("LPA:")) item {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    ShopPrimaryButton(
                        onClick = {
                            ShopStore.track(ctx, EsimEvents.INSTALL_STEP, sim.destinationId, mapOf("step" to if (installer.canInstall) "euicc" else "intent"))
                            if (installer.canInstall) installer.install(sim.lpa, sim.iccid) else systemInstall(ctx, sim.lpa)
                        },
                        busy = installer.busyKey == sim.iccid,
                    ) {
                        Icon(painterResource(R.drawable.ic_rv_download), null, tint = Color.White, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(if (installer.canInstall) R.string.esim_install else R.string.shop_open_settings))
                    }
                    Text(
                        stringResource(if (installer.canInstall) R.string.shop_install_now else R.string.shop_no_esim_support),
                        style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp, start = 4.dp),
                    )
                }
            }
            item {
                ShopSection(stringResource(R.string.shop_other_ways)) {
                    ShopRow {
                        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                            if (sim.lpa.isNotBlank()) QrImage(sim.lpa, 190.dp, stringResource(R.string.shop_install_code))
                            Spacer(Modifier.height(10.dp))
                            Text(stringResource(R.string.tabs_install_other), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, textAlign = TextAlign.Center)
                        }
                    }
                    // The whole activation string, which Android's "Enter code" field takes as is.
                    if (sim.lpa.isNotBlank()) CopyValueRow(stringResource(R.string.shop_copy_code), sim.lpa)
                    if (sim.smdp.isNotBlank()) CopyValueRow(stringResource(R.string.shop_smdp), sim.smdp)
                    if (sim.matchingId.isNotBlank()) CopyValueRow(stringResource(R.string.shop_activation_code), sim.matchingId)
                    if (sim.apn.isNotBlank()) CopyValueRow(stringResource(R.string.shop_apn), sim.apn, divider = sim.installGuideUrl.startsWith("http"))
                    if (sim.installGuideUrl.startsWith("http")) {
                        ShopRow(onClick = { openWeb(ctx, sim.installGuideUrl) }, divider = false) {
                            Text(stringResource(R.string.shop_install_guide), Modifier.weight(1f), color = ShopColors.accentOnCard(), fontWeight = FontWeight.Medium)
                            Icon(painterResource(R.drawable.ic_rv_open), null, tint = ShopColors.accentOnCard(), modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(24.dp).navigationBarsPadding()) }
        }
    }
}

/**
 * No usable EuiccManager: try the activation string as an `LPA:` link (some
 * OEM eSIM setup apps register it), then the SIM settings page (API 34's
 * "manage all SIMs", else the mobile network settings), where "Add eSIM"
 * takes the QR or the copied code.
 */
internal fun systemInstall(ctx: Context, lpa: String) {
    val tries = buildList {
        if (lpa.startsWith("LPA:")) add(Intent(Intent.ACTION_VIEW, Uri.parse(lpa)))
        if (android.os.Build.VERSION.SDK_INT >= 34) add(Intent(Settings.ACTION_MANAGE_ALL_SIM_PROFILES_SETTINGS))
        add(Intent(Settings.ACTION_NETWORK_OPERATOR_SETTINGS))
        add(Intent(Settings.ACTION_WIRELESS_SETTINGS))
    }
    for (i in tries) {
        try { ctx.startActivity(i); return } catch (_: ActivityNotFoundException) { } catch (_: SecurityException) { }
    }
}
