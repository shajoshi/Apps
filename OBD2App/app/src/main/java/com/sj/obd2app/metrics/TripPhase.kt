package com.sj.obd2app.metrics

/**
 * Represents the current state of a trip recording session.
 */
enum class TripPhase {
    /** No trip is active */
    IDLE,
    /** Trip is actively recording data */
    RUNNING,
    /** Trip is paused (data not accumulating) */
    PAUSED
}
