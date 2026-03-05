package com.example.smsmanager

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.widget.Toast

class SmsService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "android.intent.action.RESPOND_VIA_MESSAGE" -> {
                showToast("Responding to message")
            }
        }
        return START_STICKY
    }

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }
}