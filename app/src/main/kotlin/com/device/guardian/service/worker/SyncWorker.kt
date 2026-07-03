package com.device.guardian.service.worker

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.device.guardian.service.data.local.AppDatabase
import com.device.guardian.service.data.remote.FirebaseRepository

class SyncWorker(
    private val context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            val db = AppDatabase.getInstance(context)
            val repo = FirebaseRepository(db.messageDao(), context)
            
            repo.syncPending()
            Result.success()
        } catch (e: Exception) {
            Log.e("SyncWorker", "Background sync failed", e)
            Result.retry()
        }
    }
}
