package com.sj.obd2app.ui.mapview

import android.app.Application
import android.util.JsonReader
import android.util.JsonToken
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.sj.obd2app.ui.tripsummary.TripSelectionStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.osmdroid.util.GeoPoint
import java.io.StringWriter

class MapViewModel(application: Application) : AndroidViewModel(application) {

    private val TAG = "MapViewModel"

    val selectedTrack get() = TripSelectionStore.selectedTrack

    private val _pathPoints = MutableStateFlow<List<GeoPoint>>(emptyList())
    val pathPoints: StateFlow<List<GeoPoint>> = _pathPoints

    private val _pathLoading = MutableStateFlow(false)
    val pathLoading: StateFlow<Boolean> = _pathLoading

    private val _fetchedSample = MutableStateFlow<JSONObject?>(null)
    val fetchedSample: StateFlow<JSONObject?> = _fetchedSample

    val sampleCount: Int get() = _pathPoints.value.size

    /**
     * Streams the track file extracting only lat/lon per sample into GeoPoints.
     * No JSONObject is held in memory — peak cost is one GeoPoint (~40 bytes) per sample.
     */
    fun loadPathPoints() {
        val track = TripSelectionStore.selectedTrack ?: return
        viewModelScope.launch {
            _pathLoading.value = true
            _pathPoints.value = emptyList()
            _fetchedSample.value = null
            try {
                val points = withContext(Dispatchers.IO) {
                    val inputStream = getApplication<Application>()
                        .contentResolver.openInputStream(track.uri)
                        ?: return@withContext emptyList<GeoPoint>()
                    inputStream.bufferedReader(Charsets.UTF_8).use { br ->
                        val result = mutableListOf<GeoPoint>()
                        val reader = JsonReader(br)
                        try {
                            reader.beginObject()
                            while (reader.hasNext()) {
                                if (reader.nextName() == "samples") {
                                    reader.beginArray()
                                    while (reader.hasNext()) {
                                        val geoPoint = readGeoPoint(reader)
                                        if (geoPoint != null) result.add(geoPoint)
                                    }
                                    reader.endArray()
                                } else {
                                    reader.skipValue()
                                }
                            }
                            reader.endObject()
                        } catch (e: Exception) {
                            Log.e(TAG, "loadPathPoints: parse error", e)
                        }
                        result
                    }
                }
                _pathPoints.value = points
                Log.d(TAG, "loadPathPoints: ${points.size} GPS points for ${track.fileName}")
            } catch (e: Exception) {
                Log.e(TAG, "loadPathPoints: Failed", e)
            } finally {
                _pathLoading.value = false
            }
        }
    }

    /**
     * Re-opens the file and reads the single sample at [index] on demand.
     * Only one JSONObject is ever in memory at a time.
     */
    fun fetchSample(index: Int) {
        val track = TripSelectionStore.selectedTrack ?: return
        viewModelScope.launch {
            val sample = withContext(Dispatchers.IO) {
                val inputStream = getApplication<Application>()
                    .contentResolver.openInputStream(track.uri)
                    ?: return@withContext null
                inputStream.bufferedReader(Charsets.UTF_8).use { br ->
                    val reader = JsonReader(br)
                    var result: JSONObject? = null
                    try {
                        reader.beginObject()
                        while (reader.hasNext()) {
                            if (reader.nextName() == "samples") {
                                reader.beginArray()
                                var i = 0
                                while (reader.hasNext()) {
                                    if (i == index) {
                                        result = JSONObject(readJsonValue(reader))
                                        break
                                    } else {
                                        reader.skipValue()
                                        i++
                                    }
                                }
                                break
                            } else {
                                reader.skipValue()
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "fetchSample[$index]: parse error", e)
                    }
                    result
                }
            }
            _fetchedSample.value = sample
        }
    }

    fun clearSelection() {
        TripSelectionStore.clearSelectedTrack()
        _pathPoints.value = emptyList()
        _fetchedSample.value = null
    }

    /**
     * Reads one sample object from the JsonReader extracting only lat/lon.
     * Skips all other fields — allocates only a GeoPoint, not a JSONObject.
     */
    private fun readGeoPoint(reader: JsonReader): GeoPoint? {
        var lat = Double.NaN
        var lon = Double.NaN
        reader.beginObject()
        while (reader.hasNext()) {
            if (reader.nextName() == "gps") {
                reader.beginObject()
                while (reader.hasNext()) {
                    when (reader.nextName()) {
                        "lat" -> lat = reader.nextDouble()
                        "lon" -> lon = reader.nextDouble()
                        else  -> reader.skipValue()
                    }
                }
                reader.endObject()
            } else {
                reader.skipValue()
            }
        }
        reader.endObject()
        return if (!lat.isNaN() && !lon.isNaN()) GeoPoint(lat, lon) else null
    }

    /**
     * Reconstructs one complete JSON value token-by-token into a String.
     * Used only for fetchSample — not called during path loading.
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
