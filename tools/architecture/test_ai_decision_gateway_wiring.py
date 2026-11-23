"""P1 regressions: both task callbacks must enter one shared decision Gateway."""
import re
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]


def code(path: str) -> str:
    source = (ROOT / path).read_text(encoding="utf-8")
    return re.sub(r"/\*[\s\S]*?\*/|//[^\n]*|\"(?:\\.|[^\"\\])*\"", "", source)


class AiDecisionGatewayWiringTest(unittest.TestCase):
    def test_draft_coordinator_depends_on_gateway_not_engine_or_transport(self):
        source = code("business/src/main/kotlin/com/freedom/features/aireply/ai/AiReplyDraftCoordinator.kt")
        self.assertIn("private val createGateway:", source)
        self.assertIn("createGateway(configuration).decide(", source)
        self.assertIn("ModelDecisionRequest(", source)
        self.assertNotIn("AiReplyDecisionEngine(", source)
        self.assertNotIn("createProvider(", source)
        self.assertNotIn("AiModelTransport(", source)

    def test_application_uses_one_gateway_factory_and_distinct_business_identities(self):
        source = code("app/src/main/java/com/freedom/app/AppCompositionRoot.kt")
        self.assertEqual(1, len(re.findall(r"createGateway\s*=", source)))
        self.assertIn("EngineBackedAiDecisionGateway(", source)
        self.assertIn("taskId = PrivateMessagesPolicy.taskId", source)
        self.assertIn("taskId = ReceivedGreetingsPolicy.taskId", source)

    def test_gateway_does_not_gain_ui_or_second_provider_implementation(self):
        source = code("business/src/main/kotlin/com/freedom/features/aireply/ai/EngineBackedAiDecisionGateway.kt")
        self.assertIn("AiReplyDecisionEngine(", source)
        self.assertIn("return engine.decide(", source)
        self.assertNotRegex(source, r"\b(?:sendDraft|dispatchGesture|navigateBack|readRecentMessages|"
                                    r"writeDraft|performAction)\s*\(")
        self.assertNotIn("AiModelTransport(", source)


if __name__ == "__main__":
    unittest.main()
