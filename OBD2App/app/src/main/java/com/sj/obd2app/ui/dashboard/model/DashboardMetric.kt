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
}
