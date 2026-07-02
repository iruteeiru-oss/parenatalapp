package com.device.guardian.service.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON",
            "com.htc.intent.action.QUICKBOOT_POWERON" -> {
                // Accessibility service auto-restarts after boot
                // if permission was granted — no manual start needed
                Log.d("BootReceiver", "Boot complete — accessibility service will resume")
            }
        }
    }
}
