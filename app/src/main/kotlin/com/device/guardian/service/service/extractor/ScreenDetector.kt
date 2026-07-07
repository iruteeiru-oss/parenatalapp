package com.device.guardian.service.service.extractor

import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Detects which WhatsApp screen is currently active.
 *
 * WhatsApp has several distinct screens, each with unique UI element IDs:
 * - Chat List (home): contact rows, FAB, tab bar
 * - Chat View (conversation): input bar, send button, message bubbles
 * - Status / Calls / Communities tabs
 * - Settings, Profile, etc.
 *
 * Only CHAT_VIEW should trigger message extraction.
 */
@Suppress("DEPRECATION")
object ScreenDetector {

    private const val TAG = "ScreenDetector"

    enum class WhatsAppScreen {
        /** Inside an active conversation — safe to extract messages */
        CHAT_VIEW,
        /** Main screen showing conversation list — DO NOT extract */
        CHAT_LIST,
        /** Status, Calls, Communities, Settings, Profile, etc. — DO NOT extract */
        OTHER
    }

    // ── Positive signals: elements that ONLY exist inside a chat conversation ──

    private val CHAT_VIEW_IDS = listOf(
        // Message input area — strongest signal
        "com.whatsapp:id/entry",
        "com.whatsapp:id/text_entry_frame",
        "com.whatsapp:id/input_bar",
        "com.whatsapp:id/msg_input_bar",
        "com.whatsapp:id/conversation_compose_row_new",
        "com.whatsapp:id/conversation_compose_row",
        // Send/voice button
        "com.whatsapp:id/send",
        "com.whatsapp:id/send_btn",
        "com.whatsapp:id/voice_note_btn",
        // Attachment button inside chat
        "com.whatsapp:id/input_attach_button",
        // Emoji button inside chat
        "com.whatsapp:id/emoji_picker_btn",
        "com.whatsapp:id/sticker_btn"
    )

    // ── Secondary positive: conversation header elements ──

    private val CHAT_HEADER_IDS = listOf(
        "com.whatsapp:id/conversation_contact_name",
        "com.whatsapp:id/conversation_title",
        "com.whatsapp:id/conversation_contact_status",
        "com.whatsapp:id/toolbar_subtitle"
    )

    // ── Message bubble containers ──

    private val MESSAGE_CONTAINER_IDS = listOf(
        "com.whatsapp:id/message_text",
        "com.whatsapp:id/chat_list",        // The RecyclerView holding messages
        "com.whatsapp:id/messages_list",
        "com.whatsapp:id/message_container"
    )

    // ── Negative signals: elements on the CHAT LIST / HOME screen ──

    private val CHAT_LIST_IDS = listOf(
        "com.whatsapp:id/fab",                          // Floating action button
        "com.whatsapp:id/tabbar",                       // Tab bar (Chats/Status/Calls)
        "com.whatsapp:id/tab_layout",                   // Tab layout
        "com.whatsapp:id/home_tab_layout",              // Home tab layout
        "com.whatsapp:id/contact_row_container",        // Contact row on list
        "com.whatsapp:id/conversations_row_contact_name", // Contact name in list
        "com.whatsapp:id/conversations_row",            // Conversation row
        "com.whatsapp:id/conversation_row_body",        // Row body
        "com.whatsapp:id/search_input",                 // Search field on main screen
        "com.whatsapp:id/menuitem_search"               // Search menu item
    )

    // ── Other screen signals (Status, Calls, Settings) ──

    private val OTHER_SCREEN_IDS = listOf(
        "com.whatsapp:id/status_list",                  // Status tab
        "com.whatsapp:id/call_log_list",                // Calls tab
        "com.whatsapp:id/settings_list",                // Settings
        "com.whatsapp:id/community_tab_recycler"        // Communities
    )

    /**
     * Analyzes the accessibility tree to determine the current WhatsApp screen.
     *
     * Detection priority:
     * 1. Check for CHAT_VIEW input elements (strongest positive signal)
     * 2. Check for CHAT_VIEW header + message bubbles combo
     * 3. Check for CHAT_LIST elements (negative signal)
     * 4. Check for OTHER screen elements
     * 5. Default to OTHER (safe — skip extraction)
     */
    fun detect(root: AccessibilityNodeInfo): WhatsAppScreen {
        // ── Step 1: Strongest positive — chat input bar ──
        if (hasAnyNode(root, CHAT_VIEW_IDS)) {
            Log.d(TAG, "Detected: CHAT_VIEW (input bar found)")
            return WhatsAppScreen.CHAT_VIEW
        }

        // ── Step 2: Secondary positive — header + messages combo ──
        val hasHeader = hasAnyNode(root, CHAT_HEADER_IDS)
        val hasMessages = hasAnyNode(root, MESSAGE_CONTAINER_IDS)
        if (hasHeader && hasMessages) {
            Log.d(TAG, "Detected: CHAT_VIEW (header + messages found)")
            return WhatsAppScreen.CHAT_VIEW
        }

        // ── Step 3: Negative — chat list screen ──
        if (hasAnyNode(root, CHAT_LIST_IDS)) {
            Log.d(TAG, "Detected: CHAT_LIST")
            return WhatsAppScreen.CHAT_LIST
        }

        // ── Step 4: Other screens ──
        if (hasAnyNode(root, OTHER_SCREEN_IDS)) {
            Log.d(TAG, "Detected: OTHER (status/calls/settings)")
            return WhatsAppScreen.OTHER
        }

        // ── Step 5: Unknown — default to OTHER for safety ──
        Log.d(TAG, "Detected: OTHER (no known markers)")
        return WhatsAppScreen.OTHER
    }

    private fun hasAnyNode(root: AccessibilityNodeInfo, ids: List<String>): Boolean {
        for (id in ids) {
            val nodes = root.findAccessibilityNodeInfosByViewId(id)
            if (!nodes.isNullOrEmpty()) {
                nodes.forEach { it.recycle() }
                return true
            }
        }
        return false
    }
}
