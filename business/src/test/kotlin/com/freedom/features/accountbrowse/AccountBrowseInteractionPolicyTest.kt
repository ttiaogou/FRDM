package com.freedom.features.accountbrowse

import com.freedom.features.recovery.RecoveryTransactionState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountBrowseInteractionPolicyTest {
    private val policy = AccountBrowseInteractionPolicy()

    @Test
    fun reservedTaskSlotNeverAuthorizesAutomaticStepsDuringRecovery() {
        for (recoveryState in RecoveryTransactionState.values().filter {
            it != RecoveryTransactionState.ACTIVE
        }) {
            assertFalse(
                "Recovery state $recoveryState cannot authorize browsing actions",
                policy.mayPerformAutomaticStep(
                    sessionEnabled = true,
                    touchActive = false,
                    phase = AccountBrowsePhase.WAIT_FOR_LIST_SCROLL,
                    recoveryState = recoveryState
                )
            )
        }
    }

    @Test
    fun preservesExistingAutomaticInteractionGate() {
        assertTrue(
            policy.mayPerformAutomaticStep(
                sessionEnabled = true,
                touchActive = false,
                phase = AccountBrowsePhase.WAIT_FOR_LIST_SCROLL,
                recoveryState = RecoveryTransactionState.ACTIVE
            )
        )
        assertFalse(
            policy.mayPerformAutomaticStep(
                sessionEnabled = true,
                touchActive = true,
                phase = AccountBrowsePhase.WAIT_FOR_LIST_SCROLL,
                recoveryState = RecoveryTransactionState.ACTIVE
            )
        )
        assertFalse(
            policy.mayPerformAutomaticStep(
                sessionEnabled = true,
                touchActive = false,
                phase = AccountBrowsePhase.WAIT_FOR_FEED_STABLE,
                recoveryState = RecoveryTransactionState.VERIFYING
            )
        )
    }
}
