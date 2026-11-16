package com.freedom.adapters.social.momo

/**
 * Tracks repeated incomplete page-safety inspections for diagnostics only.
 * The count is capped, but never exhausts the task's retryability: an incomplete/obstructed
 * snapshot must keep the pending task recoverable and must never grant permission to act.
 */
internal class MomoPopupSafetyRetryPolicy(
    private val maxIncompleteAttemptsPerWindow: Int = 3,
    private val retrySequenceWindowMs: Long = 5_000L
) {
    data class Attempt(
        val number: Int,
        val maximum: Int,
        val retryable: Boolean
    )

    private var activeWindowKey: String? = null
    private var incompleteAttempts = 0
    private var lastIncompleteAtUptimeMs: Long? = null

    init {
        require(maxIncompleteAttemptsPerWindow >= 1)
        require(retrySequenceWindowMs > 0L)
    }

    @Synchronized
    fun onIncomplete(windowKey: String, nowUptimeMs: Long): Attempt {
        require(windowKey.isNotBlank())
        val lastAttempt = lastIncompleteAtUptimeMs
        val sequenceExpired = lastAttempt != null &&
            (nowUptimeMs - lastAttempt) > retrySequenceWindowMs
        if (activeWindowKey != windowKey || sequenceExpired) {
            activeWindowKey = windowKey
            incompleteAttempts = 0
        }
        incompleteAttempts = (incompleteAttempts + 1).coerceAtMost(maxIncompleteAttemptsPerWindow)
        lastIncompleteAtUptimeMs = nowUptimeMs
        return Attempt(
            number = incompleteAttempts,
            maximum = maxIncompleteAttemptsPerWindow,
            retryable = true
        )
    }

    @Synchronized
    fun onComplete(windowKey: String, nowUptimeMs: Long) {
        require(windowKey.isNotBlank())
        activeWindowKey = windowKey
        incompleteAttempts = 0
        lastIncompleteAtUptimeMs = nowUptimeMs
    }
}
