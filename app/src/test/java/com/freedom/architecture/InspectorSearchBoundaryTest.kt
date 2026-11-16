package com.freedom.architecture

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/** Protects the stage-four search/filter contract across both inspector presentations. */
class InspectorSearchBoundaryTest {
    private val root: File
        get() = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
            .first { File(it, "settings.gradle.kts").isFile }

    private fun source(path: String) = File(root, path).readText()

    @Test fun pureSearchEngineCoversQueryAttributesRegexAndRegion() {
        val engine = source("app/src/main/java/com/freedom/core/accessibility/inspector/InspectorSearch.kt")
        listOf("regularExpression", "Pattern.compile", "CLICKABLE", "EDITABLE", "SCROLLABLE", "containsBounds", "retainedPaths")
            .forEach { assertTrue("Missing search contract: $it", engine.contains(it)) }
    }

    @Test fun bothInspectorSurfacesExposeSearchAndHighlightMatches() {
        val bounds = source("app/src/main/java/com/freedom/adapters/accessibility/android/NodeBoundsHighlightOverlay.kt")
        val hierarchy = source("app/src/main/java/com/freedom/adapters/accessibility/android/NodeHierarchyOverlay.kt")
        val bar = source("app/src/main/java/com/freedom/adapters/accessibility/android/InspectorSearchBarView.kt")
        assertTrue(bounds.contains("InspectorSearchBarView(context"))
        assertTrue(bounds.contains("regionPreview = ScreenRect("))
        assertTrue(bounds.contains("regionSelectionMode = enabled"))
        assertTrue(bar.contains("框选区域"))
        assertTrue(bounds.contains("matchedBoundsPaint"))
        assertTrue(bounds.contains("InspectorSearchEngine.evaluate"))
        assertTrue(hierarchy.contains("InspectorSearchBarView(context"))
        assertTrue(hierarchy.contains("highlightedRowLabel("))
        assertTrue(hierarchy.contains("BackgroundColorSpan"))
        assertTrue(bar.contains("搜索文本 / ID / 类名"))
        assertTrue(bar.contains("InspectorNodeAttribute.values()"))
    }

    @Test fun textIsEphemeralForSearchAndStrippedFromSavedLayout() {
        val useCase = source("app/src/main/java/com/freedom/application/monitor/GlobalNodeLayoutVisualizationUseCase.kt")
        val exporter = source("app/src/main/java/com/freedom/adapters/accessibility/android/NodeLayoutSnapshotExporter.kt")
        assertTrue(useCase.contains("textCapture = NodeTextCapture.EPHEMERAL"))
        assertTrue(exporter.contains("put(\"textCapture\", \"NONE\")"))
        assertTrue(exporter.contains("put(\"text\", \"\")"))
        assertTrue(exporter.contains("put(\"desc\", JSONObject.NULL)"))
    }
}
