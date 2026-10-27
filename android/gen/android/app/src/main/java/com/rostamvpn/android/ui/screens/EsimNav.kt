package com.rostamvpn.android.ui.screens

import com.rostamvpn.android.BuildConfig
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * One route into the eSIM tab, shared by the alert notifications (B) and the
 * deep links (C): open the tab, optionally My eSIMs at one card, optionally
 * straight into its reload sheet. MainActivity sets it from an Intent; the
 * tabs treatment consumes it. A brand without the tabs treatment never reads
 * it, so setting it there is inert.
 */
internal data class EsimRoute(
    val iccid: String? = null,
    val reload: Boolean = false,
    val mine: Boolean = iccid != null,
    /** Store: open this destination (catalogue id or the site's slug). */
    val dest: String? = null,
    /** Store: the destination's number plans (`/number/{id}`). */
    val number: Boolean = false,
    /** Store: a seasonal collection (`/c/{id}`). */
    val collection: String? = null,
    /** My eSIMs: a finished order (`/install/{id}`), waited for and opened. */
    val orderId: String? = null,
)

/** Route words of e-sim.net (and esimnet://) that the eSIM tab handles. */
private val ROUTE_WORDS = setOf("esim", "go", "c", "number", "topup", "install", "me")
private val LOCALE = Regex("^[a-z]{2,3}(-[A-Za-z]{2,4})?$")

/**
 * A deep link into the eSIM tab (C7), or null when it is not one. Pure over
 * the URI's parts so it is unit-tested. `esimnet://{word}/{id}` and
 * `https://e-sim.net[/{locale}]/{word}/{id}`; any other path on the site
 * opens the store.
 */
internal fun esimRouteFor(
    scheme: String?,
    host: String?,
    segments: List<String>,
    /** The store's host. Defaults to the brand's own (empty when it sells no
     *  eSIM, which makes every https route below unroutable) and is passed
     *  explicitly by the tests, so the literal lives in the test, not here. */
    siteHost: String = ESIM_HOST,
): EsimRoute? {
    val words: List<String> = when {
        scheme == "esimnet" -> listOfNotNull(host) + segments
        scheme == "https" && siteHost.isNotEmpty() &&
            host?.lowercase().let { it == siteHost || it == "www.$siteHost" } ->
            if (segments.size >= 2 && segments[0] !in ROUTE_WORDS && LOCALE.matches(segments[0])) segments.drop(1) else segments
        else -> return null
    }.filter { it.isNotBlank() }
    val id = words.getOrNull(1)
    return when (words.firstOrNull()) {
        "esim", "go" -> EsimRoute(dest = id)
        "number" -> EsimRoute(dest = id, number = true)
        "c" -> EsimRoute(collection = id)
        "topup" -> if (id != null) EsimRoute(iccid = id, reload = true) else EsimRoute(mine = true)
        "install" -> EsimRoute(mine = true, orderId = id)
        "me" -> EsimRoute(mine = true)
        else -> EsimRoute()
    }
}

internal object EsimNav {
    const val EXTRA_ICCID = "esim_iccid"
    const val EXTRA_RELOAD = "esim_reload"
    const val EXTRA_OPEN = "esim_open"

    /** Set by MainActivity before any intent is read (for the session store). */
    @Volatile var appCtx: Context? = null

    private val _route = MutableStateFlow<EsimRoute?>(null)
    val route: StateFlow<EsimRoute?> = _route.asStateFlow()

    fun open(r: EsimRoute) { _route.value = r }
    fun consumed() { _route.value = null }

    /** Read our own extras, or an eSIM deep link, from an Intent. True when it was an eSIM route. */
    fun fromIntent(intent: Intent?): Boolean {
        intent ?: return false
        val u = intent.data
        // Deep links (C7). Only the internetpro manifest declares these
        // filters, so other brands never receive such a URI.
        if (com.rostamvpn.android.BuildConfig.FEATURE_ESIM && intent.action == Intent.ACTION_VIEW && u != null) {
            val r = esimRouteFor(u.scheme, u.host, u.pathSegments.orEmpty())
            if (r != null) {
                // D: sign-in returns on esimnet://me#session=… (or the https /me
                // link). Stored only if this app started that sign-in within
                // the last 15 minutes, once; a cold link is dropped silently
                // (EsimSession.offer). Either way it just opens My eSIMs.
                if (r.mine && r.orderId == null && r.iccid == null) {
                    sessionFromFragment(u.fragment)?.let { t -> appCtx?.let { c -> EsimSession.offer(c, t) } }
                }
                open(r)
                intent.data = null
                return true
            }
        }
        if (!intent.getBooleanExtra(EXTRA_OPEN, false)) return false
        open(EsimRoute(intent.getStringExtra(EXTRA_ICCID), intent.getBooleanExtra(EXTRA_RELOAD, false)))
        intent.removeExtra(EXTRA_OPEN)
        return true
    }
}

/**
 * The alerts scheduler, reached from shared code without a WorkManager
 * dependency in every brand: the implementation lives in the internetpro
 * source set (EsimAlertsImpl) and is found by name. Elsewhere this is a no-op.
 */
internal object EsimAlerts {
    private val impl: Any? by lazy {
        runCatching { Class.forName("com.rostamvpn.android.EsimAlertsImpl").getField("INSTANCE").get(null) }.getOrNull()
    }

    /** Schedule the periodic check while the device has at least one eSIM; cancel otherwise. */
    fun sync(ctx: Context, hasEsims: Boolean) {
        val i = impl ?: return
        runCatching {
            i.javaClass.getMethod("sync", Context::class.java, Boolean::class.javaPrimitiveType).invoke(i, ctx.applicationContext, hasEsims)
        }
    }

    val available: Boolean get() = impl != null
}

/**
 * Chrome Custom Tabs without the androidx.browser library: the protocol is a
 * VIEW intent carrying the session extra (a null binder means "no session"),
 * sent to a browser that exports the CustomTabsService. No dependency reaches
 * any brand; only internetpro declares the <queries> that let it see browsers
 * on API 30+, so elsewhere [provider] finds nothing and callers fall back.
 *
 * Used for e-sim.net checkout (C11): Google Pay renders in a real browser,
 * usually not in a WebView.
 */
internal object CustomTab {
    private const val SERVICE = "android.support.customtabs.action.CustomTabsService"
    private const val EXTRA_SESSION = "android.support.customtabs.extra.SESSION"

    /** The default browser if it supports Custom Tabs, else any that does; null when none. */
    fun provider(ctx: Context): String? = runCatching {
        val pm = ctx.packageManager
        val supports = pm.queryIntentServices(Intent(SERVICE), 0).mapNotNull { it.serviceInfo?.packageName }.toSet()
        if (supports.isEmpty()) return@runCatching null
        // Any https URI resolves the user's default browser; this used to be
        // the eSIM store's, which put that host in the dex of brands that do
        // not sell eSIM. The brand's own privacy URL is already compiled in.
        val default = pm.resolveActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(BuildConfig.PRIVACY_URL)), 0)
            ?.activityInfo?.packageName
        default?.takeIf { it in supports } ?: supports.firstOrNull { it == "com.android.chrome" } ?: supports.first()
    }.getOrNull()

    /** Open [url] in a Custom Tab of [pkg]. False when it could not be started. */
    fun open(ctx: Context, url: String, pkg: String): Boolean = runCatching {
        val i = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)).setPackage(pkg)
            .putExtras(android.os.Bundle().apply { putBinder(EXTRA_SESSION, null) })
        if (ctx.findActivity() == null) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(i)
        true
    }.getOrDefault(false)
}
