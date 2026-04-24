package com.sj.obd2app.can

import android.content.Context
import android.net.Uri
import android.util.Log
import org.json.JSONObject
import java.io.File

/**
 * CRUD repository for [CanProfile]. Mirrors [com.sj.obd2app.settings.VehicleProfileRepository]
 * by persisting each profile as an individual JSON file inside
 * `files/can_profiles/can_profile_<name>.json`.
 *
 * Imported DBC files are copied into `files/can_dbc/<profileId>.dbc` — deleting a profile
 * also removes its DBC copy.
 */
class CanProfileRepository private constructor(private val context: Context) {

    companion object {
        private const val TAG = "CanProfileRepository"
        private const val PROFILE_FILE_PREFIX = "can_profile_"

        @Volatile
        private var INSTANCE: CanProfileRepository? = null

        fun getInstance(context: Context): CanProfileRepository =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: CanProfileRepository(context.applicationContext).also { INSTANCE = it }
            }
    }

    private val profilesDir: File = File(context.filesDir, "can_profiles").apply {
        if (!exists()) mkdirs()
    }

    private val dbcDir: File = File(context.filesDir, "can_dbc").apply {
        if (!exists()) mkdirs()
    }

    private val captureDir: File = File(context.filesDir, "can_captures").apply {
        if (!exists()) mkdirs()
    }

    @Volatile
    private var cached: List<CanProfile>? = null

    // ── Read ──────────────────────────────────────────────────────────────────

    fun getAll(): List<CanProfile> {
        cached?.let { return it }
        val files = profilesDir.listFiles { f: File ->
            f.isFile && f.name.startsWith(PROFILE_FILE_PREFIX) && f.name.endsWith(".json")
        }?.sortedBy { it.name } ?: emptyList()

        val out = mutableListOf<CanProfile>()
        for (f in files) {
            try {
                val json = JSONObject(f.readText(Charsets.UTF_8))
                out += CanProfile.fromJson(json)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load CAN profile from ${f.name}", e)
            }
        }
        cached = out
        return out
    }

    fun getById(id: String): CanProfile? = getAll().firstOrNull { it.id == id }

    fun getDefault(): CanProfile? = getAll().firstOrNull { it.isDefault }

    // ── Write ─────────────────────────────────────────────────────────────────

    fun save(profile: CanProfile) {
        val fileName = "$PROFILE_FILE_PREFIX${sanitize(profile.name)}.json"
        val file = File(profilesDir, fileName)
        try {
            file.writeText(profile.toJson().toString(2), Charsets.UTF_8)
            cached = null
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save CAN profile: ${profile.name}", e)
        }
    }

    fun delete(id: String) {
        val profile = getById(id) ?: return
        val file = File(profilesDir, "$PROFILE_FILE_PREFIX${sanitize(profile.name)}.json")
        if (file.exists()) file.delete()
        deleteDbcFile(profile.id)
        deleteCaptureFile(profile.id)
        cached = null
    }

    /** Exclusively mark one profile as default; clears the flag on all others. */
    fun setDefault(id: String) {
        val all = getAll()
        for (p in all) {
            val shouldBeDefault = (p.id == id)
            if (p.isDefault != shouldBeDefault) {
                save(p.copy(isDefault = shouldBeDefault))
            }
        }
        cached = null
    }

    // ── DBC file handling ─────────────────────────────────────────────────────

    /**
     * Copy a user-picked DBC [sourceUri] into app-private storage under
     * `files/can_dbc/<profileId>.dbc`. Returns the destination file.
     */
    fun importDbc(profileId: String, sourceUri: Uri): File {
        val dest = File(dbcDir, "$profileId.dbc")
        context.contentResolver.openInputStream(sourceUri).use { input ->
            requireNotNull(input) { "Cannot open DBC input stream: $sourceUri" }
            dest.outputStream().use { out -> input.copyTo(out) }
        }
        return dest
    }

    fun dbcFileFor(profileId: String): File? {
        val f = File(dbcDir, "$profileId.dbc")
        return if (f.exists()) f else null
    }

    fun deleteDbcFile(profileId: String) {
        File(dbcDir, "$profileId.dbc").takeIf { it.exists() }?.delete()
    }

    // ── Playback capture file handling ────────────────────────────────────────

    /**
     * Copy a user-picked JSONL capture [sourceUri] (same format as `CanBusScanner`'s
     * `*.raw.jsonl` output) into `files/can_captures/<profileId>.jsonl`. Returns the
     * destination file.
     */
    fun importCapture(profileId: String, sourceUri: Uri): File {
        val dest = File(captureDir, "$profileId.jsonl")
        context.contentResolver.openInputStream(sourceUri).use { input ->
            requireNotNull(input) { "Cannot open capture input stream: $sourceUri" }
            dest.outputStream().use { out -> input.copyTo(out) }
        }
        return dest
    }

    fun captureFileFor(profileId: String): File? {
        val f = File(captureDir, "$profileId.jsonl")
        return if (f.exists()) f else null
    }

    fun deleteCaptureFile(profileId: String) {
        File(captureDir, "$profileId.jsonl").takeIf { it.exists() }?.delete()
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun sanitize(name: String): String =
        name.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "profile" }
}
