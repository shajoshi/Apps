package com.sj.obd2app.metrics

import android.content.Context
import android.net.Uri
import android.util.Log
import org.json.JSONObject

/**
 * Loads the full sample array from a track file for map visualization.
 */
object TrackFileMapParser {

    private const val TAG = "TrackFileMapParser"

    data class TrackPathData(
        val fileName: String,
        val samples: List<JSONObject>
    )

    fun parseTrackPath(context: Context, uri: Uri, fileName: String): TrackPathData? {
        return try {
            val inputStream = context.contentResolver.openInputStream(uri) ?: return null
            inputStream.use { stream ->
                val rootJson = JSONObject(stream.bufferedReader(Charsets.UTF_8).use { it.readText() })
                val samplesArray = rootJson.optJSONArray("samples") ?: return null
                val samples = buildList {
                    for (i in 0 until samplesArray.length()) {
                        val sample = samplesArray.optJSONObject(i)
                        if (sample != null) add(sample)
                    }
                }
                if (samples.isEmpty()) {
                    Log.e(TAG, "No samples found for map path")
                    null
                } else {
                    TrackPathData(fileName = fileName, samples = samples)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing track path: ${e.message}", e)
            null
        }
    }
}
