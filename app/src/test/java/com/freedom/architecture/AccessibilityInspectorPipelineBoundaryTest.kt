package com.freedom.architecture

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Regression guards for the P2-P5 node-inspection and visual-highlight pipeline. */
class AccessibilityInspectorPipelineBoundaryTest {
    private val projectRoot: File
        get() = File(System.getProperty("user.dir")).let { start ->
            generateSequence(start) { it.parentFile }
                .firstOrNull { File(it, "settings.gradle.kts").isFile }
                ?: start
        }

    private fun source(relativePath: String): String =
        TestSourceResolver.resolve(projectRoot, relativePath).readText()

    @Test
    fun inspectorRemainsSnapshotOnlyAndDoesNotExecuteTargetActions() {
        val bounds = source("app/src/main/java/com/freedom/adapters/accessibility/android/NodeBoundsHighlightOverlay.kt")
        val hierarchy = source("app/src/main/java/com/freedom/adapters/accessibility/android/NodeHierarchyOverlay.kt")
        val owner = source("app/src/main/java/com/freedom/application/monitor/GlobalNodeLayoutVisualizationUseCase.kt")
        assertTrue("The capture owner passes detached snapshots to the overlay", owner.contains("showInteractiveNodeLayoutBounds(snapshots, label)"))
        assertTrue("The capture owner exposes the same detached snapshots", owner.contains("fun snapshots(): List<AccessibilityTreeSnapshot> = latestSnapshots"))
        assertTrue("The bounds view reuses captured windows for hierarchy", bounds.contains("NodeHierarchyOverlay.show(this.context, capturedSnapshots)"))
        for (inspector in listOf(bounds, hierarchy)) {
            assertTrue("Inspector must render immutable node snapshots", inspector.contains("AccessibilityNodeSnapshot"))
            assertTrue("Inspector must consume detached trees", inspector.contains("AccessibilityTreeSnapshot"))
            assertFalse("Manual capture entry belongs to the floating-button menu, not the inspector", inspector.contains("requestManualCapture()"))
            assertFalse("UI must not send Android capture broadcasts directly", inspector.contains("ACTION_MANUAL_NODE_CAPTURE"))
            assertFalse("UI must not import live Android node handles", inspector.contains("import android.view.accessibility.AccessibilityNodeInfo"))
            assertFalse("Inspector must not perform node actions", inspector.contains("performAction("))
            assertFalse("Inspector must not dispatch screen gestures", inspector.contains("dispatchGesture("))
        }
    }

    @Test
    fun liveInspectorSearchUsesCurrentDetachedSnapshotsAndClearsItsPresentation() {
        // The retired Activity used an asynchronous 160ms filter. The existing floating
        // surfaces evaluate detached trees synchronously; do not claim executor equivalence.
        val bounds = source("app/src/main/java/com/freedom/adapters/accessibility/android/NodeBoundsHighlightOverlay.kt")
        val hierarchy = source("app/src/main/java/com/freedom/adapters/accessibility/android/NodeHierarchyOverlay.kt")
        val search = source("app/src/main/java/com/freedom/adapters/accessibility/android/InspectorSearchBarView.kt")
        assertTrue(search.contains("private fun notifyChanged() = onChanged(currentQuery(), regularExpression, enabledAttributes.toSet())"))
        assertTrue(bounds.contains("searchCriteria = searchCriteria.copy(query = query, regularExpression = regex, attributes = attributes)"))
        assertTrue(bounds.contains("searchResults = capturedSnapshots.associate"))
        assertTrue(bounds.contains("NodeInspectorVisibilitySession.hiddenPaths(snapshot.window)"))
        assertTrue(bounds.contains("fun dispose()"))
        assertTrue(bounds.contains("removeCallbacks(longPressRunnable)"))
        assertTrue(bounds.contains("searchBar?.hideKeyboard()"))
        assertTrue(bounds.contains("searchBar = null"))
        assertTrue(hierarchy.contains("searchCriteria = searchCriteria.copy(query = query, regularExpression = regex, attributes = attributes)"))
        assertTrue(hierarchy.contains("val snapshot = currentSnapshot() ?: return"))
        assertTrue(hierarchy.contains("val rows = treeRows ?: return"))
        assertTrue(hierarchy.contains("InspectorSearchEngine.evaluate(snapshot, searchCriteria, hiddenPaths)"))
        assertTrue(hierarchy.contains("removeVisibilityObserver?.invoke()"))
        assertTrue(hierarchy.contains("treeRows = null"))
        assertTrue(hierarchy.contains("capturedSnapshots = emptyList()"))
        assertTrue(hierarchy.contains("searchCriteria = InspectorSearchCriteria()"))
    }

    @Test
    fun screenHighlightMustRefreshAndResolveBeforeDrawing() {
        val service = source(
            "app/src/main/java/com/freedom/accessibility/FreedomAccessibilityService.kt"
        )
        val start = service.indexOf("private fun handleNodeBoundsHighlightRequest(intent: Intent)")
        assertTrue("Highlight request handler must exist", start >= 0)
        val end = service.indexOf("\n    private fun handleNodeLayoutBoundsRequest", start)
        assertTrue("Highlight request handler must end before the next handler", end > start)
        val handler = service.substring(start, end)

        val refresh = handler.indexOf("inspectTargetWindows(")
        val resolve = handler.indexOf("NodeTargetResolver.resolve(")
        val draw = handler.indexOf("showNodeBoundsHighlight(")
        assertTrue("Fresh-window capture must precede resolution", refresh >= 0 && refresh < resolve)
        assertTrue("Resolution must precede drawing", resolve >= 0 && resolve < draw)
        assertTrue("Unresolved targets must be rejected", handler.contains("if (!resolution.isResolved)"))
        assertTrue("Failure paths must clear any previous highlight", handler.contains("clearNodeBoundsHighlight()"))
    }

    @Test
    fun transientNodeHighlightsRemainPassThroughAndHaveBoundedLifetime() {
        val overlay = source(
            "app/src/main/java/com/freedom/adapters/accessibility/android/NodeBoundsHighlightOverlay.kt"
        )

        assertTrue("Highlight must use the accessibility overlay window type", overlay.contains("WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY"))
        assertTrue("Highlight must not intercept touch input", overlay.contains("WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE"))
        assertTrue("Highlight lifetime must have a minimum bound", overlay.contains("MIN_DURATION_MS = 500L"))
        assertTrue("Highlight lifetime must have a maximum bound", overlay.contains("MAX_DURATION_MS = 15_000L"))
        assertTrue("Scheduled removal must clamp the requested lifetime", overlay.contains("durationMs.coerceIn(MIN_DURATION_MS, MAX_DURATION_MS)"))
        assertTrue("Full layout preview must cap drawn rectangles", overlay.contains("MAX_LAYOUT_BOUNDS = 600"))
        assertTrue("Legacy full layout preview remains available", overlay.contains("fun showAll("))
        assertTrue("Persistent full layout inspection is touch-interactive", overlay.contains("fun showInteractiveAll("))
        assertTrue("Interactive mode receives touches rather than setting FLAG_NOT_TOUCHABLE", overlay.contains("if (interactive) {\n            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL"))
        assertTrue("Interactive inspector has an explicit restore/exit control", overlay.contains("退出节点检查器，恢复原页面"))
        assertTrue("Highlight cleanup must remove the attached view", overlay.contains("manager.removeViewImmediate(view)"))
    }

    @Test
    fun manualCaptureIsPackageFilteredAndHasABoundedCrossWindowBudget() {
        val gateway = source(
            "app/src/main/java/com/freedom/adapters/accessibility/android/AndroidAccessibilityGateway.kt"
        )

        assertTrue("Window roots must be checked against the selected target package", gateway.contains("rootPackage != expectedPackage"))
        assertTrue("The number of same-package windows must be capped", gateway.contains("MAX_TARGET_WINDOWS_PER_CAPTURE = 8"))
        assertTrue("The node budget must be shared across windows", gateway.contains("request.copy(maxNodes = (request.maxNodes / selectedRoots.size)"))
        assertTrue("Accessibility window handles must be recycled", gateway.contains("window.recycle()"))
        assertTrue("Active and focused target windows must be prioritized", gateway.contains("priority = if (isActiveOrFocused) -1 else index"))
        assertTrue("Excess same-package roots must be released", gateway.contains("prioritized.drop(MAX_TARGET_WINDOWS_PER_CAPTURE).forEach { recycle(it.root) }"))
        assertTrue("Exceeding the window cap must mark results incomplete", gateway.contains("windowBudgetTruncated -> \"WINDOW_BUDGET\""))
    }

    @Test
    fun fullLayoutPreviewMustRefreshTheSelectedTargetWindowBeforeDrawing() {
        val service = source(
            "app/src/main/java/com/freedom/accessibility/FreedomAccessibilityService.kt"
        )
        val start = service.indexOf("private fun handleNodeLayoutBoundsRequest(intent: Intent)")
        assertTrue("Full layout preview handler must exist", start >= 0)
        val end = service.indexOf("\n    private fun handleGlobalNodeLayoutBoundsRequest", start)
        assertTrue("Full layout preview handler must end before the next handler", end > start)
        val handler = service.substring(start, end)
        val sourceCheck = handler.indexOf("LatestAccessibilityNodeDump.latestAll(source)")
        val refresh = handler.indexOf("inspectTargetWindows(")
        val draw = handler.indexOf("showNodeLayoutBounds(")
        assertTrue("Selected source snapshot must be checked before refresh", sourceCheck >= 0 && sourceCheck < refresh)
        assertTrue("Fresh capture must precede layout drawing", refresh >= 0 && refresh < draw)
        assertTrue("Preview must remain scoped to the target package", handler.contains("snapshot.window.packageName == expectedPackage"))
        assertTrue("Preview must preserve selected window identity", handler.contains("snapshot.windowId == requestedWindowId"))
        assertTrue("Preview must reject changed window generations", handler.contains("snapshot.window.generation == sourceSnapshot.window.generation"))
    }

    @Test
    fun floatingGlobalVisualizationCapturesAllFreshSamePackageWindowsInPlace() {
        val service = source(
            "app/src/main/java/com/freedom/accessibility/FreedomAccessibilityService.kt"
        )
        val useCase = source(
            "app/src/main/java/com/freedom/application/monitor/GlobalNodeLayoutVisualizationUseCase.kt"
        )
        val start = service.indexOf("private fun handleGlobalNodeLayoutBoundsRequest()")
        assertTrue("Floating global visualization handler must exist", start >= 0)
        val end = service.indexOf("private fun handleGlobalNodeHierarchyRequest()", start)
        assertTrue("Floating global visualization handler must be bounded", end > start)
        val handler = service.substring(start, end)
        assertTrue("Service receiver must delegate the complete scan to the use case", handler.contains("globalNodeLayoutVisualizationUseCase.show()"))
        val capture = useCase.indexOf("inspectTargetWindows(")
        val packageFilter = useCase.indexOf("snapshot.window.packageName == expectedPackage")
        val bounds = useCase.indexOf("flatMap { snapshot -> snapshot.nodes.asSequence() }")
        val draw = useCase.indexOf("showInteractiveNodeLayoutBounds(snapshots, label)")
        assertTrue("The launcher action must collect a fresh detached tree", capture >= 0)
        assertTrue("Only the selected target package may be drawn", packageFilter > capture)
        assertTrue("All same-package windows must contribute nodes", bounds > packageFilter)
        assertTrue("Interactive drawing must receive the detached node snapshots", draw >= 0)
        assertTrue("The user-invoked inspector may capture transient text for search", useCase.contains("textCapture = NodeTextCapture.EPHEMERAL"))
        val exporter = source("app/src/main/java/com/freedom/adapters/accessibility/android/NodeLayoutSnapshotExporter.kt")
        assertTrue("Saved layout snapshots must continue to omit captured text", exporter.contains("put(\"textCapture\", \"NONE\")"))
        assertTrue("Saved node text must remain blank", exporter.contains("put(\"text\", \"\")"))
        assertTrue("Failure must clear an old visualization", useCase.contains("overlay.clearNodeBoundsHighlight()"))
        assertTrue("The same detached snapshots are reused for hierarchy view", useCase.contains("fun snapshots(): List<AccessibilityTreeSnapshot> = latestSnapshots"))
    }

    @Test
    fun persistentBoundsOverlayIsPassThroughUntilExplicitlyCleared() {
        val overlay = source(
            "app/src/main/java/com/freedom/adapters/accessibility/android/NodeBoundsHighlightOverlay.kt"
        )
        val adapter = source(
            "app/src/main/java/com/freedom/adapters/accessibility/android/AndroidOverlayPort.kt"
        )
        val port = source("app/src/main/java/com/freedom/core/accessibility/overlay/OverlayPort.kt")

        assertTrue(overlay.contains("fun showAllPersistent("))
        assertTrue(overlay.contains("showAllInternal(context, bounds, label, durationMs = null)"))
        assertTrue(overlay.contains("durationMs?.let { duration ->"))
        assertTrue(overlay.contains("WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE"))
        assertTrue(adapter.contains("NodeBoundsHighlightOverlay.showAllPersistent("))
        assertTrue(port.contains("fun showPersistentNodeLayoutBounds("))
    }

}
