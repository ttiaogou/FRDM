package com.freedom.features.recovery

import org.junit.Assert.assertEquals
import org.junit.Test

class RecoveryTransactionStateTest {
    @Test
    fun sharedStatesKeepTheRecoveryLifecycleBusinessNeutral() {
        assertEquals(
            listOf(
                "ACTIVE",
                "RECOVERING",
                "VERIFYING",
                "RESUMING",
                "STOPPED_BY_USER"
            ),
            RecoveryTransactionState.entries.map(Enum<*>::name)
        )
    }

    @Test
    fun eventsExposeOnlyGenericRecoverySignals() {
        val events: List<RecoveryTransactionEvent> = listOf(
            RecoveryTransactionEvent.UnknownPage(),
            RecoveryTransactionEvent.CheckpointLoaded(
                RecoveryTransactionEvent.Source.PROCESS_RESTART
            ),
            RecoveryTransactionEvent.VerificationStarted(1),
            RecoveryTransactionEvent.VerificationSample(stable = true),
            RecoveryTransactionEvent.UserInteractionStarted,
            RecoveryTransactionEvent.UserInteractionEnded,
            RecoveryTransactionEvent.RetryScheduled(500L),
            RecoveryTransactionEvent.ResumeRequested,
            RecoveryTransactionEvent.ResumeCompleted,
            RecoveryTransactionEvent.StoppedByUser("user_stop")
        )

        assertEquals(10, events.size)
        assertEquals(RecoveryTransactionEvent.Source.PROCESS_RESTART,
            (events[1] as RecoveryTransactionEvent.CheckpointLoaded).source)
    }

    @Test
    fun unknown_requires_two_stable_samples_before_resume() {
        val machine = UnifiedRecoveryTransactionStateMachine()

        assertEquals(RecoveryTransactionState.RECOVERING,
            machine.dispatch(RecoveryTransactionEvent.UnknownPage()))
        assertEquals(RecoveryTransactionState.VERIFYING,
            machine.dispatch(RecoveryTransactionEvent.VerificationStarted(1)))
        assertEquals(RecoveryTransactionState.VERIFYING,
            machine.dispatch(RecoveryTransactionEvent.VerificationSample(stable = true)))
        assertEquals(RecoveryTransactionState.VERIFYING,
            machine.dispatch(RecoveryTransactionEvent.VerificationSample(stable = true)))
        assertEquals(RecoveryTransactionState.RESUMING,
            machine.dispatch(RecoveryTransactionEvent.ResumeRequested))
        assertEquals(RecoveryTransactionState.ACTIVE,
            machine.dispatch(RecoveryTransactionEvent.ResumeCompleted))
    }

    @Test
    fun user_interaction_does_not_change_the_recovery_transaction_state() {
        val machine = UnifiedRecoveryTransactionStateMachine()
        machine.dispatch(RecoveryTransactionEvent.UnknownPage())
        assertEquals(RecoveryTransactionState.RECOVERING,
            machine.dispatch(RecoveryTransactionEvent.UserInteractionStarted))
        assertEquals(RecoveryTransactionState.RECOVERING,
            machine.dispatch(RecoveryTransactionEvent.UserInteractionEnded))
        assertEquals(0, machine.stableVerificationSamples)
    }

    @Test
    fun only_the_explicit_user_stop_event_enters_the_terminal_state() {
        val machine = UnifiedRecoveryTransactionStateMachine()

        machine.dispatch(RecoveryTransactionEvent.UnknownPage())
        machine.dispatch(RecoveryTransactionEvent.RetryScheduled(500L))
        assertEquals(RecoveryTransactionState.RECOVERING, machine.state)

        assertEquals(
            RecoveryTransactionState.STOPPED_BY_USER,
            machine.dispatch(RecoveryTransactionEvent.StoppedByUser("USER_STOP"))
        )
        assertEquals(
            RecoveryTransactionState.STOPPED_BY_USER,
            machine.dispatch(RecoveryTransactionEvent.UnknownPage())
        )
    }
}
