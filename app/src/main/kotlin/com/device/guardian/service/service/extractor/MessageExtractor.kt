package com.device.guardian.service.service.extractor

import android.view.accessibility.AccessibilityNodeInfo

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
        refreshChatContext(root)
        return collectMessages(root)
    }

    // ── Chat context ──────────────────────────────────────────────────────────

    private fun refreshChatContext(root: AccessibilityNodeInfo) {
        // Primary: contact name in toolbar
        findById(root, "com.whatsapp:id/conversation_contact_name")
            .firstOrNull()?.text?.toString()?.let {
                currentChatName = it
            }

        // Detect group via subtitle row (groups show member names as subtitle)
        val subtitle = findById(root, "com.whatsapp:id/toolbar_subtitle")
        isGroupChat = subtitle.isNotEmpty() &&
                subtitle.first().text?.contains(",") == true
    }

    // ── Message collection ────────────────────────────────────────────────────

    private fun collectMessages(root: AccessibilityNodeInfo): List<RawMessage> {
        val results = mutableListOf<RawMessage>()

        // Primary method — WhatsApp view IDs
        val messageNodes = findById(root, "com.whatsapp:id/message_text")

        val nodes = if (messageNodes.isNotEmpty()) messageNodes
                    else fallbackTraversal(root)

        nodes.forEach { node ->
            val text = node.text?.toString()?.trim() ?: return@forEach
            if (text.length < 2) return@forEach

            val outgoing = detectOutgoing(node)
            val sender = resolveSender(node, outgoing)

            results.add(
                RawMessage(
                    content = text,
                    sender = sender,
                    chatName = currentChatName,
                    isGroupChat = isGroupChat,
                    isOutgoing = outgoing
                )
            )
        }

        return results
    }

    // ── Outgoing detection ────────────────────────────────────────────────────

    private fun detectOutgoing(node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = node.parent
        repeat(8) {
            current?.let { n ->
                // Check view resource ID for outgoing container
                n.viewIdResourceName?.let { id ->
                    if (id.contains("outgoing", ignoreCase = true)) return true
                    if (id.contains("incoming", ignoreCase = true)) return false
                }
                // Check content description set by WhatsApp
                n.contentDescription?.toString()?.let { desc ->
                    if (desc.contains("sent", ignoreCase = true)) return true
                    if (desc.contains("received", ignoreCase = true)) return false
                }
            }
            current = current?.parent
        }
        return false
    }

    // ── Sender resolution ─────────────────────────────────────────────────────

    private fun resolveSender(
        node: AccessibilityNodeInfo,
        isOutgoing: Boolean
    ): String {
        if (isOutgoing) return "Child"
        if (!isGroupChat) return currentChatName

        // In groups, author name appears above the message bubble
        var parent: AccessibilityNodeInfo? = node.parent
        repeat(5) {
            val authorNodes = parent?.findAccessibilityNodeInfosByViewId(
                "com.whatsapp:id/message_author"
            )
            if (!authorNodes.isNullOrEmpty()) {
                return authorNodes.first().text?.toString() ?: currentChatName
            }
            parent = parent?.parent
        }
        return currentChatName
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun findById(
        root: AccessibilityNodeInfo,
        viewId: String
    ): List<AccessibilityNodeInfo> {
        return root.findAccessibilityNodeInfosByViewId(viewId) ?: emptyList()
    }

    // Fallback when WhatsApp updates change view IDs
    private fun fallbackTraversal(
        root: AccessibilityNodeInfo
    ): List<AccessibilityNodeInfo> {
        val results = mutableListOf<AccessibilityNodeInfo>()
        traverse(root, results)
        return results
    }

    private fun traverse(
        node: AccessibilityNodeInfo?,
        results: MutableList<AccessibilityNodeInfo>
    ) {
        node ?: return
        if (node.className == "android.widget.TextView" &&
            !node.text.isNullOrBlank() &&
            node.text.length > 2) {
            results.add(node)
        }
        for (i in 0 until node.childCount) {
            traverse(node.getChild(i), results)
        }
    }
}
