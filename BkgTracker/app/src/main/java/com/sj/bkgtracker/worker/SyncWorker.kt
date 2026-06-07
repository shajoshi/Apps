package com.sj.bkgtracker.worker

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.sj.bkgtracker.data.local.SyncPrefs
import com.sj.bkgtracker.data.local.UnifiedLocationCache
import com.sj.bkgtracker.data.repository.LocationRepositoryImpl
import com.google.firebase.auth.FirebaseAuth

class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "SyncWorker"
        const val WORK_NAME = "location_sync"
    }

    override suspend fun doWork(): Result {
        // Set current user for unified cache operations
        val userId = FirebaseAuth.getInstance().currentUser?.uid
        if (userId != null) {
            UnifiedLocationCache.setCurrentUser(userId)
        }
        
        val records = UnifiedLocationCache.drainUnsavedPoints(applicationContext)
        if (records.isEmpty()) {
            Log.d(TAG, "Nothing to sync")
            return Result.success()
        }

        Log.d(TAG, "Uploading ${records.size} records")
        return LocationRepositoryImpl(applicationContext).uploadBatch(records).fold(
            onSuccess = {
                Log.d(TAG, "Upload success")
                SyncPrefs.updateLastSync(applicationContext, success = true)
                // Add synced points to unified cache
                if (userId != null && records.isNotEmpty()) {
                    UnifiedLocationCache.addPoints(userId, records)
                    Log.d(TAG, "Added ${records.size} synced points to unified cache for current user")
                }
                Result.success()
            },
            onFailure = { e ->
                Log.e(TAG, "Upload failed: ${e.javaClass.simpleName}: ${e.message}", e)
                UnifiedLocationCache.requeueUnsavedPoints(applicationContext, records)
                SyncPrefs.updateLastSync(applicationContext, success = false)
                Result.retry()
            }
        )
    }
}
