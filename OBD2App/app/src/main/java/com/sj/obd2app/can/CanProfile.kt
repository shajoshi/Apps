package com.sj.obd2app.can

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * User-created CAN Bus logging profile.
 *
 * Each profile binds a DBC file (copied into app-private `files/can_dbc/<id>.dbc`) to a
 * selection of signals to decode and a sampling cadence. At most one profile is marked as
 * [isDefault] across the whole repository.
 */
data class CanProfile(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val objective: String = "",
    val dbcFileName: String,
    val selectedSignals: List<SignalRef> = emptyList(),
    val samplingMs: Long = 500L,
    /** Optional filter — limits ELM327 `ATCRA`/`ATCF+ATCM` to these IDs. `null` = no HW filter. */
    val canIdFilter: List<Int>? = null,
    /** If true, raw frames are written alongside the trip log during real (non-mock) scans. */
    val recordRawFrames: Boolean = false,
    /**
     * Optional original file name of a CAN capture (JSONL, same schema as the scanner's
     * `*.raw.jsonl`) used to drive mock scans. The imported copy lives at
     * `files/can_captures/<id>.jsonl`. When set and the app is in mock mode, `CanBusScanner`
     * replays this file instead of generating synthetic frames.
     */
    val playbackCaptureFileName: String? = null,
    val isDefault: Boolean = false,
    /** When true and the app is in mock mode, skip DBC loading and use [DemoDbcDatabase] instead. */
    val useDemoData: Boolean = false
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("objective", objective)
        put("dbcFileName", dbcFileName)
        put("samplingMs", samplingMs)
        put("recordRawFrames", recordRawFrames)
        put("isDefault", isDefault)
        put("useDemoData", useDemoData)
        playbackCaptureFileName?.let { put("playbackCaptureFileName", it) }
        put("selectedSignals", JSONArray().apply {
            selectedSignals.forEach { ref ->
                put(JSONObject().apply {
                    put("messageId", ref.messageId)
                    put("signalName", ref.signalName)
                })
            }
        })
        canIdFilter?.let { ids ->
            put("canIdFilter", JSONArray().apply { ids.forEach { put(it) } })
        }
    }

    companion object {
        fun fromJson(json: JSONObject): CanProfile {
            val selected = mutableListOf<SignalRef>()
            if (json.has("selectedSignals")) {
                val arr = json.getJSONArray("selectedSignals")
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    selected += SignalRef(
                        messageId = o.getInt("messageId"),
                        signalName = o.getString("signalName")
                    )
                }
            }
            val filter = if (json.has("canIdFilter")) {
                val arr = json.getJSONArray("canIdFilter")
                (0 until arr.length()).map { arr.getInt(it) }
            } else null
            return CanProfile(
                id = json.getString("id"),
                name = json.getString("name"),
                objective = json.optString("objective", ""),
                dbcFileName = json.getString("dbcFileName"),
                selectedSignals = selected,
                samplingMs = json.optLong("samplingMs", 500L),
                canIdFilter = filter,
                recordRawFrames = json.optBoolean("recordRawFrames", false),
                playbackCaptureFileName = json.optString("playbackCaptureFileName", "")
                    .takeIf { it.isNotEmpty() },
                isDefault = json.optBoolean("isDefault", false),
                useDemoData = json.optBoolean("useDemoData", false)
            )
        }
    }
}
