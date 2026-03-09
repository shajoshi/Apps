package com.sj.obd2app.ui.dashboard.data

import android.content.Context
import androidx.core.content.FileProvider
import com.google.gson.*
import com.sj.obd2app.ui.dashboard.model.DashboardLayout
import com.sj.obd2app.ui.dashboard.model.DashboardMetric
import java.io.File
import java.lang.reflect.Type

/**
 * Handles saving and loading DashboardLayout instances to/from JSON files
 * using Google's Gson library.
 */
class LayoutRepository(private val context: Context) {

    private val layoutsDir = File(context.filesDir, "layouts").apply {
        if (!exists()) mkdirs()
    }

    // Gson requires a custom adapter for the sealed class [DashboardMetric]
    private val gson = GsonBuilder()
        .registerTypeAdapter(DashboardMetric::class.java, DashboardMetricAdapter())
        .setPrettyPrinting()
        .create()

    /**
     * Serialise the layout to a JSON file in the private app storage.
     */
    fun saveLayout(layout: DashboardLayout): Result<File> {
        return try {
            val json = gson.toJson(layout)
            // Use alpha-numeric name to prevent path injection
            val safeName = layout.name.replace(Regex("[^A-Za-z0-9 _-]"), "")
            val file = File(layoutsDir, "$safeName.json")
            file.writeText(json)
            Result.success(file)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Deserialize all JSON layouts from the local directory.
     * Legacy WidgetType names (REV_COUNTER, SPEEDOMETER_7SEG, FUEL_BAR, IFC_BAR) are
     * automatically migrated to their canonical equivalents on load.
     */
    fun getSavedLayouts(): List<DashboardLayout> {
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
        return if (changed) layout.copy(widgets = migratedWidgets) else layout
    }

    fun deleteLayout(name: String) {
        val safeName = name.replace(Regex("[^A-Za-z0-9 _-]"), "")
        val file = File(layoutsDir, "$safeName.json")
        if (file.exists()) file.delete()
        if (getDefaultLayoutName() == name) clearDefaultLayout()
    }

    fun getDefaultLayoutName(): String? =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_DEFAULT_LAYOUT, null)

    fun setDefaultLayoutName(name: String) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_DEFAULT_LAYOUT, name).apply()

    fun clearDefaultLayout() =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().remove(KEY_DEFAULT_LAYOUT).apply()

    companion object {
        private const val PREFS_NAME = "dashboard_prefs"
        private const val KEY_DEFAULT_LAYOUT = "default_layout_name"
    }
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
