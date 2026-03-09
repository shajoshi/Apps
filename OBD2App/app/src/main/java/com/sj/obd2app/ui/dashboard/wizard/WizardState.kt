package com.sj.obd2app.ui.dashboard.wizard

import com.sj.obd2app.ui.dashboard.model.DashboardMetric
import com.sj.obd2app.ui.dashboard.model.WidgetType

/**
 * Shared mutable state passed between the three wizard steps.
 * Held in AddWidgetWizardSheet and read/written by each step fragment.
 */
data class WizardState(
    var selectedType: WidgetType? = null,
    var selectedMetric: DashboardMetric? = null,
    var rangeMin: Float = 0f,
    var rangeMax: Float = 100f,
    var majorTickInterval: Float = 10f,
    var minorTickCount: Int = 4,
    var warningThreshold: Float? = null,
    var decimalPlaces: Int = 1,
    var displayUnit: String = "",
    var gridW: Int = 4,
    var gridH: Int = 4
)

/** Size preset options for Step 3. */
enum class SizePreset(val label: String, val gridW: Int, val gridH: Int, val hint: String) {
    SMALL  ("S",    2, 2, "Small: 2 × 2 grid units"),
    MEDIUM ("M",    4, 4, "Medium: 4 × 4 grid units"),
    LARGE  ("L",    6, 6, "Large: 6 × 6 grid units"),
    WIDE   ("Wide", 6, 3, "Wide: 6 × 3 grid units"),
    TALL   ("Tall", 3, 6, "Tall: 3 × 6 grid units")
}
