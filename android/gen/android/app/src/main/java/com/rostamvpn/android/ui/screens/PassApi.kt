package com.rostamvpn.android.ui.screens

import android.net.Uri
import android.util.Log
import com.rostamvpn.android.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * e-sim.net `/v1` for the native Passes tab. Documented in
 * silo/e-sim.net/server/README.md. Parsers are pure so they can be checked
 * against a captured response; fetchers return null on any failure.
 *
 * Only numbers and ids are used for display. The server's English prose
 * (`reason`, `tip`, `line`) is not shown, so every visible line localises.
 */
internal val PASS_API: String = BuildConfig.ESIM_BASE + "/v1"

internal data class PassDest(
    val id: String,
    val kind: String,          // country | region | global
    val name: String,
    val flag: String,
    val planCount: Int,
    val fromCents: Long,
    /** The server's own flag that this destination has number plans, when it
     *  sends one (forward-compatible: absent today, see numberDestinationIds). */
    val hasNumbers: Boolean? = null,
    /** ISO-2 codes a regional plan covers (`countries[]`); [id] for a country. */
    val countries: List<String> = emptyList(),
    val has5g: Boolean = false,
    /** Hero photo URL, when the server has one. */
    val image: String = "",
    /** `networks[]`: operator name and its radio types ("5G", "LTE"). */
    val networks: List<Pair<String, List<String>>> = emptyList(),
)

internal data class PassPlan(
    val slug: String,
    val destId: String,
    val dataMb: Long,
    val unlimited: Boolean,
    val days: Int,
    val priceCents: Long,
    val voiceMin: Int,
    val sms: Int,
    /** `data` or `data-voice-text` (a plan that includes a local number). */
    val level: String = "data",
    val rechargeable: Boolean = false,
    val topupGraceDays: Int = 0,
    /**
     * Days of the VPN the purchase grants (`perk.vpn_days`). The server sends
     * it only while the grant is configured; null = no grant, and then no
     * screen may promise one.
     */
    val vpnDays: Int? = null,
    /** The network operator (`operator`), "" when the server does not say. */
    val operator: String = "",
) {
    val isNumber get() = level == "data-voice-text"
}

/** `perk.vpn_days` when present and positive, else null. Pure. */
internal fun perkVpnDays(o: JSONObject?): Int? =
    o?.optJSONObject("perk")?.takeIf { it.has("vpn_days") && !it.isNull("vpn_days") }
        ?.optInt("vpn_days", 0)?.takeIf { it > 0 }

internal data class PassPick(
    val plan: PassPlan,
    val needGb: Double,
    val shorter: PassPlan?,
    val longer: PassPlan?,
)

internal data class PassCatalog(val popular: List<PassDest>, val regions: List<PassDest>, val countries: List<PassDest>)

private fun JSONObject.dest() = PassDest(
    id = optString("id"), kind = optString("kind"), name = optString("name"),
    flag = optString("flag"), planCount = optInt("plan_count"), fromCents = optLong("from_cents"),
    // `has_number` (e-sim.net 2026-10-15); `has_numbers` tolerated.
    hasNumbers = when {
        has("has_number") -> optBoolean("has_number")
        has("has_numbers") -> optBoolean("has_numbers")
        else -> null
    },
    countries = optJSONArray("countries")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty(),
    has5g = optBoolean("has_5g"),
    image = optString("image"),
    networks = optJSONArray("networks")?.let { a ->
        (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { n ->
            n.optString("name") to (n.optJSONArray("types")?.let { t -> (0 until t.length()).map { t.optString(it) } }.orEmpty())
        }
    }.orEmpty(),
)

private fun JSONObject.plan() = PassPlan(
    slug = optString("slug"), destId = optString("destination_id"), dataMb = optLong("data_mb"),
    unlimited = optBoolean("unlimited"), days = optInt("days"), priceCents = optLong("price_cents"),
    voiceMin = optInt("voice_min"), sms = optInt("sms"),
    level = optString("level", "data"), rechargeable = optBoolean("rechargeable"),
    topupGraceDays = optInt("topup_grace_days"),
    vpnDays = perkVpnDays(this),
    operator = optString("operator"),
)

private fun JSONArray?.dests() = if (this == null) emptyList() else (0 until length()).map { getJSONObject(it).dest() }
private fun JSONArray?.plans() = if (this == null) emptyList() else (0 until length()).map { getJSONObject(it).plan() }

internal fun parseCatalog(body: String): PassCatalog = JSONObject(body).let {
    PassCatalog(it.optJSONArray("popular").dests(), it.optJSONArray("regions").dests(), it.optJSONArray("countries").dests())
}

/** `best` first (the country, then regions that contain it), then `also`. */
internal fun parseSearch(body: String): List<PassDest> = JSONObject(body).let {
    it.optJSONArray("best").dests() + it.optJSONArray("also").dests()
}

/** A destination's plans; a destination-level `perk` applies to plans that carry none. */
internal fun parsePlans(body: String): List<PassPlan> = JSONObject(body).let { o ->
    val destPerk = perkVpnDays(o)
    o.optJSONArray("plans").plans().map { if (it.vpnDays == null && destPerk != null) it.copy(vpnDays = destPerk) else it }
}

/**
 * Top-ups of one eSIM. `[]` means this network cannot be reloaded; null means
 * we do not know: `{"topups": null}` (the server's 503 on an Airalo failure),
 * a missing key, or any non-200, which [fetchTopups] already turns into null.
 * Unknown must never read as "not reloadable" (NumberState.reloadState).
 */
internal fun parseTopups(body: String): List<PassPlan>? = JSONObject(body).let {
    if (!it.has("topups") || it.isNull("topups")) null else it.optJSONArray("topups")?.plans()
}

internal fun parsePlan(body: String): PassPlan = JSONObject(body).plan()

/**
 * Destinations that sell numbers, from the catalogue, never hard-coded.
 * Preferred: the `has_number` field on /v1/destinations (server 2026-10-15,
 * not deployed everywhere yet). Without it: the site's own sitemap, which the server
 * generates from plan levels (`/number/{id}` for every destination that has a
 * `data-voice-text` plan). Ids come back in catalogue form (country ISO upper,
 * region lower).
 */
internal fun numberDestinationIds(catalog: PassCatalog?, sitemap: String?): Set<String> {
    val all = catalog?.let { it.countries + it.regions + it.popular }.orEmpty()
    if (all.any { it.hasNumbers != null }) return all.filter { it.hasNumbers == true }.map { it.id }.toSet()
    val ids = Regex("""/number/([a-z0-9-]+)""").findAll(sitemap.orEmpty()).map { it.groupValues[1] }.toSet()
    return ids.map { id -> all.firstOrNull { it.id.equals(id, ignoreCase = true) }?.id ?: if (id.length == 2) id.uppercase() else id }.toSet()
}

internal fun parsePick(body: String): PassPick? = JSONObject(body).let { o ->
    val p = o.optJSONObject("plan") ?: return null
    val alt = o.optJSONObject("alternatives")
    val topPerk = perkVpnDays(o)
    PassPick(
        plan = p.plan().let { if (it.vpnDays == null && topPerk != null) it.copy(vpnDays = topPerk) else it },
        needGb = o.optDouble("need_gb", 0.0),
        shorter = alt?.optJSONObject("short")?.plan()?.let { if (it.vpnDays == null && topPerk != null) it.copy(vpnDays = topPerk) else it },
        longer = alt?.optJSONObject("long")?.plan()?.let { if (it.vpnDays == null && topPerk != null) it.copy(vpnDays = topPerk) else it },
    )
}

/**
 * The e-sim.net sign-in session (D): the `esim_session` cookie value the site
 * hands back as `/me#session=` after Sign in with Google/Apple (auth.py
 * `_finish`, app=1). Sent ONLY as a Cookie header on requests this file and
 * EsimMine.fetchEsims make to e-sim.net, never logged, kept in its own prefs
 * file that backup and device transfer exclude (backup_rules.xml and data_extraction_rules.xml). With
 * it, /v1/esims also returns the eSIMs bought with that email on any device.
 */
internal object EsimSession {
    private const val FILE = "esim_session"
    private const val KEY = "session"
    val token = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    @Volatile private var loaded = false
    @Volatile private var app: android.content.Context? = null

    fun init(ctx: android.content.Context) {
        if (loaded) return
        app = ctx.applicationContext
        token.value = app?.getSharedPreferences(FILE, android.content.Context.MODE_PRIVATE)?.getString(KEY, null)
        loaded = true
    }

    fun set(ctx: android.content.Context, value: String?) {
        app = ctx.applicationContext
        app?.getSharedPreferences(FILE, android.content.Context.MODE_PRIVATE)?.edit()?.apply {
            if (value.isNullOrBlank()) remove(KEY) else putString(KEY, value)
        }?.apply()
        token.value = value?.takeIf { it.isNotBlank() }
        loaded = true
    }

    // ── Accepting a returned session: only one this app asked for ──────────
    // A session link can arrive cold (a message, a web page, another app):
    // esimnet://me#session=<attacker's token> would sign this phone into the
    // attacker's e-sim.net account, which would then receive this user's
    // receipts and install codes. So a returned session is stored only when
    // this app itself opened /auth/{provider}/login in the last 15 minutes, and only
    // once. Same rule as iOS. The start time is on disk because the process
    // can die while the browser is in front.
    private const val KEY_STARTED = "signin_started_at"

    /** The app is about to open the site's /auth/{provider}/login. */
    fun signInStarted(ctx: android.content.Context, nowMs: Long = System.currentTimeMillis()) {
        ctx.applicationContext.getSharedPreferences(FILE, android.content.Context.MODE_PRIVATE)
            .edit().putLong(KEY_STARTED, nowMs).apply()
    }

    /**
     * A session arrived on a return link. Stored only inside the window of a
     * sign-in this app started, which it then closes; otherwise dropped
     * silently. True when it was stored.
     */
    fun offer(ctx: android.content.Context, value: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        val prefs = ctx.applicationContext.getSharedPreferences(FILE, android.content.Context.MODE_PRIVATE)
        val gate = SignInGate(prefs.getLong(KEY_STARTED, 0L).takeIf { it > 0L })
        if (!gate.offer(nowMs)) return false
        prefs.edit().remove(KEY_STARTED).apply()     // once
        set(ctx, value)
        return true
    }

    /** Attach the cookie to a connection that is going to e-sim.net, and only then. */
    fun attach(c: HttpURLConnection) {
        val t = token.value ?: return
        if (ESIM_HOST.isEmpty()) return
        if (c.url.protocol != "https" || c.url.host != ESIM_HOST) return
        c.setRequestProperty("Cookie", "esim_session=$t")
    }
}

/** How long after the app opens /auth/{provider}/login a returned session is accepted. */
internal const val SIGNIN_WINDOW_MS = 15 * 60 * 1000L

/** Pure: a returned session is accepted only within the window of a sign-in this app started. */
internal fun acceptReturnedSession(startedAtMs: Long?, nowMs: Long): Boolean =
    startedAtMs != null && nowMs >= startedAtMs && nowMs - startedAtMs <= SIGNIN_WINDOW_MS

/**
 * The accept-once rule over the recorded start time, pure so it is tested.
 * [offer] accepts inside the window and closes it; anything else is refused.
 */
internal class SignInGate(var startedAtMs: Long?) {
    fun offer(nowMs: Long): Boolean {
        if (!acceptReturnedSession(startedAtMs, nowMs)) return false
        startedAtMs = null
        return true
    }
}

/**
 * The session from a `/me#session=…` return, or null. The site URL-quotes the
 * token (`email|exp|hmac`); [decoded] is the fragment Android already decoded
 * once (Uri.getFragment), so it is used as is. Pure.
 */
internal fun sessionFromFragment(decoded: String?): String? =
    decoded?.split('&')?.firstOrNull { it.startsWith("session=") }?.removePrefix("session=")
        ?.takeIf { it.count { ch -> ch == '|' } == 2 && it.length in 20..600 }

private suspend fun get(path: String): String? = withContext(Dispatchers.IO) {
    runCatching {
        val c = URL("$PASS_API$path").openConnection() as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = 20_000
        EsimSession.attach(c)
        try {
            if (c.responseCode != 200) null else c.inputStream.bufferedReader().readText()
        } finally {
            c.disconnect()
        }
    }.onFailure { Log.w("PassApi", "GET failed: ${it.javaClass.simpleName}") }.getOrNull()
}

/**
 * `POST /v1/events`, fire and forget (server api.py `events`). Names must be
 * in the deployed server's EVENT_NAMES or the row is dropped silently: it has
 * `pay_sheet_open` and `pay_success`, not "pay_opened" or "paid". The id is
 * the same device id every other e-sim.net call already carries.
 */
internal object EsimEvents {
    const val SEARCH = "search"
    const val LANDING_VIEW = "landing_view"
    const val PAY_SHEET_OPEN = "pay_sheet_open"
    const val PAY_SUCCESS = "pay_success"
    const val INSTALL_STEP = "install_step"
    const val FIRST_ATTACH = "first_attach"
    const val TOPUP_PROMPT_SHOWN = "topup_prompt_shown"
    const val TOPUP_ACCEPTED = "topup_accepted"

    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)

    fun track(deviceId: String, name: String, dest: String = "", props: Map<String, Any?> = emptyMap()) {
        if (!BuildConfig.FEATURE_ESIM) return
        val body = eventBody(deviceId, name, dest, props)
        scope.launch { post("/events", body) }
    }
}

/** Pure, so the wire shape is checked by a test. */
internal fun eventBody(deviceId: String, name: String, dest: String, props: Map<String, Any?>): String {
    val ev = JSONObject()
        .put("name", name).put("device_id", deviceId).put("dest", dest)
        .put("utm", BuildConfig.CASHIER_BRAND).put("source", "android")
        .put("props", JSONObject().apply { props.forEach { (k, v) -> if (v != null) put(k, v) } })
    return JSONObject().put("events", JSONArray().put(ev)).toString()
}

/** JSON POST (or DELETE when [body] is null and [method] says so). Status and body; status -1 on failure. */
internal suspend fun post(path: String, body: String?, method: String = "POST"): Pair<Int, String?> = withContext(Dispatchers.IO) {
    runCatching {
        val c = URL("$PASS_API$path").openConnection() as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = 20_000
        c.requestMethod = method
        EsimSession.attach(c)
        try {
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(body.toByteArray()) }
            }
            val code = c.responseCode
            code to runCatching { (if (code < 400) c.inputStream else c.errorStream)?.bufferedReader()?.readText() }.getOrNull()
        } finally {
            c.disconnect()
        }
    }.onFailure { Log.w("PassApi", "$method failed: ${it.javaClass.simpleName}") }.getOrElse { -1 to null }
}

/**
 * The last good catalogue on disk, so the store still opens offline (req C-2).
 * Only `/destinations` and `/destinations/{id}`: prices and plans, nothing
 * tied to the device. A body is written only when it parsed, and read only
 * when the network failed; [stale] then says the screen shows saved prices.
 */
internal object PassCache {
    @Volatile private var dir: java.io.File? = null
    val stale = kotlinx.coroutines.flow.MutableStateFlow(false)

    fun init(ctx: android.content.Context) {
        if (dir == null) dir = java.io.File(ctx.applicationContext.cacheDir, "esim-catalog").apply { mkdirs() }
    }

    private fun file(path: String) = dir?.let { java.io.File(it, path.trim('/').replace(Regex("[^A-Za-z0-9_-]"), "_") + ".json") }

    fun put(path: String, body: String) {
        runCatching { file(path)?.writeText(body) }
        stale.value = false
    }

    fun read(path: String): String? = runCatching { file(path)?.takeIf { it.exists() }?.readText() }.getOrNull()
        ?.also { stale.value = true }
}

/** GET with the disk fallback above; [parse] gates what is written. */
private suspend fun <T> getCached(path: String, parse: (String) -> T): T? {
    get(path)?.let { body -> runCatching { parse(body) }.getOrNull()?.let { PassCache.put(path, body); return it } }
    return withContext(Dispatchers.IO) { PassCache.read(path) }?.let { runCatching { parse(it) }.getOrNull() }
}

/** `POST /v1/account/code`: this device's e-sim.net access code (created on first ask). */
internal suspend fun fetchAccessCode(deviceId: String): String? {
    val (code, body) = post("/account/code", JSONObject().put("device_id", deviceId).toString())
    return if (code == 200) body?.let { runCatching { JSONObject(it).optString("code").takeIf { c -> c.isNotBlank() } }.getOrNull() } else null
}

/** Restore outcome: moved count, or why not. */
internal sealed class ClaimResult {
    data class Moved(val n: Int) : ClaimResult()
    data object BadCode : ClaimResult()
    data object Failed : ClaimResult()
}

/** `POST /v1/account/claim`: every eSIM of the code's device moves to this device. */
internal suspend fun claimAccessCode(code: String, deviceId: String): ClaimResult {
    val (status, body) = post("/account/claim", JSONObject().put("code", code.trim().uppercase()).put("device_id", deviceId).toString())
    return when (status) {
        200 -> ClaimResult.Moved(body?.let { runCatching { JSONObject(it).optInt("moved") }.getOrNull() } ?: 0)
        404 -> ClaimResult.BadCode
        else -> ClaimResult.Failed
    }
}

/** A seasonal collection (`/v1/collections`, server collections.py), already in [lang]. */
internal data class PassCollection(
    val id: String,
    val title: String,
    val subtitle: String,
    val destinations: List<PassDest> = emptyList(),
)

private fun JSONObject.collection() = PassCollection(
    id = optString("id"), title = optString("title"), subtitle = optString("subtitle"),
    destinations = optJSONArray("destinations").dests(),
)

internal fun parseCollections(body: String): List<PassCollection> =
    JSONObject(body).optJSONArray("collections").let { a ->
        if (a == null) emptyList() else (0 until a.length()).map { a.getJSONObject(it).collection() }
    }.filter { it.id.isNotBlank() && it.title.isNotBlank() }

internal fun parseCollection(body: String): PassCollection = JSONObject(body).collection()

private fun langQ(lang: String?) = lang?.let { "?lang=${Uri.encode(it)}" } ?: ""

internal suspend fun fetchCollections(lang: String?) =
    get("/collections${langQ(lang)}")?.let { runCatching { parseCollections(it) }.getOrNull() }
internal suspend fun fetchCollection(id: String, lang: String?) =
    get("/collections/${Uri.encode(id)}${langQ(lang)}")?.let { runCatching { parseCollection(it) }.getOrNull() }

/** Plan-list order (C10). */
internal enum class PlanSort { TOTAL, PER_GB, PER_DAY }

/** Cents per GB; null for unlimited or no data (they cannot be compared per GB). */
internal fun perGbCents(p: PassPlan): Double? = if (p.unlimited || p.dataMb <= 0) null else p.priceCents * 1024.0 / p.dataMb

internal fun sortPlans(plans: List<PassPlan>, by: PlanSort): List<PassPlan> = when (by) {
    PlanSort.TOTAL -> plans.sortedWith(compareBy({ it.priceCents }, { -it.dataMb }))
    PlanSort.PER_DAY -> plans.sortedWith(compareBy({ it.priceCents.toDouble() / it.days.coerceAtLeast(1) }, { it.priceCents }))
    PlanSort.PER_GB -> plans.sortedWith(compareBy<PassPlan>({ perGbCents(it) == null }, { perGbCents(it) ?: 0.0 }, { it.priceCents }))
}

/** The server's own rule (catalog.best_value): lowest price per GB among plans with a data cap. */
internal fun bestValue(plans: List<PassPlan>): PassPlan? = plans.filter { perGbCents(it) != null }.minByOrNull { perGbCents(it)!! }

/** `GET /v1/me`: who is signed in, and which providers the site offers. */
internal data class EsimMe(val signedIn: Boolean, val email: String, val apple: Boolean, val google: Boolean)

internal fun parseMe(body: String): EsimMe = JSONObject(body).let {
    EsimMe(it.optBoolean("signed_in"), it.optString("email"), it.optBoolean("apple"), it.optBoolean("google"))
}

internal suspend fun fetchMe(): EsimMe? = get("/me")?.let { runCatching { parseMe(it) }.getOrNull() }

/** `DELETE /v1/me`: unlink the email from every code and order, end the session. */
internal suspend fun deleteMe(): Boolean = post("/me", null, method = "DELETE").first == 200

/** The site's sign-in for [provider] (google | apple), returning to /me#session= (app=1). */
internal fun signInUrl(provider: String, deviceId: String): String =
    BuildConfig.ESIM_BASE + "/auth/${Uri.encode(provider)}/login?app=1&next=%2Fme&device_id=${Uri.encode(deviceId)}"

/**
 * Catalogue and top-up paths, each with `utm_source=<CASHIER_BRAND>`: the
 * server sends `perk.vpn_days` only when the request names a brand whose
 * grant is configured (e-sim.net e828773; `app=1` alone does not count).
 * Pure, so a test pins the parameter on every one.
 */
internal object CatalogPaths {
    private fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20")
    // utm_medium=android is what makes the Internet Pro perk eligible: the iOS app sends the same utm_source
    // and must never earn VPN time for an eSIM (App Review 3.1.1 vs 3.1.3(e)); the server keys the perk on both.
    private fun utm(path: String, brand: String) = path + (if ('?' in path) "&" else "?") + "utm_source=" + enc(brand) + "&utm_medium=android"
    fun destinations(brand: String = BuildConfig.CASHIER_BRAND) = utm("/destinations", brand)
    fun destination(id: String, brand: String = BuildConfig.CASHIER_BRAND) = utm("/destinations/${enc(id)}", brand)
    fun plan(slug: String, brand: String = BuildConfig.CASHIER_BRAND) = utm("/plans/${enc(slug)}", brand)
    fun topups(iccid: String, brand: String = BuildConfig.CASHIER_BRAND) = utm("/esims/${enc(iccid)}/topups", brand)
    fun recommend(destId: String, days: Int, usage: String, brand: String = BuildConfig.CASHIER_BRAND) =
        utm("/recommend?dest=${enc(destId)}&days=$days&usage=${enc(usage)}", brand)
}

internal suspend fun fetchCatalog() = getCached(CatalogPaths.destinations(), ::parseCatalog)
internal suspend fun fetchSearch(q: String) =
    get("/search?q=${Uri.encode(q)}")?.let { runCatching { parseSearch(it) }.getOrNull() }
internal suspend fun fetchPlans(destId: String) = getCached(CatalogPaths.destination(destId), ::parsePlans)
internal suspend fun fetchPlan(slug: String) =
    get(CatalogPaths.plan(slug))?.let { runCatching { parsePlan(it) }.getOrNull() }

/** The site's sitemap (number-destination fallback). Not under /v1. */
internal suspend fun fetchSitemap(): String? = withContext(Dispatchers.IO) {
    runCatching {
        if (BuildConfig.ESIM_BASE.isEmpty()) return@runCatching null
        val c = URL(BuildConfig.ESIM_BASE + "/sitemap.xml").openConnection() as HttpURLConnection
        c.connectTimeout = 15_000; c.readTimeout = 20_000
        try { if (c.responseCode != 200) null else c.inputStream.bufferedReader().readText() } finally { c.disconnect() }
    }.getOrNull()
}

internal suspend fun fetchTopups(iccid: String): List<PassPlan>? =
    get(CatalogPaths.topups(iccid))?.let { runCatching { parseTopups(it) }.getOrNull() }

/** `usage` is one of maps | socials | streaming (README). */
internal suspend fun fetchPick(destId: String, days: Int, usage: String) =
    get(CatalogPaths.recommend(destId, days, usage))?.let { runCatching { parsePick(it) }.getOrNull() }

/**
 * The only web surface: e-sim.net's own payment page. `iccid` makes it a
 * top-up of that eSIM (web.py `checkout(plan, iccid)`). The utm is the
 * brand's cashier id, never a literal.
 */
internal fun passCheckoutUrl(slug: String, deviceId: String, iccid: String? = null, lang: String? = null): String =
    BuildConfig.ESIM_BASE + "/checkout?plan=${Uri.encode(slug)}&device_id=${Uri.encode(deviceId)}&app=1" +
        "&utm_source=${Uri.encode(BuildConfig.CASHIER_BRAND)}&utm_medium=android" +
        (iccid?.let { "&iccid=${Uri.encode(it)}" } ?: "") +
        (lang?.let { "&lang=${Uri.encode(it)}" } ?: "")

/** e-sim.net's languages (server app/i18n.py LANGS). English is its default. */
private val SITE_LANGS = setOf(
    "en", "es", "fr", "de", "it", "pt", "nl", "pl", "tr", "ru", "uk", "ar", "he", "fa",
    "hi", "id", "th", "vi", "ja", "ko",
)

/**
 * The app's locale in e-sim.net's code, or null when the site has no such
 * language (then no `lang=` is sent and the site keeps its own default).
 * Android still reports Hebrew and Indonesian under their legacy codes
 * (`iw`, `in`); Chinese is split by script, falling back to the region.
 */
internal fun esimLang(loc: java.util.Locale): String? {
    return when (val l = loc.language.lowercase()) {
        "iw" -> "he"
        "in" -> "id"
        "zh" -> when {
            loc.script.equals("Hant", true) -> "zh-Hant"
            loc.script.equals("Hans", true) -> "zh-Hans"
            loc.country.uppercase() in setOf("TW", "HK", "MO") -> "zh-Hant"
            else -> "zh-Hans"
        }
        else -> l.takeIf { it in SITE_LANGS }
    }
}

/** "$5" / "$4.99". Prices are USD, tax included, the same on web and app. */
internal fun usd(cents: Long): String =
    "$" + if (cents % 100 == 0L) "${cents / 100}" else String.format(java.util.Locale.US, "%.2f", cents / 100.0)

/**
 * Data size in the user's own digits and unit (Persian shows Persian digits
 * and its own unit word). The catalogue counts 1 GB as 1024 MB, so the byte
 * count handed to the framework formatter is scaled to print "5 GB", not "5.4".
 */
internal fun dataLabel(ctx: android.content.Context, mb: Long): String {
    val loc0 = ctx.resources.configuration.locales[0]
    if (mb < 1024) {
        // The formatter rolls over to GB above ~900 units, so 1000 MB read as
        // "1.0 GB". Below 1024 MB keep MB: take the localized unit from a
        // sample the formatter writes in MB, and put this count in front.
        val sample = android.text.format.Formatter.formatShortFileSize(ctx, 500_000_000L)
        val unit = sample.replace(java.text.NumberFormat.getInstance(loc0).format(500), "").trim()
        val n = java.text.NumberFormat.getInstance(loc0).format(mb)
        return if (unit.isNotEmpty() && unit != sample) "$n $unit" else "$mb MB"
    }
    val bytes = (mb / 1024.0 * 1e9).toLong()
    val out = android.text.format.Formatter.formatShortFileSize(ctx, bytes)
    // The formatter keeps one decimal ("5.0 GB", "۵٫۰ گیگابایت"); drop it for a
    // whole number, written with the same locale's digits.
    val whole = if (mb >= 1024) mb / 1024 else mb
    if (mb >= 1024 && mb % 1024 != 0L) return out
    val loc = ctx.resources.configuration.locales[0]
    val oneDec = java.text.NumberFormat.getInstance(loc).apply { minimumFractionDigits = 1; maximumFractionDigits = 1 }
    return out.replace(oneDec.format(whole.toDouble()), java.text.NumberFormat.getInstance(loc).format(whole))
}
