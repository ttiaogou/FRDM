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
    val replyLimit: Int = 0,
    /** Stored preference; image understanding/reply execution is not wired into the runtime yet. */
    val imagePreReplyLimit: Int = 0
)
