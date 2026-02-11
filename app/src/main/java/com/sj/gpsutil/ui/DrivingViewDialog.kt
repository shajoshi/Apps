package com.sj.gpsutil.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.sj.gpsutil.tracking.TrackingState
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

private val AccelGreen = Color(0xFF4CAF50)
private val BrakeRed = Color(0xFFF44336)
private val LateralYellow = Color(0xFFFFCA28)
private val CircleGray = Color(0xFF9E9E9E)
private val PointerBlue = Color(0xFF2196F3)
private val TextWhite = Color(0xFFFFFFFF)
private val BgDark = Color(0xFF1A1A2E)

private const val CHEVRON_THRESHOLD = 1.5f // m/s² per chevron
private const val MAX_CHEVRONS = 3

@Composable
fun DrivingViewDialog(onDismiss: () -> Unit) {
    val latestSample by TrackingState.latestSample.collectAsState()
    val elapsedMillis by TrackingState.elapsedMillis.collectAsState()
    val driverEventCount by TrackingState.driverEventCount.collectAsState()

    val signedFwdRms = latestSample?.accelSignedFwdRms ?: 0f
    val signedLatRms = latestSample?.accelSignedLatRms ?: 0f
    val leanAngle = latestSample?.accelLeanAngleDeg ?: 0f
    val speed = latestSample?.speedKmph ?: 0.0
    val smoothness = latestSample?.driverMetrics?.smoothnessScore ?: 0f
    
    // Event display state
    var currentEvent by remember { mutableStateOf<String?>(null) }
    var eventAlpha by remember { mutableFloatStateOf(0f) }
    val currentPrimaryEvent = latestSample?.driverMetrics?.primaryEvent
    
    // Handle event display animation
    LaunchedEffect(currentPrimaryEvent) {
        if (currentPrimaryEvent != null && currentPrimaryEvent != "normal" && currentPrimaryEvent != "low_speed") {
            currentEvent = currentPrimaryEvent
            eventAlpha = 1f
            
            // Keep full opacity for 2 seconds
            kotlinx.coroutines.delay(2000)
            
            // Fade over 10 seconds
            val fadeSteps = 100
            for (i in 1..fadeSteps) {
                kotlinx.coroutines.delay(100)
                eventAlpha = 1f - (i.toFloat() / fadeSteps)
            }
            
            // Reset if no new event
            if (currentEvent == currentPrimaryEvent) {
                currentEvent = null
                eventAlpha = 0f
            }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(BgDark)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Title bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Driving View",
                    style = MaterialTheme.typography.titleLarge,
                    color = TextWhite
                )
                Button(onClick = onDismiss) {
                    Text("Close")
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Speed display with event icon
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                // Event icon
                currentEvent?.let { event ->
                    val (icon, color) = when (event) {
                        "hard_brake" -> "⛔" to Color.Red
                        "hard_accel" -> "🔺" to Color(0xFFFF9800)
                        "swerve" -> "⚠️" to Color(0xFFE91E63)
                        "aggressive_corner" -> "⚡" to Color(0xFFFFEB3B)
                        else -> "●" to Color.Green
                    }
                    Text(
                        text = icon,
                        style = MaterialTheme.typography.headlineMedium,
                        color = color.copy(alpha = eventAlpha),
                        modifier = Modifier.padding(end = 8.dp)
                    )
                }
                
                Text(
                    "${"%.1f".format(speed)} km/h",
                    style = MaterialTheme.typography.headlineMedium,
                    color = TextWhite,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Main gauge canvas
            val textMeasurer = rememberTextMeasurer()
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Canvas(
                    modifier = Modifier.size(340.dp)
                ) {
                    val canvasSize = size.minDimension
                    val center = Offset(size.width / 2f, size.height / 2f)
                    val circleRadius = canvasSize * 0.18f
                    val chevronSize = canvasSize * 0.06f
                    val chevronGap = canvasSize * 0.07f

                    // Draw center circle
                    drawCircle(
                        color = CircleGray,
                        radius = circleRadius,
                        center = center,
                        style = Stroke(width = 3f)
                    )

                    // Draw lean angle pointer inside circle
                    drawLeanPointer(center, circleRadius, leanAngle)

                    // Draw lean angle text inside circle
                    val leanFontSize = 28.sp
                    drawText(
                        textMeasurer = textMeasurer,
                        text = "${"%.1f".format(leanAngle)}°",
                        topLeft = Offset(
                            center.x - textMeasurer.measure(
                                "${"%.1f".format(leanAngle)}°",
                                TextStyle(fontSize = leanFontSize)
                            ).size.width / 2f,
                            center.y + circleRadius * 0.3f
                        ),
                        style = TextStyle(color = TextWhite, fontSize = leanFontSize)
                    )

                    // --- Up chevrons (acceleration) ---
                    val fwdChevrons = if (signedFwdRms > 0) {
                        min(MAX_CHEVRONS, (abs(signedFwdRms) / CHEVRON_THRESHOLD).toInt() + 1)
                    } else 0
                    for (i in 0 until fwdChevrons) {
                        val yOffset = center.y - circleRadius - chevronGap * (i + 1)
                        drawChevronUp(center.x, yOffset, chevronSize, AccelGreen)
                    }

                    // Accel label above chevrons
                    if (signedFwdRms > 0) {
                        val labelY = center.y - circleRadius - chevronGap * (fwdChevrons + 1) - 10f
                        val accelText = "${"%.1f".format(abs(signedFwdRms))} m/s²"
                        val accelLayout = textMeasurer.measure(accelText, TextStyle(fontSize = 28.sp))
                        drawText(
                            textMeasurer = textMeasurer,
                            text = accelText,
                            topLeft = Offset(center.x - accelLayout.size.width / 2f, labelY),
                            style = TextStyle(color = AccelGreen, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                        )
                    }

                    // --- Down chevrons (braking) ---
                    val brakeChevrons = if (signedFwdRms < 0) {
                        min(MAX_CHEVRONS, (abs(signedFwdRms) / CHEVRON_THRESHOLD).toInt() + 1)
                    } else 0
                    for (i in 0 until brakeChevrons) {
                        val yOffset = center.y + circleRadius + chevronGap * (i + 1)
                        drawChevronDown(center.x, yOffset, chevronSize, BrakeRed)
                    }

                    // Brake label below chevrons
                    if (signedFwdRms < 0) {
                        val labelY = center.y + circleRadius + chevronGap * (brakeChevrons + 1) + 5f
                        val brakeText = "${"%.1f".format(abs(signedFwdRms))} m/s²"
                        val brakeLayout = textMeasurer.measure(brakeText, TextStyle(fontSize = 28.sp))
                        drawText(
                            textMeasurer = textMeasurer,
                            text = brakeText,
                            topLeft = Offset(center.x - brakeLayout.size.width / 2f, labelY),
                            style = TextStyle(color = BrakeRed, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                        )
                    }

                    // --- Right chevrons (positive lateral) ---
                    val rightChevrons = if (signedLatRms > 0) {
                        min(MAX_CHEVRONS, (abs(signedLatRms) / CHEVRON_THRESHOLD).toInt() + 1)
                    } else 0
                    for (i in 0 until rightChevrons) {
                        val xOffset = center.x + circleRadius + chevronGap * (i + 1)
                        drawChevronRight(xOffset, center.y, chevronSize, LateralYellow)
                    }

                    // Right lateral label
                    if (signedLatRms > 0) {
                        val labelX = center.x + circleRadius + chevronGap * (rightChevrons + 1) + 5f
                        val latText = "${"%.1f".format(abs(signedLatRms))}"
                        val latLayout = textMeasurer.measure(latText, TextStyle(fontSize = 28.sp))
                        drawText(
                            textMeasurer = textMeasurer,
                            text = latText,
                            topLeft = Offset(labelX, center.y - latLayout.size.height / 2f),
                            style = TextStyle(color = LateralYellow, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                        )
                    }

                    // --- Left chevrons (negative lateral) ---
                    val leftChevrons = if (signedLatRms < 0) {
                        min(MAX_CHEVRONS, (abs(signedLatRms) / CHEVRON_THRESHOLD).toInt() + 1)
                    } else 0
                    for (i in 0 until leftChevrons) {
                        val xOffset = center.x - circleRadius - chevronGap * (i + 1)
                        drawChevronLeft(xOffset, center.y, chevronSize, LateralYellow)
                    }

                    // Left lateral label
                    if (signedLatRms < 0) {
                        val labelX = center.x - circleRadius - chevronGap * (leftChevrons + 1) - 5f
                        val latText = "${"%.1f".format(abs(signedLatRms))}"
                        val latLayout = textMeasurer.measure(latText, TextStyle(fontSize = 28.sp))
                        drawText(
                            textMeasurer = textMeasurer,
                            text = latText,
                            topLeft = Offset(labelX - latLayout.size.width, center.y - latLayout.size.height / 2f),
                            style = TextStyle(color = LateralYellow, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                        )
                    }
                }
            }

            // Event counter and tracking time
            val totalSeconds = (elapsedMillis / 1000).toInt()
            val minutes = totalSeconds / 60
            val seconds = totalSeconds % 60
            val smoothnessColor = when {
                smoothness > 70f -> Color.Green
                smoothness > 40f -> Color(0xFFFF9800) // Orange
                else -> Color.Red
            }
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                MetricLabel("Events", driverEventCount.toFloat(), Color.White, suffix = "")
                MetricLabel("Time", (minutes + seconds / 60f).toFloat(), Color.White, suffix = " (${String.format("%d:%02d", minutes, seconds)})")
                MetricLabel("Smooth", smoothness, smoothnessColor, suffix = "")
            }
            
            // Bottom numeric summary
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                MetricLabel("Fwd RMS", signedFwdRms, if (signedFwdRms >= 0) AccelGreen else BrakeRed)
                MetricLabel("Lat RMS", signedLatRms, LateralYellow)
                MetricLabel("Lean", leanAngle, PointerBlue, suffix = "°")
            }
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

@Composable
private fun MetricLabel(label: String, value: Float, color: Color, suffix: String = " m/s²") {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, color = Color.Gray, fontSize = 12.sp)
        Text(
            "${"%.2f".format(value)}$suffix",
            color = color,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

// --- Chevron drawing helpers ---

private fun DrawScope.drawChevronUp(cx: Float, cy: Float, size: Float, color: Color) {
    val path = Path().apply {
        moveTo(cx - size, cy + size * 0.5f)
        lineTo(cx, cy - size * 0.5f)
        lineTo(cx + size, cy + size * 0.5f)
    }
    drawPath(path, color, style = Stroke(width = 4f, cap = StrokeCap.Round))
}

private fun DrawScope.drawChevronDown(cx: Float, cy: Float, size: Float, color: Color) {
    val path = Path().apply {
        moveTo(cx - size, cy - size * 0.5f)
        lineTo(cx, cy + size * 0.5f)
        lineTo(cx + size, cy - size * 0.5f)
    }
    drawPath(path, color, style = Stroke(width = 4f, cap = StrokeCap.Round))
}

private fun DrawScope.drawChevronRight(cx: Float, cy: Float, size: Float, color: Color) {
    val path = Path().apply {
        moveTo(cx - size * 0.5f, cy - size)
        lineTo(cx + size * 0.5f, cy)
        lineTo(cx - size * 0.5f, cy + size)
    }
    drawPath(path, color, style = Stroke(width = 4f, cap = StrokeCap.Round))
}

private fun DrawScope.drawChevronLeft(cx: Float, cy: Float, size: Float, color: Color) {
    val path = Path().apply {
        moveTo(cx + size * 0.5f, cy - size)
        lineTo(cx - size * 0.5f, cy)
        lineTo(cx + size * 0.5f, cy + size)
    }
    drawPath(path, color, style = Stroke(width = 4f, cap = StrokeCap.Round))
}

private fun DrawScope.drawLeanPointer(center: Offset, radius: Float, angleDeg: Float) {
    val angleRad = Math.toRadians(angleDeg.toDouble())
    // Pointer line from center to edge of circle, rotated by lean angle from vertical (12 o'clock)
    val pointerLength = radius * 0.85f
    val endX = center.x + pointerLength * sin(angleRad).toFloat()
    val endY = center.y - pointerLength * cos(angleRad).toFloat()

    // Pointer line
    drawLine(
        color = PointerBlue,
        start = center,
        end = Offset(endX, endY),
        strokeWidth = 4f,
        cap = StrokeCap.Round
    )

    // Small dot at center
    drawCircle(
        color = PointerBlue,
        radius = 6f,
        center = center
    )

    // Small dot at tip
    drawCircle(
        color = PointerBlue,
        radius = 4f,
        center = Offset(endX, endY)
    )
}
