package com.freedom.data

import android.content.Context
import com.freedom.application.ports.AiModelCloudConsentSettings

/** Persists explicit remote-model processing consent; missing or outdated consent always denies. */
internal class SharedPreferencesAiModelCloudConsent(context: Context) : AiModelCloudConsentSettings {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE
    )

    override fun isGranted(): Boolean =
        preferences.getBoolean(KEY_GRANTED, false) &&
            preferences.getInt(KEY_POLICY_VERSION, 0) == CURRENT_POLICY_VERSION

    override fun setGranted(granted: Boolean) {
        val editor = preferences.edit().putBoolean(KEY_GRANTED, granted)
        if (granted) {
            editor.putInt(KEY_POLICY_VERSION, CURRENT_POLICY_VERSION)
                .putLong(KEY_GRANTED_AT_MILLIS, System.currentTimeMillis())
        } else {
            editor.remove(KEY_POLICY_VERSION).remove(KEY_GRANTED_AT_MILLIS)
        }
        check(editor.commit()) { "无法保存云端模型处理授权状态" }
    }

    private companion object {
        const val PREFERENCES_NAME = "freedom_ai_model_privacy"
        const val KEY_GRANTED = "cloud_processing_consent_granted"
        const val KEY_POLICY_VERSION = "cloud_processing_consent_policy_version"
        const val KEY_GRANTED_AT_MILLIS = "cloud_processing_consent_granted_at_millis"
        const val CURRENT_POLICY_VERSION = 1
    }
}
