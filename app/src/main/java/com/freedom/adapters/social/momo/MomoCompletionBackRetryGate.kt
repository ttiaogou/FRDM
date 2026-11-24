package com.freedom.adapters.social.momo

/** A submitted BACK cannot be replayed until its settle interval and fresh source proof pass. */
internal class MomoCompletionBackRetryGate(private val timeoutMs: Long) {
    private val confirmation = MomoSayHiListReturnConfirmationPolicy()
    private var submittedAtMs: Long? = null

    init { require(timeoutMs > 0L) }

    @Synchronized
    fun onSubmitted(nowMs: Long) {
        submittedAtMs = nowMs
        confirmation.reset()
    }

    @Synchronized
    fun mayRetry(sourceKey: String?, nowMs: Long): Boolean {
        val submitted = submittedAtMs ?: return false
        if (nowMs - submitted < timeoutMs || sourceKey.isNullOrBlank()) {
            confirmation.reset()
            return false
        }
        return confirmation.observe(sourceKey, nowMs)
    }

    @Synchronized
    fun invalidateSamples() = confirmation.reset()

    @Synchronized
    fun reset() {
        submittedAtMs = null
        confirmation.reset()
    }
}
