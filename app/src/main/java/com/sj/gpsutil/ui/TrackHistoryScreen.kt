package com.sj.gpsutil.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import android.util.Log
import com.sj.gpsutil.AppDestinations
import com.sj.gpsutil.data.SettingsRepository
import com.sj.gpsutil.data.TrackingSettings
import com.sj.gpsutil.data.VehicleProfileRepository
import com.sj.gpsutil.tracking.CalibrateParams
import com.sj.gpsutil.tracking.TrackHistoryRepository
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun TrackHistoryScreen(onNavigate: (AppDestinations) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val settingsRepository = remember { SettingsRepository(context) }
    val trackHistoryRepository = remember { TrackHistoryRepository(context) }
    val logTag = "TrackHistoryScreen"
    val viewModel: TrackHistoryViewModel = viewModel(factory = TrackHistoryViewModel.factory(context))
    val profileRepository = remember { VehicleProfileRepository(context) }
    val settingsState by settingsRepository.settingsFlow.collectAsState(initial = TrackingSettings())
    val folderLabel by remember(settingsState) {
        mutableStateOf(trackHistoryRepository.resolveFolderLabel(settingsState))
    }
    val uiState by viewModel.state.collectAsState()
    val calibrationState by viewModel.calibrationState.collectAsState()
    var selectedInfo by remember { mutableStateOf<TrackHistoryRepository.TrackFileInfo?>(null) }
    var details by remember { mutableStateOf<TrackHistoryRepository.TrackDetails?>(null) }
    var isDetailsLoading by remember { mutableStateOf(false) }
    var availableProfiles by remember { mutableStateOf<List<String>>(emptyList()) }
    val scope = rememberCoroutineScope()

    fun refresh() {
        viewModel.refresh(settingsState)
    }

    fun loadDetails(info: TrackHistoryRepository.TrackFileInfo) {
        scope.launch {
            isDetailsLoading = true
            details = runCatching { trackHistoryRepository.loadDetails(settingsState, info) }
                .getOrNull()
            isDetailsLoading = false
        }
    }

    LaunchedEffect(settingsState.folderUri) {
        if (viewModel.shouldRefresh(settingsState)) {
            Log.d(logTag, "trigger refresh for folder=${settingsState.folderUri}")
            refresh()
        } else {
            Log.d(logTag, "skip refresh; using cached tracks")
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "Saved Tracks",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Button(onClick = { refresh() }, enabled = !uiState.isSearching) {
                Text("Refresh")
            }
            Text(
                text = "Folder: $folderLabel",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f)
            )
        }

        when {
            uiState.isSearching -> {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    CircularProgressIndicator()
                    Text("Searching for tracks…", modifier = Modifier.padding(top = 8.dp))
                }
            }

            uiState.errorMessage != null -> {
                Text(
                    text = uiState.errorMessage ?: "",
                    color = MaterialTheme.colorScheme.error
                )
            }

            uiState.tracks.isEmpty() && uiState.hasSearchCompleted && !uiState.isSearching && uiState.errorMessage == null -> {
                Log.d(logTag, "showing empty state: tracks=0 hasSearchCompleted=${uiState.hasSearchCompleted} isSearching=${uiState.isSearching} error=${uiState.errorMessage}")
                Text(
                    text = "No tracks found",
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            else -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(uiState.tracks, key = { it.name + it.timestampMillis }) { info ->
                        TrackHistoryCard(
                            info = info,
                            onClick = {
                                selectedInfo = info
                                loadDetails(info)
                            },
                            onCalibrateClick = {
                                scope.launch {
                                    availableProfiles = runCatching {
                                        profileRepository.listProfiles(settingsState.folderUri)
                                            .map { it.name }
                                    }.getOrElse { emptyList() }
                                }
                                viewModel.requestCalibrate(info, settingsState)
                            }
                        )
                    }
                }
            }
        }
    }

    // --- Calibration dialogs ---
    if (calibrationState.isValidating || calibrationState.isCalibrating) {
        AlertDialog(
            onDismissRequest = {},
            confirmButton = {},
            title = { Text(if (calibrationState.isValidating) "Validating…" else "Analyzing…") },
            text = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    Text(if (calibrationState.isValidating) "Checking track for raw data…" else "Analyzing track data…")
                }
            }
        )
    }

    if (calibrationState.calibrationError != null) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissCalibrationError() },
            confirmButton = {
                TextButton(onClick = { viewModel.dismissCalibrationError() }) { Text("OK") }
            },
            title = { Text("Calibration Error") },
            text = { Text(calibrationState.calibrationError ?: "") }
        )
    }

    if (calibrationState.showParamsDialog) {
        val target = calibrationState.calibrateTarget
        if (target != null) {
            CalibrateParametersDialog(
                trackName = target.name,
                initialParams = CalibrateParams(
                    minSpeedKmph = settingsState.driverThresholds.minSpeedKmph
                ),
                onAnalyze = { params -> viewModel.runCalibration(settingsState, params) },
                onDismiss = { viewModel.dismissCalibration() }
            )
        }
    }

    val calibResult = calibrationState.calibrationResult
    if (calibResult != null && !calibrationState.isApplyingProfile) {
        val target = calibrationState.calibrateTarget
        ThresholdRecommendationDialog(
            trackName = target?.name ?: "",
            recommendation = calibResult,
            currentProfileName = settingsState.currentProfileName,
            currentCalibration = settingsState.calibration,
            currentDriverThresholds = settingsState.driverThresholds,
            availableProfiles = availableProfiles,
            isApplying = calibrationState.isApplyingProfile,
            onApplyToCurrent = {
                val profileName = settingsState.currentProfileName ?: return@ThresholdRecommendationDialog
                viewModel.applyToProfile(profileName, calibResult, settingsState, settingsState.folderUri)
            },
            onApplyToProfile = { profileName ->
                viewModel.applyToProfile(profileName, calibResult, settingsState, settingsState.folderUri)
            },
            onDismiss = { viewModel.dismissCalibration() }
        )
    }

    if (calibrationState.isApplyingProfile) {
        AlertDialog(
            onDismissRequest = {},
            confirmButton = {},
            title = { Text("Applying…") },
            text = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    Text("Saving profile…")
                }
            }
        )
    }

    if (calibrationState.applySuccess != null) {
        AlertDialog(
            onDismissRequest = {
                viewModel.dismissApplySuccess()
                viewModel.dismissCalibration()
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.dismissApplySuccess()
                    viewModel.dismissCalibration()
                }) { Text("OK") }
            },
            title = { Text("Applied") },
            text = { Text("Thresholds applied to profile: ${calibrationState.applySuccess}") }
        )
    }

    val currentDetails = details
    if (selectedInfo != null) {
        AlertDialog(
            onDismissRequest = {
                selectedInfo = null
                details = null
            },
            confirmButton = {
                TextButton(onClick = {
                    selectedInfo = null
                    details = null
                }) {
                    Text("Close")
                }
            },
            title = { Text(selectedInfo?.name ?: "Track Details") },
            text = {
                if (isDetailsLoading) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.padding(end = 8.dp))
                        Text("Loading details…")
                    }
                } else {
                    val detail = currentDetails
                    DetailContent(detail)
                }
            }
        )
    }
}

@Composable
private fun TrackHistoryCard(
    info: TrackHistoryRepository.TrackFileInfo,
    onClick: () -> Unit,
    onCalibrateClick: () -> Unit
) {
    val zone = remember { ZoneId.systemDefault() }
    val formatter = remember {
        DateTimeFormatter.ofPattern("MMM d, yyyy · HH:mm")
            .withLocale(Locale.getDefault())
            .withZone(zone)
    }
    val displayDate = formatter.format(Instant.ofEpochMilli(info.timestampMillis))
    val sizeLabel = remember(info.sizeBytes) {
        val bytes = info.sizeBytes ?: return@remember "N/A"
        if (bytes >= 1_000_000) {
            String.format(Locale.getDefault(), "%.2f MB", bytes / 1_000_000.0)
        } else if (bytes >= 1_000) {
            String.format(Locale.getDefault(), "%.1f KB", bytes / 1_000.0)
        } else {
            "$bytes B"
        }
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(info.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    "$displayDate • $sizeLabel",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (info.extension.lowercase() == "json") {
                IconButton(onClick = onCalibrateClick) {
                    Icon(
                        imageVector = Icons.Filled.Tune,
                        contentDescription = "Calibrate Thresholds",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}

@Composable
private fun DetailContent(detail: TrackHistoryRepository.TrackDetails?) {
    val locale = Locale.getDefault()
    val zone = remember { ZoneId.systemDefault() }
    val dateFmt = remember {
        DateTimeFormatter.ofPattern("MMM d, yyyy HH:mm:ss")
            .withLocale(locale)
            .withZone(zone)
    }

    fun fmtDistanceMeters(m: Double?): String = m?.let { "%.2f km".format(locale, it / 1000.0) } ?: "N/A"
    fun fmtPoints(p: Int?): String = p?.toString() ?: "N/A"
    fun fmtDuration(ms: Long?): String = ms?.let {
        val totalSec = (it / 1000).coerceAtLeast(0)
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        String.format(locale, "%02d:%02d:%02d", h, m, s)
    } ?: "N/A"
    fun fmtTime(ts: Long?): String = ts?.let { dateFmt.format(Instant.ofEpochMilli(it)) } ?: "N/A"
    fun fmtD(v: Double): String = "%.3f".format(locale, v)
    fun fmtD1(v: Double): String = "%.1f".format(locale, v)

    Column(
        modifier = Modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text("File Name: ${detail?.name ?: "N/A"}")
        Text("Distance: ${fmtDistanceMeters(detail?.distanceMeters)}")
        Text("Points: ${fmtPoints(detail?.pointCount)}")
        Text("Tracking Duration: ${fmtDuration(detail?.durationMillis)}")
        Text("Start: ${fmtTime(detail?.startMillis)}")
        Text("End: ${fmtTime(detail?.endMillis)}")

        // --- Tracking Metrics ---
        detail?.trackingMetrics?.let { tm ->
            Spacer(modifier = Modifier.height(4.dp))
            HorizontalDivider()
            Text("Tracking Metrics", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Text("Accel points: ${tm.accelPoints} / ${tm.totalPoints}")

            if (tm.roadQualityCounts.isNotEmpty()) {
                Text("Road Quality:", fontWeight = FontWeight.SemiBold)
                for (q in listOf("smooth", "average", "rough", "below_speed")) {
                    val cnt = tm.roadQualityCounts[q] ?: continue
                    val pct = if (tm.accelPoints > 0) "%.1f".format(locale, 100.0 * cnt / tm.accelPoints) else "0"
                    Text("  $q: $cnt ($pct%)")
                }
            }

            if (tm.featureCounts.isNotEmpty()) {
                Text("Features:", fontWeight = FontWeight.SemiBold)
                for ((feat, cnt) in tm.featureCounts) {
                    Text("  $feat: $cnt")
                }
            } else {
                Text("No features detected")
            }

            Text("Metric Ranges:", fontWeight = FontWeight.SemiBold)
            Text("  RMS Z: ${fmtD(tm.rmsVertMin)} – ${fmtD(tm.rmsVertMax)} (avg ${fmtD(tm.rmsVertMean)})")
            Text("  Peak Z: ${fmtD(tm.peakZMin)} – ${fmtD(tm.peakZMax)} (avg ${fmtD(tm.peakZMean)})")
            Text("  StdDev Z: ${fmtD(tm.stdDevMin)} – ${fmtD(tm.stdDevMax)} (avg ${fmtD(tm.stdDevMean)})")
            Text("  Peak Ratio: ${fmtD(tm.peakRatioMin)} – ${fmtD(tm.peakRatioMax)} (avg ${fmtD(tm.peakRatioMean)})")
        }

        // --- Driver Metrics ---
        detail?.driverMetrics?.let { dm ->
            Spacer(modifier = Modifier.height(4.dp))
            HorizontalDivider()
            Text("Driver Metrics", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Text("Fixes: ${dm.totalFixes} (${dm.movingFixes} moving)")

            if (dm.eventCounts.isNotEmpty()) {
                Text("Events:", fontWeight = FontWeight.SemiBold)
                for (evt in listOf("hard_brake", "hard_accel", "swerve", "aggressive_corner", "normal", "low_speed")) {
                    val cnt = dm.eventCounts[evt] ?: continue
                    val pct = if (dm.totalFixes > 0) "%.1f".format(locale, 100.0 * cnt / dm.totalFixes) else "0"
                    Text("  $evt: $cnt ($pct%)")
                }
            }

            Text("Smoothness: ${fmtD1(dm.avgSmoothnessScore)} / 100", fontWeight = FontWeight.SemiBold)
            Text("Forward Accel:", fontWeight = FontWeight.SemiBold)
            Text("  Avg RMS: ${fmtD(dm.avgFwdRms)}  Avg Max: ${fmtD(dm.avgFwdMax)}  Peak: ${fmtD(dm.maxFwdMax)}")
            Text("Lateral Accel:", fontWeight = FontWeight.SemiBold)
            Text("  Avg RMS: ${fmtD(dm.avgLatRms)}  Avg Max: ${fmtD(dm.avgLatMax)}  Peak: ${fmtD(dm.maxLatMax)}")
            Text("Max Friction Circle: ${fmtD(dm.maxFrictionCircle)}")
            Text("Lean Angle: avg ${fmtD1(dm.avgLeanAngle)}° max ${fmtD1(dm.maxLeanAngle)}°")
        }
    }
}
