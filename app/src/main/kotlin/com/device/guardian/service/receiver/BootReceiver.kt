package com.device.guardian.service.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.ExistingPeriodicWorkPolicy
import com.device.guardian.service.worker.SyncWorker
import java.util.concurrent.TimeUnit

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON",
            "com.htc.intent.action.QUICKBOOT_POWERON" -> {
                Log.d("BootReceiver", "Boot complete — scheduling SyncWorker")
                
                val syncRequest = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
                    .build()
                    
                WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                    "GuardianSyncWork",
                    ExistingPeriodicWorkPolicy.KEEP,
                    syncRequest
                )
            }
        }
    }
}
