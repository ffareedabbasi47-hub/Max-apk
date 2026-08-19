package com.example.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import com.example.data.model.MaxState
import com.example.ui.theme.CyanPrimary
import com.example.ui.theme.CyanTertiary
import com.example.ui.theme.NeonAmberAlert
import com.example.ui.theme.NeonGreenStatus
import kotlin.math.cos
import kotlin.math.sin

/**
 * MAX's visual identity. This intentionally replaces the old Arc Reactor
 * dashboard widget with a synthetic humanoid made from animated cells,
 * particles and energy links. It remains a single efficient Canvas so it can
 * react to voice state without a large Compose hierarchy.
 */
@Composable
fun ArcReactorView(
    maxState: MaxState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val transition = rememberInfiniteTransition(label = "MaxHumanoid")
    val rotation by transition.animateFloat(
        0f, 360f,
        infiniteRepeatable(tween(18000, easing = LinearEasing)),
        label = "orbit"
    )
    val breathe by transition.animateFloat(
        0.96f, 1.04f,
        infiniteRepeatable(tween(1800, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "breathe"
    )
    val voicePulse by transition.animateFloat(
        0.72f, 1.18f,
        infiniteRepeatable(
            tween(if (maxState == MaxState.SPEAKING) 380 else 1200, easing = FastOutSlowInEasing),
            RepeatMode.Reverse
        ),
        label = "voicePulse"
    )
    val particlePhase by transition.animateFloat(
        0f, 1f,
        infiniteRepeatable(tween(2600, easing = LinearEasing)),
        label = "particles"
    )

    val coreColor = when (maxState) {
        MaxState.IDLE -> CyanPrimary
        MaxState.WAKE_DETECTED -> Color(0xFF66FFC2)
        MaxState.LISTENING -> NeonGreenStatus
        MaxState.PROCESSING -> NeonAmberAlert
        MaxState.EXECUTING -> Color(0xFF00E5FF)
        MaxState.SPEAKING -> CyanTertiary
        MaxState.READY -> NeonGreenStatus
        MaxState.ERROR -> Color(0xFFFF5252)
    }
    val speaking = maxState == MaxState.SPEAKING
    val active = maxState != MaxState.IDLE

    Box(
        modifier = modifier.clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.matchParentSize()) {
            val cx = size.width / 2f
            val cy = size.height * 0.47f
            val unit = size.minDimension
            val scale = unit / 260f
            val glow = if (speaking) 0.70f * voicePulse else if (active) 0.38f else 0.22f

            // Deep atmospheric aura.
            drawCircle(
                brush = Brush.radialGradient(
                    listOf(coreColor.copy(alpha = glow), coreColor.copy(alpha = 0.06f), Color.Transparent),
                    center = Offset(cx, cy),
                    radius = unit * 0.46f * voicePulse
                ),
                center = Offset(cx, cy),
                radius = unit * 0.46f * voicePulse
            )

            // Slow orbital energy rings: subtle, not a dashboard.
            rotate(rotation, pivot = Offset(cx, cy)) {
                drawOval(
                    color = coreColor.copy(alpha = 0.20f),
                    topLeft = Offset(cx - unit * 0.40f, cy - unit * 0.33f),
                    size = androidx.compose.ui.geometry.Size(unit * 0.80f, unit * 0.66f),
                    style = Stroke(width = 1.2.dp.toPx())
                )
                drawArc(
                    color = coreColor.copy(alpha = 0.65f),
                    startAngle = 210f,
                    sweepAngle = 48f,
                    useCenter = false,
                    topLeft = Offset(cx - unit * 0.43f, cy - unit * 0.37f),
                    size = androidx.compose.ui.geometry.Size(unit * 0.86f, unit * 0.74f),
                    style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
                )
            }

            // Deterministic cellular humanoid. Every cell is a small light,
            // and neighboring cells form the robot's synthetic anatomy.
            val cols = 19
            val rows = 24
            val cell = unit * 0.026f
            val points = Array(rows) { arrayOfNulls<Offset>(cols) }

            fun occupied(nx: Float, ny: Float): Boolean {
                // Head/face ellipse.
                val hx = nx / 0.31f
                val hy = (ny + 0.15f) / 0.37f
                val head = hx * hx + hy * hy <= 1.0f
                // Neck and torso/shoulder silhouette.
                val neck = nx in -0.105f..0.105f && ny in 0.18f..0.32f
                val torso = ((nx / 0.39f) * (nx / 0.39f) + ((ny - 0.42f) / 0.25f) * ((ny - 0.42f) / 0.25f)) <= 1.0f
                val jawCut = ny < 0.13f || head
                return (head && jawCut) || neck || torso
            }

            for (r in 0 until rows) {
                val ny = -0.50f + r * (1.02f / (rows - 1))
                for (c in 0 until cols) {
                    val nx = -0.47f + c * (0.94f / (cols - 1))
                    if (occupied(nx, ny)) {
                        val jitter = sin((r * 17 + c * 31).toFloat()) * 0.0035f
                        points[r][c] = Offset(cx + (nx + jitter) * unit, cy + ny * unit)
                    }
                }
            }

            // Energy links first, cells above them.
            for (r in 0 until rows) {
                for (c in 0 until cols) {
                    val p = points[r][c] ?: continue
                    val right = if (c + 1 < cols) points[r][c + 1] else null
                    val down = if (r + 1 < rows) points[r + 1][c] else null
                    if (right != null) drawLine(color = coreColor.copy(alpha = 0.22f), start = p, end = right, strokeWidth = 1f)
                    if (down != null) drawLine(color = coreColor.copy(alpha = 0.16f), start = p, end = down, strokeWidth = 1f)
                }
            }

            // Cells shimmer independently; speaking multiplies their glow.
            for (r in 0 until rows) {
                for (c in 0 until cols) {
                    val p = points[r][c] ?: continue
                    val shimmer = (sin((r * 0.9f + c * 1.7f) + particlePhase * 6.28f) + 1f) / 2f
                    val radius = cell * (0.45f + shimmer * if (speaking) 0.55f else 0.25f)
                    drawCircle(color = coreColor.copy(alpha = 0.22f + shimmer * 0.55f), radius = radius * 2.8f, center = p)
                    drawCircle(color = Color.White.copy(alpha = 0.30f + shimmer * 0.45f), radius = radius * 0.70f, center = p)
                }
            }

            // Face plate: two expressive luminous eyes and a subtle mouth.
            val eyeY = cy - unit * 0.17f
            val eyeDx = unit * 0.105f
            val eyePulse = if (speaking) voicePulse else 1f
            for (eyeX in floatArrayOf(cx - eyeDx, cx + eyeDx)) {
                drawCircle(color = coreColor.copy(alpha = 0.18f), radius = unit * 0.055f * eyePulse, center = Offset(eyeX, eyeY))
                drawOval(
                    color = Color.White,
                    topLeft = Offset(eyeX - unit * 0.043f, eyeY - unit * 0.014f),
                    size = androidx.compose.ui.geometry.Size(unit * 0.086f, unit * 0.028f)
                )
            }

            val mouthY = cy + unit * 0.035f
            val mouthWidth = unit * 0.12f * (if (speaking) 1.0f + 0.15f * voicePulse else 1f)
            drawArc(
                color = coreColor.copy(alpha = if (speaking) 0.95f else 0.45f),
                startAngle = 18f,
                sweepAngle = 144f,
                useCenter = false,
                topLeft = Offset(cx - mouthWidth, mouthY - unit * 0.025f),
                size = androidx.compose.ui.geometry.Size(mouthWidth * 2f, unit * 0.055f),
                style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
            )

            // Floating particles around MAX give the head/torso depth.
            for (i in 0 until 28) {
                val angle = i * 0.77f + particlePhase * 6.28f * if (i % 2 == 0) 1f else -1f
                val rr = unit * (0.34f + ((i * 37) % 100) / 100f * 0.18f)
                val p = Offset(cx + cos(angle) * rr, cy + sin(angle) * rr * 0.78f)
                val a = 0.10f + 0.20f * ((i % 5) / 4f)
                drawCircle(color = coreColor.copy(alpha = a), radius = unit * 0.006f, center = p)
            }

            // Voice bloom: when MAX speaks, the whole humanoid gets a short,
            // bright energy halo instead of merely changing a status label.
            if (speaking) {
                drawCircle(
                    color = coreColor.copy(alpha = 0.10f * voicePulse),
                    center = Offset(cx, cy),
                    radius = unit * 0.40f * voicePulse,
                    style = Stroke(width = 4.dp.toPx())
                )
            }

            // Tiny chin/neck energy marker.
            drawCircle(
                color = coreColor.copy(alpha = if (active) 0.9f else 0.45f),
                center = Offset(cx, cy + unit * 0.30f * breathe),
                radius = unit * 0.012f
            )
        }

        // Minimal identity: MAX remains the name, while the animation carries
        // the personality. No giant dashboard labels.
        Text(
            text = "MAX",
            color = Color.White.copy(alpha = 0.92f),
            fontSize = 19.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.SansSerif,
            letterSpacing = 6.sp,
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }
}
