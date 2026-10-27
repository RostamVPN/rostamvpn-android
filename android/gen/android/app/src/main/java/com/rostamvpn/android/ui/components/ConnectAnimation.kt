package com.rostamvpn.android.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import com.rostamvpn.android.VpnViewModel.ConnectionState
import com.rostamvpn.android.ui.theme.Brand1
import com.rostamvpn.android.ui.theme.Brand2
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * Shockwave burst animation that fires when VPN transitions to Connected.
 * Shows expanding concentric rings + radial particle burst from center.
 */
@Composable
fun ConnectShockwave(
    connectionState: ConnectionState,
    modifier: Modifier = Modifier
) {
    var previousState by remember { mutableStateOf<ConnectionState>(ConnectionState.Disconnected) }
    var shouldAnimate by remember { mutableStateOf(false) }

    // Detect Connecting -> Connected transition
    LaunchedEffect(connectionState) {
        if (connectionState is ConnectionState.Connected &&
            previousState is ConnectionState.Connecting) {
            shouldAnimate = true
        }
        previousState = connectionState
    }

    if (!shouldAnimate) return

    // Ring 1 - fast expanding
    val ring1Progress = remember { Animatable(0f) }
    // Ring 2 - slightly delayed
    val ring2Progress = remember { Animatable(0f) }
    // Ring 3 - most delayed, subtle
    val ring3Progress = remember { Animatable(0f) }
    // Particle burst
    val burstProgress = remember { Animatable(0f) }

    // Generate random burst particles
    val burstParticles = remember {
        List(24) {
            BurstParticle(
                angle = (it * 15f + Random.nextFloat() * 10f),
                speed = 0.6f + Random.nextFloat() * 0.4f,
                size = 2f + Random.nextFloat() * 4f,
                color = if (Random.nextFloat() > 0.5f) Brand1 else Brand2
            )
        }
    }

    LaunchedEffect(shouldAnimate) {
        if (shouldAnimate) {
            // Fire all animations in parallel with staggered starts
            launch {
                ring1Progress.animateTo(
                    1f,
                    animationSpec = tween(800, easing = FastOutSlowInEasing)
                )
            }
            launch {
                delay(100)
                ring2Progress.animateTo(
                    1f,
                    animationSpec = tween(900, easing = FastOutSlowInEasing)
                )
            }
            launch {
                delay(200)
                ring3Progress.animateTo(
                    1f,
                    animationSpec = tween(1000, easing = FastOutSlowInEasing)
                )
            }
            launch {
                burstProgress.animateTo(
                    1f,
                    animationSpec = tween(700, easing = FastOutSlowInEasing)
                )
            }

            // Wait for all to complete, then clean up
            delay(1100)
            shouldAnimate = false
            ring1Progress.snapTo(0f)
            ring2Progress.snapTo(0f)
            ring3Progress.snapTo(0f)
            burstProgress.snapTo(0f)
        }
    }

    Canvas(modifier = modifier.fillMaxSize()) {
        val centerX = size.width / 2f
        val centerY = size.height * 0.45f // Slightly above center (where power button is)
        val maxRadius = size.width * 0.7f

        // Draw expanding rings
        listOf(
            ring1Progress.value to 3f,
            ring2Progress.value to 2f,
            ring3Progress.value to 1.5f
        ).forEach { (progress, strokeWidth) ->
            if (progress > 0f) {
                val radius = maxRadius * progress
                val alpha = (1f - progress).coerceIn(0f, 0.6f)
                drawCircle(
                    color = Brand2.copy(alpha = alpha),
                    radius = radius,
                    center = Offset(centerX, centerY),
                    style = Stroke(width = strokeWidth * (1f - progress * 0.5f))
                )
            }
        }

        // Draw burst particles
        val bp = burstProgress.value
        if (bp > 0f) {
            burstParticles.forEach { particle ->
                val angleRad = Math.toRadians(particle.angle.toDouble())
                val distance = maxRadius * 0.8f * bp * particle.speed
                val px = centerX + (cos(angleRad) * distance).toFloat()
                val py = centerY + (sin(angleRad) * distance).toFloat()
                val alpha = (1f - bp).coerceIn(0f, 0.8f)
                val particleSize = particle.size * (1f - bp * 0.5f)

                drawCircle(
                    color = particle.color.copy(alpha = alpha),
                    radius = particleSize,
                    center = Offset(px, py)
                )
            }
        }
    }
}

private data class BurstParticle(
    val angle: Float,
    val speed: Float,
    val size: Float,
    val color: Color
)

/**
 * Soft pulsing glow behind the power button area when connected.
 * Rendered as a radial gradient that breathes slowly.
 */
@Composable
fun ConnectedGlow(
    isConnected: Boolean,
    modifier: Modifier = Modifier
) {
    if (!isConnected) return

    val infiniteTransition = rememberInfiniteTransition(label = "glow")
    val glowAlpha by infiniteTransition.animateFloat(
        initialValue = 0.12f,
        targetValue = 0.25f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glowAlpha"
    )
    val glowScale by infiniteTransition.animateFloat(
        initialValue = 0.9f,
        targetValue = 1.1f,
        animationSpec = infiniteRepeatable(
            animation = tween(3000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glowScale"
    )

    Canvas(modifier = modifier) {
        val centerX = size.width / 2f
        val centerY = size.height / 2f
        val radius = size.minDimension * 0.6f * glowScale

        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    Brand1.copy(alpha = glowAlpha),
                    Brand2.copy(alpha = glowAlpha * 0.5f),
                    Color.Transparent
                ),
                center = Offset(centerX, centerY),
                radius = radius
            ),
            radius = radius,
            center = Offset(centerX, centerY)
        )
    }
}
