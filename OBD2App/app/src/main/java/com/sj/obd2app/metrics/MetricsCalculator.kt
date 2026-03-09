package com.sj.obd2app.metrics

import android.content.Context
import com.sj.obd2app.gps.GpsDataItem
import com.sj.obd2app.gps.GpsDataSource
import com.sj.obd2app.obd.Obd2Command
import com.sj.obd2app.obd.Obd2CommandRegistry
import com.sj.obd2app.obd.Obd2DataItem
import com.sj.obd2app.obd.Obd2ServiceProvider
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
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _metrics = MutableStateFlow(VehicleMetrics())
    val metrics: StateFlow<VehicleMetrics> = _metrics

    private val tripState = TripState()
    private val logger = MetricsLogger()
    @Volatile private var isTripPaused = false

    /** Most recently received OBD2 readings, keyed by PID */
    private var latestObd2: Map<String, String> = emptyMap()

    /** PIDs confirmed supported by the ECU (populated after first poll cycle) */
    var supportedPids: List<Obd2Command> = emptyList()
        private set

    // ── Collection ────────────────────────────────────────────────────────────

    private fun startCollecting() {
        val obdService = Obd2ServiceProvider.getService()
        val gpsSource  = GpsDataSource.getInstance(context)

        scope.launch {
            obdService.obd2Data.collect { items ->
                latestObd2 = items.associate { it.pid to it.value }
                if (supportedPids.isEmpty() && items.isNotEmpty()) {
                    supportedPids = Obd2CommandRegistry.commands.filter { cmd ->
                        items.any { it.pid == cmd.pid }
                    }
                }
                // Persist last-known values for this vehicle profile
                val profileId = VehicleProfileRepository.getInstance(context).activeProfile?.id
                PidAvailabilityStore.update(context, profileId, latestObd2)

                val gps = gpsSource.gpsData.value
                val snapshot = calculate(items, gps)
                _metrics.value = snapshot
                if (AppSettings.isLoggingEnabled(context) && logger.isOpen) {
                    logger.append(snapshot)
                }
            }
        }

        scope.launch {
            gpsSource.gpsData.collect { gps ->
                val items = latestObd2.entries.map { (pid, value) ->
                    val cmd = Obd2CommandRegistry.commands.firstOrNull { it.pid == pid }
                    Obd2DataItem(pid = pid, name = cmd?.name ?: pid, value = value, unit = cmd?.unit ?: "")
                }
                val snapshot = calculate(items, gps)
                _metrics.value = snapshot
                if (AppSettings.isLoggingEnabled(context) && logger.isOpen) {
                    logger.append(snapshot)
                }
            }
        }
    }

    // ── Trip control ──────────────────────────────────────────────────────────

    fun startTrip() {
        tripState.reset()
        if (AppSettings.isLoggingEnabled(context)) {
            val profile = VehicleProfileRepository.getInstance(context).activeProfile
            logger.open(context, profile, supportedPids)
        }
    }

    fun pauseTrip() {
        isTripPaused = true
    }

    fun resumeTrip() {
        isTripPaused = false
    }

    fun stopTrip() {
        isTripPaused = false
        tripState.reset()
        logger.close()
    }

    fun getLogShareUri() = logger.getShareUri()

    // ── Calculation ───────────────────────────────────────────────────────────

    private fun calculate(
        items: List<Obd2DataItem>,
        gps: GpsDataItem?
    ): VehicleMetrics {

        val profile = VehicleProfileRepository.getInstance(context).activeProfile
        val fuelType = profile?.fuelType ?: FuelType.PETROL

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
        val gpsSpeed    = gps?.speedKmh
        val altitude    = gps?.altitudeMsl
        val accuracy    = gps?.accuracyM
        val bearing     = null as Float? // GpsDataItem doesn't carry bearing yet

        // Effective speed: prefer GPS, fall back to OBD
        val speedKmh = gpsSpeed ?: obdSpeedKmh ?: 0f

        // Effective fuel rate
        val fuelRateEffective: Float? = when {
            fuelRatePid != null && fuelRatePid > 0f -> fuelRatePid
            maf != null && maf > 0f -> (maf * fuelType.mafLitreFactor * 3600.0).toFloat()
            else -> null
        }

        // Update trip accumulators (skipped when paused)
        if (!isTripPaused) tripState.update(speedKmh, fuelRateEffective ?: 0f)

        // Instantaneous consumption
        val instantLpk: Float? = if (fuelRateEffective != null && speedKmh > 2f)
            (fuelRateEffective * 100f) / speedKmh else null
        val instantKpl: Float? = if (instantLpk != null && instantLpk > 0f)
            100f / instantLpk else null

        // Trip averages
        val tripDist = tripState.tripDistanceKm
        val tripFuel = tripState.tripFuelUsedL
        val tripAvgLpk: Float? = if (tripDist > 0.1f) (tripFuel * 100f) / tripDist else null
        val tripAvgKpl: Float? = if (tripAvgLpk != null && tripAvgLpk > 0f) 100f / tripAvgLpk else null

        // Range
        val range: Float? = if (fuelLevel != null && tripAvgLpk != null && tripAvgLpk > 0f)
            (fuelLevel / 100f * (profile?.tankCapacityL ?: 40f)) / (tripAvgLpk / 100f) else null

        // Fuel cost
        val cost: Float? = if (profile != null && profile.fuelPricePerLitre > 0f)
            tripFuel * profile.fuelPricePerLitre else null

        // CO2
        val co2: Float? = if (tripAvgLpk != null)
            tripAvgLpk * fuelType.co2Factor.toFloat() else null

        // Trip time
        val now = System.currentTimeMillis()
        val tripTimeSec = (now - tripState.tripStartMs) / 1000L

        // Average speed
        val movingSec = tripState.movingTimeSec
        val avgSpeed: Float? = if (movingSec > 0) tripDist / (movingSec / 3600f) else null

        // Speed diff
        val spdDiff: Float? = if (gpsSpeed != null && obdSpeedKmh != null)
            gpsSpeed - obdSpeedKmh else null

        // Drive mode
        val (pctCity, pctHwy, pctIdle) = tripState.driveModePercents()

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
            gpsSpeedKmh            = gpsSpeed,
            altitudeMslM           = altitude,
            gpsAccuracyM           = accuracy,
            gpsBearingDeg          = bearing,
            fuelRateEffectiveLh    = fuelRateEffective,
            instantLper100km       = instantLpk,
            instantKpl             = instantKpl,
            tripFuelUsedL          = tripFuel,
            tripAvgLper100km       = tripAvgLpk,
            tripAvgKpl             = tripAvgKpl,
            fuelFlowCcMin          = fuelRateEffective?.let { it * 1000f / 60f },
            rangeRemainingKm       = range,
            fuelCostEstimate       = cost,
            avgCo2gPerKm           = co2,
            tripDistanceKm         = tripDist,
            tripTimeSec            = tripTimeSec,
            movingTimeSec          = movingSec,
            stoppedTimeSec         = tripState.stoppedTimeSec,
            tripAvgSpeedKmh        = avgSpeed,
            tripMaxSpeedKmh        = tripState.maxSpeedKmh,
            spdDiffKmh             = spdDiff,
            pctCity                = pctCity,
            pctHighway             = pctHwy,
            pctIdle                = pctIdle
        )
    }
}
