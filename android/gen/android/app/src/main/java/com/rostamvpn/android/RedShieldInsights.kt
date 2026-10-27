package com.rostamvpn.android

import android.content.Context
import org.json.JSONObject

/**
 * What the Traffic view and Ask RedShield derive from the flow table and the
 * score cache, on the device (iOS GuardianInsights.swift). Pure functions of
 * (flows, cache, rules) apart from the two small stores at the bottom ("first
 * seen" for New today, and the weekly per-app totals). Nothing here touches
 * the network.
 */
object ShieldInsights {

    /** A protocol, from the destination port and TCP vs UDP (iOS GuardianProtocol, same table). */
    enum class Proto(val color: Long) {
        HTTPS(0xFF4D85D1), QUIC(0xFF6BB3DB), HTTP(0xFFE8912D), DNS(0xFF8C7ACC),
        MAIL(0xFF59AD8C), SSH(0xFFBF8C59), MESSAGING(0xFF73BF66), OTHER(0xFF8E9399),
    }

    fun classify(port: Int, udp: Boolean): Proto = when (port) {
        443 -> if (udp) Proto.QUIC else Proto.HTTPS
        80 -> if (udp) Proto.OTHER else Proto.HTTP
        53, 853 -> Proto.DNS
        25, 110, 143, 465, 587, 993, 995 -> Proto.MAIL
        22 -> Proto.SSH
        5222, 5223, 5228 -> Proto.MESSAGING
        else -> Proto.OTHER
    }

    /** Per-destination facts, computed once per refresh. */
    class Facts(
        val flow: ShieldFlow,
        val score: ShieldScore?,
        /** On the user's own deny list. */
        val denied: Boolean,
        /** First seen on this phone today. */
        val newToday: Boolean,
    ) {
        val service: String = service(flow, score)
        val protocols: Map<Proto, Long>
        val otherPorts: List<Int>

        init {
            val p = HashMap<Proto, Long>()
            val other = sortedSetOf<Int>()
            for ((port, n) in flow.portBytes) {
                val k = classify(port, false); p[k] = (p[k] ?: 0) + n; if (k == Proto.OTHER) other += port
            }
            for ((port, n) in flow.udpPortBytes) {
                val k = classify(port, true); p[k] = (p[k] ?: 0) + n; if (k == Proto.OTHER) other += port
            }
            // An older core has no per-port bytes: the first port carries them.
            if (p.isEmpty() && flow.totalBytes > 0) {
                val port = flow.ports.firstOrNull() ?: 443
                val k = classify(port, false); p[k] = flow.totalBytes; if (k == Proto.OTHER) other += port
            }
            protocols = p
            otherPorts = other.toList()
        }

        val dominant: Proto? get() = protocols.maxByOrNull { it.value }?.key
        /** One of ours (FirstParty): listed, never "Needs attention"; a verdict cached before the exemption is ignored. */
        val ownHost: Boolean = RedShield.isOwn(flow.host)
        val blocked: Boolean get() = flow.blocked > 0 || (!ownHost && score?.verdict == "blocked")
        val flagged: Boolean get() = !ownHost && score?.isFlagged == true
        val rare: Boolean get() = !ownHost && score?.isRare == true
        /** Blocked, flagged, unusual or rare: the "Needs attention" rows. */
        val needsAttention: Boolean get() = !ownHost && (blocked || flagged || flow.unusual || rare)
        val known: Boolean get() = score?.verdict == "known"
        /** TCP 80 carried bytes: no encryption between the exit and the site. */
        val unencrypted: Boolean get() = (protocols[Proto.HTTP] ?: 0) > 0
        val uploadHeavy: Boolean get() = flow.bytesUp > 3 * maxOf(flow.bytesDown, 1) && flow.bytesUp > 1_048_576
        val rawIp: Boolean get() = flow.isAddress
        val isIpv6: Boolean get() = flow.host.contains(':')
        /**
         * Active for over 30 minutes and still active in the last minute. The
         * core keeps first/last per destination, not per connection, so this
         * is the destination's span (iOS reads a per-connection flag).
         */
        val longLived: Boolean get() = flow.firstSecsAgo - flow.lastSecsAgo > 1_800 && flow.lastSecsAgo < 60
        val activeRecently: Boolean get() = flow.lastSecsAgo < 300
        /** The score's country first; the regional map's (literal IPs) otherwise. */
        val country: String? get() = score?.cc?.uppercase()?.takeIf { it.length == 2 } ?: flow.cc
        val connections: Int get() = flow.connections + flow.udpConnections
        /** The worst verdict for colouring: blocked > suspicious > known > clear. */
        val tone: String get() = when {
            blocked -> "blocked"
            flagged -> "suspicious"
            else -> score?.verdict ?: "clear"
        }
    }

    /** One app (by uid) or one service. */
    class Group(val key: String, val name: String, val uid: Int?, val rows: List<Facts>) {
        val bytes: Long = rows.sumOf { it.flow.totalBytes }
        val needsAttention: Boolean get() = rows.any { it.needsAttention }
        val attention: Int get() = rows.count { it.needsAttention }
        val activeRecently: Boolean get() = rows.any { it.activeRecently }
        val unusual: Boolean get() = rows.any { it.flow.unusual }
        val country: String? get() {
            val by = HashMap<String, Long>()
            for (r in rows) r.country?.let { by[it] = (by[it] ?: 0) + r.flow.totalBytes }
            return by.maxByOrNull { it.value }?.key
        }
        val dominant: Proto? get() {
            val by = HashMap<Proto, Long>()
            for (r in rows) for ((k, v) in r.protocols) by[k] = (by[k] ?: 0) + v
            return by.maxByOrNull { it.value }?.key
        }
        val tone: String get() = rows.map { it.tone }.minByOrNull { rank(it) } ?: "clear"
    }

    /** The whole derived view. [byApp] groups by uid (unattributed last), else by service. */
    class View(
        flows: List<ShieldFlow>, cache: ShieldScoreCache, deny: Set<String>, newToday: Set<String>,
        val byApp: Boolean, appName: (Int?) -> String,
    ) {
        val facts: List<Facts> = flows.map { Facts(it, cache.score(it.host), it.host in deny, it.host in newToday) }
        val totalBytes: Long = facts.sumOf { it.flow.totalBytes }
        val groups: List<Group> = run {
            val by = LinkedHashMap<String, MutableList<Facts>>()
            for (f in facts) by.getOrPut(if (byApp) (f.flow.uid?.toString() ?: "") else f.service) { mutableListOf() } += f
            by.map { (k, rows) ->
                val uid = if (byApp) k.toIntOrNull() else null
                Group(k, if (byApp) appName(uid) else k, uid, rows.sortedByDescending { it.flow.totalBytes })
            }.sortedWith(compareBy<Group> { byApp && it.uid == null }.thenByDescending { it.bytes })
        }

        /** Rare + Unusual first (the strongest local signal), then blocked, flagged, the rest. */
        val attention: List<Facts> = facts.filter { it.needsAttention }.sortedWith(
            compareBy<Facts> {
                when {
                    it.rare && it.flow.unusual -> 0
                    it.blocked -> 1
                    it.flagged -> 2
                    else -> 3
                }
            }.thenByDescending { it.flow.totalBytes },
        )

        /** Bytes and connections per protocol, biggest first. */
        val protocols: List<Triple<Proto, Long, Int>> = run {
            val bytes = HashMap<Proto, Long>()
            val conns = HashMap<Proto, Int>()
            for (f in facts) {
                for ((k, v) in f.protocols) bytes[k] = (bytes[k] ?: 0) + v
                f.dominant?.let { conns[it] = (conns[it] ?: 0) + f.connections }
            }
            bytes.map { Triple(it.key, it.value, conns[it.key] ?: 0) }.sortedByDescending { it.second }
        }

        val otherPorts: List<Int> = facts.flatMap { it.otherPorts }.toSortedSet().toList()

        /** Share of bytes to literal IPv6 destinations, 0..1. */
        val ipv6Share: Double =
            if (totalBytes <= 0) 0.0 else facts.filter { it.isIpv6 }.sumOf { it.flow.totalBytes }.toDouble() / totalBytes

        /** Connections per country, biggest first. */
        val countries: List<Pair<String, Int>> = run {
            val by = HashMap<String, Int>()
            for (f in facts) f.country?.let { by[it] = (by[it] ?: 0) + maxOf(1, f.connections) }
            by.entries.sortedByDescending { it.value }.map { it.key to it.value }
        }

        /** True when at least one destination carries an app. */
        val attributed: Boolean = facts.any { it.flow.uid != null }
    }

    fun rank(v: String): Int = when (v) { "blocked" -> 0; "suspicious" -> 1; "known" -> 2; "clear" -> 3; else -> 4 }

    /** The service a destination belongs to: the score's service or org, else its registrable domain. */
    fun service(f: ShieldFlow, s: ShieldScore?): String {
        (s?.service ?: s?.org)?.takeIf { it.isNotEmpty() }?.let { return it }
        if (f.isAddress) return f.host
        val l = f.host.split('.')
        if (l.size <= 2) return f.host
        val sld = l[l.size - 2]
        val two = l.last().length == 2 && sld in setOf("co", "com", "net", "org", "ac", "gov", "edu")
        return l.takeLast(if (two) 3 else 2).joinToString(".")
    }

    // ── Squarified treemap (Bruls, Huizing, van Wijk), no dependency ────────

    data class Rect(val x: Float, val y: Float, val w: Float, val h: Float)

    /** Rectangles for [values] (any order; returned in the same order) inside width x height. */
    fun treemap(values: List<Double>, width: Float, height: Float): List<Rect> {
        val total = values.sum()
        if (total <= 0 || width <= 0 || height <= 0) return values.map { Rect(0f, 0f, 0f, 0f) }
        val scale = width.toDouble() * height / total
        val remaining = values.indices.sortedByDescending { values[it] }.map { it to values[it] * scale }.toMutableList()
        val out = MutableList(values.size) { Rect(0f, 0f, 0f, 0f) }
        var rx = 0.0; var ry = 0.0; var rw = width.toDouble(); var rh = height.toDouble()

        fun worst(row: List<Double>, side: Double): Double {
            val s = row.sum()
            val mx = row.maxOrNull() ?: return Double.MAX_VALUE
            val mn = row.minOrNull() ?: return Double.MAX_VALUE
            if (s <= 0 || mn <= 0) return Double.MAX_VALUE
            return maxOf(side * side * mx / (s * s), (s * s) / (side * side * mn))
        }

        while (remaining.isNotEmpty()) {
            val side = minOf(rw, rh)
            val row = mutableListOf<Pair<Int, Double>>()
            for (item in remaining) {
                if (row.isEmpty() || worst(row.map { it.second } + item.second, side) <= worst(row.map { it.second }, side)) row += item
                else break
            }
            val sum = row.sumOf { it.second }
            if (rw >= rh) {
                val w = sum / rh
                var y = ry
                for ((i, a) in row) { val h = a / maxOf(w, 0.0001); out[i] = Rect(rx.toFloat(), y.toFloat(), w.toFloat(), h.toFloat()); y += h }
                rx += w; rw = maxOf(0.0, rw - w)
            } else {
                val h = sum / rw
                var x = rx
                for ((i, a) in row) { val w = a / maxOf(h, 0.0001); out[i] = Rect(x.toFloat(), ry.toFloat(), w.toFloat(), h.toFloat()); x += w }
                ry += h; rh = maxOf(0.0, rh - h)
            }
            repeat(row.size) { remaining.removeAt(0) }
        }
        return out
    }

    // ── First seen (for "New today") and the weekly per-app totals ──────────

    private const val MAX_SEEN = 5_000

    /** Hosts first seen today on this phone. Records every host it is shown. */
    fun newToday(ctx: Context, hosts: Collection<String>): Set<String> {
        val p = RedShield.prefs(ctx)
        val today = day(System.currentTimeMillis())
        val seen = runCatching { JSONObject(p.getString("seen", "{}")!!) }.getOrDefault(JSONObject())
        var changed = false
        val out = HashSet<String>()
        for (h in hosts) {
            val d = seen.optString(h)
            if (d.isEmpty()) { seen.put(h, today); changed = true; out += h } else if (d == today) out += h
        }
        if (changed) {
            val keep = if (seen.length() > MAX_SEEN) JSONObject().also { k ->
                seen.keys().asSequence().toList().takeLast(MAX_SEEN / 2).forEach { k.put(it, seen.optString(it)) }
            } else seen
            p.edit().putString("seen", keep.toString()).apply()
        }
        return out
    }

    private val lastBytes = HashMap<String, Long>()

    /** Called by the engine each tick: per-app byte deltas into today's bucket (8 days kept). */
    fun accumulate(ctx: Context, flows: List<ShieldFlow>) {
        val pm = ctx.packageManager
        val add = HashMap<String, Long>()
        synchronized(lastBytes) {
            for (f in flows) {
                val prev = lastBytes[f.host] ?: 0L
                val now = f.totalBytes
                lastBytes[f.host] = now
                val delta = if (now >= prev) now - prev else now
                val uid = f.uid ?: continue
                if (delta <= 0) continue
                val pkg = runCatching { pm.getPackagesForUid(uid)?.firstOrNull() }.getOrNull() ?: continue
                add[pkg] = (add[pkg] ?: 0L) + delta
            }
        }
        if (add.isEmpty()) return
        val p = RedShield.prefs(ctx)
        val week = runCatching { JSONObject(p.getString("week", "{}")!!) }.getOrDefault(JSONObject())
        val today = day(System.currentTimeMillis())
        val bucket = week.optJSONObject(today) ?: JSONObject()
        add.forEach { (k, v) -> bucket.put(k, bucket.optLong(k) + v) }
        week.put(today, bucket)
        week.keys().asSequence().toList().sorted().dropLast(8).forEach { week.remove(it) }
        p.edit().putString("week", week.toString()).apply()
    }

    fun resetSession() = synchronized(lastBytes) { lastBytes.clear() }

    /** package -> bytes over the last 7 days, biggest first. */
    fun week(ctx: Context): List<Pair<String, Long>> {
        val week = runCatching { JSONObject(RedShield.prefs(ctx).getString("week", "{}")!!) }.getOrDefault(JSONObject())
        val cutoff = day(System.currentTimeMillis() - 6L * 86_400_000)
        val by = HashMap<String, Long>()
        for (d in week.keys()) {
            if (d < cutoff) continue
            val b = week.optJSONObject(d) ?: continue
            for (k in b.keys()) by[k] = (by[k] ?: 0L) + b.optLong(k)
        }
        return by.entries.sortedByDescending { it.value }.map { it.key to it.value }
    }

    private fun day(ms: Long): String = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date(ms))
}

/**
 * RedShield per-app "Pause this app's internet while connected". A package
 * list the tunnel service applies with addDisallowedApplication, and only when
 * the system "Block connections without VPN" is on (TunnelVpnService.applyAppRules):
 * there a disallowed app has no network at all; without it the app would go
 * around the tunnel instead, which is the opposite of a pause.
 */
object ShieldAppControls {
    private const val KEY = "paused_apps"

    fun paused(ctx: Context): Set<String> = RedShield.prefs(ctx).getStringSet(KEY, emptySet()).orEmpty().toSet()

    fun isPaused(ctx: Context, pkg: String): Boolean = pkg in paused(ctx)

    fun setPaused(ctx: Context, pkg: String, on: Boolean) {
        val next = if (on) paused(ctx) + pkg else paused(ctx) - pkg
        RedShield.prefs(ctx).edit().putStringSet(KEY, next).apply()
    }
}
