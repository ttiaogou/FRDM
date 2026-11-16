package com.freedom.core.accessibility.query

import com.freedom.core.accessibility.api.AccessibilityNodeSnapshot
import com.freedom.core.accessibility.api.AccessibilityNodeCaptureEvidence
import com.freedom.core.accessibility.api.AccessibilityNodeField
import com.freedom.core.accessibility.api.AccessibilityTreeSnapshot
import com.freedom.core.accessibility.api.NodePath
import com.freedom.core.accessibility.api.NodeTargetRelocationPolicy
import com.freedom.core.accessibility.api.NodeTextCapture
import com.freedom.core.accessibility.api.ScreenRect
import com.freedom.core.accessibility.api.WindowRef
import org.junit.Assert.*
import org.junit.Test

class NodeTargetResolverTest {
    private val window = WindowRef("com.example", "MainActivity", 12)

    @Test
    fun keepsTheOriginalPathWhenItsFingerprintStillMatches() {
        val original = tree(
            node(emptyList(), "Root", "root", childCount = 1),
            node(listOf(0), "Button", "dismiss", text = "Close", childCount = 0)
        )
        val target = NodeTreeIndex(original).targetFor(NodePath(listOf(0)))!!
        val result = NodeTargetResolver.resolve(target, original)
        assertEquals(NodeTargetResolutionStatus.EXACT_PATH, result.status)
        assertEquals(NodePath(listOf(0)), result.resolvedPath)
    }

    @Test
    fun relocatesOnlyWhenOneFingerprintMatchExistsInTheSameWindow() {
        val original = tree(
            node(emptyList(), "Root", "root", childCount = 1),
            node(listOf(0), "Button", "dismiss", text = "Close", childCount = 0)
        )
        val target = NodeTreeIndex(original).targetFor(NodePath(listOf(0)))!!
        val refreshed = tree(
            node(emptyList(), "Root", "root", childCount = 2),
            node(listOf(0), "Button", "other", text = "Open", childCount = 0),
            node(listOf(1), "Button", "dismiss", text = "Close", childCount = 0)
        )

        val result = NodeTargetResolver.resolve(target, refreshed)
        assertEquals(NodeTargetResolutionStatus.RELOCATED, result.status)
        assertEquals(NodePath(listOf(1)), result.resolvedPath)
    }

    @Test
    fun refusesAmbiguousDuplicateTargets() {
        val original = tree(
            node(emptyList(), "Root", "root", childCount = 1),
            node(listOf(0), "Button", "dismiss", text = "Close", childCount = 0)
        )
        val target = NodeTreeIndex(original).targetFor(NodePath(listOf(0)))!!
        val refreshed = tree(
            node(emptyList(), "Root", "root", childCount = 2),
            node(listOf(0), "Button", "dismiss", text = "Close", childCount = 0),
            node(listOf(1), "Button", "dismiss", text = "Close", childCount = 0)
        )

        val result = NodeTargetResolver.resolve(target, refreshed)
        assertEquals(NodeTargetResolutionStatus.AMBIGUOUS, result.status)
        assertNull(result.resolvedPath)
        assertEquals(2, result.candidatePaths.size)
    }

    @Test
    fun refusesRelocationWhenTheFreshSnapshotIsTruncated() {
        val original = tree(
            node(emptyList(), "Root", "root", childCount = 1),
            node(listOf(0), "Button", "dismiss", text = "Close", childCount = 0)
        )
        val target = NodeTreeIndex(original).targetFor(NodePath(listOf(0)))!!
        val refreshed = tree(
            node(emptyList(), "Root", "root", childCount = 2),
            node(listOf(0), "Button", "other", text = "Open", childCount = 0),
            node(listOf(1), "Button", "dismiss", text = "Close", childCount = 0),
            truncated = true
        )

        assertEquals(
            NodeTargetResolutionStatus.SNAPSHOT_TRUNCATED,
            NodeTargetResolver.resolve(target, refreshed).status
        )
    }

    @Test
    fun neverRelocatesAcrossWindowGenerations() {
        val original = tree(
            node(emptyList(), "Root", "root", childCount = 1),
            node(listOf(0), "Button", "dismiss", text = "Close", childCount = 0)
        )
        val target = NodeTreeIndex(original).targetFor(NodePath(listOf(0)))!!
        val otherGeneration = AccessibilityTreeSnapshot(
            window = window.copy(generation = window.generation + 1),
            nodes = original.nodes,
            visitedNodeCount = original.visitedNodeCount,
            truncated = false
        )
        assertEquals(
            NodeTargetResolutionStatus.WINDOW_STALE,
            NodeTargetResolver.resolve(target, otherGeneration).status
        )
    }

    @Test
    fun pathOnlyTargetsRetainLegacyFailClosedBehavior() {
        val targetTree = tree(
            node(emptyList(), "Root", "root", childCount = 1),
            node(listOf(0), "Button", "dismiss", text = "Close", childCount = 0)
        )
        val target = NodeTreeIndex(targetTree).targetFor(
            NodePath(listOf(0)), NodeTargetRelocationPolicy.PATH_ONLY
        )!!
        val changedTree = tree(
            node(emptyList(), "Root", "root", childCount = 1),
            node(listOf(0), "Button", "other", text = "Open", childCount = 0)
        )
        assertEquals(
            NodeTargetResolutionStatus.PATH_MISMATCH,
            NodeTargetResolver.resolve(target, changedTree).status
        )
    }


    @Test
    fun refusesEvenAnExactFingerprintWhenTheSnapshotCannotProveUniqueness() {
        val complete = tree(
            node(emptyList(), "Root", "root", childCount = 1),
            node(listOf(0), "Button", "dismiss", text = "Close", childCount = 0)
        )
        val target = NodeTreeIndex(complete).targetFor(NodePath(listOf(0)))!!
        val truncated = tree(
            node(emptyList(), "Root", "root", childCount = 1),
            node(listOf(0), "Button", "dismiss", text = "Close", childCount = 0),
            truncated = true
        )
        assertEquals(
            NodeTargetResolutionStatus.SNAPSHOT_TRUNCATED,
            NodeTargetResolver.resolve(target, truncated).status
        )
    }

    @Test
    fun refusesRelocationWhenIdentityHasOnlyAClassName() {
        val fingerprint = com.freedom.core.accessibility.api.NodeIdentityFingerprint.create(
            className = "Button", viewId = null, text = null, contentDescription = null,
            childCount = 0, parentClassName = null, parentViewId = null
        )
        val target = com.freedom.core.accessibility.api.NodeTarget(
            window = window, path = NodePath(listOf(4)), identityFingerprint = fingerprint,
            relocationPolicy = NodeTargetRelocationPolicy.UNIQUE_MATCH_IN_SAME_WINDOW
        )
        val current = tree(
            node(emptyList(), "Root", "root", childCount = 1),
            node(listOf(0), "Button", "", childCount = 0)
        )
        assertEquals(
            NodeTargetResolutionStatus.INSUFFICIENT_IDENTITY,
            NodeTargetResolver.resolve(target, current).status
        )
    }


    @Test
    fun parentAnchorPreventsRelocationIntoAnotherContainer() {
        val original = tree(
            node(emptyList(), "Root", "root", childCount = 1),
            node(listOf(0), "ActionBar", "top_actions", childCount = 1),
            node(listOf(0, 0), "Button", "dismiss", text = "Close", childCount = 0)
        )
        val target = NodeTreeIndex(original).targetFor(NodePath(listOf(0, 0)))!!
        val refreshed = tree(
            node(emptyList(), "Root", "root", childCount = 1),
            node(listOf(0), "ContentPanel", "content", childCount = 1),
            node(listOf(0, 0), "Button", "dismiss", text = "Close", childCount = 0)
        )
        assertEquals(
            NodeTargetResolutionStatus.NOT_FOUND,
            NodeTargetResolver.resolve(target, refreshed).status
        )
    }

    @Test
    fun refusesDifferentWindowIdsEvenWithinTheSameGeneration() {
        val original = tree(
            node(emptyList(), "Root", "root", childCount = 1),
            node(listOf(0), "Button", "dismiss", text = "Close", childCount = 0)
        )
        val target = NodeTreeIndex(original).targetFor(NodePath(listOf(0)))!!
        val changedWindowId = AccessibilityTreeSnapshot(
            window = window,
            nodes = listOf(
                node(emptyList(), "Root", "root", childCount = 1).copy(windowId = 9),
                node(listOf(0), "Button", "dismiss", text = "Close", childCount = 0).copy(windowId = 9)
            ),
            visitedNodeCount = 2,
            truncated = false,
            windowId = 9
        )
        assertEquals(
            NodeTargetResolutionStatus.WINDOW_ID_MISMATCH,
            NodeTargetResolver.resolve(target, changedWindowId).status
        )
    }

    @Test
    fun refusesTargetsFromAnotherPackageOrActivity() {
        val current = tree(node(emptyList(), "Root", "root", childCount = 0))
        val target = com.freedom.core.accessibility.api.NodeTarget(
            window = window.copy(packageName = "com.other"),
            path = NodePath(emptyList())
        )
        assertEquals(
            NodeTargetResolutionStatus.WINDOW_MISMATCH,
            NodeTargetResolver.resolve(target, current).status
        )
    }

    @Test
    fun reportsBudgetExhaustionAsIndeterminateInsteadOfResolving() {
        val original = tree(
            node(emptyList(), "Root", "root", childCount = 1),
            node(listOf(0), "Button", "dismiss", text = "Close", childCount = 0)
        )
        val target = NodeTreeIndex(original).targetFor(NodePath(listOf(0)))!!

        val result = NodeTargetResolver.resolve(
            target,
            original,
            NodeTargetResolutionBudget(maxVisitedNodes = 1, timeoutMs = Long.MAX_VALUE)
        )

        assertEquals(NodeTargetResolutionStatus.QUERY_BUDGET_EXCEEDED, result.status)
        assertEquals("QUERY_NODE_BUDGET", result.stopReason)
        assertFalse(result.isResolved)
        assertEquals(0, result.scannedNodeCount)
    }

    @Test
    fun doesNotMatchAFieldWhoseFreshReadFailed() {
        val original = tree(
            node(emptyList(), "Root", "root", childCount = 1),
            node(listOf(0), "Button", "dismiss", text = "Close", childCount = 0)
        )
        val target = NodeTreeIndex(original).targetFor(NodePath(listOf(0)))!!
        val unreadable = node(listOf(0), "Button", "dismiss", text = "Close", childCount = 0).copy(
            captureEvidence = AccessibilityNodeCaptureEvidence(
                failedFields = setOf(AccessibilityNodeField.VIEW_ID)
            )
        )
        val refreshed = tree(
            node(emptyList(), "Root", "root", childCount = 1),
            unreadable
        )

        val result = NodeTargetResolver.resolve(target, refreshed)

        assertEquals(NodeTargetResolutionStatus.NOT_FOUND, result.status)
        assertNull(result.resolvedPath)
    }

    @Test
    fun doesNotTreatAnUnreadableCandidateWindowIdAsAMatch() {
        val original = tree(
            node(emptyList(), "Root", "root", childCount = 1),
            node(listOf(0), "Button", "dismiss", text = "Close", childCount = 0)
        )
        val target = NodeTreeIndex(original).targetFor(NodePath(listOf(0)))!!
        val unreadableWindowId = node(
            listOf(0), "Button", "dismiss", text = "Close", childCount = 0
        ).copy(
            captureEvidence = AccessibilityNodeCaptureEvidence(
                failedFields = setOf(AccessibilityNodeField.WINDOW_ID)
            )
        )
        val refreshed = tree(
            node(emptyList(), "Root", "root", childCount = 1),
            unreadableWindowId
        )

        val result = NodeTargetResolver.resolve(target, refreshed)

        assertEquals(NodeTargetResolutionStatus.NOT_FOUND, result.status)
        assertNull(result.resolvedPath)
    }

    @Test
    fun doesNotExposeOriginalTextInFingerprint() {
        val snapshot = tree(
            node(emptyList(), "Root", "root", childCount = 1),
            node(listOf(0), "TextView", "title", text = "private-contact-name", childCount = 0)
        ).copy(textCapture = NodeTextCapture.EPHEMERAL)
        val fingerprint = NodeTreeIndex(snapshot).identityFingerprint(NodePath(listOf(0)))!!
        assertFalse(fingerprint.toString().contains("private-contact-name"))
        assertNotNull(fingerprint.textDigest)
    }

    private fun tree(vararg nodes: AccessibilityNodeSnapshot, truncated: Boolean = false) =
        AccessibilityTreeSnapshot(
            window = window,
            nodes = nodes.toList(),
            visitedNodeCount = nodes.size,
            truncated = truncated,
            walkTruncated = truncated
        )

    private fun node(
        path: List<Int>,
        className: String,
        viewId: String,
        text: String? = null,
        childCount: Int
    ) = AccessibilityNodeSnapshot(
        path = NodePath(path),
        className = className,
        viewId = viewId,
        text = text,
        contentDescription = null,
        bounds = ScreenRect(0, 0, 20, 20),
        visible = true,
        enabled = true,
        checkable = false,
        checked = false,
        clickable = true,
        longClickable = false,
        scrollable = false,
        editable = false,
        selected = false,
        focused = false,
        childCount = childCount,
        windowId = 4
    )
}
