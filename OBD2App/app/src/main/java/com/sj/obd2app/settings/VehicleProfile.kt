package com.sj.obd2app.settings

import java.util.UUID

enum class FuelType(
    val displayName: String,
    /** L/g conversion factor for MAF-based fuel rate fallback */
    val mafLitreFactor: Double,
    /** g CO₂ per L/100km */
    val co2Factor: Double,
    /** Lower heating value (MJ/L) — used for thermodynamic power calculation */
    val energyDensityMJpL: Double
) {
    PETROL("Petrol", 0.0000746, 23.1, 34.2),
    E20("E20 Petrol", 0.0000751, 22.3, 27.4),
    DIESEL("Diesel", 0.0000594, 26.4, 38.6),
    CNG("CNG",    0.0000740, 16.0, 23.0)
}

/**
 * Vehicle-specific configuration profile.
 * Stored as JSON in SharedPreferences via [VehicleProfileRepository].
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
    val obdCommandDelayMs: Long? = null
) {
    /** Filesystem-safe name: spaces → underscores, non-alphanumeric stripped */
    val sanitisedName: String
        get() = name.trim()
            .replace(Regex("\\s+"), "_")
            .replace(Regex("[^A-Za-z0-9_\\-]"), "")
            .ifEmpty { "Vehicle" }
}
