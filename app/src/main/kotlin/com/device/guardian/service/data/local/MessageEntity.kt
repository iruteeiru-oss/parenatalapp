package com.device.guardian.service.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

// BUG-23 fix: Added composite indices for dedup query performance and sync lookups
@Entity(
    tableName = "messages",
    indices = [
        Index(value = ["chatName", "timestamp"]),
        Index(value = ["isSynced"]),
        Index(value = ["platform"])
    ]
)
data class MessageEntity(
    @PrimaryKey val id: String,
    val content: String,
    val sender: String,
    val chatName: String,
    val timestamp: Long,
    val isGroupChat: Boolean,
    val isOutgoing: Boolean,
    val isFlagged: Boolean,
    val flagReason: String?,
    val platform: String = "whatsapp",
    val isSynced: Boolean = false
)
