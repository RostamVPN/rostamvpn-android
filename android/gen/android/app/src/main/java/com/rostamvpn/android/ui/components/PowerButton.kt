package com.rostamvpn.android.ui.components

import android.provider.Settings
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.booleanResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.rostamvpn.android.R
import com.rostamvpn.android.VpnViewModel.ConnectionState
import com.rostamvpn.android.ui.theme.DarkBase
import com.rostamvpn.android.ui.theme.legibleOn
import com.rostamvpn.android.ui.theme.mostLegibleOn

/**
 * THE CONNECT DIAL — the one thing this app is for.
 *
 * It replaces a 120dp hairline ring that measured lighter than the cards
 * beneath it, so the primary action of the product was the third thing the
 * eye found, after a mascot and a word. Three ideas, in order of importance:
 *
 * 1. IT IS THE LARGEST OBJECT ON THE SCREEN. Nothing else may out-weigh it.
 *    A 216dp socket around a raised 152dp disc is roughly six times the area
 *    of the button it replaces and twice the area of anything else on the
 *    page.
 *
 * 2. STATE IS SHAPE AND COLOUR, NOT TEXT. "VPN On" is a word, and this app
 *    ships in 38 languages to people reading it in a hurry. The ring is the
 *    state: EMPTY when off, a SWEEPING ARC while connecting, CLOSED when
 *    connected — a complete circle for a completed connection — and a closed
 *    ring in `error` when it failed. The disc reinforces it: a white puck
 *    with a brand-coloured glyph when off, a solid brand-coloured disc with
 *    an inverted glyph when on. Across a room that is a white circle versus
 *    a saturated one, with no reading involved.
 *
 * 3. IT ANSWERS THE FINGER. A press scales it to 0.955 and drops its
 *    elevation on a spring, immediately, before any network work starts.
 *    Arrival springs the whole dial once as the ring closes.
 *
 * MULTI-BRAND. Not one colour literal here: every value is a `colorScheme`
 * role, and every foreground drawn ON a brand colour is chosen by measured
 * contrast (`ui/theme/Color.kt`). That is load-bearing, not fastidiousness —
 * RedVPN's `primary` is 1.83:1 on its own dark page, so a ring hardcoded to
 * `primary` would be invisible for the brand with 63.5K installs while
 * looking perfect for the brand being developed against.
 *
 * MOTION. Only the connecting sweep and the connected halo repeat, and both
 * are switched off when ANIMATOR_DURATION_SCALE is 0 (developer options, or
 * Accessibility -> Remove animations). The resting frame is COMPLETE in that
 * case, never a half-drawn one: connecting still shows a partial arc, just a
 * still one, and connected still shows its halo at a fixed strength. Every
 * other transition is a `tween`, which Compose already collapses to zero
 * duration at that setting.
 */

/** Overall footprint. Also the tap target — generous on purpose.
 *
 *  248dp, up from 216. The mascot used to sit ABOVE this as an 84dp crest and
 *  the two were separate masses competing to be the visual anchor; the mascot
 *  is now the face INSIDE the dial, so the crest is gone and the dial takes the
 *  86dp it freed. Net effect on the brand is the opposite of a demotion: the
 *  mascot renders at 132dp here against 84dp as a crest, so Rostam is 57%
 *  larger than before AND is now the thing you press. */
private val DIAL_SIZE = 248.dp

/** The socket: thick enough to read as a collar, not a hairline. */
private val RING_STROKE = 14.dp

/** Page showing between the box edge and the outside of the ring. */
private val RING_INSET = 10.dp

/** Page showing between the inside of the ring and the disc — the gap is
 *  what makes the disc read as sitting IN something rather than ON it. */
private val RING_GAP = 8.dp

private val DISC_SIZE = DIAL_SIZE - (RING_INSET + RING_STROKE + RING_GAP) * 2  // 184dp

/** The power glyph, for the four brands that have no mascot. */
private val GLYPH_SIZE = 76.dp

/** The brand mark, for the one brand that has one.
 *
 *  LARGER THAN THE DISC, deliberately, and that is the whole trick. The mark's
 *  circular aperture is 880 of its 1040 viewport units wide, so drawing it at
 *  1040/880 x DISC_SIZE makes the aperture land EXACTLY on the disc — and the
 *  horns, which sit outside that aperture, then rise past the disc edge on
 *  their own.
 *
 *  Derived rather than written as a number so it cannot drift from the
 *  drawable: change the aperture in brand_mark.xml and this follows. Writing
 *  the rounded literal instead leaves the crop circle a fraction wider than the
 *  disc, which shows as a sliver of face outside the fill.
 *
 *  Do not "tidy" this to DISC_SIZE. Matching it would shrink the aperture
 *  inside the disc and leave a ring of bare fill around the face, which is the
 *  pasted-in look this replaced. */
/** Rostam's own ratio, kept as the documented derivation. Other brands read
 *  `brand_mark_scale` from resources; see [markSize]. */
private val ROSTAM_MARK_SIZE = DISC_SIZE * (MARK_VIEWPORT / (APERTURE_R * 2f))

/**
 * How large to draw the brand mark, per brand.
 *
 * `brand_mark_scale` is generated into each brand's `brand_mascot.xml` from
 * `brands/<id>.json`. 1.0 fills the disc, which is right for a mark that IS a
 * disc: DollarVPN's coin, a roundel, a badge. Rostam is the exception at
 * 1040/880, because its artwork has a circular aperture that must land exactly
 * on the disc while the horns escape it.
 *
 * This was a hardcoded constant, so DollarVPN's coin would have rendered at
 * 217dp inside a 184dp disc with its rim clipped off.
 */
@Composable
private fun markSize(): androidx.compose.ui.unit.Dp {
    // dimensionResource() cannot read an <item format="float">, so the value is
    // read through the raw Resources, which is the documented way.
    val ctx = LocalContext.current
    val v = remember(ctx) {
        val tv = android.util.TypedValue()
        ctx.resources.getValue(R.dimen.brand_mark_scale, tv, true)
        tv.float
    }
    return DISC_SIZE * v
}

/** brand_mark.xml's own numbers. Read them off the drawable, never guess:
 *  viewport 1040, aperture `M66,555 A440,440 …` -> centre (506,555) r440. */
private const val MARK_VIEWPORT = 1040f
private const val APERTURE_CX = 506f
private const val APERTURE_CY = 555f
private const val APERTURE_R = 440f

/* NO COMPOSE OFFSET HERE, AND DO NOT ADD ONE.
 *
 * brand_mark.xml already carries `<group android:translateX="14"
 * android:translateY="-35">` around the whole drawing (line 44), which moves
 * the aperture from (506,555) to (520,520) -- the exact centre of the 1040
 * viewport. So centring the Image by its bounding box centres the APERTURE on
 * the disc, which is what we want.
 *
 * I briefly added a MARK_DX/MARK_DY offset here, derived from the raw
 * `<clip-path>` coordinates, having read those without noticing the group
 * transform above them. That applied the same (14,-35) a SECOND time and the
 * logo sat visibly off inside the ring. iOS gets this right by applying the
 * identical translate once, in RostamBrandMark.swift:42.
 *
 * If you ever need to check the aperture's real position, read it AFTER the
 * group transform, not from the clip-path alone. */

@Composable
fun PowerButton(
    state: ConnectionState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isConnecting = state is ConnectionState.Connecting
    val isConnected = state is ConnectionState.Connected
    val isFailed = state is ConnectionState.Failed

    val scheme = MaterialTheme.colorScheme
    val animate = rememberMotionScale() > 0f

    // The ring is a non-text graphic, so 3:1 is the bar (WCAG 1.4.11). Brand
    // colour where it clears it; the neutral where it does not.
    // brand_ring, not scheme.primary. Defaults to primary for every brand, so
    // nothing changes unless a brand asks: DollarVPN points it at its gold so
    // the ring belongs to the coin instead of contrasting with it. Still run
    // through legibleOn, so a brand that picks something unreadable gets the
    // neutral rather than an invisible state indicator.
    val ringInk = legibleOn(
        scheme.background,
        androidx.compose.ui.res.colorResource(R.color.brand_ring),
        scheme.onSurfaceVariant,
        min = 3f,
    )
    val restAlpha = run {
        val ctx = LocalContext.current
        remember(ctx) {
            val tv = android.util.TypedValue()
            ctx.resources.getValue(R.dimen.brand_ring_rest_alpha, tv, true)
            tv.float
        }
    }
    val activeRing = if (isFailed) scheme.error else ringInk

    // ── Disc fill: the loudest state signal on the screen ──
    //
    // …FOR THE BRANDS WHOSE MARK SURVIVES IT. The disc turning `primary` puts
    // the brand mark ON the brand colour, which only works while the two
    // contrast. Rostam's mark does (gold horns and a dark beard on teal).
    // RedVPN's does not: its mark is a red shield, and composited over its own
    // `primary` the artwork measures 1.04–1.47:1 against the 3:1 WCAG 1.4.11
    // asks of a non-text graphic — so the logo dissolved into the button at the
    // one moment the button matters. On `surface` the same pixels measure
    // 4.46–6.84:1.
    //
    // `brand_disc_fills_on_connect` (generated from brands/<id>.json, DEFAULT
    // TRUE) is a BOOLEAN and not a colour on purpose. `primary` and `surface`
    // are roles that already differ between light and dark; a literal hex here
    // would pin the connected disc to one theme and quietly change every
    // brand's dark mode. As a boolean, every brand that does not ask evaluates
    // the identical expression it does today, in both themes.
    //
    // When false the disc is `surface` in ALL THREE states and the ring carries
    // connect state by itself — empty socket, rotating arc, closed circle plus
    // halo. That is not a downgrade invented here: it is what RedVPN's own
    // designer drew. Every asset in the design package puts the shield on white,
    // the ON-state component included, with the red moved into concentric rings
    // around the disc.
    val discFillsOnConnect = booleanResource(R.bool.brand_disc_fills_on_connect)
    val discOn = if (discFillsOnConnect) scheme.primary else scheme.surface

    val discFill by animateColorAsState(
        targetValue = when {
            isConnected -> discOn
            // A hint of the destination colour while it is being reached.
            // Derived from `discOn`, not from `primary`, so it can never end up
            // MORE saturated than the state it is heading towards: on a brand
            // whose disc stays white, a 10% primary wash here would make
            // "connecting" pinker than "connected" and read as going backwards.
            isConnecting -> discOn.copy(alpha = 0.10f).compositeOver(scheme.surface)
            else -> scheme.surface
        },
        // 200ms, NOT the 450 this first shipped with. The disc fill and the
        // glyph tint move in OPPOSITE directions across the same interval —
        // white -> brand under brand -> white — so at the midpoint both are
        // the same mid-grey and the glyph vanishes into the disc. Caught on
        // device: the arrival frame was a muddy puck with no symbol on it.
        // The dip is unavoidable in a colour cross-fade, so the fix is to
        // make it too short to see. The ARRIVAL is still slow and legible;
        // it is carried by the ring closing (700ms), the halo, the spring
        // and the page wash, none of which have this problem.
        animationSpec = tween(200, easing = FastOutSlowInEasing),
        label = "discFill",
    )

    val glyphTint by animateColorAsState(
        targetValue = when {
            // On the filled disc the glyph sits on the BRAND colour, where
            // neither white nor ink is right for every brand: white is 5.95:1
            // on Rostam's teal and 4.66:1 on RedVPN's red, but only 3.23:1 on
            // Rostam's lighter dark-mode tone, where ink wins instead.
            //
            // Measured against `discOn`, the colour the disc ACTUALLY takes,
            // not against `primary`. They are the same thing for every brand
            // that fills on connect; for one that does not they are white and
            // white, and asking `primary` here would paint a white glyph onto a
            // white disc. No brand hits that today (the only brand with the flag
            // off also has a mascot, so it takes the branch below), but the two
            // settings are independent and the invisible-glyph combination must
            // not be reachable.
            isConnected -> mostLegibleOn(discOn, Color.White, DarkBase)
            isFailed -> scheme.error
            // On the white disc, brand colour unless this brand cannot afford
            // it (RedVPN dark: 1.63:1 on #1E1E1E).
            else -> legibleOn(scheme.surface, scheme.primary, scheme.onSurface)
        },
        // Matched to the disc, for the reason documented above it.
        animationSpec = tween(200, easing = FastOutSlowInEasing),
        label = "glyphTint",
    )

    // ── The ring IS the state ──
    // 0 = open socket, 96 = working, 360 = closed circuit. Animated, so
    // arriving at Connected is visibly the ring CLOSING rather than a swap.
    val sweep by animateFloatAsState(
        targetValue = when {
            isConnected || isFailed -> 360f
            isConnecting -> 96f
            else -> 0f
        },
        animationSpec = tween(700, easing = FastOutSlowInEasing),
        label = "ringSweep",
    )

    val rotation = if (isConnecting && animate) {
        val t = rememberInfiniteTransition(label = "sweepRotation")
        val r by t.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(tween(1150, easing = LinearEasing)),
            label = "rotation",
        )
        r
    } else {
        0f
    }

    // ── Connected halo ──
    val haloFade by animateFloatAsState(
        targetValue = if (isConnected) 1f else 0f,
        animationSpec = tween(700),
        label = "haloFade",
    )
    val haloAlpha = if (isConnected && animate) {
        val t = rememberInfiniteTransition(label = "halo")
        val a by t.animateFloat(
            initialValue = 0.10f,
            targetValue = 0.24f,
            animationSpec = infiniteRepeatable(
                tween(2600, easing = LinearEasing), RepeatMode.Reverse,
            ),
            label = "haloAlpha",
        )
        a
    } else {
        // Animations off: the halo is still THERE, just still. A resting
        // state that is missing a layer is a different design, not a calmer
        // one.
        0.17f
    }

    // ── The face in the disc ──
    //
    // WHICH face is a property of the BRAND, read from a generated boolean
    // (src/<brand>/res/values/brand_mascot.xml <- branding.hasMascot), never
    // from `if (brand == "rostam")` — CLAUDE.md 6b. rostam, redvpn and
    // dollarvpn ship REAL artwork; a brand with only the generated placeholder
    // (a brand-coloured disc with a letter in it) would look absurd inside this
    // disc, so it keeps the power glyph. Default is false, so a brand added
    // without regenerating gets the glyph rather than its placeholder rendered
    // as a logo. ⚠️ redvpn had real artwork and an UNSET flag for a while, so
    // the shield sat in res/drawable while the dial drew the generic glyph.
    // If a brand looks unbranded here, check the flag before the artwork.
    val hasMascot = booleanResource(R.bool.brand_has_mascot)

    // He REPORTS, he does not carry. The ring is the state signal — an empty
    // socket, a sweeping arc, a closed circle — and that is deliberate: a face
    // has no natural on/off reading, so if the mascot were the only cue the
    // whole point of making the SILHOUETTE change would be lost. This is a
    // second channel on top of it, in a different visual dimension (colour
    // intensity), free to be subtle because it is not load-bearing.
    //
    // TUNED DOWN TWICE, both times on the device, and the direction of the
    // error was the same each time: the bigger he gets, the less desaturation
    // he can take. 0.62/0.22 as an 84dp crest read as a failed image load.
    // 0.85/0.50 filling the disc read as a sick man — at aperture scale the
    // gold is most of the mark's area, and draining it drains the brand.
    // 0.95/0.80 is the resting state: unmistakably his own colour, just held
    // back, and it lifts cleanly when the disc turns.
    //
    // Erring quiet is right BECAUSE this cue is not load-bearing. The ring
    // carries the state, and the disc turning from white to full brand colour
    // underneath him is the loud half of this transition. He is the garnish on
    // it, so he is allowed to be subtle, and he is not allowed to look broken
    // in the state the app spends most of its life in.
    val mascotAlpha by animateFloatAsState(
        targetValue = if (isConnected) 1f else 0.95f,
        animationSpec = tween(650),
        label = "mascotAlpha",
    )
    val mascotSaturation by animateFloatAsState(
        targetValue = if (isConnected) 1f else 0.80f,
        animationSpec = tween(650),
        label = "mascotSaturation",
    )
    val mascotFilter = remember(mascotSaturation) {
        ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(mascotSaturation) })
    }

    // ── Touch response ──
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
        label = "discElevation",
    )

    // Arrival: one spring for the whole dial, fired only on the real
    // Connecting -> Connected edge (never on a recomposition, never when the
    // app is opened already connected).
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

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(DIAL_SIZE)
            .scale(pressScale * arrival.value)
            .clickable(
                interactionSource = interaction,
                indication = ripple(bounded = false, radius = DIAL_SIZE / 2, color = activeRing),
                onClick = onClick,
            )
            .semantics {
                // The dial's own name, translated, instead of the English
                // literals ("Connect VPN") this used to hand TalkBack in all
                // 38 locales.
                contentDescription = label
                role = Role.Button
            },
    ) {
        // ── LAYER 1: the halo, behind everything ──
        Canvas(modifier = Modifier.fillMaxSize()) {
            val radius = size.minDimension / 2f - RING_INSET.toPx() - RING_STROKE.toPx() / 2f
            val c = center

            if (haloFade > 0.01f) {
                val outer = size.minDimension / 2f
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            activeRing.copy(alpha = haloAlpha * haloFade),
                            activeRing.copy(alpha = haloAlpha * haloFade * 0.45f),
                            Color.Transparent,
                        ),
                        center = c,
                        radius = outer,
                    ),
                    radius = outer,
                    center = c,
                )
            }

        }

        // ── LAYER 2: the disc ──
        Box(
            modifier = Modifier
                .size(DISC_SIZE)
                .shadow(elevation, CircleShape)
                .background(discFill, CircleShape),
        )

        // ── LAYER 3: the ring, UNDERNEATH the mark ──
        //
        // The horns pass IN FRONT of the ring, so the ring reads as passing
        // behind the helmet. Owner's call, and the reverse shipped first: the
        // ring was drawn last so nothing could notch the state indicator.
        //
        // What that argument missed is that a notch is not a loss of state.
        // The ring carries connected / connecting / disconnected / failed
        // through COLOUR and SWEEP, both of which survive two 8dp gold
        // crossings at the top; the socket circle underneath keeps the track
        // continuous, so the eye completes the band. Occlusion would only
        // matter if the ring encoded state by its outline being unbroken, and
        // it does not.
        Canvas(modifier = Modifier.fillMaxSize()) {
            val stroke = RING_STROKE.toPx()
            val radius = size.minDimension / 2f - RING_INSET.toPx() - stroke / 2f
            val c = center

            // The socket. Always a complete circle, so the arc that fills it
            // reads as progress along a track rather than a floating shape.
            drawCircle(
                // Per brand: see brand_ring_rest_alpha. A faint track works for
                // a mid-dark ring on a light ground and disappears for a light
                // one, so the number cannot be shared.
                color = ringInk.copy(alpha = restAlpha),
                radius = radius,
                center = c,
                style = Stroke(stroke),
            )

            if (sweep > 0.5f) {
                drawArc(
                    color = activeRing,
                    startAngle = -90f + rotation,
                    sweepAngle = sweep,
                    useCenter = false,
                    topLeft = Offset(c.x - radius, c.y - radius),
                    size = Size(radius * 2, radius * 2),
                    style = Stroke(stroke, cap = StrokeCap.Round),
                )
            }
        }

        // ── LAYER 4: the mark, ON TOP, horns crossing the ring ──
        run {
            if (hasMascot) {
                // Full colour on the filled disc when connected. Checked by
                // compositing the real asset over this brand's `primary`
                // before writing any of this: the gold horns gain contrast
                // against teal and the dark beard still separates, so the
                // mascot reads BETTER on the connected fill than on the white
                // resting disc. That is what made it safe to keep the disc
                // fill exactly as measured instead of weakening it to a tint
                // to protect the artwork.
                Image(
                    painter = painterResource(id = R.drawable.brand_mark),
                    contentDescription = null,
                    modifier = Modifier
                        .size(markSize())
                        .alpha(mascotAlpha),
                    contentScale = ContentScale.Fit,
                    colorFilter = mascotFilter,
                )
            } else {
                // THE SHINE. A single specular band sweeps across the glyph
                // while connected, and only while connected: it is the reward
                // for the tap, so firing it at rest would spend the meaning.
                //
                // Brands without a mascot (every brand but Rostam) get their
                // identity from this glyph alone — DollarVPN overrides
                // ic_power with a dollar sign — so the glyph carries more
                // weight here than it does on the mascot path and is worth
                // animating.
                //
                // Drawn with DstIn over the glyph's own alpha, so the band is
                // clipped to the strokes themselves rather than sweeping a
                // rectangle across the disc. Diagonal, because a vertical
                // sweep on a vertical bar reads as a glitch.
                val connected = state is ConnectionState.Connected
                val sheen by if (connected && animate) {
                    rememberInfiniteTransition(label = "sheen").animateFloat(
                        initialValue = -1f,
                        targetValue = 2f,
                        animationSpec = infiniteRepeatable(
                            // Long tail, short sweep: the band should feel like
                            // an occasional catch of light, not a barber pole.
                            animation = tween(2600, easing = LinearEasing),
                            repeatMode = RepeatMode.Restart,
                        ),
                        label = "sheenPos",
                    )
                } else {
                    remember { mutableFloatStateOf(-1f) }
                }

                Icon(
                    painter = painterResource(id = R.drawable.ic_power),
                    contentDescription = null,
                    tint = glyphTint,
                    modifier = Modifier
                        .size(GLYPH_SIZE)
                        .drawWithContent {
                            drawContent()
                            if (sheen < -0.5f) return@drawWithContent
                            val w = size.width
                            val band = w * 0.42f
                            val x = sheen * (w + band * 2) - band
                            drawRect(
                                brush = Brush.linearGradient(
                                    0f to Color.Transparent,
                                    0.5f to Color.White.copy(alpha = 0.55f),
                                    1f to Color.Transparent,
                                    start = Offset(x, 0f),
                                    end = Offset(x + band, size.height),
                                ),
                                blendMode = BlendMode.SrcAtop,
                            )
                        },
                )
            }
        }

    }
}

/**
 * Developer options "Animator duration scale" and Accessibility -> Remove
 * animations, which both write it. 1f normally, 0f when animations are off.
 *
 * Compose already scales every `tween` by this value, so the transitions
 * above need no help. `rememberInfiniteTransition` does NOT read it — an
 * infinite animation has no duration to scale — which is exactly why the two
 * repeating effects here are gated by hand.
 */
@Composable
private fun rememberMotionScale(): Float {
    val ctx = LocalContext.current
    return remember(ctx) {
        runCatching {
            Settings.Global.getFloat(
                ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f,
            )
        }.getOrDefault(1f)
    }
}
