"""R07-H: exact relocation of existing recovery observation bookkeeping."""
import re
import unittest
from pathlib import Path
from tools.architecture.test_pp_r00_freeze import git_blob
from tools.architecture.test_pp_r09b_legacy_cleanup import restore_r09a_runtime

ROOT = Path(__file__).resolve().parents[2]
AI = ROOT / "business/src/main/kotlin/com/freedom/features/aireply/"
PREVIOUS = "d6871d72fabe6af2af018fdeb709692851260335"
INVERSE = [
  [
    "receivedGreetingsCheckpointVerifier.receivedGreetingsRecoveryRetryAttempt",
    "receivedGreetingsRecoveryRetryAttempt",
    9
  ],
  [
    "messageListCheckpointVerifier.messageRecoveryRetryAttempt",
    "messageRecoveryRetryAttempt",
    10
  ],
  [
    "pageRecoveryVerificationGate.messageListRecoveryVerificationPending",
    "messageListRecoveryVerificationPending",
    11
  ],
  [
    "pageRecoveryVerificationGate.receivedGreetingsRecoveryVerifiedForNextSnapshot",
    "receivedGreetingsRecoveryVerifiedForNextSnapshot",
    3
  ],
  [
    "pageRecoveryVerificationGate.receivedGreetingsRecoveryVerificationPending",
    "receivedGreetingsRecoveryVerificationPending",
    3
  ],
  [
    "pageRecoveryVerificationGate.lastRecoveryAndroidReadinessWait",
    "lastRecoveryAndroidReadinessWait",
    6
  ],
  [
    "transientEmptySnapshotRecovery.snapshotRecoveryStartedAtMs",
    "snapshotRecoveryStartedAtMs",
    9
  ],
  [
    "${messageRecoveryRetryAttempt}",
    "$messageRecoveryRetryAttempt",
    1
  ],
  [
    "        pageRecoveryVerificationGate.clearGreetingResumeProof()",
    "        receivedGreetingsRecoveryVerificationPending = false\n        receivedGreetingsRecoveryVerifiedForNextSnapshot = false",
    2
  ],
  [
    "                pageRecoveryVerificationGate.clearGreetingResumeProof()",
    "                receivedGreetingsRecoveryVerificationPending = false\n                receivedGreetingsRecoveryVerifiedForNextSnapshot = false",
    1
  ],
  [
    "                    pageRecoveryVerificationGate.clearGreetingResumeProof()",
    "                    receivedGreetingsRecoveryVerificationPending = false\n                    receivedGreetingsRecoveryVerifiedForNextSnapshot = false",
    1
  ],
  [
    "            pageRecoveryVerificationGate.retainGreetingResumeProof()",
    "            receivedGreetingsRecoveryVerificationPending = false\n            receivedGreetingsRecoveryVerifiedForNextSnapshot = true",
    1
  ],
  [
    "    /** Generation invalidates callbacks",
    "    private var receivedGreetingsRecoveryVerificationPending = false\n    private var receivedGreetingsRecoveryVerifiedForNextSnapshot = false\n    private var receivedGreetingsRecoveryRetryAttempt = 0\n    private var messageRecoveryRetryAttempt = 0\n    private var messageListRecoveryVerificationPending = false\n    /** Generation invalidates callbacks",
    1
  ],
  [
    "    private var receivedGreetingsUserActive",
    "    private var snapshotRecoveryStartedAtMs = 0L\n    private var receivedGreetingsUserActive",
    1
  ],
  [
    "    /** Diagnostic-only deduplication;",
    "    private var lastRecoveryAndroidReadinessWait: String? = null\n    /** Diagnostic-only deduplication;",
    1
  ]
]
OWNERS = {
  "AiReplySessionTraversalCoordinator.kt": [
    "3a44370c00672af7332e0dd660141241bcf4a8d4",
    "b40194531fa0231cf70a176027b6259cb8e4e2ce",
    "    // Empty-root episode survives the retry-counter reset until fresh page proof is accepted.\n    var snapshotRecoveryStartedAtMs = 0L\n\n"
  ],
  "PageRecoveryVerificationGate.kt": [
    "41136af09eb89e74cc7a8ba00add1676b8f27f60",
    "7404738452eefe21aab1bdb34f3ae60be7d035b5",
    "    // Route-admission bookkeeping, separate from the shared recovery reducer.\n    var lastRecoveryAndroidReadinessWait: String? = null\n    var receivedGreetingsRecoveryVerificationPending = false\n    var receivedGreetingsRecoveryVerifiedForNextSnapshot = false\n    var messageListRecoveryVerificationPending = false\n\n    fun retainGreetingResumeProof() {\n        receivedGreetingsRecoveryVerificationPending = false\n        receivedGreetingsRecoveryVerifiedForNextSnapshot = true\n    }\n\n    fun clearGreetingResumeProof() {\n        receivedGreetingsRecoveryVerificationPending = false\n        receivedGreetingsRecoveryVerifiedForNextSnapshot = false\n    }\n\n"
  ],
  "MessageListCheckpointVerification.kt": [
    "3a58c7dc9690004f184f5c6a7ba0b61cc72a90e5",
    "fb0f8d7449f6e102c5d9ddc48b1d4a0d5f99a456",
    "    // One-shot observation retry debt before or outside the retained checkpoint plan.\n    var messageRecoveryRetryAttempt = 0\n\n"
  ],
  "ReceivedGreetingsCheckpointVerification.kt": [
    "8834d6054710aa10c7dafbbb5b6fe756737b7def",
    "e313a29018b7a10d25b21e78fb196c24a538fe19",
    "    // Route-specific read-only retry debt; the shared plan still owns its own timer.\n    var receivedGreetingsRecoveryRetryAttempt = 0\n\n"
  ]
}
FIELDS = {
  "AiReplySessionTraversalCoordinator.kt": [
    "snapshotRecoveryStartedAtMs"
  ],
  "PageRecoveryVerificationGate.kt": [
    "lastRecoveryAndroidReadinessWait",
    "receivedGreetingsRecoveryVerificationPending",
    "receivedGreetingsRecoveryVerifiedForNextSnapshot",
    "messageListRecoveryVerificationPending"
  ],
  "MessageListCheckpointVerification.kt": [
    "messageRecoveryRetryAttempt"
  ],
  "ReceivedGreetingsCheckpointVerification.kt": [
    "receivedGreetingsRecoveryRetryAttempt"
  ]
}

def restore_r07g_runtime(runtime, owners=None):
    runtime = restore_r09a_runtime(runtime)
    if owners is None:
        owners = {name: (AI / name).read_text(encoding="utf-8") for name in OWNERS}
    for name, (prior, live, block) in OWNERS.items():
        source = owners[name]
        if git_blob(source.encode("utf-8")) != live or source.count(block) != 1:
            raise AssertionError("R07-H live recovery owner changed: " + name)
        if git_blob(source.replace(block, "", 1).encode("utf-8")) != prior:
            raise AssertionError("R07-H failed exact prior owner reconstruction: " + name)
    for current, prior, count in INVERSE:
        if "\n" in current or current.startswith(" "):
            pattern = r"(?m)^" + re.escape(current)
            if len(re.findall(pattern, runtime)) != count:
                raise AssertionError("R07-H line inverse anchor changed: " + current[:100])
            runtime = re.sub(pattern, lambda _: prior, runtime)
        else:
            if runtime.count(current) != count:
                raise AssertionError("R07-H inverse anchor changed: " + current[:100])
            runtime = runtime.replace(current, prior)
    if git_blob(runtime.encode("utf-8")) != PREVIOUS:
        raise AssertionError("R07-H did not reconstruct exact R07-G Runtime")
    return runtime

class PpR07RecoveryLifecycleMigrationTest(unittest.TestCase):
    def test_exact_inverse_and_unique_recovery_observation_owner(self):
        runtime = (AI / "AiReplyObservationRuntime.kt").read_text(encoding="utf-8")
        owners = {name: (AI / name).read_text(encoding="utf-8") for name in OWNERS}
        self.assertEqual(PREVIOUS, git_blob(restore_r07g_runtime(runtime, owners).encode("utf-8")))
        for name, fields in FIELDS.items():
            for field in fields:
                declaration = r"\bvar\s+" + re.escape(field) + r"\b"
                self.assertEqual(1, len(re.findall(declaration, owners[name])), field)
                self.assertNotRegex(runtime, declaration)
                self.assertEqual(1, sum(len(re.findall(declaration, source)) for source in owners.values()))
        self.assertIn("transientEmptySnapshotRecovery.reset()", runtime)
        self.assertIn("transientEmptySnapshotRecovery.snapshotRecoveryStartedAtMs = 0L", runtime)
        self.assertIn("pageRecoveryVerificationGate.retainGreetingResumeProof()", runtime)
        self.assertIn("pageRecoveryVerificationGate.clearGreetingResumeProof()", runtime)
        self.assertIn("messageListCheckpointVerifier.hasRetainedObservationPlan()", runtime)
        self.assertIn("recoveryContext.acknowledgeVerifiedCheckpoint(targetRoute, sharedProof)", runtime)
        self.assertIn("RecoveryEventDispatcher(", runtime)

    def test_mutations_reject_observation_lifetime_or_proof_loss(self):
        runtime = (AI / "AiReplyObservationRuntime.kt").read_text(encoding="utf-8")
        owners = {name: (AI / name).read_text(encoding="utf-8") for name in OWNERS}
        for old, new in (
            ("transientEmptySnapshotRecovery.snapshotRecoveryStartedAtMs = 0L", "Unit"),
            ("pageRecoveryVerificationGate.retainGreetingResumeProof()", "Unit"),
            ("pageRecoveryVerificationGate.clearGreetingResumeProof()", "Unit"),
            ("messageListCheckpointVerifier.hasRetainedObservationPlan()", "false"),
            ("recoveryContext.acknowledgeVerifiedCheckpoint(targetRoute, sharedProof)", "Unit"),
        ):
            self.assertIn(old, runtime)
            with self.subTest(old=old), self.assertRaises(AssertionError):
                restore_r07g_runtime(runtime.replace(old, new, 1), owners)
        for name, old, new in (
            ("AiReplySessionTraversalCoordinator.kt", "retryCount = 0", "retryCount = 1"),
            ("PageRecoveryVerificationGate.kt", "receivedGreetingsRecoveryVerifiedForNextSnapshot = true", "receivedGreetingsRecoveryVerifiedForNextSnapshot = false"),
            ("MessageListCheckpointVerification.kt", "var messageRecoveryRetryAttempt = 0", "var messageRecoveryRetryAttempt = 1"),
            ("ReceivedGreetingsCheckpointVerification.kt", "var receivedGreetingsRecoveryRetryAttempt = 0", "var receivedGreetingsRecoveryRetryAttempt = 1"),
        ):
            self.assertIn(old, owners[name])
            with self.subTest(name=name), self.assertRaises(AssertionError):
                restore_r07g_runtime(runtime, {**owners, name: owners[name].replace(old, new, 1)})
