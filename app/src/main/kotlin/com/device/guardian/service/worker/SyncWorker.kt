package com.device.guardian.service.worker

import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.device.guardian.service.data.local.AppDatabase
import com.device.guardian.service.data.remote.FirebaseRepository

// BUG-16 fix: Removed `private val context` that shadowed applicationContext.
// CoroutineWorker already provides applicationContext.
class SyncWorker(
    appContext: android.content.Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        return try {
            val db = AppDatabase.getInstance(applicationContext)
            val repo = FirebaseRepository(db.messageDao(), applicationContext)
            
            repo.syncPending()
            Result.success()
        } catch (e: Exception) {
            Log.e("SyncWorker", "Background sync failed", e)
            Result.retry()
        }
    }
}
