package com.freedom.application.runtime

import com.freedom.core.runtime.RunMode
import com.freedom.core.runtime.RunStateRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RunSessionCommandUseCaseTest {
    private class FakeState : RunStateRepository {
        var requested = false
        var mode: RunMode? = null
        val writes = mutableListOf<String>()

        override fun isRunRequested() = requested
        override fun activeMode() = mode
        override fun setActiveMode(mode: RunMode?) {
            this.mode = mode
            writes += "mode:${mode?.storageValue ?: "null"}"
        }
        override fun setRunRequested(requested: Boolean) {
            this.requested = requested
            writes += "running:$requested"
            if (!requested) mode = null
        }
    }

    @Test
    fun requestStartPersistsModeBeforeRunIntent() {
        val state = FakeState()
        val commands = RunSessionCommandUseCase(state)
        assertTrue(commands.requestStart(RunMode.AI_REPLY))
        assertEquals(listOf("mode:ai_reply", "running:true"), state.writes)
        assertEquals(RunMode.AI_REPLY, state.mode)
    }

    @Test
    fun requestStartWithoutModeCreatesManualObservation() {
        val state = FakeState()
        val commands = RunSessionCommandUseCase(state)

        assertTrue(commands.requestStart())
        assertTrue(state.requested)
        assertEquals(null, state.mode)
        assertEquals(listOf("mode:null", "running:true"), state.writes)
    }

    @Test
    fun requestStartDoesNotReplaceAnExistingSession() {
        val state = FakeState().apply {
            requested = true
            mode = RunMode.ACCOUNT_BROWSE
        }
        val commands = RunSessionCommandUseCase(state)
        assertFalse(commands.requestStart(RunMode.AI_REPLY))
        assertEquals(RunMode.ACCOUNT_BROWSE, state.mode)
        assertTrue(state.writes.isEmpty())
    }

    @Test
    fun requestStopClearsPersistedRunIntentAndLatchedMode() {
        val state = FakeState().apply {
            requested = true
            mode = RunMode.ACCOUNT_BROWSE
        }
        RunSessionCommandUseCase(state).requestStop()
        assertFalse(state.requested)
        assertEquals(null, state.mode)
        assertEquals(listOf("running:false"), state.writes)
    }
}
