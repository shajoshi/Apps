package com.sj.gpsutil.ui

import android.content.Context
import kotlinx.coroutines.flow.first
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.Uri
import android.os.Environment
import android.os.Build
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import android.util.Log
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import android.widget.Toast
import com.sj.gpsutil.data.OutputFormat
import com.sj.gpsutil.data.SettingsRepository
import com.sj.gpsutil.data.TrackingSettings
import com.sj.gpsutil.data.CalibrationSettings
import com.sj.gpsutil.data.DriverThresholdSettings
import com.sj.gpsutil.data.VehicleProfile
import com.sj.gpsutil.AppDestinations
import com.sj.gpsutil.data.VehicleProfileRepository
import com.sj.gpsutil.tracking.TrackingState
import com.sj.gpsutil.tracking.TrackingStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.sqrt

private const val TAG = "SettingsScreen"

@Composable
fun SettingsScreen(onNavigate: (AppDestinations) -> Unit, modifier: Modifier = Modifier) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val repository = remember { SettingsRepository(context) }
    val profileRepository = remember { VehicleProfileRepository(context) }
    val scope = rememberCoroutineScope()
    val settings by repository.settingsFlow.collectAsState(initial = TrackingSettings())
    val trackingStatus by TrackingState.status.collectAsState()
    var previousBaseline by remember { mutableStateOf<FloatArray?>(null) }
    var baselineCaptureInProgress by remember { mutableStateOf(false) }
    var temporaryBaseline by remember { mutableStateOf<FloatArray?>(null) }
    val hasAccelerometer = remember {
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as? android.hardware.SensorManager
        sm?.getDefaultSensor(android.hardware.Sensor.TYPE_ACCELEROMETER) != null
    }

        
    // Wait for real settings before checking profiles — the initial TrackingSettings()
    // has folderUri=null which would look in Downloads instead of the configured folder.
    val folderUri = settings.folderUri
    var defaultProfilesChecked by remember { mutableStateOf(false) }
    LaunchedEffect(folderUri) {
        if (!defaultProfilesChecked || folderUri != null) {
            scope.launch(Dispatchers.IO) {
                // Re-read from DataStore to ensure we have the actual persisted value
                val realSettings = repository.settingsFlow.first()
                val existingProfiles = profileRepository.listProfiles(realSettings.folderUri)
                if (existingProfiles.isEmpty()) {
                    profileRepository.createDefaultProfiles(realSettings.folderUri)
                }
                defaultProfilesChecked = true
            }
        }
    }
    var folderLabel by remember(settings.folderUri) {
        mutableStateOf(folderPathFromUri(context, settings.folderUri))
    }
    var showCalibration by remember { mutableStateOf(false) }

    // Calibration state (as strings for TextFields)
    val cal = settings.calibration
    var rmsSmoothMax by remember(cal) { mutableStateOf(cal.rmsSmoothMax.toString()) }
    var peakThresholdZ by remember(cal) { mutableStateOf(cal.peakThresholdZ.toString()) }
    var movingAverageWindow by remember(cal) { mutableStateOf(cal.movingAverageWindow.toString()) }
    var stdDevSmoothMax by remember(cal) { mutableStateOf(cal.stdDevSmoothMax.toString()) }
    var rmsRoughMin by remember(cal) { mutableStateOf(cal.rmsRoughMin.toString()) }
    var peakRatioRoughMin by remember(cal) { mutableStateOf((cal.peakRatioRoughMin * 100f).toString()) }
    var stdDevRoughMin by remember(cal) { mutableStateOf(cal.stdDevRoughMin.toString()) }
    var magMaxSevereMin by remember(cal) { mutableStateOf(cal.magMaxSevereMin.toString()) }
    var qualityWindowSize by remember(cal) { mutableStateOf(cal.qualityWindowSize.toString()) }

    // Driver threshold state (as strings for TextFields)
    val dt = settings.driverThresholds
    var dtHardBrakeFwdMax by remember(dt) { mutableStateOf(dt.hardBrakeFwdMax.toString()) }
    var dtHardAccelFwdMax by remember(dt) { mutableStateOf(dt.hardAccelFwdMax.toString()) }
    var dtSwerveLatMax by remember(dt) { mutableStateOf(dt.swerveLatMax.toString()) }
    var dtAggressiveCornerLatMax by remember(dt) { mutableStateOf(dt.aggressiveCornerLatMax.toString()) }
    var dtAggressiveCornerDCourse by remember(dt) { mutableStateOf(dt.aggressiveCornerDCourse.toString()) }
    var dtMinSpeedKmph by remember(dt) { mutableStateOf(dt.minSpeedKmph.toString()) }
    var dtSmoothnessRmsMax by remember(dt) { mutableStateOf(dt.smoothnessRmsMax.toString()) }
    var dtFallLeanAngle by remember(dt) { mutableStateOf(dt.fallLeanAngle.toString()) }

    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            takePersistablePermission(context, uri)
            folderLabel = folderPathFromUri(context, uri.toString())
            scope.launch(Dispatchers.IO) {
                repository.updateFolderUri(uri.toString())
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineSmall)

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("Disable point filtering")
            val disableFilteringState = rememberUpdatedState(settings.disablePointFiltering)
            Switch(
                checked = disableFilteringState.value,
                onCheckedChange = { checked ->
                    scope.launch(Dispatchers.IO) {
                        repository.updateDisablePointFiltering(checked)
                    }
                    Toast.makeText(
                        context,
                        if (checked) "Point filtering disabled" else "Point filtering enabled",
                        Toast.LENGTH_LONG
                    ).show()
                }
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("Record acceleration")
            val recordAccelState = rememberUpdatedState(settings.enableAccelerometer)
            Switch(
                checked = recordAccelState.value,
                onCheckedChange = { checked ->
                    scope.launch(Dispatchers.IO) {
                        repository.updateEnableAccelerometer(checked)
                    }
                    Toast.makeText(
                        context,
                        if (checked) "Acceleration recording enabled" else "Acceleration recording disabled",
                        Toast.LENGTH_LONG
                    ).show()
                }
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("Road calibration run")
            val calibrationModeState = rememberUpdatedState(settings.roadCalibrationMode)
            Switch(
                checked = calibrationModeState.value,
                onCheckedChange = { checked ->
                    scope.launch(Dispatchers.IO) {
                        repository.updateRoadCalibrationMode(checked)
                    }
                    Toast.makeText(
                        context,
                        if (checked) "Calibration mode enabled" else "Calibration mode disabled",
                        Toast.LENGTH_LONG
                    ).show()
                }
            )
        }

        val canCaptureBaseline = hasAccelerometer && trackingStatus != TrackingStatus.Recording && !baselineCaptureInProgress
        OutlinedButton(
            onClick = {
                baselineCaptureInProgress = true
                Log.d(TAG, "Baseline capture requested from Settings")
                scope.launch {
                    val gravity = captureBaselineFromAccelerometer(context, durationMillis = 500L)
                    if (gravity != null) {
                        withContext(Dispatchers.IO) {
                            // Note: baseGravityVector is no longer stored in calibration
                            // Gravity is captured per recording session in TrackingService
                        }
                        temporaryBaseline = gravity.copyOf()
                        Log.d(TAG, "Baseline capture successful: ${gravity.joinToString(prefix = "[", postfix = "]")}")
                    } else {
                        Toast.makeText(context, "Unable to capture baseline", Toast.LENGTH_SHORT).show()
                        Log.w(TAG, "Baseline capture failed (no samples or permission issue)")
                    }
                    baselineCaptureInProgress = false
                }
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = canCaptureBaseline
        ) {
            Text(
                when {
                    !hasAccelerometer -> "No accelerometer — baseline not available"
                    baselineCaptureInProgress -> "Capturing baseline..."
                    canCaptureBaseline -> "Capture mount baseline (stationary)"
                    else -> "Stop recording to capture baseline"
                }
            )
        }

        // Display temporary baseline values if captured
        temporaryBaseline?.let { g ->
            if (g.size >= 3) {
                val magnitude = sqrt(g[0] * g[0] + g[1] * g[1] + g[2] * g[2].toDouble()).toFloat()
                Text(
                    "Baseline: x=${"%.2f".format(g[0])}, y=${"%.2f".format(g[1])}, z=${"%.2f".format(g[2])}",
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    "g: ${"%.2f".format(magnitude)} m/s²",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("Allow profile save")
            val allowSaveState = rememberUpdatedState(settings.allowProfileSave)
            Switch(
                checked = allowSaveState.value,
                onCheckedChange = { checked ->
                    scope.launch(Dispatchers.IO) {
                        repository.updateAllowProfileSave(checked)
                    }
                }
            )
        }

        // Calibration entry point
        val profileName = settings.currentProfileName ?: "Default"
        Button(onClick = { showCalibration = true }) {
            Text("Calibration (Profile: $profileName)")
        }

        Spacer(modifier = Modifier.height(8.dp))

        Text("Output format:")
        @OptIn(ExperimentalLayoutApi::class)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutputFormat.entries.forEach { fmt ->
                val isSelected = settings.outputFormat == fmt
                if (isSelected) {
                    Button(onClick = {}) {
                        Text(fmt.name)
                    }
                } else {
                    OutlinedButton(onClick = {
                        scope.launch(Dispatchers.IO) {
                            repository.updateOutputFormat(fmt)
                        }
                        Toast.makeText(context, "Output format: ${fmt.name}", Toast.LENGTH_SHORT).show()
                    }) {
                        Text(fmt.name)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Text("Save folder: ${folderLabel ?: defaultDownloadsPath(context)}")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { folderPicker.launch(null) }) {
                Text("Choose folder")
            }
            OutlinedButton(onClick = {
                folderLabel = null
                scope.launch(Dispatchers.IO) {
                    repository.updateFolderUri(null)
                }
            }) {
                Text("Use Downloads")
            }
        }
    }

    if (showCalibration) {
        CalibrationDialog(
            context = context,
            repository = repository,
            profileRepository = remember { VehicleProfileRepository(context) },
            currentProfileName = settings.currentProfileName,
            folderUri = settings.folderUri,
            allowProfileSave = settings.allowProfileSave,
            initialValues = settings.calibration,
            initialDriverThresholds = settings.driverThresholds,
            rmsSmoothMaxText = rmsSmoothMax,
            peakThresholdZText = peakThresholdZ,
            movingAverageWindowText = movingAverageWindow,
            stdDevSmoothMaxText = stdDevSmoothMax,
            rmsRoughMinText = rmsRoughMin,
            peakRatioRoughMinText = peakRatioRoughMin,
            stdDevRoughMinText = stdDevRoughMin,
            magMaxSevereMinText = magMaxSevereMin,
            qualityWindowSizeText = qualityWindowSize,
            dtHardBrakeFwdMaxText = dtHardBrakeFwdMax,
            dtHardAccelFwdMaxText = dtHardAccelFwdMax,
            dtSwerveLatMaxText = dtSwerveLatMax,
            dtAggressiveCornerLatMaxText = dtAggressiveCornerLatMax,
            dtAggressiveCornerDCourseText = dtAggressiveCornerDCourse,
            dtMinSpeedKmphText = dtMinSpeedKmph,
            dtSmoothnessRmsMaxText = dtSmoothnessRmsMax,
            dtFallLeanAngleText = dtFallLeanAngle,
            onValuesChange = { newVals ->
                rmsSmoothMax = newVals.rmsSmoothMax
                peakThresholdZ = newVals.peakThresholdZ
                movingAverageWindow = newVals.movingAverageWindow
                stdDevSmoothMax = newVals.stdDevSmoothMax
                rmsRoughMin = newVals.rmsRoughMin
                peakRatioRoughMin = newVals.peakRatioRoughMin
                stdDevRoughMin = newVals.stdDevRoughMin
                magMaxSevereMin = newVals.magMaxSevereMin
                qualityWindowSize = newVals.qualityWindowSize
                dtHardBrakeFwdMax = newVals.dtHardBrakeFwdMax
                dtHardAccelFwdMax = newVals.dtHardAccelFwdMax
                dtSwerveLatMax = newVals.dtSwerveLatMax
                dtAggressiveCornerLatMax = newVals.dtAggressiveCornerLatMax
                dtAggressiveCornerDCourse = newVals.dtAggressiveCornerDCourse
                dtMinSpeedKmph = newVals.dtMinSpeedKmph
                dtSmoothnessRmsMax = newVals.dtSmoothnessRmsMax
                dtFallLeanAngle = newVals.dtFallLeanAngle
            },
            onResetDefaults = {
                val defaults = CalibrationSettings()
                rmsSmoothMax = defaults.rmsSmoothMax.toString()
                peakThresholdZ = defaults.peakThresholdZ.toString()
                movingAverageWindow = defaults.movingAverageWindow.toString()
                stdDevSmoothMax = defaults.stdDevSmoothMax.toString()
                rmsRoughMin = defaults.rmsRoughMin.toString()
                peakRatioRoughMin = (defaults.peakRatioRoughMin * 100f).toString()
                stdDevRoughMin = defaults.stdDevRoughMin.toString()
                magMaxSevereMin = defaults.magMaxSevereMin.toString()
                qualityWindowSize = defaults.qualityWindowSize.toString()
                val dtDefaults = DriverThresholdSettings()
                dtHardBrakeFwdMax = dtDefaults.hardBrakeFwdMax.toString()
                dtHardAccelFwdMax = dtDefaults.hardAccelFwdMax.toString()
                dtSwerveLatMax = dtDefaults.swerveLatMax.toString()
                dtAggressiveCornerLatMax = dtDefaults.aggressiveCornerLatMax.toString()
                dtAggressiveCornerDCourse = dtDefaults.aggressiveCornerDCourse.toString()
                dtMinSpeedKmph = dtDefaults.minSpeedKmph.toString()
                dtSmoothnessRmsMax = dtDefaults.smoothnessRmsMax.toString()
                dtFallLeanAngle = dtDefaults.fallLeanAngle.toString()
            },
            onLoadProfile = { profile ->
                rmsSmoothMax = profile.calibration.rmsSmoothMax.toString()
                peakThresholdZ = profile.calibration.peakThresholdZ.toString()
                movingAverageWindow = profile.calibration.movingAverageWindow.toString()
                stdDevSmoothMax = profile.calibration.stdDevSmoothMax.toString()
                rmsRoughMin = profile.calibration.rmsRoughMin.toString()
                peakRatioRoughMin = (profile.calibration.peakRatioRoughMin * 100f).toString()
                stdDevRoughMin = profile.calibration.stdDevRoughMin.toString()
                magMaxSevereMin = profile.calibration.magMaxSevereMin.toString()
                qualityWindowSize = profile.calibration.qualityWindowSize.toString()
                dtHardBrakeFwdMax = profile.driverThresholds.hardBrakeFwdMax.toString()
                dtHardAccelFwdMax = profile.driverThresholds.hardAccelFwdMax.toString()
                dtSwerveLatMax = profile.driverThresholds.swerveLatMax.toString()
                dtAggressiveCornerLatMax = profile.driverThresholds.aggressiveCornerLatMax.toString()
                dtAggressiveCornerDCourse = profile.driverThresholds.aggressiveCornerDCourse.toString()
                dtMinSpeedKmph = profile.driverThresholds.minSpeedKmph.toString()
                dtSmoothnessRmsMax = profile.driverThresholds.smoothnessRmsMax.toString()
                dtFallLeanAngle = profile.driverThresholds.fallLeanAngle.toString()
            },
            onDismiss = { showCalibration = false }
        )
    }
}

@Composable
private fun CalibrationDialog(
    context: Context,
    repository: SettingsRepository,
    profileRepository: VehicleProfileRepository,
    currentProfileName: String?,
    folderUri: String?,
    allowProfileSave: Boolean,
    initialValues: CalibrationSettings,
    initialDriverThresholds: DriverThresholdSettings,
    rmsSmoothMaxText: String,
    peakThresholdZText: String,
    movingAverageWindowText: String,
    stdDevSmoothMaxText: String,
    rmsRoughMinText: String,
    peakRatioRoughMinText: String,
    stdDevRoughMinText: String,
    magMaxSevereMinText: String,
    qualityWindowSizeText: String,
    dtHardBrakeFwdMaxText: String,
    dtHardAccelFwdMaxText: String,
    dtSwerveLatMaxText: String,
    dtAggressiveCornerLatMaxText: String,
    dtAggressiveCornerDCourseText: String,
    dtMinSpeedKmphText: String,
    dtSmoothnessRmsMaxText: String,
    dtFallLeanAngleText: String,
    onValuesChange: (CalibrationTextValues) -> Unit,
    onResetDefaults: () -> Unit,
    onLoadProfile: (VehicleProfile) -> Unit,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var showSaveAsDialog by remember { mutableStateOf(false) }
    var showLoadDialog by remember { mutableStateOf(false) }
    val profileDisplayName = currentProfileName ?: "Default"

    // Helper to build CalibrationTextValues with current state, overriding one field
    fun currentVals(
        rms: String = rmsSmoothMaxText, peak: String = peakThresholdZText,
        maWin: String = movingAverageWindowText, stdSmooth: String = stdDevSmoothMaxText,
        rmsR: String = rmsRoughMinText, peakR: String = peakRatioRoughMinText,
        stdR: String = stdDevRoughMinText, magMax: String = magMaxSevereMinText,
        qWin: String = qualityWindowSizeText,
        hBrake: String = dtHardBrakeFwdMaxText, hAccel: String = dtHardAccelFwdMaxText,
        swerve: String = dtSwerveLatMaxText, aggLat: String = dtAggressiveCornerLatMaxText,
        aggDC: String = dtAggressiveCornerDCourseText, minSpd: String = dtMinSpeedKmphText,
        smRms: String = dtSmoothnessRmsMaxText, fallLean: String = dtFallLeanAngleText
    ) = CalibrationTextValues(rms, peak, maWin, stdSmooth, rmsR, peakR, stdR, magMax, qWin,
        hBrake, hAccel, swerve, aggLat, aggDC, minSpd, smRms, fallLean)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Calibration - Profile: $profileDisplayName") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("Road Quality Thresholds", style = MaterialTheme.typography.titleSmall)
                CalibrationField("RMS smooth max", rmsSmoothMaxText) { onValuesChange(currentVals(rms = it)) }
                CalibrationField("StdDev smooth max", stdDevSmoothMaxText) { onValuesChange(currentVals(stdSmooth = it)) }
                CalibrationField("RMS rough min", rmsRoughMinText) { onValuesChange(currentVals(rmsR = it)) }
                CalibrationField("StdDev rough min", stdDevRoughMinText) { onValuesChange(currentVals(stdR = it)) }
                CalibrationField("Peak Threshold", peakThresholdZText) { onValuesChange(currentVals(peak = it)) }
                CalibrationField("Peak ratio rough min (%)", peakRatioRoughMinText) { onValuesChange(currentVals(peakR = it)) }
                CalibrationField("MagMax severe min", magMaxSevereMinText) { onValuesChange(currentVals(magMax = it)) }
                
                Spacer(modifier = Modifier.height(8.dp))
                Text("Other Settings", style = MaterialTheme.typography.titleSmall)
                CalibrationField("Moving average window", movingAverageWindowText) { onValuesChange(currentVals(maWin = it)) }
                CalibrationField("Quality window size", qualityWindowSizeText) { onValuesChange(currentVals(qWin = it)) }

                Spacer(modifier = Modifier.height(8.dp))
                Text("Driver Metric Thresholds", style = MaterialTheme.typography.titleSmall)
                CalibrationField("Hard brake fwd max (m/s²)", dtHardBrakeFwdMaxText) { onValuesChange(currentVals(hBrake = it)) }
                CalibrationField("Hard accel fwd max (m/s²)", dtHardAccelFwdMaxText) { onValuesChange(currentVals(hAccel = it)) }
                CalibrationField("Swerve lat max (m/s²)", dtSwerveLatMaxText) { onValuesChange(currentVals(swerve = it)) }
                CalibrationField("Aggressive corner lat max (m/s²)", dtAggressiveCornerLatMaxText) { onValuesChange(currentVals(aggLat = it)) }
                CalibrationField("Aggressive corner Δcourse (°)", dtAggressiveCornerDCourseText) { onValuesChange(currentVals(aggDC = it)) }
                CalibrationField("Min speed (km/h)", dtMinSpeedKmphText) { onValuesChange(currentVals(minSpd = it)) }
                CalibrationField("Smoothness RMS max", dtSmoothnessRmsMaxText) { onValuesChange(currentVals(smRms = it)) }
                CalibrationField("Fall lean angle (°)", dtFallLeanAngleText) { onValuesChange(currentVals(fallLean = it)) }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(
                    onClick = {
                        val parsed = parseCalibration(
                            initialValues,
                            rmsSmoothMaxText, peakThresholdZText,
                            movingAverageWindowText,
                            stdDevSmoothMaxText, rmsRoughMinText, peakRatioRoughMinText, stdDevRoughMinText,
                            magMaxSevereMinText,
                            qualityWindowSizeText
                        )
                        val parsedDt = parseDriverThresholds(
                            initialDriverThresholds,
                            dtHardBrakeFwdMaxText, dtHardAccelFwdMaxText,
                            dtSwerveLatMaxText, dtAggressiveCornerLatMaxText,
                            dtAggressiveCornerDCourseText, dtMinSpeedKmphText,
                            dtSmoothnessRmsMaxText, dtFallLeanAngleText
                        )
                        if (parsed == null || parsedDt == null) {
                            Toast.makeText(context, "Enter valid calibration numbers", Toast.LENGTH_LONG).show()
                            return@TextButton
                        }
                        if (currentProfileName == null) {
                            Toast.makeText(context, "Cannot save to Default profile. Use Save As.", Toast.LENGTH_LONG).show()
                            return@TextButton
                        }
                        scope.launch(Dispatchers.IO) {
                            repository.updateCalibration(parsed)
                            repository.updateDriverThresholds(parsedDt)
                            val profile = VehicleProfile(currentProfileName, parsed, parsedDt)
                            profileRepository.saveProfile(profile, folderUri)
                            withContext(Dispatchers.Main) {
                                Toast.makeText(context, "Profile '$currentProfileName' saved", Toast.LENGTH_LONG).show()
                            }
                        }
                    },
                    enabled = allowProfileSave && currentProfileName != null
                ) { Text("Save") }
                
                TextButton(
                    onClick = { showSaveAsDialog = true },
                    enabled = allowProfileSave
                ) { Text("Save As") }
                TextButton(onClick = { showLoadDialog = true }) { Text("Load") }
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(
                    onClick = onResetDefaults,
                    enabled = allowProfileSave
                ) { Text("Reset") }
                TextButton(onClick = onDismiss) { Text("Back") }
            }
        }
    )

    if (showSaveAsDialog) {
        SaveAsDialog(
            context = context,
            repository = repository,
            profileRepository = profileRepository,
            folderUri = folderUri,
            initialValues = initialValues,
            initialDriverThresholds = initialDriverThresholds,
            rmsSmoothMaxText = rmsSmoothMaxText,
            peakThresholdZText = peakThresholdZText,
            movingAverageWindowText = movingAverageWindowText,
            stdDevSmoothMaxText = stdDevSmoothMaxText,
            rmsRoughMinText = rmsRoughMinText,
            peakRatioRoughMinText = peakRatioRoughMinText,
            stdDevRoughMinText = stdDevRoughMinText,
            magMaxSevereMinText = magMaxSevereMinText,
            qualityWindowSizeText = qualityWindowSizeText,
            dtHardBrakeFwdMaxText = dtHardBrakeFwdMaxText,
            dtHardAccelFwdMaxText = dtHardAccelFwdMaxText,
            dtSwerveLatMaxText = dtSwerveLatMaxText,
            dtAggressiveCornerLatMaxText = dtAggressiveCornerLatMaxText,
            dtAggressiveCornerDCourseText = dtAggressiveCornerDCourseText,
            dtMinSpeedKmphText = dtMinSpeedKmphText,
            dtSmoothnessRmsMaxText = dtSmoothnessRmsMaxText,
            dtFallLeanAngleText = dtFallLeanAngleText,
            onDismiss = { showSaveAsDialog = false }
        )
    }

    if (showLoadDialog) {
        LoadProfileDialog(
            context = context,
            repository = repository,
            profileRepository = profileRepository,
            folderUri = folderUri,
            onProfileSelected = { profile ->
                onLoadProfile(profile)
                showLoadDialog = false
            },
            onDismiss = { showLoadDialog = false }
        )
    }
}

@Composable
private fun SaveAsDialog(
    context: Context,
    repository: SettingsRepository,
    profileRepository: VehicleProfileRepository,
    folderUri: String?,
    initialValues: CalibrationSettings,
    initialDriverThresholds: DriverThresholdSettings,
    rmsSmoothMaxText: String,
    peakThresholdZText: String,
    movingAverageWindowText: String,
    stdDevSmoothMaxText: String,
    rmsRoughMinText: String,
    peakRatioRoughMinText: String,
    stdDevRoughMinText: String,
    magMaxSevereMinText: String,
    qualityWindowSizeText: String,
    dtHardBrakeFwdMaxText: String,
    dtHardAccelFwdMaxText: String,
    dtSwerveLatMaxText: String,
    dtAggressiveCornerLatMaxText: String,
    dtAggressiveCornerDCourseText: String,
    dtMinSpeedKmphText: String,
    dtSmoothnessRmsMaxText: String,
    dtFallLeanAngleText: String,
    onDismiss: () -> Unit
) {
    var profileName by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Save Profile As") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Enter a name for this profile:")
                TextField(
                    value = profileName,
                    onValueChange = { profileName = it.filter { c -> c.isLetterOrDigit() || c == ' ' || c == '_' || c == '-' } },
                    label = { Text("Profile Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (profileName.isBlank()) {
                        Toast.makeText(context, "Profile name cannot be empty", Toast.LENGTH_SHORT).show()
                        return@TextButton
                    }
                    val parsed = parseCalibration(
                        initialValues,
                        rmsSmoothMaxText, peakThresholdZText,
                        movingAverageWindowText,
                        stdDevSmoothMaxText, rmsRoughMinText, peakRatioRoughMinText, stdDevRoughMinText,
                        magMaxSevereMinText,
                        qualityWindowSizeText
                    )
                    val parsedDt = parseDriverThresholds(
                        initialDriverThresholds,
                        dtHardBrakeFwdMaxText, dtHardAccelFwdMaxText,
                        dtSwerveLatMaxText, dtAggressiveCornerLatMaxText,
                        dtAggressiveCornerDCourseText, dtMinSpeedKmphText,
                        dtSmoothnessRmsMaxText, dtFallLeanAngleText
                    )
                    if (parsed == null || parsedDt == null) {
                        Toast.makeText(context, "Enter valid calibration numbers", Toast.LENGTH_LONG).show()
                        return@TextButton
                    }
                    scope.launch(Dispatchers.IO) {
                        repository.updateCalibration(parsed)
                        repository.updateDriverThresholds(parsedDt)
                        repository.updateCurrentProfileName(profileName)
                        val profile = VehicleProfile(profileName, parsed, parsedDt)
                        profileRepository.saveProfile(profile, folderUri)
                        withContext(Dispatchers.Main) {
                            Toast.makeText(context, "Profile '$profileName' saved", Toast.LENGTH_LONG).show()
                            onDismiss()
                        }
                    }
                },
                enabled = profileName.isNotBlank()
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun LoadProfileDialog(
    context: Context,
    repository: SettingsRepository,
    profileRepository: VehicleProfileRepository,
    folderUri: String?,
    onProfileSelected: (VehicleProfile) -> Unit,
    onDismiss: () -> Unit
) {
    var profiles by remember { mutableStateOf<List<VehicleProfile>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        scope.launch(Dispatchers.IO) {
            val loadedProfiles = profileRepository.listProfiles(folderUri)
            withContext(Dispatchers.Main) {
                profiles = loadedProfiles
                isLoading = false
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Load Profile") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (isLoading) {
                    Text("Loading profiles...")
                } else if (profiles.isEmpty()) {
                    Text("No profiles found. Create one using Save As.")
                } else {
                    profiles.forEach { profile ->
                        OutlinedButton(
                            onClick = {
                                scope.launch(Dispatchers.IO) {
                                    repository.updateCalibration(profile.calibration)
                                    repository.updateDriverThresholds(profile.driverThresholds)
                                    repository.updateCurrentProfileName(profile.name)
                                    withContext(Dispatchers.Main) {
                                        onProfileSelected(profile)
                                        Toast.makeText(context, "Profile '${profile.name}' loaded", Toast.LENGTH_LONG).show()
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(profile.name)
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun CalibrationField(label: String, value: String, onChange: (String) -> Unit) {
    TextField(
        modifier = Modifier.fillMaxWidth(),
        label = { Text(label) },
        value = value,
        onValueChange = { input ->
            onChange(input.filter { it.isDigit() || it == '.' || it == '-' })
        },
        singleLine = true
    )
}

private data class CalibrationTextValues(
    val rmsSmoothMax: String,
    val peakThresholdZ: String,
    val movingAverageWindow: String,
    val stdDevSmoothMax: String,
    val rmsRoughMin: String,
    val peakRatioRoughMin: String,
    val stdDevRoughMin: String,
    val magMaxSevereMin: String,
    val qualityWindowSize: String,
    val dtHardBrakeFwdMax: String = "",
    val dtHardAccelFwdMax: String = "",
    val dtSwerveLatMax: String = "",
    val dtAggressiveCornerLatMax: String = "",
    val dtAggressiveCornerDCourse: String = "",
    val dtMinSpeedKmph: String = "",
    val dtSmoothnessRmsMax: String = "",
    val dtFallLeanAngle: String = ""
)

private fun parseCalibration(
    initialValues: CalibrationSettings,
    rmsSmoothMax: String,
    peakThresholdZ: String,
    movingAverageWindow: String,
    stdDevSmoothMax: String,
    rmsRoughMin: String,
    peakRatioRoughMin: String,
    stdDevRoughMin: String,
    magMaxSevereMin: String,
    qualityWindowSize: String
): CalibrationSettings? {
    val rmsSmooth = rmsSmoothMax.toFloatOrNull() ?: return null
    val peakThresh = peakThresholdZ.toFloatOrNull() ?: return null
    val maWindow = movingAverageWindow.toIntOrNull() ?: return null
    if (maWindow <= 0) return null
    val stdDevSmooth = stdDevSmoothMax.toFloatOrNull() ?: return null
    val rmsRough = rmsRoughMin.toFloatOrNull() ?: return null
    val peakRoughPercent = peakRatioRoughMin.toFloatOrNull() ?: return null
    if (peakRoughPercent < 1f || peakRoughPercent > 99f) return null
    val peakRough = peakRoughPercent / 100f
    val stdDevRough = stdDevRoughMin.toFloatOrNull() ?: return null
    val magSevereMin = magMaxSevereMin.toFloatOrNull() ?: return null
    if (!magSevereMin.isFinite()) return null
    if (magSevereMin <= 0f) return null
    val qWindowSize = qualityWindowSize.toIntOrNull() ?: return null
    if (qWindowSize <= 0) return null
    return CalibrationSettings(
        rmsSmoothMax = rmsSmooth,
        peakThresholdZ = peakThresh,
        movingAverageWindow = maWindow,
        stdDevSmoothMax = stdDevSmooth,
        rmsRoughMin = rmsRough,
        peakRatioRoughMin = peakRough,
        stdDevRoughMin = stdDevRough,
        magMaxSevereMin = magSevereMin,
        qualityWindowSize = qWindowSize
    )
}

private fun parseDriverThresholds(
    initialValues: DriverThresholdSettings,
    hardBrakeFwdMax: String,
    hardAccelFwdMax: String,
    swerveLatMax: String,
    aggressiveCornerLatMax: String,
    aggressiveCornerDCourse: String,
    minSpeedKmph: String,
    smoothnessRmsMax: String,
    fallLeanAngle: String
): DriverThresholdSettings? {
    val hBrake = hardBrakeFwdMax.toFloatOrNull() ?: return null
    val hAccel = hardAccelFwdMax.toFloatOrNull() ?: return null
    val swerve = swerveLatMax.toFloatOrNull() ?: return null
    val aggLat = aggressiveCornerLatMax.toFloatOrNull() ?: return null
    val aggDC = aggressiveCornerDCourse.toFloatOrNull() ?: return null
    val minSpd = minSpeedKmph.toFloatOrNull() ?: return null
    val smRms = smoothnessRmsMax.toFloatOrNull() ?: return null
    val fLean = fallLeanAngle.toFloatOrNull() ?: return null
    if (hBrake <= 0f || hAccel <= 0f || swerve <= 0f || aggLat <= 0f || aggDC <= 0f || minSpd < 0f || smRms <= 0f || fLean <= 0f) return null
    return DriverThresholdSettings(
        hardBrakeFwdMax = hBrake,
        hardAccelFwdMax = hAccel,
        swerveLatMax = swerve,
        aggressiveCornerLatMax = aggLat,
        aggressiveCornerDCourse = aggDC,
        minSpeedKmph = minSpd,
        smoothnessRmsMax = smRms,
        fallLeanAngle = fLean
    )
}

private suspend fun captureBaselineFromAccelerometer(
    context: Context,
    durationMillis: Long
): FloatArray? {
    val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager ?: return null
    val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return null

    val samples = mutableListOf<FloatArray>()
    val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent?) {
            val e = event ?: return
            if (e.sensor.type != Sensor.TYPE_ACCELEROMETER) return
            samples.add(floatArrayOf(e.values[0], e.values[1], e.values[2]))
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    val registered = withContext(Dispatchers.Main) {
        try {
            sensorManager.registerListener(listener, accelerometer, 10_000)
            true
        } catch (_: SecurityException) {
            false
        }
    }

    if (!registered) return null

    try {
        delay(durationMillis)
    } finally {
        withContext(Dispatchers.Main) {
            sensorManager.unregisterListener(listener)
        }
    }

    if (samples.isEmpty()) return null

    var sumX = 0f
    var sumY = 0f
    var sumZ = 0f
    samples.forEach {
        sumX += it[0]
        sumY += it[1]
        sumZ += it[2]
    }
    val count = samples.size.coerceAtLeast(1)
    return floatArrayOf(sumX / count, sumY / count, sumZ / count)
}

private fun takePersistablePermission(context: Context, uri: Uri) {
    val flags = IntentFlags.FLAG_GRANT_READ or IntentFlags.FLAG_GRANT_WRITE
    context.contentResolver.takePersistableUriPermission(uri, flags)
}

private fun folderPathFromUri(context: Context, uriString: String?): String? {
    if (uriString.isNullOrBlank()) return null
    val uri = Uri.parse(uriString)
    return runCatching {
        val documentId = DocumentsContract.getTreeDocumentId(uri)
        val parts = documentId.split(":", limit = 2)
        val root = parts.getOrNull(0) ?: "primary"
        val relativePath = parts.getOrNull(1)
        val basePath = if (root.equals("primary", ignoreCase = true)) {
            Environment.getExternalStorageDirectory().absolutePath
        } else {
            "/storage/$root"
        }
        if (relativePath.isNullOrBlank()) basePath else "$basePath/${relativePath.trimStart('/')}"
    }.getOrElse {
        androidx.documentfile.provider.DocumentFile.fromTreeUri(context, uri)?.name
    }
}

private fun defaultDownloadsPath(context: Context): String {
    return Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)?.absolutePath
        ?: context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)?.absolutePath
        ?: "Downloads"
}

private object IntentFlags {
    const val FLAG_GRANT_READ = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
    const val FLAG_GRANT_WRITE = android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
}
