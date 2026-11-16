package com.freedom.data

import android.content.Context

/** Persists per-package OCR plugin enablement in the Data layer. */
class OcrPluginSettingsStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    fun setEnabled(packageName: String, enabled: Boolean) {
        preferences.edit().putBoolean(enabledKey(packageName), enabled).apply()
    }

    fun isEnabled(packageName: String): Boolean =
        preferences.getBoolean(enabledKey(packageName), true)

    private fun enabledKey(packageName: String): String = "enabled:$packageName"

    private companion object {
        const val PREFERENCES_NAME = "ocr_plugin_manager"
    }
}
