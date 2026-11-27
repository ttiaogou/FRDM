"""R05-I: integration gate for the reviewed state ownership and cross-controller wiring.

This gate supplements the R05-A..H exact inverse tests, R00/R01 freezes,
M04 action freezes and L07 hard architecture checks. It is not device evidence.
"""
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
MOMO = "app/src/main/java/com/freedom/adapters/social/momo/"
RULES = {
    "app/src/main/java/com/freedom/adapters/social/momo/MomoSocialAdapter.kt": [
        ["private val sayHiTraversalState = MomoSayHiTraversalState(",1],
        ["private val sayHiNavigationFlowController = MomoSayHiNavigationFlowController(",1],
        ["private val receivedGreetingsEntrySearchController = MomoReceivedGreetingsEntrySearchController(",1],
        ["private val draftTransaction = MomoDraftTransactionController(",1],
        ["private val privateFollowUpController = MomoPrivateFollowUpController(",1],
        ["private val imageTransaction = MomoImageSendTransactionController(",1],
        ["private val interceptionTransaction = MomoInterceptionTransactionController(",1],
        ["private var privateFollowUp: PrivateFollowUpTransaction?",0],
        ["private val inboxIdentityCaptureLock",0],
        ["submitPrivateFollowUpStep = privateFollowUpController::submitPrivateFollowUpStep",1],
        ["privateFollowUpController.submitPrivateFollowUpStep(child, PrivateFollowUpStage.WAIT_SEND_RESULT)",2],
        ["privateFollowUpController.submitPrivateFollowUpStep(child, next)",1],
        ["isBoundAiReplyUiOperationOwned = isBoundAiReplyUiOperationOwned,",2],
        ["private fun submitPrivateFollowUpStep(",0],
        ["private fun persistPrivateFollowUpStep(",0],
        ["reconcileInterception = interceptionTransaction::reconcileInterception",1],
        ["observeImage = imageTransaction::observe",1],
        ["observeInterception = interceptionTransaction::observe",1],
    ],
    "app/src/main/java/com/freedom/adapters/social/momo/MomoSocialAdapterComponents.kt": [
        ["val inboxIdentity = MomoInboxIdentityCaptureState(contactIdentityManager)",1],
        ["internal class MomoInboxIdentityCaptureState(",1],
    ],
    "app/src/main/java/com/freedom/adapters/social/momo/MomoPrivateFollowUpController.kt": [
        ["@Volatile private var privateFollowUp: PrivateFollowUpTransaction? = null",1],
        ["val image = MomoImageSendState()",1],
        ["val interceptionState = MomoInterceptionState()",1],
        ["!isBoundAiReplyUiOperationOwned(child.target.taskLease)",1],
        ["if (!persistPrivateFollowUpStep(child, previous))",1],
        ["if (child.inFlight)",1],
    ],
    "app/src/main/java/com/freedom/adapters/social/momo/MomoImageSendTransactionController.kt": [
        ["internal class MomoImageSendTransactionController(",1],
        ["internal class PrivateFollowUpTransaction(",0],
        ["private var privateFollowUp:",0],
    ],
    "app/src/main/java/com/freedom/adapters/social/momo/MomoInterceptionTransactionController.kt": [
        ["internal class MomoInterceptionTransactionController(",1],
        ["private var privateFollowUp:",0],
    ],
    "app/src/main/java/com/freedom/app/AppCompositionRoot.kt": [
        ["MomoSocialAdapter(",1],
        ["inputPort = momoSocialAdapter,",1],
        ["sendPort = momoSocialAdapter,",1],
        ["privateChatFollowUpPort = momoSocialAdapter,",1],
        ["aiReplyObservationRuntime.isPrivateFollowUpWriteBlocked()",1],
        ["inspectObservedChildPageReadiness = { root, activity, mode ->",1],
        ["momoSocialAdapter.inspectObservedPrivateFollowUpReadiness(root, activity, mode)",1],
    ],
    "business/src/main/kotlin/com/freedom/features/aireply/AiReplyObservationRuntime.kt": [
        ["ownsTransaction = { ownsPrivateFollowUp(child) }",1],
        ["val observed = runCatching { port.observe() }",1],
        ["if (!port.privateFollowUpSucceeded())",1],
    ],
}

def integration_violations(sources):
    issues = []
    for path, rules in RULES.items():
        source = sources.get(path)
        if source is None:
            issues.append("Missing integration owner: " + path)
            continue
        for token, expected in rules:
            actual = source.count(token)
            if actual != expected:
                issues.append(f"{path}: {token}: expected {expected}, got {actual}")
    return issues


class PpR05IntegrationAcceptanceTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.sources = {path: (ROOT / path).read_text(encoding="utf-8") for path in RULES}

    def test_reviewed_single_owners_and_live_wiring(self):
        self.assertEqual([], integration_violations(self.sources))

    def test_missing_owner_duplicate_state_and_disconnected_ports_fail_closed(self):
        adapter = MOMO + "MomoSocialAdapter.kt"
        shared = MOMO + "MomoPrivateFollowUpController.kt"
        root = "app/src/main/java/com/freedom/app/AppCompositionRoot.kt"
        for path, old, new in (
            (adapter, "observeImage = imageTransaction::observe", "observeImage = unchecked"),
            (adapter, "observeInterception = interceptionTransaction::observe", "observeInterception = unchecked"),
            (adapter, "submitPrivateFollowUpStep = privateFollowUpController::submitPrivateFollowUpStep", "submitPrivateFollowUpStep = unchecked"),
            (adapter, "private val privateFollowUpController = MomoPrivateFollowUpController(", "private var privateFollowUp: PrivateFollowUpTransaction? = null"),
            (shared, "if (!persistPrivateFollowUpStep(child, previous))", "if (false)"),
            (root, "privateChatFollowUpPort = momoSocialAdapter,", "privateChatFollowUpPort = other,"),
            (root, "aiReplyObservationRuntime.isPrivateFollowUpWriteBlocked()", "false"),
        ):
            with self.subTest(path=path, token=old):
                self.assertIn(old, self.sources[path])
                changed = dict(self.sources)
                changed[path] = changed[path].replace(old, new, 1)
                self.assertTrue(integration_violations(changed))
        for path in RULES:
            with self.subTest(missing=path):
                changed = dict(self.sources)
                del changed[path]
                self.assertTrue(integration_violations(changed))
