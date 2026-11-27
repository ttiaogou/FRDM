"""R05-D: reconstruct the exact green R05-C adapter from the migrated execution owner."""
import unittest
from pathlib import Path
from tools.architecture.test_pp_r00_freeze import git_blob
from tools.architecture.test_pp_r05_image_transaction_migration import restore_r05d_adapter

ROOT = Path(__file__).resolve().parents[2]
MOMO = ROOT / "app/src/main/java/com/freedom/adapters/social/momo/"
PREVIOUS_ADAPTER = "a7e2b9cb16f4764839f55d24f69e8bb7fb462f4d"
OWNER = "e56c3fc9787320aaade7b5b9ce7f731aef2794bb"
FACADE_INPUT = "    private val draftTransaction = MomoDraftTransactionController(\n        rootAccess = rootAccess,\n        targetPackage = targetPackage,\n        activityClass = activityClass,\n        onLog = onLog,\n        gesturePolicy = gesturePolicy,\n        isAiReplyUiOperationOwned = isAiReplyUiOperationOwned,\n        isBoundAiReplyUiOperationOwned = isBoundAiReplyUiOperationOwned,\n        sendTapDurationMs = SEND_TAP_DURATION_MS\n    )\n\n    override fun writeDraft(target: ChatDraftInputTarget, expectedText: String, text: String): ChatDraftInputResult =\n        draftTransaction.writeDraft(target, expectedText, text)\n\n    override fun verifyDraft(target: ChatDraftInputTarget, text: String): ChatDraftInputResult =\n        draftTransaction.verifyDraft(target, text)\n\n"
FACADE_SEND = "    override fun sendDraft(target: ChatDraftInputTarget, expectedText: String): ChatDraftInputResult =\n        sendDraft(target, expectedText) {}\n\n    override fun sendDraft(\n        target: ChatDraftInputTarget,\n        expectedText: String,\n        onGestureCompleted: () -> Unit\n    ): ChatDraftInputResult = draftTransaction.sendDraft(target, expectedText, onGestureCompleted)\n\n\n"

def replace_once(source, old, new):
    if source.count(old) != 1:
        raise AssertionError("Missing or duplicate R05-D migration anchor: " + old[:80])
    return source.replace(old, new, 1)

def region(source, start, end):
    if source.count(start) != 1:
        raise AssertionError("Missing or duplicate R05-D owner anchor: " + start)
    left = source.index(start)
    return source[left:source.index(end, left + len(start))]

def restore_r05c_adapter(adapter, controller=None):
    adapter = restore_r05d_adapter(adapter)
    if controller is None:
        controller = (MOMO / "MomoDraftTransactionController.kt").read_text(encoding="utf-8")
    if git_blob(controller.encode("utf-8")) != OWNER:
        raise AssertionError("R05-D transaction owner changed")
    input_body = region(controller, "    private fun draftInputController(", "    override fun sendDraft(target: ChatDraftInputTarget, expectedText: String)")
    send_body = region(controller, "    override fun sendDraft(target: ChatDraftInputTarget, expectedText: String)", "    private companion object {")
    send_body = replace_once(send_body, "durationMs = sendTapDurationMs", "durationMs = SEND_TAP_DURATION_MS")
    send_body = replace_once(send_body, "ScreenRect(rect.left, rect.top, rect.right, rect.bottom)", "rect.toScreenRect()")
    selectors = region(controller, "        private val SEND_CONTROL_VIEW_ID_SUFFIXES = setOf(", "    }\n}\n")
    adapter = replace_once(adapter, FACADE_INPUT, input_body)
    adapter = replace_once(adapter, FACADE_SEND, send_body)
    adapter = replace_once(adapter, "        private const val RECEIVED_GREETINGS_ENTRY_SEARCH_SCROLL_DURATION_MS", selectors + "        private const val RECEIVED_GREETINGS_ENTRY_SEARCH_SCROLL_DURATION_MS")
    adapter = replace_once(adapter, "import com.freedom.core.accessibility.api.NodeActionRequest\n",
                           "import com.freedom.core.accessibility.api.NodeAction\nimport com.freedom.core.accessibility.api.NodeActionRequest\n")
    adapter = replace_once(adapter, "import com.freedom.features.aireply.ports.ChatDraftSendPort\n",
                           "import com.freedom.features.aireply.ports.ChatDraftInputStatus\nimport com.freedom.features.aireply.ports.ChatDraftSendPort\n")
    if git_blob(adapter.encode("utf-8")) != PREVIOUS_ADAPTER:
        raise AssertionError("R05-D did not reconstruct the exact green R05-C adapter")
    return adapter

class PpR05DraftTransactionMigrationTest(unittest.TestCase):
    def test_full_facade_and_transaction_restore(self):
        adapter = (MOMO / "MomoSocialAdapter.kt").read_text(encoding="utf-8")
        self.assertEqual(PREVIOUS_ADAPTER, git_blob(restore_r05c_adapter(adapter).encode("utf-8")))
        self.assertNotIn("performVerifiedChatInputAction(", adapter)
        self.assertNotIn("private fun isCurrentDraftTarget(", adapter)
        self.assertNotIn("SEND_CONTROL_VIEW_ID_SUFFIXES", adapter)
        self.assertEqual(1, adapter.count("MomoDraftTransactionController("))

    def test_changed_owner_or_facade_fails_closed(self):
        adapter = (MOMO / "MomoSocialAdapter.kt").read_text(encoding="utf-8")
        owner = (MOMO / "MomoDraftTransactionController.kt").read_text(encoding="utf-8")
        for old, new in (
            ("isBoundAiReplyUiOperationOwned(target.taskLease)", "true"),
            ("SEND_CONTROL_AMBIGUOUS", "SEND_SUBMITTED"),
            ("GestureExecutionStatus.COMPLETED", "GestureExecutionStatus.REJECTED"),
            ("inputText != expectedText", "false"),
        ):
            self.assertIn(old, owner)
            with self.subTest(old=old), self.assertRaises(AssertionError):
                restore_r05c_adapter(adapter, owner.replace(old, new, 1))
        with self.assertRaises(AssertionError):
            restore_r05c_adapter(adapter.replace("sendTapDurationMs = SEND_TAP_DURATION_MS", "sendTapDurationMs = 1L", 1), owner)
