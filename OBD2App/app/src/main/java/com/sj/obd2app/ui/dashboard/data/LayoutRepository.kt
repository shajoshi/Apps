package com.sj.obd2app.ui.dashboard.data

import android.content.Context
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
 * If external storage (.obd directory) is available, layouts are stored there.
 * Otherwise, falls back to app-private storage for backward compatibility.
 */
class LayoutRepository(private val context: Context) {

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
            context.contentResolver.openOutputStream(layoutFile.uri, "wt")?.use { output ->
                output.write(json.toByteArray())
            }
        }
    }

    private fun saveToAppStorage(layoutName: String, json: String) {
        val safeName = layoutName.replace(Regex("[^A-Za-z0-9 _-]"), "")
        val file = File(layoutsDir, "$safeName.json")
        file.writeText(json)
    }

    /**
     * Deserialize all JSON layouts.
     * Legacy WidgetType names (REV_COUNTER, SPEEDOMETER_7SEG, FUEL_BAR, IFC_BAR) are
     * automatically migrated to their canonical equivalents on load.
     */
    fun getSavedLayouts(): List<DashboardLayout> {
        return if (useExternalStorage) {
            getLayoutsFromExternalStorage()
        } else {
            getLayoutsFromAppStorage()
        }
    }

    private fun getLayoutsFromExternalStorage(): List<DashboardLayout> {
        val files = AppDataDirectory.listLayoutFilesDocumentFile(context)
        return files.mapNotNull { file ->
            try {
                val content = context.contentResolver.openInputStream(file.uri)?.use {
                    it.readBytes().toString(Charsets.UTF_8)
                }
                content?.let {
                    val layout = gson.fromJson(it, DashboardLayout::class.java)
                    migrateLegacyTypes(layout)
                }
            } catch (e: Exception) {
                null
            }
        }
    }

    private fun getLayoutsFromAppStorage(): List<DashboardLayout> {
        val files = layoutsDir.listFiles { _, name -> name.endsWith(".json") } ?: return emptyList()
        return files.mapNotNull { file ->
            try {
                val layout = gson.fromJson(file.readText(), DashboardLayout::class.java)
                migrateLegacyTypes(layout)
            } catch (e: Exception) {
                null
            }
        }
    }

    /** Replaces deprecated WidgetType values with their canonical counterparts. */
    private fun migrateLegacyTypes(layout: DashboardLayout): DashboardLayout {
        var changed = false
        val migratedWidgets = layout.widgets.map { widget ->
            val canonical = widget.type.canonical()
            if (canonical != widget.type) { changed = true; widget.copy(type = canonical) }
            else widget
        }
        return if (changed) {
            layout.copy(
                widgets = migratedWidgets,
                orientation = layout.orientation ?: DashboardOrientation.PORTRAIT
            )
        } else {
            // Even if no widget type changes, ensure orientation is not null for legacy layouts
            layout.copy(
                orientation = layout.orientation ?: DashboardOrientation.PORTRAIT
            )
        }
    }

    fun deleteLayout(name: String) {
        if (useExternalStorage) {
            AppDataDirectory.deleteLayoutFile(context, name)
        } else {
            val safeName = name.replace(Regex("[^A-Za-z0-9 _-]"), "")
            val file = File(layoutsDir, "$safeName.json")
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
