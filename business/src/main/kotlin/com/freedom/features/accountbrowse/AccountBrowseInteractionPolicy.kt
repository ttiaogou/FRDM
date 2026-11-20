package com.freedom.features.accountbrowse

import com.freedom.features.recovery.RecoveryTransactionState

/** Centralizes the existing AccountBrowse automatic-interaction gate. */
internal class AccountBrowseInteractionPolicy {
    fun mayPerformAutomaticStep(
        sessionEnabled: Boolean,
        touchActive: Boolean,
        phase: AccountBrowsePhase,
        recoveryState: RecoveryTransactionState
    ): Boolean {
        if (!sessionEnabled || touchActive || phase == AccountBrowsePhase.PAUSED) return false
        return recoveryState == RecoveryTransactionState.ACTIVE ||
            phase == AccountBrowsePhase.WAIT_FOR_FEED_STABLE
    }
}
