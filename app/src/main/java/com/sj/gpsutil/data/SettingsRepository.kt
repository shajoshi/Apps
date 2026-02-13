package com.sj.gpsutil.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

const val MIN_INTERVAL_SECONDS = 1L

enum class OutputFormat {
    KML,
    GPX,
    JSON
}

private const val SETTINGS_STORE_NAME = "tracking_settings"

val Context.settingsDataStore by preferencesDataStore(SETTINGS_STORE_NAME)

data class TrackingSettings(
    val intervalSeconds: Long = MIN_INTERVAL_SECONDS,
    val folderUri: String? = null,
    val outputFormat: OutputFormat = OutputFormat.KML,
    val disablePointFiltering: Boolean = false,
    val enableAccelerometer: Boolean = true,
    val roadCalibrationMode: Boolean = false,
    val calibration: CalibrationSettings = CalibrationSettings(),
    val driverThresholds: DriverThresholdSettings = DriverThresholdSettings(),
    val currentProfileName: String? = null
)

data class CalibrationSettings(
    val rmsSmoothMax: Float = 1.0f,
    val peakThresholdZ: Float = 1.5f,
    val movingAverageWindow: Int = 5,
    val stdDevSmoothMax: Float = 2.5f,
    val rmsRoughMin: Float = 4.5f,
    val peakRatioRoughMin: Float = 0.6f,
    val stdDevRoughMin: Float = 3.0f,
    val magMaxSevereMin: Float = 20.0f,
    val qualityWindowSize: Int = 3
)

data class DriverThresholdSettings(
    val hardBrakeFwdMax: Float = 15f,
    val hardAccelFwdMax: Float = 15f,
    val swerveLatMax: Float = 4f,
    val aggressiveCornerLatMax: Float = 4f,
    val aggressiveCornerDCourse: Float = 15f,
    val minSpeedKmph: Float = 6f,
    val smoothnessRmsMax: Float = 10f,
    val fallLeanAngle: Float = 40f
)

class SettingsRepository(private val context: Context) {
    private val intervalKey = longPreferencesKey("interval_seconds")
    private val folderUriKey = stringPreferencesKey("folder_uri")
    private val outputFormatKey = stringPreferencesKey("output_format")
    private val disableFilteringKey = booleanPreferencesKey("disable_point_filtering")
    private val enableAccelerometerKey = booleanPreferencesKey("enable_accelerometer")
    private val roadCalibrationModeKey = booleanPreferencesKey("road_calibration_mode")
    private val rmsSmoothMaxKey = floatPreferencesKey("cal_rms_smooth_max")
    private val peakThresholdKey = floatPreferencesKey("cal_peak_threshold_z")
    private val movingAverageWindowKey = longPreferencesKey("cal_moving_average_window")
    private val stdDevSmoothMaxKey = floatPreferencesKey("cal_stddev_smooth_max")
    private val rmsRoughMinKey = floatPreferencesKey("cal_rms_rough_min")
    private val peakRatioRoughMinKey = floatPreferencesKey("cal_peakratio_rough_min")
    private val stdDevRoughMinKey = floatPreferencesKey("cal_stddev_rough_min")
    private val magMaxSevereMinKey = floatPreferencesKey("cal_magmax_severe_min")
    private val qualityWindowSizeKey = longPreferencesKey("cal_quality_window_size")
    private val currentProfileNameKey = stringPreferencesKey("current_profile_name")
    private val dtHardBrakeFwdMaxKey = floatPreferencesKey("dt_hard_brake_fwd_max")
    private val dtHardAccelFwdMaxKey = floatPreferencesKey("dt_hard_accel_fwd_max")
    private val dtSwerveLatMaxKey = floatPreferencesKey("dt_swerve_lat_max")
    private val dtAggressiveCornerLatMaxKey = floatPreferencesKey("dt_aggressive_corner_lat_max")
    private val dtAggressiveCornerDCourseKey = floatPreferencesKey("dt_aggressive_corner_dcourse")
    private val dtMinSpeedKmphKey = floatPreferencesKey("dt_min_speed_kmph")
    private val dtSmoothnessRmsMaxKey = floatPreferencesKey("dt_smoothness_rms_max")
    private val dtFallLeanAngleKey = floatPreferencesKey("dt_fall_lean_angle")

    
    val settingsFlow: Flow<TrackingSettings> = context.settingsDataStore.data.map { prefs ->
        TrackingSettings(
            intervalSeconds = (prefs[intervalKey] ?: MIN_INTERVAL_SECONDS).coerceAtLeast(MIN_INTERVAL_SECONDS),
            folderUri = prefs[folderUriKey],
            outputFormat = runCatching {
                prefs[outputFormatKey]?.let { OutputFormat.valueOf(it) }
            }.getOrNull() ?: OutputFormat.KML,
            disablePointFiltering = prefs[disableFilteringKey] ?: false,
            enableAccelerometer = prefs[enableAccelerometerKey] ?: true,
            roadCalibrationMode = prefs[roadCalibrationModeKey] ?: false,
            calibration = CalibrationSettings(
                rmsSmoothMax = prefs[rmsSmoothMaxKey] ?: 1.0f,
                peakThresholdZ = prefs[peakThresholdKey] ?: 1.5f,
                movingAverageWindow = (prefs[movingAverageWindowKey] ?: 5L).toInt(),
                stdDevSmoothMax = prefs[stdDevSmoothMaxKey] ?: 2.5f,
                rmsRoughMin = prefs[rmsRoughMinKey] ?: 4.5f,
                peakRatioRoughMin = prefs[peakRatioRoughMinKey] ?: 0.6f,
                stdDevRoughMin = prefs[stdDevRoughMinKey] ?: 3.0f,
                magMaxSevereMin = prefs[magMaxSevereMinKey] ?: 20.0f,
                qualityWindowSize = (prefs[qualityWindowSizeKey] ?: 3L).toInt()
            ),
            driverThresholds = DriverThresholdSettings(
                hardBrakeFwdMax = prefs[dtHardBrakeFwdMaxKey] ?: 15f,
                hardAccelFwdMax = prefs[dtHardAccelFwdMaxKey] ?: 15f,
                swerveLatMax = prefs[dtSwerveLatMaxKey] ?: 4f,
                aggressiveCornerLatMax = prefs[dtAggressiveCornerLatMaxKey] ?: 4f,
                aggressiveCornerDCourse = prefs[dtAggressiveCornerDCourseKey] ?: 15f,
                minSpeedKmph = prefs[dtMinSpeedKmphKey] ?: 6f,
                smoothnessRmsMax = prefs[dtSmoothnessRmsMaxKey] ?: 10f,
                fallLeanAngle = prefs[dtFallLeanAngleKey] ?: 40f
            ),
            currentProfileName = prefs[currentProfileNameKey]
        )
    }

    suspend fun updateIntervalSeconds(seconds: Long) {
        context.settingsDataStore.edit { prefs ->
            prefs[intervalKey] = seconds.coerceAtLeast(MIN_INTERVAL_SECONDS)
        }
    }

    suspend fun updateFolderUri(uri: String?) {
        context.settingsDataStore.edit { prefs ->
            if (uri.isNullOrBlank()) {
                prefs.remove(folderUriKey)
            } else {
                prefs[folderUriKey] = uri
            }
        }
    }

    suspend fun updateOutputFormat(format: OutputFormat) {
        context.settingsDataStore.edit { prefs ->
            prefs[outputFormatKey] = format.name
        }
    }

    suspend fun updateDisablePointFiltering(disable: Boolean) {
        context.settingsDataStore.edit { prefs ->
            prefs[disableFilteringKey] = disable
        }
    }

    suspend fun updateEnableAccelerometer(enable: Boolean) {
        context.settingsDataStore.edit { prefs ->
            prefs[enableAccelerometerKey] = enable
        }
    }

    suspend fun updateRoadCalibrationMode(enabled: Boolean) {
        context.settingsDataStore.edit { prefs ->
            prefs[roadCalibrationModeKey] = enabled
        }
    }

    suspend fun updateCalibration(calibration: CalibrationSettings) {
        // Get current settings to compare and avoid unnecessary writes
        val prefs = context.settingsDataStore.data.first()
        val currentCalibration = CalibrationSettings(
            rmsSmoothMax = prefs[rmsSmoothMaxKey] ?: 1.0f,
            peakThresholdZ = prefs[peakThresholdKey] ?: 1.5f,
            movingAverageWindow = (prefs[movingAverageWindowKey] ?: 5L).toInt(),
            stdDevSmoothMax = prefs[stdDevSmoothMaxKey] ?: 2.5f,
            rmsRoughMin = prefs[rmsRoughMinKey] ?: 4.5f,
            peakRatioRoughMin = prefs[peakRatioRoughMinKey] ?: 0.6f,
            stdDevRoughMin = prefs[stdDevRoughMinKey] ?: 3.0f,
            magMaxSevereMin = prefs[magMaxSevereMinKey] ?: 20.0f,
            qualityWindowSize = (prefs[qualityWindowSizeKey] ?: 3L).toInt()
        )
        
        // Only update if values have actually changed
        if (currentCalibration.rmsSmoothMax != calibration.rmsSmoothMax ||
            currentCalibration.peakThresholdZ != calibration.peakThresholdZ ||
            currentCalibration.movingAverageWindow != calibration.movingAverageWindow ||
            currentCalibration.stdDevSmoothMax != calibration.stdDevSmoothMax ||
            currentCalibration.rmsRoughMin != calibration.rmsRoughMin ||
            currentCalibration.peakRatioRoughMin != calibration.peakRatioRoughMin ||
            currentCalibration.stdDevRoughMin != calibration.stdDevRoughMin ||
            currentCalibration.magMaxSevereMin != calibration.magMaxSevereMin ||
            currentCalibration.qualityWindowSize != calibration.qualityWindowSize) {
            
            context.settingsDataStore.edit { prefs ->
                prefs[rmsSmoothMaxKey] = calibration.rmsSmoothMax
                prefs[peakThresholdKey] = calibration.peakThresholdZ
                prefs[movingAverageWindowKey] = calibration.movingAverageWindow.toLong()
                prefs[stdDevSmoothMaxKey] = calibration.stdDevSmoothMax
                prefs[rmsRoughMinKey] = calibration.rmsRoughMin
                prefs[peakRatioRoughMinKey] = calibration.peakRatioRoughMin
                prefs[stdDevRoughMinKey] = calibration.stdDevRoughMin
                prefs[magMaxSevereMinKey] = calibration.magMaxSevereMin
                prefs[qualityWindowSizeKey] = calibration.qualityWindowSize.toLong()
            }
        }
    }

    suspend fun updateDriverThresholds(dt: DriverThresholdSettings) {
        context.settingsDataStore.edit { prefs ->
            prefs[dtHardBrakeFwdMaxKey] = dt.hardBrakeFwdMax
            prefs[dtHardAccelFwdMaxKey] = dt.hardAccelFwdMax
            prefs[dtSwerveLatMaxKey] = dt.swerveLatMax
            prefs[dtAggressiveCornerLatMaxKey] = dt.aggressiveCornerLatMax
            prefs[dtAggressiveCornerDCourseKey] = dt.aggressiveCornerDCourse
            prefs[dtMinSpeedKmphKey] = dt.minSpeedKmph
            prefs[dtSmoothnessRmsMaxKey] = dt.smoothnessRmsMax
            prefs[dtFallLeanAngleKey] = dt.fallLeanAngle
        }
    }

    suspend fun updateCurrentProfileName(profileName: String?) {
        context.settingsDataStore.edit { prefs ->
            if (profileName == null) {
                prefs.remove(currentProfileNameKey)
            } else {
                prefs[currentProfileNameKey] = profileName
            }
        }
    }
}
