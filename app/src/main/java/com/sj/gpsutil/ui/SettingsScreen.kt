package com.sj.gpsutil.ui

import android.content.Context
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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
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
import com.sj.gpsutil.data.VehicleProfile
import com.sj.gpsutil.data.VehicleProfileRepository
import com.sj.gpsutil.data.MIN_INTERVAL_SECONDS
import com.sj.gpsutil.tracking.TrackingState
import com.sj.gpsutil.tracking.TrackingStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.sqrt

private const val TAG = "SettingsScreen"

@Composable
fun SettingsScreen(modifier: Modifier = Modifier) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val repository = remember { SettingsRepository(context) }
    val profileRepository = remember { VehicleProfileRepository(context) }
    val scope = rememberCoroutineScope()
    val settings by repository.settingsFlow.collectAsState(initial = TrackingSettings())
    val trackingStatus by TrackingState.status.collectAsState()
    var previousBaseline by remember { mutableStateOf<FloatArray?>(null) }
    var baselineCaptureInProgress by remember { mutableStateOf(false) }
    var temporaryBaseline by remember { mutableStateOf<FloatArray?>(null) }

        
    LaunchedEffect(Unit) {
        scope.launch(Dispatchers.IO) {
            val existingProfiles = profileRepository.listProfiles(settings.folderUri)
            if (existingProfiles.isEmpty()) {
                profileRepository.createDefaultProfiles(settings.folderUri)
            }
        }
    }
    var intervalText by remember(settings.intervalSeconds) {
        mutableStateOf(settings.intervalSeconds.toString())
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
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Interval (sec)")
            TextField(
                modifier = Modifier.width(80.dp),
                value = intervalText,
                onValueChange = { newValue ->
                    val digitsOnly = newValue.filter { it.isDigit() }.take(2)
                    intervalText = digitsOnly
                },
                singleLine = true
            )
            Button(
                modifier = Modifier.width(88.dp),
                onClick = {
                    val parsed = intervalText.toLongOrNull()
                    if (parsed == null) {
                        Toast.makeText(context, "Enter a valid number of seconds", Toast.LENGTH_LONG).show()
                        return@Button
                    }
                    if (parsed < MIN_INTERVAL_SECONDS) {
                        Toast.makeText(context, "Minimum ${MIN_INTERVAL_SECONDS}s", Toast.LENGTH_LONG).show()
                    }
                    val seconds = parsed.coerceAtLeast(MIN_INTERVAL_SECONDS)
                    intervalText = seconds.toString()
                    scope.launch(Dispatchers.IO) {
                        repository.updateIntervalSeconds(seconds)
                    }
                    Toast.makeText(context, "Interval set to ${seconds}s", Toast.LENGTH_LONG).show()
                }
            ) {
                Text("Save")
            }
        }
        val presetOptions = listOf(5L, 10L, 15L, 30L)
        Text("Quick select:")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            presetOptions.forEach { seconds ->
                OutlinedButton(onClick = {
                    intervalText = seconds.toString()
                    scope.launch(Dispatchers.IO) {
                        repository.updateIntervalSeconds(seconds)
                    }
                    Toast.makeText(context, "Interval set to ${seconds}s", Toast.LENGTH_LONG).show()
                }) {
                    Text("${seconds}s")
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

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

        val canCaptureBaseline = trackingStatus != TrackingStatus.Recording && !baselineCaptureInProgress
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

        // Calibration entry point
        val profileName = settings.currentProfileName ?: "Default"
        Button(onClick = { showCalibration = true }) {
            Text("Calibration (Profile: $profileName)")
        }

        Spacer(modifier = Modifier.height(8.dp))

        Text("Output format:")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val currentFormat = settings.outputFormat
            OutlinedButton(
                onClick = {
                    scope.launch(Dispatchers.IO) {
                        repository.updateOutputFormat(OutputFormat.KML)
                    }
                    Toast.makeText(context, "Output format: KML", Toast.LENGTH_LONG).show()
                },
                enabled = currentFormat != OutputFormat.KML
            ) {
                Text("KML")
            }
            OutlinedButton(
                onClick = {
                    scope.launch(Dispatchers.IO) {
                        repository.updateOutputFormat(OutputFormat.GPX)
                    }
                    Toast.makeText(context, "Output format: GPX", Toast.LENGTH_LONG).show()
                },
                enabled = currentFormat != OutputFormat.GPX
            ) {
                Text("GPX")
            }
            OutlinedButton(
                onClick = {
                    scope.launch(Dispatchers.IO) {
                        repository.updateOutputFormat(OutputFormat.JSON)
                    }
                    Toast.makeText(context, "Output format: JSON", Toast.LENGTH_LONG).show()
                },
                enabled = currentFormat != OutputFormat.JSON
            ) {
                Text("JSON")
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
            initialValues = settings.calibration,
            rmsSmoothMaxText = rmsSmoothMax,
            peakThresholdZText = peakThresholdZ,
            movingAverageWindowText = movingAverageWindow,
            stdDevSmoothMaxText = stdDevSmoothMax,
            rmsRoughMinText = rmsRoughMin,
            peakRatioRoughMinText = peakRatioRoughMin,
            stdDevRoughMinText = stdDevRoughMin,
            magMaxSevereMinText = magMaxSevereMin,
            qualityWindowSizeText = qualityWindowSize,
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
    initialValues: CalibrationSettings,
    rmsSmoothMaxText: String,
    peakThresholdZText: String,
    movingAverageWindowText: String,
    stdDevSmoothMaxText: String,
    rmsRoughMinText: String,
    peakRatioRoughMinText: String,
    stdDevRoughMinText: String,
    magMaxSevereMinText: String,
    qualityWindowSizeText: String,
    onValuesChange: (CalibrationTextValues) -> Unit,
    onResetDefaults: () -> Unit,
    onLoadProfile: (VehicleProfile) -> Unit,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var showSaveAsDialog by remember { mutableStateOf(false) }
    var showLoadDialog by remember { mutableStateOf(false) }
    val profileDisplayName = currentProfileName ?: "Default"

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Calibration - Profile: $profileDisplayName") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("Road Quality Thresholds", style = MaterialTheme.typography.titleSmall)
                CalibrationField("RMS smooth max", rmsSmoothMaxText) { onValuesChange(CalibrationTextValues(it, peakThresholdZText, movingAverageWindowText, stdDevSmoothMaxText, rmsRoughMinText, peakRatioRoughMinText, stdDevRoughMinText, magMaxSevereMinText, qualityWindowSizeText)) }
                CalibrationField("StdDev smooth max", stdDevSmoothMaxText) { onValuesChange(CalibrationTextValues(rmsSmoothMaxText, peakThresholdZText, movingAverageWindowText, it, rmsRoughMinText, peakRatioRoughMinText, stdDevRoughMinText, magMaxSevereMinText, qualityWindowSizeText)) }
                CalibrationField("RMS rough min", rmsRoughMinText) { onValuesChange(CalibrationTextValues(rmsSmoothMaxText, peakThresholdZText, movingAverageWindowText, stdDevSmoothMaxText, it, peakRatioRoughMinText, stdDevRoughMinText, magMaxSevereMinText, qualityWindowSizeText)) }
                CalibrationField("StdDev rough min", stdDevRoughMinText) { onValuesChange(CalibrationTextValues(rmsSmoothMaxText, peakThresholdZText, movingAverageWindowText, stdDevSmoothMaxText, rmsRoughMinText, peakRatioRoughMinText, it, magMaxSevereMinText, qualityWindowSizeText)) }
                CalibrationField("Peak Threshold", peakThresholdZText) { onValuesChange(CalibrationTextValues(rmsSmoothMaxText, it, movingAverageWindowText, stdDevSmoothMaxText, rmsRoughMinText, peakRatioRoughMinText, stdDevRoughMinText, magMaxSevereMinText, qualityWindowSizeText)) }
                CalibrationField("Peak ratio rough min (%)", peakRatioRoughMinText) { onValuesChange(CalibrationTextValues(rmsSmoothMaxText, peakThresholdZText, movingAverageWindowText, stdDevSmoothMaxText, rmsRoughMinText, it, stdDevRoughMinText, magMaxSevereMinText, qualityWindowSizeText)) }
                CalibrationField("MagMax severe min", magMaxSevereMinText) { onValuesChange(CalibrationTextValues(rmsSmoothMaxText, peakThresholdZText, movingAverageWindowText, stdDevSmoothMaxText, rmsRoughMinText, peakRatioRoughMinText, stdDevRoughMinText, it, qualityWindowSizeText)) }
                
                Spacer(modifier = Modifier.height(8.dp))
                Text("Other Settings", style = MaterialTheme.typography.titleSmall)
                CalibrationField("Moving average window", movingAverageWindowText) { onValuesChange(CalibrationTextValues(rmsSmoothMaxText, peakThresholdZText, it, stdDevSmoothMaxText, rmsRoughMinText, peakRatioRoughMinText, stdDevRoughMinText, magMaxSevereMinText, qualityWindowSizeText)) }
                CalibrationField("Quality window size", qualityWindowSizeText) { onValuesChange(CalibrationTextValues(rmsSmoothMaxText, peakThresholdZText, movingAverageWindowText, stdDevSmoothMaxText, rmsRoughMinText, peakRatioRoughMinText, stdDevRoughMinText, magMaxSevereMinText, it)) }
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
                        if (parsed == null) {
                            Toast.makeText(context, "Enter valid calibration numbers", Toast.LENGTH_LONG).show()
                            return@TextButton
                        }
                        if (currentProfileName == null) {
                            Toast.makeText(context, "Cannot save to Default profile. Use Save As.", Toast.LENGTH_LONG).show()
                            return@TextButton
                        }
                        scope.launch(Dispatchers.IO) {
                            repository.updateCalibration(parsed)
                            val profile = VehicleProfile(currentProfileName, parsed)
                            profileRepository.saveProfile(profile, folderUri)
                            withContext(Dispatchers.Main) {
                                Toast.makeText(context, "Profile '$currentProfileName' saved", Toast.LENGTH_LONG).show()
                            }
                        }
                    },
                    enabled = currentProfileName != null
                ) { Text("Save") }
                
                TextButton(onClick = { showSaveAsDialog = true }) { Text("Save As") }
                TextButton(onClick = { showLoadDialog = true }) { Text("Load") }
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onResetDefaults) { Text("Reset") }
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
            rmsSmoothMaxText = rmsSmoothMaxText,
            peakThresholdZText = peakThresholdZText,
            movingAverageWindowText = movingAverageWindowText,
            stdDevSmoothMaxText = stdDevSmoothMaxText,
            rmsRoughMinText = rmsRoughMinText,
            peakRatioRoughMinText = peakRatioRoughMinText,
            stdDevRoughMinText = stdDevRoughMinText,
            magMaxSevereMinText = magMaxSevereMinText,
            qualityWindowSizeText = qualityWindowSizeText,
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
    rmsSmoothMaxText: String,
    peakThresholdZText: String,
    movingAverageWindowText: String,
    stdDevSmoothMaxText: String,
    rmsRoughMinText: String,
    peakRatioRoughMinText: String,
    stdDevRoughMinText: String,
    magMaxSevereMinText: String,
    qualityWindowSizeText: String,
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
                    if (parsed == null) {
                        Toast.makeText(context, "Enter valid calibration numbers", Toast.LENGTH_LONG).show()
                        return@TextButton
                    }
                    scope.launch(Dispatchers.IO) {
                        repository.updateCalibration(parsed)
                        repository.updateCurrentProfileName(profileName)
                        val profile = VehicleProfile(profileName, parsed)
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
    val qualityWindowSize: String
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
