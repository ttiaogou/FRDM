"""M04-A: enforce the device-verified click baseline in the existing CI test discovery.

Only action implementations and their ordered dispatch call sites are pinned.
Business orchestration, result evidence and recovery may evolve outside the pinned regions.
"""
import hashlib
import json
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
MANIFEST = Path(__file__).with_name("m04_media_click_freeze.json")


def fingerprint(text):
    return hashlib.sha1(text.encode("utf-8")).hexdigest()


def freeze_violations(manifest, sources):
    issues = []
    for path, digest in manifest["files"].items():
        source = sources.get(path)
        if source is None or fingerprint(source.rstrip("\n")) != digest:
            issues.append(f"Frozen media inspector changed or missing: {path}")
    for region in manifest["regions"]:
        path = region["path"]
        lines = sources.get(path, "").splitlines()
        hits = [i for i, line in enumerate(lines) if line == region["anchor"]]
        if len(hits) != 1 or fingerprint(
            "\n".join(lines[hits[0]:hits[0] + region["lines"]])
        ) != region["sha1"]:
            issues.append(f"Frozen media action changed, missing or duplicated: {path}: {region['anchor'].strip()}")

    adapter = sources.get(manifest["adapter"], "")
    # A later authorized refactor may move the orchestration and update this order check,
    # but must preserve the action implementations, selectors and relative execution order.
    start = adapter.find("    override fun observe() {")
    end = adapter.find("\n    override fun sendDraft(", start)
    observe = adapter[start:end] if 0 <= start < end else ""
    positions = []
    for name in manifest["image_order"]:
        matches = list(re.finditer(r"\b" + re.escape(name) + r"\s*\(", observe))
        if len(matches) != 1:
            issues.append(f"Frozen image action call missing or duplicated: {name}")
        else:
            positions.append(matches[0].start())
    if positions != sorted(positions):
        issues.append("Frozen image action order changed")
    positions = []
    for resolver in manifest["interception_resolvers"]:
        if observe.count(resolver) != 1:
            issues.append(f"Frozen interception action resolver changed, missing or duplicated: {resolver.splitlines()[0]}")
        else:
            positions.append(observe.index(resolver))
    if positions != sorted(positions):
        issues.append("Frozen interception action order changed")
    return issues


class M04MediaClickFreezeTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.manifest = json.loads(MANIFEST.read_text(encoding="utf-8"))
        # Missing files are violations, not silently skipped.
        paths = set(cls.manifest["files"]) | {r["path"] for r in cls.manifest["regions"]} | {cls.manifest["adapter"]}
        cls.sources = {
            path: (ROOT / path).read_text(encoding="utf-8")
            for path in paths if (ROOT / path).is_file()
        }

    def test_device_verified_click_baseline(self):
        self.assertEqual("2c624b372532a379036192cb939e65c514f03b85", self.manifest["baseline"])
        self.assertEqual([], freeze_violations(self.manifest, self.sources))

    def test_changed_missing_or_duplicate_action_is_rejected(self):
        region = self.manifest["regions"][0]
        path = region["path"]
        original = self.sources[path]
        anchor = region["anchor"]
        for changed in (
            original.replace(anchor, anchor + " // changed", 1),
            original.replace(anchor, "", 1),
            original + "\n" + anchor,
        ):
            with self.subTest(changed=changed[-80:]):
                sources = dict(self.sources)
                sources[path] = changed
                self.assertTrue(freeze_violations(self.manifest, sources))

    def test_changed_selector_and_reordered_steps_are_rejected(self):
        path = next(iter(self.manifest["files"]))
        sources = dict(self.sources)
        sources[path] += "\n// mutation"
        self.assertTrue(freeze_violations(self.manifest, sources))
        sources = dict(self.sources)
        adapter = self.manifest["adapter"]
        a, b = self.manifest["interception_resolvers"][:2]
        src = sources[adapter]
        sources[adapter] = src.replace(a, "__M04_SWAP__", 1).replace(b, a, 1).replace("__M04_SWAP__", b, 1)
        self.assertIn("Frozen interception action order changed", freeze_violations(self.manifest, sources))

    def test_unrelated_adapter_code_is_not_frozen(self):
        sources = dict(self.sources)
        sources[self.manifest["adapter"]] += "\n// unrelated orchestration documentation\n"
        self.assertEqual([], freeze_violations(self.manifest, sources))

    def test_retired_manual_media_entry_is_absent(self):
        paths = [
            "app/src/main/java/com/freedom/ui/config/ConfigurationHome.kt",
            "app/src/main/java/com/freedom/application/TaskLaunchParameters.kt",
            "app/src/main/java/com/freedom/application/ports/TaskLaunchParametersRepository.kt",
            "app/src/main/java/com/freedom/data/TaskLaunchParametersStore.kt",
            "app/src/main/java/com/freedom/app/AppCompositionRoot.kt",
            "business/src/main/kotlin/com/freedom/features/aireply/AiReplyObservationRuntime.kt",
            self.manifest["adapter"],
        ]
        for path in paths:
            source = (ROOT / path).read_text(encoding="utf-8")
            for marker in ("imageSendTestRequestId", "isImageSendTestRequestAvailable",
                           "claimImageSendTestRequest", "isImageSendTestRun",
                           "buildImageSendTestRow", "ChatImageSendTestPort",
                           "SEND_TAP_COMPLETED_DELIVERY_NOT_VERIFIED",
                           "SWITCH_TAPPED_ONCE_AND_CHAT_RETURNED_STATE_NOT_READ"):
                with self.subTest(path=path, marker=marker):
                    self.assertNotIn(marker, source)
        self.assertFalse((ROOT / "business/src/main/kotlin/com/freedom/features/aireply/ports/ChatImageSendTestPort.kt").exists())
