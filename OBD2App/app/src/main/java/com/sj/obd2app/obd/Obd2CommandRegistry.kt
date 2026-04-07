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

        // ── Status / Monitor ─────────────────────────────────────────────────

        Obd2Command(
            pid = "0101",
            name = "Monitor Status",
            unit = "",
            bytesReturned = 4,
            parse = { d ->
                val milOn = (d[0] and 0x80) != 0
                val dtcCount = d[0] and 0x7F
                if (milOn) "MIL ON, $dtcCount DTC(s)" else "MIL OFF, $dtcCount DTC(s)"
            }
        ),

        Obd2Command(
            pid = "0102",
            name = "Freeze DTC",
            unit = "",
            bytesReturned = 2,
            parse = { d ->
                val code = d[0] * 256 + d[1]
                if (code == 0) "No freeze DTC"
                else {
                    val prefix = when ((d[0] shr 6) and 0x03) {
                        0 -> "P"; 1 -> "C"; 2 -> "B"; else -> "U"
                    }
                    "$prefix${String.format("%04X", code and 0x3FFF)}"
                }
            }
        ),

        Obd2Command(
            pid = "0103",
            name = "Fuel System Status",
            unit = "",
            bytesReturned = 2,
            parse = { d ->
                fun decode(b: Int) = when (b) {
                    0x01 -> "Open loop"
                    0x02 -> "Closed loop"
                    0x04 -> "Open loop (load)"
                    0x08 -> "Open loop (failure)"
                    0x10 -> "Closed loop (fault)"
                    else -> "Unknown"
                }
                "B1: ${decode(d[0])}, B2: ${decode(d[1])}"
            }
        ),

        // ── Engine ───────────────────────────────────────────────────────────

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

        Obd2Command(
            pid = "011C",
            name = "OBD Standard",
            unit = "",
            bytesReturned = 1,
            parse = { d ->
                when (d[0]) {
                    1 -> "OBD-II (CARB)"; 2 -> "OBD (EPA)"; 3 -> "OBD + OBD-II"
                    4 -> "OBD-I"; 6 -> "EOBD"; 7 -> "EOBD + OBD-II"
                    9 -> "EOBD + OBD"; 11 -> "JOBD"; else -> "Other (${d[0]})"
                }
            }
        ),

        Obd2Command(
            pid = "011F",
            name = "Run Time Since Engine Start",
            unit = "sec",
            bytesReturned = 2,
            parse = { d -> "${d[0] * 256 + d[1]}" }
        ),

        Obd2Command(
            pid = "0141",
            name = "Monitor Status This Drive Cycle",
            unit = "",
            bytesReturned = 4,
            parse = { d ->
                val milOn = (d[0] and 0x80) != 0
                val dtcCount = d[0] and 0x7F
                if (milOn) "MIL ON, $dtcCount DTC(s)" else "MIL OFF, $dtcCount DTC(s)"
            }
        ),

        Obd2Command(
            pid = "014D",
            name = "Time with MIL On",
            unit = "min",
            bytesReturned = 2,
            parse = { d -> "${d[0] * 256 + d[1]}" }
        ),

        Obd2Command(
            pid = "014E",
            name = "Time Since Codes Cleared",
            unit = "min",
            bytesReturned = 2,
            parse = { d -> "${d[0] * 256 + d[1]}" }
        ),

        Obd2Command(
            pid = "014F",
            name = "Maximum Values (MAF, O2V, O2I, MAP)",
            unit = "",
            bytesReturned = 4,
            parse = { d ->
                "MAF=${d[0] * 10}g/s, O2V=${d[1]}V, O2I=${d[2]}mA, MAP=${d[3] * 10}kPa"
            }
        ),

        Obd2Command(
            pid = "0150",
            name = "Maximum MAF",
            unit = "g/s",
            bytesReturned = 4,
            parse = { d -> "${d[0] * 10}" }
        ),

        // ── Fuel ─────────────────────────────────────────────────────────────

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
            pid = "0108",
            name = "Short-term Fuel Trim (Bank 2)",
            unit = "%",
            bytesReturned = 1,
            parse = { d -> String.format("%.1f", d[0] * 100.0 / 128.0 - 100.0) }
        ),

        Obd2Command(
            pid = "0109",
            name = "Long-term Fuel Trim (Bank 2)",
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

        Obd2Command(
            pid = "0122",
            name = "Fuel Rail Pressure (vacuum ref.)",
            unit = "kPa",
            bytesReturned = 2,
            parse = { d -> String.format("%.2f", (d[0] * 256.0 + d[1]) * 0.079) }
        ),

        Obd2Command(
            pid = "0123",
            name = "Fuel Rail Pressure (direct inject)",
            unit = "kPa",
            bytesReturned = 2,
            parse = { d -> "${(d[0] * 256 + d[1]) * 10}" }
        ),

        Obd2Command(
            pid = "012C",
            name = "Commanded EGR",
            unit = "%",
            bytesReturned = 1,
            parse = { d -> String.format("%.1f", d[0] * 100.0 / 255.0) }
        ),

        Obd2Command(
            pid = "012D",
            name = "EGR Error",
            unit = "%",
            bytesReturned = 1,
            parse = { d -> String.format("%.1f", d[0] * 100.0 / 128.0 - 100.0) }
        ),

        Obd2Command(
            pid = "012E",
            name = "Commanded Evap Purge",
            unit = "%",
            bytesReturned = 1,
            parse = { d -> String.format("%.1f", d[0] * 100.0 / 255.0) }
        ),

        Obd2Command(
            pid = "0130",
            name = "Warm-ups Since Codes Cleared",
            unit = "",
            bytesReturned = 1,
            parse = { d -> "${d[0]}" }
        ),

        Obd2Command(
            pid = "0132",
            name = "Evap System Vapour Pressure",
            unit = "Pa",
            bytesReturned = 2,
            parse = { d ->
                val raw = d[0] * 256 + d[1]
                val signed = if (raw > 32767) raw - 65536 else raw
                String.format("%.2f", signed / 4.0)
            }
        ),

        Obd2Command(
            pid = "0153",
            name = "Absolute Evap System Vapour Pressure",
            unit = "kPa",
            bytesReturned = 2,
            parse = { d -> String.format("%.3f", (d[0] * 256.0 + d[1]) / 200.0) }
        ),

        Obd2Command(
            pid = "0154",
            name = "Evap System Vapour Pressure",
            unit = "Pa",
            bytesReturned = 2,
            parse = { d ->
                val raw = (d[0] * 256 + d[1]).toShort().toInt()
                "$raw"
            }
        ),

        Obd2Command(
            pid = "0155",
            name = "Short-term O2 Trim (Bank 1, Bank 3)",
            unit = "%",
            bytesReturned = 2,
            parse = { d ->
                val b1 = String.format("%.1f", d[0] * 100.0 / 128.0 - 100.0)
                val b3 = String.format("%.1f", d[1] * 100.0 / 128.0 - 100.0)
                "B1=$b1, B3=$b3"
            }
        ),

        Obd2Command(
            pid = "0156",
            name = "Long-term O2 Trim (Bank 1, Bank 3)",
            unit = "%",
            bytesReturned = 2,
            parse = { d ->
                val b1 = String.format("%.1f", d[0] * 100.0 / 128.0 - 100.0)
                val b3 = String.format("%.1f", d[1] * 100.0 / 128.0 - 100.0)
                "B1=$b1, B3=$b3"
            }
        ),

        Obd2Command(
            pid = "0157",
            name = "Short-term O2 Trim (Bank 2, Bank 4)",
            unit = "%",
            bytesReturned = 2,
            parse = { d ->
                val b2 = String.format("%.1f", d[0] * 100.0 / 128.0 - 100.0)
                val b4 = String.format("%.1f", d[1] * 100.0 / 128.0 - 100.0)
                "B2=$b2, B4=$b4"
            }
        ),

        Obd2Command(
            pid = "0158",
            name = "Long-term O2 Trim (Bank 2, Bank 4)",
            unit = "%",
            bytesReturned = 2,
            parse = { d ->
                val b2 = String.format("%.1f", d[0] * 100.0 / 128.0 - 100.0)
                val b4 = String.format("%.1f", d[1] * 100.0 / 128.0 - 100.0)
                "B2=$b2, B4=$b4"
            }
        ),

        Obd2Command(
            pid = "0159",
            name = "Fuel Rail Absolute Pressure",
            unit = "kPa",
            bytesReturned = 2,
            parse = { d -> "${(d[0] * 256 + d[1]) * 10}" }
        ),

        Obd2Command(
            pid = "015E",
            name = "Engine Fuel Rate",
            unit = "L/h",
            bytesReturned = 2,
            parse = { d -> String.format("%.2f", (d[0] * 256.0 + d[1]) / 20.0) }
        ),

        Obd2Command(
            pid = "015D",
            name = "Fuel Injection Timing",
            unit = "°",
            bytesReturned = 2,
            parse = { d -> String.format("%.2f", (d[0] * 256.0 + d[1]) / 128.0 - 210.0) }
        ),

        Obd2Command(
            pid = "0151",
            name = "Fuel Type",
            unit = "",
            bytesReturned = 1,
            parse = { d ->
                when (d[0]) {
                    1 -> "Petrol"; 2 -> "Methanol"; 3 -> "Ethanol"; 4 -> "Diesel"
                    5 -> "LPG"; 6 -> "CNG"; 7 -> "Propane"; 8 -> "Electric"
                    9 -> "Bifuel Petrol"; 10 -> "Bifuel Methanol"
                    else -> "Unknown (${d[0]})"
                }
            }
        ),

        Obd2Command(
            pid = "0152",
            name = "Ethanol Fuel Percentage",
            unit = "%",
            bytesReturned = 1,
            parse = { d -> String.format("%.1f", d[0] * 100.0 / 255.0) }
        ),

        // ── Air / Intake ─────────────────────────────────────────────────────

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
            pid = "0112",
            name = "Secondary Air Status",
            unit = "",
            bytesReturned = 1,
            parse = { d ->
                when (d[0]) {
                    0x01 -> "Upstream"; 0x02 -> "Downstream"
                    0x04 -> "Outside/atmosphere"; 0x08 -> "Pump commanded off"
                    else -> "Unknown"
                }
            }
        ),

        Obd2Command(
            pid = "0113",
            name = "O2 Sensors Present",
            unit = "",
            bytesReturned = 1,
            parse = { d ->
                val bits = (0..7).filter { (d[0] shr it) and 1 == 1 }
                if (bits.isEmpty()) "None" else bits.joinToString(", ") { "S${it + 1}" }
            }
        ),

        Obd2Command(
            pid = "011D",
            name = "O2 Sensors Present (wide-range)",
            unit = "",
            bytesReturned = 1,
            parse = { d ->
                val bits = (0..7).filter { (d[0] shr it) and 1 == 1 }
                if (bits.isEmpty()) "None" else bits.joinToString(", ") { "S${it + 1}" }
            }
        ),

        Obd2Command(
            pid = "011E",
            name = "Auxiliary Input Status",
            unit = "",
            bytesReturned = 1,
            parse = { d -> if ((d[0] and 0x01) != 0) "PTO Active" else "PTO Inactive" }
        ),

        Obd2Command(
            pid = "0133",
            name = "Barometric Pressure",
            unit = "kPa",
            bytesReturned = 1,
            parse = { d -> "${d[0]}" }
        ),

        // ── O2 Sensors ───────────────────────────────────────────────────────

        Obd2Command(
            pid = "0114",
            name = "O2 Sensor Voltage (Bank 1, Sensor 1)",
            unit = "V",
            bytesReturned = 2,
            parse = { d -> String.format("%.3f", d[0] / 200.0) }
        ),

        Obd2Command(
            pid = "0115",
            name = "O2 Sensor Voltage (Bank 1, Sensor 2)",
            unit = "V",
            bytesReturned = 2,
            parse = { d -> String.format("%.3f", d[0] / 200.0) }
        ),

        Obd2Command(
            pid = "0116",
            name = "O2 Sensor Voltage (Bank 2, Sensor 1)",
            unit = "V",
            bytesReturned = 2,
            parse = { d -> String.format("%.3f", d[0] / 200.0) }
        ),

        Obd2Command(
            pid = "0117",
            name = "O2 Sensor Voltage (Bank 2, Sensor 2)",
            unit = "V",
            bytesReturned = 2,
            parse = { d -> String.format("%.3f", d[0] / 200.0) }
        ),

        Obd2Command(
            pid = "0118",
            name = "O2 Sensor Voltage (Bank 2, Sensor 3)",
            unit = "V",
            bytesReturned = 2,
            parse = { d -> String.format("%.3f", d[0] / 200.0) }
        ),

        Obd2Command(
            pid = "0119",
            name = "O2 Sensor Voltage (Bank 2, Sensor 4)",
            unit = "V",
            bytesReturned = 2,
            parse = { d -> String.format("%.3f", d[0] / 200.0) }
        ),

        Obd2Command(
            pid = "011A",
            name = "O2 Sensor Voltage (Bank 3, Sensor 1)",
            unit = "V",
            bytesReturned = 2,
            parse = { d -> String.format("%.3f", d[0] / 200.0) }
        ),

        Obd2Command(
            pid = "011B",
            name = "O2 Sensor Voltage (Bank 3, Sensor 2)",
            unit = "V",
            bytesReturned = 2,
            parse = { d -> String.format("%.3f", d[0] / 200.0) }
        ),

        Obd2Command(
            pid = "0124",
            name = "O2 Wide-range (Bank 1, Sensor 1)",
            unit = "V",
            bytesReturned = 4,
            parse = { d -> String.format("%.3f", (d[2] * 256.0 + d[3]) * 8.0 / 65536.0) }
        ),

        Obd2Command(
            pid = "0125",
            name = "O2 Wide-range (Bank 1, Sensor 2)",
            unit = "V",
            bytesReturned = 4,
            parse = { d -> String.format("%.3f", (d[2] * 256.0 + d[3]) * 8.0 / 65536.0) }
        ),

        Obd2Command(
            pid = "0126",
            name = "O2 Wide-range (Bank 2, Sensor 1)",
            unit = "V",
            bytesReturned = 4,
            parse = { d -> String.format("%.3f", (d[2] * 256.0 + d[3]) * 8.0 / 65536.0) }
        ),

        Obd2Command(
            pid = "0127",
            name = "O2 Wide-range (Bank 2, Sensor 2)",
            unit = "V",
            bytesReturned = 4,
            parse = { d -> String.format("%.3f", (d[2] * 256.0 + d[3]) * 8.0 / 65536.0) }
        ),

        Obd2Command(
            pid = "0128",
            name = "O2 Wide-range (Bank 2, Sensor 3)",
            unit = "V",
            bytesReturned = 4,
            parse = { d -> String.format("%.3f", (d[2] * 256.0 + d[3]) * 8.0 / 65536.0) }
        ),

        Obd2Command(
            pid = "0129",
            name = "O2 Wide-range (Bank 2, Sensor 4)",
            unit = "V",
            bytesReturned = 4,
            parse = { d -> String.format("%.3f", (d[2] * 256.0 + d[3]) * 8.0 / 65536.0) }
        ),

        Obd2Command(
            pid = "012A",
            name = "O2 Wide-range (Bank 3, Sensor 1)",
            unit = "V",
            bytesReturned = 4,
            parse = { d -> String.format("%.3f", (d[2] * 256.0 + d[3]) * 8.0 / 65536.0) }
        ),

        Obd2Command(
            pid = "012B",
            name = "O2 Wide-range (Bank 3, Sensor 2)",
            unit = "V",
            bytesReturned = 4,
            parse = { d -> String.format("%.3f", (d[2] * 256.0 + d[3]) * 8.0 / 65536.0) }
        ),

        // ── O2 Sensor Current ──────────────────────────────────────────────────

        Obd2Command(
            pid = "0134",
            name = "O2 Sensor Current (Bank 1, Sensor 1)",
            unit = "mA",
            bytesReturned = 4,
            parse = { d -> String.format("%.3f", ((d[2] * 256.0 + d[3]) / 256.0) - 128.0) }
        ),

        Obd2Command(
            pid = "0135",
            name = "O2 Sensor Current (Bank 1, Sensor 2)",
            unit = "mA",
            bytesReturned = 4,
            parse = { d -> String.format("%.3f", ((d[2] * 256.0 + d[3]) / 256.0) - 128.0) }
        ),

        Obd2Command(
            pid = "0136",
            name = "O2 Sensor Current (Bank 2, Sensor 1)",
            unit = "mA",
            bytesReturned = 4,
            parse = { d -> String.format("%.3f", ((d[2] * 256.0 + d[3]) / 256.0) - 128.0) }
        ),

        Obd2Command(
            pid = "0137",
            name = "O2 Sensor Current (Bank 2, Sensor 2)",
            unit = "mA",
            bytesReturned = 4,
            parse = { d -> String.format("%.3f", ((d[2] * 256.0 + d[3]) / 256.0) - 128.0) }
        ),

        Obd2Command(
            pid = "0138",
            name = "O2 Sensor Current (Bank 2, Sensor 3)",
            unit = "mA",
            bytesReturned = 4,
            parse = { d -> String.format("%.3f", ((d[2] * 256.0 + d[3]) / 256.0) - 128.0) }
        ),

        Obd2Command(
            pid = "0139",
            name = "O2 Sensor Current (Bank 2, Sensor 4)",
            unit = "mA",
            bytesReturned = 4,
            parse = { d -> String.format("%.3f", ((d[2] * 256.0 + d[3]) / 256.0) - 128.0) }
        ),

        Obd2Command(
            pid = "013A",
            name = "O2 Sensor Current (Bank 3, Sensor 1)",
            unit = "mA",
            bytesReturned = 4,
            parse = { d -> String.format("%.3f", ((d[2] * 256.0 + d[3]) / 256.0) - 128.0) }
        ),

        Obd2Command(
            pid = "013B",
            name = "O2 Sensor Current (Bank 3, Sensor 2)",
            unit = "mA",
            bytesReturned = 4,
            parse = { d -> String.format("%.3f", ((d[2] * 256.0 + d[3]) / 256.0) - 128.0) }
        ),

        // ── Distance / Status ────────────────────────────────────────────────

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

        // ── Catalyst Temperatures ────────────────────────────────────────────

        Obd2Command(
            pid = "013C",
            name = "Catalyst Temperature (Bank 1, Sensor 1)",
            unit = "°C",
            bytesReturned = 2,
            parse = { d -> String.format("%.1f", (d[0] * 256.0 + d[1]) / 10.0 - 40.0) }
        ),

        Obd2Command(
            pid = "013D",
            name = "Catalyst Temperature (Bank 2, Sensor 1)",
            unit = "°C",
            bytesReturned = 2,
            parse = { d -> String.format("%.1f", (d[0] * 256.0 + d[1]) / 10.0 - 40.0) }
        ),

        Obd2Command(
            pid = "013E",
            name = "Catalyst Temperature (Bank 1, Sensor 2)",
            unit = "°C",
            bytesReturned = 2,
            parse = { d -> String.format("%.1f", (d[0] * 256.0 + d[1]) / 10.0 - 40.0) }
        ),

        Obd2Command(
            pid = "013F",
            name = "Catalyst Temperature (Bank 2, Sensor 2)",
            unit = "°C",
            bytesReturned = 2,
            parse = { d -> String.format("%.1f", (d[0] * 256.0 + d[1]) / 10.0 - 40.0) }
        ),

        // ── Voltage / Load / Throttle ─────────────────────────────────────────

        Obd2Command(
            pid = "0142",
            name = "Control Module Voltage",
            unit = "V",
            bytesReturned = 2,
            parse = { d -> String.format("%.3f", (d[0] * 256.0 + d[1]) / 1000.0) }
        ),

        Obd2Command(
            pid = "0143",
            name = "Absolute Load Value",
            unit = "%",
            bytesReturned = 2,
            parse = { d -> String.format("%.1f", (d[0] * 256.0 + d[1]) * 100.0 / 255.0) }
        ),

        Obd2Command(
            pid = "0144",
            name = "Commanded Air-Fuel Equivalence Ratio",
            unit = "",
            bytesReturned = 2,
            parse = { d -> String.format("%.4f", (d[0] * 256.0 + d[1]) / 32768.0) }
        ),

        Obd2Command(
            pid = "0145",
            name = "Relative Throttle Position",
            unit = "%",
            bytesReturned = 1,
            parse = { d -> String.format("%.1f", d[0] * 100.0 / 255.0) }
        ),

        Obd2Command(
            pid = "0146",
            name = "Ambient Air Temperature",
            unit = "°C",
            bytesReturned = 1,
            parse = { d -> "${d[0] - 40}" }
        ),

        Obd2Command(
            pid = "0147",
            name = "Throttle Position B",
            unit = "%",
            bytesReturned = 1,
            parse = { d -> String.format("%.1f", d[0] * 100.0 / 255.0) }
        ),

        Obd2Command(
            pid = "0148",
            name = "Throttle Position C",
            unit = "%",
            bytesReturned = 1,
            parse = { d -> String.format("%.1f", d[0] * 100.0 / 255.0) }
        ),

        Obd2Command(
            pid = "0149",
            name = "Accelerator Pedal Position D",
            unit = "%",
            bytesReturned = 1,
            parse = { d -> String.format("%.1f", d[0] * 100.0 / 255.0) }
        ),

        Obd2Command(
            pid = "014A",
            name = "Accelerator Pedal Position E",
            unit = "%",
            bytesReturned = 1,
            parse = { d -> String.format("%.1f", d[0] * 100.0 / 255.0) }
        ),

        Obd2Command(
            pid = "014B",
            name = "Accelerator Pedal Position F",
            unit = "%",
            bytesReturned = 1,
            parse = { d -> String.format("%.1f", d[0] * 100.0 / 255.0) }
        ),

        Obd2Command(
            pid = "014C",
            name = "Commanded Throttle Actuator",
            unit = "%",
            bytesReturned = 1,
            parse = { d -> String.format("%.1f", d[0] * 100.0 / 255.0) }
        ),

        // ── Temperature / Misc ────────────────────────────────────────────────

        Obd2Command(
            pid = "015A",
            name = "Relative Accelerator Pedal Position",
            unit = "%",
            bytesReturned = 1,
            parse = { d -> String.format("%.1f", d[0] * 100.0 / 255.0) }
        ),

        Obd2Command(
            pid = "015B",
            name = "Hybrid Battery Pack Remaining Life",
            unit = "%",
            bytesReturned = 1,
            parse = { d -> String.format("%.1f", d[0] * 100.0 / 255.0) }
        ),

        Obd2Command(
            pid = "015C",
            name = "Engine Oil Temperature",
            unit = "°C",
            bytesReturned = 1,
            parse = { d -> "${d[0] - 40}" }
        ),

        Obd2Command(
            pid = "015F",
            name = "Emission Requirements",
            unit = "",
            bytesReturned = 1,
            parse = { d ->
                when (d[0]) {
                    0x00 -> "Not available"; 0x01 -> "OBD-II"; 0x02 -> "OBD"
                    0x03 -> "OBD + OBD-II"; 0x04 -> "OBD-I"
                    else -> "Other (${d[0]})"
                }
            }
        ),

        // ── Torque ────────────────────────────────────────────────────────────

        Obd2Command(
            pid = "0161",
            name = "Driver Demand Engine Torque",
            unit = "%",
            bytesReturned = 1,
            parse = { d -> "${d[0] - 125}" }
        ),

        Obd2Command(
            pid = "0162",
            name = "Actual Engine Torque",
            unit = "%",
            bytesReturned = 1,
            parse = { d -> "${d[0] - 125}" }
        ),

        Obd2Command(
            pid = "0163",
            name = "Engine Reference Torque",
            unit = "Nm",
            bytesReturned = 2,
            parse = { d -> "${d[0] * 256 + d[1]}" }
        ),

        // ── Extended Standard PIDs (0x64–0x7F) ─────────────────────────────

        Obd2Command(
            pid = "0164",
            name = "Engine Percent Torque Data",
            unit = "%",
            bytesReturned = 5,
            parse = { d ->
                "Idle=${d[0] - 125}, EP1=${d[1] - 125}, EP2=${d[2] - 125}, EP3=${d[3] - 125}, EP4=${d[4] - 125}"
            }
        ),

        Obd2Command(
            pid = "0166",
            name = "MAF Sensor (multi)",
            unit = "g/s",
            bytesReturned = 5,
            parse = { d ->
                val s1 = String.format("%.2f", (d[1] * 256.0 + d[2]) / 32.0)
                val s2 = String.format("%.2f", (d[3] * 256.0 + d[4]) / 32.0)
                "S1=$s1, S2=$s2"
            }
        ),

        Obd2Command(
            pid = "0167",
            name = "Engine Coolant Temp (multi)",
            unit = "°C",
            bytesReturned = 3,
            parse = { d ->
                val s1 = d[1] - 40
                val s2 = d[2] - 40
                "S1=$s1, S2=$s2"
            }
        ),

        Obd2Command(
            pid = "0168",
            name = "Intake Air Temp (multi)",
            unit = "°C",
            bytesReturned = 7,
            parse = { d ->
                val s1 = d[1] - 40
                val s2 = d[2] - 40
                "S1=$s1, S2=$s2"
            }
        ),

        Obd2Command(
            pid = "016B",
            name = "EGR Temperature",
            unit = "°C",
            bytesReturned = 5,
            parse = { d ->
                val s1 = String.format("%.1f", (d[1] * 256.0 + d[2]) / 10.0 - 40.0)
                val s2 = String.format("%.1f", (d[3] * 256.0 + d[4]) / 10.0 - 40.0)
                "S1=$s1, S2=$s2"
            }
        ),

        Obd2Command(
            pid = "016D",
            name = "Fuel Pressure Control",
            unit = "kPa",
            bytesReturned = 6,
            parse = { d ->
                val target = (d[1] * 256 + d[2]) * 10
                val actual = (d[3] * 256 + d[4]) * 10
                "Target=$target, Actual=$actual"
            }
        ),

        Obd2Command(
            pid = "016E",
            name = "Injection Pressure Control",
            unit = "kPa",
            bytesReturned = 5,
            parse = { d ->
                val target = (d[1] * 256 + d[2]) * 10
                val actual = (d[3] * 256 + d[4]) * 10
                "Target=$target, Actual=$actual"
            }
        ),

        Obd2Command(
            pid = "016F",
            name = "Turbo Compressor Inlet Pressure",
            unit = "kPa",
            bytesReturned = 3,
            parse = { d ->
                val s1 = d[1]
                val s2 = d[2]
                "S1=$s1, S2=$s2"
            }
        ),

        Obd2Command(
            pid = "0170",
            name = "Boost Pressure Control",
            unit = "kPa",
            bytesReturned = 9,
            parse = { d ->
                val targetA = String.format("%.1f", (d[1] * 256.0 + d[2]) * 0.03125)
                val actualA = String.format("%.1f", (d[3] * 256.0 + d[4]) * 0.03125)
                "Target=$targetA, Actual=$actualA"
            }
        ),

        Obd2Command(
            pid = "0173",
            name = "Exhaust Pressure",
            unit = "kPa",
            bytesReturned = 5,
            parse = { d ->
                val s1 = String.format("%.2f", (d[1] * 256.0 + d[2]) * 0.01)
                val s2 = String.format("%.2f", (d[3] * 256.0 + d[4]) * 0.01)
                "S1=$s1, S2=$s2"
            }
        ),

        Obd2Command(
            pid = "0174",
            name = "Turbocharger RPM",
            unit = "rpm",
            bytesReturned = 5,
            parse = { d ->
                val s1 = (d[1] * 256 + d[2]) * 10
                val s2 = (d[3] * 256 + d[4]) * 10
                "S1=$s1, S2=$s2"
            }
        ),

        Obd2Command(
            pid = "0175",
            name = "Turbocharger Temp A",
            unit = "°C",
            bytesReturned = 7,
            parse = { d ->
                val s1 = String.format("%.1f", (d[1] * 256.0 + d[2]) / 10.0 - 40.0)
                val s2 = String.format("%.1f", (d[3] * 256.0 + d[4]) / 10.0 - 40.0)
                "S1=$s1, S2=$s2"
            }
        ),

        Obd2Command(
            pid = "0176",
            name = "Turbocharger Temp B",
            unit = "°C",
            bytesReturned = 7,
            parse = { d ->
                val s1 = String.format("%.1f", (d[1] * 256.0 + d[2]) / 10.0 - 40.0)
                val s2 = String.format("%.1f", (d[3] * 256.0 + d[4]) / 10.0 - 40.0)
                "S1=$s1, S2=$s2"
            }
        ),

        Obd2Command(
            pid = "0178",
            name = "EGT Bank 1",
            unit = "°C",
            bytesReturned = 9,
            parse = { d ->
                val s1 = String.format("%.1f", (d[1] * 256.0 + d[2]) / 10.0 - 40.0)
                val s2 = String.format("%.1f", (d[3] * 256.0 + d[4]) / 10.0 - 40.0)
                val s3 = String.format("%.1f", (d[5] * 256.0 + d[6]) / 10.0 - 40.0)
                val s4 = String.format("%.1f", (d[7] * 256.0 + d[8]) / 10.0 - 40.0)
                "S1=$s1, S2=$s2, S3=$s3, S4=$s4"
            }
        ),

        Obd2Command(
            pid = "0179",
            name = "EGT Bank 2",
            unit = "°C",
            bytesReturned = 9,
            parse = { d ->
                val s1 = String.format("%.1f", (d[1] * 256.0 + d[2]) / 10.0 - 40.0)
                val s2 = String.format("%.1f", (d[3] * 256.0 + d[4]) / 10.0 - 40.0)
                val s3 = String.format("%.1f", (d[5] * 256.0 + d[6]) / 10.0 - 40.0)
                val s4 = String.format("%.1f", (d[7] * 256.0 + d[8]) / 10.0 - 40.0)
                "S1=$s1, S2=$s2, S3=$s3, S4=$s4"
            }
        ),

        Obd2Command(
            pid = "017A",
            name = "DPF Differential Pressure",
            unit = "kPa",
            bytesReturned = 3,
            parse = { d ->
                val s1 = String.format("%.2f", (d[1] * 256.0 + d[2]) * 0.01)
                "S1=$s1"
            }
        ),

        Obd2Command(
            pid = "017B",
            name = "DPF Temperature",
            unit = "°C",
            bytesReturned = 3,
            parse = { d ->
                val s1 = String.format("%.1f", (d[1] * 256.0 + d[2]) / 10.0 - 40.0)
                "S1=$s1"
            }
        ),

        Obd2Command(
            pid = "017C",
            name = "PM Filter Temp",
            unit = "°C",
            bytesReturned = 9,
            parse = { d ->
                val s1 = String.format("%.1f", (d[1] * 256.0 + d[2]) / 10.0 - 40.0)
                val s2 = String.format("%.1f", (d[3] * 256.0 + d[4]) / 10.0 - 40.0)
                "S1=$s1, S2=$s2"
            }
        )
    )
}
