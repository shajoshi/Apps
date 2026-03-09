package com.sj.obd2app.ui.dashboard.model

/**
 * Represents a single widget instance placed on the dashboard canvas.
 * This model is JSON-serializable to save and load layouts.
 */
data class DashboardWidget(
    val id: String,
    val type: WidgetType,
    val metric: DashboardMetric,
    // Position on a virtual grid (e.g. 1 unit = 24dp)
    var gridX: Int,
    var gridY: Int,
    // Size in grid units
    var gridW: Int,
    var gridH: Int,
    // Drawing order, higher is drawn later (on top)
    var zOrder: Int,
    // Transparency level: 0f (invisible) to 1f (opaque)
    var alpha: Float = 1.0f,
    // Gauge scale — auto-populated from MetricDefaults when the metric is chosen
    var rangeMin: Float = 0f,
    var rangeMax: Float = 100f,
    var majorTickInterval: Float = 10f,
    var minorTickCount: Int = 4,
    // Optional warning threshold — arc/zone turns red above this value (null = no warning zone)
    var warningThreshold: Float? = null,
    // Number of decimal places to show in numeric displays
    var decimalPlaces: Int = 1,
    // Display unit label — inferred from the metric at config time, user-editable per widget
    var displayUnit: String = ""
)
