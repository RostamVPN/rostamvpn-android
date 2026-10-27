package com.rostamvpn.android.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rostamvpn.android.R
import com.rostamvpn.android.ui.theme.Brand1
import kotlinx.coroutines.launch

/**
 * My eSIMs, iOS ESimMineView: Active · Ready to install · Past, what is left
 * on each, install again, top-ups, and restore by access code. The eSIMs
 * belong to this device's cid; the access code moves them to another phone.
 */
internal enum class Phase { ACTIVE, UPCOMING, PAST }

/** iOS ESim.phase(number:): a number whose data ran out still has calls and SMS until it expires. */
internal fun Esim.phase(nowMs: Long = System.currentTimeMillis()): Phase {
    val s = status.lowercase()
    val u = usageStatus
    val exp = parseAiraloTime(expiredAt)
    if (looksLikeNumber && u == "FINISHED" && s != "expired" && exp != null && exp > nowMs) return Phase.ACTIVE
    if (s == "depleted" || s == "expired" || u == "FINISHED" || u == "EXPIRED") return Phase.PAST
    if (exp != null && exp < nowMs && u != "NOT_ACTIVE") return Phase.PAST
    if (s == "active" || u == "ACTIVE") return Phase.ACTIVE
    return Phase.UPCOMING
}

@Composable
internal fun MineList(nav: ShopNav) {
    val ctx = LocalContext.current
    val sims by ShopStore.sims.collectAsState()
    val failed by ShopStore.simsFailed.collectAsState()
    LaunchedEffect(Unit) { ShopStore.loadSims(ctx) }
    val scheme = MaterialTheme.colorScheme
    LazyColumn(Modifier.fillMaxSize()) {
        val list = sims
        when {
            list == null -> item { ShopSection { repeat(3) { SkeletonRow(it < 2) } } }
            list.isEmpty() -> item {
                Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    androidx.compose.material3.Icon(painterResource(R.drawable.ic_rv_sim), null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(40.dp))
                    Spacer(Modifier.height(10.dp))
                    Text(stringResource(R.string.shop_no_esims), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = scheme.onBackground)
                    Text(
                        stringResource(if (failed) R.string.esim_load_failed else R.string.shop_no_esims_body),
                        color = scheme.onSurfaceVariant, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            else -> Phase.entries.forEach { ph ->
                val rows = list.filter { it.phase() == ph }
                if (rows.isNotEmpty()) item(ph.name) {
                    ShopSection(stringResource(when (ph) { Phase.ACTIVE -> R.string.shop_active; Phase.UPCOMING -> R.string.shop_ready_install; Phase.PAST -> R.string.shop_past })) {
                        rows.forEachIndexed { i, s -> SimRow(s, i < rows.lastIndex) { nav.push(ShopPage.Detail(s)) } }
                    }
                }
            }
        }
        item("restore") {
            ShopSection(footer = stringResource(R.string.shop_code_body)) {
                ShopRow(onClick = { nav.push(ShopPage.Restore) }) {
                    Icon(Icons.Filled.Refresh, null, tint = ShopColors.accentOnCard()); Spacer(Modifier.width(12.dp))
                    Text(stringResource(R.string.shop_restore_here), color = scheme.onSurface)
                }
                ShopRow(onClick = { nav.push(ShopPage.AccessCode) }, divider = false) {
                    Icon(Icons.Filled.Lock, null, tint = ShopColors.accentOnCard()); Spacer(Modifier.width(12.dp))
                    Text(stringResource(R.string.shop_your_access_code), color = scheme.onSurface)
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun SimRow(s: Esim, divider: Boolean, onClick: () -> Unit) {
    val ctx = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    ShopRow(onClick = onClick, divider = divider) {
        FlagTile(s.flag.ifBlank { flagOf(s.destinationId) ?: "🌍" })
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(s.destination.ifBlank { s.destinationId }, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, color = scheme.onSurface)
                SimStatus(s)
            }
            if (s.line.isNotBlank()) Text(s.line, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
            val total = s.totalMb ?: 0
            val unlimited = s.totalMb == 0L && s.remainingMb != null
            if (total > 0 || unlimited) {
                Spacer(Modifier.height(6.dp))
                if (!unlimited) UsageBar((s.remainingMb ?: 0).toFloat() / total.toFloat())
                Row(Modifier.padding(top = 4.dp)) {
                    Text(
                        if (unlimited) stringResource(R.string.shop_unlimited_data)
                        else stringResource(R.string.shop_left_of, dataLabel(ctx, s.remainingMb ?: 0), dataLabel(ctx, total)),
                        Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant,
                    )
                    daysLeft(s)?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant) }
                }
            }
        }
    }
}

@Composable
private fun daysLeft(s: Esim): String? {
    val exp = parseAiraloTime(s.expiredAt) ?: return null
    if (s.usageStatus == "NOT_ACTIVE") return null
    val d = Math.ceil((exp - System.currentTimeMillis()) / 86_400_000.0).toInt()
    return when {
        d <= 0 -> stringResource(R.string.shop_expired)
        d == 1 -> stringResource(R.string.shop_one_day_left)
        else -> pluralStringResource(R.plurals.shop_days_left, d, d)
    }
}

@Composable
private fun SimStatus(s: Esim) {
    when (s.phase()) {
        Phase.ACTIVE -> StatusPill(stringResource(R.string.shop_active), ShopColors.successText())
        Phase.UPCOMING -> StatusPill(stringResource(R.string.shop_not_installed), ShopColors.accentOnCard())
        Phase.PAST -> StatusPill(
            stringResource(if (s.status.lowercase() == "depleted" || s.usageStatus == "FINISHED") R.string.shop_used_up else R.string.shop_expired),
            MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** One eSIM: usage, install (again), top-ups, details. */
@Composable
internal fun EsimDetailPage(nav: ShopNav, sim: Esim, canPurchase: Boolean) {
    val ctx = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    var topups by remember { mutableStateOf<List<PassPlan>?>(null) }
    var topupsLoaded by remember { mutableStateOf(false) }
    LaunchedEffect(sim.iccid) {
        if (!canPurchase) return@LaunchedEffect
        topups = fetchTopups(sim.iccid)
        topupsLoaded = true
        topups?.takeIf { it.isNotEmpty() }?.let { ShopStore.track(ctx, EsimEvents.TOPUP_PROMPT_SHOWN, sim.destinationId, mapOf("n" to it.size)) }
    }
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        ShopTopBar(sim.destination.ifBlank { sim.destinationId }, onBack = { nav.pop() })
        LazyColumn(Modifier.fillMaxSize()) {
            item {
                ShopSection {
                    ShopRow {
                        FlagTile(sim.flag.ifBlank { flagOf(sim.destinationId) ?: "🌍" }, 48.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(sim.destination.ifBlank { sim.destinationId }, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = scheme.onSurface)
                            if (sim.line.isNotBlank()) Text(sim.line, color = scheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                        }
                        SimStatus(sim)
                    }
                    val total = sim.totalMb ?: 0
                    if (sim.remainingMb != null) {
                        ShopRow(divider = sim.msisdn.isNotBlank()) {
                            Column(Modifier.fillMaxWidth()) {
                                if (total == 0L) Text(stringResource(R.string.shop_unlimited_data), style = MaterialTheme.typography.titleMedium, color = scheme.onSurface)
                                else {
                                    Text(stringResource(R.string.shop_left_of, dataLabel(ctx, sim.remainingMb), dataLabel(ctx, total)), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = scheme.onSurface)
                                    Spacer(Modifier.height(8.dp))
                                    UsageBar(sim.remainingMb.toFloat() / total.toFloat(), 8.dp)
                                }
                                val bits = listOfNotNull(
                                    daysLeft(sim),
                                    sim.remainingVoice?.takeIf { (sim.totalVoice ?: 0) > 0 }?.let { pluralStringResource(R.plurals.shop_min, it, it) },
                                    sim.remainingText?.takeIf { (sim.totalText ?: 0) > 0 }?.let { pluralStringResource(R.plurals.shop_texts, it, it) },
                                )
                                if (bits.isNotEmpty()) Text(bits.joinToString("  ·  "), Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                            }
                        }
                    }
                    if (sim.msisdn.isNotBlank()) CopyValueRow(stringResource(R.string.shop_your_number), "+" + sim.msisdn.trimStart('+'), mono = false, divider = false)
                }
            }
            item {
                ShopSection(footer = stringResource(R.string.shop_installs_once)) {
                    ShopRow(onClick = { nav.push(ShopPage.Install(sim, sim.looksLikeNumber)) }, divider = false) {
                        Icon(painterResource(R.drawable.ic_rv_download), null, tint = ShopColors.accentOnCard()); Spacer(Modifier.width(12.dp))
                        Text(stringResource(if (sim.phase() == Phase.UPCOMING) R.string.shop_install_esim else R.string.shop_install_again), Modifier.weight(1f), color = scheme.onSurface)
                        Chevron()
                    }
                }
            }
            if (canPurchase && topupsLoaded) item {
                val t = topups
                // null = unknown (Airalo down): say nothing rather than "not reloadable".
                if (t != null) ShopSection(stringResource(R.string.shop_add_data), if (t.isNotEmpty()) stringResource(R.string.shop_data_added) else null) {
                    if (t.isEmpty() || sim.rechargeable == false) {
                        ShopRow(divider = false) { Text(stringResource(R.string.shop_not_reloadable), color = scheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium) }
                    } else t.forEachIndexed { i, p ->
                        ShopRow(onClick = {
                            ShopStore.track(ctx, EsimEvents.TOPUP_ACCEPTED, sim.destinationId, mapOf("plan" to p.slug))
                            nav.push(ShopPage.Checkout(p, ShopStore.cached(sim.destinationId)?.first, sim.iccid))
                        }, divider = i < t.lastIndex) { PlanLine(p, null, featured = false) }
                    }
                }
            }
            item {
                ShopSection(stringResource(R.string.shop_details)) {
                    CopyValueRow(stringResource(R.string.shop_iccid), sim.iccid, divider = sim.orderId.isNotBlank())
                    if (sim.orderId.isNotBlank()) CopyValueRow(stringResource(R.string.shop_order), sim.orderId, divider = false)
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

/** Restore: claim an access code from another phone onto this one. */
@Composable
internal fun RestorePage(nav: ShopNav) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val scheme = MaterialTheme.colorScheme
    var code by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var done by remember { mutableStateOf(false) }
    val badCode = stringResource(R.string.tabs_esim_code_bad)
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        ShopTopBar(stringResource(R.string.shop_restore_title), onBack = { nav.pop() }) {
            if (busy) CircularProgressIndicator(Modifier.size(20.dp).padding(end = 4.dp), strokeWidth = 2.dp)
            else Text(
                stringResource(if (done) R.string.shop_done else R.string.shop_restore),
                color = if (code.trim().length >= 4 || done) ShopColors.accent() else scheme.onSurfaceVariant,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clickable(enabled = done || code.trim().length >= 4, role = Role.Button) {
                    if (done) { nav.pop(); return@clickable }
                    busy = true
                    scope.launch {
                        when (val r = ShopApi.claim(ctx, code, ShopStore.deviceId(ctx))) {
                            is Shop.Ok -> { done = true; message = ctx.getString(R.string.tabs_esim_restored, r.value); ShopStore.loadSims(ctx) }
                            is Shop.Err -> message = if (r.code == 404) badCode else r.message
                        }
                        busy = false
                    }
                }.padding(12.dp),
            )
        }
        ShopSection(stringResource(R.string.shop_access_code), stringResource(R.string.shop_find_code)) {
            ShopRow(divider = false) {
                androidx.compose.foundation.layout.Box(Modifier.weight(1f)) {
                    if (code.isEmpty()) Text("XXXX-XXXX", fontFamily = FontFamily.Monospace, fontSize = 20.sp, color = scheme.onSurfaceVariant)
                    BasicTextField(
                        code, { code = it.uppercase() }, singleLine = true,
                        textStyle = MaterialTheme.typography.titleLarge.copy(fontFamily = FontFamily.Monospace, color = scheme.onSurface),
                        cursorBrush = SolidColor(Brand1),
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, autoCorrectEnabled = false),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
        message?.let {
            Text(it, Modifier.padding(horizontal = 20.dp), color = if (done) ShopColors.successText() else Color(0xFFE08A2E))
        }
    }
}

/** This device's access code, to restore on another phone. */
@Composable
internal fun AccessCodePage(nav: ShopNav) {
    val ctx = LocalContext.current
    var code by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        when (val r = ShopApi.accessCode(ctx, ShopStore.deviceId(ctx))) {
            is Shop.Ok -> code = r.value
            is Shop.Err -> error = r.message
        }
    }
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        ShopTopBar(stringResource(R.string.shop_access_code), onBack = null) {
            Text(stringResource(R.string.shop_done), color = ShopColors.accent(), fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clip(RoundedCornerShape(50)).clickable(role = Role.Button) { nav.pop() }.padding(12.dp))
        }
        ShopSection(footer = stringResource(R.string.shop_code_body)) {
            val c = code
            when {
                c != null -> CopyValueRow(stringResource(R.string.shop_your_access_code), c, divider = false)
                error != null -> ShopRow(divider = false) { Text(error!!, color = Color(0xFFE08A2E)) }
                else -> ShopRow(divider = false) { CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) }
            }
        }
    }
}

