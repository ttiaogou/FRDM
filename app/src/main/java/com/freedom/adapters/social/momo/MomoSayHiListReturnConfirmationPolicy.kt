package com.freedom.adapters.social.momo

/**
 * Confirms a received-greetings return only from consecutive fresh observations of the same
 * active window generation. A missing/stale observation or a window change restarts the count.
 */
internal class MomoSayHiListReturnConfirmationPolicy(
    private val requiredStableSamples: Int = 2,
    private val minimumSampleSpacingMs: Long = 500L
) {
    private var lastWindowIdentity: String? = null
    private var lastSampleAtMs: Long? = null

    var stableSampleCount: Int = 0
        private set

    init {
        require(requiredStableSamples >= 1)
        require(minimumSampleSpacingMs >= 0L)
    }

    @Synchronized
    fun observe(windowIdentity: String?, observedAtMs: Long): Boolean {
        if (windowIdentity.isNullOrBlank()) {
            reset()
            return false
        }

        val previousIdentity = lastWindowIdentity
        val previousAtMs = lastSampleAtMs
        stableSampleCount = if (
            previousIdentity == windowIdentity && previousAtMs != null &&
            observedAtMs - previousAtMs >= minimumSampleSpacingMs
        ) {
            stableSampleCount + 1
        } else {
            1
        }
        lastWindowIdentity = windowIdentity
        lastSampleAtMs = observedAtMs
        return stableSampleCount >= requiredStableSamples
    }

    @Synchronized
    fun reset() {
        lastWindowIdentity = null
        lastSampleAtMs = null
        stableSampleCount = 0
    }
}
