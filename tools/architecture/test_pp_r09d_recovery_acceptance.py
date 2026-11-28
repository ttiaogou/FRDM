"""R09-D supplemental source evidence. Runtime behavior remains tested by JVM suites."""
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
REQUIRED = {
  "business/src/main/kotlin/com/freedom/features/conversation/domain/contact/ContactHistoryResolver.kt": [
    "it.contactId != verifiedIdentity.contactId",
    "clear()\n                    addLog(\"AI 草稿历史读取：预加载联系人已变化；丢弃旧历史并重新查询\")",
    "loadHistory(verifiedIdentity, chatTitle)"
  ],
  "business/src/main/kotlin/com/freedom/features/recovery/RecoveryScheduler.kt": [
    "current !== schedule || current.callbackToken != token",
    "current.callbackToken++",
    "it.checkpointId == identity.checkpointId",
    "current.matchesTransaction(identity)"
  ],
  "business/src/main/kotlin/com/freedom/features/recovery/UnifiedRecoveryTransactionManager.kt": [
    "identity.runGeneration != generation",
    "identity.observationGeneration < old.observationGeneration",
    "old.checkpointId == identity.checkpointId"
  ],
  "business/src/main/kotlin/com/freedom/features/recovery/RecoveryCheckpointVerificationCoordinator.kt": [
    "evidence.observationGeneration <= lastObservationGeneration",
    "evidence.runGeneration == checkpoint.runGeneration",
    "evidence.targetIdentityFingerprint == checkpoint.targetIdentityFingerprint",
    "RecoveryCheckpointVerificationResult.ResumeReady(activeCheckpoint.executionPhase)",
    "scheduler.completeRecovery(identity)"
  ],
  "business/src/main/kotlin/com/freedom/features/aireply/ChatMessageReadCoordinator.kt": [
    "expectedGeneration != requestGeneration || !requestInFlight",
    "requestGeneration += 1L",
    "messagePort.invalidateOcrRecognitionReuse()",
    "isResultCurrent(requestedSnapshot, batch, activeSnapshot)"
  ],
  "business/src/main/kotlin/com/freedom/features/aireply/AiReplyObservationRuntime.kt": [
    "ocrVisit != messageReadCoordinator.draftVisitToken",
    "ocrRun != currentRunGeneration()",
    "!isMessageReadResultCurrent(ocrSnapshot, ocrBatch, fresh)",
    "greetingBindingRejectReason(fresh, ocrGreetingBinding)",
    "taskCoordinator.withCurrentLease(lease, invokeDraft)",
    "messageReadCoordinator.renewVisit()",
    "invalidateMessageReadTransaction()"
  ],
  "business/src/main/kotlin/com/freedom/features/aireply/ai/AiReplyDraftCoordinator.kt": [
    "!isCurrentGeneration(requestGeneration) || !isAiReplyActive()",
    "taskLease = captureTaskLease()",
    "AI_REPLY_STATE_CHANGED_BEFORE_INPUT",
    "SEND_PRECHECKPOINT_NOT_DURABLE",
    "ReplyTaskStatus.SENT_UNCONFIRMED"
  ],
  "business/src/main/kotlin/com/freedom/features/accountbrowse/DynamicPageRecovery.kt": [
    "checkpoint.runGeneration != currentRunGeneration()",
    "plan.runGeneration == currentRunGeneration()",
    "checkpointDispatcher.reset()",
    "requireNotNull(checkpointVerifier).completeResume()"
  ],
  "business/src/main/kotlin/com/freedom/features/aireply/PrivateMessagesTraversalTaskExecutor.kt": [
    "resumeSessionKey = checkpoint.resumeSessionKey",
    "checkpointNextCursorKey = checkpoint.nextCursorKey",
    "checkpointTraversalDirection = checkpoint.traversalDirection"
  ],
  "business/src/main/kotlin/com/freedom/features/aireply/MessageListCheckpointVerification.kt": [
    "observationGeneration <= lastObservationGeneration",
    "runGeneration = currentRunGeneration()",
    "schedule?.suspendForServiceDisconnect()"
  ],
  "business/src/main/kotlin/com/freedom/features/aireply/ReceivedGreetingsCheckpointVerification.kt": [
    "observationGeneration <= lastObservationGeneration",
    "runGeneration = currentRunGeneration()",
    "schedule?.suspendForServiceDisconnect()"
  ]
}

def violations(sources):
    return [path + ": " + token for path, tokens in REQUIRED.items()
            for token in tokens if token not in sources.get(path, "")]

class PpR09dRecoveryAcceptanceTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.sources = {path: (ROOT / path).read_text(encoding="utf-8") for path in REQUIRED}

    def test_recovery_checkpoint_and_async_admission_edges(self):
        self.assertEqual([], violations(self.sources))

    def test_missing_recovery_or_callback_gate_is_detected(self):
        for path, tokens in REQUIRED.items():
            for token in tokens:
                changed = dict(self.sources)
                changed[path] = changed[path].replace(token, "R09D_REMOVED_GATE")
                with self.subTest(path=path, token=token):
                    self.assertIn(path + ": " + token, violations(changed))
