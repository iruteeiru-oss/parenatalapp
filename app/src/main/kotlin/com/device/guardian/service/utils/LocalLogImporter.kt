package com.device.guardian.service.utils

import android.content.Context
import android.provider.CallLog
import android.net.Uri
import android.util.Log
import com.device.guardian.service.data.local.AppDatabase
import com.device.guardian.service.data.local.MessageEntity
import com.device.guardian.service.data.remote.FirebaseRepository
import com.device.guardian.service.service.filter.MessageFilter
import java.util.UUID

object LocalLogImporter {
    private const val TAG = "LocalLogImporter"

    suspend fun importHistory(context: Context, db: AppDatabase, repo: FirebaseRepository) {
        try {
            importCallLogs(context, db)
            importSmsLogs(context, db)
            repo.syncPending()
        } catch (e: Exception) {
            Log.e(TAG, "Error importing local history: ${e.message}")
        }
    }

    private suspend fun importCallLogs(context: Context, db: AppDatabase) {
        val hasPermission = context.checkSelfPermission(android.Manifest.permission.READ_CALL_LOG) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!hasPermission) {
            Log.w(TAG, "No READ_CALL_LOG permission granted. Skipping call import.")
            return
        }

        val contentResolver = context.contentResolver
        val uri = CallLog.Calls.CONTENT_URI
        val projection = arrayOf(
            CallLog.Calls.NUMBER,
            CallLog.Calls.DATE,
            CallLog.Calls.TYPE
        )

        // Query the last 50 call logs
        val cursor = contentResolver.query(
            uri,
            projection,
            null,
            null,
            "${CallLog.Calls.DATE} DESC LIMIT 50"
        )

        cursor?.use { c ->
            val numberIndex = c.getColumnIndex(CallLog.Calls.NUMBER)
            val dateIndex = c.getColumnIndex(CallLog.Calls.DATE)
            val typeIndex = c.getColumnIndex(CallLog.Calls.TYPE)

            while (c.moveToNext()) {
                val number = c.getString(numberIndex) ?: "Unknown"
                val timestamp = c.getLong(dateIndex)
                val type = c.getInt(typeIndex)

                val eventType = when (type) {
                    CallLog.Calls.INCOMING_TYPE -> "Incoming Call"
                    CallLog.Calls.OUTGOING_TYPE -> "Outgoing Call"
                    CallLog.Calls.MISSED_TYPE -> "Missed Call"
                    else -> "Call Logged"
                }

                val content = "$eventType: $number"

                // Check for duplicates
                val duplicates = db.messageDao().countDuplicates(content, number, timestamp - 1000)
                if (duplicates == 0) {
                    val entity = MessageEntity(
                        id = UUID.randomUUID().toString(),
                        content = content,
                        sender = number,
                        chatName = number,
                        timestamp = timestamp,
                        isGroupChat = false,
                        isOutgoing = (type == CallLog.Calls.OUTGOING_TYPE),
                        isFlagged = false,
                        flagReason = null,
                        platform = "calls",
                        isSynced = false
                    )
                    db.messageDao().insert(entity)
                    Log.d(TAG, "Imported Call event with $number at $timestamp")
                }
            }
        }
    }

    private suspend fun importSmsLogs(context: Context, db: AppDatabase) {
        val hasPermission = context.checkSelfPermission(android.Manifest.permission.READ_SMS) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!hasPermission) {
            Log.w(TAG, "No READ_SMS permission granted. Skipping SMS import.")
            return
        }

        val contentResolver = context.contentResolver
        val uri = Uri.parse("content://sms")
        val projection = arrayOf(
            "address",
            "body",
            "date",
            "type"
        )

        // Query the last 100 SMS logs
        val cursor = contentResolver.query(
            uri,
            projection,
            null,
            null,
            "date DESC LIMIT 100"
        )

        cursor?.use { c ->
            val addressIndex = c.getColumnIndex("address")
            val bodyIndex = c.getColumnIndex("body")
            val dateIndex = c.getColumnIndex("date")
            val typeIndex = c.getColumnIndex("type")

            while (c.moveToNext()) {
                val address = c.getString(addressIndex) ?: "Unknown"
                val body = c.getString(bodyIndex) ?: ""
                val timestamp = c.getLong(dateIndex)
                val type = c.getInt(typeIndex) // 1 = inbox, 2 = sent

                val isOutgoing = (type == 2)
                
                // Analyze for flagged keywords
                val filterResult = MessageFilter.analyze(body)
                val isFlagged = filterResult != null
                val flagReason = filterResult

                // Check for duplicates
                val duplicates = db.messageDao().countDuplicates(body, address, timestamp - 1000)
                if (duplicates == 0) {
                    val entity = MessageEntity(
                        id = UUID.randomUUID().toString(),
                        content = body,
                        sender = address,
                        chatName = address,
                        timestamp = timestamp,
                        isGroupChat = false,
                        isOutgoing = isOutgoing,
                        isFlagged = isFlagged,
                        flagReason = flagReason,
                        platform = "sms",
                        isSynced = false
                    )
                    db.messageDao().insert(entity)
                    Log.d(TAG, "Imported SMS message from/to $address at $timestamp")
                }
            }
        }
    }
}
