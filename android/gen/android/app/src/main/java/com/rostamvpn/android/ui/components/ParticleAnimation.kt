package com.rostamvpn.android.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import com.rostamvpn.android.VpnViewModel.ConnectionState
import com.rostamvpn.android.ui.theme.Brand1
import com.rostamvpn.android.ui.theme.Brand2
import kotlin.math.sin
import kotlin.random.Random

private data class Particle(
    val x: Float,        // 0..1 normalized horizontal position
    val baseSpeed: Float, // 0..1 base speed
    val size: Float,      // radius in dp-ish units
    val baseAlpha: Float, // 0..1 base opacity
    val phase: Float,     // animation phase offset
    val layer: Int,       // 0=back, 1=mid, 2=front (for parallax)
    val hue: Int          // 0=brand1, 1=brand2, 2=accent
)

/**
 * Premium ambient particle system with connection-aware behavior:
 * - Disconnected: no particles (clean look)
 * - Connecting: energetic particles swirling upward, higher speed
 * - Connected: calm, elegant upward drift with teal glow particles + parallax depth
 */
@Composable
fun ParticleAnimation(
    connectionState: ConnectionState,
    modifier: Modifier = Modifier
) {
    val isConnected = connectionState is ConnectionState.Connected
    val isConnecting = connectionState is ConnectionState.Connecting
    val isActive = isConnected || isConnecting

    // Smooth fade in/out
    val globalAlpha by animateFloatAsState(
        targetValue = if (isActive) 1f else 0f,
        animationSpec = tween(durationMillis = if (isActive) 800 else 400),
        label = "particleFade"
    )

    if (globalAlpha < 0.01f) return

    val particles = remember {
        List(40) {
            Particle(
                x = Random.nextFloat(),
                baseSpeed = 0.2f + Random.nextFloat() * 0.6f,
                size = 1f + Random.nextFloat() * 4f,
                baseAlpha = 0.1f + Random.nextFloat() * 0.4f,
                phase = Random.nextFloat(),
                layer = (it % 3),
                hue = (it % 3)
            )
        }
    }

    val infiniteTransition = rememberInfiniteTransition(label = "particles")

    // Primary animation driver
    val progress by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            tween(10000, easing = LinearEasing),
            RepeatMode.Restart
        ),
        label = "particleProgress"
    )

    // Secondary for wave motion
    val wave by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 6.2832f, // 2*PI
        animationSpec = infiniteRepeatable(
            tween(8000, easing = LinearEasing),
            RepeatMode.Restart
        ),
        label = "waveMotion"
    )

    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height

        particles.forEach { p ->
            // Speed multiplier based on state
            val speedMul = if (isConnecting) 1.8f else 1f
            // Parallax: back layers move slower
            val layerSpeed = when (p.layer) {
                0 -> 0.6f
                1 -> 1.0f
                else -> 1.4f
            }

            val t = ((progress * speedMul * p.baseSpeed * layerSpeed) + p.phase) % 1f

            // Y: float upward
            val y = h * (1f - t)

            // X: gentle sinusoidal drift
            val drift = sin((wave + p.phase * 6.2832f).toDouble()).toFloat()
            val driftAmount = if (isConnecting) 0.08f else 0.04f
            val x = w * p.x + drift * w * driftAmount

            // Alpha: fade in at bottom, fade out at top
            val fadeIn = (t * 4f).coerceIn(0f, 1f)
            val fadeOut = ((1f - t) * 3f).coerceIn(0f, 1f)
            val alpha = p.baseAlpha * fadeIn * fadeOut * globalAlpha

            // Size: slightly smaller as they rise
            val particleSize = p.size * (0.7f + 0.3f * (1f - t))

            // Color based on hue variant
            val color = when (p.hue) {
                0 -> Brand1
                1 -> Brand2
                else -> if (isConnecting) Brand2 else Brand1
            }

            // Draw particle with soft glow for larger ones
            if (particleSize > 2.5f) {
                // Outer glow
                drawCircle(
                    color = color.copy(alpha = alpha * 0.3f),
                    radius = particleSize * 2f,
                    center = Offset(x, y)
                )
            }

            // Core particle
            drawCircle(
                color = color.copy(alpha = alpha),
                radius = particleSize,
                center = Offset(x, y)
            )
        }
    }
}

/**
 * Simplified particle animation for use as background.
 * Always active, very subtle.
 */
@Composable
fun ParticleAnimation(
    isActive: Boolean,
    modifier: Modifier = Modifier
) {
    if (!isActive) return

    val particles = remember {
        List(30) {
            Particle(
                x = Random.nextFloat(),
                baseSpeed = 0.3f + Random.nextFloat() * 0.7f,
                size = 1f + Random.nextFloat() * 3f,
                baseAlpha = 0.2f + Random.nextFloat() * 0.5f,
                phase = Random.nextFloat(),
                layer = 1,
                hue = if (Random.nextFloat() > 0.5f) 0 else 1
            )
        }
    }

    val infiniteTransition = rememberInfiniteTransition(label = "particles")
    val progress by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            tween(8000, easing = LinearEasing),
            RepeatMode.Restart
        ),
        label = "particleProgress"
    )

    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height

        particles.forEach { p ->
            val t = (progress + p.phase) % 1f
            val y = h * (1f - t * p.baseSpeed)
            val x = w * p.x + (sin((t + p.phase) * 6.28f) * w * 0.05f).toFloat()

            drawCircle(
                color = Brand2.copy(alpha = p.baseAlpha * (1f - t * 0.6f)),
                radius = p.size,
                center = Offset(x, y)
            )
        }
    }
}
