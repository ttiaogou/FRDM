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



def governance_record_violations(root):
    """Validate mutable governance records, without freezing their evolving contents."""
    issues = []
    records = {}
    for name in ("RESPONSIBILITY_MAP", "AUDIT_REGISTER", "AUDIT_CHECKPOINT"):
        path = root / "docs/audits" / (name + ".md")
        if path.is_symlink() or not path.is_file():
            issues.append("Missing or symlink governance record: " + name)
            continue
        try:
            source = path.read_text(encoding="utf-8")
        except UnicodeError:
            issues.append("Non-UTF-8 governance record: " + name)
            continue
        if not source.strip():
            issues.append("Empty governance record: " + name)
        records[name] = source

    own = records.get("RESPONSIBILITY_MAP", "")
    own_ids = re.findall(r"^\|\s*(OWN-\d+)\s*\|", own, re.M)
    if len(own_ids) != len(set(own_ids)):
        issues.append("Duplicate responsibility ID")
    if not {"OWN-%02d" % i for i in range(1, 33)}.issubset(own_ids):
        issues.append("Missing initial responsibility ID")
    for line in own.splitlines():
        if re.match(r"^\|\s*OWN-\d+\s*\|", line):
            cells = re.split(r"(?<!\\)\|", line)[1:-1]
            if len(cells) < 6 or any(not cell.strip() for cell in cells):
                issues.append("Incomplete responsibility record: " + cells[0].strip())

    ledger = records.get("AUDIT_REGISTER", "")
    issue_ids = []
    statuses = {"CANDIDATE", "OPEN", "RETAINED", "LIMITATION", "DEFERRED",
                "IN_PROGRESS", "VERIFY_PENDING", "CLOSED", "REOPENED"}
    for line in ledger.splitlines():
        match = re.match(r"^\|\s*([A-Z][A-Z0-9-]*-\d+|R09G-G\d+)\s*\|", line)
        if not match:
            continue
        issue_id = match.group(1)
        issue_ids.append(issue_id)
        cells = re.split(r"(?<!\\)\|", line)[1:-1]
        if len(cells) < 5 or any(not cell.strip() for cell in cells):
            issues.append("Incomplete audit record: " + issue_id)
        elif cells[3].strip() not in statuses:
            issues.append("Invalid audit status: " + issue_id)
    if len(issue_ids) != len(set(issue_ids)):
        issues.append("Duplicate audit ID")
    initial_ids = ({"R09G-G%02d" % i for i in range(1, 10)}
                   | {"DOC-%03d" % i for i in range(1, 9)} | {"GOV-001"})
    if not initial_ids.issubset(issue_ids):
        issues.append("Missing initial audit ID")

    checkpoint = records.get("AUDIT_CHECKPOINT", "")
    fields = {}
    for line in checkpoint.splitlines():
        match = re.match(r"^\|\s*(repository / branch|reviewedHead|reviewedTree|当前任务|当前状态|设备验收|下一阶段)\s*\|\s*([^|]+)\|$", line)
        if match:
            name, value = match.groups()
            if name in fields:
                issues.append("Duplicate checkpoint field: " + name)
            fields[name] = value.strip()
    for name in ("repository / branch", "reviewedHead", "reviewedTree",
                 "当前任务", "当前状态", "设备验收", "下一阶段"):
        if not fields.get(name):
            issues.append("Missing checkpoint field: " + name)
    if fields.get("repository / branch") != "ttiaogou/FRDM / PP":
        issues.append("Invalid checkpoint repository/branch")
    for name in ("reviewedHead", "reviewedTree"):
        if not re.fullmatch(r"[0-9a-f]{40}", fields.get(name, "")):
            issues.append("Invalid checkpoint SHA: " + name)
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
                # R09-F: keep independent OCR test/build and pair delivery APKs on one runner.
                path = ".github/workflows/android-build.yml"
                self.assertEqual("89791661110e58988564a17fcb219ecc35f7ebc8", expected[path])
                expected[path] = "85129fe78eb39ebd36e3c042a397c1b01a7316f4"
                # R03 reviewed exact R00 gate update; keep every historical assertion
                # and protect the revised gate by its exact blob, not an exemption.
                path = "tools/architecture/test_pp_r00_freeze.py"
                self.assertEqual("1524f5031c0391d615fbf016d8ae9867d8086c78", expected[path])
                # R04 also pins the reviewed relocation; R03 remains in the R00 gate.
                expected[path] = "f3b31960b6a7240c1dac532d3a9d4ec618d86532"
                # R06-F appends the exact reviewed R00 source/owner pins.
                self.assertEqual("f3b31960b6a7240c1dac532d3a9d4ec618d86532", expected[path])
                expected[path] = "e693f9d3f699bc2d973500484327ab964d54eac1"
                # R07-B reviewed relocation to the existing observation owner.
                self.assertEqual("e693f9d3f699bc2d973500484327ab964d54eac1", expected[path])
                expected[path] = "69fed894d80b28862f552d5584500b25553c543a"
                # R07-C exact reviewed private visit relocation.
                self.assertEqual("69fed894d80b28862f552d5584500b25553c543a", expected[path])
                expected[path] = "5aa6a516787c9bb4503c36dac3ed130b8fc36cc7"
                # R07-C compile-only repair retains the prior exact pin.
                self.assertEqual("5aa6a516787c9bb4503c36dac3ed130b8fc36cc7", expected[path])
                expected[path] = "853a4a0e1662fee09328331d8b1bd5bdfe17f449"
                # R07-D exact reviewed greeting visit relocation.
                self.assertEqual("853a4a0e1662fee09328331d8b1bd5bdfe17f449", expected[path])
                expected[path] = "d42cc0350c728b036395c6980a52cbbef2dede6c"
                # R07-E exact reviewed read lifecycle relocation.
                self.assertEqual("d42cc0350c728b036395c6980a52cbbef2dede6c", expected[path])
                expected[path] = "41c70c7a33d37b233318eef9fc04c6a3417b5de5"
                # R07-E compile-only repair retains prior exact pin.
                self.assertEqual("41c70c7a33d37b233318eef9fc04c6a3417b5de5", expected[path])
                expected[path] = "0baa28ac95b8645e1528e7cb41bb8fd4bdf19869"
                # R07-F exact reviewed send and child binding relocation.
                self.assertEqual("0baa28ac95b8645e1528e7cb41bb8fd4bdf19869", expected[path])
                expected[path] = "f251ece52a0d39a2a450f9ea46d5d15c9154535f"
                # R07-G exact reviewed paired lifecycle relocation.
                self.assertEqual("f251ece52a0d39a2a450f9ea46d5d15c9154535f", expected[path])
                expected[path] = "331c0e421a41e17f1dd36a01d7c048784f560d03"
                # R07-H exact recovery observation relocation.
                self.assertEqual("331c0e421a41e17f1dd36a01d7c048784f560d03", expected[path])
                expected[path] = "6bc4ccf5b5b2e810227fb5f40027618fdd1ea6dd"
                # R08-A exact reviewed diagnostic lifecycle relocation.
                self.assertEqual("6bc4ccf5b5b2e810227fb5f40027618fdd1ea6dd", expected[path])
                expected[path] = "be349c8ace2514cb4e75597f513048d493c320f2"
                # R08-B exact reviewed browse evidence relocation.
                self.assertEqual("be349c8ace2514cb4e75597f513048d493c320f2", expected[path])
                expected[path] = "67fce22dc06c5840f164aa58f76bd10e9741805c"
                # R08-C exact reviewed private viewport policy relocation.
                self.assertEqual("67fce22dc06c5840f164aa58f76bd10e9741805c", expected[path])
                expected[path] = "2b233aeab2e7c5183d9c47c633276bb141ddd165"
                # R08-D exact reviewed export presentation relocation.
                self.assertEqual("2b233aeab2e7c5183d9c47c633276bb141ddd165", expected[path])
                expected[path] = "e90afbdd451822838d2889adb3023ba755814983"
                # R08-E exact reviewed diagnostic serialization relocation.
                self.assertEqual("e90afbdd451822838d2889adb3023ba755814983", expected[path])
                expected[path] = "76a60f7086f827234d58ddc5ea32817c92503761"
                # R08-F exact reviewed runtime-local preload relocation.
                self.assertEqual("76a60f7086f827234d58ddc5ea32817c92503761", expected[path])
                expected[path] = "99bdc00f6d7c1f2828c96f6e5aa8e1842bd501aa"
                # R08-G exact reviewed evidence-model extraction.
                self.assertEqual("99bdc00f6d7c1f2828c96f6e5aa8e1842bd501aa", expected[path])
                expected[path] = "b1f1ff7d6322412d1b129c74509e4fd21128300a"
                # R08-H exact reviewed node-read fallback convergence.
                self.assertEqual("b1f1ff7d6322412d1b129c74509e4fd21128300a", expected[path])
                expected[path] = "88822eb6021711bc55ee6396bd7e536f33838f78"
                # R09-B exact retired-handle cleanup; original manifest and S0 unchanged.
                self.assertEqual("88822eb6021711bc55ee6396bd7e536f33838f78", expected[path])
                expected[path] = "009a91302db56eb8faa4767e9835c65b321c05d8"
                # R09-B1/B2/B3 exact approved production changes.
                self.assertEqual("009a91302db56eb8faa4767e9835c65b321c05d8", expected[path])
                expected[path] = "ecd3b1ce0ea3dec8ccd5cb787434a397974ce15a"
                # R09-B1 CI rollback preserves original overlay contract.
                self.assertEqual("ecd3b1ce0ea3dec8ccd5cb787434a397974ce15a", expected[path])
                expected[path] = "8233890fe4ad1f273070e1f10e95ce7c625ffc12"
                # R09-D exact verified-contact preload isolation.
                self.assertEqual("8233890fe4ad1f273070e1f10e95ce7c625ffc12", expected[path])
                expected[path] = "2cdc86e6aea3926b836a937c9e608bc1fdb15e6f"
                # D01-B: exact reviewed S1 node ZIP retirement; all S0 pins remain.
                self.assertEqual("2cdc86e6aea3926b836a937c9e608bc1fdb15e6f", expected[path])
                expected[path] = "82f2d6f342a6dd3e4f89fc385d1958f236700aa3"
                # D01-C exact approved export-range changes.
                self.assertEqual("82f2d6f342a6dd3e4f89fc385d1958f236700aa3", expected[path])
                expected[path] = "35228cb4bc9cd4a72b81bfb014550b9891070d05"
                # D01-D1 exact retirement; all live source and S0 guards remain.
                self.assertEqual("35228cb4bc9cd4a72b81bfb014550b9891070d05", expected[path])
                expected[path] = "cd45c1be998ee7de2a217db2ad00e1688bd55a06"
                # D01-D2: exact S1 retirement; S0 and the original manifest stay unchanged.
                self.assertEqual("cd45c1be998ee7de2a217db2ad00e1688bd55a06", expected[path])
                expected[path] = "aed81c353771212cff21424a8c9e3d3ad75dd6ab"
                # D01-D3: one exact authorized S0 node-only migration; original manifest preserved.
                self.assertEqual("aed81c353771212cff21424a8c9e3d3ad75dd6ab", expected[path])
                expected[path] = "6f80d78495bb6d21fcc7bfb095795248e68d64c7"
                # R10-A preserves the original manifests and registers the exact source change.
                self.assertEqual("6f80d78495bb6d21fcc7bfb095795248e68d64c7", expected[path])
                expected[path] = "b8a440786d9809c732a98dbf4d111781a80ec0a3"
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
                # R07-B reviewed relocation to the existing observation owner.
                self.assertEqual("f625a28a50f90ee07d6d32587a776c4a39fdc66a", expected[path])
                expected[path] = "d38ca403c504f37db495383cbfd8c5ee60d8d942"
                # R07-E exact reviewed read lifecycle relocation.
                self.assertEqual("d38ca403c504f37db495383cbfd8c5ee60d8d942", expected[path])
                expected[path] = "89f4d091c964ff88d59a52d79aadd2721f883a4d"
                # R07-F exact reviewed send and child binding relocation.
                self.assertEqual("89f4d091c964ff88d59a52d79aadd2721f883a4d", expected[path])
                expected[path] = "f8c5b40f2dbf9ed8feac6985df441532998b7ef5"
                # R07-G exact reviewed paired lifecycle relocation.
                self.assertEqual("f8c5b40f2dbf9ed8feac6985df441532998b7ef5", expected[path])
                expected[path] = "8970502be84b80b5dcfab25cde196cbee26392fe"
                path = "tools/architecture/test_verify_boundaries.py"
                self.assertEqual("bec2d575a43477763c2309a04a9e6014ad39967a", expected[path])
                expected[path] = "06485947b2fda4007b4ef09d1371c06b2f745fb2"
                # R07-B reviewed relocation to the existing observation owner.
                self.assertEqual("06485947b2fda4007b4ef09d1371c06b2f745fb2", expected[path])
                expected[path] = "92633e6fdc095e6780325ec923712a249821194b"
            if field == "protected_test_files":
                # R10-A explicitly changes only the confirmed-row/unknown-status behavior.
                path = "app/src/test/java/com/freedom/adapters/social/momo/MomoInterceptionRecoveryPolicyTest.kt"
                self.assertEqual("cceade363e6d5f10a245154fccc3cbe09cdbc111", expected[path])
                expected[path] = "8f5ce67a1ca6715f0364ce394bc22cbc49ce54f9"
            if field == "protected_test_files":
                # R09-F CI: relocate the OCR artifact assertion to its verified delivery job.
                path = "app/src/test/java/com/freedom/architecture/ArchitectureBoundaryTest.kt"
                self.assertEqual("f7158e8658aafc9c58efd9d4fc6322fbc0178b1a", expected[path])
                expected[path] = "5de62c7a40ea101eb4a99291264fac0b617f0b1a"
            if field == "protected_test_files":
                # Reflection targets follow the actual owner; original regressions remain.
                for path, (previous, replacement) in {
                    "business/src/test/kotlin/com/freedom/features/aireply/AiReplyObservationRuntimeTest.kt": ("ebca85f52082cb8954bba1ce780e815f2f46a2e4", "2ac15e03b5ecc30548dc492f0c0a01ea9403d077"),
                    "business/src/test/kotlin/com/freedom/features/aireply/MomoPageObservationCoordinatorTest.kt": ("2ce47f253365ea3841b53eff4e0d30a073eca23a", "690d0330efb0dfce042ebae573f6b6fc41a186c9"),
                }.items():
                    self.assertEqual(previous, expected[path])
                    expected[path] = replacement
            if field == "protected_test_files":
                # R07-C keeps existing tests; binding fixtures target the true owner.
                for path, (previous, replacement) in {
                    "business/src/test/kotlin/com/freedom/features/aireply/AiReplyObservationRuntimeTest.kt": ("2ac15e03b5ecc30548dc492f0c0a01ea9403d077", "8751283ecb1586a67c4cd106387118f136019259"),
                    "business/src/test/kotlin/com/freedom/features/aireply/PrivateMessagesTraversalTaskExecutorTest.kt": ("1e274631f878868e244326214bacf091e289f4cf", "1434666f844bcbc6226cab2a44e439da68c59ed2"),
                }.items():
                    self.assertEqual(previous, expected[path])
                    expected[path] = replacement
            if field == "protected_test_files":
                # R07-D keeps existing regressions, moves binding fixture and adds owner tests.
                for path, (previous, replacement) in {
                    "business/src/test/kotlin/com/freedom/features/aireply/AiReplyObservationRuntimeTest.kt": ("8751283ecb1586a67c4cd106387118f136019259", "26bb2a5e7c6c89de865189270761b2986c2e7475"),
                    "business/src/test/kotlin/com/freedom/features/aireply/ReceivedGreetingsTaskFlowControllerTest.kt": ("d99439f0d216087371040251ab727b258df58f97", "3cd7cc0f0d195e4b011800e172b64db92b409811"),
                }.items():
                    self.assertEqual(previous, expected[path])
                    expected[path] = replacement
            if field == "protected_test_files":
                # R07-E retains existing assertions and adds same-window stale callback and cleanup coverage.
                for path, (previous, replacement) in {
                    "business/src/test/kotlin/com/freedom/features/aireply/AiReplyObservationRuntimeTest.kt": ("26bb2a5e7c6c89de865189270761b2986c2e7475", "e42a0caa2871b9661ace38bef5019bfd32397fe5"),
                    "business/src/test/kotlin/com/freedom/features/aireply/ChatMessageReadCoordinatorTest.kt": ("57ddd2f422245ec764ffede6e9ec347bcad1bcd8", "8c979b47f8638e8579b17dff8633434209665b98"),
                }.items():
                    self.assertEqual(previous, expected[path])
                    expected[path] = replacement
            if field == "protected_test_files":
                # R07-F adds receipt/child retirement and send-proof retry isolation tests.
                for path, (previous, replacement) in {
                    "business/src/test/kotlin/com/freedom/features/aireply/PrivateMessagesTraversalTaskExecutorTest.kt": ("1434666f844bcbc6226cab2a44e439da68c59ed2", "b65455085622625628eca5015ab53fbb0a5836ac"),
                    "business/src/test/kotlin/com/freedom/features/aireply/ChatMessageReadCoordinatorTest.kt": ("8c979b47f8638e8579b17dff8633434209665b98", "e89d9b105fbbd516a8346b639cf3e0eb6ad82ec5"),
                }.items():
                    self.assertEqual(previous, expected[path])
                    expected[path] = replacement
            if field == "protected_test_files":
                # R07-G: existing reflection fixture follows the real paired state owner.
                path = "business/src/test/kotlin/com/freedom/features/aireply/AiReplyObservationRuntimeTest.kt"
                self.assertEqual("e42a0caa2871b9661ace38bef5019bfd32397fe5", expected[path])
                expected[path] = "141287073659ea627d52443d2f8c9310c065dd21"
            if field == "protected_test_files":
                # R08-B: source assertions follow the actual Feature owner; all platform gates remain.
                path = "app/src/test/java/com/freedom/architecture/AccountBrowseManualHandoffBoundaryTest.kt"
                self.assertEqual("3ae02f95b291a0bf127794d4ffdb1f9fd9d57f84", expected[path])
                expected[path] = "3b0f76367242cf64aa4f4e498e17b4252971e61d"
            if field == "protected_test_files":
                # R09-B adds live region refresh reset/cancel regression without deleting any test.
                path = "business/src/test/kotlin/com/freedom/features/aireply/MomoPageObservationCoordinatorTest.kt"
                self.assertEqual("690d0330efb0dfce042ebae573f6b6fc41a186c9", expected[path])
                expected[path] = "2196239ec6feb94c51eb6bc6a70e87e2c51b977b"
            if field == "protected_test_files":
                # D01-B CI: the authorized retired ZIP must be absent, not present.
                path = "app/src/test/java/com/freedom/architecture/FloatingWindowLifecycleBoundaryTest.kt"
                self.assertEqual("b7e725a22c349128aa720bf32fa8e13f95336ca9", expected[path])
                expected[path] = "9dc38c8285e88d2d30f033f414f46b7111e6eab2"
                # D01-C: preserve historical floating checks and update only authorized menu expectations.
                self.assertEqual("9dc38c8285e88d2d30f033f414f46b7111e6eab2", expected[path])
                expected[path] = "0d3ac1e093e9a90f10290a2d11c463a94bcfe160"
            if field == "protected_test_files":
                # D01-D1: move legacy batch regression to the actual unified packager.
                path = "app/src/test/java/com/freedom/ui/logs/SessionSampleArchiveManagerTest.kt"
                self.assertEqual("755a8f1aa7c51037e3c1036223ea9e733d313423", expected[path])
                expected[path] = "efbc15dfc2c46e3c976caa10270e07c34aa2fa25"
            if field == "protected_test_files":
                # D01-D2: migrate the retired Activity presentation checks to the live overlays.
                path = "app/src/test/java/com/freedom/architecture/AccessibilityInspectorPipelineBoundaryTest.kt"
                self.assertEqual("14aa980f91bd9478b45b95790593439bd27167aa", expected[path])
                expected[path] = "8acfe58fc2d9d9f6f6d6e0958a794c8b4b0e8bbb"
            if field == "protected_test_files":
                # D01-D3: exact migration of obsolete manual/legacy highlight assertions.
                for path, (previous, current) in {
                    "app/src/test/java/com/freedom/application/monitor/AccessibilityTreePreviewUseCaseTest.kt": ("417e63bb7d8e5c608211a9e3e099f9dce7c995a1", "2a5e14313b881369cf894e2f5cbe3545402b0e92"),
                    "app/src/test/java/com/freedom/architecture/AccessibilityInspectorPipelineBoundaryTest.kt": ("8acfe58fc2d9d9f6f6d6e0958a794c8b4b0e8bbb", "36011d6f7db2726e0b056c0ad9a8e72c54dbe258"),
                    "app/src/test/java/com/freedom/application/monitor/GlobalNodeLayoutVisualizationUseCaseTest.kt": ("9534769443d7ca8376b72829b2da0fe5d3b9a5eb", "906ff57e562b332da26a31e2c4c3a42363fe90e9"),
                }.items():
                    self.assertEqual(previous, expected[path])
                    expected[path] = current
            if field == "protected_rule_files":
                # G01-E: only the explicitly approved append-only AGENTS governance navigation.
                # Preserve the original manifest pin and verify the exact new full blob.
                path = "AGENTS.md"
                self.assertEqual("e0879a754be35699d0cc8741caa4e31cccd1aa1b", expected[path])
                expected[path] = "edb31ea66ae4ff5f6fd8aea0dd3b347c7cf8352f"
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
        reviewed_migration_evidence = {
            "tools/architecture/test_pp_r05_draft_transaction_migration.py": "624e2df6d11189e26cbde371e413a12287cdb096",
            "tools/architecture/test_pp_r05_image_transaction_migration.py": "b6c9382900c53b460de1bd403f98373dffde004b",
            "tools/architecture/test_pp_r05_interception_transaction_migration.py": "983a9908e70616b0298e48a532c0b8cadb1414d8",
            "tools/architecture/test_pp_r05_follow_up_boundary_migration.py": "6d1103b2b8a7c7e4bdafd0e56d56436f4da5a573",
            "tools/architecture/test_pp_r05_facade_convergence.py": "5422e057795e4b30d6dde17123f7301d8a98fe46",
            "tools/architecture/test_pp_r05_integration_acceptance.py": "cb2537f571b13e7114f0ba4ecc8aba79785c43d8",
            "tools/architecture/test_pp_r06_page_evidence_migration.py": "75bb710a68fffd979258b4b7f453694cc86cfa40",
            "tools/architecture/test_pp_r06_private_candidate_migration.py": "4c75d9113449ceea34488255cc96471e0c194a1c",
            "tools/architecture/test_pp_r06_greeting_evidence_migration.py": "00f7dec146b9c7ce7eced4b146c94fe7443c7d0b",
            "tools/architecture/test_pp_r06_message_read_evidence_migration.py": "8f650cc1b7ffe73c9ed03f3d848ae97c97821a16",
            "business/src/test/kotlin/com/freedom/features/aireply/ReceivedGreetingsChatEvidenceTest.kt": "f2621a1c7cd67e765b593b417bdba550e082e78d",
        }
        # R06-F prepends the exact inverse to R06-E; retain its historical pin.
        path = "tools/architecture/test_pp_r06_message_read_evidence_migration.py"
        self.assertEqual("8f650cc1b7ffe73c9ed03f3d848ae97c97821a16", reviewed_migration_evidence[path])
        reviewed_migration_evidence[path] = "182f702579c5d26c5b592935e3abf5660da6882d"
        path = "tools/architecture/test_pp_r06_diagnostics_migration.py"
        self.assertNotIn(path, reviewed_migration_evidence)
        reviewed_migration_evidence[path] = "b8aa9ab9b90a3b9549efcf69545977b129a8cf29"
        # R06-G protects the integrated ownership and reconstruction gate.
        path = "tools/architecture/test_pp_r06_integration_acceptance.py"
        self.assertNotIn(path, reviewed_migration_evidence)
        reviewed_migration_evidence[path] = "9b37f8e5acbdeff306fba0ac78a1dac50c7cd735"
        # R07-B preserves the R06 inverse chain and moves the state owner assertion.
        for path, (previous, replacement) in {
            "tools/architecture/test_pp_r06_diagnostics_migration.py": ("b8aa9ab9b90a3b9549efcf69545977b129a8cf29", "d6c8f93abc902986c7e70cb6f5b7f2b7b284e276"),
            "tools/architecture/test_pp_r06_integration_acceptance.py": ("9b37f8e5acbdeff306fba0ac78a1dac50c7cd735", "9b6be1181f0d057c56037853c004b5bd6f32c6fa"),
        }.items():
            self.assertEqual(previous, reviewed_migration_evidence[path])
            reviewed_migration_evidence[path] = replacement
        path = "tools/architecture/test_pp_r07_page_observation_migration.py"
        self.assertNotIn(path, reviewed_migration_evidence)
        reviewed_migration_evidence[path] = "f2620e02c77dea679f3a44f8ab71215d5d67be51"
        # R07-C preserves the complete historical inverse chain and true state ownership.
        for path, (previous, replacement) in {
            "tools/architecture/test_pp_r07_page_observation_migration.py": ("f2620e02c77dea679f3a44f8ab71215d5d67be51", "7926398e95b7f7ededa760b3d0ba93b2e9222e9f"),
            "tools/architecture/test_pp_r06_integration_acceptance.py": ("9b6be1181f0d057c56037853c004b5bd6f32c6fa", "0c1322687be5cb454b8d65d0a98a1113611abd25"),
        }.items():
            self.assertEqual(previous, reviewed_migration_evidence[path])
            reviewed_migration_evidence[path] = replacement
        path = "tools/architecture/test_pp_r07_private_visit_migration.py"
        self.assertNotIn(path, reviewed_migration_evidence)
        reviewed_migration_evidence[path] = "b8f63983f68c2949c2145234c2225ac8e4bfbfe6"
        # R07-C CI fix: preserve R06-B mutation coverage at its relocated live binding owner.
        path = "tools/architecture/test_pp_r06_page_evidence_migration.py"
        self.assertEqual("75bb710a68fffd979258b4b7f453694cc86cfa40", reviewed_migration_evidence[path])
        reviewed_migration_evidence[path] = "1505d976cf8940d96a936b1bdda5d05ef216199d"
        # R07-C compile-only repair extends the inverse and rejected-call mutation coverage.
        path = "tools/architecture/test_pp_r07_private_visit_migration.py"
        self.assertEqual("b8f63983f68c2949c2145234c2225ac8e4bfbfe6", reviewed_migration_evidence[path])
        reviewed_migration_evidence[path] = "eaf74669222604021c1b940c371a7db516a8cda6"
        # R07-D preserves the earlier inverse chain and moves the live binding owner.
        for path, (previous, replacement) in {
            "tools/architecture/test_pp_r07_private_visit_migration.py": ("eaf74669222604021c1b940c371a7db516a8cda6", "c5064427432b478d91fc4d0a90e4a3c029e251bd"),
            "tools/architecture/test_pp_r06_integration_acceptance.py": ("0c1322687be5cb454b8d65d0a98a1113611abd25", "1539db80216a866c908b27d2d6061964dc32862e"),
        }.items():
            self.assertEqual(previous, reviewed_migration_evidence[path])
            reviewed_migration_evidence[path] = replacement
        path = "tools/architecture/test_pp_r07_greeting_visit_migration.py"
        self.assertNotIn(path, reviewed_migration_evidence)
        reviewed_migration_evidence[path] = "8666cfb7f9d66e2c3e9eafc429349bec775fc507"
        # R07-E preserves the historical inverse chain and live owner checks.
        for path, (previous, replacement) in {
            "tools/architecture/test_pp_r07_greeting_visit_migration.py": ("8666cfb7f9d66e2c3e9eafc429349bec775fc507", "53334ae767ecedd73b214e7696d27ea67ecda9fa"),
            "tools/architecture/test_pp_r06_integration_acceptance.py": ("1539db80216a866c908b27d2d6061964dc32862e", "0b49878a13d91daa5141dd19ff71b3a9d1f57e0a"),
        }.items():
            self.assertEqual(previous, reviewed_migration_evidence[path])
            reviewed_migration_evidence[path] = replacement
        path = "tools/architecture/test_pp_r07_message_read_lifecycle_migration.py"
        self.assertNotIn(path, reviewed_migration_evidence)
        reviewed_migration_evidence[path] = "7c1c21b4643d7e32b1b5a4b622c9bd0bfda0bc6d"
        # R07-E compile-only repair extends exact inverse and explicit type protection.
        path = "tools/architecture/test_pp_r07_message_read_lifecycle_migration.py"
        self.assertEqual("7c1c21b4643d7e32b1b5a4b622c9bd0bfda0bc6d", reviewed_migration_evidence[path])
        reviewed_migration_evidence[path] = "019f92291a5d403f4f13cc5c11c6933dbf265628"
        # R07-F exact inverse of Runtime and both existing owners preserves all prior gates.
        for path, (previous, replacement) in {
            "tools/architecture/test_pp_r07_message_read_lifecycle_migration.py": ("019f92291a5d403f4f13cc5c11c6933dbf265628", "8786781e3e7e012d97fbe2185bebc6e524fe5568"),
            "tools/architecture/test_pp_r07_private_visit_migration.py": ("c5064427432b478d91fc4d0a90e4a3c029e251bd", "84960e17041e918580fbf9720343d236e72c3e00"),
            "tools/architecture/test_pp_r06_integration_acceptance.py": ("0b49878a13d91daa5141dd19ff71b3a9d1f57e0a", "cb56d2aa25ddcf787ce349a217936534a6b45fa0"),
        }.items():
            self.assertEqual(previous, reviewed_migration_evidence[path])
            reviewed_migration_evidence[path] = replacement
        path = "tools/architecture/test_pp_r07_send_follow_up_lifecycle_migration.py"
        self.assertNotIn(path, reviewed_migration_evidence)
        reviewed_migration_evidence[path] = "9e4dc213fa52a4e7a7d5747523ba7922562e7aa8"
        # R07-G extends the exact inverse chain; historical assertions remain.
        path = "tools/architecture/test_pp_r07_send_follow_up_lifecycle_migration.py"
        self.assertEqual("9e4dc213fa52a4e7a7d5747523ba7922562e7aa8", reviewed_migration_evidence[path])
        reviewed_migration_evidence[path] = "edbd28d0d71fd21cb159de419b64c345af7076ce"
        path = "tools/architecture/test_pp_r07_paired_task_lifecycle_migration.py"
        self.assertNotIn(path, reviewed_migration_evidence)
        reviewed_migration_evidence[path] = "d9b311939034c99a543e35cd1ff0a2942e327a7d"
        # R07-H extends the historical inverse chain without changing prior assertions.
        path = "tools/architecture/test_pp_r07_paired_task_lifecycle_migration.py"
        self.assertEqual("d9b311939034c99a543e35cd1ff0a2942e327a7d", reviewed_migration_evidence[path])
        reviewed_migration_evidence[path] = "aec54c7b4d1f670d3266991ecbc4eaae1b9da9ac"
        path = "tools/architecture/test_pp_r07_recovery_lifecycle_migration.py"
        self.assertNotIn(path, reviewed_migration_evidence)
        reviewed_migration_evidence[path] = "c0e59b4d2737c36fd5b565a21078d9a9e3950e52"
        # R07-I integrated acceptance: preserve all historical evidence and pin the new test.
        path = "tools/architecture/test_pp_r07_integration_acceptance.py"
        self.assertNotIn(path, reviewed_migration_evidence)
        reviewed_migration_evidence[path] = "b3afef44df7cc76510d23862e4849d0db93a6697"
        # R08-A preserves all existing evidence and pins the exact inverse.
        path = "tools/architecture/test_pp_r08_draft_diagnostics_migration.py"
        self.assertNotIn(path, reviewed_migration_evidence)
        reviewed_migration_evidence[path] = "630af8b38c711dfc3660d350296ef28447e7c5ba"
        # R08-B preserves prior evidence and pins exact migration of source assertions.
        path = "tools/architecture/test_pp_r08_browse_runtime_migration.py"
        self.assertNotIn(path, reviewed_migration_evidence)
        reviewed_migration_evidence[path] = "e618aa641cc85c401cba9656b1ca29c70e0b8481"
        # R08-C preserves all historical inverse checks and pins the new direct tests.
        path = "tools/architecture/test_pp_r07_send_follow_up_lifecycle_migration.py"
        self.assertEqual("edbd28d0d71fd21cb159de419b64c345af7076ce", reviewed_migration_evidence[path])
        reviewed_migration_evidence[path] = "9aeb3c8b2a21a49276e1d330dec8faf102fd5e7e"
        for path, replacement in {
            "tools/architecture/test_pp_r08_private_viewport_policy_migration.py": "5fbe0ee152b30e1f05e67d3bb07c6e21f8ed1347",
            "business/src/test/kotlin/com/freedom/features/aireply/ports/R08PrivateViewportPolicyTest.kt": "7782eff5c10d273eaa61706da7c5c5b9d585d961",
        }.items():
            self.assertNotIn(path, reviewed_migration_evidence)
            reviewed_migration_evidence[path] = replacement
        # R08-C test-only correction: existing decoder retains its marker prefix.
        path = "business/src/test/kotlin/com/freedom/features/aireply/ports/R08PrivateViewportPolicyTest.kt"
        self.assertEqual("7782eff5c10d273eaa61706da7c5c5b9d585d961", reviewed_migration_evidence[path])
        reviewed_migration_evidence[path] = "b2749e4b4739c6f6c9f8c155751117d8d29789cd"
        # R08-D preserves historical evidence and pins exact presentation migration.
        path = "tools/architecture/test_pp_r08_activity_export_presentation_migration.py"
        self.assertNotIn(path, reviewed_migration_evidence)
        reviewed_migration_evidence[path] = "d17659994930f521942b574fd0e7dec3b8f5acd8"
        # R08-E preserves historical evidence and pins the exact inverse.
        path = "tools/architecture/test_pp_r08_diagnostic_serialization_migration.py"
        self.assertNotIn(path, reviewed_migration_evidence)
        reviewed_migration_evidence[path] = "f34263967f041f6f9e0734fc4388e2dbe58909d2"
        # R08-F retains all historical evidence and pins direct behavior and exact inverse.
        for path, replacement in {
            "tools/architecture/test_pp_r08_composition_history_migration.py": "bed8d6302c2a573fa318bdd3a130a368436b77ec",
            "business/src/test/kotlin/com/freedom/features/conversation/domain/contact/R08DraftHistoryPreloadTest.kt": "74da3cfefa90d189fa1541786cf7f8211e691b6b",
        }.items():
            self.assertNotIn(path, reviewed_migration_evidence)
            reviewed_migration_evidence[path] = replacement
        # R08-G exact evidence and interface contract reconstruction.
        path = "tools/architecture/test_pp_r08_chat_page_contract_migration.py"
        self.assertNotIn(path, reviewed_migration_evidence)
        reviewed_migration_evidence[path] = "45252af931dc11bcff7f934e36d111abb857dae1"
        # R08-H exact inverse and direct read fallback tests.
        for path, replacement in {
            "tools/architecture/test_pp_r08_momo_read_fallback_convergence.py": "4cb01a2609b86c0d3d6ab4bc4ae310b49dd7ca5b",
            "app/src/test/java/com/freedom/adapters/social/momo/R08MomoNodeReadFallbacksTest.kt": "86e2878392bc9cfe751c6e5f090669b8c14c0e55",
        }.items():
            self.assertNotIn(path, reviewed_migration_evidence)
            reviewed_migration_evidence[path] = replacement
        # R09-A: additional whole-checkout coverage and domain ownership evidence.
        path = "tools/architecture/test_pp_r09a_repository_acceptance.py"
        self.assertNotIn(path, reviewed_migration_evidence)
        reviewed_migration_evidence[path] = "3976325d97ac6c1ee70da31a39f5c5fb3f5f6d9a"
        # R09-A CI audit: pin the exact known excluded, declaration-free history placeholder.
        self.assertEqual("3976325d97ac6c1ee70da31a39f5c5fb3f5f6d9a", reviewed_migration_evidence[path])
        reviewed_migration_evidence[path] = "ca9b2b6b5d84ef0c2b8d447bfd3a280a66a03f69"
        # R09-B extends historical reconstruction by one exact inverse and prohibits the retired field.
        for path, (previous, replacement) in {
            "tools/architecture/test_pp_r07_page_observation_migration.py": ("7926398e95b7f7ededa760b3d0ba93b2e9222e9f", "26bf86bf75b6038197b7c25daa6dc6b3c4933bfe"),
            "tools/architecture/test_pp_r07_integration_acceptance.py": ("b3afef44df7cc76510d23862e4849d0db93a6697", "49cef760108aa85a1f5cdc9950ea6a45a87282b9"),
            "tools/architecture/test_pp_r06_integration_acceptance.py": ("cb56d2aa25ddcf787ce349a217936534a6b45fa0", "2229644b6a27918cf80832353cf0b9427b9c2b95"),
            "tools/architecture/test_pp_r07_recovery_lifecycle_migration.py": ("c0e59b4d2737c36fd5b565a21078d9a9e3950e52", "bfd4780e475a99c717e595d6c9f3d6855f9cb2ed"),
        }.items():
            self.assertEqual(previous, reviewed_migration_evidence[path])
            reviewed_migration_evidence[path] = replacement
        path = "tools/architecture/test_pp_r09b_legacy_cleanup.py"
        self.assertNotIn(path, reviewed_migration_evidence)
        reviewed_migration_evidence[path] = "4822d269aa6e799b72111f6ac386858e853c5ae5"
        path = "tools/architecture/test_pp_r09b_legacy_inventory.py"
        self.assertNotIn(path, reviewed_migration_evidence)
        reviewed_migration_evidence[path] = "3e5c5d0e7f913113749e72133e101cb28091ccea"
        # R09-B1/B2/B3 exact inverse and direct HTTP regression evidence.
        for path, replacement in {
            "tools/architecture/test_pp_r09b_followup_cleanup.py": "1a07053392e4699bc752edf40ddc47c0ad81090d",
            "app/src/test/java/com/freedom/adapters/ai/AiHttpTransportSupportTest.kt": "3fd5846c0af9bbc31d00f0cb08add604f40bb9d8",
        }.items():
            self.assertNotIn(path, reviewed_migration_evidence)
            reviewed_migration_evidence[path] = replacement
        # R09-B1 CI: retain exact historical patch evidence and enforce restored live contract.
        path = "tools/architecture/test_pp_r09b_followup_cleanup.py"
        self.assertEqual("1a07053392e4699bc752edf40ddc47c0ad81090d", reviewed_migration_evidence[path])
        reviewed_migration_evidence[path] = "3838580c5e544cd360a2503f78113f423008443a"
        # R09-C additional whole-source and combined runtime authority regression evidence.
        for path, replacement in {
            "tools/architecture/test_pp_r09c_authority_acceptance.py": "33bba22ec8750c58b7005ef2c86d18c09f8f8bfa",
            "app/src/test/java/com/freedom/application/runtime/R09cPageWriteAuthorityTest.kt": "0b18cc0940e3a65e09d46b503ebd59d462718141",
        }.items():
            self.assertNotIn(path, reviewed_migration_evidence)
            reviewed_migration_evidence[path] = replacement
        # R09-D exact historical inverse and new async/recovery test evidence.
        path = "tools/architecture/test_pp_r08_composition_history_migration.py"
        self.assertEqual("bed8d6302c2a573fa318bdd3a130a368436b77ec", reviewed_migration_evidence[path])
        reviewed_migration_evidence[path] = "83b0e32117415b1d924c9c418b2a77e956d51fcd"
        for path, replacement in {
            "tools/architecture/test_pp_r09d_recovery_acceptance.py": "f348c1c15287c55740e4bb425355e9a97a07791c",
            "business/src/test/kotlin/com/freedom/features/recovery/R09dRecoveryIsolationTest.kt": "6be49782b3a36b78b384def1b50549287510271e",
            "business/src/test/kotlin/com/freedom/features/conversation/domain/contact/R09dDraftHistoryIsolationTest.kt": "6cf04b6a45b5bfbd6465246bfe45b1e484383083",
        }.items():
            self.assertNotIn(path, reviewed_migration_evidence)
            reviewed_migration_evidence[path] = replacement
        # R09-F: exact additional APK delivery guard evidence.
        path = "tools/architecture/test_pp_r09f_apk_delivery.py"
        self.assertNotIn(path, reviewed_migration_evidence)
        reviewed_migration_evidence[path] = "0e5244a48297dee14ba617b4c916d5961ab62df0"
        # D01-B: retain R08-D inverse and pin the exact retirement regression.
        path = "tools/architecture/test_pp_r08_activity_export_presentation_migration.py"
        self.assertEqual("d17659994930f521942b574fd0e7dec3b8f5acd8", reviewed_migration_evidence[path])
        reviewed_migration_evidence[path] = "4e9b6e41c6368cc2719b20600ff4a58389a57d17"
        path = "tools/architecture/test_pp_d01b_node_zip_retirement.py"
        self.assertNotIn(path, reviewed_migration_evidence)
        reviewed_migration_evidence[path] = "3fdc82bc0c55b47f47ae544f8fa08c29b9feaa31"
        # D01-C preserves the D01-B inverse, extending it by an exact reviewed change.
        self.assertEqual("3fdc82bc0c55b47f47ae544f8fa08c29b9feaa31", reviewed_migration_evidence[path])
        reviewed_migration_evidence[path] = "37b9ed6d70d625b1412b6496e3ca89920ec93db2"
        for path, replacement in {
            "tools/architecture/test_pp_d01c_combined_export_migration.py": "57e04ae8b0de0f182ee7c31417c1e76b7404875f",
            "app/src/test/java/com/freedom/ui/logs/D01cCombinedExportTest.kt": "73bd68e842bf5c010f56aa0b0047d1bd34cf3db1",
        }.items():
            self.assertNotIn(path, reviewed_migration_evidence)
            reviewed_migration_evidence[path] = replacement
        # D01-D1: keep the D01-C inverse and extend it with exact old-writer retirement.
        path = "tools/architecture/test_pp_d01c_combined_export_migration.py"
        self.assertEqual("57e04ae8b0de0f182ee7c31417c1e76b7404875f", reviewed_migration_evidence[path])
        reviewed_migration_evidence[path] = "311ad0b9c0537385fbe7c5b53087cf89c9942d30"
        path = "tools/architecture/test_pp_d01d1_batch_packager_retirement.py"
        self.assertNotIn(path, reviewed_migration_evidence)
        reviewed_migration_evidence[path] = "80a7acc2c6f867ad14d153266434c362d39f5589"
        # D01-D2: preserve historical inspector tests and require retired symbols absent.
        path = "tools/architecture/test_pp_d01d2_legacy_inspector_retirement.py"
        self.assertNotIn(path, reviewed_migration_evidence)
        reviewed_migration_evidence[path] = "121afa08950dfa3caad75e9b5d07ed4239be6d51"
        # D01-D3: retain exact historical proofs through the authorized inverse.
        for path, (previous, current) in {
            "tools/architecture/test_pp_d01b_node_zip_retirement.py": ("37b9ed6d70d625b1412b6496e3ca89920ec93db2", "f0ed49a254ff293e198ac44eb15ae19b64e3c8c3"),
            "tools/architecture/test_pp_d01d2_legacy_inspector_retirement.py": ("121afa08950dfa3caad75e9b5d07ed4239be6d51", "1d7fde2b059b8d742cc74962fa7fd7c0997756e7"),
            "tools/architecture/test_pp_r05_facade_convergence.py": ("5422e057795e4b30d6dde17123f7301d8a98fe46", "70b530adc1f034b2d17ac801aec67d614d52cb14"),
            "tools/architecture/test_pp_r08_composition_history_migration.py": ("83b0e32117415b1d924c9c418b2a77e956d51fcd", "c22692bc7c38cfb81af3fedde4ebd4eed69342e9"),
        }.items():
            self.assertEqual(previous, reviewed_migration_evidence[path])
            reviewed_migration_evidence[path] = current
        path = "tools/architecture/test_pp_d01d3_legacy_node_cache_retirement.py"
        self.assertNotIn(path, reviewed_migration_evidence)
        reviewed_migration_evidence[path] = "fed2bc5b09ee2e2afceb9f8c5a425ce5687bbe62"
        # D01-D4: add independent repository reference and integration regression evidence.
        path = "tools/architecture/test_pp_d01d4_node_export_reference_regression.py"
        self.assertNotIn(path, reviewed_migration_evidence)
        reviewed_migration_evidence[path] = "d069e1b60408b2cb4789286d4b62be93bc091cc2"
        # R10-A exact inverse chain; no exemptions from prior proofs.
        path = "tools/architecture/test_pp_d01d3_legacy_node_cache_retirement.py"
        self.assertEqual("fed2bc5b09ee2e2afceb9f8c5a425ce5687bbe62", reviewed_migration_evidence[path])
        reviewed_migration_evidence[path] = "01dba43f13ccde5ac9dc4cbe1329a3b64c8ebfc4"
        path = "tools/architecture/test_pp_r05_interception_transaction_migration.py"
        self.assertEqual("983a9908e70616b0298e48a532c0b8cadb1414d8", reviewed_migration_evidence[path])
        reviewed_migration_evidence[path] = "99c84268d3aabfe5e2d0f706a04e190069df42b8"
        path = "tools/architecture/test_pp_r10a_child_component_evidence.py"
        self.assertNotIn(path, reviewed_migration_evidence)
        reviewed_migration_evidence[path] = "b7d7dbdcfcc577811a7afebac7ffb4848c41fe08"
        issues.extend(evidence_violations(ROOT, reviewed_migration_evidence))
        self.assertEqual([], issues)

    def test_d01b_floating_export_contract_exact_migration(self):
        path = ROOT / "app/src/test/java/com/freedom/architecture/FloatingWindowLifecycleBoundaryTest.kt"
        source = path.read_text(encoding="utf-8")
        from tools.architecture.test_pp_d01c_combined_export_migration import FLOATING_TEST, restore_d01c_source
        source = restore_d01c_source(FLOATING_TEST, source)
        self.assertEqual("9dc38c8285e88d2d30f033f414f46b7111e6eab2", git_blob(source.encode("utf-8")))
        for current, previous in [["            .substringBefore(\"private fun confirmSessionSampleExport(\")\n","            .substringBefore(\"private fun launchNodeDumpExport(\")\n"],["        assertFalse(recordsEntry.contains(\"launchNodeDumpExport()\"))\n        assertFalse(activity.contains(\"private fun launchNodeDumpExport(\"))\n","        assertTrue(recordsEntry.contains(\"launchNodeDumpExport()\"))\n"]]:
            self.assertEqual(1, source.count(current))
            source = source.replace(current, previous, 1)
        self.assertEqual("b7e725a22c349128aa720bf32fa8e13f95336ca9", git_blob(source.encode("utf-8")))

    def test_g01e_agents_exact_append_only_migration(self):
        path = ROOT / "AGENTS.md"
        self.assertFalse(path.is_symlink())
        actual = path.read_bytes()
        self.assertEqual("e0879a754be35699d0cc8741caa4e31cccd1aa1b",
                         self.manifest["protected_rule_files"]["AGENTS.md"])
        self.assertEqual(9563, len(actual))
        self.assertEqual("e0879a754be35699d0cc8741caa4e31cccd1aa1b",
                         git_blob(actual[:7238]))
        self.assertEqual("edb31ea66ae4ff5f6fd8aea0dd3b347c7cf8352f", git_blob(actual))
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            entry = root / "AGENTS.md"
            expected = {"AGENTS.md": "edb31ea66ae4ff5f6fd8aea0dd3b347c7cf8352f"}
            entry.write_bytes(actual)
            self.assertEqual([], evidence_violations(root, expected))
            for changed in (actual[:7238], b"!" + actual[1:], actual + b"\n"):
                with self.subTest(length=len(changed)):
                    entry.write_bytes(changed)
                    self.assertEqual(["Protected evidence changed: AGENTS.md"],
                                     evidence_violations(root, expected))

    def test_g01e_governance_records_are_structurally_valid(self):
        self.assertEqual([], governance_record_violations(ROOT))

    def test_g01e_governance_records_reject_loss_duplicates_and_invalid_status(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            audits = root / "docs/audits"
            audits.mkdir(parents=True)
            for name in ("RESPONSIBILITY_MAP.md", "AUDIT_REGISTER.md", "AUDIT_CHECKPOINT.md"):
                (audits / name).write_bytes((ROOT / "docs/audits" / name).read_bytes())
            self.assertEqual([], governance_record_violations(root))
            own = audits / "RESPONSIBILITY_MAP.md"
            original = own.read_text(encoding="utf-8")
            row = next(line for line in original.splitlines() if line.startswith("| OWN-01 |"))
            own.write_text(original + "\n" + row + "\n", encoding="utf-8")
            self.assertIn("Duplicate responsibility ID", governance_record_violations(root))
            own.write_text(original.replace(row, "", 1), encoding="utf-8")
            self.assertIn("Missing initial responsibility ID", governance_record_violations(root))
            own.write_text(original, encoding="utf-8")
            ledger = audits / "AUDIT_REGISTER.md"
            original = ledger.read_text(encoding="utf-8")
            row = next(line for line in original.splitlines() if line.startswith("| R09G-G01 |"))
            ledger.write_text(original + "\n" + row + "\n", encoding="utf-8")
            self.assertIn("Duplicate audit ID", governance_record_violations(root))
            cells = re.split(r"(?<!\\)\|", row)[1:-1]
            cells[3] = " INVALID_STATUS "
            ledger.write_text(original.replace(row, "|" + "|".join(cells) + "|", 1), encoding="utf-8")
            self.assertIn("Invalid audit status: R09G-G01", governance_record_violations(root))
            ledger.write_text(original, encoding="utf-8")
            # Mutable records may grow; the gate must not require the original count or blob.
            ledger.write_text(original + "\n| GOV-999 | test | evidence | CANDIDATE | review |\n",
                              encoding="utf-8")
            self.assertEqual([], governance_record_violations(root))
            checkpoint = audits / "AUDIT_CHECKPOINT.md"
            checkpoint.unlink()
            self.assertIn("Missing or symlink governance record: AUDIT_CHECKPOINT",
                          governance_record_violations(root))

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
