package com.sj.bkgtracker.ui

import android.app.Application
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.sj.bkgtracker.data.local.UnifiedLocationCache
import com.sj.bkgtracker.data.local.UsageTracker
import com.sj.bkgtracker.domain.model.LocationRecord
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

data class TrackedUser(
    val uid: String,
    val email: String,
    val points: List<LocationRecord>
)

data class MapState(
    val users: List<TrackedUser> = emptyList(),
    val visibleUsers: Set<String> = emptySet(),
    val timeWindowHours: Int = 1,
    val totalPoints: Int = 0,
    val firebasePoints: Int = 0,
    val cachePoints: Int = 0,
    val isLoading: Boolean = false,
    val error: String? = null,
    val timelineEnabled: Boolean = false,
    val timelineIndex: Int = 0,
    val timelineTotal: Int = 0,
    val currentTimelinePoint: LocationRecord? = null
)

class MapViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "MapViewModel"
        val TIME_OPTIONS = listOf(1, 6, 24, 72)
        
        // Dynamic query limits based on time window
        private fun getQueryLimit(timeWindowHours: Int): Int {
            return when (timeWindowHours) {
                1 -> 240    // 1 hour = 4 points/min × 60 min = 240 points
                6 -> 1000   // 6 hours = reasonable buffer
                24 -> 1500  // 24 hours = reasonable buffer
                72 -> 2000  // 3 days = max limit
                else -> 2000
            }
        }
    }

    private val db   = FirebaseFirestore.getInstance()
    private val auth = FirebaseAuth.getInstance()

    private val _state = MutableStateFlow(MapState())
    val state: StateFlow<MapState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            val ctx = getApplication<Application>()
            if (!isNetworkAvailable(ctx)) {
                Toast.makeText(ctx, "No network — cannot load map data", Toast.LENGTH_SHORT).show()
                _state.update { it.copy(isLoading = false, error = "No network") }
                return@launch
            }
            _state.update { it.copy(isLoading = true, error = null) }
            try {
                val since = System.currentTimeMillis() - _state.value.timeWindowHours * 3600_000L
                val currentUserId = auth.currentUser?.uid
                var firebasePointsCount = 0
                var cachePointsCount = 0
                val users = mutableListOf<TrackedUser>()

                
                // Get all users from Firebase
                val userDocs = db.collection("locations").get().await()
                UsageTracker.recordReads(getApplication(), userDocs.size())

                for (userDoc in userDocs.documents) {
                    val uid = userDoc.id
                    try {
                        var points: List<LocationRecord>
                        var fromFirebase = false
                        var userEmail: String? = null

                        // Get user email first (from cache or Firebase)
                        userEmail = UnifiedLocationCache.getCachedEmail(uid)
                        if (userEmail == null) {
                            // Try to get email from user document
                            try {
                                val userDoc = db.collection("locations").document(uid).get().await()
                                UsageTracker.recordReads(getApplication(), 1)
                                userEmail = userDoc.getString("email") ?: uid
                                if (userEmail != uid) {
                                    UnifiedLocationCache.setEmail(uid, userEmail)
                                }
                            } catch (e: Exception) {
                                userEmail = uid
                            }
                        }

                        // Check if cache covers the requested time window
                        if (UnifiedLocationCache.cacheCoversTimeWindow(uid, since)) {
                            // Cache covers the full window - use cached data and optionally fetch newer points
                            val (cachedPoints, lastFirebaseFetch) = UnifiedLocationCache.getCachedPoints(uid, since)
                            points = cachedPoints
                            cachePointsCount += points.size
                            
                            // For current user with cache, also fetch any new points from Firebase since last fetch
                            if (uid == currentUserId && lastFirebaseFetch > 0) {
                                val queryLimit = getQueryLimit(_state.value.timeWindowHours)
                                val newPointsQuery = db.collection("locations")
                                    .document(uid)
                                    .collection("records")
                                    .whereGreaterThan("timestamp", lastFirebaseFetch)
                                    .orderBy("timestamp", Query.Direction.DESCENDING)
                                    .limit(queryLimit.toLong())
                                
                                try {
                                    val newSnap = newPointsQuery.get().await()
                                    UsageTracker.recordReads(getApplication(), newSnap.size())
                                    if (newSnap.size() > 0) {
                                        val newPoints = newSnap.documents.mapNotNull { d ->
                                            val ts = d.getLong("timestamp") ?: return@mapNotNull null
                                            LocationRecord(
                                                latitude    = d.getDouble("lat")      ?: 0.0,
                                                longitude   = d.getDouble("lon")      ?: 0.0,
                                                timestampMs = ts,
                                                accuracyM   = (d.getDouble("accuracy") ?: 0.0).toFloat(),
                                                speedKmh    = (d.getDouble("speed")    ?: 0.0).toFloat(),
                                                altitudeM   = d.getDouble("altitude")  ?: 0.0,
                                                bearingDeg  = d.getDouble("bearing")?.toFloat()
                                            )
                                        }
                                        // Merge and update
                                        points = (cachedPoints + newPoints).sortedBy { it.timestampMs }
                                        UnifiedLocationCache.addPoints(uid, newPoints)
                                        Log.d(TAG, "Fetched ${newPoints.size} new points for current user $uid since $lastFirebaseFetch")
                                    }
                                } catch (e: Exception) {
                                    Log.w(TAG, "Could not fetch new points for $uid: ${e.message}")
                                }
                            }
                            
                            Log.d(TAG, "Using cached data for $uid: ${points.size} points (covers full window)")
                        } else {
                            // Cache doesn't cover window - fetch full time window from Firebase
                            val queryLimit = getQueryLimit(_state.value.timeWindowHours)
                            val query = db.collection("locations")
                                .document(uid)
                                .collection("records")
                                .whereGreaterThanOrEqualTo("timestamp", since)
                                .orderBy("timestamp", Query.Direction.DESCENDING)
                                .limit(queryLimit.toLong())

                            val snap = query.get().await()
                            UsageTracker.recordReads(getApplication(), snap.size())

                            val newPoints = snap.documents.mapNotNull { d ->
                                val ts = d.getLong("timestamp") ?: return@mapNotNull null
                                LocationRecord(
                                    latitude    = d.getDouble("lat")      ?: 0.0,
                                    longitude   = d.getDouble("lon")      ?: 0.0,
                                    timestampMs = ts,
                                    accuracyM   = (d.getDouble("accuracy") ?: 0.0).toFloat(),
                                    speedKmh    = (d.getDouble("speed")    ?: 0.0).toFloat(),
                                    altitudeM   = d.getDouble("altitude")  ?: 0.0,
                                    bearingDeg  = d.getDouble("bearing")?.toFloat()
                                )
                            }

                            points = newPoints.sortedBy { it.timestampMs }

                            // Update cache with fetched data
                            UnifiedLocationCache.addPoints(uid, newPoints)
                            fromFirebase = true
                            firebasePointsCount += points.size
                            Log.d(TAG, "Fetched from Firebase for $uid: ${points.size} points")
                        }

                        // Filter by time window
                        val filteredPoints = points.filter { it.timestampMs >= since }

                        if (filteredPoints.isNotEmpty()) {
                            users.add(TrackedUser(uid, userEmail ?: uid, filteredPoints))
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Could not fetch records for uid $uid: ${e.message}")
                    }
                }

                val currentVisible = _state.value.visibleUsers
                val newVisible = if (currentVisible.isEmpty()) {
                    users.map { it.email }.toSet()
                } else {
                    currentVisible
                }

                val totalFiltered = users.sumOf { it.points.size }
                Log.d(TAG, "Refresh complete: $totalFiltered points displayed ($firebasePointsCount from Firebase, $cachePointsCount from cache)")
                
                _state.update { 
                    it.copy(
                        users = users, 
                        visibleUsers = newVisible, 
                        totalPoints = totalFiltered,
                        firebasePoints = firebasePointsCount,
                        cachePoints = cachePointsCount,
                        isLoading = false
                    ) 
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load locations", e)
                _state.update { it.copy(isLoading = false, error = e.message) }
            }
        }
    }

    fun toggleUser(email: String) {
        _state.update { s ->
            val visible = s.visibleUsers.toMutableSet()
            if (visible.contains(email)) visible.remove(email) else visible.add(email)
            s.copy(visibleUsers = visible)
        }
    }

    fun setTimeWindowHours(hours: Int) {
        _state.update { it.copy(timeWindowHours = hours) }
        refresh()
    }

    private fun isNetworkAvailable(context: Application): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    fun enableTimeline() {
        val allPoints = getAllTimelinePoints()
        _state.update { 
            it.copy(
                timelineEnabled = true,
                timelineTotal = allPoints.size,
                timelineIndex = allPoints.size - 1, // Start at the most recent point
                currentTimelinePoint = allPoints.lastOrNull()
            )
        }
    }

    fun disableTimeline() {
        _state.update { 
            it.copy(
                timelineEnabled = false,
                timelineIndex = 0,
                timelineTotal = 0,
                currentTimelinePoint = null
            )
        }
    }

    fun setTimelineIndex(index: Int) {
        val allPoints = getAllTimelinePoints()
        if (index in 0 until allPoints.size) {
            _state.update { 
                it.copy(
                    timelineIndex = index,
                    currentTimelinePoint = allPoints[index]
                )
            }
        }
    }

    fun goToStart() {
        setTimelineIndex(0)
    }

    fun goToEnd() {
        val total = _state.value.timelineTotal
        if (total > 0) {
            setTimelineIndex(total - 1)
        }
    }

    fun goToPrevious() {
        val currentIndex = _state.value.timelineIndex
        if (currentIndex > 0) {
            setTimelineIndex(currentIndex - 1)
        }
    }

    fun goToNext() {
        val currentIndex = _state.value.timelineIndex
        val total = _state.value.timelineTotal
        if (currentIndex < total - 1) {
            setTimelineIndex(currentIndex + 1)
        }
    }

    private fun getAllTimelinePoints(): List<LocationRecord> {
        return _state.value.users
            .filter { it.email in _state.value.visibleUsers }
            .flatMap { it.points }
            .sortedBy { it.timestampMs }
    }
}
