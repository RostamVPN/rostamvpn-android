package com.rostamvpn.android

import app.netcore.NetCoreBridge
import android.content.Context
import android.util.Log
import com.rostamvpn.android.R
import java.io.File
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL

/**
 * The app's configuration, fetched from the server.
 *
 * Everything the client's behaviour depends on -- endpoints, the ad split,
 * region policy -- lives in this JSON. Until now it was baked into the
 * APK, so changing any of it meant a store release and weeks of waiting for
 * users to update, and DNS-TXT (the only refresh path) is blocked. That left no
 * way to change a shipped client at all.
 *
 * Three sources, in strict order:
 *
 *   1. the copy fetched from the server, cached on disk
 *   2. the bundled asset
 *
 * The bundled asset is the bootstrap, not the policy. It only needs to be good
 * enough to reach the network once; after that the cached server copy wins, and
 * it keeps winning while offline. That ordering matters on a censored network:
 * a client that could only start with a successful config fetch would be unable
 * to start on exactly the networks it exists to defeat.
 *
 * Never blocks startup. The app comes up on whatever it already has and the
 * refresh happens behind it.
 */
object RemoteConfig {
    private const val TAG = "RemoteConfig"
    private const val CACHE_FILE = "remote-config.json"
    private const val PREFS = "redsocket"
    private const val KEY_ETAG = "config_etag"

    // 🚨 THE FORMAT LIVES AT THE PATH, NOT IN THE PAYLOAD.
    //
    // v1 serves the CERT-SEALED config and must serve it forever: every client
    // shipped before the two-key unseal derives its key with the APK signing
    // certificate mixed in and has nothing else to try. Publishing a cert-less
    // config on v1 would leave those installs unable to open anything, and
    // because an unopenable config is treated as `Current` they would freeze on
    // their last-read config silently, with no error and no way back until the
    // app updated.
    //
    // v2 serves the CERT-LESS config. Only a client that knows to ask for it
    // can receive it, so the split -- not fleet adoption -- is what makes the
    // format change safe. An old client never learns v2 exists.
    internal const val PATH_V2 = "/api/v2/config"

    // v3 serves a PER-CID SUBSET of the same v2 file, sealed identically.
    //
    // WHY. Every phone fetched the same ~230 addresses and the planner
    // (select.rs) probes its 8 best-ranked candidates and dials the fastest, so
    // every phone on a carrier converged on the same two or three fronts while
    // twenty idled -- and when the censor found those few, the whole region
    // failed at once. v3 hands this cid its own deterministic 8, and a
    // genuinely disjoint 8 when the device reports that the whole plan failed
    // (`retry=k`). Load spreads by ASSIGNMENT; latency only picks within the
    // subset.
    //
    // The body is the same format, so NOTHING else changes: same unseal, same
    // `carrier_priorities`/`wifi_priorities` application (now a no-op outside
    // the subset), same premium entries, same regions.
    //
    // A STATIC ANSWER IS NORMAL, NOT A FAILURE. A cid the server has not seen
    // yet (its registry refreshes every ~10 min), the first minute after a
    // configd restart, or any server-side trouble returns the v2 bytes --
    // the full list and no `assign` block. That is planned on exactly like
    // today's config: no retry bump, no "v3 unavailable" pin.
    internal const val PATH_V3 = "/api/v3/config"
    private const val KEY_VERSION = "config_version"
    private const val KEY_PUBLISHED = "config_published_at"
    private const val KEY_NO_V2 = "config_no_v2"

    /** The persisted subset-retry counter `k`. See [onPlanFailed]. */
    private const val KEY_RETRY = "config_retry"

    /**
     * The cid pref, read WITHOUT minting one.
     *
     * `CashierIdentity.cid()` creates an identity as a side effect; a config
     * fetch must never be the thing that does that, so this reads the same key
     * and treats "not there yet" as "ask v2".
     */
    private const val KEY_CID = "redsocket_cid"

    /** `select::LAST_OK_KEY` — the core writes it into the same prefs file. */
    private const val KEY_LAST_OK = "redsocket_last_ok"

    /**
     * The core's verdict when the WHOLE plan failed: `SELECT_BUDGET` (60 s)
     * exhausted with no handshake, i.e. `select()` returned `None`
     * (`netcore/src/lib.rs`). This exact string and nothing else:
     * "endpoint selection timed out" (the 90 s verdict timeout), a bind
     * failure or a runtime failure are not evidence that the subset is blocked.
     */
    const val PLAN_FAILED = "no http endpoint reachable"

    /**
     * Ceiling on `k`. configd clamps to the same number, and the clamp is also
     * what bounds how much of the pool one device can ever learn:
     * `(MAX_RETRY + 1) x 8 = 40` addresses of ~200.
     */
    const val MAX_RETRY = 4

    /**
     * The PNG chunk the payload rides in.
     *
     * Valid as a PRIVATE ANCILLARY chunk per the PNG spec, which is what makes
     * the file a real image to everything that is not us: byte 1 lowercase means
     * ancillary (skip if unknown), byte 2 lowercase means private (not a
     * registered type), byte 3 MUST be uppercase (reserved), byte 4 lowercase
     * means safe to copy. Deliberately unremarkable — a chunk named for what it
     * carries would be a string worth grepping for on a decompiled binary.
     */
    private const val PAYLOAD_CHUNK = "msDt"

    /**
     * Ceiling on a mirror response. The sealed config is ~7.5 KB; a PNG wrapping
     * it is a little more. 256 KB is far above anything legitimate and far below
     * anything that hurts. An untrusted host must not get to choose how much
     * memory we spend, and `readBytes()` on a proxy response is exactly that.
     */
    private const val MIRROR_MAX_BYTES = 256 * 1024

    /** The config to start with: cached server copy if present, else bundled. */
    fun current(context: Context): String {
        val cached = File(context.filesDir, CACHE_FILE)
        if (cached.exists()) {
            val text = runCatching { cached.readText() }.getOrNull()
            // A cached file that will not parse is worse than no cache: it would
            // be preferred over the bundled asset on every launch. Drop it.
            if (!text.isNullOrBlank() && looksUsable(text)) {
                Log.i(TAG, "using cached server config (${text.length} bytes)")
                return text
            }
            Log.w(TAG, "cached config unusable; discarding and falling back to bundled")
            cached.delete()
        }
        return bundled(context)
    }

    /**
     * What config this device is actually running: publish version and date.
     *
     * Empty version means the bundled asset is in use -- no server config has
     * ever been fetched. That distinction is the useful one when someone reports
     * a problem: "which config" is nearly always the first question, and until
     * now the footer answered it with the JSON's schema version, which is the
     * same string across every publish.
     */
    data class Stamp(val version: String, val publishedAtMillis: Long)

    fun stamp(context: Context): Stamp {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return Stamp(
            version = p.getString(KEY_VERSION, "") ?: "",
            publishedAtMillis = p.getLong(KEY_PUBLISHED, 0L) * 1000L,
        )
    }

    fun bundled(context: Context): String =
        context.assets.open("default-config.json").bufferedReader().use { it.readText() }

    // ========================================================================
    // v3: the per-cid subset
    // ========================================================================

    /** The stored cid, or "" when this install has not minted one yet. */
    internal fun storedCid(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_CID, "").orEmpty()
            .takeIf { it.length == 16 }.orEmpty()

    /** The persisted subset-retry counter, clamped to what the server accepts. */
    fun retry(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_RETRY, 0).coerceIn(0, MAX_RETRY)

    /** `k` after one whole-plan failure. Saturates rather than wrapping. */
    internal fun nextRetry(k: Int): Int = (k.coerceAtLeast(0) + 1).coerceAtMost(MAX_RETRY)

    /**
     * Does this `start()` verdict mean the WHOLE plan failed?
     *
     * Exactly one string qualifies. "endpoint selection timed out" is the 90 s
     * verdict timeout and may fire while a handshake is still in flight; a bind
     * failure or a runtime failure never reached the network at all; "connection
     * check failed" is one endpoint inside a working plan. None of those is
     * evidence that this device's subset is blocked, and treating them as such
     * would walk a healthy device off its own assignment.
     */
    internal fun isPlanFailure(coreResult: String): Boolean =
        coreResult.removePrefix("err:").trim() == PLAN_FAILED

    /**
     * The tunnel passed its post-connect check, so this subset works.
     *
     * The ONLY reset. Deliberately not a handshake: a POP can complete Noise
     * and still carry nothing, which is exactly the failure `retry` exists to
     * route around, so clearing `k` on a handshake would throw away the signal
     * the moment it mattered. This is the same evidence `markActiveCarrier(true)`
     * uses to clear a bad mark in the core.
     */
    fun resetRetry(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getInt(KEY_RETRY, 0) == 0) return
        prefs.edit().putInt(KEY_RETRY, 0).apply()
        Log.i(TAG, "[CONFIG] subset verified; retry reset to 0")
    }

    /**
     * The whole plan failed. Ask for a DIFFERENT subset and say whether one
     * arrived.
     *
     * Called only when `select()` returned None ([PLAN_FAILED]) -- never for a
     * single endpoint failing inside a plan, which `BAD_KEY` and the
     * next-endpoint loop already handle. Bumping on those would walk a healthy
     * device off its own assignment on one bad POP.
     *
     * `If-None-Match` is deliberately not sent: the point of the request is to
     * get bytes the device does not have.
     *
     * @return true only when a NEW config was stored, i.e. there is something
     *         different to plan on. "Unchanged" and "no v3 here" are false, so
     *         the caller fails the connect exactly as it does today instead of
     *         re-planning the same list.
     */
    @Synchronized
    fun onPlanFailed(context: Context, socksPort: Int? = null): Boolean {
        val cid = storedCid(context)
        if (cid.isEmpty()) return false
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val was = retry(context)
        val k = nextRetry(was)
        prefs.edit().putInt(KEY_RETRY, k).apply()
        if (k == was) {
            // Capped. The server would deal the same subset, and spending
            // another 60 s select budget to discover that helps nobody.
            Log.w(TAG, "[CONFIG] whole plan failed at retry=$k (capped); not re-planning")
            return false
        }
        Log.w(TAG, "[CONFIG] whole plan failed; asking for subset retry=$k")
        val query = v3Query(context, cid, k)
        for (base in ConfigManager.apiConfig.bases) {
            when (refreshFrom(context, base, socksPort, PATH_V3, query, bypassEtag = true)) {
                Outcome.Updated -> return true
                // Reached and answered with what we already have, or refused.
                Outcome.Current -> return false
                // No v3 route on this base, or it did not answer: another base
                // may still have one.
                Outcome.Absent, Outcome.Unreachable -> continue
            }
        }
        return false
    }

    /**
     * `cid`, `carrier`, `cc`, `last`, `retry` for this device, right now.
     *
     * Every key is always present, empty when unknown: configd filters empties
     * itself, and a stable query shape is easier to read in a capture than one
     * whose parameters come and go.
     */
    private fun v3Query(context: Context, cid: String, retry: Int): String {
        val tm = context.getSystemService(Context.TELEPHONY_SERVICE)
            as? android.telephony.TelephonyManager
        val carrier = configCarrier(onWifi(context), tm?.networkOperator)
        val cc = configCountry(tm?.simCountryIso, tm?.networkCountryIso,
            java.util.TimeZone.getDefault().id)
        val last = lastOkHost(
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_LAST_OK, "").orEmpty()
        )
        return v3Query(cid, carrier, cc, last, retry)
    }

    internal fun v3Query(cid: String, carrier: String, cc: String, last: String, retry: Int): String {
        fun e(s: String) = java.net.URLEncoder.encode(s, "UTF-8")
        return "cid=${e(cid)}&carrier=${e(carrier)}&cc=${e(cc)}&last=${e(last)}" +
            "&retry=${retry.coerceIn(0, MAX_RETRY)}"
    }

    /**
     * Wi-Fi or Ethernet rather than mobile data.
     *
     * Shared with `withPreferredHosts`, which has always made exactly this
     * distinction for `carrier_priorities`: on Wi-Fi the SIM says nothing about
     * the ISP carrying the bytes, so naming the MNO would ask the server to
     * reason about a network the device is not on.
     */
    internal fun onWifi(context: Context): Boolean = runCatching {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE)
            as android.net.ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        caps?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) == true ||
            caps?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET) == true
    }.getOrDefault(false)

    /** MCC+MNC, and only on mobile data. */
    internal fun configCarrier(onWifi: Boolean, networkOperator: String?): String =
        if (onWifi) "" else networkOperator.orEmpty().trim()

    /**
     * ISO-2 country: the SIM first, then the network, then the timezone.
     *
     * The timezone arm is the one that matters in the field: a phone with no
     * SIM on a Wi-Fi network in Iran is exactly the device that needs a working
     * subset, and `Asia/Tehran` is the only thing it can say about itself.
     * The map is the config's own `freedom_regions`, so adding a region is a
     * publish rather than a release.
     */
    internal fun configCountry(
        simCountry: String?,
        networkCountry: String?,
        timezoneId: String,
        freedomRegions: Map<String, List<String>> = mapOf("IR" to listOf("Asia/Tehran", "Iran")),
    ): String =
        (simCountry?.takeIf { it.isNotEmpty() } ?: networkCountry.orEmpty())
            .uppercase()
            .ifEmpty { freedomRegions.entries.firstOrNull { timezoneId in it.value }?.key.orEmpty() }

    /**
     * `LAST_OK_KEY` holds a `host:port` label; the server wants the host.
     *
     * Stripped HERE rather than left to configd, because configd's own parse
     * refuses to split a label whose left half still contains a colon -- which
     * is every IPv6 address, and v6 is two of the eight entries in an Iranian
     * subset. Sending the bare host is the form both sides read the same way.
     */
    internal fun lastOkHost(label: String): String =
        label.trim().takeIf { it.isNotEmpty() }?.substringBeforeLast(':').orEmpty()

    /**
     * The one line that joins a device report to the server's `cfg_assign` row.
     *
     * Null when the body carries no `assign` block, which is the normal static
     * answer and says nothing is wrong.
     */
    internal fun assignLine(json: String): String? = runCatching {
        val a = org.json.JSONObject(json).optJSONObject("assign") ?: return null
        "[nc] assign bucket=${a.optInt("bucket", -1)} epoch=${assignEpoch(json, a)} " +
            "retry=${a.optInt("retry", 0)} reach=${a.optString("reach")} n=${a.optInt("n", 0)}"
    }.getOrNull()

    /**
     * ⚠️ `epoch` IS A u64 AND ROUTINELY EXCEEDS `Long.MAX_VALUE`.
     *
     * It is a hash of the enabled host set and the group flags, so roughly half
     * of all pool versions have the top bit set. `optLong` SATURATES there --
     * caught on a device, which logged a real
     * `epoch=16581088215364782296` as `9223372036854775807` -- and that number
     * is the only thing joining this line to the server's `cfg_assign` row, so
     * saturating it quietly makes the whole telemetry item useless.
     *
     * `org.json` cannot hold it either: past `Long` it falls back to `Double`
     * and `toString()` then prints `1.6581088215364782E19`, losing the low
     * digits. So the digits are read out of the TEXT, inside the `assign`
     * object so nothing else named `epoch` can be picked up, and the parsed
     * value is only the fallback.
     */
    private val EPOCH_RE = Regex("\"assign\"\\s*:\\s*\\{[^}]*\"epoch\"\\s*:\\s*(\\d+)")

    internal fun assignEpoch(json: String, assign: org.json.JSONObject): String =
        EPOCH_RE.find(json)?.groupValues?.get(1)
            ?: assign.opt("epoch")?.toString().orEmpty()

    /**
     * Fetch and cache. BLOCKS -- call from an IO dispatcher.
     *
     * @return true when a new config was stored.
     */
    @Synchronized
    fun refresh(context: Context, socksPort: Int? = null): Boolean =
        when (refreshOnce(context, null)) {
            Outcome.Updated -> true
            Outcome.Current, Outcome.Absent -> false
            // Every base and mirror was unreachable directly. If the tunnel is
            // up, ask again THROUGH it: the app is excluded from its own VPN,
            // so the direct path is the censored one, and the tunnel is the one
            // path we know works right now.
            Outcome.Unreachable -> if (socksPort != null) {
                Log.i(TAG, "config unreachable directly; retrying through the tunnel")
                refreshOnce(context, socksPort) == Outcome.Updated
            } else false
        }

    private fun refreshOnce(context: Context, socksPort: Int?): Outcome {
        clearV1Pin(context)
        val cid = storedCid(context)
        val query = if (cid.isEmpty()) "" else v3Query(context, cid, retry(context))
        val walked = walkBases(ConfigManager.apiConfig.bases, cid.isNotEmpty()) { base, path ->
            refreshFrom(context, base, socksPort, path, if (path == PATH_V3) query else "")
        }
        if (walked != Outcome.Unreachable) return walked
        // Every host we own is unreachable. That is the censored case, and it is
        // the only case a mirror is for: it is slower, staler and depends on a
        // third party, so it must never run when a direct path would have
        // worked. Reaching here means the alternative is the bundled config.
        val api = ConfigManager.apiConfig
        for (mirror in api.mirrors) {
            val origin = api.bases.firstOrNull() ?: break
            when (val r = refreshViaMirror(context, mirror, origin, socksPort)) {
                Outcome.Updated, Outcome.Current, Outcome.Absent -> return r
                Outcome.Unreachable -> continue
            }
        }
        return Outcome.Unreachable
    }

    /**
     * Fetch the config through a public image proxy.
     *
     * WHY THIS EXISTS. Every base in `api` resolves to one machine, so one IP
     * block takes the config channel away entirely — and the config is how a
     * client learns where everything else is. Here the client's connection is to
     * the PROXY. Our origin is fetched by the proxy's own infrastructure, from
     * its network, so our address is never in the client's traffic.
     *
     * WHY IT IS A PNG. The proxies that are worth using are IMAGE proxies, and
     * they reject anything else: verified against slack-imgs.com, which answers
     * 415 for a text content type and passes `image/png` through
     * BYTE-IDENTICALLY as long as no resize parameter is present.
     *
     * ⚠️ Do not write a wildcard media type in a comment here. Kotlin NESTS
     * block comments, so a slash-star inside KDoc opens a nested comment that
     * never closes and silently swallows the rest of the file. The compiler
     * reports it as "unresolved reference" on symbols hundreds of lines away and
     * "unclosed comment" at EOF, which points nowhere near the cause. So the origin serves the sealed config
     * wrapped in a real PNG and this reads it back out of the container.
     *
     * ⚠️ A resize parameter DESTROYS the payload. `o1=wi32.he32.si` on that proxy
     * decodes and re-encodes to 32x32; a 272x92 test image came back 32x32 and
     * nothing survives that. A mirror template must request the image as-is.
     *
     * WHAT WE GIVE UP, deliberately:
     *  - No `X-Config-Version` / `X-Config-Published` / `ETag`, because the
     *    proxy serves its own headers. The version is read from the body
     *    instead, and the publish time is left alone rather than guessed.
     *  - The proxy caches. slack-imgs.com returned `max-age=1800`, so a publish
     *    can take half an hour to arrive on this path. Acceptable for a last
     *    resort; not acceptable as a primary, which is why it is ordered last.
     *  - We are a guest on somebody else's CDN and they can stop at any time
     *    with no notice. Failing here costs nothing: the cached or bundled
     *    config stands.
     *
     * Trust is not among the things we give up. The payload is unsealed by the
     * same native call and rejected the same way, so a hostile mirror can
     * withhold a config but cannot forge one.
     */
    private fun refreshViaMirror(
        context: Context,
        template: String,
        originBase: String,
        socksPort: Int?,
    ): Outcome {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return try {
            // The origin the PROXY will fetch. `.png`, not `/config`, because the
            // proxy refuses a non-image and because this endpoint needs no
            // headers: the proxy sends none of ours.
            val target = "$originBase/api/v1/config.png?brand=" +
                java.net.URLEncoder.encode(BuildConfig.CASHIER_BRAND, "UTF-8")
            val url = URL(template.replace("{url}", java.net.URLEncoder.encode(target, "UTF-8")))
            val conn = (if (socksPort != null)
                url.openConnection(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", socksPort)))
            else url.openConnection()) as HttpURLConnection
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000
            // Keep this socket out of the platform connection pool -- see the
            // long note in CashierApi.open. A SOCKS connect to a dead tunnel
            // leaves a socket with a null InetAddress, and okhttp's pool
            // cleanup thread dereferences it and takes the process down. 144K
            // reports, no frame of ours in the stack, uncatchable at the call
            // site; not pooling it is the only lever the caller has.
            conn.setRequestProperty("Connection", "close")
            // Look like something asking a CDN for an image, because that is what
            // this is. No X-Api-Key and no X-App-Key: the proxy would not forward
            // them, and sending them here only marks the request.
            conn.setRequestProperty("Accept", "image/png,image/*;q=0.8,*/*;q=0.5")

            if (conn.responseCode != 200) {
                Log.w(TAG, "mirror returned ${conn.responseCode}")
                return Outcome.Unreachable
            }
            // Bounded read. This is an untrusted host and an unbounded one would
            // let it decide how much memory we spend.
            val bytes = conn.inputStream.use { it.readBoundedBytes(MIRROR_MAX_BYTES) }
                ?: run {
                    Log.w(TAG, "mirror body over ${MIRROR_MAX_BYTES} bytes; ignoring")
                    return Outcome.Unreachable
                }
            val sealed = pngPayload(bytes) ?: run {
                // A proxy that resized, an error image, or a placeholder. All of
                // them are "this host did not give us the config".
                Log.w(TAG, "mirror body carried no payload (${bytes.size} bytes)")
                return Outcome.Unreachable
            }
            storeSealed(context, prefs, sealed, version = null, published = null, etag = null)
        } catch (t: Throwable) {
            Log.w(TAG, "mirror fetch failed: ${t.javaClass.simpleName}: ${t.message}")
            Outcome.Unreachable
        }
    }

    /** Read at most [max] bytes, or null if the stream has more than that. */
    private fun java.io.InputStream.readBoundedBytes(max: Int): ByteArray? {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (true) {
            val n = read(buf)
            if (n < 0) return out.toByteArray()
            if (out.size() + n > max) return null
            out.write(buf, 0, n)
        }
    }

    /**
     * The sealed config carried inside a PNG, or null if this is just an image.
     *
     * Walks the PNG chunk structure rather than decoding the image. Decoding
     * would mean `BitmapFactory`, which premultiplies alpha and may apply a
     * colour-space transform — both of which silently corrupt bytes that were
     * never meant to be colours. The container is trivial to walk and exact:
     *
     *     89 50 4E 47 0D 0A 1A 0A          signature
     *     [u32 len][4-char type][data][u32 crc]  ...repeating
     *
     * The payload rides in a private ancillary chunk, so every PNG decoder on
     * earth ignores it and the file stays a valid image to anything that checks.
     */
    internal fun pngPayload(bytes: ByteArray): String? {
        val sig = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        if (bytes.size < sig.size + 12) return null
        for (i in sig.indices) if (bytes[i] != sig[i]) return null
        var p = sig.size
        while (p + 8 <= bytes.size) {
            val len = ((bytes[p].toInt() and 0xFF) shl 24) or
                ((bytes[p + 1].toInt() and 0xFF) shl 16) or
                ((bytes[p + 2].toInt() and 0xFF) shl 8) or
                (bytes[p + 3].toInt() and 0xFF)
            // A length that does not fit is a truncated or hostile file, not a
            // chunk to skip: walking on would read whatever follows as a header.
            //
            // ⚠️ Written as a SUBTRACTION, not `p + 12 + len > bytes.size`. That
            // form overflows Int on a declared length near 0x7FFFFFFF, wraps
            // negative, compares as "fits", and the read below then runs off the
            // array. A unit test with a chunk claiming 0x7FFFFFFF bytes caught
            // it. These bytes come from a host we do not control, so that is a
            // reachable path, not a theoretical one. Both terms here stay small
            // and positive, so nothing can wrap.
            if (len < 0 || len > bytes.size - p - 12) return null
            val type = String(bytes, p + 4, 4, Charsets.US_ASCII)
            if (type == PAYLOAD_CHUNK) {
                return String(bytes, p + 8, len, Charsets.US_ASCII).trim()
            }
            p += 12 + len
        }
        return null
    }

    /**
     * Why the distinction matters: only `Unreachable` justifies trying the next
     * host. A server that answered and said "unchanged", or that answered with
     * something we refused, has been reached -- moving on would be asking a
     * different host the same question we already have an answer to.
     */
    internal enum class Outcome { Updated, Current, Unreachable, Absent }

    /**
     * The etag is PER PATH and must stay that way.
     *
     * v1, v2 and v3 are independent counters over different bytes, so an etag
     * offered to the wrong path can collide and earn a 304 for a body we do not
     * have. That is the same collision §16 already fixed between brands, in a
     * place where the consequence is worse: the client would believe it was
     * current on a config it never received.
     *
     * v3 makes it sharper still: configd's etag is over THIS DEVICE's subset,
     * so a shared key would hand the v2 etag to a per-cid body and the other
     * way round, every refresh.
     */
    internal fun etagKey(path: String) = when (path) {
        PATH_V3 -> KEY_ETAG + "_v3"
        PATH_V2 -> KEY_ETAG + "_v2"
        else -> KEY_ETAG
    }

    /**
     * Ask v2 only.
     *
     * v1 used to be the fallback for servers that predate v2, and a 404 on v2
     * set KEY_NO_V2 so the install asked v1 from then on. But v1's seal can no
     * longer be opened by current builds, so a pinned install refused every
     * config it fetched and ran on the bundled one for the rest of its life --
     * one 404 from one host, ever, and the user never saw another address list.
     * The cashier serves v2 on every base now, so: v2 everywhere, the old pin is
     * cleared, and a 404 means "this host has no v2" -- try the next base.
     */
    private fun clearV1Pin(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.contains(KEY_NO_V2)) {
            Log.i(TAG, "clearing the v1 pin; v2 only from now on")
            prefs.edit().remove(KEY_NO_V2).apply()
        }
    }

    /**
     * The base-list walk, with v3's one new rule.
     *
     * 404 on v3 means "this host has no v3 route", not "this host is no use":
     * the Caddy route is per box and a base can lag the rest, so the SAME base
     * is asked for v2 before the walk moves on. Anything else -- a 5xx, a
     * captive portal, a timeout -- is the host failing, and that still means
     * the next base, exactly as before.
     *
     * Without a cid there is nothing for v3 to assign, so the walk is byte for
     * byte the v2-only walk it has always been.
     *
     * It stops at the first base that ANSWERS, not the first that hands back a
     * new config: "unchanged" is a successful answer and the common case, and
     * treating it as a failure would walk every fallback on every launch.
     *
     * Pure on purpose: [fetch] is the only thing that touches the network, so
     * the ORDER OF REQUESTS is unit-testable without a device.
     */
    internal fun walkBases(
        bases: List<String>,
        hasCid: Boolean,
        fetch: (base: String, path: String) -> Outcome,
    ): Outcome {
        for (base in bases) {
            val v3 = if (hasCid) fetch(base, PATH_V3) else Outcome.Absent
            val r = if (v3 == Outcome.Absent) fetch(base, PATH_V2) else v3
            when (r) {
                Outcome.Updated, Outcome.Current -> return r
                // A base with neither path, or one that did not answer.
                Outcome.Absent, Outcome.Unreachable -> continue
            }
        }
        return Outcome.Unreachable
    }

    private fun refreshFrom(
        context: Context,
        base: String,
        socksPort: Int?,
        path: String,
        query: String = "",
        bypassEtag: Boolean = false,
    ): Outcome {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return try {
            val url = URL("$base$path" + if (query.isEmpty()) "" else "?$query")
            val conn = (if (socksPort != null)
                url.openConnection(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", socksPort)))
            else url.openConnection()) as HttpURLConnection
            // 4 s to connect, then the next base: on a censored network a
            // blocked base blackholes the SYN, and five bases at 10 s each was
            // close to a minute before reaching one that works. A base that
            // connects still gets the full 10 s to answer.
            conn.connectTimeout = 4_000
            conn.readTimeout = 10_000
            // Keep this socket out of the platform connection pool -- see the
            // long note in CashierApi.open. A SOCKS connect to a dead tunnel
            // leaves a socket with a null InetAddress, and okhttp's pool
            // cleanup thread dereferences it and takes the process down. 144K
            // reports, no frame of ours in the stack, uncatchable at the call
            // site; not pooling it is the only lever the caller has.
            conn.setRequestProperty("Connection", "close")
            conn.setRequestProperty("X-Api-Key", ConfigManager.apiConfig.key)
            // WHICH APP is asking. Five apps are built from one tree and ship to
            // different stores, regions and pay rails, so each gets its own
            // config. The key authorises; this only selects. A brand with
            // nothing published falls back to the shared config, so it is safe
            // to send unconditionally.
            conn.setRequestProperty("X-App-Key", BuildConfig.CASHIER_BRAND)
            // Costs nothing when unchanged, and this is fetched on networks where
            // bytes are expensive and a large response may not survive.
            // `bypassEtag` is the re-plan fetch: the device has just told the
            // server its whole subset failed, so a 304 ("you already have the
            // current config") is the one answer that is useless here. It also
            // steps around the 300 s the static fallback is cacheable for.
            if (!bypassEtag) {
                prefs.getString(etagKey(path), "")?.takeIf { it.isNotEmpty() }
                    ?.let { conn.setRequestProperty("If-None-Match", it) }
            }

            when (val code = conn.responseCode) {
                304 -> {
                    // Still worth recording: the body is unchanged but this is
                    // proof of what the server currently considers current, which
                    // is what the version/date in the UI is claiming.
                    prefs.edit().apply {
                        conn.getHeaderField("X-Config-Version")?.let { putString(KEY_VERSION, it) }
                        conn.getHeaderField("X-Config-Published")?.toLongOrNull()
                            ?.takeIf { it > 0 }?.let { putLong(KEY_PUBLISHED, it) }
                        apply()
                    }
                    Log.i(TAG, "config unchanged")
                    Outcome.Current
                }
                200 -> storeSealed(
                    context, prefs,
                    sealed = conn.inputStream.bufferedReader().readText(),
                    version = conn.getHeaderField("X-Config-Version"),
                    published = conn.getHeaderField("X-Config-Published"),
                    etag = conn.getHeaderField("ETag"),
                    path = path,
                )
                404 -> {
                    // Nothing published at this path on this host. The caller
                    // decides what that means ([walkBases] asks the same base
                    // for v2 after a v3 404, and moves on after a v2 404);
                    // v1 is never asked any more.
                    Log.w(TAG, "no config endpoint at $path on $base")
                    Outcome.Absent
                }
                else -> {
                    // 5xx, a captive portal, an interception page: this host is
                    // not usefully serving us. Try the next one.
                    Log.w(TAG, "config fetch returned $code")
                    Outcome.Unreachable
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "config fetch from $base failed: ${t.javaClass.simpleName}: ${t.message}")
            Outcome.Unreachable
        }
    }

    /**
     * Verify a sealed config and store it. The ONLY path that writes one.
     *
     * Shared by the direct fetch and the mirror on purpose. The mirror is the
     * path a censored user depends on, so it is the one place a weaker check
     * would do the most damage, and two copies of this logic is exactly how that
     * happens. One copy means the mirror cannot be more permissive than the
     * direct path even by accident.
     *
     * A sealed config is base64, not JSON. Unwrapping happens in the native
     * library so the key never enters managed code.
     *
     * Sealed is the ONLY accepted form over the network. Accepting plaintext as
     * well would make the signature decorative: anyone who can answer the
     * request -- a hostile CDN front, a MITM on a censored network, a mirror
     * having a bad day -- would simply reply with unsigned JSON and skip
     * verification entirely. The bundled asset is the only unsealed config, and
     * it is trusted because it arrives inside the signed APK.
     *
     * @param version   from a header, or null when the carrier has none (a
     *                  proxy serves its own headers). Null falls back to the
     *                  version inside the verified body.
     * @param published server publish time. Null leaves the stored value alone
     *                  rather than guessing: a fetch time would claim a config
     *                  published months ago was "updated just now".
     */
    private fun storeSealed(
        context: Context,
        prefs: android.content.SharedPreferences,
        sealed: String,
        version: String?,
        published: String?,
        etag: String?,
        path: String = PATH_V2,
    ): Outcome {
        val opened = NetCoreBridge.unsealConfig(sealed.trim(), signingCertSha256(context))
        if (opened.startsWith("err:")) {
            // Refused, not ignored. A config that fails to verify is either
            // corrupt or hostile, and this file decides which servers users
            // connect to. The host was reached and served something we will not
            // accept; another host would serve the same bytes.
            Log.e(TAG, "config did not verify ($opened); keeping what we have")
            return Outcome.Current
        }
        if (!looksUsable(opened)) {
            // Refusing a bad config is the difference between a bad publish being
            // an inconvenience and being a fleet-wide outage that survives the
            // fix, because clients cache it.
            Log.e(TAG, "config failed validation; keeping what we have")
            return Outcome.Current
        }
        File(context.filesDir, CACHE_FILE).writeText(opened)
        // Protect Mode's first-party set takes the new hosts now, not at next launch.
        FirstParty.onStoredConfig(opened)
        val bodyVersion = version ?: runCatching {
            org.json.JSONObject(opened).optString("version", "").takeIf { it.isNotEmpty() }
        }.getOrNull()
        prefs.edit().apply {
            etag?.let { putString(etagKey(path), it) }
            bodyVersion?.let { putString(KEY_VERSION, it) }
            published?.toLongOrNull()?.takeIf { it > 0 }?.let { putLong(KEY_PUBLISHED, it) }
            apply()
        }
        Log.i(TAG, "config updated (v$bodyVersion, ${opened.length} bytes)")
        return Outcome.Updated
    }

    /**
     * SHA-256 of this app's signing certificate, hex.
     *
     * Mixed into the decryption key, so a REPACKAGED build derives a different
     * key and cannot open a config sealed for ours. Cached after the first read:
     * it cannot change while the process lives.
     */
    private var certHash: String? = null

    internal fun signingCertSha256(context: Context): String {
        certHash?.let { return it }
        val h = try {
            val pm = context.packageManager
            val sigs = if (android.os.Build.VERSION.SDK_INT >= 28) {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(context.packageName, android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES)
                    .signingInfo?.apkContentsSigners
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(context.packageName, android.content.pm.PackageManager.GET_SIGNATURES).signatures
            }
            val first = sigs?.firstOrNull()
            if (first == null) "" else
                java.security.MessageDigest.getInstance("SHA-256")
                    .digest(first.toByteArray())
                    .joinToString("") { "%02x".format(it) }
        } catch (t: Throwable) {
            Log.w(TAG, "could not read the signing certificate: ${t.javaClass.simpleName}")
            ""
        }
        certHash = h
        return h
    }

    /**
     * Apply a scanned QR bootstrap.
     *
     * A QR code holds ~2.9 KB and the sealed config is ~17 KB, so a scan can only
     * ever carry ENDPOINTS -- enough to get a blocked client back to a POP, from
     * where it fetches the real config. It is therefore MERGED into whatever
     * config we already have, never substituted for it: replacing a full config
     * with this stub would silently drop ads, health and region policy.
     *
     * The payload is verified before anything is written. A QR is an untrusted
     * delivery path by definition -- a poster, a forwarded screenshot, a channel
     * anyone can post to -- so an unsigned or tampered one is discarded here, and
     * that signature check is the only reason scanning is safe at all.
     *
     * @return null on success, else a short reason for the UI.
     */
    fun applyQrBootstrap(context: Context, uri: String): String? {
        val b64 = uri.trim().removePrefix("rostam://c/").trim()
        if (b64.isEmpty() || b64 == uri.trim()) return "not a config code"

        val opened = NetCoreBridge.unsealConfig(b64, signingCertSha256(context))
        if (opened.startsWith("err:")) {
            Log.e(TAG, "QR bootstrap failed verification ($opened)")
            // Shown to the user when a scanned config fails its signature
            // check, so it named the wrong app in four of five brands.
            return "this code is not signed by " + context.getString(R.string.app_name)
        }
        return try {
            val scanned = org.json.JSONObject(opened)
            val entries = scanned.optJSONObject("transport")
                ?.optJSONObject("redsocket")?.optJSONArray("entries")
            if (entries == null || entries.length() == 0) return "code carries no servers"

            val base = org.json.JSONObject(current(context))
            val netcore = base.optJSONObject("transport")?.optJSONObject("redsocket")
                ?: return "current config has no transport block"
            // Scanned endpoints go FIRST: the user scanned this because what they
            // already had was unreachable, so trying the old list first would make
            // them wait out its timeouts before reaching the ones that work.
            val merged = org.json.JSONArray()
            for (i in 0 until entries.length()) merged.put(entries.get(i))
            netcore.optJSONArray("entries")?.let { old ->
                for (i in 0 until old.length()) merged.put(old.get(i))
            }
            netcore.put("entries", merged)
            // A code may also carry fresh API addresses (for example emailed to
            // users whose config fetch is blocked): scanned ones go first, the
            // existing ones stay behind them, duplicates dropped.
            scanned.optJSONObject("api")?.let { sApi ->
                val api = base.optJSONObject("api") ?: org.json.JSONObject().also { base.put("api", it) }
                val order = mutableListOf<String>()
                sApi.optString("base", "").takeIf { it.isNotBlank() }?.let { order += it }
                sApi.optJSONArray("fallback_bases")?.let { a -> for (i in 0 until a.length()) order += a.optString(i) }
                api.optString("base", "").takeIf { it.isNotBlank() }?.let { order += it }
                api.optJSONArray("fallback_bases")?.let { a -> for (i in 0 until a.length()) order += a.optString(i) }
                val bases = order.map { it.trim().trimEnd('/') }.filter { it.startsWith("https://") }.distinct()
                if (bases.isNotEmpty()) {
                    api.put("base", bases.first())
                    api.put("fallback_bases", org.json.JSONArray(bases.drop(1)))
                }
            }

            if (!looksUsable(base.toString())) return "merged config failed validation"
            File(context.filesDir, CACHE_FILE).writeText(base.toString())
            // Drop the ETag: the cached body no longer matches what the server
            // last sent, and keeping it would make the next refresh take a 304
            // and never correct this.
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().remove(KEY_ETAG).remove(etagKey(PATH_V2)).remove(etagKey(PATH_V3)).apply()
            Log.i(TAG, "QR bootstrap applied (+${entries.length()} endpoints)")
            null
        } catch (t: Throwable) {
            Log.e(TAG, "QR bootstrap merge failed: $t")
            "could not read that code"
        }
    }

    /**
     * Enough of a check that a client cannot be talked into a config it cannot
     * use. Deliberately shallow -- the app is the only thing that interprets the
     * rest, and validating fields here would reject anything added server-side
     * before the app knew about it.
     */
    private fun looksUsable(json: String): Boolean = try {
        val o = org.json.JSONObject(json)
        val entries = o.optJSONObject("transport")
            ?.optJSONObject("redsocket")?.optJSONArray("entries")?.length() ?: 0
        o.has("version") && entries > 0
    } catch (_: Throwable) {
        false
    }
}
