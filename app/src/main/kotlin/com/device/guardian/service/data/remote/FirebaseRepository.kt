package com.device.guardian.service.data.remote

import android.util.Log
import com.device.guardian.service.data.local.MessageDao
import com.device.guardian.service.data.local.MessageEntity
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.google.firebase.storage.FirebaseStorage
import kotlinx.coroutines.tasks.await
import java.io.File
import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class FirebaseRepository(
    private val dao: MessageDao,
    private val context: android.content.Context
) {
    private val tag = "FirebaseRepo"
    private val db = FirebaseFirestore.getInstance()
    private val storage = FirebaseStorage.getInstance()

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

    suspend fun syncMediaInventory(inventory: List<Map<String, Any>>) {
        val parentId = getParentId()
        if (inventory.isEmpty()) return

        // Firestore limit is 500 writes per batch
        val chunks = inventory.chunked(450)
        
        for (chunk in chunks) {
            try {
                val batch = db.batch()
                
                for (item in chunk) {
                    val fileId = item["fileId"] as String
                    val docRef = db.collection("monitors")
                        .document(parentId)
                        .collection("media_inventory")
                        .document(fileId)
                        
                    // Only merge to avoid overwriting requestStatus if parent already requested it
                    batch.set(docRef, item, SetOptions.merge())
                }
                
                batch.commit().await()
                Log.d(tag, "Synced ${chunk.size} media metadata items to Firestore.")
            } catch (e: Exception) {
                Log.w(tag, "Failed to sync media inventory chunk", e)
            }
        }
    }

    fun listenForMediaRequests() {
        val parentId = getParentId()
        
        db.collection("monitors")
            .document(parentId)
            .collection("media_inventory")
            .whereEqualTo("requestStatus", "REQUESTED")
            .addSnapshotListener { snapshots, e ->
                if (e != null) {
                    Log.w(tag, "Listen failed.", e)
                    return@addSnapshotListener
                }

                snapshots?.documentChanges?.forEach { dc ->
                    if (dc.type == com.google.firebase.firestore.DocumentChange.Type.ADDED || 
                        dc.type == com.google.firebase.firestore.DocumentChange.Type.MODIFIED) {
                        
                        val doc = dc.document
                        val fileId = doc.id
                        val filePath = doc.getString("filePath")
                        
                        if (filePath != null) {
                            Log.d(tag, "Media requested by parent: $fileId")
                            CoroutineScope(Dispatchers.IO).launch {
                                uploadMediaFile(parentId, fileId, filePath)
                            }
                        }
                    }
                }
            }
    }

    private suspend fun uploadMediaFile(parentId: String, fileId: String, filePath: String) {
        val file = File(filePath)
        if (!file.exists()) {
            Log.e(tag, "Requested file does not exist: $filePath")
            // Mark as failed
            db.collection("monitors").document(parentId).collection("media_inventory").document(fileId)
                .set(mapOf("requestStatus" to "FAILED_NOT_FOUND"), SetOptions.merge())
            return
        }

        val storageRef = storage.reference.child("monitors/$parentId/media/${file.name}")
        val fileUri = Uri.fromFile(file)

        try {
            Log.d(tag, "Uploading media file: ${file.name}")
            // Mark as uploading
            val docRef = db.collection("monitors").document(parentId).collection("media_inventory").document(fileId)
            docRef.set(mapOf("requestStatus" to "UPLOADING"), SetOptions.merge()).await()

            // Upload
            storageRef.putFile(fileUri).await()
            val downloadUrl = storageRef.downloadUrl.await().toString()

            // Update Firestore with URL
            docRef.set(
                mapOf(
                    "requestStatus" to "UPLOADED",
                    "downloadUrl" to downloadUrl
                ), 
                SetOptions.merge()
            ).await()
            
            Log.d(tag, "Media upload complete: $downloadUrl")
        } catch (e: Exception) {
            Log.e(tag, "Media upload failed", e)
            db.collection("monitors").document(parentId).collection("media_inventory").document(fileId)
                .set(mapOf("requestStatus" to "FAILED_UPLOAD"), SetOptions.merge())
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
