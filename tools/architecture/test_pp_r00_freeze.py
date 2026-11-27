"""PP R00: temporary pre-migration source gate plus durable S0 inventory.

Runs in the existing architecture unittest discovery; no Android runtime changes.
The snapshot is from the green PP Git tree, not generated from the checkout.
"""
import hashlib
import json
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
MANIFEST = Path(__file__).with_name("pp_r00_freeze.json")
BASELINE = "435406f593bc1dd512edac0af28f8f7c48feb0c2"
BASELINE_TREE = "f5e7d6b8a5bc334f66c4d4911975ffcab79a8ac4"
MANIFEST_BLOB = "4510f804078288427e8f8b61f2ec69e8a210bab7"
SOURCE_ROOTS = (
    "app/src/main", "business/src/main", "core/src/main",
    "gesture/gesture-api/src/main", "gesture/gesture-core/src/main",
    "gesture/gesture-runtime/src/main", "gesture/gesture-android/src/main",
    "ocr-plugin-api/src/main", "ocr-plugin/src/main",
)


def git_blob(data):
    # Git object identity, not a security signature. Hash actual checkout bytes.
    return hashlib.sha1(b"blob " + str(len(data)).encode("ascii") + b"\0" + data).hexdigest()


def inventory(root, source_roots, config_paths):
    paths = set(config_paths)
    for source_root in source_roots:
        directory = root / source_root
        if not directory.is_dir() or directory.is_symlink():
            raise AssertionError(f"Missing or redirected frozen source root: {source_root}")
        for path in directory.rglob("*"):
            if path.is_file() or path.is_symlink():
                paths.add(path.relative_to(root).as_posix())
    # Also reject newly introduced alternative build/config files in known modules.
    for directory in [root] + [(root / p).parent.parent for p in source_roots]:
        for name in ("build.gradle", "build.gradle.kts", "settings.gradle",
                     "settings.gradle.kts", "gradle.properties"):
            candidate = directory / name
            if candidate.exists() or candidate.is_symlink():
                paths.add(candidate.relative_to(root).as_posix())
        for candidate in directory.glob("*.pro"):
            paths.add(candidate.relative_to(root).as_posix())
    actual = {}
    for relative in sorted(paths):
        path = root / relative
        if path.is_symlink():
            actual[relative] = "SYMLINK"
        elif path.is_file():
            actual[relative] = git_blob(path.read_bytes())
    return actual


def freeze_violations(s0, s1, actual):
    if set(s0) & set(s1):
        raise ValueError("Overlapping S0/S1 ownership")
    expected = {**s0, **s1}
    issues = []
    for path, digest in sorted(expected.items()):
        tier = "S0" if path in s0 else "S1"
        if path not in actual:
            issues.append(f"{tier} missing: {path}")
        elif actual[path] != digest:
            issues.append(f"{tier} changed: {path}")
    for path in sorted(set(actual) - set(expected)):
        issues.append(f"Unapproved new frozen-scope file: {path}")
    return issues


class PpR00FreezeTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        raw = MANIFEST.read_bytes()
        if git_blob(raw) != MANIFEST_BLOB:
            raise AssertionError("R00 manifest changed; do not regenerate to make CI green")
        cls.manifest = json.loads(raw)

    def test_pinned_green_baseline_and_inventory(self):
        m = self.manifest
        self.assertEqual(1, m["schema"])
        self.assertEqual("ttiaogou/FRDM", m["repository"])
        self.assertEqual("PP", m["branch"])
        self.assertEqual(BASELINE, m["baseline"])
        self.assertEqual(BASELINE_TREE, m["baseline_tree"])
        self.assertEqual(46048752283, m["baseline_ci"])
        self.assertEqual(SOURCE_ROOTS, tuple(m["source_roots"]))
        self.assertEqual(15, len(m["config_paths"]))
        self.assertEqual(76, len(m["permanent_s0"]))
        self.assertEqual(393, len(m["pre_migration_s1"]))
        self.assertFalse(set(m["permanent_s0"]) & set(m["pre_migration_s1"]))
        files = {**m["permanent_s0"], **m["pre_migration_s1"]}
        self.assertEqual(469, len(files))
        self.assertEqual(set(m["config_paths"]), {
            p for p in files if not any(p.startswith(r + "/") for r in SOURCE_ROOTS)
        })
        for digest in files.values():
            self.assertRegex(digest, re.compile(r"^[0-9a-f]{40}$"))

    def test_checkout_matches_pre_migration_freeze(self):
        m = self.manifest
        actual = inventory(ROOT, SOURCE_ROOTS, m["config_paths"])
        self.assertEqual([], freeze_violations(
            m["permanent_s0"], m["pre_migration_s1"], actual))

    def test_detects_changed_deleted_added_and_moved_sources(self):
        s0 = {"core.kt": git_blob(b"core")}
        s1 = {"business.kt": git_blob(b"business")}
        original = {**s0, **s1}
        self.assertEqual([], freeze_violations(s0, s1, original))
        self.assertEqual(["S0 changed: core.kt"], freeze_violations(
            s0, s1, {**original, "core.kt": git_blob(b"changed")}))
        self.assertEqual(["S1 changed: business.kt"], freeze_violations(
            s0, s1, {**original, "business.kt": git_blob(b"changed")}))
        self.assertEqual(["S1 missing: business.kt"], freeze_violations(s0, s1, s0))
        self.assertEqual(["Unapproved new frozen-scope file: extra.kt"], freeze_violations(
            s0, s1, {**original, "extra.kt": git_blob(b"extra")}))
        self.assertEqual(["S1 missing: business.kt", "Unapproved new frozen-scope file: moved.kt"],
                         freeze_violations(s0, s1, {**s0, "moved.kt": s1["business.kt"]}))

    def test_inventory_reads_worktree_and_detects_extra_files(self):
        import tempfile
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / "app/src/main"
            source.mkdir(parents=True)
            file = source / "Original.kt"
            file.write_bytes(b"original")
            config = root / "app/build.gradle.kts"
            config.write_bytes(b"config")
            expected = {
                "app/src/main/Original.kt": git_blob(b"original"),
                "app/build.gradle.kts": git_blob(b"config"),
            }
            self.assertEqual(expected, inventory(root, ("app/src/main",), ("app/build.gradle.kts",)))
            file.write_bytes(b"changed")
            (source / "Added.kt").write_bytes(b"added")
            (root / "app/build.gradle").write_bytes(b"alternative")
            config.unlink()
            actual = inventory(root, ("app/src/main",), ("app/build.gradle.kts",))
            self.assertEqual(git_blob(b"changed"), actual["app/src/main/Original.kt"])
            self.assertIn("app/src/main/Added.kt", actual)
            self.assertIn("app/build.gradle", actual)
            self.assertNotIn("app/build.gradle.kts", actual)
