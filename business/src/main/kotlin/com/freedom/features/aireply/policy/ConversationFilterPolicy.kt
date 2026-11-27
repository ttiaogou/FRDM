package com.freedom.features.aireply.policy

/** User-selected thresholds; zero disables that specific condition. */
data class ConversationFilterSettings(
    val maxLastActiveMinutes: Int = DEFAULT_MAX_LAST_ACTIVE_MINUTES,
    val maxDistanceKm: Int = 0,
    val minAge: Int = 0,
    val maxAge: Int = 0
) {
    /** Invalid non-zero configuration still reaches the policy and fails closed. */
    val hasEnabledConditions: Boolean
        get() = maxLastActiveMinutes != 0 || maxDistanceKm != 0 || minAge != 0 || maxAge != 0

    companion object {
        const val DEFAULT_MAX_LAST_ACTIVE_MINUTES = 0
    }
}

/** Only normalized, unambiguous evidence. Null means missing, conflicting or unrecognized. */
data class ConversationFilterEvidence(
    val lastActiveMinutes: Long? = null,
    val distanceKm: Double? = null,
    val age: Int? = null
) {
    /** The receiver is the preferred observation; the other source only fills missing fields. */
    fun supplementedBy(other: ConversationFilterEvidence): ConversationFilterEvidence =
        ConversationFilterEvidence(
            lastActiveMinutes = lastActiveMinutes ?: other.lastActiveMinutes,
            distanceKm = distanceKm ?: other.distanceKm,
            age = age ?: other.age
        )
}

enum class ConversationFilterResult { ALLOW, EXCLUDE, UNKNOWN }

enum class ConversationFilterReason {
    ACTIVE_TIME_EXCEEDED, DISTANCE_EXCEEDED, AGE_BELOW_MINIMUM, AGE_ABOVE_MAXIMUM,
    ACTIVE_TIME_UNAVAILABLE, DISTANCE_UNAVAILABLE, AGE_UNAVAILABLE, INVALID_SETTINGS
}

data class ConversationFilterDecision(
    val result: ConversationFilterResult,
    val reason: ConversationFilterReason? = null
)

/** Pure admission policy. No navigation, persistence, task state or Android dependencies. */
object ConversationFilterPolicy {
    fun evaluate(
        settings: ConversationFilterSettings,
        evidence: ConversationFilterEvidence
    ): ConversationFilterDecision {
        if (settings.maxLastActiveMinutes < 0 || settings.maxDistanceKm < 0 ||
            settings.minAge !in 0..120 || settings.maxAge !in 0..120 ||
            (settings.minAge > 0 && settings.maxAge > 0 && settings.minAge > settings.maxAge)
        ) return ConversationFilterDecision(ConversationFilterResult.UNKNOWN, ConversationFilterReason.INVALID_SETTINGS)

        var unknown: ConversationFilterReason? = null
        fun missing(reason: ConversationFilterReason) {
            if (unknown == null) unknown = reason
        }
        fun exclude(reason: ConversationFilterReason) =
            ConversationFilterDecision(ConversationFilterResult.EXCLUDE, reason)

        if (settings.maxLastActiveMinutes > 0) {
            val minutes = evidence.lastActiveMinutes?.takeIf { it >= 0 }
            if (minutes == null) missing(ConversationFilterReason.ACTIVE_TIME_UNAVAILABLE)
            else if (minutes > settings.maxLastActiveMinutes.toLong())
                return exclude(ConversationFilterReason.ACTIVE_TIME_EXCEEDED)
        }
        if (settings.maxDistanceKm > 0) {
            val distance = evidence.distanceKm?.takeIf { it.isFinite() && it >= 0.0 }
            if (distance == null) missing(ConversationFilterReason.DISTANCE_UNAVAILABLE)
            else if (distance > settings.maxDistanceKm.toDouble())
                return exclude(ConversationFilterReason.DISTANCE_EXCEEDED)
        }
        if (settings.minAge > 0 || settings.maxAge > 0) {
            val age = evidence.age?.takeIf { it in 1..120 }
            if (age == null) missing(ConversationFilterReason.AGE_UNAVAILABLE)
            else {
                if (settings.minAge > 0 && age < settings.minAge)
                    return exclude(ConversationFilterReason.AGE_BELOW_MINIMUM)
                if (settings.maxAge > 0 && age > settings.maxAge)
                    return exclude(ConversationFilterReason.AGE_ABOVE_MAXIMUM)
            }
        }
        return unknown?.let { ConversationFilterDecision(ConversationFilterResult.UNKNOWN, it) }
            ?: ConversationFilterDecision(ConversationFilterResult.ALLOW)
    }
}
