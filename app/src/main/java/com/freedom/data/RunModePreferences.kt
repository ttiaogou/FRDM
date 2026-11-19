package com.freedom.data

import android.content.Context
import com.freedom.core.runtime.RunMode

/** Reads the existing UI preferences without changing their persisted keys. */
object RunModePreferences {
    private const val PREFS_NAME = "freedom_main_ui"
    private const val KEY_KEEP_ACCOUNT_MODE = "keep_account_mode_enabled"
    private const val KEY_AI_REPLY_MODE = "ai_reply_mode_enabled"

    private fun preferences(context: Context) = context.applicationContext.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE
    )

    fun selectedMode(context: Context): RunMode? = RunMode.fromSelection(
        browseEnabled = isBrowseModeSelected(context),
        aiEnabled = isAiReplyModeSelected(context)
    )

    fun isBrowseModeSelected(context: Context): Boolean =
        preferences(context).getBoolean(KEY_KEEP_ACCOUNT_MODE, false)

    fun setBrowseModeSelected(context: Context, enabled: Boolean) {
        preferences(context).edit().putBoolean(KEY_KEEP_ACCOUNT_MODE, enabled).apply()
        if (enabled) preferences(context).edit().putBoolean(KEY_AI_REPLY_MODE, false).apply()
    }

    fun isAiReplyModeSelected(context: Context): Boolean =
        preferences(context).getBoolean(KEY_AI_REPLY_MODE, true)

    fun setAiReplyModeSelected(context: Context, enabled: Boolean) {
        preferences(context).edit().putBoolean(KEY_AI_REPLY_MODE, enabled).apply()
        if (enabled) preferences(context).edit().putBoolean(KEY_KEEP_ACCOUNT_MODE, false).apply()
    }
}
