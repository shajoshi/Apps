package com.sj.bkgtracker

import android.app.Application
import android.util.Log
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
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
        scheduleSync()
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
