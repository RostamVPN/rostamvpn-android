package com.rostamvpn.android.ui.screens

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.rostamvpn.android.BuildConfig
import com.rostamvpn.android.R
import com.rostamvpn.android.billing.CashierIdentity
import com.rostamvpn.android.ui.theme.Brand1

/**
 * The eSIM store, shown only where `BuildConfig.FEATURE_ESIM` is true.
 *
 * The site is the product: it already does destinations, the plan picker,
 * checkout, install and "My eSIMs" for the web and iOS. This screen hosts it
 * and adds only what an app has to own:
 *
 *  - IDENTITY. The site keys everything on `device_id` (16..64 lowercase hex,
 *    `site.js:17`), and stores it in localStorage. We pass the cashier cid,
 *    which is 16 hex, so a user's eSIMs follow the same id their subscription
 *    does. The site ignores `cid=`, which is why it is not used here.
 *  - NAVIGATION POLICY. Top-level pages stay on e-sim.net and Stripe. Anything
 *    else opens in the system browser, so the WebView can never become a
 *    general browser inside the app.
 *  - THE PLAY PAYMENTS GATE. In the `play` flavour (`WEB_PAY_ENABLED=false`)
 *    a navigation to the checkout or top-up page is refused and a neutral
 *    notice is shown instead. Play's payments policy on eSIM checkout inside
 *    an app is unresolved, and this listing shares a developer account with a
 *    much larger one. The notice deliberately does not point anywhere else to
 *    pay: that pointer is itself a policy violation (steering).
 *
 * The app excludes itself from its own tunnel, so this WebView talks to the
 * site directly, never through the VPN.
 */
/** The store's origin and host come from the brand manifest
 *  (BuildConfig.ESIM_BASE, set only when `features.esim` is true). Both are
 *  EMPTY on a brand that sells no eSIM, so the host is absent from that
 *  brand's dex rather than merely unreachable at runtime -- a `strings` dump
 *  does not care which screens are wired up. Not `const`: a const would be
 *  inlined into every call site, which is fine, but a plain val keeps the
 *  assembled URLs out of the constant pool too. */
private val ESIM_BASE: String = BuildConfig.ESIM_BASE
internal val ESIM_HOST: String = ESIM_BASE.substringAfter("://").trimEnd('/')

/** Top-level hosts the WebView may load. Subframes are not checked: Stripe's
 *  payment element renders in iframes on js.stripe.com / m.stripe.network. */
private val ALLOWED_HOSTS = listOf(ESIM_HOST, "stripe.com", "stripe.network").filter { it.isNotEmpty() }

/** Pages that take money. Gated in the play flavour. */
private val PAY_PAGES = setOf("checkout", "topup")

/** The site's locale prefix: /es, /zh-Hans, /pt. */
private val LOCALE_SEGMENT = Regex("^[a-z]{2,3}(-[A-Za-z]{2,4})?$")

internal fun esimStoreUrl(deviceId: String) =
    "$ESIM_BASE/?device_id=$deviceId&utm_source=${Uri.encode(BuildConfig.CASHIER_BRAND)}&utm_medium=android"

internal fun esimMineUrl(deviceId: String) = "$ESIM_BASE/me?device_id=$deviceId"

/** The store on one of its pages (`/esim/jp`, `/number/us`), same identity and attribution as the home. */
internal fun esimPageUrl(path: String, deviceId: String) =
    "$ESIM_BASE$path?device_id=$deviceId&utm_source=${Uri.encode(BuildConfig.CASHIER_BRAND)}&utm_medium=android"

/** Shared with the Passes checkout sheet: e-sim.net and Stripe only. */
internal fun hostAllowed(host: String?): Boolean {
    val h = host?.lowercase() ?: return false
    return ALLOWED_HOSTS.any { h == it || h.endsWith(".$it") }
}

/** Path segments with the site's optional locale prefix removed. */
private fun siteSegments(uri: Uri): List<String> {
    val segs = uri.pathSegments.orEmpty()
    return if (segs.size >= 2 && LOCALE_SEGMENT.matches(segs[0])) segs.drop(1) else segs
}

private fun isEsimHost(uri: Uri) = ESIM_HOST.isNotEmpty() &&
    uri.host?.lowercase().let { it == ESIM_HOST || it == "www.$ESIM_HOST" }

/** `esimnet://install/{id}` (what iOS returns on) or `https://e-sim.net/install/{id}`. */
internal fun isInstallUrl(uri: Uri): Boolean =
    (uri.scheme == "esimnet" && uri.host == "install") ||
        (isEsimHost(uri) && siteSegments(uri).firstOrNull() == "install")

/** The order id of an install return (`esimnet://install/{id}`, `/install/{id}`, any locale prefix). */
internal fun installOrderId(uri: Uri): String? = when {
    uri.scheme == "esimnet" && uri.host == "install" -> uri.pathSegments.orEmpty().firstOrNull()
    isEsimHost(uri) -> siteSegments(uri).takeIf { it.firstOrNull() == "install" }?.getOrNull(1)
    else -> null
}?.takeIf { it.isNotBlank() }

/** e-sim.net's account page, any locale prefix. A checkout landing here has finished (C6). */
internal fun isMeUrl(uri: Uri): Boolean = isEsimHost(uri) && siteSegments(uri) == listOf("me")

internal fun isPayPage(uri: Uri): Boolean =
    isEsimHost(uri) && siteSegments(uri).firstOrNull() in PAY_PAGES

@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun EsimScreen(startPath: String? = null, onBack: () -> Unit) {
    val context = LocalContext.current
    // Read directly, not through VpnViewModel.cid(), which answers "" when
    // MainActivity.instance is momentarily null; "" would make the site mint
    // its own random id and the user's eSIMs would land on a stranger key.
    val deviceId = remember { CashierIdentity.cid(context.applicationContext) }
    var mine by remember { mutableStateOf(false) }
    var progress by remember { mutableIntStateOf(0) }
    var payBlocked by remember { mutableStateOf(false) }

    val webView = remember {
        WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            // window.open() navigates in place instead of silently doing nothing.
            settings.setSupportMultipleWindows(false)
            // ponytail: third-party cookies on for this WebView only, because
            // Stripe 3-D Secure fails silently without them (direct flavour).
            // The play flavour never reaches Stripe; cost there is nil.
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView, newProgress: Int) {
                    progress = newProgress
                }
            }
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val uri = request.url
                    if (isInstallUrl(uri)) {
                        mine = true   // the native list, which can install
                        return true
                    }
                    if (!request.isForMainFrame) return false
                    if (!BuildConfig.WEB_PAY_ENABLED && isPayPage(uri)) {
                        payBlocked = true
                        return true
                    }
                    val web = uri.scheme == "https" || uri.scheme == "http"
                    if (web && hostAllowed(uri.host)) return false
                    // Everything else, including mailto:/tel: and Google sign-in
                    // (which Google refuses inside a WebView anyway), goes to the
                    // system. ponytail: signing in therefore completes in the
                    // browser, not here; the device_id link is what carries the
                    // user's eSIMs in the app.
                    try {
                        view.context.startActivity(
                            Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    } catch (e: ActivityNotFoundException) {
                        Log.w("EsimScreen", "no handler for ${uri.scheme}")
                    }
                    return true
                }

                // A redirect or a server-side 30x into checkout does not always
                // pass through shouldOverrideUrlLoading. Catch it on the way in.
                override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                    val uri = url?.let(Uri::parse) ?: return
                    if (!BuildConfig.WEB_PAY_ENABLED && isPayPage(uri)) {
                        view.stopLoading()
                        if (view.canGoBack()) view.goBack() else view.loadUrl(esimStoreUrl(deviceId))
                        payBlocked = true
                    }
                }

                // A dead renderer must not take the VPN app down with it.
                override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                    Log.w("EsimScreen", "renderer gone (crash=${detail.didCrash()})")
                    onBack()
                    return true
                }
            }
            loadUrl(startPath?.let { esimPageUrl(it, deviceId) } ?: esimStoreUrl(deviceId))
        }
    }

    DisposableEffect(webView) {
        onDispose {
            webView.stopLoading()
            webView.destroy()
        }
    }

    BackHandler {
        when {
            mine -> mine = false
            webView.canGoBack() -> webView.goBack()
            else -> onBack()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        CenterAlignedTopAppBar(
            title = {
                Text(
                    text = stringResource(R.string.esim_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = Brand1
                )
            },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.cd_back),
                        tint = Brand1
                    )
                }
            },
            actions = {
                FilterChip(
                    selected = mine,
                    onClick = { mine = !mine },
                    label = { Text(stringResource(R.string.esim_mine)) },
                    modifier = Modifier.padding(end = 8.dp)
                )
            },
            colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                containerColor = Color.Transparent
            )
        )
        Box(Modifier.fillMaxSize()) {
            AndroidView(factory = { webView }, modifier = Modifier.fillMaxSize())
            if (!mine && progress in 1..99) {
                LinearProgressIndicator(
                    progress = { progress / 100f },
                    modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter),
                    color = Brand1
                )
            }
            // The native list sits over the WebView, which stays alive so the
            // store keeps its place. "Account and restore" is the site's /me
            // (access code, sign-in, restore), which the native list does not redo.
            if (mine) {
                EsimMine(deviceId) {
                    mine = false
                    webView.loadUrl(esimMineUrl(deviceId))
                }
            }
        }
    }

    if (payBlocked) {
        AlertDialog(
            onDismissRequest = { payBlocked = false },
            text = { Text(stringResource(R.string.esim_purchase_unavailable)) },
            confirmButton = {
                TextButton(onClick = { payBlocked = false }) {
                    Text(stringResource(android.R.string.ok))
                }
            }
        )
    }
}
