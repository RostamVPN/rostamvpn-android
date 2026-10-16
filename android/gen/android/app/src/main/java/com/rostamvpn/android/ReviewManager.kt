// Copyright 2026 Internet Mastering & Company, Inc.
// SPDX-License-Identifier: Apache-2.0

package com.rostamvpn.android

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.google.android.play.core.review.ReviewInfo
import com.google.android.play.core.review.ReviewManagerFactory

/**
 * Asks for a Play Store rating, in-app, at a moment the user is plausibly
 * happy.
 */
object ReviewManager {
    private const val TAG = "ReviewManager"

    /** How long a connection must hold before it counts as a real success. */
    private val minSustainedMs: Long
        get() = ConfigManager.reviewConfig.minSustainedSecs * 1000L

    /** Sustained sessions required before we are willing to ask at all. */
    private val minGoodSessions: Int
        get() = ConfigManager.reviewConfig.minGoodSessions

    /** Days since first use before we are willing to ask at all. */
    private val minDaysSinceFirstUse: Long
        get() = ConfigManager.reviewConfig.minDaysSinceFirstUse.toLong()

    /** Days between two asks, enforced by us — Play's counter is unreadable. */
    private val askCooldownDays: Long
        get() = ConfigManager.reviewConfig.askCooldownDays.toLong()

    /**
     * DIVERGES from iOS. Backoff when Play tells us the flow is unavailable
     * (sideloaded build, no Play Services, no network at that instant). iOS has no
     * equivalent because `requestReview` has no failure channel to observe.
     */
    private const val UNAVAILABLE_BACKOFF_DAYS = 7L

    private const val DAY_MS = 86_400_000L

    private const val PREFS = "rostam_prefs"
    private const val KEY_FIRST_USE = "review_first_use_at"
    private const val KEY_GOOD_SESSIONS = "review_good_sessions"
    private const val KEY_NEXT_ASK_AT = "review_next_ask_at"
    private const val KEY_ASK_COUNT = "review_ask_count"

    /**
     * Monotonic clock reading from when the tunnel reached Connected; 0 when not
     * connected. `elapsedRealtime`, not wall time: a session must not be judged by
     * a clock the user or the network can move.
     */
    @Volatile private var connectedAtMs: Long = 0L

    /** One bank per connection. Reset by [onConnected]. */
    @Volatile private var sessionBanked = false

    /**
     * Sticky for the life of the process. A user who hit an error or saw the
     * paywall this launch is not asked to rate us this launch, even if a later
     * connection goes perfectly.
     */
    @Volatile private var sessionPoisoned = false

    /**
     * Pre-fetched review token. `requestReviewFlow()` does real network work, so
     * asking for it at the instant we want the card produces a visible stall; it
     * is warmed at connect instead, minutes ahead of the disconnect that may spend
     * it.
     */
    @Volatile private var warmedInfo: ReviewInfo? = null

    @Volatile private var warmedAtMs: Long = 0L
    private const val WARM_TTL_MS = 5 * 60 * 1000L

    private fun prefs(): SharedPreferences? =
        MainActivity.instance?.applicationContext
            ?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * The tunnel reached Connected. Starts the sustained-connection clock and
     * warms the Play token if this session could plausibly end in an ask.
     */
    fun onConnected() {
        connectedAtMs = SystemClock.elapsedRealtime()
        sessionBanked = false
        bootstrapFirstUse()
        warmIfEligible()
    }

    /**
     * A connect attempt failed, or the watchdog gave up on a live tunnel and tore
     * it down. Poisons this launch: no rating ask until the app restarts.
     */
    fun onConnectionFailed() {
        connectedAtMs = 0L
        sessionPoisoned = true
    }

    fun onPaywallShown() {
        sessionPoisoned = true
    }

    /** Evaluate every gate and, if they all pass, show Play's rating card. */
    fun onDisconnected(activity: Activity) {
        if (!ConfigManager.reviewConfig.enabled) { consumeQualifyingSession(); return }

        if (!fromPlay(activity)) { consumeQualifyingSession(); block("not_from_play"); return }
        if (!consumeQualifyingSession()) return
        if (activity.isFinishing || activity.isDestroyed) return

        val fresh = warmedAtMs != 0L &&
            SystemClock.elapsedRealtime() - warmedAtMs < WARM_TTL_MS
        val cached = if (fresh) warmedInfo else null
        if (warmedInfo != null && !fresh) Log.i(TAG, "warmed token expired; re-requesting")
        warmedInfo = null
        warmedAtMs = 0L
        if (cached != null) {
            if (launchFlow(activity, cached)) markAsked(askCooldownDays, counted = true)
            return
        }

        try {
            ReviewManagerFactory.create(activity).requestReviewFlow()
                .addOnCompleteListener { task ->
                    if (task.isSuccessful) {
                        if (launchFlow(activity, task.result)) {
                            markAsked(askCooldownDays, counted = true)
                        }
                    } else {
                        block("flow_unavailable")
                        Log.i(TAG, "flow unavailable, backing off ${UNAVAILABLE_BACKOFF_DAYS}d: ${task.exception}")
                        markAsked(UNAVAILABLE_BACKOFF_DAYS, counted = false)
                    }
                }
        } catch (t: Throwable) {
            Log.w(TAG, "requestReviewFlow: $t")
            markAsked(UNAVAILABLE_BACKOFF_DAYS, counted = false)
        }
    }

    /**
     * The user tapped Rate. Opens the Play listing; does NOT call the in-app
     * review API.
     */
    fun askNow(activity: Activity) {
        openListing(activity)
    }

    private var installedFromPlay: Boolean? = null

    private fun fromPlay(ctx: Context): Boolean {
        installedFromPlay?.let { return it }
        val who = try {
            val pm = ctx.packageManager
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                pm.getInstallSourceInfo(ctx.packageName).installingPackageName
            } else {
                @Suppress("DEPRECATION")
                pm.getInstallerPackageName(ctx.packageName)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "install source unreadable: ${t.message}")
            null
        }
        val ok = who == null || who == "com.android.vending"
        installedFromPlay = ok
        Log.i(TAG, "install source ${who ?: "unknown"} -> in-app review ${if (ok) "possible" else "impossible"}")
        return ok
    }

    /** Play's listing for this build's own applicationId. */
    private fun openListing(activity: Activity) {
        val pkg = activity.packageName
        val market = android.content.Intent(
            android.content.Intent.ACTION_VIEW,
            android.net.Uri.parse("market://details?id=$pkg"),
        ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        val web = android.content.Intent(
            android.content.Intent.ACTION_VIEW,
            android.net.Uri.parse("https://play.google.com/store/apps/details?id=$pkg"),
        ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        for (i in listOf(market, web)) {
            try { activity.startActivity(i); return } catch (_: Throwable) { }
        }
        Log.i(TAG, "no store app and no browser; nothing to open")
    }

    private fun launchFlow(activity: Activity, info: ReviewInfo): Boolean {
        if (activity.isFinishing || activity.isDestroyed) return false
        return try {
            ReviewManagerFactory.create(activity).launchReviewFlow(activity, info)
                .addOnCompleteListener {
                    Log.i(TAG, "review flow finished")
                }
            true
        } catch (t: Throwable) {
            Log.w(TAG, "launchReviewFlow: $t")
            false
        }
    }

    /**
     * True when this disconnect is a genuinely good moment to ask. Always clears
     * [connectedAtMs], so one connection can qualify at most once.
     */
    /**
     * Called once a second while connected. Banks the session the moment it has
     * genuinely held, instead of waiting for a disconnect that may never come.
     */
    fun onSessionTick(connectedForMs: Long) {
        if (connectedForMs < minSustainedMs) return
        if (sessionBanked) return
        sessionBanked = true
        val p = prefs() ?: return
        p.edit().putInt(KEY_GOOD_SESSIONS, p.getInt(KEY_GOOD_SESSIONS, 0) + 1).apply()
    }

    private fun consumeQualifyingSession(): Boolean {
        val start = connectedAtMs
        connectedAtMs = 0L
        if (start == 0L) return block("no_session")

        if (SystemClock.elapsedRealtime() - start < minSustainedMs) return block("too_short")

        val p = prefs() ?: return block("no_prefs")
        val sessions = p.getInt(KEY_GOOD_SESSIONS, 0)

        if (sessionPoisoned) return block("poisoned")

        return persistentGatesPass(sessions)
    }

    /** Why an ask did not happen, counted on the device and never sent anywhere. */
    private fun block(reason: String): Boolean {
        try {
            prefs()?.let { p ->
                val k = "review_block_$reason"
                p.edit().putInt(k, p.getInt(k, 0) + 1).apply()
            }
        } catch (_: Throwable) { }
        Log.i(TAG, "no ask: $reason")
        return false
    }

    /**
     * The gates that survive a restart: enough good sessions, enough days, and the
     * cooldown elapsed. Split out so the warm path can ask the same question at
     * connect time using the session count this session *will* produce.
     */
    private fun persistentGatesPass(sessions: Int, quiet: Boolean = false): Boolean {
        fun miss(reason: String): Boolean =
            if (quiet) false else block(reason)
        if (sessions < minGoodSessions) return miss("too_few_sessions")
        val p = prefs() ?: return miss("no_prefs")
        val now = System.currentTimeMillis()

        val firstUse = p.getLong(KEY_FIRST_USE, 0L)
        if (firstUse <= 0L) return miss("no_first_use")
        if (now - firstUse < minDaysSinceFirstUse * DAY_MS) return miss("too_new")

        val nextAsk = p.getLong(KEY_NEXT_ASK_AT, 0L)
        if (now < nextAsk) {
            if (nextAsk - now <= askCooldownDays * DAY_MS) return miss("cooldown")
            Log.i(TAG, "next-ask deadline is implausibly far out — treating clock as moved")
        }
        return true
    }

    /**
     * Pre-fetch the Play token while the tunnel is up, but only for a session that
     * could actually end in an ask -- otherwise ~500K devices would make a
     * pointless Play RPC on every connect.
     */
    private fun warmIfEligible() {
        if (warmedInfo != null || sessionPoisoned) return
        val p = prefs() ?: return

        if (!persistentGatesPass(p.getInt(KEY_GOOD_SESSIONS, 0) + 1, quiet = true)) return
        val ctx = MainActivity.instance?.applicationContext ?: return

        Handler(Looper.getMainLooper()).post {
            try {
                ReviewManagerFactory.create(ctx).requestReviewFlow()
                    .addOnCompleteListener { task ->
                        if (task.isSuccessful) { warmedInfo = task.result; warmedAtMs = SystemClock.elapsedRealtime() }
                        else Log.i(TAG, "warm failed (will retry at disconnect): ${task.exception}")
                    }
            } catch (t: Throwable) {
                Log.w(TAG, "warm requestReviewFlow: $t")
            }
        }
    }

    /** Start the cooldown. */
    private fun markAsked(cooldownDays: Long, counted: Boolean) {
        val p = prefs() ?: return
        val edit = p.edit()
            .putLong(KEY_NEXT_ASK_AT, System.currentTimeMillis() + cooldownDays * DAY_MS)
        if (counted) edit.putInt(KEY_ASK_COUNT, p.getInt(KEY_ASK_COUNT, 0) + 1)
        edit.apply()
        Log.i(TAG, "review ${if (counted) "asked (count=${p.getInt(KEY_ASK_COUNT, 0)})" else "unavailable"}" +
            ", next in ${cooldownDays}d")
    }

    /**
     * First-use timestamp, written once. Not the install date -- we have no
     * reliable one -- but "first time the app got far enough to connect", which is
     * the more meaningful clock for this decision anyway.
     */
    /** Stamp first use once. */
    private fun bootstrapFirstUse() {
        val p = prefs() ?: return
        val now = System.currentTimeMillis()
        val stored = p.getLong(KEY_FIRST_USE, 0L)
        if (stored <= 0L || stored > now) {
            if (stored > now) Log.i(TAG, "first-use stamp is in the future; clock moved, re-stamping")
            p.edit().putLong(KEY_FIRST_USE, now).apply()
        }
    }
}
