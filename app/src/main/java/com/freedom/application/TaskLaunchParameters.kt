package com.freedom.application

import com.freedom.features.aireply.policy.ConversationFilterSettings

/**
 * Editable filter and launch-form values shared by the launcher and its persistence adapter.
 * Distance, age and activity-time filters are persisted here; the current runtime does not yet
 * consume these filter preferences, so the UI must not imply that candidate filtering is active.
 */
data class TaskLaunchParameters(
    val maxDistanceKm: Int = 0,
    val minAge: Int = 0,
    val maxAge: Int = 0,
    /** Maximum platform-displayed activity age in minutes; 0 disables the filter. */
    val maxLastActiveMinutes: Int = ConversationFilterSettings.DEFAULT_MAX_LAST_ACTIVE_MINUTES,
    /** Per-contact confirmed text-reply count after which message interception is requested; 0 disables it. */
    val messageInterceptionThreshold: Int = 0,
    /** Per-contact confirmed text-reply count after which a configured image is eligible; 0 disables it. */
    val imageSendThreshold: Int = 0,
    /** Persistable document URIs selected by the user for the future image-send transaction. */
    val imageResourceUris: List<String> = emptyList()
) {
    fun conversationFilterSettings(): ConversationFilterSettings = ConversationFilterSettings(
        maxLastActiveMinutes = maxLastActiveMinutes,
        maxDistanceKm = maxDistanceKm,
        minAge = minAge,
        maxAge = maxAge
    )
}
