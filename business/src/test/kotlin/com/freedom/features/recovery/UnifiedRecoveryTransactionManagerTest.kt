package com.freedom.features.recovery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** P6: the process-wide admission barrier owns task, checkpoint and run identity. */
class UnifiedRecoveryTransactionManagerTest {
    private fun identity(
        task: String,
        generation: Long = 7L,
        route: String = task,
        checkpoint: String = "checkpoint-1",
        observation: Long = 1L
    ) = RecoveryEventIdentity(
        taskId = task,
        routeId = route,
        runGeneration = generation,
        checkpointId = checkpoint,
        observationGeneration = observation,
        unknownReason = "PAGE_UNKNOWN"
    )

    private fun dispatcher(manager: UnifiedRecoveryTransactionManager) =
        RecoveryEventDispatcher(UnifiedRecoveryTransactionStateMachine(), sharedManager = manager)

    @Test
    fun aiAndBrowseUnknownShareOneManagerButRetainIndependentVerification() {
        val manager = UnifiedRecoveryTransactionManager({ 7L })
        val ai = dispatcher(manager)
        val browse = dispatcher(manager)
        val aiIdentity = identity("AI_REPLY_PRIVATE_MESSAGES")
        val browseIdentity = identity("ACCOUNT_BROWSE", route = "account-browse")
        ai.activate(aiIdentity)
        browse.activate(browseIdentity)
        assertEquals(RecoveryTransactionState.RECOVERING,
            ai.dispatch(RecoveryTransactionEvent.UnknownPage("EMPTY_SNAPSHOT")))
        assertEquals(RecoveryTransactionState.RECOVERING,
            browse.dispatch(RecoveryTransactionEvent.UnknownPage("BROWSE_UNKNOWN")))
        assertEquals(setOf("AI_REPLY_PRIVATE_MESSAGES", "ACCOUNT_BROWSE"), manager.trackedTaskIds())

        ai.dispatch(RecoveryTransactionEvent.VerificationStarted(1))
        ai.dispatch(RecoveryTransactionEvent.VerificationSample(stable = true))
        ai.dispatch(RecoveryTransactionEvent.VerificationSample(stable = true))
        ai.dispatch(RecoveryTransactionEvent.ResumeRequested)
        assertEquals(RecoveryTransactionState.RESUMING, ai.state)
        ai.dispatch(RecoveryTransactionEvent.ResumeCompleted)
        assertEquals(RecoveryTransactionState.ACTIVE, ai.state)
        assertNull(manager.snapshot(aiIdentity.taskId))
        assertEquals(RecoveryTransactionState.RECOVERING, manager.snapshot(browseIdentity.taskId)?.state)
    }

    @Test
    fun staleGenerationOrCheckpointCannotChangeCurrentRecoveryTransaction() {
        var generation = 8L
        val manager = UnifiedRecoveryTransactionManager({ generation })
        val active = dispatcher(manager)
        val id = identity("AI_REPLY_RECEIVED_GREETINGS", generation)
        active.activate(id)
        active.dispatch(RecoveryTransactionEvent.UnknownPage("GREETINGS_PAGE_UNKNOWN"))
        val attempt = manager.dispatch(
            identity(id.taskId, generation, checkpoint = "old-checkpoint", observation = 2L),
            RecoveryTransactionEvent.UnknownPage("OUT_OF_ORDER"),
            active.state
        ) { RecoveryTransactionState.RESUMING }
        assertFalse(attempt.accepted)
        assertEquals(RecoveryTransactionState.RECOVERING, manager.snapshot(id.taskId)?.state)

        generation = 9L
        val stale = manager.dispatch(id, RecoveryTransactionEvent.ResumeCompleted, active.state) {
            RecoveryTransactionState.ACTIVE
        }
        assertFalse(stale.accepted)
        assertNull(manager.snapshot(id.taskId))

        val restored = dispatcher(manager)
        val newIdentity = identity(id.taskId, generation, checkpoint = "restored-checkpoint")
        restored.activate(newIdentity)
        restored.dispatch(RecoveryTransactionEvent.CheckpointLoaded(
            RecoveryTransactionEvent.Source.SERVICE_RECONNECT))
        assertEquals(RecoveryTransactionState.RECOVERING, manager.snapshot(id.taskId)?.state)
    }

    @Test
    fun readOnlyEvidenceIsNotAnImplicitResumeOrUiAuthorization() {
        val manager = UnifiedRecoveryTransactionManager({ 15L })
        val dispatcher = dispatcher(manager)
        val id = identity("AI_REPLY_PRIVATE_MESSAGES", 15L, checkpoint = "confirmed-checkpoint")
        dispatcher.activate(id)
        dispatcher.dispatch(RecoveryTransactionEvent.UnknownPage("UNREAD_UNKNOWN"))
        dispatcher.dispatch(RecoveryTransactionEvent.VerificationStarted(1))
        dispatcher.dispatch(RecoveryTransactionEvent.VerificationSample(stable = true))
        assertEquals(RecoveryTransactionState.VERIFYING, manager.snapshot(id.taskId)?.state)
        dispatcher.dispatch(RecoveryTransactionEvent.ResumeRequested)
        assertEquals(RecoveryTransactionState.VERIFYING, manager.snapshot(id.taskId)?.state)

        dispatcher.dispatch(RecoveryTransactionEvent.VerificationSample(stable = true))
        dispatcher.dispatch(RecoveryTransactionEvent.ResumeRequested)
        assertEquals(RecoveryTransactionState.RESUMING, manager.snapshot(id.taskId)?.state)
        // No further event (or merely a matching page) grants a lease.
        assertTrue(manager.snapshot(id.taskId) != null)
        dispatcher.dispatch(RecoveryTransactionEvent.ResumeCompleted)
        assertNull(manager.snapshot(id.taskId))
    }

    @Test
    fun checkpointVerificationLaneDoesNotConsumeMainTransactionSamples() {
        val manager = UnifiedRecoveryTransactionManager({ 21L })
        val task = "AI_REPLY_PRIVATE_MESSAGES"
        val id = identity(task, 21L, checkpoint = "persisted-checkpoint")
        val main = dispatcher(manager)
        val verifier = RecoveryEventDispatcher(
            UnifiedRecoveryTransactionStateMachine(),
            sharedManager = manager,
            managerLaneId = "AI_REPLY_PRIVATE_MESSAGES_CHECKPOINT"
        )
        main.activate(id)
        verifier.activate(id.copy(observationGeneration = 0L))
        main.dispatch(RecoveryTransactionEvent.UnknownPage("PAGE_UNKNOWN"))
        verifier.dispatch(RecoveryTransactionEvent.CheckpointLoaded(
            RecoveryTransactionEvent.Source.SERVICE_RECONNECT))

        assertEquals(RecoveryTransactionState.RECOVERING, manager.snapshot(task)?.state)
        assertEquals(RecoveryTransactionState.RECOVERING,
            manager.snapshot(task, "AI_REPLY_PRIVATE_MESSAGES_CHECKPOINT")?.state)

        verifier.dispatch(RecoveryTransactionEvent.VerificationStarted(1),
            id.copy(observationGeneration = 1L))
        verifier.dispatch(RecoveryTransactionEvent.VerificationSample(true),
            id.copy(observationGeneration = 1L))
        assertEquals(RecoveryTransactionState.VERIFYING,
            manager.snapshot(task, "AI_REPLY_PRIVATE_MESSAGES_CHECKPOINT")?.state)
        assertEquals(RecoveryTransactionState.RECOVERING, manager.snapshot(task)?.state)
        assertTrue(manager.completeVerified(id))
        assertNull(manager.snapshot(task))
        assertNull(manager.snapshot(task, "AI_REPLY_PRIVATE_MESSAGES_CHECKPOINT"))
    }

    @Test
    fun unboundPreRunObservationsNeverCreateInventedTasks() {
        val seen = mutableListOf<String>()
        val manager = UnifiedRecoveryTransactionManager({ 0L }, seen::add)
        val dispatcher = dispatcher(manager)
        dispatcher.dispatch(RecoveryTransactionEvent.UnknownPage("NO_ACTIVE_TASK"))
        assertTrue(manager.trackedTaskIds().isEmpty())
        assertTrue(seen.any { it.contains("recoveryManager=unbound_observation") })
    }
}
