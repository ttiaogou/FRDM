package com.freedom.application

/**
 * Editable filter and launch-form values shared by the launcher and its persistence adapter.
 * Distance, age and online-status filters are persisted here; the current runtime does not yet
 * consume these filter preferences, so the UI must not imply that candidate filtering is active.
 */
data class TaskLaunchParameters(
    val maxDistanceKm: Int = 0,
    val minAge: Int = 0,
    val maxAge: Int = 0,
    /** True means the user selected “仅在线”; currently persisted as a preference only. */
    val onlineOnly: Boolean = false,
    /** Per-contact confirmed text-reply count after which message interception is requested; 0 disables it. */
    val messageInterceptionThreshold: Int = 0,
    /** Per-contact confirmed text-reply count after which a configured image is eligible; 0 disables it. */
    val imageSendThreshold: Int = 0,
    /** Persistable document URIs selected by the user for the future image-send transaction. */
    val imageResourceUris: List<String> = emptyList()
)
