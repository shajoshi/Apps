package com.sj.obd2app.sensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager

/**
 * Provides a continuously buffered stream of LINEAR_ACCELERATION samples and
 * the latest GRAVITY vector for vehicle-frame decomposition.
 *
 * Call [start] at trip start and [stop] at trip stop.
 * Call [drainBuffer] once per OBD2 poll tick to consume accumulated samples.
 */
class AccelerometerSource private constructor(private val context: Context) {

    private val sensorManager: SensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    private val linearAccelSensor: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
    private val gravitySensor: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY)

    @Volatile var gravityVector: FloatArray? = null
        private set

    private val bufferLock = Any()
    private var buffer = mutableListOf<FloatArray>()

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            when (event.sensor.type) {
                Sensor.TYPE_LINEAR_ACCELERATION -> {
                    val sample = event.values.copyOf()
                    synchronized(bufferLock) { buffer.add(sample) }
                }
                Sensor.TYPE_GRAVITY -> {
                    gravityVector = event.values.copyOf()
                }
            }
        }
        override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit
    }

    /**
     * Starts sensor registration at SENSOR_DELAY_GAME (~50 Hz).
     * Safe to call multiple times — re-registers if already stopped.
     */
    fun start() {
        synchronized(bufferLock) { buffer.clear() }
        gravityVector = null
        linearAccelSensor?.let {
            sensorManager.registerListener(listener, it, SensorManager.SENSOR_DELAY_GAME)
        }
        gravitySensor?.let {
            sensorManager.registerListener(listener, it, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    /**
     * Stops sensor registration and clears the internal buffer.
     */
    fun stop() {
        sensorManager.unregisterListener(listener)
        synchronized(bufferLock) { buffer.clear() }
    }

    /**
     * Atomically swaps the internal buffer and returns all accumulated samples
     * since the last call (or since [start]).
     * Returns an empty list if the sensor is unavailable or no samples arrived.
     */
    fun drainBuffer(): List<FloatArray> {
        return synchronized(bufferLock) {
            val drained = buffer
            buffer = mutableListOf()
            drained
        }
    }

    /** True if the device has a LINEAR_ACCELERATION sensor. */
    val isAvailable: Boolean get() = linearAccelSensor != null

    companion object {
        @Volatile
        private var INSTANCE: AccelerometerSource? = null

        fun getInstance(context: Context): AccelerometerSource =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: AccelerometerSource(context.applicationContext).also { INSTANCE = it }
            }
    }
}
