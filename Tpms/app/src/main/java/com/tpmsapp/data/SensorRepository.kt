package com.tpmsapp.data

import android.content.Context
import android.content.SharedPreferences
import com.tpmsapp.model.SensorConfig
import com.tpmsapp.model.TyrePosition

class SensorRepository(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("sensor_configs", Context.MODE_PRIVATE)

    fun loadSensorConfigs(): List<SensorConfig> {
        val configs = mutableListOf<SensorConfig>()
        TyrePosition.values().forEach { position ->
            val mac = prefs.getString(macKey(position), null)
            if (!mac.isNullOrBlank()) {
                val nickname = prefs.getString(nicknameKey(position), position.label) ?: position.label
                configs.add(SensorConfig(mac, position, nickname))
            }
        }
        return configs
    }

    fun saveSensorConfig(config: SensorConfig) {
        prefs.edit()
            .putString(macKey(config.position), config.macAddress)
            .putString(nicknameKey(config.position), config.nickname)
            .apply()
    }

    fun removeSensorConfig(position: TyrePosition) {
        prefs.edit()
            .remove(macKey(position))
            .remove(nicknameKey(position))
            .apply()
    }

    fun getSensorConfig(position: TyrePosition): SensorConfig? {
        val mac = prefs.getString(macKey(position), null) ?: return null
        val nickname = prefs.getString(nicknameKey(position), position.label) ?: position.label
        return SensorConfig(mac, position, nickname)
    }

    private fun macKey(position: TyrePosition) = "mac_${position.name}"
    private fun nicknameKey(position: TyrePosition) = "nickname_${position.name}"
}
