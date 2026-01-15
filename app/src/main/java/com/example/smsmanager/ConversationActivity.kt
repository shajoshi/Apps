package com.example.smsmanager

import android.os.Bundle
import android.provider.Telephony
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.*

class ConversationActivity : AppCompatActivity() {
    private lateinit var conversationRecyclerView: RecyclerView
    private lateinit var adapter: ConversationAdapter
    private val messages = mutableListOf<SmsMessage>()
    private var senderAddress: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_conversation)

        // Initialize views
        conversationRecyclerView = findViewById(R.id.conversationRecyclerView)

        // Get the sender's address from the intent
        senderAddress = intent.getStringExtra("SENDER_ADDRESS") ?: run {
            finish()
            return
        }

        // Set up the action bar
        supportActionBar?.apply {
            title = senderAddress
            setDisplayHomeAsUpEnabled(true)
        }

        // Set up RecyclerView
        adapter = ConversationAdapter(messages)
        conversationRecyclerView.apply {
            layoutManager = LinearLayoutManager(this@ConversationActivity)
            adapter = this@ConversationActivity.adapter
        }

        // Load messages
        loadMessages()
        
        // Handle back button press
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                finish()
            }
        })
    }

    private fun loadMessages() {
        try {
            messages.clear()
            val cursor = contentResolver.query(
                Telephony.Sms.CONTENT_URI,
                arrayOf(
                    Telephony.Sms._ID,
                    Telephony.Sms.ADDRESS,
                    Telephony.Sms.BODY,
                    Telephony.Sms.DATE,
                    Telephony.Sms.READ,
                    Telephony.Sms.TYPE
                ),
                "${Telephony.Sms.ADDRESS} = ?",
                arrayOf(senderAddress),
                "${Telephony.Sms.DATE} ASC"  // Show oldest first
            )

            cursor?.use {
                val idIndex = it.getColumnIndexOrThrow(Telephony.Sms._ID)
                val addressIndex = it.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
                val bodyIndex = it.getColumnIndexOrThrow(Telephony.Sms.BODY)
                val dateIndex = it.getColumnIndexOrThrow(Telephony.Sms.DATE)

                while (it.moveToNext()) {
                    val id = it.getString(idIndex)
                    val address = it.getString(addressIndex) ?: "Unknown"
                    val body = it.getString(bodyIndex) ?: ""
                    val date = it.getLong(dateIndex)
                    
                    messages.add(
                        SmsMessage(
                            id = id,
                            address = address,
                            body = body,
                            date = date,
                            isDefaultSmsApp = packageName == Telephony.Sms.getDefaultSmsPackage(this)
                        )
                    )
                }
            }
            
            adapter.notifyDataSetChanged()
            
            // Scroll to the bottom of the conversation
            if (messages.isNotEmpty()) {
                conversationRecyclerView.scrollToPosition(messages.size - 1)
            }
        } catch (e: Exception) {
            Log.e("ConversationActivity", "Error loading messages", e)
            Toast.makeText(this, "Error loading conversation", Toast.LENGTH_SHORT).show()
        }
    }

    private inner class ConversationAdapter(
        private val messages: List<SmsMessage>
    ) : RecyclerView.Adapter<ConversationAdapter.ConversationViewHolder>() {

        inner class ConversationViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val messageText: TextView = itemView.findViewById(R.id.messageText)
            val messageTime: TextView = itemView.findViewById(R.id.messageTime)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ConversationViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_conversation_message, parent, false)
            return ConversationViewHolder(view)
        }

        override fun onBindViewHolder(holder: ConversationViewHolder, position: Int) {
            val message = messages[position]
            holder.messageText.text = message.body
            
            val timeFormat = SimpleDateFormat("h:mm a", Locale.getDefault())
            holder.messageTime.text = timeFormat.format(Date(message.date))
            
            // Style based on message type (incoming/outgoing)
            val isOutgoing = message.address != senderAddress
            val bgColor = if (isOutgoing) {
                ContextCompat.getColor(this@ConversationActivity, R.color.message_outgoing)
            } else {
                ContextCompat.getColor(this@ConversationActivity, R.color.message_incoming)
            }
            
            holder.itemView.setBackgroundColor(bgColor)
        }

        override fun getItemCount() = messages.size
    }
}
