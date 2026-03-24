package com.sj.obd2app.storage

import android.content.Context
import android.util.Log
import android.widget.Toast

/**
 * Handles data integrity checks on app startup.
 * Ensures existing data in .obd directory is not overwritten when app is reinstalled.
 */
object DataMigration {

    private const val TAG = "DataMigration"

    /**
     * Checks for existing data on app startup.
     * Shows a Toast if existing data is found to reassure user their data is preserved.
     * 
     * This is important when app is reinstalled and user reselects the same folder.
     */
    fun checkExistingData(context: Context) {
        // Check if external storage is available
        if (!AppDataDirectory.isUsingExternalStorage(context)) {
            Log.d(TAG, "External storage not available")
            Toast.makeText(context, "External storage not available", Toast.LENGTH_LONG).show()
            return
        }

        // Check if .obd directory already has existing data
        // This reassures user their data is preserved after reinstall
        if (hasExistingData(context)) {
            Log.i(TAG, "Existing data detected in .obd directory")
            //Toast.makeText(context, "Existing data found - your profiles and dashboards are preserved", Toast.LENGTH_LONG).show()
        }
    }

    /**
     * Checks if the .obd directory already contains existing data files.
     * Returns true if any profiles or layouts are found.
     */
    private fun hasExistingData(context: Context): Boolean {
        // Check for existing profiles
        val existingProfiles = AppDataDirectory.listProfileFilesDocumentFile(context)
        if (existingProfiles.isNotEmpty()) {
            Log.d(TAG, "Found ${existingProfiles.size} existing profile(s)")
            return true
        }
        
        // Check for existing layouts
        val existingLayouts = AppDataDirectory.listLayoutFilesDocumentFile(context)
        if (existingLayouts.isNotEmpty()) {
            Log.d(TAG, "Found ${existingLayouts.size} existing layout(s)")
            return true
        }
        
        return false
    }
}
