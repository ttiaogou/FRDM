package com.freedom.features.recovery

import com.freedom.core.runtime.RunMode
import com.freedom.core.runtime.TaskCoordinator
import com.freedom.core.runtime.UiOperationOwnershipCoordinator
import com.freedom.core.runtime.UiWriteAuthorization
import com.freedom.core.runtime.UiWriteScope
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
    fun verifiedDestinationRebindPreservesCheckpointAndRejectsOldRouteEvents() {
        val manager = UnifiedRecoveryTransactionManager({ 7L })
        val dispatcher = dispatcher(manager)
        val detail = identity("AI_REPLY_PRIVATE_MESSAGES", route = "AI_REPLY_CHAT_DETAIL")
        dispatcher.activate(detail)
        dispatcher.dispatch(RecoveryTransactionEvent.UnknownPage("EMPTY_SNAPSHOT"))
        dispatcher.dispatch(RecoveryTransactionEvent.VerificationStarted(1))
        dispatcher.dispatch(RecoveryTransactionEvent.VerificationSample(stable = true))
        val list = detail.copy(routeId = detail.taskId, observationGeneration = 2L)

        assertFalse(manager.rebindPendingRoute(detail, list.copy(checkpointId = "different")))
        assertFalse(manager.rebindPendingRoute(detail, list.copy(runGeneration = 8L)))
        assertFalse(manager.rebindPendingRoute(detail, list.copy(observationGeneration = 1L)))
        assertTrue(manager.rebindPendingRoute(detail, list))
        assertEquals(detail.checkpointId, manager.snapshot(detail.taskId)?.checkpointId)
        assertEquals(list.routeId, manager.snapshot(detail.taskId)?.routeId)
        assertEquals(RecoveryTransactionState.RECOVERING, manager.snapshot(detail.taskId)?.state)
        assertFalse(manager.completeVerified(detail))
        assertFalse(manager.dispatch(detail, RecoveryTransactionEvent.ResumeCompleted,
            RecoveryTransactionState.RESUMING) { RecoveryTransactionState.ACTIVE }.accepted)

        dispatcher.reset()
        dispatcher.activate(list)
        dispatcher.dispatch(RecoveryTransactionEvent.CheckpointLoaded(
            RecoveryTransactionEvent.Source.PAGE_OBSERVATION))
        dispatcher.dispatch(RecoveryTransactionEvent.VerificationStarted(1))
        dispatcher.dispatch(RecoveryTransactionEvent.VerificationSample(stable = true))
        dispatcher.dispatch(RecoveryTransactionEvent.ResumeRequested)
        assertEquals(RecoveryTransactionState.VERIFYING, dispatcher.state)
        dispatcher.dispatch(RecoveryTransactionEvent.VerificationSample(stable = true))
        dispatcher.dispatch(RecoveryTransactionEvent.ResumeRequested)
        dispatcher.dispatch(RecoveryTransactionEvent.ResumeCompleted)
        assertEquals(RecoveryTransactionState.ACTIVE, dispatcher.state)
        assertNull(manager.snapshot(detail.taskId))
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
    fun taskSwitchDuringUnknownCannotGrantUiOwnershipBeforeVerifiedHandoff() {
        val tasks = TaskCoordinator(UiOperationOwnershipCoordinator())
        val runGeneration = 31L
        val gate = UiWriteAuthorization(tasks, { runGeneration }, { RunMode.AI_REPLY })
        val previousLease = requireNotNull(tasks.tryAcquire("AI_REPLY_PRIVATE_MESSAGES", runGeneration))
        var writes = 0
        assertEquals(1, gate.withAuthorizedWrite(UiWriteScope.AI_COMPOSER) { ++writes })

        val manager = UnifiedRecoveryTransactionManager({ runGeneration })
        val privateRecovery = dispatcher(manager)
        privateRecovery.activate(identity("AI_REPLY_PRIVATE_MESSAGES", runGeneration))
        assertEquals(
            RecoveryTransactionState.RECOVERING,
            privateRecovery.dispatch(RecoveryTransactionEvent.UnknownPage("PAGE_UNKNOWN"))
        )

        // UNKNOWN is a read-only hold; the business coordinator withdraws its exact
        // lease. The recovery manager itself must not be treated as a lease issuer.
        assertTrue(tasks.release(previousLease))
        tasks.receiveBusinessTaskSwitch(
            "AI_REPLY_PRIVATE_MESSAGES", "AI_REPLY_RECEIVED_GREETINGS", "unknown_recovery_handoff"
        )
        assertNull(tasks.currentLease())
        assertNull(gate.withAuthorizedWrite(UiWriteScope.GLOBAL_NAVIGATION) { ++writes })

        val greetings = dispatcher(manager)
        greetings.activate(identity(
            "AI_REPLY_RECEIVED_GREETINGS", runGeneration, checkpoint = "greetings-checkpoint"
        ))
        greetings.dispatch(RecoveryTransactionEvent.CheckpointLoaded(
            RecoveryTransactionEvent.Source.PAGE_OBSERVATION
        ))
        greetings.dispatch(RecoveryTransactionEvent.VerificationStarted(1))
        greetings.dispatch(RecoveryTransactionEvent.VerificationSample(stable = true))
        assertEquals(
            RecoveryTransactionState.VERIFYING,
            greetings.dispatch(RecoveryTransactionEvent.ResumeRequested)
        )
        assertNull(gate.withAuthorizedWrite(UiWriteScope.AUTONOMOUS_GESTURE) { ++writes })

        greetings.dispatch(RecoveryTransactionEvent.VerificationSample(stable = true))
        assertEquals(
            RecoveryTransactionState.RESUMING,
            greetings.dispatch(RecoveryTransactionEvent.ResumeRequested)
        )
        assertNull(gate.withAuthorizedWrite(UiWriteScope.GLOBAL_NAVIGATION) { ++writes })
        assertEquals(
            RecoveryTransactionState.ACTIVE,
            greetings.dispatch(RecoveryTransactionEvent.ResumeCompleted)
        )
        // Even verified recovery is not an implicit UI lease grant.
        assertNull(gate.withAuthorizedWrite(UiWriteScope.GLOBAL_NAVIGATION) { ++writes })

        val nextLease = requireNotNull(tasks.tryAcquire("AI_REPLY_RECEIVED_GREETINGS", runGeneration))
        assertTrue(tasks.isCurrent(nextLease))
        assertFalse(gate.permits(UiWriteScope.AI_COMPOSER))
        assertTrue(gate.permits(UiWriteScope.GLOBAL_NAVIGATION))
        assertNull(tasks.withCurrentLease(previousLease) { ++writes })
        assertNull(gate.withAuthorizedWrite(UiWriteScope.AI_COMPOSER) { ++writes })
        assertEquals(2, gate.withAuthorizedWrite(UiWriteScope.GLOBAL_NAVIGATION) { ++writes })
        assertEquals(2, writes)
        assertEquals(RecoveryTransactionState.RECOVERING, manager.snapshot("AI_REPLY_PRIVATE_MESSAGES")?.state)
    }

    @Test
    fun oldRunRecoveryCallbacksCannotReauthorizeAfterRunGenerationRevocation() {
        var runGeneration = 41L
        val tasks = TaskCoordinator(UiOperationOwnershipCoordinator())
        val gate = UiWriteAuthorization(tasks, { runGeneration }, { RunMode.AI_REPLY })
        val oldLease = requireNotNull(tasks.tryAcquire("AI_REPLY_PRIVATE_MESSAGES", runGeneration))
        val manager = UnifiedRecoveryTransactionManager({ runGeneration })
        val oldRecovery = dispatcher(manager)
        oldRecovery.activate(identity("AI_REPLY_PRIVATE_MESSAGES", runGeneration, checkpoint = "old"))
        oldRecovery.dispatch(RecoveryTransactionEvent.UnknownPage("PAGE_UNKNOWN"))
        assertTrue(gate.permits(UiWriteScope.AI_COMPOSER))

        // A service-reconnect/new run invalidates all previously captured capabilities.
        runGeneration = 42L
        tasks.advanceRunGeneration(runGeneration)
        assertFalse(tasks.isCurrent(oldLease))
        assertNull(gate.withAuthorizedWrite(UiWriteScope.AI_COMPOSER) { "unsafe" })
        assertEquals(
            RecoveryTransactionState.RECOVERING,
            oldRecovery.dispatch(RecoveryTransactionEvent.ResumeCompleted)
        )
        assertEquals(
            RecoveryTransactionState.RECOVERING,
            oldRecovery.dispatch(RecoveryTransactionEvent.ResumeRequested)
        )
        assertNull(manager.snapshot("AI_REPLY_PRIVATE_MESSAGES"))

        val freshRecovery = dispatcher(manager)
        freshRecovery.activate(identity(
            "AI_REPLY_PRIVATE_MESSAGES", runGeneration, checkpoint = "fresh-checkpoint"
        ))
        freshRecovery.dispatch(RecoveryTransactionEvent.CheckpointLoaded(
            RecoveryTransactionEvent.Source.SERVICE_RECONNECT
        ))
        freshRecovery.dispatch(RecoveryTransactionEvent.VerificationStarted(1))
        freshRecovery.dispatch(RecoveryTransactionEvent.VerificationSample(stable = true))
        assertEquals(
            RecoveryTransactionState.VERIFYING,
            freshRecovery.dispatch(RecoveryTransactionEvent.ResumeRequested)
        )
        assertNull(tasks.currentLease())
        freshRecovery.dispatch(RecoveryTransactionEvent.VerificationSample(stable = true))
        assertEquals(
            RecoveryTransactionState.RESUMING,
            freshRecovery.dispatch(RecoveryTransactionEvent.ResumeRequested)
        )
        freshRecovery.dispatch(RecoveryTransactionEvent.ResumeCompleted)
        assertNull(gate.withAuthorizedWrite(UiWriteScope.AI_COMPOSER) { "unsafe" })

        val freshLease = requireNotNull(tasks.tryAcquire("AI_REPLY_PRIVATE_MESSAGES", runGeneration))
        assertTrue(tasks.isCurrent(freshLease))
        assertNull(tasks.withCurrentLease(oldLease) { "unsafe" })
        assertEquals(
            "authorized",
            gate.withAuthorizedWrite(UiWriteScope.AI_COMPOSER) { "authorized" }
        )
        assertNull(manager.snapshot("AI_REPLY_PRIVATE_MESSAGES"))
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
    fun unidentifiedProductionEventCannotMutateLocalReducer() {
        val diagnostics = mutableListOf<String>()
        val manager = UnifiedRecoveryTransactionManager({ 9L }, diagnostics::add)
        val dispatcher = RecoveryEventDispatcher(
            UnifiedRecoveryTransactionStateMachine(), sharedManager = manager
        )
        assertEquals(RecoveryTransactionState.ACTIVE,
            dispatcher.dispatch(RecoveryTransactionEvent.UnknownPage("NO_TASK")))
        assertEquals(RecoveryTransactionState.ACTIVE, dispatcher.state)
        assertTrue(manager.trackedTaskIds().isEmpty())
        assertTrue(diagnostics.any { it.contains("unbound_observation") })
        assertEquals(RecoveryTransactionState.ACTIVE,
            dispatcher.observeFingerprint("unverified-fingerprint"))
        assertTrue(manager.trackedTaskIds().isEmpty())
    }

    @Test
    fun staleRunFingerprintCannotBypassManagerAndCompleteRecovery() {
        var currentGeneration = 2L
        val manager = UnifiedRecoveryTransactionManager({ currentGeneration })
        val dispatcher = RecoveryEventDispatcher(
            UnifiedRecoveryTransactionStateMachine(), sharedManager = manager
        )
        val id = identity("ACCOUNT_BROWSE", 2L)
        dispatcher.activate(id)
        dispatcher.dispatch(RecoveryTransactionEvent.UnknownPage("PAGE_UNKNOWN"))
        currentGeneration = 3L
        assertEquals(RecoveryTransactionState.RECOVERING,
            dispatcher.observeFingerprint("fresh-visual-page"))
        assertEquals(0, dispatcher.stableVerificationSamples)
        assertEquals(RecoveryTransactionState.RECOVERING, dispatcher.state)
        assertTrue(manager.trackedTaskIds().isEmpty())
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
