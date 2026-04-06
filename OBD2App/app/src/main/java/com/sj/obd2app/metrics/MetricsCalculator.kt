package com.sj.obd2app.metrics

import android.content.Context
import android.net.Uri
import android.util.Log
import com.sj.obd2app.gps.GpsDataItem
import com.sj.obd2app.gps.GpsDataSource
import com.sj.obd2app.metrics.calculator.FuelCalculator
import com.sj.obd2app.metrics.calculator.PowerCalculator
import com.sj.obd2app.metrics.calculator.TripCalculator
import com.sj.obd2app.metrics.collector.DataOrchestrator
import com.sj.obd2app.obd.Obd2Command
import com.sj.obd2app.obd.Obd2CommandRegistry
import com.sj.obd2app.obd.Obd2DataItem
import com.sj.obd2app.obd.Obd2ServiceProvider
import com.sj.obd2app.sensors.AccelerometerSource
import com.sj.obd2app.settings.AppSettings
import com.sj.obd2app.settings.FuelType
import com.sj.obd2app.settings.VehicleProfileRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Singleton service that combines live OBD2 + GPS data, computes all
 * primary and derived metrics, and exposes them as [StateFlow<VehicleMetrics>].
 *
 * Also manages trip accumulation and optional JSON logging via [MetricsLogger].
 */
class MetricsCalculator private constructor(private val context: Context) {

    companion object {
        @Volatile
        private var INSTANCE: MetricsCalculator? = null

        fun getInstance(context: Context): MetricsCalculator =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: MetricsCalculator(context.applicationContext).also {
                    INSTANCE = it
                    it.startCollecting()
                }
            }

        // ── Outlier Detection Thresholds ─────────────────────────────────────────
        private const val MAX_SPEED_KMH = 150f
        private const val MAX_RPM_DIESEL = 5000f
        private const val MAX_RPM_PETROL = 9000f
        private const val MAX_MAF_GS = 200f
        private const val MAX_MAP_KPA = 300f
        private const val MAX_ENGINE_LOAD_PCT = 100f
        private const val MAX_CONTROL_MODULE_VOLTAGE = 20f
        private const val MIN_CONTROL_MODULE_VOLTAGE = 8f
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _metrics = MutableStateFlow(VehicleMetrics())
    val metrics: StateFlow<VehicleMetrics> = _metrics

    private val _tripPhase = MutableStateFlow(TripPhase.IDLE)
    val tripPhase: StateFlow<TripPhase> = _tripPhase

    private val _dashboardEditMode = MutableStateFlow(false)
    val dashboardEditMode: StateFlow<Boolean> = _dashboardEditMode

    private val tripState = TripState()
    private val logger = MetricsLogger()

    // New calculator components
    private val fuelCalculator = FuelCalculator()
    private val powerCalculator = PowerCalculator()
    private val tripCalculator = TripCalculator()
    private val dataOrchestrator = DataOrchestrator(context, scope, this)

    /** Elapsed active trip seconds, honouring pauses. 0 when IDLE. */
    fun elapsedTripSec(): Long {
        val start = tripState.tripStartMs
        return ((System.currentTimeMillis() - start) / 1000L).coerceAtLeast(0L)
    }

    private val accelEngine = AccelEngine()
    @Volatile private var vehicleBasis: AccelEngine.VehicleBasis? = null

    /** Gravity vector captured when [startTrip] was last called; null if no trip started or accel disabled. */
    @Volatile var capturedGravityVector: FloatArray? = null
        private set

    /** True if we're waiting for the first gravity reading after a trip start. */
    @Volatile private var waitingForGravityCapture: Boolean = false

    /** Number of samples appended to the current log file (0 if no trip active). */
    val currentSampleNo: Int get() = logger.currentSampleNo

    /** Most recently received OBD2 readings, keyed by PID */
    private var latestObd2: Map<String, String> = emptyMap()

    /** PIDs confirmed supported by the ECU (populated after first poll cycle) */
    var supportedPids: List<Obd2Command> = emptyList()
        private set

    // ── Methods for DataOrchestrator ────────────────────────────────────────

    /** Updates the metrics StateFlow with a new snapshot. */
    internal fun updateMetrics(snapshot: VehicleMetrics) {
        _metrics.value = snapshot
    }

    /** Appends metrics to log if logging is active. */
    internal fun logMetrics(snapshot: VehicleMetrics) {
        if (AppSettings.isLoggingEnabled(context) && logger.isOpen) {
            logger.append(snapshot)
        }
    }

    /** True if logging is currently active. */
    internal val isLoggingActive: Boolean get() = logger.isOpen

    /** Sets the canonical trip phase. Intended to be driven by TripLifecycleFacade. */
    internal fun setTripPhase(phase: TripPhase) {
        _tripPhase.value = phase
    }

    // ── Dashboard Edit Mode ───────────────────────────────────────────────────

    /** Sets the dashboard edit mode state (called by DashboardEditorFragment). */
    fun setDashboardEditMode(isEditMode: Boolean) {
        _dashboardEditMode.value = isEditMode
    }

    // ── Collection ────────────────────────────────────────────────────────────

    private fun startCollecting() {
        dataOrchestrator.startCollecting()
    }

    // ── Trip control ──────────────────────────────────────────────────────────

    internal fun startTripInternal() {
        tripState.reset()
        val accelSource = AccelerometerSource.getInstance(context)
        if (AppSettings.isAccelerometerEnabled(context) && accelSource.isAvailable) {
            accelSource.start()
            // Don't capture yet; wait for first gravity reading in the calculation loop
            waitingForGravityCapture = true
            capturedGravityVector = null
            vehicleBasis = null
        }
        if (AppSettings.isLoggingEnabled(context)) {
            val profile = VehicleProfileRepository.getInstance(context).activeProfile
            logger.open(context, profile, supportedPids)
        }
        // Try auto-connect at trip start if OBD not connected
        val connectionManager = com.sj.obd2app.obd.ObdConnectionManager.getInstance(context)
        val obdConnected = connectionManager.tryConnectForTripStart()
        
        // Start OBD connection monitoring for auto-reconnect during trip
        // Only monitor if OBD was connected at trip start or auto-connect succeeded
        connectionManager.startMonitoring(obdWasConnected = obdConnected)
    }

    internal fun stopTripInternal() {
        tripState.reset()
        AccelerometerSource.getInstance(context).stop()
        vehicleBasis = null
        capturedGravityVector = null
        waitingForGravityCapture = false
        
        // Get current metrics and profile before closing logger
        val currentMetrics = metrics.value
        val currentProfile = VehicleProfileRepository.getInstance(context).activeProfile
        
        logger.close(context, currentMetrics, currentProfile)
        
        // Stop OBD connection monitoring when trip ends
        com.sj.obd2app.obd.ObdConnectionManager.getInstance(context).stopMonitoring()
    }

    fun startTrip() = com.sj.obd2app.metrics.TripLifecycleFacade.getInstance(context).startTrip()

    fun stopTrip() = com.sj.obd2app.metrics.TripLifecycleFacade.getInstance(context).stopTrip()

    fun getLogShareUri() = logger.getShareUri()

    // ── Outlier Detection ─────────────────────────────────────────────────────

    /**
     * Detects outlier values in OBD data based on predefined thresholds.
     * Returns a list of parameter names that exceeded their limits.
     */
    private fun detectOutliers(
        rpm: Float?,
        speed: Float?,
        maf: Float?,
        map: Float?,
        engineLoad: Float?,
        voltage: Float?,
        fuelType: FuelType
    ): List<String> {
        val outliers = mutableListOf<String>()

        // Check RPM based on fuel type
        rpm?.let {
            val maxRpm = when (fuelType) {
                FuelType.DIESEL -> MAX_RPM_DIESEL
                FuelType.PETROL, FuelType.E20, FuelType.CNG -> MAX_RPM_PETROL
            }
            if (it > maxRpm) outliers.add("rpm")
        }

        // Check speed
        speed?.let {
            if (it > MAX_SPEED_KMH) outliers.add("speed")
        }

        // Check MAF
        maf?.let {
            if (it > MAX_MAF_GS) outliers.add("maf")
        }

        // Check MAP
        map?.let {
            if (it > MAX_MAP_KPA) outliers.add("map")
        }

        // Check engine load
        engineLoad?.let {
            if (it > MAX_ENGINE_LOAD_PCT) outliers.add("engineLoad")
        }

        // Check control module voltage
        voltage?.let {
            if (it > MAX_CONTROL_MODULE_VOLTAGE || it < MIN_CONTROL_MODULE_VOLTAGE) {
                outliers.add("voltage")
            }
        }

        return outliers
    }

    // ── Calculation ───────────────────────────────────────────────────────────

    internal fun calculate(
        items: List<Obd2DataItem>,
        gps: GpsDataItem?
    ): VehicleMetrics {
        return try {
            performCalculations(items, gps)
        } catch (e: Exception) {
            Log.e("MetricsCalculator", "Calculation error: ${e.message}")
            // Return empty metrics on failure
            VehicleMetrics(
                timestampMs = System.currentTimeMillis(),
                rpm = null,
                vehicleSpeedKmh = null,
                engineLoadPct = null,
                throttlePct = null,
                coolantTempC = null,
                intakeTempC = null,
                oilTempC = null,
                ambientTempC = null,
                fuelLevelPct = null,
                fuelPressureKpa = null,
                fuelRateLh = null,
                mafGs = null,
                intakeMapKpa = null,
                baroPressureKpa = null,
                timingAdvanceDeg = null,
                stftPct = null,
                ltftPct = null,
                stftBank2Pct = null,
                ltftBank2Pct = null,
                o2Voltage = null,
                controlModuleVoltage = null,
                runTimeSec = null,
                distanceMilOnKm = null,
                distanceSinceCleared = null,
                absoluteLoadPct = null,
                relativeThrottlePct = null,
                accelPedalDPct = null,
                accelPedalEPct = null,
                commandedThrottlePct = null,
                timeMilOnMin = null,
                timeSinceClearedMin = null,
                ethanolPct = null,
                hybridBatteryPct = null,
                fuelInjectionTimingDeg = null,
                driverDemandTorquePct = null,
                actualTorquePct = null,
                engineReferenceTorqueNm = null,
                catalystTempB1S1C = null,
                catalystTempB2S1C = null,
                fuelSystemStatus = null,
                monitorStatus = null,
                fuelTypeStr = null,
                gpsLatitude = null,
                gpsLongitude = null,
                gpsSpeedKmh = null,
                altitudeMslM = null,
                altitudeEllipsoidM = null,
                geoidUndulationM = null,
                gpsAccuracyM = null,
                gpsBearingDeg = null,
                gpsVerticalAccuracyM = null,
                gpsSatelliteCount = null,
                fuelRateEffectiveLh = null,
                instantLper100km = null,
                instantKpl = null,
                tripFuelUsedL = tripState.tripFuelUsedL,
                tripAvgLper100km = null,
                tripAvgKpl = null,
                fuelFlowCcMin = null,
                rangeRemainingKm = null,
                fuelCostEstimate = null,
                avgCo2gPerKm = null,
                tripDistanceKm = tripState.tripDistanceKm,
                tripTimeSec = (System.currentTimeMillis() - tripState.tripStartMs) / 1000L,
                movingTimeSec = tripState.movingTimeSec,
                stoppedTimeSec = tripState.stoppedTimeSec,
                tripAvgSpeedKmh = tripCalculator.averageSpeed(tripState.tripDistanceKm, (System.currentTimeMillis() - tripState.tripStartMs) / 1000L),
                tripMaxSpeedKmh = tripState.maxSpeedKmh,
                spdDiffKmh = null,
                pctCity = 0f,
                pctHighway = 0f,
                pctIdle = 0f,
                powerAccelKw = null,
                powerThermoKw = null,
                powerOBDKw = null,
                accelVertRms = null,
                accelVertMax = null,
                accelVertMean = null,
                accelVertStdDev = null,
                accelVertPeakRatio = null,
                accelFwdRms = null,
                accelFwdMax = null,
                accelFwdMaxBrake = null,
                accelFwdMaxAccel = null,
                accelFwdMean = null,
                accelLatRms = null,
                accelLatMax = null,
                accelLatMean = null,
                accelLeanAngleDeg = null,
                accelRawSampleCount = null
            )
        }
    }

    private fun performCalculations(
        items: List<Obd2DataItem>,
        gps: GpsDataItem?
    ): VehicleMetrics {

        val profile = VehicleProfileRepository.getInstance(context).activeProfile
        val fuelType = profile?.fuelType ?: FuelType.E20

        fun pid(p: String): Float? = latestObd2[p]?.toFloatOrNull()
            ?: items.firstOrNull { it.pid == p }?.value?.toFloatOrNull()

        fun pidStr(p: String): String? = latestObd2[p]
            ?: items.firstOrNull { it.pid == p }?.value

        // Primary OBD2
        val rpm             = pid("010C")
        val obdSpeedKmh     = pid("010D")
        val engineLoad      = pid("0104")
        val throttle        = pid("0111")
        val coolant         = pid("0105")
        val intakeTemp      = pid("010F")
        val oilTemp         = pid("015C")
        val ambientTemp     = pid("0146")
        val fuelLevel       = pid("012F")
        val fuelPressure    = pid("010A")
        val fuelRatePid     = pid("015E")
        val maf             = pid("0110")
        val map             = pid("010B")
        val baro            = pid("0133")
        val timing          = pid("010E")
        val stft1           = pid("0106")
        val ltft1           = pid("0107")
        val stft2           = pid("0108")
        val ltft2           = pid("0109")
        val o2v             = pid("0114")
        val moduleVoltage   = pid("0142")
        val runTime         = pid("011F")?.toInt()
        val distMil         = pid("0121")?.toInt()
        val distCleared     = pid("0131")?.toInt()
        val absLoad         = pid("0143")
        val relThrottle     = pid("0145")
        val pedalD          = pid("0149")
        val pedalE          = pid("014A")
        val cmdThrottle     = pid("014C")
        val timeMilOn       = pid("014D")?.toInt()
        val timeCleared     = pid("014E")?.toInt()
        val ethanol         = pid("0152")
        val hybridBatt      = pid("015B")
        val injTiming       = pid("015D")
        val driverTorque    = pid("0161")
        val actualTorque    = pid("0162")
        val refTorque       = pid("0163")?.toInt()
        val catTempB1S1     = pid("013C")
        val catTempB2S1     = pid("013D")
        val fuelSysStatus   = pidStr("0103")
        val monitorStat     = pidStr("0101")
        val fuelTypeStr     = pidStr("0151")

        // GPS
        val gpsSpeed          = gps?.speedKmh
        val altitude          = gps?.altitudeMsl
        val accuracy          = gps?.accuracyM
        val bearing           = gps?.bearingDeg
        val gpsLat            = gps?.latitude
        val gpsLon            = gps?.longitude
        val altEllipsoid      = gps?.altitudeEllipsoid
        val geoidUndulation   = gps?.geoidUndulation
        val vertAccuracy      = gps?.verticalAccuracyM
        val satelliteCount    = gps?.satelliteCount

        // Effective speed: use hybrid calculation (OBD up to 20 km/h, GPS above 20 km/h)
        val speedKmh = tripCalculator.hybridSpeed(gpsSpeed, obdSpeedKmh) ?: 0f

        // Effective fuel rate (3-tier: PID 015E → MAF → Speed-Density)
        val displacementCc = profile?.engineDisplacementCc ?: 0
        val vePct = profile?.volumetricEfficiencyPct ?: 85f
        val dieselCorrectionFactor = profile?.dieselCorrectionFactor ?: 0.25f
        val fuelRateEffective: Float? = fuelCalculator.effectiveFuelRate(
            fuelRatePid, maf, fuelType.mafMlPerGram,
            mapKpa = map, iatC = intakeTemp, rpm = rpm,
            displacementCc = displacementCc, vePct = vePct,
            fuelType = fuelType, baroKpa = baro, engineLoadPct = engineLoad,
            dieselCorrectionFactor = dieselCorrectionFactor
        )

        // Capture first gravity vector after trip start (if waiting)
        if (waitingForGravityCapture) {
            val accelSource = AccelerometerSource.getInstance(context)
            val gv = accelSource.gravityVector
            if (gv != null) {
                capturedGravityVector = gv.copyOf()
                vehicleBasis = gv.let { accelEngine.computeVehicleBasis(it) }
                waitingForGravityCapture = false
            }
        }

        // Detect outliers in OBD data
        val outlierList = detectOutliers(
            rpm = rpm,
            speed = obdSpeedKmh,
            maf = maf,
            map = map,
            engineLoad = engineLoad,
            voltage = moduleVoltage,
            fuelType = fuelType
        )
        val hasOutliers = outlierList.isNotEmpty()
        val outlierNamesStr = if (hasOutliers) outlierList.joinToString(",") else null

        // Update trip accumulators (skipped when outliers detected)
        if (!hasOutliers) {
            tripState.update(speedKmh, fuelRateEffective ?: 0f)
        }

        // Instantaneous consumption
        val (instantLpk, instantKplVal) = fuelCalculator.instantaneous(fuelRateEffective, speedKmh)

        // Trip averages
        val tripDist = tripState.tripDistanceKm
        val tripFuel = tripState.tripFuelUsedL
        val (tripAvgLpk, tripAvgKpl) = fuelCalculator.tripAverages(tripFuel, tripDist)

        // Range
        val range: Float? = fuelCalculator.range(fuelLevel, profile?.tankCapacityL ?: 40f, tripAvgLpk)

        // Fuel cost
        val cost: Float? = fuelCalculator.cost(tripFuel, profile?.fuelPricePerLitre ?: 0f)

        // CO2
        val co2: Float? = fuelCalculator.co2(tripAvgLpk, fuelType.co2Factor)

        // Trip time
        val now = System.currentTimeMillis()
        val tripTimeMs = (now - tripState.tripStartMs).coerceAtLeast(0L)
        val tripTimeSec = tripTimeMs / 1000L

        // Average speed
        val avgSpeed: Float = tripCalculator.averageSpeed(tripDist, tripTimeSec)

        // Speed diff
        val spdDiff: Float? = tripCalculator.speedDiff(gpsSpeed, obdSpeedKmh)

        // ── Accelerometer ────────────────────────────────────────────────────
        val accelSource = AccelerometerSource.getInstance(context)
        val accelMetrics: AccelMetrics? = if (AppSettings.isAccelerometerEnabled(context)) {
            val basis = vehicleBasis
                ?: accelSource.gravityVector?.let { accelEngine.computeVehicleBasis(it) }
                    .also { vehicleBasis = it }
            val buffer = accelSource.drainBuffer()
            if (buffer.isNotEmpty()) accelEngine.computeAccelMetrics(buffer, basis) else null
        } else null

        // ── Power calculations ───────────────────────────────────────────────
        val speedMs = speedKmh / 3.6f

        val powerAccelKw: Float? = powerCalculator.fromAccelerometer(profile?.vehicleMassKg ?: 0f, accelMetrics?.fwdMean, speedMs)
        val powerThermoKw: Float? = powerCalculator.thermodynamic(fuelRateEffective, fuelType.energyDensityMJpL)
        val powerOBDKw: Float? = powerCalculator.fromObd(actualTorque, refTorque, rpm)

        // Drive mode percentages (calculate once for performance)
        val (pctCity, pctHighway, pctIdle) = tripState.tripDriveModePercents()

        return VehicleMetrics(
            timestampMs            = now,
            rpm                    = rpm,
            vehicleSpeedKmh        = obdSpeedKmh,
            engineLoadPct          = engineLoad,
            throttlePct            = throttle,
            coolantTempC           = coolant,
            intakeTempC            = intakeTemp,
            oilTempC               = oilTemp,
            ambientTempC           = ambientTemp,
            fuelLevelPct           = fuelLevel,
            fuelPressureKpa        = fuelPressure,
            fuelRateLh             = fuelRatePid,
            mafGs                  = maf,
            intakeMapKpa           = map,
            baroPressureKpa        = baro,
            timingAdvanceDeg       = timing,
            stftPct                = stft1,
            ltftPct                = ltft1,
            stftBank2Pct           = stft2,
            ltftBank2Pct           = ltft2,
            o2Voltage              = o2v,
            controlModuleVoltage   = moduleVoltage,
            runTimeSec             = runTime,
            distanceMilOnKm        = distMil,
            distanceSinceCleared   = distCleared,
            absoluteLoadPct        = absLoad,
            relativeThrottlePct    = relThrottle,
            accelPedalDPct         = pedalD,
            accelPedalEPct         = pedalE,
            commandedThrottlePct   = cmdThrottle,
            timeMilOnMin           = timeMilOn,
            timeSinceClearedMin    = timeCleared,
            ethanolPct             = ethanol,
            hybridBatteryPct       = hybridBatt,
            fuelInjectionTimingDeg = injTiming,
            driverDemandTorquePct  = driverTorque,
            actualTorquePct        = actualTorque,
            engineReferenceTorqueNm = refTorque,
            catalystTempB1S1C      = catTempB1S1,
            catalystTempB2S1C      = catTempB2S1,
            fuelSystemStatus       = fuelSysStatus,
            monitorStatus          = monitorStat,
            fuelTypeStr            = fuelTypeStr,
            gpsLatitude            = if (gpsLat != null && gpsLat != 0.0) gpsLat else null,
            gpsLongitude           = if (gpsLon != null && gpsLon != 0.0) gpsLon else null,
            gpsSpeedKmh            = gpsSpeed,
            altitudeMslM           = altitude,
            altitudeEllipsoidM     = altEllipsoid,
            geoidUndulationM       = geoidUndulation,
            gpsAccuracyM           = accuracy,
            gpsBearingDeg          = bearing,
            gpsVerticalAccuracyM   = vertAccuracy,
            gpsSatelliteCount      = satelliteCount,
            fuelRateEffectiveLh    = fuelRateEffective,
            instantLper100km       = instantLpk,
            instantKpl             = instantKplVal,
            tripFuelUsedL          = tripFuel,
            tripAvgLper100km       = tripAvgLpk,
            tripAvgKpl             = tripAvgKpl,
            fuelFlowCcMin          = fuelCalculator.fuelFlowCcMin(fuelRateEffective),
            rangeRemainingKm       = range,
            fuelCostEstimate       = cost,
            avgCo2gPerKm           = co2,
            tripDistanceKm         = tripDist,
            tripTimeSec            = tripTimeSec,
            movingTimeSec          = tripState.movingTimeSec,
            stoppedTimeSec         = tripState.stoppedTimeSec,
            tripAvgSpeedKmh        = avgSpeed,
            tripMaxSpeedKmh        = tripState.maxSpeedKmh,
            spdDiffKmh             = spdDiff,
            pctCity                = pctCity,
            pctHighway             = pctHighway,
            pctIdle                = pctIdle,
            powerThermoKw          = powerThermoKw,
            powerOBDKw             = powerOBDKw,
            accelVertRms           = accelMetrics?.vertRms,
            accelVertMax           = accelMetrics?.vertMax,
            accelVertMean          = accelMetrics?.vertMean,
            accelVertStdDev        = accelMetrics?.vertStdDev,
            accelVertPeakRatio     = accelMetrics?.vertPeakRatio,
            accelFwdRms            = accelMetrics?.fwdRms,
            accelFwdMax            = accelMetrics?.fwdMax,
            accelFwdMaxBrake       = accelMetrics?.fwdMaxBrake,
            accelFwdMaxAccel       = accelMetrics?.fwdMaxAccel,
            accelFwdMean           = accelMetrics?.fwdMean,
            accelLatRms            = accelMetrics?.latRms,
            accelLatMax            = accelMetrics?.latMax,
            accelLatMean           = accelMetrics?.latMean,
            accelLeanAngleDeg      = accelMetrics?.leanAngleDeg,
            accelRawSampleCount    = accelMetrics?.rawAccelSampleCount,
            outlierDetected        = hasOutliers,
            outlierNames           = outlierNamesStr
        )
    }
}
