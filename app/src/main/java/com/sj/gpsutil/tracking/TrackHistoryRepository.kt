package com.sj.gpsutil.tracking

import android.content.Context
import android.location.Location
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import com.sj.gpsutil.data.TrackingSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader
import java.time.Instant
import java.time.format.DateTimeParseException

class TrackHistoryRepository(private val context: Context) {
    suspend fun listTracks(settings: TrackingSettings): List<TrackFileInfo> = withContext(Dispatchers.IO) {
        val entries = collectEntries(settings)
        entries.sortedByDescending { it.lastModified }
            .map { entry ->
                TrackFileInfo(
                    name = entry.name,
                    timestampMillis = if (entry.lastModified > 0) entry.lastModified else System.currentTimeMillis(),
                    distanceKm = null,
                    extension = entry.extension,
                    uri = entry.documentFile?.uri,
                    filePath = entry.file?.absolutePath,
                    sizeBytes = entry.sizeBytes
                )
            }
    }

    fun resolveFolderLabel(settings: TrackingSettings): String {
        val folderUri = settings.folderUri?.takeIf { it.isNotBlank() }?.let { Uri.parse(it) }
        if (folderUri != null) {
            val doc = DocumentFile.fromTreeUri(context, folderUri)
            val displayPath = folderUri.toDisplayPath()
            val name = doc?.name
            return when {
                !displayPath.isNullOrBlank() -> displayPath
                !name.isNullOrBlank() -> name!!
                else -> folderUri.toString()
            }
        }
        val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        return downloads?.absolutePath ?: "Downloads"
    }

    private fun Uri.toDisplayPath(): String? {
        return runCatching {
            val docId = DocumentsContract.getTreeDocumentId(this)
            val parts = docId.split(":", limit = 2)
            val type = parts.firstOrNull() ?: return null
            val relativePath = parts.getOrNull(1)
            val base = when {
                type.equals("primary", ignoreCase = true) -> Environment.getExternalStorageDirectory().absolutePath
                else -> null
            } ?: return null
            if (relativePath.isNullOrBlank()) base else "$base/${relativePath.trimStart('/')}"
        }.getOrNull()
    }

    private fun collectEntries(settings: TrackingSettings): List<FileEntry> {
        val folderUri = settings.folderUri?.let { Uri.parse(it) }
        return if (folderUri != null) {
            val folder = DocumentFile.fromTreeUri(context, folderUri) ?: return emptyList()
            folder.listFiles()
                .filter { it.isFile && it.name.isTrackFileName() }
                .map { doc ->
                    FileEntry(
                        name = doc.name.orEmpty(),
                        extension = doc.name.orEmpty().substringAfterLast('.', "").lowercase(),
                        lastModified = doc.lastModified(),
                        sizeBytes = doc.length(),
                        documentFile = doc
                    )
                }
        } else {
            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            downloadsDir?.listFiles()?.filter { it.isFile && it.name.isTrackFileName() }?.map { file ->
                FileEntry(
                    name = file.name,
                    extension = file.extension.lowercase(),
                    lastModified = file.lastModified(),
                    sizeBytes = file.length(),
                    file = file
                )
            } ?: emptyList()
        }
    }

    private fun String?.isTrackFileName(): Boolean {
        if (this.isNullOrBlank()) return false
        val lower = lowercase()
        return lower.endsWith(".kml") || lower.endsWith(".gpx") || lower.endsWith(".json")
    }

    suspend fun readJsonText(settings: TrackingSettings, info: TrackFileInfo): String? = withContext(Dispatchers.IO) {
        val entry = collectEntries(settings).find { candidate ->
            when {
                info.uri != null && candidate.documentFile?.uri == info.uri -> true
                info.filePath != null && candidate.file?.absolutePath == info.filePath -> true
                else -> candidate.name == info.name
            }
        } ?: return@withContext null
        if (entry.extension.lowercase() != "json") return@withContext null
        entry.readText(context)
    }

    suspend fun loadDetails(settings: TrackingSettings, info: TrackFileInfo): TrackDetails? = withContext(Dispatchers.IO) {
        val entry = collectEntries(settings).find { candidate ->
            when {
                info.uri != null && candidate.documentFile?.uri == info.uri -> true
                info.filePath != null && candidate.file?.absolutePath == info.filePath -> true
                else -> candidate.name == info.name
            }
        } ?: return@withContext null

        val parsed = when (entry.extension.lowercase()) {
            "kml" -> parseKmlPoints(entry)
            "gpx" -> parseGpxPoints(entry)
            "json" -> parseJsonPoints(entry)
            else -> ParsedTrack(emptyList())
        }

        val distanceMeters = computeDistanceMeters(parsed.points)
        val startMillis = parsed.points.firstOrNull()?.timestampMillis
        val endMillis = parsed.points.lastOrNull()?.timestampMillis
        val durationMillis = if (startMillis != null && endMillis != null) (endMillis - startMillis).coerceAtLeast(0) else null

        // Parse metric summaries for JSON files
        var trackingMetrics: TrackingMetricsSummary? = null
        var driverMetricsSummary: DriverMetricsSummary? = null
        if (entry.extension.lowercase() == "json") {
            val text = entry.readText(context)
            if (text != null) {
                val pair = parseMetricSummaries(text)
                trackingMetrics = pair.first
                driverMetricsSummary = pair.second
            }
        }

        TrackDetails(
            name = entry.name,
            distanceMeters = distanceMeters,
            pointCount = if (parsed.points.isNotEmpty()) parsed.points.size else null,
            startMillis = startMillis,
            endMillis = endMillis,
            durationMillis = durationMillis,
            trackingMetrics = trackingMetrics,
            driverMetrics = driverMetricsSummary
        )
    }

    private fun parseMetricSummaries(jsonText: String): Pair<TrackingMetricsSummary?, DriverMetricsSummary?> {
        return runCatching {
            val root = JSONObject(jsonText)
            val obj = root.optJSONObject("gpslogger2path") ?: return@runCatching Pair(null, null)
            val dataArray = obj.optJSONArray("data") ?: return@runCatching Pair(null, null)

            // Read minSpeed from file's recording settings if available, else default
            val meta = obj.optJSONObject("meta")
            val recSettings = meta?.optJSONObject("recordingSettings")
            val dtObj = recSettings?.optJSONObject("driverThresholds")
            val minSpeedForDetection = dtObj?.optDouble("minSpeedKmph", 6.0) ?: 6.0

            // Accumulators for tracking metrics
            val roadQualityCounts = mutableMapOf<String, Int>()
            val featureCounts = mutableMapOf<String, Int>()
            val rmsVals = mutableListOf<Double>()
            val peakVals = mutableListOf<Double>()
            val stdDevVals = mutableListOf<Double>()
            val peakRatioVals = mutableListOf<Double>()
            var accelPoints = 0

            // Accumulators for driver metrics
            val eventCounts = mutableMapOf<String, Int>()
            val smoothnessVals = mutableListOf<Double>()
            val fwdRmsVals = mutableListOf<Double>()
            val fwdMaxVals = mutableListOf<Double>()
            val latRmsVals = mutableListOf<Double>()
            val latMaxVals = mutableListOf<Double>()
            val frictionVals = mutableListOf<Double>()
            val leanVals = mutableListOf<Double>()
            var movingFixes = 0

            for (i in 0 until dataArray.length()) {
                val point = dataArray.optJSONObject(i) ?: continue
                val gps = point.optJSONObject("gps")
                val speed = gps?.optDouble("speed", 0.0) ?: 0.0
                val accel = point.optJSONObject("accel")
                val driver = point.optJSONObject("driver")

                // Tracking metrics from accel
                if (accel != null) {
                    accelPoints++
                    val rms = accel.optDouble("rms", Double.NaN)
                    val magMax = accel.optDouble("magMax", Double.NaN)
                    val stdDev = accel.optDouble("stdDev", Double.NaN)
                    val peakRatio = accel.optDouble("peakRatio", Double.NaN)

                    if (!rms.isNaN()) rmsVals.add(rms)
                    if (!magMax.isNaN()) peakVals.add(magMax)
                    if (!stdDev.isNaN()) stdDevVals.add(stdDev)
                    if (!peakRatio.isNaN()) peakRatioVals.add(peakRatio)

                    val rq = accel.optString("roadQuality", "")
                    if (rq.isNotEmpty()) {
                        roadQualityCounts[rq] = (roadQualityCounts[rq] ?: 0) + 1
                    } else if (speed < minSpeedForDetection) {
                        roadQualityCounts["below_speed"] = (roadQualityCounts["below_speed"] ?: 0) + 1
                    }

                    val feat = accel.optString("featureDetected", "")
                    if (feat.isNotEmpty()) {
                        featureCounts[feat] = (featureCounts[feat] ?: 0) + 1
                    }

                    // Driver fwd/lat metrics from accel object
                    val fwdRms = accel.optDouble("fwdRms", Double.NaN)
                    val fwdMax = accel.optDouble("fwdMax", Double.NaN)
                    val latRms = accel.optDouble("latRms", Double.NaN)
                    val latMax = accel.optDouble("latMax", Double.NaN)
                    val lean = accel.optDouble("leanAngleDeg", Double.NaN)

                    if (speed >= minSpeedForDetection) {
                        movingFixes++
                        if (!fwdRms.isNaN()) fwdRmsVals.add(fwdRms)
                        if (!fwdMax.isNaN()) fwdMaxVals.add(fwdMax)
                        if (!latRms.isNaN()) latRmsVals.add(latRms)
                        if (!latMax.isNaN()) latMaxVals.add(latMax)
                        if (!lean.isNaN()) leanVals.add(kotlin.math.abs(lean))
                        if (!fwdMax.isNaN() && !latMax.isNaN()) {
                            frictionVals.add(kotlin.math.sqrt(fwdMax * fwdMax + latMax * latMax))
                        }
                    }
                }

                // Driver metrics from driver object
                if (driver != null) {
                    val primaryEvent = driver.optString("primaryEvent", "normal")
                    eventCounts[primaryEvent] = (eventCounts[primaryEvent] ?: 0) + 1

                    val sm = driver.optDouble("smoothnessScore", Double.NaN)
                    if (!sm.isNaN() && speed >= minSpeedForDetection) smoothnessVals.add(sm)
                }
            }

            val totalPoints = dataArray.length()

            val trackingSummary = if (accelPoints > 0 && rmsVals.isNotEmpty()) {
                TrackingMetricsSummary(
                    totalPoints = totalPoints,
                    accelPoints = accelPoints,
                    roadQualityCounts = roadQualityCounts,
                    featureCounts = featureCounts,
                    rmsVertMin = rmsVals.min(), rmsVertMax = rmsVals.max(), rmsVertMean = rmsVals.average(),
                    peakZMin = if (peakVals.isNotEmpty()) peakVals.min() else 0.0,
                    peakZMax = if (peakVals.isNotEmpty()) peakVals.max() else 0.0,
                    peakZMean = if (peakVals.isNotEmpty()) peakVals.average() else 0.0,
                    stdDevMin = if (stdDevVals.isNotEmpty()) stdDevVals.min() else 0.0,
                    stdDevMax = if (stdDevVals.isNotEmpty()) stdDevVals.max() else 0.0,
                    stdDevMean = if (stdDevVals.isNotEmpty()) stdDevVals.average() else 0.0,
                    peakRatioMin = if (peakRatioVals.isNotEmpty()) peakRatioVals.min() else 0.0,
                    peakRatioMax = if (peakRatioVals.isNotEmpty()) peakRatioVals.max() else 0.0,
                    peakRatioMean = if (peakRatioVals.isNotEmpty()) peakRatioVals.average() else 0.0,
                )
            } else null

            val driverSummary = if (eventCounts.isNotEmpty()) {
                DriverMetricsSummary(
                    totalFixes = totalPoints,
                    movingFixes = movingFixes,
                    eventCounts = eventCounts,
                    avgSmoothnessScore = if (smoothnessVals.isNotEmpty()) smoothnessVals.average() else 0.0,
                    avgFwdRms = if (fwdRmsVals.isNotEmpty()) fwdRmsVals.average() else 0.0,
                    avgFwdMax = if (fwdMaxVals.isNotEmpty()) fwdMaxVals.average() else 0.0,
                    maxFwdMax = if (fwdMaxVals.isNotEmpty()) fwdMaxVals.max() else 0.0,
                    avgLatRms = if (latRmsVals.isNotEmpty()) latRmsVals.average() else 0.0,
                    avgLatMax = if (latMaxVals.isNotEmpty()) latMaxVals.average() else 0.0,
                    maxLatMax = if (latMaxVals.isNotEmpty()) latMaxVals.max() else 0.0,
                    maxFrictionCircle = if (frictionVals.isNotEmpty()) frictionVals.max() else 0.0,
                    avgLeanAngle = if (leanVals.isNotEmpty()) leanVals.average() else 0.0,
                    maxLeanAngle = if (leanVals.isNotEmpty()) leanVals.max() else 0.0,
                )
            } else null

            Pair(trackingSummary, driverSummary)
        }.getOrElse { Pair(null, null) }
    }

    private fun computeDistanceMeters(points: List<TrackPoint>): Double? {
        if (points.size < 2) return null
        var distance = 0.0
        val result = FloatArray(1)
        for (i in 1 until points.size) {
            val a = points[i - 1]
            val b = points[i]
            Location.distanceBetween(a.lat, a.lon, b.lat, b.lon, result)
            distance += result[0]
        }
        return distance
    }

    private data class ParsedTrack(val points: List<TrackPoint>)
    private data class TrackPoint(val lat: Double, val lon: Double, val timestampMillis: Long?)

    private fun parseKmlPoints(entry: FileEntry): ParsedTrack {
        val text = entry.readText(context) ?: return ParsedTrack(emptyList())
        val coords = mutableListOf<Pair<Double, Double>>()
        val times = mutableListOf<Long?>()

        val gxRegex = Regex("<gx:coord>(.*?)</gx:coord>")
        gxRegex.findAll(text).forEach { matchResult ->
            val parts = matchResult.groupValues[1].trim().split(" ")
            if (parts.size >= 2) {
                val lon = parts[0].toDoubleOrNull()
                val lat = parts[1].toDoubleOrNull()
                if (lat != null && lon != null) coords.add(lat to lon)
            }
        }

        val whenRegex = Regex("<when>(.*?)</when>")
        whenRegex.findAll(text).forEach { matchResult ->
            val ts = matchResult.groupValues[1]
            val millis = runCatching { Instant.parse(ts).toEpochMilli() }.getOrNull()
            times.add(millis)
        }

        val points = coords.mapIndexed { index, (lat, lon) ->
            val ts = times.getOrNull(index)
            TrackPoint(lat, lon, ts)
        }
        return ParsedTrack(points)
    }

    private fun parseGpxPoints(entry: FileEntry): ParsedTrack {
        val text = entry.readText(context) ?: return ParsedTrack(emptyList())
        val regex = Regex("""<trkpt[^>]*lat=\"([^\"]+)\"[^>]*lon=\"([^\"]+)\"[^>]*>\s*(?:<ele>.*?</ele>\s*)?(?:<time>(.*?)</time>)?""", RegexOption.DOT_MATCHES_ALL)
        val points = regex.findAll(text).mapNotNull { match ->
            val lat = match.groupValues[1].toDoubleOrNull()
            val lon = match.groupValues[2].toDoubleOrNull()
            val timeStr = match.groupValues.getOrNull(3)?.takeIf { it.isNotBlank() }
            val ts = timeStr?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
            if (lat != null && lon != null) TrackPoint(lat, lon, ts) else null
        }.toList()
        return ParsedTrack(points)
    }

    private fun parseJsonPoints(entry: FileEntry): ParsedTrack {
        val text = entry.readText(context) ?: return ParsedTrack(emptyList())
        return runCatching {
            val root = JSONObject(text)
            val obj = root.optJSONObject("gpslogger2path") ?: return@runCatching ParsedTrack(emptyList())
            val dataArray = obj.optJSONArray("data") ?: return@runCatching ParsedTrack(emptyList())
            val startTs = obj.optJSONObject("meta")?.optLong("ts")
            val points = buildList {
                for (i in 0 until dataArray.length()) {
                    val gps = dataArray.optJSONObject(i)?.optJSONObject("gps") ?: continue
                    val lat = gps.optDouble("lat")
                    val lon = gps.optDouble("lon")
                    val offset = gps.optLong("ts")
                    if (!lat.isNaN() && !lon.isNaN()) add(TrackPoint(lat, lon, startTs?.plus(offset)))
                }
            }
            ParsedTrack(points)
        }.getOrElse { ParsedTrack(emptyList()) }
    }

    private fun FileEntry.readText(context: Context): String? {
        return when {
            documentFile != null -> context.contentResolver.openInputStream(documentFile.uri)?.use { it.readAllText() }
            file != null -> file.inputStream().use { it.readAllText() }
            else -> null
        }
    }

    private fun InputStream.readAllText(): String {
        val reader = BufferedReader(InputStreamReader(this))
        val builder = StringBuilder()
        var line: String?
        while (reader.readLine().also { line = it } != null) {
            builder.append(line).append('\n')
        }
        return builder.toString()
    }

    data class TrackFileInfo(
        val name: String,
        val timestampMillis: Long,
        val distanceKm: Double?,
        val extension: String,
        val uri: Uri? = null,
        val filePath: String? = null,
        val sizeBytes: Long? = null,
    )

    data class TrackDetails(
        val name: String,
        val distanceMeters: Double?,
        val pointCount: Int?,
        val startMillis: Long?,
        val endMillis: Long?,
        val durationMillis: Long?,
        val trackingMetrics: TrackingMetricsSummary? = null,
        val driverMetrics: DriverMetricsSummary? = null,
    )

    data class TrackingMetricsSummary(
        val totalPoints: Int,
        val accelPoints: Int,
        val roadQualityCounts: Map<String, Int>,  // smooth, average, rough, below_speed
        val featureCounts: Map<String, Int>,       // speed_bump, pothole, bump
        val rmsVertMin: Double, val rmsVertMax: Double, val rmsVertMean: Double,
        val peakZMin: Double, val peakZMax: Double, val peakZMean: Double,
        val stdDevMin: Double, val stdDevMax: Double, val stdDevMean: Double,
        val peakRatioMin: Double, val peakRatioMax: Double, val peakRatioMean: Double,
    )

    data class DriverMetricsSummary(
        val totalFixes: Int,
        val movingFixes: Int,
        val eventCounts: Map<String, Int>,  // hard_brake, hard_accel, swerve, aggressive_corner, normal, low_speed
        val avgSmoothnessScore: Double,
        val avgFwdRms: Double, val avgFwdMax: Double, val maxFwdMax: Double,
        val avgLatRms: Double, val avgLatMax: Double, val maxLatMax: Double,
        val maxFrictionCircle: Double,
        val avgLeanAngle: Double, val maxLeanAngle: Double,
    )

    private data class FileEntry(
        val name: String,
        val extension: String,
        val lastModified: Long,
        val sizeBytes: Long?,
        val documentFile: DocumentFile? = null,
        val file: File? = null
    )
}
