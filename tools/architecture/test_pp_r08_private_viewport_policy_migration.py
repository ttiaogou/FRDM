"""R08-C exact relocation of pure private-list viewport evidence, with historical R07 inverse."""
import unittest
from pathlib import Path
from tools.architecture.test_pp_r00_freeze import git_blob

ROOT = Path(__file__).resolve().parents[2]
EXECUTOR = "business/src/main/kotlin/com/freedom/features/aireply/PrivateMessagesTraversalTaskExecutor.kt"
POLICY = "business/src/main/kotlin/com/freedom/features/aireply/ports/ChatSessionManagementPolicy.kt"
EXECUTOR_PREVIOUS = "1d12a483673719255862b5c512c8db9fd9219fad"
POLICY_PREVIOUS = "fb8d97a3a33e6c576cc636f7eb95b00f838d6167"
POLICY_BLOCK = "    internal const val COLLECTION_POSITION_PREFIX = \"collection-position:\"\n\n    internal fun visibleSessionKeys(snapshot: ChatPageSnapshot): List<String> =\n        ChatSessionManagementPolicy.orderBottomToTop(\n            snapshot.chatListCandidates.filter {\n                ChatSessionManagementPolicy.isCompleteBoundaryEvidence(it, snapshot.chatListViewport)\n            }\n        ).mapNotNull { candidate ->\n            val identityKey = ChatSessionManagementPolicy.stableSessionKey(candidate)\n            if (identityKey.isBlank()) return@mapNotNull null\n            // Keep exact geometry for boundary, identity-return and checkpoint proof.\n            // Position continuity is evaluated separately; row indexes are not identities.\n            \"$identityKey@${candidate.rowBounds.left},${candidate.rowBounds.top},\" +\n                \"${candidate.rowBounds.right},${candidate.rowBounds.bottom}\"\n        }\n\n    private fun collectionPositionKey(snapshot: ChatPageSnapshot): String? =\n        validatedCollectionRows(snapshot)?.let { rows ->\n            \"$COLLECTION_POSITION_PREFIX${snapshot.windowIdentityKey()}:${rows.first()}:${rows.last()}\"\n        }\n\n    internal fun collectionPositionFromKey(key: String?): Pair<String, IntRange>? {\n        if (key == null || !key.startsWith(COLLECTION_POSITION_PREFIX)) return null\n        val last = key.substringAfterLast(':').toIntOrNull() ?: return null\n        val prefix = key.substringBeforeLast(':')\n        val first = prefix.substringAfterLast(':').toIntOrNull() ?: return null\n        if (first < 0 || last < first) return null\n        return prefix.substringBeforeLast(':') to (first..last)\n    }\n\n    internal fun orderedIdentityKeys(keys: List<String>): List<String> =\n        keys.filterNot { it.startsWith(COLLECTION_POSITION_PREFIX) }\n            .map { it.substringBefore('@') }.filter(String::isNotBlank)\n\n    /** Transient scroll baseline: compare row range first; retain old evidence as fallback. */\n    internal fun scrollPositionKeys(snapshot: ChatPageSnapshot): List<String> {\n        val legacy = visibleSessionKeys(snapshot)\n        if (legacy.isEmpty()) return legacy\n        return collectionPositionKey(snapshot)?.let { listOf(it) + legacy } ?: legacy\n    }\n\n    /** Keep the independent, complete-row boundary stability check; never downgrade a\n     * collection-proven boundary to an identity-only observation when evidence disappears. */\n    internal fun boundaryConfirmationKeys(snapshot: ChatPageSnapshot): List<String> {\n        val legacy = visibleSessionKeys(snapshot)\n        if (legacy.isEmpty()) return legacy\n        val position = collectionPositionKey(snapshot) ?: return legacy\n        return listOf(\"$position:${snapshot.chatListCollectionRowCount}:\" +\n            \"${snapshot.chatListCanScrollForward}:${snapshot.chatListCanScrollBackward}\") + legacy\n    }\n\n"
POLICY_ANCHOR = "object ChatSessionManagementPolicy {\n"
EXECUTOR_INVERSE = [
  [
    "    /** Keep the independent, complete-row boundary stability check; never downgrade a\n     * collection-proven boundary to an identity-only observation when evidence disappears. */\n    fun boundaryConfirmationKeys(snapshot: ChatPageSnapshot): List<String> =\n        ChatSessionManagementPolicy.boundaryConfirmationKeys(snapshot)\n",
    "    /** Keep the independent, complete-row boundary stability check; never downgrade a\n     * collection-proven boundary to an identity-only observation when evidence disappears. */\n    fun boundaryConfirmationKeys(snapshot: ChatPageSnapshot): List<String> {\n        val legacy = visibleSessionKeys(snapshot)\n        if (legacy.isEmpty()) return legacy\n        val position = collectionPositionKey(snapshot) ?: return legacy\n        return listOf(\"$position:${snapshot.chatListCollectionRowCount}:\" +\n            \"${snapshot.chatListCanScrollForward}:${snapshot.chatListCanScrollBackward}\") + legacy\n    }\n",
    1
  ],
  [
    "    /** Transient scroll baseline: compare row range first; retain old evidence as fallback. */\n    fun scrollPositionKeys(snapshot: ChatPageSnapshot): List<String> =\n        ChatSessionManagementPolicy.scrollPositionKeys(snapshot)\n",
    "    /** Transient scroll baseline: compare row range first; retain old evidence as fallback. */\n    fun scrollPositionKeys(snapshot: ChatPageSnapshot): List<String> {\n        val legacy = visibleSessionKeys(snapshot)\n        if (legacy.isEmpty()) return legacy\n        return collectionPositionKey(snapshot)?.let { listOf(it) + legacy } ?: legacy\n    }\n",
    1
  ],
  [
    "    private fun orderedIdentityKeys(keys: List<String>): List<String> =\n        ChatSessionManagementPolicy.orderedIdentityKeys(keys)\n",
    "    private fun orderedIdentityKeys(keys: List<String>): List<String> =\n        keys.filterNot { it.startsWith(COLLECTION_POSITION_PREFIX) }\n            .map { it.substringBefore('@') }.filter(String::isNotBlank)\n",
    1
  ],
  [
    "    private fun collectionPositionFromKey(key: String?): Pair<String, IntRange>? =\n        ChatSessionManagementPolicy.collectionPositionFromKey(key)\n",
    "    private fun collectionPositionFromKey(key: String?): Pair<String, IntRange>? {\n        if (key == null || !key.startsWith(COLLECTION_POSITION_PREFIX)) return null\n        val last = key.substringAfterLast(':').toIntOrNull() ?: return null\n        val prefix = key.substringBeforeLast(':')\n        val first = prefix.substringAfterLast(':').toIntOrNull() ?: return null\n        if (first < 0 || last < first) return null\n        return prefix.substringBeforeLast(':') to (first..last)\n    }\n",
    1
  ],
  [
    "    /** Record only an independently observed list, never a stale coordinate or row target. */\n",
    "    private fun collectionPositionKey(snapshot: ChatPageSnapshot): String? =\n        collectionRows(snapshot)?.let { rows ->\n            \"$COLLECTION_POSITION_PREFIX${snapshot.windowIdentityKey()}:${rows.first()}:${rows.last()}\"\n        }\n\n    /** Record only an independently observed list, never a stale coordinate or row target. */\n",
    1
  ],
  [
    "    fun visibleSessionKeys(snapshot: ChatPageSnapshot): List<String> =\n        ChatSessionManagementPolicy.visibleSessionKeys(snapshot)\n",
    "    fun visibleSessionKeys(snapshot: ChatPageSnapshot): List<String> =\n        ChatSessionManagementPolicy.orderBottomToTop(\n            snapshot.chatListCandidates.filter {\n                ChatSessionManagementPolicy.isCompleteBoundaryEvidence(it, snapshot.chatListViewport)\n            }\n        ).mapNotNull { candidate ->\n            val identityKey = ChatSessionManagementPolicy.stableSessionKey(candidate)\n            if (identityKey.isBlank()) return@mapNotNull null\n            // Keep exact geometry for boundary, identity-return and checkpoint proof.\n            // Position continuity is evaluated separately; row indexes are not identities.\n            \"$identityKey@${candidate.rowBounds.left},${candidate.rowBounds.top},\" +\n                \"${candidate.rowBounds.right},${candidate.rowBounds.bottom}\"\n        }\n",
    1
  ],
  [
    "private const val UNREAD_BOUNDARY_STABILITY_CONFIRMATIONS = 2\n",
    "private const val UNREAD_BOUNDARY_STABILITY_CONFIRMATIONS = 2\nprivate const val COLLECTION_POSITION_PREFIX = \"collection-position:\"\n",
    1
  ],
  [
    "import com.freedom.features.aireply.ports.ChatSessionManagementPolicy\nimport com.freedom.features.aireply.ports.ChatSessionManagementPolicy.COLLECTION_POSITION_PREFIX\n",
    "import com.freedom.features.aireply.ports.ChatSessionManagementPolicy\n",
    1
  ]
]

def restore_r08b_private_owner(executor, policy=None):
    if policy is None:
        policy = (ROOT / POLICY).read_text(encoding="utf-8")
    anchor = POLICY_ANCHOR + POLICY_BLOCK
    if policy.count(anchor) != 1:
        raise AssertionError("R08-C policy migration anchor changed")
    restored_policy = policy.replace(anchor, POLICY_ANCHOR, 1)
    if git_blob(restored_policy.encode("utf-8")) != POLICY_PREVIOUS:
        raise AssertionError("R08-C prior policy did not reconstruct")
    for current, prior, count in EXECUTOR_INVERSE:
        if executor.count(current) != count:
            raise AssertionError("R08-C executor inverse anchor changed: " + current[:100])
        executor = executor.replace(current, prior)
    if git_blob(executor.encode("utf-8")) != EXECUTOR_PREVIOUS:
        raise AssertionError("R08-C prior traversal owner did not reconstruct")
    return executor

class PpR08PrivateViewportPolicyMigrationTest(unittest.TestCase):
    def setUp(self):
        self.executor = (ROOT / EXECUTOR).read_text(encoding="utf-8")
        self.policy = (ROOT / POLICY).read_text(encoding="utf-8")

    def test_exact_inverse_and_pure_policy_delegation(self):
        self.assertEqual(EXECUTOR_PREVIOUS,
            git_blob(restore_r08b_private_owner(self.executor, self.policy).encode("utf-8")))
        for name in ("visibleSessionKeys", "collectionPositionFromKey",
                     "orderedIdentityKeys", "scrollPositionKeys", "boundaryConfirmationKeys"):
            self.assertEqual(1, self.executor.count("ChatSessionManagementPolicy." + name + "("))
            self.assertEqual(1, self.policy.count("fun " + name + "("))
        self.assertEqual(1, self.policy.count('const val COLLECTION_POSITION_PREFIX = "collection-position:"'))
        self.assertNotIn("const val COLLECTION_POSITION_PREFIX", self.executor)
        for token in ("var ", "ScheduledTask", "TaskLease", "GestureApi", "Repository", "import android."):
            self.assertNotIn(token, POLICY_BLOCK)
        for token in ("if (scanContinuityRecoveryPending || keys.isEmpty() || snapshot.chatListExtractionTruncated) return",
                      "lastStableSessionListVisibleKeys = keys",
                      "lastObservedViewportPosition = window to collectionRows(snapshot)",
                      "fun resumeAfterReturnedInbox()", "fun renewExhaustedPrivateSelection()"):
            self.assertIn(token, self.executor)

    def test_mutations_reject_downgraded_viewport_evidence_and_state_loss(self):
        for old, new in (
            ("ChatSessionManagementPolicy.isCompleteBoundaryEvidence(it, snapshot.chatListViewport)", "true"),
            ("if (first < 0 || last < first) return null", "Unit"),
            ("if (legacy.isEmpty()) return legacy", "Unit"),
            ("validatedCollectionRows(snapshot)?.let { rows ->", "listOf(0).let { rows ->"),
        ):
            self.assertIn(old, self.policy)
            with self.subTest(old=old), self.assertRaises(AssertionError):
                restore_r08b_private_owner(self.executor, self.policy.replace(old, new, 1))
        for old, new in (
            ("ChatSessionManagementPolicy.scrollPositionKeys(snapshot)", "visibleSessionKeys(snapshot)"),
            ("lastStableSessionListVisibleKeys = keys", "Unit"),
            ("clearRecoveryViewportEvidence()", "Unit"),
        ):
            self.assertIn(old, self.executor)
            with self.subTest(old=old), self.assertRaises(AssertionError):
                restore_r08b_private_owner(self.executor.replace(old, new, 1), self.policy)
