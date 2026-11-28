"""R06-G: integrated read-only evidence ownership and exact R06-A reconstruction."""
import re
import unittest
from pathlib import Path

from tools.architecture.test_pp_r00_freeze import git_blob
from tools.architecture.test_pp_r06_page_evidence_migration import restore_r06a_runtime

ROOT = Path(__file__).resolve().parents[2]
AI = "business/src/main/kotlin/com/freedom/features/aireply/"
RUNTIME = AI + "AiReplyObservationRuntime.kt"
PAGE_OWNER = AI + "MomoPageObservationCoordinator.kt"
PAGE_STATE = {"pageInspectionTask", "fullInspectionCallbackToken", "pendingPageReadPurpose",
              "regionRefreshTask", "lastPageInspection", "lastConfirmedChatListInspection", "lastRegionFingerprint"}
BASELINE = "1c0c3bc1669f0cdc56e3abbb36c2f8c0be064f8a"
OWNERS = {
    "AiReplyPageEvidence.kt": (
        "recoveryStructuralKey", "isMessageViewportPositionedAgainstTab",
        "hasRelevantMessageStructureDelta", "pageIdentityChanged", "hasSameWindowSession",
        "windowSessionKey", "selfNavigationMatchesInspection", "chatSurfaceKey",
    ),
    "PrivateMessageCandidateEvidence.kt": (
        "countEligiblePrivateRows", "countAffectedEligibleRows", "hasOnlyVerifiedEligiblePrivateRows",
        "formatUnreadAudit",
    ),
    "ReceivedGreetingsChatEvidence.kt": (
        "matchesReceivedGreetingsChatHeader", "collectReceivedGreetingsChatWindowEvidence",
        "verifyReceivedGreetingsChatWindowEvidence", "hasOffscreenGreetingChatLocators",
    ),
    "ChatMessageReadEvidence.kt": (
        "matchesMessageReadWindowEvidence", "matchesPrivateMessageReadPageEvidence",
        "sendConfirmationPageEvidenceRejectionReason", "sendConfirmationBatchEvidenceRejectionReason",
        "countMergedOcrTargets", "createOcrDebugFingerprint", "escapeOcrDebugText",
    ),
    "AiReplyPageDiagnostics.kt": (
        "summarize", "formatChatListSessionSummary", "formatNodeTreeProbeSummary", "formatNodeEvidence",
    ),
}
STATE = (
    "receivedGreetingsTaskFlow", "pageInspectionTask", "fullInspectionCallbackToken",
    "pendingPageReadPurpose", "regionRefreshTask", "lastPageInspection",
    "lastConfirmedChatListInspection", "lastRegionFingerprint", "lastMessageFingerprint",
    "loggedOcrDebugFingerprints", "contactMessageLedger", "ocrDebugBatchLimitLogged",
    "messageReadCoordinator", "recoveryContext", "privateMessagesTraversalTaskExecutor",
    "pageReadCoordinator", "greetingChatBinding", "currentSessionBinding", "activePrivateFollowUp",
)
FORBIDDEN = (
    "TaskCoordinator", "TaskLease", "TaskScheduler", "RecoveryContext", "ChatPagePort",
    "ChatMessagePort", "GestureApi", "AccessibilityNodeInfo", "android.accessibilityservice",
    "inspectChatPage(", "dispatchGesture(", "onLog(", "onDiagnosticLog(",
)
ENTRY = "app/src/main/java/com/freedom/accessibility/FreedomAccessibilityService.kt"
PIPELINE = "app/src/main/java/com/freedom/adapters/accessibility/android/AndroidAccessibilityEventPipeline.kt"
COMPOSITION = "app/src/main/java/com/freedom/app/AppCompositionRoot.kt"
SESSION = "app/src/main/java/com/freedom/application/runtime/RunSessionCoordinator.kt"
WIRING = {
    ENTRY: ("accessibilityEventForwarder.accept(event)",),
    PIPELINE: ("unifiedAccessibilityEntry.accept(UnifiedAccessibilityEvent.TargetApplication(normalizedEvent))",),
    COMPOSITION: ("runSessionCoordinator.dispatchTargetApplicationEvent(notification.event)",
                  "AiReplyObservationRuntime("),
    SESSION: ("mode?.let { runtimes[it]?.onTargetApplicationEvent(event) }",),
    RUNTIME: (
        "ChatMessageReadCoordinator(", "AiReplyPageReadCoordinator(",
        "inspectPage = { chatPagePort.inspectChatPage() }", "isResultCurrent = ::isMessageReadResultCurrent",
        "handleMessageReadBatch(batch, requestedSnapshot)",
        "if (batch.status == MessageReadStatus.WINDOW_STALE) return false",
        "matchesMessageReadWindowEvidence(snapshot, batch, active, expectedWindowSessionKey)",
        "privateMessagesTraversalTaskExecutor.ambiguousSessionIdentityKeys,",
        "privateMessagesTraversalTaskExecutor.attemptedSessionKeys",
        "verifyReceivedGreetingsChatWindowEvidence(", "greetingBindingRejectReason(active)",
        "summarize(inspection)", "logStructureEvidence(inspection)", ".map(::formatNodeEvidence)",
        "MAX_LOGGED_STRUCTURE_EVIDENCE = 256", "MAX_STRUCTURE_LOG_LINE_CHARS = 3_500",
    ),
}


def integration_violations(sources):
    issues = []
    runtime = sources.get(RUNTIME, "")
    helper_sources = {name: sources.get(AI + name, "") for name in OWNERS}
    for name, methods in OWNERS.items():
        source = helper_sources[name]
        for method in methods:
            declaration = r"\bfun\s+" + re.escape(method) + r"\s*\("
            if len(re.findall(declaration, source)) != 1:
                issues.append(name + ": missing or duplicate owner for " + method)
            if re.search(declaration, runtime) or any(
                re.search(declaration, other) for other_name, other in helper_sources.items()
                if other_name != name
            ):
                issues.append(name + ": duplicate owner outside helper for " + method)
        for token in FORBIDDEN:
            if token in source:
                issues.append(name + ": forbidden dependency " + token)
    for state in STATE:
        declaration = r"\b(?:val|var)\s+" + re.escape(state) + r"\b"
        if state in PAGE_STATE:
            owner = sources.get(PAGE_OWNER, "")
            if re.search(declaration, runtime) or len(re.findall(declaration, owner)) != 1:
                issues.append("Page observation state ownership changed: " + state)
        elif len(re.findall(r"\bprivate\s+(?:val|var)\s+" + re.escape(state) + r"\b", runtime)) != 1:
            issues.append("Runtime state declaration changed: " + state)
        if any(re.search(declaration, source) for source in helper_sources.values()):
            issues.append("Runtime state duplicated in helper: " + state)
    for path, tokens in WIRING.items():
        for token in tokens:
            if token not in sources.get(path, ""):
                issues.append(path + ": disconnected boundary " + token)
    return issues


class PpR06IntegrationAcceptanceTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        paths = set(WIRING) | {AI + name for name in OWNERS} | {PAGE_OWNER}
        cls.sources = {path: (ROOT / path).read_text(encoding="utf-8") for path in paths}

    def test_all_five_stages_reconstruct_frozen_r06a_runtime(self):
        restored = restore_r06a_runtime(self.sources[RUNTIME])
        self.assertEqual(BASELINE, git_blob(restored.encode("utf-8")))
        self.assertEqual(27, sum(len(methods) for methods in OWNERS.values()))

    def test_integrated_single_owners_state_and_entry_wiring(self):
        self.assertEqual([], integration_violations(self.sources))

    def test_duplicate_owner_state_or_disconnected_boundary_fails_closed(self):
        for path in self.sources:
            with self.subTest(missing=path):
                changed = dict(self.sources)
                del changed[path]
                self.assertTrue(integration_violations(changed))
        for path, fragment in (
            (RUNTIME, "\nprivate fun summarize() = \"duplicate\"\n"),
            (AI + "AiReplyPageEvidence.kt", "\nprivate var lastPageInspection = null\n"),
            (AI + "ChatMessageReadEvidence.kt", "\nprivate fun bypass() = dispatchGesture()\n"),
            (AI + "AiReplyPageDiagnostics.kt", "\nprivate fun write() = onLog(\"raw\")\n"),
        ):
            with self.subTest(path=path, fragment=fragment):
                changed = dict(self.sources)
                changed[path] += fragment
                self.assertTrue(integration_violations(changed))
        for path, token in (
            (ENTRY, "accessibilityEventForwarder.accept(event)"),
            (COMPOSITION, "runSessionCoordinator.dispatchTargetApplicationEvent(notification.event)"),
            (RUNTIME, "isResultCurrent = ::isMessageReadResultCurrent"),
            (RUNTIME, "if (batch.status == MessageReadStatus.WINDOW_STALE) return false"),
        ):
            with self.subTest(path=path, token=token):
                self.assertIn(token, self.sources[path])
                changed = dict(self.sources)
                changed[path] = changed[path].replace(token, "Unit", 1)
                self.assertTrue(integration_violations(changed))
