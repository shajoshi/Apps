package com.sj.bkgtracker

import android.app.Application
import android.util.Log
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.google.firebase.messaging.FirebaseMessaging
import com.sj.bkgtracker.data.local.AppSettings
import com.sj.bkgtracker.data.local.ExpressSyncManager
import com.sj.bkgtracker.data.local.LocationCache
import com.sj.bkgtracker.data.local.SyncPrefs
import com.sj.bkgtracker.domain.model.LocationRecord
import com.sj.bkgtracker.worker.SyncWorker
import java.util.concurrent.TimeUnit

class BkgTrackerApp : Application() {

    companion object {
        private const val TAG = "BkgTrackerApp"
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Application onCreate")
        SyncPrefs.load(this)
        LocationCache.initialise(this)
        ExpressSyncManager.initialise(this)
        subscribeFcmTopic()
        runCacheValidationTest()
        scheduleSync()
    }

    private fun subscribeFcmTopic() {
        FirebaseMessaging.getInstance().subscribeToTopic("bkgtracker_family")
            .addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    Log.d(TAG, "FCM topic 'bkgtracker_family' subscribed")
                } else {
                    Log.e(TAG, "FCM topic subscribe failed", task.exception)
                }
            }
    }

    private fun runCacheValidationTest() {
        try {
            Log.d(TAG, "=== Cache Validation Test Starting ===")

            // Clear any existing test data
            AppSettings.clearCache(this)

            // Test 1: Append 10 records individually
            val testRecords = (1..10).map { i ->
                LocationRecord(
                    latitude = 18.56 + i * 0.001,
                    longitude = 73.80 + i * 0.001,
                    timestampMs = System.currentTimeMillis() + i * 1000,
                    accuracyM = 5f + i,
                    speedKmh = 10f + i,
                    altitudeM = 500.0 + i,
                    bearingDeg = if (i % 2 == 0) i * 10f else null
                )
            }

            val initialSize = AppSettings.getCacheFileSize(this)
            Log.d(TAG, "Initial cache file size: $initialSize bytes")

            // Append each record individually (O(1) operation)
            val startTime = System.currentTimeMillis()
            testRecords.forEach { record ->
                AppSettings.appendRecord(this, record)
            }
            val appendTime = System.currentTimeMillis() - startTime
            Log.d(TAG, "Appended ${testRecords.size} records in ${appendTime}ms (${appendTime / testRecords.size}ms per record)")

            val afterAppendSize = AppSettings.getCacheFileSize(this)
            Log.d(TAG, "Cache file size after append: $afterAppendSize bytes")

            // Test 2: Load all records back
            val loadedRecords = AppSettings.loadCache(this)
            Log.d(TAG, "Loaded ${loadedRecords.size} records from cache")

            // Test 3: Verify data integrity
            val allMatch = testRecords.zip(loadedRecords).all { (original, loaded) ->
                original.latitude == loaded.latitude &&
                original.longitude == loaded.longitude &&
                original.timestampMs == loaded.timestampMs &&
                original.accuracyM == loaded.accuracyM &&
                original.speedKmh == loaded.speedKmh &&
                original.altitudeM == loaded.altitudeM &&
                original.bearingDeg == loaded.bearingDeg
            }

            if (allMatch && testRecords.size == loadedRecords.size) {
                Log.d(TAG, "✓ Data integrity verified: All ${testRecords.size} records match")
            } else {
                Log.e(TAG, "✗ Data integrity FAILED: Expected ${testRecords.size}, got ${loadedRecords.size}, match=$allMatch")
            }

            // Test 4: Clear cache
            AppSettings.clearCache(this)
            val afterClear = AppSettings.loadCache(this)
            if (afterClear.isEmpty()) {
                Log.d(TAG, "✓ Cache clear works: ${afterClear.size} records after clear")
            } else {
                Log.e(TAG, "✗ Cache clear FAILED: Still has ${afterClear.size} records")
            }

            Log.d(TAG, "=== Cache Validation Test Complete ===")

        } catch (e: Exception) {
            Log.e(TAG, "Cache validation test failed", e)
        }
    }

    private fun scheduleSync() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val syncRequest = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            SyncWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            syncRequest
        )
        Log.d(TAG, "WorkManager sync scheduled (15-min, network-connected)")
    }
}
