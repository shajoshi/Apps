package com.sj.gpsutil.ui

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
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
import androidx.core.content.ContextCompat
import com.sj.gpsutil.tracking.TrackingService
import com.sj.gpsutil.tracking.TrackingState
import com.sj.gpsutil.tracking.TrackingStatus
import kotlin.math.abs
import kotlin.math.min

private val AccelGreen = Color(0xFF4CAF50)
private val BrakeRed = Color(0xFFF44336)
private val LateralYellow = Color(0xFFFFCA28)
private val PointerBlue = Color(0xFF2196F3)
private val TextWhite = Color(0xFFFFFFFF)
private val BgDark = Color(0xFF1A1A2E)

private const val MAX_LEAN_ANGLE = 90f

private const val CHEVRON_THRESHOLD = 3f // m/s² per chevron
private const val MAX_CHEVRONS = 2

@Composable
fun DrivingViewDialog(onDismiss: () -> Unit) {
    val latestSample by TrackingState.latestSample.collectAsState()
    val driverEventCount by TrackingState.driverEventCount.collectAsState()
    val trackingStatus by TrackingState.status.collectAsState()
    
    // Use a state that updates continuously for elapsed time during active recording
    var currentElapsedMillis by remember { mutableStateOf(0L) }
    
    // Update elapsed time continuously when recording is active
    LaunchedEffect(trackingStatus) {
        while (trackingStatus == TrackingStatus.Recording) {
            currentElapsedMillis = TrackingState.getCurrentElapsedMillis()
            delay(1000) // Update every second
        }
        // When not recording, use the static elapsed time
        currentElapsedMillis = TrackingState.elapsedMillis.value
    }
    
    // Initialize when dialog opens
    LaunchedEffect(Unit) {
        currentElapsedMillis = if (trackingStatus == TrackingStatus.Recording) {
            TrackingState.getCurrentElapsedMillis()
        } else {
            TrackingState.elapsedMillis.value
        }
    }

    // --- Test mode state ---
    var testMode by remember { mutableStateOf(false) }
    var testLean by remember { mutableFloatStateOf(0f) }
    var testFwd by remember { mutableFloatStateOf(0f) }
    var testLat by remember { mutableFloatStateOf(0f) }
    var testSpeed by remember { mutableFloatStateOf(0f) }
    var testSmoothness by remember { mutableFloatStateOf(0f) }
    var testEvents by remember { mutableIntStateOf(0) }
    var testEvent by remember { mutableStateOf<String?>(null) }
    var testFeature by remember { mutableStateOf<String?>(null) }
    var testQuality by remember { mutableStateOf<String?>(null) }
    var testAltitude by remember { mutableFloatStateOf(0f) }

    // Test mode animation sequence
    LaunchedEffect(testMode) {
        if (!testMode) return@LaunchedEffect
        val steps = 60
        val stepDelay = 30L

        // Phase 1: Sweep needle from 0 → +90 (right)
        for (i in 0..steps) {
            testLean = (i.toFloat() / steps) * MAX_LEAN_ANGLE
            testSpeed = (i.toFloat() / steps) * 120.0f
            testAltitude = 500f + (i.toFloat() / steps) * 500f
            delay(stepDelay)
        }
        // Phase 2: Sweep needle from +90 → -90 (right to left)
        for (i in 0..steps * 2) {
            testLean = MAX_LEAN_ANGLE - (i.toFloat() / steps) * MAX_LEAN_ANGLE
            delay(stepDelay)
        }
        // Phase 3: Sweep needle from -90 → 0 (settle)
        for (i in 0..steps) {
            testLean = -MAX_LEAN_ANGLE + (i.toFloat() / steps) * MAX_LEAN_ANGLE
            delay(stepDelay)
        }

        // Phase 4: 5-second cycle — accel ramps 2→10→0, rotate through all states
        val events = listOf("hard_brake", "hard_accel", "swerve", "aggressive_corner")
        val features = listOf("speed_bump", "pothole", "bump")
        val qualities = listOf("smooth", "average", "rough")
        val cycleSteps = 100
        val cycleDelay = 50L // 100 steps * 50ms = 5 sec total
        val stateCount = maxOf(events.size, features.size, qualities.size)

        for (i in 0..cycleSteps) {
            val frac = i.toFloat() / cycleSteps
            // Ramp accel: 2 → 10 in first half, 10 → 0 in second half
            val accelVal = if (frac <= 0.5f) {
                2f + (frac / 0.5f) * 8f
            } else {
                10f * (1f - (frac - 0.5f) / 0.5f)
            }
            // Alternate sign: positive first half, negative second half
            testFwd = if (frac <= 0.5f) accelVal else -accelVal
            testLat = if (frac <= 0.5f) accelVal * 0.8f else -accelVal * 0.8f
            testSmoothness = 20f + accelVal * 7f
            testEvents = (accelVal / 2f).toInt().coerceAtLeast(1)
            testSpeed = accelVal * 12f
            testAltitude = 500f + accelVal * 50f
            testLean = if (frac <= 0.5f) accelVal * 5f else -accelVal * 5f

            // Rotate through event/feature/quality states
            val stateIdx = (frac * stateCount).toInt().coerceAtMost(stateCount - 1)
            testEvent = events[stateIdx % events.size]
            testFeature = features[stateIdx % features.size]
            testQuality = qualities[stateIdx % qualities.size]

            delay(cycleDelay)
        }

        // Phase 5: Settle to normal
        testFwd = 0f
        testLat = 0f
        testLean = 0f
        testSpeed = 0f
        testSmoothness = 0f
        testEvents = 0
        testEvent = null
        testFeature = null
        testQuality = null
        testAltitude = 0f
        delay(500)
        testMode = false
    }

    // --- Effective display values (test overrides real) ---
    val signedFwdRms = if (testMode) testFwd else (latestSample?.accelSignedFwdRms ?: 0f)
    val signedLatRms = if (testMode) testLat else (latestSample?.accelSignedLatRms ?: 0f)
    val leanAngle = if (testMode) testLean else (latestSample?.accelLeanAngleDeg ?: 0f)
    val speed = if (testMode) testSpeed.toDouble() else (latestSample?.speedKmph ?: 0.0)
    val smoothness = if (testMode) testSmoothness else (latestSample?.driverMetrics?.smoothnessScore ?: 0f)
    val displayAltitude: Double? = if (testMode) testAltitude.toDouble() else latestSample?.altitudeMeters
    val displayEventCount = if (testMode) testEvents else driverEventCount
    
    // Driver event display state (10s timeout or until next event)
    var currentEvent by remember { mutableStateOf<String?>(null) }
    val currentPrimaryEvent = if (testMode) testEvent else latestSample?.driverMetrics?.primaryEvent
    
    LaunchedEffect(currentPrimaryEvent) {
        if (currentPrimaryEvent != null && currentPrimaryEvent != "normal" && currentPrimaryEvent != "low_speed") {
            currentEvent = currentPrimaryEvent
            delay(10_000)
            if (currentEvent == currentPrimaryEvent) {
                currentEvent = null
            }
        }
    }

    // Road feature display state (10s timeout or until next feature)
    var currentFeature by remember { mutableStateOf<String?>(null) }
    val latestFeature = if (testMode) testFeature else latestSample?.featureDetected
    
    LaunchedEffect(latestFeature) {
        if (latestFeature != null) {
            currentFeature = latestFeature
            delay(10_000)
            if (currentFeature == latestFeature) {
                currentFeature = null
            }
        }
    }

    // Road quality (always shows latest)
    val roadQuality = if (testMode) testQuality else latestSample?.roadQuality

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
            // Control bar: Back + Start/Pause/Stop
            val context = androidx.compose.ui.platform.LocalContext.current
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val testEnabled = !testMode && trackingStatus == TrackingStatus.Idle
                OutlinedButton(
                    onClick = { testMode = true },
                    enabled = testEnabled,
                    border = BorderStroke(1.dp, if (testEnabled) Color.Cyan else Color.Gray)
                ) {
                    Text("Test", color = if (testEnabled) Color.Cyan else Color.Gray)
                }
                OutlinedButton(
                    onClick = onDismiss,
                    border = BorderStroke(1.dp, Color.Gray)
                ) {
                    Text("Back", color = TextWhite)
                }
                Spacer(modifier = Modifier.weight(1f))
                Button(
                    onClick = {
                        sendDrivingAction(context, TrackingService.ACTION_START)
                    },
                    enabled = trackingStatus != TrackingStatus.Recording,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF4CAF50)
                    )
                ) {
                    Text("Start")
                }
                OutlinedButton(
                    onClick = {
                        sendDrivingAction(context, TrackingService.ACTION_PAUSE)
                    },
                    enabled = trackingStatus == TrackingStatus.Recording,
                    border = BorderStroke(1.dp, if (trackingStatus == TrackingStatus.Recording) Color(0xFFFF9800) else Color.Gray)
                ) {
                    Text("Pause", color = if (trackingStatus == TrackingStatus.Recording) Color(0xFFFF9800) else Color.Gray)
                }
                OutlinedButton(
                    onClick = {
                        sendDrivingAction(context, TrackingService.ACTION_STOP)
                    },
                    enabled = trackingStatus != TrackingStatus.Idle,
                    border = BorderStroke(1.dp, if (trackingStatus != TrackingStatus.Idle) Color.Red else Color.Gray)
                ) {
                    Text("Stop", color = if (trackingStatus != TrackingStatus.Idle) Color.Red else Color.Gray)
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Speed and altitude display
            Row(
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.Center
            ) {
                Text(
                    "${"%.1f".format(speed)} km/h",
                    style = MaterialTheme.typography.headlineMedium,
                    color = TextWhite,
                    fontWeight = FontWeight.Bold
                )
                displayAltitude?.let { alt ->
                    Text(
                        "  ${"%.0f".format(alt)}m",
                        color = Color(0xFFFF9800),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(bottom = 2.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Main gauge + chevrons area
            val textMeasurer = rememberTextMeasurer()
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                // Semi-circular lean angle gauge (reusable component)
                SemiCircularGauge(
                    config = GaugeConfig(
                        value = leanAngle,
                        minValue = -MAX_LEAN_ANGLE,
                        maxValue = MAX_LEAN_ANGLE,
                        label = "LEAN",
                        unit = "°",
                        centerIsZero = true,
                        scale = 0.75f,
                        tickInterval = 10,
                        majorTickInterval = 30
                    ),
                    modifier = Modifier.fillMaxSize()
                )

                // Chevrons overlay canvas
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val canvasSize = size.minDimension
                    val center = Offset(size.width / 2f, size.height / 2f)
                    val gaugeRadius = minOf(size.width / 2f, size.height * 0.75f) * 0.80f * 0.75f
                    val arcWidth = gaugeRadius * 0.12f
                    val chevronSize = canvasSize * 0.04f
                    val chevronGap = canvasSize * 0.05f
                    val chevronStart = gaugeRadius + arcWidth + chevronGap * 0.5f
                    // Pivot Y matches the gauge pivot (baseline of semi-circle)
                    val pivotY = size.height * 0.55f

                    // --- Up chevrons (acceleration) ---
                    val fwdChevrons = if (signedFwdRms > 0) {
                        min(MAX_CHEVRONS, (abs(signedFwdRms) / CHEVRON_THRESHOLD).toInt() + 1)
                    } else 0
                    for (i in 0 until fwdChevrons) {
                        val yOffset = pivotY - chevronStart - chevronGap * i
                        drawChevronUp(center.x, yOffset, chevronSize, AccelGreen)
                    }

                    // Accel label at absolute position (above max chevrons)
                    if (signedFwdRms > 0) {
                        val accelText = "${"%.1f".format(abs(signedFwdRms))} m/s²"
                        val accelLayout = textMeasurer.measure(accelText, TextStyle(fontSize = 28.sp))
                        safeDrawText(
                            textMeasurer = textMeasurer,
                            text = accelText,
                            topLeft = Offset(
                                center.x - accelLayout.size.width / 2f,
                                pivotY - chevronStart - chevronGap * MAX_CHEVRONS - accelLayout.size.height - 4f
                            ),
                            style = TextStyle(color = AccelGreen, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                        )
                    }

                    // --- Down chevrons (braking) ---
                    val brakeChevrons = if (signedFwdRms < 0) {
                        min(MAX_CHEVRONS, (abs(signedFwdRms) / CHEVRON_THRESHOLD).toInt() + 1)
                    } else 0
                    val brakeExtraOffset = 45.dp.toPx()
                    val lateralExtraOffset = 15.dp.toPx()
                    val lateralTextExtraOffset = 30.dp.toPx()
                    for (i in 0 until brakeChevrons) {
                        val yOffset = pivotY + brakeExtraOffset + chevronGap * (i + 1)
                        drawChevronDown(center.x, yOffset, chevronSize, BrakeRed)
                    }

                    // Brake label at absolute position (below max chevrons)
                    if (signedFwdRms < 0) {
                        val brakeText = "${"%.1f".format(abs(signedFwdRms))} m/s²"
                        val brakeLayout = textMeasurer.measure(brakeText, TextStyle(fontSize = 28.sp))
                        safeDrawText(
                            textMeasurer = textMeasurer,
                            text = brakeText,
                            topLeft = Offset(
                                center.x - brakeLayout.size.width / 2f,
                                pivotY + brakeExtraOffset + chevronGap * (MAX_CHEVRONS + 1) + 5f
                            ),
                            style = TextStyle(color = BrakeRed, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                        )
                    }

                    // --- Right chevrons (positive lateral) ---
                    val rightChevrons = if (signedLatRms > 0) {
                        min(MAX_CHEVRONS, (abs(signedLatRms) / CHEVRON_THRESHOLD).toInt() + 1)
                    } else 0
                    for (i in 0 until rightChevrons) {
                        val xOffset = center.x + chevronStart + chevronGap * i
                        drawChevronRight(xOffset, pivotY + lateralExtraOffset, chevronSize, LateralYellow)
                    }

                    // Right lateral label
                    if (signedLatRms > 0) {
                        val latText = "${"%.1f".format(abs(signedLatRms))}"
                        val latLayout = textMeasurer.measure(latText, TextStyle(fontSize = 28.sp))
                        safeDrawText(
                            textMeasurer = textMeasurer,
                            text = latText,
                            topLeft = Offset(
                                center.x + chevronStart + chevronGap * MAX_CHEVRONS + 5f,
                                pivotY + lateralExtraOffset + lateralTextExtraOffset - latLayout.size.height / 2f
                            ),
                            style = TextStyle(color = LateralYellow, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                        )
                    }

                    // --- Left chevrons (negative lateral) ---
                    val leftChevrons = if (signedLatRms < 0) {
                        min(MAX_CHEVRONS, (abs(signedLatRms) / CHEVRON_THRESHOLD).toInt() + 1)
                    } else 0
                    for (i in 0 until leftChevrons) {
                        val xOffset = center.x - chevronStart - chevronGap * i
                        drawChevronLeft(xOffset, pivotY + lateralExtraOffset, chevronSize, LateralYellow)
                    }

                    // Left lateral label
                    if (signedLatRms < 0) {
                        val latText = "${"%.1f".format(abs(signedLatRms))}"
                        val latLayout = textMeasurer.measure(latText, TextStyle(fontSize = 28.sp))
                        safeDrawText(
                            textMeasurer = textMeasurer,
                            text = latText,
                            topLeft = Offset(
                                center.x - chevronStart - chevronGap * MAX_CHEVRONS - 5f - latLayout.size.width,
                                pivotY + lateralExtraOffset + lateralTextExtraOffset - latLayout.size.height / 2f
                            ),
                            style = TextStyle(color = LateralYellow, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                        )
                    }
                }
            }

            // Status row: road quality (left), road feature (center), driver event (right)
            Spacer(modifier = Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Road quality circle (left)
                val qualityColor = when (roadQuality) {
                    "smooth" -> Color.Green
                    "rough" -> Color.Red
                    else -> Color(0xFFFF9800) // Orange for average/null
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Canvas(modifier = Modifier.size(16.dp)) {
                        drawCircle(color = qualityColor)
                    }
                    Text(
                        text = " ${roadQuality ?: "--"}",
                        color = qualityColor,
                        fontSize = 14.sp
                    )
                }

                // Road feature (center)
                Text(
                    text = currentFeature?.replace("_", " ")?.replaceFirstChar { it.uppercase() } ?: "",
                    color = Color(0xFFCE93D8),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold
                )

                // Driver event (right)
                currentEvent?.let { event ->
                    val (label, color) = when (event) {
                        "hard_brake" -> "Hard Brake" to Color.Red
                        "hard_accel" -> "Hard Accel" to Color(0xFFFF9800)
                        "swerve" -> "Swerve" to Color(0xFFE91E63)
                        "aggressive_corner" -> "Agg Corner" to Color(0xFFFFEB3B)
                        else -> event to Color.White
                    }
                    Text(
                        text = label,
                        color = color,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                } ?: Text(text = "", fontSize = 16.sp) // placeholder for layout
            }
            Spacer(modifier = Modifier.height(4.dp))

            // Event counter and tracking time
            val totalSeconds = (currentElapsedMillis / 1000).toInt()
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
                MetricLabel("Events", displayEventCount.toFloat(), Color.White, suffix = "", showDecimals = false)
                MetricLabel("Time", 0f, Color.White, suffix = String.format("%d:%02d", minutes, seconds), showValue = false)
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
private fun MetricLabel(label: String, value: Float, color: Color, suffix: String = " m/s²", showDecimals: Boolean = true, showValue: Boolean = true) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, color = Color.Gray, fontSize = 16.sp)
        Text(
            if (showValue) "${if (showDecimals) "%.2f".format(value) else "%.0f".format(value)}$suffix" else suffix,
            color = color,
            fontSize = 22.sp,
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
    drawPath(path, color, style = Stroke(width = 8f, cap = StrokeCap.Round))
}

private fun DrawScope.drawChevronDown(cx: Float, cy: Float, size: Float, color: Color) {
    val path = Path().apply {
        moveTo(cx - size, cy - size * 0.5f)
        lineTo(cx, cy + size * 0.5f)
        lineTo(cx + size, cy - size * 0.5f)
    }
    drawPath(path, color, style = Stroke(width = 8f, cap = StrokeCap.Round))
}

private fun DrawScope.drawChevronRight(cx: Float, cy: Float, size: Float, color: Color) {
    val path = Path().apply {
        moveTo(cx - size * 0.5f, cy - size)
        lineTo(cx + size * 0.5f, cy)
        lineTo(cx - size * 0.5f, cy + size)
    }
    drawPath(path, color, style = Stroke(width = 8f, cap = StrokeCap.Round))
}

private fun DrawScope.drawChevronLeft(cx: Float, cy: Float, size: Float, color: Color) {
    val path = Path().apply {
        moveTo(cx + size * 0.5f, cy - size)
        lineTo(cx - size * 0.5f, cy)
        lineTo(cx + size * 0.5f, cy + size)
    }
    drawPath(path, color, style = Stroke(width = 8f, cap = StrokeCap.Round))
}

// Safe drawText helper: clamps position so text stays within canvas bounds
private fun DrawScope.safeDrawText(
    textMeasurer: androidx.compose.ui.text.TextMeasurer,
    text: String,
    topLeft: Offset,
    style: TextStyle
) {
    val layout = textMeasurer.measure(text, style)
    val safeX = topLeft.x.coerceIn(0f, (size.width - layout.size.width).coerceAtLeast(0f))
    val safeY = topLeft.y.coerceIn(0f, (size.height - layout.size.height).coerceAtLeast(0f))
    drawText(
        textMeasurer = textMeasurer,
        text = text,
        topLeft = Offset(safeX, safeY),
        style = style
    )
}

// Send tracking action from DrivingView (no track name dialog — track is already named on resume)
private fun sendDrivingAction(context: Context, action: String) {
    val intent = Intent(context, TrackingService::class.java).apply {
        this.action = action
    }
    if (action == TrackingService.ACTION_START && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        ContextCompat.startForegroundService(context, intent)
    } else {
        context.startService(intent)
    }
}
