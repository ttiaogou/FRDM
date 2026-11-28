"""R08-H: exact consolidation of existing low-level Momo read fallbacks."""
import unittest
from pathlib import Path
from tools.architecture.test_pp_r00_freeze import git_blob

ROOT = Path(__file__).resolve().parents[2]
SHARED = "app/src/main/java/com/freedom/adapters/social/momo/MomoNodeReadFallbacks.kt"
SHARED_BLOB = "df39945103fb16e193cefcdac85a5bf84162b382"
PREVIOUS = {
  "app/src/main/java/com/freedom/adapters/social/momo/MomoChatListSessionExtractor.kt": "581a0a323522ed6e12b71fe2e1d85fb197adb18e",
  "app/src/main/java/com/freedom/adapters/social/momo/MomoChatMessageReader.kt": "1cc8714d36bcf379ee108dccb108aa988dc33fa2",
  "app/src/main/java/com/freedom/adapters/social/momo/MomoChatPageInspector.kt": "25af913c686272851a511bf33f667f01b4431f6e"
}
INVERSE = {
  "app/src/main/java/com/freedom/adapters/social/momo/MomoChatListSessionExtractor.kt": [
    [
      "    private fun empty(reason: String) = MomoChatListSessionExtraction(\n",
      "    private fun boundsOf(node: AccessibilityNodeInfo): ScreenRect {\n        val rect = Rect()\n        runCatching { node.getBoundsInScreen(rect) }\n        return ScreenRect(rect.left, rect.top, rect.right, rect.bottom)\n    }\n\n    private fun empty(reason: String) = MomoChatListSessionExtraction(\n"
    ],
    [
      "    companion object {\n",
      "    private fun safeBoolean(block: () -> Boolean): Boolean = runCatching(block).getOrDefault(false)\n    private fun safeInt(block: () -> Int): Int = runCatching(block).getOrDefault(0)\n    private fun safeNode(block: () -> AccessibilityNodeInfo?): AccessibilityNodeInfo? = runCatching(block).getOrNull()\n\n    companion object {\n"
    ],
    [
      "import android.view.accessibility.AccessibilityNodeInfo\nimport com.freedom.adapters.social.momo.momoReadBooleanOrFalse as safeBoolean\nimport com.freedom.adapters.social.momo.momoReadIntOrZero as safeInt\nimport com.freedom.adapters.social.momo.momoReadNodeOrNull as safeNode\nimport com.freedom.adapters.social.momo.momoReadBounds as boundsOf\n",
      "import android.view.accessibility.AccessibilityNodeInfo\n"
    ]
  ],
  "app/src/main/java/com/freedom/adapters/social/momo/MomoChatMessageReader.kt": [
    [
      "import android.view.accessibility.AccessibilityNodeInfo\n",
      "import android.graphics.Rect\nimport android.view.accessibility.AccessibilityNodeInfo\n"
    ],
    [
      "    private fun intersects(first: ScreenRect, second: ScreenRect): Boolean =\n",
      "    private fun boundsOf(node: AccessibilityNodeInfo): ScreenRect {\n        val rect = Rect()\n        runCatching { node.getBoundsInScreen(rect) }\n        return ScreenRect(rect.left, rect.top, rect.right, rect.bottom)\n    }\n\n    private fun intersects(first: ScreenRect, second: ScreenRect): Boolean =\n"
    ],
    [
      "    private companion object {\n",
      "    private fun safeBoolean(block: () -> Boolean): Boolean = runCatching(block).getOrDefault(false)\n    private fun safeInt(block: () -> Int): Int = runCatching(block).getOrDefault(0)\n    private fun safeNode(block: () -> AccessibilityNodeInfo?): AccessibilityNodeInfo? =\n        runCatching(block).getOrNull()\n\n    private companion object {\n"
    ],
    [
      "import android.view.accessibility.AccessibilityNodeInfo\nimport com.freedom.adapters.social.momo.momoReadBooleanOrFalse as safeBoolean\nimport com.freedom.adapters.social.momo.momoReadIntOrZero as safeInt\nimport com.freedom.adapters.social.momo.momoReadNodeOrNull as safeNode\nimport com.freedom.adapters.social.momo.momoReadBounds as boundsOf\n",
      "import android.view.accessibility.AccessibilityNodeInfo\n"
    ]
  ],
  "app/src/main/java/com/freedom/adapters/social/momo/MomoChatPageInspector.kt": [
    [
      "    companion object {\n",
      "    private fun safeBoolean(block: () -> Boolean): Boolean = runCatching(block).getOrDefault(false)\n    private fun safeInt(block: () -> Int): Int = runCatching(block).getOrDefault(0)\n    private fun safeNode(block: () -> AccessibilityNodeInfo?): AccessibilityNodeInfo? = runCatching(block).getOrNull()\n\n    companion object {\n"
    ],
    [
      "import android.view.accessibility.AccessibilityNodeInfo\nimport com.freedom.adapters.social.momo.momoReadBooleanOrFalse as safeBoolean\nimport com.freedom.adapters.social.momo.momoReadIntOrZero as safeInt\nimport com.freedom.adapters.social.momo.momoReadNodeOrNull as safeNode\n",
      "import android.view.accessibility.AccessibilityNodeInfo\n"
    ]
  ]
}

def restore_sources(sources, shared):
    if git_blob(shared.encode("utf-8")) != SHARED_BLOB:
        raise AssertionError("R08-H shared read fallback changed")
    restored = {}
    for path, edits in INVERSE.items():
        current = sources[path]
        for new, old in edits:
            if current.count(new) != 1:
                raise AssertionError("R08-H inverse anchor changed: " + path)
            current = current.replace(new, old, 1)
        if git_blob(current.encode("utf-8")) != PREVIOUS[path]:
            raise AssertionError("R08-H original source not reconstructed: " + path)
        restored[path] = current
    return restored

class PpR08MomoReadFallbackConvergenceTest(unittest.TestCase):
    def setUp(self):
        self.sources = {p: (ROOT / p).read_text(encoding="utf-8") for p in PREVIOUS}
        self.shared = (ROOT / SHARED).read_text(encoding="utf-8")

    def test_exact_inverse_and_one_implementation_per_fallback(self):
        restore_sources(self.sources, self.shared)
        for path, source in self.sources.items():
            for old, new in (("safeBoolean", "momoReadBooleanOrFalse"),
                             ("safeInt", "momoReadIntOrZero"),
                             ("safeNode", "momoReadNodeOrNull")):
                self.assertNotIn("private fun " + old + "(", source, path)
                self.assertEqual(1, source.count(new + " as " + old), path)
                self.assertEqual(1, self.shared.count("internal fun " + new + "("))
            if not path.endswith("MomoChatPageInspector.kt"):
                self.assertNotIn("private fun boundsOf(", source)
                self.assertIn("momoReadBounds as boundsOf", source)
            self.assertIn("private inline fun safeString", source)
        self.assertEqual(1, self.shared.count("internal fun momoReadBounds("))
        for token in ("dispatchGesture(", "performAction(", "TaskCoordinator", "recycle(",
                      "getChild(", "getRootInActiveWindow", "var ", "class "):
            self.assertNotIn(token, self.shared)

    def test_mutations_reject_changed_fallback_or_read_budget(self):
        for old, new in (
            ("getOrDefault(false)", "getOrDefault(true)"),
            ("getOrDefault(0)", "getOrDefault(-1)"),
            ("runCatching(block).getOrNull()", "block()"),
            ("runCatching { node.getBoundsInScreen(rect) }", "node.getBoundsInScreen(rect)"),
        ):
            self.assertIn(old, self.shared)
            with self.subTest(old=old), self.assertRaises(AssertionError):
                restore_sources(self.sources, self.shared.replace(old, new, 1))
        for path, source in self.sources.items():
            old = "momoReadBooleanOrFalse as safeBoolean"
            changed = dict(self.sources)
            changed[path] = source.replace(old, "momoReadIntOrZero as safeBoolean", 1)
            with self.subTest(path=path), self.assertRaises(AssertionError):
                restore_sources(changed, self.shared)
