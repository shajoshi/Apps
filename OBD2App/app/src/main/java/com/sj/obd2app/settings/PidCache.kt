package com.sj.obd2app.settings

import org.json.JSONObject

/**
 * Represents cached PIDs discovered for a specific MAC address.
 */
data class PidCache(
    val macAddress: String,
    val discoveredPids: Map<String, String>, // PID name -> last known value
    val timestamp: Long
) {
    companion object {
        fun fromJSON(json: JSONObject): PidCache {
            val pidsJson = json.getJSONObject("discoveredPids")
            val pids = mutableMapOf<String, String>()
            pidsJson.keys().forEach { key ->
                pids[key] = pidsJson.optString(key, "")
            }
            
            return PidCache(
                macAddress = json.getString("macAddress"),
                discoveredPids = pids,
                timestamp = json.getLong("timestamp")
            )
        }
    }
    
    fun toJSON(): JSONObject {
        val pidsJson = JSONObject()
        discoveredPids.forEach { (key, value) ->
            pidsJson.put(key, value)
        }
        
        return JSONObject().apply {
            put("macAddress", macAddress)
            put("discoveredPids", pidsJson)
            put("timestamp", timestamp)
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
