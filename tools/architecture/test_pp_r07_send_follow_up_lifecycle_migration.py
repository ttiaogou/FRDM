"""R07-F: exact private send receipt/child binding and shared send-proof retry relocation."""
import re
import unittest
from pathlib import Path
from tools.architecture.test_pp_r00_freeze import git_blob

ROOT = Path(__file__).resolve().parents[2]
AI = ROOT / "business/src/main/kotlin/com/freedom/features/aireply/"
PREVIOUS = "ef2c4f6218f4ccfde40a1ebcef26ee5860562b06"
PRIVATE_PREVIOUS = "04f11c0f5b1ac69891478a053d6b05f60bb38d6b"
READ_PREVIOUS = "8d00203bdb93c2e7cfb046ab8ebabf9002373058"
PRIVATE_OWNER = "1d12a483673719255862b5c512c8db9fd9219fad"
READ_OWNER = "0f8ba84a23f83d0f62c7a757d82183c5a8b88fa7"
INVERSE = [
  [
    "messageReadCoordinator.deferredSendProofRetryAttempt",
    "deferredSendProofRetryAttempt",
    6
  ],
  [
    "privateMessagesTraversalTaskExecutor.currentSessionSendConfirmed",
    "currentSessionSendConfirmed",
    35
  ],
  [
    "privateMessagesTraversalTaskExecutor.confirmedPrivateCompletionReceipt",
    "confirmedPrivateCompletionReceipt",
    2
  ],
  [
    "privateMessagesTraversalTaskExecutor.activePrivateFollowUp",
    "activePrivateFollowUp",
    7
  ],
  [
    "privateMessagesTraversalTaskExecutor.retainPrivateCompletionReceipt(sendConfirmationId)",
    "confirmedPrivateCompletionReceipt = sendConfirmationId",
    1
  ],
  [
    "privateMessagesTraversalTaskExecutor.clearPrivateFollowUpBinding()",
    "activePrivateFollowUp = null",
    5
  ],
  [
    "        val child = privateMessagesTraversalTaskExecutor.beginPrivateFollowUpBinding(contactKey, sendId, target, checkpoint)",
    "        val child = PrivateFollowUp(contactKey, sendId, target, checkpoint)\n        activePrivateFollowUp = child",
    1
  ],
  [
    "    private var messageListRecoveryVerificationPending",
    "    /** Read-only send-proof admission failures use the existing unbounded recovery backoff. */\n    private var deferredSendProofRetryAttempt = 0\n    private var messageListRecoveryVerificationPending",
    1
  ],
  [
    "    /** Owns asynchronous OCR/message callback lifetime;",
    "    // Run-local receipt for the current confirmed visit only. Durable send/follow-up\n    // facts remain in their existing journals and ledger.\n    private var confirmedPrivateCompletionReceipt: String? = null\n    private var currentSessionSendConfirmed = false\n        set(value) {\n            field = value\n            if (!value) confirmedPrivateCompletionReceipt = null\n        }\n\n    /** Owns asynchronous OCR/message callback lifetime;",
    1
  ],
  [
    "    fun isPrivateFollowUpActive()",
    "    // Ephemeral binding for the existing adapter execution; the ledger remains the durable owner.\n    private class PrivateFollowUp(\n        val contactKey: String,\n        val sendConfirmationId: String?,\n        val target: ChatDraftInputTarget,\n        @Volatile var checkpoint: ContactFollowUpCheckpoint\n    ) {\n        var observationCount = 0L\n        var unconfirmedReported = false\n    }\n    @Volatile private var activePrivateFollowUp: PrivateFollowUp? = null\n    fun isPrivateFollowUpActive()",
    1
  ]
]
PRIVATE_INVERSE = [
  [
    "    var currentSessionWindowKey: String? = null\n    // R07-F: run-local completion and child binding; durable facts remain in the ledger.\n    // Run-local receipt for the current confirmed visit only. Durable send/follow-up\n    // facts remain in their existing journals and ledger.\n    var confirmedPrivateCompletionReceipt: String? = null\n        private set\n    var currentSessionSendConfirmed = false\n        set(value) {\n            field = value\n            if (!value) confirmedPrivateCompletionReceipt = null\n        }\n\n    @Volatile var activePrivateFollowUp: PrivateFollowUp? = null\n        private set\n\n    fun beginPrivateFollowUpBinding(\n        contactKey: String, sendId: String?, target: ChatDraftInputTarget, checkpoint: ContactFollowUpCheckpoint\n    ): PrivateFollowUp {\n        val child = PrivateFollowUp(contactKey, sendId, target, checkpoint)\n        activePrivateFollowUp = child\n        return child\n    }\n\n    fun clearPrivateFollowUpBinding() {\n        activePrivateFollowUp = null\n    }\n\n    fun retainPrivateCompletionReceipt(sendConfirmationId: String) {\n        confirmedPrivateCompletionReceipt = sendConfirmationId\n    }\n\n",
    "    var currentSessionWindowKey: String? = null\n"
  ],
  [
    "// Ephemeral binding for the existing adapter execution; the ledger remains the durable owner.\ninternal class PrivateFollowUp(\n    val contactKey: String,\n    val sendConfirmationId: String?,\n    val target: ChatDraftInputTarget,\n    @Volatile var checkpoint: ContactFollowUpCheckpoint\n) {\n    var observationCount = 0L\n    var unconfirmedReported = false\n}\n\n/**\n * Owns the private-message list traversal state",
    "/**\n * Owns the private-message list traversal state"
  ],
  [
    "import com.freedom.features.aireply.domain.contact.ContactFollowUpCheckpoint\nimport com.freedom.features.aireply.ports.ChatDraftInputTarget\nimport com.freedom.features.aireply.ports.ChatListCandidateSnapshot\n",
    "import com.freedom.features.aireply.ports.ChatListCandidateSnapshot\n"
  ]
]
READ_INVERSE = [
  [
    "    var messageReadRetryCount = 0\n    /** Read-only send-proof admission failures use the existing unbounded recovery backoff. */\n    var deferredSendProofRetryAttempt = 0\n",
    "    var messageReadRetryCount = 0\n"
  ]
]

def _restore_owner(source, expected, previous, inverse):
    if git_blob(source.encode("utf-8")) != expected:
        raise AssertionError("R07-F live owner changed")
    for current, prior in inverse:
        if source.count(current) != 1:
            raise AssertionError("R07-F owner inverse anchor changed: " + current[:100])
        source = source.replace(current, prior, 1)
    if git_blob(source.encode("utf-8")) != previous:
        raise AssertionError("R07-F failed exact prior owner reconstruction")
    return source

def restore_r07e_private_owner(owner):
    return _restore_owner(owner, PRIVATE_OWNER, PRIVATE_PREVIOUS, PRIVATE_INVERSE)

def restore_r07e_read_owner(owner):
    return _restore_owner(owner, READ_OWNER, READ_PREVIOUS, READ_INVERSE)

def restore_r07e_runtime(runtime, private_owner=None, read_owner=None):
    if private_owner is None:
        private_owner = (AI / "PrivateMessagesTraversalTaskExecutor.kt").read_text(encoding="utf-8")
    if read_owner is None:
        read_owner = (AI / "ChatMessageReadCoordinator.kt").read_text(encoding="utf-8")
    restore_r07e_private_owner(private_owner)
    restore_r07e_read_owner(read_owner)
    for current, previous, count in INVERSE:
        if runtime.count(current) != count:
            raise AssertionError("R07-F inverse anchor changed: " + current[:100])
        runtime = runtime.replace(current, previous)
    if git_blob(runtime.encode("utf-8")) != PREVIOUS:
        raise AssertionError("R07-F did not reconstruct exact R07-E Runtime")
    return runtime

class PpR07SendFollowUpLifecycleMigrationTest(unittest.TestCase):
    def test_exact_inverse_and_unique_state_owner(self):
        runtime = (AI / "AiReplyObservationRuntime.kt").read_text(encoding="utf-8")
        private = (AI / "PrivateMessagesTraversalTaskExecutor.kt").read_text(encoding="utf-8")
        read = (AI / "ChatMessageReadCoordinator.kt").read_text(encoding="utf-8")
        self.assertEqual(PREVIOUS, git_blob(restore_r07e_runtime(runtime, private, read).encode("utf-8")))
        for state, owner in (
            ("activePrivateFollowUp", private), ("currentSessionSendConfirmed", private),
            ("confirmedPrivateCompletionReceipt", private), ("deferredSendProofRetryAttempt", read),
        ):
            declaration = r"\bvar\s+" + state + r"\b"
            self.assertEqual(1, len(re.findall(declaration, owner)), state)
            self.assertNotRegex(runtime, declaration)
            self.assertNotRegex(runtime, r"(?<!\.)\b" + state + r"\b")
        for method in ("beginPrivateFollowUpBinding", "clearPrivateFollowUpBinding", "retainPrivateCompletionReceipt"):
            self.assertIn("privateMessagesTraversalTaskExecutor." + method + "(", runtime)
            self.assertEqual(1, private.count("fun " + method + "("))
        self.assertIn("@Volatile var activePrivateFollowUp", private)
        self.assertIn("@Volatile var checkpoint: ContactFollowUpCheckpoint", private)
        self.assertIn("if (!value) confirmedPrivateCompletionReceipt = null", private)
        self.assertIn("contactMessageLedger.updateFollowUpCheckpoint(contactKey, expected, step, true)", runtime)
        self.assertIn("child.target.taskLease?.let { taskCoordinator?.isCurrent(it) } == true", runtime)
        self.assertIn("checkpoint.reconciliationRequired || checkpoint.revision != 0L || sendId == null", runtime)
        self.assertIn("private var pendingPrivateQuotaReceipt", runtime)
        self.assertNotIn("scheduler.schedule(", private)
        self.assertNotIn("dispatchGesture(", private)

    def test_mutations_reject_receipt_or_child_authority_loss(self):
        runtime = (AI / "AiReplyObservationRuntime.kt").read_text(encoding="utf-8")
        private = (AI / "PrivateMessagesTraversalTaskExecutor.kt").read_text(encoding="utf-8")
        read = (AI / "ChatMessageReadCoordinator.kt").read_text(encoding="utf-8")
        for old, new in (
            ("if (!value) confirmedPrivateCompletionReceipt = null", "Unit"),
            ("activePrivateFollowUp = child", "Unit"),
            ("@Volatile var checkpoint", "var checkpoint"),
        ):
            self.assertIn(old, private)
            with self.subTest(old=old), self.assertRaises(AssertionError):
                restore_r07e_runtime(runtime, private.replace(old, new, 1), read)
        for old, new in (
            ("privateMessagesTraversalTaskExecutor.activePrivateFollowUp !== child", "false"),
            ("privateMessagesTraversalTaskExecutor.clearPrivateFollowUpBinding()", "Unit"),
            ("privateMessagesTraversalTaskExecutor.retainPrivateCompletionReceipt(sendConfirmationId)", "Unit"),
            ("++messageReadCoordinator.deferredSendProofRetryAttempt", "0"),
        ):
            self.assertIn(old, runtime)
            with self.subTest(old=old), self.assertRaises(AssertionError):
                restore_r07e_runtime(runtime.replace(old, new, 1), private, read)
