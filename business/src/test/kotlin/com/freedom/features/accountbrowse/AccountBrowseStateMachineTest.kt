package com.freedom.features.accountbrowse

import org.junit.Assert.assertEquals
import org.junit.Test

class AccountBrowseStateMachineTest {
    @Test
    fun ownsPhaseTransitionsAndReset() {
        val machine = AccountBrowseStateMachine()

        assertEquals(AccountBrowsePhase.IDLE, machine.phase)
        machine.transitionTo(AccountBrowsePhase.WAIT_FOR_DYNAMIC_TAB)
        assertEquals(AccountBrowsePhase.WAIT_FOR_DYNAMIC_TAB, machine.phase)

        machine.reset()
        assertEquals(AccountBrowsePhase.IDLE, machine.phase)
    }
}
