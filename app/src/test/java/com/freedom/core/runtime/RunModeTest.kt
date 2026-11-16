package com.freedom.core.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RunModeTest {
    @Test
    fun selectedBrowseModeResolvesToAccountBrowse() {
        assertEquals(RunMode.ACCOUNT_BROWSE, RunMode.fromSelection(true, false))
    }

    @Test
    fun selectedAiModeResolvesToAiReply() {
        assertEquals(RunMode.AI_REPLY, RunMode.fromSelection(false, true))
    }

    @Test
    fun noModeOrConflictingModesAreRejected() {
        assertNull(RunMode.fromSelection(false, false))
        assertNull(RunMode.fromSelection(true, true))
    }

    @Test
    fun persistedValuesAreStableAndValidated() {
        assertEquals(RunMode.AI_REPLY, RunMode.fromStorageValue("ai_reply"))
        assertEquals(RunMode.ACCOUNT_BROWSE, RunMode.fromStorageValue("account_browse"))
        assertNull(RunMode.fromStorageValue("unknown"))
        assertNull(RunMode.fromStorageValue(null))
    }
}
