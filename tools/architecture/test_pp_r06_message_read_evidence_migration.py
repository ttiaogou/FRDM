"""R06-E: exact inverse of message/OCR read-only evidence extraction."""
import unittest
from pathlib import Path
from tools.architecture.test_pp_r00_freeze import git_blob

ROOT = Path(__file__).resolve().parents[2]
AI = ROOT / "business/src/main/kotlin/com/freedom/features/aireply/"
PREVIOUS = "9e231125225143534c708288b104876900e7d51a"
OWNER = "69586bffabff2436c67f844b81b66fede6477f6a"
REVERSE_CALLS = [
    ["            val mergedOcrCount = countMergedOcrTargets(batch)","            val mergedOcrCount = batch.ocrTargets.count { target ->\n                batch.messages.any { message -> message.bounds == target.rowBounds }\n            }"],
    ["        return sendConfirmationBatchEvidenceRejectionReason(batch, sourceWindowKey)","        if (batch.status == MessageReadStatus.WINDOW_STALE) return \"OCR_READ_INVALIDATED\"\n        if (batch.windowSessionKey != sourceWindowKey) return \"OCR_BATCH_WINDOW_MISMATCH\"\n        return \"GATE_REJECTED_UNCLASSIFIED\""],
    ["        sendConfirmationPageEvidenceRejectionReason(source, fresh)?.let { return it }","        if (fresh.pageKind != ChatPageKind.CHAT_DETAIL_CANDIDATE) return \"FRESH_CHAT_DETAIL_NOT_CONFIRMED\"\n        if (fresh.packageName != source.packageName) return \"FRESH_PACKAGE_MISMATCH\"\n        if (!fresh.hasSameWindowIdentity(source)) return \"WINDOW_IDENTITY_CHANGED\"\n        if (!hasSameWindowSession(source, fresh)) return \"WINDOW_GENERATION_OR_SESSION_CHANGED\""],
    ["        return matchesPrivateMessageReadPageEvidence(snapshot, active) &&","        return active.pageKind == ChatPageKind.CHAT_DETAIL_CANDIDATE &&\n            active.packageName == snapshot.packageName &&\n            active.hasSameWindowIdentity(snapshot) &&\n            hasSameWindowSession(snapshot, active) &&"],
    ["            return matchesMessageReadWindowEvidence(snapshot, batch, active, expectedWindowSessionKey)","            return expectedWindowSessionKey != null &&\n                batch.windowSessionKey == expectedWindowSessionKey &&\n                hasSameWindowSession(snapshot, active)"],
    ["                matchesMessageReadWindowEvidence(snapshot, batch, active, expectedWindowSessionKey)","                expectedWindowSessionKey != null && batch.windowSessionKey == expectedWindowSessionKey &&\n                hasSameWindowSession(snapshot, active)"],
    ["        private const val MAX_OCR_DEBUG_BATCHES_PER_WINDOW","        private const val MAX_OCR_DEBUG_TEXT_CHARS = 4_200\n        private const val MAX_OCR_DEBUG_BATCHES_PER_WINDOW"],
    ["import java.util.LinkedHashSet\n","import java.nio.charset.StandardCharsets\nimport java.security.MessageDigest\nimport java.util.LinkedHashSet\n"],
]
DEBUG_END = "    private fun handleSessionListPage("

def replace_once(source, old, new):
    if source.count(old) != 1:
        raise AssertionError("Missing or duplicate R06-E anchor: " + old[:100])
    return source.replace(old, new, 1)

def restore_r06d_runtime(runtime, owner=None):
    if owner is None:
        owner = (AI / "ChatMessageReadEvidence.kt").read_text(encoding="utf-8")
    if git_blob(owner.encode("utf-8")) != OWNER:
        raise AssertionError("R06-E message evidence implementation changed")
    for current, previous in REVERSE_CALLS:
        runtime = replace_once(runtime, current, previous)
    debug = owner[owner.index("internal fun createOcrDebugFingerprint("):].rstrip()
    debug = "\n".join(
        "    " + line.replace("internal fun ", "private fun ", 1)
        if line.startswith("internal fun ") else ("    " + line if line else "")
        for line in debug.split("\n")
    ) + "\n\n"
    runtime = replace_once(runtime, DEBUG_END, debug + DEBUG_END)
    if git_blob(runtime.encode("utf-8")) != PREVIOUS:
        raise AssertionError("R06-E did not reconstruct the exact R06-D runtime")
    return runtime

class PpR06MessageReadEvidenceMigrationTest(unittest.TestCase):
    def test_exact_inverse_and_single_read_only_evidence_owner(self):
        runtime = (AI / "AiReplyObservationRuntime.kt").read_text(encoding="utf-8")
        owner = (AI / "ChatMessageReadEvidence.kt").read_text(encoding="utf-8")
        self.assertEqual(PREVIOUS, git_blob(restore_r06d_runtime(runtime, owner).encode("utf-8")))
        for method in (
            "matchesMessageReadWindowEvidence", "matchesPrivateMessageReadPageEvidence",
            "sendConfirmationPageEvidenceRejectionReason", "sendConfirmationBatchEvidenceRejectionReason",
            "countMergedOcrTargets", "createOcrDebugFingerprint", "escapeOcrDebugText",
        ):
            self.assertEqual(1, owner.count("internal fun " + method + "("))
            self.assertNotIn("private fun " + method + "(", runtime)
        for forbidden in (
            "ChatMessageReadCoordinator", "ChatMessagePort", "TaskCoordinator", "TaskLease",
            "TaskScheduler", "RecoveryContext", "onLog(", "inspectChatPage(", "onSendTransaction(",
        ):
            self.assertNotIn(forbidden, owner)

    def test_stale_window_batch_and_debug_limits_fail_closed(self):
        runtime = (AI / "AiReplyObservationRuntime.kt").read_text(encoding="utf-8")
        owner = (AI / "ChatMessageReadEvidence.kt").read_text(encoding="utf-8")
        for old, new in (
            ("batch.windowSessionKey == expectedWindowSessionKey", "true"),
            ("active.hasSameWindowIdentity(snapshot)", "true"),
            ("!hasSameWindowSession(source, fresh)", "false"),
            ("batch.status == MessageReadStatus.WINDOW_STALE", "false"),
            ("batch.windowSessionKey != sourceWindowKey", "false"),
            ("MAX_OCR_DEBUG_TEXT_CHARS = 4_200", "MAX_OCR_DEBUG_TEXT_CHARS = 42_000"),
            ('MessageDigest.getInstance("SHA-256")', 'MessageDigest.getInstance("MD5")'),
        ):
            self.assertIn(old, owner)
            with self.subTest(old=old), self.assertRaises(AssertionError):
                restore_r06d_runtime(runtime, owner.replace(old, new, 1))
        with self.assertRaises(AssertionError):
            restore_r06d_runtime(runtime.replace("sendConfirmationPageEvidenceRejectionReason(source, fresh)?.let { return it }", "Unit", 1), owner)
