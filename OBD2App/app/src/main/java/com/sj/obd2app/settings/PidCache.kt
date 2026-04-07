package com.sj.obd2app.settings

import org.json.JSONObject

/**
 * Represents cached PIDs discovered for a specific MAC address.
 */
data class PidCache(
    val macAddress: String,
    val discoveredPids: Map<String, CachedPidEntry>,
    val timestamp: Long,
    val protocolNumber: String? = null
) {
    companion object {
        fun fromJSON(json: JSONObject): PidCache {
            val pidsJson = json.getJSONObject("discoveredPids")
            val pids = mutableMapOf<String, CachedPidEntry>()
            pidsJson.keys().forEach { key ->
                pids[key] = CachedPidEntry.fromJSON(pidsJson.getJSONObject(key))
            }
            
            return PidCache(
                macAddress = json.getString("macAddress"),
                discoveredPids = pids,
                timestamp = json.getLong("timestamp"),
                protocolNumber = json.optString("protocolNumber", "").takeIf { it.isNotEmpty() }
            )
        }
    }
    
    fun toJSON(): JSONObject {
        val pidsJson = JSONObject()
        discoveredPids.forEach { (key, value) ->
            pidsJson.put(key, value.toJSON())
        }
        
        return JSONObject().apply {
            put("macAddress", macAddress)
            put("discoveredPids", pidsJson)
            put("timestamp", timestamp)
            protocolNumber?.let { put("protocolNumber", it) }
        }
    }
}

data class CachedPidEntry(
    val rawPidId: String,
    val commandString: String,
    val displayName: String,
    val value: String
) {
    companion object {
        fun fromJSON(json: JSONObject): CachedPidEntry {
            return CachedPidEntry(
                rawPidId = json.optString("rawPidId", ""),
                commandString = json.optString("commandString", ""),
                displayName = json.optString("displayName", ""),
                value = json.optString("value", "")
            )
        }
    }

    fun toJSON(): JSONObject {
        return JSONObject().apply {
            put("rawPidId", rawPidId)
            put("commandString", commandString)
            put("displayName", displayName)
            put("value", value)
        }
    }
}

/**
 * Represents the final state of metrics from the last trip.
 */
data class LastTripSnapshot(
    val timestamp: Long,
    val macAddress: String?,
    val vehicleProfileName: String?,
    val metrics: Map<String, String> // All OBD + calculated metrics
) {
    companion object {
        fun fromJSON(json: JSONObject): LastTripSnapshot {
            val metricsJson = json.getJSONObject("metrics")
            val metrics = mutableMapOf<String, String>()
            metricsJson.keys().forEach { key ->
                metrics[key] = metricsJson.optString(key, "")
            }
            
            return LastTripSnapshot(
                timestamp = json.getLong("timestamp"),
                macAddress = json.optString("macAddress", "").takeIf { it.isNotEmpty() },
                vehicleProfileName = json.optString("vehicleProfileName", "").takeIf { it.isNotEmpty() },
                metrics = metrics
            )
        }
    }
    
    fun toJSON(): JSONObject {
        val metricsJson = JSONObject()
        metrics.forEach { (key, value) ->
            metricsJson.put(key, value)
        }
        
        return JSONObject().apply {
            put("timestamp", timestamp)
            put("macAddress", macAddress ?: "")
            put("vehicleProfileName", vehicleProfileName ?: "")
            put("metrics", metricsJson)
        }
    }
}
