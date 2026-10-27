package com.rostamvpn.android.billing

import android.net.Uri
import android.util.Log
import app.netcore.NetCoreBridge
import com.rostamvpn.android.WgtcpBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL

/**
 * The one networking layer for the cashier. The app talks ONLY to this host.
 *
 * Transport matches the rest of the app: java.net.HttpURLConnection + org.json
 * (no OkHttp/Retrofit/serialization dep — fewer libraries is the right call for a
 * censorship-tool APK). When the tunnel is up we route through its SOCKS5 proxy
 * (127.0.0.1:1080), mirroring VpnViewModel.sendDebugToServer — the cashier host
 * may be blocked on the raw network, so the tunnel is the reliable path.
 *
 * No secrets ship in the app; everything is brokered server-side.
 */
object CashierApi {
    private const val TAG = "CashierApi"
    // Brand-specific values come from the `brand` product flavor (BuildConfig), so
    // this file is brand-agnostic and a new brand is a Gradle change only.
    /**
     * Cashier host, from config.
     *
     * A getter, not a val: the config is loaded after this object is first
     * touched, and capturing the compiled-in value at class-init would pin the
     * app to it for the process lifetime -- so a rotated host would take effect
     * only after a restart, and never at all for a process that had already
     * made one cashier call.
     *
     * Still per-brand: each brand ships its own default-config.json overlay, so
     * api.base differs per brand exactly as the BuildConfig field used to.
     */
    val BASE: String
        get() = com.rostamvpn.android.ConfigManager.apiConfig.bases.firstOrNull()
            ?: com.rostamvpn.android.BuildConfig.CASHIER_BASE
    private val BRAND: String = com.rostamvpn.android.BuildConfig.CASHIER_BRAND
    // The store verifies against the *installed* package, so read it from the build
    // rather than repeating it — a per-brand applicationId can never drift from this.
    private val PKG: String = com.rostamvpn.android.BuildConfig.APPLICATION_ID

    data class PayOrder(val orderNo: String, val payUrl: String, val status: String)
    data class Plan(val slug: String, val label: String, val price: String)
    data class Registration(val tier: String, val product: String, val paidUntil: String) {
        val isPaid: Boolean get() = tier.equals("paid", true)
    }
    /** Free-data status (region-driven, cashier-side). The NODE meters bytes →
     *  cashier; the app only READS this for display + the over-quota paywall. */
    data class UsageStatus(
        val usedMb: Long, val freeQuotaMb: Long, val remainingMb: Long,
        val overQuota: Boolean, val unlimited: Boolean, val paid: Boolean,
        // Who may pick a region (cashier offering): off | until_quota | pro_only | on.
        val regionSelect: String = "on",
        /**
         * Days until the free allowance refills; 0 = NEVER (a one-time trial).
         *
         * Server-owned, per brand (`Product.free_period_days`). The client must
         * not assume monthly: DollarVPN's allowance does not come back, and a
         * screen that promises a refill it will never get is the §22 failure —
         * copy that contradicts the product. Defaults to 30 so a cashier that
         * does not send the field behaves as it always has.
         */
        val periodDays: Int = 30,
    ) {
        /** Does the free allowance come back? False = one-time. */
        val renews: Boolean get() = periodDays > 0
        /** Whether the region picker should be unlocked for this user right now. */
        val canSelectRegion: Boolean
            get() = paid || regionSelect == "on" || (regionSelect == "until_quota" && !overQuota)
    }
    /** Which rails the server has enabled right now — the app gates UI on these. */
    /**
     * Rails the cashier says are usable right now. PayPal is gone; `stripe` is the
     * card rail and `crypto`/`nowpayments` the self-sovereign ones.
     *
     * NOTE `stripe` is not yet emitted by `/pay/methods` (cashier side — see the
     * HANDOFF board), so it reads false until that ships. That is deliberate: the
     * rail stays dark rather than failing in the user's face.
     */
    data class PayMethods(
        val shepherdCn: Boolean, val crypto: Boolean, val paymentwall: Boolean,
        val googleIap: Boolean, val appleIap: Boolean,
        val stripe: Boolean = false, val nowpayments: Boolean = false,
    ) {
        /** Either crypto processor is enough to render the crypto rail. */
        val anyCrypto: Boolean get() = crypto || nowpayments
    }

    // ── Identity / entitlement ──────────────────────────────────────────────
    /** First-launch registration → server-truth tier for this cid. Null on error.
     *  Optional [ifa]/GAID) and [lmt] land on our cashier/adtech so demand can
     *  fill; freedom-region stripping still happens server-side before any SSP.
     *
     *  Ad attribution (docs/ATTRIBUTION-2026-10-25.md), both optional and both
     *  sent once, on the launch that first obtains them: [referrer] is the Play
     *  Install Referrer string (Google puts its gclid in it for an install that
     *  came from an ad; no permission, no advertising id — [InstallReferrer]);
     *  [click] is a vpn.red click id, for a download path that can hand one to
     *  the app (none does today, so it is normally null). */
    suspend fun registerCid(
        cid: String,
        tz: String,
        ifa: String? = null,
        lmt: Int = 0,
        referrer: String? = null,
        click: String? = null,
    ): Registration? = withContext(Dispatchers.IO) {
        val body = JSONObject().apply {
            put("cid", cid)
            put("brand", BRAND)
            put("tz", tz)
            if (!ifa.isNullOrBlank()) put("ifa", ifa)
            put("lmt", lmt)
            if (!referrer.isNullOrBlank()) put("referrer", referrer.take(1024))
            if (!click.isNullOrBlank()) put("click", click.take(32))
        }
        val r = postJson("$BASE/api/v1/cid/register", body) ?: return@withContext null
        Registration(r.optString("tier", "free"), r.optString("product"), r.optString("paid_until"))
    }

    /** Report cumulative data used for this cid (app is the meter; last-write-wins). */
    /**
     * Report the cumulative total AND return the fresh status, in ONE round
     * trip.
     *
     * This threw the response away (`postJson(...) != null`) and the caller
     * then issued a GET for data the POST had already returned. The server has
     * always sent it -- `report_usage` ends `return _status_counted(...)` and
     * its own docstring says "Returns the fresh quota status so the app can
     * paywall immediately on over_quota".
     *
     * Measured on the box, 10 minutes of production: 8,494 POST /usage against
     * 35,703 GET /usage. At least one GET per POST was this pair, each one a
     * full round trip on a censored link, through the tunnel, once a minute per
     * metered device, for bytes already in hand.
     */
    suspend fun reportUsage(cid: String, usedMb: Long): UsageStatus? = withContext(Dispatchers.IO) {
        val body = JSONObject().apply { put("cid", cid); put("brand", BRAND); put("used_mb", usedMb) }
        parseUsage(postJson("$BASE/api/v1/usage", body))
    }

    /** Free-data status for this cid. Still needed where nothing was reported —
     *  the Usage-stats screen and the post-purchase refresh. */
    suspend fun usage(cid: String): UsageStatus? = withContext(Dispatchers.IO) {
        parseUsage(getJson("$BASE/api/v1/usage?cid=$cid"))
    }

    /** ONE parse for both, so the two shapes cannot drift apart -- they are the
     *  same server-side object (`_status_counted`). */
    private fun parseUsage(r: JSONObject?): UsageStatus? {
        if (r == null) return null
        return UsageStatus(
            r.optLong("used_mb"), r.optLong("free_quota_mb"), r.optLong("remaining_mb"),
            r.optBoolean("over_quota"), r.optBoolean("unlimited"), r.optBoolean("paid"),
            r.optString("region_select", "on"),
            r.optInt("period_days", 30),
        )
    }

    /** The anonymous account code for this cid (show once, user keeps it). Null on error. */
    suspend fun accountCode(cid: String): String? = withContext(Dispatchers.IO) {
        val r = postJson("$BASE/api/v1/account/code", JSONObject().put("cid", cid)) ?: return@withContext null
        r.optString("code").ifEmpty { null }
    }

    /** Restore/bridge: this cid inherits the code's entitlement. True if now entitled. */
    suspend fun claim(code: String, cid: String): Boolean = withContext(Dispatchers.IO) {
        val body = JSONObject().apply { put("code", code); put("cid", cid) }
        val r = postJson("$BASE/api/v1/auth/claim", body) ?: return@withContext false
        r.optBoolean("ok", false) && r.optBoolean("entitled", false)
    }

    // ── Web account (vpn.red ⇄ app), brand from X-App-Key ───────────────────
    /** What the cashier says once a device is linked to a web account
     *  (`/auth/email/verify` and `/auth/handoff/claim` answer the same shape). */
    data class AccountLink(
        val email: String, val entitled: Boolean, val tier: String,
        val paidUntil: String?, val accountCode: String?,
    )

    /** An account call's outcome: the HTTP status (-1 = unreachable or deferred)
     *  and, on 2xx with ok=true, the link. The screen needs the status to tell a
     *  wrong code (401) from a rate limit (429) from no network. */
    data class AccountResult(val status: Int, val link: AccountLink?)

    private fun accountLink(r: JSONObject?): AccountLink? =
        r?.takeIf { it.optBoolean("ok", false) }?.let {
            AccountLink(
                email = it.optString("email"),
                entitled = it.optBoolean("entitled", false),
                tier = it.optString("tier", "free"),
                paidUntil = it.optString("paid_until").takeIf { v -> v.isNotEmpty() && v != "null" },
                accountCode = it.optString("account_code").ifEmpty { null },
            )
        }

    /** Email sign-in step 1: mail a 6-digit code. Returns the HTTP status, -1 when unreachable. */
    suspend fun emailStart(email: String, cid: String): Int = withContext(Dispatchers.IO) {
        postStatus("$BASE/api/v1/auth/email/start", JSONObject().put("email", email).put("cid", cid)).first
    }

    /** Email sign-in step 2: check the code and link this cid to the account. */
    suspend fun emailVerify(email: String, code: String, cid: String): AccountResult = withContext(Dispatchers.IO) {
        val (st, r) = postStatus("$BASE/api/v1/auth/email/verify",
            JSONObject().put("email", email).put("code", code).put("cid", cid))
        AccountResult(st, accountLink(r))
    }

    /** The website handed this device a one-time token (`/app/open?t=`): link the cid to that web account. */
    suspend fun handoffClaim(token: String, cid: String): AccountResult = withContext(Dispatchers.IO) {
        val (st, r) = postStatus("$BASE/api/v1/auth/handoff/claim", JSONObject().put("token", token).put("cid", cid))
        AccountResult(st, accountLink(r))
    }

    /**
     * Erase this cid and everything behind it (`POST /api/v1/account/delete`,
     * cashier account_delete.py). `confirm: true` is required by the server.
     * Returns the HTTP status, -1 when unreachable: 200 erased, 404 no record
     * (nothing left to erase), 400 confirm lost.
     */
    suspend fun accountDelete(cid: String): Int = withContext(Dispatchers.IO) {
        postStatus("$BASE/api/v1/account/delete", JSONObject().put("cid", cid).put("confirm", true)).first
    }

    /** A URL that opens the website signed in as this device's account. Https only; null on any failure. */
    suspend fun webHandoffUrl(cid: String): String? = withContext(Dispatchers.IO) {
        val r = postJson("$BASE/api/v1/auth/handoff", JSONObject().put("cid", cid)) ?: return@withContext null
        r.optString("url").takeIf { it.startsWith("https://") }
    }

    /** The email this cid is signed in with, "" when it is not linked, null when unknown (unreachable). */
    suspend fun signedInEmail(cid: String): String? = withContext(Dispatchers.IO) {
        val r = getJson("$BASE/api/v1/me/status?cid=${Uri.encode(cid)}") ?: return@withContext null
        if (r.optBoolean("logged_in", false)) r.optString("email") else ""
    }

    /**
     * The published QR bootstrap: a signed, endpoints-only config small enough to
     * be a QR code.
     *
     * Served by the config service (:8081), NOT api-key gated -- the whole point
     * is that it is screenshotted and passed to someone whose own endpoints are
     * blocked, so a key travelling with it would protect nothing. Safety comes
     * from the signature, which the client verifies before applying.
     */
    suspend fun configQrPayload(): String? = withContext(Dispatchers.IO) {
        for (base in com.rostamvpn.android.ConfigManager.apiConfig.bases) {
            val body = runCatching {
                val c = java.net.URL("$base/api/v1/config/qr").openConnection()
                        as java.net.HttpURLConnection
                c.connectTimeout = 10_000; c.readTimeout = 10_000
                if (c.responseCode == 200) c.inputStream.bufferedReader().use { it.readText() } else null
            }.getOrNull()
            if (!body.isNullOrBlank()) return@withContext body.trim()
        }
        null
    }

    /** Which payment rails are live. App shows only enabled ones (don't hardcode). */
    suspend fun payMethods(): PayMethods? = withContext(Dispatchers.IO) {
        val r = getJson("$BASE/api/v1/pay/methods") ?: return@withContext null
        fun on(k: String) = r.optJSONObject(k)?.optBoolean("enabled", false) ?: false
        PayMethods(
            shepherdCn = on("shepherd_cn"), crypto = on("crypto"),
            paymentwall = on("paymentwall"), googleIap = on("google_iap"),
            appleIap = on("apple_iap"), stripe = on("stripe"),
            nowpayments = on("nowpayments"),
        )
    }

    // ── Free-tier ad (WebView) ──────────────────────────────────────────────
    /**
     * Interstitial URL for a free user. Honors Limit-Ad-Tracking: when [ad.limited]
     * is set we omit `ifa` and send `&lmt=1` (Google Play requirement).
     *
     * [baseOverride] lets the adtech container HTML be served from a dedicated
     * adtech host (config `ads.adtech_url`) instead of the cashier's own
     * `/ad/interstitial`. Empty → the cashier base (default). The path
     * `/ad/interstitial` and all query params are identical either way, so the
     * host is the only thing that moves.
     */
    fun adInterstitialUrl(
        cid: String,
        ad: AdId,
        region: String,
        sponsorOnly: Boolean = false,
        baseOverride: String = "",
        allowHouse: Boolean = true,
    ): String {
        val base = baseOverride.trim().trimEnd('/').ifEmpty { BASE }
        val b = Uri.parse("$base/ad/interstitial").buildUpon()
            .appendQueryParameter("brand", BRAND)
            .appendQueryParameter("cid", cid)
            .appendQueryParameter("region", region)
        if (ad.limited || ad.gaid.isNullOrEmpty()) {
            b.appendQueryParameter("lmt", "1")
        } else {
            b.appendQueryParameter("ifa", ad.gaid)
            b.appendQueryParameter("lmt", "0")
        }
        if (sponsorOnly) b.appendQueryParameter("sponsor_only_req", "1")
        // Paid demand only. The cashier then answers a no-bid with the no-fill
        // sentinel instead of a house creative, so the caller can fall through
        // to its next demand source. Omitted on the last rung, where house IS
        // the answer.
        if (!allowHouse) b.appendQueryParameter("no_house", "1")
        return b.build().toString()
    }

    /**
     * Seconds to wait before the next interstitial attempt. 0 = show one now.
     * Fails OPEN (0) on network error — a missed cap is better than suppressing
     * a connect-time ad the server allows. A non-frequency decline never maps to
     * 0; that used to open a blank card against an explicit refusal.
     */
    suspend fun adRetryAfterSecs(
        cid: String,
        region: String,
        slot: String = "interstitial",
        declineBackoffSecs: Int = 300,
    ): Int =
        withContext(Dispatchers.IO) {
            val u = Uri.parse("$BASE/api/v1/ad/should-show").buildUpon()
                .appendQueryParameter("cid", cid)
                .appendQueryParameter("brand", BRAND)
                .appendQueryParameter("region", region)
                .appendQueryParameter("slot", slot)
                .build().toString()
            val r = getJson(u) ?: return@withContext 0
            if (r.optBoolean("show", true)) return@withContext 0
            // A decline must NEVER map to 0 — 0 means "show now" to the caller.
            val hint = r.optInt("retry_after_secs", 0)
            if (hint > 0) return@withContext hint
            // interval_secs=0 (once-per-connect) passes declineBackoffSecs=0;
            // still need a positive so we don't open the card against a refusal.
            if (declineBackoffSecs > 0) declineBackoffSecs else 300
        }

    // ── Google Play purchase verification (outside China) ───────────────────
    /** POST /api/v1/iap/verify → true when the cashier confirms the cid is entitled. */
    suspend fun iapVerify(cid: String, productId: String, token: String): Boolean =
        withContext(Dispatchers.IO) {
            val body = JSONObject().apply {
                put("platform", "google")
                put("cid", cid)
                put("product_id", productId)
                put("plan_slug", productId)   // product id == cashier plan slug (1:1)
                put("token", token)
                put("brand", BRAND)
                put("package_name", PKG)
            }
            // THE ONE EXEMPTION, and the reason is in our own code rather
            // than in Play policy: `handlePlayPurchase` puts BOTH
            // `acknowledge()` and `setPaid(true)` inside `if (ok)`. So a
            // deferred verify does not merely delay bookkeeping, it withholds
            // premium from someone who has just paid for it -- and the brand
            // this still runs on (rostam, which is off Adapty) would answer a
            // purchase with "reopen the app shortly".
            //
            // Renewals are unaffected either way: Play renews on its own and
            // `queryActivePurchases` restores locally with no cashier call.
            val resp = postJson("$BASE/api/v1/iap/verify", body, requireTunnel = false)
                ?: return@withContext false
            resp.optBoolean("ok", false) && resp.optBoolean("entitled", false)
        }

    // ── China rail (Alipay / WeChat, cashier-brokered) ──────────────────────
    /** GET /api/v1/plans?brand&region — ALWAYS pass a region: empty returns every
     *  plan incl. CN (CNY), which then leaks onto non-CN paywalls. */
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

    /** POST /api/v1/pay/create → an order with a pay_url to open. `channel`:
     *  "shepherd_cn" (Alipay/WeChat) | "paymentwall" (cards/local widget). */
    suspend fun payCreate(planSlug: String, cid: String, channel: String,
                          payMethod: String = ""): PayOrder? =
        withContext(Dispatchers.IO) {
            val body = JSONObject().apply {
                put("plan_slug", planSlug)
                put("cid", cid)
                put("brand", BRAND)
                put("channel", channel)
                put("pay_method", payMethod)   // shepherd: alipay|wxpay · paymentwall: ""
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

    /**
     * GET [url] and return the body as text, or null on any failure/non-200.
     * Shares [open] so it inherits the tunnel-aware SOCKS routing every other
     * cashier call uses. Blocking: call it off the main thread.
     */
    fun fetchText(url: String): String? = try {
        val conn = open(url)
        conn.requestMethod = "GET"
        if (conn.responseCode == 200) {
            conn.inputStream.bufferedReader().use { it.readText() }
        } else null
    } catch (_: Throwable) { null }

    // ── HTTP plumbing ───────────────────────────────────────────────────────
    /**
     * True when SOMETHING is serving SOCKS5 on 127.0.0.1:1080.
     *
     * Must ask BOTH transports. This used to test NetCore alone, so a session
     * that WGTCP had won looked tunnel-less here and every cashier call went out
     * on the raw network -- the one network where the cashier host is most
     * likely to be blocked, and with `TunnelVpnService.addDisallowedApplication`
     * excluding us from the tun there is no route back through it by accident.
     * `VpnViewModel.tunnelAlive()` already reasons this way and says why: both
     * shipping transports serve 1080, so the port is the tunnel, not the crate.
     */
    /** Our own tunnel is up, so a cashier call goes through it (pinned path). */
    fun tunnelUp(): Boolean {
        val rs = NetCoreBridge.isLoaded &&
            (try { NetCoreBridge.isRunning() != 0 } catch (_: Throwable) { false })
        if (rs) return true
        return try { WgtcpBridge.isRunning() } catch (_: Throwable) { false }
    }

    /**
     * True when the device already holds a VPN profile for us. Android's own
     * consent is granted once and remembered until uninstall, and
     * `VpnService.prepare` returns null exactly then -- which is literally the
     * "is there a tunnel profile" question, not a proxy for it.
     *
     * A null context answers false, i.e. the first-run allowance below, which
     * is the behaviour this file had before the gate.
     */
    private fun hasTunnelProfile(): Boolean = try {
        com.rostamvpn.android.MainActivity.instance?.applicationContext?.let {
            android.net.VpnService.prepare(it) == null
        } ?: false
    } catch (_: Throwable) { false }

    /**
     * [requireTunnel] false exempts a call from the gate below. Exactly one
     * caller may do that -- see `iapVerify`.
     */
    private fun open(url: String, requireTunnel: Boolean = true): HttpURLConnection {
        // A PAID_ONLY build is a commercial brand with no freedom region: the
        // "cashier only through the tunnel" rule would keep a web or eSIM
        // renewal from ever reaching a device whose tunnel is down or refused,
        // and that device can only get the tunnel back by proving it paid.
        if (requireTunnel && !com.rostamvpn.android.BuildConfig.PAID_ONLY && !tunnelUp() && hasTunnelProfile()) {
            // Once consent exists, the cashier is reached THROUGH the tunnel or
            // not at all. Every cashier hostname resolves to one address, and
            // that address is the kind Iran blocks -- so a raw-network call from
            // the largest user base was failing anyway, silently, while putting
            // the cid on the open network on the way.
            //
            // Refusing is a supported outcome, not a new failure mode: every
            // caller already treats null as "cashier unreachable" and keeps its
            // cached state, because that is the normal condition for these
            // users. The call is re-made on the next connect, where
            // VpnViewModel re-runs syncEntitlement().
            throw java.io.IOException("cashier deferred: tunnel profile exists, tunnel down")
        }
        val u = URL(url)
        val conn = if (tunnelUp()) {
            u.openConnection(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", 1080)))
        } else {
            u.openConnection()
        } as HttpURLConnection
        conn.connectTimeout = 15000
        conn.readTimeout = 25000
        conn.setRequestProperty("Accept", "application/json")
        conn.setRequestProperty("X-App-Key", BRAND)   // brand attribution (not a secret)
        // 🚨 DO NOT LET THIS SOCKET REACH THE PLATFORM'S CONNECTION POOL.
        //
        // Android's bundled okhttp crashes cleaning up a pooled socket that was
        // never connected -- and a SOCKS-proxied connect to a tunnel that is
        // down produces exactly that, a socket whose getInetAddress() is null:
        //
        //   NullPointerException: 'java.net.InetAddress.toString()' on a null
        //     object reference
        //     at com.android.okhttp.internal.Util.closeQuietly (Util.java:95)
        //     at com.android.okhttp.ConnectionPool.cleanup (ConnectionPool:282)
        //     at com.android.okhttp.ConnectionPool$1.run (ConnectionPool:94)
        //
        // 144,085 reports across 3,858 users, 2026-09-01..10-06, versionCodes
        // 133 through 465 -- the single largest crash on the listing, and ~37
        // per user, because the pool retries forever.
        //
        // NOT ONE FRAME IS OURS, which is why try/catch at the call site cannot
        // help: ConnectionPool.cleanup runs on its own executor thread and takes
        // the process down. The only lever the caller has is to keep the socket
        // out of the pool, and `Connection: close` is it -- a connection marked
        // close is never recycled, so cleanup never sees it.
        //
        // Cheap here: these calls are seconds apart at best, so losing keep-alive
        // costs one handshake on a path that already pays a tunnel round trip.
        conn.setRequestProperty("Connection", "close")
        return conn
    }

    private fun postJson(url: String, body: JSONObject, requireTunnel: Boolean = true): JSONObject? = try {
        val conn = open(url, requireTunnel)
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        readJson(conn)
    } catch (e: Exception) {
        Log.w(TAG, "POST $url failed: ${e.message}"); null
    }

    /** POST that keeps the status code: (status, body-or-null). -1 = no answer. */
    private fun postStatus(url: String, body: JSONObject): Pair<Int, JSONObject?> = try {
        val conn = open(url)
        try {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            code to (if (code in 200..299 && text.isNotEmpty()) runCatching { JSONObject(text) }.getOrNull() else null)
        } finally {
            conn.disconnect()
        }
    } catch (e: Exception) {
        // The path only: a body here would put an email or a token in logcat.
        Log.w(TAG, "POST ${url.substringAfter(BASE)} failed: ${e.javaClass.simpleName}"); -1 to null
    }

    private fun getJson(url: String): JSONObject? = try {
        val conn = open(url)
        conn.requestMethod = "GET"
        readJson(conn)
    } catch (e: Exception) {
        Log.w(TAG, "GET $url failed: ${e.message}"); null
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
