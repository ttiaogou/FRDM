package com.freedom.features.accountbrowse

/**
 * Owns the current AccountBrowse phase.
 *
 * The state machine deliberately contains no Android, scheduler, page-inspection or gesture
 * dependency. Runtime coordinates persistence and actions; this class owns only phase state.
 */
internal class AccountBrowseStateMachine(
    initialPhase: AccountBrowsePhase = AccountBrowsePhase.IDLE
) {
    var phase: AccountBrowsePhase = initialPhase
        private set

    fun transitionTo(nextPhase: AccountBrowsePhase) {
        phase = nextPhase
    }

    fun reset() {
        phase = AccountBrowsePhase.IDLE
    }
}
