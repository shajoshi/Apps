package com.sj.obd2app.obd

/**
 * Registry of all supported OBD-II Mode 01 PIDs.
 *
 * Each entry defines the PID hex string, human-readable name, unit, expected data byte count,
 * and a parse lambda that converts the raw response bytes into a formatted value string.
 *
 * Formulas are based on the SAE J1979 / ISO 15031-5 standard.
 */
object Obd2CommandRegistry {

    val commands: List<Obd2Command> = listOf(

        // ── Engine ──────────────────────────────────────────────

        Obd2Command(
            pid = "0104",
            name = "Calculated Engine Load",
            unit = "%",
            bytesReturned = 1,
            parse = { d -> String.format("%.1f", d[0] * 100.0 / 255.0) }
        ),

        Obd2Command(
            pid = "0105",
            name = "Coolant Temperature",
            unit = "°C",
            bytesReturned = 1,
            parse = { d -> "${d[0] - 40}" }
        ),

        Obd2Command(
            pid = "010C",
            name = "Engine RPM",
            unit = "rpm",
            bytesReturned = 2,
            parse = { d -> String.format("%.0f", (d[0] * 256.0 + d[1]) / 4.0) }
        ),

        Obd2Command(
            pid = "010D",
            name = "Vehicle Speed",
            unit = "km/h",
            bytesReturned = 1,
            parse = { d -> "${d[0]}" }
        ),

        Obd2Command(
            pid = "010E",
            name = "Timing Advance",
            unit = "° before TDC",
            bytesReturned = 1,
            parse = { d -> String.format("%.1f", d[0] / 2.0 - 64.0) }
        ),

        Obd2Command(
            pid = "010F",
            name = "Intake Air Temperature",
            unit = "°C",
            bytesReturned = 1,
            parse = { d -> "${d[0] - 40}" }
        ),

        // ── Fuel ────────────────────────────────────────────────

        Obd2Command(
            pid = "0106",
            name = "Short-term Fuel Trim (Bank 1)",
            unit = "%",
            bytesReturned = 1,
            parse = { d -> String.format("%.1f", d[0] * 100.0 / 128.0 - 100.0) }
        ),

        Obd2Command(
            pid = "0107",
            name = "Long-term Fuel Trim (Bank 1)",
            unit = "%",
            bytesReturned = 1,
            parse = { d -> String.format("%.1f", d[0] * 100.0 / 128.0 - 100.0) }
        ),

        Obd2Command(
            pid = "010A",
            name = "Fuel Pressure",
            unit = "kPa",
            bytesReturned = 1,
            parse = { d -> "${d[0] * 3}" }
        ),

        Obd2Command(
            pid = "012F",
            name = "Fuel Tank Level",
            unit = "%",
            bytesReturned = 1,
            parse = { d -> String.format("%.1f", d[0] * 100.0 / 255.0) }
        ),

        // ── Air / Intake ────────────────────────────────────────

        Obd2Command(
            pid = "010B",
            name = "Intake Manifold Pressure",
            unit = "kPa",
            bytesReturned = 1,
            parse = { d -> "${d[0]}" }
        ),

        Obd2Command(
            pid = "0110",
            name = "MAF Air Flow Rate",
            unit = "g/s",
            bytesReturned = 2,
            parse = { d -> String.format("%.2f", (d[0] * 256.0 + d[1]) / 100.0) }
        ),

        Obd2Command(
            pid = "0111",
            name = "Throttle Position",
            unit = "%",
            bytesReturned = 1,
            parse = { d -> String.format("%.1f", d[0] * 100.0 / 255.0) }
        ),

        Obd2Command(
            pid = "0133",
            name = "Barometric Pressure",
            unit = "kPa",
            bytesReturned = 1,
            parse = { d -> "${d[0]}" }
        ),

        // ── Emissions / O2 ─────────────────────────────────────

        Obd2Command(
            pid = "0114",
            name = "O2 Sensor Voltage (Bank 1, Sensor 1)",
            unit = "V",
            bytesReturned = 2,
            parse = { d -> String.format("%.3f", d[0] / 200.0) }
        ),

        Obd2Command(
            pid = "011F",
            name = "Run Time Since Engine Start",
            unit = "sec",
            bytesReturned = 2,
            parse = { d -> "${d[0] * 256 + d[1]}" }
        ),

        // ── Distance / Status ───────────────────────────────────

        Obd2Command(
            pid = "0121",
            name = "Distance with MIL On",
            unit = "km",
            bytesReturned = 2,
            parse = { d -> "${d[0] * 256 + d[1]}" }
        ),

        Obd2Command(
            pid = "0131",
            name = "Distance Since Codes Cleared",
            unit = "km",
            bytesReturned = 2,
            parse = { d -> "${d[0] * 256 + d[1]}" }
        ),

        // ── Voltage / Misc ─────────────────────────────────────

        Obd2Command(
            pid = "0142",
            name = "Control Module Voltage",
            unit = "V",
            bytesReturned = 2,
            parse = { d -> String.format("%.3f", (d[0] * 256.0 + d[1]) / 1000.0) }
        ),

        Obd2Command(
            pid = "0146",
            name = "Ambient Air Temperature",
            unit = "°C",
            bytesReturned = 1,
            parse = { d -> "${d[0] - 40}" }
        ),

        Obd2Command(
            pid = "015C",
            name = "Engine Oil Temperature",
            unit = "°C",
            bytesReturned = 1,
            parse = { d -> "${d[0] - 40}" }
        ),

        Obd2Command(
            pid = "015E",
            name = "Engine Fuel Rate",
            unit = "L/h",
            bytesReturned = 2,
            parse = { d -> String.format("%.2f", (d[0] * 256.0 + d[1]) / 20.0) }
        )
    )
}
