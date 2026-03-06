package com.sj.obd2app.ui.dashboard.model

/**
 * The complete, saveable dashboard state.
 */
data class DashboardLayout(
    val name: String,
    val colorScheme: ColorScheme,
    val widgets: List<DashboardWidget>
)

/**
 * Holds colours to apply to all widgets on the dashboard.
 * Used for dynamic theming (e.g. Red night mode, blue modern mode).
 */
data class ColorScheme(
    // e.g. 0xFF1A1A2E.toInt()
    val background: Int,
    val surface: Int,
    val accent: Int,
    val text: Int,
    val warning: Int
) {
    companion object {
        val DEFAULT_DARK = ColorScheme(
            background = 0xFF1A1A2E.toInt(),
            surface = 0xFF2A2A3E.toInt(),
            accent = 0xFF4FC3F7.toInt(), // Light Blue
            text = 0xFFAAAACC.toInt(),
            warning = 0xFFFF7043.toInt()
        )
        val NEON_RED = ColorScheme(
            background = 0xFF110000.toInt(),
            surface = 0xFF220000.toInt(),
            accent = 0xFFFF1133.toInt(),
            text = 0xFFFFCCCC.toInt(),
            warning = 0xFFFFFF00.toInt()
        )
        val GREEN_LCD = ColorScheme(
            background = 0xFF001100.toInt(),
            surface = 0xFF002200.toInt(),
            accent = 0xFF00FF00.toInt(),
            text = 0xFF88FF88.toInt(),
            warning = 0xFFFFAA00.toInt()
        )
    }
}
