package com.example.smsmanager

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast

class MmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            "android.provider.Telephony.WAP_PUSH_DELIVER" -> {
                val mimeType = intent.type
                if (mimeType == "application/vnd.wap.mms-message") {
                    showToast(context, "MMS received")
                }
            }
        }
    }

    private fun showToast(context: Context, message: String) {
        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
    }
}