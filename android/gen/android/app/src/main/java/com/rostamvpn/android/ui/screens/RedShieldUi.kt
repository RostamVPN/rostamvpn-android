package com.rostamvpn.android.ui.screens

import android.content.Context
import android.text.format.DateUtils
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.rostamvpn.android.GuardStore
import com.rostamvpn.android.R
import com.rostamvpn.android.RedShieldDemo
import com.rostamvpn.android.RedShieldEngine
import com.rostamvpn.android.RedShieldProfiles
import com.rostamvpn.android.ShieldCtx
import com.rostamvpn.android.ShieldFlow
import com.rostamvpn.android.ShieldFlows
import com.rostamvpn.android.ShieldProfile
import com.rostamvpn.android.ShieldScore
import com.rostamvpn.android.ShieldScoreCache
import java.util.Calendar
import java.util.Locale

// Shared pieces of the RedShield screens (home card, sheet, report): where the
// data comes from (the live core and stores, or the debug fixture), the words
// for verdicts and categories, and small drawn glyphs. Brand-neutral: the
// feature's name is RedShield.name (BuildConfig.PROTECTION_NAME).

/** Where every screen reads from. The debug fixture wins when it is on. */
internal object ShieldData {
    fun demo(): Boolean = RedShieldDemo.active

    /** The flow table with app attribution; null when the tunnel is down. */
    fun flows(ctx: Context): ShieldFlows? = RedShieldDemo.flows(ctx) ?: RedShieldEngine.attach(ctx).let { RedShieldEngine.flows() }

    fun scores(ctx: Context): ShieldScoreCache = RedShieldDemo.scores() ?: ShieldScoreCache.load(ctx)

    fun blocked(ctx: Context): List<GuardStore.Row> =
        RedShieldDemo.blockedRows() ?: runCatching {
            // A record for one of ours stored before the first-party exemption is not shown.
            val rows = GuardStore.get(ctx).recent().filter { it.refused && !com.rostamvpn.android.RedShield.isOwn(it.host) }
            // Rows stored before ctx existed (or before the host was scored): what the cache knows now.
            if (rows.all { it.ctx != null }) rows else {
                val cache = ShieldScoreCache.load(ctx)
                rows.map { r -> if (r.ctx != null) r else r.copy(ctx = ShieldContext.of(cache.score(r.host), null)) }
            }
        }.getOrDefault(emptyList())

    fun blockedToday(ctx: Context): Int {
        RedShieldDemo.blockedRows()?.let { rows ->
            val midnight = midnight()
            return rows.count { it.refused && it.ts >= midnight }
        }
        return runCatching { GuardStore.get(ctx).refusedToday() }.getOrDefault(0)
    }

    fun catalogue(ctx: Context): List<ShieldProfile> = RedShieldDemo.profiles() ?: RedShieldProfiles.catalogue(ctx)

    private fun midnight(): Long = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis
}

/** One line in the home card's feed. */
internal data class ShieldNotice(val kind: Kind, val host: String, val detail: String, val at: Long, val uid: Int?, val ctx: ShieldCtx? = null) {
    enum class Kind { BLOCKED, FLAGGED, UNUSUAL }
}

/** Refused rows, cached flags among this session's destinations, Unusual ones; newest first, one per host. */
internal fun shieldNotices(ctx: Context, flows: List<ShieldFlow>, scores: ShieldScoreCache, rows: List<GuardStore.Row>): List<ShieldNotice> {
    val now = System.currentTimeMillis()
    val out = mutableListOf<ShieldNotice>()
    for (f in flows) {
        val s = scores.score(f.host)
        if (s != null && s.isFlagged) {
            out += ShieldNotice(ShieldNotice.Kind.FLAGGED, f.host,
                ShieldContext.reason(ctx, s.context) ?: shieldLabel(ctx, s.label) ?: ctx.getString(R.string.rs_kind_flagged), s.used, f.uid,
                ShieldContext.of(s, f))
        } else if (f.unusual) {
            out += ShieldNotice(ShieldNotice.Kind.UNUSUAL, f.host, ctx.getString(R.string.rs_connections, f.connections),
                now - f.lastSecsAgo * 1000, f.uid, ShieldContext.of(s, f))
        }
    }
    for (r in rows) if (r.refused) out += ShieldNotice(ShieldNotice.Kind.BLOCKED, r.host,
        ShieldContext.reason(ctx, r.ctx) ?: shieldReason(ctx, r.reason), r.ts, r.uid, r.ctx ?: ShieldContext.of(scores.score(r.host), null))
    return out.groupBy { it.host }.map { (_, v) -> v.maxBy { it.at } }.sortedByDescending { it.at }
}

/** A refusal's reason in words: a profile's name, a known category, or the label as sent. */
internal fun shieldReason(ctx: Context, reason: String): String {
    if (reason.startsWith("profile:")) {
        val id = reason.removePrefix("profile:")
        return RedShieldProfiles.name(ctx, id) ?: humanize(id)
    }
    val res = when (reason.lowercase(Locale.ROOT)) {
        "rating_malicious" -> R.string.rs_cat_rating_malicious
        "domain" -> R.string.rs_cat_domain
        "malware" -> R.string.rs_cat_malware
        "phishing" -> R.string.rs_cat_phishing
        "controller" -> R.string.rs_cat_controller
        "orb" -> R.string.rs_cat_orb
        "scanner" -> R.string.rs_cat_scanner
        "proxy" -> R.string.rs_cat_proxy
        "user" -> R.string.rs_cat_user
        "", "auto", "profile" -> R.string.rs_kind_blocked
        else -> 0
    }
    return if (res != 0) ctx.getString(res) else humanize(reason)
}

/** A score's label: a known category id in the reader's language, else as the server sent it. */
internal fun shieldLabel(ctx: Context, label: String?): String? {
    val l = label?.takeIf { it.isNotEmpty() } ?: return null
    val key = l.lowercase(Locale.ROOT).replace(' ', '_')
    return if (key in KNOWN_CATEGORIES) shieldReason(ctx, key) else l
}

private val KNOWN_CATEGORIES = setOf("rating_malicious", "malware", "phishing", "controller", "orb", "scanner", "proxy")

private fun humanize(id: String): String =
    id.replace('_', ' ').replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }

/**
 * The context line for one destination (owner, 2026-10-26: "there should be
 * some basic whois maybe even isp"): "Hetzner Online GmbH · AS24940 · Germany",
 * the reverse name under it, and the reason in the reader's language. From
 * the server's `ctx` when it sent one, else the older score fields, else the
 * regional map's country (the flow table's cc for a literal address).
 */
internal object ShieldContext {
    /** Server ctx, else the score's own org/cc, else the flow's mapped country. */
    fun of(score: ShieldScore?, flow: ShieldFlow?): ShieldCtx? =
        (score?.context ?: ShieldCtx()).orElse(ShieldCtx(cc = flow?.cc)).takeIf { !it.isEmpty }

    /** "Org · AS24940 · Germany"; null when none of the three is known. */
    fun line(c: ShieldCtx?): String? {
        if (c == null) return null
        val parts = listOfNotNull(c.org?.let(::isolate), c.asn?.let { isolate("AS$it") }, ShieldWords.country(c.cc))
        return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
    }

    /** The reverse name, unless it only repeats the host. */
    fun rdns(c: ShieldCtx?, host: String): String? =
        c?.rdns?.takeIf { !it.equals(host, ignoreCase = true) }?.let(::isolate)

    /**
     * The reason in the reader's language when this build knows the key
     * ("malware_host" and "malware" both read as Malware), else the server's
     * English sentence, else null (the caller keeps its own wording).
     */
    fun reason(ctx: Context, c: ShieldCtx?): String? {
        if (c == null) return null
        // The server's sentence is English and more specific than our word for
        // the category; an English reader gets it as sent.
        val english = androidx.core.os.ConfigurationCompat.getLocales(ctx.resources.configuration)[0]?.language == "en"
        if (english && c.reason != null) return c.reason
        for (k in listOfNotNull(c.reasonKey, c.category)) reasonRes(k)?.let { return ctx.getString(it) }
        return c.reason
    }

    internal fun reasonRes(key: String): Int? {
        val k = key.lowercase(Locale.ROOT).replace('-', '_').replace(' ', '_')
            .removePrefix("known_").removeSuffix("_host").removeSuffix("_server").removeSuffix("_site")
        return when (k) {
            "malware", "malware_distribution" -> R.string.rs_cat_malware
            "phishing" -> R.string.rs_cat_phishing
            "controller", "c2", "botnet_controller", "command_and_control" -> R.string.rs_cat_controller
            "orb" -> R.string.rs_cat_orb
            "scanner", "scanning" -> R.string.rs_cat_scanner
            "proxy", "open_proxy" -> R.string.rs_cat_proxy
            "rating_malicious", "malicious" -> R.string.rs_cat_rating_malicious
            "domain", "bad_domain" -> R.string.rs_cat_domain
            "user", "user_rule" -> R.string.rs_cat_user
            else -> null
        }
    }
}

/** Context under a destination: the owner line, the reverse name. Nothing when nothing is known. */
@Composable
internal fun ShieldContextLines(c: ShieldCtx?, host: String, color: Color, maxLines: Int = 2) {
    val line = ShieldContext.line(c)
    val rdns = ShieldContext.rdns(c, host)
    if (line == null && rdns == null) return
    Column {
        line?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium, color = color,
                maxLines = maxLines, overflow = TextOverflow.Ellipsis)
        }
        rdns?.let {
            Text(it, style = MaterialTheme.typography.labelSmall, color = color.copy(alpha = 0.8f),
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

internal object ShieldWords {
    fun verdict(ctx: Context, v: String): String = ctx.getString(when (v) {
        "blocked" -> R.string.rs_kind_flagged
        "suspicious" -> R.string.rs_suspicious
        "known" -> R.string.rs_known
        else -> R.string.rs_no_record
    })

    fun verdictColor(v: String): Color = when (v) {
        "blocked" -> Color(0xFFE5484D)
        "suspicious" -> Color(0xFFF08C2E)
        "known" -> Color(0xFF3B8EEA)
        else -> Color(0xFF1FA97C)
    }

    fun rank(v: String): Int = when (v) { "blocked" -> 0; "suspicious" -> 1; "known" -> 2; else -> 3 }

    fun classWord(ctx: Context, cls: String?): String? = when (cls) {
        "cloud" -> R.string.rs_class_cloud
        "cdn" -> R.string.rs_class_cdn
        "hosting" -> R.string.rs_class_hosting
        "isp" -> R.string.rs_class_isp
        "mobile" -> R.string.rs_class_mobile
        "edu" -> R.string.rs_class_edu
        "gov" -> R.string.rs_class_gov
        else -> null
    }?.let(ctx::getString)

    /** "DE" -> the flag emoji. */
    fun flag(cc: String?): String? {
        val c = cc?.uppercase(Locale.ROOT) ?: return null
        if (c.length != 2 || !c.all { it in 'A'..'Z' }) return null
        return String(Character.toChars(0x1F1E6 + (c[0] - 'A'))) + String(Character.toChars(0x1F1E6 + (c[1] - 'A')))
    }

    fun country(cc: String?): String? = cc?.takeIf { it.isNotEmpty() }?.let { Locale("", it).displayCountry.ifEmpty { it } }

    /** Amendment D: "<service or org> · <flag> · <class word>". */
    fun spaceFacts(ctx: Context, s: ShieldScore): String =
        listOfNotNull(s.service ?: s.org, flag(s.cc), classWord(ctx, s.cls)).filter { it.isNotEmpty() }.joinToString(" · ")

    /** Join the non-empty parts with " · ", each piece once ("CDN" as a label and a class reads twice otherwise). */
    fun uniqueLine(parts: List<String?>): String {
        val seen = HashSet<String>(); val out = ArrayList<String>()
        for (p in parts.filterNotNull()) for (piece in p.split(" · ")) if (piece.isNotEmpty() && seen.add(piece)) out += piece
        return out.joinToString(" · ")
    }

    fun facts(label: String?, service: String?, asname: String?, cc: String?): String =
        listOfNotNull(label, service ?: asname, country(cc)).filter { it.isNotEmpty() }.joinToString(" · ")

    fun bytes(ctx: Context, n: Long): String = android.text.format.Formatter.formatShortFileSize(ctx, n)

    fun ago(at: Long): String = DateUtils.getRelativeTimeSpanString(
        at, System.currentTimeMillis(), DateUtils.SECOND_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE,
    ).toString()
}

// ── Per-app attribution (Android only: iOS cannot know the app) ──────────────

internal data class AppLabel(val name: String, val icon: ImageBitmap?)

internal object AppLabels {
    private val cache = HashMap<Int, AppLabel?>()

    /** The app behind [uid]: its label and launcher icon, or null (system, unknown, not visible). */
    fun of(ctx: Context, uid: Int?): AppLabel? {
        if (uid == null || uid < 10_000) return null
        synchronized(cache) { if (cache.containsKey(uid)) return cache[uid] }
        val pm = ctx.packageManager
        val out = runCatching {
            val pkg = pm.getPackagesForUid(uid)?.firstOrNull() ?: return@runCatching null
            val ai = pm.getApplicationInfo(pkg, 0)
            val icon = runCatching { pm.getApplicationIcon(ai).toBitmap(96, 96).asImageBitmap() }.getOrNull()
            AppLabel(pm.getApplicationLabel(ai).toString(), icon)
        }.getOrNull()
        synchronized(cache) { cache[uid] = out }
        return out
    }
}

@Composable
internal fun AppIcon(app: AppLabel?, size: Dp = 28.dp) {
    val bmp = app?.icon ?: return
    Image(bmp, contentDescription = app.name, modifier = Modifier.size(size).clip(RoundedCornerShape(size * 0.24f)))
}

@Composable
internal fun rememberApp(uid: Int?): AppLabel? {
    val ctx = LocalContext.current
    return remember(uid) { AppLabels.of(ctx, uid) }
}

// ── Small drawn pieces ──────────────────────────────────────────────────────

/** A capsule: the verdict, Rare, Unusual, Blocked. */
@Composable
internal fun ShieldPill(text: String, color: Color) {
    val ink = liftText(MaterialTheme.colorScheme.surface, color, min = 4.5f)
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold,
        color = ink,
        maxLines = 1,
        overflow = TextOverflow.Clip,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(color.copy(alpha = 0.15f)).padding(horizontal = 7.dp, vertical = 2.dp),
    )
}

internal enum class ShieldGlyph { CHECK, OFF, FLAG, HAND, PULSE }

/**
 * Drawn rather than an icon font: material-icons-core has no shield, and
 * the extended set is megabytes. Scales with [size]; [color] is the ink.
 */
@Composable
internal fun ShieldIcon(glyph: ShieldGlyph, color: Color, size: Dp = 20.dp, cut: Color = MaterialTheme.colorScheme.surface) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width; val h = this.size.height
        fun shield(): Path = Path().apply {
            moveTo(w * 0.5f, h * 0.06f)
            lineTo(w * 0.86f, h * 0.2f)
            lineTo(w * 0.86f, h * 0.48f)
            cubicTo(w * 0.86f, h * 0.72f, w * 0.7f, h * 0.88f, w * 0.5f, h * 0.95f)
            cubicTo(w * 0.3f, h * 0.88f, w * 0.14f, h * 0.72f, w * 0.14f, h * 0.48f)
            lineTo(w * 0.14f, h * 0.2f)
            close()
        }
        val sw = w * 0.11f
        when (glyph) {
            ShieldGlyph.CHECK -> {
                drawPath(shield(), color)
                drawPath(Path().apply { moveTo(w * 0.33f, h * 0.5f); lineTo(w * 0.46f, h * 0.63f); lineTo(w * 0.68f, h * 0.38f) },
                    cut, style = Stroke(sw, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
            ShieldGlyph.OFF -> {
                drawPath(shield(), color, style = Stroke(w * 0.08f, join = StrokeJoin.Round))
                drawLine(color, Offset(w * 0.12f, h * 0.1f), Offset(w * 0.88f, h * 0.92f), strokeWidth = w * 0.08f, cap = StrokeCap.Round)
            }
            ShieldGlyph.FLAG -> {
                drawPath(shield(), color)
                drawLine(cut, Offset(w * 0.5f, h * 0.3f), Offset(w * 0.5f, h * 0.56f), strokeWidth = sw, cap = StrokeCap.Round)
                drawCircle(cut, radius = sw * 0.62f, center = Offset(w * 0.5f, h * 0.72f))
            }
            ShieldGlyph.HAND -> {
                drawCircle(color, radius = w * 0.42f, style = Stroke(w * 0.1f))
                drawLine(color, Offset(w * 0.22f, h * 0.22f), Offset(w * 0.78f, h * 0.78f), strokeWidth = w * 0.1f, cap = StrokeCap.Round)
            }
            ShieldGlyph.PULSE -> {
                drawPath(Path().apply {
                    moveTo(w * 0.04f, h * 0.55f); lineTo(w * 0.26f, h * 0.55f); lineTo(w * 0.38f, h * 0.2f)
                    lineTo(w * 0.56f, h * 0.85f); lineTo(w * 0.68f, h * 0.45f); lineTo(w * 0.96f, h * 0.45f)
                }, color, style = Stroke(w * 0.1f, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        }
    }
}

/** The glyph in a small tinted tile (the card's and the sheet's leading mark). */
@Composable
internal fun ShieldTile(on: Boolean, size: Dp = 36.dp) {
    val scheme = MaterialTheme.colorScheme
    val c = if (on) ShopColors.success else scheme.onSurfaceVariant
    Box(
        Modifier.size(size).clip(RoundedCornerShape(size * 0.3f)).background(c.copy(alpha = 0.14f)),
        contentAlignment = Alignment.Center,
    ) { ShieldIcon(if (on) ShieldGlyph.CHECK else ShieldGlyph.OFF, liftText(scheme.surface, c, 3f), size * 0.56f, cut = scheme.surface) }
}

internal fun noticeColor(k: ShieldNotice.Kind): Color = when (k) {
    ShieldNotice.Kind.BLOCKED -> Color(0xFFE5484D)
    ShieldNotice.Kind.FLAGGED -> Color(0xFFF08C2E)
    ShieldNotice.Kind.UNUSUAL -> Color(0xFF9B59D0)
}

internal fun noticeGlyph(k: ShieldNotice.Kind): ShieldGlyph = when (k) {
    ShieldNotice.Kind.BLOCKED -> ShieldGlyph.HAND
    ShieldNotice.Kind.FLAGGED -> ShieldGlyph.FLAG
    ShieldNotice.Kind.UNUSUAL -> ShieldGlyph.PULSE
}

internal fun noticeWord(ctx: Context, k: ShieldNotice.Kind): String = ctx.getString(when (k) {
    ShieldNotice.Kind.BLOCKED -> R.string.rs_kind_blocked
    ShieldNotice.Kind.FLAGGED -> R.string.rs_kind_flagged
    ShieldNotice.Kind.UNUSUAL -> R.string.rs_kind_unusual
})

/** Bidi-safe: hostnames and counts inside RTL text. */
internal fun ltr(s: String): String = "⁦$s⁩"


/** Brand track when on; when off a visible outline and thumb (the theme default is near-invisible on a white card). */
@Composable
internal fun shieldSwitchColors() = androidx.compose.material3.SwitchDefaults.colors(
    checkedTrackColor = com.rostamvpn.android.ui.theme.Brand1,
    checkedThumbColor = Color.White,
    uncheckedTrackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f),
    uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant,
    uncheckedBorderColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
)
