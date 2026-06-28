package com.sj.obd2app.metrics

import android.content.Context
import android.net.Uri
import android.util.JsonReader
import android.util.JsonToken
import android.util.Log
import org.json.JSONObject
import java.io.StringWriter

/**
 * Efficiently parses track log files by token-streaming with JsonReader.
 * Never loads the full file into memory — extracts only vehicleProfile and last sample.
 */
object TrackFileParser {

    private const val TAG = "TrackFileParser"

    data class TrackFileSummary(
        val vehicleProfile: JSONObject,
        val lastSample: JSONObject,
        val fileName: String
    )

    /**
     * Token-streams a track file to extract vehicleProfile from the header
     * and the last sample. Peak memory: two small JSONObjects, never the full file.
     */
    fun parseTrackFile(context: Context, uri: Uri, fileName: String): TrackFileSummary? {
        val inputStream = context.contentResolver.openInputStream(uri) ?: run {
            Log.e(TAG, "Failed to open input stream for $uri")
            return null
        }
        return try {
            inputStream.bufferedReader(Charsets.UTF_8).use { br ->
                val reader = JsonReader(br)
                var vehicleProfile: JSONObject? = null
                var lastSample: JSONObject? = null
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
                if (vehicleProfile == null || lastSample == null) {
                    Log.e(TAG, "Missing vehicleProfile or samples in $fileName")
                    null
                } else {
                    Log.d(TAG, "Successfully parsed $fileName")
                    TrackFileSummary(vehicleProfile, lastSample, fileName)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing $fileName: ${e.message}", e)
            null
        }
    }

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
                JsonToken.STRING  -> w.write(JSONObject.quote(r.nextString()))
                JsonToken.NUMBER  -> w.write(r.nextString())
                JsonToken.BOOLEAN -> w.write(r.nextBoolean().toString())
                JsonToken.NULL    -> { r.nextNull(); w.write("null") }
                else              -> r.skipValue()
            }
        }
        write(reader, sw)
        return sw.toString()
    }
}
