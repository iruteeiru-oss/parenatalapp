package com.device.guardian.service.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
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

class SmsTrackerReceiver : BroadcastReceiver() {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    companion object {
        private const val TAG = "SmsTracker"
    }

    override fun onReceive(context: Context?, intent: Intent?) {
        context ?: return
        intent ?: return

        if (intent.action == Telephony.Sms.Intents.SMS_RECEIVED_ACTION) {
            try {
                val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
                if (messages.isNullOrEmpty()) return

                val db = AppDatabase.getInstance(context)
                val repo = FirebaseRepository(db.messageDao(), context)

                // Group multi-part SMS messages by address if needed, or process individually
                messages.forEach { sms ->
                    val senderAddress = sms.originatingAddress ?: "Unknown"
                    val body = sms.messageBody ?: ""
                    val timestamp = sms.timestampMillis

                    scope.launch {
                        // Dedup check (10 min window)
                        val since = timestamp - 600_000L
                        val dupes = db.messageDao().countDuplicates(body, senderAddress, since)
                        if (dupes > 0) return@launch

                        val filter = MessageFilter.analyze(body)

                        val entity = MessageEntity(
                            id = UUID.randomUUID().toString(),
                            content = body,
                            sender = senderAddress,
                            chatName = senderAddress,
                            timestamp = timestamp,
                            isGroupChat = false,
                            isOutgoing = false,
                            isFlagged = filter.isFlagged,
                            flagReason = filter.reason,
                            platform = "sms",
                            isSynced = false
                        )

                        db.messageDao().insert(entity)
                        Log.d(TAG, "Captured incoming SMS from $senderAddress")
                        
                        try {
                            repo.syncPending()
                        } catch (e: Exception) {
                            Log.w(TAG, "SMS sync failed: ${e.message}")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error parsing incoming SMS: ${e.message}")
            }
        }
    }
}
