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

        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return

        val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE) ?: return

        // BUG-07 fix: Only log on IDLE — this is when the call is complete and CallLog has accurate data
        if (state != TelephonyManager.EXTRA_STATE_IDLE) return

        // BUG-01 fix: Use goAsync() to keep the receiver alive until coroutine completes
        val pendingResult = goAsync()

        scope.launch {
            try {
                val db = AppDatabase.getInstance(context)
                val repo = FirebaseRepository(db.messageDao(), context)

                val latestCall = getLatestCallLog(context)
                if (latestCall != null) {
                    val callTypeStr = when (latestCall.type) {
                        android.provider.CallLog.Calls.INCOMING_TYPE -> "Incoming Call"
                        android.provider.CallLog.Calls.OUTGOING_TYPE -> "Outgoing Call"
                        android.provider.CallLog.Calls.MISSED_TYPE -> "Missed Call"
                        android.provider.CallLog.Calls.REJECTED_TYPE -> "Rejected Call"
                        else -> "Call Ended"
                    }
                    val displayText = "$callTypeStr (${latestCall.duration}s): ${latestCall.number}"
                    val isOut = latestCall.type == android.provider.CallLog.Calls.OUTGOING_TYPE

                    // Dedup check (5 sec window)
                    val dupes = db.messageDao().countDuplicates(displayText, latestCall.number, latestCall.date - 5000L)
                    if (dupes > 0) return@launch

                    val entity = MessageEntity(
                        id = UUID.randomUUID().toString(),
                        content = displayText,
                        sender = latestCall.number,
                        chatName = latestCall.number,
                        timestamp = latestCall.date,
                        isGroupChat = false,
                        isOutgoing = isOut,
                        isFlagged = false,
                        flagReason = null,
                        platform = "calls",
                        isSynced = false
                    )

                    db.messageDao().insert(entity)
                    Log.d(TAG, "Logged call: $displayText")

                    try {
                        repo.syncPending()
                    } catch (e: Exception) {
                        Log.w(TAG, "Call log sync failed: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error logging call event: ${e.message}")
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun getLatestCallLog(context: Context): CallLogEntry? {
        if (context.checkSelfPermission(android.Manifest.permission.READ_CALL_LOG) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            return null
        }
        val contentResolver = context.contentResolver
        val uri = android.provider.CallLog.Calls.CONTENT_URI
        val projection = arrayOf(
            android.provider.CallLog.Calls.NUMBER,
            android.provider.CallLog.Calls.DATE,
            android.provider.CallLog.Calls.TYPE,
            android.provider.CallLog.Calls.DURATION
        )

        try {
            val cursor = contentResolver.query(
                uri,
                projection,
                null,
                null,
                "${android.provider.CallLog.Calls.DATE} DESC LIMIT 1"
            )

            cursor?.use { c ->
                if (c.moveToFirst()) {
                    val numIndex = c.getColumnIndex(android.provider.CallLog.Calls.NUMBER)
                    val dateIndex = c.getColumnIndex(android.provider.CallLog.Calls.DATE)
                    val typeIndex = c.getColumnIndex(android.provider.CallLog.Calls.TYPE)
                    val durIndex = c.getColumnIndex(android.provider.CallLog.Calls.DURATION)

                    if (numIndex >= 0 && dateIndex >= 0 && typeIndex >= 0 && durIndex >= 0) {
                        val number = c.getString(numIndex) ?: "Unknown"
                        val date = c.getLong(dateIndex)
                        val type = c.getInt(typeIndex)
                        val duration = c.getInt(durIndex)

                        if (System.currentTimeMillis() - date < 20_000) {
                            return CallLogEntry(number, date, type, duration)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error querying latest call log: ${e.message}")
        }
        return null
    }

    private data class CallLogEntry(
        val number: String,
        val date: Long,
        val type: Int,
        val duration: Int
    )
}
