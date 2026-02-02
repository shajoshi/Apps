package com.sj.gpsutil.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import android.view.WindowManager
import androidx.compose.foundation.BorderStroke
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.tasks.Task
import com.sj.gpsutil.data.SettingsRepository
import com.sj.gpsutil.data.TrackingSettings
import com.sj.gpsutil.tracking.TrackingService
import com.sj.gpsutil.tracking.TrackingState
import com.sj.gpsutil.tracking.TrackingSample
import com.sj.gpsutil.tracking.TrackingStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.math.roundToInt
import kotlin.coroutines.resume

@Composable
fun TrackingScreen(modifier: Modifier = Modifier) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val fusedLocationClient = remember { LocationServices.getFusedLocationProviderClient(context) }
    val settingsRepository = remember { SettingsRepository(context) }
    val settingsState by settingsRepository.settingsFlow.collectAsState(initial = TrackingSettings())
    val status by TrackingState.status.collectAsState()
    val latestSample by TrackingState.latestSample.collectAsState()
    val accumulatedMillis by TrackingState.elapsedMillis.collectAsState()
    val recordingStartMillis by TrackingState.recordingStartMillis.collectAsState()
    val pointCount by TrackingState.pointCount.collectAsState()
    val satelliteCount by TrackingState.satelliteCount.collectAsState()
    val currentFileName by TrackingState.currentFileName.collectAsState()
    val distanceMeters by TrackingState.distanceMeters.collectAsState()
    val notMovingMillis by TrackingState.notMovingMillis.collectAsState()
    val skippedPoints by TrackingState.skippedPoints.collectAsState()
    var pendingStart by remember { mutableStateOf(false) }
    val requiredPermissions = remember {
        buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    val permissionsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val granted = requiredPermissions.all { permission ->
            result[permission] == true
        }
        if (pendingStart && granted) {
            sendTrackingAction(context, TrackingService.ACTION_START)
        }
        pendingStart = false
    }

    suspend fun <T> Task<T>.awaitOrNull(): T? = suspendCancellableCoroutine { cont ->
        addOnSuccessListener { cont.resume(it) }
        addOnFailureListener { cont.resume(null) }
        addOnCanceledListener { cont.cancel() }
    }

    fun hasFineLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    LaunchedEffect(status, settingsState.intervalSeconds) {
        if (status != TrackingStatus.Idle) return@LaunchedEffect
        if (!hasFineLocationPermission()) return@LaunchedEffect

        val intervalMillis = settingsState.intervalSeconds.coerceAtLeast(1L) * 1000L

        while (status == TrackingStatus.Idle) {
            val location = runCatching { fusedLocationClient.lastLocation.awaitOrNull() }.getOrNull()
            if (location != null) {
                val sample = TrackingSample(
                    latitude = location.latitude,
                    longitude = location.longitude,
                    altitudeMeters = if (location.hasAltitude()) location.altitude else null,
                    speedKmph = if (location.hasSpeed()) location.speed * 3.6 else null,
                    bearingDegrees = if (location.hasBearing()) location.bearing else null,
                    verticalAccuracyMeters = if (location.hasVerticalAccuracy()) location.verticalAccuracyMeters else null,
                    accuracyMeters = if (location.hasAccuracy()) location.accuracy else null,
                    satelliteCount = null,
                    timestampMillis = location.time
                )
                TrackingState.updateSample(sample)
            }
            delay(intervalMillis)
        }
    }

    // Keep screen on during recording
    val view = LocalView.current
    DisposableEffect(status) {
        if (status == TrackingStatus.Recording) {
            view.keepScreenOn = true
        }
        onDispose {
            view.keepScreenOn = false
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("Tracking", style = MaterialTheme.typography.headlineSmall)
        val accuracyDisplay = latestSample?.accuracyMeters?.let { "±%.1f m".format(it) } ?: "--"
        Text(
            "Status: ${status.name}  |  Accuracy: $accuracyDisplay",
            style = MaterialTheme.typography.bodyLarge
        )

        val locationText = latestSample?.let {
            "${"%.5f".format(it.latitude)}, ${"%.5f".format(it.longitude)}"
        } ?: "--"
        val altitudeText = latestSample?.altitudeMeters?.let { "%.1f m".format(it) } ?: "--"
        val speedText = latestSample?.speedKmph?.let { "%.1f km/h".format(it) } ?: "--"
        val bearingValue = latestSample?.bearingDegrees
        val bearingText = bearingValue?.let { "%.1f°".format(it) } ?: "--"
        val bearingCardinal = bearingToCardinal(bearingValue)
        val verticalAccuracyText = latestSample?.verticalAccuracyMeters?.let { "±%.1f m".format(it) } ?: "--"

        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Last location: $locationText")
            Text("Altitude: $altitudeText")
            Text("Vertical accuracy: $verticalAccuracyText")
            Text("Speed: $speedText")
            val bearingDisplay = if (bearingCardinal != null && bearingText != "--") {
                "$bearingText ($bearingCardinal)"
            } else {
                bearingText
            }
            Text("Bearing: $bearingDisplay")
            val accelVertMeanText = latestSample?.accelVertMean?.let { "%.3f m/s²".format(it) } ?: "--"
            Text("Accel vertical mean: $accelVertMeanText")
            
            // Road quality and feature detection
            val roadQualityText = latestSample?.roadQuality?.let { quality ->
                when (quality) {
                    "smooth" -> "🟢 Smooth"
                    "average" -> "🟡 Average"
                    "rough" -> "🔴 Rough"
                    else -> quality
                }
            } ?: "--"
            Text("Road quality: $roadQualityText")
            
            latestSample?.featureDetected?.let { feature ->
                val featureText = when (feature) {
                    "speed_bump" -> "⚠️ Speed Bump"
                    "pothole" -> "🕳️ Pothole"
                    "bump" -> "⚡ Bump"
                    else -> feature
                }
                Text("Feature: $featureText", color = androidx.compose.ui.graphics.Color.Red)
            }
        }

        // Calibration Mode UI
        if (status == TrackingStatus.Recording) {
            Spacer(modifier = Modifier.height(16.dp))
            Text("Calibration Mode", style = MaterialTheme.typography.titleMedium)
            
            // Mount Calibration
            OutlinedButton(
                onClick = { sendTrackingAction(context, TrackingService.ACTION_CAPTURE_BASELINE) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Capture Mount Baseline (Stationary)")
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            Text("Ground Truth: Road Quality", style = MaterialTheme.typography.labelMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val currentLabel by TrackingState.manualLabel.collectAsState()
                
                Button(
                    onClick = { TrackingState.setManualLabel("smooth") },
                    colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                        containerColor = if (currentLabel == "smooth") androidx.compose.ui.graphics.Color.Green else androidx.compose.ui.graphics.Color.Gray
                    ),
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Smooth")
                }
                
                Button(
                    onClick = { TrackingState.setManualLabel("average") },
                    colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                        containerColor = if (currentLabel == "average") androidx.compose.ui.graphics.Color.Yellow else androidx.compose.ui.graphics.Color.Gray
                    ),
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Avg", color = androidx.compose.ui.graphics.Color.Black)
                }
                
                Button(
                    onClick = { TrackingState.setManualLabel("rough") },
                    colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                        containerColor = if (currentLabel == "rough") androidx.compose.ui.graphics.Color.Red else androidx.compose.ui.graphics.Color.Gray
                    ),
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Rough")
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            Text("Ground Truth: Features (Tap on event)", style = MaterialTheme.typography.labelMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MomentaryHighlightButton(
                    text = "Bump",
                    onClick = { TrackingState.setPendingFeatureLabel("speed_bump") },
                    modifier = Modifier.weight(1f),
                    highlightMillis = 750L
                )

                MomentaryHighlightButton(
                    text = "Pothole",
                    onClick = { TrackingState.setPendingFeatureLabel("pothole") },
                    modifier = Modifier.weight(1f),
                    highlightMillis = 750L
                )

                MomentaryHighlightButton(
                    text = "Jolt",
                    onClick = { TrackingState.setPendingFeatureLabel("bump") },
                    modifier = Modifier.weight(1f),
                    highlightMillis = 750L
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = {
                    val missing = requiredPermissions.filterNot { permission ->
                        ContextCompat.checkSelfPermission(context, permission) ==
                            PackageManager.PERMISSION_GRANTED
                    }
                    if (missing.isNotEmpty()) {
                        pendingStart = true
                        permissionsLauncher.launch(missing.toTypedArray())
                    } else {
                        sendTrackingAction(context, TrackingService.ACTION_START)
                    }
                },
                enabled = status != TrackingStatus.Recording
            ) {
                Text("Start")
            }
            OutlinedButton(
                onClick = { sendTrackingAction(context, TrackingService.ACTION_PAUSE) },
                enabled = status == TrackingStatus.Recording
            ) {
                Text("Pause")
            }
            OutlinedButton(
                onClick = { sendTrackingAction(context, TrackingService.ACTION_STOP) },
                enabled = status != TrackingStatus.Idle
            ) {
                Text("Stop")
            }
        }

        val tickingNow = remember { mutableLongStateOf(System.currentTimeMillis()) }
        LaunchedEffect(recordingStartMillis) {
            if (recordingStartMillis != null) {
                while (recordingStartMillis != null) {
                    tickingNow.longValue = System.currentTimeMillis()
                    delay(1000)
                }
            }
        }
        val runningContribution = recordingStartMillis?.let { start ->
            (tickingNow.longValue - start).coerceAtLeast(0L)
        } ?: 0L
        val totalSeconds = ((accumulatedMillis + runningContribution) / 1000).coerceAtLeast(0L)
        val formattedTime = formatSeconds(totalSeconds)
        Text("Tracking time: $formattedTime")
        val notMovingSeconds = (notMovingMillis / 1000).coerceAtLeast(0L)
        val notMovingDisplay = formatSeconds(notMovingSeconds)
        Text("Not moving for: $notMovingDisplay")
        val distanceKm = distanceMeters / 1000.0
        Text("Distance: ${"%.2f".format(distanceKm)} km")
        Text("Points: $pointCount")
        Text("Skipped points: $skippedPoints")
        Text("Satellites: $satelliteCount")
        val fileLabel = currentFileName ?: "--"
        Text("Current file: $fileLabel")
    }
}

private fun sendTrackingAction(context: Context, action: String) {
    val intent = Intent(context, TrackingService::class.java).apply {
        this.action = action
    }
    if (action == TrackingService.ACTION_START && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        ContextCompat.startForegroundService(context, intent)
    } else {
        context.startService(intent)
    }
}

private fun bearingToCardinal(bearingDegrees: Float?): String? {
    val bearing = bearingDegrees ?: return null
    val normalized = ((bearing % 360) + 360) % 360
    val directions = listOf(
        "N", "NNE", "NE", "ENE",
        "E", "ESE", "SE", "SSE",
        "S", "SSW", "SW", "WSW",
        "W", "WNW", "NW", "NNW"
    )
    val index = (normalized / 22.5).roundToInt() % directions.size
    return directions[index]
}

private fun formatSeconds(totalSeconds: Long): String {
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return "%02d:%02d:%02d".format(hours, minutes, seconds)
}

@Composable
private fun MomentaryHighlightButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    highlightMillis: Long = 750L
) {
    var highlighted by remember { mutableStateOf(false) }

    LaunchedEffect(highlighted) {
        if (highlighted) {
            delay(highlightMillis)
            highlighted = false
        }
    }

    val highlightYellow = Color(0xFFFFEB3B)
    val containerColor = if (highlighted) {
        highlightYellow
    } else {
        Color.Transparent
    }
    val border = if (highlighted) {
        BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
    } else {
        BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    }

    val contentColor = if (highlighted) {
        Color.Black
    } else {
        MaterialTheme.colorScheme.onSurface
    }

    OutlinedButton(
        onClick = {
            highlighted = true
            onClick()
        },
        modifier = modifier,
        border = border,
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = containerColor,
            contentColor = contentColor
        )
    ) {
        Text(text)
    }
}
