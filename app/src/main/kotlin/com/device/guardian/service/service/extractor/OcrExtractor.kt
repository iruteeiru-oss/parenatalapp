package com.device.guardian.service.service.extractor

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.os.Build
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.Executors
import kotlin.coroutines.resume

/**
 * OCR-based message extraction fallback using ML Kit Text Recognition.
 *
 * This is a SECONDARY extraction layer — only invoked when:
 * 1. ScreenDetector confirms we're on CHAT_VIEW
 * 2. The primary accessibility tree extraction returns 0 messages
 *    (e.g., WhatsApp updated their view IDs)
 *
 * Uses AccessibilityService.takeScreenshot() (API 30+) → ML Kit on-device OCR.
 *
 * Limitations:
 * - API 30+ only (older devices skip OCR entirely)
 * - Cannot distinguish sender vs receiver reliably
 * - Cannot detect outgoing vs incoming
 * - Filters out UI chrome (buttons, headers, timestamps)
 * - Rate-limited to prevent battery drain
 */
class OcrExtractor {

    companion object {
        private const val TAG = "OcrExtractor"
        private const val MIN_INTERVAL_MS = 8_000L  // At most 1 OCR every 8 seconds
    }

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.Builder().build())
    private val executor = Executors.newSingleThreadExecutor()
    private var lastOcrTimestamp = 0L

    data class OcrMessage(
        val text: String,
        val confidence: Float
    )

    /**
     * Returns true if OCR is available on this device (API 30+).
     */
    fun isAvailable(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    /**
     * Returns true if enough time has passed since the last OCR invocation.
     */
    fun isRateLimitOk(): Boolean {
        return (System.currentTimeMillis() - lastOcrTimestamp) >= MIN_INTERVAL_MS
    }

    /**
     * Takes a screenshot via AccessibilityService and extracts text via OCR.
     * Returns a list of recognized text lines that look like chat messages.
     */
    suspend fun extractFromScreen(
        service: AccessibilityService,
        chatName: String
    ): List<MessageExtractor.RawMessage> {
        if (!isAvailable()) return emptyList()
        if (!isRateLimitOk()) return emptyList()

        lastOcrTimestamp = System.currentTimeMillis()

        val bitmap = takeScreenshot(service)
        if (bitmap == null) {
            Log.d(TAG, "Screenshot returned null — skipping OCR")
            return emptyList()
        }

        val ocrResults = try {
            recognizeText(bitmap)
        } finally {
            bitmap.recycle()
        }

        if (ocrResults.isEmpty()) return emptyList()

        // Convert OCR lines → RawMessages
        // Note: OCR cannot determine sender or outgoing status, so we use best-effort defaults
        val now = System.currentTimeMillis()
        return ocrResults.mapIndexed { index, ocr ->
            MessageExtractor.RawMessage(
                content = ocr.text,
                sender = chatName,          // Best effort — we know the chat name from toolbar
                chatName = chatName,
                isGroupChat = false,        // Cannot determine from OCR
                isOutgoing = false,         // Cannot determine from OCR
                timestamp = now + index * 10L
            )
        }
    }

    // ── Screenshot capture ───────────────────────────────────────────────────

    @Suppress("NewApi") // Guarded by isAvailable() check at call site
    private suspend fun takeScreenshot(service: AccessibilityService): Bitmap? {
        return suspendCancellableCoroutine { continuation ->
            try {
                service.takeScreenshot(
                    android.view.Display.DEFAULT_DISPLAY,
                    executor,
                    object : AccessibilityService.TakeScreenshotCallback {
                        override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                            try {
                                val hardwareBuffer = result.hardwareBuffer
                                val colorSpace = result.colorSpace
                                val hwBitmap = Bitmap.wrapHardwareBuffer(hardwareBuffer, colorSpace)
                                hardwareBuffer.close()

                                // ML Kit needs a software bitmap (ARGB_8888)
                                val softBitmap = hwBitmap?.copy(Bitmap.Config.ARGB_8888, false)
                                hwBitmap?.recycle()

                                if (softBitmap != null) {
                                    Log.d(TAG, "Screenshot captured: ${softBitmap.width}x${softBitmap.height}")
                                }
                                continuation.resume(softBitmap)
                            } catch (e: Exception) {
                                Log.w(TAG, "Screenshot bitmap conversion failed: ${e.message}")
                                continuation.resume(null)
                            }
                        }

                        override fun onFailure(errorCode: Int) {
                            Log.w(TAG, "Screenshot failed, errorCode=$errorCode")
                            continuation.resume(null)
                        }
                    }
                )
            } catch (e: Exception) {
                Log.w(TAG, "takeScreenshot() call failed: ${e.message}")
                continuation.resume(null)
            }
        }
    }

    // ── ML Kit text recognition ──────────────────────────────────────────────

    private suspend fun recognizeText(bitmap: Bitmap): List<OcrMessage> {
        val inputImage = InputImage.fromBitmap(bitmap, 0)

        return suspendCancellableCoroutine { continuation ->
            recognizer.process(inputImage)
                .addOnSuccessListener { visionText ->
                    val messages = mutableListOf<OcrMessage>()

                    for (block in visionText.textBlocks) {
                        for (line in block.lines) {
                            val text = line.text.trim()
                            val confidence = line.confidence

                            // Skip short text
                            if (text.length < 3) continue

                            // Skip UI chrome
                            if (isUiChrome(text)) continue

                            // Skip timestamps and dates
                            if (isTimestampOrDate(text)) continue

                            // Skip very low confidence text
                            if (confidence < 0.4f && confidence > 0f) continue

                            messages.add(OcrMessage(text, confidence))
                        }
                    }

                    Log.d(TAG, "OCR recognized ${messages.size} message candidates from ${visionText.textBlocks.size} blocks")
                    continuation.resume(messages)
                }
                .addOnFailureListener { e ->
                    Log.w(TAG, "ML Kit OCR failed: ${e.message}")
                    continuation.resume(emptyList())
                }
        }
    }

    // ── Noise filters ────────────────────────────────────────────────────────

    /**
     * Filters out WhatsApp UI chrome — buttons, labels, headers, and other
     * non-message text that appears on the chat screen.
     */
    private fun isUiChrome(text: String): Boolean {
        val lower = text.lowercase().trim()

        // Exact match UI labels
        val exactLabels = setOf(
            "whatsapp", "type a message", "chats", "status", "calls",
            "communities", "search", "camera", "photos", "documents",
            "gallery", "audio", "contact", "location", "online",
            "typing...", "typing…", "last seen", "tap to retry",
            "read", "delivered", "new chat", "select", "cancel", "ok",
            "mute", "archive", "delete", "forward", "reply", "copy",
            "pin", "mark as read", "starred messages", "settings",
            "new group", "linked devices", "end-to-end encrypted",
            "messages and calls are end-to-end encrypted",
            "tap for more info", "you", "unread messages",
            "message options", "search...", "search…"
        )
        if (exactLabels.contains(lower)) return true

        // Partial match patterns
        val partialPatterns = listOf(
            "last seen at", "last seen today", "last seen yesterday",
            "tap to add", "click here", "no messages",
            "messages to this", "this chat is with",
            "message privately", "business account",
            "default message timer", "disappearing messages"
        )
        if (partialPatterns.any { lower.contains(it) }) return true

        // Emoji-only or single special characters
        if (lower.all { !it.isLetterOrDigit() }) return true

        return false
    }

    /**
     * Filters out timestamps and date headers.
     */
    private fun isTimestampOrDate(text: String): Boolean {
        val trimmed = text.trim()

        // Time: "12:34", "1:23 PM", "10:35 am"
        if (trimmed.matches(Regex("^\\d{1,2}:\\d{2}(?:\\s*[AaPp][Mm])?$"))) return true

        // Named days
        val upper = trimmed.uppercase()
        if (upper == "TODAY" || upper == "YESTERDAY") return true
        if (upper.matches(Regex("^(MONDAY|TUESDAY|WEDNESDAY|THURSDAY|FRIDAY|SATURDAY|SUNDAY)$"))) return true
        if (upper.contains("YESTERDAY AT") || upper.contains("TODAY AT")) return true

        // Dates: "04/07/2026", "2026-07-04", "04.07.26"
        if (trimmed.matches(Regex("^\\d{1,4}[/.-]\\d{1,2}[/.-]\\d{2,4}$"))) return true

        // Month name dates: "4 July 2026", "July 4, 2026"
        if (trimmed.matches(Regex("^\\d{1,2}\\s+\\w+\\s+\\d{4}$"))) return true
        if (trimmed.matches(Regex("^\\w+\\s+\\d{1,2},?\\s+\\d{4}$"))) return true

        return false
    }

    /**
     * Release ML Kit resources when the service is destroyed.
     */
    fun close() {
        try {
            recognizer.close()
        } catch (_: Exception) {}
    }
}
