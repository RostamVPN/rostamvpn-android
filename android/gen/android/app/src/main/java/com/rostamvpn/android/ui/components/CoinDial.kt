package com.rostamvpn.android.ui.components

import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.booleanResource
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.rostamvpn.android.R
import com.rostamvpn.android.VpnViewModel.ConnectionState
import com.rostamvpn.android.ui.theme.legibleOn
import com.rostamvpn.android.ui.theme.liftOnto
import kotlin.math.cos
import kotlin.math.sin

/**
 * THE MILLED DIAL — the `value` home style's connect control.
 *
 * The third dial silhouette, and the reason there are three is that the owner's
 * complaint was that five apps looked like one app. Recolouring a shape does
 * not fix that; the shapes have to differ. So: rostam has a SOCKET (one thick
 * ring around a raised puck), RedVPN has a BAND STACK (four nested annuli), and
 * DollarVPN has a ring of TICKS. Those read as different objects across a room,
 * which is the bar, and none of them is reachable from a brand name — each is a
 * named composition a manifest selects.
 *
 * ── WHY TICKS, FOR THIS BRAND SPECIFICALLY ──────────────────────────────
 *
 * DollarVPN has no design package; unlike RedVPN there is nothing to reconcile
 * against, so the form has to be argued from what the brand IS. Two facts
 * decide it. Its mark is a COIN, and a coin's defining edge detail is milling —
 * the radial grooves cut into its rim. And its position is the value tier at
 * $0.99 (CLAUDE.md §22), whose whole pitch is how much you get for the money,
 * which is a GAUGE's job. Ticks are the one form that is both.
 *
 * It also solves a problem the other two dials do not have here. The coin is
 * already a full-bleed disc at `brand_mark_scale` 1.0, so anything drawn as a
 * solid ring around it makes a disc inside a disc: two concentric solid rounds
 * competing to be the object. Ticks are pierced, so the page shows between
 * them and the coin stays the only mass.
 *
 * ── STATE, IN FOUR CHANNELS ─────────────────────────────────────────────
 *
 *   DISCONNECTED  every tick dim. A milled edge at rest.
 *   CONNECTING    a COMET of lit ticks travels the rim. Position, not just
 *                 colour, so it survives greyscale.
 *   CONNECTED     every tick lit, at full length, plus a halo.
 *   FAILED        every tick lit in `error`, and SHORT — the ring visibly
 *                 contracts, so failure differs from success in size as well
 *                 as hue.
 *
 * Tick LENGTH is the channel that does the most work: dim/lit is a colour
 * difference, but short/long is a shape difference and reads with no colour
 * vision at all. Motion is gated on ANIMATOR_DURATION_SCALE like the other two
 * dials, and the still frame when animations are off is a complete one (a
 * static comet at the top), never a frozen tween.
 */

private val DIAL_SIZE = 252.dp

/** Page showing outside the tick ring. */
private val TICK_INSET = 6.dp

/** How far a tick reaches inward when lit, and when at rest. The rest length
 *  is deliberately a little over half: a milled edge should be legible as a
 *  texture before anything is connected, or the dial has no resting form. */
private val TICK_LEN_LIT = 15.dp
private val TICK_LEN_REST = 9.dp
private val TICK_LEN_FAILED = 7.dp

private val TICK_WIDTH = 2.5.dp

/** 48 grooves. Enough to read as milling rather than as a dashed line, few
 *  enough that each is a distinct mark at 252dp rather than a solid ring. */
private const val TICK_COUNT = 48

/** Page showing between the ticks and the coin. */
private val COIN_GAP = 10.dp

/** The power glyph, for a `value` brand with no mark of its own. */
private val GLYPH_SIZE = 72.dp

@Composable
fun CoinDial(
    state: ConnectionState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isConnecting = state is ConnectionState.Connecting
    val isConnected = state is ConnectionState.Connected
    val isFailed = state is ConnectionState.Failed

    val scheme = MaterialTheme.colorScheme
    val animate = rememberCoinMotionScale() > 0f

    // `brand_ring` already exists and already means "the colour of this brand's
    // connect indicator when it should not be primary" — DollarVPN points it at
    // a yellow gold (#AD8510) precisely because its mark is a gold coin and the
    // primary is a dark green the coin has nothing to do with. Reusing it here
    // rather than inventing a `brand_tick` keeps one knob for one idea.
    //
    // Then held to 3:1 against the page, which is the WCAG 1.4.11 floor for a
    // non-text graphic: legibleOn falls back to the neutral if a brand picks a
    // gold nobody can see, and liftOnto rescues the dark theme, where #AD8510
    // is fine but a darker brand gold would not be.
    val tickInk = liftOnto(
        scheme.background,
        legibleOn(scheme.background, colorResource(R.color.brand_ring), scheme.onSurfaceVariant, min = 3f),
        min = 3f,
        toward = if (scheme.background.luminance() < 0.5f) Color.White else scheme.onSurface,
    )
    val restAlpha = run {
        val ctx = LocalContext.current
        remember(ctx) {
            val tv = android.util.TypedValue()
            ctx.resources.getValue(R.dimen.brand_ring_rest_alpha, tv, true)
            tv.float
        }
    }
    val activeInk = if (isFailed) scheme.error else tickInk

    // 0 = resting rim, 1 = fully lit.
    val litLevel by animateFloatAsState(
        targetValue = if (isConnected) 1f else 0f,
        animationSpec = tween(700, easing = FastOutSlowInEasing),
        label = "tickLit",
    )
    // The travelling comet. Position in turns, 0..1.
    val cometAt = if (isConnecting && animate) {
        val t = rememberInfiniteTransition(label = "comet")
        val v by t.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(1400, easing = LinearEasing)),
            label = "cometPos",
        )
        v
    } else if (isConnecting) {
        0f   // animations off: a still comet at the top is a complete frame
    } else {
        null
    }

    val haloFade by animateFloatAsState(
        targetValue = if (isConnected) 1f else 0f,
        animationSpec = tween(700),
        label = "haloFade",
    )
    val haloAlpha = if (isConnected && animate) {
        val t = rememberInfiniteTransition(label = "halo")
        val a by t.animateFloat(
            initialValue = 0.10f,
            targetValue = 0.22f,
            animationSpec = infiniteRepeatable(
                tween(2600, easing = LinearEasing), androidx.compose.animation.core.RepeatMode.Reverse,
            ),
            label = "haloAlpha",
        )
        a
    } else {
        0.16f
    }

    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (pressed) 0.955f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow,
        ),
        label = "pressScale",
    )
    val elevation by animateDpAsState(
        targetValue = when {
            pressed -> 1.dp
            isConnected -> 10.dp
            else -> 6.dp
        },
        animationSpec = tween(180),
        label = "coinElevation",
    )
    var previousState by remember { mutableStateOf<ConnectionState>(ConnectionState.Disconnected) }
    val arrival = remember { Animatable(1f) }
    LaunchedEffect(state) {
        if (state is ConnectionState.Connected && previousState is ConnectionState.Connecting) {
            arrival.animateTo(1.06f, tween(160, easing = FastOutSlowInEasing))
            arrival.animateTo(
                1f,
                spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessMedium,
                ),
            )
        }
        previousState = state
    }

    val label = stringResource(
        when (state) {
            is ConnectionState.Connected -> R.string.tap_to_disconnect
            is ConnectionState.Connecting -> R.string.tap_to_cancel
            else -> R.string.tap_to_connect
        }
    )

    val hasMascot = booleanResource(R.bool.brand_has_mascot)
    val markScale = run {
        val ctx = LocalContext.current
        remember(ctx) {
            val tv = android.util.TypedValue()
            ctx.resources.getValue(R.dimen.brand_mark_scale, tv, true)
            tv.float
        }
    }
    val coinSize = DIAL_SIZE - (TICK_INSET + TICK_LEN_LIT + COIN_GAP) * 2
    val markSize = coinSize * markScale

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(DIAL_SIZE)
            .scale(pressScale * arrival.value)
            .clickable(
                interactionSource = interaction,
                indication = ripple(bounded = false, radius = DIAL_SIZE / 2, color = activeInk),
                onClick = onClick,
            )
            .semantics {
                contentDescription = label
                role = Role.Button
            },
    ) {
        // ── LAYER 1: halo ──
        Canvas(modifier = Modifier.fillMaxSize()) {
            if (haloFade > 0.01f) {
                val outer = size.minDimension / 2f
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            activeInk.copy(alpha = haloAlpha * haloFade),
                            activeInk.copy(alpha = haloAlpha * haloFade * 0.45f),
                            Color.Transparent,
                        ),
                        center = center,
                        radius = outer,
                    ),
                    radius = outer,
                    center = center,
                )
            }
        }

        // ── LAYER 2: the milled rim ──
        Canvas(modifier = Modifier.fillMaxSize()) {
            val outerR = size.minDimension / 2f - TICK_INSET.toPx()
            val w = TICK_WIDTH.toPx()
            val lenLit = TICK_LEN_LIT.toPx()
            val lenRest = TICK_LEN_REST.toPx()
            val lenFail = TICK_LEN_FAILED.toPx()
            val c = center

            for (i in 0 until TICK_COUNT) {
                val turn = i.toFloat() / TICK_COUNT
                // -90 so tick 0 is at 12 o'clock, which is where the comet
                // parks when animations are off.
                val rad = Math.toRadians((turn * 360f - 90f).toDouble())
                val cosA = cos(rad).toFloat()
                val sinA = sin(rad).toFloat()

                // How lit is THIS tick? Connected lights all of them; connecting
                // lights a short trailing comet; otherwise none.
                val lit = when {
                    isFailed -> 0f
                    cometAt != null -> {
                        // Distance behind the comet head, wrapped to 0..1.
                        var d = cometAt - turn
                        if (d < 0f) d += 1f
                        // A tail of ~1/4 turn, falling off to nothing.
                        (1f - (d / 0.25f)).coerceIn(0f, 1f)
                    }
                    else -> litLevel
                }

                val len = when {
                    isFailed -> lenFail
                    else -> lenRest + (lenLit - lenRest) * lit
                }
                val alpha = when {
                    isFailed -> 0.85f
                    else -> restAlpha + (1f - restAlpha) * lit
                }
                val start = Offset(c.x + cosA * outerR, c.y + sinA * outerR)
                val end = Offset(c.x + cosA * (outerR - len), c.y + sinA * (outerR - len))
                drawLine(
                    color = activeInk.copy(alpha = alpha),
                    start = start,
                    end = end,
                    strokeWidth = w,
                    cap = StrokeCap.Round,
                )
            }
        }

        // ── LAYER 3: the coin ──
        //
        // `surface` rather than a literal: the coin is a full-bleed raster at
        // markScale 1.0, so this disc is almost entirely covered and exists for
        // its SHADOW — the edge that makes the coin sit in the page rather than
        // on it. A brand on `value` with a smaller mark would see it properly.
        Box(
            modifier = Modifier
                .size(coinSize)
                .shadow(elevation, CircleShape)
                .background(scheme.surface, CircleShape),
        )

        if (hasMascot) {
            Image(
                painter = painterResource(id = R.drawable.brand_mark),
                contentDescription = null,
                modifier = Modifier.size(markSize),
                contentScale = ContentScale.Fit,
            )
        } else {
            Icon(
                painter = painterResource(id = R.drawable.ic_power),
                contentDescription = null,
                tint = if (isFailed) scheme.error else scheme.primary,
                modifier = Modifier.size(GLYPH_SIZE),
            )
        }
    }
}

@Composable
private fun rememberCoinMotionScale(): Float {
    val ctx = LocalContext.current
    return remember(ctx) {
        runCatching {
            Settings.Global.getFloat(
                ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f,
            )
        }.getOrDefault(1f)
    }
}
