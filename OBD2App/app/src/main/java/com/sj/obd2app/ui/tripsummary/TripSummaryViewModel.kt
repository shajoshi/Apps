package com.sj.obd2app.ui.tripsummary

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import android.util.JsonReader
import android.util.JsonToken
import com.sj.obd2app.metrics.TrackFileParser
import com.sj.obd2app.settings.AppSettings
import com.sj.obd2app.ui.tripsummary.TripSelectionStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedWriter
import java.io.OutputStreamWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
    TRIP_SUMMARY,
    ANALYZING
}

private data class ParsedFile(
    val item: TrackFileItem,
    val header: JSONObject,
    val lastSample: JSONObject
)

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
                        TripSelectionStore.setSelectedTrack(
                            TripSelectionStore.SelectedTrack(
                                fileName = fileItem.name,
                                uri = fileItem.uri,
                                lastSample = parsed.lastSample
                            )
                        )
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
                stoppedTimeSec = trip.optLong("stoppedTimeSec", trip.optLong("idleTimeSec", 0L)),
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
     * Analyzes multiple selected track files by merging their GPS samples
     * and computing combined trip statistics. Files are sorted chronologically
     * before merging. The combined result is saved to the log folder.
     */
    fun analyzeSelectedFiles(selectedFiles: List<TrackFileItem>) {
        viewModelScope.launch {
            _isLoading.value = true
            _loadingType.value = TripSummaryLoadingType.ANALYZING
            _error.value = null
            _summary.value = null

            try {
                val result = withContext(Dispatchers.IO) {
                    val sorted = selectedFiles.sortedBy { it.lastModified }

                    // Line-scanner: read each file line-by-line to extract vehicleProfile
                    // and last sample only — never loads the full file into memory.
                    val parsed = sorted.mapNotNull { item ->
                        try {
                            scanFileForStats(item)
                        } catch (e: Exception) {
                            Log.e(TAG, "analyzeSelectedFiles: Failed to scan ${item.name}", e)
                            null
                        }
                    }

                    if (parsed.isEmpty()) {
                        return@withContext null
                    }

                    // Sum cumulative trip stats across last-samples of each file
                    var totalDistanceKm = 0.0
                    var totalTimeSec = 0L
                    var totalMovingTimeSec = 0L
                    var totalStoppedTimeSec = 0L
                    var totalFuelUsedL = 0.0
                    var maxSpeedKmh = 0.0
                    var weightedSpeedSum = 0.0
                    var weightedSpeedTime = 0L
                    var weightedCitySum = 0.0
                    var weightedHwySum = 0.0
                    var weightedIdleSum = 0.0
                    var totalFuelCost = 0.0

                    for (pf in parsed) {
                        val fuel = pf.lastSample.optJSONObject("fuel") ?: JSONObject()
                        val trip = pf.lastSample.optJSONObject("trip") ?: JSONObject()
                        totalDistanceKm += trip.optDouble("distanceKm", 0.0)
                        totalTimeSec += trip.optLong("timeSec", 0L)
                        totalMovingTimeSec += trip.optLong("movingTimeSec", 0L)
                        totalStoppedTimeSec += trip.optLong("stoppedTimeSec",
                            trip.optLong("idleTimeSec", 0L))
                        totalFuelUsedL += fuel.optDouble("tripFuelUsedL", 0.0)
                        totalFuelCost += fuel.optDouble("fuelCostEstimate", 0.0)
                        val spd = trip.optDouble("maxSpeedKmh", 0.0)
                        if (spd > maxSpeedKmh) maxSpeedKmh = spd
                        val t = trip.optLong("timeSec", 0L)
                        weightedSpeedSum += trip.optDouble("avgSpeedKmh", 0.0) * t
                        weightedSpeedTime += t
                        weightedCitySum += trip.optDouble("pctCity", 0.0) * t
                        weightedHwySum += trip.optDouble("pctHighway", 0.0) * t
                        weightedIdleSum += trip.optDouble("pctIdle", 0.0) * t
                    }

                    val combinedAvgSpeedKmh = if (weightedSpeedTime > 0)
                        (weightedSpeedSum / weightedSpeedTime).toFloat() else 0f
                    val combinedPctCity = if (weightedSpeedTime > 0)
                        (weightedCitySum / weightedSpeedTime).toFloat() else 0f
                    val combinedPctHwy = if (weightedSpeedTime > 0)
                        (weightedHwySum / weightedSpeedTime).toFloat() else 0f
                    val combinedPctIdle = if (weightedSpeedTime > 0)
                        (weightedIdleSum / weightedSpeedTime).toFloat() else 0f

                    val combinedAvgLper100km = if (totalDistanceKm > 0)
                        ((totalFuelUsedL / totalDistanceKm) * 100).toFloat() else 0f
                    val combinedAvgKpl = if (combinedAvgLper100km > 0)
                        (100f / combinedAvgLper100km) else 0f

                    val firstHeader = parsed.first().header
                    val vehicleName = firstHeader.optString("name", "Unknown Vehicle")
                    val fuelType = firstHeader.optString("fuelTypeDisplay",
                        firstHeader.optString("fuelType", "Unknown"))
                    val energyDensity = firstHeader.optDouble("energyDensityMJpL", 34.2)
                    val avgCo2 = if (totalDistanceKm > 0)
                        ((totalFuelUsedL * energyDensity * 1000 * 0.0734) / totalDistanceKm).toFloat()
                    else 0f

                    // Build combined file name and save to SAF folder
                    val ts = SimpleDateFormat("yyyy-MM-dd_HHmmss", Locale.US).format(Date())
                    val safeProfile = vehicleName.replace(Regex("[^A-Za-z0-9_\\-]"), "_")
                    val combinedFileName = "${safeProfile}_combined_${parsed.size}files_${ts}.json"

                    // Synthesise a combined last-sample for TripSelectionStore
                    val syntheticLastSample = JSONObject().apply {
                        put("fuel", JSONObject().apply {
                            put("tripFuelUsedL", totalFuelUsedL)
                            put("tripAvgLper100km", combinedAvgLper100km)
                            put("tripAvgKpl", combinedAvgKpl)
                            put("fuelCostEstimate", totalFuelCost)
                            put("avgCo2gPerKm", avgCo2)
                        })
                        put("trip", JSONObject().apply {
                            put("distanceKm", totalDistanceKm)
                            put("timeSec", totalTimeSec)
                            put("movingTimeSec", totalMovingTimeSec)
                            put("stoppedTimeSec", totalStoppedTimeSec)
                            put("avgSpeedKmh", combinedAvgSpeedKmh)
                            put("maxSpeedKmh", maxSpeedKmh)
                            put("pctCity", combinedPctCity)
                            put("pctHighway", combinedPctHwy)
                            put("pctIdle", combinedPctIdle)
                        })
                    }

                    // Stream-write combined file — line-copy sample lines from source files
                    val savedUri: Uri? = saveCombinedFile(
                        combinedFileName, firstHeader, sorted
                    )

                    TripSelectionStore.setSelectedTrack(
                        TripSelectionStore.SelectedTrack(
                            fileName = combinedFileName,
                            uri = savedUri ?: parsed.first().item.uri,
                            lastSample = syntheticLastSample
                        )
                    )

                    TripSummaryData(
                        fileName = combinedFileName,
                        vehicleName = vehicleName,
                        fuelType = fuelType,
                        tankCapacityL = firstHeader.optDouble("tankCapacityL", 0.0).toFloat(),
                        fuelPricePerLitre = firstHeader.optDouble("fuelPricePerLitre", 0.0).toFloat(),
                        enginePowerBhp = firstHeader.optDouble("enginePowerBhp", 0.0).toFloat(),
                        vehicleMassKg = firstHeader.optDouble("vehicleMassKg", 0.0).toFloat(),
                        tripFuelUsedL = totalFuelUsedL.toFloat(),
                        tripAvgLper100km = combinedAvgLper100km,
                        tripAvgKpl = combinedAvgKpl,
                        fuelCostEstimate = totalFuelCost.toFloat(),
                        avgCo2gPerKm = avgCo2,
                        distanceKm = totalDistanceKm.toFloat(),
                        timeSec = totalTimeSec,
                        movingTimeSec = totalMovingTimeSec,
                        stoppedTimeSec = totalStoppedTimeSec,
                        avgSpeedKmh = combinedAvgSpeedKmh,
                        maxSpeedKmh = maxSpeedKmh.toFloat(),
                        pctCity = combinedPctCity,
                        pctHighway = combinedPctHwy,
                        pctIdle = combinedPctIdle
                    )
                }

                if (result != null) {
                    _summary.value = result
                    Log.d(TAG, "analyzeSelectedFiles: Combined summary ready")
                } else {
                    _error.value = "Failed to analyze selected files"
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error analyzing files", e)
                _error.value = "Analysis failed: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    /**
     * Token-streams a track file using JsonReader to extract vehicleProfile and last sample only.
     * Never loads the full file into memory — one token at a time.
     */
    private fun scanFileForStats(item: TrackFileItem): ParsedFile? {
        val inputStream = getApplication<Application>()
            .contentResolver.openInputStream(item.uri) ?: return null
        return inputStream.bufferedReader(Charsets.UTF_8).use { br ->
            val reader = JsonReader(br)
            var vehicleProfile: JSONObject? = null
            var lastSample: JSONObject? = null
            try {
                reader.beginObject()
                while (reader.hasNext()) {
                    when (reader.nextName()) {
                        "header" -> {
                            reader.beginObject()
                            while (reader.hasNext()) {
                                if (reader.nextName() == "vehicleProfile") {
                                    vehicleProfile = JSONObject(readJsonValue(reader))
                                } else {
                                    reader.skipValue()
                                }
                            }
                            reader.endObject()
                        }
                        "samples" -> {
                            reader.beginArray()
                            while (reader.hasNext()) {
                                lastSample = JSONObject(readJsonValue(reader))
                            }
                            reader.endArray()
                        }
                        else -> reader.skipValue()
                    }
                }
                reader.endObject()
            } catch (e: Exception) {
                Log.e(TAG, "scanFileForStats: parse error for ${item.name}", e)
            }
            if (vehicleProfile == null || lastSample == null) null
            else ParsedFile(item = item, header = vehicleProfile, lastSample = lastSample)
        }
    }

    /**
     * Reads one complete JSON value (object, array, or primitive) from a JsonReader
     * and returns it as a JSON string. Used to extract sub-objects without loading
     * the entire file.
     */
    private fun readJsonValue(reader: JsonReader): String {
        val sw = StringWriter()
        fun write(r: JsonReader, w: StringWriter) {
            when (r.peek()) {
                JsonToken.BEGIN_OBJECT -> {
                    r.beginObject(); w.write("{")
                    var first = true
                    while (r.hasNext()) {
                        if (!first) w.write(",")
                        first = false
                        w.write(JSONObject.quote(r.nextName()))
                        w.write(":")
                        write(r, w)
                    }
                    r.endObject(); w.write("}")
                }
                JsonToken.BEGIN_ARRAY -> {
                    r.beginArray(); w.write("[")
                    var first = true
                    while (r.hasNext()) {
                        if (!first) w.write(",")
                        first = false
                        write(r, w)
                    }
                    r.endArray(); w.write("]")
                }
                JsonToken.STRING -> w.write(JSONObject.quote(r.nextString()))
                JsonToken.NUMBER -> w.write(r.nextString())
                JsonToken.BOOLEAN -> w.write(r.nextBoolean().toString())
                JsonToken.NULL -> { r.nextNull(); w.write("null") }
                else -> r.skipValue()
            }
        }
        write(reader, sw)
        return sw.toString()
    }

    /**
     * Stream-writes a combined trip JSON file to the SAF log folder.
     * Re-opens each source file and copies sample lines directly to the output writer
     * one line at a time — peak memory is one line buffer per file, never the full content.
     */
    private fun saveCombinedFile(
        fileName: String,
        vehicleProfileJson: JSONObject,
        sourceFiles: List<TrackFileItem>
    ): Uri? {
        return try {
            val folderUriStr = AppSettings.getLogFolderUri(getApplication()) ?: return null
            val folder = DocumentFile.fromTreeUri(getApplication(), Uri.parse(folderUriStr))
                ?: return null
            val file = folder.createFile("application/json", fileName) ?: return null

            val sourceNamesArray = JSONArray().also { arr ->
                sourceFiles.forEach { arr.put(it.name) }
            }
            val headerJson = JSONObject().apply {
                put("appVersion", "combined")
                put("logStartedAt", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(Date()))
                put("isCombined", true)
                put("sourceFiles", sourceNamesArray)
                put("vehicleProfile", vehicleProfileJson)
            }

            getApplication<Application>().contentResolver
                .openOutputStream(file.uri, "wt")?.use { os ->
                    val writer = BufferedWriter(OutputStreamWriter(os, Charsets.UTF_8))
                    writer.write("{\"header\":")
                    writer.write(headerJson.toString())
                    writer.write(",\"samples\":[")
                    var firstSample = true
                    for (sourceFile in sourceFiles) {
                        val inStream = getApplication<Application>()
                            .contentResolver.openInputStream(sourceFile.uri) ?: continue
                        inStream.bufferedReader(Charsets.UTF_8).use { br ->
                            val reader = JsonReader(br)
                            try {
                                reader.beginObject()
                                while (reader.hasNext()) {
                                    if (reader.nextName() == "samples") {
                                        reader.beginArray()
                                        while (reader.hasNext()) {
                                            if (!firstSample) writer.write(",")
                                            writer.write(readJsonValue(reader))
                                            firstSample = false
                                        }
                                        reader.endArray()
                                    } else {
                                        reader.skipValue()
                                    }
                                }
                                reader.endObject()
                            } catch (e: Exception) {
                                Log.e(TAG, "saveCombinedFile: error reading ${sourceFile.name}", e)
                            }
                        }
                    }
                    writer.write("]}") 
                    writer.flush()
                }

            Log.d(TAG, "saveCombinedFile: Saved $fileName")
            file.uri
        } catch (e: Exception) {
            Log.e(TAG, "saveCombinedFile: Failed", e)
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
