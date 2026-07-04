package com.device.guardian.service.data.remote

import android.util.Log
import com.device.guardian.service.data.local.MessageDao
import com.device.guardian.service.data.local.MessageEntity
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.tasks.await

class FirebaseRepository(
    private val dao: MessageDao,
    private val context: android.content.Context
) {
    private val tag = "FirebaseRepo"
    private val db = FirebaseFirestore.getInstance()

    private fun getParentId(): String {
        // Try the new centralized PrefsManager first
        val centralPrefs = context.getSharedPreferences("guardian_app_prefs", android.content.Context.MODE_PRIVATE)
        val centralId = centralPrefs.getString("parent_id", null)
        if (!centralId.isNullOrBlank()) return centralId

        // Fallback: try the old child prefs for backwards compatibility
        val oldPrefs = context.getSharedPreferences("gd_prefs", android.content.Context.MODE_PRIVATE)
        val oldId = oldPrefs.getString("pid", null)
        if (!oldId.isNullOrBlank()) return oldId

        return "default_parent"
    }

    // BUG-11 fix: Use true batching — accumulate up to 450 writes per batch, single commit
    suspend fun syncPending() {
        val unsynced = dao.getUnsynced()
        if (unsynced.isEmpty()) return

        val parentId = getParentId()
        
        // Firestore limit is 500 writes per batch; use 450 to leave headroom for alerts
        val chunks = unsynced.chunked(200) // ~200 messages + possible 200 alerts = 400 max writes
        
        for (chunk in chunks) {
            try {
                val batch = db.batch()
                val idsToMark = mutableListOf<String>()

                for (entity in chunk) {
                    val msgRef = db.collection("monitors")
                        .document(parentId)
                        .collection("messages")
                        .document(entity.id)
                    batch.set(msgRef, entity.toMap(), SetOptions.merge())

                    if (entity.isFlagged) {
                        val alertRef = db.collection("monitors")
                            .document(parentId)
                            .collection("alerts")
                            .document(entity.id)
                        batch.set(alertRef, mapOf(
                            "messageId"  to entity.id,
                            "chatName"   to entity.chatName,
                            "reason"     to entity.flagReason,
                            "timestamp"  to entity.timestamp,
                            "sender"     to entity.sender,
                            "isRead"     to false
                        ))
                    }
                    idsToMark.add(entity.id)
                }

                batch.commit().await()
                
                // Mark all synced after the batch succeeds
                for (id in idsToMark) {
                    dao.markSynced(id)
                }
                
                Log.d(tag, "Batch synced ${idsToMark.size} messages")
            } catch (e: Exception) {
                Log.w(tag, "Batch sync failed — will retry on next cycle", e)
            }
        }
    }

    suspend fun syncDeviceStatus(
        batteryLevel: Int,
        isCharging: Boolean,
        isOnline: Boolean,
        latitude: Double?,
        longitude: Double?
    ) {
        val parentId = getParentId()
        val data = mutableMapOf<String, Any>(
            "batteryLevel" to batteryLevel,
            "isCharging"   to isCharging,
            "isOnline"     to isOnline,
            "timestamp"    to System.currentTimeMillis()
        )
        if (latitude != null && longitude != null) {
            data["latitude"] = latitude
            data["longitude"] = longitude
        }

        try {
            db.collection("monitors")
                .document(parentId)
                .collection("status")
                .document("device")
                .set(data, SetOptions.merge())
                .await()
        } catch (e: Exception) {
            Log.w(tag, "Failed to sync device status", e)
        }
    }

    private fun MessageEntity.toMap() = mapOf(
        "id"          to id,
        "content"     to content,
        "sender"      to sender,
        "chatName"    to chatName,
        "timestamp"   to timestamp,
        "isGroupChat" to isGroupChat,
        "isOutgoing"  to isOutgoing,
        "isFlagged"   to isFlagged,
        "flagReason"  to flagReason,
        "platform"    to platform
    )
}
