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
                        movingAverageWindow = 5,
                        stdDevSmoothMax = 2.0f,
                        rmsRoughMin = 4.0f,
                        peakRatioRoughMin = 0.55f,
                        stdDevRoughMin = 2.8f
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
                        movingAverageWindow = 7,
                        stdDevSmoothMax = 2.5f,
                        rmsRoughMin = 4.5f,
                        peakRatioRoughMin = 0.60f,
                        stdDevRoughMin = 3.0f
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
                        movingAverageWindow = 3,
                        stdDevSmoothMax = 1.8f,
                        rmsRoughMin = 3.5f,
                        peakRatioRoughMin = 0.50f,
                        stdDevRoughMin = 2.5f
                    )
                )
            )
        }
    }
}
