package com.rostamvpn.android.ui.screens

import android.content.Context
import android.provider.Settings
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * The travel art of the tabs treatment, ported from iOS (ESim/WorldDotsMap.swift,
 * ESim/TravelGlobe.swift, LaunchIntro.swift's splash art):
 *
 *  - the owner's dotted world map (`world-dots.json`, 1319 dot centres, a
 *    stylised tilted crop, not a projection) as the home backdrop: STATIC;
 *  - the slowly turning wireframe globe on the eSIM tab's header;
 *  - the designer's constellation for the launch.
 *
 * Both data files are OPTIONAL brand assets (src/<brand>/assets): a brand
 * that ships neither simply draws nothing, so no brand check is needed and
 * no other brand carries the bytes.
 *
 * Motion follows the system's animator scale: 0 (Remove animations, or
 * Reduce motion on some ROMs) draws one still frame, and the globe never
 * ticks while its screen is not RESUMED or it has left composition.
 */
internal class WorldDots(val aspect: Float, val dotRadiusRel: Float, val dots: FloatArray)

private object ArtCache {
    @Volatile var dots: WorldDots? = null
    @Volatile var dotsTried = false
    @Volatile var constellation: ImageBitmap? = null
    @Volatile var constellationTried = false
}

internal fun worldDots(ctx: Context): WorldDots? {
    if (ArtCache.dotsTried) return ArtCache.dots
    ArtCache.dots = runCatching {
        val o = org.json.JSONObject(ctx.assets.open("world-dots.json").bufferedReader().readText())
        val a = o.getJSONArray("dots")
        val out = FloatArray(a.length() * 2)
        for (i in 0 until a.length()) {
            val p = a.getJSONArray(i)
            out[2 * i] = p.getDouble(0).toFloat()
            out[2 * i + 1] = p.getDouble(1).toFloat()
        }
        WorldDots(o.optDouble("aspect", 1.2135).toFloat(), o.optDouble("dot_radius_rel", 0.00535).toFloat(), out)
    }.getOrNull()
    ArtCache.dotsTried = true
    return ArtCache.dots
}

internal fun launchConstellation(ctx: Context): ImageBitmap? {
    if (ArtCache.constellationTried) return ArtCache.constellation
    ArtCache.constellation = runCatching {
        ctx.assets.open("launch-constellation.png").use { android.graphics.BitmapFactory.decodeStream(it) }?.asImageBitmap()
    }.getOrNull()
    ArtCache.constellationTried = true
    return ArtCache.constellation
}

/** The system animator scale; 0 = draw still frames only. */
internal fun animatorScale(ctx: Context): Float = runCatching {
    Settings.Global.getFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
}.getOrDefault(1f)

/**
 * Draws the dotted map full width of the canvas at its own aspect, its top
 * edge at [top] px. One tint for every dot. Pure drawing, no state: as the
 * home backdrop it is drawn once and the home's per-second rate updates do
 * not touch it (its inputs do not change).
 */
internal fun DrawScope.drawWorldDots(data: WorldDots, tint: Color, top: Float = 0f) {
    val w = size.width
    val h = w / data.aspect
    val r = max(0.6f, data.dotRadiusRel * w)
    val path = Path()
    val d = data.dots
    var i = 0
    while (i < d.size) {
        val x = d[i] * w
        val y = top + d[i + 1] * h
        path.addOval(androidx.compose.ui.geometry.Rect(x - r, y - r, x + r, y + r))
        i += 2
    }
    drawPath(path, tint)
}

/** The map as a composable: a full-bleed static backdrop. */
@Composable
internal fun WorldDotsMap(tint: Color, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val data = remember { worldDots(ctx) } ?: return
    Canvas(modifier.clearAndSetSemantics { }) { drawWorldDots(data, tint) }
}

/** Where a destination sits on the globe (main arrival city / rough regional centroid). iOS GeoPoint. */
private val GEO: Map<String, Pair<Double, Double>> = mapOf(
    "US" to (40.7 to -74.0), "CA" to (43.7 to -79.4), "MX" to (19.4 to -99.1), "BR" to (-23.5 to -46.6),
    "AR" to (-34.6 to -58.4), "CO" to (4.7 to -74.1), "PE" to (-12.0 to -77.0), "CL" to (-33.4 to -70.6),
    "GB" to (51.5 to -0.1), "FR" to (48.9 to 2.4), "DE" to (52.5 to 13.4), "IT" to (41.9 to 12.5),
    "ES" to (40.4 to -3.7), "PT" to (38.7 to -9.1), "NL" to (52.4 to 4.9), "CH" to (47.4 to 8.5),
    "AT" to (48.2 to 16.4), "GR" to (37.98 to 23.7), "HR" to (45.8 to 16.0), "CZ" to (50.1 to 14.4),
    "PL" to (52.2 to 21.0), "IE" to (53.3 to -6.3), "IS" to (64.1 to -21.9), "TR" to (41.0 to 29.0),
    "AE" to (25.2 to 55.3), "SA" to (21.5 to 39.2), "QA" to (25.3 to 51.5), "EG" to (30.0 to 31.2),
    "MA" to (33.6 to -7.6), "ZA" to (-33.9 to 18.4), "KE" to (-1.3 to 36.8), "TZ" to (-6.8 to 39.3),
    "JP" to (35.7 to 139.7), "KR" to (37.6 to 127.0), "CN" to (31.2 to 121.5), "HK" to (22.3 to 114.2),
    "TW" to (25.0 to 121.6), "TH" to (13.8 to 100.5), "VN" to (10.8 to 106.7), "SG" to (1.35 to 103.8),
    "MY" to (3.1 to 101.7), "ID" to (-8.7 to 115.2), "PH" to (14.6 to 121.0), "IN" to (19.1 to 72.9),
    "AU" to (-33.9 to 151.2), "NZ" to (-36.8 to 174.8),
)
private val ROUTES = listOf("US" to "GB", "GB" to "JP")

private class V(val x: Double, val y: Double, val z: Double)

private fun unit(lat: Double, lon: Double): V {
    val a = Math.toRadians(lat); val o = Math.toRadians(lon)
    return V(cos(a) * sin(o), sin(a), cos(a) * cos(o))
}

/** Meridians and parallels every 30°, sampled every 6°. Built once. */
private val GRATICULE: List<List<V>> by lazy {
    val lines = mutableListOf<List<V>>()
    var lon = 0.0
    while (lon < 360) { lines += (-84..84 step 6).map { unit(it.toDouble(), lon) }; lon += 30 }
    for (lat in listOf(-60.0, -30.0, 0.0, 30.0, 60.0)) lines += (0..360 step 6).map { unit(lat, it.toDouble()) }
    lines
}

/** Spin about the pole, then tilt ~25° N toward the viewer, where most travel is. */
private class Camera(spin: Double, tilt: Double = Math.toRadians(25.0)) {
    private val cs = cos(spin); private val sn = sin(spin); private val ct = cos(tilt); private val st = sin(tilt)
    fun apply(p: V): V {
        val x = p.x * cs + p.z * sn
        val z0 = -p.x * sn + p.z * cs
        return V(x, p.y * ct - z0 * st, p.y * st + z0 * ct)
    }
}

/**
 * The slowly turning wireframe globe: graticule, brand dots, two great-circle
 * routes with a dot flying along them. One path and one stroke per layer.
 * Ticks only while RESUMED and in composition (a LazyColumn drops it when it
 * scrolls away); animator scale 0 = a single still frame.
 */
@Composable
internal fun TravelGlobe(ink: Color, accent: Color, dark: Boolean, modifier: Modifier = Modifier, periodSec: Double = 12.0) {
    val ctx = LocalContext.current
    val still = remember { animatorScale(ctx) <= 0f }
    var nanos by remember { mutableLongStateOf(-1L) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    if (!still) {
        LaunchedEffect(lifecycle) {
            lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                while (true) withFrameNanos { nanos = it }
            }
        }
    }
    val dots = remember { GEO.keys.sorted().map { val (la, lo) = GEO.getValue(it); unit(la, lo) } }
    Canvas(modifier.clearAndSetSemantics { }) {
        val t = if (still || nanos < 0) -1.0 else nanos / 1e9
        drawGlobe(t, periodSec, ink, accent, dark, dots)
    }
}

private fun DrawScope.drawGlobe(t: Double, period: Double, ink: Color, red: Color, dark: Boolean, dots: List<V>) {
    val r = (min(size.width, size.height * 1.3f) * 0.5f - 2f).toDouble()
    val c = Offset(size.width / 2, size.height / 2)
    // Westward, so destinations rise over the eastern limb like a sunrise.
    val spin = if (t < 0) Math.toRadians(-15.0) else -((t % period) / period) * 2 * Math.PI
    val cam = Camera(spin)
    fun screen(p: V, lift: Double = 1.0) = Offset((c.x + p.x * r * lift).toFloat(), (c.y - p.y * r * lift).toFloat())
    val lineAlpha = 0.22f

    drawCircle(
        Brush.radialGradient(
            listOf(ink.copy(alpha = if (dark) 0.08f else 0.05f), ink.copy(alpha = 0f)),
            center = Offset((c.x - r * 0.35).toFloat(), (c.y - r * 0.4).toFloat()), radius = (r * 1.4).toFloat(),
        ),
        radius = r.toFloat(), center = c,
    )
    drawCircle(ink.copy(alpha = lineAlpha * 0.9f), radius = r.toFloat(), center = c, style = Stroke(0.8f * density))

    val grid = Path()
    for (line in GRATICULE) {
        var prev: V? = null
        for (p0 in line) {
            val p = cam.apply(p0)
            val q = prev
            if (q != null && q.z > 0 && p.z > 0) {
                val a = screen(q); val b = screen(p)
                grid.moveTo(a.x, a.y); grid.lineTo(b.x, b.y)
            }
            prev = p
        }
    }
    drawPath(grid, ink.copy(alpha = lineAlpha), style = Stroke(0.6f * density))

    val halos = Path(); val points = Path()
    for (u in dots) {
        val p = cam.apply(u)
        if (p.z <= 0.05) continue
        val s = screen(p)
        val k = (0.6 + 0.4 * p.z).toFloat() * density
        halos.addOval(androidx.compose.ui.geometry.Rect(s, 5f * k))
        points.addOval(androidx.compose.ui.geometry.Rect(s, 2.2f * k))
    }
    drawPath(halos, red.copy(alpha = 0.18f))
    drawPath(points, red)

    ROUTES.forEachIndexed { i, (from, to) ->
        val a = GEO[from]?.let { unit(it.first, it.second) } ?: return@forEachIndexed
        val b = GEO[to]?.let { unit(it.first, it.second) } ?: return@forEachIndexed
        val omega = acos((a.x * b.x + a.y * b.y + a.z * b.z).coerceIn(-1.0, 1.0))
        if (omega <= 0.01) return@forEachIndexed
        fun at(f: Double): Pair<V, Double> {
            val s0 = sin((1 - f) * omega) / sin(omega); val s1 = sin(f * omega) / sin(omega)
            return cam.apply(V(a.x * s0 + b.x * s1, a.y * s0 + b.y * s1, a.z * s0 + b.z * s1)) to 1 + 0.18 * sin(Math.PI * f)
        }
        val arc = Path(); var started = false
        for (k in 0..40) {
            val (p, lift) = at(k / 40.0)
            if (p.z > -0.15 * lift + 0.15) {
                val s = screen(p, lift)
                if (started) arc.lineTo(s.x, s.y) else { arc.moveTo(s.x, s.y); started = true }
            } else started = false
        }
        drawPath(arc, red.copy(alpha = 0.55f), style = Stroke(1.2f * density, cap = StrokeCap.Round,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(3f * density, 3f * density))))
        if (t < 0) return@forEachIndexed
        val f = ((t + i * 2.5) % 5) / 4
        if (f > 1) return@forEachIndexed
        val (p, lift) = at(f)
        if (p.z > -0.15 * lift + 0.15) {
            val s = screen(p, lift)
            drawCircle(red.copy(alpha = 0.22f), 6f * density, s)
            drawCircle(red, 3f * density, s)
        }
    }
}
