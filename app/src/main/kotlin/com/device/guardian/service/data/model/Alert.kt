package com.device.guardian.service.data.model

data class Alert(
    val id: String = "",
    val messageId: String = "",
    val chatName: String = "",
    val reason: String = "",
    val timestamp: Long = 0L,
    val sender: String = "",
    val isRead: Boolean = false
)
