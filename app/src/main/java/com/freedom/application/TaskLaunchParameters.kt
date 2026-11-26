package com.freedom.application

import com.freedom.features.aireply.policy.ConversationFilterSettings

/**
 * Editable filter and launch-form values shared by the launcher and its persistence adapter.
 * Distance, age and activity-time filters are persisted here and consumed by the
 * received-greetings new-conversation admission path.
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
    /** Validate the image setting without requiring a pool when the feature is disabled. */
    fun imageSendThresholdValidationError(threshold: Int): String? = when {
        threshold < 0 -> "请输入大于或等于 0 的数字"
        threshold > 0 && imageResourceUris.none { it.isNotBlank() } -> "请先导入至少一张图片"
        threshold > 0 && messageInterceptionThreshold > 0 &&
            threshold > messageInterceptionThreshold -> "图片发送阈值大于消息拦截阈值；拦截会先阻断后续回复"
        else -> null
    }

    /** Append verified MediaStore resources, preserving import order and avoiding duplicate media. */
    fun withImportedImageResourceUris(imported: List<String>): TaskLaunchParameters =
        copy(imageResourceUris = (imageResourceUris + imported)
            .map(String::trim).filter(String::isNotBlank).distinct())

    fun conversationFilterSettings(): ConversationFilterSettings = ConversationFilterSettings(
        maxLastActiveMinutes = maxLastActiveMinutes,
        maxDistanceKm = maxDistanceKm,
        minAge = minAge,
        maxAge = maxAge
    )
}
