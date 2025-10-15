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
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
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
        adapter = SmsAdapter(smsList) { position ->
            // Toggle selection state when an item is clicked
            smsList[position].isSelected = !smsList[position].isSelected
            adapter.notifyItemChanged(position)
            // Update the delete button state after selection changes
            updateDeleteButtonState()
        }
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

        // Initially request runtime permissions when the app starts
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
    
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CODE_DEFAULT_SMS) {
            if (isDefaultSmsApp()) {
                Toast.makeText(this, "Now set as default SMS app. You can now delete messages.", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Please set this app as the default SMS app to delete messages.", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun searchSms() {
        val pattern = searchEditText.text.toString().trim()
        
        // Clear the current list and reset selection states
        smsList.clear()
        selectAllCheckBox.isChecked = false
        adapter.notifyDataSetChanged()
        
        // If search box is empty, just clear the list and return
        if (pattern.isBlank()) {
            updateDeleteButtonState()
            return
        }

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
                null,
                null,
                "${Telephony.Sms.DATE} DESC"
            )

            var count = 0
            cursor?.use { cursor ->
                val idIndex = cursor.getColumnIndexOrThrow(Telephony.Sms._ID)
                val addressIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
                val bodyIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.BODY)
                val dateIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.DATE)

                while (cursor.moveToNext() && count < 1000) { // Limit to 1000 messages for performance
                    val body = cursor.getString(bodyIndex) ?: ""
                    if (body.contains(pattern, ignoreCase = true)) {
                        val id = cursor.getString(idIndex)
                        val address = cursor.getString(addressIndex) ?: "Unknown"
                        val date = cursor.getLong(dateIndex)

                        smsList.add(SmsMessage(id, address, body, date))
                        count++
                    }
                }
            }

            adapter.notifyDataSetChanged()
            val message = if (count > 0) {
                "Found $count messages matching '$pattern'"
            } else {
                "No messages found matching '$pattern'"
            }
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.e("SMSManager", "Error searching SMS", e)
            Toast.makeText(this, "Error searching messages: ${e.message}", Toast.LENGTH_LONG).show()
        }

        updateSelectionState()
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
            startActivityForResult(intent, REQUEST_CODE_DEFAULT_SMS)
        } else {
            // For older versions, open settings
            val intent = Intent(Settings.ACTION_WIRELESS_SETTINGS)
            startActivity(intent)
        }
    }

    private fun deleteSelectedMessages(selectedMessages: List<SmsMessage>) {
        try {
            val contentResolver = contentResolver
            val deletedCount = selectedMessages.count { message ->
                try {
                    val uri = Uri.parse("content://sms/${message.id}")
                    val rowsDeleted = contentResolver.delete(uri, null, null) > 0
                    if (rowsDeleted) {
                        // Remove the message from our list if deletion was successful
                        smsList.removeAll { it.id == message.id }
                    }
                    rowsDeleted
                } catch (e: Exception) {
                    Log.e("SMSManager", "Error deleting message ${message.id}", e)
                    false
                }
            }

            // Update the UI
            adapter.notifyDataSetChanged()
            updateDeleteButtonState()
            
            // Clear any remaining selections
            smsList.forEach { it.isSelected = false }
            selectAllCheckBox.isChecked = false
            
            val message = if (deletedCount > 0) {
                "Successfully deleted $deletedCount message(s)"
            } else {
                "Failed to delete messages. Please try again."
            }
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        } catch (e: SecurityException) {
            Log.e("SMSManager", "Security exception while deleting messages", e)
            Toast.makeText(this, "Permission denied. Please make sure this app is the default SMS app.", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Log.e("SMSManager", "Error deleting messages", e)
            Toast.makeText(this, "Error deleting messages: ${e.message}", Toast.LENGTH_LONG).show()
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
        
        // Update the select all checkbox state
        selectAllCheckBox.setOnCheckedChangeListener(null) // Remove listener to prevent infinite loop
        selectAllCheckBox.isChecked = selectedCount > 0 && smsList.all { it.isSelected }
        selectAllCheckBox.jumpDrawablesToCurrentState() // Update the visual state without animation
        selectAllCheckBox.setOnCheckedChangeListener { _, isChecked ->
            smsList.forEach { it.isSelected = isChecked }
            adapter.notifyDataSetChanged()
            updateDeleteButtonState()
        }
    }
    
    private fun updateSelectionState() {
        // This function is called to clear all selections
        smsList.forEach { it.isSelected = false }
        selectAllCheckBox.isChecked = false
        adapter.notifyDataSetChanged()
        updateDeleteButtonState()
    }

    // --- RecyclerView Adapter ---

    private inner class SmsAdapter(
        private val messages: List<SmsMessage>,
        private val onItemClick: (Int) -> Unit
    ) : RecyclerView.Adapter<SmsAdapter.SmsViewHolder>() {

        inner class SmsViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val senderTextView: TextView = itemView.findViewById(R.id.senderTextView)
            val bodyTextView: TextView = itemView.findViewById(R.id.bodyTextView)
            val dateTextView: TextView = itemView.findViewById(R.id.dateTextView)
            val selectCheckBox: CheckBox = itemView.findViewById(R.id.selectCheckBox)

            init {
                itemView.setOnClickListener {
                    val position = bindingAdapterPosition
                    if (position != RecyclerView.NO_POSITION) {
                        onItemClick(position)
                    }
                }
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
