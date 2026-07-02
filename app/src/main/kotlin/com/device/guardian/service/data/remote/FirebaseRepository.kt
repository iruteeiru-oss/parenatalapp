package com.device.guardian.service.data.remote

import android.util.Log
import com.device.guardian.service.data.local.MessageDao
import com.device.guardian.service.data.local.MessageEntity
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.tasks.await

class FirebaseRepository(
    private val dao: MessageDao,
    private val parentId: String
) {
    private val tag = "FirebaseRepo"
    private val db = FirebaseFirestore.getInstance()

    suspend fun syncPending() {
        val unsynced = dao.getUnsynced()
        if (unsynced.isEmpty()) return

        unsynced.forEach { entity ->
            try {
                db.collection("monitors")
                    .document(parentId)
                    .collection("messages")
                    .document(entity.id)
                    .set(entity.toMap(), SetOptions.merge())
                    .await()

                dao.markSynced(entity.id)

                if (entity.isFlagged) {
                    pushAlert(entity)
                }

            } catch (e: Exception) {
                Log.w(tag, "Sync failed for ${entity.id} — will retry")
            }
        }
    }

    private suspend fun pushAlert(entity: MessageEntity) {
        try {
            db.collection("monitors")
                .document(parentId)
                .collection("alerts")
                .document(entity.id)
                .set(mapOf(
                    "messageId"  to entity.id,
                    "chatName"   to entity.chatName,
                    "reason"     to entity.flagReason,
                    "timestamp"  to entity.timestamp,
                    "sender"     to entity.sender
                ))
                .await()
        } catch (e: Exception) {
            Log.w(tag, "Alert push failed", e)
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
        "platform"    to "whatsapp"
    )
}
