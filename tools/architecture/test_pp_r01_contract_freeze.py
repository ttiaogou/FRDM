"""R01 protects the existing evidence and records business freeze contracts.

R00 remains the source-content gate. R01 adds no runtime or replay framework.
New regression tests are allowed; existing evidence may not silently be changed.
"""
import json
import re
import tempfile
import unittest
from pathlib import Path

from tools.architecture.test_pp_r00_freeze import git_blob

ROOT = Path(__file__).resolve().parents[2]
MANIFEST = Path(__file__).with_name("pp_r01_contracts.json")
R00_MANIFEST = Path(__file__).with_name("pp_r00_freeze.json")
R00_COMMIT = "099b5ee0ca528cdf2608efe22d88ed23896a3d37"
R00_MANIFEST_BLOB = "4510f804078288427e8f8b61f2ec69e8a210bab7"
R01_MANIFEST_BLOB = "033bb92c5bc369ba7d7e315ac11fd3bfc9b513a7"
CONTRACT_IDS = (
    "entry", "gesture", "ownership", "private", "greetings", "browse",
    "identity", "ocr", "reply", "media", "recovery", "storage",
)
EVIDENCE_COUNTS = {
    "protected_test_files": 254,
    "protected_gate_files": 15,
    "protected_rule_files": 11,
}


def evidence_violations(root, expected):
    """Verify actual checkout content; additions outside expected are permitted."""
    issues = []
    for relative, digest in sorted(expected.items()):
        path = root / relative
        if path.is_symlink():
            issues.append(f"Protected evidence replaced by symlink: {relative}")
        elif not path.is_file():
            issues.append(f"Protected evidence missing: {relative}")
        elif git_blob(path.read_bytes()) != digest:
            issues.append(f"Protected evidence changed: {relative}")
    return issues


def contract_violations(contracts, source_paths, evidence_paths):
    issues = []
    if tuple(c["id"] for c in contracts) != CONTRACT_IDS:
        issues.append("Required business contract IDs/order changed")
    for contract in contracts:
        label = contract["id"]
        for field in ("title", "owner", "behavior"):
            if not isinstance(contract.get(field), str) or not contract[field].strip():
                issues.append(f"{label}: missing {field}")
        for field, known in (("source_paths", source_paths), ("regression_paths", evidence_paths)):
            paths = contract.get(field, [])
            if not paths or len(paths) != len(set(paths)):
                issues.append(f"{label}: empty or duplicate {field}")
            for path in paths:
                if path not in known:
                    issues.append(f"{label}: unmapped {field}: {path}")
    return issues


class PpR01ContractFreezeTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        raw = MANIFEST.read_bytes()
        if git_blob(raw) != R01_MANIFEST_BLOB:
            raise AssertionError("R01 contract/evidence manifest changed without reviewed migration")
        cls.manifest = json.loads(raw)
        source_raw = R00_MANIFEST.read_bytes()
        if git_blob(source_raw) != R00_MANIFEST_BLOB:
            raise AssertionError("R00 source baseline changed")
        cls.source_manifest = json.loads(source_raw)

    def test_pinned_baseline_and_evidence_inventory(self):
        m = self.manifest
        self.assertEqual(1, m["schema"])
        self.assertEqual("ttiaogou/FRDM", m["repository"])
        self.assertEqual("PP", m["branch"])
        self.assertEqual(R00_COMMIT, m["r00_commit"])
        self.assertEqual(46050312939, m["r00_ci"])
        self.assertEqual(R00_MANIFEST_BLOB, m["r00_manifest_blob"])
        seen = set()
        for field, count in EVIDENCE_COUNTS.items():
            paths = m[field]
            self.assertEqual(count, len(paths), field)
            self.assertFalse(seen.intersection(paths), field)
            seen.update(paths)
            for digest in paths.values():
                self.assertRegex(digest, re.compile(r"^[0-9a-f]{40}$"))
        self.assertEqual(280, len(seen))
        self.assertIn(".github/workflows/android-build.yml", m["protected_gate_files"])
        self.assertIn("tools/architecture/test_pp_r00_freeze.py", m["protected_gate_files"])
        self.assertIn("tools/architecture/test_m04_media_click_freeze.py", m["protected_gate_files"])
        self.assertIn("tools/architecture/m04_media_click_freeze.json", m["protected_gate_files"])

    def test_existing_tests_rules_and_ci_gates_are_unchanged(self):
        issues = []
        for field in EVIDENCE_COUNTS:
            expected = dict(self.manifest[field])
            if field == "protected_gate_files":
                # R03 reviewed exact R00 gate update; keep every historical assertion
                # and protect the revised gate by its exact blob, not an exemption.
                path = "tools/architecture/test_pp_r00_freeze.py"
                self.assertEqual("1524f5031c0391d615fbf016d8ae9867d8086c78", expected[path])
                # R04 also pins the reviewed relocation; R03 remains in the R00 gate.
                expected[path] = "44d5b3dd3cf8dd647e0488b4cb5ea88f458320da"
                # R05-E relocates the image order check to the actual image owner;
                # the original M04 manifest and every frozen action assertion remain pinned.
                path = "tools/architecture/test_m04_media_click_freeze.py"
                self.assertEqual("8e0e9acbc33eb5b0c41d57f99e2ac4e02e82088a", expected[path])
                expected[path] = "f459ec8dd8dfe0d93c6822a4990c3181dc67ec30"
                # R05-G moves the existing L07 gate to the actual shared owner
                # and adds owner/recovery/delegation mutation checks.
                path = "tools/architecture/verify_boundaries.py"
                self.assertEqual("713a0cfc1ed4617a37a31c66e1a0b056f42db1a3", expected[path])
                expected[path] = "f625a28a50f90ee07d6d32587a776c4a39fdc66a"
                path = "tools/architecture/test_verify_boundaries.py"
                self.assertEqual("bec2d575a43477763c2309a04a9e6014ad39967a", expected[path])
                expected[path] = "06485947b2fda4007b4ef09d1371c06b2f745fb2"
            issues.extend(evidence_violations(ROOT, expected))
        # New R04 regression evidence is pinned independently of the historical inventory.
        issues.extend(evidence_violations(ROOT, {
            "app/src/test/java/com/freedom/adapters/social/momo/R04MomoReadOnlyMigrationTest.kt":
                "08cbddbca8ff6c96e484a6e823e69af95dd4f6cd",
        }))
        issues.extend(evidence_violations(ROOT, {
            "tools/architecture/test_pp_r04_node_read_migration.py": "1917bd9733e5b0f568479dd037ae78421b13d5bb",
        }))
        issues.extend(evidence_violations(ROOT, {
            "tools/architecture/test_pp_r04_greeting_message_migration.py": "c45b6c2b48bd097176ab561fe745c552d6d78dda",
        }))
        issues.extend(evidence_violations(ROOT, {
            "tools/architecture/test_pp_r04_helper_convergence.py": "4ccda56f57cdc0ba5800eea0f89a6a658872d815",
            "app/src/test/java/com/freedom/adapters/social/momo/R04HelperConvergenceTest.kt": "6f77d80b80e821eb0d8a2896f25410ec741e5d3d",
        }))
        issues.extend(evidence_violations(ROOT, {
            "tools/architecture/test_pp_r05_inbox_identity_migration.py": "df493ddb2d84b33685aa4eeb4e0d745dcff1f18f",
            "app/src/test/java/com/freedom/adapters/social/momo/R05InboxIdentityStateTest.kt": "8980b617f388e0c6db11ffe13ce85d86d81ad4fa",
        }))
        issues.extend(evidence_violations(ROOT, {
            "tools/architecture/test_pp_r05_greetings_state_migration.py": "88c59b0c367dd8fa211c038755c81dbf9e77b6bd",
            "app/src/test/java/com/freedom/adapters/social/momo/R05GreetingsStateTest.kt": "0668e9b39fa6add20b716eb4493c9f0e05726b2d",
        }))
        issues.extend(evidence_violations(ROOT, {
            "tools/architecture/test_pp_r05_avatar_transaction_migration.py": "bc2f436b5719fdf40b0c78b13d04c8f87823266b",
            "app/src/test/java/com/freedom/adapters/social/momo/R05AvatarTransactionMigrationTest.kt": "79598dad5eb51d53187a91dbf97d48dc89b0d8fa",
        }))
        # R05-D adds an exact inverse reconstruction of the prior R05-C source.
        issues.extend(evidence_violations(ROOT, {
            "tools/architecture/test_pp_r05_draft_transaction_migration.py": "624e2df6d11189e26cbde371e413a12287cdb096",
            "tools/architecture/test_pp_r05_image_transaction_migration.py": "b6c9382900c53b460de1bd403f98373dffde004b",
            "tools/architecture/test_pp_r05_interception_transaction_migration.py": "983a9908e70616b0298e48a532c0b8cadb1414d8",
            "tools/architecture/test_pp_r05_follow_up_boundary_migration.py": "6d1103b2b8a7c7e4bdafd0e56d56436f4da5a573",
            "tools/architecture/test_pp_r05_facade_convergence.py": "5422e057795e4b30d6dde17123f7301d8a98fe46",
            "tools/architecture/test_pp_r05_integration_acceptance.py": "cb2537f571b13e7114f0ba4ecc8aba79785c43d8",
            "tools/architecture/test_pp_r06_page_evidence_migration.py": "51ae99e6de3d383b7055360510f02023456f55e3",
        }))
        self.assertEqual([], issues)

    def test_contracts_map_to_real_frozen_sources_and_existing_regressions(self):
        source_paths = set(self.source_manifest["permanent_s0"]) | set(
            self.source_manifest["pre_migration_s1"])
        evidence_paths = set(self.manifest["protected_test_files"]) | set(
            self.manifest["protected_gate_files"])
        contracts = self.manifest["contracts"]
        self.assertEqual(12, len(contracts))
        self.assertEqual(57, len({p for c in contracts for p in c["source_paths"]}))
        self.assertEqual(45, len({p for c in contracts for p in c["regression_paths"]}))
        self.assertEqual([], contract_violations(contracts, source_paths, evidence_paths))

    def test_evidence_changes_and_deletion_rejected_but_new_tests_allowed(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "old.kt").write_bytes(b"original assertion")
            expected = {"old.kt": git_blob(b"original assertion")}
            self.assertEqual([], evidence_violations(root, expected))
            (root / "new.kt").write_bytes(b"new regression")
            self.assertEqual([], evidence_violations(root, expected))
            (root / "old.kt").write_bytes(b"weakened assertion")
            self.assertEqual(["Protected evidence changed: old.kt"], evidence_violations(root, expected))
            (root / "old.kt").unlink()
            self.assertEqual(["Protected evidence missing: old.kt"], evidence_violations(root, expected))
            (root / "old.kt").symlink_to(root / "new.kt")
            self.assertEqual(["Protected evidence replaced by symlink: old.kt"],
                             evidence_violations(root, expected))

    def test_unmapped_contract_or_missing_owner_rejected(self):
        contracts = [dict(c) for c in self.manifest["contracts"]]
        sources = {p for c in contracts for p in c["source_paths"]}
        evidence = {p for c in contracts for p in c["regression_paths"]}
        contracts[0]["owner"] = ""
        contracts[0]["regression_paths"] = ["not-a-test.kt"]
        issues = contract_violations(contracts, sources, evidence)
        self.assertIn("entry: missing owner", issues)
        self.assertIn("entry: unmapped regression_paths: not-a-test.kt", issues)
        self.assertIn("Required business contract IDs/order changed",
                      contract_violations(contracts[1:], sources, evidence))
