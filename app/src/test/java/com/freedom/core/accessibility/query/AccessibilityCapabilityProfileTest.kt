package com.freedom.core.accessibility.query

import com.freedom.core.accessibility.api.AccessibilityFieldCaptureState
import com.freedom.core.accessibility.api.AccessibilityNodeCaptureEvidence
import com.freedom.core.accessibility.api.AccessibilityNodeField
import com.freedom.core.accessibility.api.AccessibilityNodeSnapshot
import com.freedom.core.accessibility.api.AccessibilityTreeSnapshot
import com.freedom.core.accessibility.api.NodePath
import com.freedom.core.accessibility.api.NodeTextCapture
import com.freedom.core.accessibility.api.ScreenRect
import com.freedom.core.accessibility.api.WindowRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessibilityCapabilityProfileTest {
    private val window = WindowRef("com.example", "MainActivity", 4)

    @Test
    fun distinguishesNotRequestedTextFromAnEmptyCapturedValue() {
        val node = node(emptyList(), "TextView", text = null)
        val structureOnly = snapshot(listOf(node), textCapture = NodeTextCapture.NONE)
        val ephemeral = snapshot(listOf(node), textCapture = NodeTextCapture.EPHEMERAL)

        assertEquals(
            AccessibilityFieldCaptureState.NOT_REQUESTED,
            node.captureState(AccessibilityNodeField.TEXT, structureOnly.textCapture)
        )
        assertEquals(
            AccessibilityFieldCaptureState.EMPTY,
            node.captureState(AccessibilityNodeField.TEXT, ephemeral.textCapture)
        )
        assertEquals(
            AccessibilityFieldCoverageState.NOT_CAPTURED,
            AccessibilityCapabilityProfile.from(structureOnly).coverage(AccessibilityNodeField.TEXT)?.state
        )
        assertEquals(
            AccessibilityFieldCoverageState.AVAILABLE,
            AccessibilityCapabilityProfile.from(ephemeral).coverage(AccessibilityNodeField.TEXT)?.state
        )
    }

    @Test
    fun reportsGetterFailuresSeparatelyFromObservedFalseValues() {
        val readableFalse = node(listOf(0), "Button", clickable = false)
        val failedFalse = node(
            listOf(1), "Button", clickable = false,
            evidence = AccessibilityNodeCaptureEvidence(
                failedFields = setOf(AccessibilityNodeField.CLICKABLE)
            )
        )
        val profile = AccessibilityCapabilityProfile.from(snapshot(listOf(readableFalse, failedFalse)))
        val coverage = profile.coverage(AccessibilityNodeField.CLICKABLE)!!

        assertEquals(1, coverage.valuePresentCount)
        assertEquals(1, coverage.readFailedCount)
        assertEquals(AccessibilityFieldCoverageState.PARTIAL, coverage.state)
        assertEquals(
            AccessibilityFieldCaptureState.VALUE_PRESENT,
            readableFalse.captureState(AccessibilityNodeField.CLICKABLE, NodeTextCapture.NONE)
        )
        assertEquals(
            AccessibilityFieldCaptureState.READ_FAILED,
            failedFalse.captureState(AccessibilityNodeField.CLICKABLE, NodeTextCapture.NONE)
        )
    }

    @Test
    fun includesUnsupportedAndIntentionallyUnvisitedFieldsInCoverage() {
        val node = node(
            emptyList(), "Root",
            evidence = AccessibilityNodeCaptureEvidence(
                unsupportedFields = setOf(AccessibilityNodeField.HEADING),
                notCapturedFields = setOf(AccessibilityNodeField.CHILD_NODES)
            )
        )
        val profile = AccessibilityCapabilityProfile.from(snapshot(listOf(node)))

        assertEquals(
            AccessibilityFieldCoverageState.UNSUPPORTED,
            profile.coverage(AccessibilityNodeField.HEADING)?.state
        )
        assertEquals(
            AccessibilityFieldCoverageState.NOT_CAPTURED,
            profile.coverage(AccessibilityNodeField.CHILD_NODES)?.state
        )
        assertTrue(profile.snapshotComplete)
        assertFalse(profile.textCapture == NodeTextCapture.EPHEMERAL)
    }

    @Test
    fun legacySnapshotsDoNotClaimNewPlatformAttributesWereCaptured() {
        val legacyNode = node(emptyList(), "Button")
        val profile = AccessibilityCapabilityProfile.from(snapshot(listOf(legacyNode)))

        assertEquals(
            AccessibilityFieldCaptureState.NOT_REQUESTED,
            legacyNode.captureState(AccessibilityNodeField.DISMISSABLE, NodeTextCapture.NONE)
        )
        assertEquals(
            AccessibilityFieldCoverageState.NOT_CAPTURED,
            profile.coverage(AccessibilityNodeField.DISMISSABLE)?.state
        )
    }

    @Test
    fun actionLabelReadFailureIsNotReportedAsAnEmptyLabelList() {
        val node = node(
            emptyList(), "Button",
            evidence = AccessibilityNodeCaptureEvidence(
                failedFields = setOf(AccessibilityNodeField.ACTIONS)
            )
        )

        assertEquals(
            AccessibilityFieldCaptureState.READ_FAILED,
            node.captureState(AccessibilityNodeField.ACTION_LABELS, NodeTextCapture.EPHEMERAL)
        )
    }

    private fun snapshot(
        nodes: List<AccessibilityNodeSnapshot>,
        textCapture: NodeTextCapture = NodeTextCapture.NONE
    ) = AccessibilityTreeSnapshot(
        window = window,
        nodes = nodes,
        visitedNodeCount = nodes.size,
        truncated = false,
        textCapture = textCapture
    )

    private fun node(
        path: List<Int>,
        className: String,
        text: String? = null,
        clickable: Boolean = false,
        evidence: AccessibilityNodeCaptureEvidence? = null
    ) = AccessibilityNodeSnapshot(
        path = NodePath(path),
        className = className,
        viewId = "",
        text = text,
        contentDescription = null,
        bounds = ScreenRect(0, 0, 40, 40),
        visible = true,
        enabled = true,
        checkable = false,
        checked = false,
        clickable = clickable,
        longClickable = false,
        scrollable = false,
        editable = false,
        selected = false,
        focused = false,
        childCount = 0
    ).let { snapshot ->
        if (evidence == null) snapshot else snapshot.copy(captureEvidence = evidence)
    }
}
