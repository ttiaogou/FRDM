"""R09-B lexical inventory, advisory only; candidates are not removal authorization."""
import collections
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
MODULES = ("app", "business", "core", "gesture/gesture-api", "gesture/gesture-core",
           "gesture/gesture-runtime", "gesture/gesture-android", "ocr-plugin-api", "ocr-plugin")
IDENT = re.compile(r"[A-Za-z_][A-Za-z0-9_]*")
TOKEN = re.compile(r'//[^\n]*|/\*|"""[\s\S]*?"""|"(?:\\.|[^"\\])*"|'
                   r"'(?:\\.|[^'\\])*'|[A-Za-z_][A-Za-z0-9_]*|[^\s]")
MODIFIERS = {"public", "private", "protected", "internal", "override", "open", "final",
             "abstract", "suspend", "inline", "tailrec", "operator", "infix", "external"}

def tokenize(source):
    """Preserve strings and punctuation; skip whitespace, line and nested block comments."""
    tokens = []
    pos = 0
    line = 1
    while True:
        match = TOKEN.search(source, pos)
        if match is None:
            return tokens
        line += source.count("\n", pos, match.start())
        value = match.group()
        end = match.end()
        if value == "/*":
            depth = 1
            while depth and end < len(source):
                opening = source.find("/*", end)
                closing = source.find("*/", end)
                if closing < 0:
                    end = len(source)
                    break
                if 0 <= opening < closing:
                    end = opening + 2
                    depth += 1
                else:
                    end = closing + 2
                    depth -= 1
        elif not value.startswith("//"):
            tokens.append((value, line))
        line += source.count("\n", match.start(), end)
        pos = end

def matching(tokens, start, opening, closing):
    depth = 0
    for i in range(start, len(tokens)):
        if tokens[i][0] == opening:
            depth += 1
        elif tokens[i][0] == closing:
            depth -= 1
            if depth == 0:
                return i
    return None

def simple_declarations(path, source):
    """Named Kotlin functions with simple direct signatures, and named interface types."""
    tokens = tokenize(source)
    methods, interfaces = [], []
    for i, (value, line) in enumerate(tokens):
        if i + 1 >= len(tokens) or not IDENT.fullmatch(tokens[i + 1][0]):
            continue
        name = tokens[i + 1][0]
        location = f"{path}:{line}:{name}"
        if value == "interface":
            interfaces.append((name, location))
        if value != "fun" or i + 2 >= len(tokens) or tokens[i + 2][0] != "(":
            continue
        cursor = i - 1
        modifiers = set()
        while cursor >= 0 and tokens[cursor][0] in MODIFIERS:
            modifiers.add(tokens[cursor][0])
            cursor -= 1
        end = matching(tokens, i + 2, "(", ")")
        body = ()
        if end is not None and end + 1 < len(tokens):
            cursor = end + 1
            if tokens[cursor][0] == ":":
                cursor += 1
                # Only a simple single-line return type; skip complex signatures rather than
                # accidentally absorbing a later declaration's body.
                while cursor < len(tokens) and tokens[cursor][1] == tokens[end][1]:
                    part = tokens[cursor][0]
                    if part in {"{", "="} or part in {"fun", "val", "var", "class", "interface", "where"}:
                        break
                    if not (IDENT.fullmatch(part) or part in {".", "<", ">", "?", ",", "*"}):
                        break
                    cursor += 1
            if cursor < len(tokens) and tokens[cursor][0] == "{":
                stop = matching(tokens, cursor, "{", "}")
                if stop is not None:
                    body = tuple(token for token, _ in tokens[cursor + 1:stop])
        methods.append((name, location, "private" in modifiers, body))
    return methods, interfaces

def inventory(production, reference_texts):
    # Count even comments/string literals, tests and configuration conservatively.
    refs = collections.Counter()
    for source in reference_texts:
        refs.update(IDENT.findall(source))
    duplicates = collections.defaultdict(list)
    private_candidates, interface_candidates = [], []
    method_count = interface_count = 0
    for path, source in production.items():
        methods, interfaces = simple_declarations(path, source)
        method_count += len(methods)
        interface_count += len(interfaces)
        for name, location, private, body in methods:
            if private and refs[name] == 1:
                private_candidates.append(location)
            # Ignore trivial getters, stubs and empty blocks.
            if len(body) >= 12:
                duplicates[body].append(location)
        for name, location in interfaces:
            if refs[name] == 1:
                interface_candidates.append(location)
    return (method_count, interface_count, sorted(private_candidates), sorted(interface_candidates),
            sorted(sorted(group) for group in duplicates.values() if len(group) > 1))

class PpR09bLegacyInventoryTest(unittest.TestCase):
    def test_inventory_distinguishes_bodies_literals_and_references(self):
        source = """
private fun unused() { val x = 1; val y = 2; return x + y }
private fun a() { val x = 1; val y = 2; return x + y }
private fun b() { val x = 1; val y = 2; return x + y }
private fun c() { val x = 3; val y = 2; return x + y }
interface Lonely
interface Used
"""
        result = inventory({"x.kt": source}, [source, 'a(); b(); c(); val x: Used; /* "a" */'])
        self.assertEqual(4, result[0])
        self.assertEqual(2, result[1])
        self.assertEqual(["x.kt:2:unused"], result[2])
        self.assertEqual(["x.kt:6:Lonely"], result[3])
        self.assertEqual([["x.kt:2:unused", "x.kt:3:a", "x.kt:4:b"]], result[4])

    def test_whole_module_inventory_advisory(self):
        production, references = {}, []
        for module in MODULES:
            directory = ROOT / module
            self.assertTrue((directory / "src").is_dir(), module)
            candidates = list((directory / "src").rglob("*"))
            candidates += list(directory.glob("*.pro")) + list(directory.glob("*.gradle.kts"))
            for path in candidates:
                if not path.is_file() or path.suffix not in {".kt", ".java", ".xml", ".aidl", ".pro", ".kts", ".json", ".properties"}:
                    continue
                content = path.read_text(encoding="utf-8")
                references.append(content)
                if path.suffix == ".kt" and path.is_relative_to(directory / "src/main"):
                    production[path.relative_to(ROOT).as_posix()] = content
        self.assertTrue(any(path.endswith("/AiReplyObservationRuntime.kt") for path in production))
        self.assertTrue(any(path.endswith("/ChatPagePort.kt") for path in production))
        method_count, interface_count, private, interfaces, groups = inventory(production, references)
        print(f"R09B_INVENTORY files={len(production)} simple_functions={method_count} "
              f"interface_types={interface_count} single_reference_private={len(private)} "
              f"single_reference_interfaces={len(interfaces)} exact_nontrivial_body_groups={len(groups)}")
        for candidate in private:
            print("R09B_PRIVATE_CANDIDATE", candidate)
        for candidate in interfaces:
            print("R09B_INTERFACE_CANDIDATE", candidate)
        for group in groups:
            print("R09B_DUPLICATE_BODY_CANDIDATE", " | ".join(group))
        # No assertion that candidates are dead; no automatic deletion, no business policy changes.
        self.assertGreater(method_count, 0)
        self.assertGreater(interface_count, 0)
