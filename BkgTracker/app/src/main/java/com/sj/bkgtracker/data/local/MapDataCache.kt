package com.sj.bkgtracker.data.local

import com.sj.bkgtracker.domain.model.LocationRecord
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * In-memory cache for Firebase map location data.
 * Caches points for the last 48 hours per user to reduce Firestore queries.
 */
object MapDataCache {
    
    /** Cache retention period - 48 hours in milliseconds */
    private const val CACHE_DURATION_MS = 48 * 60 * 60 * 1000L
    
    // Cache data: userId -> list of location records
    private val cachedUsers = mutableMapOf<String, MutableList<LocationRecord>>()
    
    // Cache user emails: userId -> email
    private val userEmails = mutableMapOf<String, String>()
    
    // Track when we last successfully fetched data for each user
    private val lastFetchTimes = mutableMapOf<String, Long>()
    
    // State flows for UI observation
    private val _cacheStats = MutableStateFlow(CacheStats(0, 0))
    val cacheStats: StateFlow<CacheStats> 
        get() {
            // Clean up expired entries before returning stats
            cleanupExpiredEntries()
            return _cacheStats.asStateFlow()
        }
    
    data class CacheStats(
        val totalCachedPoints: Int,
        val cachedUserCount: Int
    )
    
    /**
     * Get cached points for a user within the specified time window.
     * Returns Pair<List<LocationRecord>, lastFetchTime>
     */
    fun getCachedPoints(uid: String, since: Long): Pair<List<LocationRecord>, Long> {
        val points = cachedUsers[uid]?.filter { it.timestampMs >= since } ?: emptyList()
        val lastFetch = lastFetchTimes[uid] ?: 0L
        return Pair(points, lastFetch)
    }
    
    /**
     * Add or update cached points for a user.
     * Merges with existing cache and updates fetch timestamp.
     */
    fun addPoints(uid: String, points: List<LocationRecord>) {
        val existing = cachedUsers.getOrPut(uid) { mutableListOf() }
        
        // Merge new points with existing, avoiding duplicates
        val existingTimestamps = existing.map { it.timestampMs }.toSet()
        val newPoints = points.filter { it.timestampMs !in existingTimestamps }
        
        existing.addAll(newPoints)
        
        // Sort by timestamp and keep only recent points (last 48 hours)
        val cutoff = System.currentTimeMillis() - CACHE_DURATION_MS
        val filtered = existing.filter { it.timestampMs >= cutoff }.sortedBy { it.timestampMs }
        
        cachedUsers[uid] = filtered.toMutableList()
        lastFetchTimes[uid] = System.currentTimeMillis()
        
        updateCacheStats()
    }
    
    /**
     * Get the last successful fetch time for a user.
     */
    fun getLastFetchTime(uid: String): Long? = lastFetchTimes[uid]
    
    /**
     * Get cached email for a user.
     */
    fun getCachedEmail(uid: String): String? = userEmails[uid]
    
    /**
     * Store email for a user.
     */
    fun setEmail(uid: String, email: String) {
        userEmails[uid] = email
    }
    
    /**
     * Check if cache has valid data for a user (data exists and is within 48 hours).
     */
    fun hasValidCache(uid: String): Boolean {
        val lastFetch = lastFetchTimes[uid] ?: return false
        val cutoff = System.currentTimeMillis() - CACHE_DURATION_MS
        return lastFetch > cutoff && cachedUsers[uid]?.isNotEmpty() == true
    }
    
    /**
     * Clean up expired entries older than 48 hours.
     * Call this periodically to manage memory.
     */
    fun cleanupExpiredEntries() {
        val cutoff = System.currentTimeMillis() - CACHE_DURATION_MS
        val iterator = cachedUsers.entries.iterator()
        
        while (iterator.hasNext()) {
            val (uid, points) = iterator.next()
            val filtered = points.filter { it.timestampMs >= cutoff }
            
            if (filtered.isEmpty()) {
                iterator.remove()
                lastFetchTimes.remove(uid)
                userEmails.remove(uid)
            } else {
                cachedUsers[uid] = filtered.toMutableList()
            }
        }
        
        updateCacheStats()
    }
    
    /**
     * Clear all cached data (useful for testing/debugging).
     */
    fun clearCache() {
        cachedUsers.clear()
        lastFetchTimes.clear()
        userEmails.clear()
        updateCacheStats()
    }
    
    /**
     * Get cache statistics for debugging.
     */
    fun getCacheInfo(): String {
        // Clean up expired entries before calculating stats
        cleanupExpiredEntries()
        
        val totalPoints = cachedUsers.values.sumOf { it.size }
        val userCount = cachedUsers.size
        val oldestPoint = cachedUsers.values.flatten().minOfOrNull { it.timestampMs }
        val newestPoint = cachedUsers.values.flatten().maxOfOrNull { it.timestampMs }
        
        return "Cache: $userCount users, $totalPoints points" +
                (if (oldestPoint != null) " (${(System.currentTimeMillis() - oldestPoint) / 3600000}h ago to ${(System.currentTimeMillis() - newestPoint!!) / 3600000}h ago)" else "")
    }
    
    private fun updateCacheStats() {
        val totalPoints = cachedUsers.values.sumOf { it.size }
        val userCount = cachedUsers.size
        _cacheStats.value = CacheStats(totalPoints, userCount)
    }
}
