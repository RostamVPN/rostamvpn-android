package com.rostamvpn.android

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The in-app VPN disclosure, shown BEFORE Android's own VPN dialog (Play's
 * VpnService policy and its prominent-disclosure rules, 2026-10-26).
 *
 * Every path in MainActivity that would launch `VpnService.prepare()`'s intent
 * asks here first and continues only on Continue. Not now = no system dialog,
 * the app stays disconnected, and the next connect asks again. Shown whenever
 * the system dialog would be (prepare() != null), which on a fresh install is
 * the first connect.
 */
object VpnDisclosure {
    private val _pending = MutableStateFlow<((Boolean) -> Unit)?>(null)
    val pending: StateFlow<((Boolean) -> Unit)?> = _pending.asStateFlow()

    /** [onAnswer] gets true on Continue, false on Not now / back. */
    fun request(onAnswer: (Boolean) -> Unit) {
        // A second request while one is up replaces the continuation; the
        // first caller is told no, so it can reset its own state.
        _pending.value?.invoke(false)
        _pending.value = onAnswer
    }

    fun answer(ok: Boolean) {
        val cb = _pending.value ?: return
        _pending.value = null
        cb(ok)
    }
}

/**
 * Consent for Protect Mode (BuildConfig.PROTECTION_NAME), a SEPARATE
 * disclosure from the VPN one, as Play requires.
 *
 * Protect Mode's switch defaults on, but nothing it sends (the destinations
 * and the device id) leaves the phone until the user taps Continue on the
 * disclosure: [RedShield.deviceOn] requires [consented], and that one gate
 * covers scoring, automatic denies and profiles. Backing out of the sheet is
 * not consent. "Turn off" records the answer and switches the feature off, so
 * the sheet does not return on every launch; turning the switch on later asks
 * again.
 */
object ProtectConsent {
    private const val KEY_CONSENT = "consent"
    private const val KEY_ANSWERED = "consent_answered"

    /** The switch was turned on without consent: show the disclosure. */
    private val _asked = MutableStateFlow(false)
    val asked: StateFlow<Boolean> = _asked.asStateFlow()

    /** Bumped on every answer so hosts re-read the stored flags. */
    private val _rev = MutableStateFlow(0)
    val rev: StateFlow<Int> = _rev.asStateFlow()

    fun consented(ctx: Context): Boolean = RedShield.prefs(ctx).getBoolean(KEY_CONSENT, false)
    fun answered(ctx: Context): Boolean = RedShield.prefs(ctx).getBoolean(KEY_ANSWERED, false)

    /** First-launch case: the feature is on by default and nobody has answered yet. */
    fun needsAnswer(ctx: Context): Boolean =
        RedShield.available && RedShield.enabledByConfig && RedShield.userOn(ctx) &&
            !consented(ctx) && !answered(ctx)

    fun ask() { _asked.value = true }

    /** Back or dismiss: no answer recorded, nothing sent. */
    fun dismiss() { _asked.value = false; _rev.value++ }

    fun record(ctx: Context, accepted: Boolean) {
        RedShield.prefs(ctx).edit()
            .putBoolean(KEY_CONSENT, accepted)
            .putBoolean(KEY_ANSWERED, true)
            .apply()
        _asked.value = false
        _rev.value++
    }
}
