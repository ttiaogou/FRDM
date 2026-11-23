"""P0 architecture tests: contract types must remain platform- and side-effect-free."""
import re
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
AI = "business/src/main/kotlin/com/freedom/features/aireply/ai/AiDecisionGateway.kt"
POLICY = "business/src/main/kotlin/com/freedom/features/aireply/policy/TaskPolicy.kt"
REGISTRY = "business/src/main/kotlin/com/freedom/features/aireply/policy/PolicyRegistry.kt"


def code_only(text: str) -> str:
    return re.sub(r"/\*[\s\S]*?\*/|//[^\n]*|\"(?:\\.|[^\"\\])*\"",
                  "", text)


class AiDecisionPolicyContractsBoundaryTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.sources = {}
        for path in (AI, POLICY, REGISTRY):
            cls.sources[path] = (ROOT / path).read_text(encoding="utf-8")

    def test_contracts_remain_in_business_and_never_depend_on_platform_adapters(self):
        forbidden = (
            r"\bimport\s+android(?:x)?\.",
            r"\bimport\s+com\.freedom\.(?:adapters|data|application|accessibility|ui)\.",
            r"\b(?:dispatchGesture|executeGesture|performAction|navigateBackToSessionList|"
            r"sendDraft|persistChatSample|setText|onAccessibilityEvent)\s*\(",
            r"\b(?:AccessibilityService|AccessibilityNodeInfo|ChatPageSnapshot|"
            r"GestureApi|TaskCoordinator|UnifiedRecoveryTransactionManager)\b",
        )
        for path, source in self.sources.items():
            with self.subTest(path=path):
                code = code_only(source)
                for rule in forbidden:
                    self.assertNotRegex(code, rule)

    def test_gateway_reuses_existing_model_outcome_without_second_transport(self):
        gateway = code_only(self.sources[AI])
        self.assertRegex(gateway, r"\binterface\s+AiDecisionGateway\b")
        self.assertRegex(gateway, r"\bfun\s+decide\s*\(")
        self.assertIn("typealias ModelDecisionOutcome = AiReplyEvaluation", gateway)
        self.assertNotRegex(gateway, r"\b(?:interface|class)\s+AiModelTransport\b")
        self.assertNotRegex(gateway, r"\b(?:DeepSeekAiModelTransport|OpenAiCompatibleAiModelTransport)\b")

    def test_policy_is_declarative_and_registry_returns_nullable_unknown(self):
        policy = code_only(self.sources[POLICY])
        registry = code_only(self.sources[REGISTRY])
        for declaration in ("TaskPolicy", "TaskTriggerEvidence", "TaskPerceptionConfig",
                            "ContextPolicy", "TaskFailure", "TaskRecoveryDirective"):
            self.assertIn(declaration, policy)
        for function in ("evaluateTrigger", "perceptionConfig", "contextPolicy",
                         "modelContract", "recoveryDirective"):
            self.assertRegex(policy, r"\bfun\s+" + function + r"\s*\(")
        self.assertRegex(registry, r"\binterface\s+PolicyRegistry\b")
        self.assertRegex(registry, r"\bfun\s+resolve\(taskId:\s*String\):\s*TaskPolicy\?")


if __name__ == "__main__":
    unittest.main()
