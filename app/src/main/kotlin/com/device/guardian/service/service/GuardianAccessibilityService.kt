package com.device.guardian.service.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.Location
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.device.guardian.service.data.local.AppDatabase
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.device.guardian.service.data.local.MessageEntity
import com.device.guardian.service.data.remote.FirebaseRepository
import com.device.guardian.service.service.extractor.MessageExtractor
import com.device.guardian.service.service.filter.MessageFilter
import kotlinx.coroutines.*
import kotlinx.coroutines.tasks.await
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

@Suppress("DEPRECATION")
class GuardianAccessibilityService : AccessibilityService() {

    private lateinit var db: AppDatabase
    private lateinit var firebaseRepo: FirebaseRepository

    private val extractor = MessageExtractor()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // BUG-20 fix: Time-based dedup cache with expiry
    private val dedupCache = ConcurrentHashMap<String, Long>()
    private val DEDUP_MAX = 200
    private val DEDUP_WINDOW_MS = 600_000L // 10 minutes

    companion object {
        private const val TAG = "GuardianService"
        private const val SYNC_INTERVAL_MS = 30_000L
    }

    override fun onServiceConnected() {
        super.onServiceConnected()

        db = AppDatabase.getInstance(this)
        firebaseRepo = FirebaseRepository(db.messageDao(), this)

        serviceInfo = serviceInfo.apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                         AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            notificationTimeout = 100
            packageNames = arrayOf("com.whatsapp", "com.whatsapp.w4b")
        }

        startPeriodicSync()
        startCleanupWorker()
        startStatusSync()
        startDedupCacheCleanup()

        Log.d(TAG, "Service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return

        val pkg = event.packageName?.toString() ?: return
        if (!pkg.startsWith("com.whatsapp")) return

        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return

        val root = rootInActiveWindow ?: return

        val extracted = try {
            extractor.extract(root)
        } catch (e: Exception) {
            Log.w(TAG, "Extraction error: ${e.message}")
            emptyList()
        } finally {
            root.recycle()
        }

        if (extracted.isEmpty()) return

        scope.launch {
            extracted.forEachIndexed { index, rawMessage ->
                val baseTime = if (rawMessage.timestamp > 0L) rawMessage.timestamp else System.currentTimeMillis()
                val adjustedTimestamp = baseTime + index * 10L
                processMessage(rawMessage, adjustedTimestamp)
            }
        }
    }

    private suspend fun processMessage(raw: MessageExtractor.RawMessage, timestamp: Long) {
        val now = System.currentTimeMillis()

        // Step 1 — in-memory dedup with time-based expiry (BUG-20 fix)
        val cacheKey = "${raw.chatName}::${raw.content}::${raw.sender}"
        val lastSeen = dedupCache[cacheKey]
        if (lastSeen != null && (now - lastSeen) < DEDUP_WINDOW_MS) return
        dedupCache[cacheKey] = now

        // Evict oldest entries if cache is too large
        if (dedupCache.size > DEDUP_MAX) {
            val cutoff = now - DEDUP_WINDOW_MS
            dedupCache.entries.removeIf { it.value < cutoff }
        }

        // Step 2 — DB-level dedup (10 minute window)
        val since = timestamp - DEDUP_WINDOW_MS
        val dupes = db.messageDao().countDuplicates(raw.content, raw.chatName, since)
        if (dupes > 0) return

        // Step 3 — analyze
        val filter = MessageFilter.analyze(raw.content)

        // Step 4 — persist locally first
        val entity = MessageEntity(
            id = UUID.randomUUID().toString(),
            content = raw.content,
            sender = raw.sender,
            chatName = raw.chatName,
            timestamp = timestamp,
            isGroupChat = raw.isGroupChat,
            isOutgoing = raw.isOutgoing,
            isFlagged = filter.isFlagged,
            flagReason = filter.reason,
            platform = "whatsapp"
        )
        db.messageDao().insert(entity)
        
        // Sync to Firebase immediately
        try {
            firebaseRepo.syncPending()
        } catch (e: Exception) {
            Log.w(TAG, "Immediate sync failed, periodic sync will retry: ${e.message}")
        }
    }

    private fun startPeriodicSync() {
        scope.launch {
            while (isActive) {
                delay(SYNC_INTERVAL_MS)
                try {
                    firebaseRepo.syncPending()
                } catch (e: Exception) {
                    Log.w(TAG, "Periodic sync error: ${e.message}")
                }
            }
        }
    }

    private fun startStatusSync() {
        scope.launch {
            while (isActive) {
                try {
                    syncStatusNow()
                } catch (e: Exception) {
                    Log.w(TAG, "Status sync error: ${e.message}")
                }
                delay(60_000L)
            }
        }
    }

    // BUG-20 fix: Periodically clean expired entries from dedup cache
    private fun startDedupCacheCleanup() {
        scope.launch {
            while (isActive) {
                delay(DEDUP_WINDOW_MS)
                val cutoff = System.currentTimeMillis() - DEDUP_WINDOW_MS
                dedupCache.entries.removeIf { it.value < cutoff }
            }
        }
    }

    private suspend fun syncStatusNow() {
        val batteryFilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val batteryStatus = registerReceiver(null, batteryFilter)
        val level = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val batteryPct = if (level >= 0 && scale > 0) (level * 100 / scale) else -1
        val isCharging = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) == BatteryManager.BATTERY_STATUS_CHARGING

        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork
        val caps = network?.let { cm.getNetworkCapabilities(it) }
        val isOnline = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true

        var lat: Double? = null
        var lon: Double? = null
        try {
            if (checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                val fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
                // Try getCurrentLocation first
                val currentLocation: Location? = fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null).await()
                if (currentLocation != null) {
                    lat = currentLocation.latitude
                    lon = currentLocation.longitude
                } else {
                    // Fallback to getLastLocation
                    val lastLocation: Location? = fusedLocationClient.lastLocation.await()
                    lastLocation?.let { loc ->
                        lat = loc.latitude
                        lon = loc.longitude
                        Log.d(TAG, "Using last known location (fallback)")
                    }
                }
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "Location permission not granted yet")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get location: ${e.message}")
        }

        firebaseRepo.syncDeviceStatus(batteryPct, isCharging, isOnline, lat, lon)
    }

    private fun startCleanupWorker() {
        scope.launch {
            while (isActive) {
                val cutoff = System.currentTimeMillis() - (30L * 24 * 60 * 60 * 1000L)
                db.messageDao().deleteOlderThan(cutoff)
                delay(24 * 60 * 60 * 1000L)
            }
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "Service interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}
