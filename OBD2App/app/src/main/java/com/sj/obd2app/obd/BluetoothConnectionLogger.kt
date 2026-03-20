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
    
    /**
     * Log a Bluetooth connection event to file.
     * Only logs if BT logging is enabled in settings.
     */
    fun log(message: String) {
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
            
            // Append to file using ContentResolver and flush immediately
            context.contentResolver.openOutputStream(logFile.uri, "wa")?.use { output ->
                output.write(logEntry.toByteArray())
                output.flush()
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write BT log: ${e.message}", e)
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
     */
    private fun getLogFileDocumentFile(): androidx.documentfile.provider.DocumentFile? {
        val obdDir = AppDataDirectory.getObdDirectoryDocumentFile(context) ?: return null
        
        // Find existing log file first - this prevents creating duplicates
        var logFile = obdDir.findFile(LOG_FILE_NAME)
        if (logFile == null || !logFile.exists()) {
            // Only create if it doesn't exist
            logFile = obdDir.createFile("text/plain", LOG_FILE_NAME)
        }
        
        return logFile
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
