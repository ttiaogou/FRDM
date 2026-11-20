#!/usr/bin/env python3
"""Static architecture guardrails for the Freedom modular Android project.

Uses only the Python standard library so it can run before Gradle/Android SDK setup.
This is a source-level guard, not a substitute for Kotlin compilation or Gradle dependency
resolution; those are exercised by the Android build job.
"""
from __future__ import annotations

import argparse
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

    # 6) AGENTS source integrity: the eight ordered fragments must reconstruct the archive byte-for-byte.
    agent_archive = root / "docs/agent/source/AGENTS.original.md"
    agent_parts_dir = root / "docs/agent/parts"
    if agent_archive.is_file() and agent_parts_dir.is_dir():
        parts = sorted(agent_parts_dir.glob("*.md"))
        expected_names = [f"{index:02d}" for index in range(8)]
        actual_prefixes = [part.name[:2] for part in parts]
        if actual_prefixes != expected_names:
            issues.append(
                "AGENTS integrity: expected exactly ordered parts 00-07; "
                f"found {actual_prefixes}"
            )
        elif b"".join(part.read_bytes() for part in parts) != agent_archive.read_bytes():
            issues.append(
                "AGENTS integrity: concatenated docs/agent/parts/00-07 differs byte-for-byte "
                "from docs/agent/source/AGENTS.original.md; do not auto-rewrite protected specs"
            )
    else:
        issues.append("AGENTS integrity: source archive or docs/agent/parts is missing")

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

    # 11) Final hard boundaries for the unified accessibility/recovery architecture.
    issues.extend(final_architecture_boundary_violations(code_by_path))

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
    print(" - OCR runtime/models: confined to the separately installed :ocr-plugin APK")
    print(" - Host/plugin AIDL contract: identical; host does not package ONNX Runtime or OpenCV")
    print(" - AGENTS source integrity and Feature/Adapter/Data/UI ownership boundaries: valid")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
