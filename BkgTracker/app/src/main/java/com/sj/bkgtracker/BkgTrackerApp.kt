package com.sj.bkgtracker

import android.app.Application
import android.util.Log
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.google.firebase.messaging.FirebaseMessaging
import com.sj.bkgtracker.data.local.ExpressSyncManager
import com.sj.bkgtracker.data.local.LocationCache
import com.sj.bkgtracker.data.local.SyncPrefs
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
