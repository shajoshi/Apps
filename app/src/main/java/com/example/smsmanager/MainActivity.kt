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
import android.provider.ContactsContract
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
import kotlinx.coroutines.delay
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
    val isDefaultSmsApp: Boolean = false,
    var isSelected: Boolean = false,
    val contactName: String = "" // Cached contact name
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
        private const val REQUEST_READ_CONTACTS_PERMISSION = 103
        private const val INITIAL_BATCH_SIZE = 20 // Show first 20 messages immediately
        private const val BATCH_SIZE = 50 // Load 50 messages at a time in background
    }

    private var smsList = mutableListOf<SmsMessage>()
    private lateinit var adapter: SmsAdapter
    private var isFirstLoad = true
    private var isLoadingMore = false
    
    // Contact name cache to optimize repeated contact resolution
    private val contactNameCache = mutableMapOf<String, String>()
    
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
    
    private fun getContactName(phoneNumber: String): String {
        // Check cache first for immediate response
        contactNameCache[phoneNumber]?.let { cachedName ->
            Log.d("SMSManager", "Contact resolution: Cache hit for $phoneNumber -> '$cachedName'")
            return cachedName
        }
        
        if (!hasContactsPermission()) {
            Log.d("SMSManager", "Contact resolution: No contacts permission, caching and returning phone number")
            contactNameCache[phoneNumber] = phoneNumber
            return phoneNumber
        }
        
        val contactResolutionStart = System.currentTimeMillis()
        
        return try {
            val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(phoneNumber))
            val projection = arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME)
            
            val resolvedName = contentResolver.query(uri, projection, null, null, null)?.use { cursor: android.database.Cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(ContactsContract.PhoneLookup.DISPLAY_NAME)
                    val contactName = cursor.getString(nameIndex)
                    val resolutionTime = System.currentTimeMillis() - contactResolutionStart
                    Log.d("SMSManager", "Contact resolution: Found '$contactName' for $phoneNumber in ${resolutionTime}ms")
                    contactName
                } else {
                    Log.d("SMSManager", "Contact resolution: No contact found for $phoneNumber in ${System.currentTimeMillis() - contactResolutionStart}ms")
                    phoneNumber
                }
            } ?: phoneNumber
            
            // Cache the result (whether found or not)
            contactNameCache[phoneNumber] = resolvedName
            Log.d("SMSManager", "Contact resolution: Cached result for $phoneNumber -> '$resolvedName'")
            
            resolvedName
        } catch (e: Exception) {
            Log.w("SMSManager", "Error resolving contact name for $phoneNumber", e)
            // Cache the error case (phone number) to avoid repeated failures
            contactNameCache[phoneNumber] = phoneNumber
            phoneNumber
        }
    }
    
    private fun hasContactsPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
    }
    
    private fun clearContactCache() {
        val cacheSize = contactNameCache.size
        contactNameCache.clear()
        Log.d("SMSManager", "Contact cache: Cleared $cacheSize cached entries")
    }
    
    private fun logCacheStats() {
        Log.d("SMSManager", "Contact cache stats: ${contactNameCache.size} entries cached")
        // Log first few entries for debugging
        contactNameCache.entries.take(5).forEach { (phone, name) ->
            Log.d("SMSManager", "Cache entry: $phone -> '$name'")
        }
    }
    
    private fun requestContactsPermission() {
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.READ_CONTACTS),
            REQUEST_READ_CONTACTS_PERMISSION
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val startTime = System.currentTimeMillis()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        
        // Test debug logging
        Log.d("SMSManager", "MainActivity onCreate - Debug logging is working!")
        Log.d("SMSManager", "Performance: onCreate took ${System.currentTimeMillis() - startTime}ms")

        // Initialize UI elements
        searchEditText = findViewById(R.id.searchEditText)
        searchButton = findViewById(R.id.searchButton)
        selectAllCheckBox = findViewById(R.id.selectAllCheckBox)
        deleteButton = findViewById(R.id.deleteButton)
        recyclerView = findViewById(R.id.recyclerView)

        // Setup RecyclerView
        val adapterSetupStart = System.currentTimeMillis()
        adapter = SmsAdapter(
            smsList,
            // Checkbox click handler
            object : (Int) -> Unit {
                override fun invoke(position: Int) {
                    Log.i("SMSManager", "User interaction: Checkbox clicked for message at position $position")
                    val toggleStart = System.currentTimeMillis()
                    
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
                            Log.i("SMSManager", "User interaction: Select all checkbox changed to $isChecked")
                            smsList.forEach { it.isSelected = isChecked }
                            adapter.notifyDataSetChanged()
                            updateDeleteButtonState()
                        }
                    }
                    
                    Log.d("SMSManager", "Performance: Checkbox toggle took ${System.currentTimeMillis() - toggleStart}ms")
                }
            },
            // Message area click handler
            object : (Int) -> Unit {
                override fun invoke(position: Int) {
                    Log.i("SMSManager", "User interaction: Message clicked at position $position")
                    val message = smsList[position]
                    Log.i("SMSManager", "User action: Opening conversation for ${message.contactName.ifEmpty { message.address }}")
                    val intent = Intent(this@MainActivity, ConversationActivity::class.java).apply {
                        putExtra("SENDER_ADDRESS", message.address)
                        putExtra("CONTACT_NAME", message.contactName)
                    }
                    startActivity(intent)
                }
            }
        )
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter
        Log.d("SMSManager", "Performance: Adapter setup took ${System.currentTimeMillis() - adapterSetupStart}ms")

        // Set up button listeners
        searchButton.setOnClickListener {
            Log.i("SMSManager", "User interaction: Search button clicked")
            checkAndRequestPermissions()
        }
        
        // Add text changed listener to search EditText
        searchEditText.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (s.isNullOrEmpty()) {
                    Log.i("SMSManager", "User interaction: Search cleared, loading all messages")
                    searchSms()
                } else {
                    Log.i("SMSManager", "User interaction: Search text changed to '$s'")
                }
            }
            
            override fun afterTextChanged(s: android.text.Editable?) {}
        })

        selectAllCheckBox.setOnCheckedChangeListener { _, isChecked ->
            Log.i("SMSManager", "User interaction: Select all checkbox changed to $isChecked")
            val selectAllStart = System.currentTimeMillis()
            smsList.forEach { it.isSelected = isChecked }
            adapter.notifyDataSetChanged()
            updateDeleteButtonState()
            Log.d("SMSManager", "Performance: Select all operation took ${System.currentTimeMillis() - selectAllStart}ms")
        }

        deleteButton.setOnClickListener {
            Log.i("SMSManager", "User interaction: Delete button clicked")
            val selectedCount = smsList.count { it.isSelected }
            Log.i("SMSManager", "User action: Attempting to delete $selectedCount selected messages")
            confirmAndDeleteMessages()
        }
        
        // Load all messages when the activity starts (this will also check for permissions)
        Log.i("SMSManager", "App startup: Starting permission check and initial message load")
        checkAndRequestPermissions()
    }

    private fun checkAndRequestPermissions() {
        val permissionCheckStart = System.currentTimeMillis()
        val smsPermission = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_SMS)
        val contactsPermission = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS)
        
        Log.d("SMSManager", "Permission check: SMS=${if (smsPermission == PackageManager.PERMISSION_GRANTED) "GRANTED" else "DENIED"}, Contacts=${if (contactsPermission == PackageManager.PERMISSION_GRANTED) "GRANTED" else "DENIED"}")
        
        when {
            smsPermission != PackageManager.PERMISSION_GRANTED -> {
                Log.i("SMSManager", "Permission request: Requesting SMS and Contacts permissions")
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.READ_SMS, Manifest.permission.READ_CONTACTS),
                    REQUEST_READ_SMS_PERMISSION
                )
            }
            contactsPermission != PackageManager.PERMISSION_GRANTED -> {
                Log.i("SMSManager", "Permission request: Requesting Contacts permission only")
                requestContactsPermission()
            }
            else -> {
                Log.i("SMSManager", "Permission status: All permissions granted, starting message load")
                searchSms()
            }
        }
        
        Log.d("SMSManager", "Performance: Permission check took ${System.currentTimeMillis() - permissionCheckStart}ms")
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        Log.d("SMSManager", "Permission result received for requestCode: $requestCode")
        
        when (requestCode) {
            REQUEST_READ_SMS_PERMISSION -> {
                val smsGranted = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
                val contactsGranted = grantResults.size > 1 && grantResults[1] == PackageManager.PERMISSION_GRANTED
                
                Log.i("SMSManager", "Permission result: SMS=${if (smsGranted) "GRANTED" else "DENIED"}, Contacts=${if (contactsGranted) "GRANTED" else "DENIED"}")
                
                if (smsGranted) {
                    Log.i("SMSManager", "Permission success: SMS permission granted, loading messages")
                    searchSms()
                } else {
                    Log.w("SMSManager", "Permission denied: SMS permission denied, showing error message")
                    Toast.makeText(
                        this,
                        "SMS read permission is required to search messages.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
            REQUEST_READ_CONTACTS_PERMISSION -> {
                val contactsGranted = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
                Log.i("SMSManager", "Permission result: Contacts=${if (contactsGranted) "GRANTED" else "DENIED"}")
                
                if (contactsGranted) {
                    Log.i("SMSManager", "Permission success: Contacts permission granted, clearing cache and refreshing list")
                    // Clear cache since we now have permission to resolve contacts properly
                    clearContactCache()
                    // Refresh the list to show contact names
                    adapter.notifyDataSetChanged()
                } else {
                    Log.w("SMSManager", "Permission denied: Contacts permission denied")
                }
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
        if (isLoadingMore) {
            Log.d("SMSManager", "Load skipped: Already loading messages")
            return
        }
        isLoadingMore = true
        
        val loadType = if (filterSender != null) "filtered messages for $filterSender" else "all messages"
        Log.i("SMSManager", "Message loading: Starting to load $loadType")
        val overallLoadStart = System.currentTimeMillis()
        
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // Load only initial batch first
                loadInitialBatch(filterSender)
                Log.d("SMSManager", "Performance: Overall message loading took ${System.currentTimeMillis() - overallLoadStart}ms")
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Log.e("SMSManager", "Error loading messages", e)
                    Toast.makeText(this@MainActivity, "Error loading messages: ${e.message}", Toast.LENGTH_SHORT).show()
                    isLoadingMore = false
                }
            }
        }
    }
    
    private suspend fun loadInitialBatch(filterSender: String? = null) {
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

        val initialMessages = mutableListOf<SmsMessage>()
        var loadedCount = 0
        
        cursor?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(Telephony.Sms._ID)
            val addressIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
            val bodyIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.BODY)
            val dateIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.DATE)

            // Load ONLY the initial batch
            while (cursor.moveToNext() && loadedCount < INITIAL_BATCH_SIZE && loadedCount < maxMessageLimit) {
                val id = cursor.getString(idIndex)
                val address = cursor.getString(addressIndex) ?: "Unknown"
                val body = cursor.getString(bodyIndex) ?: ""
                val date = cursor.getLong(dateIndex)
                
                // Resolve contact name during loading for better performance
                val contactName = getContactName(address)
                initialMessages.add(SmsMessage(id, address, body, date, isDefaultSmsApp = isDefaultSmsApp(), contactName = contactName))
                loadedCount++
                
                // Debug logging for each message loaded
                if (loadedCount <= 5) {
                    Log.d("SMSManager", "Loaded initial message $loadedCount: $address -> $contactName")
                }
            }
            
            Log.d("SMSManager", "Initial batch complete: loaded $loadedCount messages")
        }
        
        // Show initial batch immediately and ensure UI is updated
        withContext(Dispatchers.Main) {
            smsList.clear()
            smsList.addAll(initialMessages)
            adapter.notifyDataSetChanged()
            isLoadingMore = false
            
            // Log for debugging
            Log.d("SMSManager", "Initial batch loaded: ${initialMessages.size} messages displayed")
            logCacheStats()
        }
        
        // Start loading remaining messages in background after a small delay
        if (loadedCount >= INITIAL_BATCH_SIZE && loadedCount < maxMessageLimit) {
            delay(100) // Small delay to ensure UI is fully updated
            loadRemainingMessagesIncrementally(filterSender, loadedCount)
        }
        
        // Count total messages in background with delay to not interfere with initial UI
        if (isFirstLoad && filterSender == null) {
            delay(200) // Delay to ensure initial UI is fully rendered
            countTotalMessages()
        }
    }
    
    private fun loadRemainingMessagesIncrementally(filterSender: String? = null, startOffset: Int = INITIAL_BATCH_SIZE) {
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

                var currentOffset = startOffset
                var totalLoaded = startOffset
                
                cursor?.use { cursor ->
                    val idIndex = cursor.getColumnIndexOrThrow(Telephony.Sms._ID)
                    val addressIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
                    val bodyIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.BODY)
                    val dateIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.DATE)

                    // Skip already loaded messages
                    if (cursor.moveToPosition(startOffset - 1)) {
                        cursor.moveToNext()
                        
                        // Load remaining messages in batches
                        val batch = mutableListOf<SmsMessage>()
                        
                        while (cursor.moveToNext() && totalLoaded < maxMessageLimit) {
                            val id = cursor.getString(idIndex)
                            val address = cursor.getString(addressIndex) ?: "Unknown"
                            val body = cursor.getString(bodyIndex) ?: ""
                            val date = cursor.getLong(dateIndex)
                            
                            val contactName = getContactName(address)
                            batch.add(SmsMessage(id, address, body, date, isDefaultSmsApp = isDefaultSmsApp(), contactName = contactName))
                            totalLoaded++
                            
                            // When batch is full, update UI
                            if (batch.size >= BATCH_SIZE) {
                                withContext(Dispatchers.Main) {
                                    val currentSize = smsList.size
                                    smsList.addAll(batch)
                                    adapter.notifyItemRangeInserted(currentSize, batch.size)
                                }
                                batch.clear()
                                delay(50) // Small delay to keep UI responsive
                            }
                        }
                        
                        // Load any remaining messages in the last batch
                        if (batch.isNotEmpty()) {
                            withContext(Dispatchers.Main) {
                                val currentSize = smsList.size
                                smsList.addAll(batch)
                                adapter.notifyItemRangeInserted(currentSize, batch.size)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("SMSManager", "Error loading remaining messages", e)
            }
        }
    }
    
        
    private fun countTotalMessages() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val contentResolver = contentResolver
                val cursor = contentResolver.query(
                    Telephony.Sms.CONTENT_URI,
                    arrayOf(Telephony.Sms._ID),
                    null,
                    null,
                    null
                )
                
                var totalCount = 0
                cursor?.use { cursor ->
                    while (cursor.moveToNext()) {
                        totalCount++
                    }
                }
                
                // Show toast on main thread after counting is complete
                withContext(Dispatchers.Main) {
                    val message = if (totalCount > maxMessageLimit) {
                        "Found $totalCount total messages (showing latest $maxMessageLimit)"
                    } else {
                        "Found $totalCount total messages"
                    }
                    Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
                    isFirstLoad = false
                }
            } catch (e: Exception) {
                Log.e("SMSManager", "Error counting total messages", e)
                // Still set isFirstLoad to false even if counting fails
                withContext(Dispatchers.Main) {
                    isFirstLoad = false
                }
            }
        }
    }
    
    private fun searchSms() {
        val pattern = searchEditText.text.toString().trim()
        val searchStart = System.currentTimeMillis()
        
        Log.i("SMSManager", "Search operation: Starting search for pattern '$pattern'")
        
        // Clear the current list and reset selection states
        smsList.clear()
        selectAllCheckBox.isChecked = false
        adapter.notifyDataSetChanged()
        
        // If search box is empty, show all messages
        if (pattern.isBlank()) {
            Log.i("SMSManager", "Search operation: Empty pattern, loading all messages")
            loadSmsMessages()
            return
        }
        
        Log.i("SMSManager", "Search operation: Searching for messages containing '$pattern'")
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
                        
                        // Resolve contact name during loading for better performance
                        val contactName = getContactName(address)
                        messages.add(SmsMessage(id, address, body, date, isDefaultSmsApp = isDefaultSmsApp(), contactName = contactName))
                        count++
                    }
                }
                
                // Update UI on main thread
                withContext(Dispatchers.Main) {
                    smsList.addAll(messages)
                    adapter.notifyDataSetChanged()
                    
                    val searchDuration = System.currentTimeMillis() - searchStart
                    Log.i("SMSManager", "Search completed: Found $count messages in ${searchDuration}ms")
                    
                    if (count == 0) {
                        Log.i("SMSManager", "Search result: No messages found for pattern '$pattern'")
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

    private inner class SmsAdapter(
        private val messages: MutableList<SmsMessage>,
        private val onItemClick: (Int) -> Unit, // For checkbox
        private val onMessageClick: (Int) -> Unit // For message content
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
            
            // Use cached contact name and display with phone number
            val displayText = if (message.contactName.isNotEmpty() && message.contactName != message.address) {
                "${message.contactName} (${message.address})"
            } else {
                message.address.ifEmpty { "Unknown Sender" }
            }
            holder.senderTextView.text = displayText
            
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
