package com.device.guardian.service.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.device.guardian.service.R
import com.device.guardian.service.ui.DashboardActivity
import com.device.guardian.service.utils.PrefsManager
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class GuardianFCMService : FirebaseMessagingService() {

    companion object {
        private const val CHANNEL_ID = "guardian_alerts"
        private const val CHANNEL_NAME = "Safety Alerts"
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)

        val title = message.data["title"] ?: "Guardian Alert"
        val body  = message.data["body"]  ?: "New safety alert"

        showNotification(title, body)
    }

    private fun showNotification(title: String, body: String) {
        createChannel()

        val intent = Intent(this, DashboardActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            val prefs = PrefsManager(this@GuardianFCMService)
            prefs.migrateOldPrefsIfNeeded(this@GuardianFCMService)
            prefs.parentId?.let { putExtra("parent_id", it) }
        }

        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_alert)
            .setContentTitle(title)
            .setContentText(body)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setVibrate(longArrayOf(0, 500, 200, 500))
            .build()

        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(System.currentTimeMillis().toInt(), notification)
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            CHANNEL_NAME,
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Child safety alerts"
            enableVibration(true)
        }
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)
    }

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        val prefs = PrefsManager(this)
        prefs.migrateOldPrefsIfNeeded(this)
        val parentId = prefs.parentId ?: return
        
        CoroutineScope(Dispatchers.IO).launch {
            try {
                FirebaseFirestore.getInstance()
                    .collection("monitors")
                    .document(parentId)
                    .collection("fcmTokens")
                    .document(token)
                    .set(mapOf("token" to token, "timestamp" to System.currentTimeMillis()))
            } catch (e: Exception) {
                // Ignore failure
            }
        }
    }
}
