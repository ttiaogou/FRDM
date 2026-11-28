"""R09-C: independent full-source authority boundary checks, supplementing P0-P7."""
import re
import unittest
from pathlib import Path
from tools.architecture.verify_boundaries import kotlin_sources, mask_kotlin_non_code

ROOT = Path(__file__).resolve().parents[2]
APP = "app/src/main/java/com/freedom/"
CORE = "core/src/main/kotlin/com/freedom/core/runtime/"
COMPOSITION = APP + "app/AppCompositionRoot.kt"
SERVICE = APP + "accessibility/FreedomAccessibilityService.kt"
SESSION = APP + "application/runtime/RunSessionCoordinator.kt"
FORWARDER = APP + "adapters/accessibility/android/AndroidAccessibilityEventForwarder.kt"
PIPELINE = APP + "adapters/accessibility/android/AndroidAccessibilityEventPipeline.kt"
TASK = CORE + "TaskCoordinator.kt"
UI = CORE + "UiOperationOwnershipCoordinator.kt"
WRITE = CORE + "UiWriteAuthorization.kt"
ENTRY = "core/src/main/kotlin/com/freedom/core/accessibility/api/UnifiedAccessibilityEntry.kt"
# Count actual symbol occurrences, not comments/literal strings. The session's own
# declarations count once; only the composition gateway is allowed to call them.
COUNTS = {
    "target dispatch": (r"\bdispatchTargetApplicationEvent\s*\(", {SESSION: 1, COMPOSITION: 1}),
    "touch dispatch": (r"\bdispatchGlobalTouchEvent\s*\(", {SESSION: 1, COMPOSITION: 1}),
    "activity dispatch": (r"\bdispatchActivityTransition\s*\(", {SESSION: 1, COMPOSITION: 1}),
    "task capability construction": (r"\bTaskLease\s*\(", {TASK: 1}),
    "UI capability construction": (r"\bUiOperationLease\s*\(", {UI: 1}),
    "task slot": (r"\bvar\s+activeLease\s*:\s*TaskLease\?", {TASK: 1}),
    "UI slot": (r"\bvar\s+activeLease\s*:\s*UiOperationLease\?", {UI: 1}),
    "page runtime slot": (r"\bvar\s+pageRuntime\s*:\s*PageRuntimeSessionState\?", {SESSION: 1}),
    "entry SAM construction/declaration": (r"\bUnifiedAccessibilityEntry\s*\{", {ENTRY: 1, COMPOSITION: 1}),
}
REQUIRED = {
    SERVICE: ("override fun onAccessibilityEvent(event: AccessibilityEvent?) {\n        accessibilityEventForwarder.accept(event)\n    }",
              "unifiedAccessibilityEntry.accept(",
              "UnifiedAccessibilityEvent.NavigationObservation("),
    FORWARDER: ("pipeline.processWithoutRuntime(event)", "pipeline.process(event, runMode)"),
    PIPELINE: ("private val unifiedAccessibilityEntry: UnifiedAccessibilityEntry",
               "unifiedAccessibilityEntry.accept(UnifiedAccessibilityEvent.GlobalTouch(routedTouchEvent))",
               "unifiedAccessibilityEntry.accept(UnifiedAccessibilityEvent.TargetApplication(normalizedEvent))",
               "unifiedAccessibilityEntry.accept(UnifiedAccessibilityEvent.TargetApplication(routedTargetEvent))",
               "unifiedAccessibilityEntry.accept(UnifiedAccessibilityEvent.ActivityTransition(className))"),
    COMPOSITION: ("TaskCoordinator(uiOperationOwnershipCoordinator)",
                  "generationClock = root.runGenerationClock",
                  "currentRunGeneration = root.runGenerationClock::current",
                  "activeRunMode = { runSessionCoordinator.currentWriteRoute()?.mode }",
                  "runSessionCoordinator.currentWriteRoute()?.takeUnless",
                  "aiReplyObservationRuntime.isReceivedGreetingsRecoveryPending()",
                  "aiReplyObservationRuntime.isObservedPrivateRecoveryPending()",
                  "aiReplyObservationRuntime.isPrivateFollowUpWriteBlocked()",
                  "runSessionCoordinator.admitBusinessPageTaskOwnership(lease, verifiedHandoff)",
                  "runSessionCoordinator.rollbackBusinessPageTaskOwnership(failedLease)",
                  "unifiedAccessibilityEntry = unifiedAccessibilityEntry"),
    SESSION: ("route.lifecycle != PageRuntimeLifecycle.READY",
              "route.runGeneration != generationClock.current()",
              "val lease = tasks.currentLease() ?: return null",
              "lease.taskId != route.taskId || lease.runGeneration != route.runGeneration",
              "withTaskBarrier { pageRuntime = route }",
              "tasks.withCurrentLease(lease)",
              "mode?.let { runtimes[it]?.onTargetApplicationEvent(event) }"),
    WRITE: ("val lease = expectedLease ?: return null",
            "return taskCoordinator.withCurrentLease(lease)",
            "currentRoute() != route) null else action()",
            "route.lifecycle == PageRuntimeLifecycle.READY",
            "route.taskId == lease.taskId",
            "route.runGeneration == lease.runGeneration"),
}
def authority_violations(sources):
    issues = []
    for label, (pattern, expected) in COUNTS.items():
        found = {path: count for path, source in sources.items()
                 if (count := len(re.findall(pattern, source)))}
        if found != expected:
            issues.append(f"{label}: expected {expected}, found {found}")
    for path, tokens in REQUIRED.items():
        for token in tokens:
            if token not in sources.get(path, ""):
                issues.append(f"missing live boundary: {path}: {token}")
    for path, source in sources.items():
        if path != COMPOSITION and re.search(
            r"::\s*dispatch(?:TargetApplicationEvent|GlobalTouchEvent|ActivityTransition)\b", source
        ):
            issues.append("event dispatch callable-reference bypass: " + path)
    if len(re.findall(r"\btaskCoordinator\s*=\s*root\.taskCoordinator\b",
                      sources.get(COMPOSITION, ""))) != 5:
        issues.append("five consumers must use shared root task coordinator")
    session = sources.get(SESSION, "")
    if len(re.findall(r"\bpageRuntime\s*=(?!=)", session)) != 2:
        issues.append("page route publication must remain confined to barrier and READY transition")
    return issues

class PpR09cAuthorityAcceptanceTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.sources = {p.relative_to(ROOT).as_posix(): mask_kotlin_non_code(p.read_text(encoding="utf-8"))
                       for p in kotlin_sources(ROOT)}

    def test_repository_has_only_reviewed_authority_paths(self):
        self.assertEqual([], authority_violations(self.sources))

    def test_extra_dispatch_and_capability_paths_are_rejected(self):
        for fragment in (
            "session.dispatchTargetApplicationEvent(event)",
            "val bypass = session::dispatchGlobalTouchEvent",
            "TaskLease(id, generation, ui, revision)",
            "UiOperationLease(id, generation, revision)",
            "val gateway = UnifiedAccessibilityEntry { event -> consume(event) }",
            "var pageRuntime: PageRuntimeSessionState? = null",
        ):
            changed = dict(self.sources)
            changed["business/src/main/kotlin/UnexpectedR09c.kt"] = fragment
            with self.subTest(fragment=fragment):
                self.assertTrue(authority_violations(changed))

    def test_removed_route_guard_and_unbarriered_publication_rejected(self):
        for path, token in (
            (SESSION, "withTaskBarrier { pageRuntime = route }"),
            (WRITE, "return taskCoordinator.withCurrentLease(lease)"),
            (COMPOSITION, "aiReplyObservationRuntime.isPrivateFollowUpWriteBlocked()"),
            (PIPELINE, "unifiedAccessibilityEntry.accept(UnifiedAccessibilityEvent.TargetApplication(routedTargetEvent))"),
        ):
            self.assertIn(token, self.sources[path])
            changed = dict(self.sources)
            changed[path] = changed[path].replace(token, "R09C_REMOVED_BOUNDARY")
            with self.subTest(token=token):
                self.assertTrue(authority_violations(changed))
