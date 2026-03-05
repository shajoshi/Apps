package com.tpmsapp.ble

import com.tpmsapp.model.TyreData
import com.tpmsapp.model.TyrePosition

/**
 * Parses raw BLE advertisement manufacturer data bytes into TyreData.
 *
 * Most generic aftermarket TPMS sensors broadcast data in BLE advertisement
 * manufacturer-specific data (type 0xFF). The byte layout used here follows
 * the widely documented format for common Chinese OEM TPMS sensors:
 *
 * Byte 0-1 : Manufacturer ID (little-endian, e.g. 0x0001)
 * Byte 2   : Sensor index / position hint (0=FL,1=FR,2=RL,3=RR)
 * Byte 3-4 : Pressure in units of 0.1 kPa (big-endian unsigned short)
 * Byte 5   : Temperature in °C offset by 50 (value - 50 = actual °C)
 * Byte 6   : Battery level 0–100
 * Byte 7   : Status/alarm flags
 *              bit 0 = low pressure alarm
 *              bit 1 = high pressure alarm
 *              bit 2 = high temperature alarm
 *
 * If your sensors use a different layout, adjust the offsets below.
 */
object TpmsPacketParser {

    private const val MIN_PACKET_LENGTH = 8

    @Suppress("UNUSED_PARAMETER")
fun parse(macAddress: String, manufacturerData: ByteArray, assignedPosition: TyrePosition? = null): TyreData? {
        if (manufacturerData.size < MIN_PACKET_LENGTH) return null

        return try {
            val pressureRaw = ((manufacturerData[3].toInt() and 0xFF) shl 8) or
                    (manufacturerData[4].toInt() and 0xFF)
            val pressureKpa = pressureRaw * 0.1f

            val temperatureCelsius = (manufacturerData[5].toInt() and 0xFF) - 50f

            val battery = manufacturerData[6].toInt() and 0xFF

            val flags = manufacturerData[7].toInt() and 0xFF
            val isLow  = (flags and 0x01) != 0
            val isHigh = (flags and 0x02) != 0
            val isTemp = (flags and 0x04) != 0

            val positionHint = manufacturerData[2].toInt() and 0xFF
            val position = assignedPosition ?: positionFromHint(positionHint)

            TyreData(
                position = position,
                pressureKpa = pressureKpa,
                temperatureCelsius = temperatureCelsius,
                batteryPercent = battery.coerceIn(0, 100),
                isAlarmLow = isLow,
                isAlarmHigh = isHigh,
                isAlarmTemp = isTemp
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun positionFromHint(hint: Int): TyrePosition = when (hint and 0x03) {
        0    -> TyrePosition.FRONT_LEFT
        1    -> TyrePosition.FRONT_RIGHT
        2    -> TyrePosition.REAR_LEFT
        else -> TyrePosition.REAR_RIGHT
    }
}
