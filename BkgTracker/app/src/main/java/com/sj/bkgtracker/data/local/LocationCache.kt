package com.sj.bkgtracker.data.local

import com.sj.bkgtracker.domain.model.LocationRecord
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentLinkedQueue

object LocationCache {

    private val queue = ConcurrentLinkedQueue<LocationRecord>()

    private val _cacheSize = MutableStateFlow(0)
    val cacheSize: StateFlow<Int> = _cacheSize.asStateFlow()

    private val _lastLocation = MutableStateFlow<LocationRecord?>(null)
    val lastLocation: StateFlow<LocationRecord?> = _lastLocation.asStateFlow()

    fun add(record: LocationRecord) {
        queue.offer(record)
        _cacheSize.value = queue.size
        _lastLocation.value = record
    }

    fun drainAll(): List<LocationRecord> {
        val drained = mutableListOf<LocationRecord>()
        while (queue.isNotEmpty()) {
            queue.poll()?.let { drained.add(it) }
        }
        _cacheSize.value = 0
        return drained
    }

    fun requeue(records: List<LocationRecord>) {
        records.forEach { queue.offer(it) }
        _cacheSize.value = queue.size
    }
}
