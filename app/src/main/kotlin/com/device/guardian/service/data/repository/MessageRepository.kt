package com.device.guardian.service.data.repository

import android.util.Log
import com.device.guardian.service.data.model.Alert
import com.device.guardian.service.data.model.Message
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await

class MessageRepository(private val parentId: String) {

    private val db = FirebaseFirestore.getInstance()
    private val tag = "MessageRepo"

    private val messagesRef get() = db
        .collection("monitors")
        .document(parentId)
        .collection("messages")

    private val alertsRef get() = db
        .collection("monitors")
        .document(parentId)
        .collection("alerts")

    // ── Real-time message stream ───────────────────────────────────────────────

    fun observeMessages(limit: Long = 200): Flow<List<Message>> = callbackFlow {
        val listener = messagesRef
            .orderBy("timestamp", Query.Direction.DESCENDING)
            .limit(limit)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.e(tag, "Message listen error", error)
                    return@addSnapshotListener
                }
                val messages = snapshot?.documents?.mapNotNull { doc ->
                    try {
                        Message(
                            id          = doc.id,
                            content     = doc.getString("content") ?: "",
                            sender      = doc.getString("sender") ?: "",
                            chatName    = doc.getString("chatName") ?: "",
                            timestamp   = doc.getLong("timestamp") ?: 0L,
                            isGroupChat = doc.getBoolean("isGroupChat") ?: false,
                            isOutgoing  = doc.getBoolean("isOutgoing") ?: false,
                            isFlagged   = doc.getBoolean("isFlagged") ?: false,
                            flagReason  = doc.getString("flagReason"),
                            platform    = doc.getString("platform") ?: "whatsapp"
                        )
                    } catch (e: Exception) {
                        Log.w(tag, "Failed to parse message ${doc.id}", e)
                        null
                    }
                } ?: emptyList()

                trySend(messages)
            }

        awaitClose { listener.remove() }
    }

    // ── Real-time alert stream ─────────────────────────────────────────────────

    fun observeAlerts(): Flow<List<Alert>> = callbackFlow {
        val listener = alertsRef
            .orderBy("timestamp", Query.Direction.DESCENDING)
            .limit(50)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.e(tag, "Alert listen error", error)
                    return@addSnapshotListener
                }
                val alerts = snapshot?.documents?.mapNotNull { doc ->
                    try {
                        Alert(
                            id        = doc.id,
                            messageId = doc.getString("messageId") ?: "",
                            chatName  = doc.getString("chatName") ?: "",
                            reason    = doc.getString("reason") ?: "",
                            timestamp = doc.getLong("timestamp") ?: 0L,
                            sender    = doc.getString("sender") ?: "",
                            isRead    = doc.getBoolean("isRead") ?: false
                        )
                    } catch (e: Exception) {
                        null
                    }
                } ?: emptyList()

                trySend(alerts)
            }

        awaitClose { listener.remove() }
    }

    // ── Mark alert read ────────────────────────────────────────────────────────

    suspend fun markAlertRead(alertId: String) {
        try {
            alertsRef.document(alertId)
                .update("isRead", true)
                .await()
        } catch (e: Exception) {
            Log.w(tag, "Failed to mark alert read", e)
        }
    }

    // ── Fetch messages for a specific chat ────────────────────────────────────

    suspend fun getMessagesForChat(chatName: String): List<Message> {
        return try {
            messagesRef
                .whereEqualTo("chatName", chatName)
                .orderBy("timestamp", Query.Direction.ASCENDING)
                .get()
                .await()
                .documents
                .mapNotNull { doc ->
                    Message(
                        id        = doc.id,
                        content   = doc.getString("content") ?: "",
                        sender    = doc.getString("sender") ?: "",
                        chatName  = doc.getString("chatName") ?: "",
                        timestamp = doc.getLong("timestamp") ?: 0L,
                        isGroupChat = doc.getBoolean("isGroupChat") ?: false,
                        isOutgoing  = doc.getBoolean("isOutgoing") ?: false,
                        isFlagged   = doc.getBoolean("isFlagged") ?: false,
                        flagReason  = doc.getString("flagReason")
                    )
                }
        } catch (e: Exception) {
            Log.e(tag, "Failed to fetch chat messages", e)
            emptyList()
        }
    }
}
