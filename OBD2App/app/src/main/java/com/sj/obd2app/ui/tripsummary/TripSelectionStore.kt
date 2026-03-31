package com.sj.obd2app.ui.tripsummary

import android.net.Uri
import org.json.JSONObject

/**
 * Shared selection for the currently viewed track file and its parsed samples.
 * Keeps the Map View decoupled from Trip Summary lifecycle.
 */
object TripSelectionStore {
    data class SelectedTrack(
        val fileName: String,
        val uri: Uri,
        val lastSample: JSONObject,
        val samples: List<JSONObject>
    )

    @Volatile
    var selectedTrack: SelectedTrack? = null
        private set

    fun setSelectedTrack(track: SelectedTrack) {
        selectedTrack = track
    }

    fun clearSelectedTrack() {
        selectedTrack = null
    }

    fun hasSelection(): Boolean = selectedTrack != null
}
