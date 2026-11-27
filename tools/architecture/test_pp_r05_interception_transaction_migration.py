"""R05-F: restore both exact R05-E sources from the relocated interception owner."""
import re
import unittest
from pathlib import Path
from tools.architecture.test_pp_r00_freeze import git_blob

ROOT = Path(__file__).resolve().parents[2]
MOMO = ROOT / "app/src/main/java/com/freedom/adapters/social/momo/"
PREVIOUS_ADAPTER = "d31b652c8d12b9507d7df6a5fc941a9faf919062"
PREVIOUS_IMAGE = "2517ee16b10ebbb08f31ab3e2b47ab07365c976f"
OWNER = "cdf761e0f4a221fc289d0621bbd309900e84cc9e"
FIELDS = ["switchVerifiedOn","interceptionSettingsObserved","interceptionLastAction","interceptionRetryAttempt","interceptionRetryNotBefore"]
EXPOSED = ["interceptionChecked","interceptionSettingsPage","interceptionSettingsChecked","reconcileInterception"]
CONSTRUCTOR = "    private val interceptionTransaction = MomoInterceptionTransactionController(\n        rootAccess = rootAccess,\n        accessibilityGateway = accessibilityGateway,\n        targetPackage = targetPackage,\n        onLog = onLog,\n        nodeBounds = ::interceptionNodeBounds,\n        originalPrivateFollowUpChat = ::originalPrivateFollowUpChat,\n        followUpWait = ::followUpWait,\n        followUpTerminal = ::followUpTerminal,\n        submitInterceptionStep = ::submitInterceptionStep,\n        interceptionDismissBounds = ::interceptionDismissBounds,\n        interceptionReturnedToChat = ::interceptionReturnedToChat,\n        interceptionBackBounds = ::interceptionBackBounds\n    )\n\n    private fun interceptionChecked(root: AccessibilityNodeInfo): Boolean? =\n        interceptionTransaction.interceptionChecked(root)\n\n    private fun interceptionSettingsPage(root: AccessibilityNodeInfo, child: PrivateFollowUpTransaction): Boolean =\n        interceptionTransaction.interceptionSettingsPage(root, child)\n\n    private fun interceptionSettingsChecked(root: AccessibilityNodeInfo, child: PrivateFollowUpTransaction): Boolean? =\n        interceptionTransaction.interceptionSettingsChecked(root, child)\n\n"
STAGE_FACADE = "            PrivateFollowUpStage.INTERCEPTION_OPEN_SETTINGS, PrivateFollowUpStage.INTERCEPTION_OPEN_CONTROL,\n            PrivateFollowUpStage.INTERCEPTION_TAP_SWITCH, PrivateFollowUpStage.INTERCEPTION_DISMISS,\n            PrivateFollowUpStage.INTERCEPTION_BACK, PrivateFollowUpStage.INTERCEPTION_RETURN ->\n                interceptionTransaction.observe(child)\n"

def replace_once(source, old, new):
    if source.count(old) != 1:
        raise AssertionError("Missing or duplicate R05-F anchor: " + old[:80])
    return source.replace(old, new, 1)

def region(source, start, end):
    left = source.index(start)
    return source[left:source.index(end, left + len(start))]

def unqualify(source):
    return re.sub(r"\bchild\.interceptionState\.(" + "|".join(FIELDS) + r")\b", r"child.\1", source)

def restore_r05e_sources(adapter, image=None, owner=None):
    if image is None:
        image = (MOMO / "MomoImageSendTransactionController.kt").read_text(encoding="utf-8")
    if owner is None:
        owner = (MOMO / "MomoInterceptionTransactionController.kt").read_text(encoding="utf-8")
    if git_blob(owner.encode("utf-8")) != OWNER:
        raise AssertionError("R05-F interception owner changed")
    state = region(owner, "    @Volatile var switchVerifiedOn", "}\n\n/** Live interception")
    switch = region(state, "    @Volatile var switchVerifiedOn", "    var interceptionSettingsObserved")
    remaining = state[state.index("    var interceptionSettingsObserved"):]
    image = replace_once(image, "    val interceptionState = MomoInterceptionState()\n", switch)
    image = replace_once(image, "    var lastWaitReason", remaining + "    var lastWaitReason")
    if git_blob(image.encode("utf-8")) != PREVIOUS_IMAGE:
        raise AssertionError("R05-F changed the prior image owner")
    reads = unqualify(region(owner, "    /** Read-only confirmation of the very same switch in the confirmed interception popup. */", "    fun observe(child: PrivateFollowUpTransaction) {\n        when (child.stage) {\n"))
    for method in EXPOSED:
        reads = replace_once(reads, "    fun " + method + "(", "    private fun " + method + "(")
    stages = unqualify(region(owner, "            PrivateFollowUpStage.INTERCEPTION_OPEN_SETTINGS -> {\n", "            else -> Unit // Non-interception stages remain with the parent transaction."))
    adapter = unqualify(adapter)
    adapter = replace_once(adapter, CONSTRUCTOR, reads)
    adapter = replace_once(adapter, STAGE_FACADE, stages)
    adapter = replace_once(adapter, "        if (child.interception && !interceptionTransaction.reconcileInterception(child)) return\n", "        if (child.interception && !reconcileInterception(child)) return\n")
    if git_blob(adapter.encode("utf-8")) != PREVIOUS_ADAPTER:
        raise AssertionError("R05-F did not reconstruct the exact green R05-E adapter")
    return adapter, image

class PpR05InterceptionTransactionMigrationTest(unittest.TestCase):
    def test_exact_inverse_and_single_owner(self):
        adapter = (MOMO / "MomoSocialAdapter.kt").read_text(encoding="utf-8")
        image = (MOMO / "MomoImageSendTransactionController.kt").read_text(encoding="utf-8")
        previous_adapter, previous_image = restore_r05e_sources(adapter, image)
        self.assertEqual(PREVIOUS_ADAPTER, git_blob(previous_adapter.encode("utf-8")))
        self.assertEqual(PREVIOUS_IMAGE, git_blob(previous_image.encode("utf-8")))
        self.assertEqual(1, adapter.count("MomoInterceptionTransactionController("))
        self.assertNotIn("private fun reconcileInterception(", adapter)
        self.assertNotIn("private fun observeInterceptionPage(", adapter)
        self.assertEqual(1, image.count("val interceptionState = MomoInterceptionState()"))

    def test_switch_proof_return_identity_and_checkpoint_gate_cannot_change(self):
        adapter = (MOMO / "MomoSocialAdapter.kt").read_text(encoding="utf-8")
        image = (MOMO / "MomoImageSendTransactionController.kt").read_text(encoding="utf-8")
        owner = (MOMO / "MomoInterceptionTransactionController.kt").read_text(encoding="utf-8")
        for old, new in (
            ("currentInterceptionChecked() != true", "false"),
            ("interceptionReturnedToChat(child)", "true"),
            ("child.interceptionState.switchVerifiedOn", "true"),
            ("MomoInterceptionRecoveryPolicy.Page.POPUP_SOURCE_UNCONFIRMED", "MomoInterceptionRecoveryPolicy.Page.POPUP_ON"),
        ):
            self.assertIn(old, owner)
            with self.subTest(old=old), self.assertRaises(AssertionError):
                restore_r05e_sources(adapter, image, owner.replace(old, new, 1))
        with self.assertRaises(AssertionError):
            restore_r05e_sources(adapter.replace("submitInterceptionStep = ::submitInterceptionStep", "submitInterceptionStep = unchecked", 1), image, owner)
        with self.assertRaises(AssertionError):
            restore_r05e_sources(adapter, image.replace("val interceptionState = MomoInterceptionState()", "val interceptionState = unsafe()", 1), owner)
