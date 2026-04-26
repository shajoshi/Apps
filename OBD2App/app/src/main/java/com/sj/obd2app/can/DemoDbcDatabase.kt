package com.sj.obd2app.can

/**
 * Built-in demo DBC used by [CanBusScanner] in mock mode when the profile has no DBC file loaded.
 *
 * Provides a handful of realistic vehicle signals across two messages so the full CAN pipeline
 * (decode → DataOrchestrator → UI) can be exercised without any external file.
 *
 * Signal layout uses 8-bit Intel (little-endian, unsigned) slots so [CanEncoder] / [CanDecoder]
 * can round-trip cleanly without any bit-packing edge cases.
 */
object DemoDbcDatabase {

    /** CAN ID used for engine metrics in the demo database. */
    const val ENGINE_MSG_ID = 0x7E8

    /** CAN ID used for chassis metrics in the demo database. */
    const val CHASSIS_MSG_ID = 0x7E9

    val database: DbcDatabase by lazy { build() }

    /** Returns a [CanProfile] whose [CanProfile.selectedSignals] cover all demo signals. */
    fun demoProfile(): CanProfile = CanProfile(
        name = "Demo Profile",
        dbcFileName = "<built-in demo>",
        selectedSignals = database.messages.flatMap { msg ->
            msg.signals.map { sig -> SignalRef(msg.id, sig.name) }
        }
    )

    private fun build(): DbcDatabase {
        val engineMsg = CanMessage(
            id = ENGINE_MSG_ID,
            extended = false,
            name = "Engine",
            dlc = 8,
            transmitter = "ECU",
            signals = listOf(
                signal(name = "RPM",          startBit = 0,  length = 16, factor = 0.25,  offset = 0.0,  min = 0.0,    max = 8000.0, unit = "rpm"),
                signal(name = "Coolant_Temp", startBit = 16, length = 8,  factor = 1.0,   offset = -40.0, min = -40.0, max = 215.0,  unit = "°C"),
                signal(name = "Throttle",     startBit = 24, length = 8,  factor = 0.392, offset = 0.0,  min = 0.0,    max = 100.0,  unit = "%"),
                signal(name = "MAP",          startBit = 32, length = 8,  factor = 1.0,   offset = 0.0,  min = 0.0,    max = 255.0,  unit = "kPa")
            )
        )
        val chassisMsg = CanMessage(
            id = CHASSIS_MSG_ID,
            extended = false,
            name = "Chassis",
            dlc = 8,
            transmitter = "BCM",
            signals = listOf(
                signal(name = "Speed",        startBit = 0,  length = 16, factor = 0.01,  offset = 0.0,  min = 0.0,    max = 300.0,  unit = "kph"),
                signal(name = "Brake_Press",  startBit = 16, length = 8,  factor = 1.0,   offset = 0.0,  min = 0.0,    max = 255.0,  unit = "bar"),
                signal(name = "SteerAngle",   startBit = 24, length = 16, factor = 0.1,   offset = -780.0, min = -780.0, max = 780.0, unit = "deg")
            )
        )
        return DbcDatabase(
            version = null,
            nodes = emptyList(),
            messages = listOf(engineMsg, chassisMsg),
            sourceFileName = "<built-in demo>"
        )
    }

    private fun signal(
        name: String, startBit: Int, length: Int,
        factor: Double, offset: Double, min: Double, max: Double, unit: String
    ) = CanSignal(
        name = name, startBit = startBit, length = length,
        littleEndian = true, signed = false,
        factor = factor, offset = offset, min = min, max = max,
        unit = unit, receivers = emptyList()
    )
}
