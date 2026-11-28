"""R07-D: exact greeting binding and send receipt ownership migration."""
import re
import unittest
from pathlib import Path
from tools.architecture.test_pp_r00_freeze import git_blob
from tools.architecture.test_pp_r07_message_read_lifecycle_migration import restore_r07d_runtime

ROOT = Path(__file__).resolve().parents[2]
AI = ROOT / "business/src/main/kotlin/com/freedom/features/aireply/"
PREVIOUS = "ec018569ae4dc4f7a446380cb1e2f651686b25b0"
OWNER = "7557397096bf6063b447f39a62a17659bd81c168"
INVERSE = [
  [
    "receivedGreetingsTaskFlow.confirmedGreetingSend",
    "confirmedGreetingSend",
    6
  ],
  [
    "receivedGreetingsTaskFlow.greetingChatBinding",
    "greetingChatBinding",
    15
  ],
  [
    "        receivedGreetingsTaskFlow.clearGreetingVisit()",
    "        greetingChatBinding = null\n        confirmedGreetingSend = null",
    1
  ],
  [
    "                    receivedGreetingsTaskFlow.retainConfirmedGreetingSend(bound, sendId)",
    "                    confirmedGreetingSend = ConfirmedGreetingSend(bound, sendId)",
    1
  ],
  [
    "        receivedGreetingsTaskFlow.replaceGreetingVisit {\n            GreetingChatBinding(\n                rowKeyHash = evidence.rowKeyHash,\n                contactId = evidence.contactId,\n                normalizedDisplayName = evidence.normalizedDisplayName,\n                chatTitleKey = evidence.chatTitleKey,\n                windowSessionKey = evidence.windowSessionKey,\n                runGeneration = currentRunGeneration(),\n                visitToken = draftVisitToken,\n                taskLease = evidence.taskLease\n            )\n        }\n",
    "        confirmedGreetingSend = null\n        greetingChatBinding = GreetingChatBinding(\n            rowKeyHash = evidence.rowKeyHash,\n            contactId = evidence.contactId,\n            normalizedDisplayName = evidence.normalizedDisplayName,\n            chatTitleKey = evidence.chatTitleKey,\n            windowSessionKey = evidence.windowSessionKey,\n            runGeneration = currentRunGeneration(),\n            visitToken = draftVisitToken,\n            taskLease = evidence.taskLease\n        )\n",
    1
  ],
  [
    "    private var greetingModelFailureRecovery:",
    "    /** A complete greeting send is not a completed row until its final chat sample and safe BACK. */\n    private data class ConfirmedGreetingSend(\n        val binding: GreetingChatBinding,\n        val confirmationId: String,\n        var finalSampleSaved: Boolean = false,\n        var sendJournalSaved: Boolean = false\n    )\n    private var confirmedGreetingSend: ConfirmedGreetingSend? = null\n    private var greetingModelFailureRecovery:",
    1
  ],
  [
    "    /** Latest verified greeting-detail message observation",
    "    /**\n     * Source-anchored greeting chat binding. This is evidence, not a UI-operation lease:\n     * every OCR callback must still revalidate its live window and the exact task lease.\n     * Contact IDs and titles remain ephemeral and must never be written to diagnostics.\n     */\n    private data class GreetingChatBinding(\n        val rowKeyHash: String,\n        val contactId: String,\n        val normalizedDisplayName: String,\n        val chatTitleKey: String,\n        val windowSessionKey: String,\n        val runGeneration: Long,\n        val visitToken: String,\n        val taskLease: TaskLease?\n    )\n    private var greetingChatBinding: GreetingChatBinding? = null\n    /** Latest verified greeting-detail message observation",
    1
  ]
]

def restore_r07c_runtime(runtime, owner=None):
    runtime = restore_r07d_runtime(runtime)
    if owner is None:
        owner = (AI / "ReceivedGreetingsTaskFlowController.kt").read_text(encoding="utf-8")
    if git_blob(owner.encode("utf-8")) != OWNER:
        raise AssertionError("R07-D greeting visit owner changed")
    for current, previous, count in INVERSE:
        if runtime.count(current) != count:
            raise AssertionError("R07-D inverse anchor changed: " + current[:100])
        runtime = runtime.replace(current, previous)
    if git_blob(runtime.encode("utf-8")) != PREVIOUS:
        raise AssertionError("R07-D did not reconstruct exact R07-C Runtime")
    return runtime

class PpR07GreetingVisitMigrationTest(unittest.TestCase):
    def test_exact_inverse_and_single_state_owner(self):
        runtime = (AI / "AiReplyObservationRuntime.kt").read_text(encoding="utf-8")
        owner = (AI / "ReceivedGreetingsTaskFlowController.kt").read_text(encoding="utf-8")
        self.assertEqual(PREVIOUS, git_blob(restore_r07c_runtime(runtime, owner).encode("utf-8")))
        for state in ("greetingChatBinding", "confirmedGreetingSend"):
            declaration = r"\bvar\s+" + state + r"\b"
            self.assertEqual(1, len(re.findall(declaration, owner)), state)
            self.assertNotRegex(runtime, declaration)
            self.assertNotRegex(runtime, r"receivedGreetingsTaskFlow\." + state + r"\s*=(?!=)")
        for method in ("replaceGreetingVisit", "retainConfirmedGreetingSend", "clearGreetingVisit"):
            self.assertIn("receivedGreetingsTaskFlow." + method, runtime)
            self.assertEqual(1, owner.count("fun " + method + "("))
        self.assertIn("binding !== receivedGreetingsTaskFlow.greetingChatBinding || binding.visitToken != messageReadCoordinator.draftVisitToken", runtime)
        self.assertIn("greetingBindingRejectReason(snapshot, confirmed.binding)", runtime)
        self.assertIn("persistReceivedGreetingConfirmedSend(confirmed.confirmationId)", runtime)
        self.assertIn("receivedGreetingsTaskFlow.completeDetailInspection(true)", runtime)
        # R07-E moves these two deferred R07-D states to the actual read owner.
        read_owner = (AI / "ChatMessageReadCoordinator.kt").read_text(encoding="utf-8")
        self.assertIn("var latestGreetingMessageBatch", read_owner)
        self.assertIn("var greetingModelFailureRecovery:", read_owner)
        self.assertNotRegex(runtime, r"\bvar\s+latestGreetingMessageBatch\b")
        self.assertNotRegex(runtime, r"\bvar\s+greetingModelFailureRecovery\b")

    def test_mutations_reject_stale_visit_and_send_receipt_loss(self):
        runtime = (AI / "AiReplyObservationRuntime.kt").read_text(encoding="utf-8")
        owner = (AI / "ReceivedGreetingsTaskFlowController.kt").read_text(encoding="utf-8")
        for old, new in (
            ("confirmedGreetingSend = null", "Unit"),
            ("greetingChatBinding = createBinding()", "Unit"),
            ("confirmedGreetingSend = ConfirmedGreetingSend(binding, confirmationId)", "Unit"),
        ):
            self.assertIn(old, owner)
            with self.subTest(old=old), self.assertRaises(AssertionError):
                restore_r07c_runtime(runtime, owner.replace(old, new, 1))
        for old, new in (
            ("binding !== receivedGreetingsTaskFlow.greetingChatBinding || binding.visitToken != messageReadCoordinator.draftVisitToken", "false"),
            ("receivedGreetingsTaskFlow.clearGreetingVisit()", "Unit"),
            ("confirmed.sendJournalSaved = true", "Unit"),
            ("confirmed.finalSampleSaved = true", "Unit"),
        ):
            self.assertIn(old, runtime)
            with self.subTest(old=old), self.assertRaises(AssertionError):
                restore_r07c_runtime(runtime.replace(old, new, 1), owner)
