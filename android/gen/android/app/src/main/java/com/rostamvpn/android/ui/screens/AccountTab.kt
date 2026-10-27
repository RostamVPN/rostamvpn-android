package com.rostamvpn.android.ui.screens

import androidx.compose.foundation.background
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rostamvpn.android.BuildConfig
import com.rostamvpn.android.R
import com.rostamvpn.android.VpnViewModel
import com.rostamvpn.android.billing.CashierApi
import com.rostamvpn.android.billing.CashierIdentity
import kotlinx.coroutines.launch

/**
 * Account: one card (Pro, the price, active until a date or not active with
 * Go Pro), the restore code, then Settings and a single Legal link. No
 * ladder, no badges, no savings copy (design direction).
 */
@Composable
internal fun AccountTab(viewModel: VpnViewModel) {
    val scheme = MaterialTheme.colorScheme
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val isPaid by viewModel.isPaid.collectAsState()
    val sub by viewModel.subscription.collectAsState()
    val products by viewModel.subProducts.collectAsState()
    val webPlans by viewModel.webPlans.collectAsState()

    var code by remember { mutableStateOf<String?>(null) }
    var showCode by remember { mutableStateOf(false) }
    var enterCode by remember { mutableStateOf(false) }
    var legal by remember { mutableStateOf(false) }

    // The store's own localised price when Play has answered, else the
    // cashier's. Never a literal: Play prices differ per country.
    val price = products.firstOrNull()?.subscriptionOfferDetails?.firstOrNull()
        ?.pricingPhases?.pricingPhaseList?.lastOrNull()?.formattedPrice
        ?: webPlans.firstOrNull()?.price

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
        Spacer(Modifier.height(14.dp))
        Text(stringResource(R.string.tabs_account), fontSize = 23.sp, fontWeight = FontWeight.ExtraBold,
            letterSpacing = (-0.5).sp, color = scheme.onBackground)
        Spacer(Modifier.height(14.dp))

        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(scheme.surface)
                .border(1.5.dp, scheme.primary.copy(alpha = if (isPaid) 0.45f else 0.30f), RoundedCornerShape(20.dp))
                .padding(18.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.tabs_pro), fontSize = 20.sp, fontWeight = FontWeight.ExtraBold,
                    color = scheme.onSurface, modifier = Modifier.weight(1f))
                if (price != null) Text(stringResource(R.string.tabs_per_month, price), fontSize = 15.sp,
                    fontWeight = FontWeight.Bold, color = scheme.onSurface)
            }
            Spacer(Modifier.height(6.dp))
            // paid_until is ISO from the cashier; show it in the user's own date format.
            val until = remember(sub?.paidUntil) {
                sub?.paidUntil?.takeIf { it.length >= 10 }?.let { iso ->
                    runCatching {
                        val d = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
                            .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.parse(iso.substring(0, 10))!!
                        java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM).format(d)
                    }.getOrNull()
                }
            }
            Text(
                when {
                    isPaid && until != null -> stringResource(R.string.tabs_active_until, until)
                    isPaid -> stringResource(R.string.tabs_active)
                    else -> stringResource(R.string.tabs_not_active)
                },
                fontSize = 14.sp, color = if (isPaid) scheme.primary else scheme.onSurfaceVariant, fontWeight = FontWeight.SemiBold,
            )
            if (!isPaid) {
                Spacer(Modifier.height(14.dp))
                PrimaryPill(stringResource(R.string.tabs_go_pro), { viewModel.openPaywallAndLoad() }, Modifier.fillMaxWidth())
                Spacer(Modifier.height(6.dp))
                Text(stringResource(R.string.tabs_renews), fontSize = 11.5.sp, color = scheme.onSurfaceVariant,
                    textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            }
            Spacer(Modifier.height(14.dp))
            Text(
                stringResource(R.string.tabs_restore_code), fontSize = 14.sp, fontWeight = FontWeight.Bold, color = scheme.primary,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable {
                    showCode = true
                    if (code == null) scope.launch { code = CashierApi.accountCode(CashierIdentity.cid(ctx.applicationContext)) }
                }.padding(vertical = 6.dp),
            )
            Text(
                stringResource(R.string.tabs_other_device), fontSize = 13.sp, color = scheme.onSurfaceVariant,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { enterCode = true }.padding(vertical = 6.dp),
            )
        }

        // e-sim.net's own account (C4): a different code from the VPN restore
        // code above (different server, different code space), so it has its
        // own card and its own words. Replaces the site's /me for this brand.
        if (BuildConfig.FEATURE_ESIM) {
            Spacer(Modifier.height(14.dp))
            EsimAccountCard()
        }

        Spacer(Modifier.height(18.dp))
        LinkRow(stringResource(R.string.settings)) { viewModel.toggleSettings() }
        LinkRow(stringResource(R.string.tabs_legal)) { legal = true }
        Spacer(Modifier.height(16.dp))
        Text(
            "${stringResource(R.string.app_name)} · v${BuildConfig.VERSION_NAME}", fontSize = 11.5.sp,
            color = scheme.onSurfaceVariant.copy(alpha = 0.7f), textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(16.dp))
    }

    if (showCode) AlertDialog(
        onDismissRequest = { showCode = false },
        confirmButton = { TextButton(onClick = { showCode = false }) { Text(stringResource(android.R.string.ok)) } },
        title = { Text(stringResource(R.string.tabs_restore_code), fontWeight = FontWeight.Bold) },
        text = {
            Column {
                Text(stringResource(R.string.tabs_code_body), fontSize = 13.sp, color = scheme.onSurfaceVariant)
                Spacer(Modifier.height(12.dp))
                Text(code ?: "…", fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, color = scheme.primary,
                    textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            }
        },
    )

    if (enterCode) {
        var input by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { enterCode = false },
            confirmButton = {
                TextButton(onClick = { if (input.isNotBlank()) viewModel.claimAccountCode(input); enterCode = false }) {
                    Text(stringResource(R.string.restore))
                }
            },
            dismissButton = { TextButton(onClick = { enterCode = false }) { Text(stringResource(android.R.string.cancel)) } },
            title = { Text(stringResource(R.string.tabs_other_device), fontWeight = FontWeight.Bold) },
            text = {
                OutlinedTextField(value = input, onValueChange = { input = it }, singleLine = true,
                    label = { Text(stringResource(R.string.tabs_restore_code)) }, modifier = Modifier.fillMaxWidth())
            },
        )
    }

    if (legal) TabsSheet(stringResource(R.string.tabs_legal), onClose = { legal = false }) {
        Column {
            LinkRow(stringResource(R.string.privacy_policy)) { legal = false; viewModel.openPrivacyPolicy() }
            LinkRow(stringResource(R.string.open_source_libraries)) { legal = false; viewModel.openOpenSource() }
            LinkRow(stringResource(R.string.about)) { legal = false; viewModel.openAbout() }
        }
    }
}

@Composable
private fun LinkRow(label: String, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(vertical = 14.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = scheme.onSurface, modifier = Modifier.weight(1f))
        androidx.compose.material3.Icon(
            androidx.compose.material.icons.Icons.AutoMirrored.Filled.KeyboardArrowRight, null,
            tint = scheme.onSurfaceVariant,
        )
    }
}

/** eSIM access code and restore, native over e-sim.net /v1/account (C4). */
@Composable
private fun EsimAccountCard() {
    val scheme = MaterialTheme.colorScheme
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val deviceId = remember { CashierIdentity.cid(ctx.applicationContext) }
    var code by remember { mutableStateOf<String?>(null) }
    var codeFailed by remember { mutableStateOf(false) }
    var showCode by remember { mutableStateOf(false) }
    var restore by remember { mutableStateOf(false) }
    // D: Sign in with Google/Apple through the site, so eSIMs bought with the
    // email on any device appear. The site's /auth/{provider}/login runs in a Custom
    // Tab (Google refuses OAuth inside a WebView) or the browser. It finishes
    // on /me?app=1#session=, whose page hops to esimnet://me#session= (server
    // d9a9fee, site.js) with a "Return to the app" button as fallback; EsimNav
    // stores it. That works without verified app links, on every API level.
    // /v1/me says who is signed in and which providers the site offers.
    EsimSession.init(ctx)
    val token by EsimSession.token.collectAsState()
    var me by remember { mutableStateOf<EsimMe?>(null) }
    var meAttempt by remember { mutableIntStateOf(0) }
    var confirmDelete by remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(token, meAttempt) {
        val m = fetchMe() ?: return@LaunchedEffect
        // A stored session the server no longer accepts (expired, deleted): drop it.
        if (token != null && !m.signedIn) EsimSession.set(ctx, null)
        me = m
    }
    val signIn: (String) -> Unit = { provider ->
        val url = signInUrl(provider, deviceId)
        EsimSession.signInStarted(ctx)   // opens the 15-minute window for the return
        val pkg = CustomTab.provider(ctx)
        if (pkg == null || !CustomTab.open(ctx, url, pkg)) runCatching {
            ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)))
        }
    }

    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(scheme.surface)
            .border(1.dp, scheme.outlineVariant, RoundedCornerShape(20.dp)).padding(18.dp),
    ) {
        Text(stringResource(R.string.tabs_my_passes), fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, color = scheme.onSurface)
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.tabs_esim_code), fontSize = 14.sp, fontWeight = FontWeight.Bold, color = scheme.primary,
            modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable {
                showCode = true
                if (code == null) scope.launch { codeFailed = false; code = fetchAccessCode(deviceId); codeFailed = code == null }
            }.padding(vertical = 6.dp),
        )
        Text(
            stringResource(R.string.tabs_esim_restore), fontSize = 13.sp, color = scheme.onSurfaceVariant,
            modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { restore = true }.padding(vertical = 6.dp),
        )
        val m = me
        if (m != null && m.signedIn && token != null) {
            Spacer(Modifier.height(10.dp))
            Text(stringResource(R.string.tabs_signed_in_as, m.email), fontSize = 13.sp, color = scheme.onSurface)
            Row {
                Text(
                    stringResource(R.string.tabs_sign_out), fontSize = 13.sp, fontWeight = FontWeight.Bold, color = scheme.primary,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { EsimSession.set(ctx, null); me = null; meAttempt++ }
                        .padding(vertical = 6.dp).padding(end = 16.dp),
                )
                Text(
                    stringResource(R.string.tabs_delete_account), fontSize = 13.sp, color = scheme.error,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { confirmDelete = true }.padding(vertical = 6.dp),
                )
            }
        } else if (m != null && (m.google || m.apple)) {
            Spacer(Modifier.height(10.dp))
            Text(stringResource(R.string.tabs_signin_body), fontSize = 12.5.sp, color = scheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            if (m.google) OutlinePill(stringResource(R.string.tabs_signin_google), { signIn("google") }, Modifier.fillMaxWidth())
            if (m.google && m.apple) Spacer(Modifier.height(8.dp))
            if (m.apple) OutlinePill(stringResource(R.string.tabs_signin_apple), { signIn("apple") }, Modifier.fillMaxWidth())
        }
    }

    if (confirmDelete) {
        var busy by remember { mutableStateOf(false) }
        var failed by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { if (!busy) confirmDelete = false },
            title = { Text(stringResource(R.string.tabs_delete_account), fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text(stringResource(R.string.tabs_delete_body), fontSize = 13.sp, color = scheme.onSurfaceVariant)
                    if (failed) {
                        Spacer(Modifier.height(8.dp))
                        Text(stringResource(R.string.tabs_load_failed), fontSize = 12.5.sp, color = scheme.error)
                    }
                }
            },
            confirmButton = {
                TextButton(enabled = !busy, onClick = {
                    busy = true; failed = false
                    scope.launch {
                        if (deleteMe()) {
                            EsimSession.set(ctx, null); me = null; meAttempt++; confirmDelete = false
                            android.widget.Toast.makeText(ctx, ctx.getString(R.string.tabs_deleted), android.widget.Toast.LENGTH_LONG).show()
                        } else failed = true
                        busy = false
                    }
                }) { Text(stringResource(R.string.tabs_delete_confirm), color = scheme.error) }
            },
            dismissButton = { TextButton(enabled = !busy, onClick = { confirmDelete = false }) { Text(stringResource(android.R.string.cancel)) } },
        )
    }

    if (showCode) AlertDialog(
        onDismissRequest = { showCode = false },
        confirmButton = { TextButton(onClick = { showCode = false }) { Text(stringResource(android.R.string.ok)) } },
        dismissButton = code?.let { c ->
            {
                TextButton(onClick = {
                    (ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                        .setPrimaryClip(android.content.ClipData.newPlainText("code", c))
                    android.widget.Toast.makeText(ctx, ctx.getString(R.string.esim_copied), android.widget.Toast.LENGTH_SHORT).show()
                }) { Text(stringResource(R.string.esim_copy)) }
            }
        },
        title = { Text(stringResource(R.string.tabs_esim_code), fontWeight = FontWeight.Bold) },
        text = {
            Column {
                Text(stringResource(R.string.tabs_esim_code_body), fontSize = 13.sp, color = scheme.onSurfaceVariant)
                Spacer(Modifier.height(12.dp))
                Text(
                    when { code != null -> code!!; codeFailed -> stringResource(R.string.tabs_load_failed); else -> "…" },
                    fontSize = if (code != null) 22.sp else 14.sp, fontWeight = FontWeight.ExtraBold, color = scheme.primary,
                    textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
                )
            }
        },
    )

    if (restore) {
        var input by remember { mutableStateOf("") }
        var busy by remember { mutableStateOf(false) }
        var error by remember { mutableStateOf<Int?>(null) }
        AlertDialog(
            onDismissRequest = { if (!busy) restore = false },
            confirmButton = {
                TextButton(enabled = !busy && input.isNotBlank(), onClick = {
                    busy = true; error = null
                    scope.launch {
                        when (val r = claimAccessCode(input, deviceId)) {
                            is ClaimResult.Moved -> {
                                restore = false
                                android.widget.Toast.makeText(ctx, ctx.getString(R.string.tabs_esim_restored, r.n), android.widget.Toast.LENGTH_LONG).show()
                                EsimNav.open(EsimRoute(mine = true))
                            }
                            ClaimResult.BadCode -> error = R.string.tabs_esim_code_bad
                            ClaimResult.Failed -> error = R.string.tabs_load_failed
                        }
                        busy = false
                    }
                }) { Text(stringResource(R.string.restore)) }
            },
            dismissButton = { TextButton(enabled = !busy, onClick = { restore = false }) { Text(stringResource(android.R.string.cancel)) } },
            title = { Text(stringResource(R.string.tabs_esim_restore), fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    OutlinedTextField(value = input, onValueChange = { input = it.take(32) }, singleLine = true,
                        label = { Text(stringResource(R.string.tabs_esim_code)) }, modifier = Modifier.fillMaxWidth())
                    error?.let {
                        Spacer(Modifier.height(8.dp))
                        Text(stringResource(it), fontSize = 12.5.sp, color = scheme.error)
                    }
                }
            },
        )
    }
}

