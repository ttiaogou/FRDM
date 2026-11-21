package com.freedom.features.recovery

import com.freedom.core.runtime.ScheduledTask
import com.freedom.core.runtime.TaskScheduler

/**
 * Schedules fresh, read-only observations for one shared recovery transaction at a time.
 *
 * It has no page, node, gesture or navigation dependency. A business route supplies the
 * observation callback; any dangerous operation remains outside this scheduler and must wait
 * for its own later verification gate.
 */
class RecoveryScheduler(
    private val scheduler: TaskScheduler,
    private val nowEpochMs: () -> Long,
    private val onReadOnlyObservation: (RecoveryScheduleSnapshot) -> Unit,
    private val onLog: (String) -> Unit = {}
) {
    private var active: ActiveSchedule? = null

    /** Starts (or returns) the plan for a transaction. No retry limit is applied. */
    @Synchronized
    fun begin(identity: RecoveryEventIdentity): RecoveryScheduleSnapshot {
        val current = active
        if (current != null && current.matchesTransaction(identity)) {
            if (identity.observationGeneration >= current.identity.observationGeneration) {
                current.identity = identity
            }
            return current.snapshot()
        }
        current?.cancelPending()
        return ActiveSchedule(identity).also {
            active = it
            scheduleNext(it, INITIAL_BACKOFF_STAGE)
        }.snapshot()
    }

    /** Returns the durable in-memory record callers can query while recovery is active. */
    @Synchronized
    fun currentPlan(): RecoveryScheduleSnapshot? = active?.snapshot()

    /** Touch suppresses dangerous actions elsewhere and reduces this read-only polling cadence. */
    @Synchronized
    fun onUserInteractionStarted(identity: RecoveryEventIdentity): RecoveryScheduleSnapshot? {
        val current = active?.takeIf { it.accepts(identity) } ?: return active?.snapshot()
        current.touchActive = true
        scheduleNext(current, LONG_WAIT_BACKOFF_STAGE, TOUCH_ACTIVE_OBSERVATION_DELAY_MS)
        return current.snapshot()
    }

    /** Returns automatically to the fast observation cadence; no manual-resume state exists. */
    @Synchronized
    fun onUserInteractionEnded(identity: RecoveryEventIdentity): RecoveryScheduleSnapshot? {
        val current = active?.takeIf { it.accepts(identity) } ?: return active?.snapshot()
        current.touchActive = false
        scheduleNext(current, INITIAL_BACKOFF_STAGE)
        return current.snapshot()
    }

    /** The scheduler may be ended only by the explicit user-stop path. */
    @Synchronized
    fun stopByUser(identity: RecoveryEventIdentity): Boolean {
        val current = active ?: return false
        if (!current.accepts(identity)) return false
        current.cancelPending()
        active = null
        onLog(current.logFields("stopped_by_user"))
        return true
    }

    @Synchronized
    private fun scheduleNext(
        schedule: ActiveSchedule,
        backoffStage: Int,
        delayOverrideMs: Long? = null
    ) {
        schedule.cancelPending()
        val resolvedStage = backoffStage.coerceIn(INITIAL_BACKOFF_STAGE, LONG_WAIT_BACKOFF_STAGE)
        val delayMs = delayOverrideMs ?: BACKOFF_DELAYS_MS[resolvedStage]
        val token = ++schedule.callbackToken
        schedule.backoffStage = resolvedStage
        schedule.nextObservationAtEpochMs = nowEpochMs() + delayMs
        schedule.pendingTask = scheduler.schedule(delayMs) observation@{
            val observation = synchronized(this@RecoveryScheduler) {
                val current = active
                if (current !== schedule || current.callbackToken != token) return@synchronized null
                current.pendingTask = null
                current.observationCount += 1L
                val nextStage = if (current.touchActive) {
                    LONG_WAIT_BACKOFF_STAGE
                } else {
                    (current.backoffStage + 1).coerceAtMost(LONG_WAIT_BACKOFF_STAGE)
                }
                val nextDelay = if (current.touchActive) TOUCH_ACTIVE_OBSERVATION_DELAY_MS else null
                scheduleNext(current, nextStage, nextDelay)
                current.snapshot()
            }
            observation?.let {
                onLog(it.logFields("read_only_observation_due"))
                onReadOnlyObservation(it)
            }
        }
        onLog(schedule.snapshot().logFields("observation_scheduled"))
    }

    private class ActiveSchedule(initialIdentity: RecoveryEventIdentity) {
        var identity: RecoveryEventIdentity = initialIdentity
        var observationCount: Long = 0L
        var backoffStage: Int = INITIAL_BACKOFF_STAGE
        var nextObservationAtEpochMs: Long = 0L
        var touchActive: Boolean = false
        var callbackToken: Long = 0L
        var pendingTask: ScheduledTask? = null

        fun matchesTransaction(other: RecoveryEventIdentity): Boolean =
            identity.taskId == other.taskId &&
                identity.routeId == other.routeId &&
                identity.runGeneration == other.runGeneration &&
                identity.checkpointId == other.checkpointId

        fun accepts(other: RecoveryEventIdentity): Boolean =
            matchesTransaction(other) && other.observationGeneration >= identity.observationGeneration

        fun cancelPending() {
            pendingTask?.cancel()
            pendingTask = null
        }

        fun snapshot(): RecoveryScheduleSnapshot = RecoveryScheduleSnapshot(
            taskId = identity.taskId,
            routeId = identity.routeId,
            runGeneration = identity.runGeneration,
            recoveryReason = identity.unknownReason ?: "RECOVERY_REQUESTED",
            checkpointId = identity.checkpointId,
            observationCount = observationCount,
            nextObservationAtEpochMs = nextObservationAtEpochMs,
            backoffStage = backoffStage,
            touchActive = touchActive
        )

        fun logFields(action: String): String = snapshot().logFields(action)
    }

    companion object {
        private val BACKOFF_DELAYS_MS = longArrayOf(500L, 1_000L, 2_000L, 5_000L, 10_000L, 30_000L)
        private const val INITIAL_BACKOFF_STAGE = 0
        private const val LONG_WAIT_BACKOFF_STAGE = 5
        private const val TOUCH_ACTIVE_OBSERVATION_DELAY_MS = 30_000L
    }
}

/** Immutable recovery-plan record for diagnostics and future checkpoint persistence. */
data class RecoveryScheduleSnapshot(
    val taskId: String,
    val routeId: String,
    val runGeneration: Long,
    val recoveryReason: String,
    val checkpointId: String,
    val observationCount: Long,
    val nextObservationAtEpochMs: Long,
    val backoffStage: Int,
    val touchActive: Boolean
) {
    fun logFields(action: String): String =
        "recoveryAction=$action; taskId=$taskId; routeId=$routeId; runGeneration=$runGeneration;" +
            " recoveryReason=$recoveryReason; checkpointId=$checkpointId;" +
            " observationCount=$observationCount; nextObservationAtEpochMs=$nextObservationAtEpochMs;" +
            " backoffStage=$backoffStage; touchActive=$touchActive"
}
