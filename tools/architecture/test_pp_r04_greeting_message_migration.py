"""R04 batch 3 source-equivalence evidence; not a device execution test."""
import unittest
from pathlib import Path
from tools.architecture.test_pp_r00_freeze import git_blob

ROOT = Path(__file__).resolve().parents[2]
MOMO = ROOT / "app/src/main/java/com/freedom/adapters/social/momo/"
BASELINE = "6f34c3809e12a6ce062701f7aab369ca640a5cdf"
EXPECTED = {
    "format": "3e3c0144e491e67df1f3bbe8421aa08875b34bd2",
    "diagnostic": "51e260aee9ddd882e1261325da0068ae4b05c60d",
    "hash": "319c3671aeab107d61ef7216b5b43d9f384b2fcd",
    "window": "daffed5448052744379cf0cf773d7d784c098ad4",
    "previous": "16bfdf804ec986221c7aa9bfe32538e8aecfbfeb",
    "region": "6bd2d3d6df93a3ad20dca67389b205381f3ba8df",
    "failure": "2b7d2ad5cec404707b04a6adfd2e4a0508019e16",
    "refresh": "b5e7e927c5fa55aa6b81888bc465917edf0022d3",
    "structure": "d18776a4b64bd52bfd9f8396f08a485c18392f24",
    "ocr": "4c688a14b58d5e69089679385e59a8aa2b6b5fc8",
    "count": "11c547cf34710d1c53d20d73aac06ba553f57f34"
}


def source(name):
    return (MOMO / name).read_text(encoding="utf-8")


def region(s, start, end):
    left = s.index(start)
    right = s.index(end, left + len(start))
    return s[left:right].rstrip()


def indent(s, n):
    return "\n".join(" " * n + line if line else line for line in s.split("\n"))


class PpR04GreetingMessageMigrationTest(unittest.TestCase):
    def assertBaseline(self, name, actual):
        self.assertEqual(EXPECTED[name], git_blob(actual.encode("utf-8")), name)

    def test_greeting_evidence_relocated_without_changing_rules(self):
        nav = source("MomoSayHiNavigationEvidenceInspector.kt")
        policy = source("MomoSayHiIdentityFingerprintPolicy.kt")
        adapter = source("MomoSocialAdapter.kt")
        fmt = region(nav, "    fun formatBounds(", "    /** Contains geometry")
        self.assertBaseline("format", fmt.replace("fun formatBounds", "private fun formatBounds", 1))
        diag = region(nav, "    /** Contains geometry", "    fun inspect(")
        self.assertBaseline("diagnostic", diag.replace("fun describeSayHiRowTargetRevalidation", "private fun describeSayHiRowTargetRevalidation", 1))
        h = region(policy, "    /**\n     * A diagnostic hash of the CURRENT row", "    fun contactIdHash(")
        self.assertBaseline("hash", h.replace("fun sayHiSafeTargetEvidenceHash", "private fun sayHiSafeTargetEvidenceHash", 1))
        selected = region(source("MomoSayHiAccessibilityInspector.kt"),
                          "    fun selectVisibleReceivedGreetingsWindow(", "    /**\n     * Received-greetings list page fingerprint")
        body = selected[selected.index("        val candidates ="):]
        body = body.replace("snapshots.filter", "accessibilityGateway.inspectTargetWindows(request).filter", 1)
        body = body.replace("hasReceivedGreetingsListFingerprint(candidate)", "sayHiAccessibilityInspector.hasReceivedGreetingsListFingerprint(candidate)", 1)
        caller = region(adapter, "    /**\n     * Reacquires the received-greetings list", "    private fun processReceivedGreetingRows(")
        prefix = caller[:caller.index("        return sayHiAccessibilityInspector.selectVisibleReceivedGreetingsWindow(")]
        self.assertIn("accessibilityGateway.inspectTargetWindows(request), targetPackage", caller)
        self.assertBaseline("window", prefix + body)
        for name in ("formatBounds", "describeSayHiRowTargetRevalidation", "sayHiSafeTargetEvidenceHash"):
            self.assertNotIn("private fun " + name + "(", adapter)

    def test_region_mapping_and_live_root_gate_remain_exact(self):
        models = source("MomoChatPageModels.kt")
        previous = region(models, "    fun previousRegionInspection(", "    fun refreshedRegionSnapshot(")
        expr = previous.split(" = MomoChatPageInspection(", 1)[1]
        original_previous = "        val previousInspection = MomoChatPageInspection(" + "\n".join(
            line if i == 0 or not line else "    " + line for i, line in enumerate(expr.split("\n")))
        self.assertBaseline("previous", original_previous)
        mapping = region(models, "    fun refreshedRegionSnapshot(", "    fun supportsChatDetailActivity(")
        original_region = indent(mapping.split(" =\n", 1)[1], 4)
        self.assertBaseline("region", original_region)
        caller = region(source("MomoSocialAdapter.kt"), "    override fun refreshKnownChatRegions(", "    override fun invalidateOcrRecognitionReuse(")
        caller = caller.replace("        val previousInspection = MomoChatPageContract.previousRegionInspection(previous, messageListTarget, inputFieldTarget)", original_previous)
        caller = caller.replace("            MomoChatPageContract.refreshedRegionSnapshot(refreshed)", original_region)
        self.assertBaseline("refresh", caller)

    def test_message_reader_helpers_and_full_ocr_callback_order(self):
        reader = source("MomoChatMessageReader.kt")
        failure = region(reader, "    fun structuralReadFailure(", "    fun formalBodyRowCount(")
        original_failure = indent(failure.split(" =\n", 1)[1], 4)
        self.assertBaseline("failure", original_failure)
        count = region(reader, "    fun formalBodyRowCount(", "    /** Merges ephemeral local OCR")
        original_count = "            val formalBodyRows = " + count.split(" =\n", 1)[1].strip()
        self.assertBaseline("count", original_count)
        adapter = source("MomoSocialAdapter.kt")
        structure = region(adapter, "    override fun readRecentMessagesStructureAsync(", "    override fun readRecentMessagesAsync(")
        self.assertBaseline("structure", structure.replace("            messageComponents.reader.structuralReadFailure()", original_failure))
        ocr = region(adapter, "    private fun readRecentMessagesWithOcr(", "    fun close()")
        self.assertBaseline("ocr", ocr.replace("            val formalBodyRows = messageComponents.reader.formalBodyRowCount(structuralBatch)", original_count))
