"""P3 architecture gate: a single exact registry routes both model-context businesses."""
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
BUSINESS = "business/src/main/kotlin/com/freedom/features/aireply/"
APP = "app/src/main/java/com/freedom/app/AppCompositionRoot.kt"

def noncode_removed(text: str) -> str:
    return re.sub(r'"""[\s\S]*?"""|/\*[\s\S]*?\*/|//[^\n]*|"(?:\\.|[^"\\])*"', "", text)

class AiReplyPolicyRegistryWiringTest(unittest.TestCase):
    def test_registry_is_closed_to_unknown_task_ids(self):
        registry = (ROOT / (BUSINESS + "policy/AiReplyPolicyRegistry.kt")).read_text(encoding="utf-8")
        self.assertIn("object AiReplyPolicyRegistry : PolicyRegistry", registry)
        self.assertIn("ReceivedGreetingsPolicy.taskId -> ReceivedGreetingsPolicy", registry)
        self.assertIn("PrivateMessagesPolicy.taskId -> PrivateMessagesPolicy", registry)
        self.assertIn("else -> null", registry)
        self.assertNotRegex(noncode_removed(registry), r"\b(?:claimOwnership|sendDraft|dispatchGesture|readRecentMessages)\s*\(")

    def test_root_shares_one_registry_with_both_callbacks_and_model_path(self):
        root = (ROOT / APP).read_text(encoding="utf-8")
        self.assertIn("val aiReplyPolicyRegistry: PolicyRegistry = AiReplyPolicyRegistry", root)
        self.assertIn("policyRegistry = root.aiReplyPolicyRegistry", root)
        self.assertEqual(2, root.count("root.aiReplyPolicyRegistry.resolve(AiDecisionTaskIds.PRIVATE_MESSAGES)"))
        self.assertEqual(1, root.count("root.aiReplyPolicyRegistry.resolve(AiDecisionTaskIds.RECEIVED_GREETINGS)"))
        self.assertNotIn("PrivateMessagesPolicy.contextPolicy()", root)
        self.assertNotIn("ReceivedGreetingsPolicy.contextPolicy()", root)
        self.assertIn("onDraftConversationObserved = {", root)
        self.assertIn("onGreetingDraftConversationObserved = {", root)

    def test_draft_requests_are_admitted_by_registry_and_retry_keeps_source_policy(self):
        coordinator = (ROOT / (BUSINESS + "ai/AiReplyDraftCoordinator.kt")).read_text(encoding="utf-8")
        self.assertGreaterEqual(coordinator.count("policyRegistry.resolve(taskId)?.takeIf { it.taskId == taskId }"), 2)
        self.assertIn("policy.contextPolicy().selectHistory(history)", coordinator)
        self.assertIn("outputContract = policy.modelContract()", coordinator)
        self.assertIn("taskId = policy.taskId", coordinator)
        self.assertIn("policy, contextScope, emptyRetryAttempt + 1", coordinator)
        self.assertNotIn("AiReplyDecisionEngine(", coordinator)
        self.assertNotIn("outputContract = AiReplyModelContracts.currentOutput()", coordinator)

if __name__ == "__main__":
    unittest.main()
