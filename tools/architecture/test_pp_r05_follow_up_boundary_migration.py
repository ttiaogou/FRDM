"""R05-G: exact inverse of the shared operation/recovery boundary relocation."""
import unittest
from pathlib import Path
from tools.architecture.test_pp_r00_freeze import git_blob
from tools.architecture.test_pp_r05_facade_convergence import restore_r05g_adapter

ROOT = Path(__file__).resolve().parents[2]
MOMO = ROOT / "app/src/main/java/com/freedom/adapters/social/momo/"
PREVIOUS_ADAPTER = "353ffb311dece0e79f6309862fcc8f71df19f076"
PREVIOUS_IMAGE = "f6583cfa341c69a1f2e56f3ea827215d16065b23"
OWNER = "14c07c5bcfc97bbefd617ad68dc1d04a1d7f391d"
OVERRIDES = ["progress","beginPrivateFollowUp","privateFollowUpSucceeded","abort","reset"]
EXPOSED = ["followUpCurrent","followUpWait","followUpTerminal","submitPrivateFollowUpStep"]
FACADE_LIFETIME = "    private val privateFollowUpController = MomoPrivateFollowUpController(\n        accessibilityGateway = accessibilityGateway,\n        targetPackage = targetPackage,\n        onLog = onLog,\n        isBoundAiReplyUiOperationOwned = isBoundAiReplyUiOperationOwned,\n        captureImageSendBaseline = ::captureImageSendBaseline\n    )\n\n    private val privateFollowUp: PrivateFollowUpTransaction?\n        get() = privateFollowUpController.current()\n\n    override fun progress(): PrivateChatFollowUpProgress = privateFollowUpController.progress()\n\n    private fun followUpCurrent(child: PrivateFollowUpTransaction): Boolean =\n        privateFollowUpController.followUpCurrent(child)\n\n"
FACADE_EXECUTION = "    override fun beginPrivateFollowUp(\n        target: ChatDraftInputTarget,\n        transactionId: String,\n        interception: Boolean,\n        beforeStep: (com.freedom.features.aireply.domain.contact.ContactFollowUpStep) -> Boolean,\n        ownsTransaction: () -> Boolean\n    ): Boolean = privateFollowUpController.beginPrivateFollowUp(\n        target, transactionId, interception, beforeStep, ownsTransaction\n    )\n\n    override fun privateFollowUpSucceeded(): Boolean = privateFollowUpController.privateFollowUpSucceeded()\n\n    private fun followUpWait(child: PrivateFollowUpTransaction, reason: String) =\n        privateFollowUpController.followUpWait(child, reason)\n\n    private fun followUpTerminal(child: PrivateFollowUpTransaction, result: String, reason: String) =\n        privateFollowUpController.followUpTerminal(child, result, reason)\n\n    override fun abort(reason: String) = privateFollowUpController.abort(reason)\n\n    override fun reset(reason: String) = privateFollowUpController.reset(reason)\n\n    private fun submitPrivateFollowUpStep(\n        child: PrivateFollowUpTransaction,\n        next: PrivateFollowUpStage,\n        action: ((GestureExecutionStatus) -> Unit) -> GestureSubmission\n    ) = privateFollowUpController.submitPrivateFollowUpStep(child, next, action)\n\n"
FACADE_OBSERVATION = "    override fun observe() = privateFollowUpController.observe(\n        reconcileInterception = interceptionTransaction::reconcileInterception,\n        observeImage = imageTransaction::observe,\n        observeInterception = interceptionTransaction::observe\n    )\n\n"

def replace_once(source, old, new):
    if source.count(old) != 1:
        raise AssertionError("Missing or duplicate R05-G anchor: " + old[:80])
    return source.replace(old, new, 1)

def region(source, start, end):
    left = source.index(start)
    return source[left:source.index(end, left + len(start))]

def restore_visibility(source):
    for method in OVERRIDES:
        source = source.replace("    fun " + method + "(", "    override fun " + method + "(", 1)
    for method in EXPOSED:
        source = source.replace("    fun " + method + "(", "    private fun " + method + "(", 1)
    return source

def restore_r05f_sources(adapter, image=None, owner=None):
    adapter = restore_r05g_adapter(adapter)
    if image is None:
        image = (MOMO / "MomoImageSendTransactionController.kt").read_text(encoding="utf-8")
    if owner is None:
        owner = (MOMO / "MomoPrivateFollowUpController.kt").read_text(encoding="utf-8")
    if git_blob(owner.encode("utf-8")) != OWNER:
        raise AssertionError("R05-G operation/recovery owner changed")
    types = region(owner, "// The existing Momo adapter owns this short-lived platform navigation transaction.", "/** Shared platform child lifecycle")
    image = replace_once(image, "/** Image-only observation and verification", types + "/** Image-only observation and verification")
    if git_blob(image.encode("utf-8")) != PREVIOUS_IMAGE:
        raise AssertionError("R05-G changed prior image execution")
    lifetime = region(owner, "    @Volatile private var privateFollowUp: PrivateFollowUpTransaction? = null\n", "    fun beginPrivateFollowUp(")
    lifetime = restore_visibility(replace_once(lifetime, "    fun current(): PrivateFollowUpTransaction? = privateFollowUp\n\n", ""))
    execution = restore_visibility(region(owner, "    fun beginPrivateFollowUp(", "    fun observe(\n        reconcileInterception: (PrivateFollowUpTransaction) -> Boolean,\n        observeImage: (PrivateFollowUpTransaction) -> Unit,\n        observeInterception: (PrivateFollowUpTransaction) -> Unit\n    ) {\n"))
    observation = owner[owner.index("    fun observe(\n        reconcileInterception: (PrivateFollowUpTransaction) -> Boolean,\n        observeImage: (PrivateFollowUpTransaction) -> Unit,\n        observeInterception: (PrivateFollowUpTransaction) -> Unit\n    ) {\n"):-2]
    observation = replace_once(observation, "    fun observe(\n        reconcileInterception: (PrivateFollowUpTransaction) -> Boolean,\n        observeImage: (PrivateFollowUpTransaction) -> Unit,\n        observeInterception: (PrivateFollowUpTransaction) -> Unit\n    ) {\n", "    override fun observe() {\n")
    observation = replace_once(observation, "!reconcileInterception(child)", "!interceptionTransaction.reconcileInterception(child)")
    observation = replace_once(observation, "observeImage(child)", "imageTransaction.observe(child)")
    observation = replace_once(observation, "observeInterception(child)", "interceptionTransaction.observe(child)")
    adapter = replace_once(adapter, FACADE_LIFETIME, lifetime)
    adapter = replace_once(adapter, FACADE_EXECUTION, execution)
    adapter = replace_once(adapter, FACADE_OBSERVATION, observation)
    if git_blob(adapter.encode("utf-8")) != PREVIOUS_ADAPTER:
        raise AssertionError("R05-G did not reconstruct the exact green R05-F adapter")
    return adapter, image

class PpR05FollowUpBoundaryMigrationTest(unittest.TestCase):
    def test_exact_inverse_and_single_lifecycle_owner(self):
        adapter = (MOMO / "MomoSocialAdapter.kt").read_text(encoding="utf-8")
        image = (MOMO / "MomoImageSendTransactionController.kt").read_text(encoding="utf-8")
        prior, prior_image = restore_r05f_sources(adapter, image)
        self.assertEqual(PREVIOUS_ADAPTER, git_blob(prior.encode("utf-8")))
        self.assertEqual(PREVIOUS_IMAGE, git_blob(prior_image.encode("utf-8")))
        self.assertEqual(1, adapter.count("MomoPrivateFollowUpController("))
        self.assertNotIn("@Volatile private var privateFollowUp:", adapter)
        self.assertNotIn("private fun persistPrivateFollowUpStep(", adapter)
        self.assertNotIn("internal class PrivateFollowUpTransaction(", image)

    def test_lost_owner_write_veto_checkpoint_and_recovery_order_fail_closed(self):
        adapter = (MOMO / "MomoSocialAdapter.kt").read_text(encoding="utf-8")
        image = (MOMO / "MomoImageSendTransactionController.kt").read_text(encoding="utf-8")
        owner = (MOMO / "MomoPrivateFollowUpController.kt").read_text(encoding="utf-8")
        for old, new in (
            ("privateFollowUp === child", "true"),
            ("!isBoundAiReplyUiOperationOwned(child.target.taskLease)", "false"),
            ("!persistPrivateFollowUpStep(child, previous)", "false"),
            ("if (child.inFlight)", "if (false)"),
            ("!reconcileInterception(child)", "false"),
        ):
            self.assertIn(old, owner)
            with self.subTest(old=old), self.assertRaises(AssertionError):
                restore_r05f_sources(adapter, image, owner.replace(old, new, 1))
        with self.assertRaises(AssertionError):
            restore_r05f_sources(adapter.replace("isBoundAiReplyUiOperationOwned = isBoundAiReplyUiOperationOwned,\n        captureImageSendBaseline", "isBoundAiReplyUiOperationOwned = { true },\n        captureImageSendBaseline", 1), image, owner)
