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

        if (oldChildPid != null) parentId = oldChildPid
        else if (oldParentId != null) parentId = oldParentId

        if (oldRole != null) role = oldRole

        prefs.edit().putBoolean(migratedKey, true).apply()
    }
}
