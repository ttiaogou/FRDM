package com.freedom.core.accessibility.query

import com.freedom.core.accessibility.api.AccessibilityNodeSnapshot
import com.freedom.core.accessibility.api.AccessibilityNodeCaptureEvidence
import com.freedom.core.accessibility.api.AccessibilityNodeField
import com.freedom.core.accessibility.api.NodeTextCapture
import com.freedom.core.accessibility.api.AccessibilityTreeSnapshot
import com.freedom.core.accessibility.api.NodePath
import com.freedom.core.accessibility.api.ScreenRect
import com.freedom.core.accessibility.api.WindowRef
import org.junit.Assert.*
import org.junit.Test

class NodeTreeIndexTest {
    private val window = WindowRef("com.example", "MainActivity", 7)

    @Test
    fun indexesRelationshipsAndRespectsRowScope() {
        val index = NodeTreeIndex(tree(
            node(emptyList(), "Root", childCount = 2),
            node(listOf(0), "Row", clickable = true, childCount = 2),
            node(listOf(0, 0), "Text", viewId = "title", text = "Alice"),
            node(listOf(0, 1), "Avatar"),
            node(listOf(1), "Row", clickable = true, childCount = 1),
            node(listOf(1, 0), "Text", viewId = "title", text = "Bob")
        ))
        val alice = NodePath(listOf(0, 0))
        val avatar = NodePath(listOf(0, 1))
        val bob = NodePath(listOf(1, 0))
        assertEquals("Row", index.parentOf(alice)?.className)
        assertEquals(2, index.childrenOf(NodePath(listOf(0))).size)
        assertEquals(NodePath(listOf(0)), index.commonAncestor(alice, avatar)?.path)
        assertEquals(NodePath(emptyList()), index.commonAncestor(alice, bob)?.path)
        assertEquals(NodePath(listOf(0)), index.nearestClickableAncestor(alice)?.path)
        assertTrue(index.sameRow(alice, avatar, NodePath(emptyList())))
        assertFalse(index.sameRow(alice, bob, NodePath(emptyList())))
        assertTrue(index.isWithin(alice, NodePath(listOf(0))))
        assertFalse(index.isWithin(bob, NodePath(listOf(0))))
    }

    @Test
    fun uniqueQueryReportsAmbiguityInsteadOfSilentlyChoosingFirst() {
        val index = NodeTreeIndex(tree(
            node(emptyList(), "Root", childCount = 2),
            node(listOf(0), "TextView", viewId = "same", text = "A"),
            node(listOf(1), "TextView", viewId = "same", text = "B")
        ))
        val result = index.findUnique(NodeSelector(viewId = "same"))
        assertTrue(result.ambiguous)
        assertEquals(2, result.matchCount)
        assertEquals(2, result.matchedPaths.size)
        assertEquals("MULTIPLE_MATCHES", result.stopReason)
        assertFalse(result.matchCountExact)
    }

    @Test
    fun scopedQueryDoesNotCrossIntoSiblingContainers() {
        val index = NodeTreeIndex(tree(
            node(emptyList(), "Root", childCount = 2),
            node(listOf(0), "Container", childCount = 1),
            node(listOf(0, 0), "Button", viewId = "go"),
            node(listOf(1), "Container", childCount = 1),
            node(listOf(1, 0), "Button", viewId = "go")
        ))
        val result = index.queryWithin(NodePath(listOf(0)), NodeSelector(viewId = "go"))
        assertEquals(listOf(NodePath(listOf(0, 0))), result.matchedPaths)
        assertEquals(2, result.visitedNodeCount)
    }

    @Test
    fun generationIsCarriedByTheIndexAndIncompleteSnapshotIsNotClaimedUnique() {
        val snapshot = tree(node(emptyList(), "Root"), truncated = true)
        val result = NodeTreeIndex(snapshot).findUnique(NodeSelector(className = "Root"))
        assertEquals(7L, NodeTreeIndex(snapshot).windowGeneration)
        assertFalse(result.matchCountExact)
        assertTrue(result.truncated)
    }

    @Test
    fun queryStopsAtNodeBudgetAndReportsIncompleteMatchCount() {
        val index = NodeTreeIndex(tree(
            node(emptyList(), "Root", childCount = 3),
            node(listOf(0), "Button", viewId = "target"),
            node(listOf(1), "Button", viewId = "target"),
            node(listOf(2), "Button", viewId = "other")
        ))

        val result = index.findAll(
            NodeSelector(viewId = "target"),
            budget = NodeQueryBudget(maxVisitedNodes = 2)
        )

        assertEquals(2, result.visitedNodeCount)
        assertEquals(1, result.matchCount)
        assertFalse(result.matchCountExact)
        assertTrue(result.truncated)
        assertEquals("QUERY_NODE_BUDGET", result.stopReason)
    }

    @Test
    fun descendantSequenceIsLazyAndKeepsTraversalOrder() {
        val index = NodeTreeIndex(tree(
            node(emptyList(), "Root", childCount = 2),
            node(listOf(0), "Row", childCount = 1),
            node(listOf(0, 0), "Text"),
            node(listOf(1), "Footer")
        ))
        val sequence = index.descendantSequenceOf(NodePath(listOf(0)))

        assertEquals(listOf(NodePath(listOf(0, 0))), sequence.map { it.path }.toList())
    }

    @Test
    fun identityFingerprintAndTargetIgnoreFieldsThatWereNotCapturedSuccessfully() {
        val targetNode = node(listOf(0), "Button", viewId = "stale-id", text = "stale text").copy(
            captureEvidence = AccessibilityNodeCaptureEvidence(
                failedFields = setOf(AccessibilityNodeField.CLASS_NAME, AccessibilityNodeField.VIEW_ID),
                notCapturedFields = setOf(AccessibilityNodeField.TEXT)
            )
        )
        val snapshot = tree(
            node(emptyList(), "Root", childCount = 1),
            targetNode
        ).copy(textCapture = NodeTextCapture.EPHEMERAL)
        val index = NodeTreeIndex(snapshot)

        val fingerprint = index.identityFingerprint(targetNode.path)!!
        val target = index.targetFor(targetNode.path)!!

        assertNull(fingerprint.className)
        assertNull(fingerprint.viewId)
        assertNull(fingerprint.textDigest)
        assertNull(target.expectedClassName)
        assertNull(target.expectedViewId)
    }

    @Test
    fun diffReportsChangedNodesOnlyForCompleteSameWindowSnapshots() {
        val previous = NodeTreeIndex(tree(
            node(emptyList(), "Root", childCount = 2),
            node(listOf(0), "Row", viewId = "old"),
            node(listOf(1), "Footer")
        ))
        val current = NodeTreeIndex(tree(
            node(emptyList(), "Root", childCount = 2),
            node(listOf(0), "Row", viewId = "old"),
            node(listOf(1), "Button")
        ))

        val delta = current.diffFrom(previous)
        assertTrue(delta.comparable)
        assertEquals(listOf(NodePath(listOf(1))), delta.changedPaths)
        assertEquals(2, delta.unchangedNodeCount)
        assertTrue(delta.addedPaths.isEmpty())
        assertTrue(delta.removedPaths.isEmpty())

        val otherWindow = NodeTreeIndex(current.snapshot.copy(
            window = WindowRef("com.example", "OtherActivity", 8)
        ))
        assertFalse(otherWindow.diffFrom(previous).comparable)
    }

    @Test
    fun diffReportsAddedAndRemovedPaths() {
        val previous = NodeTreeIndex(tree(
            node(emptyList(), "Root", childCount = 2),
            node(listOf(0), "Row"),
            node(listOf(1), "Footer")
        ))
        val current = NodeTreeIndex(tree(
            node(emptyList(), "Root", childCount = 3),
            node(listOf(0), "Row"),
            node(listOf(2), "Footer")
        ))

        val delta = current.diffFrom(previous)
        assertTrue(delta.comparable)
        assertEquals(listOf(NodePath(listOf(2))), delta.addedPaths)
        assertEquals(listOf(NodePath(listOf(1))), delta.removedPaths)
        assertEquals(listOf(NodePath(emptyList())), delta.changedPaths)
    }

    private fun tree(
        vararg nodes: AccessibilityNodeSnapshot,
        truncated: Boolean = false
    ) = AccessibilityTreeSnapshot(
        window = window,
        nodes = nodes.toList(),
        visitedNodeCount = nodes.size,
        truncated = truncated,
        walkTruncated = truncated
    )

    private fun node(
        path: List<Int>,
        className: String,
        viewId: String = "",
        text: String? = null,
        childCount: Int = 0,
        clickable: Boolean = false
    ) = AccessibilityNodeSnapshot(
        path = NodePath(path), className = className, viewId = viewId,
        text = text, contentDescription = null,
        bounds = ScreenRect(0, 0, 10, 10), visible = true, enabled = true,
        checkable = false, checked = false, clickable = clickable, longClickable = false,
        scrollable = false, editable = false, selected = false, focused = false,
        childCount = childCount
    )
}
