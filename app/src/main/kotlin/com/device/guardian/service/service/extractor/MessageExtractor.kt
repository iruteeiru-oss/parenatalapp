package com.device.guardian.service.service.extractor

import android.view.accessibility.AccessibilityNodeInfo
import java.util.Calendar

@Suppress("DEPRECATION")
class MessageExtractor {

    data class RawMessage(
        val content: String,
        val sender: String,
        val chatName: String,
        val isGroupChat: Boolean,
        val isOutgoing: Boolean,
        val timestamp: Long
    )

    private var currentChatName = "Unknown"
    private var isGroupChat = false

    /**
     * Extracts messages from the WhatsApp accessibility tree.
     * Returns null (NOT empty list) if we're not on a chat screen — this signals
     * the caller that extraction was skipped (not that there were 0 messages).
     */
    fun extract(root: AccessibilityNodeInfo): List<RawMessage>? {
        // ── Step 0: Screen detection gate ──
        val screen = ScreenDetector.detect(root)
        if (screen != ScreenDetector.WhatsAppScreen.CHAT_VIEW) {
            return null  // null = "not on chat screen, skip entirely"
        }

        // ── Step 1: Refresh chat context ──
        currentChatName = "Unknown"
        isGroupChat = false
        refreshChatContext(root)

        // ── Step 2: Validate chat context ──
        // Even if ScreenDetector says CHAT_VIEW, if we can't find the chat name
        // at all, something is wrong — bail out to prevent "Unknown" messages.
        if (currentChatName == "Unknown") {
            // Last resort: try toolbar text extraction
            val toolbarName = findToolbarTitle(root)
            if (toolbarName != null) {
                currentChatName = toolbarName
            } else {
                // We're supposedly on a chat screen but can't identify it — skip
                return null
            }
        }

        // ── Step 3: Collect messages ──
        return collectMessages(root)
    }

    /**
     * Returns the detected chat name from the last extract() call.
     * Useful for OCR fallback which needs the chat context.
     */
    fun getLastChatName(): String = currentChatName

    /**
     * Returns whether the last detected chat was a group.
     */
    fun isLastChatGroup(): Boolean = isGroupChat

    // ── Chat context ──────────────────────────────────────────────────────────

    private fun refreshChatContext(root: AccessibilityNodeInfo) {
        val possibleIds = listOf(
            "com.whatsapp:id/conversation_contact_name",
            "com.whatsapp:id/chat_name",
            "com.whatsapp:id/name",
            "com.whatsapp:id/conversation_title",
            "com.whatsapp:id/title"
        )
        
        var foundName = false
        for (id in possibleIds) {
            val names = root.findAccessibilityNodeInfosByViewId(id)
            if (!names.isNullOrEmpty()) {
                val text = names.firstOrNull()?.text?.toString()
                names.forEach { it.recycle() }
                if (!text.isNullOrBlank()) {
                    currentChatName = text
                    foundName = true
                    break
                }
            }
        }
        
        // Robust Toolbar search if ID lookup fails
        if (!foundName) {
            findToolbarTitle(root)?.let {
                currentChatName = it
                foundName = true
            }
        }

        val subtitles = root.findAccessibilityNodeInfosByViewId("com.whatsapp:id/toolbar_subtitle")
        isGroupChat = subtitles?.firstOrNull()?.text?.contains(",") == true
        subtitles?.forEach { it.recycle() }
    }

    private fun findToolbarTitle(root: AccessibilityNodeInfo): String? {
        val possibleToolbarIds = listOf("com.whatsapp:id/toolbar", "com.whatsapp:id/action_bar")
        for (toolbarId in possibleToolbarIds) {
            val toolbars = root.findAccessibilityNodeInfosByViewId(toolbarId)
            if (!toolbars.isNullOrEmpty()) {
                val toolbar = toolbars[0]
                for (i in 0 until toolbar.childCount) {
                    val child = toolbar.getChild(i) ?: continue
                    if (child.className == "android.widget.TextView") {
                        val text = child.text?.toString()
                        child.recycle()
                        if (!text.isNullOrBlank() && text.length > 2 && !text.contains(",") && !text.contains("online")) {
                            toolbars.forEach { it.recycle() }
                            return text
                        }
                    } else {
                        child.recycle()
                    }
                }
                toolbars.forEach { it.recycle() }
            }
        }
        return null
    }

    // ── Message collection ────────────────────────────────────────────────────

    private fun collectMessages(root: AccessibilityNodeInfo): List<RawMessage> {
        val results = mutableListOf<RawMessage>()
        
        val nodes = root.findAccessibilityNodeInfosByViewId("com.whatsapp:id/message_text")
        
        if (!nodes.isNullOrEmpty()) {
            nodes.forEach { node ->
                processNode(node)?.let { results.add(it) }
                node.recycle()
            }
        } else {
            // Fallback: traverse tree but with stricter filtering
            // Since ScreenDetector already confirmed we're on CHAT_VIEW,
            // the fallback is safer than before — but we still apply extra guards
            val fallbackMessages = mutableListOf<RawMessage>()
            traverseAndCollect(root, fallbackMessages)
            results.addAll(fallbackMessages)
        }
        return results
    }

    private fun isTimestampOrDate(text: String): Boolean {
        // Match times like "12:34", "1:23 PM", "12:34 PM", "22:15", "10:35 AM", etc.
        val timeRegex = Regex("^\\d{1,2}:\\d{2}(?:\\s*[AaPp][Mm])?$")
        if (text.matches(timeRegex)) return true
        
        // Match generic days or headers
        val upper = text.uppercase()
        if (upper == "TODAY" || upper == "YESTERDAY" || upper.contains("YESTERDAY AT") || upper.contains("TODAY AT")) return true
        
        // Match dates like "04/07/2026", "04.07.26", "2026-07-04"
        val dateRegex = Regex("^\\d{1,4}[/.-]\\d{1,2}[/.-]\\d{2,4}$")
        if (text.matches(dateRegex)) return true
        
        return false
    }

    /**
     * Checks if text looks like WhatsApp UI chrome rather than a message.
     */
    private fun isUiLabel(text: String): Boolean {
        val lower = text.lowercase().trim()
        val labels = setOf(
            "type a message", "online", "typing...", "typing…",
            "last seen", "tap for more info", "you", "unread messages",
            "end-to-end encrypted", "search...", "search…",
            "messages and calls are end-to-end encrypted",
            "disappearing messages", "message options"
        )
        return labels.contains(lower) || labels.any { lower == it }
    }

    private fun processNode(node: AccessibilityNodeInfo): RawMessage? {
        val text = node.text?.toString()?.trim() ?: return null
        if (text.length < 2) return null
        if (isTimestampOrDate(text)) return null
        if (isUiLabel(text)) return null
        
        val outgoing = detectOutgoing(node)
        val sender = resolveSender(node, outgoing)
        
        // Extract time from sibling
        val timeStr = findTimeSibling(node)
        val parsedTime = if (timeStr != null) parseTimeToMillis(timeStr) else System.currentTimeMillis()
        
        return RawMessage(
            content = text,
            sender = sender,
            chatName = currentChatName,
            isGroupChat = isGroupChat,
            isOutgoing = outgoing,
            timestamp = parsedTime
        )
    }

    // BUG-05 fix: Don't recycle parent — the caller doesn't own it.
    // Only traverse children, recycle each child after use.
    private fun findTimeSibling(node: AccessibilityNodeInfo): String? {
        val parent = node.parent ?: return null
        try {
            for (i in 0 until parent.childCount) {
                val child = parent.getChild(i) ?: continue
                try {
                    val id = child.viewIdResourceName ?: ""
                    if (id.contains("message_time") || id.contains("time")) {
                        val text = child.text?.toString()
                        if (!text.isNullOrBlank()) return text
                        continue
                    }
                    
                    if (child.className == "android.widget.TextView") {
                        val text = child.text?.toString()?.trim() ?: ""
                        if (text.matches(Regex("^\\d{1,2}:\\d{2}(?:\\s*[AaPp][Mm])?$"))) {
                            return text
                        }
                    }
                } finally {
                    child.recycle()
                }
            }
        } finally {
            parent.recycle()
        }
        return null
    }

    // BUG-12 fix: Clamp parsed time to `now` — prevent future timestamps
    private fun parseTimeToMillis(timeStr: String): Long {
        val now = System.currentTimeMillis()
        try {
            val calendar = Calendar.getInstance()
            calendar.timeInMillis = now
            val cleaned = timeStr.trim().uppercase()
            
            // 12-hour AM/PM: "10:30 AM" or "10:30PM"
            val match12 = Regex("(\\d{1,2}):(\\d{2})\\s*([AP]M)").find(cleaned)
            if (match12 != null) {
                var hour = match12.groupValues[1].toInt()
                val minute = match12.groupValues[2].toInt()
                val amPm = match12.groupValues[3]
                
                if (amPm == "PM" && hour < 12) hour += 12
                if (amPm == "AM" && hour == 12) hour = 0
                
                calendar.set(Calendar.HOUR_OF_DAY, hour)
                calendar.set(Calendar.MINUTE, minute)
                calendar.set(Calendar.SECOND, 0)
                calendar.set(Calendar.MILLISECOND, 0)
                
                // BUG-12 fix: If parsed time is in the future, it was from yesterday
                if (calendar.timeInMillis > now) {
                    calendar.add(Calendar.DAY_OF_YEAR, -1)
                }
                return calendar.timeInMillis
            }
            
            // 24-hour: "22:15" or "10:30"
            val match24 = Regex("(\\d{1,2}):(\\d{2})").find(cleaned)
            if (match24 != null) {
                val hour = match24.groupValues[1].toInt()
                val minute = match24.groupValues[2].toInt()
                
                calendar.set(Calendar.HOUR_OF_DAY, hour)
                calendar.set(Calendar.MINUTE, minute)
                calendar.set(Calendar.SECOND, 0)
                calendar.set(Calendar.MILLISECOND, 0)
                
                // BUG-12 fix: If parsed time is in the future, it was from yesterday
                if (calendar.timeInMillis > now) {
                    calendar.add(Calendar.DAY_OF_YEAR, -1)
                }
                return calendar.timeInMillis
            }
        } catch (e: Exception) {}
        return now
    }

    // ── Outgoing detection ────────────────────────────────────────────────────

    private fun detectOutgoing(node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = node.parent
        var isOut = false
        var iterations = 0
        while (current != null && iterations < 8) {
            val id = current.viewIdResourceName
            if (id != null) {
                if (id.contains("outgoing", ignoreCase = true)) { isOut = true; current.recycle(); break }
                if (id.contains("incoming", ignoreCase = true)) { isOut = false; current.recycle(); break }
            }
            val desc = current.contentDescription?.toString()
            if (desc != null) {
                if (desc.contains("sent", ignoreCase = true)) { isOut = true; current.recycle(); break }
                if (desc.contains("received", ignoreCase = true)) { isOut = false; current.recycle(); break }
            }
            val nextParent = current.parent
            current.recycle()
            current = nextParent
            iterations++
        }
        current?.recycle()
        return isOut
    }

    // ── Sender resolution ─────────────────────────────────────────────────────

    private fun resolveSender(node: AccessibilityNodeInfo, isOutgoing: Boolean): String {
        if (isOutgoing) return "Child"
        if (!isGroupChat) return currentChatName

        var current: AccessibilityNodeInfo? = node.parent
        var sender = currentChatName
        var iterations = 0
        while (current != null && iterations < 5) {
            val authors = current.findAccessibilityNodeInfosByViewId("com.whatsapp:id/message_author")
            if (!authors.isNullOrEmpty()) {
                authors.firstOrNull()?.text?.toString()?.let { sender = it }
                authors.forEach { it.recycle() }
                current.recycle()
                return sender
            }
            val nextParent = current.parent
            current.recycle()
            current = nextParent
            iterations++
        }
        current?.recycle()
        return sender
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    // BUG-06 fix: Refactored fallback to collect RawMessages directly
    // instead of using obtain() + recycle patterns that caused use-after-recycle
    private fun traverseAndCollect(node: AccessibilityNodeInfo?, results: MutableList<RawMessage>) {
        node ?: return
        if (node.className == "android.widget.TextView" && !node.text.isNullOrBlank() && node.text.length > 2) {
            val resId = node.viewIdResourceName ?: ""
            if (!resId.contains("toolbar") && !resId.contains("time") && !resId.contains("date") && !resId.contains("header")) {
                val text = node.text.toString().trim()
                if (!isTimestampOrDate(text) && !isUiLabel(text)) {
                    results.add(
                        RawMessage(
                            content = text,
                            sender = currentChatName,
                            chatName = currentChatName,
                            isGroupChat = isGroupChat,
                            isOutgoing = false,
                            timestamp = System.currentTimeMillis()
                        )
                    )
                }
            }
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            traverseAndCollect(child, results)
            child?.recycle()
        }
    }
}
