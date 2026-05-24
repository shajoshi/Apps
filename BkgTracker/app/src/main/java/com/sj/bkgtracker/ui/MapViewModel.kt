package com.sj.bkgtracker.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
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
    val timeWindowHours: Int = 24,
    val isLoading: Boolean = false,
    val error: String? = null
)

class MapViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "MapViewModel"
        val TIME_OPTIONS = listOf(1, 6, 24, 72)
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
            _state.update { it.copy(isLoading = true, error = null) }
            try {
                val since = System.currentTimeMillis() - _state.value.timeWindowHours * 3600_000L

                val userDocs = db.collection("locations").get().await()
                val users = mutableListOf<TrackedUser>()

                for (userDoc in userDocs.documents) {
                    val uid = userDoc.id
                    try {
                        val snap = db.collection("locations")
                            .document(uid)
                            .collection("records")
                            .limit(2000)
                            .get()
                            .await()

                        val points = snap.documents.mapNotNull { d ->
                            val ts = d.getLong("timestamp") ?: return@mapNotNull null
                            if (ts < since) return@mapNotNull null
                            LocationRecord(
                                latitude    = d.getDouble("lat")      ?: 0.0,
                                longitude   = d.getDouble("lon")      ?: 0.0,
                                timestampMs = ts,
                                accuracyM   = (d.getDouble("accuracy") ?: 0.0).toFloat(),
                                speedKmh    = (d.getDouble("speed")    ?: 0.0).toFloat(),
                                altitudeM   = d.getDouble("altitude")  ?: 0.0,
                                bearingDeg  = d.getDouble("bearing")?.toFloat()
                            )
                        }.sortedBy { it.timestampMs }

                        if (points.isNotEmpty()) {
                            val email = snap.documents.firstOrNull()?.getString("email") ?: uid
                            users.add(TrackedUser(uid, email, points))
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

                _state.update { it.copy(users = users, visibleUsers = newVisible, isLoading = false) }
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
}
