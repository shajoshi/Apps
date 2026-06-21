package com.sj.bkgtracker.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionResult
import com.sj.bkgtracker.data.local.ActivityLogCache
import com.sj.bkgtracker.data.local.ActivityStateHolder
import com.sj.bkgtracker.service.LocationForegroundService

class ActivityTransitionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        Log.d(TAG, "onReceive() called. action=${intent.action}, hasResult=${ActivityTransitionResult.hasResult(intent)}")
        if (!ActivityTransitionResult.hasResult(intent)) {
            Log.w(TAG, "Intent does NOT have ActivityTransitionResult - ignoring")
            return
        }

        val result = ActivityTransitionResult.extractResult(intent) ?: return
        Log.d(TAG, "Got ${result.transitionEvents.size} transition events")
        for (event in result.transitionEvents) {
            val isStart = event.transitionType == ActivityTransition.ACTIVITY_TRANSITION_ENTER
            val activityName = ActivityStateHolder.activityName(event.activityType)
            val label = if (isStart) "started" else "ended"
            Log.d(TAG, "Activity transition: $activityName $label")

            // Log to persistent NDJSON directly here — ensures logging even if service is slow to start
            ActivityLogCache.logActivity(context, activityName, isStart)

            // Show Toast on main thread for debugging
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(context, "Activity: $activityName $label", Toast.LENGTH_SHORT).show()
            }

            if (isStart) {
                LocationForegroundService.startForActivityWake(context, event.activityType)
            } else {
                LocationForegroundService.notifyActivityEnded(context, event.activityType)
            }
        }
    }

    companion object {
        private const val TAG = "ActivityTransitionRx"
    }
}
