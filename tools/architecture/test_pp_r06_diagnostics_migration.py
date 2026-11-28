"""R06-F: exact inverse of the approved stateless diagnostic formatting extraction."""
import unittest
from pathlib import Path
from tools.architecture.test_pp_r00_freeze import git_blob
from tools.architecture.test_pp_r07_page_observation_migration import restore_r06f_runtime

ROOT = Path(__file__).resolve().parents[2]
AI = ROOT / "business/src/main/kotlin/com/freedom/features/aireply/"
PREVIOUS = "64727753394d6c5c3cf1e497a36ade3ffa7cc578"
OWNER = "a7ae4eac8cc824af0984955e520e54f20f3ab10e"
HEADER = ("package com.freedom.features.aireply\n\n"
          "import com.freedom.features.aireply.ports.ChatPageKind\n"
          "import com.freedom.features.aireply.ports.ChatPageSnapshot\n\n"
          "/** Pure diagnostic formatting only; log emission and limits remain in the observation runtime. */\n")
LOG_ANCHOR = "    /**\n     * Emits bounded, redacted node evidence in numbered records."
NODE_ANCHOR = "/** Structural fields only: never serialize node text, content descriptions or contact data. */"
END = "\n}\n"


def replace_once(source, old, new):
    if source.count(old) != 1:
        raise AssertionError("Missing or duplicate R06-F anchor: " + old[:100])
    return source.replace(old, new, 1)


def restore_r06e_runtime(runtime, owner=None):
    runtime = restore_r06f_runtime(runtime)
    if owner is None:
        owner = (AI / "AiReplyPageDiagnostics.kt").read_text(encoding="utf-8")
    if git_blob(owner.encode("utf-8")) != OWNER:
        raise AssertionError("R06-F diagnostic implementation changed")
    if not owner.startswith(HEADER) or not owner.endswith("\n") or not runtime.endswith(END):
        raise AssertionError("R06-F diagnostic source boundaries changed")
    body = owner[len(HEADER):-1]
    a, b = body.split(NODE_ANCHOR)
    a = replace_once(a, "internal fun summarize(", "private fun summarize(")
    b = replace_once(NODE_ANCHOR + b, "internal fun formatNodeEvidence(", "private fun formatNodeEvidence(")
    def indent(block):
        return "\n".join("    " + line if line else "" for line in block.split("\n"))
    runtime = replace_once(runtime, LOG_ANCHOR, indent(a) + LOG_ANCHOR)
    runtime = runtime[:-len(END)] + indent(b) + END
    if git_blob(runtime.encode("utf-8")) != PREVIOUS:
        raise AssertionError("R06-F did not reconstruct the exact R06-E runtime")
    return runtime


class PpR06DiagnosticsMigrationTest(unittest.TestCase):
    def test_exact_inverse_and_unique_pure_formatting_owner(self):
        runtime = (AI / "AiReplyObservationRuntime.kt").read_text(encoding="utf-8")
        owner = (AI / "AiReplyPageDiagnostics.kt").read_text(encoding="utf-8")
        self.assertEqual(PREVIOUS, git_blob(restore_r06e_runtime(runtime, owner).encode("utf-8")))
        for visibility, method in (("internal", "summarize"), ("private", "formatChatListSessionSummary"),
                                   ("private", "formatNodeTreeProbeSummary"), ("internal", "formatNodeEvidence")):
            self.assertEqual(1, owner.count(visibility + " fun " + method + "("))
            self.assertNotIn("private fun " + method + "(", runtime)
        for forbidden in ("onLog(", "onDiagnosticLog(", "TaskCoordinator", "TaskScheduler", "TaskLease",
                          "RecoveryContext", "ChatPagePort", "inspectChatPage(", "dispatchGesture("):
            self.assertNotIn(forbidden, owner)
        for state in ("private fun logStructureEvidence(", "private fun recordPrivateUnreadDiagnostic(",
                      "MAX_LOGGED_STRUCTURE_EVIDENCE = 256", "MAX_STRUCTURE_LOG_LINE_CHARS = 3_500",
                      "loggedOcrDebugFingerprints", "ocrDebugBatchLimitLogged"):
            self.assertIn(state, runtime)

    def test_redaction_and_log_limits_mutations_fail_closed(self):
        runtime = (AI / "AiReplyObservationRuntime.kt").read_text(encoding="utf-8")
        owner = (AI / "AiReplyPageDiagnostics.kt").read_text(encoding="utf-8")
        for old, new in (
            ("candidates.count { it.hasReadableName }", "candidates.toString()"),
            ("item.className.substringAfterLast('.')", "item.toString()"),
            ("snapshot.nodeTreeProbeVisitedNodeCount == 0", "false"),
        ):
            self.assertIn(old, owner)
            with self.subTest(old=old), self.assertRaises(AssertionError):
                restore_r06e_runtime(runtime, owner.replace(old, new, 1))
        for old, new in (
            ("MAX_LOGGED_STRUCTURE_EVIDENCE = 256", "MAX_LOGGED_STRUCTURE_EVIDENCE = 512"),
            ("MAX_STRUCTURE_LOG_LINE_CHARS = 3_500", "MAX_STRUCTURE_LOG_LINE_CHARS = 7_000"),
            (".map(::formatNodeEvidence)", ".map { it.toString() }"),
            ("logStructureEvidence(inspection)", "Unit"),
        ):
            self.assertIn(old, runtime)
            with self.subTest(old=old), self.assertRaises(AssertionError):
                restore_r06e_runtime(runtime.replace(old, new, 1), owner)
