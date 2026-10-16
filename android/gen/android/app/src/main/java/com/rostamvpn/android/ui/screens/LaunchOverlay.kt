// Copyright 2026 Internet Mastering & Company, Inc.
// SPDX-License-Identifier: Apache-2.0

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
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import com.rostamvpn.android.ui.theme.BrandPalette
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import com.rostamvpn.android.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** A tap anywhere ends the launch overlay. */
object LaunchTouch {
    @JvmField
    var onTouch: (() -> Unit)? = null
}

/** The animated launch. */
@Composable
fun LaunchOverlay(onFinish: () -> Unit) {
    val ctx = LocalContext.current

    val dir = if (LocalLayoutDirection.current == LayoutDirection.Rtl) -1f else 1f

    val motion = remember(ctx) { motionScale(ctx) }
    val silent = remember(ctx) { touchExploring(ctx) }

    val mark = ImageBitmap.imageResource(R.drawable.launch_mark)
    val word = ImageBitmap.imageResource(R.drawable.launch_wordmark)

    val ground = colorResource(R.color.launch_bg)
    val deep = colorResource(R.color.launch_bg_deep)
    val ink = colorResource(R.color.launch_ink)

    val depth = remember { Animatable(0f) }
    val sweep = remember { Animatable(0f) }
    val name = remember { Animatable(0f) }
    val fade = remember { Animatable(1f) }
    val scale = remember { Animatable(1f) }

    DisposableEffect(Unit) {
        LaunchTouch.onTouch = onFinish
        onDispose { LaunchTouch.onTouch = null }
    }

    LaunchedEffect(Unit) {
        if (silent || motion <= 0f) {
            onFinish()
            return@LaunchedEffect
        }
        val g = LaunchGeometry
        fun beat(ms: Int) = (ms * motion).toLong()

        depth.animateTo(1f, tween(g.SETTLE, easing = FastOutSlowInEasing))

        launch {
            delay(beat((g.SWEEP * g.NAME_IN).roundToInt()))
            name.animateTo(1f, tween((g.SWEEP * g.NAME_SPAN).roundToInt(), easing = LinearEasing))
        }
        sweep.animateTo(1f, tween(g.SWEEP, easing = FastOutSlowInEasing))
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
                .graphicsLayer {
                    scaleX = scale.value
                    scaleY = scale.value
                },
        ) {
            drawLockup(mark, word, ink, name.value, sweep.value, dir)
        }
    }
}

/**
 * The sweep highlight, taken from the brand's own accent so it reads as the
 * artwork's metal catching light rather than an applied colour.
 */
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
    if (sweep <= 0f) return

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

/**
 * Developer-options "Animator duration scale" and Accessibility -> Remove
 * animations, which both write it. 1f normally, 0f when animations are off.
 */
private fun motionScale(ctx: Context): Float = runCatching {
    Settings.Global.getFloat(
        ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f,
    )
}.getOrDefault(1f)

private fun touchExploring(ctx: Context): Boolean = runCatching {
    (ctx.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager)
        .isTouchExplorationEnabled
}.getOrDefault(false)
