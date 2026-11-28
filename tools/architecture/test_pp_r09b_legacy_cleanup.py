"""R09-B: remove only the always-null legacy region handle; retain exact history."""
import hashlib
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
AI = "business/src/main/kotlin/com/freedom/features/aireply/"
RUNTIME = AI + "AiReplyObservationRuntime.kt"
OWNER = AI + "MomoPageObservationCoordinator.kt"
# Fixed original line positions and contents. Reinsert only the reviewed removals.
RUNTIME_REMOVALS = [[3240,"        pageObservationCoordinator.clearLegacyRegionRefreshHandle()\n"],[3350,"        pageObservationCoordinator.cancelLegacyRegionRefreshHandle()\n"],[3361,"        pageObservationCoordinator.clearLegacyRegionRefreshHandle()\n"],[4675,"            pageObservationCoordinator.cancelLegacyRegionRefreshHandle()\n"],[4676,"            pageObservationCoordinator.clearLegacyRegionRefreshHandle()\n"],[4691,"            pageObservationCoordinator.cancelLegacyRegionRefreshHandle()\n"],[4692,"            pageObservationCoordinator.clearLegacyRegionRefreshHandle()\n"],[4721,"            pageObservationCoordinator.cancelLegacyRegionRefreshHandle()\n"],[5072,"        pageObservationCoordinator.cancelLegacyRegionRefreshHandle()\n"],[5073,"        pageObservationCoordinator.clearLegacyRegionRefreshHandle()\n"]]
OWNER_REMOVALS = [[33,"    private var regionRefreshTask: ScheduledTask? = null\n"],[69,"    // R07-A flagged this unassigned legacy handle; retain it pending authorized cleanup.\n"],[70,"    fun cancelLegacyRegionRefreshHandle() { regionRefreshTask?.cancel() }\n"],[72,"    fun clearLegacyRegionRefreshHandle() { regionRefreshTask = null }\n"]]
RETIRED = re.compile(r"\b(?:regionRefreshTask|cancelLegacyRegionRefreshHandle|clearLegacyRegionRefreshHandle)\b")
MODULES = ("app", "business", "core", "gesture/gesture-api", "gesture/gesture-core",
           "gesture/gesture-runtime", "gesture/gesture-android", "ocr-plugin-api", "ocr-plugin")

def blob(source):
    data = source.encode("utf-8")
    return hashlib.sha1(("blob %d\0" % len(data)).encode("ascii") + data).hexdigest()

def restore_exact(source, removals, previous):
    lines = source.splitlines(keepends=True)
    for index, line in removals:
        lines.insert(index, line)
    restored = "".join(lines)
    if blob(restored) != previous:
        raise AssertionError("R09-B exact historical reconstruction failed")
    return restored

def restore_r09a_runtime(source):
    return restore_exact(source, RUNTIME_REMOVALS, "7f5d99bcfccf80e2404880fcdfd5341c73a19014")

def restore_r09a_page_owner(source):
    return restore_exact(source, OWNER_REMOVALS, "26ee9be133eb554f0b08bd000dd055f0164a95b8")

class PpR09bLegacyCleanupTest(unittest.TestCase):
    def test_only_exact_legacy_deletions(self):
        runtime = (ROOT / RUNTIME).read_text(encoding="utf-8")
        owner = (ROOT / OWNER).read_text(encoding="utf-8")
        self.assertEqual("6f48cd1414f94402bc5332ab81895ea565865eac", blob(runtime))
        self.assertEqual("d6e9a30f6c4094172d271dc066cf158e0d201cb0", blob(owner))
        self.assertEqual("7f5d99bcfccf80e2404880fcdfd5341c73a19014", blob(restore_r09a_runtime(runtime)))
        self.assertEqual("26ee9be133eb554f0b08bd000dd055f0164a95b8", blob(restore_r09a_page_owner(owner)))
        self.assertNotRegex(runtime, RETIRED)
        self.assertNotRegex(owner, RETIRED)
        self.assertIn("regionRefreshChannel.requestSnapshot()", owner)
        self.assertIn("regionRefreshChannel.cancel()", owner)
        self.assertIn("token != fullInspectionCallbackToken || pageInspectionTask == null", owner)

    def test_no_retired_symbol_in_production_tests_or_resource_references(self):
        issues = []
        for module in MODULES:
            directory = ROOT / module
            candidates = list((directory / "src").rglob("*"))
            candidates += list(directory.glob("*.pro"))
            candidates += list(directory.glob("*.gradle.kts"))
            for path in candidates:
                if path.is_file() and path.suffix in {".kt", ".java", ".xml", ".aidl", ".pro", ".kts", ".json", ".properties"}:
                    if RETIRED.search(path.read_text(encoding="utf-8")):
                        issues.append(str(path.relative_to(ROOT)))
        self.assertEqual([], issues)

    def test_mutations_reject_live_channel_or_callback_regression(self):
        runtime = (ROOT / RUNTIME).read_text(encoding="utf-8")
        owner = (ROOT / OWNER).read_text(encoding="utf-8")
        for old, new in (("regionRefreshChannel.cancel()", "Unit"),
                         ("regionRefreshChannel.requestSnapshot()", "Unit"),
                         ("token != fullInspectionCallbackToken || pageInspectionTask == null", "false")):
            self.assertIn(old, owner)
            with self.subTest(old=old), self.assertRaises(AssertionError):
                restore_r09a_page_owner(owner.replace(old, new, 1))
        with self.assertRaises(AssertionError):
            restore_r09a_runtime(runtime + "\nprivate var regionRefreshTask = null\n")
