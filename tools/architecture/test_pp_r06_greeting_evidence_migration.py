"""R06-D: exact inverse of the reviewed received-greetings evidence extraction."""
import unittest
from pathlib import Path
from tools.architecture.test_pp_r00_freeze import git_blob

ROOT = Path(__file__).resolve().parents[2]
AI = ROOT / "business/src/main/kotlin/com/freedom/features/aireply/"
PREVIOUS = "b3e21fb598010c1fae1fc2086fb7042f5b1d0829"
OWNER = "6c8c124a55dac2dd99000bc67570f508138bbf96"
HEADER_CALL = "        if (!matchesReceivedGreetingsChatHeader(snapshot, displayName)) {\n            return GreetingChatAdmission.Rejected(\"CHAT_HEADER_MISMATCH\")\n        }"
HEADER_CALL_ORIGINAL = "        if (ChatSessionManagementPolicy.matchChatHeader(displayName, snapshot.chatTitleText) == null) {\n            return GreetingChatAdmission.Rejected(\"CHAT_HEADER_MISMATCH\")\n        }"
WINDOW_DECLARATION = "        val observedWindowEvidence = collectReceivedGreetingsChatWindowEvidence(snapshot)\n        val verifiedWindowEvidence = verifyReceivedGreetingsChatWindowEvidence(\n            snapshot, targetPackage(), observedWindowEvidence\n        ) ?: return GreetingChatAdmission.Rejected(\"CHAT_WINDOW_NOT_VERIFIED\")"
WINDOW_RESTORE = "        val sessionKey = windowSessionKey(snapshot)\n        val titleKey = snapshot.conversationIdentityKey()\n        if (snapshot.packageName != targetPackage() ||\n            snapshot.pageKind != ChatPageKind.CHAT_DETAIL_CANDIDATE ||\n            sessionKey == null || titleKey == null\n        ) return GreetingChatAdmission.Rejected(\"CHAT_WINDOW_NOT_VERIFIED\")"
WINDOW_FIELDS = "                chatTitleKey = verifiedWindowEvidence.chatTitleKey,\n                windowSessionKey = verifiedWindowEvidence.windowSessionKey,"
WINDOW_FIELDS_ORIGINAL = "                chatTitleKey = titleKey,\n                windowSessionKey = sessionKey,"
OFFSCREEN_ANCHOR = "    /** The only admission-and-bind entry for a newly verified received-greeting chat. */"

def replace_once(source, old, new):
    if source.count(old) != 1:
        raise AssertionError("Missing or duplicate R06-D anchor: " + old[:100])
    return source.replace(old, new, 1)

def restore_r06c_runtime(runtime, owner=None):
    if owner is None:
        owner = (AI / "ReceivedGreetingsChatEvidence.kt").read_text(encoding="utf-8")
    if git_blob(owner.encode("utf-8")) != OWNER:
        raise AssertionError("R06-D greeting evidence implementation changed")
    runtime = replace_once(runtime, HEADER_CALL, HEADER_CALL_ORIGINAL)
    runtime = replace_once(runtime, WINDOW_DECLARATION, WINDOW_RESTORE)
    runtime = replace_once(runtime, WINDOW_FIELDS, WINDOW_FIELDS_ORIGINAL)
    start = owner.rfind("/**", 0, owner.index("A transition can expose chat anchors"))
    block = owner[start:].rstrip()
    block = replace_once(block, "internal fun hasOffscreenGreetingChatLocators(", "private fun hasOffscreenGreetingChatLocators(")
    block = "\n".join("    " + line if line else "" for line in block.splitlines()) + "\n\n"
    runtime = replace_once(runtime, OFFSCREEN_ANCHOR, block + OFFSCREEN_ANCHOR)
    if git_blob(runtime.encode("utf-8")) != PREVIOUS:
        raise AssertionError("R06-D did not reconstruct the exact R06-C runtime")
    return runtime

class PpR06GreetingEvidenceMigrationTest(unittest.TestCase):
    def test_exact_inverse_and_single_read_only_evidence_owner(self):
        runtime = (AI / "AiReplyObservationRuntime.kt").read_text(encoding="utf-8")
        owner = (AI / "ReceivedGreetingsChatEvidence.kt").read_text(encoding="utf-8")
        self.assertEqual(PREVIOUS, git_blob(restore_r06c_runtime(runtime, owner).encode("utf-8")))
        self.assertNotIn("private fun hasOffscreenGreetingChatLocators(", runtime)
        for method in (
            "matchesReceivedGreetingsChatHeader",
            "collectReceivedGreetingsChatWindowEvidence",
            "verifyReceivedGreetingsChatWindowEvidence",
            "hasOffscreenGreetingChatLocators",
        ):
            self.assertEqual(1, owner.count("internal fun " + method + "("))
        for forbidden in (
            "ReceivedGreetingsTaskFlowController", "ReceivedGreetingsTaskExecutor",
            "TaskCoordinator", "TaskLease", "RecoveryContext", "ChatPagePort",
            "GestureApi", "TaskScheduler", "inspectChatPage(", "dispatchGesture("
        ):
            self.assertNotIn(forbidden, owner)

    def test_header_window_and_offscreen_mutations_fail_closed(self):
        runtime = (AI / "AiReplyObservationRuntime.kt").read_text(encoding="utf-8")
        owner = (AI / "ReceivedGreetingsChatEvidence.kt").read_text(encoding="utf-8")
        for old, new in (
            ("ChatSessionManagementPolicy.matchChatHeader(displayName, snapshot.chatTitleText) != null", "true"),
            ("val sessionKey = windowSessionKey(snapshot)", "val sessionKey = null"),
            ("snapshot.packageName != expectedPackage", "false"),
            ("snapshot.pageKind != ChatPageKind.CHAT_DETAIL_CANDIDATE", "false"),
            ("node.path in paths && node.boundsValid", "true"),
            ("node.right <= display.left", "false"),
        ):
            self.assertIn(old, owner)
            with self.subTest(old=old), self.assertRaises(AssertionError):
                restore_r06c_runtime(runtime, owner.replace(old, new, 1))
        with self.assertRaises(AssertionError):
            restore_r06c_runtime(runtime.replace(HEADER_CALL, "if (true) {", 1), owner)
