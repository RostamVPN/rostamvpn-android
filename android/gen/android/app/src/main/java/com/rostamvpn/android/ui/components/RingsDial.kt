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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawscope.Stroke
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
import com.rostamvpn.android.ui.theme.liftOnto

/**
 * THE CONCENTRIC BAND DIAL — the `rings` home style's connect control.
 *
 * A SECOND dial, deliberately, rather than a set of switches inside
 * [PowerButton]. The classic dial is a socket-and-disc: one ring, one raised
 * puck, state carried by the ring's sweep and the disc's fill. This is a stack
 * of nested annuli that charge from the centre outward. They are different
 * objects, not the same object with different numbers, and trying to express
 * both from one body of code would have meant editing the control that 691K
 * rostam installs depend on in order to ship a second brand's look. That trade
 * is never worth it: PowerButton.kt is untouched by this work, so rostam's
 * dial cannot have regressed, and the on-device pixel diff confirms it did not.
 *
 * ── EVERY NUMBER HERE WAS MEASURED, NOT CHOSEN ──────────────────────────
 *
 * Sampled from the brand's own design package, `4 Free Home Screen On.png`
 * (~/code/appdev/graphics-redvpn), by scanning a horizontal line through the
 * dial's centre and reading the band boundaries off the pixels:
 *
 *   white core        r = 294px          294/550 = 0.535 of the dial radius
 *   band 4 (deepest)  r = 294 -> 358px   each band exactly 64px thick,
 *   band 3            r = 358 -> 422px   i.e. 0.116 of the radius,
 *   band 2            r = 422 -> 486px   four of them, no gaps
 *   band 1 (palest)   r = 486 -> 550px
 *
 * And the fills are ONE COLOUR at four opacities. Every sampled band solves
 * exactly for `accentOnLight` (#BB0008) composited over white:
 *
 *   #BB0008 -> alpha 1.00      #C1171E -> alpha 0.91
 *   #D04D52 -> alpha 0.69      #E4999C -> alpha 0.40
 *
 * That matters beyond fidelity: the whole stack derives from ONE existing
 * palette token, so a sixth brand selecting `rings` gets a correct band stack
 * in its own colour with nothing to author. Had these been five hex literals
 * they would have been RedVPN's forever.
 *
 * ── WHY THE CORE STAYS WHITE IN EVERY STATE ─────────────────────────────
 *
 * The designer's OFF frame puts the shield on a DARK CHARCOAL disc. Measured
 * against the real artwork (brands/assets/redvpn/mark.png composited per
 * pixel), that lands at a median 2.58:1 with only 18% of the mark clearing the
 * 3:1 that WCAG 1.4.11 asks of a non-text graphic. On white the same pixels
 * measure a median 5.49:1 with 100% clearing it. So the charcoal is not
 * followed, and this is the one place the design file is overruled.
 *
 * Nothing is lost by it, because the charcoal was never the state signal. The
 * RINGS are: outline when off, filled when on. Keeping the disc constant
 * actually sharpens that — one variable instead of two.
 *
 * ── STATE IS SHAPE FIRST, THEN COLOUR, THEN MOTION ──────────────────────
 *
 * Colour alone fails for colourblind users and in bright sun, and this app is
 * used outdoors by people in a hurry:
 *
 *   DISCONNECTED  four HAIRLINE OUTLINES, nothing filled.
 *   CONNECTING    bands fill from the centre outward, cycling — the dial
 *                 visibly charges.
 *   CONNECTED     all four filled, at the measured opacity ramp.
 *   FAILED        four outlines again in `error`, and thicker. Outline says
 *                 "not connected" in the same language OFF does; the colour
 *                 says why. A filled error state would have said "connected,
 *                 but red".
 *
 * MOTION is gated on ANIMATOR_DURATION_SCALE exactly as PowerButton is, and
 * the resting frame with animations off is COMPLETE rather than half-drawn:
 * connecting settles at two filled bands, which is a legible steady state, not
 * a frozen tween.
 */

/** MEASURED, like everything else here. The design draws the dial at 1100px
 *  across a 1500px artboard, i.e. 73% of the screen width. The test device is
 *  1080x2340 at 450dpi (2.8125x), so its 384dp of width puts the design's dial
 *  at 280dp.
 *
 *  260dp SHIPS, not 280, and the difference is the one thing the design file
 *  could not know: its screen has no data meter and no footer. Ours has both,
 *  plus the pill and the region card, and the vertical budget on this device
 *  works out at roughly 700dp of content for a ~322dp lower stack — so a
 *  280dp dial plus its 18dp gap plus the 104dp caption reservation came to
 *  402dp against ~378dp available and CLIPPED THE TRAFFIC ROW off the bottom
 *  of the connected screen. Caught on device; it is invisible in source and
 *  invisible in the disconnected state, because the row only exists while
 *  connected.
 *
 *  Still WIDER than the classic dial's 248dp, and that part is deliberate:
 *  the classic dial is a solid 184dp puck inside a 14dp ring and is heavy at
 *  any size, while this one is a white core inside four HAIRLINES when
 *  disconnected. At 248dp it read as a faint ripple rather than as the
 *  primary control on the screen. */
/** The width the band radii were MEASURED on (the test device, 1080px @ ~2.75x). */
private val DIAL_BASE = 260.dp
private const val DIAL_BASE_WIDTH_DP = 393f

/**
 * 🚨 A FIXED dial shrinks as phones grow. 260dp is 66% of a 393dp screen and
 * only 59% of a 440pt iPhone 17 Pro Max, which put the mark at 35.4% of the
 * width on the test device and 31.5% on a Pro Max — measured, and it is what
 * "why is the logo so small" was seeing on iOS. Android carries the same
 * constant and has the same behaviour on a wide device; both now scale.
 *
 * Derived from the measurement (260/393), not chosen, so a 393dp screen renders
 * byte-identically to what shipped. Clamped so this cannot become the opposite
 * bug on a small phone or a tablet.
 *
 * ⚠️ Kept in lockstep with iOS `RingsDial.dialSize`.
 */
private val DIAL_MIN = 248.dp
private val DIAL_MAX = 320.dp

@Composable
private fun dialSize(): androidx.compose.ui.unit.Dp {
    val w = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp.toFloat()
    val scaled = DIAL_BASE * (w / DIAL_BASE_WIDTH_DP)
    return scaled.coerceIn(DIAL_MIN, DIAL_MAX)
}

/** Fractions of the dial RADIUS, measured off the design file (see above). */
private const val CORE_FRACTION = 0.535f
private const val BAND_FRACTION = 0.116f
private const val BAND_COUNT = 4

/** Inner -> outer. The measured opacity ramp of `accentOnLight` over white. */
private val BAND_ALPHA = floatArrayOf(1.00f, 0.91f, 0.69f, 0.40f)

/** The OFF outlines. Faint, but a real echo of the radii that will fill —
 *  the designer's own #E0E0E0 grey measures 1.2:1 on white and reads as a
 *  printing artefact rather than a control, so this uses the brand colour at
 *  low opacity instead: same hue as the filled state, unmistakably the same
 *  four rings. */
private val REST_ALPHA = floatArrayOf(0.46f, 0.37f, 0.28f, 0.19f)

private val OUTLINE_STROKE = 2.dp
private val FAILED_STROKE = 2.5.dp

/** The power glyph, for a `rings` brand with no mark of its own. */
private val GLYPH_SIZE = 64.dp

@Composable
fun RingsDial(
    state: ConnectionState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isConnecting = state is ConnectionState.Connecting
    val isConnected = state is ConnectionState.Connected
    val isFailed = state is ConnectionState.Failed

    val scheme = MaterialTheme.colorScheme
    val animate = rememberRingsMotionScale() > 0f

    // ONE token drives the whole stack. `brand_ring` already exists and already
    // defaults to `primary`, but RedVPN's design uses its DEEP red for the
    // bands and its bright red for the wordmark, and those are different
    // palette roles — so the band colour is accentOnLight, read through the
    // generated brand colour rather than written here as a hex.
    val bandColor = colorResource(R.color.brand_band)

    // ── THE DIAL IS SELF-CONTAINED, AND THAT IS A DARK-MODE FIX ──
    //
    // The measured opacity ramp (1.00 / 0.91 / 0.69 / 0.40) was read off a
    // design drawn on WHITE, and alpha is not a colour: over a light page it
    // fades the bands toward white as intended, and over a #121212 page it
    // fades them toward BLACK. The first dark build inverted the whole stack —
    // the ramp ran the wrong way and the outer bands disappeared into the
    // page. Same class of bug as a colour defined only inside one branch.
    //
    // So the ramp is composited ONCE, here, against the ground it was measured
    // on, and the bands are drawn OPAQUE. They are now the exact sampled hexes
    // (#BB0008, #C1171E, #D04D52, #E4999C for RedVPN) in every theme, and the
    // dial reads identically on any page — which is also what the design file
    // shows it doing.
    val coreGround = Color.White
    val bandFill = remember(bandColor) {
        List(BAND_COUNT) { i ->
            bandColor.copy(alpha = BAND_ALPHA[i]).compositeOver(coreGround)
        }
    }
    // The OUTLINES are the opposite case: they are strokes ON the page, so they
    // must contrast with the page rather than with the core. #BB0008 at 0.46
    // over a dark page is ~1.4:1 and simply is not there. liftOnto keeps the
    // hue and spends only the saturation legibility costs.
    val outlineInk = liftOnto(scheme.background, bandColor, min = 3f)

    // How many bands are filled, as a continuous value so a band can be
    // partially lit. 0 = empty socket, 4 = closed.
    val fillTarget = when {
        isConnected -> BAND_COUNT.toFloat()
        isConnecting -> if (animate) 0f else 2f   // still frame when motion is off
        else -> 0f
    }
    val settled by animateFloatAsState(
        targetValue = fillTarget,
        animationSpec = tween(700, easing = FastOutSlowInEasing),
        label = "bandFill",
    )
    // Connecting CHARGES: the fill sweeps out from the centre and restarts, so
    // the dial reads as working rather than as a stuck partial state.
    val charging = if (isConnecting && animate) {
        val t = rememberInfiniteTransition(label = "charge")
        val v by t.animateFloat(
            initialValue = 0f,
            targetValue = BAND_COUNT.toFloat(),
            animationSpec = infiniteRepeatable(tween(1500, easing = LinearEasing)),
            label = "chargeLevel",
        )
        v
    } else {
        null
    }
    val fill = charging ?: settled

    // ── Touch and arrival, matched to the classic dial ──
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
        label = "coreElevation",
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
    val dialSize = dialSize()
    val coreSize = dialSize * CORE_FRACTION          // core DIAMETER = 2 * r_core
    val markSize = coreSize * markScale

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(dialSize)
            .scale(pressScale * arrival.value)
            .clickable(
                interactionSource = interaction,
                indication = ripple(bounded = false, radius = dialSize / 2, color = outlineInk),
                onClick = onClick,
            )
            .semantics {
                contentDescription = label
                role = Role.Button
            },
    ) {
        // ── LAYER 1: the band stack ──
        Canvas(modifier = Modifier.fillMaxSize()) {
            val r = size.minDimension / 2f
            val core = r * CORE_FRACTION
            val band = r * BAND_FRACTION
            val c = center

            // Painted OUTERMOST FIRST so the inner, denser bands land on top and
            // each boundary is a clean edge rather than a seam between two
            // translucent fills.
            for (i in BAND_COUNT - 1 downTo 0) {
                val outer = core + band * (i + 1)
                when {
                    isFailed -> drawCircle(
                        color = scheme.error.copy(alpha = REST_ALPHA[i] * 2.2f),
                        radius = outer - band / 2f,
                        center = c,
                        style = Stroke(FAILED_STROKE.toPx()),
                    )
                    // Filled: this band is fully lit when `fill` has passed it,
                    // and fades in across the one it is currently on.
                    fill > i -> {
                        val lit = (fill - i).coerceIn(0f, 1f)
                        drawCircle(
                            color = bandFill[i].copy(alpha = lit),
                            radius = outer,
                            center = c,
                        )
                        // The unlit remainder keeps its outline, so a partially
                        // charged dial still shows all four radii.
                        if (lit < 1f) {
                            drawCircle(
                                color = outlineInk.copy(alpha = REST_ALPHA[i]),
                                radius = outer - band / 2f,
                                center = c,
                                style = Stroke(OUTLINE_STROKE.toPx()),
                            )
                        }
                    }
                    else -> drawCircle(
                        color = outlineInk.copy(alpha = REST_ALPHA[i]),
                        radius = outer - band / 2f,
                        center = c,
                        style = Stroke(OUTLINE_STROKE.toPx()),
                    )
                }
            }
        }

        // ── LAYER 2: the core, white in every state (see the header) ──
        Box(
            modifier = Modifier
                .size(coreSize)
                .shadow(elevation, CircleShape)
                // WHITE, not `surface`, in both themes. The mark is a fixed
                // asset with no dark variant — the manifest records that there
                // is no white-knockout version of the shield anywhere in the
                // design package — so the disc under it has to be the ground it
                // was drawn for. On `surface` the dark theme resolves #1E1E1E
                // and the shield lands at the SAME 2.58:1 that got the
                // designer's charcoal rejected in light mode. A brand whose
                // mark is dark-safe would want this configurable; none is.
                .background(coreGround, CircleShape),
        )

        // ── LAYER 3: the mark ──
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
                // On the white core, never on a brand fill, so the ordinary
                // brand colour is safe here — but still routed through the
                // scheme rather than a literal.
                tint = if (isFailed) scheme.error else scheme.primary,
                modifier = Modifier.size(GLYPH_SIZE),
            )
        }
    }
}

/** See PowerButton's copy of this: `rememberInfiniteTransition` does not read
 *  ANIMATOR_DURATION_SCALE, so the repeating effects are gated by hand. */
@Composable
private fun rememberRingsMotionScale(): Float {
    val ctx = LocalContext.current
    return remember(ctx) {
        runCatching {
            Settings.Global.getFloat(
                ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f,
            )
        }.getOrDefault(1f)
    }
}
