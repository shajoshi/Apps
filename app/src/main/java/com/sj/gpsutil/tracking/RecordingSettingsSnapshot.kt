package com.sj.gpsutil.tracking

import com.sj.gpsutil.data.CalibrationSettings
import com.sj.gpsutil.data.OutputFormat

/**
 * Immutable snapshot of the key recording settings that were active when a track run started.
 * This allows downstream analysis to know which interval, calibration thresholds, and flags
 * were used for the exported file.
 */
data class RecordingSettingsSnapshot(
    val intervalSeconds: Long,
    val disablePointFiltering: Boolean,
    val enableAccelerometer: Boolean,
    val roadCalibrationMode: Boolean,
    val outputFormat: OutputFormat,
    val calibration: CalibrationSettings,
    val profileName: String?,
    val baseGravityVector: FloatArray? = null
)
