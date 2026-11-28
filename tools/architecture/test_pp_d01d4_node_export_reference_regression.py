"""D01-D4: cross-module reference and end-to-end contract regression, no production changes."""
import re
import subprocess
import unittest
from pathlib import Path, PurePosixPath

ROOT = Path(__file__).resolve().parents[2]
APP = "app/src/main/java/com/freedom/"
RETIRED = re.compile(
    r"\b(?:launchNodeDumpExport|exportNodeDumpArchive|pendingNodeDumpExportFile|"
    r"REQUEST_EXPORT_NODE_DUMP|createBatchArchive|NodeInspectorDialog|"
    r"NodeCapturePresentationPort|NodeInspectorBoundsMap|NodeInspectorTextFormatter|"
    r"LatestAccessibilityNodeDump|ACTION_MANUAL_NODE_CAPTURE|ACTION_HIGHLIGHT_NODE_BOUNDS|"
    r"ACTION_SHOW_NODE_LAYOUT_BOUNDS|ACTION_CLEAR_NODE_BOUNDS_HIGHLIGHT|"
    r"EXTRA_NODE_CAPTURE_SOURCE|EXTRA_NODE_PATH|EXTRA_NODE_WINDOW_ID|"
    r"captureManual|onNodeTreeSnapshot|onSnapshotCaptured|onSnapshotsCaptured|"
    r"handleNodeBoundsHighlightRequest|handleNodeLayoutBoundsRequest|manualNodeCaptureReceiver)\b"
    r"|com\.freedom\.extra\.(?:NODE_CAPTURE_SOURCE|NODE_PATH|NODE_WINDOW_ID)\b"
    r"|Freedom-Node-Dump-|导出最近节点预览 ZIP"
)
TEXT_SUFFIXES = {".kt", ".java", ".xml", ".aidl", ".json", ".properties",
                 ".kts", ".gradle", ".pro", ".toml", ".yml", ".yaml", ".sh", ".py",
                 ".txt", ".cfg", ".ini", ".js", ".html"}
TEST_SOURCE = re.compile(r"(?:^|/)(?:test|androidTest|testFixtures|testDebug|testRelease)(?:/|$)")
RETIRED_IMPORT = re.compile(r"(?m)^\s*import\s+[^\n;]*\b(?:NodeInspectorDialog|NodeCapturePresentationPort|"
                            r"NodeInspectorBoundsMap|NodeInspectorTextFormatter|LatestAccessibilityNodeDump)\b")


def tracked_paths():
    raw = subprocess.check_output(["git", "ls-files", "-z"], cwd=ROOT)
    return [path for path in raw.decode("utf-8").split("\0") if path]


def scope(path):
    p = PurePosixPath(path)
    if path in {"gradlew", "gradlew.bat"}:
        return "runtime"
    if p.suffix not in TEXT_SUFFIXES:
        return None
    if "src" in p.parts:
        return "test" if TEST_SOURCE.search(path) else "runtime"
    if path.startswith(".github/") or p.suffix in {".gradle", ".kts", ".pro", ".properties", ".toml"}:
        return "runtime"
    return None


def retired_violations(sources):
    result = []
    for path, source in sources.items():
        category = scope(path)
        if category is None:
            continue
        # Assertion strings about retired symbols are valid historical/negative test evidence.
        matcher = RETIRED if category == "runtime" else RETIRED_IMPORT
        for index, line in enumerate(source.splitlines(), 1):
            match = matcher.search(line)
            if match:
                result.append((path, index, match.group(0)))
    return result


def read(path):
    return (ROOT / path).read_text(encoding="utf-8")


class D01d4NodeExportReferenceRegressionTest(unittest.TestCase):
    def test_tracked_runtime_build_and_test_import_references(self):
        paths = tracked_paths()
        sources = {p: read(p) for p in paths if scope(p)}
        runtime = [p for p in sources if scope(p) == "runtime"]
        test = [p for p in sources if scope(p) == "test"]
        for module in ("app", "business", "core", "gesture", "ocr-plugin", "ocr-plugin-api"):
            self.assertTrue(any(p.startswith(module + "/") and "/src/" in p for p in runtime), module)
        self.assertIn(".github/workflows/android-build.yml", runtime)
        self.assertIn("app/build.gradle.kts", runtime)
        self.assertEqual([], retired_violations(sources))
        print("D01-D4 reference inventory: runtime/config=%d test-source=%d tracked=%d" %
              (len(runtime), len(test), len(paths)))
        # Shared types are candidates only; report rather than prohibit future legitimate callers.
        for symbol in ("NodeCaptureSource", "NodeBoundsHighlightPlanner", "NodeTargetResolver", "NodeTreeIndex"):
            matches = [p for p, s in sources.items() if re.search(r"\b" + symbol + r"\b", s)]
            print("D01-D4 shared candidate %s: %s" % (symbol, ", ".join(matches) or "(none)"))

    def test_reference_gate_detects_wire_literals_config_and_test_imports(self):
        cases = {
            "app/src/main/assets/legacy.json": '{"extra":"com.freedom.extra.NODE_WINDOW_ID"}',
            "business/src/main/kotlin/Legacy.kt": 'val action = "com.freedom.ACTION_MANUAL_NODE_CAPTURE"',
            "app/debug-shrink-rules.pro": "-keep class com.freedom.ui.logs.NodeInspectorDialog",
            "core/src/test/kotlin/Legacy.kt": "import com.freedom.adapters.accessibility.diagnostics.LatestAccessibilityNodeDump",
            "app/src/debug/java/Legacy.kt": "fun call() = createBatchArchive()",
        }
        self.assertEqual(set(cases), {p for p, _, _ in retired_violations(cases)})
        self.assertEqual([], retired_violations({
            "app/src/test/java/Negative.kt": 'assertFalse(source.contains("LatestAccessibilityNodeDump"))',
            "docs/audits/Historical.md": "LatestAccessibilityNodeDump",
            "app/src/main/java/Current.kt": 'val action = "com.freedom.ACTION_SHOW_GLOBAL_NODE_LAYOUT_BOUNDS"',
        }))

    def test_three_scopes_share_single_verified_export_transaction(self):
        activity = read(APP + "MainActivity.kt")
        manager = read(APP + "ui/logs/SessionSampleArchiveManager.kt")
        diag = read(APP + "data/RunDiagnosticsManager.kt")
        self.assertEqual(1, activity.count("SessionSampleArchiveManager.createExportBundle("))
        self.assertEqual(1, activity.count("SessionSampleArchiveManager.cleanupAfterVerifiedExport("))
        self.assertEqual(1, manager.count("fun createExportBundle("))
        for call in ("beginSessionSampleExport(archives, includeAllDiagnostics = true, displayLogs = snapshot)",
                     "beginSessionSampleExport(emptyList(), includeAllDiagnostics = true, displayLogs = snapshot)",
                     "beginSessionSampleExport(archives)"):
            self.assertIn(call, activity)
        transaction = activity.split("private fun exportSessionSampleArchive(", 1)[1]
        order = [transaction.index(token) for token in ("output.use {", "contentResolver.openInputStream(destinationUri)",
                "SessionSampleArchiveManager.cleanupAfterVerifiedExport(", "clearExportedDisplayLogs(")]
        self.assertEqual(sorted(order), order)
        self.assertIn("原始数据保留", transaction)
        cleanup = manager.split("fun cleanupAfterVerifiedExport(", 1)[1].split("fun createExportBundle(", 1)[0]
        self.assertLess(cleanup.index("savedZip.use(::sha256) == receipt.zipSha256"), cleanup.index("source.file.delete()"))
        for token in ("fingerprint(source.file) != source.fingerprint", "sampleIsSealed(sampleRoot, source)",
                      "!unknownReference && source.runId !in excluded && source.runId !in referenced"):
            self.assertIn(token, cleanup)
        self.assertIn("copy(setOfNotNull(activeRun?.runId, finalizingRun?.runId))", diag)

    def test_retained_node_paths_have_distinct_owners_and_lifecycle(self):
        live = read(APP + "application/monitor/GlobalNodeLayoutVisualizationUseCase.kt")
        preview = read(APP + "application/monitor/AccessibilityTreePreviewUseCase.kt")
        scheduler = read(APP + "adapters/accessibility/android/AndroidMonitorPreviewScheduler.kt")
        service = read(APP + "accessibility/FreedomAccessibilityService.kt")
        history = read(APP + "adapters/social/momo/MomoChatSampleNodeTreeStore.kt")
        exporter = read(APP + "adapters/accessibility/android/NodeLayoutSnapshotExporter.kt")
        for token in ("gateway.inspectTargetWindows(", "textCapture = NodeTextCapture.EPHEMERAL",
                      "snapshot.window.packageName == expectedPackage",
                      "fun snapshots(): List<AccessibilityTreeSnapshot> = latestSnapshots"):
            self.assertIn(token, live)
        for token in ("if (!isRunning()) return", "gateway.inspectActiveWindow(request)",
                      "textCapture = NodeTextCapture.NONE", "snapshotRepository.save("):
            self.assertIn(token, preview)
        self.assertNotIn("inspectTargetWindows(", preview)
        self.assertIn("const val DEFAULT_DEBOUNCE_MS = 350L", scheduler)
        self.assertIn("if (isBrowseRunActive()) scanActiveWindow()", scheduler)
        for start, end in (("private val targetChangedReceiver", "private val refreshReceiver"),
                           ("private fun handleAccessibilityWindowTransition(", "private fun dp("),
                           ("override fun onInterrupt()", "override fun onDestroy()"),
                           ("override fun onDestroy()", None)):
            section = service.split(start, 1)[1]
            if end is not None:
                self.assertIn(end, section)
                section = section.split(end, 1)[0]
            self.assertIn("globalNodeLayoutVisualizationUseCase.clear()", section, start)
            self.assertIn("NodeHierarchyOverlay.clear()", section, start)
        destroy = service.split("override fun onDestroy()", 1)[1]
        self.assertIn("monitorPreviewScheduler.cancel()", destroy)
        self.assertIn("serviceReceiverRegistry.unregisterAll()", destroy)
        self.assertEqual(3, history.count("includeText = false"))
        self.assertIn('put("textCapture", "NONE")', exporter)
