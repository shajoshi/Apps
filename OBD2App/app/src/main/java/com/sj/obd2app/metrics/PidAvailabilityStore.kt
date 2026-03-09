package com.sj.obd2app.metrics

import android.content.Context
import org.json.JSONObject

/**
 * Persists the last-known non-null OBD2 PID values seen per vehicle profile.
 *
 * Key structure in SharedPreferences("pid_availability"):
 *   "<profileId>"  →  JSON object  { "<pid>": "<lastValue>", … }
 *
 * A PID is considered "available for this vehicle" once it has been seen
 * with a non-empty, non-error response at least once.
 */
object PidAvailabilityStore {

    private const val PREFS_NAME = "pid_availability"
    private val ERROR_VALUES = setOf("NODATA", "ERROR", "?", "UNABLE TO CONNECT", "BUS INIT")

    // ── Read ──────────────────────────────────────────────────────────────────

    /**
     * Returns the set of PIDs ever confirmed for [profileId], or an empty set
     * if no data has been recorded yet (or no profile is active).
     */
    fun getKnownPids(context: Context, profileId: String?): Set<String> {
        profileId ?: return emptySet()
        val json = prefs(context).getString(profileId, null) ?: return emptySet()
        return try {
            val obj = JSONObject(json)
            (0 until obj.length()).map { obj.names()?.getString(it) ?: "" }.filter { it.isNotEmpty() }.toSet()
        } catch (_: Exception) { emptySet() }
    }

    /**
     * Returns all last-known PID→value pairs for [profileId].
     * Useful for displaying the last-seen value in the wizard preview.
     */
    fun getLastValues(context: Context, profileId: String?): Map<String, String> {
        profileId ?: return emptyMap()
        val json = prefs(context).getString(profileId, null) ?: return emptyMap()
        return try {
            val obj = JSONObject(json)
            buildMap {
                val names = obj.names() ?: return emptyMap()
                for (i in 0 until names.length()) {
                    val key = names.getString(i)
                    put(key, obj.getString(key))
                }
            }
        } catch (_: Exception) { emptyMap() }
    }

    /** Returns true if at least one PID has been recorded for [profileId]. */
    fun hasData(context: Context, profileId: String?): Boolean {
        profileId ?: return false
        return prefs(context).contains(profileId)
    }

    // ── Write ─────────────────────────────────────────────────────────────────

    /**
     * Merges [newValues] (pid → rawValueString) into the stored map for [profileId].
     * Skips entries whose value looks like an OBD error token.
     * Only writes to disk if at least one new PID is added.
     */
    fun update(context: Context, profileId: String?, newValues: Map<String, String>) {
        profileId ?: return
        if (newValues.isEmpty()) return

        val existing = getLastValues(context, profileId).toMutableMap()
        var changed = false
        for ((pid, value) in newValues) {
            val v = value.trim().uppercase()
            if (v.isEmpty() || ERROR_VALUES.any { v.startsWith(it) }) continue
            if (existing[pid] != value) {
                existing[pid] = value
                changed = true
            }
        }
        if (!changed) return

        val obj = JSONObject()
        existing.forEach { (k, v) -> obj.put(k, v) }
        prefs(context).edit().putString(profileId, obj.toString()).apply()
    }

    /**
     * Clears all stored availability data for [profileId].
     * Call when the user deletes or resets a vehicle profile.
     */
    fun clear(context: Context, profileId: String) {
        prefs(context).edit().remove(profileId).apply()
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
