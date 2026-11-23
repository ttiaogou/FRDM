package com.freedom.core.accessibility.query

import com.freedom.core.accessibility.api.TreeInspectionRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NodeQueryBudgetTest {
    @Test
    fun defaultQueryBudgetIsBoundedByNodeCountAndTime() {
        assertEquals(5_000, NodeQueryBudget.DEFAULT.maxVisitedNodes)
        assertEquals(250L, NodeQueryBudget.DEFAULT.timeoutMs)
        assertNotEquals(Long.MAX_VALUE, NodeQueryBudget.DEFAULT.timeoutMs)
    }

    @Test
    fun unboundedBudgetIsAnExplicitOptInOnly() {
        assertEquals(Int.MAX_VALUE, NodeQueryBudget.UNBOUNDED.maxVisitedNodes)
        assertEquals(Long.MAX_VALUE, NodeQueryBudget.UNBOUNDED.timeoutMs)
    }

    @Test
    fun treeInspectionRequestDefaultsToFiniteDepthAndDuration() {
        val request = TreeInspectionRequest(maxNodes = 5_000)
        assertEquals(48, request.maxDepth)
        assertEquals(750L, request.maxDurationMs)
    }

    @Test
    fun treeInspectionRequestRejectsUnboundedOrInvalidDurations() {
        assertTrue(rejects { TreeInspectionRequest(maxNodes = 100, maxDurationMs = 0L) })
        assertTrue(rejects { TreeInspectionRequest(maxNodes = 100, maxDurationMs = 5_001L) })
        assertTrue(rejects { TreeInspectionRequest(maxNodes = 100, maxDepth = 129) })
    }

    private fun rejects(block: () -> Unit): Boolean = try {
        block()
        false
    } catch (_: IllegalArgumentException) {
        true
    }
}
