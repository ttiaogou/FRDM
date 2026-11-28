"""R07-B: exact page observation state/behavior relocation and regression wiring."""
import unittest
from pathlib import Path
from tools.architecture.test_pp_r00_freeze import git_blob

ROOT = Path(__file__).resolve().parents[2]
AI = ROOT / "business/src/main/kotlin/com/freedom/features/aireply/"
PREVIOUS = "ee67d98af55bdcba4270128132db9b5317f6c942"
OWNER = "26ee9be133eb554f0b08bd000dd055f0164a95b8"
# Exact reviewed inverse substitutions; the live owner must also match its full reviewed blob.
INVERSE = [
  [
    "pageObservationCoordinator.lastRegionFingerprint",
    "lastRegionFingerprint",
    7
  ],
  [
    "pageObservationCoordinator.lastConfirmedChatListInspection",
    "lastConfirmedChatListInspection",
    3
  ],
  [
    "pageObservationCoordinator.lastPageInspection",
    "lastPageInspection",
    55
  ],
  [
    "pageObservationCoordinator.pendingPageReadPurpose",
    "pendingPageReadPurpose",
    4
  ],
  [
    "pageObservationCoordinator.clearLegacyRegionRefreshHandle()",
    "regionRefreshTask = null",
    5
  ],
  [
    "pageObservationCoordinator.cancelLegacyRegionRefreshHandle()",
    "regionRefreshTask?.cancel()",
    5
  ],
  [
    "!pageObservationCoordinator.hasExplicitInspection()",
    "pageInspectionTask == null",
    2
  ],
  [
    "pageObservationCoordinator.hasExplicitInspection()",
    "pageInspectionTask != null",
    4
  ],
  [
    "pageObservationCoordinator.clearExplicitInspectionHandle()",
    "pageInspectionTask = null",
    4
  ],
  [
    "pageObservationCoordinator.cancelExplicitInspectionHandle()",
    "pageInspectionTask?.cancel()",
    3
  ],
  [
    "    private fun stabilizeIncomingPageSnapshot(incoming: ChatPageSnapshot): ChatPageSnapshot =\n        pageObservationCoordinator.stabilizeIncomingPageSnapshot(incoming, ::isSemanticallyUnconfirmed, onLog)\n\n",
    "    /**\n     * Keeps the last positively confirmed inbox identity as comparison-only evidence across\n     * transient UNKNOWN samples. It never supplies coordinates or node paths to an action.\n     */\n    private fun stabilizeIncomingPageSnapshot(incoming: ChatPageSnapshot): ChatPageSnapshot {\n        val confirmed = lastConfirmedChatListInspection\n            ?: lastPageInspection?.takeIf { it.pageKind == ChatPageKind.CHAT_LIST }\n        val stable = stabilizeConfirmedChatList(confirmed, incoming)\n        when {\n            stable.pageKind == ChatPageKind.CHAT_LIST -> lastConfirmedChatListInspection = stable\n            isSemanticallyUnconfirmed(stable, confirmed) && confirmed != null &&\n                confirmed.packageName == stable.packageName &&\n                confirmed.hasSameWindowIdentity(stable) ->\n                lastConfirmedChatListInspection = confirmed\n            else -> lastConfirmedChatListInspection = null\n        }\n        lastPageInspection = stable\n        if (stable !== incoming) {\n            onLog(\"AI 页面稳定性[TRANSIENT_UNKNOWN_NORMALIZED]：新快照的窗口代次、消息Tab锚点和可见主列表视口一致；使用新快照数据，不复用旧节点路径或坐标\")\n        }\n        return stable\n    }\n\n",
    1
  ],
  [
    "        pageObservationCoordinator.scheduleExplicitFullInspection(delayMs, purpose, ::runFullInspection)",
    "        pageObservationCoordinator.cancelFullInspection()\n        pageObservationCoordinator.cancelRegionRefresh()\n        val token = ++fullInspectionCallbackToken\n        pageInspectionTask?.cancel()\n        pageInspectionTask = null\n        pendingPageReadPurpose = purpose\n        pageInspectionTask = sharedPageObservationCoordinator.scheduleReadOnlyObservation(delayMs) observation@{\n            // A cancelled callback must not retire a replacement observation or advance a\n            // new child. Reset/route recovery can also clear the pending slot without reposting.\n            if (token != fullInspectionCallbackToken || pageInspectionTask == null) return@observation\n            runFullInspection()\n        }",
    1
  ],
  [
    "        runFullInspection(pageObservationCoordinator.consumeFullInspectionPurpose())",
    "        // An event/watcher can satisfy an ordinary pending full observation before\n        // its explicit timer fires. Retire that timer rather than losing its handle.\n        // Protected send/scroll reads are still shielded at their existing entry gates.\n        ++fullInspectionCallbackToken\n        pageInspectionTask?.cancel()\n        pageInspectionTask = null\n        pageObservationCoordinator.cancelFullInspection()\n        val purpose = pendingPageReadPurpose\n        pendingPageReadPurpose = AiReplyPageReadPurpose.FULL_INSPECTION\n        runFullInspection(purpose)",
    1
  ],
  [
    "    private var lastMessageFingerprint",
    "    /** Startup navigation is page-structure verified and remains bounded. */\n    private var lastPageInspection: ChatPageSnapshot? = null\n    /** Last positively confirmed inbox anchor; survives only same-window transient UNKNOWN samples. */\n    private var lastConfirmedChatListInspection: ChatPageSnapshot? = null\n    private var lastRegionFingerprint = \"\"\n    private var lastMessageFingerprint",
    1
  ],
  [
    "    private val inboxPageRecognizer",
    "    private var pageInspectionTask: ScheduledTask? = null\n    private var fullInspectionCallbackToken = 0L\n    /** Purpose carried with the next scheduled full observation. */\n    private var pendingPageReadPurpose = AiReplyPageReadPurpose.FULL_INSPECTION\n    private var regionRefreshTask: ScheduledTask? = null\n    private val inboxPageRecognizer",
    1
  ]
]

def restore_r06f_runtime(runtime, owner=None):
    if owner is None:
        owner = (AI / "MomoPageObservationCoordinator.kt").read_text(encoding="utf-8")
    if git_blob(owner.encode("utf-8")) != OWNER:
        raise AssertionError("R07-B page observation owner changed")
    for current, previous, count in INVERSE:
        if runtime.count(current) != count:
            raise AssertionError("R07-B inverse anchor changed: " + current[:100])
        runtime = runtime.replace(current, previous)
    if git_blob(runtime.encode("utf-8")) != PREVIOUS:
        raise AssertionError("R07-B did not reconstruct the exact R06-F runtime")
    return runtime

class PpR07PageObservationMigrationTest(unittest.TestCase):
    def test_exact_inverse_and_single_state_owner(self):
        runtime = (AI / "AiReplyObservationRuntime.kt").read_text(encoding="utf-8")
        owner = (AI / "MomoPageObservationCoordinator.kt").read_text(encoding="utf-8")
        self.assertEqual(PREVIOUS, git_blob(restore_r06f_runtime(runtime, owner).encode("utf-8")))
        for field in ("pageInspectionTask", "fullInspectionCallbackToken", "pendingPageReadPurpose",
                      "regionRefreshTask", "lastPageInspection", "lastConfirmedChatListInspection",
                      "lastRegionFingerprint"):
            self.assertNotRegex(runtime, r"\b(?:val|var)\s+" + field + r"\b")
            self.assertRegex(owner, r"\bvar\s+" + field + r"\b")
        self.assertIn("pageObservationCoordinator.scheduleExplicitFullInspection(delayMs, purpose, ::runFullInspection)", runtime)
        self.assertIn("pageObservationCoordinator.consumeFullInspectionPurpose()", runtime)
        self.assertIn("pageInspectionTask = observationCoordinator.scheduleReadOnlyObservation", owner)
        self.assertIn("token != fullInspectionCallbackToken || pageInspectionTask == null", owner)
        self.assertIn("if (!hasProtectedPrivateInspection() && !hasProtectedSendInspection()) runFullInspection()", runtime)
        self.assertIn("pageObservationCoordinator.pendingPageReadPurpose == AiReplyPageReadPurpose.MESSAGE_READ_RETRY", runtime)

    def test_mutations_reject_stale_callbacks_and_cache_regression(self):
        runtime = (AI / "AiReplyObservationRuntime.kt").read_text(encoding="utf-8")
        owner = (AI / "MomoPageObservationCoordinator.kt").read_text(encoding="utf-8")
        for old, new in (
            ("token != fullInspectionCallbackToken || pageInspectionTask == null", "false"),
            ("confirmed.hasSameWindowIdentity(stable)", "true"),
            ("lastPageInspection = stable", "lastPageInspection = incoming"),
        ):
            self.assertIn(old, owner)
            with self.subTest(old=old), self.assertRaises(AssertionError):
                restore_r06f_runtime(runtime, owner.replace(old, new, 1))
        for old, new in (
            ("!hasProtectedPrivateInspection() && !hasProtectedSendInspection()", "true"),
            ("pageObservationCoordinator.clearExplicitInspectionHandle()", "Unit"),
        ):
            self.assertIn(old, runtime)
            with self.subTest(old=old), self.assertRaises(AssertionError):
                restore_r06f_runtime(runtime.replace(old, new, 1), owner)
