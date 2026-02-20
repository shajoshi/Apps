package com.sj.gpsutil.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.sj.gpsutil.data.SettingsRepository
import com.sj.gpsutil.data.TrackingSettings
import com.sj.gpsutil.data.VehicleProfile
import com.sj.gpsutil.data.VehicleProfileRepository
import com.sj.gpsutil.tracking.CalibrateParams
import com.sj.gpsutil.tracking.ThresholdRecommendation
import com.sj.gpsutil.tracking.ThresholdRecommendationEngine
import com.sj.gpsutil.tracking.TrackHistoryRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class TrackHistoryUiState(
    val tracks: List<TrackHistoryRepository.TrackFileInfo> = emptyList(),
    val isSearching: Boolean = false,
    val hasSearchCompleted: Boolean = false,
    val errorMessage: String? = null,
    val lastLoadedFolderUri: String? = null
)

data class CalibrationUiState(
    val calibrateTarget: TrackHistoryRepository.TrackFileInfo? = null,
    val showParamsDialog: Boolean = false,
    val isValidating: Boolean = false,
    val isCalibrating: Boolean = false,
    val calibrationResult: ThresholdRecommendation? = null,
    val calibrationError: String? = null,
    val isApplyingProfile: Boolean = false,
    val applySuccess: String? = null
)

class TrackHistoryViewModel(
    private val repository: TrackHistoryRepository,
    private val settingsRepository: SettingsRepository,
    private val profileRepository: VehicleProfileRepository
) : ViewModel() {

    private val _state = MutableStateFlow(TrackHistoryUiState())
    val state: StateFlow<TrackHistoryUiState> = _state

    private val _calibrationState = MutableStateFlow(CalibrationUiState())
    val calibrationState: StateFlow<CalibrationUiState> = _calibrationState

    fun shouldRefresh(settings: TrackingSettings): Boolean {
        val targetFolder = settings.folderUri
        if (targetFolder == null) return false
        val current = _state.value
        return current.lastLoadedFolderUri != targetFolder || current.tracks.isEmpty()
    }

    fun refresh(settings: TrackingSettings) {
        val targetFolder = settings.folderUri ?: return
        _state.value = _state.value.copy(
            isSearching = true,
            hasSearchCompleted = false,
            errorMessage = null
        )
        viewModelScope.launch {
            val result = runCatching { repository.listTracks(settings) }
            val tracks = result.getOrNull()
            val error = result.exceptionOrNull()?.localizedMessage
            _state.value = _state.value.copy(
                tracks = tracks ?: emptyList(),
                isSearching = false,
                hasSearchCompleted = true,
                errorMessage = error,
                lastLoadedFolderUri = targetFolder
            )
        }
    }

    fun requestCalibrate(info: TrackHistoryRepository.TrackFileInfo, settings: TrackingSettings) {
        if (info.extension.lowercase() != "json") {
            _calibrationState.value = _calibrationState.value.copy(
                calibrationError = "Only JSON track files contain raw accelerometer data."
            )
            return
        }
        _calibrationState.value = _calibrationState.value.copy(
            calibrateTarget = info,
            isValidating = true,
            calibrationError = null,
            calibrationResult = null,
            showParamsDialog = false
        )
        viewModelScope.launch {
            val jsonText = repository.readJsonText(settings, info)
            val hasRaw = jsonText != null && withContext(Dispatchers.Default) {
                ThresholdRecommendationEngine.hasRawAccelData(jsonText)
            }
            if (!hasRaw) {
                _calibrationState.value = _calibrationState.value.copy(
                    isValidating = false,
                    calibrationError = "This track has no raw accelerometer data. Re-record with Raw Data enabled."
                )
            } else {
                _calibrationState.value = _calibrationState.value.copy(
                    isValidating = false,
                    showParamsDialog = true
                )
            }
        }
    }

    fun runCalibration(settings: TrackingSettings, params: CalibrateParams) {
        val info = _calibrationState.value.calibrateTarget ?: return
        _calibrationState.value = _calibrationState.value.copy(
            showParamsDialog = false,
            isCalibrating = true,
            calibrationError = null
        )
        viewModelScope.launch {
            val result = runCatching {
                val jsonText = repository.readJsonText(settings, info)
                    ?: throw IllegalArgumentException("Could not read track file.")
                withContext(Dispatchers.Default) {
                    ThresholdRecommendationEngine().analyze(jsonText, params)
                }
            }
            val rec = result.getOrNull()
            val error = result.exceptionOrNull()?.localizedMessage
            _calibrationState.value = _calibrationState.value.copy(
                isCalibrating = false,
                calibrationResult = rec,
                calibrationError = error
            )
        }
    }

    fun applyToProfile(
        profileName: String,
        rec: ThresholdRecommendation,
        settings: TrackingSettings,
        folderUri: String?
    ) {
        _calibrationState.value = _calibrationState.value.copy(isApplyingProfile = true)
        viewModelScope.launch {
            runCatching {
                val existing = profileRepository.loadProfile(profileName, folderUri)
                val updated = (existing ?: VehicleProfile(profileName, rec.recommended, rec.recommendedDriver))
                    .copy(
                        calibration = rec.recommended,
                        driverThresholds = rec.recommendedDriver,
                        lastModified = System.currentTimeMillis()
                    )
                profileRepository.saveProfile(updated, folderUri)
                if (settings.currentProfileName == profileName) {
                    settingsRepository.updateCalibration(rec.recommended)
                    settingsRepository.updateDriverThresholds(rec.recommendedDriver)
                }
            }
            _calibrationState.value = _calibrationState.value.copy(
                isApplyingProfile = false,
                applySuccess = profileName
            )
        }
    }

    fun dismissCalibration() {
        _calibrationState.value = CalibrationUiState()
    }

    fun dismissCalibrationError() {
        _calibrationState.value = _calibrationState.value.copy(calibrationError = null)
    }

    fun dismissApplySuccess() {
        _calibrationState.value = _calibrationState.value.copy(applySuccess = null)
    }

    companion object {
        fun factory(context: Context): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val repo = TrackHistoryRepository(context.applicationContext)
                val settingsRepo = SettingsRepository(context.applicationContext)
                val profileRepo = VehicleProfileRepository(context.applicationContext)
                @Suppress("UNCHECKED_CAST")
                return TrackHistoryViewModel(repo, settingsRepo, profileRepo) as T
            }
        }
    }
}
