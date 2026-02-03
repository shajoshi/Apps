package com.sj.gpsutil.data

import com.google.gson.Gson
import com.google.gson.JsonSyntaxException

data class VehicleProfile(
    val name: String,
    val calibration: CalibrationSettings,
    val createdAt: Long = System.currentTimeMillis(),
    val lastModified: Long = System.currentTimeMillis()
) {
    fun toJson(): String {
        return Gson().toJson(this)
    }

    companion object {
        fun fromJson(json: String): VehicleProfile? {
            return try {
                Gson().fromJson(json, VehicleProfile::class.java)
            } catch (e: JsonSyntaxException) {
                null
            }
        }

        fun getDefaultProfiles(): List<VehicleProfile> {
            return listOf(
                VehicleProfile(
                    name = "Motorcycle",
                    calibration = CalibrationSettings(
                        rmsSmoothMax = 1.0f,
                        peakThresholdZ = 1.5f,
                        symmetricBumpThreshold = 2.0f,
                        potholeDipThreshold = -2.5f,
                        bumpSpikeThreshold = 2.5f,
                        peakRatioSmoothMax = 0.05f,
                        movingAverageWindow = 5
                    )
                ),
                VehicleProfile(
                    name = "Car",
                    calibration = CalibrationSettings(
                        rmsSmoothMax = 1.5f,
                        peakThresholdZ = 2.0f,
                        symmetricBumpThreshold = 2.5f,
                        potholeDipThreshold = -3.0f,
                        bumpSpikeThreshold = 3.0f,
                        peakRatioSmoothMax = 0.08f,
                        movingAverageWindow = 7
                    )
                ),
                VehicleProfile(
                    name = "Bicycle",
                    calibration = CalibrationSettings(
                        rmsSmoothMax = 0.7f,
                        peakThresholdZ = 1.0f,
                        symmetricBumpThreshold = 1.5f,
                        potholeDipThreshold = -2.0f,
                        bumpSpikeThreshold = 2.0f,
                        peakRatioSmoothMax = 0.03f,
                        movingAverageWindow = 3
                    )
                )
            )
        }
    }
}
