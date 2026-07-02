package com.device.guardian.service.data.model

data class Message(
    val id: String,
    val content: String,
    val sender: String,
    val chatName: String,
    val timestamp: Long,
    val isGroupChat: Boolean,
    val isOutgoing: Boolean,
    val isFlagged: Boolean = false,
    val flagReason: String? = null,
    val platform: String = "whatsapp"
)
