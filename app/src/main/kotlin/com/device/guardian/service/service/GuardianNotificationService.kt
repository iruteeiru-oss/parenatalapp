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
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.UUID

class GuardianNotificationService : NotificationListenerService() {

    private lateinit var db: AppDatabase
    private lateinit var repo: FirebaseRepository  // BUG-13 fix: create once
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    companion object {
        private const val TAG = "NotificationService"
    }

    override fun onCreate() {
        super.onCreate()
        db = AppDatabase.getInstance(this)
        repo = FirebaseRepository(db.messageDao(), this)  // BUG-13 fix: reuse
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

        // BUG-R2-08 fix: Use Android's group conversation flag (API 28+) for reliability,
        // with fallback to "Sender @ GroupName" format only.
        // Removed ": " heuristic which false-positives on names like "Dr: Smith".
        val isGroup: Boolean
        val chatName: String
        val sender: String

        val androidIsGroup = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            extras.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION, false)
        } else {
            null // can't determine from system flag
        }

        when {
            androidIsGroup == true || title.contains(" @ ") -> {
                // Group chat: "Sender @ GroupName" format or flagged by Android
                isGroup = true
                if (title.contains(" @ ")) {
                    sender = title.substringBefore(" @ ").trim()
                    chatName = title.substringAfter(" @ ").trim()
                } else {
                    // Group flagged by Android but no @ delimiter — title is group name
                    chatName = title
                    sender = title  // best effort
                }
            }
            else -> {
                // 1:1 chat — title is the contact name
                isGroup = false
                chatName = title
                sender = title
            }
        }

        // Ignore summary notifications like "2 new messages"
        if (text.matches(Regex("\\d+ new messages?"))) return

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
                    platform = "whatsapp",
                    isSynced = false
                )
                
                db.messageDao().insert(entity)
                Log.d(TAG, "Saved background message from $sender")
                
                repo.syncPending()
            } catch (e: Exception) {
                Log.e(TAG, "Error saving notification message: ${e.message}")
            }
        }
    }

    // BUG-02 fix: Cancel the coroutine scope on destroy
    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}
