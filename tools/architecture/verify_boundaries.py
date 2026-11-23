#!/usr/bin/env python3
"""Static architecture guardrails for the Freedom modular Android project.

Uses only the Python standard library so it can run before Gradle/Android SDK setup.
This is a source-level guard, not a substitute for Kotlin compilation or Gradle dependency
resolution; those are exercised by the Android build job.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import re
import sys
from pathlib import Path
from typing import Iterable

EXPECTED_MODULES = {
    ":app",
    ":business",
    ":core",
    ":gesture-api",
    ":gesture-core",
    ":gesture-runtime",
    ":gesture-android",
    ":ocr-plugin-api",
    ":ocr-plugin",
}

# Explicitly document the allowed Gradle project-dependency direction.
ALLOWED_PROJECT_DEPENDENCIES = {
    ":app": {":business", ":core", ":gesture-api", ":gesture-runtime", ":gesture-android", ":ocr-plugin-api"},
    ":business": {":core", ":gesture-api"},
    ":core": set(),
    ":gesture-api": {":core"},
    ":gesture-core": {":core"},
    ":gesture-runtime": {":core", ":gesture-api", ":gesture-core"},
    ":gesture-android": {":core", ":gesture-runtime"},
    # The tiny AIDL/Parcelable API is shared; the OCR application itself remains separately installed.
    ":ocr-plugin-api": set(),
    ":ocr-plugin": {":ocr-plugin-api"},
}

RUNTIME_SOURCE_PREFIX = "gesture/gesture-runtime/src/main/kotlin/com/freedom/application/gesture/"
COMPILER_PATH = RUNTIME_SOURCE_PREFIX + "GestureCommandCompiler.kt"
VALIDATOR_PATH = RUNTIME_SOURCE_PREFIX + "GesturePolicyValidator.kt"
COMMAND_MODEL_PATH = "gesture/gesture-runtime/src/main/kotlin/com/freedom/core/accessibility/gesture/GestureCommand.kt"
GESTURE_SPEC_PATH = "core/src/main/kotlin/com/freedom/core/accessibility/api/AccessibilityGateway.kt"
ANDROID_DISPATCHER_PATH = "gesture/gesture-android/src/main/kotlin/com/freedom/adapters/accessibility/android/AndroidGestureDispatcher.kt"

# Kotlin comments and literals are blanked before scanning to avoid false positives from
# documentation, diagnostics, and test fixture strings while preserving line positions.
_KOTLIN_NON_CODE = re.compile(
    r'"""[\s\S]*?"""|/\*[\s\S]*?\*/|//[^\n]*|"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'',
    re.MULTILINE,
)



def agents_integrity_violations(root: Path) -> list[str]:
    """Verify the indexed AGENTS archive, its manifest, and the active ordered fragments."""
    agent_dir = root / "docs/agent"
    manifest_path = agent_dir / "INTEGRITY.json"
    if not manifest_path.is_file():
        return ["AGENTS integrity: docs/agent/INTEGRITY.json is missing"]

    try:
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    except (OSError, UnicodeError, json.JSONDecodeError) as error:
        return [f"AGENTS integrity: invalid manifest: {error}"]
    if not isinstance(manifest, dict):
        return ["AGENTS integrity: manifest must be a JSON object"]

    originals = manifest.get("originals")
    parts = manifest.get("parts")
    active = manifest.get("active_manual")
    if (not isinstance(originals, list) or not isinstance(parts, list)
            or not isinstance(active, dict)
            or not all(isinstance(entry, dict) for entry in originals + parts)):
        return ["AGENTS integrity: manifest originals, parts, or active_manual is invalid"]

    expected_originals = [
        "docs/agent/source/Freedom_按需执行索引.original.md",
        "docs/agent/source/Android_无障碍落地开发手册.original.md",
    ]
    expected_active = "docs/agent/source/Android_无障碍落地开发手册.current.md"
    issues: list[str] = []
    if [entry.get("path") for entry in originals] != expected_originals:
        issues.append("AGENTS integrity: expected exactly the two protected original archives")
    if active.get("path") != expected_active:
        issues.append("AGENTS integrity: active manual path differs from the approved source")

    part_names = [entry.get("path") for entry in parts]
    expected_prefixes = [f"{number:02d}-" for number in range(1, 11)]
    if (len(part_names) != 10 or
            any(not isinstance(name, str) or
                not name.startswith(f"docs/agent/parts/{prefix}") or
                not name.endswith(".md")
                for name, prefix in zip(part_names, expected_prefixes))):
        issues.append("AGENTS integrity: expected exactly ten ordered parts 01-10")

    parts_dir = agent_dir / "parts"
    actual_names = sorted(path.relative_to(root).as_posix()
                          for path in parts_dir.glob("*.md")) if parts_dir.is_dir() else []
    if actual_names != sorted(name for name in part_names if isinstance(name, str)):
        issues.append("AGENTS integrity: actual parts do not match the manifest file list")

    def read_verified(entry: dict, role: str) -> bytes | None:
        name = entry.get("path")
        if (not isinstance(name, str) or not name.startswith("docs/agent/") or
                ".." in Path(name).parts or Path(name).is_absolute()):
            issues.append(f"AGENTS integrity: unsafe or missing {role} path")
            return None
        source = root / name
        if not source.is_file():
            issues.append(f"AGENTS integrity: missing {role}: {name}")
            return None
        content = source.read_bytes()
        if entry.get("bytes") != len(content):
            issues.append(f"AGENTS integrity: byte length mismatch: {name}")
        if entry.get("sha256") != hashlib.sha256(content).hexdigest():
            issues.append(f"AGENTS integrity: SHA-256 mismatch: {name}")
        return content

    originals_data = [read_verified(entry, "original") for entry in originals]
    parts_data = [read_verified(entry, "part") for entry in parts]
    active_data = read_verified(active, "active manual")
    if active_data is not None and all(fragment is not None for fragment in parts_data):
        if b"".join(parts_data) != active_data:
            issues.append("AGENTS integrity: concatenated parts differ from the active manual")

    entry_path = root / "AGENTS.md"
    if not entry_path.is_file():
        issues.append("AGENTS integrity: project AGENTS.md entry is missing")
    elif (len(originals_data) >= 1 and originals_data[0] is not None):
        original_index = originals_data[0]
        if not entry_path.read_bytes().startswith(original_index):
            issues.append("AGENTS integrity: AGENTS.md does not preserve the original index prefix")
        if manifest.get("root_index_original_prefix_sha256") != hashlib.sha256(original_index).hexdigest():
            issues.append("AGENTS integrity: index prefix SHA-256 does not match the archive")
    return issues


def mask_kotlin_non_code(source: str) -> str:
    def blank(match: re.Match[str]) -> str:
        return "".join("\n" if char == "\n" else " " for char in match.group(0))

    return _KOTLIN_NON_CODE.sub(blank, source)


def strip_gradle_comments(source: str) -> str:
    # Gradle dependency coordinates live inside string literals, so only remove comments.
    source = re.sub(r"/\*[\s\S]*?\*/", "", source)
    return re.sub(r"//[^\n]*", "", source)


def project_dependencies(build_file: Path) -> set[str]:
    if not build_file.is_file():
        return set()
    source = strip_gradle_comments(build_file.read_text(encoding="utf-8"))
    return set(re.findall(r'project\s*\(\s*["\'](:[A-Za-z0-9_:-]+)["\']\s*\)', source))


def kotlin_sources(root: Path) -> list[Path]:
    search_roots = [
        root / "app/src/main/java",
        root / "core/src/main/kotlin",
        root / "business/src/main/kotlin",
        root / "gesture/gesture-api/src/main/kotlin",
        root / "gesture/gesture-core/src/main/kotlin",
        root / "gesture/gesture-runtime/src/main/kotlin",
        root / "gesture/gesture-android/src/main/kotlin",
        root / "ocr-plugin/src/main/java",
        root / "ocr-plugin-api/src/main/java",
        root / "gesture",
        root / "business",
        root / "core",
    ]
    files: set[Path] = set()
    for search_root in search_roots:
        if not search_root.exists():
            continue
        for path in search_root.rglob("*.kt"):
            rel = path.relative_to(root).as_posix()
            if any(part in {"build", ".gradle", ".git", "test", "androidTest"} for part in path.parts):
                continue
            files.add(path)
    return sorted(files)


def account_browse_state_ownership_leaks(code_by_path: dict[str, str], state_code: str) -> list[str]:
    """Enforce Runtime ownership of immutable AccountBrowse snapshots and update commands."""
    state_rel = "business/src/main/kotlin/com/freedom/features/accountbrowse/AccountBrowseSessionState.kt"
    runtime_rel = "business/src/main/kotlin/com/freedom/features/accountbrowse/AccountBrowseRuntime.kt"
    runtime_code = code_by_path.get(runtime_rel, "")
    offenders: set[str] = set()

    snapshot_match = re.search(
        r"data\s+class\s+AccountBrowseSessionSnapshot\s*\((.*?)\)\s*",
        state_code,
        re.DOTALL,
    )
    if snapshot_match is None or re.search(r"^\s*var\s+\w+\s*:", snapshot_match.group(1), re.MULTILINE):
        offenders.add(state_rel)
    if not re.search(r"internal\s+class\s+AccountBrowseSessionStateOwner\b", state_code):
        offenders.add(state_rel)
    if not re.search(r"private\s+val\s+sessionStateOwner\s*=\s*AccountBrowseSessionStateOwner\(\)", runtime_code):
        offenders.add(runtime_rel)
    if not re.search(r"val\s+session:\s*AccountBrowseSessionStatePort\s*=\s*sessionStateOwner\.port", runtime_code):
        offenders.add(runtime_rel)

    adapter_reference = re.compile(
        r"\b(?:AccountBrowseSessionStateOwner|AccountBrowseSessionSnapshot|AccountBrowseSessionState)\b"
    )
    owner_construction = re.compile(r"\bAccountBrowseSessionStateOwner\s*\(")
    for rel, code in code_by_path.items():
        if ("/adapters/" in rel or "/stability/" in rel) and adapter_reference.search(code):
            offenders.add(rel)
        if owner_construction.search(code) and rel != runtime_rel:
            offenders.add(rel)

    return sorted(offenders)


def core_test_application_dependencies(root: Path) -> list[str]:
    """Find :core-owned test sources that import :app implementation classes.

    :core compiles app/src/test/java/com/freedom/core/** as its own test source set.
    Such tests may depend on core contracts, but must not import :app application classes.
    """
    test_root = root / "core/src/test/kotlin/com/freedom/core"
    if not test_root.exists():
        return []
    offenders: list[str] = []
    app_import = re.compile(r"^\s*import\s+com\.freedom\.application(?:\.|$)", re.MULTILINE)
    for path in sorted(test_root.rglob("*.kt")):
        code = mask_kotlin_non_code(path.read_text(encoding="utf-8"))
        if app_import.search(code):
            offenders.append(path.relative_to(root).as_posix())
    return offenders


def relative(path: Path, root: Path) -> str:
    return path.relative_to(root).as_posix()


def inspect_project(root: Path) -> list[str]:
    root = root.resolve()
    issues: list[str] = []

    # 1) Gradle modules and their dependency direction.
    settings = root / "settings.gradle.kts"
    if not settings.is_file():
        return ["Missing settings.gradle.kts"]
    settings_text = strip_gradle_comments(settings.read_text(encoding="utf-8"))
    included = set(re.findall(r'"(:[A-Za-z0-9_:-]+)"', settings_text))
    missing_modules = EXPECTED_MODULES - included
    if missing_modules:
        issues.append(f"settings.gradle.kts is missing modules: {sorted(missing_modules)}")

    module_builds = {
        ":app": root / "app/build.gradle.kts",
        ":business": root / "business/build.gradle.kts",
        ":core": root / "core/build.gradle.kts",
        ":gesture-api": root / "gesture/gesture-api/build.gradle.kts",
        ":gesture-core": root / "gesture/gesture-core/build.gradle.kts",
        ":gesture-runtime": root / "gesture/gesture-runtime/build.gradle.kts",
        ":gesture-android": root / "gesture/gesture-android/build.gradle.kts",
        ":ocr-plugin-api": root / "ocr-plugin-api/build.gradle.kts",
        ":ocr-plugin": root / "ocr-plugin/build.gradle.kts",
    }
    for module, build_file in module_builds.items():
        if not build_file.is_file():
            issues.append(f"{module} is missing its build.gradle.kts")
            continue
        actual = project_dependencies(build_file)
        allowed = ALLOWED_PROJECT_DEPENDENCIES[module]
        unexpected = actual - allowed
        missing = allowed - actual
        if unexpected:
            issues.append(f"{module} has forbidden project dependencies: {sorted(unexpected)}")
        if missing:
            issues.append(f"{module} is missing expected project dependencies: {sorted(missing)}")

    sources = kotlin_sources(root)
    code_by_path = {relative(path, root): mask_kotlin_non_code(path.read_text(encoding="utf-8")) for path in sources}

    # 1b) The OCR runtime must stay inside the separately installed plugin APK.
    host_build_text = strip_gradle_comments((root / "app/build.gradle.kts").read_text(encoding="utf-8"))
    plugin_build_text = strip_gradle_comments((root / "ocr-plugin/build.gradle.kts").read_text(encoding="utf-8"))
    runtime_coordinates = ("onnxruntime-android", "org.opencv:opencv")
    for coordinate in runtime_coordinates:
        if coordinate in host_build_text:
            issues.append(f":app must not package OCR runtime dependency {coordinate}")
        if coordinate not in plugin_build_text:
            issues.append(f":ocr-plugin must own OCR runtime dependency {coordinate}")
    if re.search(r'project\s*\(\s*[:\"]ocr-plugin', host_build_text):
        issues.append(":app must not depend on the :ocr-plugin Gradle module; it is installed as a separate APK")
    plugin_projects = project_dependencies(module_builds[":ocr-plugin"])
    unexpected_plugin_projects = plugin_projects - {":ocr-plugin-api"}
    if unexpected_plugin_projects:
        issues.append(f":ocr-plugin must not depend on host Gradle modules: {sorted(unexpected_plugin_projects)}")
    if ":ocr-plugin-api" not in plugin_projects:
        issues.append(":ocr-plugin must use the shared AutoJs6-compatible OCR AIDL API module")
    if ":ocr-plugin-api" not in project_dependencies(module_builds[":app"]):
        issues.append(":app must use the shared AutoJs6-compatible OCR AIDL API module")

    runtime_import = re.compile(r"^\s*import\s+(?:com\.paddle\.ocr(?:\.|$)|ai\.onnxruntime(?:\.|$)|org\.opencv(?:\.|$))", re.MULTILINE)
    for path, code in code_by_path.items():
        if path.startswith("app/src/main/") and runtime_import.search(code):
            issues.append(f"OCR engine/runtime import leaked into the host APK: {path}")

    app_source_root = root / "app/src/main"
    embedded_models = [p.relative_to(root).as_posix() for p in app_source_root.rglob("*.onnx")]
    if embedded_models:
        issues.append(f"OCR ONNX model files must not be bundled in the host APK: {embedded_models}")

    aidl_host = root / "app/src/main/aidl/com/freedom/ocr/ipc/IOcrPluginService.aidl"
    aidl_plugin = root / "ocr-plugin/src/main/aidl/com/freedom/ocr/ipc/IOcrPluginService.aidl"
    if not aidl_host.is_file() or not aidl_plugin.is_file():
        issues.append("Host and OCR plugin must both declare IOcrPluginService.aidl")
    elif aidl_host.read_bytes() != aidl_plugin.read_bytes():
        issues.append("Host and OCR plugin AIDL contracts differ")
    autojs_ocr_aidl = root / "ocr-plugin-api/src/main/aidl/org/autojs/plugin/paddle/ocr/api/IOcrPlugin.aidl"
    plugin_info_aidl = root / "ocr-plugin-api/src/main/aidl/org/autojs/plugin/common/api/PluginInfo.aidl"
    if not autojs_ocr_aidl.is_file() or not plugin_info_aidl.is_file():
        issues.append("The shared AutoJs6-compatible OCR AIDL and PluginInfo contracts are missing")

    required_ocr_files = (
        "app/src/main/java/com/freedom/ocr/OcrManager.kt",
        "app/src/main/java/com/freedom/ocr/PluginManager.kt",
        "app/src/main/java/com/freedom/ocr/OcrPluginInstaller.kt",
        "ocr-plugin/src/main/java/com/freedom/ocr/plugin/OcrPluginService.kt",
        "ocr-plugin/src/main/java/com/freedom/ocr/plugin/model/OcrModelManager.kt",
    )
    for rel in required_ocr_files:
        if not (root / rel).is_file():
            issues.append(f"OCR plugin architecture source is missing: {rel}")

    # 1a) :core test sources are selected by the com/freedom/core/** path under app/src/test.
    # They must not import application-layer implementation owned by :app.
    for rel in core_test_application_dependencies(root):
        issues.append(f":core test source imports :app application implementation: {rel}")

    runtime_build = module_builds[":gesture-runtime"]
    if runtime_build.is_file():
        runtime_text = strip_gradle_comments(runtime_build.read_text(encoding="utf-8"))
        if not re.search(r'implementation\s*\(\s*project\s*\(\s*":gesture-core"\s*\)\s*\)', runtime_text):
            issues.append(":gesture-runtime must keep :gesture-core as an implementation dependency")
        if re.search(r'api\s*\(\s*project\s*\(\s*":gesture-core"\s*\)\s*\)', runtime_text):
            issues.append(":gesture-runtime must not re-export :gesture-core algorithm implementation")

    # 2) One public API declaration and no resurrected legacy entry points.
    api_path = "gesture/gesture-api/src/main/kotlin/com/freedom/gesture/api/GestureApi.kt"
    api_file = root / api_path
    if not api_file.is_file():
        issues.append(f"Missing public gesture contract: {api_path}")
    declarations = [
        path for path, code in code_by_path.items()
        if re.search(r"\binterface\s+GestureApi\b", code)
    ]
    if declarations != [api_path]:
        issues.append(f"GestureApi must be declared exactly once at {api_path}; found {declarations}")

    legacy_declarations = []
    for path, code in code_by_path.items():
        if re.search(r"\binterface\s+(?:GesturePolicyPort|InterruptibleGesturePolicyPort)\b", code):
            legacy_declarations.append(path)
    if legacy_declarations:
        issues.append(f"Legacy public gesture ports must not be redeclared: {legacy_declarations}")

    if api_file.is_file():
        api_code = code_by_path.get(api_path, mask_kotlin_non_code(api_file.read_text(encoding="utf-8")))
        leaked = re.findall(r"\b(?:GestureCommand|GestureSpec|GestureExecutor|ValidatedGestureRequest|ApprovedGestureCommand|GestureGeometryPolicy)\b", api_code)
        if leaked:
            issues.append(f"GestureApi exposes Runtime implementation types: {sorted(set(leaked))}")

    # 3) Feature code can depend on the public API but not platform/adapters/Runtime internals.
    feature_root = root / "business/src/main/kotlin/com/freedom/features"
    if feature_root.exists():
        forbidden_import = re.compile(
            r"^\s*import\s+(?:androidx?\.|com\.freedom\.(?:adapters|data|application|accessibility|overlay|ui|stability)\.)",
            re.MULTILINE,
        )
        forbidden_types = re.compile(
            r"\b(?:GestureCommand|GesturePoint|GesturePathCommand|GestureStrokeSpec|GestureSpec|"
            r"GestureDispatchPurpose|GestureExecutor|InterruptibleGestureExecutor|GestureGeometryPolicy|"
            r"ValidatedGestureRequest|ApprovedGestureCommand)\b"
        )
        for path in feature_root.rglob("*.kt"):
            if "test" in path.parts or "build" in path.parts:
                continue
            code = mask_kotlin_non_code(path.read_text(encoding="utf-8"))
            rel = relative(path, root)
            if forbidden_import.search(code):
                issues.append(f"Business Feature has an outer/platform import: {rel}")
            if forbidden_types.search(code):
                issues.append(f"Business Feature references internal gesture types: {rel}")

    # The adapter consumers that express business intent must use GestureApi, not Runtime internals.
    gesture_consumers = [
        "app/src/main/java/com/freedom/adapters/social/momo/MomoSocialAdapter.kt",
        "app/src/main/java/com/freedom/adapters/accessibility/android/AndroidAccountBrowseWorkflow.kt",
        "app/src/main/java/com/freedom/adapters/android/gestures/AndroidGestureCalibrationHost.kt",
    ]
    forbidden_consumer_symbols = re.compile(
        r"\b(?:GestureCommand|GesturePoint|GesturePathCommand|GestureStrokeSpec|GestureSpec|"
        r"GestureDispatchPurpose|GestureExecutor|InterruptibleGestureExecutor|GestureGeometryPolicy|"
        r"ValidatedGestureRequest|ApprovedGestureCommand)\b|\.dispatchGesture\s*\("
    )
    for rel in gesture_consumers:
        path = root / rel
        if not path.is_file():
            issues.append(f"Required GestureApi consumer is missing: {rel}")
            continue
        code = mask_kotlin_non_code(path.read_text(encoding="utf-8"))
        if (
            "import com.freedom.gesture.api.GestureApi" not in code and
            "import com.freedom.features.accountbrowse.ports.AccountBrowseGesturePort" not in code
        ):
            issues.append(f"Gesture business adapter must depend on GestureApi: {rel}")
        if forbidden_consumer_symbols.search(code):
            issues.append(f"Gesture business adapter uses a Runtime/platform bypass: {rel}")

    # 4) Runtime validation tokens are private to Runtime; only the Validator can mint them.
    token_refs = re.compile(r"\b(?:ValidatedGestureRequest|ApprovedGestureCommand)\b")
    issuers = re.compile(r"(?:ValidatedGestureRequest|ApprovedGestureCommand)\s*\.\s*issueAfterValidation\s*\(")
    for rel, code in code_by_path.items():
        if token_refs.search(code) and not rel.startswith(RUNTIME_SOURCE_PREFIX):
            issues.append(f"Runtime validation capability leaked outside Runtime: {rel}")
        if issuers.search(code) and rel != VALIDATOR_PATH:
            issues.append(f"Only GesturePolicyValidator may issue validation capabilities: {rel}")

    # 5) Compile and dispatch internals have a single owner each.
    command_constructors = [
        rel for rel, code in code_by_path.items()
        if re.search(r"\bGestureCommand\s*\(", code)
    ]
    if any(rel != COMPILER_PATH for rel in command_constructors):
        issues.append(f"GestureCommand construction is restricted to GestureCommandCompiler: {command_constructors}")
    if COMPILER_PATH not in command_constructors:
        issues.append("GestureCommandCompiler must contain GestureCommand construction")

    spec_constructors = [
        rel for rel, code in code_by_path.items()
        if re.search(r"\bGestureSpec\s*\(", code)
    ]
    if any(rel not in {COMPILER_PATH, GESTURE_SPEC_PATH} for rel in spec_constructors):
        issues.append(f"GestureSpec construction is restricted to compiler and API model declaration: {spec_constructors}")
    if COMPILER_PATH not in spec_constructors:
        issues.append("GestureCommandCompiler must contain GestureSpec construction")

    dispatch_call = re.compile(r"\b[A-Za-z_][A-Za-z0-9_]*\s*\.\s*dispatchGesture\s*\(")
    dispatch_files = [rel for rel, code in code_by_path.items() if dispatch_call.search(code)]
    allowed_dispatch_files = {
        "gesture/gesture-runtime/src/main/kotlin/com/freedom/application/gesture/GestureExecutionCoordinator.kt",
        "app/src/main/java/com/freedom/adapters/accessibility/android/AndroidAccessibilityGateway.kt",
        ANDROID_DISPATCHER_PATH,
    }
    unexpected_dispatch = sorted(set(dispatch_files) - allowed_dispatch_files)
    if unexpected_dispatch:
        issues.append(f"dispatchGesture port calls are outside the approved coordinator/adapter path: {unexpected_dispatch}")

    platform_dispatch_files = [
        rel for rel, code in code_by_path.items()
        if re.search(r"\bservice\s*\.\s*dispatchGesture\s*\(", code)
    ]
    if platform_dispatch_files != [ANDROID_DISPATCHER_PATH]:
        issues.append(
            "AccessibilityService.dispatchGesture must be called only by AndroidGestureDispatcher; "
            f"found {platform_dispatch_files}"
        )

    # The public execution coordinator must accept the approved type, never an untrusted command.
    coordinator_path = root / (RUNTIME_SOURCE_PREFIX + "GestureExecutionCoordinator.kt")
    if not coordinator_path.is_file():
        issues.append("GestureExecutionCoordinator.kt is missing")
    else:
        coordinator_code = mask_kotlin_non_code(coordinator_path.read_text(encoding="utf-8"))
        if not re.search(r"approvedCommand\s*:\s*ApprovedGestureCommand", coordinator_code):
            issues.append("GestureExecutionCoordinator must accept ApprovedGestureCommand")

    # 6) Verify the current indexed AGENTS sources without weakening the content checks.
    issues.extend(agents_integrity_violations(root))

    # 7) Clean/Hexagonal boundaries across every production Kotlin source, not just selected files.
    def imported_packages(code: str) -> list[str]:
        return re.findall(r"^\s*import\s+([A-Za-z_][A-Za-z0-9_.]*)", code, re.MULTILINE)

    for rel, code in code_by_path.items():
        if "/src/test/" in rel or "/src/androidTest/" in rel:
            continue
        imports = imported_packages(code)
        if "/features/" in rel:
            forbidden = [name for name in imports if name.startswith((
                "android.", "androidx.", "com.freedom.adapters.", "com.freedom.data.",
                "com.freedom.stability.", "com.freedom.ui.", "com.freedom.accessibility.",
            ))]
            if forbidden:
                issues.append(f"Feature outer-layer dependency: {rel}: {sorted(set(forbidden))}")
        if "/adapters/" in rel or "/stability/" in rel:
            data_imports = [name for name in imports if name.startswith("com.freedom.data.")]
            if data_imports:
                issues.append(f"Adapter/platform layer imports Data implementation: {rel}: {sorted(set(data_imports))}")
        if "/application/" in rel:
            data_imports = [name for name in imports if name.startswith("com.freedom.data.")]
            if data_imports:
                issues.append(f"Application layer imports Data implementation: {rel}: {sorted(set(data_imports))}")
        if "/ui/" in rel:
            forbidden = [name for name in imports if name.startswith((
                "com.freedom.adapters.", "com.freedom.data.", "com.freedom.accessibility.",
                "com.freedom.stability.", "android.view.accessibility.",
            ))]
            if forbidden:
                issues.append(f"UI depends on concrete outer implementation: {rel}: {sorted(set(forbidden))}")

    # 8) Platform entry points must not own application composition or data construction.
    composition_root_rel = "app/src/main/java/com/freedom/app/AppCompositionRoot.kt"
    application_rel = "app/src/main/java/com/freedom/app/FreedomApplication.kt"
    manifest_rel = "app/src/main/AndroidManifest.xml"
    if not (root / composition_root_rel).is_file():
        issues.append(f"Missing application composition root: {composition_root_rel}")
    if not (root / application_rel).is_file():
        issues.append(f"Missing Application bootstrap for AppCompositionRoot: {application_rel}")
    elif "AppCompositionRoot(this)" not in (root / application_rel).read_text(encoding="utf-8"):
        issues.append("FreedomApplication must initialize AppCompositionRoot before platform entry points")
    if (root / manifest_rel).is_file():
        manifest_text = (root / manifest_rel).read_text(encoding="utf-8")
        if 'android:name=".app.FreedomApplication"' not in manifest_text:
            issues.append("AndroidManifest.xml must register .app.FreedomApplication")

    service_rel = "app/src/main/java/com/freedom/accessibility/FreedomAccessibilityService.kt"
    service_path = root / service_rel
    if service_path.is_file():
        service_code = code_by_path.get(service_rel, mask_kotlin_non_code(service_path.read_text(encoding="utf-8")))
        if re.search(r"\bRunSessionCoordinator\s*\(", service_code):
            issues.append("FreedomAccessibilityService constructs RunSessionCoordinator; composition belongs in app/AppCompositionRoot")
        if re.search(r"^\s*import\s+com\.freedom\.data\.", service_code, re.MULTILINE):
            issues.append("FreedomAccessibilityService imports Data implementations; inject ports/dependencies from AppCompositionRoot")
        if "createAccessibilityServiceDependencies" not in service_code:
            issues.append("FreedomAccessibilityService must receive its runtime graph from AppCompositionRoot")
    activity_rel = "app/src/main/java/com/freedom/MainActivity.kt"
    activity_path = root / activity_rel
    if activity_path.is_file():
        activity_code = code_by_path.get(activity_rel, mask_kotlin_non_code(activity_path.read_text(encoding="utf-8")))
        if re.search(r"^\s*import\s+com\.freedom\.data\.", activity_code, re.MULTILINE):
            issues.append("MainActivity imports Data implementations; UI must receive application ports/use cases")
        if re.search(r"\b(?:MonitorStoreRepository|TaskLaunchParametersStore|OcrReviewStore|RunDiagnosticsManager)\s*\(", activity_code):
            issues.append("MainActivity constructs persistence implementations; move composition to AppCompositionRoot")
        if "createActivityDependencies" not in activity_code:
            issues.append("MainActivity must receive its application dependencies from AppCompositionRoot")

    # 9) Feature state must not be exposed as mutable implementation to outer layers.
    account_state_rel = "business/src/main/kotlin/com/freedom/features/accountbrowse/AccountBrowseSessionState.kt"
    account_state_path = root / account_state_rel
    if account_state_path.is_file():
        state_code = code_by_path.get(account_state_rel, mask_kotlin_non_code(account_state_path.read_text(encoding="utf-8")))
        for rel in account_browse_state_ownership_leaks(code_by_path, state_code):
            issues.append(f"AccountBrowse state ownership leak into adapter/platform layer: {rel}")

    # 10) Shared recovery transaction is an architecture boundary, not a business-local utility.
    issues.extend(recovery_transaction_boundary_violations(code_by_path))

    # P0: freeze single authority declarations and known construction routes.
    issues.extend(architecture_authority_violations(code_by_path))
    issues.extend(unified_page_observation_violations(code_by_path))
    issues.extend(unified_task_lifecycle_violations(code_by_path))
    issues.extend(unified_ui_write_authorization_violations(code_by_path))
    issues.extend(unified_recovery_adapter_registration_violations(code_by_path))
    issues.extend(unified_recovery_transaction_manager_violations(code_by_path))
    issues.extend(legacy_architecture_bypass_violations(code_by_path))

    # 11) Final hard boundaries for the unified accessibility/recovery architecture.
    issues.extend(final_architecture_boundary_violations(code_by_path))

    # 12) The platform callback and checkpoint restore gates are mandatory boundaries.
    issues.extend(unified_accessibility_entry_violations(code_by_path))
    issues.extend(unified_business_event_entry_violations(code_by_path))
    issues.extend(checkpoint_recovery_boundary_violations(code_by_path))

    # 13) Stage 4-C: removed manual handoff must stay removed; all three checkpoint
    # routes must keep the common verifier and a bounded, non-terminal scheduler.
    issues.extend(automatic_recovery_hard_boundary_violations(code_by_path))

    return issues


def final_architecture_boundary_violations(code_by_path: dict[str, str]) -> list[str]:
    """Enforce the final UNKNOWN/RETRYABLE and platform-entry boundaries."""
    issues: list[str] = []
    feature_paths = {
        rel for rel in code_by_path
        if "/features/" in rel and "/src/main/" in rel and rel.endswith(".kt")
    }
    adapter_paths = {
        rel for rel in code_by_path
        if "/adapters/" in rel and "/src/main/" in rel and rel.endswith(".kt")
    }

    direct_android_accessibility = re.compile(
        r"\bAccessibilityNodeInfo\b|\bandroid\.view\.accessibility\b|\.dispatchGesture\s*\("
    )
    for rel in sorted(feature_paths):
        if direct_android_accessibility.search(code_by_path[rel]):
            issues.append(f"Feature directly accesses Android Accessibility or dispatchGesture: {rel}")

    task_state_machine = re.compile(
        r"\b(?:AccountBrowseStateMachine|UnifiedRecoveryTransactionStateMachine|"
        r"RecoveryTransactionStateMachine)\s*\("
    )
    for rel in sorted(adapter_paths):
        if task_state_machine.search(code_by_path[rel]):
            issues.append(f"Adapter instantiates a task/recovery state machine: {rel}")

    transition_sources = {
        rel: code for rel, code in code_by_path.items()
        if rel.endswith(("ReplyTaskRepository.kt", "ReplySegmentTask.kt"))
    }
    unknown_to_final = re.compile(
        r"(?:ReplyTaskStatus|ReplySegmentTaskStatus)\.UNKNOWN\s+to\s+setOf\([^)]*"
        r"(?:ReplyTaskStatus|ReplySegmentTaskStatus)\.FAILED_FINAL",
        re.DOTALL,
    )
    for rel, code in transition_sources.items():
        if unknown_to_final.search(code):
            issues.append(f"UNKNOWN may transition directly to FAILED_FINAL: {rel}")

    retryable_cache_write = re.compile(
        r"(?:failedInboxIdentityCaptureKeys|permanentFailure|failedCache)[\s\S]{0,180}"
        r"FAILED_RETRYABLE|FAILED_RETRYABLE[\s\S]{0,180}"
        r"(?:failedInboxIdentityCaptureKeys|permanentFailure|failedCache)",
        re.IGNORECASE,
    )
    for rel, code in code_by_path.items():
        if retryable_cache_write.search(code):
            issues.append(f"RETRYABLE result is written to a permanent failure cache: {rel}")
    return issues



# P0 authority baseline retains an exact inventory of current production owners.
# P7 removes the old event and recovery-routing bypasses while retaining isolated
# per-task verification reducers; new construction counts stay exact and audited.
P0_AUTHORITY_DECLARATIONS = {
    "UnifiedAccessibilityEntry": "core/src/main/kotlin/com/freedom/core/accessibility/api/UnifiedAccessibilityEntry.kt",
    "AndroidAccessibilityEventPipeline": "app/src/main/java/com/freedom/adapters/accessibility/android/AndroidAccessibilityEventPipeline.kt",
    "PageObservationCoordinator": "core/src/main/kotlin/com/freedom/core/runtime/PageObservationCoordinator.kt",
    "TaskCoordinator": "core/src/main/kotlin/com/freedom/core/runtime/TaskCoordinator.kt",
    "UiOperationOwnershipCoordinator": "core/src/main/kotlin/com/freedom/core/runtime/UiOperationOwnershipCoordinator.kt",
    "UnifiedRecoveryTransactionStateMachine": "business/src/main/kotlin/com/freedom/features/recovery/RecoveryTransactionState.kt",
    "RecoveryEventDispatcher": "business/src/main/kotlin/com/freedom/features/recovery/RecoveryEventDispatcher.kt",
    "RecoveryScheduler": "business/src/main/kotlin/com/freedom/features/recovery/RecoveryScheduler.kt",
    "RecoveryCheckpointVerificationCoordinator": "business/src/main/kotlin/com/freedom/features/recovery/RecoveryCheckpointVerificationCoordinator.kt",
}

# Exact counts prevent a new authority being silently created in an approved owner.
# The second DynamicPageRecovery reducer is the independently verified checkpoint
# lane behind the P6 process manager, not another UI-operation authorization path.
P0_AUTHORITY_CONSTRUCTIONS = {
    "AndroidAccessibilityEventPipeline": {
        "app/src/main/java/com/freedom/app/AppCompositionRoot.kt": 1,
    },
    "PageObservationCoordinator": {
        "app/src/main/java/com/freedom/app/AppCompositionRoot.kt": 1,
    },
    "TaskCoordinator": {
        "app/src/main/java/com/freedom/app/AppCompositionRoot.kt": 1,
    },
    "UiOperationOwnershipCoordinator": {
        "app/src/main/java/com/freedom/app/AppCompositionRoot.kt": 1,
    },
    "UnifiedRecoveryTransactionStateMachine": {
        "business/src/main/kotlin/com/freedom/features/aireply/AiReplyObservationRuntime.kt": 1,
        "business/src/main/kotlin/com/freedom/features/accountbrowse/DynamicPageRecovery.kt": 2,
        "business/src/main/kotlin/com/freedom/features/aireply/CheckpointVerificationWiring.kt": 1,
    },
    "RecoveryEventDispatcher": {
        "business/src/main/kotlin/com/freedom/features/aireply/AiReplyObservationRuntime.kt": 1,
        "business/src/main/kotlin/com/freedom/features/accountbrowse/DynamicPageRecovery.kt": 2,
        "business/src/main/kotlin/com/freedom/features/aireply/CheckpointVerificationWiring.kt": 1,
    },
    "RecoveryScheduler": {
        "business/src/main/kotlin/com/freedom/features/accountbrowse/DynamicPageRecovery.kt": 1,
        "business/src/main/kotlin/com/freedom/features/aireply/MessageListCheckpointVerification.kt": 1,
        "business/src/main/kotlin/com/freedom/features/aireply/ReceivedGreetingsCheckpointVerification.kt": 1,
    },
    "RecoveryCheckpointVerificationCoordinator": {
        "business/src/main/kotlin/com/freedom/features/accountbrowse/DynamicPageRecovery.kt": 1,
        "business/src/main/kotlin/com/freedom/features/aireply/MessageListCheckpointVerification.kt": 1,
        "business/src/main/kotlin/com/freedom/features/aireply/ReceivedGreetingsCheckpointVerification.kt": 1,
    },
}

# P2 removed the last legacy page-coordinator alias and local construction fallback.
P0_LEGACY_AUTHORITY_ALIASES = {}


def architecture_authority_violations(code_by_path: dict[str, str]) -> list[str]:
    """Freeze architecture ownership while later stages migrate known legacy routes.

    Source strings must already have Kotlin comments and literals masked by the caller.
    This is a lexical CI guard, not a substitute for compiled call-graph tests.
    """
    production = {
        rel: code for rel, code in code_by_path.items()
        if "/src/main/" in rel and rel.endswith(".kt")
    }
    issues: list[str] = []
    for name, expected in P0_AUTHORITY_DECLARATIONS.items():
        declared = sorted(
            rel for rel, code in production.items()
            if re.search(rf"\b(?:class|interface)\s+{re.escape(name)}\b", code)
        )
        if declared != [expected]:
            issues.append(
                f"P0 architecture authority {name} must be declared exactly once "
                f"at {expected}; found {declared}"
            )

        if name not in P0_AUTHORITY_CONSTRUCTIONS:
            continue
        constructed: dict[str, int] = {}
        for rel, code in production.items():
            # A class primary constructor declaration is not an invocation.
            body = re.sub(
                rf"\bclass\s+{re.escape(name)}\b(?=\s*\()", "", code
            )
            count = len(re.findall(rf"\b{re.escape(name)}\s*\(", body))
            if count:
                constructed[rel] = count
        expected_constructions = P0_AUTHORITY_CONSTRUCTIONS[name]
        if constructed != expected_constructions:
            issues.append(
                f"P0 architecture authority {name} construction drift: "
                f"expected {expected_constructions}; found {constructed}"
            )

    aliases: dict[tuple[str, str, str], int] = {}
    for rel, code in production.items():
        for imported, alias in re.findall(
            r"^\s*import\s+([A-Za-z_][A-Za-z_0-9.]*)\s+as\s+([A-Za-z_][A-Za-z_0-9]*)",
            code,
            re.MULTILINE,
        ):
            name = imported.rsplit(".", 1)[-1]
            if name not in P0_AUTHORITY_DECLARATIONS:
                continue
            key = (rel, name, alias)
            count = len(re.findall(rf"\b{re.escape(alias)}\s*\(", code))
            aliases[key] = count
            if key not in P0_LEGACY_AUTHORITY_ALIASES:
                issues.append(f"P0 architecture authority alias bypass is forbidden: {rel}: {name} as {alias}")
        if re.search(
            r"^\s*(?:(?:public|internal|private)\s+)?typealias\s+\w+\s*=\s*"
            r"(?:[A-Za-z_][A-Za-z_0-9]*\.)*"
            r"(?:" + "|".join(map(re.escape, P0_AUTHORITY_DECLARATIONS)) + r")\b",
            code,
            re.MULTILINE,
        ):
            issues.append(f"P0 architecture authority typealias bypass is forbidden: {rel}")
        if rel.startswith("core/src/main/") and re.search(
            r"^\s*import\s+com\.freedom\.(?:features|adapters|application|data|ui)\.",
            code,
            re.MULTILINE,
        ):
            issues.append(f"P0 core cannot import an outer architecture/business layer: {rel}")
    if aliases != P0_LEGACY_AUTHORITY_ALIASES:
        issues.append(
            f"P0 architecture authority legacy alias drift: "
            f"expected {P0_LEGACY_AUTHORITY_ALIASES}; found {aliases}"
        )
    return issues




def unified_page_observation_violations(code_by_path: dict[str, str]) -> list[str]:
    """P2: one injected scheduler, observations as evidence, task decisions in runtimes."""
    issues: list[str] = []
    required = {
        "app/src/main/java/com/freedom/app/AppCompositionRoot.kt": (
            r"\bval\s+pageObservationCoordinator\s*:\s*PageObservationCoordinator\b",
            r"\bsharedPageObservationCoordinator\s*=\s*pageObservationCoordinator\b",
            r"\bpageObservationCoordinator\s*=\s*pageObservationCoordinator\b",
        ),
        "business/src/main/kotlin/com/freedom/features/aireply/AiReplyObservationRuntime.kt": (
            r"\bprivate\s+val\s+sharedPageObservationCoordinator\s*:\s*PageObservationCoordinator\s*,",
            r"\bsharedCoordinator\s*=\s*sharedPageObservationCoordinator\b",
            r"\bisObservationActive\s*=\s*isAiReplyActive\b",
            r"\bpageInspectionTask\s*=\s*sharedPageObservationCoordinator\.scheduleReadOnlyObservation\s*\(",
            r"ReceivedGreetingsPageArrivalStatus\.LIST_CONFIRMED\s*->\s*\{\s*startReceivedGreetingsTaskFromConfirmedPage\s*\(",
        ),
        "business/src/main/kotlin/com/freedom/features/aireply/MomoPageObservationCoordinator.kt": (
            r"\bsharedCoordinator\s*:\s*PageObservationCoordinator\b",
            r"\bisEnabled\s*=\s*isObservationActive\b",
            r"\bscheduleReadOnlyObservation\s*=\s*observationCoordinator::scheduleReadOnlyObservation\b",
        ),
        "business/src/main/kotlin/com/freedom/features/aireply/ReceivedGreetingsPageArrivalWatcher.kt": (
            r"\bprivate\s+val\s+scheduleReadOnlyObservation\s*:\s*\(Long,\s*\(\)\s*->\s*Unit\)",
            r"\bretryTask\s*=\s*scheduleReadOnlyObservation\s*\(",
            r"\bfallbackTask\s*=\s*scheduleReadOnlyObservation\s*\(",
        ),
        "app/src/main/java/com/freedom/adapters/accessibility/android/AndroidAccountBrowseWorkflow.kt": (
            r"\bprivate\s+val\s+pageObservationCoordinator\s*:\s*PageObservationCoordinator\s*,",
            r"\bprivate\s+val\s+observationCoordinator\s*=\s*pageObservationCoordinator\b",
            r"\bisEnabled\s*=\s*\{\s*isMonitoringRunning\(\)\s*&&\s*isBrowseModeActive\(\)\s*\}",
            r"\baccountBrowseRuntime\.onCoalescedPageObservation\s*\(",
        ),
        "business/src/main/kotlin/com/freedom/features/accountbrowse/AccountBrowseRuntime.kt": (
            r"\bfun\s+onCoalescedPageObservation\s*\(",
            r"\bphase\s+!in\s+eligiblePhases\b",
            r"\bscheduleStep\s*\(\s*delayMs\s*\)",
        ),
        "core/src/main/kotlin/com/freedom/core/runtime/PageObservationCoordinator.kt": (
            r"\bfun\s+scheduleReadOnlyObservation\s*\(",
            r"\bisEnabled\s*:\s*\(\)\s*->\s*Boolean\b",
            r"\bisEnabled\(\)\s*&&\s*\(eventTypes\.isEmpty\(\)",
        ),
    }
    for rel, patterns in required.items():
        code = code_by_path.get(rel, "")
        for pattern in patterns:
            if not re.search(pattern, code):
                issues.append(f"P2 unified page observation missing required wiring: {rel}: {pattern}")
    forbidden = {
        "business/src/main/kotlin/com/freedom/features/aireply/MomoPageObservationCoordinator.kt": (
            r"\b(?:Shared)?PageObservationCoordinator\s*\(\s*scheduler\b",
            r"\bonReceivedGreetingsConfirmed\b",
            r"\bstartReceivedGreetingsTaskFromConfirmedPage\s*\(",
        ),
        "business/src/main/kotlin/com/freedom/features/aireply/ReceivedGreetingsPageArrivalWatcher.kt": (
            r"\bonPageConfirmed\b",
            r"\bscheduler\.schedule\s*\(",
        ),
        "app/src/main/java/com/freedom/adapters/accessibility/android/AndroidAccountBrowseWorkflow.kt": (
            r"\bPageObservationCoordinator\s*\(\s*taskScheduler\b",
        ),
        "business/src/main/kotlin/com/freedom/features/aireply/AiReplyObservationRuntime.kt": (
            r"\bpageInspectionTask\s*=\s*scheduler\.schedule\s*\(",
        ),
    }
    for rel, patterns in forbidden.items():
        code = code_by_path.get(rel, "")
        for pattern in patterns:
            if re.search(pattern, code):
                issues.append(f"P2 page observer bypasses common scheduler or task boundary: {rel}: {pattern}")
    browse = code_by_path.get(
        "app/src/main/java/com/freedom/adapters/accessibility/android/AndroidAccountBrowseWorkflow.kt", ""
    )
    if re.search(
        r"private\s+fun\s+onCoalescedPageObservationRequested\s*\([^)]*\)\s*\{"
        r"[^}]*\baccountBrowseRuntime\.scheduleStep\s*\(", browse
    ):
        issues.append("P2 page observer callback directly schedules a business step")
    return issues


def unified_task_lifecycle_violations(code_by_path: dict[str, str]) -> list[str]:
    """P3: the process run clock invalidates one shared task slot before mode callbacks."""
    issues: list[str] = []
    root_rel = "app/src/main/java/com/freedom/app/AppCompositionRoot.kt"
    session_rel = "app/src/main/java/com/freedom/application/runtime/RunSessionCoordinator.kt"
    task_rel = "core/src/main/kotlin/com/freedom/core/runtime/TaskCoordinator.kt"
    execution_rel = "core/src/main/kotlin/com/freedom/core/runtime/TaskExecutionController.kt"
    ai_rel = "business/src/main/kotlin/com/freedom/features/aireply/AiReplyTaskOwnership.kt"
    browse_rel = "app/src/main/java/com/freedom/adapters/runtime/android/AccountBrowseModeRuntimeAdapter.kt"

    required = {
        root_rel: (
            r"\bgenerationClock\s*=\s*root\.runGenerationClock\b",
            r"\btaskCoordinator\s*=\s*root\.taskCoordinator\b",
            r"\bTaskCoordinator\s*\(\s*uiOperationOwnershipCoordinator\s*\)",
        ),
        session_rel: (
            r"\bprivate\s+val\s+taskCoordinator\s*:\s*TaskCoordinator\?\s*=\s*null\b",
            r"\bval\s+newGeneration\s*=\s*generationClock\.advance\(\)\s*taskCoordinator\?\.advanceRunGeneration\s*\(\s*newGeneration\s*\)",
        ),
        task_rel: (
            r"\bfun\s+advanceRunGeneration\s*\(\s*runGeneration\s*:\s*Long\s*\)",
            r"\bif\s*\(\s*runGeneration\s*<=\s*latestRunGeneration\s*\)\s*return\b",
            r"\bactiveLease\?\.let\s*\{\s*uiOperationOwnership\.release\s*\(\s*it\.uiOperationLease\s*\)\s*\}",
            r"\bactiveLease\s*=\s*null\b",
            r"\blatestRunGeneration\s*=\s*runGeneration\b",
            r"\bif\s*\(\s*runGeneration\s*>\s*latestRunGeneration\s*\)\s*advanceRunGeneration\s*\(\s*runGeneration\s*\)",
            r"\bfun\s+activeTaskId\s*\(\s*\)\s*:\s*String\?\s*=\s*currentLease\s*\(\s*\)\?\.taskId\b",
        ),
        execution_rel: (
            r"\btaskCoordinator\.isCurrent\s*\(\s*current\s*\)",
            r"\btaskCoordinator\.transfer\s*\(\s*current\s*,\s*taskId\s*\)",
            r"\btaskCoordinator\.tryAcquire\s*\(\s*taskId\s*,\s*generation\s*\)",
            r"\bgeneration\s*!=\s*currentRunGeneration\s*\(\s*\)",
        ),
        ai_rel: (
            r"\bprivate\s+val\s+delegate\s*=\s*TaskExecutionController\s*\(",
            r"\bcoordinator\s*=\s*coordinator\b",
        ),
        browse_rel: (
            r"\bprivate\s+val\s+taskExecutionController\s*=\s*TaskExecutionController\s*\(",
            r"\bcoordinator\s*=\s*taskCoordinator\b",
            r"\btaskExecutionController\.beginRun\s*\(\s*currentRunGeneration\(\)\s*\)",
            r"\btaskExecutionController\.claim\s*\(\s*TASK_ID\s*,\s*reason\s*\)",
            r"\btaskExecutionController\.release\s*\(\s*TASK_ID\s*,\s*reason\s*\)",
        ),
    }
    for rel, patterns in required.items():
        code = code_by_path.get(rel, "")
        for pattern in patterns:
            if not re.search(pattern, code):
                issues.append(f"P3 task lifecycle unified wiring missing: {rel}: {pattern}")

    root = code_by_path.get(root_rel, "")
    if len(re.findall(r"\btaskCoordinator\s*=\s*root\.taskCoordinator\b", root)) != 5:
        issues.append("P3/P4 all five production consumers (gesture, AI, Browse, session, authorization) must share root.taskCoordinator")

    production = {
        rel: code for rel, code in code_by_path.items()
        if "/src/main/" in rel and rel.endswith(".kt")
    }
    approved_execution_owners = {ai_rel: 1, browse_rel: 1}
    execution_owners = {}
    for rel, code in production.items():
        body = re.sub(r"\bclass\s+TaskExecutionController\b(?=\s*\()", "", code)
        count = len(re.findall(r"\bTaskExecutionController\s*\(", body))
        if count:
            execution_owners[rel] = count
        if rel != session_rel and rel != task_rel and re.search(
            r"\.advanceRunGeneration\s*\(", code
        ):
            issues.append(f"P3 run generation must advance only in the session/task coordinators: {rel}")
    if execution_owners != approved_execution_owners:
        issues.append(
            "P3 shared task execution controller construction drift: "
            f"expected {approved_execution_owners}; found {execution_owners}"
        )
    session_creators = {
        rel: len(re.findall(r"\bRunSessionCoordinator\s*\(", re.sub(
            r"\bclass\s+RunSessionCoordinator\b(?=\s*\()", "", code
        )))
        for rel, code in production.items()
    }
    session_creators = {rel: count for rel, count in session_creators.items() if count}
    if session_creators != {root_rel: 1}:
        issues.append(f"P3 session coordinator construction drift: {session_creators}")
    return issues


def unified_recovery_adapter_registration_violations(code_by_path: dict[str, str]) -> list[str]:
    """P5: one registered, task-scoped recovery route adapter contract for every feature."""
    issues: list[str] = []
    router_rel = "core/src/main/kotlin/com/freedom/core/runtime/PageFingerprintRouter.kt"
    coordinator_rel = "core/src/main/kotlin/com/freedom/core/runtime/PageObservationCoordinator.kt"
    ai_rel = "business/src/main/kotlin/com/freedom/features/aireply/AiReplyObservationRuntime.kt"
    browse_rel = "app/src/main/java/com/freedom/adapters/accessibility/android/AndroidAccountBrowseWorkflow.kt"
    adapter_rels = {
        "business/src/main/kotlin/com/freedom/features/aireply/MessageListRecoveryRouteAdapter.kt":
            (r"\btaskId\s*:\s*String\s*=\s*PrivateMessagesTaskController\.TASK_ID\b",),
        "business/src/main/kotlin/com/freedom/features/aireply/ReceivedGreetingsRecoveryRouteAdapter.kt":
            (r"\btaskId\s*:\s*String\s*=\s*ReceivedGreetingsTaskController\.TASK_ID\b",),
        "business/src/main/kotlin/com/freedom/features/aireply/ChatDetailRecoveryRouteAdapter.kt":
            (r"\btaskId\s*:\s*String\s*=\s*PrivateMessagesTaskController\.TASK_ID\b",
             r"\bprimaryForTask\s*:\s*Boolean\s*=\s*false\b"),
        "business/src/main/kotlin/com/freedom/features/accountbrowse/DynamicFeedRecoveryRouteAdapter.kt":
            (r"\btaskId\s*:\s*String\s*=",),
    }
    required = {
        router_rel: (
            r"\binterface\s+PageRecoveryRouteAdapter\b",
            r"\bval\s+taskId\s*:\s*String\s+get\s*\(\s*\)\s*=\s*routeId\b",
            r"\bval\s+primaryForTask\s*:\s*Boolean\s+get\s*\(\s*\)\s*=\s*true\b",
            r"\bclass\s+PageRecoveryAdapterRegistry\b",
            r"\bfun\s+register\s*\(\s*adapter\s*:\s*PageRecoveryRouteAdapter\s*\)",
            r"\bfun\s+primaryForTask\s*\(\s*taskId\s*:\s*String\s*\)",
            r"\bfun\s+adapterForRoute\s*\(\s*routeId\s*:\s*String\s*\)",
            r"\bfun\s+match\s*\(\s*fingerprint\s*:\s*PageFingerprint\s*,\s*allowedTaskIds\s*:\s*Set<String>\s*\)",
            r"\bit\.taskId\s+in\s+allowedTaskIds\s*&&\s*it\.matches\s*\(\s*fingerprint\s*\)",
            r"\bit\.routeId\s*!=\s*adapter\.routeId\s*&&\s*it\.taskId\s*==\s*adapter\.taskId",
        ),
        coordinator_rel: (
            r"\brecoveryAdapterRegistry\s*=\s*PageRecoveryAdapterRegistry\s*\(\s*\)",
            r"\bfun\s+registerRecoveryAdapter\s*\(\s*adapter\s*:\s*PageRecoveryRouteAdapter\s*\)",
            r"\brecoveryAdapterRegistry\.register\s*\(\s*adapter\s*\)",
            r"\bcheck\s*\(\s*adapter\.routeId\s+!in\s+fingerprintRouter\.registeredRouteIds\s*\(\s*\)\s*\)",
            r"\bcheck\s*\(\s*recoveryAdapterRegistry\.adapterForRoute\s*\(\s*route\.routeId\s*\)\s*==\s*null\s*\)",
            r"\bfun\s+matchRecoveryAdapter\s*\(",
            r"\brecoveryAdapterRegistry\.match\s*\(\s*fingerprint\s*,\s*allowedTaskIds\s*\)",
            r"\bfun\s+primaryRecoveryAdapterForTask\s*\(",
            r"\bfun\s+recoveryAdapterForRoute\s*\(",
            r"\brecoveryAdapterRegistry\.unregister\s*\(\s*routeId\s*\)",
        ),
        ai_rel: (
            r"\bregisterRecoveryAdapter\s*\(\s*messageListRecoveryRoute\s*\)",
            r"\bregisterRecoveryAdapter\s*\(\s*receivedGreetingsRecoveryRoute\s*\)",
            r"\bregisterRecoveryAdapter\s*\(\s*chatDetailRecoveryRoute\s*\)",
            r"\bmatchRecoveryAdapter\s*\(\s*pageFingerprint\s*,\s*setOf\s*\(\s*PRIVATE_MESSAGES_TASK_ID\s*,\s*RECEIVED_GREETINGS_TASK_ID\s*\)",
            r"\brecoveryAdapterForRoute\s*\(\s*routeId\s*\)\?\.taskId\b",
            r"\bprimaryRecoveryAdapterForTask\b",
        ),
        browse_rel: (
            r"\bregisterRecoveryAdapter\s*\(\s*dynamicFeedRecoveryRoute\s*\)",
            r"\bmatchRecoveryAdapter\s*\(",
            r"\ballowedTaskIds\s*=\s*setOf\s*\(\s*dynamicFeedRecoveryRoute\.taskId\s*\)",
        ),
        **adapter_rels
    }
    for rel, patterns in required.items():
        code = code_by_path.get(rel, "")
        for pattern in patterns:
            if not re.search(pattern, code):
                issues.append(f"P5 shared recovery adapter registration missing: {rel}: {pattern}")

    # The typed checkpoint-to-durable-record adapters already share
    # RecoveryCheckpointAdapter<T>; keep all three existing business calls and
    # their business-owned persistence rather than weakening the resume gates.
    checkpoint_interface = (
        "business/src/main/kotlin/com/freedom/features/recovery/RecoveryCheckpointAdapter.kt"
    )
    if not re.search(r"\binterface\s+RecoveryCheckpointAdapter\s*<\s*T\s*>",
                     code_by_path.get(checkpoint_interface, "")):
        issues.append("P5 typed checkpoint mapper interface must remain shared")

    raw_fingerprint_registration = re.compile(r"\bregisterFingerprintRoute\s*\(")
    local_fallback = re.compile(r"\blistOf\s*\(\s*messageListRecoveryRoute\s*,")
    for rel in (ai_rel, browse_rel):
        code = code_by_path.get(rel, "")
        if raw_fingerprint_registration.search(code) or local_fallback.search(code):
            issues.append(f"P5 business routes must use shared recovery adapter registry: {rel}")
    if re.search(r"\bwhen\s*\(\s*routeId\s*\)", code_by_path.get(ai_rel, "")):
        issues.append("P5 AI recovery task lookup must use registered adapter ownership")
    if re.search(r"\bmatchFingerprint\s*\(", code_by_path.get(browse_rel, "")):
        issues.append("P5 browse recovery route match must be scoped by task")

    production = {
        rel: code for rel, code in code_by_path.items()
        if "/src/main/" in rel and rel.endswith(".kt")
    }
    constructs = {
        rel: len(re.findall(r"\bPageRecoveryAdapterRegistry\s*\(", code))
        for rel, code in production.items()
    }
    constructs = {rel: number for rel, number in constructs.items() if number}
    if constructs != {coordinator_rel: 1}:
        issues.append(f"P5 recovery adapter registry construction drift: {constructs}")
    return issues


def unified_recovery_transaction_manager_violations(code_by_path: dict[str, str]) -> list[str]:
    """P6: identified UNKNOWN and included retryables converge before local reducers."""
    issues: list[str] = []
    manager_rel = "business/src/main/kotlin/com/freedom/features/recovery/UnifiedRecoveryTransactionManager.kt"
    dispatcher_rel = "business/src/main/kotlin/com/freedom/features/recovery/RecoveryEventDispatcher.kt"
    app_rel = "app/src/main/java/com/freedom/app/AppCompositionRoot.kt"
    ai_rel = "business/src/main/kotlin/com/freedom/features/aireply/AiReplyObservationRuntime.kt"
    browse_rel = "business/src/main/kotlin/com/freedom/features/accountbrowse/DynamicPageRecovery.kt"
    browse_runtime = "business/src/main/kotlin/com/freedom/features/accountbrowse/AccountBrowseRuntime.kt"
    workflow = "app/src/main/java/com/freedom/adapters/accessibility/android/AndroidAccountBrowseWorkflow.kt"
    wiring = "business/src/main/kotlin/com/freedom/features/aireply/CheckpointVerificationWiring.kt"
    list_verifier = "business/src/main/kotlin/com/freedom/features/aireply/MessageListCheckpointVerification.kt"
    greetings_verifier = "business/src/main/kotlin/com/freedom/features/aireply/ReceivedGreetingsCheckpointVerification.kt"

    required = {
        manager_rel: (
            r"\bclass\s+UnifiedRecoveryTransactionManager\s*\(",
            r"\bcurrentRunGeneration\s*:\s*\(\)\s*->\s*Long",
            r"\bprivate\s+val\s+transactions\s*=\s*LinkedHashMap\s*<\s*LaneKey",
            r"\bfun\s+dispatch\s*\(",
            r"\bidentity\.runGeneration\s*!=\s*generation",
            r"\bidentity\.observationGeneration\s*<\s*old\.observationGeneration",
            r"\bfun\s+completeVerified\s*\(",
            r"\bfun\s+snapshot\s*\(",
            r"\bval\s+key\s*=\s*LaneKey\s*\(\s*identity\.taskId\s*,\s*laneId\s*\)",
            r"\bval\s+next\s*=\s*apply\s*\(\s*\)",
            r"\bevent\s*==\s*RecoveryTransactionEvent\.ResumeCompleted\s*&&\s*next\s*==\s*RecoveryTransactionState\.ACTIVE",
        ),
        dispatcher_rel: (
            r"\bprivate\s+val\s+sharedManager\s*:\s*UnifiedRecoveryTransactionManager\?",
            r"\bprivate\s+val\s+managerLaneId\s*:\s*String",
            r"\bsharedManager\?\.dispatch\s*\(",
            r"\btransaction\.dispatch\s*\(\s*envelope\s*\)",
            r"\bif\s*\(\s*accepted\s*\)\s*activeIdentity\s*=\s*eventIdentity",
        ),
        app_rel: (
            r"\bval\s+recoveryTransactionManager\s+by\s+lazy\b",
            r"\bUnifiedRecoveryTransactionManager\s*\(\s*currentRunGeneration\s*=\s*runGenerationClock::current",
            r"\brecoveryTransactionManager\s*=\s*root\.recoveryTransactionManager",
        ),
        ai_rel: (
            r"\bsharedManager\s*=\s*recoveryTransactionManager",
            r"\brecoveryTransactionManager\s*=\s*recoveryTransactionManager",
            r"\brequestUnifiedPauseAndCheckpoint\s*\(\s*activeTask\s*,",
            r"\brequestUnifiedPauseAndCheckpoint\s*\(\s*PRIVATE_MESSAGES_TASK_ID\s*,",
            r"\bprivate\s+fun\s+recoverRetryablePrivateList\s*\(",
            r"\bbusinessPageKind\s*==\s*ChatPageKind\.UNKNOWN\s*&&\s*taskOwnership\.activeTaskId\s*\(\s*\)\s*==\s*PRIVATE_MESSAGES_TASK_ID",
            r"\bval\s+checkpointRequested\s*=\s*sessionRunStarted\s*&&\s*activeTask\s*!=\s*null",
            r"\brecoveryTransactionManager\?\.completeVerified\s*\(",
        ),
        workflow: (r"\brecoveryTransactionManager\s*=\s*recoveryTransactionManager",),
        browse_runtime: (r"\bsharedManager\s*=\s*recoveryTransactionManager",),
        browse_rel: (
            r"\bsharedManager\s*:\s*UnifiedRecoveryTransactionManager\?",
            r"\bsharedManager\s*=\s*sharedManager",
            r"\brecoveryScheduler\.begin\s*\(",
            r"\bRecoveryTransactionEvent\.UnknownPage\s*\(",
        ),
        wiring: (
            r"\bmanagerLaneId\s*=\s*laneId",
            r"\bsharedManager\s*=\s*manager",
        ),
        list_verifier: (
            r"\bCheckpointVerificationWiring\.newDispatcher\s*\(",
            r"\bCheckpointVerificationWiring\.newDispatcher\s*\(\s*onLog\s*,\s*recoveryTransactionManager\s*,",
        ),
        greetings_verifier: (
            r"\bCheckpointVerificationWiring\.newDispatcher\s*\(",
            r"\bCheckpointVerificationWiring\.newDispatcher\s*\(\s*onLog\s*,\s*recoveryTransactionManager\s*,",
        ),
    }
    for rel, patterns in required.items():
        code = code_by_path.get(rel, "")
        for pattern in patterns:
            if not re.search(pattern, code):
                issues.append(f"P6 unified recovery transaction manager wiring missing: {rel}: {pattern}")

    if len(re.findall(r"\brecoverRetryablePrivateList\s*\(", code_by_path.get(ai_rel, ""))) < 7:
        issues.append("P6 AI retryable unread, unexpected page and UNKNOWN must use six managed recovery call sites")

    constructed = {
        rel: len(re.findall(r"\bUnifiedRecoveryTransactionManager\s*\(", code))
        for rel, code in code_by_path.items()
        if "/src/main/" in rel and rel.endswith(".kt")
    }
    # Strip the class declaration from the one legitimate source.
    constructed[manager_rel] = len(re.findall(
        r"\bUnifiedRecoveryTransactionManager\s*\(",
        re.sub(r"\bclass\s+UnifiedRecoveryTransactionManager(?=\s*\()", "",
               code_by_path.get(manager_rel, ""))
    ))
    constructed = {rel: n for rel, n in constructed.items() if n}
    if constructed != {app_rel: 1}:
        issues.append(f"P6 manager must be constructed only by AppCompositionRoot: {constructed}")
    return issues


def legacy_architecture_bypass_violations(code_by_path: dict[str, str]) -> list[str]:
    """P7: legacy event, route, and reducer paths cannot coexist with unified owners."""
    issues: list[str] = []
    pipeline_rel = "app/src/main/java/com/freedom/adapters/accessibility/android/AndroidAccessibilityEventPipeline.kt"
    root_rel = "app/src/main/java/com/freedom/app/AppCompositionRoot.kt"
    coordinator_rel = "core/src/main/kotlin/com/freedom/core/runtime/PageObservationCoordinator.kt"
    dispatcher_rel = "business/src/main/kotlin/com/freedom/features/recovery/RecoveryEventDispatcher.kt"
    manager_rel = "business/src/main/kotlin/com/freedom/features/recovery/UnifiedRecoveryTransactionManager.kt"
    browse_rel = "business/src/main/kotlin/com/freedom/features/accountbrowse/DynamicPageRecovery.kt"
    ai_rel = "business/src/main/kotlin/com/freedom/features/aireply/AiReplyObservationRuntime.kt"
    pipeline = code_by_path.get(pipeline_rel, "")
    coordinator = code_by_path.get(coordinator_rel, "")
    dispatcher = code_by_path.get(dispatcher_rel, "")
    manager = code_by_path.get(manager_rel, "")
    browse = code_by_path.get(browse_rel, "")
    ai = code_by_path.get(ai_rel, "")

    required = {
        pipeline_rel: (
            r"\bprivate\s+val\s+unifiedAccessibilityEntry\s*:\s*UnifiedAccessibilityEntry\s*[,)]",
            r"\bunifiedAccessibilityEntry\.accept\s*\(\s*UnifiedAccessibilityEvent\.GlobalTouch",
            r"\bunifiedAccessibilityEntry\.accept\s*\(\s*UnifiedAccessibilityEvent\.TargetApplication",
            r"\bunifiedAccessibilityEntry\.accept\s*\(\s*UnifiedAccessibilityEvent\.ActivityTransition",
        ),
        coordinator_rel: (
            r"\brecoveryAdapterRegistry\.register\s*\(\s*adapter\s*\)",
            r"\bcheck\s*\(\s*adapter\.routeId\s+!in\s+fingerprintRouter\.registeredRouteIds\s*\(\s*\)\s*\)",
            r"\bcheck\s*\(\s*recoveryAdapterRegistry\.adapterForRoute\s*\(\s*route\.routeId\s*\)\s*==\s*null\s*\)",
            r"\bfun\s+unregisterRecoveryAdapter\s*\(",
        ),
        dispatcher_rel: (
            r"\bif\s*\(\s*sharedManager\s*!=\s*null\s*\)",
            r"\bsharedManager\b",
            r"\bmanager\.dispatch\s*\(\s*identity\s*,\s*event\s*,\s*transaction\.state\s*,\s*managerLaneId\s*\)",
            r"\bfun\s+observeFingerprint\s*\(",
        ),
        manager_rel: (
            r"\bnext\s*!=\s*RecoveryTransactionState\.ACTIVE",
            r"\bfun\s+restartVerificationLane\s*\(",
            r"\blaneId\s*==\s*PRIMARY_LANE",
        ),
        browse_rel: (
            r"\bprivate\s+val\s+checkpointDispatcher\s*=\s*RecoveryEventDispatcher\s*\(",
            r"\bmanagerLaneId\s*=\s*",
            r"\btransaction\s*=\s*checkpointDispatcher\b",
            r"\bdispatcher\.activate\s*\(\s*identity\s*\(",
            r"\brecoveryObservationGeneration\b",
            r"\brecoveryTransactionManager\?\.completeVerified\s*\(",
            r"\brecoveryTransactionManager\?\.restartVerificationLane\s*\(",
            r"\bif\s*\(\s*checkpointVerifier\s*!=\s*null\s*\)\s*checkpointDispatcher\.state",
            r"\bif\s*\(\s*checkpointVerifier\s*!=\s*null\s*\)\s*checkpointDispatcher\.stableVerificationSamples",
        ),
        ai_rel: (
            r"\bprivate\s+fun\s+activateRecoveryIdentity\s*\(",
            r"\brouteId\s*:\s*String\s*=\s*recoveryRouteIdForTask\s*\(",
            r"\brouteId\s*=\s*chatDetailRecoveryRoute\.routeId\b",
        ),
    }
    for rel, expressions in required.items():
        code = code_by_path.get(rel, "")
        for pattern in expressions:
            if not re.search(pattern, code):
                issues.append(f"P7 bypass migration missing: {rel}: {pattern}")

    # The platform pipeline must not expose its former direct business callbacks,
    # even as nullable test fixtures. Tests now inject the unified gateway as well.
    if re.search(r"\bdispatch(?:TargetApplicationEvent|GlobalTouchEvent|ActivityTransition)\b", pipeline):
        issues.append(f"P7 legacy business callback remains in event pipeline: {pipeline_rel}")
    if re.search(r"\bunifiedAccessibilityEntry\s*!=\s*null\b", pipeline):
        issues.append(f"P7 nullable unified-entry fallback remains: {pipeline_rel}")
    if re.search(r"\bdispatch(?:TargetApplicationEvent|GlobalTouchEvent|ActivityTransition)\s*=", code_by_path.get(root_rel, "")):
        issues.append(f"P7 app root still injects direct business event callbacks: {root_rel}")

    # Recovery adapters cannot be copied into the unscoped fingerprint router.
    if re.search(r"\bfingerprintRouter\.register\s*\(\s*adapter\.asRoute\s*\(", coordinator):
        issues.append(f"P7 duplicate recovery route registration remains: {coordinator_rel}")
    if re.search(r"\brecoveryAdapterRegistry\.unregister\s*\(\s*route\.routeId\s*\)", coordinator):
        issues.append(f"P7 raw route can evict a task-scoped adapter: {coordinator_rel}")

    # All production unidentified events and fingerprint samples must be rejected
    # or admitted via the process-owned transaction manager respectively.
    if not re.search(r"\bif\s*\(\s*eventIdentity\s*==\s*null\s*\)[\s\S]{0,440}"
                     r"\bif\s*\(\s*sharedManager\s*!=\s*null\s*\)[\s\S]{0,200}"
                     r"\breturn\s+transaction\.state\b", dispatcher):
        issues.append(f"P7 unbound recovery event can bypass manager: {dispatcher_rel}")
    if re.search(r"\bfun\s+observeFingerprint\s*\([^)]*\)\s*:\s*RecoveryTransactionState\s*=\s*"
                 r"transaction\.observeFingerprint\s*\(", dispatcher):
        issues.append(f"P7 fingerprint observation bypasses manager: {dispatcher_rel}")
    if re.search(r"\btransaction\s*=\s*dispatcher\b", browse):
        issues.append(f"P7 browse checkpoint and UNKNOWN reuse one reducer lane: {browse_rel}")

    send_block = re.search(
        r"\bprivate\s+fun\s+requestSendConfirmationRecovery\s*\([^)]*\)[\s\S]*?"
        r"(?=\bprivate\s+fun\s+pageIdentityChanged\s*\()", ai)
    if send_block is None or re.search(
        r"\bRecoveryEventIdentity\s*\(|\brecoveryContext\.request\s*\(", send_block.group(0)
    ):
        issues.append(f"P7 send confirmation still owns duplicate recovery identity logic: {ai_rel}")
    return issues


def android_ui_mutation_bypass_violations(code_by_path: dict[str, str]) -> list[str]:
    """Reject direct Android accessibility writes outside their audited platform outlets.

    Call-site ownership and exact existing counts are deliberately pinned; this is a
    Kotlin lexical guard, not a substitute for runtime authorization tests. Inputs
    are already stripped of comments and literals by inspect_project.
    """
    gateway = "app/src/main/java/com/freedom/adapters/accessibility/android/AndroidAccessibilityGateway.kt"
    coordinator = (
        "gesture/gesture-runtime/src/main/kotlin/com/freedom/application/gesture/"
        "GestureExecutionCoordinator.kt"
    )
    dispatcher = (
        "gesture/gesture-android/src/main/kotlin/com/freedom/adapters/accessibility/"
        "android/AndroidGestureDispatcher.kt"
    )
    # Audited platform owners and call sites; do not allow another direct write
    # merely because it was added inside an otherwise approved file.
    approved_calls = {
        (gateway, "node", "performAction"): 1,
        (gateway, "service", "performGlobalAction"): 2,
        (gateway, "gestureDispatcher", "dispatchGesture"): 2,
        (coordinator, "dispatcher", "dispatchGesture"): 1,
        (dispatcher, "service", "dispatchGesture"): 3,
    }
    call = re.compile(
        r"\b(?P<receiver>[A-Za-z_]\w*(?:\s*\.\s*[A-Za-z_]\w*)*)"
        r"\s*(?:\?\.|!!\s*\.|\.)\s*"
        r"(?P<method>performAction|performGlobalAction|dispatchGesture)\s*\("
    )
    obscured_receiver = re.compile(
        r"\)\s*(?:\?\.|!!\s*\.|\.)\s*"
        r"(?P<method>performAction|performGlobalAction|dispatchGesture)\s*\("
    )
    callable_reference = re.compile(r"::\s*(performAction|performGlobalAction|dispatchGesture)\b")
    counts = {key: 0 for key in approved_calls}
    issues: list[str] = []

    for rel, code in sorted(code_by_path.items()):
        if "/src/main/" not in rel or not rel.endswith(".kt"):
            continue
        for match in call.finditer(code):
            receiver = re.sub(r"\s+", "", match.group("receiver"))
            method = match.group("method")
            key = (rel, receiver, method)
            if key in counts:
                counts[key] += 1
            elif method == "performGlobalAction" and receiver == "accessibilityGateway":
                # Calls through the shared gateway retain its existing runtime checks.
                continue
            else:
                issues.append(
                    f"Direct Android UI mutation bypass: {rel}: {receiver}.{method}"
                )
        # A parenthesized/cast result or a bound method reference must not evade
        # the receiver matcher by changing the shape of the same Android call.
        for match in obscured_receiver.finditer(code):
            issues.append(
                f"Direct Android UI mutation via expression receiver: {rel}: {match.group('method')}"
            )
        for match in callable_reference.finditer(code):
            issues.append(
                f"Direct Android UI mutation callable reference: {rel}: {match.group(1)}"
            )

    for (rel, receiver, method), expected in approved_calls.items():
        actual = counts[(rel, receiver, method)]
        if actual != expected:
            issues.append(
                f"Audited Android UI mutation outlet drift: {rel}: "
                f"{receiver}.{method} expected={expected} actual={actual}"
            )
    return issues


def unified_ui_write_authorization_violations(code_by_path: dict[str, str]) -> list[str]:
    """P4: every business-owned Android mutation must cross the exact lease gate."""
    issues: list[str] = []
    root_rel = "app/src/main/java/com/freedom/app/AppCompositionRoot.kt"
    gateway_rel = "app/src/main/java/com/freedom/adapters/accessibility/android/AndroidAccessibilityGateway.kt"
    core_rel = "core/src/main/kotlin/com/freedom/core/runtime/UiWriteAuthorization.kt"
    root = code_by_path.get(root_rel, "")
    gateway = code_by_path.get(gateway_rel, "")
    core = code_by_path.get(core_rel, "")
    required = {
        root_rel: (
            r"\bval\s+uiWriteAuthorization\s*:\s*UiWriteAuthorization\s+by\s+lazy\b",
            r"\bUiWriteAuthorization\s*\(\s*taskCoordinator\s*=\s*root\.taskCoordinator\b",
            r"\buiWriteAuthorization\s*=\s*uiWriteAuthorization\b",
            r"\buiWriteAuthorization\.permits\s*\(\s*UiWriteScope\.AI_COMPOSER\s*\)",
        ),
        gateway_rel: (
            r"\bprivate\s+val\s+uiWriteAuthorization\s*:\s*UiWriteAuthorization\b",
            r"\buiWriteAuthorization\.permits\s*\(\s*UiWriteScope\.BROWSE_NODE_ACTION\s*\)",
            r"\buiWriteAuthorization\.withAuthorizedWrite\s*\(\s*UiWriteScope\.BROWSE_NODE_ACTION\s*\)",
            r"\buiWriteAuthorization\.permits\s*\(\s*UiWriteScope\.VERIFIED_AI_BACK\s*\)",
            r"\buiWriteAuthorization\.withAuthorizedWrite\s*\(\s*UiWriteScope\.VERIFIED_AI_BACK\s*\)",
            r"\buiWriteAuthorization\.permits\s*\(\s*UiWriteScope\.GLOBAL_NAVIGATION\s*\)",
            r"\buiWriteAuthorization\.withAuthorizedWrite\s*\(\s*UiWriteScope\.GLOBAL_NAVIGATION\s*,\s*submit\s*\)",
            r"\buiWriteAuthorization\.withAuthorizedWrite\s*\(\s*UiWriteScope\.AUTONOMOUS_GESTURE\s*,\s*submit\s*\)",
            r"\buiWriteAuthorization\.withAuthorizedWrite\s*\(\s*UiWriteScope\.AI_COMPOSER\s*\)",
            r"\bprivate\s+fun\s+authorizedGestureDispatch\s*\(",
            r"\bcurrentActiveWindowPackage\s*\(\s*\)\s*==\s*service\.packageName\b",
        ),
        core_rel: (
            r"\bclass\s+UiWriteAuthorization\s*\(",
            r"\bprivate\s+val\s+taskCoordinator\s*:\s*TaskCoordinator\b",
            r"\bprivate\s+val\s+currentRunGeneration\s*:\s*\(\)\s*->\s*Long\b",
            r"\bprivate\s+val\s+activeRunMode\s*:\s*\(\)\s*->\s*RunMode\?",
            r"\blease\.runGeneration\s*!=\s*runGeneration\b",
            r"\btaskCoordinator\.withCurrentLease\s*\(\s*lease\s*\)",
            r"\bUiWriteScope\.AI_COMPOSER\s*->\s*privateMessages\b",
            r"\bUiWriteScope\.BROWSE_NODE_ACTION\s*->\s*browse\b",
        ),
    }
    for rel, patterns in required.items():
        code = code_by_path.get(rel, "")
        for pattern in patterns:
            if not re.search(pattern, code):
                issues.append(f"P4 UI mutation authorization missing: {rel}: {pattern}")
    if "isUiOperationOwned" in gateway:
        issues.append("P4 gateway still uses broad any-task authorization")
    if re.search(r"\broot\.taskCoordinator\.activeTaskId\s*\(\s*\)\s*(!=|==)", root):
        issues.append("P4 composition still authorizes UI writes by any active task ID")
    if re.search(r"\bperformCurrentInputAction\s*\([^)]*\)\s*:\s*Boolean\s*=\s*when\b", gateway):
        issues.append("P4 current input focus/set-text bypasses the exact lease gate")
    if re.search(
        r"\boverride\s+fun\s+dispatchGesture\s*\([^)]*\)[\s\S]*?"
        r"=\s*(?:gestureDispatcher\.dispatchGesture|if\s*\(\s*isUiOperationOwned\s*\()",
        gateway
    ):
        issues.append("P4 gesture dispatch bypasses the final authorization outlet")
    # Explicit user calibration and manual-review lock-screen safety are not autonomous
    # business actions. The only permitted direct Android platform mutation owners follow.
    for rel, code in code_by_path.items():
        if not rel.startswith("app/src/main/") or not rel.endswith(".kt"):
            continue
        if rel != gateway_rel and re.search(
            r"\bservice\.performGlobalAction\s*\(|\b(?:node|target)\.performAction\s*\(", code
        ):
            issues.append(f"P4 direct Android node/global mutation bypass: {rel}")
    issues.extend(android_ui_mutation_bypass_violations(code_by_path))
    return issues


def unified_business_event_entry_violations(code_by_path: dict[str, str]) -> list[str]:
    """P1 production wiring: all business notifications cross one normalized entry."""
    issues: list[str] = []
    root_rel = "app/src/main/java/com/freedom/app/AppCompositionRoot.kt"
    pipeline_rel = "app/src/main/java/com/freedom/adapters/accessibility/android/AndroidAccessibilityEventPipeline.kt"
    service_rel = "app/src/main/java/com/freedom/accessibility/FreedomAccessibilityService.kt"
    contract_rel = "core/src/main/kotlin/com/freedom/core/accessibility/api/UnifiedAccessibilityEntry.kt"
    root = code_by_path.get(root_rel, "")
    pipeline = code_by_path.get(pipeline_rel, "")
    service = code_by_path.get(service_rel, "")
    contract = code_by_path.get(contract_rel, "")
    required = {
        root_rel: (root, (
            r"\bval\s+unifiedAccessibilityEntry\s*:\s*UnifiedAccessibilityEntry\b",
            r"unifiedAccessibilityEntry\s*=\s*unifiedAccessibilityEntry",
            r"\bUnifiedAccessibilityEvent\.TargetApplication\b",
            r"\bUnifiedAccessibilityEvent\.GlobalTouch\b",
            r"\bUnifiedAccessibilityEvent\.ActivityTransition\b",
            r"\bUnifiedAccessibilityEvent\.NavigationObservation\b",
        )),
        pipeline_rel: (pipeline, (
            r"\bprivate\s+val\s+unifiedAccessibilityEntry\s*:\s*UnifiedAccessibilityEntry\s*[,)]",
            r"\bunifiedAccessibilityEntry\.accept\s*\(\s*UnifiedAccessibilityEvent\.TargetApplication",
            r"\bunifiedAccessibilityEntry\.accept\s*\(\s*UnifiedAccessibilityEvent\.GlobalTouch",
            r"\bunifiedAccessibilityEntry\.accept\s*\(\s*UnifiedAccessibilityEvent\.ActivityTransition",
        )),
        service_rel: (service, (
            r"\baccessibilityEventForwarder\.accept\s*\(\s*event\s*\)",
            r"\bunifiedAccessibilityEntry\.accept\s*\(\s*UnifiedAccessibilityEvent\.NavigationObservation",
        )),
        contract_rel: (contract, (
            r"\bsealed\s+interface\s+UnifiedAccessibilityEvent\b",
            r"\bfun\s+accept\s*\(\s*event\s*:\s*UnifiedAccessibilityEvent\s*\)",
        )),
    }
    for rel, (code, expressions) in required.items():
        if not code:
            issues.append(f"P1 unified entry source missing: {rel}")
        for expression in expressions:
            if not re.search(expression, code):
                issues.append(f"P1 unified business entry wiring missing: {rel}: {expression}")
    if re.search(r"\baiReplyObservationRuntime\.(?:onSelfNavigationAttached|onUnmatchedNavigationObserved)\s*\(", service):
        issues.append(f"P1 window callback directly bypasses unified business entry: {service_rel}")
    if re.search(r"\bdispatch(?:TargetApplicationEvent|GlobalTouchEvent|ActivityTransition)\s*=\s*"
                 r"runSessionCoordinator::", root):
        issues.append(f"P1 pipeline still binds direct business dispatch callbacks: {root_rel}")
    return issues


def unified_accessibility_entry_violations(code_by_path: dict[str, str]) -> list[str]:
    """Require every production AccessibilityService callback to use the one event gateway."""
    issues: list[str] = []
    production = {
        rel: code for rel, code in code_by_path.items()
        if "/src/main/" in rel and rel.endswith(".kt")
    }
    callback_declarations = sorted(
        rel for rel, code in production.items()
        if re.search(r"\boverride\s+fun\s+onAccessibilityEvent\s*\(", code)
    )
    service_rel = "app/src/main/java/com/freedom/accessibility/FreedomAccessibilityService.kt"
    if callback_declarations != [service_rel]:
        issues.append(
            "AccessibilityService callback must be declared exactly once at "
            f"{service_rel}; found {callback_declarations}"
        )

    service_code = production.get(service_rel, "")
    callback = re.search(
        r"override\s+fun\s+onAccessibilityEvent\s*\([^)]*\)\s*\{(?P<body>[\s\S]*?)\n\s*\}",
        service_code,
    )
    if callback is None:
        issues.append(f"Unified Accessibility entry is missing from {service_rel}")
    else:
        body = callback.group("body")
        if not re.search(r"\baccessibilityEventForwarder\.accept\s*\(", body):
            issues.append(
                "FreedomAccessibilityService.onAccessibilityEvent must only forward to "
                "AndroidAccessibilityEventForwarder.accept"
            )
        if re.search(r"\b(?:pipeline|pageObservationCoordinator|onTargetApplicationEvent|onGlobalTouchEvent)\b", body):
            issues.append(
                "FreedomAccessibilityService.onAccessibilityEvent must not bypass the unified forwarder"
            )
    return issues


def checkpoint_recovery_boundary_violations(code_by_path: dict[str, str]) -> list[str]:
    """Require checkpoint restoration to pass the read-only page-verification gate."""
    issues: list[str] = []
    runtime_rel = "business/src/main/kotlin/com/freedom/features/accountbrowse/AccountBrowseRuntime.kt"
    recovery_rel = "business/src/main/kotlin/com/freedom/features/accountbrowse/DynamicPageRecovery.kt"
    workflow_rel = "app/src/main/java/com/freedom/adapters/accessibility/android/AndroidAccountBrowseWorkflow.kt"
    runtime = code_by_path.get(runtime_rel, "")
    recovery = code_by_path.get(recovery_rel, "")
    workflow = code_by_path.get(workflow_rel, "")

    required = {
        runtime_rel: (runtime, ("restoreFromCheckpoint", "beginCheckpointVerification", "observeDynamicFeedFingerprint")),
        recovery_rel: (recovery, ("CheckpointLoaded", "VerificationStarted", "ResumeRequested", "ResumeCompleted")),
        workflow_rel: (workflow, ("hasPendingCheckpointVerification", "beginCheckpointVerification", "observeDynamicFeedFingerprint")),
    }
    for rel, (code, tokens) in required.items():
        if not code:
            issues.append(f"Checkpoint recovery boundary source is missing: {rel}")
            continue
        missing = [token for token in tokens if token not in code]
        if missing:
            issues.append(f"Checkpoint recovery gate is incomplete in {rel}: missing {missing}")

    if runtime and re.search(r"checkpointRepository\?\.load\s*\(\)", runtime):
        if not re.search(r"stateMachine\.transitionTo\s*\(\s*AccountBrowsePhase\.PAUSED\s*\)", runtime):
            issues.append("AccountBrowse checkpoint load must enter PAUSED before verification")
        if not re.search(r"dynamicPageRecovery\.restoreFromCheckpoint\s*\(", runtime):
            issues.append("AccountBrowse checkpoint load must activate DynamicPageRecovery")
    if workflow and "startRestoredCheckpointVerification" in workflow:
        start = re.search(
            r"private\s+fun\s+startRestoredCheckpointVerification\s*\(\)\s*\{(?P<body>[\s\S]*?)\n\s*\}",
            workflow,
        )
        if start and not re.search(r"beginCheckpointVerification\s*\(\)", start.group("body")):
            issues.append("Restored checkpoint workflow must begin read-only verification before scheduling")
    return issues


def recovery_transaction_boundary_violations(code_by_path: dict[str, str]) -> list[str]:
    """Hard guardrails for the shared recovery transaction boundary."""
    issues: list[str] = []
    state_machine_paths = {
        rel for rel in code_by_path
        if rel.endswith("/UnifiedRecoveryTransactionStateMachine.kt")
        or ("/recovery/" in rel and rel.endswith(".kt"))
    }
    adapter_paths = {
        rel for rel in code_by_path
        if "/adapters/" in rel and "/src/main/" in rel and rel.endswith(".kt")
    }
    business_tokens = re.compile(
        r"\b(?:Momo|MomoSocialAdapter|ChatPageSnapshot|ChatListCandidateSnapshot|"
        r"userlist_item_|hilist_tv_|message_list|receivedGreetings|privateMessages)\b"
    )
    for rel in sorted(state_machine_paths):
        code = code_by_path[rel]
        imports = re.findall(r"^\s*import\s+([A-Za-z_][A-Za-z0-9_.]*)", code, re.MULTILINE)
        leaked = [name for name in imports if name.startswith((
            "com.freedom.adapters.", "com.freedom.data.", "android.", "androidx."
        ))]
        if leaked:
            issues.append(f"Unified recovery state machine imports adapter/platform/data implementation: {rel}: {sorted(set(leaked))}")
        if business_tokens.search(code):
            issues.append(f"Unified recovery state machine contains business-specific page/traversal token: {rel}")
    bypass = re.compile(
        r"\b(?:UnifiedRecoveryTransactionStateMachine|RecoveryTransactionStateMachine)\s*\(|"
        r"\.\b(?:forceResume|resumeWithoutVerification|transitionRecoveryState)\s*\("
    )
    for rel in sorted(adapter_paths):
        if bypass.search(code_by_path[rel]):
            issues.append(f"Adapter bypasses the unified recovery transaction state machine: {rel}")
    return issues


def automatic_recovery_hard_boundary_violations(code_by_path: dict[str, str]) -> list[str]:
    """Prevent legacy manual recovery and regression of the three shared checkpoint gates.

    inspect_project passes Kotlin with comments/literals masked; this is a source-level
    contract, not a substitute for the behavioral recovery/gesture tests or Kotlin CI.
    """
    issues: list[str] = []

    legacy_files = {
        "TieredManualHandoffQuietPeriod.kt",
        "AccountBrowseManualHandoffCoordinator.kt",
    }
    legacy_symbols = re.compile(
        r"\b(?:TieredManualHandoffQuietPeriod|AccountBrowseManualHandoffCoordinator|"
        r"allowManualResume|cancelManualTouchResume|scheduleManualTouchResume|"
        r"manualRecoveryLock|manualHandoffLock|manualResumeRequired|"
        r"manualRecoveryRequired|recoveryRetryExhausted)\b"
    )
    for rel, code in sorted(code_by_path.items()):
        if "/src/main/" not in rel or not rel.endswith(".kt"):
            continue
        if Path(rel).name in legacy_files or legacy_symbols.search(code):
            issues.append(f"Legacy manual recovery is forbidden in production: {rel}")

    scheduler_rel = "business/src/main/kotlin/com/freedom/features/recovery/RecoveryScheduler.kt"
    scheduler = code_by_path.get(scheduler_rel)
    if scheduler is None:
        issues.append(f"Shared recovery scheduler source is missing: {scheduler_rel}")
    else:
        backoff = re.search(r"\bBACKOFF_DELAYS_MS\s*=\s*longArrayOf\s*\(([^)]*)\)", scheduler)
        stages = ([int(number.replace("_", "")) for number in
                   re.findall(r"\b(\d[\d_]*)L?\b", backoff.group(1))]
                  if backoff else [])
        if stages != [500, 1000, 2000, 5000, 10000, 30000]:
            issues.append(f"Shared recovery scheduler lost the bounded non-terminal backoff: {scheduler_rel}")
        touch_delay = re.search(
            r"\bTOUCH_ACTIVE_OBSERVATION_DELAY_MS\s*=\s*(\d[\d_]*)L?\b", scheduler
        )
        if touch_delay is None or not 0 < int(touch_delay.group(1).replace("_", "")) <= 30000:
            issues.append(f"Shared recovery touch observation must remain bounded by 30s: {scheduler_rel}")
        scheduler_gates = {
            "read-only observation": r"\bonReadOnlyObservation\s*\(",
            "fresh recovery plan": r"\bfun\s+begin\s*\(",
            "service disconnect isolation": r"\bfun\s+suspendForServiceDisconnect\s*\(",
            "persisted plan restore": r"\bfun\s+restorePersisted\s*\(",
            "touch start cadence": r"\bfun\s+onUserInteractionStarted\s*\(",
            "automatic touch end cadence": r"\bfun\s+onUserInteractionEnded\s*\(",
            "fast retry after touch": r"scheduleNext\s*\(\s*current\s*,\s*INITIAL_BACKOFF_STAGE\s*\)",
            "stale callback token check": r"current\.callbackToken\s*!=\s*token",
            "run generation identity": r"identity\.runGeneration\s*==\s*other\.runGeneration",
            "checkpoint identity": r"identity\.checkpointId\s*==\s*other\.checkpointId",
            "stale observation acceptance": r"other\.observationGeneration\s*>=\s*identity\.observationGeneration",
            "explicit user stop": r"\bfun\s+stopByUser\s*\(",
            "verified completion": r"\bfun\s+completeRecovery\s*\(",
        }
        for label, pattern in scheduler_gates.items():
            if not re.search(pattern, scheduler):
                issues.append(f"Shared recovery scheduler lost {label}: {scheduler_rel}")

    common_verifier_rel = (
        "business/src/main/kotlin/com/freedom/features/recovery/"
        "RecoveryCheckpointVerificationCoordinator.kt"
    )
    common_verifier = code_by_path.get(common_verifier_rel)
    if common_verifier is None:
        issues.append(f"Shared checkpoint verifier source is missing: {common_verifier_rel}")
    else:
        common_requirements = {
            "two stable snapshots": r"stableVerificationSamples\s*<\s*UnifiedRecoveryTransactionStateMachine\.REQUIRED_STABLE_SAMPLES",
            "stale observation generation": r"observationGeneration\s*<=\s*lastObservationGeneration",
            "fresh evidence matching": r"\bevidenceMatches\s*\(",
            "target package evidence": r"evidence\.packageName\s*==\s*checkpoint\.targetPackage",
            "page fingerprint evidence": r"evidence\.pageFingerprint\s*==\s*checkpoint\.pageFingerprint",
            "run generation evidence": r"evidence\.runGeneration\s*==\s*checkpoint\.runGeneration",
            "resume proof": r"RecoveryCheckpointVerificationResult\.ResumeReady\s*\(",
            "explicit completed resume": r"\bfun\s+completeResume\s*\(",
            "verified scheduler completion": r"scheduler\.completeRecovery\s*\(",
        }
        for label, pattern in common_requirements.items():
            if not re.search(pattern, common_verifier):
                issues.append(f"Shared checkpoint verifier lost {label}: {common_verifier_rel}")

    # Keep the existing page fingerprints, business checkpoints and ownership checks
    # in their respective owners; require that every restored branch goes through the
    # common verifier rather than regressing to the old local-only stable-sample gate.
    routes = {
        "business/src/main/kotlin/com/freedom/features/accountbrowse/AccountBrowseRuntime.kt": {
            "browse checkpoint adapter": r"AccountBrowseRecoveryCheckpointAdapter\s*\(",
            "browse checkpoint mapping": r"checkpointAdapter\.adapt\s*\(",
            "browse checkpoint verification": r"dynamicPageRecovery\.observeRestoredCheckpoint\s*\(",
        },
        "business/src/main/kotlin/com/freedom/features/accountbrowse/DynamicPageRecovery.kt": {
            "shared browse verifier": r"RecoveryCheckpointVerificationCoordinator\s*\(",
            "read-only browse scheduler": r"scheduler\s*=\s*recoveryScheduler",
            "fresh browse evidence": r"requireNotNull\s*\(\s*checkpointVerifier\s*\)\.observe\s*\(",
            "browse resume proof": r"RecoveryCheckpointVerificationResult\.ResumeReady",
            "browse resume completion": r"requireNotNull\s*\(\s*checkpointVerifier\s*\)\.completeResume\s*\(",
        },
        "business/src/main/kotlin/com/freedom/features/aireply/MessageListCheckpointVerification.kt": {
            "message checkpoint adapter": r"PrivateMessagesRecoveryCheckpointAdapter\s*\(",
            "message checkpoint mapping": r"adapter\.adapt\s*\(",
            "shared message verifier": r"RecoveryCheckpointVerificationCoordinator\s*\(",
            "read-only message scheduler": r"RecoveryScheduler\s*\(",
            "fresh message evidence": r"requireNotNull\s*\(\s*verifier\s*\)\.observe\s*\(",
            "message resume completion": r"current\.completeResume\s*\(",
        },
        "business/src/main/kotlin/com/freedom/features/aireply/ReceivedGreetingsCheckpointVerification.kt": {
            "greetings checkpoint adapter": r"ReceivedGreetingsRecoveryCheckpointAdapter\s*\(",
            "greetings checkpoint mapping": r"adapter\.adapt\s*\(",
            "shared greetings verifier": r"RecoveryCheckpointVerificationCoordinator\s*\(",
            "read-only greetings scheduler": r"RecoveryScheduler\s*\(",
            "fresh greetings evidence": r"requireNotNull\s*\(\s*verifier\s*\)\.observe\s*\(",
            "greetings resume completion": r"active\.completeResume\s*\(",
        },
        "app/src/main/java/com/freedom/app/AppCompositionRoot.kt": {
            "message checkpoint repository injection": r"privateMessagesCheckpointRepository\s*=\s*root\.privateMessagesCheckpointRepository",
            "greetings checkpoint repository injection": r"receivedGreetingsCheckpointRepository\s*=\s*root\.sayHiTraversalCheckpointRepository",
        },
        "business/src/main/kotlin/com/freedom/features/aireply/AiReplyObservationRuntime.kt": {
            "message verifier invocation": r"messageListCheckpointVerifier\.observe\s*\(",
            "message verified completion": r"messageListCheckpointVerifier\.completeResume\s*\(",
            "greetings verifier invocation": r"receivedGreetingsCheckpointVerifier\.observe\s*\(",
            "greetings verified completion": r"receivedGreetingsCheckpointVerifier\.completeResume\s*\(",
            "greetings adapter confirmation": r"\bval\s+synchronized\s*=\s*receivedGreetingsTaskFlow\.synchronizeRecoveryConfirmation\s*\(",
            "task ownership gate": r"claimTaskOwnership\s*\(",
        },
    }
    for rel, requirements in routes.items():
        code = code_by_path.get(rel)
        if code is None:
            issues.append(f"Shared checkpoint route source is missing: {rel}")
            continue
        for label, pattern in requirements.items():
            if not re.search(pattern, code):
                issues.append(f"Shared checkpoint route lost {label}: {rel}")

    ai_rel = "business/src/main/kotlin/com/freedom/features/aireply/AiReplyObservationRuntime.kt"
    ai_code = code_by_path.get(ai_rel, "")
    if ai_code and len(re.findall(r"recoveryContext\.acknowledgeVerifiedCheckpoint\s*\(", ai_code)) < 2:
        issues.append(f"AI message and greetings recovery must both acknowledge shared proof: {ai_rel}")
    if ai_code:
        greetings_confirmation = re.search(
            r"\bval\s+synchronized\s*=\s*receivedGreetingsTaskFlow\.synchronizeRecoveryConfirmation\s*\(",
            ai_code,
        )
        task_claim = re.search(r"\bclaimTaskOwnership\s*\(\s*targetTaskId\b", ai_code)
        if (greetings_confirmation is not None and task_claim is not None and
                greetings_confirmation.start() >= task_claim.start()):
            issues.append(f"Greetings adapter confirmation must precede UI ownership claim: {ai_rel}")

    return issues


def architecture_advisories(root: Path) -> list[str]:
    """Report high-LOC production classes as review signals, never automatic violations."""
    root = root.resolve()
    advisories: list[str] = []
    for path in kotlin_sources(root):
        if "/src/test/" in path.as_posix() or "/src/androidTest/" in path.as_posix():
            continue
        source = path.read_text(encoding="utf-8")
        lines = len(source.splitlines())
        if lines >= 1000:
            advisories.append(f"{relative(path, root)}: {lines} lines; review responsibility cohesion before splitting")
    return advisories


def main(argv: Iterable[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[2], help="Freedom project root")
    parser.add_argument("--show-advisories", action="store_true", help="show large-source review signals")
    args = parser.parse_args(argv)

    issues = inspect_project(args.root)
    if args.show_advisories:
        advisories = architecture_advisories(args.root)
        print(f"ARCHITECTURE ADVISORIES ({len(advisories)})")
        for advisory in advisories:
            print(f" - {advisory}")
    if issues:
        print(f"ARCHITECTURE CHECK FAILED ({len(issues)} issue(s))")
        for issue in issues:
            print(f" - {issue}")
        return 1

    print("ARCHITECTURE CHECK PASSED")
    print(f" - Gradle modules: {len(EXPECTED_MODULES)}; dependency directions: valid")
    print(" - GestureApi: one public declaration; Feature consumers do not access Runtime internals")
    print(" - Validation tokens: Runtime-only; issuance owned by GesturePolicyValidator")
    print(" - GestureCommand/GestureSpec construction: compiler-owned")
    print(" - Android dispatch: confined to AndroidGestureDispatcher")
    print(" - Recovery transaction: adapters cannot bypass it; state machine cannot depend on business page fields")
    print(" - P0 ownership baseline: authority declarations, construction sites, and legacy aliases pinned")
    print(" - P1 accessibility: target/touch/Activity/navigation notifications use one business entry")
    print(" - P2 observation: one injected scheduler, mode-isolated channels, task decisions owned by runtimes")
    print(" - P3 task lifecycle: process run clock synchronously revokes shared task/UI leases")
    print(" - P4 UI authorization: exact task/generation/mode gate owns every automated write outlet")
    print(" - P5 recovery adapters: shared task-scoped registry, common interface, no local route fallback")
    print(" - P6 recovery transaction: single process admission manager with isolated route verification lanes")
    print(" - P7 bypass cleanup: mandatory event gateway, task-scoped recovery-only registry, managed fingerprint verification")
    print(" - Automatic recovery: legacy manual handoff forbidden; three checkpoint routes use shared verification")
    print(" - Recovery scheduler: bounded non-terminal backoff, touch reset, stale-callback and user-stop gates")
    print(" - OCR runtime/models: confined to the separately installed :ocr-plugin APK")
    print(" - Host/plugin AIDL contract: identical; host does not package ONNX Runtime or OpenCV")
    print(" - AGENTS source integrity and Feature/Adapter/Data/UI ownership boundaries: valid")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
