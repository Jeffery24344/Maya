package com.jeffery.assistant.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

enum class NovaState { IDLE, LISTENING, THINKING, SPEAKING }

/**
 * A simple breathing orb — the closest thing to a "face" Nova has. Idle breathes
 * slowly, listening pulses faster and brighter, thinking rotates a shimmer, speaking
 * bounces gently in sync-ish with output. All pure Compose, no image assets needed.
 */
@Composable
fun NovaOrb(
    state: NovaState,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "nova_orb")

    val breathDurationMillis = when (state) {
        NovaState.IDLE -> 3200
        NovaState.LISTENING -> 900
        NovaState.THINKING -> 1400
        NovaState.SPEAKING -> 650
    }

    val scale by infiniteTransition.animateFloat(
        initialValue = 0.85f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = breathDurationMillis,
                easing = LinearEasing
            ),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scale"
    )

    val baseColor = MaterialTheme.colorScheme.primary

    val targetColor = when (state) {
        NovaState.IDLE -> baseColor.copy(alpha = 0.55f)
        NovaState.LISTENING -> Color(0xFF34C759)
        NovaState.THINKING -> Color(0xFFFFC107)
        NovaState.SPEAKING -> baseColor
    }

    val color by animateColorAsState(
        targetValue = targetColor,
        label = "color"
    )

    Canvas(
        modifier = modifier.size(72.dp)
    ) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val glowRadius = radius * 1.6f
        val radius = (size.minDimension / 2f) * scale

        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    color,
                    color.copy(alpha = 0.15f)
                ),
                center = center,
                radius = glowRadius
            ),
            radius = glowRadius,
            center = center
        )

        drawCircle(
            color = color,
            radius = radius,
            center = center
        )
    }
}
