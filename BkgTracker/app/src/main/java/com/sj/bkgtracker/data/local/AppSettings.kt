package com.sj.bkgtracker.data.local

import android.content.Context
import com.sj.bkgtracker.domain.model.LocationRecord
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

object AppSettings {

    private const val CACHE_FILE = "location_cache.ndjson"

    @Serializable
    private data class CachedRecord(
        val lat: Double,
        val lon: Double,
        val timestampMs: Long,
        val accuracyM: Float,
        val speedKmh: Float,
        val altitudeM: Double,
        val bearingDeg: Float? = null
    )

    private val json = Json { prettyPrint = false }

    /** Append a single record to the cache file (O(1) operation) */
    fun appendRecord(context: Context, record: LocationRecord) {
        val cached = CachedRecord(
            lat = record.latitude,
            lon = record.longitude,
            timestampMs = record.timestampMs,
            accuracyM = record.accuracyM,
            speedKmh = record.speedKmh,
            altitudeM = record.altitudeM,
            bearingDeg = record.bearingDeg
        )
        val file = File(context.filesDir, CACHE_FILE)
        file.appendText(json.encodeToString(cached) + "\n")
    }

    /** Load all records from the NDJSON file */
    fun loadCache(context: Context): List<LocationRecord> {
        val file = File(context.filesDir, CACHE_FILE)
        if (!file.exists()) return emptyList()

        return try {
            file.readLines()
                .filter { it.isNotBlank() }
                .mapNotNull { line ->
                    try {
                        val cached: CachedRecord = json.decodeFromString(line)
                        LocationRecord(
                            latitude = cached.lat,
                            longitude = cached.lon,
                            timestampMs = cached.timestampMs,
                            accuracyM = cached.accuracyM,
                            speedKmh = cached.speedKmh,
                            altitudeM = cached.altitudeM,
                            bearingDeg = cached.bearingDeg
                        )
                    } catch (e: Exception) {
                        // Silently skip corrupted lines
                        null
                    }
                }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** Legacy method - now rewrites entire file (used for requeue) */
    fun saveCache(context: Context, records: List<LocationRecord>) {
        val file = File(context.filesDir, CACHE_FILE)
        file.writeText("")
        records.forEach { appendRecord(context, it) }
    }

    fun clearCache(context: Context) {
        File(context.filesDir, CACHE_FILE).delete()
    }

    fun getCacheFileSize(context: Context): Long {
        val file = File(context.filesDir, CACHE_FILE)
        return if (file.exists()) file.length() else 0L
    }
}
