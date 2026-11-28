"""R07-G: paired task state relocation, exact inverse and unique authority."""
import re
import unittest
from pathlib import Path
from tools.architecture.test_pp_r00_freeze import git_blob
from tools.architecture.test_pp_r07_recovery_lifecycle_migration import restore_r07g_runtime

ROOT = Path(__file__).resolve().parents[2]
AI = ROOT / "business/src/main/kotlin/com/freedom/features/aireply/"
PREVIOUS = "87fc911f604f2f8fbfdd7516b161ff715fb6f2d3"
OWNER_PREVIOUS = "3f303752ae0a61e308fbf8182a9811ad932656fe"
OWNER = "85ebbdd6eab50c3085bbc9b2388581c22a531302"
INVERSE = [
  [
    "AiReplyTaskOwnership.PairWakeMode",
    "PairWakeMode",
    8
  ],
  [
    "AiReplyTaskOwnership.PairWake",
    "PairWake",
    1
  ],
  [
    "taskOwnership.resetCrossTaskGreetingsHandoffRetry",
    "resetCrossTaskGreetingsHandoffRetry",
    3
  ],
  [
    "taskOwnership.crossTaskGreetingsHandoffRetryTask",
    "crossTaskGreetingsHandoffRetryTask",
    5
  ],
  [
    "taskOwnership.crossTaskGreetingsHandoffRetryGate",
    "crossTaskGreetingsHandoffRetryGate",
    5
  ],
  [
    "taskOwnership.privateRelocationRetryOwed",
    "privateRelocationRetryOwed",
    4
  ],
  [
    "taskOwnership.pairWake",
    "pairWake",
    16
  ],
  [
    "taskOwnership.privateQuotaReturnReady",
    "privateQuotaReturnReady",
    3
  ],
  [
    "taskOwnership.pendingPrivateQuotaReceipt",
    "pendingPrivateQuotaReceipt",
    2
  ],
  [
    "taskOwnership.greetingSliceQuota",
    "greetingSliceQuota",
    4
  ],
  [
    "taskOwnership.privateSliceQuota",
    "privateSliceQuota",
    5
  ],
  [
    "taskOwnership.privateToGreetingsHandoff",
    "privateToGreetingsHandoff",
    5
  ],
  [
    "        taskOwnership.clearPairWake()\n        pageRecoveryVerificationGate.reset(NEXT_PAIR_WAKE_GATE)",
    "        pairWake = null\n        pageRecoveryVerificationGate.reset(NEXT_PAIR_WAKE_GATE)",
    1
  ],
  [
    "        val wake = taskOwnership.requestPairWake(mode, due)",
    "        val wake = PairWake(mode, due)\n        pairWake = wake",
    1
  ],
  [
    "            taskOwnership.clearPrivateQuotaReceipt()",
    "            pendingPrivateQuotaReceipt = null\n            privateQuotaReturnReady = false",
    1
  ],
  [
    "                        taskOwnership.retainPrivateQuotaReceipt(sendConfirmationId)",
    "                        pendingPrivateQuotaReceipt = sendConfirmationId\n                        privateQuotaReturnReady = false",
    1
  ],
  [
    "        taskOwnership.markPrivateQuotaReturnReady(sendConfirmationId)",
    "        if (sendConfirmationId != null && pendingPrivateQuotaReceipt == sendConfirmationId) {\n            privateQuotaReturnReady = true\n        }",
    1
  ],
  [
    "taskOwnership.discardUnreadyPrivateQuotaReceipt()",
    "if (!privateQuotaReturnReady) pendingPrivateQuotaReceipt = null",
    2
  ],
  [
    "        taskOwnership.resetPairedRun()",
    "        privateToGreetingsHandoff = null\n        privateRelocationRetryOwed = false\n        resetPairedQuotaRun()",
    2
  ],
  [
    "    /**\n     * Re-arm",
    "    private fun resetCrossTaskGreetingsHandoffRetry() {\n        crossTaskGreetingsHandoffRetryTask?.cancel()\n        crossTaskGreetingsHandoffRetryTask = null\n        crossTaskGreetingsHandoffRetryGate.reset()\n    }\n\n    /**\n     * Re-arm",
    1
  ],
  [
    "    private fun handlePrivateQuotaBoundary",
    "    private fun resetPairedQuotaRun() {\n        privateSliceQuota.resetRun()\n        greetingSliceQuota.resetRun()\n        pendingPrivateQuotaReceipt = null\n        privateQuotaReturnReady = false\n    }\n\n    private fun handlePrivateQuotaBoundary",
    1
  ],
  [
    "    private var receivedGreetingsRecoveryVerificationPending",
    "    /** Limits repeated handoff lease churn while the original private checkpoint stays pending. */\n    private val crossTaskGreetingsHandoffRetryGate = CrossTaskGreetingsHandoffRetryGate()\n    private var crossTaskGreetingsHandoffRetryTask: ScheduledTask? = null\n    private var receivedGreetingsRecoveryVerificationPending",
    1
  ],
  [
    "    /** Startup stays provisional",
    "    // Task-local yield and pair wake are independent facts.\n    private var privateToGreetingsHandoff: AiReplyTaskYieldReason? = null\n    private val privateSliceQuota = AiReplyTaskSliceQuota()\n    private val greetingSliceQuota = AiReplyTaskSliceQuota()\n    private var pendingPrivateQuotaReceipt: String? = null\n    private var privateQuotaReturnReady = false\n    private enum class PairWakeMode { REFRESH_PRIVATE, RESUME_PRIVATE }\n    private data class PairWake(val mode: PairWakeMode, var due: Boolean, var retryAttempt: Int = 0)\n    private var pairWake: PairWake? = null\n    // A deferred yield owes the original relocation a retry before yielding again.\n    private var privateRelocationRetryOwed = false\n    /** Startup stays provisional",
    1
  ]
]
OWNER_BLOCK = "    // R07-G: run-local paired-task scheduling facts; the delegate alone owns leases.\n    // Task-local yield and pair wake are independent facts.\n    var privateToGreetingsHandoff: AiReplyTaskYieldReason? = null\n    val privateSliceQuota = AiReplyTaskSliceQuota()\n    val greetingSliceQuota = AiReplyTaskSliceQuota()\n    var pendingPrivateQuotaReceipt: String? = null\n        private set\n    var privateQuotaReturnReady = false\n        private set\n    enum class PairWakeMode { REFRESH_PRIVATE, RESUME_PRIVATE }\n    data class PairWake(val mode: PairWakeMode, var due: Boolean, var retryAttempt: Int = 0)\n    var pairWake: PairWake? = null\n        private set\n    // A deferred yield owes the original relocation a retry before yielding again.\n    var privateRelocationRetryOwed = false\n    /** Limits repeated handoff lease churn while the original private checkpoint stays pending. */\n    val crossTaskGreetingsHandoffRetryGate = CrossTaskGreetingsHandoffRetryGate()\n    var crossTaskGreetingsHandoffRetryTask: ScheduledTask? = null\n\n    fun retainPrivateQuotaReceipt(sendConfirmationId: String) {\n        pendingPrivateQuotaReceipt = sendConfirmationId\n        privateQuotaReturnReady = false\n    }\n\n    fun markPrivateQuotaReturnReady(sendConfirmationId: String?) {\n        if (sendConfirmationId != null && pendingPrivateQuotaReceipt == sendConfirmationId) {\n            privateQuotaReturnReady = true\n        }\n    }\n\n    fun discardUnreadyPrivateQuotaReceipt() {\n        if (!privateQuotaReturnReady) pendingPrivateQuotaReceipt = null\n    }\n\n    fun clearPrivateQuotaReceipt() {\n        pendingPrivateQuotaReceipt = null\n        privateQuotaReturnReady = false\n    }\n\n    fun requestPairWake(mode: PairWakeMode, due: Boolean): PairWake {\n        val wake = PairWake(mode, due)\n        pairWake = wake\n        return wake\n    }\n\n    fun clearPairWake() {\n        pairWake = null\n    }\n\n    fun resetPairedRun() {\n        privateToGreetingsHandoff = null\n        privateRelocationRetryOwed = false\n        privateSliceQuota.resetRun()\n        greetingSliceQuota.resetRun()\n        clearPrivateQuotaReceipt()\n    }\n\n    fun resetCrossTaskGreetingsHandoffRetry() {\n        crossTaskGreetingsHandoffRetryTask?.cancel()\n        crossTaskGreetingsHandoffRetryTask = null\n        crossTaskGreetingsHandoffRetryGate.reset()\n    }\n\n"
FIELDS = ["privateToGreetingsHandoff","privateSliceQuota","greetingSliceQuota","pendingPrivateQuotaReceipt","privateQuotaReturnReady","pairWake","privateRelocationRetryOwed","crossTaskGreetingsHandoffRetryGate","crossTaskGreetingsHandoffRetryTask"]

def restore_r07f_runtime(runtime, owner=None):
    runtime = restore_r07g_runtime(runtime)
    if owner is None:
        owner = (AI / "AiReplyTaskOwnership.kt").read_text(encoding="utf-8")
    if git_blob(owner.encode("utf-8")) != OWNER:
        raise AssertionError("R07-G paired state owner changed")
    for current, prior in ((OWNER_BLOCK, ""), ("import com.freedom.core.runtime.ScheduledTask\n", "")):
        if owner.count(current) != 1:
            raise AssertionError("R07-G owner inverse anchor changed")
        owner = owner.replace(current, prior, 1)
    if git_blob(owner.encode("utf-8")) != OWNER_PREVIOUS:
        raise AssertionError("R07-G did not reconstruct exact original lease facade")
    for current, prior, count in INVERSE:
        if runtime.count(current) != count:
            raise AssertionError("R07-G inverse anchor changed: " + current[:100])
        runtime = runtime.replace(current, prior)
    if git_blob(runtime.encode("utf-8")) != PREVIOUS:
        raise AssertionError("R07-G did not reconstruct exact R07-F Runtime")
    return runtime

class PpR07PairedTaskLifecycleMigrationTest(unittest.TestCase):
    def test_exact_inverse_and_unique_owner(self):
        runtime = (AI / "AiReplyObservationRuntime.kt").read_text(encoding="utf-8")
        owner = (AI / "AiReplyTaskOwnership.kt").read_text(encoding="utf-8")
        self.assertEqual(PREVIOUS, git_blob(restore_r07f_runtime(runtime, owner).encode("utf-8")))
        for name in FIELDS:
            declaration = r"\b(?:var|val)\s+" + re.escape(name) + r"\b"
            self.assertEqual(1, len(re.findall(declaration, owner)), name)
            self.assertNotRegex(runtime, declaration)
        for method in ("retainPrivateQuotaReceipt", "markPrivateQuotaReturnReady",
                       "discardUnreadyPrivateQuotaReceipt", "clearPrivateQuotaReceipt",
                       "requestPairWake", "clearPairWake", "resetPairedRun",
                       "resetCrossTaskGreetingsHandoffRetry"):
            self.assertIn("taskOwnership." + method + "(", runtime)
            self.assertEqual(1, owner.count("fun " + method + "("))
        self.assertIn("private val delegate = TaskExecutionController(", owner)
        self.assertNotIn("scheduler.schedule(", owner)
        self.assertNotIn("dispatchGesture(", owner)

    def test_mutations_reject_invalid_credit_or_wake_and_handoff_lifetime(self):
        runtime = (AI / "AiReplyObservationRuntime.kt").read_text(encoding="utf-8")
        owner = (AI / "AiReplyTaskOwnership.kt").read_text(encoding="utf-8")
        for old, new in (
            ("pendingPrivateQuotaReceipt == sendConfirmationId", "true"),
            ("if (!privateQuotaReturnReady) pendingPrivateQuotaReceipt = null", "pendingPrivateQuotaReceipt = null"),
            ("greetingSliceQuota.resetRun()", "Unit"),
            ("crossTaskGreetingsHandoffRetryTask?.cancel()", "Unit"),
        ):
            self.assertIn(old, owner)
            with self.subTest(old=old), self.assertRaises(AssertionError):
                restore_r07f_runtime(runtime, owner.replace(old, new, 1))
        for old, new in (
            ("taskOwnership.pairWake !== wake", "false"),
            ("taskOwnership.markPrivateQuotaReturnReady(sendConfirmationId)", "Unit"),
            ("taskOwnership.resetPairedRun()", "Unit"),
            ("taskOwnership.privateSliceQuota.record(receipt)", "true"),
        ):
            self.assertIn(old, runtime)
            with self.subTest(old=old), self.assertRaises(AssertionError):
                restore_r07f_runtime(runtime.replace(old, new, 1), owner)
