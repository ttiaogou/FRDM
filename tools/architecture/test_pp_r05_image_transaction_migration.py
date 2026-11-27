"""R05-E: reconstruct exact R05-D source from the image-only owner; retain all earlier gates."""
import re
import unittest
from pathlib import Path
from tools.architecture.test_pp_r00_freeze import git_blob
from tools.architecture.test_pp_r05_interception_transaction_migration import restore_r05e_sources

ROOT = Path(__file__).resolve().parents[2]
MOMO = ROOT / "app/src/main/java/com/freedom/adapters/social/momo/"
PREVIOUS_ADAPTER = "3d7e95615422c89d98683fe0f53718a4d40c1848"
OWNER = "2517ee16b10ebbb08f31ab3e2b47ab07365c976f"
FIELDS = ["baseline","sendAttempt","imageRetryDialogRequested","imageRetryDialogWindowId","imageRetryAttempt","imageRetryNotBefore","albumWindow","directoryRejectedRetries"]
CONSTRUCTOR = "    private val imageTransaction = MomoImageSendTransactionController(\n        accessibilityGateway = accessibilityGateway,\n        targetPackage = targetPackage,\n        onLog = onLog,\n        followUpCurrent = ::followUpCurrent,\n        originalPrivateFollowUpChat = ::originalPrivateFollowUpChat,\n        followUpWait = ::followUpWait,\n        followUpTerminal = ::followUpTerminal,\n        submitPrivateFollowUpStep = ::submitPrivateFollowUpStep,\n        openImageAlbum = ::openImageAlbum,\n        openImageAlbumDirectory = ::openImageAlbumDirectory,\n        inspectFreedomDirectory = ::inspectFreedomDirectory,\n        openFreedomAlbum = ::openFreedomAlbum,\n        selectFreedomImage = ::selectFreedomImage,\n        sendFreedomImage = ::sendFreedomImage,\n        freshMediaSnapshot = ::freshMediaSnapshot,\n        submitImageRetryOpen = ::submitImageRetryOpen,\n        submitImageRetryConfirm = ::submitImageRetryConfirm,\n        inspectImageSendResult = ::inspectImageSendResult\n    )\n\n"
STAGE_FACADE = "        when (child.stage) {\n            PrivateFollowUpStage.OPEN_ALBUM, PrivateFollowUpStage.WAIT_ALBUM,\n            PrivateFollowUpStage.WAIT_DIRECTORY, PrivateFollowUpStage.WAIT_FREEDOM,\n            PrivateFollowUpStage.WAIT_SELECTION, PrivateFollowUpStage.WAIT_SEND_RESULT,\n            PrivateFollowUpStage.IMAGE_RETRY_OPEN, PrivateFollowUpStage.IMAGE_RETRY_CONFIRM ->\n                imageTransaction.observe(child)\n"

def replace_once(source, old, new):
    if source.count(old) != 1:
        raise AssertionError("Missing or duplicate R05-E anchor: " + old[:80])
    return source.replace(old, new, 1)

def region(source, start, end):
    left = source.index(start)
    return source[left:source.index(end, left + len(start))]

def unqualify(source):
    return re.sub(r"\bchild\.image\.(" + "|".join(FIELDS) + r")\b", r"child.\1", source)

def indent(source):
    return "\n".join("    " + line if line else "" for line in source.split("\n"))

def restore_r05d_adapter(adapter, owner=None):
    if owner is None:
        owner = (MOMO / "MomoImageSendTransactionController.kt").read_text(encoding="utf-8")
    adapter, owner = restore_r05e_sources(adapter, owner)
    if git_blob(owner.encode("utf-8")) != OWNER:
        raise AssertionError("R05-E image owner changed")
    state = region(owner, "internal class MomoImageSendState {\n", "\n}\n\n// The existing Momo adapter").split("\n", 1)[1] + "\n"
    field1 = region(state, "    var baseline:", "    var albumWindow:")
    field2 = state[state.index("    var albumWindow:"):]
    types = region(owner, "// The existing Momo adapter", "/** Image-only observation")
    types = indent(types.replace("internal enum class PrivateFollowUpStage", "private enum class PrivateFollowUpStage", 1)
                   .replace("internal class PrivateFollowUpTransaction", "private class PrivateFollowUpTransaction", 1))
    types = replace_once(types, "        val image = MomoImageSendState()\n", indent(field1))
    types = replace_once(types, "        var interceptionSettingsObserved", indent(field2) + "        var interceptionSettingsObserved")
    proof = unqualify(region(owner, "    /** Same append proof as initial send; never search globally for historical failure icons. */", "    private fun verifyPrivateImageSend(")).replace("fun imageRetryProof", "private fun imageRetryProof", 1)
    verify = unqualify(region(owner, "    private fun verifyPrivateImageSend(", "    fun observe(child: PrivateFollowUpTransaction) {\n")).replace("freshMediaSnapshot(null)", "freshMediaSnapshot()", 1)
    observe = unqualify(region(owner, "        val owns = { followUpCurrent(child) }\n        when (child.stage) {\n", "            else -> Unit // Non-image stages remain with the parent transaction."))
    observe = replace_once(observe, "owns, child.baseline,", "owns, baseline = child.baseline,")
    observe = replace_once(observe, "{ attempt ->", "onDispatched = { attempt ->")
    observe = replace_once(observe, "                        callback)\n", "                        onGestureResult = callback)\n")
    adapter = unqualify(adapter)
    adapter = replace_once(adapter, CONSTRUCTOR, types)
    adapter = replace_once(adapter, "    private fun submitImageRetryOpen(", proof + "    private fun submitImageRetryOpen(")
    adapter = replace_once(adapter, "    /**\n     * Interception uses the same parent-bound serial tap pipeline", verify + "    /**\n     * Interception uses the same parent-bound serial tap pipeline")
    adapter = replace_once(adapter, STAGE_FACADE, observe)
    adapter = replace_once(adapter, "else imageTransaction.imageRetryProof(child, snapshot)", "else imageRetryProof(child, snapshot)")
    if git_blob(adapter.encode("utf-8")) != PREVIOUS_ADAPTER:
        raise AssertionError("R05-E did not reconstruct the exact green R05-D adapter")
    return adapter

class PpR05ImageTransactionMigrationTest(unittest.TestCase):
    def test_exact_inverse_and_single_owner(self):
        adapter = (MOMO / "MomoSocialAdapter.kt").read_text(encoding="utf-8")
        self.assertEqual(PREVIOUS_ADAPTER, git_blob(restore_r05d_adapter(adapter).encode("utf-8")))
        self.assertEqual(1, adapter.count("MomoImageSendTransactionController("))
        self.assertNotIn("private fun verifyPrivateImageSend(", adapter)
        self.assertNotIn("private class PrivateFollowUpTransaction(", adapter)

    def test_mutation_of_image_proof_and_parent_gate_rejected(self):
        adapter = (MOMO / "MomoSocialAdapter.kt").read_text(encoding="utf-8")
        owner = (MOMO / "MomoImageSendTransactionController.kt").read_text(encoding="utf-8")
        for old, new in (
            ("!baseline.belongsTo(child.target, child.id)", "false"),
            ("MomoImageSendEvidenceInspector.networkDisconnected(snapshot)", "false"),
            ("child.image.imageRetryDialogRequested", "true"),
            ("MomoImageSendResult.SUCCESS -> followUpTerminal", "MomoImageSendResult.FAILED -> followUpTerminal"),
        ):
            self.assertIn(old, owner)
            with self.subTest(old=old), self.assertRaises(AssertionError):
                restore_r05d_adapter(adapter, owner.replace(old, new, 1))
        with self.assertRaises(AssertionError):
            restore_r05d_adapter(adapter.replace("submitPrivateFollowUpStep = privateFollowUpController::submitPrivateFollowUpStep", "submitPrivateFollowUpStep = unchecked", 1), owner)
