package com.sj.obd2app.settings

import android.content.Context
import com.sj.obd2app.metrics.PidAvailabilityStore
import org.json.JSONArray
import org.json.JSONObject

/**
 * CRUD repository for [VehicleProfile] objects.
 * Profiles are serialised as a JSON array in SharedPreferences("obd2_prefs").
 */
class VehicleProfileRepository private constructor(private val context: Context) {

    companion object {
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
        val all = getAll().toMutableList()
        val idx = all.indexOfFirst { it.id == profile.id }
        if (idx >= 0) all[idx] = profile else all.add(profile)
        persist(all)
    }

    fun delete(id: String) {
        val all = getAll().filter { it.id != id }
        persist(all)
        if (AppSettings.getActiveProfileId(context) == id) {
            AppSettings.setActiveProfileId(context, all.firstOrNull()?.id)
        }
        PidAvailabilityStore.clear(context, id)
    }

    fun setActive(id: String) {
        AppSettings.setActiveProfileId(context, id)
    }

    // ── Serialisation ─────────────────────────────────────────────────────────

    private fun persist(profiles: List<VehicleProfile>) {
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
        if (obdPollingDelayMs != null) put("obdPollingDelayMs", obdPollingDelayMs)
        if (obdCommandDelayMs != null) put("obdCommandDelayMs", obdCommandDelayMs)
    }

    private fun JSONObject.toProfile(): VehicleProfile = VehicleProfile(
        id                = getString("id"),
        name              = getString("name"),
        fuelType          = try { FuelType.valueOf(getString("fuelType")) } catch (_: Exception) { FuelType.PETROL },
        tankCapacityL     = getDouble("tankCapacityL").toFloat(),
        fuelPricePerLitre = getDouble("fuelPricePerLitre").toFloat(),
        enginePowerBhp    = optDouble("enginePowerBhp", 0.0).toFloat(),
        vehicleMassKg     = optDouble("vehicleMassKg", 0.0).toFloat(),
        obdPollingDelayMs = if (has("obdPollingDelayMs")) getLong("obdPollingDelayMs") else null,
        obdCommandDelayMs = if (has("obdCommandDelayMs")) getLong("obdCommandDelayMs") else null
    )
}
