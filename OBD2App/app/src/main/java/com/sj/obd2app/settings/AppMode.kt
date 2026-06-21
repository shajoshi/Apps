package com.sj.obd2app.settings

/**
 * Top-level app operating mode — determines whether the adapter is used for
 * standard OBD-II polling, high-speed CAN sniffing (500 kbps, ATMA), or
 * medium-speed CAN sniffing (125 kbps, STP 53 + ATMA).
 *
 * Stored as a string in [AppSettings] under the key "appMode".
 * Missing or unrecognised values default to [OBD].
 */
enum class AppMode {
    OBD,
    HS_CAN,
    MS_CAN;

    companion object {
        fun fromString(value: String?): AppMode =
            entries.firstOrNull { it.name == value } ?: OBD
    }
}
