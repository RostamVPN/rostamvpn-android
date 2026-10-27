package com.rostamvpn.android.ui.screens

import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * The reload state of a number eSIM, driven by data, never by copy
 * (ESIM-INTEGRATION-2026-10-15 §2 rule 3). Pure, so every arm is unit-tested.
 *
 * Numbers are never permanent: validity starts at first attach, nothing
 * auto-renews, and a reload within the grace window after expiry revives the
 * line AND keeps the number. After the grace window Airalo recycles it.
 */
internal sealed class ReloadState {
    /** Topups exist and the line has not expired: "Reload to keep this number". */
    data object Reloadable : ReloadState()
    /** Expired, still inside grace: "Reload within N days to keep this number". */
    data class InGrace(val daysLeft: Int) : ReloadState()
    /** This network cannot be reloaded: "…cannot be reloaded; it ends on <date>". */
    data class NotReloadable(val endsAtMs: Long?) : ReloadState()
    /** Recycled or past grace: "This number has ended". */
    data object Gone : ReloadState()
    /** Topups not loaded yet (per-ICCID call, rate-limited server-side). */
    data object Unknown : ReloadState()
}

/** Airalo's "YYYY-MM-DD HH:MM:SS" (UTC), or an ISO form; null if absent/unparseable. */
internal fun parseAiraloTime(v: String?): Long? {
    val t = v?.trim()?.takeIf { it.length >= 19 } ?: return null
    return runCatching {
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
            .parse(t.substring(0, 19).replace('T', ' '))!!.time
    }.getOrNull()
}

private const val DAY_MS = 24L * 60 * 60 * 1000

/**
 * @param status Airalo usage status (NOT_ACTIVE | ACTIVE | FINISHED | EXPIRED | RECYCLED | UNKNOWN)
 * @param graceUntilMs server's expired_at + topup_grace_days when sent; else computed here
 * @param graceDays the plan's topup_grace_days, for the fallback computation
 * @param topups number of reload packages for THIS eSIM; null = not loaded yet
 */
internal fun reloadState(
    status: String,
    expiredAtMs: Long?,
    graceUntilMs: Long?,
    graceDays: Int,
    recycled: Boolean,
    topups: Int?,
    nowMs: Long,
): ReloadState {
    if (recycled || status == "RECYCLED") return ReloadState.Gone
    val graceEnd = graceUntilMs ?: expiredAtMs?.let { it + graceDays * DAY_MS }
    val expired = status == "EXPIRED" || (expiredAtMs != null && nowMs >= expiredAtMs)
    if (expired) {
        if (graceEnd == null || nowMs >= graceEnd) return ReloadState.Gone
        return when (topups) {
            null -> ReloadState.Unknown
            0 -> ReloadState.Gone          // expired and this network cannot be reloaded
            else -> ReloadState.InGrace(((graceEnd - nowMs + DAY_MS - 1) / DAY_MS).toInt().coerceAtLeast(1))
        }
    }
    return when (topups) {
        null -> ReloadState.Unknown
        0 -> ReloadState.NotReloadable(expiredAtMs)
        else -> ReloadState.Reloadable
    }
}

/**
 * Run-out forecast (C3): at the pace used so far, when does the data end, and
 * how much more would last until the eSIM expires? Validity starts on first
 * attach, so the start is `expired_at - days`. Null when there is nothing
 * honest to say: no expiry yet, unlimited, under a day of history, nothing
 * used, or the data lasts anyway.
 */
internal data class Forecast(val runOutMs: Long, val shortfallMb: Long)

internal fun forecast(totalMb: Long?, remainingMb: Long?, days: Int?, expiredAtMs: Long?, nowMs: Long): Forecast? {
    if (totalMb == null || remainingMb == null || totalMb <= 0 || remainingMb <= 0) return null
    if (days == null || days <= 0 || expiredAtMs == null || nowMs >= expiredAtMs) return null
    val elapsed = nowMs - (expiredAtMs - days * DAY_MS)
    if (elapsed < DAY_MS) return null
    val used = (totalMb - remainingMb).coerceAtLeast(0)
    if (used == 0L) return null
    val perMs = used.toDouble() / elapsed
    val need = perMs * (expiredAtMs - nowMs)
    if (need <= remainingMb) return null
    return Forecast(nowMs + (remainingMb / perMs).toLong(), kotlin.math.ceil(need - remainingMb).toLong())
}

/** The cheapest top-up for THIS eSIM that covers the shortfall; null if none does. */
internal fun suggestTopup(topups: List<PassPlan>, shortfallMb: Long): PassPlan? =
    topups.filter { it.unlimited || it.dataMb >= shortfallMb }.minByOrNull { it.priceCents }

/**
 * The reload state of one My eSIMs card, number or data line alike. Grace
 * days from the row, else the plan, else none. [topups] null = not known.
 */
internal fun cardReloadState(e: Esim, planGraceDays: Int?, topups: Int?, nowMs: Long): ReloadState = reloadState(
    status = effectiveStatus(e), expiredAtMs = parseAiraloTime(e.expiredAt), graceUntilMs = parseAiraloTime(e.graceUntil),
    graceDays = e.topupGraceDays ?: planGraceDays ?: 0, recycled = e.recycled, topups = topups, nowMs = nowMs,
)

/** Whether a top-up can be offered: only while the line can still be reloaded. */
internal fun canTopUp(state: ReloadState): Boolean = state == ReloadState.Reloadable || state is ReloadState.InGrace

/**
 * Airalo's usage status, or, when usage is missing (Airalo down: no usage, or
 * the server's UNKNOWN default), the row's own status mapped onto it
 * (provisioned | active | depleted | expired). Blank when neither says.
 */
internal fun effectiveStatus(e: Esim): String {
    val u = e.usageStatus
    if (u.isNotBlank() && u != "UNKNOWN") return u
    return when (e.status) {
        "provisioned" -> "NOT_ACTIVE"
        "active" -> "ACTIVE"
        "depleted" -> "FINISHED"
        "expired" -> "EXPIRED"
        else -> ""
    }
}

/** What the card says about expiry. Pure. */
internal sealed class ExpiryLine {
    data class On(val ms: Long) : ExpiryLine()
    /** Not attached yet: validity starts on first connect. */
    data object StartsOnConnect : ExpiryLine()
    /** Attached, but the date cannot be read right now. */
    data object Unavailable : ExpiryLine()
    data object None : ExpiryLine()
}

internal fun expiryLine(e: Esim): ExpiryLine {
    parseAiraloTime(e.expiredAt)?.let { return ExpiryLine.On(it) }
    return when (effectiveStatus(e)) {
        "NOT_ACTIVE" -> ExpiryLine.StartsOnConnect
        "ACTIVE" -> ExpiryLine.Unavailable
        else -> ExpiryLine.None
    }
}

/**
 * What the live surfaces say about eSIM (owner 2026-10-17): one line, from
 * the last /v1/esims answer the app already fetched. No codes, no ICCID:
 * only what is shown. Pure.
 */
internal data class EsimGlance(
    val title: String,
    /** null when usage is not known. */
    val dataLeftMb: Long?,
    val unlimited: Boolean,
    val endsAtMs: Long?,
    val isNumber: Boolean,
    val minutesLeft: Int?,
    val smsLeft: Int?,
    /** Set only for a number line expired and in grace with reloads available. */
    val graceDaysLeft: Int?,
)

/**
 * The line worth showing: an ACTIVE eSIM first (newest first, as the server
 * sorts), else a number in grace that can still be reloaded. Nothing for
 * lines not installed yet, used up, expired or ended. [topups] is what the
 * app last learned per ICCID (null = unknown).
 */
internal fun esimGlance(list: List<Esim>, topups: (String) -> Int?, nowMs: Long): EsimGlance? {
    fun glance(e: Esim, grace: Int?) = EsimGlance(
        title = e.title.ifBlank { e.iccid.takeLast(4) },
        dataLeftMb = e.remainingMb,
        unlimited = e.totalMb == 0L,
        endsAtMs = parseAiraloTime(e.expiredAt),
        isNumber = e.looksLikeNumber,
        minutesLeft = e.remainingVoice.takeIf { e.looksLikeNumber },
        smsLeft = e.remainingText.takeIf { e.looksLikeNumber },
        graceDaysLeft = grace,
    )
    list.firstOrNull { effectiveStatus(it) == "ACTIVE" && cardReloadState(it, null, topups(it.iccid), nowMs) != ReloadState.Gone }
        ?.let { return glance(it, null) }
    for (e in list) {
        if (!e.looksLikeNumber) continue
        val s = cardReloadState(e, null, topups(e.iccid), nowMs)
        if (s is ReloadState.InGrace) return glance(e, s.daysLeft)
    }
    return null
}

/** The glance kept on the device for the notification and the widget (no network there). */
internal object EsimGlanceStore {
    private const val PREFS = "esim_glance"

    fun noteTopups(ctx: android.content.Context, iccid: String, n: Int?) {
        if (!com.rostamvpn.android.BuildConfig.FEATURE_LIVE_STATUS || n == null) return
        ctx.applicationContext.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
            .edit().putInt("topups_$iccid", n).apply()
    }

    /** After a successful /v1/esims fetch. */
    fun save(ctx: android.content.Context, list: List<Esim>, nowMs: Long = System.currentTimeMillis()) {
        if (!com.rostamvpn.android.BuildConfig.FEATURE_LIVE_STATUS) return
        val p = ctx.applicationContext.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
        val g = esimGlance(list, { id -> if (p.contains("topups_$id")) p.getInt("topups_$id", 0) else null }, nowMs)
        val ed = p.edit()
        if (g == null) ed.remove("glance") else ed.putString("glance", toJson(g))
        ed.apply()
        com.rostamvpn.android.LiveSurfaces.changed(ctx.applicationContext)
    }

    fun load(ctx: android.content.Context): EsimGlance? =
        ctx.applicationContext.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
            .getString("glance", null)?.let { runCatching { fromJson(it) }.getOrNull() }

    fun toJson(g: EsimGlance): String = org.json.JSONObject().apply {
        put("title", g.title); g.dataLeftMb?.let { put("left", it) }; put("unl", g.unlimited)
        g.endsAtMs?.let { put("ends", it) }; put("num", g.isNumber)
        g.minutesLeft?.let { put("min", it) }; g.smsLeft?.let { put("sms", it) }; g.graceDaysLeft?.let { put("grace", it) }
    }.toString()

    fun fromJson(s: String): EsimGlance = org.json.JSONObject(s).let { o ->
        fun l(k: String) = if (o.has(k)) o.getLong(k) else null
        fun i(k: String) = if (o.has(k)) o.getInt(k) else null
        EsimGlance(o.optString("title"), l("left"), o.optBoolean("unl"), l("ends"), o.optBoolean("num"), i("min"), i("sms"), i("grace"))
    }
}
