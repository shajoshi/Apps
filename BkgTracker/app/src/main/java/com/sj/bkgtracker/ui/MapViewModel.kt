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
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.sj.bkgtracker.data.local.UnifiedLocationCache
import com.sj.bkgtracker.data.local.UsageTracker
import com.sj.bkgtracker.domain.model.LocationRecord
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

private fun startOfDayLocalMs(year: Int, month: Int, day: Int): Long {
    val cal = Calendar.getInstance()
    cal.set(year, month, day, 0, 0, 0)
    cal.set(Calendar.MILLISECOND, 0)
    return cal.timeInMillis
}

private fun startOfTodayLocalMs(): Long {
    val cal = Calendar.getInstance()
    cal.set(Calendar.HOUR_OF_DAY, 0)
    cal.set(Calendar.MINUTE, 0)
    cal.set(Calendar.SECOND, 0)
    cal.set(Calendar.MILLISECOND, 0)
    return cal.timeInMillis
}

data class TrackedUser(
    val uid: String,
    val email: String,
    val points: List<LocationRecord>
)

data class MapState(
    val users: List<TrackedUser> = emptyList(),
    val visibleUsers: Set<String> = emptySet(),
    val fromDateMs: Long = System.currentTimeMillis(),
    val userDatePinned: Boolean = false,
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
        val TIME_OPTIONS = listOf(1, 6, 24)
    }

    private val db = FirebaseFirestore.getInstance()

    private val _state = MutableStateFlow(MapState())
    val state: StateFlow<MapState> = _state.asStateFlow()

    // Global in-memory read cache (web-dashboard-style)
    private val cachedPoints = mutableMapOf<String, MutableList<LocationRecord>>()
    private val cachedDocIds = mutableMapOf<String, MutableSet<String>>()
    private var lastFetchMs: Long = 0
    private var oldestCachedMs: Long = 0
    private var lastFetchFromDateMs: Long = 0
    private var lastFetchTimeWindowHours: Int = 0

    init {
        refresh()
    }

    private fun elapsed(startMs: Long) = System.currentTimeMillis() - startMs

    fun refresh() {
        if (!_state.value.userDatePinned) {
            _state.update { it.copy(fromDateMs = System.currentTimeMillis()) }
        }
        doRefresh()
    }

    private fun doRefresh() {
        viewModelScope.launch {
            val ctx = getApplication<Application>()
            if (!isNetworkAvailable(ctx)) {
                Toast.makeText(ctx, "No network — cannot load map data", Toast.LENGTH_SHORT).show()
                _state.update { it.copy(isLoading = false, error = "No network") }
                return@launch
            }
            _state.update { it.copy(isLoading = true, error = null) }
            try {
                val refreshStart = System.currentTimeMillis()
                val until = _state.value.fromDateMs
                val since = until - _state.value.timeWindowHours * 3600_000L
                val fetchStartMs = System.currentTimeMillis()
                val paramsChanged = _state.value.fromDateMs != lastFetchFromDateMs
                        || _state.value.timeWindowHours != lastFetchTimeWindowHours
                val isFullFetch = oldestCachedMs == 0L || since < oldestCachedMs || paramsChanged
                Log.d(TAG, "Refresh start: from=${_state.value.fromDateMs}, window=${_state.value.timeWindowHours}h, until=$until, isFullFetch=$isFullFetch, lastFetchMs=$lastFetchMs, oldestCachedMs=$oldestCachedMs")

                // Get all users from Firebase
                val userDocs = db.collection("locations").get().await()
                UsageTracker.recordReads(getApplication(), userDocs.size())
                Log.d(TAG, "User list fetched in ${elapsed(refreshStart)}ms (${userDocs.size()} users)")

                // If window changed/widened, discard read cache and fetch the full window fresh
                if (isFullFetch) {
                    cachedPoints.clear()
                    cachedDocIds.clear()
                }

                val fetchSince = if (isFullFetch) since else lastFetchMs

                // Resolve emails for all discovered users (cheap, sequential is fine)
                val emailByUid = mutableMapOf<String, String>()
                userDocs.documents.forEach { userDoc ->
                    val uid = userDoc.id
                    var userEmail = UnifiedLocationCache.getCachedEmail(uid)
                    if (userEmail == null) {
                        try {
                            val userDocSingle = db.collection("locations").document(uid).get().await()
                            UsageTracker.recordReads(getApplication(), 1)
                            userEmail = userDocSingle.getString("email") ?: uid
                            if (userEmail != uid) {
                                UnifiedLocationCache.setEmail(uid, userEmail)
                            }
                        } catch (e: Exception) {
                            userEmail = uid
                        }
                    }
                    emailByUid[uid] = userEmail ?: uid
                }
                Log.d(TAG, "Emails resolved in ${elapsed(refreshStart)}ms")

                // Fetch records only for the currently selected/visible users
                val visibleEmails = _state.value.visibleUsers
                val uidsToFetch = userDocs.documents
                    .filter { doc ->
                        val email = emailByUid[doc.id] ?: doc.id
                        visibleEmails.isEmpty() || email in visibleEmails
                    }
                Log.d(TAG, "Fetching records for ${uidsToFetch.size}/${userDocs.size()} selected users")

                // Fetch each user's records in parallel, matching the web dashboard query shape
                val queryStart = System.currentTimeMillis()
                var totalAdded = 0
                val fetchJobs = uidsToFetch.map { userDoc ->
                    async(Dispatchers.IO) {
                        val uid = userDoc.id
                        val userQueryStart = System.currentTimeMillis()
                        try {
                            val query = db.collection("locations")
                                .document(uid)
                                .collection("records")
                                .whereGreaterThanOrEqualTo("timestamp", fetchSince)
                                .orderBy("timestamp", Query.Direction.DESCENDING)

                            val snap = query.get().await()
                            Log.d(TAG, "User $uid query returned ${snap.size()} docs in ${elapsed(userQueryStart)}ms (fetchSince=$fetchSince)")
                            UsageTracker.recordReads(getApplication(), snap.size())

                            val processStart = System.currentTimeMillis()
                            var userAdded = 0
                            val userList = cachedPoints.getOrPut(uid) { mutableListOf() }
                            val userIdSet = cachedDocIds.getOrPut(uid) { mutableSetOf() }
                            snap.documents.forEach { d ->
                                val docId = d.id
                                if (!isFullFetch && docId in userIdSet) return@forEach
                                val ts = d.getLong("timestamp") ?: return@forEach
                                val record = LocationRecord(
                                    latitude    = d.getDouble("lat")      ?: 0.0,
                                    longitude   = d.getDouble("lon")      ?: 0.0,
                                    timestampMs = ts,
                                    accuracyM   = (d.getDouble("accuracy") ?: 0.0).toFloat(),
                                    speedKmh    = (d.getDouble("speed")    ?: 0.0).toFloat(),
                                    altitudeM   = d.getDouble("altitude")  ?: 0.0,
                                    bearingDeg  = d.getDouble("bearing")?.toFloat()
                                )
                                userList.add(record)
                                userIdSet.add(docId)
                                userAdded++
                            }
                            Log.d(TAG, "User $uid processed ${snap.size()} docs in ${elapsed(processStart)}ms")
                            userAdded
                        } catch (e: Exception) {
                            Log.w(TAG, "Could not fetch records for uid $uid after ${elapsed(userQueryStart)}ms: ${e.message}")
                            0
                        }
                    }
                }
                totalAdded = fetchJobs.awaitAll().sum()
                Log.d(TAG, "Parallel fetch done in ${elapsed(queryStart)}ms")

                if (isFullFetch) {
                    Log.d(TAG, "Fetched $totalAdded points")
                } else {
                    Log.d(TAG, "Added $totalAdded incremental points")
                }

                lastFetchMs = fetchStartMs
                lastFetchFromDateMs = _state.value.fromDateMs
                lastFetchTimeWindowHours = _state.value.timeWindowHours
                // Full fetch used timestamp >= since with no limit, so coverage reaches back to 'since'
                if (isFullFetch) oldestCachedMs = since

                // Build display list from cache filtered by current date range
                val users = mutableListOf<TrackedUser>()

                for (userDoc in userDocs.documents) {
                    val uid = userDoc.id
                    val email = emailByUid[uid] ?: uid
                    val userList = cachedPoints[uid]
                    val filtered = userList
                        ?.filter { it.timestampMs in since until until }
                        ?.sortedBy { it.timestampMs }
                        ?: emptyList()
                    users.add(TrackedUser(uid, email, filtered))
                }

                val currentVisible = _state.value.visibleUsers
                val newVisible = if (currentVisible.isEmpty()) {
                    users.map { it.email }.toSet()
                } else {
                    currentVisible
                }

                val totalFiltered = users.sumOf { it.points.size }
                val modeLabel = if (isFullFetch) "" else " — incremental"
                Log.d(TAG, "Display list built in ${elapsed(refreshStart)}ms")
                Log.d(TAG, "Refresh complete in ${elapsed(refreshStart)}ms: $totalFiltered points displayed$modeLabel")

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
        val wasVisible = _state.value.visibleUsers.contains(email)
        _state.update { s ->
            val visible = s.visibleUsers.toMutableSet()
            if (visible.contains(email)) visible.remove(email) else visible.add(email)
            s.copy(
                visibleUsers = visible,
                userDatePinned = false,
                fromDateMs = System.currentTimeMillis()
            )
        }
        if (!wasVisible) {
            // User was toggled on: they may not be in cache yet, so fetch their records
            doRefresh()
        }
    }

    fun setStartDate(fromDateMs: Long) {
        _state.update { it.copy(fromDateMs = fromDateMs, userDatePinned = true) }
        doRefresh()
    }

    fun setTimeWindowHours(hours: Int) {
        if (_state.value.userDatePinned) {
            _state.update { it.copy(timeWindowHours = hours) }
        } else {
            _state.update { it.copy(fromDateMs = System.currentTimeMillis(), timeWindowHours = hours) }
        }
        doRefresh()
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
