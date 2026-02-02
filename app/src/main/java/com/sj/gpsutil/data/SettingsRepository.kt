package com.sj.gpsutil.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
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
    val calibration: CalibrationSettings = CalibrationSettings(),
    val currentProfileName: String? = null
)

data class CalibrationSettings(
    val rmsSmoothMax: Float = 1.0f,
    val rmsAverageMax: Float = 2.0f,
    val peakThresholdZ: Float = 1.5f,
    val symmetricBumpThreshold: Float = 2.0f,
    val potholeDipThreshold: Float = -2.5f,
    val bumpSpikeThreshold: Float = 2.5f,
    val peakCountSmoothMax: Int = 5,
    val peakCountAverageMax: Int = 15,
    val movingAverageWindow: Int = 5
)

class SettingsRepository(private val context: Context) {
    private val intervalKey = longPreferencesKey("interval_seconds")
    private val folderUriKey = stringPreferencesKey("folder_uri")
    private val outputFormatKey = stringPreferencesKey("output_format")
    private val disableFilteringKey = booleanPreferencesKey("disable_point_filtering")
    private val enableAccelerometerKey = booleanPreferencesKey("enable_accelerometer")
    private val rmsSmoothMaxKey = floatPreferencesKey("cal_rms_smooth_max")
    private val rmsAverageMaxKey = floatPreferencesKey("cal_rms_average_max")
    private val peakThresholdKey = floatPreferencesKey("cal_peak_threshold_z")
    private val symmetricBumpThresholdKey = floatPreferencesKey("cal_sym_bump_threshold")
    private val potholeDipThresholdKey = floatPreferencesKey("cal_pothole_dip_threshold")
    private val bumpSpikeThresholdKey = floatPreferencesKey("cal_bump_spike_threshold")
    private val peakCountSmoothMaxKey = longPreferencesKey("cal_peakcount_smooth_max")
    private val peakCountAverageMaxKey = longPreferencesKey("cal_peakcount_average_max")
    private val movingAverageWindowKey = longPreferencesKey("cal_moving_average_window")
    private val currentProfileNameKey = stringPreferencesKey("current_profile_name")

    val settingsFlow: Flow<TrackingSettings> = context.settingsDataStore.data.map { prefs ->
        TrackingSettings(
            intervalSeconds = (prefs[intervalKey] ?: MIN_INTERVAL_SECONDS).coerceAtLeast(MIN_INTERVAL_SECONDS),
            folderUri = prefs[folderUriKey],
            outputFormat = runCatching {
                prefs[outputFormatKey]?.let { OutputFormat.valueOf(it) }
            }.getOrNull() ?: OutputFormat.KML,
            disablePointFiltering = prefs[disableFilteringKey] ?: false,
            enableAccelerometer = prefs[enableAccelerometerKey] ?: true,
            calibration = CalibrationSettings(
                rmsSmoothMax = prefs[rmsSmoothMaxKey] ?: 1.0f,
                rmsAverageMax = prefs[rmsAverageMaxKey] ?: 2.0f,
                peakThresholdZ = prefs[peakThresholdKey] ?: 1.5f,
                symmetricBumpThreshold = prefs[symmetricBumpThresholdKey] ?: 2.0f,
                potholeDipThreshold = prefs[potholeDipThresholdKey] ?: -2.5f,
                bumpSpikeThreshold = prefs[bumpSpikeThresholdKey] ?: 2.5f,
                peakCountSmoothMax = (prefs[peakCountSmoothMaxKey] ?: 5L).toInt(),
                peakCountAverageMax = (prefs[peakCountAverageMaxKey] ?: 15L).toInt(),
                movingAverageWindow = (prefs[movingAverageWindowKey] ?: 5L).toInt()
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

    suspend fun updateCalibration(calibration: CalibrationSettings) {
        context.settingsDataStore.edit { prefs ->
            prefs[rmsSmoothMaxKey] = calibration.rmsSmoothMax
            prefs[rmsAverageMaxKey] = calibration.rmsAverageMax
            prefs[peakThresholdKey] = calibration.peakThresholdZ
            prefs[symmetricBumpThresholdKey] = calibration.symmetricBumpThreshold
            prefs[potholeDipThresholdKey] = calibration.potholeDipThreshold
            prefs[bumpSpikeThresholdKey] = calibration.bumpSpikeThreshold
            prefs[peakCountSmoothMaxKey] = calibration.peakCountSmoothMax.toLong()
            prefs[peakCountAverageMaxKey] = calibration.peakCountAverageMax.toLong()
            prefs[movingAverageWindowKey] = calibration.movingAverageWindow.toLong()
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
