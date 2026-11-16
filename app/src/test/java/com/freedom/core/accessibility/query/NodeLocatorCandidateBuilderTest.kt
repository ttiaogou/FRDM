package com.freedom.core.accessibility.query

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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NodeLocatorCandidateBuilderTest {
    private val window = WindowRef("com.example", "MainActivity", 8)

    @Test
    fun buildsAndVerifiesResourceIdCandidatesAgainstTheWholeSnapshot() {
        val snapshot = snapshot(
            node(emptyList(), "Root", childCount = 1),
            node(listOf(0), "ImageView", viewId = "com.example:id/avatar")
        )

        val analysis = NodeLocatorCandidateBuilder.build(
            NodeTreeIndex(snapshot), NodePath(listOf(0))
        )

        assertEquals(2, analysis.candidates.size)
        assertEquals(
            listOf(NodeLocatorCandidateState.UNIQUE_IN_SNAPSHOT, NodeLocatorCandidateState.UNIQUE_IN_SNAPSHOT),
            analysis.candidates.map { it.state }
        )
        assertEquals(NodeLocatorCandidateKind.VIEW_ID_AND_CLASS, analysis.candidates.first().kind)
        assertTrue(analysis.hasUniqueCandidate)
    }

    @Test
    fun reportsDuplicateIdsAsAmbiguousInsteadOfChoosingTheFirstNode() {
        val snapshot = snapshot(
            node(emptyList(), "Root", childCount = 2),
            node(listOf(0), "ImageView", viewId = "com.example:id/avatar"),
            node(listOf(1), "ImageView", viewId = "com.example:id/avatar")
        )

        val analysis = NodeLocatorCandidateBuilder.build(
            NodeTreeIndex(snapshot), NodePath(listOf(0))
        )

        assertTrue(analysis.candidates.isNotEmpty())
        assertTrue(analysis.candidates.all { it.state == NodeLocatorCandidateState.AMBIGUOUS })
        assertFalse(analysis.hasUniqueCandidate)
    }

    @Test
    fun doesNotGenerateTextCandidatesWhenTextWasNotRequested() {
        val snapshot = snapshot(
            node(emptyList(), "Root", childCount = 1),
            node(listOf(0), "TextView", text = "Profile", viewId = "")
        ).copy(textCapture = NodeTextCapture.NONE)

        val analysis = NodeLocatorCandidateBuilder.build(
            NodeTreeIndex(snapshot), NodePath(listOf(0))
        )

        assertTrue(analysis.candidates.isEmpty())
        assertEquals("NO_CAPTURED_IDENTITY_OR_INTERACTION_EVIDENCE", analysis.reason)
    }

    @Test
    fun usesEphemeralTextOnlyWhenTheSnapshotActuallyContainsIt() {
        val snapshot = snapshot(
            node(emptyList(), "Root", childCount = 1),
            node(listOf(0), "TextView", text = "Profile", viewId = "")
        ).copy(textCapture = NodeTextCapture.EPHEMERAL)

        val candidate = NodeLocatorCandidateBuilder.build(
            NodeTreeIndex(snapshot), NodePath(listOf(0))
        ).candidates.single()

        assertEquals(NodeLocatorCandidateKind.TEXT_AND_CLASS, candidate.kind)
        assertEquals(NodeLocatorCandidateState.UNIQUE_IN_SNAPSHOT, candidate.state)
    }

    @Test
    fun incompleteSnapshotsCannotClaimAUniqueLocator() {
        val snapshot = snapshot(
            node(emptyList(), "Root", childCount = 1),
            node(listOf(0), "ImageView", viewId = "com.example:id/avatar")
        ).copy(truncated = true, walkTruncated = true, stopReason = "NODE_BUDGET")

        val analysis = NodeLocatorCandidateBuilder.build(
            NodeTreeIndex(snapshot), NodePath(listOf(0))
        )

        assertTrue(analysis.candidates.isNotEmpty())
        assertTrue(analysis.candidates.all { it.state == NodeLocatorCandidateState.INDETERMINATE })
        assertFalse(analysis.hasUniqueCandidate)
    }

    @Test
    fun omitsFailedInteractionPropertiesFromStructuralCandidates() {
        val selectedNode = node(
            listOf(0), "CustomActionView", viewId = "", longClickable = true,
            evidence = AccessibilityNodeCaptureEvidence(
                failedFields = setOf(AccessibilityNodeField.CLICKABLE)
            )
        )
        val snapshot = snapshot(node(emptyList(), "Root", childCount = 1), selectedNode)

        val candidate = NodeLocatorCandidateBuilder.build(
            NodeTreeIndex(snapshot), NodePath(listOf(0))
        ).candidates.single()

        assertEquals(NodeLocatorCandidateKind.STRUCTURAL_ATTRIBUTES, candidate.kind)
        assertNull(candidate.selector.clickable)
        assertEquals(true, candidate.selector.longClickable)
    }


    @Test
    fun selectorDoesNotTreatAGetterFallbackAsAnObservedFalseValue() {
        val unreadable = node(
            emptyList(), "Button", clickable = false,
            evidence = AccessibilityNodeCaptureEvidence(
                failedFields = setOf(AccessibilityNodeField.CLICKABLE)
            )
        )

        assertFalse(NodeSelector(clickable = false).matches(unreadable))
        assertEquals(
            0,
            NodeTreeIndex(snapshot(unreadable)).findAll(NodeSelector(clickable = false)).matchCount
        )
    }

    @Test
    fun reportsMissingSelectedNodeWithoutAttemptingAQuery() {
        val snapshot = snapshot(node(emptyList(), "Root"))
        val analysis = NodeLocatorCandidateBuilder.build(
            NodeTreeIndex(snapshot), NodePath(listOf(9))
        )

        assertTrue(analysis.candidates.isEmpty())
        assertEquals("SELECTED_NODE_NOT_IN_SNAPSHOT", analysis.reason)
    }

    @Test
    fun expandedPlatformAttributesRequireCaptureEvidence() {
        val legacyNode = node(emptyList(), "Button")
        assertFalse(NodeSelector(dismissable = false).matches(legacyNode))

        val observedNode = legacyNode.copy(
            dismissable = false,
            captureEvidence = AccessibilityNodeCaptureEvidence()
        )
        val selector = NodeSelector(dismissable = false)
        assertTrue(selector.matches(observedNode))
        assertTrue(selector.criteria().all { it.matches(observedNode) })
    }

    @Test
    fun selectorsIgnoreTextPropertiesThatWereNotCaptured() {
        val nodeWithUncapturedText = node(
            emptyList(), "TextView", text = "Hidden value",
            evidence = AccessibilityNodeCaptureEvidence(
                notCapturedFields = setOf(
                    AccessibilityNodeField.TEXT,
                    AccessibilityNodeField.CONTENT_DESCRIPTION
                )
            )
        )
        val selector = NodeSelector(textEquals = "Hidden value")

        assertFalse(selector.matches(nodeWithUncapturedText))
        assertFalse(selector.criteria().all { it.matches(nodeWithUncapturedText) })
    }

    @Test
    fun locatorCandidatesShareTheConfiguredScanBudget() {
        val snapshot = snapshot(
            node(emptyList(), "Root", childCount = 1),
            node(listOf(0), "ImageView", viewId = "com.example:id/avatar")
        )

        val analysis = NodeLocatorCandidateBuilder.build(
            NodeTreeIndex(snapshot),
            NodePath(listOf(0)),
            NodeQueryBudget(maxVisitedNodes = 1, timeoutMs = Long.MAX_VALUE)
        )

        assertTrue(analysis.candidates.isNotEmpty())
        assertTrue(analysis.candidates.all { it.state == NodeLocatorCandidateState.INDETERMINATE })
        assertTrue(analysis.candidates.all { it.visitedNodeCount == 1 })
        assertTrue(analysis.candidates.all { it.stopReason == "QUERY_NODE_BUDGET" })
    }

    private fun snapshot(vararg nodes: AccessibilityNodeSnapshot) = AccessibilityTreeSnapshot(
        window = window,
        nodes = nodes.toList(),
        visitedNodeCount = nodes.size,
        truncated = false,
        textCapture = NodeTextCapture.EPHEMERAL
    )

    private fun node(
        path: List<Int>,
        className: String,
        viewId: String = "",
        text: String? = null,
        childCount: Int = 0,
        clickable: Boolean = false,
        longClickable: Boolean = false,
        enabled: Boolean = true,
        evidence: AccessibilityNodeCaptureEvidence? = null
    ) = AccessibilityNodeSnapshot(
        path = NodePath(path),
        className = className,
        viewId = viewId,
        text = text,
        contentDescription = null,
        bounds = ScreenRect(0, 0, 30, 30),
        visible = true,
        enabled = enabled,
        checkable = false,
        checked = false,
        clickable = clickable,
        longClickable = longClickable,
        scrollable = false,
        editable = false,
        selected = false,
        focused = false,
        childCount = childCount
    ).let { snapshot ->
        if (evidence == null) snapshot else snapshot.copy(captureEvidence = evidence)
    }
}
