package com.freedom.features.recovery

import com.freedom.core.runtime.ScheduledTask
import com.freedom.core.runtime.TaskScheduler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class R09dRecoveryIsolationTest {
    private class Timer : TaskScheduler {
        data class Entry(val delay: Long, val callback: () -> Unit, var cancelled: Boolean = false)
        val entries = mutableListOf<Entry>()
        override fun schedule(delayMillis: Long, task: () -> Unit): ScheduledTask {
            val entry = Entry(delayMillis, task)
            entries.add(entry)
            return ScheduledTask { entry.cancelled = true }
        }
    }

    @Test
    fun reconnectRetainsCheckpointButNotOldTimerOrVerificationProof() {
        var generation = 7L
        val timer = Timer()
        val observations = mutableListOf<RecoveryScheduleSnapshot>()
        var durable: RecoveryScheduleSnapshot? = null
        val repository = object : RecoveryScheduleRepository {
            override fun load() = durable
            override fun save(snapshot: RecoveryScheduleSnapshot) { durable = snapshot }
            override fun clear() { durable = null }
        }
        val scheduler = RecoveryScheduler(timer, { 1_000L }, { observations.add(it) },
            scheduleRepository = repository)
        val manager = UnifiedRecoveryTransactionManager({ generation })
        fun dispatcher() = RecoveryEventDispatcher(UnifiedRecoveryTransactionStateMachine(), sharedManager = manager)
        val oldDispatcher = dispatcher()
        val old = RecoveryCheckpointVerificationCoordinator(oldDispatcher, scheduler)
        val checkpoint = RecoveryCheckpoint(
            "checkpoint", "AI_REPLY_PRIVATE_MESSAGES", "private-list", generation,
            "WAIT_FOR_LIST_RETURN", "com.immomo.momo", "CHAT_LIST", "list-fingerprint",
            "contact-fingerprint", "cursor-hash", setOf("completed-hash"), "UNKNOWN_PAGE", 1_000L
        )
        fun evidence(run: Long, observation: Long) = RecoveryVerificationEvidence(
            checkpoint.targetPackage, checkpoint.pageType, checkpoint.pageFingerprint,
            checkpoint.targetIdentityFingerprint, run, observation
        )
        old.restore(checkpoint, RecoveryCheckpointRestoreSource.PAGE_OBSERVATION)
        assertEquals(RecoveryCheckpointVerificationResult.Verifying(1), old.observe(evidence(generation, 1L)))
        val oldTimer = timer.entries.last()
        val oldIdentity = requireNotNull(oldDispatcher.currentIdentity())
        scheduler.suspendForServiceDisconnect()
        assertTrue(oldTimer.cancelled)
        generation++
        val restored = RecoveryCheckpointVerificationCoordinator(dispatcher(), scheduler)
        val newCheckpoint = checkpoint.copy(runGeneration = generation)
        restored.restore(newCheckpoint, RecoveryCheckpointRestoreSource.SERVICE_RECONNECT)
        assertEquals(generation, durable?.runGeneration)
        assertEquals(checkpoint.checkpointId, durable?.checkpointId)
        val restoredPlan = scheduler.currentPlan()
        oldTimer.callback()
        assertTrue(observations.isEmpty())
        assertEquals(restoredPlan, scheduler.currentPlan())
        var staleApplied = false
        assertFalse(manager.dispatch(oldIdentity, RecoveryTransactionEvent.ResumeCompleted,
            oldDispatcher.state) { staleApplied = true; RecoveryTransactionState.ACTIVE }.accepted)
        assertFalse(staleApplied)
        assertEquals(RecoveryTransactionState.RECOVERING, restored.state())
        assertEquals(RecoveryCheckpointVerificationResult.Verifying(1), restored.observe(evidence(generation, 1L)))
        assertEquals(RecoveryCheckpointVerificationResult.StaleEvidence, restored.observe(evidence(generation, 1L)))
        assertEquals(RecoveryCheckpointVerificationResult.ResumeReady(checkpoint.executionPhase),
            restored.observe(evidence(generation, 2L)))
        val newTimer = timer.entries.last()
        assertTrue(restored.completeResume())
        assertNull(scheduler.currentPlan())
        assertNull(durable)
        assertNull(manager.snapshot(checkpoint.taskId))
        assertTrue(newTimer.cancelled)
        newTimer.callback()
        assertTrue(observations.isEmpty())
    }
}
