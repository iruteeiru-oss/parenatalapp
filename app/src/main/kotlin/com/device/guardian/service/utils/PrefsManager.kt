package com.device.guardian.service.utils

import android.content.Context
import android.content.SharedPreferences

class PrefsManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("guardian_app_prefs", Context.MODE_PRIVATE)

    var parentId: String?
        get() = prefs.getString("parent_id", null)
        set(value) = prefs.edit().putString("parent_id", value).apply()

    var role: String?
        get() = prefs.getString("role", null)
        set(value) = prefs.edit().putString("role", value).apply()

    var childUid: String?
        get() = prefs.getString("child_uid", null)
        set(value) = prefs.edit().putString("child_uid", value).apply()

    var smsPrompted: Boolean
        get() = prefs.getBoolean("sms_prompted", false)
        set(value) = prefs.edit().putBoolean("sms_prompted", value).apply()

    var isStealthConsentGranted: Boolean
        get() = prefs.getBoolean("stealth_consent_granted", false)
        set(value) = prefs.edit().putBoolean("stealth_consent_granted", value).apply()

    var isStealthModeActive: Boolean
        get() = prefs.getBoolean("stealth_mode_active", false)
        set(value) = prefs.edit().putBoolean("stealth_mode_active", value).apply()

    // Used for migrating old prefs to the new centralized one
    fun migrateOldPrefsIfNeeded(context: Context) {
        val migratedKey = "prefs_migrated_v1"
        if (prefs.getBoolean(migratedKey, false)) return

        val oldChildPrefs = context.getSharedPreferences("gd_prefs", Context.MODE_PRIVATE)
        val oldParentPrefs = context.getSharedPreferences("gd_parent_prefs", Context.MODE_PRIVATE)
        val oldRolePrefs = context.getSharedPreferences("gd_role_prefs", Context.MODE_PRIVATE)

        val oldChildPid = oldChildPrefs.getString("pid", null)
        val oldParentId = oldParentPrefs.getString("parent_id", null)
        val oldRole = oldRolePrefs.getString("role", null)

        // BUG-19 fix: Respect current role when migrating to prevent wrong ID overwrite
        val currentRole = oldRole ?: role
        when (currentRole) {
            "child" -> {
                if (oldChildPid != null) parentId = oldChildPid
            }
            "parent" -> {
                if (oldParentId != null) parentId = oldParentId
            }
            else -> {
                // Fallback: prefer child pid, then parent id
                if (oldChildPid != null) parentId = oldChildPid
                else if (oldParentId != null) parentId = oldParentId
            }
        }

        if (oldRole != null) role = oldRole

        prefs.edit().putBoolean(migratedKey, true).apply()
    }
}
