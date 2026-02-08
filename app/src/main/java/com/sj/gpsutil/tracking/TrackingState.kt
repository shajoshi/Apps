package com.sj.gpsutil.tracking

import android.location.Location
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class TrackingStatus {
    Idle,
    Recording,
    Paused
}

data class TrackingSample(
    val latitude: Double,
    val longitude: Double,
    val altitudeMeters: Double?,
    val speedKmph: Double?,
    val bearingDegrees: Float?,
    val verticalAccuracyMeters: Float?,
    val accuracyMeters: Float?,
    val satelliteCount: Int?,
    val timestampMillis: Long,
    val accelXMean: Float? = null,
    val accelYMean: Float? = null,
    val accelZMean: Float? = null,
    val accelVertMean: Float? = null,
    val accelMagnitudeMax: Float? = null,
    val meanMagnitude: Float? = null,
    val accelRMS: Float? = null,
    val roadQuality: String? = null,
    val featureDetected: String? = null,
    val peakRatio: Float? = null,
    val stdDev: Float? = null,
    val avgRms: Float? = null,
    val avgMaxMagnitude: Float? = null,
    val avgMeanMagnitude: Float? = null,
    val avgStdDev: Float? = null,
    val avgPeakRatio: Float? = null,
    val rawAccelData: List<FloatArray>? = null,
    val gravityVector: FloatArray? = null,
    val manualLabel: String? = null,
    val manualFeatureLabel: String? = null
)

object TrackingState {
    private val _status = MutableStateFlow(TrackingStatus.Idle)
    private val _latestSample = MutableStateFlow<TrackingSample?>(null)
    private val _elapsedMillis = MutableStateFlow(0L)
    private val _recordingStartMillis = MutableStateFlow<Long?>(null)
    private val _pointCount = MutableStateFlow(0L)
    private val _satelliteCount = MutableStateFlow(0)
    private val _currentFileName = MutableStateFlow<String?>(null)
    private val _distanceMeters = MutableStateFlow(0.0)
    private val _lastDistanceSample = MutableStateFlow<TrackingSample?>(null)
    private val _minAccuracyForDistanceCalcMeters = MutableStateFlow(5f)
    private val _notMovingMillis = MutableStateFlow(0L)
    private val _lastMovementTimestampMillis = MutableStateFlow<Long?>(null)
    private val _skippedPoints = MutableStateFlow(0L)
    private val _manualLabel = MutableStateFlow<String?>(null)
    private val _manualLabelStartTime = MutableStateFlow<Long?>(null)
    private val _pendingFeatureLabel = MutableStateFlow<String?>(null)
    private val _pendingFeatureTimestamp = MutableStateFlow<Long?>(null)
    private val _gravityVector = MutableStateFlow<FloatArray?>(null)

    val status = _status.asStateFlow()
    val latestSample = _latestSample.asStateFlow()
    val elapsedMillis = _elapsedMillis.asStateFlow()
    val recordingStartMillis = _recordingStartMillis.asStateFlow()
    val pointCount = _pointCount.asStateFlow()
    val satelliteCount = _satelliteCount.asStateFlow()
    val currentFileName = _currentFileName.asStateFlow()
    val distanceMeters = _distanceMeters.asStateFlow()
    val minAccuracyForDistanceCalcMeters = _minAccuracyForDistanceCalcMeters.asStateFlow()
    val notMovingMillis = _notMovingMillis.asStateFlow()
    val skippedPoints = _skippedPoints.asStateFlow()
    val manualLabel = _manualLabel.asStateFlow()
    val manualLabelStartTime = _manualLabelStartTime.asStateFlow()
    val pendingFeatureLabel = _pendingFeatureLabel.asStateFlow()
    val pendingFeatureTimestamp = _pendingFeatureTimestamp.asStateFlow()
    val gravityVector = _gravityVector.asStateFlow()

    fun updateStatus(status: TrackingStatus) {
        _status.value = status
    }

    fun updateSample(sample: TrackingSample) {
        _latestSample.value = sample
    }

    fun onRecordingStarted() {
        if (_recordingStartMillis.value == null) {
            _recordingStartMillis.value = System.currentTimeMillis()
        }
        _distanceMeters.value = 0.0
        _lastDistanceSample.value = null
        resetNotMovingTimer()
    }

    fun onRecordingPaused() {
        val start = _recordingStartMillis.value ?: return
        _elapsedMillis.value += System.currentTimeMillis() - start
        _recordingStartMillis.value = null
    }

    fun onRecordingStopped() {
        _recordingStartMillis.value = null
        _elapsedMillis.value = 0L
        _pointCount.value = 0L
        _satelliteCount.value = 0
        _currentFileName.value = null
        _distanceMeters.value = 0.0
        _lastDistanceSample.value = null
        resetNotMovingTimer()
        _skippedPoints.value = 0L
    }

    fun onSampleRecorded(sample: TrackingSample) {
        val accuracyMeters = sample.accuracyMeters
        if (accuracyMeters != null && accuracyMeters > _minAccuracyForDistanceCalcMeters.value) {
            return
        }
        val previous = _lastDistanceSample.value
        if (previous != null) {
            val segmentMeters = distanceMetersBetween(previous, sample)
            _distanceMeters.value = _distanceMeters.value + segmentMeters
        }
        _lastDistanceSample.value = sample
    }

    private fun distanceMetersBetween(a: TrackingSample, b: TrackingSample): Double {
        val results = FloatArray(1)
        Location.distanceBetween(a.latitude, a.longitude, b.latitude, b.longitude, results)
        return results[0].toDouble()
    }

    fun updateMinAccuracyForDistanceCalcMeters(value: Float) {
        if (value.isFinite() && value > 0f) {
            _minAccuracyForDistanceCalcMeters.value = value
        }
    }

    fun resetNotMovingTimer(nowMillis: Long = System.currentTimeMillis()) {
        _lastMovementTimestampMillis.value = nowMillis
        _notMovingMillis.value = 0L
    }

    fun markMovement(nowMillis: Long) {
        _lastMovementTimestampMillis.value = nowMillis
        _notMovingMillis.value = 0L
    }

    fun updateNotMoving(nowMillis: Long) {
        val last = _lastMovementTimestampMillis.value
        if (last == null) {
            _lastMovementTimestampMillis.value = nowMillis
            _notMovingMillis.value = 0L
            return
        }
        _notMovingMillis.value = (nowMillis - last).coerceAtLeast(0L)
    }

    fun incrementPointCount() {
        _pointCount.value = _pointCount.value + 1
    }

    fun resetPointCount() {
        _pointCount.value = 0L
    }

    fun incrementSkippedPoints() {
        _skippedPoints.value = _skippedPoints.value + 1
    }

    fun resetSkippedPoints() {
        _skippedPoints.value = 0L
    }

    fun updateSatelliteCount(count: Int) {
        _satelliteCount.value = count
    }

    fun updateCurrentFileName(name: String?) {
        _currentFileName.value = name
    }

    fun setManualLabel(label: String?) {
        _manualLabel.value = label
        _manualLabelStartTime.value = if (label != null) System.currentTimeMillis() else null
    }

    fun setPendingFeatureLabel(label: String?) {
        _pendingFeatureLabel.value = label
        _pendingFeatureTimestamp.value = if (label != null) System.currentTimeMillis() else null
    }

    fun consumePendingFeatureLabel(): String? {
        val label = _pendingFeatureLabel.value
        val timestamp = _pendingFeatureTimestamp.value
        if (label != null && timestamp != null) {
            // Only consume if 5 seconds have passed since button press
            val elapsed = System.currentTimeMillis() - timestamp
            if (elapsed >= 5000) {
                _pendingFeatureLabel.value = null
                _pendingFeatureTimestamp.value = null
                return label
            }
        }
        return null
    }

    fun setGravityVector(vector: FloatArray?) {
        _gravityVector.value = vector
    }
}
