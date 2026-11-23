package com.freedom.features.accountbrowse

import com.freedom.core.runtime.ScheduledTask
import com.freedom.core.runtime.TaskScheduler

/**
 * Owns the single delayed callback that advances the automatic-browse state machine.
 *
 * Contract preserved from FreedomAccessibilityService:
 * 1. Eligibility is checked before scheduling.
 * 2. An already queued step is never replaced by a later request.
 * 3. The pending flag is cleared immediately before invoking the state-machine step.
 * 4. Cancellation removes this exact callback and clears the pending flag.
 *
 * Manual-touch recovery, overlay watchdog, countdown and gesture-calibration callbacks
 * intentionally remain separate because they have independent lifecycles and deadlines.
 */
internal class AccountBrowseScheduler(
    private val scheduler: TaskScheduler,
    private val canSchedule: () -> Boolean,
    private val onStep: () -> Unit
) {
    private var pending = false
    private var nextToken = 0L
    private var activeToken: Long? = null
    private var scheduledTask: ScheduledTask? = null

    fun schedule(delayMs: Long) {
        if (!canSchedule() || pending) return
        pending = true
        val token = ++nextToken
        activeToken = token
        val task = scheduler.schedule(delayMs.coerceAtLeast(0L)) callback@{
            if (activeToken != token) return@callback
            activeToken = null
            pending = false
            scheduledTask = null
            onStep()
        }
        if (activeToken == token) {
            scheduledTask = task
        } else {
            // A scheduler may execute immediately or the callback may already be invalidated.
            task.cancel()
        }
    }

    fun cancel() {
        activeToken = null
        scheduledTask?.cancel()
        scheduledTask = null
        pending = false
    }
}
