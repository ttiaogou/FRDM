package com.freedom.features.accountbrowse

import com.freedom.features.recovery.RecoveryTransactionState

/** Centralizes the existing AccountBrowse automatic-interaction gate. */
class AccountBrowseInteractionPolicy {
    fun mayPerformAutomaticStep(
        sessionEnabled: Boolean,
        touchActive: Boolean,
        phase: AccountBrowsePhase,
        recoveryState: RecoveryTransactionState
    ): Boolean {
        if (!sessionEnabled || touchActive || phase == AccountBrowsePhase.PAUSED) return false
        // Verification and recovery are deliberately read-only.  A phase name must never
        // bypass the transaction gate: every click, scroll and navigation waits for ACTIVE.
        return recoveryState == RecoveryTransactionState.ACTIVE
    }
}
