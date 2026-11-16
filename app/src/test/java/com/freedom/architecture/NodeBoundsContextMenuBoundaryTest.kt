package com.freedom.architecture

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Guards the AutoJs6 node-context actions wired into Freedom's in-place interactive bounds view. */
class NodeBoundsContextMenuBoundaryTest {
    private val projectRoot: File
        get() = File(System.getProperty("user.dir")).let { start ->
            generateSequence(start) { it.parentFile }
                .firstOrNull { File(it, "settings.gradle.kts").isFile }
                ?: start
        }

    private fun source(path: String): String = File(projectRoot, path).readText()

    @Test
    fun allNineAutoJs6ContextActionsArePresentAndConnected() {
        val overlay = source("app/src/main/java/com/freedom/adapters/accessibility/android/NodeBoundsHighlightOverlay.kt")
        listOf(
            "显示节点信息", "查看布局层级", "生成代码", "选择父节点", "隐藏节点",
            "隐藏相同边界节点", "切换窗口", "保存布局到文件", "退出"
        ).forEach { title -> assertTrue("Missing context action: $title", overlay.contains("action(\"$title\"")) }
        listOf("selectNode(captured)", "NodeHierarchyOverlay.show(", "generateCode(captured)",
            "NodeInspectorOperations.parentOf(", "hideNode(captured)", "hideSameBounds(captured)",
            "showWindowPicker(", "saveLayoutSnapshot()", "exitInspector()"
        ).forEach { call -> assertTrue("Missing action handler: $call", overlay.contains(call)) }
    }

    @Test
    fun parentHideAndWindowSelectionAreScopedToCapturedWindowIdentity() {
        val operations = source("app/src/main/java/com/freedom/core/accessibility/inspector/NodeInspectorOperations.kt")
        val overlay = source("app/src/main/java/com/freedom/adapters/accessibility/android/NodeBoundsHighlightOverlay.kt")
        assertTrue(operations.contains("entry.identity.window == selected.window"))
        assertTrue(operations.contains("entry.identity.path.childIndices == parentPath"))
        assertTrue(operations.contains("it.bounds == bounds"))
        assertTrue(overlay.contains("activeWindow = snapshot.window"))
        assertTrue(overlay.contains("refreshVisibleBounds()"))
    }


    @Test
    fun hiddenNodesAreExcludedFromBoundsHitsHierarchyRowsAndSearchResults() {
        val bounds = source("app/src/main/java/com/freedom/adapters/accessibility/android/NodeBoundsHighlightOverlay.kt")
        val hierarchy = source("app/src/main/java/com/freedom/adapters/accessibility/android/NodeHierarchyOverlay.kt")
        val search = source("app/src/main/java/com/freedom/core/accessibility/inspector/InspectorSearch.kt")
        val tree = source("app/src/main/java/com/freedom/core/accessibility/inspector/AccessibilityHierarchyTree.kt")
        assertTrue(bounds.contains("NodeInspectorVisibilitySession.hiddenIdentities()"))
        assertTrue(bounds.contains("NodeInspectorVisibilitySession.hide(captured.identity)"))
        assertTrue(hierarchy.contains("NodeInspectorVisibilitySession.hiddenPaths(snapshot.window)"))
        assertTrue(hierarchy.contains("NodeInspectorVisibilitySession.observe"))
        assertTrue(search.contains("excludedPaths: Set<List<Int>>"))
        assertTrue(tree.contains("hiddenPaths: Set<List<Int>> = emptySet()"))
    }

    @Test
    fun codeGenerationUsesCapturedSelectorsAndDoesNotReadText() {
        val generator = source("app/src/main/java/com/freedom/core/accessibility/inspector/NodeCodeSnippetGenerator.kt")
        val overlay = source("app/src/main/java/com/freedom/adapters/accessibility/android/NodeBoundsHighlightOverlay.kt")
        assertTrue(generator.contains("NodeLocatorCandidateBuilder.build(NodeTreeIndex(snapshot), node.path)"))
        assertTrue(generator.contains("UNIQUE_IN_SNAPSHOT"))
        assertTrue(generator.contains("文本和描述字段"))
        assertFalse(generator.contains("node.text"))
        assertTrue(overlay.contains("NodeCodeSnippetGenerator.generate(captured.node, captured.snapshot)"))
        assertTrue(overlay.contains("copy(\"节点定位代码\", code)"))
    }

    @Test
    fun layoutExportUsesTheAutoJs6LayoutEnvelopeAndTextFreeNodeSnapshot() {
        val exporter = source("app/src/main/java/com/freedom/adapters/accessibility/android/NodeLayoutSnapshotExporter.kt")
        assertTrue(exporter.contains(".layout.json"))
        listOf("version", "generator", "capturedAt", "root", "children", "bounds", "fullId", "textCapture")
            .forEach { key -> assertTrue("Missing layout export field: $key", exporter.contains("\"$key\"")) }
        assertTrue(exporter.contains("put(\"text\", \"\")"))
        assertTrue(exporter.contains("getExternalFilesDir(\"layouts\")"))
    }
}
