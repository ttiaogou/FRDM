package com.freedom.application.runtime

import com.freedom.core.runtime.RunStateRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RunSessionCommandUseCaseTest {
    private class FakeState : RunStateRepository {
        var requested = false
        val writes = mutableListOf<String>()

        override fun isRunRequested() = requested
        override fun setRunRequested(requested: Boolean) {
            this.requested = requested
            writes += "running:$requested"
        }
    }

    @Test
    fun requestStartPersistsOnlyUserRunIntent() {
        val state = FakeState()
        val commands = RunSessionCommandUseCase(state)
        assertTrue(commands.requestStart())
        assertEquals(listOf("running:true"), state.writes)
        assertTrue(state.requested)
    }

    @Test
    fun requestStartAuthorizesPageObservationWithoutBusinessSelection() {
        val state = FakeState()
        val commands = RunSessionCommandUseCase(state)

        assertTrue(commands.requestStart())
        assertTrue(state.requested)
        assertEquals(listOf("running:true"), state.writes)
    }

    @Test
    fun requestStartDoesNotReplaceAnExistingSession() {
        val state = FakeState().apply {
            requested = true
        }
        val commands = RunSessionCommandUseCase(state)
        assertFalse(commands.requestStart())
        assertTrue(state.requested)
        assertTrue(state.writes.isEmpty())
    }

    @Test
    fun requestStopClearsOnlyPersistedRunIntent() {
        val state = FakeState().apply {
            requested = true
        }
        RunSessionCommandUseCase(state).requestStop()
        assertFalse(state.requested)
        assertEquals(listOf("running:false"), state.writes)
    }
}
