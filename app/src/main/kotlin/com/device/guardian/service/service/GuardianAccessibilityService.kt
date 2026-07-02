package com.device.guardian.service.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.SharedPreferences
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.device.guardian.service.data.local.AppDatabase
import com.device.guardian.service.data.local.MessageEntity
import com.device.guardian.service.data.remote.FirebaseRepository
import com.device.guardian.service.service.extractor.MessageExtractor
import com.device.guardian.service.service.filter.MessageFilter
import kotlinx.coroutines.*
import java.util.UUID

class GuardianAccessibilityService : AccessibilityService() {

    private lateinit var db: AppDatabase
    private lateinit var firebaseRepo: FirebaseRepository
    private lateinit var prefs: SharedPreferences

    private val extractor = MessageExtractor()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Dedup cache — prevent same message being stored multiple times
    private val dedupCache = LinkedHashSet<String>()
    private val DEDUP_MAX = 150
    private val DEDUP_WINDOW_MS = 5000L

    companion object {
        private const val TAG = "GuardianService"
        private const val PREFS_NAME = "gd_prefs"
        private const val KEY_PARENT_ID = "pid"
        private const val SYNC_INTERVAL_MS = 30_000L
    }

    override fun onServiceConnected() {
        super.onServiceConnected()

        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val parentId = prefs.getString(KEY_PARENT_ID, "default_parent") ?: "default_parent"

        db = AppDatabase.getInstance(this)
        firebaseRepo = FirebaseRepository(db.messageDao(), parentId)

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

        scope.launch {
            try {
                val extracted = extractor.extract(root)
                extracted.forEach { processMessage(it) }
            } catch (e: Exception) {
                Log.w(TAG, "Extraction error: ${e.message}")
            }
        }
    }

    private suspend fun processMessage(raw: MessageExtractor.RawMessage) {
        // Step 1 — in-memory dedup
        val cacheKey = "${raw.chatName}::${raw.content}::${raw.sender}"
        if (dedupCache.contains(cacheKey)) return
        if (dedupCache.size >= DEDUP_MAX) dedupCache.remove(dedupCache.first())
        dedupCache.add(cacheKey)

        // Step 2 — DB-level dedup (5 second window)
        val since = System.currentTimeMillis() - DEDUP_WINDOW_MS
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
            timestamp = System.currentTimeMillis(),
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

    // Delete messages older than 30 days to save local storage
    private fun startCleanupWorker() {
        scope.launch {
            while (isActive) {
                delay(24 * 60 * 60 * 1000L) // daily
                val cutoff = System.currentTimeMillis() - (30L * 24 * 60 * 60 * 1000L)
                db.messageDao().deleteOlderThan(cutoff)
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
