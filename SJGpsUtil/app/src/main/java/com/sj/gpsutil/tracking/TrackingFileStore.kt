package com.sj.gpsutil.tracking

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import com.sj.gpsutil.data.OutputFormat
import com.sj.gpsutil.data.TrackingSettings
import java.io.OutputStream
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

private const val KML_MIME_TYPE = "application/vnd.google-earth.kml+xml"
private const val GPX_MIME_TYPE = "application/gpx+xml"
private const val JSON_MIME_TYPE = "application/json"

class TrackingFileStore(private val context: Context) {
    data class TrackFileHandle(
        val uri: Uri,
        val outputStream: OutputStream,
        val filename: String
    )

    fun createTrackOutputStream(settings: TrackingSettings, customName: String? = null): TrackFileHandle {
        val timestamp = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss").format(LocalDateTime.now())
        val prefix = customName?.takeIf { it.isNotBlank() } ?: "track"
        val (filename, mimeType) = when (settings.outputFormat) {
            OutputFormat.GPX -> "${prefix}_$timestamp.gpx" to GPX_MIME_TYPE
            OutputFormat.KML -> "${prefix}_$timestamp.kml" to KML_MIME_TYPE
            OutputFormat.JSON -> "${prefix}_$timestamp.json" to JSON_MIME_TYPE
        }
        val folderUri = settings.folderUri?.let { Uri.parse(it) }

        return if (folderUri != null) {
            createInFolder(folderUri, filename, mimeType)
        } else {
            createInDownloads(filename, mimeType)
        }
    }

    private fun createInDownloads(filename: String, mimeType: String): TrackFileHandle {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, filename)
            put(MediaStore.Downloads.MIME_TYPE, mimeType)
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error("Unable to create file in Downloads")
        val outputStream = resolver.openOutputStream(uri)
            ?: error("Unable to open output stream for Downloads file")
        return TrackFileHandle(uri, outputStream, filename)
    }

    private fun createInFolder(folderUri: Uri, filename: String, mimeType: String): TrackFileHandle {
        val folder = DocumentFile.fromTreeUri(context, folderUri)
            ?: error("Invalid folder Uri")
        val file = folder.createFile(mimeType, filename)
            ?: error("Unable to create file in selected folder")
        val outputStream = context.contentResolver.openOutputStream(file.uri)
            ?: error("Unable to open output stream for folder file")
        val resolvedName = file.name ?: filename
        return TrackFileHandle(file.uri, outputStream, resolvedName)
    }
}
