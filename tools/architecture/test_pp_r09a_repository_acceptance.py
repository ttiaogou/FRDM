"""R09-A: whole-checkout scan coverage and domain authority integration evidence.

The existing verifier remains authoritative for forbidden dependencies, UI writes,
and P0-P7 authority counts. These tests extend its coverage evidence, not replace it.
Static declarations and edges are not a compiled call graph or device behavior proof.
"""
import fnmatch
import re
import unittest
from pathlib import Path
from tools.architecture.test_pp_r00_freeze import git_blob
from tools.architecture.verify_boundaries import (
    ALLOWED_PROJECT_DEPENDENCIES, EXPECTED_MODULES, kotlin_sources,
    mask_kotlin_non_code, project_dependencies, strip_gradle_comments,
)

ROOT = Path(__file__).resolve().parents[2]
MODULES = {
  ":app": "app",
  ":business": "business",
  ":core": "core",
  ":gesture-api": "gesture/gesture-api",
  ":gesture-core": "gesture/gesture-core",
  ":gesture-runtime": "gesture/gesture-runtime",
  ":gesture-android": "gesture/gesture-android",
  ":ocr-plugin-api": "ocr-plugin-api",
  ":ocr-plugin": "ocr-plugin"
}
OWNERS = {
  "FreedomApplication": "app/src/main/java/com/freedom/app/FreedomApplication.kt",
  "AppCompositionRoot": "app/src/main/java/com/freedom/app/AppCompositionRoot.kt",
  "RunGenerationClock": "app/src/main/java/com/freedom/application/runtime/RunSessionCoordinator.kt",
  "RunSessionCoordinator": "app/src/main/java/com/freedom/application/runtime/RunSessionCoordinator.kt",
  "TaskExecutionController": "core/src/main/kotlin/com/freedom/core/runtime/TaskExecutionController.kt",
  "UnifiedRecoveryTransactionManager": "business/src/main/kotlin/com/freedom/features/recovery/UnifiedRecoveryTransactionManager.kt",
  "RecoveryContext": "business/src/main/kotlin/com/freedom/features/recovery/RecoveryContext.kt",
  "PrivateMessagesTraversalTaskExecutor": "business/src/main/kotlin/com/freedom/features/aireply/PrivateMessagesTraversalTaskExecutor.kt",
  "ReceivedGreetingsTaskFlowController": "business/src/main/kotlin/com/freedom/features/aireply/ReceivedGreetingsTaskFlowController.kt",
  "MomoSayHiTraversalState": "app/src/main/java/com/freedom/adapters/social/momo/MomoSayHiTraversalState.kt",
  "ChatMessageReadCoordinator": "business/src/main/kotlin/com/freedom/features/aireply/ChatMessageReadCoordinator.kt",
  "AiReplyTaskOwnership": "business/src/main/kotlin/com/freedom/features/aireply/AiReplyTaskOwnership.kt",
  "AccountBrowseSessionStateOwner": "business/src/main/kotlin/com/freedom/features/accountbrowse/AccountBrowseSessionState.kt",
  "AccountBrowseRuntime": "business/src/main/kotlin/com/freedom/features/accountbrowse/AccountBrowseRuntime.kt",
  "AiReplyDraftCoordinator": "business/src/main/kotlin/com/freedom/features/aireply/ai/AiReplyDraftCoordinator.kt",
  "LocalConversationRepository": "app/src/main/java/com/freedom/data/database/LocalConversationRepository.kt",
  "LocalReplyTaskRepository": "app/src/main/java/com/freedom/data/database/LocalReplyTaskRepository.kt",
  "RunDiagnosticsManager": "app/src/main/java/com/freedom/data/RunDiagnosticsManager.kt",
  "OcrManager": "app/src/main/java/com/freedom/ocr/OcrManager.kt",
  "GestureExecutionCoordinator": "gesture/gesture-runtime/src/main/kotlin/com/freedom/application/gesture/GestureExecutionCoordinator.kt"
}
EDGES = {
  "app/src/main/java/com/freedom/app/FreedomApplication.kt": [
    "compositionRoot = AppCompositionRoot(this)"
  ],
  "app/src/main/java/com/freedom/app/AppCompositionRoot.kt": [
    "TaskCoordinator(uiOperationOwnershipCoordinator)",
    "currentRunGeneration = runGenerationClock::current",
    "generationClock = root.runGenerationClock",
    "taskCoordinator = root.taskCoordinator",
    "recoveryTransactionManager = root.recoveryTransactionManager",
    "RunMode.ACCOUNT_BROWSE to accountBrowseRuntimeAdapter",
    "RunMode.AI_REPLY to AiReplyModeRuntimeAdapter(",
    "runSessionCoordinator.dispatchTargetApplicationEvent(notification.event)",
    "runSessionCoordinator.dispatchGlobalTouchEvent(notification.event)",
    "runSessionCoordinator.dispatchActivityTransition(notification.activityClass)",
    "replyTaskRepository = root.replyTaskRepository",
    "replySegmentTaskRepository = root.replySegmentTaskRepository",
    "policyRegistry = root.aiReplyPolicyRegistry"
  ],
  "app/src/main/java/com/freedom/accessibility/FreedomAccessibilityService.kt": [
    "accessibilityEventForwarder.accept(event)"
  ],
  "app/src/main/java/com/freedom/adapters/accessibility/android/AndroidAccessibilityEventForwarder.kt": [
    "if (runMode == null) pipeline.processWithoutRuntime(event)",
    "else pipeline.process(event, runMode)"
  ],
  "app/src/main/java/com/freedom/adapters/accessibility/android/AndroidAccessibilityEventPipeline.kt": [
    "unifiedAccessibilityEntry.accept(UnifiedAccessibilityEvent.TargetApplication(normalizedEvent))",
    "unifiedAccessibilityEntry.accept(UnifiedAccessibilityEvent.GlobalTouch(routedTouchEvent))"
  ],
  "app/src/main/java/com/freedom/application/runtime/RunSessionCoordinator.kt": [
    "private var generation = 0L",
    "private var pageRuntime: PageRuntimeSessionState? = null",
    "route.lifecycle != PageRuntimeLifecycle.READY",
    "route.runGeneration != generationClock.current()",
    "lease.taskId != route.taskId || lease.runGeneration != route.runGeneration",
    "tasks.withOwnershipBarrier(action)",
    "mode?.let { runtimes[it]?.onTargetApplicationEvent(event) }"
  ],
  "core/src/main/kotlin/com/freedom/core/runtime/TaskCoordinator.kt": [
    "private var activeLease: TaskLease? = null",
    "uiOperationOwnership.tryAcquire(taskId, runGeneration)",
    "uiOperationOwnership.transfer(current.uiOperationLease, nextTaskId)",
    "uiOperationOwnership.isCurrent(lease.uiOperationLease)"
  ],
  "core/src/main/kotlin/com/freedom/core/runtime/UiOperationOwnershipCoordinator.kt": [
    "private var activeLease: UiOperationLease? = null"
  ],
  "core/src/main/kotlin/com/freedom/core/runtime/TaskExecutionController.kt": [
    "private var lease: TaskLease? = null",
    "taskCoordinator.tryAcquire(taskId, generation)",
    "taskCoordinator.transfer(current, taskId)"
  ],
  "app/src/main/java/com/freedom/adapters/runtime/android/AiReplyModeRuntimeAdapter.kt": [
    "override fun onTargetApplicationEvent(event: AccessibilityEventEnvelope) = runtime().schedule(event)"
  ],
  "app/src/main/java/com/freedom/adapters/runtime/android/AccountBrowseModeRuntimeAdapter.kt": [
    "private val taskExecutionController = TaskExecutionController(",
    "workflow().onTargetAppEvent(platformType, event.eventTimeMillis)"
  ],
  "app/src/main/java/com/freedom/adapters/accessibility/android/AndroidAccountBrowseWorkflow.kt": [
    "private val accountBrowseRuntime by lazy(LazyThreadSafetyMode.NONE)",
    "AccountBrowseRuntime("
  ],
  "business/src/main/kotlin/com/freedom/features/aireply/AiReplyObservationRuntime.kt": [
    "private val taskOwnership = AiReplyTaskOwnership(",
    "private val receivedGreetingsTaskFlow = ReceivedGreetingsTaskFlowController(",
    "private val privateMessagesTraversalTaskExecutor = PrivateMessagesTraversalTaskExecutor()",
    "inspectPage = { chatPagePort.inspectChatPage() }",
    "sharedManager = recoveryTransactionManager"
  ],
  "business/src/main/kotlin/com/freedom/features/accountbrowse/AccountBrowseRuntime.kt": [
    "private val sessionStateOwner = AccountBrowseSessionStateOwner()",
    "val session: AccountBrowseSessionStatePort = sessionStateOwner.port",
    "private val stateMachine = AccountBrowseStateMachine()"
  ],
  "business/src/main/kotlin/com/freedom/features/aireply/PrivateMessagesTraversalTaskExecutor.kt": [
    "var currentSessionBinding: CurrentSessionBinding? = null",
    "var currentSessionSendConfirmed = false"
  ],
  "business/src/main/kotlin/com/freedom/features/aireply/ReceivedGreetingsTaskFlowController.kt": [
    "var greetingChatBinding: GreetingChatBinding? = null",
    "var confirmedGreetingSend: ConfirmedGreetingSend? = null"
  ],
  "app/src/main/java/com/freedom/adapters/social/momo/MomoSayHiTraversalState.kt": [
    "private val processedRowKeys = LinkedHashSet<String>()",
    "private val deferredRowKeys = LinkedHashSet<String>()"
  ],
  "business/src/main/kotlin/com/freedom/features/aireply/ChatMessageReadCoordinator.kt": [
    "private var requestGeneration = 0L",
    "var draftVisitToken = java.util.UUID.randomUUID().toString()"
  ],
  "business/src/main/kotlin/com/freedom/features/aireply/AiReplyTaskOwnership.kt": [
    "private val delegate = TaskExecutionController(",
    "val privateSliceQuota = AiReplyTaskSliceQuota()",
    "val greetingSliceQuota = AiReplyTaskSliceQuota()"
  ],
  "business/src/main/kotlin/com/freedom/features/recovery/UnifiedRecoveryTransactionManager.kt": [
    "private val transactions = LinkedHashMap<LaneKey, TransactionSnapshot>()"
  ],
  "business/src/main/kotlin/com/freedom/features/recovery/RecoveryEventDispatcher.kt": [
    "sharedManager?.dispatch(",
    "manager.onUnboundEvent(RecoveryTransactionEvent.VerificationSample(stable = false))"
  ],
  "business/src/main/kotlin/com/freedom/features/aireply/ai/AiReplyDraftCoordinator.kt": [
    "private val pending = AtomicBoolean(false)",
    "private val generation = AtomicLong(0L)",
    "createGateway(configuration).decide("
  ],
  "business/src/main/kotlin/com/freedom/features/aireply/ai/EngineBackedAiDecisionGateway.kt": [
    "private val engine = AiReplyDecisionEngine(",
    "return engine.decide("
  ],
  "business/src/main/kotlin/com/freedom/features/aireply/ai/AiReplyDecisionEngine.kt": [
    "modelProvider.generate(request)"
  ],
  "app/src/main/java/com/freedom/data/database/LocalConversationRepository.kt": [
    "private var contacts = loadContacts()",
    "private var messages = loadMessages()"
  ],
  "app/src/main/java/com/freedom/data/database/LocalReplyTaskRepository.kt": [
    "private val processLock = Any()",
    "override fun tryClaim("
  ],
  "app/src/main/java/com/freedom/data/RunDiagnosticsManager.kt": [
    "private var activeRun: ActiveRun? = null",
    "private var finalizingRun: ActiveRun? = null",
    "private var finalizationPending: Boolean = false"
  ],
  "app/src/main/java/com/freedom/ocr/OcrManager.kt": [
    "private val activeRequestLock = Any()",
    "private var activeRequestId: String? = null",
    "private var activePlugin: BoundOcrPlugin? = null"
  ],
  "gesture/gesture-runtime/src/main/kotlin/com/freedom/application/gesture/GesturePolicyPipeline.kt": [
    "commandCompiler.compile(validatedRequest, policyDecision)",
    "validator.validateAndApprove(command)",
    "return executor.execute(approvedCommand, listener)"
  ],
  "gesture/gesture-runtime/src/main/kotlin/com/freedom/application/gesture/GestureExecutionCoordinator.kt": [
    "private val pendingExecutions = ArrayDeque<ActiveExecution>()",
    "taskCoordinator?.withCurrentLease(taskLease, submitToPlatform)",
    "dispatcher.dispatchGesture(command.gesture,"
  ],
  "gesture/gesture-android/src/main/kotlin/com/freedom/adapters/accessibility/android/AndroidGestureDispatcher.kt": [
    "service.dispatchGesture(description, object : AccessibilityService.GestureResultCallback() {"
  ]
}

# The first R09-A CI exposed one intentional source-history placeholder.
# Keep it in the lexical scan and require exactly this excluded file and exact content.
REVIEWED_EXCLUDED_PLACEHOLDERS = {
    "core/src/main/kotlin/com/freedom/core/accessibility/gesture/GesturePolicyPort.kt": "a4a4d6c0cdef3056bfdb5092813bd8a905259bf7",
}

def excluded_inventory_violations(actual):
    issues = []
    for path, digest in REVIEWED_EXCLUDED_PLACEHOLDERS.items():
        if path not in actual:
            issues.append("reviewed historical placeholder no longer explicitly excluded: " + path)
        elif actual[path] != digest:
            issues.append("reviewed historical placeholder content changed: " + path)
    for path in sorted(set(actual) - set(REVIEWED_EXCLUDED_PLACEHOLDERS)):
        issues.append("unexpected physical source excluded by Gradle: " + path)
    return issues

def coverage_violations(root):
    issues = []
    physical = set()
    explicitly_excluded = {}
    for module, directory in MODULES.items():
        main = root / directory / "src/main"
        if not main.is_dir() or main.is_symlink():
            issues.append("missing or redirected module root: " + module)
            continue
        files = {p for p in main.rglob("*.kt") if p.is_file()}
        java = {p for p in main.rglob("*.java") if p.is_file()}
        if java:
            issues.append("Java production files require explicit scan support: " + module)
        if not files:
            issues.append("empty module: " + module)
        physical.update(files)
        build = strip_gradle_comments((root / directory / "build.gradle.kts").read_text(encoding="utf-8"))
        if module in (":core", ":business", ":gesture-api", ":gesture-core",
                      ":gesture-runtime", ":gesture-android"):
            # These six existing modules explicitly select Kotlin sources from their own root.
            # Exclude the separate test source-set block before reading the literal patterns.
            production = build.split("val test by getting", 1)[0]
            if 'kotlin.srcDir("src/main/kotlin")' not in production or "app/src/main" in production:
                issues.append("non-local source selection: " + module)
            include, exclude = [], []
            for kind, args in re.findall(r"kotlin\.(include|exclude)\s*\(([^)]*)\)", production, re.S):
                (include if kind == "include" else exclude).extend(re.findall(r'"([^"]+)"', args))
            source_root = main / "kotlin"
            if not include:
                issues.append("missing source selection: " + module)
            for file in sorted(files):
                if not file.is_relative_to(source_root):
                    issues.append("non-local Kotlin source: " + str(file.relative_to(root)))
                    continue
                relative = file.relative_to(source_root).as_posix()
                if not any(fnmatch.fnmatchcase(relative, pattern) for pattern in include):
                    issues.append("physical source outside declared main inclusion: " + str(file.relative_to(root)))
                if any(fnmatch.fnmatchcase(relative, pattern) for pattern in exclude):
                    explicitly_excluded[file.relative_to(root).as_posix()] = git_blob(file.read_bytes())
    issues.extend(excluded_inventory_violations(explicitly_excluded))
    scanned = set(kotlin_sources(root))
    for path in sorted(physical - scanned):
        issues.append("production source omitted by existing scanner: " + str(path.relative_to(root)))
    for path in sorted(scanned - physical):
        issues.append("scanner source outside module main roots: " + str(path.relative_to(root)))
    return issues

def graph_violations(graph):
    issues = []
    if set(graph) != set(EXPECTED_MODULES):
        issues.append("module inventory differs")
    for module, edges in graph.items():
        if edges != ALLOWED_PROJECT_DEPENDENCIES.get(module, set()):
            issues.append("module dependency differs: " + module)
    def visit(module, path):
        if module in path:
            issues.append("cyclic module dependency: " + " -> ".join((*path, module)))
            return
        for target in sorted(graph.get(module, set())):
            visit(target, (*path, module))
    for module in sorted(graph):
        visit(module, ())
    return issues

def owner_violations(sources):
    issues = []
    masked = {path: mask_kotlin_non_code(code) for path, code in sources.items()}
    for name, owner in OWNERS.items():
        pattern = r"\b(?:class|object|interface)\s+" + re.escape(name) + r"\b"
        found = sorted(path for path, code in masked.items() for _ in re.finditer(pattern, code))
        if found != [owner]:
            issues.append(name + ": expected " + owner + ", found " + str(found))
    return issues

def edge_violations(sources):
    issues = []
    for path, tokens in EDGES.items():
        code = sources.get(path, "")
        for token in tokens:
            if token not in code:
                issues.append(path + ": missing live boundary " + token)
    return issues

class PpR09ARepositoryAcceptanceTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.sources = {p.relative_to(ROOT).as_posix(): p.read_text(encoding="utf-8")
                       for p in kotlin_sources(ROOT)}

    def test_all_physical_main_sources_are_in_existing_scan_and_module_selection(self):
        self.assertEqual([], coverage_violations(ROOT))

    def test_reviewed_exclusion_is_exact_and_no_additional_source_can_be_hidden(self):
        self.assertEqual([], excluded_inventory_violations(dict(REVIEWED_EXCLUDED_PLACEHOLDERS)))
        self.assertTrue(excluded_inventory_violations({}))
        changed = {path: "0" * 40 for path in REVIEWED_EXCLUDED_PLACEHOLDERS}
        self.assertTrue(excluded_inventory_violations(changed))
        added = dict(REVIEWED_EXCLUDED_PLACEHOLDERS)
        added["core/src/main/kotlin/Unexpected.kt"] = "0" * 40
        self.assertTrue(excluded_inventory_violations(added))

    def test_actual_project_dependency_graph_is_acyclic_and_matches_existing_contract(self):
        graph = {module: project_dependencies(ROOT / directory / "build.gradle.kts")
                 for module, directory in MODULES.items()}
        self.assertEqual([], graph_violations(graph))
        graph[":core"] = {":app"}
        self.assertTrue(graph_violations(graph))

    def test_additional_domain_owner_declarations_and_live_integration_edges(self):
        self.assertEqual([], owner_violations(self.sources))
        self.assertEqual([], edge_violations(self.sources))

    def test_missing_or_duplicate_authority_and_disconnected_edge_are_rejected(self):
        for name, path in OWNERS.items():
            # Use tiny source fixtures here; the production check above spans the entire checkout.
            original = {p: "class " + n + "\n" for n, p in OWNERS.items()}
            original[path] = "class " + name
            changed = dict(original)
            changed["unexpected/src/main/kotlin/Duplicate.kt"] = "class " + name
            self.assertTrue(any(issue.startswith(name + ":") for issue in owner_violations(changed)), name)
            del changed[path]
            del changed["unexpected/src/main/kotlin/Duplicate.kt"]
            self.assertTrue(any(issue.startswith(name + ":") for issue in owner_violations(changed)), name)
        for path, tokens in EDGES.items():
            token = tokens[0]
            self.assertIn(token, self.sources[path])
            changed = dict(self.sources)
            changed[path] = changed[path].replace(token, "R09A_REMOVED_EDGE")
            self.assertTrue(any(token in issue for issue in edge_violations(changed)), path)
