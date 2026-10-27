package com.rostamvpn.android

import app.netcore.NetCoreBridge
import android.content.Context
import android.util.Base64
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.zip.GZIPInputStream

/**
 * Block profiles (REDSHIELD-TRAFFIC Amendments C and D; iOS twin:
 * RostamKit/GuardianProfiles.swift). Named, versioned, signed lists the user
 * switches on: domain suffixes, or address ranges for country, region,
 * provider and hosting profiles. The catalogue and the bodies are fetched
 * through the tunnel only, each body's Ed25519 signature is checked against
 * the compiled-in key, and the body is kept as received (gzip). Enabled ones
 * are loaded into the core (`setProfile`) on toggle and on every tunnel start.
 */
data class ShieldProfile(
    val id: String,
    val name: String,
    /** domains | ranges | country */
    val kind: String,
    val entries: Int,
    val version: String? = null,
    val bytes: Int? = null,
    /** A region's member countries (the catalogue carries them); null otherwise. */
    val countries: List<String>? = null,
) {
    /** What the core is told. */
    val coreKind: String get() = if (kind == "domains") "domains" else "ranges"
    val isCountry: Boolean get() = id.startsWith("country:")
    val isRegion: Boolean get() = id.startsWith("region:")
    val isCustom: Boolean get() = id.startsWith("custom:")
    val isAds: Boolean get() = RedShieldProfiles.isAdsId(id)
    /** The regional address map (`geo`, kind `space`): a bundle the core loads whole, never a switch. */
    val isSpace: Boolean get() = kind == "space" || id == RedShieldProfiles.GEO_ID
    /** Decided by country code once the regional map is on the phone. */
    val isPlace: Boolean get() = isCountry || isRegion
    val countryCode: String? get() = if (isCountry) id.removePrefix("country:").uppercase() else null

    fun toJson(): JSONObject = JSONObject().put("id", id).put("name", name).put("kind", kind).put("entries", entries)
        .apply {
            version?.let { put("version", it) }; bytes?.let { put("bytes", it) }
            countries?.let { put("countries", JSONArray(it)) }
        }

    companion object {
        fun fromJson(o: JSONObject): ShieldProfile? {
            val id = o.optString("id").takeIf { it.isNotEmpty() } ?: return null
            return ShieldProfile(
                id, o.optString("name").ifEmpty { id }, o.optString("kind").ifEmpty { "domains" }, o.optInt("entries"),
                o.optString("version").takeIf { o.has("version") && it.isNotEmpty() },
                if (o.has("bytes")) o.optInt("bytes") else null,
                o.optJSONArray("countries")?.let { a -> (0 until a.length()).map { a.optString(it).uppercase() } },
            )
        }

        fun listFromJson(a: JSONArray?): List<ShieldProfile> =
            (0 until (a?.length() ?: 0)).mapNotNull { a!!.optJSONObject(it)?.let(::fromJson) }
    }
}

object RedShieldProfiles {
    val CATALOGUE_URL: String = "${RedShield.API_BASE}/redshield/v1/profiles"
    /** The regional address map bundle: every prefix with its country (iOS twin: GuardianGeoStore). */
    const val GEO_ID = "geo"
    const val GEO_REFRESH_MS = 7L * 86_400_000L
    /** The bundle signing key (Ed25519, raw 32 bytes). */
    const val PUBLIC_KEY_HEX = "5cdf94ae1c8fdf7f4219dfcd8b479684f015d76600ef8e8ca0c5b7792344286e"
    const val CUSTOM_CAP = 20_000

    fun isAdsId(id: String) = id == "ads" || id.startsWith("ads:")

    fun bodyUrl(id: String): String = "$CATALOGUE_URL/" + java.net.URLEncoder.encode(id, "UTF-8").replace("+", "%20")

    private fun p(ctx: Context) = RedShield.prefs(ctx)

    // ── state ──

    fun enabled(ctx: Context): Set<String> = p(ctx).getStringSet("profiles_on", emptySet())!!.toSet()

    fun setEnabled(ctx: Context, ids: Set<String>) { p(ctx).edit().putStringSet("profiles_on", ids.toSet()).apply() }

    fun catalogue(ctx: Context): List<ShieldProfile> =
        runCatching { ShieldProfile.listFromJson(JSONObject(p(ctx).getString("catalogue", "{}")!!).optJSONArray("profiles")) }
            .getOrDefault(emptyList()).filter { !it.isSpace }

    /** The regional map's catalogue entry (its `version` is the body's sha256); null until the server lists it. */
    fun geoEntry(ctx: Context): ShieldProfile? = runCatching {
        val o = JSONObject(p(ctx).getString("catalogue", "{}")!!)
        (ShieldProfile.listFromJson(o.optJSONArray("bundles")) + ShieldProfile.listFromJson(o.optJSONArray("profiles")))
            .firstOrNull { it.id == GEO_ID }
    }.getOrNull()

    fun custom(ctx: Context): List<ShieldProfile> =
        runCatching { ShieldProfile.listFromJson(JSONArray(p(ctx).getString("custom", "[]")!!)) }.getOrDefault(emptyList())

    private fun setCustom(ctx: Context, l: List<ShieldProfile>) {
        p(ctx).edit().putString("custom", JSONArray().apply { l.forEach { put(it.toJson()) } }.toString()).apply()
    }

    fun all(ctx: Context): List<ShieldProfile> = catalogue(ctx) + custom(ctx)

    fun profile(ctx: Context, id: String): ShieldProfile? = all(ctx).firstOrNull { it.id == id }

    /** The display name for a profile id (the Blocked list's reason). */
    fun name(ctx: Context, id: String): String? =
        (RedShieldDemo.profiles()?.firstOrNull { it.id == id } ?: profile(ctx, id))?.name

    fun selectedCountries(ctx: Context): Set<String> =
        enabled(ctx).filter { it.startsWith("country:") }.map { it.removePrefix("country:").uppercase() }.toSet()

    private data class Meta(val etag: String?, val fetchedAt: Long)

    private fun meta(ctx: Context): JSONObject = runCatching { JSONObject(p(ctx).getString("meta", "{}")!!) }.getOrDefault(JSONObject())

    private fun putMeta(ctx: Context, id: String, m: Meta?) {
        val all = meta(ctx)
        if (m == null) all.remove(id) else all.put(id, JSONObject().put("etag", m.etag ?: "").put("at", m.fetchedAt))
        p(ctx).edit().putString("meta", all.toString()).apply()
    }

    private fun metaFor(ctx: Context, id: String): Meta? = meta(ctx).optJSONObject(id)?.let {
        Meta(it.optString("etag").ifEmpty { null }, it.optLong("at"))
    }

    // ── bodies on disk ──

    private fun dir(ctx: Context) = File(ctx.filesDir, "shield-profiles").apply { mkdirs() }

    private fun file(ctx: Context, id: String) =
        File(dir(ctx), id.map { if (it.isLetterOrDigit() || it == '-' || it == '_') it else '_' }.joinToString("") + ".body")

    fun hasBody(ctx: Context, id: String) = file(ctx, id).exists()

    /** The body as text, gunzipped when it is gzip. */
    fun bodyText(ctx: Context, id: String): String? = runCatching {
        val b = file(ctx, id).readBytes()
        val plain = if (b.size > 2 && b[0] == 0x1f.toByte() && b[1] == 0x8b.toByte())
            GZIPInputStream(b.inputStream()).use { it.readBytes() } else b
        String(plain, Charsets.UTF_8)
    }.getOrNull()

    private fun writeBody(ctx: Context, id: String, data: ByteArray) {
        val f = file(ctx, id)
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeBytes(data); tmp.renameTo(f)
    }

    fun removeBody(ctx: Context, id: String) { file(ctx, id).delete(); putMeta(ctx, id, null) }

    // ── verification ──

    /** Ed25519 over the received body bytes; the signature arrives hex or base64. */
    fun verify(body: ByteArray, signature: String): Boolean {
        val key = Ed25519.hex(PUBLIC_KEY_HEX) ?: return false
        val s = signature.trim()
        val sig = Ed25519.hex(s) ?: runCatching { Base64.decode(s, Base64.DEFAULT) }.getOrNull() ?: return false
        if (sig.size != 64) return false
        return Ed25519.verify(key, body, sig)
    }

    // ── fetching (through the tunnel only) ──

    enum class FetchError { OFFLINE, BAD_SIGNATURE, FAILED }

    fun fetchCatalogue(ctx: Context): List<ShieldProfile>? {
        val r = ShieldHttp.request(CATALOGUE_URL, maxBytes = 2_000_000) as? ShieldHttp.Result.Ok ?: return null
        if (r.code != 200) return null
        val raw = String(r.body)
        val o = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        val list = runCatching { ShieldProfile.listFromJson(o.optJSONArray("profiles")) }.getOrNull() ?: return null
        // Whole-file bundles (the regional map) ride a separate array.
        val bundles = ShieldProfile.listFromJson(o.optJSONArray("bundles"))
        p(ctx).edit().putString("catalogue", JSONObject()
            .put("profiles", JSONArray().apply { list.forEach { put(it.toJson()) } })
            .put("bundles", JSONArray().apply { bundles.forEach { put(it.toJson()) } }).toString()).apply()
        return list.filter { !it.isSpace }
    }

    /** Download, or revalidate with If-None-Match, one body. `force` = even if fetched in the last 24 h. */
    fun fetchBody(ctx: Context, id: String, force: Boolean = false): FetchError? {
        val m = metaFor(ctx, id)
        if (!force && hasBody(ctx, id) && m != null && System.currentTimeMillis() - m.fetchedAt < 86_400_000) return null
        val headers = if (hasBody(ctx, id) && m?.etag != null) mapOf("If-None-Match" to m.etag) else emptyMap()
        return when (val r = ShieldHttp.request(bodyUrl(id), headers = headers, timeoutMs = 30_000, maxBytes = 16_000_000)) {
            is ShieldHttp.Result.Err -> if (r.why == ShieldHttp.Failure.OFFLINE) FetchError.OFFLINE else FetchError.FAILED
            is ShieldHttp.Result.Ok -> when (r.code) {
                304 -> { putMeta(ctx, id, Meta(m?.etag, System.currentTimeMillis())); null }
                200 -> {
                    // The deployed API sends X-Signature-Ed25519 (checked live
                    // 2026-10-25); the other names are what the iOS client reads.
                    val sig = r.headers["x-signature-ed25519"] ?: r.headers["x-signature"]
                        ?: r.headers["x-redshield-signature"] ?: r.headers["signature"] ?: ""
                    if (!verify(r.body, sig)) FetchError.BAD_SIGNATURE else {
                        writeBody(ctx, id, r.body)
                        putMeta(ctx, id, Meta(r.headers["etag"], System.currentTimeMillis()))
                        null
                    }
                }
                else -> FetchError.FAILED
            }
        }
    }

    /**
     * Daily revalidation of the enabled lists, while connected; the regional
     * map on the first connected session, then weekly or when the catalogue's
     * version changes. True when anything changed (the core should resync).
     */
    fun refreshDaily(ctx: Context): Boolean {
        if (RedShield.tunnelPort() == 0) return false
        var changed = false
        val geo = hasGeo(ctx)
        for (id in enabled(ctx)) {
            if (id.startsWith("custom:")) continue
            // A place decided by code needs no body kept fresh.
            if (geo && (id.startsWith("country:") || id.startsWith("region:"))) continue
            if (fetchBody(ctx, id) == null) changed = true
        }
        // The catalogue (small) tells whether the map's version moved.
        fetchCatalogue(ctx)
        if (refreshGeoIfDue(ctx)) changed = true
        return changed
    }

    // ── the regional address map ──

    /** The map's build date and record counts, from the 32-byte header. */
    data class GeoInfo(val date: String, val v4: Int, val v6: Int) { val ranges: Int get() = v4 + v6 }

    private fun geoPlain(ctx: Context) = File(dir(ctx), "geo.plain")

    /** The decompressed map is on the phone. */
    fun hasGeo(ctx: Context): Boolean = geoPlain(ctx).exists()

    /** `size|mtime` of the cached map: the core is re-sent when it changes. */
    private fun geoStamp(ctx: Context): String? = geoPlain(ctx).takeIf { it.exists() }?.let { "${it.length()}|${it.lastModified()}" }

    /**
     * `RSGEO1` header: magic[6], version u16, built u32 (unix days), v4_off, v4_n, v6_off, v6_n,
     * flags — little-endian. Null when it is not a map.
     */
    fun parseGeoHeader(b: ByteArray): GeoInfo? {
        if (b.size < 32 || String(b, 0, 6, Charsets.US_ASCII) != "RSGEO1" || b[6] != 1.toByte() || b[7] != 0.toByte()) return null
        fun u32(o: Int): Int = (b[o].toInt() and 0xff) or ((b[o + 1].toInt() and 0xff) shl 8) or
            ((b[o + 2].toInt() and 0xff) shl 16) or ((b[o + 3].toInt() and 0xff) shl 24)
        val f = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
        return GeoInfo(f.format(java.util.Date(u32(8).toLong() * 86_400_000L)), u32(16), u32(24))
    }

    fun geoInfo(ctx: Context): GeoInfo? = runCatching {
        val f = geoPlain(ctx)
        if (!f.exists()) return null
        f.inputStream().use { val h = ByteArray(32); if (it.read(h) != 32) null else parseGeoHeader(h) }
    }.getOrNull()

    /** The cached body's version (the ETag the API sent, unquoted). */
    private fun geoCachedVersion(ctx: Context): String? = metaFor(ctx, GEO_ID)?.etag?.trim('"', ' ')?.removePrefix("W/")?.trim('"')

    /**
     * Fetch the map when due: none yet, the catalogue's `version` differs from
     * the cached one, or the last fetch is a week old. The same signed body
     * path as every list ([fetchBody]), inflated once here so the service
     * only reads it. True when the cached map changed.
     */
    fun refreshGeoIfDue(ctx: Context, now: Long = System.currentTimeMillis()): Boolean {
        val entry = geoEntry(ctx)
        if (entry == null && p(ctx).contains("catalogue")) return false   // the server does not offer it yet
        val m = metaFor(ctx, GEO_ID)
        val due = !hasGeo(ctx) || (entry?.version != null && entry.version != geoCachedVersion(ctx)) ||
            m == null || now - m.fetchedAt >= GEO_REFRESH_MS
        if (!due) return false
        if (fetchBody(ctx, GEO_ID, force = true) != null) return false
        return inflateGeo(ctx)
    }

    /** `geo.body` (gzip, verified) -> `geo.plain`. True when written. */
    private fun inflateGeo(ctx: Context): Boolean = runCatching {
        val b = file(ctx, GEO_ID).readBytes()
        val plain = if (b.size > 2 && b[0] == 0x1f.toByte() && b[1] == 0x8b.toByte())
            GZIPInputStream(b.inputStream()).use { it.readBytes() } else b
        if (parseGeoHeader(plain) == null) return false
        val f = geoPlain(ctx)
        if (f.exists() && f.length() == plain.size.toLong() && f.readBytes().contentEquals(plain)) return false
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeBytes(plain); tmp.renameTo(f)
        true
    }.getOrDefault(false)

    fun removeGeo(ctx: Context) { removeBody(ctx, GEO_ID); geoPlain(ctx).delete() }

    /** The stamp of the cached file the core currently holds. */
    @Volatile private var loadedGeoStamp: String? = null

    /** Load the cached map into the core when it is not there yet or the file changed. True when a map is loaded. */
    fun loadGeoIfNeeded(ctx: Context): Boolean {
        if (!NetCoreBridge.isLoaded) return false
        val loaded = runCatching { JSONObject(NetCoreBridge.geoInfo()).optBoolean("loaded") }.getOrDefault(false)
        val stamp = geoStamp(ctx)
        if (loaded && stamp == loadedGeoStamp) return true
        if (stamp == null) return loaded
        val r = runCatching { NetCoreBridge.setGeo(geoPlain(ctx).readBytes()) }.getOrDefault("err:jni")
        if (r.startsWith("ok")) loadedGeoStamp = stamp else android.util.Log.w("RedShield", "geo: $r")
        return r.startsWith("ok") || loaded
    }

    // ── custom lists ──

    /** Hosts-file or one-domain-per-line text, normalised, deduped, capped. */
    fun parseCustom(text: String): List<String> {
        val seen = HashSet<String>(); val out = ArrayList<String>()
        for (raw in text.lineSequence()) {
            val line = raw.substringBefore('#').trim()
            if (line.isEmpty()) continue
            val f = line.split(' ', '\t').filter { it.isNotEmpty() }
            var host = if (f.size >= 2 && f[0] in setOf("0.0.0.0", "127.0.0.1", "::", "::1")) f[1] else f[0]
            host = host.lowercase().trim('.')
            while (host.startsWith("*.")) host = host.substring(2)
            if (host.contains("/")) {
                host = runCatching { java.net.URI(if ("://" in host) host else "http://$host").host }.getOrNull() ?: continue
            }
            if ('.' !in host || host == "localhost" || ' ' in host) continue
            if (!host.all { it.isLetterOrDigit() || it == '.' || it == '-' || it == '_' }) continue
            if (!seen.add(host)) continue
            out += host
            if (out.size >= CUSTOM_CAP) break
        }
        return out
    }

    fun addCustom(ctx: Context, name: String, hosts: List<String>): ShieldProfile {
        val id = "custom:" + java.util.UUID.randomUUID().toString().take(8)
        val p = ShieldProfile(id, name, "domains", hosts.size)
        writeBody(ctx, id, hosts.joinToString("\n").toByteArray())
        setCustom(ctx, custom(ctx) + p)
        setEnabled(ctx, enabled(ctx) + id)
        return p
    }

    fun removeCustom(ctx: Context, id: String) {
        setCustom(ctx, custom(ctx).filter { it.id != id })
        setEnabled(ctx, enabled(ctx) - id)
        removeBody(ctx, id)
    }

    // ── into the core ──

    /**
     * Make the core match: every enabled profile with a body loaded, every
     * other known one cleared. `on` false clears them all. Ad lists need
     * Premium (the free tier is ad-funded), enforced here as well as on the
     * switch. Per-profile refusals are kept for the UI.
     */
    fun syncIntoCore(ctx: Context, on: Boolean): Map<String, String> {
        if (!NetCoreBridge.isLoaded) return emptyMap()
        val paid = ctx.getSharedPreferences("rostam_prefs", Context.MODE_PRIVATE).getBoolean("paid", false)
        val want = if (on) enabled(ctx).filter { paid || !isAdsId(it) }.toSet() else emptySet()
        val errors = mutableMapOf<String, String>()
        // With the regional map loaded, a country or region is installed by
        // code (kind "cc", the catalogue's member list for a region): no body.
        val geo = loadGeoIfNeeded(ctx)
        for (p in all(ctx)) if (p.id !in want) runCatching { NetCoreBridge.clearProfile(p.id) }
        for (id in want.sorted()) {
            val p = profile(ctx, id) ?: continue
            if (geo && p.isPlace) {
                val codes = if (p.isRegion) p.countries.orEmpty().joinToString(",") else ""
                val r = runCatching { NetCoreBridge.setProfile(id, "cc", codes) }.getOrDefault("err:jni")
                if (!r.startsWith("err")) continue
                // An unknown region with no member list: the body, if we have it.
            }
            val (text, own) = FirstParty.current.filterProfileBody(bodyText(ctx, id) ?: continue)
            if (own > 0) { FirstParty.blocksDropped.addAndGet(own.toLong()); Log.i("Shield", "profile $id: $own first-party line(s) left out") }
            val r = runCatching { NetCoreBridge.setProfile(id, p.coreKind, text) }.getOrDefault("err:jni")
            if (r.startsWith("err")) errors[id] = r
        }
        p(ctx).edit().putString("profile_errors", JSONObject(errors as Map<*, *>).toString()).apply()
        return errors
    }

    fun lastError(ctx: Context, id: String): String? =
        runCatching { JSONObject(p(ctx).getString("profile_errors", "{}")!!).optString(id).ifEmpty { null } }.getOrNull()
}
