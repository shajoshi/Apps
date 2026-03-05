package com.example.smsmanager

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.widget.Toast

class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Telephony.Sms.Intents.SMS_RECEIVED_ACTION -> {
                val smsMessages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
                for (message in smsMessages) {
                    val sender = message.displayOriginatingAddress ?: "Unknown"
                    val messageBody = message.messageBody ?: ""
                    showToast(context, "SMS from $sender: $messageBody")
                }
            }
            Telephony.Sms.Intents.SMS_DELIVER_ACTION -> {
                showToast(context, "SMS delivered to device")
            }
        }
    }

    private fun showToast(context: Context, message: String) {
        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
    }
}