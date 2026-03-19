package com.sj.obd2app.settings

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
    /** null = use global default from AppSettings */
    val obdPollingDelayMs: Long? = null,
    /** null = use global default from AppSettings */
    val obdCommandDelayMs: Long? = null,
    /** 
     * Available PIDs discovered for this vehicle's ECU.
     * Map of PID name -> last known value.
     * PIDs are constant per ECU, so they logically belong with the vehicle profile.
     */
    val availablePids: Map<String, String> = emptyMap()
) {
    /** Filesystem-safe name: spaces → underscores, non-alphanumeric stripped */
    val sanitisedName: String
        get() = name.trim()
            .replace(Regex("\\s+"), "_")
            .replace(Regex("[^A-Za-z0-9_\\-]"), "")
            .ifEmpty { "Vehicle" }
}
