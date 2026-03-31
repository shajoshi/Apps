package com.sj.obd2app.ui.tripsummary

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.sj.obd2app.metrics.TrackFileParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class TrackFileItem(
    val uri: Uri,
    val name: String,
    val sizeBytes: Long,
    val lastModified: Long
)

data class TripSummaryData(
    val fileName: String,
    val vehicleName: String,
    val fuelType: String,
    val tankCapacityL: Float,
    val fuelPricePerLitre: Float,
    val enginePowerBhp: Float,
    val vehicleMassKg: Float,
    val tripFuelUsedL: Float,
    val tripAvgLper100km: Float,
    val tripAvgKpl: Float,
    val fuelCostEstimate: Float,
    val avgCo2gPerKm: Float,
    val distanceKm: Float,
    val timeSec: Long,
    val movingTimeSec: Long,
    val stoppedTimeSec: Long,
    val avgSpeedKmh: Float,
    val maxSpeedKmh: Float,
    val pctCity: Float,
    val pctHighway: Float,
    val pctIdle: Float
)

enum class TripSummaryLoadingType {
    FILE_LIST,
    TRIP_SUMMARY
}

class TripSummaryViewModel(application: Application) : AndroidViewModel(application) {

    private val TAG = "TripSummaryViewModel"

    private val _fileList = MutableStateFlow<List<TrackFileItem>>(emptyList())
    val fileList: StateFlow<List<TrackFileItem>> = _fileList

    private val _summary = MutableStateFlow<TripSummaryData?>(null)
    val summary: StateFlow<TripSummaryData?> = _summary

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    private val _loadingType = MutableStateFlow(TripSummaryLoadingType.FILE_LIST)
    val loadingType: StateFlow<TripSummaryLoadingType> = _loadingType

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    private val _selectedDirectory = MutableStateFlow<String?>(null)
    val selectedDirectory: StateFlow<String?> = _selectedDirectory

    /**
     * Lists all track files in the given directory URI.
     */
    fun listTrackFiles(directoryUri: Uri) {
        Log.d(TAG, "listTrackFiles: Starting file listing for URI: $directoryUri")
        viewModelScope.launch {
            _isLoading.value = true
            _loadingType.value = TripSummaryLoadingType.FILE_LIST
            _error.value = null
            
            try {
                val files = withContext(Dispatchers.IO) {
                    val dir = DocumentFile.fromTreeUri(getApplication(), directoryUri)
                    Log.d(TAG, "listTrackFiles: Directory object: $dir")
                    if (dir == null || !dir.exists() || !dir.isDirectory) {
                        Log.e(TAG, "listTrackFiles: Invalid directory URI: $directoryUri (exists=${dir?.exists()}, isDirectory=${dir?.isDirectory})")
                        return@withContext emptyList<TrackFileItem>()
                    }

                    _selectedDirectory.value = dir.name ?: "Selected Folder"
                    Log.d(TAG, "listTrackFiles: Directory name: ${dir.name}")

                    val allFiles = dir.listFiles()
                    Log.d(TAG, "listTrackFiles: Total files in directory: ${allFiles.size}")
                    
                    val trackFiles = allFiles
                        .filter { it.isFile && it.name?.contains("_obdlog_") == true && it.name?.endsWith(".json") == true }
                    Log.d(TAG, "listTrackFiles: Found ${trackFiles.size} track files after filtering")
                    
                    trackFiles.map { file ->
                            TrackFileItem(
                                uri = file.uri,
                                name = file.name ?: "Unknown",
                                sizeBytes = file.length(),
                                lastModified = file.lastModified()
                            )
                        }
                        .sortedByDescending { it.lastModified }
                }

                _fileList.value = files
                Log.d(TAG, "Found ${files.size} track files")
            } catch (e: Exception) {
                Log.e(TAG, "Error listing track files", e)
                _error.value = "Failed to list files: ${e.message}"
                _fileList.value = emptyList()
            } finally {
                _isLoading.value = false
            }
        }
    }

    /**
     * Clears the current summary to return to file list view.
     */
    fun clearSummary() {
        _summary.value = null
    }

    /**
     * Loads and parses a track file to extract summary data.
     */
    fun loadTrackFile(fileItem: TrackFileItem) {
        viewModelScope.launch {
            _isLoading.value = true
            _loadingType.value = TripSummaryLoadingType.TRIP_SUMMARY
            _error.value = null
            _summary.value = null

            try {
                val summaryData = withContext(Dispatchers.IO) {
                    val parsed = TrackFileParser.parseTrackFile(
                        getApplication(),
                        fileItem.uri,
                        fileItem.name
                    )
                    if (parsed != null) {
                        extractSummaryData(fileItem.name, parsed.vehicleProfile, parsed.lastSample)
                    } else {
                        null
                    }
                }

                if (summaryData != null) {
                    _summary.value = summaryData
                    Log.d(TAG, "Successfully loaded track summary")
                } else {
                    _error.value = "Failed to parse track file"
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading track file", e)
                _error.value = "Failed to load file: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    /**
     * Extracts summary data from parsed JSON objects.
     */
    private fun extractSummaryData(fileName: String, profile: JSONObject, lastSample: JSONObject): TripSummaryData? {
        return try {
            val fuel = lastSample.optJSONObject("fuel") ?: JSONObject()
            val trip = lastSample.optJSONObject("trip") ?: JSONObject()

            TripSummaryData(
                fileName = fileName,
                vehicleName = profile.optString("name", "Unknown Vehicle"),
                fuelType = profile.optString("fuelTypeDisplay", profile.optString("fuelType", "Unknown")),
                tankCapacityL = profile.optDouble("tankCapacityL", 0.0).toFloat(),
                fuelPricePerLitre = profile.optDouble("fuelPricePerLitre", 0.0).toFloat(),
                enginePowerBhp = profile.optDouble("enginePowerBhp", 0.0).toFloat(),
                vehicleMassKg = profile.optDouble("vehicleMassKg", 0.0).toFloat(),
                tripFuelUsedL = fuel.optDouble("tripFuelUsedL", 0.0).toFloat(),
                tripAvgLper100km = fuel.optDouble("tripAvgLper100km", 0.0).toFloat(),
                tripAvgKpl = fuel.optDouble("tripAvgKpl", 0.0).toFloat(),
                fuelCostEstimate = fuel.optDouble("fuelCostEstimate", 0.0).toFloat(),
                avgCo2gPerKm = fuel.optDouble("avgCo2gPerKm", 0.0).toFloat(),
                distanceKm = trip.optDouble("distanceKm", 0.0).toFloat(),
                timeSec = trip.optLong("timeSec", 0L),
                movingTimeSec = trip.optLong("movingTimeSec", 0L),
                stoppedTimeSec = trip.optLong("stoppedTimeSec", 0L),
                avgSpeedKmh = trip.optDouble("avgSpeedKmh", 0.0).toFloat(),
                maxSpeedKmh = trip.optDouble("maxSpeedKmh", 0.0).toFloat(),
                pctCity = trip.optDouble("pctCity", 0.0).toFloat(),
                pctHighway = trip.optDouble("pctHighway", 0.0).toFloat(),
                pctIdle = trip.optDouble("pctIdle", 0.0).toFloat()
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting summary data", e)
            null
        }
    }

    /**
     * Clears any error message.
     */
    fun clearError() {
        _error.value = null
    }
}
