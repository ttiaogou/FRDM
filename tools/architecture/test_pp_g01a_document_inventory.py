"""G01-A: read-only document reference inventory in the existing architecture CI.

This is evidence collection, not a document deletion or policy gate.
A mention is a candidate reference, not proof of a runtime dependency.
"""
import os
import posixpath
import re
import subprocess
import unittest
from collections import defaultdict
from pathlib import Path
from urllib.parse import unquote

ROOT = Path(__file__).resolve().parents[2]
LINK = re.compile(r"\]\(\s*(?:<([^>\n]+)>|([^\s)]+))")
DEFINITION = re.compile(r"^\s*\[[^\]]+\]:\s*(?:<([^>\n]+)>|([^\s]+))")
DOC_HINT = re.compile(r"""docs/|docs\\|["']docs["']|AGENTS\.md""")


def tracked_paths(root):
    raw = subprocess.check_output(["git", "ls-files", "-z"], cwd=root)
    return sorted({os.fsdecode(p) for p in raw.split(b"\0") if p})


def is_document(path):
    return path.endswith(".md") or path.startswith("docs/")


def local_target(source, raw, paths):
    if raw.startswith(("#", "//")) or re.match(r"^[a-zA-Z][a-zA-Z0-9+.-]*:", raw):
        return None
    target = unquote(raw.split("#", 1)[0].split("?", 1)[0])
    if not target:
        return None
    relative = posixpath.normpath(posixpath.join(posixpath.dirname(source), target))
    if relative in paths:
        return None
    if target.lstrip("/") in paths:
        return ("ROOT_STYLE", target)
    return ("MISSING_CANDIDATE", target)


def inventory(root):
    paths = tracked_paths(root)
    # Include directory targets as well as tracked files.
    link_paths = set(paths)
    for p in paths:
        link_paths.update(parent.as_posix() for parent in Path(p).parents)
    docs = [p for p in paths if is_document(p)]
    names = defaultdict(list)
    for p in docs:
        names[posixpath.basename(p)].append(p)
    pattern = re.compile(
        r"(?<![A-Za-z0-9_.-])(?:"
        + "|".join(re.escape(n) for n in sorted(names, key=lambda s: (-len(s), s)))
        + r")(?![A-Za-z0-9_.-])"
    ) if names else None
    incoming = defaultdict(lambda: defaultdict(list))
    hints = defaultdict(list)
    link_issues = []
    binaries = []
    text_count = 0
    for source in paths:
        data = (root / source).read_bytes()
        if data.startswith(b"\x89PNG\r\n\x1a\n") or b"\0" in data:
            binaries.append(source)
            continue
        try:
            content = data.decode("utf-8")
        except UnicodeDecodeError:
            binaries.append(source)
            continue
        text_count += 1
        for line_no, line in enumerate(content.splitlines(), 1):
            decoded = unquote(line) if "%" in line else line
            if pattern:
                for name in sorted({m.group() for m in pattern.finditer(decoded)}):
                    for target in names[name]:
                        if target != source:
                            incoming[target][source].append(line_no)
            if not is_document(source) and DOC_HINT.search(decoded):
                hints[source].append(line_no)
            if source.endswith(".md"):
                for match in list(LINK.finditer(line)) + list(DEFINITION.finditer(line)):
                    finding = local_target(source, match.group(1) or match.group(2), link_paths)
                    if finding:
                        link_issues.append((source, line_no, *finding))
    return paths, docs, text_count, binaries, incoming, hints, link_issues


def line_summary(lines):
    return ",".join(map(str, lines[:10])) + (f",+{len(lines)-10}" if len(lines) > 10 else "")


class DocumentReferenceInventoryTest(unittest.TestCase):
    def test_repository_document_reference_inventory(self):
        paths, docs, texts, binaries, incoming, hints, issues = inventory(ROOT)
        self.assertTrue(paths, "git ls-files returned no tracked paths")
        self.assertTrue(docs, "no document inventory targets")
        self.assertEqual(len(paths), texts + len(binaries))
        print(f"G01A_SCAN tracked={len(paths)} documents={len(docs)} text={texts} "
              f"binary_or_non_utf8={len(binaries)} link_candidates={len(issues)}")
        for path in binaries:
            print(f"G01A_SKIPPED_BINARY {path}")
        for target in docs:
            sources = incoming.get(target, {})
            print(f"G01A_DOC {target} incoming_files={len(sources)}")
            for source, lines in sorted(sources.items()):
                print(f"G01A_REF {target} <- {source}:{line_summary(lines)}")
        for source, lines in sorted(hints.items()):
            print(f"G01A_PATH_HINT {source}:{line_summary(lines)}")
        for source, line_no, status, target in issues:
            print(f"G01A_LINK {status} {source}:{line_no} -> {target}")
        # Candidate link issues and mentions are reported, never silently deleted
        # or promoted to architecture violations. Existing integrity gates remain
        # authoritative; external URLs, fragments and computed paths need review.

    def test_local_target_classification(self):
        paths = {"docs/a.md", "docs/sub/b.md", "docs/c.md"}
        self.assertIsNone(local_target("docs/sub/b.md", "../a.md#section", paths))
        self.assertIsNone(local_target("docs/sub/b.md", "https://example.org/a.md", paths))
        self.assertEqual(("ROOT_STYLE", "docs/c.md"),
                         local_target("docs/sub/b.md", "docs/c.md", paths))
        self.assertEqual(("MISSING_CANDIDATE", "../absent.md"),
                         local_target("docs/sub/b.md", "../absent.md", paths))
