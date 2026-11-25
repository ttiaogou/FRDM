package com.freedom.application.runtime

import com.freedom.core.runtime.ScheduledTask
import com.freedom.core.runtime.TaskScheduler
import com.freedom.features.recovery.RecoveryScheduler

/**
 * Service-scoped read-only fallback for the independent page entry. The event-assisted
 * observation still owns reads and classification. This timer never starts a runtime,
 * owns a lease or dispatches a gesture; it only requests a new observation.
 * All calls are serialized by the service's Handler.
 */
internal class EntryPageObservationRetry(
    private val scheduler: TaskScheduler,
    private val currentRunGeneration: () -> Long,
    private val isObservationAllowed: () -> Boolean,
    private val onRetry: () -> Unit
) {
    private var generation: Long? = null
    private var token = 0L
    private var attempt = 0
    private var pending: ScheduledTask? = null

    fun update(shouldRetry: Boolean) {
        if (!shouldRetry || !isObservationAllowed()) {
            cancel()
            return
        }
        val current = currentRunGeneration()
        if (generation != current) {
            cancel()
            generation = current
        }
        if (pending != null) return
        attempt = (attempt + 1).coerceAtMost(RecoveryScheduler.fastRetryCount + 1)
        val expectedToken = ++token
        pending = scheduler.schedule(RecoveryScheduler.readOnlyRetryDelayMs(attempt)) {
            if (token == expectedToken) {
                pending = null
                if (isObservationAllowed() && currentRunGeneration() == current) {
                    onRetry()
                } else cancel()
            }
        }
    }

    fun cancel() {
        ++token
        pending?.cancel()
        pending = null
        generation = null
        attempt = 0
    }
}
