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
 * Asks for a Play Store rating, in-app, at a moment the user is plausibly happy.
 *
 * ## Zero friction: the card never leaves the app
 *
 * This uses the Play In-App Review API, which renders Play's own rating card as
 * an overlay on top of our Activity. No browser, no Play Store app, no return
 * trip. That is the whole reason to prefer it over a `market://` deep link, and
 * it is why there are no strings in this file: every pixel of that card is drawn
 * and localised by Play, in the user's own language, including all 38 we ship.
 *
 * ## What the API will not tell you, and what follows from that
 *
 * Play quotas the flow per user and gives NO signal about whether a card was
 * displayed, or whether a review was written. `launchReviewFlow` completes
 * identically for "user left five stars" and "Play silently showed nothing".
 * So this class never branches on the outcome, never retries because "it did not
 * work", and never blocks any UI on it. Every call site is fire-and-forget, and
 * a launched flow is booked as *spent* whether or not anything appeared.
 *
 * ## Policy compliance (Play Ratings and Reviews policy)
 *
 * No feature is gated on rating; nothing is offered in exchange; and the user is
 * never pre-screened with "do you like the app?" before being shown the card.
 * That last one is a removal-grade violation and the reason there is no custom
 * dialog anywhere in front of this.
 *
 * ## The gates, and why each one exists
 *
 * A prompt spent on a user who has used the app once cannot be spent on a user
 * who loves it, so every gate below is about *not* spending:
 *
 * - **Sustained success only.** A "successful tunnel" is one that stayed up for
 *   [minSustainedMs], counted at disconnect. A connect that dropped in 5s is
 *   not a success, and connecting is not the moment to ask anyway -- a user
 *   whose tunnel just came up is still waiting to use it.
 * - **Never after trouble.** Any connect failure, watchdog reconnect or paywall
 *   in this process poisons the launch permanently. Asking for five stars right
 *   after "Connection failed", or right after asking for money, reliably earns
 *   one star.
 * - **Not the first session, not the first day.** [minGoodSessions] sustained
 *   sessions AND [minDaysSinceFirstUse] days since first use.
 * - **Spaced out.** [askCooldownDays] between asks, enforced by us. We never
 *   rely on Play's quota to be the brake, because we cannot read it.
 *
 * ## Iran is NOT excluded, deliberately
 *
 * Freedom-region users get the product free and are the likeliest to leave a
 * good review. Nothing here consults entitlement, region, `premiumAvailable` or
 * `isPaid`. The one entitlement-adjacent signal is [onPaywallShown], and that is
 * about *mood*, not tier -- and it can barely fire in Iran anyway, because
 * `openPaywallAndLoad()` hard-returns for a freedom region.
 *
 * ## Sideloaded builds
 *
 * The `direct` flavor is not installed from Play, so Play cannot accept a review
 * from it at all and [ReviewManagerFactory] correctly reports the flow as
 * unavailable. There is deliberately no `market://` fallback: it would leave the
 * app (the one thing this feature exists to avoid), and it would land the user
 * on a listing whose star widget is disabled for them. That needs no build-time
 * flag -- the library is its own gate, and [onDisconnected] just backs off.
 *
 * State lives in the app's own prefs and no telemetry leaves the device.
 *
 * Kept in lockstep with iOS `rostamvpn/ReviewPrompt.swift`; the thresholds below
 * are the same numbers. Divergences are marked DIVERGES.
 */
object ReviewManager {
    private const val TAG = "ReviewManager"

    // ── Thresholds (mirror ReviewPrompt.swift) ───────────────────────────────

    // ── Thresholds: SERVER-OWNED, not constants ──────────────────────────────
    //
    // These were compile-time constants, which meant tuning the rating funnel
    // cost a release on every brand — on a value that wants experimenting with.
    // They now come from `review` in the OTA config (§7b: config is the single
    // source of policy), read at the point of use so a config fetched mid-run
    // takes effect on the next disconnect rather than the next launch.
    //
    // ⚠️ Kept in lockstep with iOS `ReviewPrompt`, values included.
    //
    // The defaults in ConfigManager.ReviewConfig.DEFAULT were LOWERED on
    // 2026-09-30: 90s sustained -> 30s, and 120 days between asks -> 45. Both
    // were our own brake sitting well inside the stores' own quotas, which are
    // invisible to us and cost nothing when they bite -- a quota'd request is a
    // silent no-op. So at 120 we were the binding limit and spent at most three
    // chances a year on whichever sessions happened to qualify first. The
    // parse floors (30 days, 10s) are unchanged and still mean a config cannot
    // remove the spacing altogether.

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
     * (sideloaded build, no Play Services, no network at that instant). iOS has
     * no equivalent because `requestReview` has no failure channel to observe.
     * A full 120-day burn for a transient outage would throw away a real review;
     * a zero backoff would re-ask Play on every single disconnect forever, which
     * is what a sideloaded install would do.
     */
    private const val UNAVAILABLE_BACKOFF_DAYS = 7L

    private const val DAY_MS = 86_400_000L

    // ── Persisted state ──────────────────────────────────────────────────────
    // The app's existing prefs file, so this adds no new storage surface.

    private const val PREFS = "rostam_prefs"
    private const val KEY_FIRST_USE = "review_first_use_at"
    private const val KEY_GOOD_SESSIONS = "review_good_sessions"
    private const val KEY_NEXT_ASK_AT = "review_next_ask_at"
    private const val KEY_ASK_COUNT = "review_ask_count"

    // ── Per-process session state ────────────────────────────────────────────

    /**
     * Monotonic clock reading from when the tunnel reached Connected; 0 when not
     * connected. `elapsedRealtime`, not wall time: a session must not be judged
     * by a clock the user or the network can move.
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
     * asking for it at the instant we want the card produces a visible stall;
     * it is warmed at connect instead, minutes ahead of the disconnect that may
     * spend it. Single-use -- cleared as soon as it is launched.
     */
    @Volatile private var warmedInfo: ReviewInfo? = null

    /**
     * When [warmedInfo] was obtained. 🚨 Google: "The ReviewInfo object is only
     * valid for a limited amount of time." It was cached from connect to
     * disconnect, which on this product is hours or days -- and an expired
     * token draws NOTHING while `markAsked` has already spent 45 days, leaving
     * no trace in any counter. Discarded after [WARM_TTL_MS]; the cold path
     * then asks Play again, which costs one round trip at a moment the user is
     * looking at a disconnected screen and waiting on nothing.
     */
    @Volatile private var warmedAtMs: Long = 0L
    private const val WARM_TTL_MS = 5 * 60 * 1000L

    private fun prefs(): SharedPreferences? =
        MainActivity.instance?.applicationContext
            ?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ── Recording: called from the VPN state machine ─────────────────────────

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
     * A connect attempt failed, or the watchdog gave up on a live tunnel and
     * tore it down. Poisons this launch: no rating ask until the app restarts.
     */
    fun onConnectionFailed() {
        connectedAtMs = 0L
        sessionPoisoned = true
    }

    /** The paywall was presented. Poisons this launch -- see the class docs. */
    fun onPaywallShown() {
        sessionPoisoned = true
    }

    // ── Asking ───────────────────────────────────────────────────────────────

    /**
     * Evaluate every gate and, if they all pass, show Play's rating card.
     *
     * Safe and cheap to call on every disconnect -- almost every call is a
     * no-op. Consumes the current connection, so a second call without an
     * intervening [onConnected] does nothing.
     *
     * The caller is responsible for only invoking this when the user is actually
     * settling on a disconnected app in the foreground (see
     * `VpnViewModel.maybeAskForReview`).
     */
    fun onDisconnected(activity: Activity) {
        // OTA kill switch. Checked here rather than inside the gate chain so a
        // disabled fleet still COUNTS sessions — turning it back on then finds
        // users already qualified instead of restarting everyone's clock.
        // ⚠️ `block("disabled")` is NOT called here, deliberately.
        // `consumeQualifyingSession()` counts its own miss, so doing both
        // incremented TWO buckets for one disconnect and the counters stopped
        // summing to the number of disconnects. The session still gets counted,
        // so turning the switch back on finds users already qualified.
        if (!ConfigManager.reviewConfig.enabled) { consumeQualifyingSession(); return }
        // A sideloaded copy cannot be reviewed through Play, so spend nothing on
        // it: no request, no cooldown, and a counter that says exactly why.
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
            // AFTER the launch, not before: `launchFlow` can bail on a
            // finishing Activity, and spending 45 days on a card that was
            // never handed to Play is the worst of both.
            if (launchFlow(activity, cached)) markAsked(askCooldownDays, counted = true)
            return
        }

        // Not warmed: either the gates only opened during this very session, or
        // the warm request failed. Ask now and pay the round trip -- by the time
        // it lands the user is looking at a disconnected screen, not waiting on
        // us for anything.
        try {
            ReviewManagerFactory.create(activity).requestReviewFlow()
                .addOnCompleteListener { task ->
                    if (task.isSuccessful) {
                        if (launchFlow(activity, task.result)) {
                            markAsked(askCooldownDays, counted = true)
                        }
                    } else {
                        // The DIRECT channel lands here every time: a
                        // sideloaded build is not a Play install, so the flow
                        // can never be served. Counted separately, because
                        // "Play said no" and "our own gate said no" want
                        // completely different responses.
                        block("flow_unavailable")
                        Log.i(TAG, "flow unavailable, backing off ${UNAVAILABLE_BACKOFF_DAYS}d: ${task.exception}")
                        markAsked(UNAVAILABLE_BACKOFF_DAYS, counted = false)
                    }
                }
        } catch (t: Throwable) {
            // Play Services missing or broken. Never crash a disconnect over a
            // rating prompt.
            Log.w(TAG, "requestReviewFlow: $t")
            markAsked(UNAVAILABLE_BACKOFF_DAYS, counted = false)
        }
    }

    /**
     * The user tapped Rate. Opens the Play listing; does NOT call the in-app
     * review API.
     *
     * 🚨 THIS DELIBERATELY DOES NOT SHOW PLAY'S CARD, and that is a reversal.
     * Google's in-app review guide says so in as many words:
     *
     *   "you should not have a call-to-action option (such as a button) to
     *    trigger the API, as a user might have already hit their quota and the
     *    flow won't be shown, presenting a broken experience to the user. For
     *    this use case, redirect the user to the Play Store instead."
     *
     * The failure it names is the one this code could not have caught. Inside
     * the quota window `requestReviewFlow` SUCCEEDS, so a listing fallback
     * behind it never runs, `launchReviewFlow` draws nothing, and the tap is
     * dead with no error to branch on -- the guide is equally explicit that an
     * in-app review error must not be surfaced or acted on. So the only shape
     * a button can take is this one.
     *
     * The automatic path is unchanged and still uses the card: it picks its own
     * moment, which is exactly what the API is for.
     *
     * ⚠️ No `markAsked` here either. The cooldown is OUR brake on asks WE
     * initiate, and nothing was spent: this never asked Play for a card.
     * Writing it let a user suppress the automatic ask for 45 days by tapping a
     * menu row, repeatedly, with nothing recording that it happened.
     *
     * ⚠️ For a `direct` install in Iran this opens a listing that is itself
     * unreachable. There is nothing better available -- Play cannot accept a
     * review from a sideloaded build at all -- but it does mean the row buys
     * that audience nothing, and they are most of the install base.
     */
    fun askNow(activity: Activity) {
        openListing(activity)
    }

    /**
     * One Play card at a product moment that happens once, keyed by [onceKey]
     * (the eSIM tab's first successful attach, C9). Only the internetpro eSIM
     * tab calls it; the VPN funnel above is untouched.
     *
     * Same brakes as the automatic path: the OTA kill switch, Play installs
     * only, and the shared cooldown both ways (it never asks inside the VPN
     * ask's 45 days, and a card shown here starts them). The key is written
     * before asking, so a quota'd or failed request is never retried: once
     * means once.
     */
    fun askOnce(activity: Activity, onceKey: String) {
        val p = prefs() ?: return
        if (p.getBoolean(onceKey, false)) return
        if (!ConfigManager.reviewConfig.enabled || !fromPlay(activity)) return
        if (System.currentTimeMillis() < p.getLong(KEY_NEXT_ASK_AT, 0L)) return
        p.edit().putBoolean(onceKey, true).apply()
        try {
            ReviewManagerFactory.create(activity).requestReviewFlow().addOnCompleteListener { task ->
                if (task.isSuccessful && launchFlow(activity, task.result)) markAsked(askCooldownDays, counted = true)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "askOnce: $t")
        }
    }

    /**
     * Was this copy installed from Play? Cached; the answer cannot change.
     *
     * 🚨 WITHOUT THIS, A SIDELOADED BUILD BURNS THE 45-DAY COOLDOWN ON A CARD
     * THAT CAN NEVER APPEAR. Play's own docs say in-app review needs the app to
     * be "listed in the user's Google Play library", and for internal app
     * sharing they are explicit that the flow still runs with the submit button
     * DISABLED. So `requestReviewFlow` succeeding proves nothing: on a sideload
     * it may well succeed, `markAsked(counted = true)` then runs, `ask_count`
     * increments, and the device reports a healthy funnel while being incapable
     * of producing a rating.
     *
     * ⚠️ I previously wrote in this file that the direct channel "lands here
     * every time" on the failure branch. That was never verified and the docs do
     * not support it. This replaces a guess with a fact the system will answer.
     *
     * It matters most where it is least visible: vpn.red/dist,
     * dollarvpn.app/dist and rostam.app/dist are a primary channel, not a
     * courtesy link.
     */
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
            // Unknown is treated as Play: the old behaviour, so a packaging
            // quirk cannot silently switch the feature off for everyone.
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

    /** True if the flow was actually handed to Play. */
    private fun launchFlow(activity: Activity, info: ReviewInfo): Boolean {
        if (activity.isFinishing || activity.isDestroyed) return false
        return try {
            ReviewManagerFactory.create(activity).launchReviewFlow(activity, info)
                .addOnCompleteListener {
                    // Deliberately outcome-blind: Play reports success here even
                    // when it decided to show nothing. There is nothing to
                    // branch on and nothing to retry.
                    Log.i(TAG, "review flow finished")
                }
            true
        } catch (t: Throwable) {
            Log.w(TAG, "launchReviewFlow: $t")
            false
        }
    }

    // ── Gates ────────────────────────────────────────────────────────────────

    /**
     * True when this disconnect is a genuinely good moment to ask. Always clears
     * [connectedAtMs], so one connection can qualify at most once.
     */
    /**
     * Called once a second while connected. Banks the session the moment it has
     * genuinely held, instead of waiting for a disconnect that may never come.
     *
     * 🚨 `minGoodSessions = 3` DID NOT MEAN THREE GOOD SESSIONS. The counter was
     * incremented in `consumeQualifyingSession` alone, which only runs on a
     * FOREGROUND, USER-INITIATED disconnect. Everything else banked nothing:
     * a session ended from the OS VPN panel (`onRevoke` tells the ViewModel
     * nothing), one ended by swiping the app away or by process death, one
     * ended by the watchdog, and -- the common case for a VPN -- one that was
     * simply still up when the user put the phone down.
     *
     * So a user who connects every day and leaves it on accrued NOTHING, for
     * ever, no matter how well the product worked for them. That is independent
     * of every threshold and was the single largest reason the card never
     * appeared.
     *
     * Banking here does not make the ask any more eager: the ask still happens
     * only at the conservative moment it always did. It just stops throwing
     * away the evidence that the user is having a good time.
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

        // 1. The connection has to have actually held.
        if (SystemClock.elapsedRealtime() - start < minSustainedMs) return block("too_short")

        // Banked by `onSessionTick` while the tunnel was up, so this path no
        // longer increments: doing both would double-count a session that was
        // long enough to tick AND ended on a foreground disconnect.
        val p = prefs() ?: return block("no_prefs")
        val sessions = p.getInt(KEY_GOOD_SESSIONS, 0)

        // 2. No errors, no paywall this launch.
        if (sessionPoisoned) return block("poisoned")

        return persistentGatesPass(sessions)
    }

    /**
     * Why an ask did not happen, counted on the device and never sent anywhere.
     *
     * 🚨 THE FUNNEL IS OTHERWISE COMPLETELY INVISIBLE, and that is the reason
     * this exists. Neither store tells us whether the card appeared or what was
     * left, so the ONLY thing measurable is our own gate chain -- and without
     * it, tuning a threshold is guesswork. The thresholds were loosened on
     * 2026-09-30 on exactly that guesswork; this is what would have informed it.
     *
     * Counters live in the app's own prefs and no telemetry leaves the device,
     * which is the same rule the rest of this file follows. Read them with:
     *
     *     adb shell run-as <pkg> cat shared_prefs/rostam_prefs.xml | grep review_block_
     *
     * or from the log line below, which names the reason on every miss.
     */
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
     * The gates that survive a restart: enough good sessions, enough days, and
     * the cooldown elapsed. Split out so the warm path can ask the same question
     * at connect time using the session count this session *will* produce.
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
            // A device whose wall clock jumped forward and back (or was never
            // set) would otherwise be locked out for good. If the deadline is
            // further away than a cooldown could legitimately put it, the clock
            // moved, not time.
            if (nextAsk - now <= askCooldownDays * DAY_MS) return miss("cooldown")
            Log.i(TAG, "next-ask deadline is implausibly far out — treating clock as moved")
        }
        return true
    }

    /**
     * Pre-fetch the Play token while the tunnel is up, but only for a session
     * that could actually end in an ask -- otherwise ~500K devices would make a
     * pointless Play RPC on every connect.
     *
     * This runs outside the tunnel: TunnelVpnService excludes our own package
     * from the tun, so Play's traffic takes the underlying network like every
     * other app's Play traffic, and nothing about it is distinguishable.
     */
    private fun warmIfEligible() {
        if (warmedInfo != null || sessionPoisoned) return
        val p = prefs() ?: return
        // +1: this session has not been counted yet and will be, at disconnect.
        // quiet: the SAME question with the SAME value is asked again at
        // disconnect, so counting here booked every gate miss twice and the
        // buckets stopped summing to the number of disconnects.
        if (!persistentGatesPass(p.getInt(KEY_GOOD_SESSIONS, 0) + 1, quiet = true)) return
        val ctx = MainActivity.instance?.applicationContext ?: return
        // onConnected() is called from the connect coroutine, which runs on
        // Dispatchers.IO. Play's review client is built around the main looper,
        // so hop before touching it rather than relying on it to tolerate a
        // background caller on every one of ~500K devices.
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

    /**
     * Start the cooldown.
     *
     * [counted] separates "we handed a flow to Play" from "Play said it had
     * nothing for us". Both close the window, but only the first is an ask.
     * KEY_ASK_COUNT is diagnostics -- the number someone reads off a device to
     * decide whether this feature is doing anything -- so counting an
     * unavailable flow would make a sideloaded install, which can never show a
     * card at all, look like it had shown several.
     */
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
     * reliable one -- but "first time the app got far enough to connect", which
     * is the more meaningful clock for this decision anyway.
     */
    /**
     * Stamp first use once. 🚨 AND HEAL A CLOCK THAT WAS AHEAD WHEN IT HAPPENED.
     *
     * This wrote `System.currentTimeMillis()` once and returned early for ever
     * after. A device whose clock was ahead at first connect stored a FUTURE
     * timestamp, so `now - firstUse` stayed negative and `too_new` blocked every
     * ask until real time caught up -- a year, for a year-ahead clock. These are
     * cheap devices on censored networks that boot without reliable NTP, so this
     * is not hypothetical.
     *
     * Worse, `rostam_prefs.xml` is not excluded from Auto Backup, so the bad
     * stamp rode a reinstall and a device transfer. Uninstalling, the usual
     * remedy, did not clear it.
     *
     * A stamp in the future cannot be a first use, so it is rewritten to now --
     * the same reasoning as the `nextAsk` escape hatch below.
     */
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
