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
        # R03 authorized removal of exactly two unused private UI helpers.
        # Preserve the historical manifest and require the exact reviewed replacement,
        # not a path exemption or a regenerated baseline.
        reviewed_s1 = dict(m["pre_migration_s1"])
        activity = "app/src/main/java/com/freedom/MainActivity.kt"
        self.assertEqual("0ce061194a011399a493a189fbda49a841eb7771", reviewed_s1[activity])
        reviewed_s1[activity] = "7afbe0c4eb9f8f67f96f9833665093424948d9c2"
        # R04: exact reviewed relocation of stateless Momo functions, no blanket exemption.
        for path, (historical, replacement) in {
            "app/src/main/java/com/freedom/adapters/social/momo/MomoChatListSessionPolicy.kt": (
                "52f6de77fb025c65c1f93666cf4bfa940e85f93d", "491b4e888cc045b2c170b48b16b11924682a1663"),
            "app/src/main/java/com/freedom/adapters/social/momo/MomoWindowPageFingerprint.kt": (
                "842648b653524414c0559a3919c8c97e91d45e6c", "b812edf273cf0c45af16e80ae1a3e41bdb8085e8"),
            "app/src/main/java/com/freedom/adapters/social/momo/MomoSayHiIdentityFingerprintPolicy.kt": (
                "7d5fc671a44945b0b3e3dce6ff2d300e18d1046a", "4f709f87c43bfe9ec1e2d586e2413d44f90754c1"),
            "app/src/main/java/com/freedom/adapters/social/momo/MomoChatPageModels.kt": (
                "2fa1871c901fbe8cf30b1d47589b8ea9189d62e1", "3fb5dd10b74ba2d161759ebd33a20d5e24220a82"),
            "app/src/main/java/com/freedom/adapters/social/momo/MomoSocialAdapter.kt": (
                "69811799d3ed1048c3fc2105c4a2e09af7f50f17", "0d35707b612ac3926a75c1933650d3083a898571"),
        }.items():
            self.assertEqual(historical, reviewed_s1[path])
            reviewed_s1[path] = replacement
        # R04 batch 2: exact relocation of read-only node acquisition and counting.
        for path, (previous, replacement) in {
            "app/src/main/java/com/freedom/adapters/social/momo/MomoSocialAdapter.kt": (
                "0d35707b612ac3926a75c1933650d3083a898571", "2d6f690d5b3504fa13bb177a14737180c3bd8a73"),
            "app/src/main/java/com/freedom/adapters/social/momo/MomoChatListSessionExtractor.kt": (
                "200836fd2c0353efa39e4ecca29a05f880046922", "581a0a323522ed6e12b71fe2e1d85fb197adb18e"),
            "app/src/main/java/com/freedom/adapters/social/momo/MomoPageInspector.kt": (
                "9fbda0573ae4e8a10e36498aad605ea60c266bb4", "dfc2877d7bc269871759a5a62e04dfeb3d21b7dc"),
        }.items():
            self.assertEqual(previous, reviewed_s1[path])
            reviewed_s1[path] = replacement
        # R04 batch 3: exact greeting evidence and message read helper relocations.
        for path, (previous, replacement) in {
            "app/src/main/java/com/freedom/adapters/social/momo/MomoSocialAdapter.kt": (
                "2d6f690d5b3504fa13bb177a14737180c3bd8a73", "ed23fe9f78461734f1a484ac72287ad2d6a18ad8"),
            "app/src/main/java/com/freedom/adapters/social/momo/MomoSayHiAccessibilityInspector.kt": (
                "5bf48e5b2befd09562ccce5e98ccbd10fb4d5888", "c3560c93fc19bb5acec659a040a216ef491d3503"),
            "app/src/main/java/com/freedom/adapters/social/momo/MomoSayHiNavigationEvidenceInspector.kt": (
                "cc24b37e75f2e1dcaa584fff5af034cd68482e58", "ee6ab55f92bb78fccad1b29a7e3e439151c4963c"),
            "app/src/main/java/com/freedom/adapters/social/momo/MomoSayHiIdentityFingerprintPolicy.kt": (
                "4f709f87c43bfe9ec1e2d586e2413d44f90754c1", "4f9094a13631680ac5a37c631c0d28e1b37486af"),
            "app/src/main/java/com/freedom/adapters/social/momo/MomoChatPageModels.kt": (
                "3fb5dd10b74ba2d161759ebd33a20d5e24220a82", "830a1a38d32b76c75d07977890501c8bed662eb2"),
            "app/src/main/java/com/freedom/adapters/social/momo/MomoChatMessageReader.kt": (
                "5d951b290b6c8eed9d425765ce9fabd9ae786f55", "1cc8714d36bcf379ee108dccb108aa988dc33fa2"),
        }.items():
            self.assertEqual(previous, reviewed_s1[path])
            reviewed_s1[path] = replacement
        # R04 batch 4: exact consolidation of existing stateless helper implementations.
        for path, (previous, replacement) in {
            "app/src/main/java/com/freedom/adapters/social/momo/MomoSocialAdapter.kt": (
                "ed23fe9f78461734f1a484ac72287ad2d6a18ad8", "8bab51d20b1d24626041635fda0a767d33eb7ff8"),
            "app/src/main/java/com/freedom/adapters/social/momo/MomoSayHiAvatarTargetPolicy.kt": (
                "71aa0ca8a1bf27b9e3baa7a4d2e2c8784943b5f1", "2571b4c6719caab7fa956a8a32fcd3614cab0b1b"),
            "app/src/main/java/com/freedom/adapters/social/momo/MomoSayHiNavigationEvidenceInspector.kt": (
                "ee6ab55f92bb78fccad1b29a7e3e439151c4963c", "6f917cf6b61ce1b91dbda2237101972398180fbe"),
        }.items():
            self.assertEqual(previous, reviewed_s1[path])
            reviewed_s1[path] = replacement
        # R05-A: exact reviewed relocation of the existing inbox identity state.
        for path, (previous, replacement) in {
            "app/src/main/java/com/freedom/adapters/social/momo/MomoSocialAdapter.kt": (
                "8bab51d20b1d24626041635fda0a767d33eb7ff8", "0c9bf36aad8f4cba2d996be835ae14d731ecd17d"),
            "app/src/main/java/com/freedom/adapters/social/momo/MomoSocialAdapterComponents.kt": (
                "7c934cd16f354b46060ca6eb6fe77c7b4b211814", "2df5450adbc3d941d6df48a5b8a4bddba8700129"),
        }.items():
            self.assertEqual(previous, reviewed_s1[path])
            reviewed_s1[path] = replacement
        # R05-B: exact relocation into existing greeting navigation and traversal owners.
        for path, (previous, replacement) in {
            "app/src/main/java/com/freedom/adapters/social/momo/MomoSocialAdapter.kt": (
                "0c9bf36aad8f4cba2d996be835ae14d731ecd17d", "a1cb58a1eaecb734a48a58656e4bdd454b52466d"),
            "app/src/main/java/com/freedom/adapters/social/momo/MomoSayHiNavigationFlowController.kt": (
                "a2b8bb566c4e3a819e0ce332c11c130055a0366e", "a879a36bc84e4a7533a2ada6987c88494d023d21"),
            "app/src/main/java/com/freedom/adapters/social/momo/MomoReceivedGreetingsEntrySearchController.kt": (
                "7d344a81f7fd324d09dd41c6520515ab42d4d608", "45683b9237ef35a3ab7c767fb646f814f2ee3629"),
            "app/src/main/java/com/freedom/adapters/social/momo/MomoSayHiTraversalState.kt": (
                "8d5daa83fe133cc685494460d0c786b0d9dd9590", "b0120b7e9a07170fe5063a529a04867334bc92a9"),
        }.items():
            self.assertEqual(previous, reviewed_s1[path])
            reviewed_s1[path] = replacement
        # R05-C: exact relocation of existing avatar and identity transaction state operations.
        for path, (previous, replacement) in {
            "app/src/main/java/com/freedom/adapters/social/momo/MomoSocialAdapter.kt": (
                "a1cb58a1eaecb734a48a58656e4bdd454b52466d", "a7e2b9cb16f4764839f55d24f69e8bb7fb462f4d"),
            "app/src/main/java/com/freedom/adapters/social/momo/MomoSocialAdapterComponents.kt": (
                "2df5450adbc3d941d6df48a5b8a4bddba8700129", "808407dd637949c040408ba5101e0849d7075cf2"),
        }.items():
            self.assertEqual(previous, reviewed_s1[path])
            reviewed_s1[path] = replacement
        # R05-D: exact reviewed relocation; keep the historical manifest and every S0/S1 check.
        path = "app/src/main/java/com/freedom/adapters/social/momo/MomoSocialAdapter.kt"
        self.assertEqual("a7e2b9cb16f4764839f55d24f69e8bb7fb462f4d", reviewed_s1[path])
        reviewed_s1[path] = "3d7e95615422c89d98683fe0f53718a4d40c1848"
        path = "app/src/main/java/com/freedom/adapters/social/momo/MomoDraftTransactionController.kt"
        self.assertNotIn(path, reviewed_s1)
        self.assertNotIn(path, m["permanent_s0"])
        reviewed_s1[path] = "e56c3fc9787320aaade7b5b9ce7f731aef2794bb"
        # R05-E: pin image-only relocation without re-recording the historical baseline.
        path = "app/src/main/java/com/freedom/adapters/social/momo/MomoSocialAdapter.kt"
        self.assertEqual("3d7e95615422c89d98683fe0f53718a4d40c1848", reviewed_s1[path])
        reviewed_s1[path] = "d31b652c8d12b9507d7df6a5fc941a9faf919062"
        path = "app/src/main/java/com/freedom/adapters/social/momo/MomoImageSendTransactionController.kt"
        self.assertNotIn(path, reviewed_s1)
        self.assertNotIn(path, m["permanent_s0"])
        reviewed_s1[path] = "2517ee16b10ebbb08f31ab3e2b47ab07365c976f"
        # R05-F: exact interception relocation; preserve the historical source inventory.
        for path, (previous, replacement) in {
            "app/src/main/java/com/freedom/adapters/social/momo/MomoSocialAdapter.kt": (
                "d31b652c8d12b9507d7df6a5fc941a9faf919062", "353ffb311dece0e79f6309862fcc8f71df19f076"),
            "app/src/main/java/com/freedom/adapters/social/momo/MomoImageSendTransactionController.kt": (
                "2517ee16b10ebbb08f31ab3e2b47ab07365c976f", "f6583cfa341c69a1f2e56f3ea827215d16065b23"),
        }.items():
            self.assertEqual(previous, reviewed_s1[path])
            reviewed_s1[path] = replacement
        path = "app/src/main/java/com/freedom/adapters/social/momo/MomoInterceptionTransactionController.kt"
        self.assertNotIn(path, reviewed_s1)
        self.assertNotIn(path, m["permanent_s0"])
        reviewed_s1[path] = "cdf761e0f4a221fc289d0621bbd309900e84cc9e"
        # R05-G: consolidate platform child ownership/recovery without changing the original baseline.
        for path, (previous, replacement) in {
            "app/src/main/java/com/freedom/adapters/social/momo/MomoSocialAdapter.kt": (
                "353ffb311dece0e79f6309862fcc8f71df19f076", "d68d98d536a7b54e955c2b150f2fbe79fdbd50e0"),
            "app/src/main/java/com/freedom/adapters/social/momo/MomoImageSendTransactionController.kt": (
                "f6583cfa341c69a1f2e56f3ea827215d16065b23", "4f641191d25d4e1e47b2b9203f38e6a33caaea3c"),
        }.items():
            self.assertEqual(previous, reviewed_s1[path])
            reviewed_s1[path] = replacement
        path = "app/src/main/java/com/freedom/adapters/social/momo/MomoPrivateFollowUpController.kt"
        self.assertNotIn(path, reviewed_s1)
        self.assertNotIn(path, m["permanent_s0"])
        reviewed_s1[path] = "14c07c5bcfc97bbefd617ad68dc1d04a1d7f391d"
        # R05-H: remove only the reviewed redundant wrappers; retain the historical baseline.
        path = "app/src/main/java/com/freedom/adapters/social/momo/MomoSocialAdapter.kt"
        self.assertEqual("d68d98d536a7b54e955c2b150f2fbe79fdbd50e0", reviewed_s1[path])
        reviewed_s1[path] = "e92cb4a3348146fa055fd3aa2b6abda2dac80824"
        # R06-B: exact reviewed page evidence extraction; original inventory remains unchanged.
        path = "business/src/main/kotlin/com/freedom/features/aireply/AiReplyObservationRuntime.kt"
        self.assertEqual("1c0c3bc1669f0cdc56e3abbb36c2f8c0be064f8a", reviewed_s1[path])
        reviewed_s1[path] = "a20bf91b008cecd8146a0a7135f4ad46e5090757"
        path = "business/src/main/kotlin/com/freedom/features/aireply/AiReplyPageEvidence.kt"
        self.assertNotIn(path, reviewed_s1)
        self.assertNotIn(path, m["permanent_s0"])
        reviewed_s1[path] = "687c609b7df393c86eab41a6916ef60764b4a74e"
        # R06-C: preserve exact reviewed private candidate evidence relocation.
        path = "business/src/main/kotlin/com/freedom/features/aireply/AiReplyObservationRuntime.kt"
        self.assertEqual("a20bf91b008cecd8146a0a7135f4ad46e5090757", reviewed_s1[path])
        reviewed_s1[path] = "9e231125225143534c708288b104876900e7d51a"
        path = "business/src/main/kotlin/com/freedom/features/aireply/ReceivedGreetingsChatEvidence.kt"
        self.assertNotIn(path, reviewed_s1)
        self.assertNotIn(path, m["permanent_s0"])
        reviewed_s1[path] = "6c8c124a55dac2dd99000bc67570f508138bbf96"
        path = "business/src/main/kotlin/com/freedom/features/aireply/PrivateMessageCandidateEvidence.kt"
        self.assertNotIn(path, reviewed_s1)
        self.assertNotIn(path, m["permanent_s0"])
        reviewed_s1[path] = "d02c68e6bd40a3ab88de3e518e126dc9af598d1f"
        # R06-E: reviewed stateless message/OCR evidence extraction.
        path = "business/src/main/kotlin/com/freedom/features/aireply/AiReplyObservationRuntime.kt"
        self.assertEqual("9e231125225143534c708288b104876900e7d51a", reviewed_s1[path])
        reviewed_s1[path] = "64727753394d6c5c3cf1e497a36ade3ffa7cc578"
        path = "business/src/main/kotlin/com/freedom/features/aireply/ChatMessageReadEvidence.kt"
        self.assertNotIn(path, reviewed_s1)
        self.assertNotIn(path, m["permanent_s0"])
        reviewed_s1[path] = "69586bffabff2436c67f844b81b66fede6477f6a"
        # R06-F: exact reviewed diagnostic formatting extraction; retain historical pins.
        path = "business/src/main/kotlin/com/freedom/features/aireply/AiReplyObservationRuntime.kt"
        self.assertEqual("64727753394d6c5c3cf1e497a36ade3ffa7cc578", reviewed_s1[path])
        reviewed_s1[path] = "ee67d98af55bdcba4270128132db9b5317f6c942"
        path = "business/src/main/kotlin/com/freedom/features/aireply/AiReplyPageDiagnostics.kt"
        self.assertNotIn(path, reviewed_s1)
        self.assertNotIn(path, m["permanent_s0"])
        reviewed_s1[path] = "a7ae4eac8cc824af0984955e520e54f20f3ab10e"
        # R07-B exact state relocation; preserve original S1 inventory and historical pins.
        path = "business/src/main/kotlin/com/freedom/features/aireply/AiReplyObservationRuntime.kt"
        self.assertEqual("ee67d98af55bdcba4270128132db9b5317f6c942", reviewed_s1[path])
        reviewed_s1[path] = "046026a655768a091e5ce70e1b8cdc37f9291539"
        path = "business/src/main/kotlin/com/freedom/features/aireply/MomoPageObservationCoordinator.kt"
        self.assertEqual("564440b87ad96fd78d5e8a46cc5545e0fa8188be", reviewed_s1[path])
        reviewed_s1[path] = "26ee9be133eb554f0b08bd000dd055f0164a95b8"
        # R07-C exact private visit state relocation; preserve previous pins.
        path = "business/src/main/kotlin/com/freedom/features/aireply/AiReplyObservationRuntime.kt"
        self.assertEqual("046026a655768a091e5ce70e1b8cdc37f9291539", reviewed_s1[path])
        reviewed_s1[path] = "770092f451d8516b8aaac251135cf9ec7a65b1a6"
        path = "business/src/main/kotlin/com/freedom/features/aireply/PrivateMessagesTraversalTaskExecutor.kt"
        self.assertEqual("22a7a66feb6a52d53b84681dab7b6c70cd822e9c", reviewed_s1[path])
        reviewed_s1[path] = "a38408df5c2779a0efc632f238bfb8c18e44f8f2"
        # R07-C compile-only repair: one call and one internal visibility adjustment.
        path = "business/src/main/kotlin/com/freedom/features/aireply/AiReplyObservationRuntime.kt"
        self.assertEqual("770092f451d8516b8aaac251135cf9ec7a65b1a6", reviewed_s1[path])
        reviewed_s1[path] = "ec018569ae4dc4f7a446380cb1e2f651686b25b0"
        path = "business/src/main/kotlin/com/freedom/features/aireply/PrivateMessagesTraversalTaskExecutor.kt"
        self.assertEqual("a38408df5c2779a0efc632f238bfb8c18e44f8f2", reviewed_s1[path])
        reviewed_s1[path] = "04f11c0f5b1ac69891478a053d6b05f60bb38d6b"
        # R07-D exact greeting visit and send receipt relocation.
        path = "business/src/main/kotlin/com/freedom/features/aireply/AiReplyObservationRuntime.kt"
        self.assertEqual("ec018569ae4dc4f7a446380cb1e2f651686b25b0", reviewed_s1[path])
        reviewed_s1[path] = "f605c1eb88f1eb97929db49a87459e2bca9530f5"
        path = "business/src/main/kotlin/com/freedom/features/aireply/ReceivedGreetingsTaskFlowController.kt"
        self.assertEqual("8cad84feb4c91d096e28e105fe6c730fe92ab228", reviewed_s1[path])
        reviewed_s1[path] = "7557397096bf6063b447f39a62a17659bd81c168"
        # R07-E exact shared read and visit retry lifecycle relocation.
        path = "business/src/main/kotlin/com/freedom/features/aireply/AiReplyObservationRuntime.kt"
        self.assertEqual("f605c1eb88f1eb97929db49a87459e2bca9530f5", reviewed_s1[path])
        reviewed_s1[path] = "26846bb806159f33ddffad506fd9ecfc9de2a2cc"
        path = "business/src/main/kotlin/com/freedom/features/aireply/ChatMessageReadCoordinator.kt"
        self.assertEqual("ac219e52e7439bde82f7c38641736c001bf6e1ba", reviewed_s1[path])
        reviewed_s1[path] = "8d00203bdb93c2e7cfb046ab8ebabf9002373058"
        # R07-E compile-only explicit Kotlin type annotations.
        path = "business/src/main/kotlin/com/freedom/features/aireply/AiReplyObservationRuntime.kt"
        self.assertEqual("26846bb806159f33ddffad506fd9ecfc9de2a2cc", reviewed_s1[path])
        reviewed_s1[path] = "ef2c4f6218f4ccfde40a1ebcef26ee5860562b06"
        # R07-F exact private completion/child and send-proof observation state relocation.
        path = "business/src/main/kotlin/com/freedom/features/aireply/AiReplyObservationRuntime.kt"
        self.assertEqual("ef2c4f6218f4ccfde40a1ebcef26ee5860562b06", reviewed_s1[path])
        reviewed_s1[path] = "87fc911f604f2f8fbfdd7516b161ff715fb6f2d3"
        path = "business/src/main/kotlin/com/freedom/features/aireply/PrivateMessagesTraversalTaskExecutor.kt"
        self.assertEqual("04f11c0f5b1ac69891478a053d6b05f60bb38d6b", reviewed_s1[path])
        reviewed_s1[path] = "1d12a483673719255862b5c512c8db9fd9219fad"
        path = "business/src/main/kotlin/com/freedom/features/aireply/ChatMessageReadCoordinator.kt"
        self.assertEqual("8d00203bdb93c2e7cfb046ab8ebabf9002373058", reviewed_s1[path])
        reviewed_s1[path] = "0f8ba84a23f83d0f62c7a757d82183c5a8b88fa7"
        # R07-G: exact paired state relocation into existing AI task ownership.
        path = "business/src/main/kotlin/com/freedom/features/aireply/AiReplyObservationRuntime.kt"
        self.assertEqual("87fc911f604f2f8fbfdd7516b161ff715fb6f2d3", reviewed_s1[path])
        reviewed_s1[path] = "d6871d72fabe6af2af018fdeb709692851260335"
        path = "business/src/main/kotlin/com/freedom/features/aireply/AiReplyTaskOwnership.kt"
        self.assertEqual("3f303752ae0a61e308fbf8182a9811ad932656fe", reviewed_s1[path])
        reviewed_s1[path] = "85ebbdd6eab50c3085bbc9b2388581c22a531302"
        # R07-H: exact recovery observation state relocation; S0 unchanged.
        path = "business/src/main/kotlin/com/freedom/features/aireply/AiReplyObservationRuntime.kt"
        self.assertEqual("d6871d72fabe6af2af018fdeb709692851260335", reviewed_s1[path])
        reviewed_s1[path] = "7f5d99bcfccf80e2404880fcdfd5341c73a19014"
        path = "business/src/main/kotlin/com/freedom/features/aireply/AiReplySessionTraversalCoordinator.kt"
        self.assertEqual("3a44370c00672af7332e0dd660141241bcf4a8d4", reviewed_s1[path])
        reviewed_s1[path] = "b40194531fa0231cf70a176027b6259cb8e4e2ce"
        path = "business/src/main/kotlin/com/freedom/features/aireply/PageRecoveryVerificationGate.kt"
        self.assertEqual("41136af09eb89e74cc7a8ba00add1676b8f27f60", reviewed_s1[path])
        reviewed_s1[path] = "7404738452eefe21aab1bdb34f3ae60be7d035b5"
        path = "business/src/main/kotlin/com/freedom/features/aireply/MessageListCheckpointVerification.kt"
        self.assertEqual("3a58c7dc9690004f184f5c6a7ba0b61cc72a90e5", reviewed_s1[path])
        reviewed_s1[path] = "fb0f8d7449f6e102c5d9ddc48b1d4a0d5f99a456"
        path = "business/src/main/kotlin/com/freedom/features/aireply/ReceivedGreetingsCheckpointVerification.kt"
        self.assertEqual("8834d6054710aa10c7dafbbb5b6fe756737b7def", reviewed_s1[path])
        reviewed_s1[path] = "e313a29018b7a10d25b21e78fb196c24a538fe19"
        # R08-A: exact relocation of diagnostic-only draft state; S0 unchanged.
        path = "business/src/main/kotlin/com/freedom/features/aireply/ai/AiReplyDraftCoordinator.kt"
        self.assertEqual("21d90c5e10b1f4edd858c0b9fc48a932e8dfe928", reviewed_s1[path])
        reviewed_s1[path] = "db6fc22c3d0e17178191a7e945c93e36c7a90ebe"
        path = "business/src/main/kotlin/com/freedom/features/aireply/ai/AiModelCallDiagnostics.kt"
        self.assertEqual("c5f87169bd3e1550109eacb0474aa98d6e026ff2", reviewed_s1[path])
        reviewed_s1[path] = "08280b83bb964694feb8b40244b318d836bc8244"
        # R08-B: exact relocation into the existing AccountBrowse Runtime; S0 unchanged.
        path = "app/src/main/java/com/freedom/adapters/accessibility/android/AndroidAccountBrowseWorkflow.kt"
        self.assertEqual("2c35e3d96bbb595e0bf08d3c1c1257f9ef7b0da6", reviewed_s1[path])
        reviewed_s1[path] = "329e0f9abe9827248d55ef24dd2541079ca5405a"
        path = "business/src/main/kotlin/com/freedom/features/accountbrowse/AccountBrowseRuntime.kt"
        self.assertEqual("d0fa5ac24dcd1d06981853c058b3b7bd3500d8de", reviewed_s1[path])
        reviewed_s1[path] = "ce9c2c2e4efd21b72e4c450a3cbaaa316b18cc99"
        # R08-C: pure viewport evidence moves to the existing session policy; S0 unchanged.
        path = "business/src/main/kotlin/com/freedom/features/aireply/PrivateMessagesTraversalTaskExecutor.kt"
        self.assertEqual("1d12a483673719255862b5c512c8db9fd9219fad", reviewed_s1[path])
        reviewed_s1[path] = "a1fd5cff7c8ea79204658ea942b2e8e2a5048cb4"
        path = "business/src/main/kotlin/com/freedom/features/aireply/ports/ChatSessionManagementPolicy.kt"
        self.assertEqual("fb8d97a3a33e6c576cc636f7eb95b00f838d6167", reviewed_s1[path])
        reviewed_s1[path] = "bd4a945fd3c7835704e7731f7f1c862f18ff4b7f"
        # R08-D: exact relocation of Activity export presentation; S0 unchanged.
        path = "app/src/main/java/com/freedom/MainActivity.kt"
        self.assertEqual("7afbe0c4eb9f8f67f96f9833665093424948d9c2", reviewed_s1[path])
        reviewed_s1[path] = "7e4148b1006924f66b11308f394b7752279f0984"
        path = "app/src/main/java/com/freedom/ui/logs/LogRecordsDialog.kt"
        self.assertEqual("468cbf7f53b5cd88fed715847b09392d25fbabae", reviewed_s1[path])
        reviewed_s1[path] = "cc26e51c6a2fb3788359b189ca23f979e1371f4f"
        path = "app/src/main/java/com/freedom/ui/logs/LogTextFormatter.kt"
        self.assertEqual("0e61d27a625a3b71bae1d29a3f78d5cc4c6660d0", reviewed_s1[path])
        reviewed_s1[path] = "de38ba4bb7b4c1219fcf1e7b988bb84036507c92"
        # R08-E: exact relocation of diagnostic serialization; S0 unchanged.
        path = "app/src/main/java/com/freedom/data/RunDiagnosticsManager.kt"
        self.assertEqual("d44a787d7f8b9195a8e226086167573b31129b3b", reviewed_s1[path])
        reviewed_s1[path] = "8964b04c99e4bc3f0b3fe5f80108448183b4fccc"
        path = "app/src/main/java/com/freedom/data/RunDiagnosticEventJsonCodec.kt"
        self.assertEqual("4e6a6e034886e073c33e9ade293138dc5c7d52ff", reviewed_s1[path])
        reviewed_s1[path] = "62c4d85a7afc2a4e818cd6477ba4405c76315d88"
        path = "app/src/main/java/com/freedom/data/RunDiagnosticReportFormatter.kt"
        self.assertEqual("1cc1720451ebb4523a8d6d3eae1cf76e8e4b5733", reviewed_s1[path])
        reviewed_s1[path] = "9c16ec9e6c76b8ec87a2d3a470887a416f9920c3"
        # R08-F: exact relocation of the runtime-local draft history preload; S0 unchanged.
        path = "app/src/main/java/com/freedom/app/AppCompositionRoot.kt"
        self.assertEqual("357ba685dcd4acde01ac7b41eebac43207a63c2f", reviewed_s1[path])
        reviewed_s1[path] = "0aed30d8527e25c3ac9d732b194a1f9aaf145b6f"
        path = "business/src/main/kotlin/com/freedom/features/conversation/domain/contact/ContactHistoryResolver.kt"
        self.assertEqual("ebab2e333685c707cf484b8eb911c66877b76712", reviewed_s1[path])
        reviewed_s1[path] = "235214217df96e84e291d5a58256365551881275"
        # R09-D: exact verified-contact cache isolation; no S0 change.
        self.assertEqual("235214217df96e84e291d5a58256365551881275", reviewed_s1[path])
        reviewed_s1[path] = "f0609031962c7c8639ef013c81987d86656d8d94"
        # R08-G: same-package evidence extraction; exact prior source reconstructed by migration test.
        path = "business/src/main/kotlin/com/freedom/features/aireply/ports/ChatPagePort.kt"
        self.assertEqual("5e7d75815304073b585412bbcbf01f1952c680d9", reviewed_s1[path])
        reviewed_s1[path] = "e7bbace419e928f0b850c4b8175cbd9acd901076"
        path = "business/src/main/kotlin/com/freedom/features/aireply/ports/ChatPageSnapshot.kt"
        self.assertNotIn(path, reviewed_s1)
        self.assertNotIn(path, m["permanent_s0"])
        reviewed_s1[path] = "a1a904f1435e94a1b60a0d5bc6240882c9a268eb"
        # R08-H exact shared node-read fallback convergence, existing adapter layer only.
        path = "app/src/main/java/com/freedom/adapters/social/momo/MomoChatListSessionExtractor.kt"
        self.assertEqual("581a0a323522ed6e12b71fe2e1d85fb197adb18e", reviewed_s1[path])
        reviewed_s1[path] = "38213acdb5353e60329e4c02d62ca500edc959bf"
        path = "app/src/main/java/com/freedom/adapters/social/momo/MomoChatMessageReader.kt"
        self.assertEqual("1cc8714d36bcf379ee108dccb108aa988dc33fa2", reviewed_s1[path])
        reviewed_s1[path] = "2eeddb722db9e09ccd8ff0b7a141ae6973b537f1"
        path = "app/src/main/java/com/freedom/adapters/social/momo/MomoChatPageInspector.kt"
        self.assertEqual("25af913c686272851a511bf33f667f01b4431f6e", reviewed_s1[path])
        reviewed_s1[path] = "31442fe88ecc22fbf47c3dd13bf648a1508ad464"
        path = "app/src/main/java/com/freedom/adapters/social/momo/MomoNodeReadFallbacks.kt"
        self.assertNotIn(path, reviewed_s1)
        self.assertNotIn(path, m["permanent_s0"])
        reviewed_s1[path] = "df39945103fb16e193cefcdac85a5bf84162b382"
        # R09-B exact removal of the always-null legacy region refresh handle and callers.
        path = "business/src/main/kotlin/com/freedom/features/aireply/AiReplyObservationRuntime.kt"
        self.assertEqual("7f5d99bcfccf80e2404880fcdfd5341c73a19014", reviewed_s1[path])
        reviewed_s1[path] = "6f48cd1414f94402bc5332ab81895ea565865eac"
        path = "business/src/main/kotlin/com/freedom/features/aireply/MomoPageObservationCoordinator.kt"
        self.assertEqual("26ee9be133eb554f0b08bd000dd055f0164a95b8", reviewed_s1[path])
        reviewed_s1[path] = "d6e9a30f6c4094172d271dc066cf158e0d201cb0"
        # R09-B1/B2/B3: exact approved dead declarations and shared HTTP primitives.
        path = "app/src/main/java/com/freedom/adapters/accessibility/android/MonitorOverlay.kt"
        self.assertEqual("9c88ed19eddca26138fad43d6a852f8df3a1de44", reviewed_s1[path])
        reviewed_s1[path] = "1e4ff5d38f5c2b0433b81352cf9f0c8d031d6c66"
        # R09-B1 CI: restore the complete original protected overlay; B2/B3 unchanged.
        self.assertEqual("1e4ff5d38f5c2b0433b81352cf9f0c8d031d6c66", reviewed_s1[path])
        reviewed_s1[path] = "9c88ed19eddca26138fad43d6a852f8df3a1de44"
        path = "app/src/main/java/com/freedom/application/ports/MonitorDataPorts.kt"
        self.assertEqual("509ea931ee65ee7994515aaff785d02ce7ff4102", reviewed_s1[path])
        reviewed_s1[path] = "93624f30c8026b53eefba63a3591fcf4e959c71a"
        path = "app/src/main/java/com/freedom/adapters/ai/DeepSeekAiModelTransport.kt"
        self.assertEqual("078a09b43ad3268983c140501f91b6cbeb727291", reviewed_s1[path])
        reviewed_s1[path] = "8af8c4772b2415924e64d63cdd47a5e37bb5dea8"
        path = "app/src/main/java/com/freedom/adapters/ai/OpenAiCompatibleAiModelTransport.kt"
        self.assertEqual("9007ab686f11b4717347706bed5675858e13a565", reviewed_s1[path])
        reviewed_s1[path] = "70e73359219880554132f75f193749ac9d81288b"
        path = "app/src/main/java/com/freedom/adapters/ai/AiHttpTransportSupport.kt"
        self.assertNotIn(path, reviewed_s1)
        self.assertNotIn(path, m["permanent_s0"])
        reviewed_s1[path] = "4f554b193371269082187a5fc2bf7744c86effeb"
        # D01-B: authorized exact retirement of the independent node ZIP; S0 unchanged.
        path = "app/src/main/java/com/freedom/MainActivity.kt"
        self.assertEqual("7e4148b1006924f66b11308f394b7752279f0984", reviewed_s1[path])
        reviewed_s1[path] = "94a8c122f60d52f24d59b4edb96d212a7d6a295d"
        path = "app/src/main/java/com/freedom/adapters/accessibility/diagnostics/LatestAccessibilityNodeDump.kt"
        self.assertEqual("0373e7e20e2d921aebb2559fe910a6c7aed22c67", reviewed_s1[path])
        reviewed_s1[path] = "2d636faa98ed11fdecae133692ab3b4ff720905e"
        # D01-C: exact approved export-range changes; original S0 and manifests unchanged.
        path = "app/src/main/java/com/freedom/MainActivity.kt"
        self.assertEqual("94a8c122f60d52f24d59b4edb96d212a7d6a295d", reviewed_s1[path])
        reviewed_s1[path] = "099ca52fb0d6dcf1ae9d394fa624725f5d233a3c"
        path = "app/src/main/java/com/freedom/ui/logs/SessionSampleArchiveManager.kt"
        self.assertEqual("7840a6f0f279bd4b6f33bc6814c6e053eed059ae", reviewed_s1[path])
        reviewed_s1[path] = "c1527cfb178dffd2b429924f0a245cb6327edde3"
        self.assertEqual([], freeze_violations(
            m["permanent_s0"], reviewed_s1, actual))

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
