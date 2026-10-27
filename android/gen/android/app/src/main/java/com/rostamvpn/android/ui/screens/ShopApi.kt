package com.rostamvpn.android.ui.screens

import android.content.Context
import com.rostamvpn.android.BuildConfig
import com.rostamvpn.android.R
import com.rostamvpn.android.billing.CashierIdentity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/**
 * What the native eSIM store adds to [PassApi]'s catalogue client: orders,
 * the card and stablecoin rails, order polling and discount checks, in the
 * shapes of e-sim.net's docs/API-NATIVE.md. The iOS twin is ESimShopAPI.swift;
 * the two must stay on the same wire.
 *
 * No new endpoint and no new field: every call below is one the deployed
 * server already answers for the iOS app. Bodies carry `device_id` = the
 * cashier cid, the same identity My eSIMs and the access code already use.
 */

/** POST /v1/orders. Card: [clientSecret] + [publishableKey]. `fake` = a local dev server. */
internal data class ShopOrder(
    val orderId: String,
    val amountCents: Long,
    val clientSecret: String?,
    val publishableKey: String?,
    val fake: Boolean,
)

/** GET /v1/orders/{id}. */
internal data class ShopOrderState(
    val orderId: String,
    val status: String,
    val esims: List<Esim>,
    val error: String,
    val cryptoTx: String,
    val planIsNumber: Boolean?,
) {
    val isPaid get() = status == "paid" || status == "provisioned"
    val isFailed get() = status == "failed" || status == "refunded"
}

/** POST /v1/orders/{id}/onchain: the inline stablecoin panel. */
internal data class OnchainPanel(
    val orderId: String,
    val rail: String,
    val token: String,
    val network: String,
    /** Exact, unique per order: send precisely this. A string, never a float. */
    val amount: String,
    val address: String,
    /** EIP-681 on usdc-base; "" on usdt-tron (no wallet-URI standard there). */
    val uri: String,
    val expiresAt: String,
    val rails: List<String>,
) {
    /** What the QR encodes: the server's own choice (onchain.qr_svg). */
    val qrPayload get() = uri.ifEmpty { address }
}

internal data class DiscountCheck(val ok: Boolean, val reason: String?, val code: String?, val discountCents: Long?, val totalCents: Long?)

internal data class ShopRecommendation(val plan: PassPlan, val reason: String, val tip: String?)

/** A call's outcome: the parsed value, or the sentence to show. */
internal sealed class Shop<out T> {
    data class Ok<T>(val value: T) : Shop<T>()
    data class Err(val code: Int, val message: String) : Shop<Nothing>()
}

/**
 * The sentence for a failed call, as iOS's `esimMessage`: the server's own
 * `error` / `detail` for a client error, our words for the rest.
 */
internal fun shopMessage(ctx: Context, code: Int, body: String?): String {
    if (code in 400..499) {
        val m = runCatching { JSONObject(body ?: "").let { it.optString("error").ifBlank { it.optString("detail") } } }.getOrNull()
        if (code == 429) return ctx.getString(R.string.shop_too_many)
        if (!m.isNullOrBlank()) return m.replaceFirstChar { it.uppercase() }.let { if (it.endsWith(".")) it else "$it." }
    }
    return ctx.getString(if (code == -1) R.string.tabs_load_failed else R.string.shop_unexpected)
}

private suspend fun <T> call(ctx: Context, path: String, body: String?, method: String, parse: (JSONObject) -> T): Shop<T> {
    val (code, text) = post(path, body, method)
    if (code !in 200..299) return Shop.Err(code, shopMessage(ctx, code, text))
    return runCatching { Shop.Ok(parse(JSONObject(text ?: "{}"))) }
        .getOrElse { Shop.Err(0, ctx.getString(R.string.shop_unexpected)) }
}

/** Attribution as the server parses it (`api._utm`): the app, never a VPN perk claim. */
internal fun shopUtm(): String = "utm_source=${BuildConfig.CASHIER_BRAND}&utm_medium=android"

internal object ShopApi {
    /** One order per Pay tap (the server rate-limits creation). `pay` card also backs the stablecoin rail. */
    suspend fun createOrder(ctx: Context, plan: PassPlan, deviceId: String, iccid: String?, code: String?): Shop<ShopOrder> {
        val b = JSONObject().put("plan_slug", plan.slug).put("device_id", deviceId).put("utm", shopUtm()).put("pay", "card")
        if (!iccid.isNullOrBlank()) b.put("iccid", iccid)
        if (!code.isNullOrBlank()) b.put("code", code)
        return call(ctx, "/orders", b.toString(), "POST") { o ->
            ShopOrder(
                orderId = o.getString("order_id"),
                amountCents = o.optLong("amount_cents"),
                clientSecret = o.optString("client_secret").takeIf { it.isNotBlank() && it != "null" },
                publishableKey = o.optString("publishable_key").takeIf { it.isNotBlank() && it != "null" },
                fake = o.optBoolean("fake"),
            )
        }
    }

    suspend fun order(ctx: Context, id: String): Shop<ShopOrderState> =
        call(ctx, "/orders/${android.net.Uri.encode(id)}", null, "GET") { o ->
            ShopOrderState(
                orderId = o.optString("order_id"),
                status = o.optString("status"),
                esims = runCatching { parseEsims(o.toString()) }.getOrDefault(emptyList()),
                error = o.optString("error"),
                cryptoTx = o.optString("crypto_tx"),
                planIsNumber = o.optJSONObject("plan")?.optString("level")?.takeIf { it.isNotBlank() }?.let { it != "data" },
            )
        }

    suspend fun onchain(ctx: Context, orderId: String, rail: String): Shop<OnchainPanel> =
        call(ctx, "/orders/${android.net.Uri.encode(orderId)}/onchain?rail=${android.net.Uri.encode(rail)}", "", "POST") { o ->
            OnchainPanel(
                orderId = o.optString("order_id"), rail = o.optString("rail"), token = o.optString("token"),
                network = o.optString("network"), amount = o.optString("amount"), address = o.optString("address"),
                uri = o.optString("uri"), expiresAt = o.optString("expires_at"),
                rails = o.optJSONArray("rails")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty(),
            )
        }

    /** Coinbase's hosted page for the same order (other coins). Direct builds only. */
    suspend fun coinbase(ctx: Context, orderId: String): Shop<Pair<String, Boolean>> =
        call(ctx, "/orders/${android.net.Uri.encode(orderId)}/crypto", "", "POST") { o ->
            val u = o.optString("pay_url")
            val abs = if (u.startsWith("/")) BuildConfig.ESIM_BASE + u else u
            abs to o.optBoolean("fake")
        }

    suspend fun checkDiscount(ctx: Context, code: String, plan: PassPlan, deviceId: String): Shop<DiscountCheck> =
        call(ctx, "/discounts/check?code=${android.net.Uri.encode(code)}&plan_slug=${android.net.Uri.encode(plan.slug)}&device_id=${android.net.Uri.encode(deviceId)}", null, "GET") { o ->
            DiscountCheck(
                ok = o.optBoolean("ok"),
                reason = o.optString("reason").takeIf { it.isNotBlank() && it != "null" },
                code = o.optString("code").takeIf { it.isNotBlank() && it != "null" },
                discountCents = if (o.has("discount_cents") && !o.isNull("discount_cents")) o.optLong("discount_cents") else null,
                totalCents = if (o.has("total_cents") && !o.isNull("total_cents")) o.optLong("total_cents") else null,
            )
        }

    suspend fun recommend(ctx: Context, dest: String, days: Int, usage: String): Shop<ShopRecommendation> =
        call(ctx, CatalogPaths.recommend(dest, days, usage), null, "GET") { o ->
            ShopRecommendation(parsePlan(o.getJSONObject("plan").toString()), o.optString("reason"), o.optString("tip").takeIf { it.isNotBlank() && it != "null" })
        }

    suspend fun destination(ctx: Context, id: String): Shop<Pair<PassDest, List<PassPlan>>> =
        call(ctx, CatalogPaths.destination(id), null, "GET") { o ->
            parseDestinationHead(o) to parsePlans(o.toString())
        }

    suspend fun search(q: String): List<PassDest>? = fetchSearch(q)

    suspend fun accessCode(ctx: Context, deviceId: String): Shop<String> =
        call(ctx, "/account/code", JSONObject().put("device_id", deviceId).toString(), "POST") { it.getString("code") }

    suspend fun claim(ctx: Context, code: String, deviceId: String): Shop<Int> =
        call(ctx, "/account/claim", JSONObject().put("code", code.trim().uppercase()).put("device_id", deviceId).toString(), "POST") { it.optInt("moved") }

    /**
     * Fake-mode server only (no Stripe keys, replayed Airalo): pretend the
     * payment succeeded, as the site's "Simulate payment" does. The server
     * answers 404 to this everywhere else, and release builds never call it.
     */
    suspend fun devPay(ctx: Context, orderId: String): Shop<Unit> =
        call(ctx, "/dev/pay/${android.net.Uri.encode(orderId)}", "", "POST") { }
}

/** A destination's head (`GET /v1/destinations/{id}` without its plans). Pure. */
internal fun parseDestinationHead(o: JSONObject): PassDest =
    parseCatalog(JSONObject().put("countries", org.json.JSONArray().put(o)).toString()).countries.first()

/** "🇯🇵" from "JP"; null when it is not an ISO-2 code. */
internal fun flagOf(iso: String): String? {
    val up = iso.uppercase()
    if (up.length != 2 || up.any { it !in 'A'..'Z' }) return null
    return String(Character.toChars(0x1F1E6 + (up[0] - 'A'))) + String(Character.toChars(0x1F1E6 + (up[1] - 'A')))
}

/** USD with cents always ("$4.99", "$5.00"), for totals and per-day/GB values. */
internal fun usdExact(cents: Long): String = String.format(java.util.Locale.US, "$%.2f", cents / 100.0)

/**
 * The shop's shared state: catalogue, collections and this device's eSIMs,
 * fetched once and kept for every screen of the tab. Process-wide like iOS's
 * ESimStore.shared; the screens observe the flows.
 */
internal object ShopStore {
    private val _catalog = MutableStateFlow<PassCatalog?>(null)
    val catalog: StateFlow<PassCatalog?> = _catalog.asStateFlow()
    private val _catalogFailed = MutableStateFlow(false)
    val catalogFailed: StateFlow<Boolean> = _catalogFailed.asStateFlow()
    private val _collections = MutableStateFlow<List<PassCollection>>(emptyList())
    val collections: StateFlow<List<PassCollection>> = _collections.asStateFlow()
    private val _sims = MutableStateFlow<List<Esim>?>(null)
    val sims: StateFlow<List<Esim>?> = _sims.asStateFlow()
    private val _simsFailed = MutableStateFlow(false)
    val simsFailed: StateFlow<Boolean> = _simsFailed.asStateFlow()
    private val details = HashMap<String, Pair<PassDest, List<PassPlan>>>()
    @Volatile private var catalogAt = 0L

    fun deviceId(ctx: Context): String = CashierIdentity.cid(ctx.applicationContext)

    suspend fun loadCatalog(force: Boolean = false) {
        if (!force && _catalog.value != null && System.currentTimeMillis() - catalogAt < 600_000) return
        val c = fetchCatalog()
        if (c != null) { _catalog.value = c; catalogAt = System.currentTimeMillis(); _catalogFailed.value = false }
        else if (_catalog.value == null) _catalogFailed.value = true
    }

    /** Separate on purpose: an empty or failing collections call never holds up the store. */
    suspend fun loadCollections(lang: String?) {
        fetchCollections(lang)?.let { _collections.value = it }
    }

    fun cached(id: String): Pair<PassDest, List<PassPlan>>? = details[id]

    suspend fun destination(ctx: Context, id: String, force: Boolean = false): Shop<Pair<PassDest, List<PassPlan>>> {
        if (!force) details[id]?.let { return Shop.Ok(it) }
        val r = ShopApi.destination(ctx, id)
        if (r is Shop.Ok) details[id] = r.value
        return r
    }

    suspend fun loadSims(ctx: Context) {
        val list = fetchEsims(deviceId(ctx))
        if (list != null) {
            _sims.value = list
            _simsFailed.value = false
            if (list.isNotEmpty()) setOwned(ctx)
        } else {
            if (_sims.value == null) _sims.value = emptyList()
            _simsFailed.value = true
        }
    }

    fun track(ctx: Context, name: String, dest: String = "", props: Map<String, Any?> = emptyMap()) =
        EsimEvents.track(deviceId(ctx), name, dest, props)

    // ── Who may buy ───────────────────────────────────────────────────────
    // iOS ESimStore.canPurchase: no purchase UI at all in a freedom region
    // (IR/RU by config) or for China traffic. Existing eSIMs still show and
    // install there, which is why the tab survives for someone who owns one.

    private const val PREFS = "esim_shop"
    private const val KEY_OWNED = "owned"
    private val CN_TZ = setOf("Asia/Shanghai", "Asia/Urumqi", "Asia/Chongqing", "Asia/Harbin", "Asia/Kashgar", "PRC")

    /** China timezone OR China SIM/network/store country: the generous test the CN rail and the sign-in gate use. */
    fun chinaTraffic(ctx: Context): Boolean =
        java.util.TimeZone.getDefault().id in CN_TZ ||
            runCatching { com.rostamvpn.android.billing.RegionDetector.detectCountry(ctx, null) }.getOrNull().equals("CN", ignoreCase = true)

    fun hasOwned(ctx: Context): Boolean =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_OWNED, false)

    private fun setOwned(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_OWNED, true).apply()
}
