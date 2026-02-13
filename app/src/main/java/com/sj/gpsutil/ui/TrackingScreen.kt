package com.sj.gpsutil.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import android.view.WindowManager
import androidx.compose.foundation.BorderStroke
import androidx.core.content.ContextCompat
import com.sj.gpsutil.AppDestinations
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
fun TrackingScreen(onNavigate: (AppDestinations) -> Unit, modifier: Modifier = Modifier) {
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
    var showTrackNameDialog by remember { mutableStateOf(false) }
    var showDrivingView by rememberSaveable { mutableStateOf(true) }
    var trackNameInput by remember { mutableStateOf("") }
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
            if (settingsState.roadCalibrationMode) {
                showTrackNameDialog = true
            } else {
                sendTrackingAction(context, TrackingService.ACTION_START)
            }
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

    if (showDrivingView) {
        // --- Driving View (main view) ---
        DrivingView(onShowDetails = { showDrivingView = false }, onNavigate = onNavigate)
    } else {
        // --- Tracking details view ---
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(0.dp)
        ) {
            // Control bar at top
            TrackingDetailsControlBar(
                trackingStatus = status,
                settingsState = settingsState,
                requiredPermissions = requiredPermissions,
                context = context,
                permissionsLauncher = permissionsLauncher,
                onPendingStart = { pendingStart = true },
                onShowTrackNameDialog = { trackNameInput = ""; showTrackNameDialog = true },
                onShowDrivingView = { showDrivingView = true },
                onNavigate = onNavigate
            )

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                val profileSuffix = settingsState.currentProfileName?.takeIf { it.isNotBlank() }?.let { " - $it" } ?: ""
                Text("Tracking$profileSuffix", style = MaterialTheme.typography.headlineSmall)
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

                // Vertical (Z) metrics
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 8.dp),
                    color = androidx.compose.ui.graphics.Color.Gray
                )
                val rmsText = latestSample?.accelRMS?.let { "%.3f".format(it) } ?: "--"
                val avgRmsText = latestSample?.avgRms?.let { "%.3f".format(it) } ?: "--"
                Text("RMS Z: $rmsText (avg: $avgRmsText)")
                
                val maxMagnitudeText = latestSample?.accelMagnitudeMax?.let { "%.3f".format(it) } ?: "--"
                val avgMaxMagText = latestSample?.avgMaxMagnitude?.let { "%.3f".format(it) } ?: "--"
                Text("Peak Z: $maxMagnitudeText (avg: $avgMaxMagText)")
  
                val stdDevText = latestSample?.stdDev?.let { "%.3f".format(it) } ?: "--"
                val avgStdDevText = latestSample?.avgStdDev?.let { "%.3f".format(it) } ?: "--"
                Text("StdDev Z: $stdDevText (avg: $avgStdDevText)")

                val peakRatioText = latestSample?.peakRatio?.let { "%.3f".format(it) } ?: "--"
                val avgPeakRatioText = latestSample?.avgPeakRatio?.let { "%.3f".format(it) } ?: "--"
                Text("Peak ratio: $peakRatioText (avg: $avgPeakRatioText)")
                
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 8.dp),
                    color = androidx.compose.ui.graphics.Color.Gray
                )
                
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

            // Calibration Mode UI (only when enabled)
            if (status == TrackingStatus.Recording && settingsState.roadCalibrationMode) {
                Spacer(modifier = Modifier.height(16.dp))
                Text("Calibration Mode", style = MaterialTheme.typography.titleMedium)
                
                Spacer(modifier = Modifier.height(8.dp))
                Text("Ground Truth: Road Quality", style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val currentLabel by TrackingState.manualLabel.collectAsState()
                    val labelStartTime by TrackingState.manualLabelStartTime.collectAsState()
                    
                    // Auto-stop timer display
                    val remainingSeconds = remember { mutableStateOf(0) }
                    LaunchedEffect(labelStartTime, currentLabel) {
                        if (currentLabel != null && labelStartTime != null) {
                            while (currentLabel != null) {
                                val elapsed = System.currentTimeMillis() - labelStartTime!!
                                val remaining = (30000 - elapsed) / 1000
                                if (remaining <= 0) {
                                    TrackingState.setManualLabel(null)
                                    break
                                }
                                remainingSeconds.value = remaining.toInt()
                                delay(100)
                            }
                        }
                    }
                    
                    Button(
                        onClick = { 
                            if (currentLabel == "smooth") {
                                TrackingState.setManualLabel(null)
                            } else {
                                TrackingState.setManualLabel("smooth")
                            }
                        },
                        colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                            containerColor = if (currentLabel == "smooth") androidx.compose.ui.graphics.Color.Green else androidx.compose.ui.graphics.Color.Gray
                        ),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(if (currentLabel == "smooth") "Smooth (${remainingSeconds.value}s)" else "Smooth")
                    }
                    
                    Button(
                        onClick = { 
                            if (currentLabel == "rough") {
                                TrackingState.setManualLabel(null)
                            } else {
                                TrackingState.setManualLabel("rough")
                            }
                        },
                        colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                            containerColor = if (currentLabel == "rough") androidx.compose.ui.graphics.Color.Red else androidx.compose.ui.graphics.Color.Gray
                        ),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(if (currentLabel == "rough") "Rough (${remainingSeconds.value}s)" else "Rough")
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
                Text("Ground Truth: Features (Tag 5s after press)", style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val pendingFeature by TrackingState.pendingFeatureLabel.collectAsState()
                    val featureTimestamp by TrackingState.pendingFeatureTimestamp.collectAsState()
                    
                    // Countdown timer for pending feature
                    val featureRemainingSeconds = remember { mutableStateOf(0) }
                    LaunchedEffect(featureTimestamp, pendingFeature) {
                        if (pendingFeature != null && featureTimestamp != null) {
                            while (pendingFeature != null) {
                                val elapsed = System.currentTimeMillis() - featureTimestamp!!
                                val remaining = (5000 - elapsed) / 1000
                                if (remaining <= 0) {
                                    break
                                }
                                featureRemainingSeconds.value = remaining.toInt()
                                delay(100)
                            }
                        }
                    }
                    
                    DelayedFeatureButton(
                        text = "Bump",
                        featureType = "speed_bump",
                        currentFeature = pendingFeature,
                        remainingSeconds = featureRemainingSeconds.value,
                        onClick = { TrackingState.setPendingFeatureLabel("speed_bump") },
                        modifier = Modifier.weight(1f)
                    )

                    DelayedFeatureButton(
                        text = "Pothole",
                        featureType = "pothole",
                        currentFeature = pendingFeature,
                        remainingSeconds = featureRemainingSeconds.value,
                        onClick = { TrackingState.setPendingFeatureLabel("pothole") },
                        modifier = Modifier.weight(1f)
                    )

                    DelayedFeatureButton(
                        text = "Jolt",
                        featureType = "bump",
                        currentFeature = pendingFeature,
                        remainingSeconds = featureRemainingSeconds.value,
                        onClick = { TrackingState.setPendingFeatureLabel("bump") },
                        modifier = Modifier.weight(1f)
                    )
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
    }

    // Track name dialog for calibration mode
    if (showTrackNameDialog) {
        AlertDialog(
            onDismissRequest = { showTrackNameDialog = false },
            title = { Text("Name this calibration track") },
            text = {
                OutlinedTextField(
                    value = trackNameInput,
                    onValueChange = { trackNameInput = it },
                    label = { Text("Track name (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showTrackNameDialog = false
                    val name = trackNameInput.trim().takeIf { it.isNotEmpty() }
                    sendTrackingAction(context, TrackingService.ACTION_START, name)
                }) {
                    Text(if (trackNameInput.trim().isNotEmpty()) "Start" else "Start with default")
                }
            },
            dismissButton = {
                TextButton(onClick = { showTrackNameDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

private fun sendTrackingAction(context: Context, action: String, trackName: String? = null) {
    val intent = Intent(context, TrackingService::class.java).apply {
        this.action = action
        if (trackName != null) {
            putExtra(TrackingService.EXTRA_TRACK_NAME, trackName)
        }
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
private fun TrackingDetailsControlBar(
    trackingStatus: TrackingStatus,
    settingsState: TrackingSettings,
    requiredPermissions: List<String>,
    context: Context,
    permissionsLauncher: androidx.activity.result.ActivityResultLauncher<Array<String>>,
    onPendingStart: () -> Unit,
    onShowTrackNameDialog: () -> Unit,
    onShowDrivingView: () -> Unit,
    onNavigate: (AppDestinations) -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
    ) {
        // Burger menu (left)
        Box {
            IconButton(onClick = { menuExpanded = true }) {
                Icon(Icons.Filled.MoreVert, contentDescription = "Menu")
            }
            DropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = { menuExpanded = false }
            ) {
                DropdownMenuItem(
                    text = { Text("Driving View") },
                    onClick = { menuExpanded = false; onShowDrivingView() }
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
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
        ) {
            IconButton(
                onClick = {
                    val missing = requiredPermissions.filterNot { permission ->
                        ContextCompat.checkSelfPermission(context, permission) ==
                            PackageManager.PERMISSION_GRANTED
                    }
                    if (missing.isNotEmpty()) {
                        onPendingStart()
                        permissionsLauncher.launch(missing.toTypedArray())
                    } else if (settingsState.roadCalibrationMode && trackingStatus == TrackingStatus.Idle) {
                        onShowTrackNameDialog()
                    } else {
                        sendTrackingAction(context, TrackingService.ACTION_START)
                    }
                },
                enabled = trackingStatus != TrackingStatus.Recording,
                colors = IconButtonDefaults.iconButtonColors(
                    contentColor = Color(0xFF4CAF50),
                    disabledContentColor = Color.Gray
                )
            ) {
                Icon(Icons.Filled.PlayArrow, contentDescription = "Start", modifier = Modifier.size(32.dp))
            }
            IconButton(
                onClick = { sendTrackingAction(context, TrackingService.ACTION_PAUSE) },
                enabled = trackingStatus == TrackingStatus.Recording,
                colors = IconButtonDefaults.iconButtonColors(
                    contentColor = Color(0xFFFF9800),
                    disabledContentColor = Color.Gray
                )
            ) {
                Icon(Icons.Filled.Pause, contentDescription = "Pause", modifier = Modifier.size(32.dp))
            }
            IconButton(
                onClick = { sendTrackingAction(context, TrackingService.ACTION_STOP) },
                enabled = trackingStatus != TrackingStatus.Idle,
                colors = IconButtonDefaults.iconButtonColors(
                    contentColor = Color.Red,
                    disabledContentColor = Color.Gray
                )
            ) {
                Icon(Icons.Filled.Stop, contentDescription = "Stop", modifier = Modifier.size(32.dp))
            }
        }

        // Invisible spacer same width as the menu icon to keep icons truly centered
        Spacer(modifier = Modifier.size(48.dp))
    }
}

@Composable
private fun DelayedFeatureButton(
    text: String,
    featureType: String,
    currentFeature: String?,
    remainingSeconds: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isActive = currentFeature == featureType
    val highlightYellow = Color(0xFFFFEB3B)
    
    val containerColor = if (isActive) {
        highlightYellow
    } else {
        Color.Transparent
    }
    
    val border = if (isActive) {
        BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
    } else {
        BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    }

    val contentColor = if (isActive) {
        Color.Black
    } else {
        MaterialTheme.colorScheme.onSurface
    }

    OutlinedButton(
        onClick = onClick,
        modifier = modifier,
        border = border,
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = containerColor,
            contentColor = contentColor
        )
    ) {
        Text(if (isActive) "$text (${remainingSeconds}s)" else text)
    }
}
