// Copyright 2026 Internet Mastering & Company, Inc.
// SPDX-License-Identifier: Apache-2.0

package com.rostamvpn.android.billing

import android.util.Log
import com.rostamvpn.android.RedSocketBridge
import com.rostamvpn.android.VpnViewModel
import com.rostamvpn.android.WgtcpBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL

/** The one networking layer for the cashier. The app talks ONLY to this host. */
object CashierApi {
    private const val TAG = "CashierApi"

    /** Cashier host, from config. */
    val BASE: String
        get() = com.rostamvpn.android.ConfigManager.apiConfig.bases.firstOrNull()
            ?: com.rostamvpn.android.BuildConfig.CASHIER_BASE
    private val BRAND: String = com.rostamvpn.android.BuildConfig.CASHIER_BRAND

    private val PKG: String = com.rostamvpn.android.BuildConfig.APPLICATION_ID

    data class PayOrder(val orderNo: String, val payUrl: String, val status: String)
    data class Plan(val slug: String, val label: String, val price: String)
    data class Registration(val tier: String, val product: String, val paidUntil: String) {
        val isPaid: Boolean get() = tier.equals("paid", true)
    }
    /**
     * Free-data status (region-driven, cashier-side). The NODE meters bytes →
     * cashier; the app only READS this for display + the over-quota paywall.
     */
    data class UsageStatus(
        val usedMb: Long, val freeQuotaMb: Long, val remainingMb: Long,
        val overQuota: Boolean, val unlimited: Boolean, val paid: Boolean,

        val regionSelect: String = "on",
        /** Days until the free allowance refills; 0 = NEVER (a one-time trial). */
        val periodDays: Int = 30,
    ) {
        /** Does the free allowance come back? False = one-time. */
        val renews: Boolean get() = periodDays > 0
        /** Whether the region picker should be unlocked for this user right now. */
        val canSelectRegion: Boolean
            get() = paid || regionSelect == "on" || (regionSelect == "until_quota" && !overQuota)
    }
    /**
     * Rails the cashier says are usable right now — the app gates UI on these.
     * `stripe` is the card rail and `crypto`/`nowpayments` the self-sovereign ones.
     */
    data class PayMethods(
        val crypto: Boolean, val googleIap: Boolean,
        val stripe: Boolean = false, val nowpayments: Boolean = false,
    ) {
        /** Either crypto processor is enough to render the crypto rail. */
        val anyCrypto: Boolean get() = crypto || nowpayments
    }

    /**
     * First-launch registration → server-truth tier for this cid. Null on error.
     * Optional [ifa] (the GAID) and [lmt] (Limit-Ad-Tracking) are passed through
     * to the cashier as sent.
     */
    suspend fun registerCid(
        cid: String,
        tz: String,
        ifa: String? = null,
        lmt: Int = 0,
    ): Registration? = withContext(Dispatchers.IO) {
        val body = JSONObject().apply {
            put("cid", cid)
            put("brand", BRAND)
            put("tz", tz)
            if (!ifa.isNullOrBlank()) put("ifa", ifa)
            put("lmt", lmt)
        }
        val r = postJson("$BASE/api/v1/cid/register", body, Route.TUNNEL) ?: return@withContext null
        Registration(r.optString("tier", "free"), r.optString("product"), r.optString("paid_until"))
    }

    /**
     * Report cumulative data used for this cid (app is the meter; last-write-
     * wins).
     */
    /** Report the cumulative total AND return the fresh status, in ONE round trip. */
    suspend fun reportUsage(cid: String, usedMb: Long): UsageStatus? = withContext(Dispatchers.IO) {
        val body = JSONObject().apply { put("cid", cid); put("brand", BRAND); put("used_mb", usedMb) }
        parseUsage(postJson("$BASE/api/v1/usage", body))
    }

    /** Free-data status for this cid. */
    suspend fun usage(cid: String): UsageStatus? = withContext(Dispatchers.IO) {
        parseUsage(getJson("$BASE/api/v1/usage?cid=$cid"))
    }

    /**
     * ONE parse for both, so the two shapes cannot drift apart -- they are the
     * same server-side object (`_status_counted`).
     */
    private fun parseUsage(r: JSONObject?): UsageStatus? {
        if (r == null) return null
        return UsageStatus(
            r.optLong("used_mb"), r.optLong("free_quota_mb"), r.optLong("remaining_mb"),
            r.optBoolean("over_quota"), r.optBoolean("unlimited"), r.optBoolean("paid"),
            r.optString("region_select", "on"),
            r.optInt("period_days", 30),
        )
    }

    /**
     * Restore/bridge: this cid inherits the code's entitlement. True if now
     * entitled.
     */
    suspend fun claim(code: String, cid: String): Boolean = withContext(Dispatchers.IO) {
        val body = JSONObject().apply { put("code", code); put("cid", cid) }
        val r = postJson("$BASE/api/v1/auth/claim", body) ?: return@withContext false
        r.optBoolean("ok", false) && r.optBoolean("entitled", false)
    }

    /** Which payment rails are live. App shows only enabled ones (don't hardcode). */
    suspend fun payMethods(): PayMethods? = withContext(Dispatchers.IO) {
        val r = getJson("$BASE/api/v1/pay/methods") ?: return@withContext null
        fun on(k: String) = r.optJSONObject(k)?.optBoolean("enabled", false) ?: false
        PayMethods(
            crypto = on("crypto"), googleIap = on("google_iap"),
            stripe = on("stripe"), nowpayments = on("nowpayments"),
        )
    }

    /**
     * POST /api/v1/iap/verify → true when the cashier confirms the cid is
     * entitled.
     */
    suspend fun iapVerify(cid: String, productId: String, token: String): Boolean =
        withContext(Dispatchers.IO) {
            val body = JSONObject().apply {
                put("platform", "google")
                put("cid", cid)
                put("product_id", productId)
                put("plan_slug", productId)
                put("token", token)
                put("brand", BRAND)
                put("package_name", PKG)
            }

            val resp = postJson("$BASE/api/v1/iap/verify", body, Route.ANY)
                ?: return@withContext false
            resp.optBoolean("ok", false) && resp.optBoolean("entitled", false)
        }

    /**
     * GET /api/v1/plans?brand&region — ALWAYS pass a region: empty returns every
     * plan incl. CN (CNY), which then leaks onto non-CN paywalls.
     */
    suspend fun plans(region: String): List<Plan> = withContext(Dispatchers.IO) {
        val url = "$BASE/api/v1/plans?brand=$BRAND&region=$region"
        val resp = getJson(url) ?: return@withContext emptyList()
        val arr = resp.optJSONArray("plans") ?: return@withContext emptyList()
        (0 until arr.length()).mapNotNull { i ->
            arr.optJSONObject(i)?.let {
                val slug = it.optString("plan_slug", it.optString("slug"))
                if (slug.isEmpty()) return@let null
                val currency = it.optString("currency", "USD")
                val amount = it.optDouble("amount_display", it.optDouble("amount_cents", 0.0) / 100.0)
                Plan(slug = slug, label = it.optString("label", it.optString("name")),
                     price = formatPrice(amount, currency))
            }
        }
    }

    private fun formatPrice(amount: Double, currency: String): String {
        val sym = when (currency.uppercase()) { "USD" -> "$"; "CNY" -> "¥"; "EUR" -> "€"; else -> "" }
        val n = if (amount == amount.toLong().toDouble()) amount.toLong().toString() else "%.2f".format(amount)
        return if (sym.isNotEmpty()) "$sym$n" else "$n $currency"
    }

    /**
     * POST /api/v1/pay/create → an order with a pay_url to open. `channel`:
     * "stripe_hosted" | "crypto" | "nowpayments".
     */
    suspend fun payCreate(planSlug: String, cid: String, channel: String,
                          payMethod: String = ""): PayOrder? =
        withContext(Dispatchers.IO) {
            val body = JSONObject().apply {
                put("plan_slug", planSlug)
                put("cid", cid)
                put("brand", BRAND)
                put("channel", channel)
                put("pay_method", payMethod)
            }
            val resp = postJson("$BASE/api/v1/pay/create", body) ?: return@withContext null
            val orderNo = resp.optString("order_no")
            val payUrl = resp.optString("pay_url")
            if (orderNo.isEmpty() || payUrl.isEmpty()) null
            else PayOrder(orderNo, payUrl, resp.optString("status", "pending"))
        }

    /** GET /api/v1/pay/status?order_no → true once paid. */
    suspend fun payStatus(orderNo: String): Boolean = withContext(Dispatchers.IO) {
        val resp = getJson("$BASE/api/v1/pay/status?order_no=$orderNo") ?: return@withContext false
        resp.optBoolean("paid", false)
    }

    /** Our own tunnel is up, so a cashier call goes through it (pinned path). */
    fun tunnelUp(): Boolean {
        val rs = RedSocketBridge.isLoaded &&
            (try { RedSocketBridge.isRunning() != 0 } catch (_: Throwable) { false })
        if (rs) return true
        return try { WgtcpBridge.isRunning() } catch (_: Throwable) { false }
    }

    /** True when the device already holds a VPN profile for us. */
    private fun hasTunnelProfile(): Boolean = try {
        com.rostamvpn.android.MainActivity.instance?.applicationContext?.let {
            android.net.VpnService.prepare(it) == null
        } ?: false
    } catch (_: Throwable) { false }

    /** When a call may leave the device while the tunnel is down. */
    internal enum class Route {
        /** Always. Exactly one caller may use this -- see `iapVerify`. */
        ANY,

        /** Until the device holds a VPN profile for us. */
        UNTIL_PROFILE,

        /** Never: the call waits for the tunnel, even on first run. */
        TUNNEL,
    }

    /** True when a call on [route] must wait for the tunnel instead of going out directly. */
    internal fun deferred(route: Route, paidOnly: Boolean, tunnelUp: Boolean, hasProfile: () -> Boolean): Boolean =
        !paidOnly && !tunnelUp && when (route) {
            Route.ANY -> false
            Route.UNTIL_PROFILE -> hasProfile()
            Route.TUNNEL -> true
        }

    private fun open(url: String, route: Route = Route.UNTIL_PROFILE): HttpURLConnection {
        if (deferred(route, com.rostamvpn.android.BuildConfig.PAID_ONLY, tunnelUp(), ::hasTunnelProfile)) {
            throw java.io.IOException("cashier deferred: tunnel down")
        }
        val u = URL(url)
        val conn = if (tunnelUp()) {
            u.openConnection(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", VpnViewModel.activeSocksPort())))
        } else {
            u.openConnection()
        } as HttpURLConnection
        conn.connectTimeout = 15000
        conn.readTimeout = 25000
        conn.setRequestProperty("Accept", "application/json")
        conn.setRequestProperty("X-App-Key", BRAND)

        conn.setRequestProperty("Connection", "close")
        return conn
    }

    private fun postJson(url: String, body: JSONObject, route: Route = Route.UNTIL_PROFILE): JSONObject? = try {
        val conn = open(url, route)
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        readJson(conn)
    } catch (e: Exception) {
        Log.w(TAG, "POST ${url.substringBefore('?')} failed: ${e.message}"); null
    }

    private fun getJson(url: String): JSONObject? = try {
        val conn = open(url)
        conn.requestMethod = "GET"
        readJson(conn)
    } catch (e: Exception) {
        Log.w(TAG, "GET ${url.substringBefore('?')} failed: ${e.message}"); null
    }

    private fun readJson(conn: HttpURLConnection): JSONObject? {
        return try {
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code in 200..299 && text.isNotEmpty()) JSONObject(text) else null
        } finally {
            conn.disconnect()
        }
    }
}
