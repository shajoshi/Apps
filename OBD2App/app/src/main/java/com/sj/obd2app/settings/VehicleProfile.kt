package com.sj.obd2app.settings

import com.sj.obd2app.obd.CustomPid
import com.sj.obd2app.obd.ManufacturerPidLibrary
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

enum class FuelType(
    val displayName: String,
    /**
     * ml of fuel per gram of air, used for MAF → fuel rate conversion.
     * Formula: 1000 / (stoichAFR × fuelDensityGperL)
     *
     * Fuel      | AFR   | Density (g/L) | mafMlPerGram
     * --------- | ----- | ------------- | ------------
     * Petrol    | 14.7  | 740           | 0.09195
     * E20       | 13.8  | 790           | 0.09166
     * Diesel    | 14.5  | 840           | 0.08210
     * CNG       | 17.2  | 423           | 0.13740
     */
    val mafMlPerGram: Double,
    /** g CO₂ per L/100km */
    val co2Factor: Double,
    /** Lower heating value (MJ/L) — used for thermodynamic power calculation */
    val energyDensityMJpL: Double
) {
    PETROL("Petrol", 0.09195, 23.1, 34.2),
    E20("E20 Petrol", 0.09166, 22.3, 27.4),
    DIESEL("Diesel", 0.08210, 26.4, 38.6),
    CNG("CNG",       0.13740, 16.0, 23.0)
}

/**
 * Vehicle-specific configuration profile.
 * Stored as JSON file with format: vehicle_profile_<name>.json
 */
data class VehicleProfile(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "My Vehicle",
    val fuelType: FuelType = FuelType.PETROL,
    val tankCapacityL: Float = 40f,
    val fuelPricePerLitre: Float = 0f,
    val enginePowerBhp: Float = 0f,
    /** Vehicle kerb mass in kg — used for acceleration-based power. 0 = not set */
    val vehicleMassKg: Float = 0f,
    /** Engine displacement in cc — used for Speed-Density fuel estimation. 0 = not set */
    val engineDisplacementCc: Int = 0,
    /** Volumetric efficiency % — used for Speed-Density fuel estimation. 85% typical for NA petrol */
    val volumetricEfficiencyPct: Float = 85f,
    /** 
     * Diesel fuel rate correction factor for MJD 1.3L calibration.
     * Accounts for real-world vs theoretical fuel consumption differences.
     * Default 0.25 (75% reduction) for typical diesel engines.
     * Only used when fuelType = DIESEL.
     */
    val dieselCorrectionFactor: Float = 0.25f,
    /** 
     * Available PIDs discovered for this vehicle's ECU.
     * Map of PID name -> last known value.
     * PIDs are constant per ECU, so they logically belong with the vehicle profile.
     */
    val availablePids: Map<String, String> = emptyMap(),
    /** User-defined extended/custom PIDs for manufacturer-specific diagnostics */
    val customPids: List<CustomPid> = emptyList(),
    /** Manufacturer profile for Mode 22 preset PIDs. Null = no manufacturer presets. */
    val manufacturer: ManufacturerPidLibrary.Manufacturer? = null
) {
    /**
     * Effective custom PIDs: user-defined PIDs merged with manufacturer presets.
     * Manufacturer presets are appended only if they are not already overridden by
     * a user-defined PID with the same id.
     */
    val effectiveCustomPids: List<CustomPid>
        get() {
            val userIds = customPids.map { it.id }.toSet()
            val presets = manufacturer?.let { ManufacturerPidLibrary.getPresets(it) } ?: emptyList()
            return customPids + presets.filter { it.id !in userIds }
        }

    /** Filesystem-safe name: spaces → underscores, non-alphanumeric stripped */
    val sanitisedName: String
        get() = name.trim()
            .replace(Regex("\\s+"), "_")
            .replace(Regex("[^A-Za-z0-9_\\-]"), "")
            .ifEmpty { "Vehicle" }
    
    companion object {
        fun fromJSON(json: JSONObject): VehicleProfile {
            val fuelTypeStr = json.optString("fuelType", "PETROL")
            val fuelType = try {
                FuelType.valueOf(fuelTypeStr)
            } catch (e: IllegalArgumentException) {
                FuelType.PETROL
            }
            
            val customPids = mutableListOf<CustomPid>()
            val customPidsArray = json.optJSONArray("customPids")
            if (customPidsArray != null) {
                for (i in 0 until customPidsArray.length()) {
                    val pidJson = customPidsArray.getJSONObject(i)
                    customPids.add(CustomPid(
                        name = pidJson.optString("name", ""),
                        header = pidJson.optString("header", ""),
                        mode = pidJson.optString("mode", "22"),
                        pid = pidJson.optString("pid", ""),
                        bytesReturned = pidJson.optInt("bytesReturned", 2),
                        unit = pidJson.optString("units", ""),
                        formula = pidJson.optString("formula", "A"),
                        signed = pidJson.optBoolean("signed", false),
                        enabled = pidJson.optBoolean("enabled", true)
                    ))
                }
            }
            
            val availablePids = mutableMapOf<String, String>()
            val availablePidsObj = json.optJSONObject("availablePids")
            if (availablePidsObj != null) {
                val keys = availablePidsObj.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    availablePids[key] = availablePidsObj.optString(key, "")
                }
            }
            
            return VehicleProfile(
                id = json.optString("id", UUID.randomUUID().toString()),
                name = json.optString("name", "My Vehicle"),
                fuelType = fuelType,
                tankCapacityL = json.optDouble("tankCapacityL", 40.0).toFloat(),
                fuelPricePerLitre = json.optDouble("fuelPricePerLitre", 0.0).toFloat(),
                enginePowerBhp = json.optDouble("enginePowerBhp", 0.0).toFloat(),
                vehicleMassKg = json.optDouble("vehicleMassKg", 0.0).toFloat(),
                engineDisplacementCc = json.optInt("engineDisplacementCc", 0),
                volumetricEfficiencyPct = json.optDouble("volumetricEfficiencyPct", 85.0).toFloat(),
                dieselCorrectionFactor = json.optDouble("dieselCorrectionFactor", 0.25).toFloat(),
                availablePids = availablePids,
                customPids = customPids,
                manufacturer = json.optString("manufacturer", "").let { str ->
                    if (str.isBlank()) null
                    else try { ManufacturerPidLibrary.Manufacturer.valueOf(str) } catch (_: Exception) { null }
                }
            )
        }
    }
}
