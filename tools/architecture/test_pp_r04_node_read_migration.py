"""R04 batch 2: verify exact relocated read-only logic and unchanged callers.

This is a source-equivalence check, not an Android node execution test.
Expected Git blob IDs are from the green pre-migration PP commit.
"""
import unittest
from pathlib import Path
from tools.architecture.test_pp_r00_freeze import git_blob

ROOT = Path(__file__).resolve().parents[2]
MOMO = ROOT / "app/src/main/java/com/freedom/adapters/social/momo/"
BASELINE = "15731d3762ff76bb48f939305558e29d44570881"
EXPECTED = {
    "overlay_model": "2303876533c2f0b3eba1de5c18b42552c413d8e3",
    "inbox_reads": "fdeba4f40a1ba638c1eb4acd8bda1881b2cacd15",
    "local_path": "c438c2b2a3d89c045e760eba96c0bf30b6ee45be",
    "visible_count": "53df66e4a3390718a0b914be58636e42f0e16625",
    "feed_caller": "c8a12b46c1e73efa525bb28ba46c5c564966ec28",
    "tap_caller": "6de22b550df9fd459b1bd2c7a6d6c70195858eb4",
    "swipe_caller": "7c6b79db12e7d9fce98f5d6cff5200fd3dbf6555"
}


def region(source, start, end):
    left = source.index(start)
    right = source.index(end, left + len(start))
    return source[left:right].rstrip()


class PpR04NodeReadMigrationTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.adapter = (MOMO / "MomoSocialAdapter.kt").read_text(encoding="utf-8")
        cls.extractor = (MOMO / "MomoChatListSessionExtractor.kt").read_text(encoding="utf-8")
        cls.inspector = (MOMO / "MomoPageInspector.kt").read_text(encoding="utf-8")

    def assertBaseline(self, name, actual):
        self.assertEqual(EXPECTED[name], git_blob(actual.encode("utf-8")), name)

    def test_moved_node_reads_preserve_lifecycle_and_failure_semantics(self):
        model = region(self.extractor, "    data class AiFinderOverlayInspection(",
                       "    /**\n     * Nested active_rv widgets")
        self.assertBaseline("overlay_model", model.replace("data class", "private data class", 1))
        reads = region(self.extractor, "    /**\n     * Nested active_rv widgets",
                       "    fun resolveLocalPath(")
        reads = reads.replace("fun findVisibleAuxiliaryRecyclerBounds(",
                              "private fun findVisibleAuxiliaryRecyclerBounds(", 1)
        reads = reads.replace("        viewport: ScreenRect,\n        targetPackage: String\n",
                              "        viewport: ScreenRect\n", 1)
        reads = reads.replace("fun inspectAiFinderFloatingOverlay(root: AccessibilityNodeInfo, targetPackage: String)",
                              "private fun inspectAiFinderFloatingOverlay(root: AccessibilityNodeInfo)", 1)
        reads = reads.replace("val next = ScreenRect(rect.left, rect.top, rect.right, rect.bottom)",
                              "val next = rect.toScreenRect()", 1)
        self.assertBaseline("inbox_reads", reads)
        path = region(self.extractor, "    fun resolveLocalPath(", "    fun extractRoot(")
        self.assertBaseline("local_path", path.replace("fun resolveLocalPath(", "private fun resolveLocalPath(", 1))
        count = region(self.inspector, "    fun countVisibleDirectChildren(", "    fun pageFingerprint(")
        count = count.replace("fun countVisibleDirectChildren(", "private fun countVisibleDirectChildren(", 1)
        count = count.replace("val count = runCatching { container.childCount }.getOrDefault(0)",
                              "val count = safeInt { container.childCount }", 1)
        self.assertBaseline("visible_count", count)
        for method in ("findVisibleAuxiliaryRecyclerBounds", "inspectAiFinderFloatingOverlay",
                       "resolveLocalPath", "countVisibleDirectChildren"):
            self.assertNotIn("private fun " + method + "(", self.adapter)

    def test_live_root_callers_keep_checks_order_and_safe_region_decisions(self):
        source = self.adapter.replace("chatComponents.listSessionExtractor.inspectAiFinderFloatingOverlay(root, targetPackage)",
                                      "inspectAiFinderFloatingOverlay(root)")
        source = source.replace("chatComponents.listSessionExtractor.findVisibleAuxiliaryRecyclerBounds(root, viewport, targetPackage)",
                                "findVisibleAuxiliaryRecyclerBounds(root, viewport)")
        source = source.replace("chatComponents.listSessionExtractor.resolveLocalPath(row, ", "resolveLocalPath(row, ")
        source = source.replace("visibleContainer?.let(browseComponents.pageInspector::countVisibleDirectChildren)",
                                "visibleContainer?.let(::countVisibleDirectChildren)")
        self.assertBaseline("feed_caller", region(source, "    override fun inspectFeed()",
                                                  "    override fun loadFeedGestureProfile("))
        self.assertBaseline("tap_caller", region(source, "    override fun resolveSessionTapBounds(",
                                                 "    override fun resolveFireTapBounds("))
        self.assertBaseline("swipe_caller", region(source, "    override fun resolveSessionListSwipeViewport(",
                                                   "    override fun persistChatSample("))
