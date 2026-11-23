"""P2 regression gates: policy-owned history decisions, unchanged runtime safety owners."""
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
POLICY_ROOT = "business/src/main/kotlin/com/freedom/features/aireply/policy/"
APP = "app/src/main/java/com/freedom/app/AppCompositionRoot.kt"

def strip_noncode(text: str) -> str:
    return re.sub(r'"""[\s\S]*?"""|/\*[\s\S]*?\*/|//[^\n]*|"(?:\\.|[^"\\])*"', "", text)

class AiReplyBusinessPoliciesBoundaryTest(unittest.TestCase):
    def test_separate_policies_declare_distinct_history_scope_but_same_model_contract(self):
        greetings = (ROOT / (POLICY_ROOT + "ReceivedGreetingsPolicy.kt")).read_text(encoding="utf-8")
        private = (ROOT / (POLICY_ROOT + "PrivateMessagesPolicy.kt")).read_text(encoding="utf-8")
        self.assertIn("ModelContextScope.CURRENT_WINDOW_ONLY", greetings)
        self.assertIn("ModelContextScope.VERIFIED_HISTORY_WITH_CURRENT_WINDOW", private)
        for source in (greetings, private):
            self.assertIn(": TaskPolicy", source)
            self.assertIn("AiReplyModelContracts.currentOutput()", source)

    def test_composition_uses_strategies_for_private_identity_and_draft_history(self):
        app = (ROOT / APP).read_text(encoding="utf-8")
        preload = app.split("prepareDraftHistory = { identity, snapshot ->", 1)[1].split(
            "onDraftConversationObserved = {", 1)[0]
        private = app.split("onDraftConversationObserved = {", 1)[1].split(
            "onGreetingDraftConversationObserved = {", 1)[0]
        greeting = app.split("onGreetingDraftConversationObserved = {", 1)[1].split(
            "onSendTransaction = {", 1)[0]
        self.assertIn("root.aiReplyPolicyRegistry.resolve(AiDecisionTaskIds.PRIVATE_MESSAGES)", preload)
        self.assertIn("root.aiReplyPolicyRegistry.resolve(AiDecisionTaskIds.PRIVATE_MESSAGES)", private)
        self.assertIn("privatePolicy.contextPolicy().verifiedHistoryIdentity(identity)", preload)
        self.assertIn("privatePolicy.contextPolicy().verifiedHistoryIdentity(identity)", private)
        self.assertIn("privatePolicy.contextPolicy().selectHistory(history)", private)
        self.assertIn("root.aiReplyPolicyRegistry.resolve(AiDecisionTaskIds.RECEIVED_GREETINGS)", greeting)
        self.assertIn("greetingPolicy.contextPolicy().selectHistory(null)", greeting)
        self.assertNotIn("loadDraftHistory(", greeting)
        self.assertNotIn("ContactHistoryResolver(", greeting)
        self.assertIn("taskId = privatePolicy.taskId", private)
        self.assertIn("taskId = greetingPolicy.taskId", greeting)

    def test_policies_cannot_take_over_page_read_ocr_gestures_or_send(self):
        for name in ("ReceivedGreetingsPolicy.kt", "PrivateMessagesPolicy.kt", "AiReplyTaskPolicyDefaults.kt"):
            source = strip_noncode((ROOT / (POLICY_ROOT + name)).read_text(encoding="utf-8"))
            with self.subTest(name=name):
                self.assertNotRegex(source, r"\bimport\s+(?:android|androidx|com\.freedom\.adapters|com\.freedom\.data)\.")
                self.assertNotRegex(source, r"\b(?:dispatchGesture|performAction|sendDraft|writeDraft|"
                                            r"readRecentMessages|persistChatSample|schedule|claimOwnership)\s*\(")
                self.assertNotRegex(source, r"\b(?:ChatPagePort|ChatMessagePort|GestureApi|"
                                            r"TaskCoordinator|UnifiedRecoveryTransactionManager)\b")

if __name__ == "__main__":
    unittest.main()
