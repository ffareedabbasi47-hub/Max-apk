package com.example.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.example.data.model.MaxState
import com.example.ui.theme.CyanPrimary
import com.example.ui.theme.NeonAmberAlert
import com.example.ui.theme.NeonGreenStatus

/**
 * PHASE 11 — SCREEN-SIDE LIGHT / EDGE GLOW
 *
 * Draws a subtle, animated glow along the four screen edges that reflects
 * MAX's current state. This is an in-app Compose overlay only -- it does
 * NOT claim, and cannot provide, hardware-level edge lighting (no Android
 * API exposes that to a normal app). It is purely a visual overlay drawn
 * on top of the existing UI.
 *
 * Design goals from spec: subtle, premium, smooth, battery efficient.
 * - IDLE: near-invisible slow breathing (very low alpha, slow cycle)
 * - LISTENING: expanding pulse from the edges inward
 * - PROCESSING (\"thinking\"): a light sweep travelling around the border
 * - SPEAKING: a faster, slightly irregular pulse (approximates audio-reactive
 *   without needing raw waveform amplitude data, which the TTS engine
 *   doesn't expose)
 * - EXECUTING: a directional sweep along one axis
 *
 * Battery note: a single rememberInfiniteTransition drives everything, and
 * the caller (HomeScreen) is responsible for not composing this at all when
 * the app is backgrounded, since animations only cost anything while composed.
 */
@Composable
fun EdgeGlowOverlay(
    maxState: MaxState,
    modifier: Modifier = Modifier
) {
    val transition = rememberInfiniteTransition(label = "EdgeGlow")

    // Single 0f..1f cycle reused for every state -- kept to ONE animator so
    // this stays cheap regardless of which state is active.
    val cycleDurationMs = when (maxState) {
        MaxState.IDLE -> 4200
        MaxState.WAKE_DETECTED -> 600
        MaxState.LISTENING -> 1400
        MaxState.PROCESSING -> 2200
        MaxState.SPEAKING -> 900
        MaxState.EXECUTING -> 1100
        MaxState.READY -> 1000
        MaxState.ERROR -> 700
    }
    val cycle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(cycleDurationMs, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "EdgeGlowCycle"
    )

    val glowColor = when (maxState) {
        MaxState.EXECUTING -> NeonAmberAlert
        MaxState.SPEAKING -> NeonGreenStatus
        MaxState.ERROR -> Color(0xFFFF5252)
        MaxState.READY -> NeonGreenStatus
        else -> CyanPrimary
    }

    val baseAlpha = when (maxState) {
        MaxState.IDLE -> 0.10f
        MaxState.WAKE_DETECTED -> 0.65f
        MaxState.LISTENING -> 0.55f
        MaxState.PROCESSING -> 0.40f
        MaxState.SPEAKING -> 0.60f
        MaxState.EXECUTING -> 0.50f
        MaxState.READY -> 0.45f
        MaxState.ERROR -> 0.55f
    }

    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val strokeWidth = 6.dp.toPx()

        when (maxState) {
            MaxState.IDLE -> {
                // Slow breathing: alpha oscillates gently, no motion.
                val breath = (kotlin.math.sin(cycle * 2 * Math.PI).toFloat() + 1f) / 2f
                val alpha = baseAlpha * (0.4f + 0.6f * breath)
                drawEdgeBorder(w, h, strokeWidth, glowColor.copy(alpha = alpha))
            }
            MaxState.LISTENING -> {
                // Expanding pulse: alpha ramps then resets each cycle.
                val alpha = baseAlpha * (1f - cycle) + 0.08f
                val inset = cycle * strokeWidth * 1.5f
                drawEdgeBorder(w, h, strokeWidth + inset, glowColor.copy(alpha = alpha.coerceIn(0f, 1f)))
            }
            MaxState.PROCESSING -> {
                // Travelling light: a bright arc moves around the perimeter.
                drawEdgeBorder(w, h, strokeWidth * 0.5f, glowColor.copy(alpha = baseAlpha * 0.35f))
                drawTravellingSweep(w, h, strokeWidth * 1.4f, cycle, glowColor)
            }
            MaxState.SPEAKING -> {
                // Faster pulse approximating audio-reactivity.
                val pulse = (kotlin.math.sin(cycle * 2 * Math.PI * 2).toFloat() + 1f) / 2f
                val alpha = baseAlpha * (0.5f + 0.5f * pulse)
                drawEdgeBorder(w, h, strokeWidth * (0.8f + 0.6f * pulse), glowColor.copy(alpha = alpha))
            }
            MaxState.EXECUTING -> {
                // Directional sweep: light travels left-to-right across the top edge only.
                drawEdgeBorder(w, h, strokeWidth * 0.5f, glowColor.copy(alpha = baseAlpha * 0.3f))
                val sweepX = cycle * w
                drawRoundedGlowDot(sweepX, 0f, glowColor)
                drawRoundedGlowDot(w - sweepX, h, glowColor)
            }
            MaxState.WAKE_DETECTED -> {
                // Quick bright flash-in, same visual language as LISTENING's
                // expanding pulse but faster -- reads as "just activated".
                val alpha = baseAlpha * (1f - cycle) + 0.15f
                drawEdgeBorder(w, h, strokeWidth * (1f + cycle), glowColor.copy(alpha = alpha.coerceIn(0f, 1f)))
            }
            MaxState.READY -> {
                // Calm steady glow, no motion -- distinct from IDLE's slow
                // breathing by being brighter and constant.
                drawEdgeBorder(w, h, strokeWidth, glowColor.copy(alpha = baseAlpha))
            }
            MaxState.ERROR -> {
                // Sharp double-blink to read as an alert without a sound.
                val blink = if ((cycle * 4).toInt() % 2 == 0) baseAlpha else baseAlpha * 0.2f
                drawEdgeBorder(w, h, strokeWidth, glowColor.copy(alpha = blink))
            }
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawEdgeBorder(
    w: Float,
    h: Float,
    strokeWidth: Float,
    color: Color
) {
    if (color.alpha <= 0.01f) return
    drawRect(
        brush = Brush.linearGradient(
            colors = listOf(color, color.copy(alpha = color.alpha * 0.3f), color)
        ),
        topLeft = Offset(strokeWidth / 2, strokeWidth / 2),
        size = androidx.compose.ui.geometry.Size(w - strokeWidth, h - strokeWidth),
        style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
    )
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawTravellingSweep(
    w: Float,
    h: Float,
    strokeWidth: Float,
    cycle: Float,
    color: Color
) {
    // Approximate perimeter position as a fraction 0..1 of total edge length,
    // walked as: top -> right -> bottom -> left.
    val perimeter = 2 * (w + h)
    val pos = cycle * perimeter
    val (x, y) = when {
        pos < w -> pos to 0f
        pos < w + h -> w to (pos - w)
        pos < 2 * w + h -> (w - (pos - w - h)) to h
        else -> 0f to (h - (pos - 2 * w - h))
    }
    drawRoundedGlowDot(x, y, color, radius = strokeWidth * 4f)
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawRoundedGlowDot(
    x: Float,
    y: Float,
    color: Color,
    radius: Float = 40f
) {
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(color.copy(alpha = 0.9f), color.copy(alpha = 0f)),
            center = Offset(x, y),
            radius = radius
        ),
        radius = radius,
        center = Offset(x, y)
    )
}
