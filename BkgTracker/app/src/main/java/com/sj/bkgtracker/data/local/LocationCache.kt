package com.sj.bkgtracker.data.local

import android.content.Context
import com.sj.bkgtracker.domain.model.LocationRecord
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object LocationCache {

    private val _cacheSize = MutableStateFlow(0)
    val cacheSize: StateFlow<Int> = _cacheSize.asStateFlow()

    private val _lastLocation = MutableStateFlow<LocationRecord?>(null)
    val lastLocation: StateFlow<LocationRecord?> = _lastLocation.asStateFlow()

    private val _lastSkippedStatus = MutableStateFlow<String?>(null)
    val lastSkippedStatus: StateFlow<String?> = _lastSkippedStatus.asStateFlow()

    private var inMemoryCache = mutableListOf<LocationRecord>()

    /** Must be called once at app startup (e.g., in Application.onCreate) */
    fun initialise(context: Context) {
        inMemoryCache = AppSettings.loadCache(context).toMutableList()
        _cacheSize.value = inMemoryCache.size
        _lastLocation.value = inMemoryCache.lastOrNull()
    }

    fun add(context: Context, record: LocationRecord) {
        inMemoryCache.add(record)
        _cacheSize.value = inMemoryCache.size
        _lastLocation.value = record
        _lastSkippedStatus.value = null // Clear skip status on successful save
        AppSettings.appendRecord(context, record) // O(1) append, not full rewrite
    }

    fun reportSkipped(lat: Double, lon: Double, reason: String) {
        _lastSkippedStatus.value = "Skipped: %.5f, %.5f (%s)".format(lat, lon, reason)
    }

    fun drainAll(context: Context): List<LocationRecord> {
        val drained = inMemoryCache.toList()
        inMemoryCache.clear()
        _cacheSize.value = 0
        AppSettings.saveCache(context, emptyList())
        return drained
    }

    fun requeue(context: Context, records: List<LocationRecord>) {
        inMemoryCache.addAll(0, records)
        _cacheSize.value = inMemoryCache.size
        AppSettings.saveCache(context, inMemoryCache)
    }
}
