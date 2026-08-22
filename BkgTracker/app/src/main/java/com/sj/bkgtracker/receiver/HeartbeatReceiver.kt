package com.sj.bkgtracker.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.util.Log
import com.sj.bkgtracker.service.LocationForegroundService

class HeartbeatReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "HeartbeatReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        Log.d(TAG, "Heartbeat alarm fired")

        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "BkgTracker:HeartbeatWakeLock"
        )
        wakeLock.setReferenceCounted(false)
        wakeLock.acquire(120_000L)

        val serviceIntent = Intent(context, LocationForegroundService::class.java).apply {
            action = LocationForegroundService.ACTION_HEARTBEAT
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        } finally {
            // Release after a short delay so the service has time to acquire its own lock
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                if (wakeLock.isHeld) wakeLock.release()
            }, 5_000L)
        }
    }
}
