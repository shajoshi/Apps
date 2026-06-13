package com.sj.bkgtracker.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionResult
import com.sj.bkgtracker.service.LocationForegroundService

class ActivityTransitionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (!ActivityTransitionResult.hasResult(intent)) return

        val result = ActivityTransitionResult.extractResult(intent) ?: return
        for (event in result.transitionEvents) {
            Log.d(TAG, "Activity transition: activity=${event.activityType}, transition=${event.transitionType}")
            if (event.transitionType == ActivityTransition.ACTIVITY_TRANSITION_ENTER) {
                LocationForegroundService.startForActivityWake(context, event.activityType)
            } else if (event.transitionType == ActivityTransition.ACTIVITY_TRANSITION_EXIT) {
                LocationForegroundService.notifyActivityEnded(context, event.activityType)
            }
        }
    }

    companion object {
        private const val TAG = "ActivityTransitionRx"
    }
}
