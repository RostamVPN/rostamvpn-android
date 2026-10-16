// Copyright 2026 Internet Mastering & Company, Inc.
// SPDX-License-Identifier: Apache-2.0

package com.rostamvpn.android

import android.content.Context
import android.util.Log
import com.rostamvpn.android.R
import java.io.File
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL

/** The app's configuration, fetched from the server. */
object RemoteConfig {
    private const val TAG = "RemoteConfig"
    private const val CACHE_FILE = "remote-config.json"
    private const val PREFS = "redsocket"
    private const val KEY_ETAG = "config_etag"

    private const val PATH_V2 = "/api/v2/config"
    private const val PATH_V1 = "/api/v1/config"
    private const val KEY_VERSION = "config_version"
    private const val KEY_PUBLISHED = "config_published_at"
    private const val KEY_NO_V2 = "config_no_v2"

    /** The PNG chunk the payload rides in. */
    private const val PAYLOAD_CHUNK = "msDt"

    /**
     * Ceiling on a config response, direct or through a mirror. The sealed config
     * is ~7.5 KB; a PNG wrapping it is a little more. 256 KB is far above anything
     * legitimate and far below anything that hurts.
     */
    private const val MAX_CONFIG_BYTES = 256 * 1024

    /** The config to start with: cached server copy if present, else bundled. */
    fun current(context: Context): String {
        val cached = File(context.filesDir, CACHE_FILE)
        if (cached.exists()) {
            val text = runCatching { cached.readText() }.getOrNull()

            if (!text.isNullOrBlank() && looksUsable(text)) {
                Log.i(TAG, "using cached server config (${text.length} bytes)")
                return text
            }
            Log.w(TAG, "cached config unusable; discarding and falling back to bundled")
            cached.delete()
        }
        return bundled(context)
    }

    /** What config this device is actually running: publish version and date. */
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

    /** Fetch and cache. BLOCKS -- call from an IO dispatcher. */
    fun refresh(context: Context, socksPort: Int? = null): Boolean {
        for (base in ConfigManager.apiConfig.bases) {
            when (refreshFromEitherPath(context, base, socksPort)) {
                Outcome.Updated -> return true

                Outcome.Current, Outcome.Absent -> return false
                Outcome.Unreachable -> continue
            }
        }

        val api = ConfigManager.apiConfig
        for (mirror in api.mirrors) {
            val origin = api.bases.firstOrNull() ?: break
            when (refreshViaMirror(context, mirror, origin, socksPort)) {
                Outcome.Updated -> return true
                Outcome.Current, Outcome.Absent -> return false
                Outcome.Unreachable -> continue
            }
        }
        return false
    }

    /** Fetch the config through a public image proxy. */
    private fun refreshViaMirror(
        context: Context,
        template: String,
        originBase: String,
        socksPort: Int?,
    ): Outcome {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return try {
            val target = "$originBase/api/v1/config.png?brand=" +
                java.net.URLEncoder.encode(BuildConfig.CASHIER_BRAND, "UTF-8")
            val url = URL(template.replace("{url}", java.net.URLEncoder.encode(target, "UTF-8")))
            val conn = (if (socksPort != null)
                url.openConnection(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", socksPort)))
            else url.openConnection()) as HttpURLConnection
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000

            conn.setRequestProperty("Connection", "close")

            conn.setRequestProperty("Accept", "image/png,image/*;q=0.8,*/*;q=0.5")

            if (conn.responseCode != 200) {
                Log.w(TAG, "mirror returned ${conn.responseCode}")
                return Outcome.Unreachable
            }

            val bytes = conn.inputStream.use { it.readBoundedBytes(MAX_CONFIG_BYTES) }
                ?: run {
                    Log.w(TAG, "mirror body over ${MAX_CONFIG_BYTES} bytes; ignoring")
                    return Outcome.Unreachable
                }
            val sealed = pngPayload(bytes) ?: run {
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

    /** The sealed config carried inside a PNG, or null if this is just an image. */
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
     * host.
     */
    private enum class Outcome { Updated, Current, Unreachable, Absent }

    /** The etag is PER PATH and must stay that way. */
    private fun etagKey(path: String) =
        if (path == PATH_V2) KEY_ETAG + "_v2" else KEY_ETAG

    /** Ask v2, fall back to v1. */
    private fun refreshFromEitherPath(context: Context, base: String, socksPort: Int?): Outcome {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(KEY_NO_V2, false)) {
            when (val r = refreshFrom(context, base, socksPort, PATH_V2)) {
                Outcome.Absent -> {
                    Log.i(TAG, "no v2 config endpoint here; using v1")
                    prefs.edit().putBoolean(KEY_NO_V2, true).apply()
                }

                else -> return r
            }
        }
        return refreshFrom(context, base, socksPort, PATH_V1)
    }

    private fun refreshFrom(context: Context, base: String, socksPort: Int?, path: String): Outcome {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return try {
            val url = URL("$base$path")
            val conn = (if (socksPort != null)
                url.openConnection(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", socksPort)))
            else url.openConnection()) as HttpURLConnection
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000

            conn.setRequestProperty("Connection", "close")
            conn.setRequestProperty("X-Api-Key", ConfigManager.apiConfig.key)

            conn.setRequestProperty("X-App-Key", BuildConfig.CASHIER_BRAND)

            prefs.getString(etagKey(path), "")?.takeIf { it.isNotEmpty() }
                ?.let { conn.setRequestProperty("If-None-Match", it) }

            when (val code = conn.responseCode) {
                304 -> {
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
                    sealed = conn.inputStream.use { it.readBoundedBytes(MAX_CONFIG_BYTES) }
                        ?.toString(Charsets.UTF_8)
                        ?: run {
                            Log.w(TAG, "config body over $MAX_CONFIG_BYTES bytes; ignoring")
                            return Outcome.Unreachable
                        },
                    version = conn.getHeaderField("X-Config-Version"),
                    published = conn.getHeaderField("X-Config-Published"),
                    etag = conn.getHeaderField("ETag"),
                    path = path,
                )
                404 -> {
                    if (path == PATH_V1) Log.i(TAG, "no config published on the server")
                    Outcome.Absent
                }
                else -> {
                    Log.w(TAG, "config fetch returned $code")
                    Outcome.Unreachable
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "config fetch from $base failed: ${t.javaClass.simpleName}: ${t.message}")
            Outcome.Unreachable
        }
    }

    /** Verify a sealed config and store it. The ONLY path that writes one. */
    private fun storeSealed(
        context: Context,
        prefs: android.content.SharedPreferences,
        sealed: String,
        version: String?,
        published: String?,
        etag: String?,
        path: String = PATH_V1,
    ): Outcome {
        val opened = RedSocketBridge.unsealConfig(sealed.trim(), signingCertSha256(context))
        if (opened.startsWith("err:")) {
            Log.e(TAG, "config did not verify ($opened); keeping what we have")
            return Outcome.Current
        }
        if (!looksUsable(opened)) {
            Log.e(TAG, "config failed validation; keeping what we have")
            return Outcome.Current
        }
        File(context.filesDir, CACHE_FILE).writeText(opened)
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

    /** SHA-256 of this app's signing certificate, hex. */
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

    /** Apply a scanned QR bootstrap. */
    fun applyQrBootstrap(context: Context, uri: String): String? {
        val b64 = uri.trim().removePrefix("rostam://c/").trim()
        if (b64.isEmpty() || b64 == uri.trim()) return "not a config code"

        val opened = RedSocketBridge.unsealConfig(b64, signingCertSha256(context))
        if (opened.startsWith("err:")) {
            Log.e(TAG, "QR bootstrap failed verification ($opened)")

            return "this code is not signed by " + context.getString(R.string.app_name)
        }
        return try {
            val scanned = org.json.JSONObject(opened)
            val entries = scanned.optJSONObject("transport")
                ?.optJSONObject("redsocket")?.optJSONArray("entries")
            if (entries == null || entries.length() == 0) return "code carries no servers"

            val base = org.json.JSONObject(current(context))
            val redsocket = base.optJSONObject("transport")?.optJSONObject("redsocket")
                ?: return "current config has no transport block"

            val merged = org.json.JSONArray()
            for (i in 0 until entries.length()) merged.put(entries.get(i))
            redsocket.optJSONArray("entries")?.let { old ->
                for (i in 0 until old.length()) merged.put(old.get(i))
            }
            redsocket.put("entries", merged)

            if (!looksUsable(base.toString())) return "merged config failed validation"
            File(context.filesDir, CACHE_FILE).writeText(base.toString())

            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().remove(KEY_ETAG).apply()
            Log.i(TAG, "QR bootstrap applied (+${entries.length()} endpoints)")
            null
        } catch (t: Throwable) {
            Log.e(TAG, "QR bootstrap merge failed: $t")
            "could not read that code"
        }
    }

    /**
     * Enough of a check that a client cannot be talked into a config it cannot
     * use.
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
