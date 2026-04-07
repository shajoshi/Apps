package com.sj.obd2app.obd

/**
 * Preset Mode 22 (Enhanced Diagnostics) PID libraries for specific manufacturers / ECU families.
 *
 * Each manufacturer's list contains [CustomPid] entries ready to be merged into a
 * [com.sj.obd2app.settings.VehicleProfile.customPids] list.  The polling infrastructure
 * in [BluetoothObd2Service.pollCustomPids] handles header switching, response parsing,
 * and formula evaluation automatically.
 *
 * Supported manufacturers:
 *  - **Suzuki** — motorcycle ECUs (Ronin, GSX-R, V-Strom, Hayabusa, etc.)
 *  - **Fiat / Bosch** — MED17 / EDC16 / EDC17 ECU families
 *  - **Ford** — PCM / TCM enhanced diagnostics
 *  - **Jaguar (JLR)** — shared Ford platform + JLR-specific modules
 *  - **Bosch Generic** — common Bosch ME7 / MED17 / EDC enhanced PIDs
 */
object ManufacturerPidLibrary {

    /** All supported manufacturer keys */
    enum class Manufacturer(val displayName: String) {
        SUZUKI("Suzuki"),
        FIAT("Fiat / Bosch"),
        FORD("Ford"),
        JAGUAR("Jaguar (JLR)"),
        BOSCH_GENERIC("Bosch Generic")
    }

    /** Return the preset [CustomPid] list for the given [manufacturer]. */
    fun getPresets(manufacturer: Manufacturer): List<CustomPid> = when (manufacturer) {
        Manufacturer.SUZUKI -> suzukiPids
        Manufacturer.FIAT -> fiatPids
        Manufacturer.FORD -> fordPids
        Manufacturer.JAGUAR -> jaguarPids
        Manufacturer.BOSCH_GENERIC -> boschGenericPids
    }

    /** Return *all* presets across every manufacturer, tagged with unique IDs. */
    fun getAllPresets(): Map<Manufacturer, List<CustomPid>> =
        Manufacturer.entries.associateWith { getPresets(it) }

    // ── Suzuki (motorcycle ECUs) ────────────────────────────────────────────

    private val suzukiPids: List<CustomPid> = listOf(
        CustomPid(
            id = "SUZUKI_GEAR",
            name = "Gear Position",
            header = "",
            mode = "22",
            pid = "F40D",
            bytesReturned = 1,
            unit = "",
            formula = "A",
            signed = false,
            enabled = true
        ),
        CustomPid(
            id = "SUZUKI_TILT",
            name = "Tilt / Lean Angle",
            header = "",
            mode = "22",
            pid = "F410",
            bytesReturned = 2,
            unit = "°",
            formula = "((A*256)+B)/100",
            signed = true,
            enabled = true
        ),
        CustomPid(
            id = "SUZUKI_SIDESTAND",
            name = "Side Stand Switch",
            header = "",
            mode = "22",
            pid = "F411",
            bytesReturned = 1,
            unit = "",
            formula = "A",
            signed = false,
            enabled = true
        ),
        CustomPid(
            id = "SUZUKI_CLUTCH",
            name = "Clutch Switch",
            header = "",
            mode = "22",
            pid = "F412",
            bytesReturned = 1,
            unit = "",
            formula = "A",
            signed = false,
            enabled = true
        ),
        CustomPid(
            id = "SUZUKI_SUBTHROTTLE",
            name = "Sub-Throttle Position",
            header = "",
            mode = "22",
            pid = "F413",
            bytesReturned = 2,
            unit = "%",
            formula = "((A*256)+B)*100/1023",
            signed = false,
            enabled = true
        ),
        CustomPid(
            id = "SUZUKI_ISC",
            name = "ISC Duty (Idle Speed Control)",
            header = "",
            mode = "22",
            pid = "F414",
            bytesReturned = 2,
            unit = "%",
            formula = "((A*256)+B)*100/255",
            signed = false,
            enabled = true
        ),
        CustomPid(
            id = "SUZUKI_INJ2",
            name = "Secondary Injector Pulse Width",
            header = "",
            mode = "22",
            pid = "F415",
            bytesReturned = 2,
            unit = "ms",
            formula = "((A*256)+B)/100",
            signed = false,
            enabled = true
        ),
        CustomPid(
            id = "SUZUKI_IAP",
            name = "Intake Air Pressure (ECU)",
            header = "",
            mode = "22",
            pid = "F416",
            bytesReturned = 2,
            unit = "kPa",
            formula = "((A*256)+B)/100",
            signed = false,
            enabled = true
        ),
        CustomPid(
            id = "SUZUKI_ATMOS",
            name = "Atmospheric Pressure (ECU)",
            header = "",
            mode = "22",
            pid = "F417",
            bytesReturned = 2,
            unit = "kPa",
            formula = "((A*256)+B)/100",
            signed = false,
            enabled = true
        ),
        CustomPid(
            id = "SUZUKI_BATT",
            name = "Battery Voltage (ECU)",
            header = "",
            mode = "22",
            pid = "F418",
            bytesReturned = 2,
            unit = "V",
            formula = "((A*256)+B)/1000",
            signed = false,
            enabled = true
        ),
        CustomPid(
            id = "SUZUKI_TIPOVER",
            name = "Tip-over Sensor",
            header = "",
            mode = "22",
            pid = "F40E",
            bytesReturned = 1,
            unit = "",
            formula = "A",
            signed = false,
            enabled = true
        )
    )

    // ── Fiat / Bosch (MED17, EDC16, EDC17) ─────────────────────────────────

    private val fiatPids: List<CustomPid> = listOf(
        CustomPid(
            id = "FIAT_BOOST_ACT",
            name = "Turbo Boost Actual",
            header = "",
            mode = "22",
            pid = "1001",
            bytesReturned = 2,
            unit = "kPa",
            formula = "((A*256)+B)*0.1",
            signed = false,
            enabled = true
        ),
        CustomPid(
            id = "FIAT_BOOST_TGT",
            name = "Turbo Boost Target",
            header = "",
            mode = "22",
            pid = "1002",
            bytesReturned = 2,
            unit = "kPa",
            formula = "((A*256)+B)*0.1",
            signed = false,
            enabled = true
        ),
        CustomPid(
            id = "FIAT_DPF_SOOT",
            name = "DPF Soot Loading",
            header = "",
            mode = "22",
            pid = "100A",
            bytesReturned = 2,
            unit = "g",
            formula = "((A*256)+B)*0.1",
            signed = false,
            enabled = true
        ),
        CustomPid(
            id = "FIAT_DPF_REGEN",
            name = "DPF Regeneration Status",
            header = "",
            mode = "22",
            pid = "100B",
            bytesReturned = 1,
            unit = "",
            formula = "A",
            signed = false,
            enabled = true
        ),
        CustomPid(
            id = "FIAT_DPF_TEMP",
            name = "DPF Temperature",
            header = "",
            mode = "22",
            pid = "100C",
            bytesReturned = 2,
            unit = "°C",
            formula = "((A*256)+B)/10-40",
            signed = false,
            enabled = true
        ),
        CustomPid(
            id = "FIAT_INJ_COR1",
            name = "Injector Correction Cyl 1",
            header = "",
            mode = "22",
            pid = "1940",
            bytesReturned = 2,
            unit = "mm³",
            formula = "((A*256)+B)/100",
            signed = true,
            enabled = true
        ),
        CustomPid(
            id = "FIAT_INJ_COR2",
            name = "Injector Correction Cyl 2",
            header = "",
            mode = "22",
            pid = "1941",
            bytesReturned = 2,
            unit = "mm³",
            formula = "((A*256)+B)/100",
            signed = true,
            enabled = true
        ),
        CustomPid(
            id = "FIAT_INJ_COR3",
            name = "Injector Correction Cyl 3",
            header = "",
            mode = "22",
            pid = "1942",
            bytesReturned = 2,
            unit = "mm³",
            formula = "((A*256)+B)/100",
            signed = true,
            enabled = true
        ),
        CustomPid(
            id = "FIAT_INJ_COR4",
            name = "Injector Correction Cyl 4",
            header = "",
            mode = "22",
            pid = "1943",
            bytesReturned = 2,
            unit = "mm³",
            formula = "((A*256)+B)/100",
            signed = true,
            enabled = true
        ),
        CustomPid(
            id = "FIAT_OIL_DEG",
            name = "Oil Degradation",
            header = "",
            mode = "22",
            pid = "194A",
            bytesReturned = 1,
            unit = "%",
            formula = "A*100/255",
            signed = false,
            enabled = true
        ),
        CustomPid(
            id = "FIAT_EGR_POS",
            name = "EGR Valve Position (actual)",
            header = "",
            mode = "22",
            pid = "2027",
            bytesReturned = 2,
            unit = "%",
            formula = "((A*256)+B)*100/65535",
            signed = false,
            enabled = true
        ),
        CustomPid(
            id = "FIAT_SWIRL",
            name = "Swirl Valve Position",
            header = "",
            mode = "22",
            pid = "200F",
            bytesReturned = 2,
            unit = "%",
            formula = "((A*256)+B)*100/65535",
            signed = false,
            enabled = true
        )
    )

    // ── Ford ────────────────────────────────────────────────────────────────

    private val fordPids: List<CustomPid> = listOf(
        CustomPid(
            id = "FORD_TRANS_TEMP",
            name = "Transmission Fluid Temp",
            header = "",
            mode = "22",
            pid = "1E01",
            bytesReturned = 2,
            unit = "°C",
            formula = "((A*256)+B)/10-40",
            signed = false,
            enabled = true
        ),
        CustomPid(
            id = "FORD_BOOST_ACT",
            name = "Turbo Boost Actual",
            header = "",
            mode = "22",
            pid = "1E10",
            bytesReturned = 2,
            unit = "kPa",
            formula = "((A*256)+B)*0.1",
            signed = false,
            enabled = true
        ),
        CustomPid(
            id = "FORD_BOOST_TGT",
            name = "Turbo Boost Target",
            header = "",
            mode = "22",
            pid = "1E11",
            bytesReturned = 2,
            unit = "kPa",
            formula = "((A*256)+B)*0.1",
            signed = false,
            enabled = true
        ),
        CustomPid(
            id = "FORD_DPF_SOOT",
            name = "DPF Soot Load",
            header = "",
            mode = "22",
            pid = "1E23",
            bytesReturned = 2,
            unit = "g",
            formula = "((A*256)+B)*0.1",
            signed = false,
            enabled = true
        ),
        CustomPid(
            id = "FORD_DPF_REGEN",
            name = "DPF Regeneration Status",
            header = "",
            mode = "22",
            pid = "1E24",
            bytesReturned = 1,
            unit = "",
            formula = "A",
            signed = false,
            enabled = true
        ),
        CustomPid(
            id = "FORD_RAIL_TGT",
            name = "Rail Pressure Target",
            header = "",
            mode = "22",
            pid = "1E2C",
            bytesReturned = 2,
            unit = "kPa",
            formula = "((A*256)+B)*10",
            signed = false,
            enabled = true
        ),
        CustomPid(
            id = "FORD_INJ_QTY",
            name = "Injection Quantity",
            header = "",
            mode = "22",
            pid = "1E30",
            bytesReturned = 2,
            unit = "mm³",
            formula = "((A*256)+B)/100",
            signed = false,
            enabled = true
        ),
        CustomPid(
            id = "FORD_BATT_SOC",
            name = "Battery State of Charge",
            header = "",
            mode = "22",
            pid = "DD01",
            bytesReturned = 1,
            unit = "%",
            formula = "A*100/255",
            signed = false,
            enabled = true
        )
    )

    // ── Jaguar (JLR) ───────────────────────────────────────────────────────

    private val jaguarPids: List<CustomPid> = listOf(
        CustomPid(
            id = "JAG_AIR_SUSP_FL",
            name = "Air Suspension Height FL",
            header = "720",
            mode = "22",
            pid = "DD02",
            bytesReturned = 2,
            unit = "mm",
            formula = "((A*256)+B)/10",
            signed = false,
            enabled = true
        ),
        CustomPid(
            id = "JAG_AIR_SUSP_FR",
            name = "Air Suspension Height FR",
            header = "720",
            mode = "22",
            pid = "DD03",
            bytesReturned = 2,
            unit = "mm",
            formula = "((A*256)+B)/10",
            signed = false,
            enabled = true
        ),
        CustomPid(
            id = "JAG_AIR_SUSP_RL",
            name = "Air Suspension Height RL",
            header = "720",
            mode = "22",
            pid = "DD04",
            bytesReturned = 2,
            unit = "mm",
            formula = "((A*256)+B)/10",
            signed = false,
            enabled = true
        ),
        CustomPid(
            id = "JAG_AIR_SUSP_RR",
            name = "Air Suspension Height RR",
            header = "720",
            mode = "22",
            pid = "DD05",
            bytesReturned = 2,
            unit = "mm",
            formula = "((A*256)+B)/10",
            signed = false,
            enabled = true
        ),
        CustomPid(
            id = "JAG_SC_BOOST",
            name = "Supercharger Boost",
            header = "",
            mode = "22",
            pid = "DD10",
            bytesReturned = 2,
            unit = "kPa",
            formula = "((A*256)+B)*0.1",
            signed = false,
            enabled = true
        ),
        CustomPid(
            id = "JAG_DAMPER_MODE",
            name = "Adaptive Damper Mode",
            header = "720",
            mode = "22",
            pid = "DD20",
            bytesReturned = 1,
            unit = "",
            formula = "A",
            signed = false,
            enabled = true
        ),
        CustomPid(
            id = "JAG_YAW_RATE",
            name = "Yaw Rate",
            header = "760",
            mode = "22",
            pid = "0456",
            bytesReturned = 2,
            unit = "°/s",
            formula = "((A*256)+B)/100",
            signed = true,
            enabled = true
        ),
        CustomPid(
            id = "JAG_LAT_ACCEL",
            name = "Lateral Acceleration",
            header = "760",
            mode = "22",
            pid = "0457",
            bytesReturned = 2,
            unit = "g",
            formula = "((A*256)+B)/1000",
            signed = true,
            enabled = true
        ),
        CustomPid(
            id = "JAG_TRANS_TEMP",
            name = "Transmission Fluid Temp",
            header = "",
            mode = "22",
            pid = "1E01",
            bytesReturned = 2,
            unit = "°C",
            formula = "((A*256)+B)/10-40",
            signed = false,
            enabled = true
        ),
        CustomPid(
            id = "JAG_BOOST_ACT",
            name = "Turbo Boost (shared Ford)",
            header = "",
            mode = "22",
            pid = "1E10",
            bytesReturned = 2,
            unit = "kPa",
            formula = "((A*256)+B)*0.1",
            signed = false,
            enabled = true
        )
    )

    // ── Bosch Generic (ME7, MED17, EDC families) ────────────────────────────

    private val boschGenericPids: List<CustomPid> = listOf(
        CustomPid(
            id = "BOSCH_LAMBDA_ACT",
            name = "Lambda Actual (Bank 1)",
            header = "",
            mode = "22",
            pid = "F44C",
            bytesReturned = 2,
            unit = "",
            formula = "((A*256)+B)/32768",
            signed = false,
            enabled = true
        ),
        CustomPid(
            id = "BOSCH_LAMBDA_TGT",
            name = "Lambda Target (Bank 1)",
            header = "",
            mode = "22",
            pid = "F44D",
            bytesReturned = 2,
            unit = "",
            formula = "((A*256)+B)/32768",
            signed = false,
            enabled = true
        ),
        CustomPid(
            id = "BOSCH_KNOCK1",
            name = "Knock Retard Cyl 1",
            header = "",
            mode = "22",
            pid = "F449",
            bytesReturned = 2,
            unit = "°",
            formula = "((A*256)+B)/100",
            signed = true,
            enabled = true
        ),
        CustomPid(
            id = "BOSCH_KNOCK2",
            name = "Knock Retard Cyl 2",
            header = "",
            mode = "22",
            pid = "F44A",
            bytesReturned = 2,
            unit = "°",
            formula = "((A*256)+B)/100",
            signed = true,
            enabled = true
        ),
        CustomPid(
            id = "BOSCH_KNOCK3",
            name = "Knock Retard Cyl 3",
            header = "",
            mode = "22",
            pid = "F44B",
            bytesReturned = 2,
            unit = "°",
            formula = "((A*256)+B)/100",
            signed = true,
            enabled = true
        ),
        CustomPid(
            id = "BOSCH_KNOCK4",
            name = "Knock Retard Cyl 4",
            header = "",
            mode = "22",
            pid = "F44E",
            bytesReturned = 2,
            unit = "°",
            formula = "((A*256)+B)/100",
            signed = true,
            enabled = true
        ),
        CustomPid(
            id = "BOSCH_MISFIRE1",
            name = "Misfire Counter Cyl 1",
            header = "",
            mode = "22",
            pid = "F460",
            bytesReturned = 2,
            unit = "",
            formula = "(A*256)+B",
            signed = false,
            enabled = true
        ),
        CustomPid(
            id = "BOSCH_MISFIRE2",
            name = "Misfire Counter Cyl 2",
            header = "",
            mode = "22",
            pid = "F461",
            bytesReturned = 2,
            unit = "",
            formula = "(A*256)+B",
            signed = false,
            enabled = true
        ),
        CustomPid(
            id = "BOSCH_MISFIRE3",
            name = "Misfire Counter Cyl 3",
            header = "",
            mode = "22",
            pid = "F462",
            bytesReturned = 2,
            unit = "",
            formula = "(A*256)+B",
            signed = false,
            enabled = true
        ),
        CustomPid(
            id = "BOSCH_MISFIRE4",
            name = "Misfire Counter Cyl 4",
            header = "",
            mode = "22",
            pid = "F463",
            bytesReturned = 2,
            unit = "",
            formula = "(A*256)+B",
            signed = false,
            enabled = true
        ),
        CustomPid(
            id = "BOSCH_IGN_ADV",
            name = "Ignition Advance (ECU)",
            header = "",
            mode = "22",
            pid = "F440",
            bytesReturned = 2,
            unit = "°",
            formula = "((A*256)+B)/100",
            signed = true,
            enabled = true
        ),
        CustomPid(
            id = "BOSCH_INJ_TIME",
            name = "Injection Time",
            header = "",
            mode = "22",
            pid = "F441",
            bytesReturned = 2,
            unit = "ms",
            formula = "((A*256)+B)/100",
            signed = false,
            enabled = true
        ),
        CustomPid(
            id = "BOSCH_BOOST_ACT",
            name = "Boost Pressure Actual",
            header = "",
            mode = "22",
            pid = "F442",
            bytesReturned = 2,
            unit = "kPa",
            formula = "((A*256)+B)*0.1",
            signed = false,
            enabled = true
        ),
        CustomPid(
            id = "BOSCH_BOOST_TGT",
            name = "Boost Pressure Target",
            header = "",
            mode = "22",
            pid = "F443",
            bytesReturned = 2,
            unit = "kPa",
            formula = "((A*256)+B)*0.1",
            signed = false,
            enabled = true
        )
    )
}
