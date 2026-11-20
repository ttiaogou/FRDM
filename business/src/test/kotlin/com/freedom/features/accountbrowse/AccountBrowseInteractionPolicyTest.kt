package com.freedom.features.accountbrowse

import com.freedom.features.recovery.RecoveryTransactionState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountBrowseInteractionPolicyTest {
    private val policy = AccountBrowseInteractionPolicy()

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
        assertTrue(
            policy.mayPerformAutomaticStep(
                sessionEnabled = true,
                touchActive = false,
                phase = AccountBrowsePhase.WAIT_FOR_FEED_STABLE,
                recoveryState = RecoveryTransactionState.VERIFYING
            )
        )
    }
}
