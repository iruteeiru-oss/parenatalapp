package com.device.guardian.service.service.extractor

import android.view.accessibility.AccessibilityNodeInfo

@Suppress("DEPRECATION")
class MessageExtractor {

    data class RawMessage(
        val content: String,
        val sender: String,
        val chatName: String,
        val isGroupChat: Boolean,
        val isOutgoing: Boolean
    )

    private var currentChatName = "Unknown"
    private var isGroupChat = false

    fun extract(root: AccessibilityNodeInfo): List<RawMessage> {
        currentChatName = "Unknown" // Prevent leaking previous chat context
        isGroupChat = false
        refreshChatContext(root)
        return collectMessages(root)
    }

    // ── Chat context ──────────────────────────────────────────────────────────

    private fun refreshChatContext(root: AccessibilityNodeInfo) {
        val names = root.findAccessibilityNodeInfosByViewId("com.whatsapp:id/conversation_contact_name")
        names?.firstOrNull()?.text?.toString()?.let {
            currentChatName = it
        }
        names?.forEach { it.recycle() }

        val subtitles = root.findAccessibilityNodeInfosByViewId("com.whatsapp:id/toolbar_subtitle")
        isGroupChat = subtitles?.firstOrNull()?.text?.contains(",") == true
        subtitles?.forEach { it.recycle() }
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
            // Fallback
            val fallbackNodes = mutableListOf<AccessibilityNodeInfo>()
            traverse(root, fallbackNodes)
            fallbackNodes.forEach { node ->
                processNode(node)?.let { results.add(it) }
                node.recycle()
            }
        }
        return results
    }

    private fun processNode(node: AccessibilityNodeInfo): RawMessage? {
        val text = node.text?.toString()?.trim() ?: return null
        if (text.length < 2) return null
        
        val outgoing = detectOutgoing(node)
        val sender = resolveSender(node, outgoing)
        
        return RawMessage(
            content = text,
            sender = sender,
            chatName = currentChatName,
            isGroupChat = isGroupChat,
            isOutgoing = outgoing
        )
    }

    // ── Outgoing detection ────────────────────────────────────────────────────

    private fun detectOutgoing(node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = node.parent
        var isOut = false
        var iterations = 0
        while (current != null && iterations < 8) {
            val id = current.viewIdResourceName
            if (id != null) {
                if (id.contains("outgoing", ignoreCase = true)) { isOut = true; break }
                if (id.contains("incoming", ignoreCase = true)) { isOut = false; break }
            }
            val desc = current.contentDescription?.toString()
            if (desc != null) {
                if (desc.contains("sent", ignoreCase = true)) { isOut = true; break }
                if (desc.contains("received", ignoreCase = true)) { isOut = false; break }
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

    // Fallback when WhatsApp updates change view IDs
    private fun traverse(node: AccessibilityNodeInfo?, results: MutableList<AccessibilityNodeInfo>) {
        node ?: return
        if (node.className == "android.widget.TextView" && !node.text.isNullOrBlank() && node.text.length > 2) {
            // Heuristic filtering for fallback (Issue #15)
            val resId = node.viewIdResourceName ?: ""
            if (!resId.contains("toolbar") && !resId.contains("time") && !resId.contains("date") && !resId.contains("header")) {
                results.add(AccessibilityNodeInfo.obtain(node)) 
            }
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            traverse(child, results)
            child?.recycle()
        }
    }
}
