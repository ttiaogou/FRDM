"""R04 batch 4: exact source evidence for consolidated helper bodies and callers."""
import unittest
from pathlib import Path
from tools.architecture.test_pp_r00_freeze import git_blob

ROOT = Path(__file__).resolve().parents[2]
MOMO = ROOT / "app/src/main/java/com/freedom/adapters/social/momo/"
BASELINE = "c89727c3b379477bca9e3169cc0d2adb21c0a04a"
EXPECTED = {
    "contains": "884c9e9817879ebd32439eb982d202e676d123eb",
    "within": "509c46da59668a984628e4e56d979a10a280b3e9",
    "policyContains": "1c37a007b2f619fc5629b228337c97d7ab7c9a9f",
    "policyWithin": "19f0697218bf3067d103ebdb6ee68b2f52d061fb",
    "describe": "4aff86124e648651c754c6806b40638ca6def6dc",
    "revalidate": "d30fbce07410371b5430e5fd521c154f394a7b21",
    "navigation": "406ddb00ee7f8ca6f7baf390545355d924fc1b07",
    "dynamic": "ea87d29aa7c5dc94bc0a5545746042f6c978c3cc",
}


def region(s, start, end):
    left = s.index(start)
    return s[left:s.index(end, left + len(start))].rstrip()


def source(name):
    return (MOMO / name).read_text(encoding="utf-8")


class PpR04HelperConvergenceTest(unittest.TestCase):
    def assertBaseline(self, name, actual):
        self.assertEqual(EXPECTED[name], git_blob(actual.encode("utf-8")), name)

    def test_shared_geometry_preserves_both_old_predicates_and_final_gate(self):
        p = source("MomoSayHiAvatarTargetPolicy.kt")
        a = source("MomoSocialAdapter.kt")
        contains = region(p, "    fun contains(", "    private fun localId(")
        old = contains.replace("fun contains(", "private fun contains(", 1)
        self.assertBaseline("policyContains", old)
        self.assertBaseline("contains", old.replace("contains(", "containsBounds(", 1))
        within = region(p, "    fun isWithin(", "    private fun overlaps(")
        old = within.replace("fun isWithin(", "private fun isWithin(", 1)
        self.assertBaseline("policyWithin", old)
        self.assertBaseline("within", old.replace(
            "isWithin(path: NodePath, root: NodePath)", "isPathWithin(path: List<Int>, ancestor: List<Int>)"
        ).replace("path.childIndices", "path").replace("root.childIndices", "ancestor").replace(" &&\n            ", " && "))
        self.assertEqual(
            "    private fun containsBounds(outer: ScreenRect, inner: ScreenRect): Boolean =\n"
            "        MomoSayHiAvatarTargetPolicy.contains(outer, inner)",
            region(a, "    private fun containsBounds(", "    private fun clearSayHiRowTapEvidence("))
        validation = region(a, "    private fun revalidateSayHiRowTarget(", "    private fun containsBounds(")
        validation = validation.replace("MomoSayHiAvatarTargetPolicy.isWithin(rowPath, target.path)",
                                        "isPathWithin(rowPath.childIndices, target.path.childIndices)")
        validation = validation.replace("MomoSayHiAvatarTargetPolicy.isWithin(namePath, rowPath)",
                                        "isPathWithin(namePath.childIndices, rowPath.childIndices)")
        self.assertBaseline("revalidate", validation)

    def test_navigation_log_formatter_and_stateful_dedup_order(self):
        nav = source("MomoSayHiNavigationEvidenceInspector.kt")
        fmt = region(nav, "    fun describeNavigationCandidates(", "    fun formatBounds(")
        original = "\n".join("    " + line if line else line for line in fmt.split("\n"))
        original = original.replace("fun describeNavigationCandidates(candidates: List<MomoNavigationTargetEvidence>, maxItems: Int)",
                                    "fun describe(candidates: List<MomoNavigationTargetEvidence>)")
        original = original.replace("maxItems", "MAX_NAVIGATION_EVIDENCE_LOG_ITEMS")
        self.assertBaseline("describe", original)
        a = source("MomoSocialAdapter.kt")
        caller = region(a, "    private fun recordMomoNavigationEvidence(", "    /**\n     * Entry point for the explicit received-greetings branch.")
        caller = caller.replace("        onLog(", original + "\n        onLog(", 1)
        for name in ("receivedGreetingsEntryTargets", "topRightActionTargets", "toggleTargets"):
            caller = caller.replace("sayHiNavigationEvidenceInspector.describeNavigationCandidates(evidence." + name + ", MAX_NAVIGATION_EVIDENCE_LOG_ITEMS)",
                                    "describe(evidence." + name + ")")
        self.assertBaseline("navigation", caller)
        self.assertIn("private const val MAX_NAVIGATION_EVIDENCE_LOG_ITEMS = 8", a)

    def test_config_access_is_exact_and_no_duplicate_wrappers_remain(self):
        a = source("MomoSocialAdapter.kt")
        dynamic = region(a, "    override fun inspectDynamicTab()", "    override fun inspectPopup()")
        for field, wrapper in (
            ("dynamicTabLabel", "configPageDynamicTabLabel"),
            ("homeTabLabel", "configPageHomeTabLabel"),
            ("dynamicTabUpperBandRatio", "configPageDynamicTabUpperBandRatio"),
            ("homeNavLowerBandRatio", "configPageHomeNavLowerBandRatio"),
        ):
            self.assertNotIn("private fun " + wrapper, a)
            dynamic = dynamic.replace("config.page." + field, wrapper + "()")
        self.assertBaseline("dynamic", dynamic)
