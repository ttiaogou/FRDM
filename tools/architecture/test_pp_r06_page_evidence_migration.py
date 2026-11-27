"""R06-B: reverse the reviewed page-evidence extraction and preserve the full runtime baseline."""
import unittest
from pathlib import Path
from tools.architecture.test_pp_r00_freeze import git_blob

ROOT = Path(__file__).resolve().parents[2]
AI = ROOT / "business/src/main/kotlin/com/freedom/features/aireply/"
PREVIOUS = "1c0c3bc1669f0cdc56e3abbb36c2f8c0be064f8a"
OWNER = "687c609b7df393c86eab41a6916ef60764b4a74e"
METHODS = ["recoveryStructuralKey","isMessageViewportPositionedAgainstTab","hasRelevantMessageStructureDelta","pageIdentityChanged","hasSameWindowSession","windowSessionKey","selfNavigationMatchesInspection","chatSurfaceKey"]
HEADER = "package com.freedom.features.aireply\n\nimport com.freedom.core.accessibility.api.ScreenRect\nimport com.freedom.core.accessibility.events.SelfNavigationTransition\nimport com.freedom.features.aireply.ports.ChatPageKind\nimport com.freedom.features.aireply.ports.ChatPageSnapshot\n\n/** Pure page evidence only. No fresh reads, cache updates, task leases or recovery scheduling. */\n"
# Anchors identify the original positions, while relocated bodies are read from the live owner.
REGIONS = [["    private fun recoveryStructuralKey(","    /** System touch boundaries"],["    private fun isMessageViewportPositionedAgainstTab(","    /**\n     * Transfers only the UI task lease"],["    private fun pageIdentityChanged(","    private fun ownsTask("],["    private fun hasSameWindowSession(","    private fun cancelMessageReadRetry("]]
GROUP_STARTS = ["internal fun recoveryStructuralKey(", "internal fun isMessageViewportPositionedAgainstTab(", "internal fun pageIdentityChanged(", "internal fun hasSameWindowSession("]
NESTED_END = "    }\n\n    /**\n     * Source-anchored greeting chat binding"
NESTED_CALL = "com.freedom.features.aireply.chatSurfaceKey(snapshot) == chatSurfaceKey"

def replace_once(source, old, new):
    if source.count(old) != 1:
        raise AssertionError("Missing or duplicate R06-B anchor: " + old[:100])
    return source.replace(old, new, 1)

def restore_r06a_runtime(runtime, owner=None):
    if owner is None:
        owner = (AI / "AiReplyPageEvidence.kt").read_text(encoding="utf-8")
    if git_blob(owner.encode("utf-8")) != OWNER:
        raise AssertionError("R06-B evidence implementation changed")
    if not owner.startswith(HEADER):
        raise AssertionError("R06-B evidence owner header changed")
    body = owner[len(HEADER):]
    blocks = []
    for index, start in enumerate(GROUP_STARTS):
        left = body.index(start)
        right = body.index(GROUP_STARTS[index + 1]) if index + 1 < len(GROUP_STARTS) else len(body)
        block = body[left:right].rstrip()
        restored = "\n".join("    " + line.replace("internal fun ", "private fun ", 1)
                             if line.startswith("internal fun ") else ("    " + line if line else "")
                             for line in block.split("\n")) + "\n\n"
        blocks.append(restored)
    for block, (_, end) in zip(blocks, REGIONS):
        runtime = replace_once(runtime, end, block + end)
    surface = blocks[-1][blocks[-1].index("    private fun chatSurfaceKey("):].rstrip()
    nested = "\n".join("    " + line if line else "" for line in surface.split("\n"))
    nested = nested.replace("private fun chatSurfaceKey(", "private fun windowSessionSurfaceKey(", 1) + "\n"
    runtime = replace_once(runtime, NESTED_CALL, "windowSessionSurfaceKey(snapshot) == chatSurfaceKey")
    runtime = replace_once(runtime, NESTED_END, nested + NESTED_END)
    if git_blob(runtime.encode("utf-8")) != PREVIOUS:
        raise AssertionError("R06-B did not reconstruct the exact R06-A runtime")
    return runtime

class PpR06PageEvidenceMigrationTest(unittest.TestCase):
    def test_exact_inverse_and_single_evidence_owner(self):
        runtime = (AI / "AiReplyObservationRuntime.kt").read_text(encoding="utf-8")
        owner = (AI / "AiReplyPageEvidence.kt").read_text(encoding="utf-8")
        self.assertEqual(PREVIOUS, git_blob(restore_r06a_runtime(runtime, owner).encode("utf-8")))
        for method in METHODS:
            self.assertNotIn("private fun " + method + "(", runtime)
            self.assertEqual(1, owner.count("internal fun " + method + "("))
        self.assertNotIn("private fun windowSessionSurfaceKey(", runtime)
        for forbidden in ("ChatPagePort", "TaskCoordinator", "TaskScheduler", "RecoveryContext", "inspectChatPage("):
            self.assertNotIn(forbidden, owner)

    def test_stale_window_and_geometry_mutations_fail_closed(self):
        runtime = (AI / "AiReplyObservationRuntime.kt").read_text(encoding="utf-8")
        owner = (AI / "AiReplyPageEvidence.kt").read_text(encoding="utf-8")
        for old, new in (
            ("left.generation == right.generation", "true"),
            ("messageWindow.generation != inputWindow.generation", "false"),
            ("first.conversationIdentityKey() == second.conversationIdentityKey()", "true"),
            ("sameNavigationAnchor && viewportProof", "viewportProof"),
            ("transition.targetActivityClass != inspection.activityClass", "false"),
        ):
            self.assertIn(old, owner)
            with self.subTest(old=old), self.assertRaises(AssertionError):
                restore_r06a_runtime(runtime, owner.replace(old, new, 1))
        with self.assertRaises(AssertionError):
            restore_r06a_runtime(runtime.replace(NESTED_CALL, "true", 1), owner)
