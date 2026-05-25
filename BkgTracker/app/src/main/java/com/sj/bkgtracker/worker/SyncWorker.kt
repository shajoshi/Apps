package com.sj.bkgtracker.worker

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.sj.bkgtracker.data.local.LocationCache
import com.sj.bkgtracker.data.local.SyncPrefs
import com.sj.bkgtracker.data.repository.LocationRepositoryImpl

class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "SyncWorker"
        const val WORK_NAME = "location_sync"
    }

    override suspend fun doWork(): Result {
        val records = LocationCache.drainAll(applicationContext)
        if (records.isEmpty()) {
            Log.d(TAG, "Nothing to sync")
            return Result.success()
        }

        Log.d(TAG, "Uploading ${records.size} records")
        return LocationRepositoryImpl().uploadBatch(records).fold(
            onSuccess = {
                Log.d(TAG, "Upload success")
                SyncPrefs.updateLastSync(applicationContext, success = true)
                Result.success()
            },
            onFailure = { e ->
                Log.e(TAG, "Upload failed: ${e.javaClass.simpleName}: ${e.message}", e)
                LocationCache.requeue(applicationContext, records)
                SyncPrefs.updateLastSync(applicationContext, success = false)
                Result.retry()
            }
        )
    }
}
