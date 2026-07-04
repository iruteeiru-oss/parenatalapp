package com.device.guardian.service.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "messages")
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
