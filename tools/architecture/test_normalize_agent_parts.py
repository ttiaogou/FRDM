import tempfile
import unittest
from pathlib import Path

from normalize_agent_parts import PARTS, normalize


class NormalizeAgentPartsTest(unittest.TestCase):
    def make_root(self, fragments: list[bytes]) -> Path:
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        root = Path(temporary.name)
        parts_dir = root / "docs/agent/parts"
        parts_dir.mkdir(parents=True)
        (root / "docs/agent/source").mkdir(parents=True)
        (root / "docs/agent/source/AGENTS.original.md").write_bytes(b"".join(fragments))
        for (escaped_name, _), content in zip(PARTS, fragments):
            (parts_dir / escaped_name).write_bytes(content)
        return root

    def test_normalizes_only_after_full_archive_match(self):
        fragments = [f"fragment-{index}\n".encode() for index in range(8)]
        root = self.make_root(fragments)
        parts_dir = root / "docs/agent/parts"
        for _, canonical_name in PARTS:
            (parts_dir / canonical_name).write_text("stale duplicate", encoding="utf-8")

        normalize(root)

        final_parts = sorted(parts_dir.glob("*.md"))
        self.assertEqual([name for _, name in PARTS], [path.name for path in final_parts])
        self.assertEqual(b"".join(path.read_bytes() for path in final_parts), b"".join(fragments))

    def test_refuses_corrupt_source_without_mutating_files(self):
        fragments = [f"fragment-{index}\n".encode() for index in range(8)]
        root = self.make_root(fragments)
        parts_dir = root / "docs/agent/parts"
        escaped = parts_dir / PARTS[3][0]
        escaped.write_bytes(b"corrupted")
        canonical = parts_dir / PARTS[3][1]
        canonical.write_bytes(b"existing")

        with self.assertRaises(ValueError):
            normalize(root)

        self.assertTrue(escaped.is_file())
        self.assertEqual(canonical.read_bytes(), b"existing")


if __name__ == "__main__":
    unittest.main()
