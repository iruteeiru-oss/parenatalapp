package com.device.guardian.service.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager
import android.util.Log
import com.device.guardian.service.data.local.AppDatabase
import com.device.guardian.service.data.local.MessageEntity
import com.device.guardian.service.data.remote.FirebaseRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.UUID

class CallTrackerReceiver : BroadcastReceiver() {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    companion object {
        private const val TAG = "CallTracker"
    }

    @Suppress("DEPRECATION")
    override fun onReceive(context: Context?, intent: Intent?) {
        context ?: return
        intent ?: return

        if (intent.action == TelephonyManager.ACTION_PHONE_STATE_CHANGED) {
            val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE) ?: return
            
            // Get the incoming number (might be null depending on permission levels or SDK version)
            val number = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER) ?: "Unknown Number"
            
            val eventType = when (state) {
                TelephonyManager.EXTRA_STATE_RINGING -> "Incoming Call Ringing"
                TelephonyManager.EXTRA_STATE_OFFHOOK -> "Call Answered / Offhook"
                TelephonyManager.EXTRA_STATE_IDLE -> "Call Ended"
                else -> return
            }

            scope.launch {
                try {
                    val db = AppDatabase.getInstance(context)
                    val repo = FirebaseRepository(db.messageDao(), context)

                    val entity = MessageEntity(
                        id = UUID.randomUUID().toString(),
                        content = "$eventType: $number",
                        sender = number,
                        chatName = number,
                        timestamp = System.currentTimeMillis(),
                        isGroupChat = false,
                        isOutgoing = (state == TelephonyManager.EXTRA_STATE_OFFHOOK),
                        isFlagged = false,
                        flagReason = null,
                        platform = "calls",
                        isSynced = false
                    )

                    db.messageDao().insert(entity)
                    Log.d(TAG, "Logged Call state event: $eventType with $number")
                    
                    try {
                        repo.syncPending()
                    } catch (e: Exception) {
                        Log.w(TAG, "Call log sync failed: ${e.message}")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error logging call event: ${e.message}")
                }
            }
        }
    }
}
