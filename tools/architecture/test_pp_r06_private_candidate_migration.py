"""R06-C: exact inverse of the reviewed private candidate evidence relocation."""
import unittest
from pathlib import Path
from tools.architecture.test_pp_r00_freeze import git_blob
from tools.architecture.test_pp_r06_greeting_evidence_migration import restore_r06c_runtime

ROOT = Path(__file__).resolve().parents[2]
AI = ROOT / "business/src/main/kotlin/com/freedom/features/aireply/"
PREVIOUS = "a20bf91b008cecd8146a0a7135f4ad46e5090757"
OWNER = "d02c68e6bd40a3ab88de3e518e126dc9af598d1f"
REVERSE_CALLS = [
    ["                \"AI 会话管理：未读证据审计；\" + formatUnreadAudit(\n                    snapshot, snapshotAmbiguousKeys,\n                    privateMessagesTraversalTaskExecutor.ambiguousSessionIdentityKeys,\n                    privateMessagesTraversalTaskExecutor.attemptedSessionKeys\n                ) +","                \"AI 会话管理：未读证据审计；\" + formatUnreadAudit(snapshot, snapshotAmbiguousKeys) +"],
    ["            val verifiedOnly = hasOnlyVerifiedEligiblePrivateRows(snapshot)","            val eligibleRows = snapshot.chatListCandidates.filter(ChatSessionManagementPolicy::isEligible)\n            val verifiedOnly = eligibleRows.isNotEmpty() && eligibleRows.all {\n                it.contactIdentity?.confidence == ContactIdentityConfidence.PLATFORM_ID_VERIFIED &&\n                    !it.contactIdentity?.contactId.isNullOrBlank()\n            }"],
    ["            val affectedRows = countAffectedEligibleRows(fresh, newlyAmbiguousFreshKeys)","            val affectedRows = fresh.chatListCandidates.count {\n                ChatSessionManagementPolicy.stableSessionKey(it) in newlyAmbiguousFreshKeys &&\n                    ChatSessionManagementPolicy.isEligible(it)\n            }"],
    ["            val affectedRows = countAffectedEligibleRows(snapshot, newlyAmbiguousKeys)","            val affectedRows = snapshot.chatListCandidates.count {\n                ChatSessionManagementPolicy.stableSessionKey(it) in newlyAmbiguousKeys &&\n                    ChatSessionManagementPolicy.isEligible(it)\n            }"],
    ["                        val eligibleUnread = countEligiblePrivateRows(inspection, ChatUnreadStatus.UNREAD)\n                        val eligibleUnknown = countEligiblePrivateRows(inspection, ChatUnreadStatus.UNKNOWN)","                        val eligibleUnread = inspection.chatListCandidates.count {\n                            it.unreadStatus == ChatUnreadStatus.UNREAD && ChatSessionManagementPolicy.isEligible(it)\n                        }\n                        val eligibleUnknown = inspection.chatListCandidates.count {\n                            it.unreadStatus == ChatUnreadStatus.UNKNOWN && ChatSessionManagementPolicy.isEligible(it)\n                        }"],
]
AUDIT_END = "    private fun scheduleSessionListStabilityRetry("

def replace_once(source, old, new):
    if source.count(old) != 1:
        raise AssertionError("Missing or duplicate R06-C anchor: " + old[:100])
    return source.replace(old, new, 1)

def restore_r06b_runtime(runtime, owner=None):
    runtime = restore_r06c_runtime(runtime)
    if owner is None:
        owner = (AI / "PrivateMessageCandidateEvidence.kt").read_text(encoding="utf-8")
    if git_blob(owner.encode("utf-8")) != OWNER:
        raise AssertionError("R06-C candidate evidence implementation changed")
    for current, previous in REVERSE_CALLS:
        runtime = replace_once(runtime, current, previous)
    audit = owner[owner.index("internal fun formatUnreadAudit("):].rstrip()
    audit = replace_once(audit, "internal fun formatUnreadAudit(", "private fun formatUnreadAudit(")
    audit = replace_once(audit, "snapshotAmbiguousKeys: Set<String>,\n    ambiguousSessionIdentityKeys: Set<String>,\n    attemptedSessionKeys: Set<String>\n", "snapshotAmbiguousKeys: Set<String>\n")
    audit = audit.replace("ambiguousSessionIdentityKeys", "privateMessagesTraversalTaskExecutor.ambiguousSessionIdentityKeys")
    audit = audit.replace("attemptedSessionKeys", "privateMessagesTraversalTaskExecutor.attemptedSessionKeys")
    audit = "\n".join("    " + line if line else "" for line in audit.split("\n")) + "\n\n"
    runtime = replace_once(runtime, AUDIT_END, audit + AUDIT_END)
    if git_blob(runtime.encode("utf-8")) != PREVIOUS:
        raise AssertionError("R06-C did not reconstruct the exact R06-B runtime")
    return runtime

class PpR06PrivateCandidateMigrationTest(unittest.TestCase):
    def test_exact_inverse_and_pure_candidate_evidence(self):
        runtime = (AI / "AiReplyObservationRuntime.kt").read_text(encoding="utf-8")
        owner = (AI / "PrivateMessageCandidateEvidence.kt").read_text(encoding="utf-8")
        self.assertEqual(PREVIOUS, git_blob(restore_r06b_runtime(runtime, owner).encode("utf-8")))
        self.assertNotIn("private fun formatUnreadAudit(", runtime)
        for method in ("countEligiblePrivateRows", "countAffectedEligibleRows", "hasOnlyVerifiedEligiblePrivateRows", "formatUnreadAudit"):
            self.assertEqual(1, owner.count("internal fun " + method + "("))
        for forbidden in ("PrivateMessagesTraversalTaskExecutor", "TaskCoordinator", "ChatPagePort", "GestureApi", "TaskScheduler", "blockSessionIdentityKey(", "checkpoint(", "inspectChatPage("):
            self.assertNotIn(forbidden, owner)

    def test_candidate_geometry_identity_and_unread_mutations_fail_closed(self):
        runtime = (AI / "AiReplyObservationRuntime.kt").read_text(encoding="utf-8")
        owner = (AI / "PrivateMessageCandidateEvidence.kt").read_text(encoding="utf-8")
        for old, new in (
            ("it.unreadStatus == unreadStatus && ChatSessionManagementPolicy.isEligible(it)", "true"),
            ("ChatSessionManagementPolicy.stableSessionKey(it) in ambiguousKeys", "true"),
            ("eligibleRows.isNotEmpty() && eligibleRows.all", "eligibleRows.all"),
            ("!it.contactIdentity?.contactId.isNullOrBlank()", "true"),
            ("key !in snapshotAmbiguousKeys && key !in ambiguousSessionIdentityKeys", "true"),
        ):
            self.assertIn(old, owner)
            with self.subTest(old=old), self.assertRaises(AssertionError):
                restore_r06b_runtime(runtime, owner.replace(old, new, 1))
        with self.assertRaises(AssertionError):
            restore_r06b_runtime(runtime.replace("countEligiblePrivateRows(inspection, ChatUnreadStatus.UNREAD)", "0", 1), owner)
