package com.sj.obd2app.ui.dashboard.model

/**
 * Defines the available metric sources a widget can map to.
 */
sealed class DashboardMetric {
    /** Connects to an OBD-II Mode 01 PID */
    data class Obd2Pid(val pid: String, val name: String, val unit: String) : DashboardMetric()

    /** Connects to the GPS fused speed stream */
    object GpsSpeed : DashboardMetric()

    /** Connects to the GPS geoid-corrected altitude stream */
    object GpsAltitude : DashboardMetric()

    /** Connects to a computed derived metric from MetricsCalculator */
    data class DerivedMetric(val key: String, val name: String, val unit: String) : DashboardMetric()

    /**
     * Connects to a CAN Bus signal decoded by `CanBusScanner`. Identified by the numeric CAN
     * message id and the signal name as declared in the DBC file attached to the active
     * [com.sj.obd2app.can.CanProfile]. [name] is the user-facing display label (falls back to
     * [signalName]) and [unit] is the DBC-declared engineering unit.
     */
    data class CanSignal(
        val messageId: Int,
        val signalName: String,
        val name: String,
        val unit: String
    ) : DashboardMetric() {
        /** Map key used by `CanBusScanner.latest` (`SignalRef.key()`). */
        fun latestKey(): String =
            "${Integer.toHexString(messageId).uppercase()}:$signalName"
    }
}
