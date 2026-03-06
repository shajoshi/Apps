package com.sj.obd2app.gps

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationManager
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
        
    private val _gpsData = MutableStateFlow<GpsDataItem?>(null)
    val gpsData: StateFlow<GpsDataItem?> = _gpsData

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val loc: Location = result.lastLocation ?: return
            
            // Speed in km/h
            val speedKmh = if (loc.hasSpeed()) loc.speed * 3.6f else 0f
            
            // Raw WGS84 altitude
            val altEllipsoid = if (loc.hasAltitude()) loc.altitude else 0.0
            
            // Correction
            val undulation = GeoidCorrection.getUndulation(loc.latitude, loc.longitude)
            
            // Orthometric height (MSL) = Ellipsoid - Undulation
            val mslAltitude = altEllipsoid - undulation
            
            val item = GpsDataItem(
                speedKmh = speedKmh,
                altitudeMsl = mslAltitude,
                altitudeEllipsoid = altEllipsoid,
                geoidUndulation = undulation,
                accuracyM = if (loc.hasAccuracy()) loc.accuracy else 0f,
                timestampMs = loc.time
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
    }

    fun stop() {
        fusedLocationClient.removeLocationUpdates(locationCallback)
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
