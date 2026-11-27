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
            issues.extend(evidence_violations(ROOT, self.manifest[field]))
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
