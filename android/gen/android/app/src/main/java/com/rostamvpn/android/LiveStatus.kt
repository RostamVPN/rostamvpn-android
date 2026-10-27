package com.rostamvpn.android

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * One snapshot of what the live surfaces show (owner 2026-10-17): the stats
 * notification (TunnelVpnService), the Quick Settings tile and the widget
 * (src/internetpro). Published by VpnViewModel from the state it already has;
 * nothing here does network or tunnel work. Only brands with
 * features.liveStatus (BuildConfig.FEATURE_LIVE_STATUS) publish at all.
 */
object LiveStatus {
    enum class State { OFF, CONNECTING, CONNECTED, PROTECTED }

    data class Snapshot(
        val state: State = State.OFF,
        /** The route as the Connect tab shows it ("Automatic · fastest" or the region's label). */
        val route: String = "",
        val blockedToday: Int = 0,
        val rxBytes: Long = 0,
        val txBytes: Long = 0,
        /** null until the app has an entitlement answer this process. */
        val paid: Boolean? = null,
    )

    private val _flow = MutableStateFlow(Snapshot())
    val flow: StateFlow<Snapshot> = _flow.asStateFlow()

    private const val PREFS = "live_status"

    /** Publish. Surfaces that cannot follow every byte (tile, widget) hear only visible changes. */
    fun publish(ctx: Context?, s: Snapshot) {
        if (!BuildConfig.FEATURE_LIVE_STATUS) return
        val old = _flow.value
        _flow.value = s
        if (ctx != null && visibleChange(old, s)) {
            val app = ctx.applicationContext
            app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString("state", s.state.name).putString("route", s.route)
                .apply { s.paid?.let { putBoolean("paid", it) } }
                .apply()
            LiveSurfaces.changed(app)
        }
    }

    /** Bytes alone never wake the tile or the widget. Pure. */
    fun visibleChange(a: Snapshot, b: Snapshot): Boolean =
        a.state != b.state || a.route != b.route || a.blockedToday != b.blockedToday || a.paid != b.paid

    /**
     * The state for a surface that may run in a process the app has not
     * touched yet (widget, tile). The tunnel lives in this process, so if the
     * service is not running, it is OFF whatever the last write said.
     */
    fun current(ctx: Context): Snapshot {
        val live = _flow.value
        if (live.state != State.OFF || !TunnelVpnService.isRunning) {
            return if (TunnelVpnService.isRunning) live else live.copy(state = State.OFF)
        }
        val p = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val st = runCatching { State.valueOf(p.getString("state", "OFF")!!) }.getOrDefault(State.OFF)
        return live.copy(state = st, route = p.getString("route", "") ?: "",
            paid = if (p.contains("paid")) p.getBoolean("paid", false) else null)
    }

    /** The connection state machine onto the four states a surface shows. Pure. */
    fun stateOf(c: VpnViewModel.ConnectionState, protectedAck: Boolean): State = when (c) {
        is VpnViewModel.ConnectionState.Connected -> if (protectedAck) State.PROTECTED else State.CONNECTED
        is VpnViewModel.ConnectionState.Connecting, VpnViewModel.ConnectionState.Verifying -> State.CONNECTING
        else -> State.OFF
    }
}

/**
 * The tile and the widget live only in the internetpro source set
 * (LiveSurfacesImpl), so the Glance dependency never reaches another brand.
 * Found by name, like EsimAlerts; elsewhere this is a no-op.
 */
object LiveSurfaces {
    private val impl: Any? by lazy {
        runCatching { Class.forName("com.rostamvpn.android.LiveSurfacesImpl").getField("INSTANCE").get(null) }.getOrNull()
    }

    fun changed(ctx: Context) {
        val i = impl ?: return
        runCatching { i.javaClass.getMethod("changed", Context::class.java).invoke(i, ctx) }
    }
}

/**
 * The stats notification's words (FEATURE_LIVE_STATUS), in the order a
 * traveller uses them: data left, days left, exit route, blocked today,
 * then this session's bytes. Pure: resource ids and arguments, so the order
 * and the gates are tested; TunnelVpnService formats them.
 */
internal object LiveNotificationText {
    data class Line(val res: Int, val args: List<Any>)

    private const val DAY = 24L * 60 * 60 * 1000

    fun titleRes(s: LiveStatus.State): Int = when (s) {
        LiveStatus.State.PROTECTED -> R.string.tabs_tile_protected
        LiveStatus.State.CONNECTED -> R.string.tabs_status_connected
        LiveStatus.State.CONNECTING -> R.string.tabs_status_connecting
        LiveStatus.State.OFF -> R.string.tabs_status_off
    }

    /**
     * [dataLeft] is the eSIM's data already formatted by the caller (it needs
     * a Context); null when there is no eSIM line or no usage.
     */
    fun lines(
        s: LiveStatus.Snapshot, g: com.rostamvpn.android.ui.screens.EsimGlance?, dataLeft: String?,
        showBlocked: Boolean, rx: String, tx: String, nowMs: Long,
    ): List<Line> = buildList {
        if (g != null) {
            if (g.graceDaysLeft != null) {
                add(Line(R.string.tabs_reload_within, listOf(g.graceDaysLeft)))
            } else {
                if (dataLeft != null) add(Line(R.string.tabs_live_esim_left, listOf(g.title, dataLeft)))
                g.endsAtMs?.let { end ->
                    val days = ((end - nowMs + DAY - 1) / DAY).toInt()
                    if (end > nowMs) add(Line(R.string.tabs_live_days_left, listOf(days)))
                }
                if (g.isNumber && (g.minutesLeft != null || g.smsLeft != null)) {
                    add(Line(R.string.tabs_voice_sms_left, listOf(g.minutesLeft ?: 0, g.smsLeft ?: 0)))
                }
            }
        }
        if (s.state != LiveStatus.State.OFF && s.route.isNotBlank()) add(Line(R.string.tabs_live_route, listOf(s.route)))
        if (showBlocked) add(Line(R.string.tabs_widget_blocked, listOf(s.blockedToday)))
        if (s.state == LiveStatus.State.CONNECTED || s.state == LiveStatus.State.PROTECTED) {
            add(Line(R.string.tabs_live_session, listOf(rx, tx)))
        }
    }
}
