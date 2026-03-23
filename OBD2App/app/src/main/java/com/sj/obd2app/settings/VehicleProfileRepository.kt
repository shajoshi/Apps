package com.sj.obd2app.settings

import android.content.Context
import android.util.Log
import android.widget.Toast
import com.sj.obd2app.obd.CustomPid
import com.sj.obd2app.storage.AppDataDirectory
import org.json.JSONArray
import org.json.JSONObject

/**
 * CRUD repository for [VehicleProfile] objects.
 * 
 * If external storage (.obd directory) is available, profiles are stored as individual JSON files
 * with format: vehicle_profile_<name>.json
 * Otherwise, stored in app-private storage with the same naming convention.
 */
class VehicleProfileRepository private constructor(private val context: Context) {

    companion object {
        private const val TAG = "VehicleProfileRepository"
        private const val PREFS_NAME = "obd2_prefs"
        private const val KEY_PROFILES = "vehicle_profiles"

        @Volatile
        private var INSTANCE: VehicleProfileRepository? = null

        fun getInstance(context: Context): VehicleProfileRepository =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: VehicleProfileRepository(context.applicationContext).also { INSTANCE = it }
            }
    }

    private val prefs get() = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ── Read ──────────────────────────────────────────────────────────────────

    fun getAll(): List<VehicleProfile> {
        return if (AppDataDirectory.isUsingExternalStorage(context)) {
            getAllFromFiles()
        } else {
            getAllFromPreferences()
        }
    }

    private fun getAllFromFiles(): List<VehicleProfile> {
        Log.d(TAG, "getAllFromFiles: loading profiles from external storage")
        val profileFiles = AppDataDirectory.listProfileFilesDocumentFile(context)
        Log.d(TAG, "getAllFromFiles: found ${profileFiles.size} profile files")
        
        val profiles = mutableListOf<VehicleProfile>()
        var errorCount = 0
        
        profileFiles.forEach { file ->
            try {
                val content = context.contentResolver.openInputStream(file.uri)?.use {
                    it.readBytes().toString(Charsets.UTF_8)
                }
                content?.let { 
                    val profile = JSONObject(it).toProfile()
                    profiles.add(profile)
                    Log.d(TAG, "getAllFromFiles: loaded profile '${profile.name}'")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load profile from ${file.name}", e)
                errorCount++
            }
        }
        
        if (errorCount > 0) {
            Toast.makeText(context, "$errorCount profile(s) could not be loaded (corrupted files)", Toast.LENGTH_SHORT).show()
        }
        
        Log.d(TAG, "getAllFromFiles: returning ${profiles.size} profiles")
        return profiles
    }

    private fun getAllFromPreferences(): List<VehicleProfile> {
        // First try to load from app-private files with new naming convention
        val profileFiles = AppDataDirectory.listProfileFilesPrivate(context)
        if (profileFiles.isNotEmpty()) {
            val profiles = mutableListOf<VehicleProfile>()
            var errorCount = 0
            
            profileFiles.forEach { file ->
                try {
                    val content = file.readText()
                    val profile = JSONObject(content).toProfile()
                    profiles.add(profile)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to load profile from ${file.name}", e)
                    errorCount++
                }
            }
            
            if (errorCount > 0) {
                Toast.makeText(context, "$errorCount profile(s) could not be loaded (corrupted files)", Toast.LENGTH_SHORT).show()
            }
            
            return profiles
        }
        
        // Fallback to old SharedPreferences format for backward compatibility
        val json = prefs.getString(KEY_PROFILES, null) ?: return emptyList()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { arr.getJSONObject(it).toProfile() }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun getById(id: String): VehicleProfile? = getAll().firstOrNull { it.id == id }

    val activeProfile: VehicleProfile?
        get() {
            val id = AppSettings.getActiveProfileId(context) ?: return null
            return getById(id)
        }

    // ── Write ─────────────────────────────────────────────────────────────────

    fun save(profile: VehicleProfile) {
        // Check if this is the first profile being created
        val existingProfiles = getAll()
        val isFirstProfile = existingProfiles.isEmpty()
        
        if (AppDataDirectory.isUsingExternalStorage(context)) {
            saveToFile(profile)
        } else {
            saveToPreferences(profile)
        }
        
        // Automatically set the first profile as active
        if (isFirstProfile) {
            AppSettings.setActiveProfileId(context, profile.id)
        }
    }

    private fun saveToFile(profile: VehicleProfile) {
        val profileFile = AppDataDirectory.getProfileFileDocumentFile(context, profile.name)
        if (profileFile != null) {
            val json = profile.toJson()
            // Use "wt" mode to truncate before writing — "w" alone does NOT truncate on Android 10+
            context.contentResolver.openOutputStream(profileFile.uri, "wt")?.use { output ->
                output.write(json.toString(2).toByteArray())
            }
        }
    }

    private fun saveToPreferences(profile: VehicleProfile) {
        // Save to app-private file with new naming convention
        val profileFile = AppDataDirectory.getProfileFilePrivate(context, profile.name)
        val json = profile.toJson()
        profileFile.writeText(json.toString(2))
    }

    fun delete(id: String) {
        // Find profile by ID to get its name
        val profile = getById(id)
        if (profile != null) {
            if (AppDataDirectory.isUsingExternalStorage(context)) {
                deleteFromFile(profile.name)
            } else {
                deleteFromPreferences(profile.name)
            }
            
            if (AppSettings.getActiveProfileId(context) == id) {
                AppSettings.setActiveProfileId(context, getAll().firstOrNull()?.id)
            }
            // PIDs are now part of the profile JSON, so they're automatically deleted
        }
    }

    private fun deleteFromFile(profileName: String) {
        AppDataDirectory.deleteProfileFile(context, profileName)
    }

    private fun deleteFromPreferences(profileName: String) {
        // Delete from app-private file
        val profileFile = AppDataDirectory.getProfileFilePrivate(context, profileName)
        if (profileFile.exists()) {
            profileFile.delete()
        }
    }

    fun setActive(id: String) {
        AppSettings.setActiveProfileId(context, id)
    }

    // ── PID Management ────────────────────────────────────────────────────────

    /**
     * Updates available PIDs for a profile.
     * Merges new PID values with existing ones, skipping error values.
     */
    fun updatePids(profileId: String, newPids: Map<String, String>) {
        if (newPids.isEmpty()) return
        
        val profile = getById(profileId) ?: return
        val errorValues = setOf("NODATA", "ERROR", "?", "UNABLE TO CONNECT", "BUS INIT")
        
        val updatedPids = profile.availablePids.toMutableMap()
        var changed = false
        
        for ((pid, value) in newPids) {
            val v = value.trim().uppercase()
            if (v.isEmpty() || errorValues.any { v.startsWith(it) }) continue
            if (updatedPids[pid] != value) {
                updatedPids[pid] = value
                changed = true
            }
        }
        
        if (changed) {
            val updatedProfile = profile.copy(availablePids = updatedPids)
            save(updatedProfile)
        }
    }

    /**
     * Returns the set of known PIDs for a profile.
     */
    fun getKnownPids(profileId: String?): Set<String> {
        profileId ?: return emptySet()
        return getById(profileId)?.availablePids?.keys ?: emptySet()
    }

    /**
     * Returns all PID -> value pairs for a profile.
     */
    fun getLastPidValues(profileId: String?): Map<String, String> {
        profileId ?: return emptyMap()
        return getById(profileId)?.availablePids ?: emptyMap()
    }

    /**
     * Returns true if profile has any discovered PIDs.
     */
    fun hasDiscoveredPids(profileId: String?): Boolean {
        profileId ?: return false
        return getById(profileId)?.availablePids?.isNotEmpty() ?: false
    }

    // ── Serialisation ─────────────────────────────────────────────────────────

    private fun persistToPreferences(profiles: List<VehicleProfile>) {
        val arr = JSONArray()
        profiles.forEach { arr.put(it.toJson()) }
        prefs.edit().putString(KEY_PROFILES, arr.toString()).apply()
    }

    private fun VehicleProfile.toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("fuelType", fuelType.name)
        put("tankCapacityL", tankCapacityL.toDouble())
        put("fuelPricePerLitre", fuelPricePerLitre.toDouble())
        put("enginePowerBhp", enginePowerBhp.toDouble())
        if (vehicleMassKg > 0f) put("vehicleMassKg", vehicleMassKg.toDouble())
        if (engineDisplacementCc > 0) put("engineDisplacementCc", engineDisplacementCc)
        if (volumetricEfficiencyPct != 85f) put("volumetricEfficiencyPct", volumetricEfficiencyPct.toDouble())
        
        // Serialize availablePids
        if (availablePids.isNotEmpty()) {
            val pidsObj = JSONObject()
            availablePids.forEach { (pid, value) -> pidsObj.put(pid, value) }
            put("availablePids", pidsObj)
        }
        
        // Serialize customPids
        if (customPids.isNotEmpty()) {
            val arr = JSONArray()
            customPids.forEach { cp ->
                arr.put(JSONObject().apply {
                    put("id", cp.id)
                    put("name", cp.name)
                    put("header", cp.header)
                    put("mode", cp.mode)
                    put("pid", cp.pid)
                    put("bytesReturned", cp.bytesReturned)
                    put("unit", cp.unit)
                    put("formula", cp.formula)
                    put("signed", cp.signed)
                    put("enabled", cp.enabled)
                })
            }
            put("customPids", arr)
        }
    }

    private fun JSONObject.toProfile(): VehicleProfile {
        // Deserialize availablePids
        val pidsMap = if (has("availablePids")) {
            val pidsObj = getJSONObject("availablePids")
            buildMap {
                val names = pidsObj.names() ?: return@buildMap
                for (i in 0 until names.length()) {
                    val key = names.getString(i)
                    put(key, pidsObj.getString(key))
                }
            }
        } else {
            emptyMap()
        }
        
        return VehicleProfile(
            id                = getString("id"),
            name              = getString("name"),
            fuelType          = try { FuelType.valueOf(getString("fuelType")) } catch (_: Exception) { FuelType.PETROL },
            tankCapacityL     = getDouble("tankCapacityL").toFloat(),
            fuelPricePerLitre = getDouble("fuelPricePerLitre").toFloat(),
            enginePowerBhp    = optDouble("enginePowerBhp", 0.0).toFloat(),
            vehicleMassKg     = optDouble("vehicleMassKg", 0.0).toFloat(),
            engineDisplacementCc = optInt("engineDisplacementCc", 0),
            volumetricEfficiencyPct = optDouble("volumetricEfficiencyPct", 85.0).toFloat(),
            availablePids     = pidsMap,
            customPids        = deserializeCustomPids(this)
        )
    }

    private fun deserializeCustomPids(json: JSONObject): List<CustomPid> {
        if (!json.has("customPids")) return emptyList()
        return try {
            val arr = json.getJSONArray("customPids")
            (0 until arr.length()).mapNotNull { i ->
                try {
                    val obj = arr.getJSONObject(i)
                    CustomPid(
                        id            = obj.getString("id"),
                        name          = obj.getString("name"),
                        header        = obj.optString("header", ""),
                        mode          = obj.optString("mode", "22"),
                        pid           = obj.getString("pid"),
                        bytesReturned = obj.optInt("bytesReturned", 2),
                        unit          = obj.optString("unit", ""),
                        formula       = obj.optString("formula", "A"),
                        signed        = obj.optBoolean("signed", false),
                        enabled       = obj.optBoolean("enabled", true)
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to deserialize custom PID at index $i", e)
                    null
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to deserialize customPids array", e)
            emptyList()
        }
    }
}
