package com.sj.obd2app.ui.dashboard.model

/**
 * Holds the default display configuration for a metric source.
 * Applied automatically when a metric is selected in the Add Widget wizard.
 * All fields are editable by the user in Step 3 of the wizard.
 */
data class MetricConfig(
    val rangeMin: Float,
    val rangeMax: Float,
    val majorTickInterval: Float,
    val minorTickCount: Int,
    val warningThreshold: Float?,
    val decimalPlaces: Int,
    val displayUnit: String,
    val suggestedWidgetType: WidgetType
)

/**
 * Static lookup of sensible defaults for every supported OBD-II PID and GPS metric.
 * Keyed by the metric identifier string:
 *   - OBD-II PIDs: the 4-char hex string (e.g. "010C")
 *   - GPS sources: "GPS_SPEED", "GPS_ALTITUDE"
 */
object MetricDefaults {

    private val defaults: Map<String, MetricConfig> = mapOf(

        // ── Engine ────────────────────────────────────────────────
        "010C" to MetricConfig(
            rangeMin = 0f, rangeMax = 8000f,
            majorTickInterval = 1000f, minorTickCount = 4,
            warningThreshold = 6000f, decimalPlaces = 0,
            displayUnit = "rpm", suggestedWidgetType = WidgetType.DIAL
        ),
        "010D" to MetricConfig(
            rangeMin = 0f, rangeMax = 220f,
            majorTickInterval = 20f, minorTickCount = 4,
            warningThreshold = 180f, decimalPlaces = 0,
            displayUnit = "km/h", suggestedWidgetType = WidgetType.SEVEN_SEGMENT
        ),
        "0104" to MetricConfig(
            rangeMin = 0f, rangeMax = 100f,
            majorTickInterval = 25f, minorTickCount = 4,
            warningThreshold = null, decimalPlaces = 1,
            displayUnit = "%", suggestedWidgetType = WidgetType.BAR_GAUGE_H
        ),
        "0111" to MetricConfig(
            rangeMin = 0f, rangeMax = 100f,
            majorTickInterval = 25f, minorTickCount = 4,
            warningThreshold = null, decimalPlaces = 1,
            displayUnit = "%", suggestedWidgetType = WidgetType.BAR_GAUGE_H
        ),
        "010E" to MetricConfig(
            rangeMin = -64f, rangeMax = 64f,
            majorTickInterval = 16f, minorTickCount = 4,
            warningThreshold = null, decimalPlaces = 1,
            displayUnit = "° BTDC", suggestedWidgetType = WidgetType.NUMERIC_DISPLAY
        ),

        // ── Temperature ───────────────────────────────────────────
        "0105" to MetricConfig(
            rangeMin = -40f, rangeMax = 180f,
            majorTickInterval = 20f, minorTickCount = 4,
            warningThreshold = 130f, decimalPlaces = 0,
            displayUnit = "°C", suggestedWidgetType = WidgetType.TEMPERATURE_ARC
        ),
        "010F" to MetricConfig(
            rangeMin = -40f, rangeMax = 80f,
            majorTickInterval = 20f, minorTickCount = 4,
            warningThreshold = 60f, decimalPlaces = 0,
            displayUnit = "°C", suggestedWidgetType = WidgetType.TEMPERATURE_ARC
        ),
        "0146" to MetricConfig(
            rangeMin = -40f, rangeMax = 80f,
            majorTickInterval = 60f, minorTickCount = 4,
            warningThreshold = null, decimalPlaces = 0,
            displayUnit = "°C", suggestedWidgetType = WidgetType.TEMPERATURE_ARC
        ),
        "015C" to MetricConfig(
            rangeMin = -40f, rangeMax = 180f,
            majorTickInterval = 20f, minorTickCount = 4,
            warningThreshold = 130f, decimalPlaces = 0,
            displayUnit = "°C", suggestedWidgetType = WidgetType.TEMPERATURE_ARC
        ),

        // ── Fuel ─────────────────────────────────────────────────
        "012F" to MetricConfig(
            rangeMin = 0f, rangeMax = 100f,
            majorTickInterval = 25f, minorTickCount = 4,
            warningThreshold = 20f, decimalPlaces = 1,
            displayUnit = "%", suggestedWidgetType = WidgetType.BAR_GAUGE_V
        ),
        "010A" to MetricConfig(
            rangeMin = 0f, rangeMax = 765f,
            majorTickInterval = 100f, minorTickCount = 4,
            warningThreshold = null, decimalPlaces = 0,
            displayUnit = "kPa", suggestedWidgetType = WidgetType.NUMERIC_DISPLAY
        ),
        "015E" to MetricConfig(
            rangeMin = 0f, rangeMax = 3276f,
            majorTickInterval = 500f, minorTickCount = 4,
            warningThreshold = null, decimalPlaces = 2,
            displayUnit = "L/h", suggestedWidgetType = WidgetType.BAR_GAUGE_H
        ),
        "0106" to MetricConfig(
            rangeMin = -100f, rangeMax = 99.2f,
            majorTickInterval = 25f, minorTickCount = 4,
            warningThreshold = null, decimalPlaces = 1,
            displayUnit = "%", suggestedWidgetType = WidgetType.NUMERIC_DISPLAY
        ),
        "0107" to MetricConfig(
            rangeMin = -100f, rangeMax = 99.2f,
            majorTickInterval = 25f, minorTickCount = 4,
            warningThreshold = null, decimalPlaces = 1,
            displayUnit = "%", suggestedWidgetType = WidgetType.NUMERIC_DISPLAY
        ),

        // ── Air / Intake ──────────────────────────────────────────
        "010B" to MetricConfig(
            rangeMin = 0f, rangeMax = 255f,
            majorTickInterval = 50f, minorTickCount = 4,
            warningThreshold = null, decimalPlaces = 0,
            displayUnit = "kPa", suggestedWidgetType = WidgetType.NUMERIC_DISPLAY
        ),
        "0110" to MetricConfig(
            rangeMin = 0f, rangeMax = 655f,
            majorTickInterval = 100f, minorTickCount = 4,
            warningThreshold = null, decimalPlaces = 2,
            displayUnit = "g/s", suggestedWidgetType = WidgetType.NUMERIC_DISPLAY
        ),
        "0133" to MetricConfig(
            rangeMin = 60f, rangeMax = 110f,
            majorTickInterval = 10f, minorTickCount = 4,
            warningThreshold = null, decimalPlaces = 0,
            displayUnit = "kPa", suggestedWidgetType = WidgetType.NUMERIC_DISPLAY
        ),

        // ── Electrical ───────────────────────────────────────────
        "0142" to MetricConfig(
            rangeMin = 10f, rangeMax = 16f,
            majorTickInterval = 1f, minorTickCount = 4,
            warningThreshold = 14.8f, decimalPlaces = 2,
            displayUnit = "V", suggestedWidgetType = WidgetType.DIAL
        ),
        "0114" to MetricConfig(
            rangeMin = 0f, rangeMax = 1.275f,
            majorTickInterval = 0.25f, minorTickCount = 4,
            warningThreshold = null, decimalPlaces = 3,
            displayUnit = "V", suggestedWidgetType = WidgetType.NUMERIC_DISPLAY
        ),

        // ── Distance / Time ───────────────────────────────────────
        "011F" to MetricConfig(
            rangeMin = 0f, rangeMax = 7200f,
            majorTickInterval = 600f, minorTickCount = 4,
            warningThreshold = null, decimalPlaces = 0,
            displayUnit = "sec", suggestedWidgetType = WidgetType.NUMERIC_DISPLAY
        ),
        "0121" to MetricConfig(
            rangeMin = 0f, rangeMax = 65535f,
            majorTickInterval = 10000f, minorTickCount = 4,
            warningThreshold = null, decimalPlaces = 0,
            displayUnit = "km", suggestedWidgetType = WidgetType.NUMERIC_DISPLAY
        ),
        "0131" to MetricConfig(
            rangeMin = 0f, rangeMax = 65535f,
            majorTickInterval = 10000f, minorTickCount = 4,
            warningThreshold = null, decimalPlaces = 0,
            displayUnit = "km", suggestedWidgetType = WidgetType.NUMERIC_DISPLAY
        ),

        // ── GPS ───────────────────────────────────────────────────
        "GPS_SPEED" to MetricConfig(
            rangeMin = 0f, rangeMax = 220f,
            majorTickInterval = 20f, minorTickCount = 4,
            warningThreshold = 180f, decimalPlaces = 0,
            displayUnit = "km/h", suggestedWidgetType = WidgetType.SEVEN_SEGMENT
        ),
        "GPS_ALTITUDE" to MetricConfig(
            rangeMin = -500f, rangeMax = 8848f,
            majorTickInterval = 500f, minorTickCount = 4,
            warningThreshold = null, decimalPlaces = 0,
            displayUnit = "m", suggestedWidgetType = WidgetType.NUMERIC_DISPLAY
        ),

        // ── Derived — Fuel Efficiency ─────────────────────────────
        "DERIVED_LPK" to MetricConfig(
            rangeMin = 0f, rangeMax = 25f,
            majorTickInterval = 5f, minorTickCount = 4,
            warningThreshold = 20f, decimalPlaces = 1,
            displayUnit = "L/100km", suggestedWidgetType = WidgetType.BAR_GAUGE_H
        ),
        "DERIVED_KPL" to MetricConfig(
            rangeMin = 0f, rangeMax = 30f,
            majorTickInterval = 5f, minorTickCount = 4,
            warningThreshold = null, decimalPlaces = 1,
            displayUnit = "km/L", suggestedWidgetType = WidgetType.NUMERIC_DISPLAY
        ),
        "DERIVED_AVG_LPK" to MetricConfig(
            rangeMin = 0f, rangeMax = 20f,
            majorTickInterval = 5f, minorTickCount = 4,
            warningThreshold = null, decimalPlaces = 1,
            displayUnit = "L/100km", suggestedWidgetType = WidgetType.NUMERIC_DISPLAY
        ),
        "DERIVED_AVG_KPL" to MetricConfig(
            rangeMin = 0f, rangeMax = 100f,
            majorTickInterval = 5f, minorTickCount = 4,
            warningThreshold = null, decimalPlaces = 1,
            displayUnit = "km/L", suggestedWidgetType = WidgetType.NUMERIC_DISPLAY
        ),
        "DERIVED_FUEL_USED" to MetricConfig(
            rangeMin = 0f, rangeMax = 60f,
            majorTickInterval = 10f, minorTickCount = 4,
            warningThreshold = null, decimalPlaces = 2,
            displayUnit = "L", suggestedWidgetType = WidgetType.NUMERIC_DISPLAY
        ),
        "DERIVED_RANGE" to MetricConfig(
            rangeMin = 0f, rangeMax = 600f,
            majorTickInterval = 100f, minorTickCount = 4,
            warningThreshold = 80f, decimalPlaces = 0,
            displayUnit = "km", suggestedWidgetType = WidgetType.BAR_GAUGE_H
        ),
        "DERIVED_FUEL_COST" to MetricConfig(
            rangeMin = 0f, rangeMax = 5000f,
            majorTickInterval = 500f, minorTickCount = 4,
            warningThreshold = null, decimalPlaces = 2,
            displayUnit = "", suggestedWidgetType = WidgetType.NUMERIC_DISPLAY
        ),

        // ── Derived — Trip Computer ───────────────────────────────
        "DERIVED_TRIP_DIST" to MetricConfig(
            rangeMin = 0f, rangeMax = 500f,
            majorTickInterval = 100f, minorTickCount = 4,
            warningThreshold = null, decimalPlaces = 1,
            displayUnit = "km", suggestedWidgetType = WidgetType.NUMERIC_DISPLAY
        ),
        "DERIVED_TRIP_TIME" to MetricConfig(
            rangeMin = 0f, rangeMax = 36000f,
            majorTickInterval = 3600f, minorTickCount = 4,
            warningThreshold = null, decimalPlaces = 0,
            displayUnit = "sec", suggestedWidgetType = WidgetType.NUMERIC_DISPLAY
        ),
        "DERIVED_AVG_SPD" to MetricConfig(
            rangeMin = 0f, rangeMax = 150f,
            majorTickInterval = 30f, minorTickCount = 4,
            warningThreshold = null, decimalPlaces = 1,
            displayUnit = "km/h", suggestedWidgetType = WidgetType.NUMERIC_DISPLAY
        ),
        "DERIVED_MAX_SPD" to MetricConfig(
            rangeMin = 0f, rangeMax = 220f,
            majorTickInterval = 20f, minorTickCount = 4,
            warningThreshold = 180f, decimalPlaces = 0,
            displayUnit = "km/h", suggestedWidgetType = WidgetType.NUMERIC_DISPLAY
        ),

        // ── Derived — Emissions & Drive Mode ─────────────────────
        "DERIVED_CO2" to MetricConfig(
            rangeMin = 0f, rangeMax = 300f,
            majorTickInterval = 50f, minorTickCount = 4,
            warningThreshold = null, decimalPlaces = 1,
            displayUnit = "g/km", suggestedWidgetType = WidgetType.NUMERIC_DISPLAY
        ),
        "DERIVED_PCT_CITY" to MetricConfig(
            rangeMin = 0f, rangeMax = 100f,
            majorTickInterval = 25f, minorTickCount = 4,
            warningThreshold = null, decimalPlaces = 0,
            displayUnit = "%", suggestedWidgetType = WidgetType.BAR_GAUGE_H
        ),
        "DERIVED_PCT_IDLE" to MetricConfig(
            rangeMin = 0f, rangeMax = 100f,
            majorTickInterval = 25f, minorTickCount = 4,
            warningThreshold = null, decimalPlaces = 0,
            displayUnit = "%", suggestedWidgetType = WidgetType.BAR_GAUGE_H
        ),

        // ── Derived — Power ───────────────────────────────────────
        "DERIVED_POWER_ACCEL" to MetricConfig(
            rangeMin = 0f, rangeMax = 300f,
            majorTickInterval = 50f, minorTickCount = 4,
            warningThreshold = null, decimalPlaces = 1,
            displayUnit = "kW", suggestedWidgetType = WidgetType.DIAL
        ),
        "DERIVED_POWER_THERMO" to MetricConfig(
            rangeMin = 0f, rangeMax = 300f,
            majorTickInterval = 50f, minorTickCount = 4,
            warningThreshold = null, decimalPlaces = 1,
            displayUnit = "kW", suggestedWidgetType = WidgetType.DIAL
        ),
        "DERIVED_POWER_OBD" to MetricConfig(
            rangeMin = 0f, rangeMax = 300f,
            majorTickInterval = 50f, minorTickCount = 4,
            warningThreshold = null, decimalPlaces = 1,
            displayUnit = "kW", suggestedWidgetType = WidgetType.DIAL
        ),
        "DERIVED_POWER_ACCEL_BHP" to MetricConfig(
            rangeMin = 0f, rangeMax = 400f,
            majorTickInterval = 50f, minorTickCount = 4,
            warningThreshold = null, decimalPlaces = 1,
            displayUnit = "bhp", suggestedWidgetType = WidgetType.DIAL
        ),
        "DERIVED_POWER_THERMO_BHP" to MetricConfig(
            rangeMin = 0f, rangeMax = 400f,
            majorTickInterval = 50f, minorTickCount = 4,
            warningThreshold = null, decimalPlaces = 1,
            displayUnit = "bhp", suggestedWidgetType = WidgetType.DIAL
        ),
        "DERIVED_POWER_OBD_BHP" to MetricConfig(
            rangeMin = 0f, rangeMax = 400f,
            majorTickInterval = 50f, minorTickCount = 4,
            warningThreshold = null, decimalPlaces = 1,
            displayUnit = "bhp", suggestedWidgetType = WidgetType.DIAL
        )
    )

    /**
     * Returns the MetricConfig for the given metric key, or a safe generic fallback.
     */
    fun get(metricKey: String): MetricConfig =
        defaults[metricKey] ?: MetricConfig(
            rangeMin = 0f, rangeMax = 100f,
            majorTickInterval = 10f, minorTickCount = 4,
            warningThreshold = null, decimalPlaces = 1,
            displayUnit = "", suggestedWidgetType = WidgetType.NUMERIC_DISPLAY
        )

    /**
     * Convenience: get the MetricConfig for a DashboardMetric instance.
     */
    fun get(metric: DashboardMetric): MetricConfig = when (metric) {
        is DashboardMetric.Obd2Pid      -> get(metric.pid)
        DashboardMetric.GpsSpeed        -> get("GPS_SPEED")
        DashboardMetric.GpsAltitude     -> get("GPS_ALTITUDE")
        is DashboardMetric.DerivedMetric -> get(metric.key)
    }
}
