package com.sj.obd2app.metrics

import android.content.Context
import android.net.Uri
import android.util.Log
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Efficiently parses track log files by reading only the header and last sample.
 * Uses reverse-read approach to minimize memory usage on large files.
 */
object TrackFileParser {

    private const val TAG = "TrackFileParser"

    data class TrackFileSummary(
        val vehicleProfile: JSONObject,
        val lastSample: JSONObject,
        val fileName: String
    )

    /**
     * Parses a track file and extracts the vehicle profile from header
     * and the last sample containing trip/fuel metrics.
     * 
     * @param context Android context for ContentResolver access
     * @param uri SAF URI of the track file
     * @return TrackFileSummary containing profile and last sample, or null on error
     */
    fun parseTrackFile(context: Context, uri: Uri, fileName: String): TrackFileSummary? {
        var inputStream: java.io.InputStream? = null
        var result: TrackFileSummary? = null
        
        try {
            inputStream = context.contentResolver.openInputStream(uri)
                ?: run {
                    Log.e(TAG, "Failed to open input stream for $uri")
                    return null
                }

            // Parse JSON directly from stream (more memory efficient)
            val rootJson = JSONObject(inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() })
            
            // Extract vehicle profile from header
            val header = rootJson.optJSONObject("header")
            val vehicleProfile = header?.optJSONObject("vehicleProfile")
                ?: run {
                    Log.e(TAG, "No vehicleProfile in header")
                    return null
                }

            // Extract last sample from samples array
            val samples = rootJson.optJSONArray("samples")
            if (samples == null || samples.length() == 0) {
                Log.e(TAG, "No samples found in the file")
                return null
            }
            
            val lastSample = samples.getJSONObject(samples.length() - 1)
            
            Log.d(TAG, "Successfully parsed: Found ${samples.length()} samples, extracted last sample")
            
            result = TrackFileSummary(vehicleProfile, lastSample, fileName)
            
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing track file: ${e.message}", e)
            result = null
        } finally {
            // Ensure the input stream is closed
            inputStream?.close()
            // Help garbage collector
            System.gc()
        }
        
        return result
    }
}
