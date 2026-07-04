package com.device.guardian.service.service

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.device.guardian.service.data.local.AppDatabase
import com.device.guardian.service.data.local.MessageEntity
import com.device.guardian.service.data.remote.FirebaseRepository
import com.device.guardian.service.service.filter.MessageFilter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.UUID

class GuardianNotificationService : NotificationListenerService() {

    private lateinit var db: AppDatabase
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    companion object {
        private const val TAG = "NotificationService"
    }

    override fun onCreate() {
        super.onCreate()
        db = AppDatabase.getInstance(this)
        Log.d(TAG, "Notification listener started")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        
        val pkg = sbn.packageName
        if (pkg != "com.whatsapp" && pkg != "com.whatsapp.w4b") return

        val notification = sbn.notification
        val extras = notification.extras ?: return

        // Extract text and title
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()

        if (text.isNullOrBlank() || title.isNullOrBlank()) return

        // In WhatsApp, title is usually the Sender or "Group Name @ Sender"
        // Let's assume title is chatName for 1:1, or contains both for groups
        val chatName = if (title.contains("@")) title.substringAfter("@").trim() else title
        val sender = if (title.contains("@")) title.substringBefore("@").trim() else title
        val isGroup = title.contains("@")

        // Ignore summary notifications like "2 new messages"
        if (text.matches(Regex("\\d+ new messages"))) return

        scope.launch {
            try {
                // Dedup check (10 min window)
                val timestamp = sbn.postTime
                val since = timestamp - 600_000L
                val dupes = db.messageDao().countDuplicates(text, chatName, since)
                if (dupes > 0) return@launch

                val filter = MessageFilter.analyze(text)

                val entity = MessageEntity(
                    id = UUID.randomUUID().toString(),
                    content = text,
                    sender = sender,
                    chatName = chatName,
                    timestamp = timestamp,
                    isGroupChat = isGroup,
                    isOutgoing = false, // Notifications are always incoming
                    isFlagged = filter.isFlagged,
                    flagReason = filter.reason,
                    isSynced = false
                )
                
                db.messageDao().insert(entity)
                Log.d(TAG, "Saved background message from $sender")
                
                // Sync to Firebase immediately
                val repo = FirebaseRepository(db.messageDao(), this@GuardianNotificationService)
                repo.syncPending()
            } catch (e: Exception) {
                Log.e(TAG, "Error saving notification message: ${e.message}")
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
    }
}
