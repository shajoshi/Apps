package com.sj.obd2app.gps

import android.annotation.SuppressLint
import android.content.Context
import android.location.GnssStatus
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.Looper
import com.google.android.gms.location.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Provides a continuous StateFlow of GPS metrics including speed and Geoid-corrected altitude.
 * Uses FusedLocationProviderClient for optimal accuracy.
 */
class GpsDataSource private constructor(private val context: Context) {

    private val fusedLocationClient: FusedLocationProviderClient = 
        LocationServices.getFusedLocationProviderClient(context)

    private val locationManager: LocationManager =
        context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    private val _gpsData = MutableStateFlow<GpsDataItem?>(null)
    val gpsData: StateFlow<GpsDataItem?> = _gpsData

    @Volatile private var latestSatelliteCount: Int? = null
    @Volatile private var activeGnssCallback: Any? = null

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.N)
    private fun buildGnssCallback() = object : GnssStatus.Callback() {
        override fun onSatelliteStatusChanged(status: GnssStatus) {
            var usedCount = 0
            for (i in 0 until status.satelliteCount) {
                if (status.usedInFix(i)) usedCount++
            }
            latestSatelliteCount = usedCount
        }
    }

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val loc: Location = result.lastLocation ?: return

            val speedKmh = if (loc.hasSpeed()) loc.speed * 3.6f else 0f
            val altEllipsoid = if (loc.hasAltitude()) loc.altitude else 0.0
            val undulation = GeoidCorrection.getUndulation(loc.latitude, loc.longitude)
            val mslAltitude = altEllipsoid - undulation
            val bearing = if (loc.hasBearing()) loc.bearing else null
            val vertAccuracy = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && loc.hasVerticalAccuracy())
                loc.verticalAccuracyMeters else null

            val item = GpsDataItem(
                speedKmh          = speedKmh,
                altitudeMsl       = mslAltitude,
                altitudeEllipsoid = altEllipsoid,
                geoidUndulation   = undulation,
                accuracyM         = if (loc.hasAccuracy()) loc.accuracy else 0f,
                timestampMs       = loc.time,
                latitude          = loc.latitude,
                longitude         = loc.longitude,
                bearingDeg        = bearing,
                verticalAccuracyM = vertAccuracy,
                satelliteCount    = latestSatelliteCount
            )

            _gpsData.value = item
        }
    }

    /**
     * Starts requesting location updates.
     * Ensure ACCESS_FINE_LOCATION permission is granted before calling.
     */
    @SuppressLint("MissingPermission")
    fun start() {
        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L)
            .setMinUpdateIntervalMillis(500L)
            .build()
        fusedLocationClient.requestLocationUpdates(
            locationRequest,
            locationCallback,
            Looper.getMainLooper()
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val cb = buildGnssCallback()
            locationManager.registerGnssStatusCallback(cb, android.os.Handler(Looper.getMainLooper()))
            activeGnssCallback = cb
        }
    }

    fun stop() {
        fusedLocationClient.removeLocationUpdates(locationCallback)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            @Suppress("UNCHECKED_CAST")
            (activeGnssCallback as? GnssStatus.Callback)?.let {
                locationManager.unregisterGnssStatusCallback(it)
            }
        }
        activeGnssCallback = null
        latestSatelliteCount = null
    }

    companion object {
        @Volatile
        private var INSTANCE: GpsDataSource? = null

        fun getInstance(context: Context): GpsDataSource {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: GpsDataSource(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}
