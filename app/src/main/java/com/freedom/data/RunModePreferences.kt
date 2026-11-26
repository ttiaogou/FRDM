package com.freedom.data

import android.content.Context

/** Upgrade cleanup only. Never reads or interprets the old mode preference values. */
internal object LegacyRunModeMigration {
    fun removeObsoleteSelection(context: Context) {
        val preferences = context.applicationContext.getSharedPreferences(
            "freedom_main_ui", Context.MODE_PRIVATE
        )
        if (preferences.contains("keep_account_mode_enabled") ||
            preferences.contains("ai_reply_mode_enabled")
        ) {
            preferences.edit()
                .remove("keep_account_mode_enabled")
                .remove("ai_reply_mode_enabled")
                .commit()
        }
    }
}
