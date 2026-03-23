package com.sj.obd2app.obd

import android.content.Context
import android.util.Log
import com.sj.obd2app.settings.AppSettings
import com.sj.obd2app.storage.AppDataDirectory
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*

/**
 * Logger for Bluetooth OBD connection events.
 * Writes timestamped logs to bt_connection.log in the user-selected tracks folder.
 */
class BluetoothConnectionLogger private constructor(private val context: Context) {

    companion object {
        private const val TAG = "BtConnectionLogger"
        private const val LOG_FILE_NAME = "obd_bt_connx.log"
        
        @Volatile
        private var instance: BluetoothConnectionLogger? = null
        
        fun getInstance(context: Context): BluetoothConnectionLogger {
            return instance ?: synchronized(this) {
                instance ?: BluetoothConnectionLogger(context.applicationContext).also { instance = it }
            }
        }
    }

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private val logLock = Any() // Synchronization lock for file operations
    
    /**
     * Log a Bluetooth connection event to file.
     * Only logs if BT logging is enabled in settings.
     * Thread-safe - synchronizes file access to prevent corruption.
     */
    fun log(message: String) {
        synchronized(logLock) {
        // Check if BT logging is enabled
        if (!AppSettings.isBtLoggingEnabled(context)) {
            return
        }
        
        try {
            val logFile = getLogFileDocumentFile() ?: run {
                Log.w(TAG, "Cannot write BT log - log folder not configured")
                return
            }
            
            val timestamp = dateFormat.format(Date())
            val logEntry = "[$timestamp] $message\n"
            
            //Log.v(TAG, "Writing to log file: ${logFile.name}")
            
            // Append to file using ContentResolver and flush immediately
            context.contentResolver.openOutputStream(logFile.uri, "wa")?.use { output ->
                output.write(logEntry.toByteArray())
                output.flush()
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write BT log: ${e.message}", e)
        }
        }
    }
    
    /**
     * Log a connection state change.
     */
    fun logConnectionStateChange(oldState: Obd2Service.ConnectionState, newState: Obd2Service.ConnectionState, deviceName: String? = null) {
        val deviceInfo = deviceName?.let { " (device: $it)" } ?: ""
        log("Connection state changed: $oldState → $newState$deviceInfo")
    }
    
    /**
     * Log a connection attempt.
     */
    fun logConnectionAttempt(deviceName: String, deviceMac: String) {
        log("Attempting connection to $deviceName ($deviceMac)")
    }
    
    /**
     * Log a connection success.
     */
    fun logConnectionSuccess(deviceName: String, pidsSupported: Int) {
        log("Connection successful to $deviceName - $pidsSupported PIDs supported")
    }
    
    /**
     * Log a connection failure.
     */
    fun logConnectionFailure(deviceName: String, error: String) {
        log("Connection failed to $deviceName: $error")
    }
    
    /**
     * Log a disconnection.
     */
    fun logDisconnection(reason: String = "User initiated") {
        log("Disconnected: $reason")
    }
    
    /**
     * Log polling errors.
     */
    fun logPollingError(consecutiveFailures: Int, maxFailures: Int) {
        log("Polling errors: $consecutiveFailures consecutive failures (max: $maxFailures)")
    }
    
    /**
     * Log socket health check failure.
     */
    fun logSocketHealthFailure() {
        log("Socket health check failed - connection lost")
    }
    
    /**
     * Log reconnection attempt.
     */
    fun logReconnectionAttempt(attemptNumber: Int, deviceMac: String) {
        log("Reconnection attempt #$attemptNumber to $deviceMac")
    }
    
    /**
     * Log reconnection success.
     */
    fun logReconnectionSuccess(attemptNumber: Int) {
        log("Reconnection successful after $attemptNumber attempts")
    }
    
    /**
     * Get the log file DocumentFile in the user-selected tracks folder.
     * Returns null if folder is not configured.
     * Always returns the same file to prevent duplicates.
     * Thread-safe - synchronizes file discovery/creation.
     */
    private fun getLogFileDocumentFile(): androidx.documentfile.provider.DocumentFile? {
        synchronized(logLock) {
        val obdDir = AppDataDirectory.getObdDirectoryDocumentFile(context) ?: return null
        
        // List all files in the directory to find existing log files
        val existingFiles = obdDir.listFiles()
        val existingLogFile = existingFiles?.find { it.name == LOG_FILE_NAME }
        
        // If we found an existing log file, use it
        if (existingLogFile != null && existingLogFile.exists()) {
            //Log.d(TAG, "Found existing log file: ${existingLogFile.name}")
            return existingLogFile
        }
        
        // No existing file found, create a new one
        Log.d(TAG, "Creating new log file: $LOG_FILE_NAME")
        val newLogFile = obdDir.createFile("text/plain", LOG_FILE_NAME)
        
        return newLogFile
        }
    }
    
    /**
     * Get the current log file size in bytes.
     */
    fun getLogFileSize(): Long {
        return try {
            getLogFileDocumentFile()?.length() ?: 0L
        } catch (e: Exception) {
            0L
        }
    }
    
    /**
     * Clear the log file.
     */
    fun clearLog() {
        try {
            val logFile = getLogFileDocumentFile() ?: return
            // Delete and recreate the file
            logFile.delete()
            log("Log file cleared")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear log file: ${e.message}", e)
        }
    }
}
