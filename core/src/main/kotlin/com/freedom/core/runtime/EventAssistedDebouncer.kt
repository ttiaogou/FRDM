package com.freedom.core.runtime

/**
 * Coalesces bursts of accessibility events while guaranteeing a bounded maximum delay.
 * Unlike a plain trailing-edge debounce, continuous event traffic cannot postpone inspection forever.
 * A generation token makes cancellation safe even when a scheduler has already queued a stale callback.
 */
class EventAssistedDebouncer(
    private val scheduler: TaskScheduler,
    private val quietPeriodMs: Long,
    private val maxWaitMs: Long,
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000L },
    private val task: () -> Unit
) {
    private var firstSignalAtMillis: Long? = null
    private var scheduled: ScheduledTask? = null
    private var generation = 0L

    init {
        require(quietPeriodMs >= 0L) { "quietPeriodMs cannot be negative" }
        require(maxWaitMs > 0L) { "maxWaitMs must be positive" }
    }

    @Synchronized
    fun signal() {
        val now = nowMillis()
        val first = firstSignalAtMillis ?: now.also { firstSignalAtMillis = it }
        val elapsed = (now - first).coerceAtLeast(0L)
        val remaining = (maxWaitMs - elapsed).coerceAtLeast(0L)
        val delay = minOf(quietPeriodMs, remaining)
        scheduled?.cancel()
        generation += 1L
        val expectedGeneration = generation
        val candidate = scheduler.schedule(delay) {
            val shouldRun = synchronized(this) {
                if (expectedGeneration != generation) {
                    false
                } else {
                    scheduled = null
                    firstSignalAtMillis = null
                    generation += 1L
                    true
                }
            }
            if (shouldRun) task()
        }
        // A scheduler is allowed to execute a zero-delay callback synchronously. Do not retain
        // an already-completed handle if that happened before schedule() returned.
        if (expectedGeneration == generation && firstSignalAtMillis != null) {
            scheduled = candidate
        } else {
            candidate.cancel()
        }
    }

    @Synchronized
    fun cancel() {
        generation += 1L
        scheduled?.cancel()
        scheduled = null
        firstSignalAtMillis = null
    }

    @Synchronized
    fun isPending(): Boolean = scheduled != null
}
