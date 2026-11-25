package com.freedom.core.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RunModeTest {
    @Test
    fun browseRuntimeCategoryIsStable() {
        assertEquals("account_browse", RunMode.ACCOUNT_BROWSE.storageValue)
        assertEquals(RunMode.ACCOUNT_BROWSE, RunMode.fromStorageValue("account_browse"))
    }

    @Test
    fun aiRuntimeCategoryIsStable() {
        assertEquals("ai_reply", RunMode.AI_REPLY.storageValue)
        assertEquals(RunMode.AI_REPLY, RunMode.fromStorageValue("ai_reply"))
    }

    @Test
    fun runtimeCategoryCannotBeInferredFromMissingOrAmbiguousLabels() {
        assertNull(RunMode.fromStorageValue(null))
        assertNull(RunMode.fromStorageValue("ai_reply,account_browse"))
        assertNull(RunMode.fromStorageValue(""))
    }

    @Test
    fun runtimeLabelsAreStableAndValidated() {
        assertEquals(RunMode.AI_REPLY, RunMode.fromStorageValue("ai_reply"))
        assertEquals(RunMode.ACCOUNT_BROWSE, RunMode.fromStorageValue("account_browse"))
        assertNull(RunMode.fromStorageValue("unknown"))
        assertNull(RunMode.fromStorageValue(null))
    }
}
