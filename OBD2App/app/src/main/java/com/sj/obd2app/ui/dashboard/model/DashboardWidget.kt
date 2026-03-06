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
    var alpha: Float = 1.0f
)
