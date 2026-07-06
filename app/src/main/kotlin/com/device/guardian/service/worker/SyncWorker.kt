package com.device.guardian.service.worker

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.device.guardian.service.data.local.AppDatabase
import com.device.guardian.service.data.remote.FirebaseRepository
import com.device.guardian.service.utils.LocalLogImporter
import kotlinx.coroutines.tasks.await

// Periodic background worker that syncs ALL data to Firestore:
// 1. Device status (battery, connectivity, location)
// 2. New SMS/call logs imported from the device
// 3. Any pending unsynced messages in Room DB
class SyncWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    companion object {
        private const val TAG = "SyncWorker"
    }

    override suspend fun doWork(): Result {
        val context = applicationContext
        return try {
            val db = AppDatabase.getInstance(context)
            val repo = FirebaseRepository(db.messageDao(), context)

            // 1. Sync device status (battery, connectivity, location)
            try {
                syncDeviceStatus(context, repo)
            } catch (e: Exception) {
                Log.w(TAG, "Device status sync failed: ${e.message}")
            }

            // 2. Import new SMS + call logs from device
            try {
                LocalLogImporter.importHistory(context, db, repo)
            } catch (e: Exception) {
                Log.w(TAG, "Log import failed: ${e.message}")
            }

            // 3. Sync any pending messages to Firestore
            repo.syncPending()

            Log.d(TAG, "Background sync completed successfully")
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Background sync failed", e)
            Result.retry()
        }
    }

    @Suppress("DEPRECATION")
    private suspend fun syncDeviceStatus(context: Context, repo: FirebaseRepository) {
        // Battery
        val batteryFilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val batteryStatus = context.registerReceiver(null, batteryFilter)
        val level = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val batteryPct = if (level >= 0 && scale > 0) (level * 100 / scale) else -1
        val isCharging = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) == BatteryManager.BATTERY_STATUS_CHARGING

        // Connectivity
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork
        val caps = network?.let { cm.getNetworkCapabilities(it) }
        val isOnline = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true

        // Location
        var lat: Double? = null
        var lon: Double? = null
        if (context.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            try {
                val fusedLocationClient = com.google.android.gms.location.LocationServices.getFusedLocationProviderClient(context)
                // Try getCurrentLocation first
                val currentLocation = fusedLocationClient.getCurrentLocation(
                    com.google.android.gms.location.Priority.PRIORITY_HIGH_ACCURACY, null
                ).await()
                if (currentLocation != null) {
                    lat = currentLocation.latitude
                    lon = currentLocation.longitude
                } else {
                    // Fallback to getLastLocation
                    val lastLocation = fusedLocationClient.lastLocation.await()
                    if (lastLocation != null) {
                        lat = lastLocation.latitude
                        lon = lastLocation.longitude
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Location fetch failed: ${e.message}")
            }
        }

        repo.syncDeviceStatus(batteryPct, isCharging, isOnline, lat, lon)
        Log.d(TAG, "Device status synced: battery=$batteryPct%, online=$isOnline, lat=$lat, lon=$lon")
    }
}
