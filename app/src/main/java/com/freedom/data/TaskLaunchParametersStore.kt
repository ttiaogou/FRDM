package com.freedom.data

import com.freedom.application.TaskLaunchParameters
import com.freedom.application.ports.TaskLaunchParametersRepository
import com.freedom.features.aireply.policy.ConversationFilterSettings
import android.content.Context
import org.json.JSONArray

class TaskLaunchParametersStore(context: Context) : TaskLaunchParametersRepository {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE
    )

    @Synchronized
    override fun read(): TaskLaunchParameters = TaskLaunchParameters(
        maxDistanceKm = preferences.getInt(KEY_MAX_DISTANCE_KM, 0).coerceAtLeast(0),
        minAge = preferences.getInt(KEY_MIN_AGE, 0).coerceIn(0, 120),
        maxAge = preferences.getInt(KEY_MAX_AGE, 0).coerceIn(0, 120),
        maxLastActiveMinutes = readMaxLastActiveMinutes(),
        messageInterceptionThreshold = preferences.getInt(
            KEY_MESSAGE_INTERCEPTION_THRESHOLD,
            preferences.getInt(LEGACY_KEY_REPLY_LIMIT, 0)
        ).coerceAtLeast(0),
        imageSendThreshold = preferences.getInt(
            KEY_IMAGE_SEND_THRESHOLD,
            preferences.getInt(LEGACY_KEY_IMAGE_PRE_REPLY_LIMIT, 0)
        ).coerceAtLeast(0),
        imageResourceUris = readImageResourceUris(),
        imageSendTestRequestId = preferences.getString(KEY_IMAGE_SEND_TEST_REQUEST, null)
            ?.takeIf { it.isNotBlank() }
    )

    @Synchronized
    override fun write(parameters: TaskLaunchParameters) {
        preferences.edit()
            .putInt(KEY_MAX_DISTANCE_KM, parameters.maxDistanceKm.coerceAtLeast(0))
            .putInt(KEY_MIN_AGE, parameters.minAge.coerceIn(0, 120))
            .putInt(KEY_MAX_AGE, parameters.maxAge.coerceIn(0, 120))
            .putInt(KEY_MAX_LAST_ACTIVE_MINUTES, parameters.maxLastActiveMinutes.coerceAtLeast(0))
            .remove(LEGACY_KEY_ONLINE_ONLY)
            .putInt(KEY_MESSAGE_INTERCEPTION_THRESHOLD, parameters.messageInterceptionThreshold.coerceAtLeast(0))
            .putInt(KEY_IMAGE_SEND_THRESHOLD, parameters.imageSendThreshold.coerceAtLeast(0))
            .putString(KEY_IMAGE_SEND_TEST_REQUEST, parameters.imageSendTestRequestId)
            .putString(KEY_IMAGE_RESOURCE_URIS_ORDERED, JSONArray(parameters.imageResourceUris
                .asSequence().map(String::trim).filter(String::isNotBlank).distinct().toList()).toString())
            // Compatibility snapshot for previous versions; only the JSON list is order-preserving.
            .putStringSet(KEY_IMAGE_RESOURCE_URIS, parameters.imageResourceUris
                .asSequence().map(String::trim).filter(String::isNotBlank).distinct().toSet())
            .apply()
    }

    @Synchronized
    override fun isImageSendTestRequestAvailable(requestId: String): Boolean {
        val settings = read()
        return requestId.isNotBlank() && settings.imageSendTestRequestId == requestId &&
            settings.imageSendTestValidationError() == null &&
            preferences.getString(KEY_IMAGE_SEND_TEST_CONSUMED, null) != requestId
    }

    /** Consume BEFORE the first UI mutation, including when a later send result is unknown. */
    @Synchronized
    override fun claimImageSendTestRequest(requestId: String): Boolean =
        isImageSendTestRequestAvailable(requestId) &&
            preferences.edit().putString(KEY_IMAGE_SEND_TEST_CONSUMED, requestId).commit()

    private fun readImageResourceUris(): List<String> {
        val ordered = preferences.getString(KEY_IMAGE_RESOURCE_URIS_ORDERED, null)
        if (ordered != null) {
            runCatching {
                val array = JSONArray(ordered)
                (0 until array.length()).mapNotNull { array.optString(it).takeIf(String::isNotBlank) }
                    .map(String::trim).filter(String::isNotBlank).distinct()
            }.getOrNull()?.let { return it }
        }
        // Legacy StringSet has no recoverable order; sort once for a stable migration.
        return preferences.getStringSet(KEY_IMAGE_RESOURCE_URIS, emptySet()).orEmpty()
            .asSequence().map(String::trim).filter(String::isNotBlank).distinct().sorted().toList()
    }

    // The old Boolean was never enforced by the runtime. Initialize the new setting
    // to the agreed 60-minute default once; an explicitly saved 0 must survive upgrades.
    private fun readMaxLastActiveMinutes(): Int {
        val default = ConversationFilterSettings.DEFAULT_MAX_LAST_ACTIVE_MINUTES
        if (!preferences.contains(KEY_MAX_LAST_ACTIVE_MINUTES)) {
            preferences.edit().putInt(KEY_MAX_LAST_ACTIVE_MINUTES, default)
                .remove(LEGACY_KEY_ONLINE_ONLY).apply()
        }
        return preferences.getInt(KEY_MAX_LAST_ACTIVE_MINUTES, default).coerceAtLeast(0)
    }

    private companion object {
        const val PREFERENCES_NAME = "freedom_task_launch_parameters"
        const val KEY_MAX_DISTANCE_KM = "max_distance_km"
        const val KEY_MIN_AGE = "min_age"
        const val KEY_MAX_AGE = "max_age"
        const val KEY_MAX_LAST_ACTIVE_MINUTES = "max_last_active_minutes"
        const val LEGACY_KEY_ONLINE_ONLY = "online_only"
        const val KEY_MESSAGE_INTERCEPTION_THRESHOLD = "message_interception_threshold"
        const val KEY_IMAGE_SEND_THRESHOLD = "image_send_threshold"
        const val KEY_IMAGE_SEND_TEST_REQUEST = "image_send_test_request"
        const val KEY_IMAGE_SEND_TEST_CONSUMED = "image_send_test_consumed"
        const val KEY_IMAGE_RESOURCE_URIS = "image_resource_uris"
        const val KEY_IMAGE_RESOURCE_URIS_ORDERED = "image_resource_uris_ordered_json"
        const val LEGACY_KEY_REPLY_LIMIT = "reply_limit"
        const val LEGACY_KEY_IMAGE_PRE_REPLY_LIMIT = "image_pre_reply_limit"
    }
}
