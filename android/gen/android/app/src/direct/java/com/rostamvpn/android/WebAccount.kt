package com.rostamvpn.android

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rostamvpn.android.billing.CashierApi
import com.rostamvpn.android.billing.CashierIdentity
import com.rostamvpn.android.ui.screens.CustomTab
import com.rostamvpn.android.ui.theme.Brand1
import kotlinx.coroutines.launch

/**
 * The web account, DIRECT flavour only (the Play twin in src/play is empty).
 *
 * One account per email per brand on the cashier (`User(email, product_slug)`),
 * shared with the brand's website (`BuildConfig.WEB_ACCOUNT_HOST`, the first of
 * the manifest's `deepLinkHosts`): sign in here with an email code and a web
 * purchase by that address reaches this device; "Manage on the web" opens the
 * site already signed in (`/api/v1/auth/handoff`), and the site's "Open the
 * app" comes back through `/app/open?t=` (WebLinks.kt).
 *
 * Off for China traffic: HANDOFF.md locked decision #3 says the app must not
 * offer email login there.
 */
object WebAccount {
    private const val TAG = "WebAccount"
    private val HOST: String = BuildConfig.WEB_ACCOUNT_HOST
    private val EMAIL = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")
    private val CN_TZ = setOf("Asia/Shanghai", "Asia/Urumqi", "Asia/Chongqing", "Asia/Harbin", "Asia/Kashgar", "PRC")

    fun available(ctx: Context): Boolean = HOST.isNotEmpty() && !chinaTraffic(ctx)

    /** The same generous test the CN payment rail uses (VpnViewModel): China timezone OR country. */
    private fun chinaTraffic(ctx: Context): Boolean {
        if (java.util.TimeZone.getDefault().id in CN_TZ) return true
        val country = runCatching {
            com.rostamvpn.android.billing.RegionDetector.detectCountry(ctx.applicationContext, null)
        }.getOrDefault("")
        return country.equals("CN", ignoreCase = true)
    }

    /**
     * Open [url] in a BROWSER, never through a plain VIEW: once the App Links
     * verify, a VIEW of our own host's /me or /login would come straight back
     * into this app. A Custom Tab when a browser offers one, else a browser
     * picked explicitly. The generated brand manifest declares the <queries>
     * that make browsers visible on API 30+.
     */
    private fun openInBrowser(ctx: Context, url: String): Boolean {
        CustomTab.provider(ctx)?.let { if (CustomTab.open(ctx, url, it)) return true }
        return runCatching {
            val probe = Intent(Intent.ACTION_VIEW, Uri.parse("https://$HOST/")).addCategory(Intent.CATEGORY_BROWSABLE)
            val browser = ctx.packageManager.queryIntentActivities(probe, 0)
                .map { it.activityInfo.packageName }.firstOrNull { it != ctx.packageName } ?: return false
            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).setPackage(browser)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        }.getOrDefault(false)
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun Screen(vm: VpnViewModel, onBack: () -> Unit) {
        val ctx = LocalContext.current
        val scope = rememberCoroutineScope()
        val scheme = MaterialTheme.colorScheme
        val cid = remember { CashierIdentity.cid(ctx.applicationContext) }
        val isPaid by vm.isPaid.collectAsState()

        var signedIn by remember { mutableStateOf(WebAccountPrefs.email(ctx)) }
        var input by remember { mutableStateOf("") }
        var sentTo by remember { mutableStateOf<String?>(null) }
        var code by remember { mutableStateOf("") }
        var busy by remember { mutableStateOf(false) }
        var error by remember { mutableStateOf<String?>(null) }

        // The cashier is the truth about who this cid is signed in as. null =
        // unreachable, so the remembered email stays.
        LaunchedEffect(Unit) {
            CashierApi.signedInEmail(cid)?.let { e ->
                signedIn = e.ifEmpty { null }
                WebAccountPrefs.setEmail(ctx, e)
            }
        }

        val failed = stringResource(R.string.tabs_load_failed)
        val badEmail = stringResource(R.string.web_account_bad_email)
        val wrongCode = stringResource(R.string.web_account_wrong_code)
        val tooMany = stringResource(R.string.web_account_too_many)
        val openFailed = stringResource(R.string.web_account_open_failed, HOST)

        fun sendCode() {
            val e = input.trim()
            if (!EMAIL.matches(e)) { error = badEmail; return }
            busy = true; error = null
            scope.launch {
                when (CashierApi.emailStart(e, cid)) {
                    in 200..299 -> { sentTo = e; code = "" }
                    400 -> error = badEmail
                    429 -> error = tooMany
                    else -> error = failed
                }
                busy = false
            }
        }

        fun verify() {
            val e = sentTo ?: return
            busy = true; error = null
            scope.launch {
                val r = CashierApi.emailVerify(e, code.trim(), cid)
                val link = r.link
                when {
                    link != null -> {
                        signedIn = link.email.ifEmpty { e }
                        sentTo = null; code = ""; input = ""
                        vm.onAccountLinked(link)
                    }
                    r.status == 400 || r.status == 401 -> error = wrongCode
                    r.status == 429 -> error = tooMany
                    else -> error = failed
                }
                busy = false
            }
        }

        fun manageOnWeb() {
            busy = true; error = null
            scope.launch {
                // Signed in on the site when the cashier can mint a hand-off;
                // otherwise the site's own account page, where the same email
                // signs in. Either way the user gets the website.
                val url = CashierApi.webHandoffUrl(cid) ?: "https://$HOST/me".also {
                    Log.i(TAG, "hand-off unavailable; opening the account page unsigned")
                }
                if (!openInBrowser(ctx, url)) error = openFailed
                busy = false
            }
        }

        BackHandler(onBack = onBack)

        Column(
            Modifier.fillMaxSize().background(scheme.background).statusBarsPadding().navigationBarsPadding().imePadding()
        ) {
            CenterAlignedTopAppBar(
                title = {
                    Text(stringResource(R.string.account), style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold, color = Brand1)
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_back), tint = Brand1)
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = Color.Transparent),
            )

            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)) {
                Spacer(Modifier.height(8.dp))
                Card(
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = scheme.surface),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(20.dp)) {
                        Text(stringResource(R.string.premium), fontSize = 18.sp, fontWeight = FontWeight.Bold, color = scheme.onSurface)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            stringResource(if (isPaid) R.string.tabs_active else R.string.tabs_not_active),
                            fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                            color = if (isPaid) scheme.primary else scheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(14.dp))
                        val who = signedIn
                        if (who != null) {
                            Text(stringResource(R.string.tabs_signed_in_as, who), fontSize = 14.sp, color = scheme.onSurface)
                        } else if (sentTo == null) {
                            Text(stringResource(R.string.web_account_body, HOST), fontSize = 14.sp, color = scheme.onSurfaceVariant)
                            Spacer(Modifier.height(12.dp))
                            OutlinedTextField(
                                value = input, onValueChange = { input = it.take(254); error = null },
                                singleLine = true, enabled = !busy,
                                label = { Text(stringResource(R.string.web_account_email)) },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Send),
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Spacer(Modifier.height(12.dp))
                            Button(onClick = { sendCode() }, enabled = !busy && input.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
                                Text(stringResource(R.string.web_account_send_code))
                            }
                        } else {
                            Text(stringResource(R.string.web_account_code_sent, sentTo!!), fontSize = 14.sp, color = scheme.onSurfaceVariant)
                            Spacer(Modifier.height(12.dp))
                            OutlinedTextField(
                                value = code, onValueChange = { v -> code = v.filter { it.isDigit() }.take(6); error = null },
                                singleLine = true, enabled = !busy,
                                label = { Text(stringResource(R.string.web_account_code)) },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Spacer(Modifier.height(12.dp))
                            Button(onClick = { verify() }, enabled = !busy && code.length == 6, modifier = Modifier.fillMaxWidth()) {
                                Text(stringResource(R.string.web_account_sign_in))
                            }
                            TextButton(onClick = { sentTo = null; code = ""; error = null }, enabled = !busy) {
                                Text(stringResource(R.string.web_account_other_email))
                            }
                        }
                        error?.let {
                            Spacer(Modifier.height(10.dp))
                            Text(it, fontSize = 13.sp, color = scheme.error)
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
                OutlinedButton(onClick = { manageOnWeb() }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.web_account_manage, HOST))
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    /**
     * The Account tab's first section (RingsTabsRoot, `navigation: tabs`):
     * signed out, "Sign in"; signed in, the email and the plan. Both open
     * [Screen]. Then "Manage on <host>" (hand-off, else the site's /me).
     * Nothing at all where [available] is false (no host, China traffic), and
     * the Play twin is empty, so a Play dex has neither the row nor its strings.
     */
    @Composable
    fun AccountRows(vm: VpnViewModel) {
        val ctx = LocalContext.current
        if (!remember { available(ctx) }) return
        val scope = rememberCoroutineScope()
        val cid = remember { CashierIdentity.cid(ctx.applicationContext) }
        val isPaid by vm.isPaid.collectAsState()
        val showing by vm.showWebAccount.collectAsState()
        val email = remember(showing) { WebAccountPrefs.email(ctx) }
        var busy by remember { mutableStateOf(false) }
        val scheme = MaterialTheme.colorScheme
        com.rostamvpn.android.ui.screens.ShopSection {
            com.rostamvpn.android.ui.screens.ShopRow(onClick = { vm.openAccount() }) {
                androidx.compose.material3.Icon(
                    androidx.compose.ui.res.painterResource(R.drawable.ic_rv_account), null,
                    tint = scheme.onSurfaceVariant, modifier = Modifier.size(28.dp),
                )
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    if (email != null) {
                        Text(email, color = scheme.onSurface, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.MiddleEllipsis)
                        Text(stringResource(if (isPaid) R.string.acct_premium else R.string.acct_free_plan),
                            style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                    } else {
                        Text(stringResource(R.string.acct_sign_in), color = scheme.onSurface)
                        Text(stringResource(R.string.acct_sign_in_sub), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                    }
                }
                com.rostamvpn.android.ui.screens.Chevron()
            }
            com.rostamvpn.android.ui.screens.ShopRow(divider = false, onClick = {
                if (busy) return@ShopRow
                busy = true
                scope.launch {
                    val url = CashierApi.webHandoffUrl(cid) ?: "https://$HOST/me"
                    openInBrowser(ctx, url)
                    busy = false
                }
            }) {
                androidx.compose.material3.Icon(
                    androidx.compose.ui.res.painterResource(R.drawable.ic_rv_open), null,
                    tint = scheme.onSurfaceVariant, modifier = Modifier.size(22.dp),
                )
                Spacer(Modifier.width(14.dp))
                Text(stringResource(R.string.acct_manage_on, HOST), Modifier.weight(1f), color = scheme.onSurface)
                if (busy) androidx.compose.material3.CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            }
        }
    }
}
