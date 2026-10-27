package com.rostamvpn.android

import app.netcore.NetCoreBridge
import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.SocketTimeoutException
import java.net.URL

// ─────────────────────────────────────────────────────────────────────────────
// The device-side protection loop (docs/REDSHIELD-TRAFFIC-2026-10-25.md,
// sections 1-2 and Amendments A-D; the iOS twin is RostamKit/GuardianScores
// and the iOS tunnel provider's scoring). Brand-neutral: the feature
// exists only where BuildConfig.PROTECTION_NAME is set (brands/<id>.json
// features.protectionName), and its display name is that string.
//
// What leaves the device, and ONLY through the tunnel (the core's own SOCKS
// port; this app excludes itself from the tun, so a plain socket would leave
// on the real interface): the client id and the destinations not scored yet.
// Nothing else. Hostnames are handed to the proxy unresolved.
// ─────────────────────────────────────────────────────────────────────────────

/** Gates, names and endpoints. */
object RedShield {
    /** The feature exists in this build. */
    val available: Boolean get() = BuildConfig.PROTECTION_NAME.isNotEmpty()
    /** The display name, from the brand manifest. */
    val name: String get() = BuildConfig.PROTECTION_NAME

    /** The Protect Mode API origin, per brand (`features.protectionApiBase`). */
    val API_BASE: String = BuildConfig.PROTECTION_API_BASE.trimEnd('/')
    val SCORE_URL: String = "$API_BASE/redshield/v1/score"
    val SCAN_URL: String = "$API_BASE/redshield/v1/scan"
    private val OWN_HOST: String = java.net.URI(API_BASE).host.orEmpty().lowercase()

    /**
     * Our own API and everything else this install is configured to reach
     * (FirstParty: config entry points, API bases, WGTCP endpoints, the
     * build's own web and store hosts), and the tun's own DNS address (HevConfig mapdns, 198.18.0.2:
     * DNS over TCP reaches the core as a CONNECT to it). Neither is a place the
     * phone talks to, so neither is ever scored or scanned. Seen live on the
     * emulator 2026-10-25: the mapdns address came back from /score as "Rare".
     */
    fun isOwn(host: String): Boolean =
        host == OWN_HOST || host.endsWith(".$OWN_HOST") || host == "198.18.0.2" || FirstParty.matches(host)

    /** OTA `redshield.enabled` (absent = on). */
    val enabledByConfig: Boolean get() = ConfigManager.redshieldEnabled

    /** The user's master switch (home card and Rules). Same pref as the Guardian's. */
    fun userOn(ctx: Context): Boolean =
        ctx.getSharedPreferences("rostam_prefs", Context.MODE_PRIVATE).getBoolean("guardian_on", true)

    /**
     * Scoring, automatic denies and profiles run: the brand has it, OTA and the
     * user allow it, AND the user accepted the Protect Mode disclosure
     * (ProtectConsent). Without that consent nothing is sent, whatever the switch says.
     */
    fun deviceOn(ctx: Context): Boolean = available && enabledByConfig && userOn(ctx) && ProtectConsent.consented(ctx)

    internal fun prefs(ctx: Context) = ctx.getSharedPreferences("shield", Context.MODE_PRIVATE)

    /** Is the transport core up with a SOCKS port to route through? */
    fun tunnelPort(): Int {
        if (!NetCoreBridge.isLoaded) return 0
        return runCatching { if (NetCoreBridge.isRunning() != 0) NetCoreBridge.getSocksPort() else 0 }.getOrDefault(0)
    }
}

// ── Data ────────────────────────────────────────────────────────────────────

/** One destination from the core's flow table. [uid] = the app that opened it, when known. */
data class ShieldFlow(
    val host: String,
    val ports: List<Int>,
    val connections: Int,
    val bytesUp: Long,
    val bytesDown: Long,
    val firstSecsAgo: Long,
    val lastSecsAgo: Long,
    val blocked: Int = 0,
    val unusual: Boolean = false,
    val uid: Int? = null,
    /** App-side sockets of the latest connections ("src:port>dst:port"), from the tun bridge. */
    val origins: List<String> = emptyList(),
    // Protocol detail (absent from older cores: empty / 0).
    /** TCP bytes up + down per destination port. */
    val portBytes: Map<Int, Long> = emptyMap(),
    val udpConnections: Int = 0,
    val udpBytes: Long = 0,
    /** UDP bytes up + down per destination port (443 = QUIC). */
    val udpPortBytes: Map<Int, Long> = emptyMap(),
    /** The regional map's country for a literal-IP destination (the score's wins when present). */
    val cc: String? = null,
) {
    /** TCP and UDP together. */
    val totalBytes: Long get() = bytesUp + bytesDown + udpBytes
    /** A literal IPv4 or IPv6 address rather than a name. */
    val isAddress: Boolean get() = isLiteralIp(host)
}

data class ShieldFlows(val sinceSecs: Long, val flows: List<ShieldFlow>) {
    companion object {
        val EMPTY = ShieldFlows(0, emptyList())

        fun parse(json: String): ShieldFlows = runCatching {
            val o = JSONObject(json)
            val a = o.optJSONArray("flows") ?: JSONArray()
            ShieldFlows(o.optLong("since_secs"), (0 until a.length()).mapNotNull { i ->
                val f = a.optJSONObject(i) ?: return@mapNotNull null
                val host = f.optString("host").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                val ports = f.optJSONArray("ports")
                ShieldFlow(
                    host = host,
                    ports = (0 until (ports?.length() ?: 0)).map { ports!!.optInt(it) },
                    connections = f.optInt("connections"),
                    bytesUp = f.optLong("bytes_up"), bytesDown = f.optLong("bytes_down"),
                    firstSecsAgo = f.optLong("first_secs_ago"), lastSecsAgo = f.optLong("last_secs_ago"),
                    blocked = f.optInt("blocked"), unusual = f.optBoolean("unusual"),
                    // The core never sends a uid; RedShieldEngine.flows() fills
                    // it from the origins (ShieldAttribution). Read if present.
                    uid = if (f.has("uid")) f.optInt("uid") else null,
                    origins = f.optJSONArray("origins")?.let { o -> (0 until o.length()).map { o.optString(it) } }.orEmpty(),
                    portBytes = portMap(f.optJSONObject("port_bytes")),
                    udpConnections = f.optInt("udp_connections"),
                    udpBytes = f.optLong("udp_bytes"),
                    udpPortBytes = portMap(f.optJSONObject("udp_port_bytes")),
                    cc = f.optString("cc").takeIf { it.length == 2 }?.uppercase(),
                )
            })
        }.getOrDefault(EMPTY)

        /** `{"443": 1200}` -> {443: 1200} */
        private fun portMap(o: JSONObject?): Map<Int, Long> {
            if (o == null) return emptyMap()
            val out = LinkedHashMap<Int, Long>()
            for (k in o.keys()) k.toIntOrNull()?.let { out[it] = o.optLong(k) }
            return out
        }
    }
}

internal fun isLiteralIp(h: String): Boolean {
    if (h.contains(':')) return h.all { it.isLetterOrDigit() || it == ':' || it == '.' } && h.count { it == ':' } >= 2
    val parts = h.split('.')
    return parts.size == 4 && parts.all { p -> p.isNotEmpty() && p.length <= 3 && p.all(Char::isDigit) && p.toInt() <= 255 }
}

/**
 * Who is behind a destination, from the score and scan responses' optional
 * `ctx` object (redshield, additive; every field nullable). A server without
 * it sends nothing and every field here stays null: today's behaviour. Kept
 * with the score and with each block record (GuardStore), so the Allow list
 * can say whose address it is and why it was stopped.
 */
data class ShieldCtx(
    val asn: Int? = null,
    val org: String? = null,
    /** ISO 3166 alpha-2, upper case. */
    val cc: String? = null,
    val rdns: String? = null,
    /** Stable id for the reason ("malware_host"); localised on the device when known. */
    val reasonKey: String? = null,
    /** The server's English sentence, shown when the key is not one this build knows. */
    val reason: String? = null,
    val category: String? = null,
) {
    val isEmpty: Boolean get() = asn == null && org == null && cc == null && rdns == null && reasonKey == null && reason == null && category == null

    fun toJson(): JSONObject = JSONObject().apply {
        asn?.let { put("asn", it) }; org?.let { put("org", it) }; cc?.let { put("cc", it) }; rdns?.let { put("rdns", it) }
        reasonKey?.let { put("reason_key", it) }; reason?.let { put("reason", it) }; category?.let { put("category", it) }
    }

    /** Fill what this lacks from [o] (server ctx first, then the older score fields, then the geo map). */
    fun orElse(o: ShieldCtx?): ShieldCtx = if (o == null) this else ShieldCtx(
        asn ?: o.asn, org ?: o.org, cc ?: o.cc, rdns ?: o.rdns, reasonKey ?: o.reasonKey, reason ?: o.reason, category ?: o.category,
    )

    companion object {
        /** Tolerant: null, not an object, wrong types or blanks all give null fields; an all-null object gives null. */
        fun fromJson(o: JSONObject?): ShieldCtx? {
            if (o == null) return null
            fun str(k: String) = if (o.has(k) && !o.isNull(k)) o.optString(k).trim().takeIf { it.isNotEmpty() } else null
            val asn = if (o.has("asn") && !o.isNull("asn")) (o.opt("asn") as? Number)?.toLong()?.takeIf { it in 1..4_294_967_295L }?.toInt()
                ?: str("asn")?.removePrefix("AS")?.removePrefix("as")?.toLongOrNull()?.takeIf { it in 1..4_294_967_295L }?.toInt() else null
            val c = ShieldCtx(
                asn = asn, org = str("org"), cc = str("cc")?.uppercase()?.takeIf { it.length == 2 && it.all { ch -> ch in 'A'..'Z' } },
                rdns = str("rdns")?.trimEnd('.'), reasonKey = str("reason_key")?.lowercase(), reason = str("reason"), category = str("category")?.lowercase(),
            )
            return c.takeIf { !it.isEmpty }
        }

        fun parse(json: String?): ShieldCtx? = json?.takeIf { it.isNotBlank() }?.let { runCatching { fromJson(JSONObject(it)) }.getOrNull() }
    }
}

/** One cached score. Times are epoch millis. */
data class ShieldScore(
    val host: String,
    /** 0-100: blocked 90-100, suspicious 50-80, known 10-20, clear 0. */
    val score: Int,
    /** blocked | suspicious | known | clear */
    val verdict: String,
    val label: String?,
    val service: String?,
    val cc: String?,
    val feedDate: String?,
    val expires: Long,
    val used: Long,
    val org: String? = null,
    /** cloud | cdn | hosting | isp | mobile | edu | gov | other */
    val cls: String? = null,
    val provider: String? = null,
    /** rare | some | common */
    val seenBy: String? = null,
    /** The response's `ctx`, when the server sent one. */
    val ctx: ShieldCtx? = null,
) {
    /** Server ctx, else what the older fields already say (org, country). Null when nothing is known. */
    val context: ShieldCtx? get() = (ctx ?: ShieldCtx()).orElse(ShieldCtx(org = org ?: service, cc = cc?.uppercase())).takeIf { !it.isEmpty }

    val isRare: Boolean get() = seenBy == "rare"
    val isFlagged: Boolean get() = verdict == "blocked" || verdict == "suspicious"

    fun toJson(): JSONObject = JSONObject().apply {
        put("host", host); put("score", score); put("verdict", verdict)
        label?.let { put("label", it) }; service?.let { put("service", it) }; cc?.let { put("cc", it) }
        feedDate?.let { put("feed_date", it) }; put("expires", expires); put("used", used)
        org?.let { put("org", it) }; cls?.let { put("class", it) }; provider?.let { put("provider", it) }
        seenBy?.let { put("seen_by", it) }
        ctx?.let { put("ctx", it.toJson()) }
    }

    companion object {
        private fun JSONObject.str(k: String) = optString(k).takeIf { has(k) && !isNull(k) && it.isNotEmpty() }

        fun fromJson(o: JSONObject): ShieldScore = ShieldScore(
            host = o.optString("host"), score = o.optInt("score"), verdict = o.optString("verdict", "clear"),
            label = o.str("label"), service = o.str("service"), cc = o.str("cc"), feedDate = o.str("feed_date"),
            expires = o.optLong("expires"), used = o.optLong("used"),
            org = o.str("org"), cls = o.str("class"), provider = o.str("provider"), seenBy = o.str("seen_by"),
            ctx = ShieldCtx.fromJson(o.optJSONObject("ctx")),
        )
    }
}

/**
 * The on-device score cache: one JSON file in filesDir, LRU-bounded at
 * [CAPACITY], TTL from the response capped at 24 h, persisted across launches
 * and cleared only by the user's Clear. Every access is under the object lock.
 */
class ShieldScoreCache(
    var feedDate: String? = null,
    var checkedAt: Long = 0,
    val entries: MutableMap<String, ShieldScore> = LinkedHashMap(),
) {
    fun score(host: String): ShieldScore? = entries[host]

    fun needsScore(host: String, now: Long = System.currentTimeMillis()): Boolean =
        entries[host]?.let { it.expires <= now } ?: true

    /** Fold a /score response in; returns the flagged scores it carried. */
    fun merge(r: JSONObject, now: Long = System.currentTimeMillis()): List<ShieldScore> {
        val ttl = (r.optLong("ttl_secs", 3_600)).coerceIn(60, MAX_TTL_SECS) * 1000
        val feed = r.optString("feed_date").takeIf { it.isNotEmpty() }
        val flagged = mutableListOf<ShieldScore>()
        val a = r.optJSONArray("scores") ?: JSONArray()
        for (i in 0 until a.length()) {
            val s = a.optJSONObject(i) ?: continue
            val host = s.optString("host").lowercase().takeIf { it.isNotEmpty() } ?: continue
            // A verdict for one of ours is never kept, whatever it says: we
            // did not ask (batch/scan never send them), and a "blocked" here
            // would cut the app off from its own infrastructure.
            if (RedShield.isOwn(host)) { FirstParty.verdictsDropped.incrementAndGet(); continue }
            fun str(k: String) = s.optString(k).takeIf { s.has(k) && !s.isNull(k) && it.isNotEmpty() }
            val e = ShieldScore(
                host = host, score = s.optInt("score"), verdict = str("verdict") ?: "clear",
                label = str("label"), service = str("service"), cc = str("cc"), feedDate = feed,
                expires = now + ttl, used = now,
                org = str("org"), cls = str("class"), provider = str("provider"), seenBy = str("seen_by"),
                ctx = ShieldCtx.fromJson(s.optJSONObject("ctx")),
            )
            entries[host] = e
            if (e.isFlagged) flagged += e
        }
        if (feed != null) feedDate = feed
        checkedAt = now
        evict()
        return flagged
    }

    fun evict() {
        if (entries.size <= CAPACITY) return
        val keep = entries.values.sortedByDescending { it.used }.take(CAPACITY)
        entries.clear(); keep.forEach { entries[it.host] = it }
    }

    /**
     * The core's automatic deny set: every unexpired `blocked` score, plus
     * (Amendment C) every scored host whose country is a selected country
     * profile. `countries` maps a selected code to the label shown for it.
     */
    fun autoRulesJson(countries: Map<String, String>, now: Long = System.currentTimeMillis()): String {
        val rules = JSONArray()
        for (e in entries.values) {
            if (e.expires <= now) continue
            // A score cached before the first-party set existed (or before
            // the config named the host) never becomes a deny.
            if (RedShield.isOwn(e.host)) continue
            val ttl = (e.expires - now) / 1000
            val label = when {
                e.verdict == "blocked" -> e.label.orEmpty()
                e.cc != null && countries.containsKey(e.cc.uppercase()) -> countries.getValue(e.cc.uppercase())
                else -> continue
            }
            rules.put(JSONObject().put("host", e.host).put("label", label).put("ttl_secs", ttl))
        }
        return JSONObject().put("rules", rules).toString()
    }

    fun toJson(): String = JSONObject().apply {
        feedDate?.let { put("feed_date", it) }
        put("checked_at", checkedAt)
        put("entries", JSONArray().apply { entries.values.forEach { put(it.toJson()) } })
    }.toString()

    companion object {
        const val CAPACITY = 5_000
        const val MAX_TTL_SECS = 86_400L
        private const val FILE = "shield-scores.json"
        private val lock = Any()

        private fun file(ctx: Context) = File(ctx.filesDir, FILE)

        fun parse(json: String): ShieldScoreCache = runCatching {
            val o = JSONObject(json)
            val c = ShieldScoreCache(o.optString("feed_date").takeIf { it.isNotEmpty() }, o.optLong("checked_at"))
            val a = o.optJSONArray("entries") ?: JSONArray()
            for (i in 0 until a.length()) a.optJSONObject(i)?.let { ShieldScore.fromJson(it) }?.let { c.entries[it.host] = it }
            c
        }.getOrDefault(ShieldScoreCache())

        fun load(ctx: Context): ShieldScoreCache = synchronized(lock) {
            val f = file(ctx)
            if (!f.exists()) ShieldScoreCache() else parse(runCatching { f.readText() }.getOrDefault(""))
        }

        /** Read, change, write atomically. */
        fun <T> update(ctx: Context, block: (ShieldScoreCache) -> T): T = synchronized(lock) {
            val c = load(ctx)
            val out = block(c)
            val f = file(ctx)
            val tmp = File(f.parentFile, "$FILE.tmp")
            runCatching { tmp.writeText(c.toJson()); tmp.renameTo(f) }
            out
        }

        fun clear(ctx: Context) = synchronized(lock) { file(ctx).delete(); Unit }
    }
}

/** POST /redshield/v1/scan response. */
data class ShieldScanItem(
    val host: String, val ips: List<String>, val verdict: String, val label: String?, val service: String?,
    val cc: String?, val asn: Int?, val asname: String?, val insights: List<String>,
    val ctx: ShieldCtx? = null,
) {
    /** Server ctx, else the scan's own asn / asname / cc. */
    val context: ShieldCtx? get() = (ctx ?: ShieldCtx()).orElse(ShieldCtx(asn = asn, org = asname, cc = cc?.uppercase())).takeIf { !it.isEmpty }
}

data class ShieldScanReport(
    val feedDate: String?, val total: Int, val flagged: Int, val suspicious: Int, val known: Int, val unknown: Int,
    val items: List<ShieldScanItem>,
) {
    companion object {
        fun parse(json: String): ShieldScanReport? = runCatching {
            val o = JSONObject(json)
            val s = o.optJSONObject("summary") ?: JSONObject()
            val a = o.optJSONArray("items") ?: JSONArray()
            fun JSONObject.str(k: String) = optString(k).takeIf { has(k) && !isNull(k) && it.isNotEmpty() }
            fun JSONArray?.strings() = (0 until (this?.length() ?: 0)).map { this!!.optString(it) }.filter { it.isNotEmpty() }
            ShieldScanReport(
                o.str("feed_date"), s.optInt("total"), s.optInt("flagged"), s.optInt("suspicious"),
                s.optInt("known"), s.optInt("unknown"),
                (0 until a.length()).mapNotNull { i ->
                    val it = a.optJSONObject(i) ?: return@mapNotNull null
                    ShieldScanItem(
                        it.optString("host"), it.optJSONArray("ips").strings(), it.str("verdict") ?: "clear",
                        it.str("label"), it.str("service"), it.str("cc"),
                        if (it.has("asn") && !it.isNull("asn")) it.optInt("asn") else null, it.str("asname"),
                        it.optJSONArray("insights").strings(),
                        ShieldCtx.fromJson(it.optJSONObject("ctx")),
                    )
                },
            )
        }.getOrNull()
    }
}

/** The last detailed report, kept so it survives a relaunch. */
object ShieldReportStore {
    fun save(ctx: Context, raw: String, at: Long = System.currentTimeMillis()) {
        RedShield.prefs(ctx).edit().putString("report", raw).putLong("report_at", at).apply()
    }

    fun load(ctx: Context): Pair<ShieldScanReport, Long>? {
        val p = RedShield.prefs(ctx)
        val r = p.getString("report", null)?.let { ShieldScanReport.parse(it) } ?: return null
        return r to p.getLong("report_at", 0)
    }
}

// ── Requests (through the tunnel only) ──────────────────────────────────────

object ShieldHttp {
    enum class Failure { OFFLINE, BUSY, TIMED_OUT, QUOTA, FAILED }

    sealed class Result {
        data class Ok(val code: Int, val body: ByteArray, val headers: Map<String, String>) : Result()
        data class Err(val why: Failure) : Result()
    }

    /**
     * One request via the core's local SOCKS port. An explicit Proxy never
     * falls back to a direct connection, so with the tunnel down this fails
     * instead of leaving on the real interface. `Connection: close` keeps
     * the socket out of the platform's pool (CashierApi: the okhttp
     * ConnectionPool.cleanup crash, 144K reports).
     */
    fun request(
        url: String, body: ByteArray? = null, headers: Map<String, String> = emptyMap(),
        timeoutMs: Int = 15_000, maxBytes: Int = 8_000_000, port: Int = RedShield.tunnelPort(),
    ): Result {
        if (port == 0) return Result.Err(Failure.OFFLINE)
        return try {
            val c = URL(url).openConnection(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", port))) as HttpURLConnection
            c.connectTimeout = timeoutMs; c.readTimeout = timeoutMs
            c.useCaches = false
            c.instanceFollowRedirects = false
            c.setRequestProperty("Connection", "close")
            c.setRequestProperty("X-App-Key", BuildConfig.CASHIER_BRAND)
            headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
            if (body != null) {
                c.requestMethod = "POST"
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.setFixedLengthStreamingMode(body.size)
                c.outputStream.use { it.write(body) }
            }
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val bytes = stream?.use { s ->
                val out = java.io.ByteArrayOutputStream()
                val buf = ByteArray(16 * 1024)
                while (true) {
                    val n = s.read(buf); if (n < 0) break
                    out.write(buf, 0, n)
                    if (out.size() > maxBytes) break
                }
                out.toByteArray()
            } ?: ByteArray(0)
            val h = c.headerFields.filterKeys { it != null }.mapKeys { it.key.lowercase() }.mapValues { it.value.firstOrNull().orEmpty() }
            c.disconnect()
            when (code) {
                503 -> Result.Err(Failure.BUSY)
                429 -> Result.Err(Failure.QUOTA)
                else -> Result.Ok(code, bytes, h)
            }
        } catch (_: SocketTimeoutException) {
            Result.Err(Failure.TIMED_OUT)
        } catch (_: java.io.IOException) {
            Result.Err(Failure.OFFLINE)
        } catch (_: Throwable) {
            Result.Err(Failure.FAILED)
        }
    }

    /** `[{host, ips?, ports}]`: names as the app sent them; `ips` only for literal-address flows. */
    fun hostsPayload(flows: List<ShieldFlow>): JSONArray = JSONArray().apply {
        flows.forEach { f ->
            put(JSONObject().apply {
                put("host", f.host)
                put("ports", JSONArray(f.ports))
                if (f.isAddress) put("ips", JSONArray(listOf(f.host)))
            })
        }
    }

    /** The detailed scan, from the UI. At most 100 destinations. */
    fun scan(ctx: Context, flows: List<ShieldFlow>): Pair<ShieldScanReport?, Failure?> {
        val cid = com.rostamvpn.android.billing.CashierIdentity.cid(ctx)
        val mine = flows.filter { RedShield.isOwn(it.host) }
        mine.forEach { FirstParty.noteExempt(it.host) }
        val body = JSONObject().put("cid", cid).put("hosts", hostsPayload((flows - mine.toSet()).take(100)))
            .put("lang", java.util.Locale.getDefault().language).toString().toByteArray()
        return when (val r = request(RedShield.SCAN_URL, body)) {
            is Result.Err -> null to r.why
            is Result.Ok -> {
                if (r.code != 200) return null to Failure.FAILED
                val raw = String(r.body)
                val rep = ShieldScanReport.parse(raw) ?: return null to Failure.FAILED
                ShieldReportStore.save(ctx, raw)
                rep to null
            }
        }
    }
}

// ── The loop (runs in the VpnService) ──────────────────────────────────────

/**
 * Started by TunnelVpnService when the tun is up, stopped with it. Everything
 * on one serial dispatcher, like iOS's guardian queue.
 *
 *  - on start: the core's filter on (the user's own Block rules always apply),
 *    then the automatic rules and the enabled profiles rebuilt (the core
 *    forgets both on stop);
 *  - every 5 s: the core's recent-blocks ring into GuardStore, so the Blocked
 *    list and "N blocked today" survive the UI being closed;
 *  - 30 s after start, then every `redshield.score_interval_secs`: one score
 *    round, at most `redshield.score_batch` unscored destinations.
 */
@OptIn(ExperimentalCoroutinesApi::class)
object RedShieldEngine {
    private const val TAG = "Shield"
    private val dispatcher = Dispatchers.IO.limitedParallelism(1)
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private var job: Job? = null
    @Volatile private var appCtx: Context? = null

    /** Bumped after every score round or rule change; the UI re-reads on it. */
    private val _revision = MutableStateFlow(0)
    val revision: StateFlow<Int> = _revision.asStateFlow()

    /** Set by the UI as well, so flows() can resolve apps before the service has started. */
    fun attach(ctx: Context) { if (appCtx == null) appCtx = ctx.applicationContext }

    fun start(ctx: Context) {
        if (!RedShield.available) return
        appCtx = ctx.applicationContext
        FirstParty.refresh(appCtx!!)
        ShieldAttribution.clear()
        ShieldInsights.resetSession()
        job?.cancel()
        job = scope.launch {
            val c = appCtx ?: return@launch
            applyAllLocked(c)
            var nextScore = System.currentTimeMillis() + 30_000
            while (isActive) {
                // Resolve apps while their connections are still open, and add
                // this tick's bytes to the weekly per-app totals.
                runCatching { flows()?.let { ShieldInsights.accumulate(c, it.flows) } }
                runCatching { copyRingLocked(c) }
                if (System.currentTimeMillis() >= nextScore) {
                    runCatching { scoreRoundLocked(c) }
                    nextScore = System.currentTimeMillis() + ConfigManager.redshieldIntervalSecs * 1000L
                }
                delay(5_000)
            }
        }
    }

    fun stop() {
        job?.cancel(); job = null
        appCtx?.let { c -> scope.launch { runCatching { copyRingLocked(c) } } }
    }

    /** The master switch or the profiles changed: make the core match now. */
    fun resync(ctx: Context) {
        if (!RedShield.available) return
        scope.launch { applyAllLocked(ctx.applicationContext); _revision.value++ }
    }

    /** Same as [resync], waiting for the core to match (a profile switch reads its refusal after). */
    suspend fun resyncNow(ctx: Context) {
        if (!RedShield.available) return
        withContext(dispatcher) { applyAllLocked(ctx.applicationContext) }
        _revision.value++
    }

    /** Pull to refresh, a Block/Allow: a score round now. */
    fun scoreNow(ctx: Context) {
        if (!RedShield.available) return
        scope.launch { runCatching { scoreRoundLocked(ctx.applicationContext) } }
    }

    /** Suspends until a score round has run (pull to refresh). */
    suspend fun scoreNowAndWait(ctx: Context) {
        if (!RedShield.available) return
        withContext(dispatcher) { runCatching { scoreRoundLocked(ctx.applicationContext) } }
    }

    /** The user's Clear: the table, the score cache, the automatic rules, the notified set. */
    suspend fun clearAll(ctx: Context) = withContext(dispatcher) {
        if (NetCoreBridge.isLoaded) runCatching { NetCoreBridge.clearFlows() }
        ShieldScoreCache.clear(ctx)
        ShieldNotifier.clear(ctx)
        ShieldAttribution.clear()
        applyAutoRulesLocked(ctx)
        _revision.value++
    }

    /** The core's flow table, each row with its app where known; null when the tunnel is not up. */
    fun flows(): ShieldFlows? {
        if (RedShield.tunnelPort() == 0) return null
        val raw = runCatching { ShieldFlows.parse(NetCoreBridge.getFlows()) }.getOrNull() ?: return null
        val ctx = appCtx
        if (ctx != null) ShieldAttribution.resolve(ctx, raw.flows)
        return raw.copy(flows = raw.flows.map { f -> if (f.uid != null) f else f.copy(uid = ShieldAttribution.uidFor(f.host)) })
    }

    private fun applyAllLocked(ctx: Context) {
        if (!NetCoreBridge.isLoaded) return
        // The core's filter stays on so the user's own Block rules apply even
        // with the master switch off (iOS: setBlockingEnabled(true) on start).
        runCatching { NetCoreBridge.setBlockingEnabled(true) }
        RedShieldProfiles.syncIntoCore(ctx, RedShield.deviceOn(ctx))
        applyAutoRulesLocked(ctx)
    }

    private fun applyAutoRulesLocked(ctx: Context) {
        if (!NetCoreBridge.isLoaded) return
        val json = if (!RedShield.deviceOn(ctx)) """{"rules":[]}""" else {
            val countries = RedShieldProfiles.selectedCountries(ctx).associateWith { cc ->
                RedShieldProfiles.name(ctx, "country:$cc") ?: cc
            }
            ShieldScoreCache.load(ctx).autoRulesJson(countries)
        }
        val r = runCatching { NetCoreBridge.setAutoRules(json) }.getOrDefault("err:jni")
        if (r.startsWith("err")) Log.w(TAG, "auto rules: $r")
    }

    /** One round: the unscored destinations, POSTed through the core's SOCKS port. */
    private fun scoreRoundLocked(ctx: Context) {
        if (!RedShield.deviceOn(ctx)) return
        val port = RedShield.tunnelPort()
        if (port == 0) return
        val flows = flows()?.flows ?: return
        val cache = ShieldScoreCache.load(ctx)
        flows.forEach { if (FirstParty.matches(it.host)) FirstParty.noteExempt(it.host) }
        val batch = batch(flows, cache, ConfigManager.redshieldBatch)
        if (batch.isEmpty()) { Log.i(TAG, "score: nothing to send, ${FirstParty.notSent} first-party not sent"); return }
        val cid = com.rostamvpn.android.billing.CashierIdentity.cid(ctx)
        val body = JSONObject().put("cid", cid).put("hosts", ShieldHttp.hostsPayload(batch)).toString().toByteArray()
        val t0 = android.os.SystemClock.elapsedRealtime()
        when (val r = ShieldHttp.request(RedShield.SCORE_URL, body, port = port, maxBytes = 256_000)) {
            is ShieldHttp.Result.Err -> Log.i(TAG, "score: ${batch.size} host(s), ${r.why}")
            is ShieldHttp.Result.Ok -> {
                val o = if (r.code == 200) runCatching { JSONObject(String(r.body)) }.getOrNull() else null
                if (o == null) { Log.i(TAG, "score: ${batch.size} host(s), status ${r.code}"); return }
                val flagged = ShieldScoreCache.update(ctx) { it.merge(o) }
                applyAutoRulesLocked(ctx)
                ShieldNotifier.notify(ctx, flagged)
                Log.i(TAG, "score: ${batch.size} sent, ${o.optJSONArray("scores")?.length() ?: 0} scored, ${flagged.size} flagged, " +
                    "${android.os.SystemClock.elapsedRealtime() - t0} ms; ${FirstParty.summary()}")
                _revision.value++
            }
        }
    }

    /** Not cached (or expired), never our own API, Unusual first, then by bytes. */
    fun batch(flows: List<ShieldFlow>, cache: ShieldScoreCache, limit: Int, now: Long = System.currentTimeMillis()): List<ShieldFlow> =
        flows.filter { !RedShield.isOwn(it.host) && cache.needsScore(it.host, now) }
            .sortedWith(compareByDescending<ShieldFlow> { it.unusual }.thenByDescending { it.totalBytes })
            .take(limit.coerceIn(0, 100))

    /** The core's ring into GuardStore (dedup is the store's). */
    private fun copyRingLocked(ctx: Context) {
        if (!NetCoreBridge.isLoaded) return
        val now = System.currentTimeMillis()
        val o = JSONObject(NetCoreBridge.getBlockStats())
        val arr = o.optJSONArray("recent") ?: return
        if (arr.length() == 0) return
        // Who the address belongs to, kept with the record: the score's ctx
        // (or its older org/cc), else the regional map's country from the
        // flow table. The score cache expires in a day; the record lasts 30.
        val scores = ShieldScoreCache.load(ctx)
        val flowCc = runCatching { flows()?.flows.orEmpty().associate { it.host to it.cc } }.getOrDefault(emptyMap())
        val rows = (0 until arr.length()).mapNotNull {
            val e = arr.getJSONObject(it)
            val host = e.optString("host")
            // Never keep a block record for one of ours (Blocked list, "N blocked today").
            if (RedShield.isOwn(host)) { FirstParty.blocksDropped.incrementAndGet(); return@mapNotNull null }
            GuardStore.Row(
                host = e.optString("host"), port = e.optInt("port"), bits = e.optInt("bits"),
                // auto: the score's label; profile: the profile id; else the category.
                reason = e.optString("reason").ifEmpty { e.optString("category") }.let { r ->
                    if (e.optString("category") == "profile") "profile:$r" else r
                },
                uid = ShieldAttribution.uidFor(e.optString("host")),
                ts = now - e.optLong("secs_ago") * 1000,
                refused = e.optBoolean("refused", true),
                ctx = (scores.score(host)?.context ?: ShieldCtx()).orElse(ShieldCtx(cc = flowCc[host])).takeIf { c -> !c.isEmpty },
            )
        }
        if (rows.isNotEmpty()) GuardStore.get(ctx).addAll(rows)
    }
}

/** One local notification per flagged host per day, where notifications are already allowed. */
object ShieldNotifier {
    private const val CHANNEL = "protection_alerts"

    fun notify(ctx: Context, flagged: List<ShieldScore>) {
        if (flagged.isEmpty()) return
        val nm = androidx.core.app.NotificationManagerCompat.from(ctx)
        // No new permission request: only where the user already allows them.
        if (!nm.areNotificationsEnabled()) return
        val p = RedShield.prefs(ctx)
        val day = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
        val sent = runCatching { JSONObject(p.getString("notified", "{}")!!) }.getOrDefault(JSONObject())
        val keep = JSONObject()
        sent.keys().forEach { k -> if (sent.optString(k) == day) keep.put(k, day) }
        val fresh = flagged.filter { !keep.has(it.host) }
        if (fresh.isEmpty()) return
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            ctx.getSystemService(android.app.NotificationManager::class.java)?.createNotificationChannel(
                android.app.NotificationChannel(CHANNEL, ctx.getString(R.string.rs_alerts_channel, RedShield.name),
                    android.app.NotificationManager.IMPORTANCE_DEFAULT),
            )
        }
        val open = android.app.PendingIntent.getActivity(ctx, 7, ctx.packageManager.getLaunchIntentForPackage(ctx.packageName),
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE)
        for (s in fresh.take(3)) {
            val b = androidx.core.app.NotificationCompat.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(ctx.getString(R.string.rs_flagged_title, RedShield.name, s.host))
                .setContentText(listOfNotNull(
                    com.rostamvpn.android.ui.screens.ShieldContext.reason(ctx, s.ctx) ?: s.label,
                    com.rostamvpn.android.ui.screens.ShieldContext.line(s.context),
                ).joinToString(" · "))
                .setContentIntent(open)
                .setAutoCancel(true)
            runCatching { nm.notify(0x5300 + (s.host.hashCode() and 0xFF), b.build()) }
            keep.put(s.host, day)
        }
        p.edit().putString("notified", keep.toString()).apply()
    }

    fun clear(ctx: Context) { RedShield.prefs(ctx).edit().remove("notified").apply() }
}

/**
 * Per-app attribution (Android only). The tun bridge (tools/hev, brands with
 * BuildConfig.APP_ATTRIBUTION) tells the core each TCP connection's app-side
 * socket; here the OS is asked which app owns it, while the connection is
 * still open: ConnectivityManager.getConnectionOwnerUid, API 29+, allowed for
 * the active VPN app. host -> uid, newest wins; memory only, per session.
 */
object ShieldAttribution {
    private val byHost = java.util.concurrent.ConcurrentHashMap<String, Int>()
    private val tried = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    fun uidFor(host: String): Int? = byHost[host]

    fun clear() { byHost.clear(); tried.clear() }

    fun resolve(ctx: Context, flows: List<ShieldFlow>) {
        if (!BuildConfig.APP_ATTRIBUTION || android.os.Build.VERSION.SDK_INT < 29) return
        val cm = ctx.getSystemService(android.net.ConnectivityManager::class.java) ?: return
        val self = android.os.Process.myUid()
        var budget = 64
        for (f in flows) {
            for (o in f.origins.asReversed()) {
                if (budget <= 0) return
                if (tried.putIfAbsent(o, true) != null) continue
                budget--
                val (src, dst) = parse(o) ?: continue
                val uid = runCatching { cm.getConnectionOwnerUid(android.system.OsConstants.IPPROTO_TCP, src, dst) }
                    .getOrDefault(android.os.Process.INVALID_UID)
                if (uid != android.os.Process.INVALID_UID && uid != self) { byHost[f.host] = uid; break }
            }
        }
        if (tried.size > 20_000) tried.clear()
        if (BuildConfig.DEBUG && budget < 64) android.util.Log.d("RedShield", "attribution: ${64 - budget} asked, ${byHost.size} hosts with an app")
    }

    /** "10.0.0.2:41234>198.18.0.5:443" (IPv6 hosts unbracketed; the port is after the last colon). */
    internal fun parse(o: String): Pair<java.net.InetSocketAddress, java.net.InetSocketAddress>? = runCatching {
        val (a, b) = o.split('>', limit = 2).let { it[0] to it[1] }
        fun sock(x: String): java.net.InetSocketAddress {
            val i = x.lastIndexOf(':')
            return java.net.InetSocketAddress(java.net.InetAddress.getByName(x.substring(0, i)), x.substring(i + 1).toInt())
        }
        sock(a) to sock(b)
    }.getOrNull()
}
