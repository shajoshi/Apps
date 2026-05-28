package com.sj.bkgtracker.service

import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.sj.bkgtracker.data.local.ExpressSyncManager

class BkgTrackerMessagingService : FirebaseMessagingService() {

    companion object {
        private const val TAG = "BkgTrackerFCM"
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val data = message.data
        Log.d(TAG, "FCM received: $data")

        when (data["type"]) {
            "express_sync" -> {
                val expiresAt = data["expiresAt"]?.toLongOrNull() ?: return
                val requestedBy = data["requestedBy"]
                Log.d(TAG, "Express sync activated: expiresAt=$expiresAt, requestedBy=$requestedBy")
                ExpressSyncManager.activate(applicationContext, expiresAt, requestedBy)
            }
            "express_sync_stop" -> {
                val stoppedBy = data["stoppedBy"]
                Log.d(TAG, "Express sync stopped by: $stoppedBy")
                ExpressSyncManager.stopByUser(applicationContext, stoppedBy)
            }
        }
    }

    override fun onNewToken(token: String) {
        Log.d(TAG, "New FCM token: $token")
    }
}
