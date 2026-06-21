package com.sj.bkgtracker.ui

import android.app.Application
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.sj.bkgtracker.data.local.UnifiedLocationCache
import com.sj.bkgtracker.data.local.UsageTracker
import com.sj.bkgtracker.domain.model.LocationRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

data class TrackedUser(
    val uid: String,
    val email: String,
    val points: List<LocationRecord>,
    val firebasePoints: Int = 0,
    val cachePoints: Int = 0
)

data class MapState(
    val users: List<TrackedUser> = emptyList(),
    val visibleUsers: Set<String> = emptySet(),
    val timeWindowHours: Int = 1,
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
                var firebasePointsCount = 0
                val users = mutableListOf<TrackedUser>()

                
                // Get all users from Firebase
                val userDocs = db.collection("locations").get().await()
                UsageTracker.recordReads(getApplication(), userDocs.size())

                for (userDoc in userDocs.documents) {
                    val uid = userDoc.id
                    try {
                        var points: List<LocationRecord>
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

                        // Per-user source tracking
                        var userFirebasePoints = 0
                        var userCachePoints = 0

                        // Check if cache covers the requested time window
                        if (UnifiedLocationCache.cacheCoversTimeWindow(uid, since)) {
                            // Cache covers the start of window - use cached data + incremental fetch for gap
                            val (cachedPoints, fetchedWindow) = UnifiedLocationCache.getCachedPoints(uid, since)
                            points = cachedPoints
                            userCachePoints = points.size
                            
                            // Fetch any new points since the last fetch to fill the gap
                            if (fetchedWindow != null) {
                                val queryLimit = getQueryLimit(_state.value.timeWindowHours)
                                val lastLatest = fetchedWindow.second
                                val newPointsQuery = db.collection("locations")
                                    .document(uid)
                                    .collection("records")
                                    .whereGreaterThan("timestamp", lastLatest)
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
                                        userFirebasePoints = newPoints.size
                                        userCachePoints = cachedPoints.size
                                        // Merge and update - pass 'since' to track the window
                                        points = (cachedPoints + newPoints).sortedBy { it.timestampMs }
                                        UnifiedLocationCache.addPoints(uid, newPoints, since)
                                        Log.d(TAG, "Fetched ${newPoints.size} new points for $uid since $lastLatest")
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
                            userFirebasePoints = points.size

                            // Update cache with fetched data - pass 'since' to track the window
                            UnifiedLocationCache.addPoints(uid, newPoints, since)
                            firebasePointsCount += points.size
                            Log.d(TAG, "Fetched from Firebase for $uid: ${points.size} points")
                        }

                        // Filter by time window — always add user even if no points in window
                        val filteredPoints = points.filter { it.timestampMs >= since }
                        users.add(TrackedUser(uid, userEmail ?: uid, filteredPoints, userFirebasePoints, userCachePoints))
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
                val cachePointsCount = users.sumOf { it.cachePoints }
                Log.d(TAG, "Refresh complete: $totalFiltered points displayed ($firebasePointsCount from Firebase, $cachePointsCount from cache)")
                
                _state.update { 
                    it.copy(
                        users = users, 
                        visibleUsers = newVisible, 
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

    fun exportRawCsv() {
        val ctx = getApplication<Application>()
        val usersToExport = _state.value.users.filter {
            it.email in _state.value.visibleUsers && it.points.isNotEmpty()
        }
        if (usersToExport.isEmpty()) {
            Toast.makeText(ctx, "No points to export", Toast.LENGTH_SHORT).show()
            return
        }
        viewModelScope.launch {
            try {
                val uris = withContext(Dispatchers.IO) {
                    val exportDir = File(ctx.filesDir, "exports").also { it.mkdirs() }
                    val fileFmt = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US)
                    val isoFmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).also {
                        it.timeZone = TimeZone.getTimeZone("UTC")
                    }
                    val stamp = fileFmt.format(Date())

                    usersToExport.map { user ->
                        val name = user.email.substringBefore("@")
                        val file = File(exportDir, "${name}_raw_$stamp.csv")
                        file.bufferedWriter().use { w ->
                            w.write("timestamp_utc,lat,lon,accuracy_m,speed_kmh,altitude_m,bearing_deg")
                            w.newLine()
                            for (pt in user.points.sortedBy { it.timestampMs }) {
                                w.write("${isoFmt.format(Date(pt.timestampMs))},${pt.latitude},${pt.longitude},${pt.accuracyM},${pt.speedKmh},${pt.altitudeM},${pt.bearingDeg ?: ""}")
                                w.newLine()
                            }
                        }
                        FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
                    }
                }
                val shareIntent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                    type = "text/csv"
                    putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                ctx.startActivity(Intent.createChooser(shareIntent, "Share Raw CSV").also {
                    it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
            } catch (e: Exception) {
                Log.e(TAG, "CSV export failed", e)
                Toast.makeText(ctx, "Export failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    fun exportGpx() {
        val ctx = getApplication<Application>()
        val usersToExport = _state.value.users.filter {
            it.email in _state.value.visibleUsers && it.points.isNotEmpty()
        }
        if (usersToExport.isEmpty()) {
            Toast.makeText(ctx, "No points to export", Toast.LENGTH_SHORT).show()
            return
        }
        viewModelScope.launch {
            try {
                val uris = withContext(Dispatchers.IO) {
                    val exportDir = File(ctx.filesDir, "exports").also { it.mkdirs() }
                    val fileFmt = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US)
                    val stamp = fileFmt.format(Date())

                    usersToExport.map { user ->
                        val name = user.email.substringBefore("@")
                        val file = File(exportDir, "${name}_$stamp.kml")
                        file.bufferedWriter().use { w ->
                            val sorted = user.points.sortedBy { it.timestampMs }
                            val sdf = SimpleDateFormat("dd-MMM HH:mm", Locale.getDefault())
                            w.write("""<?xml version="1.0" encoding="UTF-8"?>"""); w.newLine()
                            w.write("""<kml xmlns="http://www.opengis.net/kml/2.2">"""); w.newLine()
                            w.write("<Document>"); w.newLine()
                            w.write("  <name>${user.email}</name>"); w.newLine()
                            w.write("  <Style id=\"track\">"); w.newLine()
                            w.write("    <LineStyle><color>ffff0000</color><width>4</width></LineStyle>"); w.newLine()
                            w.write("    <PolyStyle><fill>0</fill></PolyStyle>"); w.newLine()
                            w.write("  </Style>"); w.newLine()
                            // Track as LineString
                            w.write("  <Placemark>"); w.newLine()
                            w.write("    <name>${user.email.substringBefore("@")} track</name>"); w.newLine()
                            w.write("    <styleUrl>#track</styleUrl>"); w.newLine()
                            w.write("    <LineString>"); w.newLine()
                            w.write("      <tessellate>1</tessellate>"); w.newLine()
                            w.write("      <coordinates>"); w.newLine()
                            for (pt in sorted) {
                                w.write("        ${pt.longitude},${pt.latitude},${pt.altitudeM}"); w.newLine()
                            }
                            w.write("      </coordinates>"); w.newLine()
                            w.write("    </LineString>"); w.newLine()
                            w.write("  </Placemark>"); w.newLine()
                            // Start marker
                            sorted.firstOrNull()?.let { first ->
                                w.write("  <Placemark>"); w.newLine()
                                w.write("    <name>Start ${sdf.format(Date(first.timestampMs))}</name>"); w.newLine()
                                w.write("    <description>Speed: ${"%.1f".format(first.speedKmh)} km/h</description>"); w.newLine()
                                w.write("    <Point><coordinates>${first.longitude},${first.latitude},${first.altitudeM}</coordinates></Point>"); w.newLine()
                                w.write("  </Placemark>"); w.newLine()
                            }
                            // End marker
                            sorted.lastOrNull()?.let { last ->
                                w.write("  <Placemark>"); w.newLine()
                                w.write("    <name>End ${sdf.format(Date(last.timestampMs))}</name>"); w.newLine()
                                w.write("    <description>Speed: ${"%.1f".format(last.speedKmh)} km/h</description>"); w.newLine()
                                w.write("    <Point><coordinates>${last.longitude},${last.latitude},${last.altitudeM}</coordinates></Point>"); w.newLine()
                                w.write("  </Placemark>"); w.newLine()
                            }
                            w.write("</Document>"); w.newLine()
                            w.write("</kml>"); w.newLine()
                        }
                        FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
                    }
                }
                val shareIntent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                    type = "application/vnd.google-earth.kml+xml"
                    putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                ctx.startActivity(Intent.createChooser(shareIntent, "Share KML").also {
                    it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
            } catch (e: Exception) {
                Log.e(TAG, "KML export failed", e)
                Toast.makeText(ctx, "Export failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }
}
