"""D01-D2: retire only the four unreachable legacy Activity inspector components."""
import re
import unittest
from pathlib import Path
from tools.architecture.test_pp_r00_freeze import git_blob
from tools.architecture.test_pp_d01d3_legacy_node_cache_retirement import INSPECTOR_TEST, restore_d01d3_source

ROOT = Path(__file__).resolve().parents[2]
TEST = "app/src/test/java/com/freedom/architecture/AccessibilityInspectorPipelineBoundaryTest.kt"
PREVIOUS = "14aa980f91bd9478b45b95790593439bd27167aa"
CURRENT = "8acfe58fc2d9d9f6f6d6e0958a794c8b4b0e8bbb"
RETIRED = {"app/src/main/java/com/freedom/application/ports/NodeCapturePresentationPort.kt":"dcea2c8aefabbd712435273a9262fcd51ec75f2d","app/src/main/java/com/freedom/ui/logs/NodeInspectorBoundsMap.kt":"ab0dff47ab04ea956eede8d2ba9345177f577629","app/src/main/java/com/freedom/ui/logs/NodeInspectorDialog.kt":"4ed1964e5f932df8ce3a02c72821e7e801f0d648","app/src/main/java/com/freedom/ui/logs/NodeInspectorTextFormatter.kt":"a6c7dbb92e20257fc41ad774ea6a4519a8d7b631"}
OLD_TEST_BLOCK = "    @Test\n    fun inspectorRemainsSnapshotOnlyAndDoesNotExecuteTargetActions() {\n        val inspector = source(\n            \"app/src/main/java/com/freedom/ui/logs/NodeInspectorDialog.kt\"\n        )\n\n        assertTrue(\"Inspector must render immutable node snapshots\", inspector.contains(\"AccessibilityNodeSnapshot\"))\n        assertTrue(\"Inspector must read snapshots through its presentation port\", inspector.contains(\"capturePort.latestSnapshots(selectedSource)\"))\n        assertTrue(\"Inspector must route highlight requests through its presentation port\", inspector.contains(\"capturePort.requestNodeBoundsHighlight\"))\n        assertFalse(\"Manual capture entry belongs to the floating-button menu, not the inspector\", inspector.contains(\"requestManualCapture()\"))\n        assertFalse(\"UI must not send Android capture broadcasts directly\", inspector.contains(\"ACTION_MANUAL_NODE_CAPTURE\"))\n        assertFalse(\"UI must not retain live Android node handles\", inspector.contains(\"AccessibilityNodeInfo\"))\n        assertFalse(\"Inspector must not perform node actions\", inspector.contains(\"performAction(\"))\n        assertFalse(\"Inspector must not dispatch screen gestures\", inspector.contains(\"dispatchGesture(\"))\n    }\n\n    @Test\n    fun inspectorFilteringIsDebouncedOffMainThreadAndInvalidatesStaleWork() {\n        val inspector = source(\n            \"app/src/main/java/com/freedom/ui/logs/NodeInspectorDialog.kt\"\n        )\n\n        assertTrue(\"Filtering must use a dedicated executor\", inspector.contains(\"filterExecutor.execute\"))\n        assertTrue(\"Typing must be debounced\", inspector.contains(\"filterHandler.postDelayed(scheduleFilter, 160L)\"))\n        assertTrue(\"Late results must be rejected by request sequence\", inspector.contains(\"requestId != filterRequestSequence\"))\n        assertTrue(\"Late results must not update a dismissed dialog\", inspector.contains(\"!inspectorDialog.isShowing\"))\n        assertTrue(\"Pending work must be cancelled on dismissal\", inspector.contains(\"pendingFilterRunnable?.let(filterHandler::removeCallbacks)\"))\n        assertTrue(\"Filter executor must be released on dismissal\", inspector.contains(\"filterExecutor.shutdownNow()\"))\n    }\n\n"
NEW_TEST_BLOCK = "    @Test\n    fun inspectorRemainsSnapshotOnlyAndDoesNotExecuteTargetActions() {\n        val bounds = source(\"app/src/main/java/com/freedom/adapters/accessibility/android/NodeBoundsHighlightOverlay.kt\")\n        val hierarchy = source(\"app/src/main/java/com/freedom/adapters/accessibility/android/NodeHierarchyOverlay.kt\")\n        val owner = source(\"app/src/main/java/com/freedom/application/monitor/GlobalNodeLayoutVisualizationUseCase.kt\")\n        assertTrue(\"The capture owner passes detached snapshots to the overlay\", owner.contains(\"showInteractiveNodeLayoutBounds(snapshots, label)\"))\n        assertTrue(\"The capture owner exposes the same detached snapshots\", owner.contains(\"fun snapshots(): List<AccessibilityTreeSnapshot> = latestSnapshots\"))\n        assertTrue(\"The bounds view reuses captured windows for hierarchy\", bounds.contains(\"NodeHierarchyOverlay.show(this.context, capturedSnapshots)\"))\n        for (inspector in listOf(bounds, hierarchy)) {\n            assertTrue(\"Inspector must render immutable node snapshots\", inspector.contains(\"AccessibilityNodeSnapshot\"))\n            assertTrue(\"Inspector must consume detached trees\", inspector.contains(\"AccessibilityTreeSnapshot\"))\n            assertFalse(\"Manual capture entry belongs to the floating-button menu, not the inspector\", inspector.contains(\"requestManualCapture()\"))\n            assertFalse(\"UI must not send Android capture broadcasts directly\", inspector.contains(\"ACTION_MANUAL_NODE_CAPTURE\"))\n            assertFalse(\"UI must not import live Android node handles\", inspector.contains(\"import android.view.accessibility.AccessibilityNodeInfo\"))\n            assertFalse(\"Inspector must not perform node actions\", inspector.contains(\"performAction(\"))\n            assertFalse(\"Inspector must not dispatch screen gestures\", inspector.contains(\"dispatchGesture(\"))\n        }\n    }\n\n    @Test\n    fun liveInspectorSearchUsesCurrentDetachedSnapshotsAndClearsItsPresentation() {\n        // The retired Activity used an asynchronous 160ms filter. The existing floating\n        // surfaces evaluate detached trees synchronously; do not claim executor equivalence.\n        val bounds = source(\"app/src/main/java/com/freedom/adapters/accessibility/android/NodeBoundsHighlightOverlay.kt\")\n        val hierarchy = source(\"app/src/main/java/com/freedom/adapters/accessibility/android/NodeHierarchyOverlay.kt\")\n        val search = source(\"app/src/main/java/com/freedom/adapters/accessibility/android/InspectorSearchBarView.kt\")\n        assertTrue(search.contains(\"private fun notifyChanged() = onChanged(currentQuery(), regularExpression, enabledAttributes.toSet())\"))\n        assertTrue(bounds.contains(\"searchCriteria = searchCriteria.copy(query = query, regularExpression = regex, attributes = attributes)\"))\n        assertTrue(bounds.contains(\"searchResults = capturedSnapshots.associate\"))\n        assertTrue(bounds.contains(\"NodeInspectorVisibilitySession.hiddenPaths(snapshot.window)\"))\n        assertTrue(bounds.contains(\"fun dispose()\"))\n        assertTrue(bounds.contains(\"removeCallbacks(longPressRunnable)\"))\n        assertTrue(bounds.contains(\"searchBar?.hideKeyboard()\"))\n        assertTrue(bounds.contains(\"searchBar = null\"))\n        assertTrue(hierarchy.contains(\"searchCriteria = searchCriteria.copy(query = query, regularExpression = regex, attributes = attributes)\"))\n        assertTrue(hierarchy.contains(\"val snapshot = currentSnapshot() ?: return\"))\n        assertTrue(hierarchy.contains(\"val rows = treeRows ?: return\"))\n        assertTrue(hierarchy.contains(\"InspectorSearchEngine.evaluate(snapshot, searchCriteria, hiddenPaths)\"))\n        assertTrue(hierarchy.contains(\"removeVisibilityObserver?.invoke()\"))\n        assertTrue(hierarchy.contains(\"treeRows = null\"))\n        assertTrue(hierarchy.contains(\"capturedSnapshots = emptyList()\"))\n        assertTrue(hierarchy.contains(\"searchCriteria = InspectorSearchCriteria()\"))\n    }\n\n"


def restore_d01d2_test(source):
    source = restore_d01d3_source(INSPECTOR_TEST, source)
    if source.count(NEW_TEST_BLOCK) != 1:
        raise AssertionError("D01-D2 test migration anchor changed")
    restored = source.replace(NEW_TEST_BLOCK, OLD_TEST_BLOCK, 1)
    if git_blob(restored.encode("utf-8")) != PREVIOUS:
        raise AssertionError("D01-D2 test baseline not reconstructed")
    return restored


class D01d2LegacyInspectorRetirementTest(unittest.TestCase):
    def test_exact_test_migration(self):
        source = (ROOT / TEST).read_text(encoding="utf-8")
        self.assertEqual(CURRENT, git_blob(restore_d01d3_source(INSPECTOR_TEST, source).encode("utf-8")))
        self.assertEqual(PREVIOUS, git_blob(restore_d01d2_test(source).encode("utf-8")))

    def test_retired_files_and_production_references_absent(self):
        for path in RETIRED:
            self.assertFalse((ROOT / path).exists(), path)
            self.assertFalse((ROOT / path).is_symlink(), path)
        retired = re.compile(r"\b(?:NodeInspectorDialog|NodeCapturePresentationPort|NodeInspectorBoundsMap|NodeInspectorTextFormatter)\b")
        issues = []
        for path in ROOT.rglob("*"):
            if not path.is_file() or path.suffix not in {".kt", ".java", ".xml", ".aidl", ".json", ".properties", ".kts"}:
                continue
            parts = path.relative_to(ROOT).parts
            if "src" not in parts or "main" not in parts or "build" in parts or ".git" in parts or ".gradle" in parts:
                continue
            if retired.search(path.read_text(encoding="utf-8")):
                issues.append(str(path.relative_to(ROOT)))
        self.assertEqual([], issues)

    def test_inverse_rejects_damage_to_live_inspector_guards(self):
        source = (ROOT / TEST).read_text(encoding="utf-8")
        for token in ("performAction(", "dispatchGesture(", "inspectTargetWindows(", "MIN_DURATION_MS = 500L", "textCapture = NodeTextCapture.EPHEMERAL"):
            self.assertIn(token, source)
            with self.assertRaises(AssertionError):
                restore_d01d2_test(source.replace(token, "BROKEN", 1))
