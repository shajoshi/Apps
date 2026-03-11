package com.sj.obd2app.metrics

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import com.sj.obd2app.obd.Obd2Command
import com.sj.obd2app.settings.AppSettings
import com.sj.obd2app.settings.VehicleProfile
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Writes one JSON log file per trip.
 *
 * File name: <ProfileName>_obdlog_<YYYY-MM-DD_HHmmss>.json
 *
 * File structure:
 * {
 *   "header": { appVersion, logStartedAt, vehicleProfile, supportedPids },
 *   "samples": [ { timestampMs, rpm, speed, … }, … ]
 * }
 *
 * Samples are written one per line inside the array.
 * [repairIfNeeded] can fix a file that was not closed cleanly.
 */
class MetricsLogger {

    private var writer: BufferedWriter? = null
    private var firstSample = true
    private var sampleNo = 0
    private var outputUri: Uri? = null
    fun getShareUri(): Uri? = outputUri
    private var outputFile: File? = null

    val isOpen: Boolean get() = writer != null
    val currentSampleNo: Int get() = sampleNo

    /**
     * Opens (or creates) the log file and writes the header.
     * Must be called before [append].
     */
    fun open(
        context: Context,
        profile: VehicleProfile?,
        supportedPids: List<Obd2Command>
    ) {
        if (isOpen) close()

        val ts = SimpleDateFormat("yyyy-MM-dd_HHmmss", Locale.US).format(Date())
        val safeName = profile?.sanitisedName ?: "Unknown"
        val fileName = "${safeName}_obdlog_${ts}.json"

        writer = buildWriter(context, fileName)?.also { w ->
            firstSample = true
            sampleNo = 0
            w.write(buildHeader(context, profile, supportedPids, ts))
            w.write(",\n  \"samples\": [\n")
            w.flush()
        }
    }

    /** Appends one [VehicleMetrics] snapshot as a JSON object. */
    @Synchronized
    fun append(metrics: VehicleMetrics) {
        val w = writer ?: return
        try {
            if (!firstSample) w.write(",\n")
            w.write("    ")
            w.write(metrics.toJson(++sampleNo).toString())
            w.flush()
            firstSample = false
        } catch (_: Exception) {}
    }

    /** Closes the JSON array and object, then flushes and closes the writer. */
    @Synchronized
    fun close() {
        try {
            writer?.write("\n  ]\n}")
            writer?.flush()
            writer?.close()
        } catch (_: Exception) {}
        writer = null
        firstSample = true
        sampleNo = 0
    }

    /** Logs an error message to the console/logcat */
    fun logError(message: String) {
        android.util.Log.e("MetricsLogger", message)
    }

    // ── Writer factory ────────────────────────────────────────────────────────

    private fun buildWriter(context: Context, fileName: String): BufferedWriter? {
        val folderUriStr = AppSettings.getLogFolderUri(context)

        return if (folderUriStr != null) {
            // SAF-selected folder
            try {
                val folderUri = Uri.parse(folderUriStr)
                val folder = DocumentFile.fromTreeUri(context, folderUri) ?: return fallbackWriter(context, fileName)
                val file = folder.createFile("application/json", fileName) ?: return fallbackWriter(context, fileName)
                outputUri = file.uri
                val os = context.contentResolver.openOutputStream(file.uri, "wa") ?: return fallbackWriter(context, fileName)
                BufferedWriter(OutputStreamWriter(os, Charsets.UTF_8))
            } catch (_: Exception) {
                fallbackWriter(context, fileName)
            }
        } else {
            fallbackWriter(context, fileName)
        }
    }

    private fun fallbackWriter(context: Context, fileName: String): BufferedWriter? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // MediaStore Downloads (no permission needed on API 29+)
            try {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                    put(MediaStore.Downloads.MIME_TYPE, "application/json")
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
                val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: return null
                outputUri = uri
                val os = context.contentResolver.openOutputStream(uri) ?: return null
                BufferedWriter(OutputStreamWriter(os, Charsets.UTF_8))
            } catch (_: Exception) { null }
        } else {
            // Direct file write to Downloads for API < 29
            try {
                val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                dir.mkdirs()
                val file = File(dir, fileName)
                outputFile = file
                outputUri = Uri.fromFile(file)
                BufferedWriter(FileWriter(file))
            } catch (_: Exception) { null }
        }
    }

    // ── Header builder ────────────────────────────────────────────────────────

    private fun buildHeader(
        context: Context,
        profile: VehicleProfile?,
        supportedPids: List<Obd2Command>,
        ts: String
    ): String {
        val isoTs = ts.replace("_", "T").let {
            // Reformat yyyy-MM-ddTHHmmss → yyyy-MM-dd'T'HH:mm:ss
            if (it.length == 17) "${it.substring(0, 13)}:${it.substring(13, 15)}:${it.substring(15, 17)}" else it
        }

        val profileJson = if (profile != null) {
            JSONObject().apply {
                put("name", profile.name)
                put("fuelType", profile.fuelType.name)
                put("fuelTypeDisplay", profile.fuelType.displayName)
                put("energyDensityMJpL", profile.fuelType.energyDensityMJpL)
                put("tankCapacityL", profile.tankCapacityL)
                put("fuelPricePerLitre", profile.fuelPricePerLitre)
                put("enginePowerBhp", profile.enginePowerBhp)
                if (profile.vehicleMassKg > 0f) put("vehicleMassKg", profile.vehicleMassKg)
                put("obdPollingDelayMs", profile.obdPollingDelayMs ?: AppSettings.getGlobalPollingDelayMs(context))
                put("obdCommandDelayMs", profile.obdCommandDelayMs ?: AppSettings.getGlobalCommandDelayMs(context))
            }
        } else JSONObject()

        val pidsJson = JSONArray().also { arr ->
            supportedPids.forEach { cmd ->
                arr.put(JSONObject().apply {
                    put("pid", cmd.pid)
                    put("name", cmd.name)
                    put("unit", cmd.unit)
                })
            }
        }

        val accelCalJson = if (AppSettings.isAccelerometerEnabled(context)) {
            val cal = AccelCalibration()
            JSONObject().apply {
                put("enabled", true)
                put("movingAverageWindow", cal.movingAverageWindow)
                put("peakThresholdZ", cal.peakThresholdZ)
            }
        } else JSONObject().apply { put("enabled", false) }

        val header = JSONObject().apply {
            put("appVersion", getAppVersion(context))
            put("logStartedAt", isoTs)
            put("logStartedAtMs", System.currentTimeMillis())
            put("vehicleProfile", profileJson)
            put("supportedPids", pidsJson)
            put("accelerometer", accelCalJson)
        }

        return "{\n  \"header\": ${header.toString(2)}"
    }

    private fun getAppVersion(context: Context): String = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
    } catch (_: Exception) { "unknown" }

    // ── VehicleMetrics → JSON (nested sub-objects) ────────────────────────────

    private fun VehicleMetrics.toJson(sampleNo: Int): JSONObject = JSONObject().apply {
        put("timestampMs", timestampMs)
        put("sampleNo", sampleNo)

        // gps sub-object — only when any GPS data is available
        val hasGps = gpsLatitude != null || gpsSpeedKmh != null || altitudeMslM != null
        if (hasGps) {
            put("gps", JSONObject().apply {
                gpsLatitude?.let  { put("lat", it) }
                gpsLongitude?.let { put("lon", it) }
                gpsSpeedKmh?.let  { put("speedKmh", it) }
                altitudeMslM?.let { put("altMsl", it) }
                altitudeEllipsoidM?.let { put("altEllipsoid", it) }
                geoidUndulationM?.let   { put("geoidUndulation", it) }
                gpsBearingDeg?.let      { put("bearingDeg", it) }
                gpsAccuracyM?.let       { put("accuracyM", it) }
                gpsVerticalAccuracyM?.let { put("vertAccuracyM", it) }
                gpsSatelliteCount?.let  { put("satelliteCount", it) }
            })
        }

        // obd sub-object — only when at least one OBD field is present
        val hasObd = rpm != null || vehicleSpeedKmh != null || engineLoadPct != null
        if (hasObd) {
            put("obd", JSONObject().apply {
                rpm?.let                  { put("rpm", it) }
                vehicleSpeedKmh?.let      { put("speedKmh", it) }
                engineLoadPct?.let        { put("engineLoadPct", it) }
                throttlePct?.let          { put("throttlePct", it) }
                coolantTempC?.let         { put("coolantTempC", it) }
                intakeTempC?.let          { put("intakeTempC", it) }
                oilTempC?.let             { put("oilTempC", it) }
                ambientTempC?.let         { put("ambientTempC", it) }
                fuelLevelPct?.let         { put("fuelLevelPct", it) }
                fuelPressureKpa?.let      { put("fuelPressureKpa", it) }
                fuelRateLh?.let           { put("fuelRateLh", it) }
                mafGs?.let                { put("mafGs", it) }
                intakeMapKpa?.let         { put("intakeMapKpa", it) }
                baroPressureKpa?.let      { put("baroPressureKpa", it) }
                timingAdvanceDeg?.let     { put("timingAdvanceDeg", it) }
                stftPct?.let              { put("stftPct", it) }
                ltftPct?.let              { put("ltftPct", it) }
                stftBank2Pct?.let         { put("stftBank2Pct", it) }
                ltftBank2Pct?.let         { put("ltftBank2Pct", it) }
                o2Voltage?.let            { put("o2Voltage", it) }
                controlModuleVoltage?.let { put("controlModuleVoltage", it) }
                runTimeSec?.let           { put("runTimeSec", it) }
                distanceMilOnKm?.let      { put("distanceMilOnKm", it) }
                distanceSinceCleared?.let { put("distanceSinceCleared", it) }
                absoluteLoadPct?.let      { put("absoluteLoadPct", it) }
                relativeThrottlePct?.let  { put("relativeThrottlePct", it) }
                accelPedalDPct?.let       { put("accelPedalDPct", it) }
                accelPedalEPct?.let       { put("accelPedalEPct", it) }
                commandedThrottlePct?.let { put("commandedThrottlePct", it) }
                timeMilOnMin?.let         { put("timeMilOnMin", it) }
                timeSinceClearedMin?.let  { put("timeSinceClearedMin", it) }
                ethanolPct?.let           { put("ethanolPct", it) }
                hybridBatteryPct?.let     { put("hybridBatteryPct", it) }
                fuelInjectionTimingDeg?.let  { put("fuelInjectionTimingDeg", it) }
                driverDemandTorquePct?.let   { put("driverDemandTorquePct", it) }
                actualTorquePct?.let         { put("actualTorquePct", it) }
                engineReferenceTorqueNm?.let { put("engineReferenceTorqueNm", it) }
                catalystTempB1S1C?.let    { put("catalystTempB1S1C", it) }
                catalystTempB2S1C?.let    { put("catalystTempB2S1C", it) }
                fuelSystemStatus?.let     { put("fuelSystemStatus", it) }
                monitorStatus?.let        { put("monitorStatus", it) }
                fuelTypeStr?.let          { put("fuelTypeStr", it) }
            })
        }

        // fuel sub-object
        put("fuel", JSONObject().apply {
            fuelRateEffectiveLh?.let { put("fuelRateEffectiveLh", it) }
            instantLper100km?.let   { put("instantLper100km", it) }
            instantKpl?.let         { put("instantKpl", it) }
            put("tripFuelUsedL", tripFuelUsedL)
            tripAvgLper100km?.let   { put("tripAvgLper100km", it) }
            tripAvgKpl?.let         { put("tripAvgKpl", it) }
            fuelFlowCcMin?.let      { put("fuelFlowCcMin", it) }
            rangeRemainingKm?.let   { put("rangeRemainingKm", it) }
            fuelCostEstimate?.let   { put("fuelCostEstimate", it) }
            avgCo2gPerKm?.let       { put("avgCo2gPerKm", it) }
            powerAccelKw?.let       { put("powerAccelKw", it) }
            powerThermoKw?.let      { put("powerThermoKw", it) }
            powerOBDKw?.let         { put("powerOBDKw", it) }
        })

        // accel sub-object — only when accelerometer data is present
        if (accelVertRms != null) {
            put("accel", JSONObject().apply {
                accelVertRms?.let        { put("vertRms", it) }
                accelVertMax?.let        { put("vertMax", it) }
                accelVertMean?.let       { put("vertMean", it) }
                accelVertStdDev?.let     { put("vertStdDev", it) }
                accelVertPeakRatio?.let  { put("vertPeakRatio", it) }
                accelFwdRms?.let         { put("fwdRms", it) }
                accelFwdMax?.let         { put("fwdMax", it) }
                accelFwdMaxBrake?.let    { put("fwdMaxBrake", it) }
                accelFwdMaxAccel?.let    { put("fwdMaxAccel", it) }
                accelFwdMean?.let        { put("fwdMean", it) }
                accelLatRms?.let         { put("latRms", it) }
                accelLatMax?.let         { put("latMax", it) }
                accelLatMean?.let        { put("latMean", it) }
                accelLeanAngleDeg?.let   { put("leanAngleDeg", it) }
                accelRawSampleCount?.let { put("rawSampleCount", it) }
            })
        }

        // trip sub-object
        put("trip", JSONObject().apply {
            put("distanceKm", tripDistanceKm)
            put("timeSec", tripTimeSec)
            put("movingTimeSec", movingTimeSec)
            put("stoppedTimeSec", stoppedTimeSec)
            tripAvgSpeedKmh?.let { put("avgSpeedKmh", it) }
            put("maxSpeedKmh", tripMaxSpeedKmh)
            spdDiffKmh?.let     { put("spdDiffKmh", it) }
            put("pctCity", pctCity)
            put("pctHighway", pctHighway)
            put("pctIdle", pctIdle)
        })
    }
}
