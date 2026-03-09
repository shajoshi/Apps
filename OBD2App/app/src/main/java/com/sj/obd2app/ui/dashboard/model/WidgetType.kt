package com.sj.obd2app.ui.dashboard.model

/**
 * The visual widget types available in the dashboard editor.
 */
enum class WidgetType {
    DIAL,              // Circular dial gauge (formerly REV_COUNTER)
    SEVEN_SEGMENT,     // Digital 7-segment display (formerly SPEEDOMETER_7SEG)
    BAR_GAUGE_H,       // Horizontal bar gauge (replaces IFC_BAR / FUEL_BAR horizontal use)
    BAR_GAUGE_V,       // Vertical bar gauge (replaces FUEL_BAR vertical use)
    NUMERIC_DISPLAY,   // Large numeric readout
    TEMPERATURE_ARC,   // 180° arc sweep for temperature metrics

    // Legacy aliases kept for JSON deserialization backward compatibility
    @Deprecated("Use DIAL", ReplaceWith("DIAL"))
    REV_COUNTER,
    @Deprecated("Use SEVEN_SEGMENT", ReplaceWith("SEVEN_SEGMENT"))
    SPEEDOMETER_7SEG,
    @Deprecated("Use BAR_GAUGE_V", ReplaceWith("BAR_GAUGE_V"))
    FUEL_BAR,
    @Deprecated("Use BAR_GAUGE_H", ReplaceWith("BAR_GAUGE_H"))
    IFC_BAR;

    /** Returns the canonical (non-deprecated) equivalent of this type. */
    fun canonical(): WidgetType = when (this) {
        REV_COUNTER   -> DIAL
        SPEEDOMETER_7SEG -> SEVEN_SEGMENT
        FUEL_BAR      -> BAR_GAUGE_V
        IFC_BAR       -> BAR_GAUGE_H
        else          -> this
    }
}
