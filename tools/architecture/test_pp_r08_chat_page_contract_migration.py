"""R08-G: separate evidence models from the existing capability contract byte-for-byte."""
import re
import unittest
from pathlib import Path
from tools.architecture.test_pp_r00_freeze import git_blob

ROOT = Path(__file__).resolve().parents[2]
PORT = "business/src/main/kotlin/com/freedom/features/aireply/ports/ChatPagePort.kt"
SNAPSHOT = "business/src/main/kotlin/com/freedom/features/aireply/ports/ChatPageSnapshot.kt"
PREVIOUS = "5e7d75815304073b585412bbcbf01f1952c680d9"
PORT_HEADER = "package com.freedom.features.aireply.ports\n\nimport com.freedom.features.conversation.domain.contact.ContactRowIdentity\n\n"
MODEL_HEADER = "package com.freedom.features.aireply.ports\n\nimport com.freedom.core.accessibility.api.NodePath\nimport com.freedom.core.accessibility.api.NodeTarget\nimport com.freedom.core.accessibility.api.ScreenRect\nimport com.freedom.features.conversation.domain.contact.ContactRowIdentity\n\n"
PORT_BLOB = "e7bbace419e928f0b850c4b8175cbd9acd901076"
SNAPSHOT_BLOB = "a1a904f1435e94a1b60a0d5bc6240882c9a268eb"
MODEL_TYPES = ["ChatPageKind","ChatNodeTextSource","ChatUnreadStatus","ChatFireStatus","ChatUnreadEvidenceSource","ChatListUnreadDiagnostics","ChatListUnreadNodeEvidence","ChatListCandidateSnapshot","ChatPageNodeEvidence","ChatHeaderNodeDiagnostic","SessionListNavigationTarget","ChatPageSnapshot","ChatRegionSnapshot"]
METHOD_COUNT = 42

def restore_source(port, snapshot):
    if not port.startswith(PORT_HEADER) or not snapshot.startswith(MODEL_HEADER):
        raise AssertionError("R08-G package or import boundary changed")
    if git_blob(port.encode("utf-8")) != PORT_BLOB:
        raise AssertionError("R08-G existing capability contract changed")
    if git_blob(snapshot.encode("utf-8")) != SNAPSHOT_BLOB:
        raise AssertionError("R08-G extracted evidence changed")
    reconstructed = snapshot + port[len(PORT_HEADER):]
    if git_blob(reconstructed.encode("utf-8")) != PREVIOUS:
        raise AssertionError("R08-G prior complete source not reconstructed")
    return reconstructed

class PpR08ChatPageContractMigrationTest(unittest.TestCase):
    def setUp(self):
        self.port = (ROOT / PORT).read_text(encoding="utf-8")
        self.snapshot = (ROOT / SNAPSHOT).read_text(encoding="utf-8")

    def test_exact_contract_and_single_model_declarations(self):
        restore_source(self.port, self.snapshot)
        self.assertEqual(METHOD_COUNT, len(re.findall(r"^    fun ", self.port, re.M)))
        self.assertEqual(1, self.port.count("interface ChatPagePort {"))
        for name in MODEL_TYPES:
            pattern = r"(?m)^(?:data |enum )?class " + name + r"\b"
            self.assertEqual(1, len(re.findall(pattern, self.snapshot)), name)
            self.assertEqual(0, len(re.findall(pattern, self.port)), name)
        for token in ("interface ChatPagePort", "TaskCoordinator", "dispatchGesture(",
                      "AccessibilityNodeInfo", "android.view", "ChatMessagePort"):
            self.assertNotIn(token, self.snapshot)
        self.assertIn("var contactIdentity: ContactRowIdentity? = null", self.snapshot)
        self.assertIn("windowIdentityKey()", self.snapshot)
        self.assertIn("fun stableKey(): String", self.snapshot)

    def test_mutations_reject_changed_defaults_or_fingerprint_or_privacy(self):
        for old, new in (
            ("fun ensureMessageWorkArea(): Boolean = true", "fun ensureMessageWorkArea(): Boolean = false"),
            ("fun supportsVerifiedMessageWorkAreaNavigation(): Boolean = false", "fun supportsVerifiedMessageWorkAreaNavigation(): Boolean = true"),
            ("ReceivedGreetingsBackResult.REJECTED", "ReceivedGreetingsBackResult.BACK_SUBMITTED"),
            ("fun confirmReceivedGreetingsCompletionReturn(preserveTraversal: Boolean = false): Boolean = !preserveTraversal",
             "fun confirmReceivedGreetingsCompletionReturn(preserveTraversal: Boolean = false): Boolean = true"),
        ):
            self.assertIn(old, self.port)
            with self.subTest(old=old), self.assertRaises(AssertionError):
                restore_source(self.port.replace(old, new, 1), self.snapshot)
        for old, new in (
            ("window.generation != windowGeneration", "false"),
            ("nameText?.hashCode() ?: 0", "nameText"),
            ('it.startsWith("与") && it.endsWith("对话")', "true"),
            ("!it.insideListCandidate || it.isListProbeRoot || it.isAlternateListProbe", "true"),
        ):
            self.assertIn(old, self.snapshot)
            with self.subTest(old=old), self.assertRaises(AssertionError):
                restore_source(self.port, self.snapshot.replace(old, new, 1))
