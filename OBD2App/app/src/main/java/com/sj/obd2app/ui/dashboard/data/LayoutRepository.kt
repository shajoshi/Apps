package com.sj.obd2app.ui.dashboard.data

import android.content.Context
import android.util.Log
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.documentfile.provider.DocumentFile
import com.google.gson.*
import com.sj.obd2app.settings.AppSettings
import com.sj.obd2app.storage.AppDataDirectory
import com.sj.obd2app.ui.dashboard.model.DashboardLayout
import com.sj.obd2app.ui.dashboard.model.DashboardMetric
import com.sj.obd2app.ui.dashboard.model.DashboardOrientation
import java.io.File
import java.lang.reflect.Type

/**
 * Handles saving and loading DashboardLayout instances to/from JSON files.
 * 
 * If external storage (.obd directory) is available, layouts are stored there with format: dashboard_<name>.json
 * Otherwise, stored in app-private storage with the same naming convention.
 */
class LayoutRepository(private val context: Context) {

    companion object {
        private const val TAG = "LayoutRepository"
    }

    private val useExternalStorage: Boolean
        get() = AppDataDirectory.isUsingExternalStorage(context)

    private val layoutsDir = File(context.filesDir, "layouts").apply {
        if (!exists()) mkdirs()
    }

    // Gson requires a custom adapter for the sealed class [DashboardMetric]
    private val gson = GsonBuilder()
        .registerTypeAdapter(DashboardMetric::class.java, DashboardMetricAdapter())
        .setPrettyPrinting()
        .create()

    /**
     * Serialise the layout to a JSON file.
     */
    fun saveLayout(layout: DashboardLayout): Result<File> {
        return try {
            val json = gson.toJson(layout)
            
            if (useExternalStorage) {
                saveToExternalStorage(layout.name, json)
            } else {
                saveToAppStorage(layout.name, json)
            }
            
            Result.success(File("")) // Return dummy file for compatibility
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun saveToExternalStorage(layoutName: String, json: String) {
        val layoutFile = AppDataDirectory.getLayoutFileDocumentFile(context, layoutName)
        if (layoutFile != null) {
            // Use "wt" mode to truncate before writing — "w" alone does NOT truncate on Android 10+
            context.contentResolver.openOutputStream(layoutFile.uri, "wt")?.use { output ->
                output.write(json.toByteArray())
            }
        }
    }

    private fun saveToAppStorage(layoutName: String, json: String) {
        val file = AppDataDirectory.getLayoutFilePrivate(context, layoutName)
        file.writeText(json)
    }

    /**
     * Deserialize all JSON layouts.
     */
    fun getSavedLayouts(): List<DashboardLayout> {
        Log.d(TAG, "getSavedLayouts: useExternalStorage=$useExternalStorage")
        return if (useExternalStorage) {
            getLayoutsFromExternalStorage()
        } else {
            getLayoutsFromAppStorage()
        }
    }

    private fun getLayoutsFromExternalStorage(): List<DashboardLayout> {
        Log.d(TAG, "getLayoutsFromExternalStorage: starting")
        val files = AppDataDirectory.listLayoutFilesDocumentFile(context)
        Log.d(TAG, "getLayoutsFromExternalStorage: found ${files.size} layout files")
        
        val layouts = mutableListOf<DashboardLayout>()
        var errorCount = 0
        
        files.forEach { file ->
            try {
                val content = context.contentResolver.openInputStream(file.uri)?.use {
                    it.readBytes().toString(Charsets.UTF_8)
                }
                content?.let {
                    val layout = gson.fromJson(it, DashboardLayout::class.java)
                    layouts.add(layout)
                    Log.d(TAG, "getLayoutsFromExternalStorage: loaded layout '${layout.name}'")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load layout from ${file.name}", e)
                errorCount++
            }
        }
        
        if (errorCount > 0) {
            Toast.makeText(context, "$errorCount dashboard(s) could not be loaded (corrupted files)", Toast.LENGTH_SHORT).show()
        }
        
        return layouts
    }

    private fun getLayoutsFromAppStorage(): List<DashboardLayout> {
        val files = AppDataDirectory.listLayoutFilesPrivate(context)
        val layouts = mutableListOf<DashboardLayout>()
        var errorCount = 0
        
        files.forEach { file ->
            try {
                val layout = gson.fromJson(file.readText(), DashboardLayout::class.java)
                layouts.add(layout)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load layout from ${file.name}", e)
                errorCount++
            }
        }
        
        if (errorCount > 0) {
            Toast.makeText(context, "$errorCount dashboard(s) could not be loaded (corrupted files)", Toast.LENGTH_SHORT).show()
        }
        
        Log.d(TAG, "getLayoutsFromExternalStorage: returning ${layouts.size} layouts")
        return layouts
    }

    
    /**
     * Seeds sample dashboards from bundled assets on first install.
     * Only copies if no dashboards currently exist in either storage location.
     */
    fun seedDefaultDashboards() {
        Log.d(TAG, "seedDefaultDashboards: called")
        val existing = getSavedLayouts()
        Log.d(TAG, "seedDefaultDashboards: getSavedLayouts() returned ${existing.size} layouts")
        
        if (existing.isNotEmpty()) {
            Log.d(TAG, "seedDefaultDashboards: existing layouts found, skipping seed")
            return
        }
        
        Log.w(TAG, "seedDefaultDashboards: NO existing layouts found, starting seed process")
        try {
            val seedFiles = context.assets.list("seed_dashboards") ?: return
            for (fileName in seedFiles) {
                if (!fileName.endsWith(".json")) continue
                val json = context.assets.open("seed_dashboards/$fileName").use {
                    it.readBytes().toString(Charsets.UTF_8)
                }
                val layout = gson.fromJson(json, DashboardLayout::class.java)
                saveLayout(layout)
                Log.i(TAG, "Seeded dashboard: ${layout.name}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to seed default dashboards", e)
        }
    }

    fun deleteLayout(name: String) {
        if (useExternalStorage) {
            AppDataDirectory.deleteLayoutFile(context, name)
        } else {
            val file = AppDataDirectory.getLayoutFilePrivate(context, name)
            if (file.exists()) file.delete()
        }
        
        if (getDefaultLayoutName() == name) clearDefaultLayout()
    }

    fun getDefaultLayoutName(): String? =
        AppSettings.getDefaultLayoutName(context)

    fun setDefaultLayoutName(name: String) =
        AppSettings.setDefaultLayoutName(context, name)

    fun clearDefaultLayout() =
        AppSettings.setDefaultLayoutName(context, null)
}

/**
 * Gson adapter to correctly serialize/deserialize the [DashboardMetric] sealed class.
 */
class DashboardMetricAdapter : JsonSerializer<DashboardMetric>, JsonDeserializer<DashboardMetric> {
    
    override fun serialize(src: DashboardMetric, typeOfSrc: Type, context: JsonSerializationContext): JsonElement {
        val result = JsonObject()
        when (src) {
            is DashboardMetric.Obd2Pid -> {
                result.addProperty("type", "Obd2Pid")
                result.add("data", context.serialize(src))
            }
            DashboardMetric.GpsSpeed -> result.addProperty("type", "GpsSpeed")
            DashboardMetric.GpsAltitude -> result.addProperty("type", "GpsAltitude")
            is DashboardMetric.DerivedMetric -> {
                result.addProperty("type", "DerivedMetric")
                result.add("data", context.serialize(src))
            }
        }
        return result
    }

    override fun deserialize(json: JsonElement, typeOfT: Type, context: JsonDeserializationContext): DashboardMetric {
        val jsonObject = json.asJsonObject
        val type = jsonObject.get("type").asString
        
        return when (type) {
            "Obd2Pid" -> context.deserialize(jsonObject.get("data"), DashboardMetric.Obd2Pid::class.java)
            "GpsSpeed" -> DashboardMetric.GpsSpeed
            "GpsAltitude" -> DashboardMetric.GpsAltitude
            "DerivedMetric" -> context.deserialize(jsonObject.get("data"), DashboardMetric.DerivedMetric::class.java)
            else -> throw JsonParseException("Unknown DashboardMetric type: $type")
        }
    }
}
