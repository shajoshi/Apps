package com.sj.bkgtracker.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.android.gms.location.ActivityTransitionResult
import com.sj.bkgtracker.service.LocationForegroundService

class ActivityTransitionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (!ActivityTransitionResult.hasResult(intent)) return

        val result = ActivityTransitionResult.extractResult(intent) ?: return
        result.transitionEvents.forEach { event ->
            Log.d(TAG, "Activity transition received: activity=${event.activityType}, transition=${event.transitionType}")
        }

        LocationForegroundService.startForActivityWake(context)
    }

    companion object {
        private const val TAG = "ActivityTransitionRx"
    }
}
