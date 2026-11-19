package com.freedom.features.recovery

import org.junit.Assert.assertEquals
import org.junit.Test

class RecoveryTransactionStateTest {
    @Test
    fun sharedStatesKeepTheRecoveryLifecycleBusinessNeutral() {
        assertEquals(
            listOf(
                "ACTIVE",
                "RECOVERY_REQUESTED",
                "PAUSED",
                "VERIFYING",
                "VERIFIED",
                "RESUMING",
                "CANCELLED"
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
            RecoveryTransactionEvent.Cancelled("user_stop")
        )

        assertEquals(10, events.size)
        assertEquals(RecoveryTransactionEvent.Source.PROCESS_RESTART,
            (events[1] as RecoveryTransactionEvent.CheckpointLoaded).source)
    }

    @Test
    fun unknown_requires_two_stable_samples_before_resume() {
        val machine = UnifiedRecoveryTransactionStateMachine()

        assertEquals(RecoveryTransactionState.PAUSED,
            machine.dispatch(RecoveryTransactionEvent.UnknownPage()))
        assertEquals(RecoveryTransactionState.VERIFYING,
            machine.dispatch(RecoveryTransactionEvent.VerificationStarted(1)))
        assertEquals(RecoveryTransactionState.VERIFYING,
            machine.dispatch(RecoveryTransactionEvent.VerificationSample(stable = true)))
        assertEquals(RecoveryTransactionState.VERIFIED,
            machine.dispatch(RecoveryTransactionEvent.VerificationSample(stable = true)))
        assertEquals(RecoveryTransactionState.RESUMING,
            machine.dispatch(RecoveryTransactionEvent.ResumeRequested))
        assertEquals(RecoveryTransactionState.ACTIVE,
            machine.dispatch(RecoveryTransactionEvent.ResumeCompleted))
    }

    @Test
    fun user_interaction_keeps_transaction_paused_until_new_verification() {
        val machine = UnifiedRecoveryTransactionStateMachine()
        machine.dispatch(RecoveryTransactionEvent.UnknownPage())
        assertEquals(RecoveryTransactionState.PAUSED,
            machine.dispatch(RecoveryTransactionEvent.UserInteractionStarted))
        assertEquals(RecoveryTransactionState.VERIFYING,
            machine.dispatch(RecoveryTransactionEvent.UserInteractionEnded))
        assertEquals(0, machine.stableVerificationSamples)
    }
}
