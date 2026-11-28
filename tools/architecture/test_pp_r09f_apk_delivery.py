"""R09-F: verify official APK artifacts share the same runner and signing gate."""
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
WORKFLOW = ROOT / ".github/workflows/android-build.yml"

def violations(source):
    issues = []
    main = source.split("  build-freedom-apk:\n", 1)[-1].split("  build-ocr-plugin-apk:\n", 1)[0]
    independent = source.split("  build-ocr-plugin-apk:\n", 1)[-1].split("  codeql:\n", 1)[0]
    ordered = [
        "      - name: Build Freedom debug APK",
        'cp app/build/outputs/apk/debug/app-debug.apk "$RUNNER_TEMP/app-debug.apk"',
        ":app:assembleDebug",
        "      - name: Build matching OCR delivery APK on this runner",
        ":ocr-plugin:assembleDebug",
        "      - name: Verify all delivery APK signatures before upload",
        'ordinary="$(signer_digest "$RUNNER_TEMP/app-debug.apk")"',
        'slim="$(signer_digest "$RUNNER_TEMP/freedom-slim-debug.apk")"',
        'ocr="$(signer_digest "ocr-plugin/build/outputs/apk/debug/ocr-plugin-debug.apk")"',
        'if [[ "$ordinary" != "$slim" || "$ordinary" != "$ocr" ]]; then',
        "exit 1",
        "R09F_APK_SIGNING_PAIR_VERIFIED",
        "      - name: Upload Freedom debug APK",
        "      - name: Upload shrink-only Freedom debug APK",
        "      - name: Upload OCR plugin debug APK",
    ]
    cursor = 0
    for token in ordered:
        pos = main.find(token, cursor)
        if pos < 0:
            issues.append("missing or reordered paired delivery step: " + token)
        else:
            cursor = pos + len(token)
    for token in ('verify --verbose --print-certs "$apk")" || return 1',
                  'if [[ ! "$digest" =~ ^[0-9a-fA-F]{64}$ ]]; then',
                  'path: ${{ runner.temp }}/app-debug.apk',
                  'path: ${{ runner.temp }}/freedom-slim-debug.apk',
                  'path: ocr-plugin/build/outputs/apk/debug/ocr-plugin-debug.apk'):
        if token not in main:
            issues.append("missing signature or artifact guard: " + token)
    for name in ("Freedom-debug-apk", "Freedom-slim-debug-apk", "Freedom-OCR-plugin-debug-apk"):
        if len(re.findall(r"^\s+name: " + re.escape(name) + r"$", source, re.M)) != 1:
            issues.append("official delivery artifact must be unique: " + name)
    if ":ocr-plugin:testDebugUnitTest :ocr-plugin:assembleDebug" not in independent:
        issues.append("independent OCR test/build removed")
    if "upload-artifact" in independent:
        issues.append("independent runner must not publish unpaired delivery APK")
    return issues

class PpR09fApkDeliveryTest(unittest.TestCase):
    def test_same_runner_and_fail_closed_signing_gate(self):
        self.assertEqual([], violations(WORKFLOW.read_text(encoding="utf-8")))

    def test_rejects_removed_signer_check_or_duplicate_ocr_delivery(self):
        source = WORKFLOW.read_text(encoding="utf-8")
        for old, new in (
            ('"$ordinary" != "$ocr"', 'false'),
            ('verify --verbose --print-certs "$apk")" || return 1', 'verify "$apk")"'),
            (":ocr-plugin:testDebugUnitTest :ocr-plugin:assembleDebug", ":ocr-plugin:assembleDebug"),
            ("  codeql:\n", "      - name: Upload duplicate\n        uses: actions/upload-artifact@v4\n        with:\n          name: Freedom-OCR-plugin-debug-apk\n\n  codeql:\n"),
        ):
            self.assertIn(old, source)
            with self.subTest(old=old):
                self.assertTrue(violations(source.replace(old, new, 1)))
