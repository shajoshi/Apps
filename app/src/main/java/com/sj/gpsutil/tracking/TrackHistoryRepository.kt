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

        TrackDetails(
            name = entry.name,
            distanceMeters = distanceMeters,
            pointCount = if (parsed.points.isNotEmpty()) parsed.points.size else null,
            startMillis = startMillis,
            endMillis = endMillis,
            durationMillis = durationMillis
        )
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
