package com.sj.bkgtracker.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.sj.bkgtracker.service.LocationForegroundService

class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.d(TAG, "Boot completed — checking auth state")
            if (FirebaseAuth.getInstance().currentUser != null) {
                Log.d(TAG, "User signed in — starting location service")
                LocationForegroundService.start(context)
            } else {
                Log.d(TAG, "No signed-in user — skipping service start")
            }
        }
    }
}
