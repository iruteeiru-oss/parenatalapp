package com.device.guardian.service.data.model

data class DeviceStatus(
    val batteryLevel: Int = -1,
    val isCharging: Boolean = false,
    val isOnline: Boolean = false,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val timestamp: Long = 0L,
    val stealthModeActive: Boolean = false,
    val stealthConsentGranted: Boolean = false
)
