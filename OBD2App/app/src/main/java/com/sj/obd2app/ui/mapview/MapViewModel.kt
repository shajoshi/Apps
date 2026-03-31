package com.sj.obd2app.ui.mapview

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.sj.obd2app.ui.tripsummary.TripSelectionStore

class MapViewModel(application: Application) : AndroidViewModel(application) {
    val selectedTrack get() = TripSelectionStore.selectedTrack

    fun clearSelection() {
        TripSelectionStore.clearSelectedTrack()
    }
}
