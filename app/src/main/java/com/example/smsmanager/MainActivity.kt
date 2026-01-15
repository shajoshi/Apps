package com.example.smsmanager

import android.Manifest
import android.content.ContentResolver
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.provider.Telephony
import android.telephony.SmsManager
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Data class to hold SMS message details
data class SmsMessage(
    val id: String,
    val address: String,
    val body: String,
    val date: Long,
    var isSelected: Boolean = false,
    var isDefaultSmsApp: Boolean = false
)

class MainActivity : AppCompatActivity() {

    // UI elements
    private lateinit var searchEditText: EditText
    private lateinit var searchButton: Button
    private lateinit var selectAllCheckBox: CheckBox
    private lateinit var deleteButton: Button
    private lateinit var recyclerView: RecyclerView

    companion object {
        private const val REQUEST_READ_SMS_PERMISSION = 101
        private const val REQUEST_CODE_DEFAULT_SMS = 102
    }

    private var smsList = mutableListOf<SmsMessage>()
    private lateinit var adapter: SmsAdapter
    private var isFirstLoad = true
    
    private val defaultSmsLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result: ActivityResult ->
        if (isDefaultSmsApp()) {
            Toast.makeText(this, "Now set as default SMS app. You can now delete messages.", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Please set this app as the default SMS app to delete messages.", Toast.LENGTH_LONG).show()
        }
    }
    
    private val maxMessageLimit: Int by lazy {
        try {
            val appInfo = packageManager.getApplicationInfo(packageName, PackageManager.GET_META_DATA)
            appInfo.metaData?.getInt("com.example.smsmanager.max_message_limit", 1000) ?: 1000
        } catch (e: Exception) {
            Log.w("SMSManager", "Could not read max_message_limit from manifest, using default 1000", e)
            1000
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Initialize UI elements
        searchEditText = findViewById(R.id.searchEditText)
        searchButton = findViewById(R.id.searchButton)
        selectAllCheckBox = findViewById(R.id.selectAllCheckBox)
        deleteButton = findViewById(R.id.deleteButton)
        recyclerView = findViewById(R.id.recyclerView)

        // Setup RecyclerView
        adapter = SmsAdapter(
            smsList,
            // Checkbox click handler
            object : (Int) -> Unit {
                override fun invoke(position: Int) {
                    // Toggle selection state
                    smsList[position].isSelected = !smsList[position].isSelected
                    adapter.notifyItemChanged(position)
                    updateDeleteButtonState()
                    
                    // Update select all checkbox state without triggering listener
                    val allSelected = smsList.all { it.isSelected }
                    val noneSelected = smsList.none { it.isSelected }
                    if (selectAllCheckBox.isChecked != (allSelected && !noneSelected)) {
                        selectAllCheckBox.setOnCheckedChangeListener(null)
                        selectAllCheckBox.isChecked = allSelected && !noneSelected
                        selectAllCheckBox.jumpDrawablesToCurrentState()
                        selectAllCheckBox.setOnCheckedChangeListener { _, isChecked ->
                            smsList.forEach { it.isSelected = isChecked }
                            adapter.notifyDataSetChanged()
                            updateDeleteButtonState()
                        }
                    }
                }
            },
            // Message area click handler
            object : (Int) -> Unit {
                override fun invoke(position: Int) {
                    // Open conversation without changing selection state
                    val message = smsList[position]
                    val intent = Intent(this@MainActivity, ConversationActivity::class.java).apply {
                        putExtra("SENDER_ADDRESS", message.address)
                    }
                    startActivity(intent)
                }
            }
        )
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        // Set up button listeners
        searchButton.setOnClickListener {
            checkAndRequestPermissions()
        }
        
        // Add text changed listener to search EditText
        searchEditText.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (s.isNullOrEmpty()) {
                    searchSms()
                }
            }
            
            override fun afterTextChanged(s: android.text.Editable?) {}
        })

        selectAllCheckBox.setOnCheckedChangeListener { _, isChecked ->
            smsList.forEach { it.isSelected = isChecked }
            adapter.notifyDataSetChanged()
            updateDeleteButtonState()
        }

        deleteButton.setOnClickListener {
            confirmAndDeleteMessages()
        }
        
        // Load all messages when the activity starts (this will also check for permissions)
        checkAndRequestPermissions()
    }

    private fun checkAndRequestPermissions() {
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.READ_SMS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            // Permission is not granted, request it
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.READ_SMS),
                REQUEST_READ_SMS_PERMISSION
            )
        } else {
            // Permission already granted, proceed with search
            searchSms()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_READ_SMS_PERMISSION) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                searchSms()
            } else {
                Toast.makeText(
                    this,
                    "SMS read permission is required to search messages.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private var isShowingConversation = false
    private var currentSender: String? = null

    private fun loadMessagesBySender(sender: String) {
        currentSender = sender
        isShowingConversation = true
        
        // Update UI for conversation view
        searchEditText.hint = "Search in conversation with $sender"
        selectAllCheckBox.visibility = View.GONE
        deleteButton.visibility = View.GONE
        
        // Clear and reload messages from this sender
        smsList.clear()
        loadSmsMessages(sender)
    }
    
    private fun showAllMessages() {
        isShowingConversation = false
        currentSender = null
        
        // Reset UI to normal view
        searchEditText.hint = "Search messages..."
        selectAllCheckBox.visibility = View.VISIBLE
        deleteButton.visibility = View.VISIBLE
        
        // Reload all messages
        searchSms()
    }
    
    private fun loadSmsMessages(filterSender: String? = null) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val contentResolver: ContentResolver = contentResolver
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
                    if (filterSender != null) "${Telephony.Sms.ADDRESS} = ?" else null,
                    if (filterSender != null) arrayOf(filterSender) else null,
                    "${Telephony.Sms.DATE} DESC"
                )

                val messages = mutableListOf<SmsMessage>()
                var count = 0
                var totalCount = 0
                cursor?.use { cursor ->
                    val idIndex = cursor.getColumnIndexOrThrow(Telephony.Sms._ID)
                    val addressIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
                    val bodyIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.BODY)
                    val dateIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.DATE)

                    // Count total messages first
                    if (filterSender == null && isFirstLoad) {
                        cursor.moveToPosition(-1)
                        while (cursor.moveToNext()) {
                            totalCount++
                        }
                        cursor.moveToPosition(-1)
                    }

                    while (cursor.moveToNext() && count < maxMessageLimit) {
                        val id = cursor.getString(idIndex)
                        val address = cursor.getString(addressIndex) ?: "Unknown"
                        val body = cursor.getString(bodyIndex) ?: ""
                        val date = cursor.getLong(dateIndex)
                        
                        messages.add(SmsMessage(id, address, body, date, isDefaultSmsApp = isDefaultSmsApp()))
                        count++
                    }
                }
                
                // Update UI on main thread
                withContext(Dispatchers.Main) {
                    smsList.clear()
                    smsList.addAll(messages)
                    adapter.notifyDataSetChanged()
                    
                    // Show total message count on first load
                    if (isFirstLoad && filterSender == null) {
                        val message = if (totalCount > maxMessageLimit) {
                            "Found $totalCount total messages (showing latest $maxMessageLimit)"
                        } else {
                            "Found $totalCount total messages"
                        }
                        Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
                        isFirstLoad = false
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Log.e("SMSManager", "Error loading messages", e)
                    Toast.makeText(this@MainActivity, "Error loading messages: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
    
    private fun searchSms() {
        val pattern = searchEditText.text.toString().trim()
        
        // Clear the current list and reset selection states
        smsList.clear()
        selectAllCheckBox.isChecked = false
        adapter.notifyDataSetChanged()
        
        // If search box is empty, show all messages
        if (pattern.isBlank()) {
            loadSmsMessages()
            return
        }
        
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val contentResolver: ContentResolver = contentResolver
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
                    "${Telephony.Sms.BODY} LIKE ? OR ${Telephony.Sms.ADDRESS} LIKE ?",
                    arrayOf("%$pattern%", "%$pattern%"),
                    "${Telephony.Sms.DATE} DESC"
                )

                val messages = mutableListOf<SmsMessage>()
                var count = 0
                cursor?.use { cursor ->
                    val idIndex = cursor.getColumnIndexOrThrow(Telephony.Sms._ID)
                    val addressIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
                    val bodyIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.BODY)
                    val dateIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.DATE)

                    while (cursor.moveToNext() && count < maxMessageLimit) {
                        val id = cursor.getString(idIndex)
                        val address = cursor.getString(addressIndex) ?: "Unknown"
                        val body = cursor.getString(bodyIndex) ?: ""
                        val date = cursor.getLong(dateIndex)
                        
                        messages.add(SmsMessage(id, address, body, date, isDefaultSmsApp = isDefaultSmsApp()))
                        count++
                    }
                }
                
                // Update UI on main thread
                withContext(Dispatchers.Main) {
                    smsList.addAll(messages)
                    adapter.notifyDataSetChanged()
                    
                    if (count == 0) {
                        Toast.makeText(this@MainActivity, "No messages found", Toast.LENGTH_SHORT).show()
                    }
                    
                    updateSelectionState()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Log.e("SMSManager", "Error searching messages", e)
                    Toast.makeText(this@MainActivity, "Error searching messages: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun confirmAndDeleteMessages() {
        val selectedMessages = smsList.filter { it.isSelected }
        if (selectedMessages.isEmpty()) {
            Toast.makeText(this, "No messages selected for deletion.", Toast.LENGTH_SHORT).show()
            return
        }

        if (!isDefaultSmsApp()) {
            showSetDefaultSmsDialog()
            return
        }

        val messageCount = selectedMessages.size
        val messageText = if (messageCount == 1) {
            "You are about to delete 1 selected message. This action cannot be undone.\n\nAre you sure you want to continue?"
        } else {
            "You are about to delete $messageCount selected messages. This action cannot be undone.\n\nAre you sure you want to continue?"
        }

        AlertDialog.Builder(this)
            .setTitle("Confirm Deletion")
            .setMessage(messageText)
            .setPositiveButton("Delete") { _, _ ->
                deleteSelectedMessages(selectedMessages)
            }
            .setNegativeButton("Cancel") { dialog, _ ->
                dialog.dismiss()
                Toast.makeText(this, "Deletion cancelled.", Toast.LENGTH_SHORT).show()
            }
            .setIcon(android.R.drawable.ic_dialog_alert)
            .create()
            .apply {
                // Make sure the dialog is not cancelled when touching outside
                setCanceledOnTouchOutside(false)
                show()
            }
    }

    private fun showSetDefaultSmsDialog() {
        AlertDialog.Builder(this)
            .setTitle("Default SMS App Required")
            .setMessage("To delete messages, this app needs to be set as the default SMS app.")
            .setPositiveButton("Set as default") { _, _ ->
                requestDefaultSmsApp()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun isDefaultSmsApp(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            packageName == Telephony.Sms.getDefaultSmsPackage(this)
        } else {
            // For older versions, assume not default as we can't check
            false
        }
    }

    private fun requestDefaultSmsApp() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            val intent = Intent(Telephony.Sms.Intents.ACTION_CHANGE_DEFAULT)
            intent.putExtra(Telephony.Sms.Intents.EXTRA_PACKAGE_NAME, packageName)
            defaultSmsLauncher.launch(intent)
        } else {
            // For older versions, open settings
            val intent = Intent(Settings.ACTION_WIRELESS_SETTINGS)
            startActivity(intent)
        }
    }

    private fun deleteSelectedMessages(selectedMessages: List<SmsMessage>) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val contentResolver = contentResolver
                var deletedCount = 0
                
                selectedMessages.forEach { message ->
                    try {
                        val deleteCount = contentResolver.delete(
                            Telephony.Sms.CONTENT_URI,
                            "${Telephony.Sms._ID} = ?",
                            arrayOf(message.id)
                        )
                        deletedCount += deleteCount
                    } catch (e: Exception) {
                        Log.e("SMSManager", "Error deleting message with ID: ${message.id}", e)
                    }
                }
                
                // Update UI on main thread
                withContext(Dispatchers.Main) {
                    // Check if select all was used before deletion
                    val wasSelectAllUsed = selectAllCheckBox.isChecked
                    
                    // Update the UI
                    adapter.notifyDataSetChanged()
                    updateDeleteButtonState()
                    
                    // Clear any remaining selections
                    smsList.forEach { it.isSelected = false }
                    selectAllCheckBox.isChecked = false
                    adapter.notifyDataSetChanged()
                    updateDeleteButtonState()
                    
                    // Clear search text if select all was used
                    if (wasSelectAllUsed) {
                        searchEditText.text.clear()
                    }
                    
                    // Show result
                    Toast.makeText(
                        this@MainActivity,
                        if (deletedCount > 0) {
                            "Successfully deleted $deletedCount message(s)"
                        } else {
                            "No messages were deleted"
                        },
                        Toast.LENGTH_SHORT
                    ).show()
                    
                    // Reload messages to update the list
                    searchSms()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Log.e("SMSManager", "Error deleting messages", e)
                    Toast.makeText(this@MainActivity, "Error deleting messages: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun updateDeleteButtonState() {
        val selectedCount = smsList.count { it.isSelected }
        deleteButton.isEnabled = selectedCount > 0
        
        // Show toast with number of selected messages
        if (selectedCount > 0) {
            val message = if (selectedCount == 1) {
                "1 message selected"
            } else {
                "$selectedCount messages selected"
            }
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun updateSelectionState() {
        // This function is called to clear all selections
        smsList.forEach { it.isSelected = false }
        selectAllCheckBox.isChecked = false
        adapter.notifyDataSetChanged()
        updateDeleteButtonState()
    }
    
    private fun showMessageDetails(message: SmsMessage) {
        AlertDialog.Builder(this)
            .setTitle(message.address.ifEmpty { "Unknown Sender" })
            .setMessage(message.body)
            .setPositiveButton("OK", null)
            .setNeutralButton("View All Messages") { _, _ ->
                loadMessagesBySender(message.address)
            }
            .show()
    }

    // --- RecyclerView Adapter ---

    private class SmsAdapter(
        private val messages: MutableList<SmsMessage>,
        private val onItemClick: (Int) -> Unit,
        private val onMessageClick: (Int) -> Unit
    ) : RecyclerView.Adapter<SmsAdapter.SmsViewHolder>() {

        inner class SmsViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val senderTextView: TextView = itemView.findViewById(R.id.senderTextView)
            val bodyTextView: TextView = itemView.findViewById(R.id.bodyTextView)
            val dateTextView: TextView = itemView.findViewById(R.id.dateTextView)
            val selectCheckBox: CheckBox = itemView.findViewById(R.id.selectCheckBox)

            init {
                // Message area click (sender, body, date)
                itemView.setOnClickListener {
                    val position = bindingAdapterPosition
                    if (position != RecyclerView.NO_POSITION) {
                        onMessageClick(position)
                    }
                }
                // Checkbox click
                selectCheckBox.setOnClickListener {
                    val position = bindingAdapterPosition
                    if (position != RecyclerView.NO_POSITION) {
                        onItemClick(position)
                    }
                }
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SmsViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_sms_message, parent, false)
            return SmsViewHolder(view)
        }

        override fun onBindViewHolder(holder: SmsViewHolder, position: Int) {
            val message = messages[position]
            holder.senderTextView.text = message.address.ifEmpty { "Unknown Sender" }
            holder.bodyTextView.text = message.body
            holder.dateTextView.text = SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault()).format(Date(message.date))
            holder.selectCheckBox.isChecked = message.isSelected

            // Show warning if not default SMS app
            if (!message.isDefaultSmsApp) {
                holder.itemView.setBackgroundColor(
                    ContextCompat.getColor(
                        holder.itemView.context,
                        android.R.color.holo_orange_light
                    )
                )
            } else {
                holder.itemView.setBackgroundColor(
                    ContextCompat.getColor(
                        holder.itemView.context,
                        android.R.color.transparent
                    )
                )
            }
        }

        override fun getItemCount() = messages.size
    }
}
