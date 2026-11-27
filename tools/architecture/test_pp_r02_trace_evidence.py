"""R02 baseline protection. Actual trace assertions execute in existing Gradle test jobs.

This gate only protects trace evidence and coverage mappings; it is not a replay engine.
"""
import json
import unittest
from pathlib import Path
from tools.architecture.test_pp_r00_freeze import git_blob

ROOT = Path(__file__).resolve().parents[2]
BASELINE = "86a173c40e43dfad6fb70d19de375d6ed98e8f20"
R01 = ROOT / "tools/architecture/pp_r01_contracts.json"
TRACE_FILES = {
    "app/src/test/java/com/freedom/adapters/social/momo/R02GreetingsTraceTest.kt": "036b49e813ddfc65d1573d534380aee5d44817a8",
    "business/src/test/kotlin/com/freedom/features/accountbrowse/R02ImagePagingTraceTest.kt": "72e140c9f75aabe2b5b4a0872605c17e34ed9ae8",
    "business/src/test/kotlin/com/freedom/features/aireply/R02MessageReadTraceTest.kt": "0519c75400b5708eb7516c137d7557cf62bfac53",
    "business/src/test/kotlin/com/freedom/features/aireply/R02PrivateReturnTraceTest.kt": "59e6fafc18caf8c8745779bd62e0cf9d35ecd4c0",
    "business/src/test/kotlin/com/freedom/features/aireply/ai/R02DraftInputTraceTest.kt": "fcf862e094e87169de72d1bcc3aeb62cd76306b7",
    "business/src/test/kotlin/com/freedom/features/aireply/domain/contact/R02MediaLedgerTraceTest.kt": "33abcfcebfbb26e17922164bcaab019d194fad3c",
    "business/src/test/kotlin/com/freedom/features/conversation/R02IdentityTraceTest.kt": "edc30465013dd3fd4f6ab7bf461b264ec63a06da",
    "business/src/test/kotlin/com/freedom/features/recovery/R02RecoveryTraceTest.kt": "7f1d94f6d4843be6f94a58fe65470c9a768b419c",
    "core/src/test/kotlin/com/freedom/core/runtime/R02OwnershipTraceTest.kt": "ec590179dc21d7276fe3fbf933561fe8cf6c86bc",
    "core/src/test/kotlin/com/freedom/core/runtime/R02PageRoutingTraceTest.kt": "2f3d15e23195881d634351ddfc4e0d7ce901482c",
    "gesture/gesture-runtime/src/test/kotlin/com/freedom/application/gesture/R02GestureDispatchTraceTest.kt": "c30981acc36f58032e3389e91730f2a793ef26ea"
}
CONTRACT_TRACE = {
    "entry": "core/src/test/kotlin/com/freedom/core/runtime/R02PageRoutingTraceTest.kt",
    "gesture": "gesture/gesture-runtime/src/test/kotlin/com/freedom/application/gesture/R02GestureDispatchTraceTest.kt",
    "ownership": "core/src/test/kotlin/com/freedom/core/runtime/R02OwnershipTraceTest.kt",
    "private": "business/src/test/kotlin/com/freedom/features/aireply/R02PrivateReturnTraceTest.kt",
    "greetings": "app/src/test/java/com/freedom/adapters/social/momo/R02GreetingsTraceTest.kt",
    "browse": "business/src/test/kotlin/com/freedom/features/accountbrowse/R02ImagePagingTraceTest.kt",
    "identity": "business/src/test/kotlin/com/freedom/features/conversation/R02IdentityTraceTest.kt",
    "ocr": "business/src/test/kotlin/com/freedom/features/aireply/R02MessageReadTraceTest.kt",
    "reply": "business/src/test/kotlin/com/freedom/features/aireply/ai/R02DraftInputTraceTest.kt",
    "media": "business/src/test/kotlin/com/freedom/features/aireply/domain/contact/R02MediaLedgerTraceTest.kt",
    "recovery": "business/src/test/kotlin/com/freedom/features/recovery/R02RecoveryTraceTest.kt",
    "storage": "business/src/test/kotlin/com/freedom/features/aireply/domain/contact/R02MediaLedgerTraceTest.kt"
}


class PpR02TraceEvidenceTest(unittest.TestCase):
    def test_trace_oracles_are_unchanged(self):
        self.assertEqual(11, len(TRACE_FILES))
        for path, expected in TRACE_FILES.items():
            with self.subTest(path=path):
                target = ROOT / path
                self.assertTrue(target.is_file() and not target.is_symlink())
                self.assertEqual(expected, git_blob(target.read_bytes()),
                                 "R02 trace oracle changed; do not re-record to hide regressions")

    def test_every_r01_contract_has_trace_and_existing_evidence(self):
        self.assertEqual("033bb92c5bc369ba7d7e315ac11fd3bfc9b513a7",
                         git_blob(R01.read_bytes()))
        contracts = json.loads(R01.read_bytes())["contracts"]
        self.assertEqual({c["id"] for c in contracts}, set(CONTRACT_TRACE))
        self.assertEqual(set(TRACE_FILES), set(CONTRACT_TRACE.values()))
        for contract in contracts:
            self.assertTrue(contract["regression_paths"], contract["id"])
            self.assertIn(CONTRACT_TRACE[contract["id"]], TRACE_FILES)

