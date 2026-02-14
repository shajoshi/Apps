package com.sj.gpsutil.ui

import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.view.Surface
import android.view.WindowManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Stop
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.sj.gpsutil.AppDestinations
import com.sj.gpsutil.tracking.TrackingService
import com.sj.gpsutil.tracking.TrackingState
import com.sj.gpsutil.tracking.TrackingStatus
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.min

private val AccelGreen = Color(0xFF4CAF50)
private val BrakeRed = Color(0xFFF44336)
private val LateralYellow = Color(0xFFFFCA28)
private val TextWhite = Color(0xFFFFFFFF)
private val BgDark = Color(0xFF1A1A2E)

private const val MAX_LEAN_ANGLE = 90f

private const val CHEVRON_THRESHOLD = 3f // m/s² per chevron
private const val MAX_CHEVRONS = 2

@Composable
fun DrivingView(onShowDetails: () -> Unit, onNavigate: (AppDestinations) -> Unit) {
    val latestSample by TrackingState.latestSample.collectAsState()
    val driverEventCount by TrackingState.driverEventCount.collectAsState()
    val trackingStatus by TrackingState.status.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current

    // --- Live accelerometer lean angle (active even before recording) ---
    var liveLeanAngle by remember { mutableFloatStateOf(0f) }
    DisposableEffect(Unit) {
        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        // Simple low-pass filter for smooth lean display
        val alpha = 0.15f
        var filtX = 0f; var filtY = 0f; var filtZ = 0f; var initialized = false
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent?) {
                if (event?.sensor?.type != Sensor.TYPE_ACCELEROMETER) return
                // Remap axes based on display rotation so X=lateral, Y=forward, Z=vertical
                // regardless of portrait/landscape orientation
                val rotation = windowManager.defaultDisplay.rotation
                val rawX = event.values[0]; val rawY = event.values[1]; val rawZ = event.values[2]
                val ax: Float; val ay: Float; val az: Float
                when (rotation) {
                    Surface.ROTATION_0 -> { ax = rawX; ay = rawY; az = rawZ }
                    Surface.ROTATION_90 -> { ax = -rawY; ay = rawX; az = rawZ }
                    Surface.ROTATION_180 -> { ax = -rawX; ay = -rawY; az = rawZ }
                    Surface.ROTATION_270 -> { ax = rawY; ay = -rawX; az = rawZ }
                    else -> { ax = rawX; ay = rawY; az = rawZ }
                }
                if (!initialized) {
                    filtX = ax; filtY = ay; filtZ = az; initialized = true
                } else {
                    filtX += alpha * (ax - filtX)
                    filtY += alpha * (ay - filtY)
                    filtZ += alpha * (az - filtZ)
                }
                // Lean angle: angle of gravity vector projected onto X-Z plane from vertical
                // X is always lateral (after remapping), Z is always vertical
                // Lean = atan2(gx, gz) gives tilt from vertical in the lateral plane
                val lean = Math.toDegrees(atan2(-filtX.toDouble(), filtZ.toDouble())).toFloat()
                liveLeanAngle = lean.coerceIn(-MAX_LEAN_ANGLE, MAX_LEAN_ANGLE)
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
        accelerometer?.let {
            sensorManager.registerListener(listener, it, SensorManager.SENSOR_DELAY_UI)
        }
        onDispose {
            sensorManager.unregisterListener(listener)
        }
    }

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
        // 5-second cycle — accel ramps 2→10→0, rotate through all states
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
    val leanAngle = if (testMode) testLean else (latestSample?.accelLeanAngleDeg ?: liveLeanAngle)
    val speed = if (testMode) testSpeed.toDouble() else (latestSample?.speedKmph ?: 0.0)
    val smoothness = if (testMode) testSmoothness else (latestSample?.driverMetrics?.smoothnessScore ?: 0f)
    val displayAltitude: Double? = if (testMode) testAltitude.toDouble() else latestSample?.altitudeMeters
    val displayEventCount = if (testMode) testEvents else driverEventCount
    
    // Driver event display state (10s timeout or until next event)
    var currentEvent by remember { mutableStateOf<String?>(null) }
    val currentPrimaryEvent = if (testMode) testEvent else latestSample?.driverMetrics?.primaryEvent
    var eventGeneration by remember { mutableIntStateOf(0) }
    
    LaunchedEffect(currentPrimaryEvent) {
        if (currentPrimaryEvent != null && currentPrimaryEvent != "normal" && currentPrimaryEvent != "low_speed") {
            currentEvent = currentPrimaryEvent
            eventGeneration++
        } else {
            // Non-interesting event arrived — start fade-out if something is showing
        }
    }
    
    LaunchedEffect(eventGeneration) {
        if (currentEvent != null) {
            val gen = eventGeneration
            delay(10_000)
            if (eventGeneration == gen) {
                currentEvent = null
            }
        }
    }

    // Road feature display state (10s timeout or until next feature)
    var currentFeature by remember { mutableStateOf<String?>(null) }
    val latestFeature = if (testMode) testFeature else latestSample?.featureDetected
    var featureGeneration by remember { mutableIntStateOf(0) }
    
    LaunchedEffect(latestFeature) {
        if (latestFeature != null) {
            currentFeature = latestFeature
            featureGeneration++
        }
    }
    
    LaunchedEffect(featureGeneration) {
        if (currentFeature != null) {
            val gen = featureGeneration
            delay(10_000)
            if (featureGeneration == gen) {
                currentFeature = null
            }
        }
    }

    // Road quality (always shows latest)
    val roadQuality = if (testMode) testQuality else latestSample?.roadQuality

    // Derived display values for info panel
    val totalSeconds = (currentElapsedMillis / 1000).toInt()
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    val smoothnessColor = when {
        smoothness > 70f -> Color.Green
        smoothness > 40f -> Color(0xFFFF9800)
        else -> Color.Red
    }
    val zRms = latestSample?.accelRMS ?: 0f
    val zPeak = latestSample?.accelMagnitudeMax ?: 0f
    val zStdDev = latestSample?.stdDev ?: 0f

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(BgDark)
            .systemBarsPadding()
            .padding(16.dp)
    ) {
            val isWide = maxWidth > maxHeight
            val shortSide = minOf(maxWidth, maxHeight)
            val scaleFactor = (shortSide / 400.dp).coerceIn(0.7f, 1.3f)

            if (isWide) {
                // --- Wide (landscape) layout: control bar on top, then gauge left + info right ---
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    ControlBar(
                        testMode = testMode,
                        trackingStatus = trackingStatus,
                        onTest = { testMode = true },
                        onStart = { sendDrivingAction(context, TrackingService.ACTION_START) },
                        onShowDetails = onShowDetails,
                        onNavigate = onNavigate
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(modifier = Modifier.fillMaxSize()) {
                        GaugePanel(
                            leanAngle = leanAngle,
                            signedFwdRms = signedFwdRms,
                            signedLatRms = signedLatRms,
                            gaugeScale = 0.90f,
                            chevronTextSize = (20 * scaleFactor).sp,
                            chevronStrokeWidth = 6f * scaleFactor,
                            modifier = Modifier
                                .weight(0.55f)
                                .fillMaxHeight()
                        )
                        InfoPanel(
                            speed = speed,
                            displayAltitude = displayAltitude,
                            roadQuality = roadQuality,
                            currentFeature = currentFeature,
                            currentEvent = currentEvent,
                            displayEventCount = displayEventCount,
                            minutes = minutes,
                            seconds = seconds,
                            smoothness = smoothness,
                            smoothnessColor = smoothnessColor,
                            zRms = zRms,
                            zPeak = zPeak,
                            zStdDev = zStdDev,
                            speedFontSize = (41 * scaleFactor).sp,
                            statusFontSize = (24 * scaleFactor).sp,
                            metricValueSize = (29 * scaleFactor).sp,
                            metricLabelSize = (22 * scaleFactor).sp,
                            spacerHeight = 4.dp,
                            modifier = Modifier
                                .weight(0.45f)
                                .fillMaxHeight()
                        )
                    }
                }
            } else {
                // --- Tall (portrait) layout: single column ---
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    ControlBar(
                        testMode = testMode,
                        trackingStatus = trackingStatus,
                        onTest = { testMode = true },
                        onStart = { sendDrivingAction(context, TrackingService.ACTION_START) },
                        onShowDetails = onShowDetails,
                        onNavigate = onNavigate
                    )
                    Spacer(modifier = Modifier.height(28.dp))

                    // Speed display
                    Text(
                        "${"%.0f".format(speed)} km/h",
                        fontSize = (42 * scaleFactor).sp,
                        color = TextWhite,
                        fontWeight = FontWeight.Bold
                    )
                    displayAltitude?.let { alt ->
                        Text(
                            "${"%.0f".format(alt)} m",
                            fontSize = (42 * scaleFactor).sp,
                            color = Color(0xFFFF9800),
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))

                    GaugePanel(
                        leanAngle = leanAngle,
                        signedFwdRms = signedFwdRms,
                        signedLatRms = signedLatRms,
                        gaugeScale = 0.75f,
                        chevronTextSize = (28 * scaleFactor).sp,
                        chevronStrokeWidth = 8f * scaleFactor,
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                    )

                    StatusRow(
                        roadQuality = roadQuality,
                        currentFeature = currentFeature,
                        currentEvent = currentEvent,
                        fontSize = (29 * scaleFactor).sp
                    )
                    Spacer(modifier = Modifier.height(4.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        MetricLabel("Events", displayEventCount.toFloat(), Color.White, suffix = "", showDecimals = false, valueFontSize = (41 * scaleFactor).sp, labelFontSize = (29 * scaleFactor).sp)
                        MetricLabel("Time", 0f, Color.White, suffix = String.format("%d:%02d", minutes, seconds), showValue = false, valueFontSize = (41 * scaleFactor).sp, labelFontSize = (29 * scaleFactor).sp)
                        MetricLabel("Smooth", smoothness, smoothnessColor, suffix = "", showDecimals = false, valueFontSize = (41 * scaleFactor).sp, labelFontSize = (29 * scaleFactor).sp)
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        MetricLabel("Z RMS", zRms, Color.Cyan, suffix = "", decimals = 1, valueFontSize = (41 * scaleFactor).sp, labelFontSize = (29 * scaleFactor).sp)
                        MetricLabel("Z Peak", zPeak, Color.Cyan, suffix = "", decimals = 1, valueFontSize = (41 * scaleFactor).sp, labelFontSize = (29 * scaleFactor).sp)
                        MetricLabel("StdDev Z", zStdDev, Color.Cyan, suffix = "", decimals = 1, valueFontSize = (41 * scaleFactor).sp, labelFontSize = (29 * scaleFactor).sp)
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }
        }
    }


// --- Extracted helper composables for responsive layout ---

@Composable
private fun ControlBar(
    testMode: Boolean,
    trackingStatus: TrackingStatus,
    onTest: () -> Unit,
    onStart: () -> Unit,
    onShowDetails: () -> Unit,
    onNavigate: (AppDestinations) -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var menuExpanded by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier.fillMaxWidth().height(36.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Burger menu (left)
        Box {
            IconButton(onClick = { menuExpanded = true }, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Filled.MoreVert, contentDescription = "Menu", tint = TextWhite, modifier = Modifier.size(36.dp))
            }
            DropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = { menuExpanded = false }
            ) {
                val testEnabled = !testMode && trackingStatus == TrackingStatus.Idle
                DropdownMenuItem(
                    text = { Text("Test") },
                    onClick = { menuExpanded = false; onTest() },
                    enabled = testEnabled
                )
                DropdownMenuItem(
                    text = { Text("Show Details") },
                    onClick = { menuExpanded = false; onShowDetails() }
                )
                DropdownMenuItem(
                    text = { Text("Tracks") },
                    onClick = { menuExpanded = false; onNavigate(AppDestinations.HISTORY) }
                )
                DropdownMenuItem(
                    text = { Text("Settings") },
                    onClick = { menuExpanded = false; onNavigate(AppDestinations.SETTINGS) },
                    enabled = trackingStatus == TrackingStatus.Idle
                )
            }
        }

        // Centered Start / Pause / Stop icons
        Row(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onStart,
                enabled = trackingStatus != TrackingStatus.Recording,
                modifier = Modifier.size(36.dp),
                colors = IconButtonDefaults.iconButtonColors(
                    contentColor = Color(0xFF4CAF50),
                    disabledContentColor = Color.Gray
                )
            ) {
                Icon(Icons.Filled.PlayArrow, contentDescription = "Start", modifier = Modifier.size(36.dp))
            }
            IconButton(
                onClick = { sendDrivingAction(context, TrackingService.ACTION_PAUSE) },
                enabled = trackingStatus == TrackingStatus.Recording,
                modifier = Modifier.size(36.dp),
                colors = IconButtonDefaults.iconButtonColors(
                    contentColor = Color(0xFFFF9800),
                    disabledContentColor = Color.Gray
                )
            ) {
                Icon(Icons.Filled.Pause, contentDescription = "Pause", modifier = Modifier.size(36.dp))
            }
            IconButton(
                onClick = { sendDrivingAction(context, TrackingService.ACTION_STOP) },
                enabled = trackingStatus != TrackingStatus.Idle,
                modifier = Modifier.size(36.dp),
                colors = IconButtonDefaults.iconButtonColors(
                    contentColor = Color.Red,
                    disabledContentColor = Color.Gray
                )
            ) {
                Icon(Icons.Filled.Stop, contentDescription = "Stop", modifier = Modifier.size(36.dp))
            }
        }

    }
}

@Composable
private fun GaugePanel(
    leanAngle: Float,
    signedFwdRms: Float,
    signedLatRms: Float,
    gaugeScale: Float,
    chevronTextSize: TextUnit,
    chevronStrokeWidth: Float,
    modifier: Modifier = Modifier
) {
    val textMeasurer = rememberTextMeasurer()
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        val dialLimit = 40f
        SemiCircularGauge(
            config = GaugeConfig(
                value = leanAngle.coerceIn(-dialLimit, dialLimit),
                minValue = -dialLimit,
                maxValue = dialLimit,
                label = "LEAN",
                unit = "°",
                centerIsZero = true,
                scale = gaugeScale,
                tickInterval = 5,
                majorTickInterval = 10
            ),
            modifier = Modifier.fillMaxSize()
        )

        // Chevrons overlay canvas
        Canvas(modifier = Modifier.fillMaxSize()) {
            val canvasSize = size.minDimension
            val center = Offset(size.width / 2f, size.height / 2f)
            val gaugeRadius = minOf(size.width / 2f, size.height * 0.75f) * 0.80f * gaugeScale
            val arcWidth = gaugeRadius * 0.12f
            val chevronSize = canvasSize * 0.04f
            val chevronGap = canvasSize * 0.05f
            val chevronStart = gaugeRadius + arcWidth + chevronGap * 0.5f
            val pivotY = size.height * 0.55f

            // --- Up chevrons (acceleration) ---
            val fwdChevrons = if (signedFwdRms > 0) {
                min(MAX_CHEVRONS, (abs(signedFwdRms) / CHEVRON_THRESHOLD).toInt() + 1)
            } else 0
            for (i in 0 until fwdChevrons) {
                val yOffset = pivotY - chevronStart - chevronGap * i
                drawChevronUp(center.x, yOffset, chevronSize, AccelGreen, chevronStrokeWidth)
            }

            if (signedFwdRms > 0) {
                val accelText = "${"%.1f".format(abs(signedFwdRms))} m/s²"
                val accelLayout = textMeasurer.measure(accelText, TextStyle(fontSize = chevronTextSize))
                safeDrawText(
                    textMeasurer = textMeasurer,
                    text = accelText,
                    topLeft = Offset(
                        center.x - accelLayout.size.width / 2f,
                        pivotY - chevronStart - chevronGap * MAX_CHEVRONS - accelLayout.size.height - 4f
                    ),
                    style = TextStyle(color = AccelGreen, fontSize = chevronTextSize, fontWeight = FontWeight.Bold)
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
                drawChevronDown(center.x, yOffset, chevronSize, BrakeRed, chevronStrokeWidth)
            }

            if (signedFwdRms < 0) {
                val brakeText = "${"%.1f".format(abs(signedFwdRms))} m/s²"
                val brakeLayout = textMeasurer.measure(brakeText, TextStyle(fontSize = chevronTextSize))
                safeDrawText(
                    textMeasurer = textMeasurer,
                    text = brakeText,
                    topLeft = Offset(
                        center.x - brakeLayout.size.width / 2f,
                        pivotY + brakeExtraOffset + chevronGap * (MAX_CHEVRONS + 1) + 5f
                    ),
                    style = TextStyle(color = BrakeRed, fontSize = chevronTextSize, fontWeight = FontWeight.Bold)
                )
            }

            // --- Right chevrons (positive lateral) ---
            val rightChevrons = if (signedLatRms > 0) {
                min(MAX_CHEVRONS, (abs(signedLatRms) / CHEVRON_THRESHOLD).toInt() + 1)
            } else 0
            for (i in 0 until rightChevrons) {
                val xOffset = center.x + chevronStart + chevronGap * i
                drawChevronRight(xOffset, pivotY + lateralExtraOffset, chevronSize, LateralYellow, chevronStrokeWidth)
            }

            if (signedLatRms > 0) {
                val latText = "${"%.1f".format(abs(signedLatRms))}"
                val latLayout = textMeasurer.measure(latText, TextStyle(fontSize = chevronTextSize))
                safeDrawText(
                    textMeasurer = textMeasurer,
                    text = latText,
                    topLeft = Offset(
                        center.x + chevronStart + chevronGap * MAX_CHEVRONS + 5f,
                        pivotY + lateralExtraOffset + lateralTextExtraOffset - latLayout.size.height / 2f
                    ),
                    style = TextStyle(color = LateralYellow, fontSize = chevronTextSize, fontWeight = FontWeight.Bold)
                )
            }

            // --- Left chevrons (negative lateral) ---
            val leftChevrons = if (signedLatRms < 0) {
                min(MAX_CHEVRONS, (abs(signedLatRms) / CHEVRON_THRESHOLD).toInt() + 1)
            } else 0
            for (i in 0 until leftChevrons) {
                val xOffset = center.x - chevronStart - chevronGap * i
                drawChevronLeft(xOffset, pivotY + lateralExtraOffset, chevronSize, LateralYellow, chevronStrokeWidth)
            }

            if (signedLatRms < 0) {
                val latText = "${"%.1f".format(abs(signedLatRms))}"
                val latLayout = textMeasurer.measure(latText, TextStyle(fontSize = chevronTextSize))
                safeDrawText(
                    textMeasurer = textMeasurer,
                    text = latText,
                    topLeft = Offset(
                        center.x - chevronStart - chevronGap * MAX_CHEVRONS - 5f - latLayout.size.width,
                        pivotY + lateralExtraOffset + lateralTextExtraOffset - latLayout.size.height / 2f
                    ),
                    style = TextStyle(color = LateralYellow, fontSize = chevronTextSize, fontWeight = FontWeight.Bold)
                )
            }
        }
    }
}

@Composable
private fun StatusRow(
    roadQuality: String?,
    currentFeature: String?,
    currentEvent: String?,
    fontSize: TextUnit = 16.sp
) {
    Spacer(modifier = Modifier.height(4.dp))
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        val qualityColor = when (roadQuality) {
            "smooth" -> Color.Green
            "rough" -> Color.Red
            else -> Color(0xFFFF9800)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Canvas(modifier = Modifier.size(16.dp)) {
                drawCircle(color = qualityColor)
            }
            Text(
                text = " ${roadQuality ?: "--"}",
                color = qualityColor,
                fontSize = (fontSize.value * 0.875f).sp
            )
        }

        Text(
            text = currentFeature?.replace("_", " ")?.replaceFirstChar { it.uppercase() } ?: "",
            color = Color(0xFFCE93D8),
            fontSize = fontSize,
            fontWeight = FontWeight.SemiBold
        )

        currentEvent?.let { event ->
            val (label, color) = when (event) {
                "fall" -> "FALL!" to Color(0xFFFF0000)
                "hard_brake" -> "Hard Brake" to Color.Red
                "hard_accel" -> "Hard Accel" to Color(0xFFFF9800)
                "swerve" -> "Swerve" to Color(0xFFE91E63)
                "aggressive_corner" -> "Agg Corner" to Color(0xFFFFEB3B)
                else -> event to Color.White
            }
            Text(
                text = label,
                color = color,
                fontSize = fontSize,
                fontWeight = FontWeight.Bold
            )
        } ?: Text(text = "", fontSize = fontSize)
    }
}

@Composable
private fun RowScope.InfoPanel(
    speed: Double,
    displayAltitude: Double?,
    roadQuality: String?,
    currentFeature: String?,
    currentEvent: String?,
    displayEventCount: Int,
    minutes: Int,
    seconds: Int,
    smoothness: Float,
    smoothnessColor: Color,
    zRms: Float,
    zPeak: Float,
    zStdDev: Float,
    speedFontSize: TextUnit,
    statusFontSize: TextUnit,
    metricValueSize: TextUnit,
    metricLabelSize: TextUnit,
    spacerHeight: Dp,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // Speed + Altitude
        Text(
            "${"%.0f".format(speed)} km/h",
            fontSize = speedFontSize,
            color = TextWhite,
            fontWeight = FontWeight.Bold
        )
        displayAltitude?.let { alt ->
            Text(
                "${"%.0f".format(alt)} m",
                fontSize = speedFontSize,
                color = Color(0xFFFF9800),
                fontWeight = FontWeight.Bold
            )
        }
        Spacer(modifier = Modifier.height(spacerHeight))

        StatusRow(
            roadQuality = roadQuality,
            currentFeature = currentFeature,
            currentEvent = currentEvent,
            fontSize = statusFontSize
        )
        Spacer(modifier = Modifier.height(spacerHeight))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            MetricLabel("Events", displayEventCount.toFloat(), Color.White, suffix = "", showDecimals = false, valueFontSize = metricValueSize, labelFontSize = metricLabelSize)
            MetricLabel("Time", 0f, Color.White, suffix = String.format("%d:%02d", minutes, seconds), showValue = false, valueFontSize = metricValueSize, labelFontSize = metricLabelSize)
            MetricLabel("Smooth", smoothness, smoothnessColor, suffix = "", showDecimals = false, valueFontSize = metricValueSize, labelFontSize = metricLabelSize)
        }
        Spacer(modifier = Modifier.height(spacerHeight))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            MetricLabel("Z RMS", zRms, Color.Cyan, suffix = "", decimals = 1, valueFontSize = metricValueSize, labelFontSize = metricLabelSize)
            MetricLabel("Z Peak", zPeak, Color.Cyan, suffix = "", decimals = 1, valueFontSize = metricValueSize, labelFontSize = metricLabelSize)
            MetricLabel("StdDev Z", zStdDev, Color.Cyan, suffix = "", decimals = 1, valueFontSize = metricValueSize, labelFontSize = metricLabelSize)
        }
    }
}

@Composable
private fun MetricLabel(
    label: String, value: Float, color: Color,
    suffix: String = " m/s²", showDecimals: Boolean = true,
    showValue: Boolean = true, decimals: Int? = null,
    valueFontSize: TextUnit = 22.sp, labelFontSize: TextUnit = 16.sp
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, color = Color.Gray, fontSize = labelFontSize)
        val fmt = when {
            !showValue -> suffix
            decimals != null -> "${"%.${decimals}f".format(value)}$suffix"
            showDecimals -> "${"%.2f".format(value)}$suffix"
            else -> "${"%.0f".format(value)}$suffix"
        }
        Text(
            fmt,
            color = color,
            fontSize = valueFontSize,
            fontWeight = FontWeight.Bold
        )
    }
}

// --- Chevron drawing helpers ---

private fun DrawScope.drawChevronUp(cx: Float, cy: Float, size: Float, color: Color, strokeWidth: Float = 8f) {
    val path = Path().apply {
        moveTo(cx - size, cy + size * 0.5f)
        lineTo(cx, cy - size * 0.5f)
        lineTo(cx + size, cy + size * 0.5f)
    }
    drawPath(path, color, style = Stroke(width = strokeWidth, cap = StrokeCap.Round))
}

private fun DrawScope.drawChevronDown(cx: Float, cy: Float, size: Float, color: Color, strokeWidth: Float = 8f) {
    val path = Path().apply {
        moveTo(cx - size, cy - size * 0.5f)
        lineTo(cx, cy + size * 0.5f)
        lineTo(cx + size, cy - size * 0.5f)
    }
    drawPath(path, color, style = Stroke(width = strokeWidth, cap = StrokeCap.Round))
}

private fun DrawScope.drawChevronRight(cx: Float, cy: Float, size: Float, color: Color, strokeWidth: Float = 8f) {
    val path = Path().apply {
        moveTo(cx - size * 0.5f, cy - size)
        lineTo(cx + size * 0.5f, cy)
        lineTo(cx - size * 0.5f, cy + size)
    }
    drawPath(path, color, style = Stroke(width = strokeWidth, cap = StrokeCap.Round))
}

private fun DrawScope.drawChevronLeft(cx: Float, cy: Float, size: Float, color: Color, strokeWidth: Float = 8f) {
    val path = Path().apply {
        moveTo(cx + size * 0.5f, cy - size)
        lineTo(cx - size * 0.5f, cy)
        lineTo(cx + size * 0.5f, cy + size)
    }
    drawPath(path, color, style = Stroke(width = strokeWidth, cap = StrokeCap.Round))
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
