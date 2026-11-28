"""R07-I: integrated B-H ownership, reconstruction and cross-owner lifetime evidence."""
import re
import unittest
from pathlib import Path

from tools.architecture.test_pp_r00_freeze import git_blob
from tools.architecture.test_pp_r07_page_observation_migration import restore_r06f_runtime

ROOT = Path(__file__).resolve().parents[2]
AI = "business/src/main/kotlin/com/freedom/features/aireply/"
RUNTIME = "AiReplyObservationRuntime.kt"
ADAPTER = "app/src/main/java/com/freedom/adapters/runtime/android/AiReplyModeRuntimeAdapter.kt"
R07_A_RUNTIME = "ee67d98af55bdcba4270128132db9b5317f6c942"
FIELDS = {
  "MomoPageObservationCoordinator.kt": [
    "pageInspectionTask",
    "fullInspectionCallbackToken",
    "pendingPageReadPurpose",
    "regionRefreshTask",
    "lastPageInspection",
    "lastConfirmedChatListInspection",
    "lastRegionFingerprint"
  ],
  "PrivateMessagesTraversalTaskExecutor.kt": [
    "currentSessionBinding",
    "expectedPrivateSessionDetailNavigation",
    "incompleteUnreadRelocationWait",
    "identityCaptureSessionKey",
    "identityCaptureStartedAtMs",
    "identityCaptureRetryCount",
    "identityCaptureTerminalSessionKey",
    "identityCaptureTimeoutTask",
    "missedPrivateSessionTapKey",
    "missedPrivateSessionTapCount",
    "confirmedPrivateReturnBackRetryCount",
    "confirmedPrivateReturnAwaitingRecovery",
    "sessionSafeViewportRetryTask",
    "sessionSafeViewportRetryCount",
    "sessionSafeViewportRetryGeneration",
    "activePrivateFollowUp",
    "currentSessionSendConfirmed",
    "confirmedPrivateCompletionReceipt"
  ],
  "ReceivedGreetingsTaskFlowController.kt": [
    "greetingChatBinding",
    "confirmedGreetingSend"
  ],
  "ChatMessageReadCoordinator.kt": [
    "messageReadRetryTask",
    "messageReadRetryCount",
    "lastMessageFingerprint",
    "latestGreetingMessageBatch",
    "latestPrivateMessageBatch",
    "greetingModelFailureRecovery",
    "greetingModelFailureRecoveryTask",
    "draftVisitToken",
    "draftReadRetries",
    "draftRetryTask",
    "stableVisualReadFailure",
    "deferredPrivateReadReturnVisit",
    "ocrAdmissionRetryTask",
    "ocrAdmissionRetryAttempt",
    "ocrAdmissionRetryGeneration",
    "privateOcrRecoveryAttempt",
    "privateModelFailureRecovery",
    "privateModelFailureRecoveryTask",
    "privateModelRetryAwaitingDraft",
    "deferredSendProofRetryAttempt"
  ],
  "AiReplyTaskOwnership.kt": [
    "privateToGreetingsHandoff",
    "privateSliceQuota",
    "greetingSliceQuota",
    "pendingPrivateQuotaReceipt",
    "privateQuotaReturnReady",
    "pairWake",
    "privateRelocationRetryOwed",
    "crossTaskGreetingsHandoffRetryGate",
    "crossTaskGreetingsHandoffRetryTask"
  ],
  "AiReplySessionTraversalCoordinator.kt": [
    "snapshotRecoveryStartedAtMs"
  ],
  "PageRecoveryVerificationGate.kt": [
    "lastRecoveryAndroidReadinessWait",
    "receivedGreetingsRecoveryVerificationPending",
    "receivedGreetingsRecoveryVerifiedForNextSnapshot",
    "messageListRecoveryVerificationPending"
  ],
  "MessageListCheckpointVerification.kt": [
    "messageRecoveryRetryAttempt"
  ],
  "ReceivedGreetingsCheckpointVerification.kt": [
    "receivedGreetingsRecoveryRetryAttempt"
  ]
}
RUNTIME_ORDER = {
  "reset": [
    "sessionActionGeneration += 1L",
    "pageObservationCoordinator.cancelExplicitInspectionHandle()",
    "taskOwnership.resetCrossTaskGreetingsHandoffRetry()",
    "sessionTransitionTask?.cancel()",
    "recoveryTransaction.reset()",
    "messageListCheckpointVerifier.reset()",
    "receivedGreetingsCheckpointVerifier.reset()",
    "if (!preservePendingRecovery || !recoveryContext.pending) recoveryContext.clear()",
    "invalidatePendingMessageRead()",
    "resetPairWake()",
    "taskOwnership.resetPairedRun()",
    "sessionManagerState = SessionManagerState.STOPPED",
    "taskOwnership.reset(",
    "privateMessagesTraversalTaskExecutor.reset()",
    "receivedGreetingsTaskFlow.reset()"
  ],
  "invalidatePendingMessageRead": [
    "messageReadCoordinator.clearGreetingModelFailureRetry()",
    "messageReadCoordinator.renewVisit()",
    "receivedGreetingsTaskFlow.clearGreetingVisit()",
    "messageReadCoordinator.clearPrivateReadAndModelRetry()",
    "invalidateMessageReadTransaction()",
    "cancelMessageReadRetry()"
  ]
}
ADAPTER_ORDER = {
  "onAccessibilityServiceConnected": [
    "runtime().scheduleInitialObservation()",
    "runtime().reloadPrivateMessagesCheckpointAfterServiceReconnect()",
    "runtime().reloadReceivedGreetingsCheckpointAfterServiceReconnect()"
  ],
  "onAccessibilityServiceDisconnected": [
    "activeRuntime.persistCheckpointBeforeServiceInterruption(\"SERVICE_DISCONNECTED\")",
    "activeRuntime.reset("
  ],
  "onAccessibilityServiceInterrupted": [
    "activeRuntime.persistCheckpointBeforeServiceInterruption(\"SERVICE_INTERRUPTED\")",
    "activeRuntime.reset("
  ],
  "onServiceDestroyed": [
    "activeRuntime.persistCheckpointBeforeServiceInterruption(\"SERVICE_DESTROYED\")",
    "activeRuntime.reset("
  ],
  "onRunStopped": [
    "activeRuntime.reset(",
    "finally",
    "activeRuntime.clearRecoverySchedulesAfterRunStop()"
  ]
}
WIRING = [
  "sharedPageObservationCoordinator.registerRecoveryAdapter(messageListRecoveryRoute)",
  "sharedPageObservationCoordinator.registerRecoveryAdapter(receivedGreetingsRecoveryRoute)",
  "sharedPageObservationCoordinator.registerRecoveryAdapter(chatDetailRecoveryRoute)",
  "sharedManager = recoveryTransactionManager",
  "recoveryContext.recordPauseState(value == SessionManagerState.PAUSED)",
  "pageObservationCoordinator.scheduleExplicitFullInspection(delayMs, purpose, ::runFullInspection)",
  "messageListCheckpointVerifier.hasRetainedObservationPlan()",
  "taskOwnership.markPrivateQuotaReturnReady(sendConfirmationId)",
  "receivedGreetingsTaskFlow.retainConfirmedGreetingSend(",
  "privateMessagesTraversalTaskExecutor.beginPrivateFollowUpBinding(",
  "messageReadCoordinator.draftVisitToken",
  "recoveryContext.acknowledgeVerifiedCheckpoint(targetRoute, sharedProof)"
]

def ownership_violations(sources):
    issues = []
    for owner, fields in FIELDS.items():
        for field in fields:
            declaration = r"\b(?:var|val)\s+" + re.escape(field) + r"\b"
            locations = [
                name for name, source in sources.items()
                for _ in re.finditer(declaration, source)
            ]
            if locations != [owner]:
                issues.append(f"{field}: expected one declaration in {owner}, found {locations}")
    return issues

def method_body(source, name):
    # The checked methods are existing class-level block-bodied functions.
    lines = source.splitlines()
    pattern = r"^    (?:(?:private|override|internal) )?fun " + re.escape(name) + r"\("
    start = next((i for i, line in enumerate(lines) if re.match(pattern, line)), -1)
    if start < 0:
        return ""
    end = next((i for i in range(start + 1, len(lines)) if lines[i] == "    }"), -1)
    return "\n".join(lines[start:end + 1]) if end >= 0 else ""

def lifecycle_violations(runtime, adapter):
    issues = []
    for token in WIRING:
        if token not in runtime:
            issues.append("missing live wiring: " + token)
    for source, sequences in ((runtime, RUNTIME_ORDER), (adapter, ADAPTER_ORDER)):
        for name, tokens in sequences.items():
            body = method_body(source, name)
            cursor = -1
            for token in tokens:
                cursor = body.find(token, cursor + 1)
                if cursor < 0:
                    issues.append(name + ": missing ordered boundary " + token)
                    break
    if "override fun onTargetApplicationEvent(event: AccessibilityEventEnvelope) = runtime().schedule(event)" not in adapter:
        issues.append("mode adapter entry disconnected")
    return issues

class PpR07IntegrationAcceptanceTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.sources = {name: (ROOT / AI / name).read_text(encoding="utf-8")
                       for name in (RUNTIME, *FIELDS)}
        cls.adapter = (ROOT / ADAPTER).read_text(encoding="utf-8")

    def test_all_seven_stages_reconstruct_r07a_runtime(self):
        # The existing B inverse invokes C, which invokes D, E, F, G and H.
        restored = restore_r06f_runtime(self.sources[RUNTIME])
        self.assertEqual(R07_A_RUNTIME, git_blob(restored.encode("utf-8")))

    def test_integrated_63_migrated_declarations_have_nine_unique_owners(self):
        self.assertEqual(9, len(FIELDS))
        self.assertEqual(63, sum(len(fields) for fields in FIELDS.values()))
        self.assertEqual([], ownership_violations(self.sources))

    def test_cross_owner_reset_reconnect_and_live_wiring(self):
        self.assertEqual([], lifecycle_violations(self.sources[RUNTIME], self.adapter))

    def test_cross_owner_duplicate_missing_and_lifetime_mutations_rejected(self):
        for owner, fields in FIELDS.items():
            field = fields[0]
            changed = dict(self.sources)
            changed[RUNTIME] += "\nprivate var " + field + " = null\n"
            self.assertTrue(ownership_violations(changed), field)
            changed = dict(self.sources)
            del changed[owner]
            self.assertTrue(ownership_violations(changed), owner)
        for token in ("recoveryContext.recordPauseState(value == SessionManagerState.PAUSED)",
                      "taskOwnership.markPrivateQuotaReturnReady(sendConfirmationId)",
                      "if (!preservePendingRecovery || !recoveryContext.pending) recoveryContext.clear()",
                      "taskOwnership.resetPairedRun()"):
            self.assertIn(token, self.sources[RUNTIME])
            # Replacing every occurrence avoids accepting another branch's unrelated call.
            self.assertTrue(lifecycle_violations(self.sources[RUNTIME].replace(token, "Unit"), self.adapter), token)
        for token in ("runtime().scheduleInitialObservation()",
                      "activeRuntime.persistCheckpointBeforeServiceInterruption(\"SERVICE_DISCONNECTED\")",
                      "activeRuntime.clearRecoverySchedulesAfterRunStop()"):
            self.assertIn(token, self.adapter)
            self.assertTrue(lifecycle_violations(self.sources[RUNTIME], self.adapter.replace(token, "Unit")), token)
