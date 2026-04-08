package com.sj.obd2app.settings

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.sj.obd2app.obd.CustomPid
import com.sj.obd2app.obd.ManufacturerPidLibrary
import com.sj.obd2app.storage.AppDataDirectory
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

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
        private const val PROFILE_FILE_PREFIX = "vehicle_profile_"

        @Volatile
        private var INSTANCE: VehicleProfileRepository? = null

        fun getInstance(context: Context): VehicleProfileRepository =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: VehicleProfileRepository(context.applicationContext).also { INSTANCE = it }
            }
    }

    private val prefs get() = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    
    private val profilesDir = File(context.filesDir, "profiles").apply {
        if (!exists()) {
            val created = mkdirs()
            if (!created) {
                Log.e(TAG, "Failed to create profiles directory: ${absolutePath}")
            }
        }
    }

    // ── Read ──────────────────────────────────────────────────────────────────

    fun getAll(): List<VehicleProfile> {
        // Always use internal storage for performance
        return getAllFromInternalFiles()
    }

    private fun getAllFromInternalFiles(): List<VehicleProfile> {
        Log.d(TAG, "getAllFromInternalFiles: loading profiles from internal storage")
        val profileFiles = profilesDir.listFiles { file -> 
            file.isFile && file.name.startsWith(PROFILE_FILE_PREFIX) && file.name.endsWith(".json")
        }?.sortedBy { it.name } ?: emptyList()
        
        Log.d(TAG, "getAllFromInternalFiles: found ${profileFiles.size} profile files")
        
        val profiles = mutableListOf<VehicleProfile>()
        var errorCount = 0
        
        profileFiles.forEach { file ->
            try {
                val content = file.readText(Charsets.UTF_8)
                val profile = JSONObject(content).toProfile()
                profiles.add(profile)
                Log.d(TAG, "getAllFromInternalFiles: loaded profile '${profile.name}'")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load profile from ${file.name}", e)
                errorCount++
            }
        }
        
        if (errorCount > 0) {
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(context, "$errorCount profile(s) could not be loaded (corrupted files)", Toast.LENGTH_SHORT).show()
            }
        }
        
        Log.d(TAG, "getAllFromInternalFiles: returning ${profiles.size} profiles")
        return profiles
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
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(context, "$errorCount profile(s) could not be loaded (corrupted files)", Toast.LENGTH_SHORT).show()
            }
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
        android.util.Log.d("VehicleProfileRepository", "Saving profile: ${profile.name} (${profile.id}) with ${profile.customPids.size} custom PIDs")
        
        // Check if this is the first profile being created
        val existingProfiles = getAll()
        val isFirstProfile = existingProfiles.isEmpty()
        
        // Always use internal storage for performance
        android.util.Log.d("VehicleProfileRepository", "Using internal storage for profile save")
        saveToInternalFile(profile)
        
        android.util.Log.d("VehicleProfileRepository", "Profile save completed: ${profile.name}")
        
        // Automatically set the first profile as active
        if (isFirstProfile) {
            AppSettings.setActiveProfileId(context, profile.id)
        }
    }

    private fun saveToInternalFile(profile: VehicleProfile) {
        val fileName = "$PROFILE_FILE_PREFIX${profile.name}.json"
        val profileFile = File(profilesDir, fileName)
        
        try {
            profileFile.writeText(JSONObject().apply {
                put("id", profile.id)
                put("name", profile.name)
                put("fuelType", profile.fuelType.name)
                put("tankCapacityL", profile.tankCapacityL)
                put("fuelPricePerLitre", profile.fuelPricePerLitre)
                put("enginePowerBhp", profile.enginePowerBhp)
                put("vehicleMassKg", profile.vehicleMassKg)
                put("engineDisplacementCc", profile.engineDisplacementCc)
                put("volumetricEfficiencyPct", profile.volumetricEfficiencyPct)
                put("dieselCorrectionFactor", profile.dieselCorrectionFactor)
                put("availablePids", JSONObject(profile.availablePids))
                put("customPids", JSONArray().apply {
                    profile.customPids.forEach { pid ->
                        put(JSONObject().apply {
                            put("id", pid.id)
                            put("name", pid.name)
                            put("header", pid.header)
                            put("mode", pid.mode)
                            put("pid", pid.pid)
                            put("bytesReturned", pid.bytesReturned)
                            put("unit", pid.unit)
                            put("formula", pid.formula)
                            put("signed", pid.signed)
                            put("enabled", pid.enabled)
                        })
                    }
                })
                put("manufacturer", profile.manufacturer?.name ?: "")
            }.toString(), Charsets.UTF_8)
            
            android.util.Log.d("VehicleProfileRepository", "Internal file write completed for profile: ${profile.name}")
        } catch (e: Exception) {
            android.util.Log.e("VehicleProfileRepository", "Failed to save profile to internal file: ${profile.name}", e)
            Toast.makeText(context, "Failed to save profile: ${profile.name}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun saveToFile(profile: VehicleProfile) {
        val profileFile = AppDataDirectory.getProfileFileDocumentFile(context, profile.name)
        if (profileFile != null) {
            val json = profile.toJson()
            android.util.Log.d("VehicleProfileRepository", "Writing profile to external file: ${profileFile.uri}")
            // Use "wt" mode to truncate before writing — "w" alone does NOT truncate on Android 10+
            context.contentResolver.openOutputStream(profileFile.uri, "wt")?.use { output ->
                output.write(json.toString(2).toByteArray())
                android.util.Log.d("VehicleProfileRepository", "External file write completed for profile: ${profile.name}")
            }
        } else {
            android.util.Log.e("VehicleProfileRepository", "Failed to get external file for profile: ${profile.name}")
        }
    }

    private fun saveToPreferences(profile: VehicleProfile) {
        // Save to app-private file with new naming convention
        val profileFile = AppDataDirectory.getProfileFilePrivate(context, profile.name)
        val json = profile.toJson()
        android.util.Log.d("VehicleProfileRepository", "Writing profile to internal file: ${profileFile.absolutePath}")
        profileFile.writeText(json.toString(2))
        android.util.Log.d("VehicleProfileRepository", "Internal file write completed for profile: ${profile.name}")
    }

    fun delete(id: String) {
        // Find profile by ID to get its name
        val profile = getById(id)
        if (profile != null) {
            // Always use internal storage for performance
            deleteFromInternalFile(profile.name)
            
            if (AppSettings.getActiveProfileId(context) == id) {
                AppSettings.setActiveProfileId(context, getAll().firstOrNull()?.id)
            }
            // PIDs are now part of the profile JSON, so they're automatically deleted
        }
    }

    private fun deleteFromInternalFile(profileName: String) {
        val fileName = "$PROFILE_FILE_PREFIX$profileName.json"
        val profileFile = File(profilesDir, fileName)
        
        try {
            if (profileFile.exists()) {
                profileFile.delete()
                android.util.Log.d("VehicleProfileRepository", "Deleted internal profile file: $fileName")
            }
        } catch (e: Exception) {
            android.util.Log.e("VehicleProfileRepository", "Failed to delete internal profile file: $fileName", e)
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
        put("manufacturer", manufacturer?.name ?: "")
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
            customPids        = deserializeCustomPids(this),
            manufacturer      = optString("manufacturer", "").let { str ->
                if (str.isBlank()) null
                else try { ManufacturerPidLibrary.Manufacturer.valueOf(str) } catch (_: Exception) { null }
            }
        )
    }

    private fun deserializeCustomPids(json: JSONObject): List<CustomPid> {
        if (!json.has("customPids")) return emptyList()
        return try {
            val arr = json.getJSONArray("customPids")
            (0 until arr.length()).mapNotNull { i ->
                var obj: JSONObject? = null
                try {
                    obj = arr.getJSONObject(i)
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
                    Log.e(TAG, "Failed to deserialize custom PID at index $i. JSONObject: $obj", e)
                    null
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to deserialize customPids array", e)
            emptyList()
        }
    }
}
