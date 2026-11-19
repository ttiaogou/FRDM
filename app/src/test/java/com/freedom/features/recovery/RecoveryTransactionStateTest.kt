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
}
