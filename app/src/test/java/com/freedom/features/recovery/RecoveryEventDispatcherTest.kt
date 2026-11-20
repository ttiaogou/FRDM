package com.freedom.features.recovery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecoveryEventDispatcherTest {
    private fun identity(observationGeneration: Long, runGeneration: Long = 7L) =
        RecoveryEventIdentity(
            taskId = "TASK",
            routeId = "ROUTE",
            runGeneration = runGeneration,
            checkpointId = "CHECKPOINT",
            observationGeneration = observationGeneration,
            unknownReason = "UNKNOWN_PAGE"
        )

    @Test
    fun staleObservationIsDiscardedWithoutChangingState() {
        val machine = UnifiedRecoveryTransactionStateMachine()
        val logs = mutableListOf<String>()
        val dispatcher = RecoveryEventDispatcher(machine, logs::add)

        dispatcher.activate(identity(4L))
        dispatcher.dispatch(RecoveryTransactionEvent.UnknownPage("new"), identity(4L))
        val stateAfterNewEvent = dispatcher.state

        dispatcher.dispatch(RecoveryTransactionEvent.ResumeCompleted, identity(3L))

        assertEquals(stateAfterNewEvent, dispatcher.state)
        assertTrue(logs.any { it.contains("outcome=stale_discarded") })
    }

    @Test
    fun differentRunCannotMutateCurrentRecoveryTransaction() {
        val machine = UnifiedRecoveryTransactionStateMachine()
        val dispatcher = RecoveryEventDispatcher(machine)
        dispatcher.activate(identity(1L, runGeneration = 8L))
        dispatcher.dispatch(RecoveryTransactionEvent.UnknownPage(), identity(1L, runGeneration = 8L))

        dispatcher.dispatch(RecoveryTransactionEvent.ResumeCompleted, identity(2L, runGeneration = 9L))

        assertEquals(RecoveryTransactionState.PAUSED, dispatcher.state)
    }
}
