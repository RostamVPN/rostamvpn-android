// Copyright 2026 Internet Mastering & Company, Inc.
// SPDX-License-Identifier: Apache-2.0

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

/** THE CONNECT DIAL — the one thing this app is for. */

/** Overall footprint. Also the tap target — generous on purpose. */
private val DIAL_SIZE = 248.dp

/** The socket: thick enough to read as a collar, not a hairline. */
private val RING_STROKE = 14.dp

/** Page showing between the box edge and the outside of the ring. */
private val RING_INSET = 10.dp

/**
 * Page showing between the inside of the ring and the disc — the gap is what
 * makes the disc read as sitting IN something rather than ON it.
 */
private val RING_GAP = 8.dp

private val DISC_SIZE = DIAL_SIZE - (RING_INSET + RING_STROKE + RING_GAP) * 2

/** The power glyph, for the four brands that have no mascot. */
private val GLYPH_SIZE = 76.dp

/** How large to draw the brand mark, per brand. */
@Composable
private fun markSize(): androidx.compose.ui.unit.Dp {
    val ctx = LocalContext.current
    val v = remember(ctx) {
        val tv = android.util.TypedValue()
        ctx.resources.getValue(R.dimen.brand_mark_scale, tv, true)
        tv.float
    }
    return DISC_SIZE * v
}

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

    val discFillsOnConnect = booleanResource(R.bool.brand_disc_fills_on_connect)
    val discOn = if (discFillsOnConnect) scheme.primary else scheme.surface

    val discFill by animateColorAsState(
        targetValue = when {
            isConnected -> discOn

            isConnecting -> discOn.copy(alpha = 0.10f).compositeOver(scheme.surface)
            else -> scheme.surface
        },

        animationSpec = tween(200, easing = FastOutSlowInEasing),
        label = "discFill",
    )

    val glyphTint by animateColorAsState(
        targetValue = when {
            isConnected -> mostLegibleOn(discOn, Color.White, DarkBase)
            isFailed -> scheme.error

            else -> legibleOn(scheme.surface, scheme.primary, scheme.onSurface)
        },

        animationSpec = tween(200, easing = FastOutSlowInEasing),
        label = "glyphTint",
    )

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
        0.17f
    }

    val hasMascot = booleanResource(R.bool.brand_has_mascot)

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
                contentDescription = label
                role = Role.Button
            },
    ) {
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

        Box(
            modifier = Modifier
                .size(DISC_SIZE)
                .shadow(elevation, CircleShape)
                .background(discFill, CircleShape),
        )

        Canvas(modifier = Modifier.fillMaxSize()) {
            val stroke = RING_STROKE.toPx()
            val radius = size.minDimension / 2f - RING_INSET.toPx() - stroke / 2f
            val c = center

            drawCircle(

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

        run {
            if (hasMascot) {
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
                val connected = state is ConnectionState.Connected
                val sheen by if (connected && animate) {
                    rememberInfiniteTransition(label = "sheen").animateFloat(
                        initialValue = -1f,
                        targetValue = 2f,
                        animationSpec = infiniteRepeatable(

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
