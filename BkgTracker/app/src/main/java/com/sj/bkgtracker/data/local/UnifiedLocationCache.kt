package com.sj.bkgtracker.data.local

import android.content.Context
import com.sj.bkgtracker.domain.model.LocationRecord
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Unified location cache that handles both upload queue and map display data.
 * Replaces LocationCache and MapDataCache with a single, efficient cache system.
 */
object UnifiedLocationCache {

    private const val CACHE_DURATION_MS = 48 * 60 * 60 * 1000L // 48 hours

    // Per-user data: userId -> list of location records (for map display)
    private val userCaches = mutableMapOf<String, MutableList<LocationRecord>>()
    
    // Upload queue: userId -> list of unsaved points (pending upload)
    private val uploadQueue = mutableMapOf<String, MutableList<LocationRecord>>()
    
    // Cache user emails: userId -> email
    private val userEmails = mutableMapOf<String, String>()
    
    // Track when we last successfully fetched data for each user (from Firebase)
    private val lastFetchTimes = mutableMapOf<String, Long>()
    
    // Track what time window was actually fetched from Firebase for each user
    // Maps userId -> Pair<earliestTimestampFetched, latestTimestampFetched>
    private val fetchedWindows = mutableMapOf<String, Pair<Long, Long>>()
    
    // Current user tracking
    private var currentUserId: String? = null

    // State flows for UI observation
    private val _cacheStats = MutableStateFlow(CacheStats(0, 0, 0))
    val cacheStats: StateFlow<CacheStats> 
        get() {
            cleanupOldPoints()
            return _cacheStats.asStateFlow()
        }

    private val _lastLocation = MutableStateFlow<LocationRecord?>(null)
    val lastLocation: StateFlow<LocationRecord?> = _lastLocation.asStateFlow()

    private val _lastSkippedStatus = MutableStateFlow<String?>(null)
    val lastSkippedStatus: StateFlow<String?> = _lastSkippedStatus.asStateFlow()

    data class CacheStats(
        val totalCachedPoints: Int,      // All points across all users
        val cachedUserCount: Int,        // Users with cached data
        val unsavedPointsCount: Int      // Current user's unsaved points
    )

    /** Must be called once at app startup to set current user and load persisted data */
    fun initialise(context: Context, userId: String) {
        currentUserId = userId
        // Load upload queue from persisted storage
        uploadQueue[userId] = AppSettings.loadCache(context).toMutableList()
        updateCacheStats()
        _lastLocation.value = uploadQueue[userId]?.lastOrNull()
    }

    /** Set current user (for upload queue operations) */
    fun setCurrentUser(userId: String) {
        currentUserId = userId
    }

    /** Add a location record for a user (from GPS) */
    fun addPoint(context: Context, userId: String, record: LocationRecord) {
        // Add to user cache for map display
        val userCache = userCaches.getOrPut(userId) { mutableListOf() }
        userCache.add(record)
        
        // Note: We do NOT update fetchedWindows here - that's only for tracking what was READ from Firebase
        // The write pipeline (GPS -> Firebase) is completely separate from the read cache optimization
        
        // Add to upload queue if this is for current user
        if (userId == currentUserId) {
            val queue = uploadQueue.getOrPut(userId) { mutableListOf() }
            queue.add(record)
            
            _lastLocation.value = record
            _lastSkippedStatus.value = null
            
            // Persist to AppSettings for upload queue
            AppSettings.appendRecord(context, record)
        }
        
        updateCacheStats()
    }

    /** Get unsaved points for current user (for upload) */
    fun getUnsavedPoints(): List<LocationRecord> {
        val userId = currentUserId ?: return emptyList()
        return uploadQueue[userId]?.toList() ?: emptyList()
    }

    /** Drain unsaved points for current user (after successful upload) */
    fun drainUnsavedPoints(context: Context): List<LocationRecord> {
        val userId = currentUserId ?: return emptyList()
        val drained = uploadQueue[userId]?.toList() ?: emptyList()
        
        uploadQueue[userId]?.clear()
        AppSettings.saveCache(context, emptyList())
        
        updateCacheStats()
        return drained
    }

    /** Requeue unsaved points for current user (after failed upload) */
    fun requeueUnsavedPoints(context: Context, records: List<LocationRecord>) {
        val userId = currentUserId ?: return
        val queue = uploadQueue.getOrPut(userId) { mutableListOf() }
        queue.addAll(0, records)
        AppSettings.saveCache(context, queue)
        updateCacheStats()
    }

    /** Get cached points for a user within time window (for map display) 
     * Returns: (points, fetchedWindow) where fetchedWindow is Pair<earliest, latest> timestamp fetched from Firebase
     */
    fun getCachedPoints(userId: String, since: Long): Pair<List<LocationRecord>, Pair<Long, Long>?> {
        val points = userCaches[userId]?.filter { it.timestampMs >= since } ?: emptyList()
        val window = fetchedWindows[userId]
        return Pair(points, window)
    }

    /** Add points for a user (from Firebase sync) - avoids duplicates 
     * @param since The 'since' timestamp that was queried from Firebase (earliest timestamp in the fetch window)
     */
    fun addPoints(userId: String, points: List<LocationRecord>, since: Long) {
        val existing = userCaches.getOrPut(userId) { mutableListOf() }
        val existingTimestamps = existing.map { it.timestampMs }.toSet()
        val newPoints = points.filter { it.timestampMs !in existingTimestamps }
        existing.addAll(newPoints)
        lastFetchTimes[userId] = System.currentTimeMillis()
        
        // Update fetched window - extend to include the new fetch range
        val currentWindow = fetchedWindows[userId]
        val newOldest = minOf(since, currentWindow?.first ?: Long.MAX_VALUE)
        val newLatest = if (points.isNotEmpty()) {
            maxOf(points.maxOf { it.timestampMs }, currentWindow?.second ?: 0L)
        } else {
            currentWindow?.second ?: System.currentTimeMillis()
        }
        fetchedWindows[userId] = Pair(newOldest, newLatest)
        
        updateCacheStats()
    }

    /** Check if cache has valid data for a user */
    fun hasValidCache(userId: String): Boolean {
        val lastFetch = lastFetchTimes[userId] ?: return false
        val cutoff = System.currentTimeMillis() - CACHE_DURATION_MS
        return lastFetch > cutoff && userCaches[userId]?.isNotEmpty() == true
    }

    /** Check if cache covers the requested time window 
     * Returns true if cache covers the start of requested window (we'll do incremental fetch for any gap)
     */
    fun cacheCoversTimeWindow(userId: String, since: Long): Boolean {
        if (!hasValidCache(userId)) return false
        val window = fetchedWindows[userId] ?: return false
        val userCache = userCaches[userId] ?: return false
        if (userCache.isEmpty()) return false
        
        // Check if fetched window covers the requested start time
        // window.first = earliest timestamp we fetched from Firebase
        // As long as we cover the start, we'll use cache + incremental fetch for the gap
        return window.first <= since
    }

    /** Get cached email for a user */
    fun getCachedEmail(userId: String): String? = userEmails[userId]

    /** Set cached email for a user */
    fun setEmail(userId: String, email: String) {
        userEmails[userId] = email
    }

    /** Get last fetch time for a user (for cache validity checks) */
    fun getLastFetchTime(userId: String): Long = lastFetchTimes[userId] ?: 0L
    
    /** Get the fetched window for a user (earliest, latest) timestamps that were fetched from Firebase */
    fun getFetchedWindow(userId: String): Pair<Long, Long>? = fetchedWindows[userId]

    /** Report skipped location (for UI feedback) */
    fun reportSkipped(lat: Double, lon: Double, reason: String) {
        _lastSkippedStatus.value = "Skipped: %.5f, %.5f (%s)".format(lat, lon, reason)
    }

    /** Get cache information string (for Usage screen) */
    fun getCacheInfo(): String {
        cleanupOldPoints()
        val totalPoints = userCaches.values.sumOf { it.size }
        val userCount = userCaches.size
        val oldestPoint = userCaches.values.flatten().minOfOrNull { it.timestampMs }
        val newestPoint = userCaches.values.flatten().maxOfOrNull { it.timestampMs }
        
        return "Cache: $userCount users, $totalPoints points" +
                (if (oldestPoint != null) " (${(System.currentTimeMillis() - oldestPoint) / 3600000}h ago to ${(System.currentTimeMillis() - newestPoint!!) / 3600000}h ago)" else "")
    }

    /** Clear all cached data */
    fun clearCache() {
        userCaches.clear()
        uploadQueue.clear()
        lastFetchTimes.clear()
        fetchedWindows.clear()
        userEmails.clear()
        updateCacheStats()
    }

    /** Clean up old points (>48 hours) and update cache stats */
    fun cleanupOldPoints() {
        val cutoff = System.currentTimeMillis() - CACHE_DURATION_MS
        
        // Clean up user caches
        val userIterator = userCaches.entries.iterator()
        while (userIterator.hasNext()) {
            val (uid, points) = userIterator.next()
            val filtered = points.filter { it.timestampMs >= cutoff }
            
            if (filtered.isEmpty()) {
                userIterator.remove()
                lastFetchTimes.remove(uid)
                fetchedWindows.remove(uid)
                userEmails.remove(uid)
            } else {
                userCaches[uid] = filtered.toMutableList()
            }
        }
        
        // Clean up upload queue
        val queueIterator = uploadQueue.entries.iterator()
        while (queueIterator.hasNext()) {
            val (uid, points) = queueIterator.next()
            val filtered = points.filter { it.timestampMs >= cutoff }
            
            if (filtered.isEmpty()) {
                queueIterator.remove()
            } else {
                uploadQueue[uid] = filtered.toMutableList()
            }
        }
        
        updateCacheStats()
    }

    /** Update cache statistics */
    private fun updateCacheStats() {
        val totalPoints = userCaches.values.sumOf { it.size }
        val userCount = userCaches.size
        val unsavedCount = currentUserId?.let { uploadQueue[it]?.size ?: 0 } ?: 0
        _cacheStats.value = CacheStats(totalPoints, userCount, unsavedCount)
    }
}
