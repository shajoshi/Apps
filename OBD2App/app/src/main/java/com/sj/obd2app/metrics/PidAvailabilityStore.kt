package com.sj.obd2app.metrics

import android.content.Context
import com.sj.obd2app.storage.AppDataDirectory
import org.json.JSONObject

/**
 * Persists the last-known non-null OBD2 PID values seen per vehicle profile.
 *
 * If external storage (.obd directory) is available, data is stored in per-profile JSON files.
 * Otherwise, falls back to SharedPreferences for backward compatibility.
 *
 * A PID is considered "available for this vehicle" once it has been seen
 * with a non-empty, non-error response at least once.
 */
object PidAvailabilityStore {

    private const val PREFS_NAME = "pid_availability"
    private val ERROR_VALUES = setOf("NODATA", "ERROR", "?", "UNABLE TO CONNECT", "BUS INIT")

    // ── Read ──────────────────────────────────────────────────────────────────

    /**
     * Returns the set of PIDs ever confirmed for [profileId], or an empty set
     * if no data has been recorded yet (or no profile is active).
     */
    fun getKnownPids(context: Context, profileId: String?): Set<String> {
        profileId ?: return emptySet()
        
        return if (AppDataDirectory.isUsingExternalStorage(context)) {
            getKnownPidsFromFile(context, profileId)
        } else {
            getKnownPidsFromPreferences(context, profileId)
        }
    }

    private fun getKnownPidsFromFile(context: Context, profileId: String): Set<String> {
        val pidsFile = AppDataDirectory.getProfilePidsFileDocumentFile(context, profileId)
        if (pidsFile == null || !pidsFile.exists()) return emptySet()
        
        return try {
            val content = context.contentResolver.openInputStream(pidsFile.uri)?.use {
                it.readBytes().toString(Charsets.UTF_8)
            } ?: return emptySet()
            
            val obj = JSONObject(content)
            (0 until obj.length()).map { obj.names()?.getString(it) ?: "" }.filter { it.isNotEmpty() }.toSet()
        } catch (_: Exception) { emptySet() }
    }

    private fun getKnownPidsFromPreferences(context: Context, profileId: String): Set<String> {
        val json = prefs(context).getString(profileId, null) ?: return emptySet()
        return try {
            val obj = JSONObject(json)
            (0 until obj.length()).map { obj.names()?.getString(it) ?: "" }.filter { it.isNotEmpty() }.toSet()
        } catch (_: Exception) { emptySet() }
    }

    /**
     * Returns all last-known PID→value pairs for [profileId].
     * Useful for displaying the last-seen value in the wizard preview.
     */
    fun getLastValues(context: Context, profileId: String?): Map<String, String> {
        profileId ?: return emptyMap()
        
        return if (AppDataDirectory.isUsingExternalStorage(context)) {
            getLastValuesFromFile(context, profileId)
        } else {
            getLastValuesFromPreferences(context, profileId)
        }
    }

    private fun getLastValuesFromFile(context: Context, profileId: String): Map<String, String> {
        val pidsFile = AppDataDirectory.getProfilePidsFileDocumentFile(context, profileId)
        if (pidsFile == null || !pidsFile.exists()) return emptyMap()
        
        return try {
            val content = context.contentResolver.openInputStream(pidsFile.uri)?.use {
                it.readBytes().toString(Charsets.UTF_8)
            } ?: return emptyMap()
            
            val obj = JSONObject(content)
            buildMap {
                val names = obj.names() ?: return emptyMap()
                for (i in 0 until names.length()) {
                    val key = names.getString(i)
                    put(key, obj.getString(key))
                }
            }
        } catch (_: Exception) { emptyMap() }
    }

    private fun getLastValuesFromPreferences(context: Context, profileId: String): Map<String, String> {
        val json = prefs(context).getString(profileId, null) ?: return emptyMap()
        return try {
            val obj = JSONObject(json)
            buildMap {
                val names = obj.names() ?: return emptyMap()
                for (i in 0 until names.length()) {
                    val key = names.getString(i)
                    put(key, obj.getString(key))
                }
            }
        } catch (_: Exception) { emptyMap() }
    }

    /** Returns true if at least one PID has been recorded for [profileId]. */
    fun hasData(context: Context, profileId: String?): Boolean {
        profileId ?: return false
        
        return if (AppDataDirectory.isUsingExternalStorage(context)) {
            val pidsFile = AppDataDirectory.getProfilePidsFileDocumentFile(context, profileId)
            pidsFile != null && pidsFile.exists()
        } else {
            prefs(context).contains(profileId)
        }
    }

    // ── Write ─────────────────────────────────────────────────────────────────

    /**
     * Merges [newValues] (pid → rawValueString) into the stored map for [profileId].
     * Skips entries whose value looks like an OBD error token.
     * Only writes to disk if at least one new PID is added.
     */
    fun update(context: Context, profileId: String?, newValues: Map<String, String>) {
        profileId ?: return
        if (newValues.isEmpty()) return

        val existing = getLastValues(context, profileId).toMutableMap()
        var changed = false
        for ((pid, value) in newValues) {
            val v = value.trim().uppercase()
            if (v.isEmpty() || ERROR_VALUES.any { v.startsWith(it) }) continue
            if (existing[pid] != value) {
                existing[pid] = value
                changed = true
            }
        }
        if (!changed) return

        val obj = JSONObject()
        existing.forEach { (k, v) -> obj.put(k, v) }
        
        if (AppDataDirectory.isUsingExternalStorage(context)) {
            updateFile(context, profileId, obj)
        } else {
            updatePreferences(context, profileId, obj)
        }
    }

    private fun updateFile(context: Context, profileId: String, obj: JSONObject) {
        val pidsFile = AppDataDirectory.getProfilePidsFileDocumentFile(context, profileId)
        if (pidsFile != null) {
            context.contentResolver.openOutputStream(pidsFile.uri, "wt")?.use { output ->
                output.write(obj.toString(2).toByteArray())
            }
        }
    }

    private fun updatePreferences(context: Context, profileId: String, obj: JSONObject) {
        prefs(context).edit().putString(profileId, obj.toString()).apply()
    }

    /**
     * Clears all stored availability data for [profileId].
     * Call when the user deletes or resets a vehicle profile.
     */
    fun clear(context: Context, profileId: String) {
        if (AppDataDirectory.isUsingExternalStorage(context)) {
            AppDataDirectory.deleteProfilePidsFile(context, profileId)
        } else {
            prefs(context).edit().remove(profileId).apply()
        }
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
