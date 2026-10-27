package com.rostamvpn.android.billing

import android.content.Context
import android.util.Log
import com.android.installreferrer.api.InstallReferrerClient
import com.android.installreferrer.api.InstallReferrerStateListener
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Play Install Referrer, read once per install and handed to the cashier on the
 * first `/cid/register` that has it (docs/ATTRIBUTION-2026-10-25.md).
 *
 * Google writes its click id (`gclid=` / `gbraid=`) into the referrer string of a
 * Play install that came from an ad, which is the one deterministic install
 * signal on Android that needs no advertising id, no permission and no SDK
 * beyond this 50 KB client library (it binds to the Play Store app over AIDL).
 * A direct-APK install, a sideload, a device without the Play Store, or an
 * organic install with no campaign all answer "nothing" and that is final: the
 * question is asked at most a few times and never again once answered.
 *
 * Persistence is a flag in SharedPreferences: `read` (the answer, or "" for
 * none) and `sent` (the cashier acknowledged it). A register that fails keeps
 * the value for the next launch; a register that succeeds marks it sent.
 */
object InstallReferrer {
    private const val TAG = "InstallReferrer"
    private const val PREFS = "install_referrer"
    private const val KEY_VALUE = "value"
    private const val KEY_READ = "read"
    private const val KEY_SENT = "sent"
    private const val KEY_TRIES = "tries"
    private const val MAX_TRIES = 3
    private const val TIMEOUT_MS = 4_000L

    /** The referrer string to send with this register, or null when there is
     *  nothing (never had one, already sent, or the Play Store will not say). */
    suspend fun pending(ctx: Context): String? {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (p.getBoolean(KEY_SENT, false)) return null
        if (p.getBoolean(KEY_READ, false)) return p.getString(KEY_VALUE, "")?.takeIf { it.isNotBlank() }
        val tries = p.getInt(KEY_TRIES, 0)
        if (tries >= MAX_TRIES) return null
        p.edit().putInt(KEY_TRIES, tries + 1).apply()
        val value = try {
            withTimeoutOrNull(TIMEOUT_MS) { read(ctx) }
        } catch (t: Throwable) {
            Log.i(TAG, "referrer unavailable: ${t.javaClass.simpleName}")
            null
        }
        if (value == null) return null          // transient: ask again next launch, up to MAX_TRIES
        p.edit().putBoolean(KEY_READ, true).putString(KEY_VALUE, value).apply()
        return value.takeIf { it.isNotBlank() }
    }

    /** The cashier took it; never send it again. */
    fun markSent(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_SENT, true).apply()
    }

    /** "" = a definitive "no referrer" (unsupported, not from Play, organic without
     *  a campaign); null = could not ask right now. */
    private suspend fun read(ctx: Context): String? = suspendCancellableCoroutine { cont ->
        val client = InstallReferrerClient.newBuilder(ctx).build()
        fun finish(v: String?) {
            try { client.endConnection() } catch (_: Throwable) {}
            if (cont.isActive) cont.resume(v)
        }
        try {
            client.startConnection(object : InstallReferrerStateListener {
                override fun onInstallReferrerSetupFinished(code: Int) {
                    when (code) {
                        InstallReferrerClient.InstallReferrerResponse.OK -> {
                            val r = try { client.installReferrer.installReferrer } catch (_: Throwable) { "" }
                            // The Play Store's own placeholder for "no campaign" is noise, not a referrer.
                            finish(if (r.isNullOrBlank() || r == "utm_source=google-play&utm_medium=organic") "" else r)
                        }
                        InstallReferrerClient.InstallReferrerResponse.FEATURE_NOT_SUPPORTED,
                        InstallReferrerClient.InstallReferrerResponse.PERMISSION_ERROR -> finish("")
                        else -> finish(null)   // SERVICE_UNAVAILABLE, DEVELOPER_ERROR, SERVICE_DISCONNECTED
                    }
                }

                override fun onInstallReferrerServiceDisconnected() {
                    finish(null)
                }
            })
        } catch (t: Throwable) {
            finish(if (t is SecurityException) "" else null)
        }
        cont.invokeOnCancellation { try { client.endConnection() } catch (_: Throwable) {} }
    }
}
