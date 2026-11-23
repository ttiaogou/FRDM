package com.freedom.core.logging

import org.junit.Assert.assertEquals
import org.junit.Test

class NodeTreeCaptureBudgetPolicyTest {
    @Test
    fun standardSnapshotsCannotConsumeReservedKeyPageCapacity() {
        assertEquals(
            NodeTreeCaptureAdmission.STANDARD_QUOTA_REACHED,
            NodeTreeCaptureBudgetPolicy.evaluate(
                priority = NodeTreeCapturePriority.STANDARD,
                totalCaptured = RunLogBudgetPolicy.MAX_SAMPLE_STANDARD_NODE_TREE_SAMPLES,
                standardCaptured = RunLogBudgetPolicy.MAX_SAMPLE_STANDARD_NODE_TREE_SAMPLES
            )
        )
    }

    @Test
    fun keyPagesRemainAdmissibleAfterStandardQuotaIsReached() {
        assertEquals(
            NodeTreeCaptureAdmission.ALLOWED,
            NodeTreeCaptureBudgetPolicy.evaluate(
                priority = NodeTreeCapturePriority.KEY_PAGE,
                totalCaptured = RunLogBudgetPolicy.MAX_SAMPLE_STANDARD_NODE_TREE_SAMPLES,
                standardCaptured = RunLogBudgetPolicy.MAX_SAMPLE_STANDARD_NODE_TREE_SAMPLES
            )
        )
    }

    @Test
    fun keyPagesCanUseRemainingCapacityUntilGlobalLimit() {
        assertEquals(
            NodeTreeCaptureAdmission.ALLOWED,
            NodeTreeCaptureBudgetPolicy.evaluate(
                priority = NodeTreeCapturePriority.KEY_PAGE,
                totalCaptured = RunLogBudgetPolicy.MAX_SAMPLE_NODE_TREE_SAMPLES - 1,
                standardCaptured = RunLogBudgetPolicy.MAX_SAMPLE_STANDARD_NODE_TREE_SAMPLES
            )
        )
        assertEquals(
            NodeTreeCaptureAdmission.TOTAL_LIMIT_REACHED,
            NodeTreeCaptureBudgetPolicy.evaluate(
                priority = NodeTreeCapturePriority.KEY_PAGE,
                totalCaptured = RunLogBudgetPolicy.MAX_SAMPLE_NODE_TREE_SAMPLES,
                standardCaptured = RunLogBudgetPolicy.MAX_SAMPLE_STANDARD_NODE_TREE_SAMPLES
            )
        )
    }

    @Test
    fun globalLimitAlsoRejectsStandardSnapshots() {
        assertEquals(
            NodeTreeCaptureAdmission.TOTAL_LIMIT_REACHED,
            NodeTreeCaptureBudgetPolicy.evaluate(
                priority = NodeTreeCapturePriority.STANDARD,
                totalCaptured = RunLogBudgetPolicy.MAX_SAMPLE_NODE_TREE_SAMPLES,
                standardCaptured = 0
            )
        )
    }
}
