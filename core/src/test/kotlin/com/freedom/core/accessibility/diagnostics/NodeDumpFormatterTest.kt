package com.freedom.core.accessibility.diagnostics

import com.freedom.core.accessibility.api.AccessibilityActionSnapshot
import com.freedom.core.accessibility.api.AccessibilityNodeSnapshot
import com.freedom.core.accessibility.api.AccessibilityTreeSnapshot
import com.freedom.core.accessibility.api.NodePath
import com.freedom.core.accessibility.api.ScreenRect
import com.freedom.core.accessibility.api.WindowRef
import org.junit.Assert.*
import org.junit.Test

class NodeDumpFormatterTest {
    private fun snapshot() = AccessibilityTreeSnapshot(
        window = WindowRef("com.example", "ChatActivity", 4),
        nodes = listOf(
            node(emptyList(), "Root", childCount = 1),
            node(listOf(0), "TextView", viewId = "message", text = "private message")
        ),
        visitedNodeCount = 2,
        truncated = false,
        requestedMaxNodes = 2,
        walkTruncated = false
    )

    @Test
    fun jsonIsHierarchicalAndOmitsTextByDefault() {
        val artifact = NodeDumpFormatter.dump(snapshot())
        assertTrue(artifact.content.contains("\"children\""))
        assertTrue(artifact.content.contains("\"path\": \"0\""))
        assertFalse(artifact.content.contains("private message"))
        assertFalse(artifact.content.contains("\"text\""))
        assertEquals(2, artifact.exportedNodeCount)
        assertFalse(artifact.truncated)
    }

    @Test
    fun textCanBeExplicitlyIncludedAndIsMarkedInManifest() {
        val artifact = NodeDumpFormatter.dump(
            snapshot(),
            NodeDumpOptions(format = NodeDumpFormat.TEXT, includeText = true)
        )
        assertTrue(artifact.content.contains("private message"))
        assertTrue(artifact.manifestJson.contains("\"includeText\": true"))
        assertTrue(artifact.manifestJson.contains("USER_OPT_IN_UNREDACTED"))
    }

    @Test
    fun xmlUsesUiAutomatorStyleHierarchyAndEscapesValues() {
        val escapedSnapshot = snapshot().copy(
            nodes = snapshot().nodes.map { if (it.path.childIndices.isEmpty()) it else it.copy(viewId = "id<&>") }
        )
        val artifact = NodeDumpFormatter.dump(
            escapedSnapshot,
            NodeDumpOptions(format = NodeDumpFormat.XML)
        )
        assertTrue(artifact.content.contains("<hierarchy"))
        assertTrue(artifact.content.contains("<node"))
        assertTrue(artifact.content.contains("id&lt;&amp;&gt;"))
        assertTrue(artifact.content.contains("</hierarchy>"))
    }

    @Test
    fun reachingTheExactExportBudgetDoesNotReportTruncation() {
        val artifact = NodeDumpFormatter.dump(snapshot(), NodeDumpOptions(maxNodes = 2))
        assertFalse(artifact.truncated)
        assertNull(artifact.stopReason)
        assertEquals(2, artifact.exportedNodeCount)
    }

    @Test
    fun actionLabelsAreOmittedUnlessTextCaptureIsExplicitlyEnabled() {
        val withAction = snapshot().copy(
            nodes = snapshot().nodes.map { node ->
                if (node.path.childIndices.isEmpty()) node
                else node.copy(actions = listOf(AccessibilityActionSnapshot(16, "private action label")))
            }
        )
        val redacted = NodeDumpFormatter.dump(withAction)
        assertFalse(redacted.content.contains("private action label"))
        val optedIn = NodeDumpFormatter.dump(withAction, NodeDumpOptions(includeText = true))
        assertTrue(optedIn.content.contains("private action label"))
    }

    @Test
    fun exportBudgetAndDepthAreReportedSeparately() {
        val budget = NodeDumpFormatter.dump(snapshot(), NodeDumpOptions(maxNodes = 1))
        assertTrue(budget.truncated)
        assertEquals("EXPORT_NODE_BUDGET", budget.stopReason)
        val depth = NodeDumpFormatter.dump(snapshot(), NodeDumpOptions(maxDepth = 0))
        assertTrue(depth.truncated)
        assertEquals("DEPTH_BUDGET", depth.stopReason)
        assertEquals(1, depth.exportedNodeCount)
    }

    private fun node(path: List<Int>, className: String, viewId: String = "", text: String? = null, childCount: Int = 0) =
        AccessibilityNodeSnapshot(
            path = NodePath(path), className = className, viewId = viewId,
            text = text, contentDescription = null, bounds = ScreenRect(0, 0, 20, 20),
            visible = true, enabled = true, checkable = false, checked = false,
            clickable = false, longClickable = false, scrollable = false, editable = false,
            selected = false, focused = false, childCount = childCount
        )
}
