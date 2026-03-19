package com.sj.obd2app.ui.dashboard.model

/**
 * The visual widget types available in the dashboard editor.
 */
enum class WidgetType {
    DIAL,              // Circular dial gauge
    SEVEN_SEGMENT,     // Digital 7-segment display
    BAR_GAUGE_H,       // Horizontal bar gauge
    BAR_GAUGE_V,       // Vertical bar gauge
    NUMERIC_DISPLAY,   // Large numeric readout
    TEMPERATURE_ARC;   // 180° arc sweep for temperature metrics
}
