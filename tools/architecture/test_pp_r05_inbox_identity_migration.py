"""R05-A: reconstruct the pre-migration facade using the relocated state and methods."""
import unittest
from pathlib import Path
from tools.architecture.test_pp_r00_freeze import git_blob
from tools.architecture.test_pp_r05_greetings_state_migration import restore_r05a_adapter

ROOT = Path(__file__).resolve().parents[2]
MOMO = ROOT / "app/src/main/java/com/freedom/adapters/social/momo/"
BASELINE = "1cc96df4ccf855e20e0a6436249db4f529abc282"
EXPECTED = {
    "types": "e3b37fd0e2bd1e6ea4815a0b153d74fa9f353de2",
    "fields": "bf7c02bbca5d8b7957a5fc8c8d31de7c27c2f8d6",
    "reset": "8edd3a7e313dfd983700533fd629c52df31f9a58",
    "consume": "cf661b70ae080728da707739a96f619a87b810b3",
    "clear": "6b33efcca14f739d68360c1323635c65a9d460dd",
    "log": "5330b6863ffa2dee2fcf7ec16be60a0b2f012eb1",
    "complete": "4ccd7255a1a4672d6fcb0252770d55b3bb248195",
}


def region(s, start, end):
    left = s.index(start)
    return s[left:s.index(end, left + len(start))].rstrip()


class PpR05InboxIdentityMigrationTest(unittest.TestCase):
    def test_state_relocation_reconstructs_exact_original_facade(self):
        adapter = (MOMO / "MomoSocialAdapter.kt").read_text(encoding="utf-8")
        component = (MOMO / "MomoSocialAdapterComponents.kt").read_text(encoding="utf-8")
        owner = component[component.index("internal class MomoInboxIdentityCaptureState("):]
        types = region(component, "internal enum class InboxIdentityCaptureStage", "/** Sole run-local owner")
        types = "\n".join("    " + line.replace("internal ", "private ", 1)
                          if line.startswith("internal ") else ("    " + line if line else "")
                          for line in types.split("\n"))
        fields = region(owner, "    val inboxIdentityCaptureLock", "    fun resetForRun()")
        fields = "\n".join(
            line.replace("@Volatile var ", "@Volatile private var ", 1) if "@Volatile var " in line
            else line.replace("    val ", "    private val ", 1) if line.startswith("    val ")
            else line.replace("    var ", "    private var ", 1) if line.startswith("    var ")
            else line for line in fields.split("\n"))
        reset = region(owner, "        synchronized(inboxIdentityCaptureLock) {", "\n    }\n\n    fun consumeIdentityReturnReinspectionRequest")
        consume = region(owner, "    fun consumeIdentityReturnReinspectionRequest(): Boolean =", "    /** Retryable failures")
        consume = consume.split("=\n", 1)[1].replace("        synchronized", "        val consumed = synchronized", 1)
        clear = region(owner, "    /** Retryable failures", "    fun onIdentityVerificationLog(")
        log = region(owner, "    fun onIdentityVerificationLog(", "    fun completePendingInboxIdentityBinding(")
        log = log.replace("fun onIdentityVerificationLog(onLog: (String) -> Unit, result:", "private fun onIdentityVerificationLog(result:", 1)
        complete = region(owner, "    fun completePendingInboxIdentityBinding(", "    private companion object")
        complete = complete.replace("fun completePendingInboxIdentityBinding(", "private fun completePendingInboxIdentityBinding(", 1)
        complete = complete.replace("identityVerified: Boolean,\n        onLog: (String) -> Unit\n", "identityVerified: Boolean\n", 1)
        complete = complete.replace("contactIdentityManager.resolveSnapshot(", "sessionComponents.contactIdentityManager.resolveSnapshot(", 1)
        complete = complete.replace("onIdentityVerificationLog(onLog, verificationResult,", "onIdentityVerificationLog(verificationResult,")
        blocks = dict(types=types, fields=fields, reset=reset, consume=consume, clear=clear, log=log, complete=complete)
        for name, block in blocks.items():
            with self.subTest(name=name):
                self.assertEqual(EXPECTED[name], git_blob(block.encode("utf-8")))
        self.assertEqual(1, component.count("val inboxIdentity = MomoInboxIdentityCaptureState(contactIdentityManager)"))
        self.assertNotIn("private val inboxIdentityCaptureLock", adapter)
        self.assertEqual(2, adapter.count("sessionComponents.inboxIdentity.resetForRun()"))
        self.assertIn("private const val MAX_INBOX_VERIFIED_IDENTITY_CACHE_ENTRIES = 256", owner)
        # Reverse R05-B with its exact R05-A hash check before applying the original R05-A assertions.
        reconstructed = restore_r05a_adapter(adapter).replace(
            "sessionComponents.inboxIdentity.onIdentityVerificationLog(onLog, ", "onIdentityVerificationLog("
        ).replace("sessionComponents.inboxIdentity.", "")
        reconstructed = reconstructed.replace("    private sealed interface SayHiCardListTapInspection",
                                              types + "\n\n    private sealed interface SayHiCardListTapInspection", 1)
        reconstructed = reconstructed.replace("    private fun transitionAvatarClickState(",
                                              fields + "\n\n    private fun transitionAvatarClickState(", 1)
        reconstructed = reconstructed.replace("        resetForRun()", reset)
        reconstructed = reconstructed.replace("        val consumed = consumeIdentityReturnReinspectionRequest()", consume, 1)
        reconstructed = reconstructed.replace(
            "    private fun completePendingInboxIdentityBinding(\n"
            "        pending: PendingInboxIdentityCapture,\n"
            "        identityVerified: Boolean\n"
            "    ) = completePendingInboxIdentityBinding(pending, identityVerified, onLog)",
            clear + "\n\n" + log + "\n\n" + complete, 1)
        reconstructed = reconstructed.replace(
            "        private const val SAYHI_PROFILE_SCROLL_TIMEOUT_MS = 8_000L",
            "        private const val MAX_INBOX_VERIFIED_IDENTITY_CACHE_ENTRIES = 256\n"
            "        private const val SAYHI_PROFILE_SCROLL_TIMEOUT_MS = 8_000L", 1)
        self.assertEqual("8bab51d20b1d24626041635fda0a767d33eb7ff8",
                         git_blob(reconstructed.encode("utf-8")))
