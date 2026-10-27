package com.rostamvpn.android.billing

import android.content.Context
import android.provider.Settings
import android.util.Log
import app.netcore.NetCoreBridge
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.random.Random

/**
 * The cashier account identity — the cid.
 *
 * **The app owns the cid** (locked contract decision): a 16-hex random id
 * generated once at first launch, persisted, protocol-agnostic, and sent on every
 * cashier call. Entitlement (free/paid, ads, purchases) keys off it.
 *
 * the core no longer mints its own — [ensure] **injects** the app cid into
 * netcore via `setClientId`, so all transport traffic uses the same id.
 * We persist in the shared "redsocket"/"redsocket_cid" pref so any cid an existing
 * install already has carries over (no orphaned paid users on upgrade).
 *
 * **Derived from SSAID on new installs** so it survives uninstall/reinstall and
 * a user keeps their subscription. `Settings.Secure.ANDROID_ID` is stable for
 * this signing key on this device, persists across reinstall, and needs no
 * permission or Play Services — which matters because the direct-flavour APK is
 * sideloaded where Play Services often is not present, and Android Auto Backup
 * therefore cannot be relied on.
 *
 * The derivation happens HERE, not on the server. The cid is
 * `HMAC-SHA256(ssaid, salt)` truncated to 16 hex, so the raw ANDROID_ID never
 * leaves the device and never reaches our infrastructure. The salt ships in the
 * APK and is not secret, so this is not privacy against someone holding both the
 * APK and a device's SSAID — but it does mean a dump of our data cannot be
 * turned into a list of handsets, which is the exposure that actually matters
 * for users here.
 *
 * Existing installs keep the random cid they already have. Re-deriving would
 * orphan every paid user on upgrade.
 *
 * TRADE-OFF, deliberate: a device can no longer obtain a fresh identity by
 * reinstalling. That is the point for billing, and a real cost for a user who
 * believes they are compromised. [resetIdentity] exists for that case.
 *
 * ponytail: plain SharedPreferences (survives updates + backup). The contract
 * says "secure storage" — but EncryptedSharedPreferences' Keystore-key
 * invalidation can wipe the value and orphan a paid cid, which is worse for the
 * stated goal (persistence). Upgrade to hardware-backed storage only if
 * on-device extraction becomes the threat. See HANDOFF item #9.
 */
object CashierIdentity {
    private const val PREFS = "redsocket"
    private const val KEY = "redsocket_cid"

    private const val TAG = "CashierIdentity"
    private const val KEY_DERIVED = "cid_from_ssaid"

    /** Not a secret. It shipped in the APK; it only stops the raw SSAID being
     *  recoverable from a cid, and stops the two being trivially interchangeable. */
    private const val SALT = "rostam-cid-v1"

    /** SSAIDs known to be shipped identically across many handsets. Treating one
     *  as an identity would merge thousands of unrelated users into one account
     *  and hand each of them the others' subscription. */
    private val BAD_SSAIDS = setOf(
        "9774d56d682e549c", "0000000000000000", "ffffffffffffffff",
    )

    /** Load or derive the app-owned cid. */
    fun cid(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val existing = prefs.getString(KEY, "").orEmpty()
        // An existing cid always wins. Re-deriving on upgrade would orphan every
        // paid user who already has a random one.
        if (existing.length == 16) return existing

        val fromDevice = deriveFromSsaid(context)
        val id = fromDevice ?: buildString {
            repeat(8) { append("%02x".format(Random.nextInt(256))) }
        }
        val derived = fromDevice != null
        prefs.edit().putString(KEY, id).putBoolean(KEY_DERIVED, derived).apply()
        Log.i(TAG, if (derived) "cid derived from device (survives reinstall)"
                   else "cid generated randomly; no usable device anchor")
        return id
    }

    /**
     * HMAC-SHA256(ANDROID_ID, salt) as 16 hex, or null when the device has no
     * identifier we are willing to trust.
     */
    private fun deriveFromSsaid(context: Context): String? {
        val ssaid = try {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
                ?.trim()?.lowercase().orEmpty()
        } catch (t: Throwable) {
            Log.w(TAG, "ANDROID_ID unreadable: ${t.javaClass.simpleName}")
            return null
        }
        // Refused rather than trusted: a duplicate here is not a near-miss, it
        // silently merges accounts.
        if (ssaid.length != 16 || !ssaid.all { it.isDigit() || it in 'a'..'f' } ||
            ssaid in BAD_SSAIDS
        ) {
            Log.w(TAG, "ANDROID_ID unusable as an anchor; falling back to random")
            return null
        }
        return try {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(SALT.toByteArray(), "HmacSHA256"))
            // 🚨 THE APPLICATION ID IS IN THE MESSAGE, and it has to be.
            //
            // ANDROID_ID is scoped per SIGNING KEY, not per app. DollarVPN ships
            // on RostamVPN's key (it has none of its own), so both apps read the
            // same SSAID and derived the same cid — and since entitlement keys
            // off the cid, DollarVPN inherited rostam's offering: 2048 MB and a
            // locked region picker instead of its own 1 GB. Verified on device:
            // the shared cid returned quota=2048/pro_only while the correctly
            // branded one returned 1024/until_quota. A rostam subscriber who
            // installed DollarVPN would also have got it free.
            //
            // Existing installs are UNAFFECTED: cid() returns any stored 16-hex
            // value before ever reaching here, so only a fresh install derives.
            // A reinstalling rostam user does get a new cid and loses SSAID
            // continuity — Play's queryPurchases restore is the path that
            // re-grants them, and it already runs at launch.
            mac.doFinal("$ssaid|${com.rostamvpn.android.BuildConfig.APPLICATION_ID}".toByteArray())
                .take(8)
                .joinToString("") { "%02x".format(it) }
        } catch (t: Throwable) {
            Log.w(TAG, "HMAC failed: ${t.javaClass.simpleName}")
            null
        }
    }

    /** True when this cid follows the device rather than the install. */
    fun isDeviceAnchored(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_DERIVED, false)

    /**
     * Abandon this identity and take a fresh random one.
     *
     * A device-anchored cid cannot be escaped by reinstalling, which is correct
     * for billing and wrong for a user who believes they are compromised. This
     * is the way out. It forfeits any entitlement attached to the old cid, so it
     * belongs behind a clear warning in the UI.
     */
    fun resetIdentity(context: Context): String {
        val fresh = buildString { repeat(8) { append("%02x".format(Random.nextInt(256))) } }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, fresh).putBoolean(KEY_DERIVED, false).apply()
        Log.w(TAG, "identity reset; previous entitlement is abandoned")
        return fresh
    }

    /** Get the cid AND inject it into netcore. Call once at startup. */
    fun ensure(context: Context): String {
        val id = cid(context)
        if (NetCoreBridge.isLoaded) {
            try { NetCoreBridge.setClientId(id) } catch (_: Throwable) { /* symbol may predate rebuild */ }
        }
        return id
    }
}
