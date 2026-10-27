package com.rostamvpn.android.ui.screens

import android.content.Context
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.res.booleanResource
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import com.rostamvpn.android.ui.theme.BrandPalette
import com.rostamvpn.android.ui.theme.liftOnto
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import com.rostamvpn.android.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * A tap anywhere ends the launch overlay.
 *
 * Deliberately NOT a pointer modifier on the overlay: a `pointerInput` would make
 * it a hit target and swallow the very tap it is trying to notice, turning the
 * overlay back into a gate. MainActivity.dispatchTouchEvent observes ACTION_DOWN
 * on its way past and calls this, then lets the event continue to the app
 * underneath, which is already composed and already handling it.
 */
object LaunchTouch {
    @JvmField
    var onTouch: (() -> Unit)? = null
}

/**
 * The animated launch.
 *
 * It is an OVERLAY above a live MainScreen, never a gate in front of it. The view
 * model, `syncEntitlement()`, `restoreEntitlement()` and auto-connect all run
 * underneath while it plays, and it carries NO pointer modifier of any kind, so
 * from frame one a user who knows where Connect is taps straight through it. The
 * animation is therefore never the thing anyone waits on -- that is a property of
 * the hierarchy, not a promise about timing.
 *
 * DO NOT add `clickable`, `pointerInput` or a "tap to skip" here. A Compose layout
 * node with no pointer modifier is not a hit target, so events reach the sibling
 * below; adding one silently converts this back into a gate with no compile error
 * and no visible difference. [LaunchTouch] exists so the dismiss-on-touch
 * behaviour did not have to cost that property.
 *
 * t=0 is IDENTICAL to the system splash -- same ground colour (`@color/launch_bg`,
 * which is also `windowSplashScreenBackground`), same drawable
 * (`@drawable/launch_mark`), same size ([LaunchGeometry.SLOT]), same window
 * centre. So nothing visible there may fade in: the flat ground gains its
 * gradient, the light arrives, the light writes the name, and the whole thing
 * leaves. Everything else was already on screen.
 */
/**
 * THE LAUNCH ENTRY — dispatched on the brand's treatment, like the home screen.
 *
 * Every brand played the SAME animation with a different mark dropped into it:
 * a gold specular sweeping a lockup on a deepening ground. That is the home
 * screen's problem on the surface a user sees first, and it had a brand leak
 * inside it besides — the sweep's highlight is the brand accent, documented as
 * "the mean of the logo's two helmet golds", i.e. ROSTAM'S METAL, dragged
 * across RedVPN's red shield and DollarVPN's coin.
 *
 * `BrandStyle` already selects the home composition, and it selects this too.
 * ONE token rather than a second `entryStyle`, because a brand's entry should
 * agree with the screen it hands over to: RedVPN's rings expand and then become
 * the dial's rings, DollarVPN's rim lights and then becomes the coin's milling.
 * Independent tokens would allow a launch that promises one product and a home
 * that delivers another, and nobody would ever have looked at that combination.
 *
 * The owner asked for VARIETY, not bespoke: three treatments a brand chooses
 * between, not five hand-built one-offs. A sixth brand picks one by name.
 *
 * ⚠️ The t=0 CONTRACT binds all three and is easy to break silently: the first
 * composed frame must be IDENTICAL to the system splash — same ground, same
 * drawable, same slot, same centre — so nothing already on screen may fade in.
 * The mark is drawn at full opacity in frame one of every variant; only things
 * that were NOT in the system splash (rings, ticks, the wordmark) may arrive.
 */
@Composable
fun LaunchOverlay(onFinish: () -> Unit) {
    when (rememberBrandStyle()) {
        BrandStyle.CLASSIC -> ClassicEntry(onFinish)
        BrandStyle.RINGS -> RingsEntry(onFinish)
        BrandStyle.VALUE -> MilledEntry(onFinish)
        // The splash is already the design's dark ground with the mark.
        BrandStyle.TABS -> ClassicEntry(onFinish)
    }
}

@Composable
private fun ClassicEntry(onFinish: () -> Unit) {
    val ctx = LocalContext.current

    // .offset/translate/rotate inside a DrawScope are raw pixels and are NOT
    // mirrored, so the sweep has to be told. A specular must run the direction
    // the eye reads, or in Persian it looks like something being undone.
    val dir = if (LocalLayoutDirection.current == LayoutDirection.Rtl) -1f else 1f

    // Compose applies this same scale to every `tween` below, via the
    // MotionDurationScale it puts in the composition's coroutine context. The
    // `delay` beats are NOT covered by that, so they are multiplied here -- read
    // once, used for the skip decision and the delays, never applied to a tween
    // (which would square it).
    val motion = remember(ctx) { motionScale(ctx) }
    val silent = remember(ctx) { touchExploring(ctx) }

    val mark = ImageBitmap.imageResource(R.drawable.launch_mark)
    val word = ImageBitmap.imageResource(R.drawable.launch_wordmark)

    val ground = colorResource(R.color.launch_bg)
    val deep = colorResource(R.color.launch_bg_deep)
    val ink = colorResource(R.color.launch_ink)

    // Every one of these starts at its t=0 value, so the FIRST composed frame is
    // the system splash's frame. Animatable, not animate*AsState: the initial
    // value has to be what renders, not something a spec eases away from.
    val depth = remember { Animatable(0f) }   // ground: flat -> gradient
    val sweep = remember { Animatable(0f) }   // specular position, 0..1
    val name = remember { Animatable(0f) }    // the wordmark's arrival
    val fade = remember { Animatable(1f) }    // whole-overlay opacity
    val scale = remember { Animatable(1f) }   // mark scale, departure only

    DisposableEffect(Unit) {
        LaunchTouch.onTouch = onFinish
        onDispose { LaunchTouch.onTouch = null }
    }

    LaunchedEffect(Unit) {
        // Two reasons to show nothing at all, and neither is a compromise.
        //
        // TalkBack: 1.6s of decoration is worth nothing to someone who is not
        // looking at it, and the overlay is semantics-cleared, so there is
        // nothing there to read either.
        //
        // Animations off (developer options, or Accessibility -> Remove
        // animations): the honest reading of ANIMATOR_DURATION_SCALE == 0 is NO
        // animation, so the answer is to hand over immediately, not to play a
        // quieter one. iOS's reduceMotion branch keeps "the same beat of light,
        // standing still", but reduceMotion means "avoid large motion" while
        // this setting means "do not animate", and a light that fades up and
        // down is still an animation. Transliterating iOS here would also have
        // been a lie in practice: Compose scales every tween by this same
        // factor, so at 0 the whole still branch completed in zero time and all
        // that survived were its `delay`s -- measured on device as a 210ms flash
        // of gradient appearing in one frame, which is worse than nothing. The
        // mark is still on screen for these users; the system splash draws it.
        if (silent || motion <= 0f) {
            onFinish()
            return@LaunchedEffect
        }
        val g = LaunchGeometry
        fun beat(ms: Int) = (ms * motion).toLong()

        depth.animateTo(1f, tween(g.SETTLE, easing = FastOutSlowInEasing))
        // The name arrives inside the sweep's own window: the pass that lights
        // the metal is the pass that writes the name. Launched as a child of
        // this beat, and phrased as fractions of SWEEP, so it cannot drift.
        launch {
            delay(beat((g.SWEEP * g.NAME_IN).roundToInt()))
            name.animateTo(1f, tween((g.SWEEP * g.NAME_SPAN).roundToInt(), easing = LinearEasing))
        }
        sweep.animateTo(1f, tween(g.SWEEP, easing = FastOutSlowInEasing))
        delay(beat(g.HOLD))
        // The ground FLATTENS as it fades, back to exactly the colour the app's
        // own background is, so the reveal has almost nothing left to reveal.
        // iOS fades a live gradient out over ContentView; this is strictly less
        // to see, and in dark mode it measured as no visible change at all.
        launch { depth.animateTo(0f, tween(g.DEPART, easing = FastOutLinearInEasing)) }
        launch { scale.animateTo(g.DEPART_SCALE, tween(g.DEPART, easing = FastOutLinearInEasing)) }
        fade.animateTo(0f, tween(g.DEPART, easing = FastOutLinearInEasing))
        onFinish()
    }

    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = fade.value }
            .drawBehind {
                drawRect(Brush.verticalGradient(listOf(ground, lerp(ground, deep, depth.value))))
            }
            .clearAndSetSemantics {},
    ) {
        Canvas(
            Modifier
                .align(Alignment.Center)
                .size(LaunchGeometry.SLOT)
                .graphicsLayer {
                    scaleX = scale.value
                    scaleY = scale.value
                },
        ) {
            drawLockup(mark, word, ink, name.value, sweep.value, dir)
        }
    }
}

/** The sweep highlight, taken from the brand's own accent so it reads as the
 *  artwork's metal catching light rather than an applied colour. It was the mean
 *  of rostam's two helmet golds (#D7A251 / #DAA75B) hardcoded here, which meant
 *  every brand's splash swept rostam's gold across its own mark. */
private val Gold = BrandPalette.accent

private fun DrawScope.drawLockup(
    mark: ImageBitmap,
    word: ImageBitmap,
    ink: Color,
    name: Float,
    sweep: Float,
    dir: Float,
) {
    val g = LaunchGeometry
    val box = IntSize(size.width.roundToInt(), size.height.roundToInt())
    val inked = ColorFilter.tint(ink)

    // The mark keeps its own colour; the wordmark is generated as an alpha mask
    // and takes the ground's ink, because the lockup bakes the name in near-black
    // and that is invisible on the dark ground.
    fun art() {
        drawImage(mark, dstSize = box, filterQuality = FilterQuality.High)
        if (name > 0f) {
            drawImage(
                word, dstSize = box, alpha = name, colorFilter = inked,
                filterQuality = FilterQuality.High,
            )
        }
    }

    art()
    if (sweep <= 0f) return  // t=0: no added light at all

    // Additive light, masked to the artwork. The outer layer composites with
    // Plus (Compose's .plusLighter); the inner one re-draws the artwork with
    // DstIn so the light survives only where the artwork is -- which also unions
    // mark and wordmark without needing a third asset, and scales the light on
    // the name by the name's own alpha.
    fun addLight(paint: (DrawScope.() -> Unit)) {
        val bounds = Rect(Offset.Zero, size)
        drawContext.canvas.saveLayer(bounds, Paint().apply { blendMode = BlendMode.Plus })
        paint()
        drawContext.canvas.saveLayer(bounds, Paint().apply { blendMode = BlendMode.DstIn })
        art()
        drawContext.canvas.restore()
        drawContext.canvas.restore()
    }

    val w = size.width * g.SHEEN_W
    val h = size.height * g.SHEEN_H
    val left = (size.width - w) / 2f
    val top = (size.height - h) / 2f
    val travel = (sweep * 2f - 1f) * size.width * g.SHEEN_TRAVEL * dir
    // iOS's five stops, at iOS's positions. The white core is 0.55 rather than
    // its 0.92: BlendMode.Plus is Skia's kPlus, the same add as .plusLighter, and
    // at 0.92 the core adds ~235 to a face that is already light, so for two
    // frames the eyes and beard wash out completely. Verified on device, frames
    // 66 and 67 of a 30fps capture. A specular that erases the face it crosses
    // is not the artwork's metal catching light. (The iOS build has the same
    // gradient over the same artwork and almost certainly the same blow-out; its
    // pass is 1450ms rather than 900ms, which softens but does not fix it.)
    val beam = Brush.horizontalGradient(
        0.00f to Color.Transparent,
        0.34f to Gold.copy(alpha = 0.35f),
        0.50f to Color.White.copy(alpha = 0.55f),
        0.66f to Gold.copy(alpha = 0.35f),
        1.00f to Color.Transparent,
        startX = left,
        endX = left + w,
    )
    addLight {
        translate(left = travel) {
            rotate(degrees = g.SHEEN_SHEAR * dir) {
                drawRect(beam, topLeft = Offset(left, top), size = Size(w, h))
            }
        }
    }
}

/** Developer-options "Animator duration scale" and Accessibility -> Remove
 *  animations, which both write it. 1f normally, 0f when animations are off.
 *  This is the same value Compose reads for its own MotionDurationScale, which
 *  is why the tweens must not be scaled again by hand. */
private fun motionScale(ctx: Context): Float = runCatching {
    Settings.Global.getFloat(
        ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f,
    )
}.getOrDefault(1f)

private fun touchExploring(ctx: Context): Boolean = runCatching {
    (ctx.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager)
        .isTouchExplorationEnabled
}.getOrDefault(false)

/**
 * THE RINGS ENTRY — RedVPN.
 *
 * The launch says what the home screen will say. Concentric rings expand out of
 * the mark and fade, at the SAME four radii the connect dial fills when it
 * connects, so the first thing the app does is draw its own dial's vocabulary;
 * two seconds later the user is looking at that dial. The design package's
 * splash (`0.png`) is a static shield-over-wordmark on near-white, which this
 * matches at rest — the motion is the part a still frame cannot specify.
 *
 * NO SPECULAR. The classic entry's highlight is Rostam's helmet gold and there
 * is nothing metallic in RedVPN's mark for a specular to catch; dragging a gold
 * band across a flat red shield reads as a rendering fault, not as light.
 *
 * THE DESIGNER'S BACKDROP, NOW SHIPPED AS TWO SMALL TINTABLE PIECES (2026-10-24,
 * parity with iOS LaunchIntro). The objection recorded here before was to
 * `0/Background.png` itself: 165KB, near-white, light-only. iOS solved all
 * three and Android reuses its answer: the constellation as a 42KB template PNG
 * (`assets/launch-constellation.png`) and the dotted map as 1319 dot centres
 * (`assets/world-dots.json`, 23KB), both drawn here in the launch ink at low
 * strength, so one asset serves both themes. They are OPTIONAL brand assets:
 * only a brand that ships them (redvpn) draws them, every other `rings` brand
 * gets exactly the entry it had, and no other APK carries the bytes.
 */
@Composable
private fun RingsEntry(onFinish: () -> Unit) {
    val ctx = LocalContext.current
    val motion = remember(ctx) { motionScale(ctx) }
    val silent = remember(ctx) { touchExploring(ctx) }

    val mark = ImageBitmap.imageResource(R.drawable.launch_mark)
    val word = ImageBitmap.imageResource(R.drawable.launch_wordmark)
    // A brand whose mark cannot sit on its own ground needs a light field
    // under it, here and in the connect dial. Same flag, same reason.
    val markNeedsLightField = !booleanResource(R.bool.brand_disc_fills_on_connect)
    val ground = colorResource(R.color.launch_bg)
    val deep = colorResource(R.color.launch_bg_deep)
    val ink = colorResource(R.color.launch_ink)
    // The dial's own band colour, so the launch and the home screen are drawn
    // from ONE token. liftOnto keeps the hue legible if the ground is dark.
    val band = liftOnto(ground, colorResource(R.color.brand_band), min = 3f)

    val depth = remember { Animatable(0f) }
    val pulse = remember { Animatable(0f) }   // rings, 0..1
    val name = remember { Animatable(0f) }
    val fade = remember { Animatable(1f) }
    val scale = remember { Animatable(1f) }

    DisposableEffect(Unit) {
        LaunchTouch.onTouch = onFinish
        onDispose { LaunchTouch.onTouch = null }
    }

    LaunchedEffect(Unit) {
        if (silent || motion <= 0f) { onFinish(); return@LaunchedEffect }
        val g = LaunchGeometry
        fun beat(ms: Int) = (ms * motion).toLong()
        depth.animateTo(1f, tween(g.SETTLE, easing = FastOutSlowInEasing))
        launch {
            delay(beat((g.SWEEP * g.NAME_IN).roundToInt()))
            name.animateTo(1f, tween((g.SWEEP * g.NAME_SPAN).roundToInt(), easing = LinearEasing))
        }
        pulse.animateTo(1f, tween(g.SWEEP, easing = LinearEasing))
        delay(beat(g.HOLD))
        launch { depth.animateTo(0f, tween(g.DEPART, easing = FastOutLinearInEasing)) }
        launch { scale.animateTo(g.DEPART_SCALE, tween(g.DEPART, easing = FastOutLinearInEasing)) }
        fade.animateTo(0f, tween(g.DEPART, easing = FastOutLinearInEasing))
        onFinish()
    }

    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = fade.value }
            .drawBehind {
                drawRect(Brush.verticalGradient(listOf(ground, lerp(ground, deep, depth.value))))
            }
            .clearAndSetSemantics {},
    ) {
        // The splash art: constellation at the top, the dotted map at the
        // bottom, easing in with the rings and leaving with the ground.
        val constellation = remember { launchConstellation(ctx) }
        val dots = remember { worldDots(ctx) }
        if (constellation != null || dots != null) {
            Canvas(Modifier.fillMaxSize().graphicsLayer { alpha = (pulse.value * 3f).coerceAtMost(1f) * depth.value.coerceAtLeast(0.6f) }) {
                val darkGround = ground.luminance() < 0.5f
                constellation?.let { img ->
                    val h = size.width * img.height / img.width
                    drawImage(
                        img, dstSize = IntSize(size.width.roundToInt(), h.roundToInt()),
                        colorFilter = ColorFilter.tint(ink.copy(alpha = if (darkGround) 0.55f else 0.45f)),
                        filterQuality = FilterQuality.High,
                    )
                }
                dots?.let { d ->
                    val h = size.width / d.aspect
                    drawWorldDots(d, ink.copy(alpha = if (darkGround) 0.18f else 0.12f), top = size.height - h)
                }
            }
        }
        Canvas(
            Modifier
                .align(Alignment.Center)
                .size(LaunchGeometry.SLOT)
                .graphicsLayer { scaleX = scale.value; scaleY = scale.value },
        ) {
            // RINGS FIRST, so they pass BEHIND the mark. Three, staggered by a
            // third of the run each, so one is always mid-flight and the dial
            // reads as broadcasting rather than blinking.
            val base = size.minDimension * 0.30f
            for (k in 0 until 3) {
                val t = ((pulse.value * 1.6f) - k * 0.33f)
                if (t <= 0f || t >= 1f) continue
                // Grows out from the mark's edge and thins as it goes, so it
                // dissipates instead of stopping.
                val r = base + (size.minDimension * 0.34f) * t
                drawCircle(
                    color = band.copy(alpha = 0.55f * (1f - t) * (1f - t)),
                    radius = r,
                    center = center,
                    style = Stroke((6f * (1f - t) + 1.5f)),
                )
            }
            // A LIGHT FIELD UNDER THE MARK, for the same reason the connect dial
            // has one. RedVPN's shield carries dark bands as part of its artwork,
            // and measured against this ground they land at 1.10:1 where the
            // floor for a graphic is 3:1, so the lower half of the mark dissolved
            // into the maroon. Only its brightest reds cleared, at 3.2-3.3:1.
            //
            // The mark is drawn for a light field: every asset in the brand's own
            // design package puts it on white, splash included. Tinting it to
            // survive a dark ground is not available without inventing artwork,
            // so the ground gets a disc instead. `discFillsOnConnect` encodes the
            // same decision for the dial; a brand whose mark reads on its own
            // ground simply never sets it and gets no disc here either.
            if (markNeedsLightField) {
                drawCircle(
                    color = androidx.compose.ui.graphics.Color.White,
                    radius = size.minDimension * 0.315f,
                    center = center,
                )
            }
            val box = IntSize(size.width.roundToInt(), size.height.roundToInt())
            // INSET THE MARK WHEN IT IS ON THE DISC. Drawn at the full slot it
            // overflowed the light field on both sides — see
            // LaunchGeometry.LIGHT_FIELD_MARK for the arithmetic. The wordmark
            // below keeps `box`: its position must not depend on the shield's
            // padding.
            val markBox = if (markNeedsLightField) {
                val k = LaunchGeometry.LIGHT_FIELD_MARK
                IntSize((size.width * k).roundToInt(), (size.height * k).roundToInt())
            } else box
            val markAt = IntOffset(
                ((size.width - markBox.width) / 2f).roundToInt(),
                ((size.height - markBox.height) / 2f).roundToInt(),
            )
            drawImage(mark, dstOffset = markAt, dstSize = markBox, filterQuality = FilterQuality.High)
            if (name.value > 0f) {
                drawImage(
                    word, dstSize = box, alpha = name.value,
                    colorFilter = ColorFilter.tint(ink), filterQuality = FilterQuality.High,
                )
            }
        }
    }
}

/**
 * THE MILLED ENTRY — DollarVPN.
 *
 * The coin's rim lights once around, then the app opens on the same rim as the
 * connect dial's resting state. Same reasoning as RingsEntry: the entry is a
 * promise the home screen keeps, and the two share one token so they cannot
 * disagree.
 *
 * The tick count, geometry and colour source are CoinDial's, deliberately, so
 * the launch is the dial at rest rather than a second drawing of a similar
 * idea. It also keeps the classic entry's gold specular away from a coin, which
 * has its own metal and does not need Rostam's.
 */
@Composable
private fun MilledEntry(onFinish: () -> Unit) {
    val ctx = LocalContext.current
    val motion = remember(ctx) { motionScale(ctx) }
    val silent = remember(ctx) { touchExploring(ctx) }

    val mark = ImageBitmap.imageResource(R.drawable.launch_mark)
    val word = ImageBitmap.imageResource(R.drawable.launch_wordmark)
    val ground = colorResource(R.color.launch_bg)
    val deep = colorResource(R.color.launch_bg_deep)
    val ink = colorResource(R.color.launch_ink)
    val tick = liftOnto(ground, colorResource(R.color.brand_ring), min = 3f)

    val depth = remember { Animatable(0f) }
    val travel = remember { Animatable(0f) }
    val name = remember { Animatable(0f) }
    val fade = remember { Animatable(1f) }
    val scale = remember { Animatable(1f) }

    DisposableEffect(Unit) {
        LaunchTouch.onTouch = onFinish
        onDispose { LaunchTouch.onTouch = null }
    }

    LaunchedEffect(Unit) {
        if (silent || motion <= 0f) { onFinish(); return@LaunchedEffect }
        val g = LaunchGeometry
        fun beat(ms: Int) = (ms * motion).toLong()
        depth.animateTo(1f, tween(g.SETTLE, easing = FastOutSlowInEasing))
        launch {
            delay(beat((g.SWEEP * g.NAME_IN).roundToInt()))
            name.animateTo(1f, tween((g.SWEEP * g.NAME_SPAN).roundToInt(), easing = LinearEasing))
        }
        travel.animateTo(1f, tween(g.SWEEP, easing = FastOutSlowInEasing))
        delay(beat(g.HOLD))
        launch { depth.animateTo(0f, tween(g.DEPART, easing = FastOutLinearInEasing)) }
        launch { scale.animateTo(g.DEPART_SCALE, tween(g.DEPART, easing = FastOutLinearInEasing)) }
        fade.animateTo(0f, tween(g.DEPART, easing = FastOutLinearInEasing))
        onFinish()
    }

    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = fade.value }
            .drawBehind {
                drawRect(Brush.verticalGradient(listOf(ground, lerp(ground, deep, depth.value))))
            }
            .clearAndSetSemantics {},
    ) {
        Canvas(
            Modifier
                .align(Alignment.Center)
                .size(LaunchGeometry.SLOT)
                .graphicsLayer { scaleX = scale.value; scaleY = scale.value },
        ) {
            val outerR = size.minDimension * 0.47f
            val n = 48
            for (i in 0 until n) {
                val turn = i.toFloat() / n
                var d = travel.value - turn
                if (d < 0f) d += 1f
                val lit = (1f - (d / 0.3f)).coerceIn(0f, 1f)
                val len = size.minDimension * (0.030f + 0.026f * lit)
                val rad = Math.toRadians((turn * 360f - 90f).toDouble())
                val cosA = kotlin.math.cos(rad).toFloat()
                val sinA = kotlin.math.sin(rad).toFloat()
                drawLine(
                    color = tick.copy(alpha = 0.22f + 0.68f * lit),
                    start = Offset(center.x + cosA * outerR, center.y + sinA * outerR),
                    end = Offset(center.x + cosA * (outerR - len), center.y + sinA * (outerR - len)),
                    strokeWidth = size.minDimension * 0.009f,
                    cap = StrokeCap.Round,
                )
            }
            val box = IntSize(size.width.roundToInt(), size.height.roundToInt())
            drawImage(mark, dstSize = box, filterQuality = FilterQuality.High)
            if (name.value > 0f) {
                drawImage(
                    word, dstSize = box, alpha = name.value,
                    colorFilter = ColorFilter.tint(ink), filterQuality = FilterQuality.High,
                )
            }
        }
    }
}
