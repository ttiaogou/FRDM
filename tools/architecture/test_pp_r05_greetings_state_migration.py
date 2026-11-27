"""R05-B: reverse only the reviewed greeting relocation and preserve R05-A evidence."""
import re
import unittest
from pathlib import Path
from tools.architecture.test_pp_r00_freeze import git_blob
from tools.architecture.test_pp_r05_avatar_transaction_migration import restore_r05b_adapter

ROOT = Path(__file__).resolve().parents[2]
MOMO = ROOT / "app/src/main/java/com/freedom/adapters/social/momo/"
BASELINE = "7006836ce4eb3efd24f5a5d46b1c28850d17866e"
PREVIOUS_ADAPTER = "0c9bf36aad8f4cba2d996be835ae14d731ecd17d"


def source(name):
    return (MOMO / name).read_text(encoding="utf-8")


def region(s, start, end):
    left = s.index(start)
    return s[left:s.index(end, left + len(start))].rstrip()


def close_fields(s):
    return re.sub(r"^    var ", "    private var ", s.replace("@Volatile var ", "@Volatile private var "), flags=re.M)


def replace_once(s, old, new):
    if s.count(old) != 1:
        raise AssertionError("Missing or duplicate migration anchor: " + old[:100])
    return s.replace(old, new, 1)


def restore_r05a_adapter(adapter):
    adapter = restore_r05b_adapter(adapter)
    nav = source("MomoSayHiNavigationFlowController.kt")
    search = source("MomoReceivedGreetingsEntrySearchController.kt")
    traversal = source("MomoSayHiTraversalState.kt")
    nav_pending = close_fields(region(nav, "    @Volatile var receivedGreetingsEntryTapPending:",
                                      "    @Volatile var receivedGreetingsEntryTapAtMs:"))
    nav_times = close_fields(region(nav, "    @Volatile var receivedGreetingsEntryTapAtMs:",
                                    "    /** Run-local entry/card evidence"))
    window = close_fields(region(search, "    @Volatile var receivedGreetingsEntrySearchWindowIdentity:", "    init {"))
    flags = close_fields(region(traversal, "    /** Business-visible read-only wait;", "    var sayHiPendingVerifiedReturnRow:"))
    receipts = close_fields(region(traversal, "    var sayHiPendingVerifiedReturnRow:", "    // These run-local credits"))
    for fields, owner in ((nav_pending + "\n" + nav_times, "sayHiNavigationFlowController"),
                          (window, "receivedGreetingsEntrySearchController"),
                          (flags + "\n" + receipts, "sayHiTraversalState")):
        for field in re.findall(r"\bvar (\w+)", fields):
            adapter = adapter.replace("${" + owner + "." + field + "}", "$" + field)
            adapter = adapter.replace(owner + "." + field, field)
    for block, anchor in (
        (nav_pending, "    @Volatile private var sayHiNonSearchGestureInFlight:"),
        (nav_times, "    private val receivedGreetingsEntrySearchController"),
        (window, "    @Volatile private var sayHiReadOnlyStage:"),
        (flags, "    @Volatile private var sayHiSelectedGreeting:"),
    ):
        adapter = replace_once(adapter, anchor, block + "\n" + anchor)
    anchor = "\n    @Synchronized\n    override fun consumeReceivedGreetingsVerifiedReturnReceipts("
    adapter = replace_once(adapter, anchor, receipts + "\n" + anchor)
    reset = region(nav, "        receivedGreetingsEntryTapPending = false\n        manualReceivedGreetingsArrivalProbeActive = false",
                   "\n    }\n\n    init {")
    consume = region(traversal, "    fun consumeVerifiedReturnReceipts(): List<String> =", "    fun recordRunSendReceipt(").split("=\n", 1)[1]
    send = region(traversal, "    fun recordRunSendReceipt(", "    fun recordVerifiedReturnReceipt(").replace(
        "fun recordRunSendReceipt", "private fun recordSayHiRunSendReceipt", 1)
    verified = region(traversal, "    fun recordVerifiedReturnReceipt(", "    fun hasVerifiedReturnReceipts(").replace(
        "fun recordVerifiedReturnReceipt", "private fun recordSayHiVerifiedReturnReceipt", 1)
    has = region(traversal, "    fun hasVerifiedReturnReceipts(", "    fun clearRunReturnReceipts(").replace(
        "fun hasVerifiedReturnReceipts", "private fun hasSayHiVerifiedReturnReceipts", 1)
    clear = region(traversal, "    fun clearRunReturnReceipts(", "    private val processedRowKeys")
    clear = clear.replace("    fun clearRunReturnReceipts() {", "        synchronized(this) {", 1)
    clear = clear.replace("\n        sayHi", "\n            sayHi")
    clear = clear[:-len("\n    }")] + "\n        }"
    for old, new in (
        ("        sayHiNavigationFlowController.resetEntryNavigationRuntime()", reset),
        ("        synchronized(this) {\n            sayHiTraversalState.clearRunReturnReceipts()\n        }", clear),
        ("        return sayHiTraversalState.consumeVerifiedReturnReceipts()", consume.replace("        ", "        return ", 1)),
        ("    private fun recordSayHiRunSendReceipt(receipt: String) {\n        sayHiTraversalState.recordRunSendReceipt(receipt)\n    }", send),
        ("    private fun recordSayHiVerifiedReturnReceipt(receipt: String) {\n        sayHiTraversalState.recordVerifiedReturnReceipt(receipt)\n    }", verified),
        ("    private fun hasSayHiVerifiedReturnReceipts(): Boolean = sayHiTraversalState.hasVerifiedReturnReceipts()", has),
    ):
        adapter = replace_once(adapter, old, new)
    if git_blob(adapter.encode("utf-8")) != PREVIOUS_ADAPTER:
        raise AssertionError("R05-B does not reconstruct the exact green R05-A adapter")
    return adapter


class PpR05GreetingsStateMigrationTest(unittest.TestCase):
    def test_adapter_reconstructs_previous_green_commit(self):
        self.assertEqual(PREVIOUS_ADAPTER, git_blob(restore_r05a_adapter(source("MomoSocialAdapter.kt")).encode("utf-8")))

    def test_existing_controller_bodies_and_checkpoint_logic_are_unchanged(self):
        for name, start, end, expected in (
            ("MomoSayHiNavigationFlowController.kt", "    @Volatile var receivedGreetingsEntryTapPending:", "    init {",
             "a2b8bb566c4e3a819e0ce332c11c130055a0366e"),
            ("MomoReceivedGreetingsEntrySearchController.kt", "    @Volatile var receivedGreetingsEntrySearchWindowIdentity:", "    init {",
             "7d344a81f7fd324d09dd41c6520515ab42d4d608"),
            ("MomoSayHiTraversalState.kt", "    /** Business-visible read-only wait;", "    private val processedRowKeys",
             "8d5daa83fe133cc685494460d0c786b0d9dd9590"),
        ):
            with self.subTest(name=name):
                current = source(name)
                inserted = region(current, start, end)
                previous = replace_once(current, inserted + "\n\n", "")
                self.assertEqual(expected, git_blob(previous.encode("utf-8")))
        adapter = source("MomoSocialAdapter.kt")
        self.assertNotIn("private val sayHiRunSendReceipts", adapter)
        self.assertNotIn("private val sayHiVerifiedReturnReceipts", adapter)
        self.assertIn("        synchronized(this) {\n            sayHiTraversalState.clearRunReturnReceipts()\n        }", adapter)
