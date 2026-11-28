"""R07-E: exact shared read, OCR admission, visit and model retry state relocation."""
import re
import unittest
from pathlib import Path
from tools.architecture.test_pp_r00_freeze import git_blob

ROOT = Path(__file__).resolve().parents[2]
AI = ROOT / "business/src/main/kotlin/com/freedom/features/aireply/"
PREVIOUS = "f605c1eb88f1eb97929db49a87459e2bca9530f5"
OWNER = "8d00203bdb93c2e7cfb046ab8ebabf9002373058"
# Compile-only type inference repair; reverse exact annotations before historical inverse.
TYPE_ANNOTATION_REPAIR = [
  [
    "private val messageReadCoordinator: ChatMessageReadCoordinator by lazy(LazyThreadSafetyMode.NONE) {",
    "private val messageReadCoordinator by lazy(LazyThreadSafetyMode.NONE) {"
  ],
  [
    "private fun cancelMessageReadRetry(): Unit = messageReadCoordinator.cancelMessageReadRetry()",
    "private fun cancelMessageReadRetry() = messageReadCoordinator.cancelMessageReadRetry()"
  ],
  [
    "private fun invalidateMessageReadTransaction(): Unit = messageReadCoordinator.invalidateMessageReadTransaction()",
    "private fun invalidateMessageReadTransaction() = messageReadCoordinator.invalidateMessageReadTransaction()"
  ]
]
FIELDS = [
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
  "privateModelRetryAwaitingDraft"
]
INVERSE = [
  [
    "messageReadCoordinator.privateModelRetryAwaitingDraft",
    "privateModelRetryAwaitingDraft",
    7
  ],
  [
    "messageReadCoordinator.privateModelFailureRecovery",
    "privateModelFailureRecovery",
    11
  ],
  [
    "messageReadCoordinator.privateOcrRecoveryAttempt",
    "privateOcrRecoveryAttempt",
    4
  ],
  [
    "messageReadCoordinator.ocrAdmissionRetryGeneration",
    "ocrAdmissionRetryGeneration",
    2
  ],
  [
    "messageReadCoordinator.ocrAdmissionRetryAttempt",
    "ocrAdmissionRetryAttempt",
    2
  ],
  [
    "messageReadCoordinator.ocrAdmissionRetryTask",
    "ocrAdmissionRetryTask",
    6
  ],
  [
    "messageReadCoordinator.deferredPrivateReadReturnVisit",
    "deferredPrivateReadReturnVisit",
    5
  ],
  [
    "messageReadCoordinator.draftRetryTask",
    "draftRetryTask",
    5
  ],
  [
    "messageReadCoordinator.draftReadRetries",
    "draftReadRetries",
    5
  ],
  [
    "messageReadCoordinator.draftVisitToken",
    "draftVisitToken",
    23
  ],
  [
    "messageReadCoordinator.greetingModelFailureRecovery",
    "greetingModelFailureRecovery",
    10
  ],
  [
    "messageReadCoordinator.latestPrivateMessageBatch",
    "latestPrivateMessageBatch",
    4
  ],
  [
    "messageReadCoordinator.latestGreetingMessageBatch",
    "latestGreetingMessageBatch",
    3
  ],
  [
    "messageReadCoordinator.lastMessageFingerprint",
    "lastMessageFingerprint",
    6
  ],
  [
    "messageReadCoordinator.messageReadRetryCount",
    "messageReadRetryCount",
    2
  ],
  [
    "messageReadCoordinator.messageReadRetryTask",
    "messageReadRetryTask",
    3
  ],
  [
    "${privateOcrRecoveryAttempt}",
    "$privateOcrRecoveryAttempt",
    1
  ],
  [
    "${draftReadRetries}",
    "$draftReadRetries",
    1
  ],
  [
    "messageReadCoordinator.observeStableVisualReadFailure(batch, draftAdmission)",
    "observeStableVisualReadFailure(batch, draftAdmission)",
    1
  ],
  [
    "        messageReadCoordinator.clearStableVisualReadFailure()\n",
    "        stableVisualReadFailure = null\n",
    1
  ],
  [
    "        messageReadCoordinator.clearPrivateReadAndModelRetry()\n",
    "        draftReadRetries = 0\n        draftRetryTask?.cancel()\n        draftRetryTask = null\n        privateOcrRecoveryAttempt = 0\n        privateModelFailureRecoveryTask?.cancel()\n        privateModelFailureRecoveryTask = null\n        privateModelFailureRecovery = null\n        privateModelRetryAwaitingDraft = false\n",
    1
  ],
  [
    "        messageReadCoordinator.renewVisit()\n",
    "        draftVisitToken = java.util.UUID.randomUUID().toString()\n        stableVisualReadFailure = null\n        deferredPrivateReadReturnVisit = null\n",
    1
  ],
  [
    "        messageReadCoordinator.clearGreetingModelFailureRetry()\n",
    "        greetingModelFailureRecoveryTask?.cancel()\n        greetingModelFailureRecoveryTask = null\n        greetingModelFailureRecovery = null\n",
    1
  ],
  [
    "    private fun cancelMessageReadRetry() = messageReadCoordinator.cancelMessageReadRetry()\n\n",
    "    private fun cancelMessageReadRetry() {\n        messageReadRetryTask?.cancel()\n        messageReadRetryTask = null\n        messageReadRetryCount = 0\n    }\n\n",
    1
  ],
  [
    "    private fun invalidateMessageReadTransaction() = messageReadCoordinator.invalidateMessageReadTransaction()\n\n    private fun invalidatePendingMessageRead() {",
    "    private fun invalidateMessageReadTransaction() {\n        ocrAdmissionRetryGeneration++\n        ocrAdmissionRetryTask?.cancel()\n        ocrAdmissionRetryTask = null\n        ocrAdmissionRetryAttempt = 0\n        messageReadCoordinator.invalidatePending()\n    }\n\n    private var draftVisitToken = java.util.UUID.randomUUID().toString()\n    private var draftReadRetries = 0\n    private var draftRetryTask: ScheduledTask? = null\n    /** Private retry state never grants UI permission or counts as a send receipt. */\n    private var privateOcrRecoveryAttempt = 0\n    private var privateModelFailureRecovery: com.freedom.features.aireply.ai.AiReplyPreSendModelFailure? = null\n    private var privateModelFailureRecoveryTask: ScheduledTask? = null\n    private var privateModelRetryAwaitingDraft = false\n\n    private fun invalidatePendingMessageRead() {",
    1
  ],
  [
    "    /** A process worker may outlive",
    "    private var ocrAdmissionRetryTask: ScheduledTask? = null\n    private var ocrAdmissionRetryAttempt = 0\n    private var ocrAdmissionRetryGeneration = 0L\n\n    /** A process worker may outlive",
    1
  ],
  [
    "    private fun unreadableVisualReturnIdle()",
    "    private data class StableVisualReadFailure(\n        val visit: String, val key: List<String>, val observationId: String,\n        val capturedAtMillis: Long, val count: Int\n    )\n    private var stableVisualReadFailure: StableVisualReadFailure? = null\n    private var deferredPrivateReadReturnVisit: String? = null\n\n    private fun observeStableVisualReadFailure(\n        batch: ChatMessageBatch, admission: VisibleDraftContext.Admission\n    ): Boolean {\n        val key = VisibleDraftContext.stableVisualDeferralKey(batch, admission)\n        if (key == null) {\n            stableVisualReadFailure = null\n            return false\n        }\n        val old = stableVisualReadFailure\n        val same = old != null && old.visit == draftVisitToken && old.key == key\n        if (same && old != null && (old.observationId == batch.effectiveObservationBatchId ||\n                batch.capturedAtMillis <= old.capturedAtMillis)) return false\n        val count = if (same && old != null) (old.count + 1).coerceAtMost(2) else 1\n        stableVisualReadFailure = StableVisualReadFailure(draftVisitToken, key,\n            batch.effectiveObservationBatchId, batch.capturedAtMillis, count)\n        return count >= 2\n    }\n\n    private fun unreadableVisualReturnIdle()",
    1
  ],
  [
    "    /**\n     * A model-only failure",
    "    /** Latest verified greeting-detail message observation used to build a pre-send diff baseline. */\n    private var latestGreetingMessageBatch: ChatMessageBatch? = null\n    /** Latest verified private-detail message observation used to build a pre-send diff baseline. */\n    private var latestPrivateMessageBatch: ChatMessageBatch? = null\n\n    private var greetingModelFailureRecovery:\n        com.freedom.features.aireply.ai.AiReplyPreSendModelFailure? = null\n    private var greetingModelFailureRecoveryTask: ScheduledTask? = null\n\n\n    /**\n     * A model-only failure",
    1
  ],
  [
    "    private val loggedOcrDebugFingerprints",
    "    private var lastMessageFingerprint = \"\"\n    private val loggedOcrDebugFingerprints",
    1
  ],
  [
    "    private var lastRecoveryAndroidReadinessWait:",
    "    private var messageReadRetryTask: ScheduledTask? = null\n    private var messageReadRetryCount = 0\n    private var lastRecoveryAndroidReadinessWait:",
    1
  ]
]

def restore_r07d_runtime(runtime, owner=None):
    if owner is None:
        owner = (AI / "ChatMessageReadCoordinator.kt").read_text(encoding="utf-8")
    if git_blob(owner.encode("utf-8")) != OWNER:
        raise AssertionError("R07-E message read lifecycle owner changed")
    for current, previous in TYPE_ANNOTATION_REPAIR:
        if runtime.count(current) != 1:
            raise AssertionError("R07-E explicit type contract changed: " + current)
        runtime = runtime.replace(current, previous, 1)
    for current, previous, count in INVERSE:
        if runtime.count(current) != count:
            raise AssertionError("R07-E inverse anchor changed: " + current[:100])
        runtime = runtime.replace(current, previous)
    if git_blob(runtime.encode("utf-8")) != PREVIOUS:
        raise AssertionError("R07-E did not reconstruct exact R07-D Runtime")
    return runtime

class PpR07MessageReadLifecycleMigrationTest(unittest.TestCase):
    def test_exact_inverse_and_unique_state_owner(self):
        runtime = (AI / "AiReplyObservationRuntime.kt").read_text(encoding="utf-8")
        owner = (AI / "ChatMessageReadCoordinator.kt").read_text(encoding="utf-8")
        self.assertEqual(PREVIOUS, git_blob(restore_r07d_runtime(runtime, owner).encode("utf-8")))
        for state in FIELDS:
            declaration = r"\bvar\s+" + re.escape(state) + r"\b"
            self.assertEqual(1, len(re.findall(declaration, owner)), state)
            self.assertNotRegex(runtime, declaration)
            self.assertNotRegex(runtime, r"(?<!\.)\b" + re.escape(state) + r"\b")
        for method in ("clearGreetingModelFailureRetry", "renewVisit", "clearPrivateReadAndModelRetry",
                       "clearStableVisualReadFailure", "observeStableVisualReadFailure",
                       "invalidateMessageReadTransaction", "cancelMessageReadRetry"):
            self.assertIn("messageReadCoordinator." + method + "(", runtime)
            self.assertEqual(1, owner.count("fun " + method + "("))
        self.assertIn("visit != messageReadCoordinator.draftVisitToken", runtime)
        self.assertIn("messageReadCoordinator.greetingModelFailureRecovery !== failure", runtime)
        self.assertIn("messageReadCoordinator.privateModelFailureRecovery !== failure", runtime)
        self.assertIn("expectedGeneration != requestGeneration || !requestInFlight", owner)
        self.assertIn("messagePort.invalidateOcrRecognitionReuse()", owner)
        for current, previous in TYPE_ANNOTATION_REPAIR:
            self.assertEqual(1, runtime.count(current))
            self.assertNotIn(previous, runtime)
            with self.subTest(annotation=current), self.assertRaises(AssertionError):
                restore_r07d_runtime(runtime.replace(current, previous, 1), owner)

    def test_mutations_reject_stale_read_or_visit_and_retry_loss(self):
        runtime = (AI / "AiReplyObservationRuntime.kt").read_text(encoding="utf-8")
        owner = (AI / "ChatMessageReadCoordinator.kt").read_text(encoding="utf-8")
        for old, new in (
            ("draftVisitToken = java.util.UUID.randomUUID().toString()", 'draftVisitToken = "fixed"'),
            ("ocrAdmissionRetryGeneration++", "Unit"),
            ("privateModelFailureRecoveryTask?.cancel()", "Unit"),
            ("expectedGeneration != requestGeneration || !requestInFlight", "false"),
        ):
            self.assertIn(old, owner)
            with self.subTest(old=old), self.assertRaises(AssertionError):
                restore_r07d_runtime(runtime, owner.replace(old, new, 1))
        for old, new in (
            ("messageReadCoordinator.renewVisit()", "Unit"),
            ("messageReadCoordinator.clearPrivateReadAndModelRetry()", "Unit"),
            ("visit != messageReadCoordinator.draftVisitToken", "false"),
            ("messageReadCoordinator.greetingModelFailureRecovery !== failure", "false"),
            ("messageReadCoordinator.privateModelFailureRecovery !== failure", "false"),
        ):
            self.assertIn(old, runtime)
            with self.subTest(old=old), self.assertRaises(AssertionError):
                restore_r07d_runtime(runtime.replace(old, new, 1), owner)
