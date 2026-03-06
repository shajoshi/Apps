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
     */
    fun getSavedLayouts(): List<DashboardLayout> {
        val files = layoutsDir.listFiles { _, name -> name.endsWith(".json") } ?: return emptyList()
        return files.mapNotNull { file ->
            try {
                gson.fromJson(file.readText(), DashboardLayout::class.java)
            } catch (e: Exception) {
                null
            }
        }
    }

    fun deleteLayout(name: String) {
        val safeName = name.replace(Regex("[^A-Za-z0-9 _-]"), "")
        val file = File(layoutsDir, "$safeName.json")
        if (file.exists()) file.delete()
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
            else -> throw JsonParseException("Unknown DashboardMetric type: $type")
        }
    }
}
