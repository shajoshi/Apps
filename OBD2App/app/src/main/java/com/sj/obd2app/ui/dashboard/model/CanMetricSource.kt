package com.sj.obd2app.ui.dashboard.model

import android.content.Context
import android.util.Log
import com.sj.obd2app.can.CanProfile
import com.sj.obd2app.can.CanProfileRepository
import com.sj.obd2app.can.DbcDatabase
import com.sj.obd2app.can.DbcParser

/**
 * Builds a list of [DashboardMetric.CanSignal] entries that widgets can bind to, sourced
 * from the user's default [CanProfile] and its associated DBC file. Used by the dashboard
 * widget pickers (Add Wizard Step 2, Edit Widget bottom sheet) so that selected CAN
 * signals show up alongside OBD-II PIDs, GPS, and derived metrics.
 *
 * If no default profile is starred, or the DBC fails to load, an empty list is returned
 * and the picker silently skips the CAN group.
 */
object CanMetricSource {

    private const val TAG = "CanMetricSource"

    data class Group(val header: String, val metrics: List<DashboardMetric>)

    fun buildGroups(context: Context): List<Group> {
        val repo = CanProfileRepository.getInstance(context)
        val profile = repo.getDefault() ?: return emptyList()
        if (profile.selectedSignals.isEmpty()) return emptyList()

        val dbc = loadDbc(context, profile) ?: return emptyList()

        // Keep picker order stable: follow the signal order declared in the profile.
        val metrics = profile.selectedSignals.mapNotNull { ref ->
            val hit = dbc.findSignal(ref.messageId, ref.signalName)
            if (hit == null) {
                Log.w(TAG, "Signal ${ref.signalName} @ 0x${Integer.toHexString(ref.messageId)} " +
                    "not found in DBC ${profile.dbcFileName}")
                return@mapNotNull null
            }
            val (_, signal) = hit
            DashboardMetric.CanSignal(
                messageId = ref.messageId,
                signalName = ref.signalName,
                name = ref.signalName,
                unit = signal.unit
            )
        }
        if (metrics.isEmpty()) return emptyList()
        return listOf(Group("CAN — ${profile.name}", metrics))
    }

    private fun loadDbc(context: Context, profile: CanProfile): DbcDatabase? {
        val file = CanProfileRepository.getInstance(context).dbcFileFor(profile.id) ?: return null
        return try {
            file.inputStream().use { DbcParser.parse(it, profile.dbcFileName) }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load DBC for CAN dashboard binding", e)
            null
        }
    }
}
