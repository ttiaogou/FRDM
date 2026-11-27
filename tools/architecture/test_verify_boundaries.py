"""Unit tests for the pre-Gradle architecture verifier."""
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import verify_boundaries as guard  # noqa: E402


class ArchitectureVerifierUnitTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        # Read the actual P1 production graph, not hand-written fragments that could miss
        # renamed sources or detached verifier calls.
        root = Path(__file__).resolve().parents[2]
        cls.recovery_code = {
            source.relative_to(root).as_posix(): guard.mask_kotlin_non_code(
                source.read_text(encoding="utf-8")
            )
            for source in guard.kotlin_sources(root)
        }

    def test_automatic_recovery_hard_guards_accept_current_production_graph(self):
        self.assertEqual(
            guard.automatic_recovery_hard_boundary_violations(self.recovery_code), []
        )

    def test_paired_ai_task_boundary_accepts_current_production_graph(self):
        self.assertEqual(guard.paired_ai_task_boundary_violations(self.recovery_code), [])

    def test_paired_ai_task_boundary_rejects_completion_gates_and_missing_checkpoint(self):
        runtime = "business/src/main/kotlin/com/freedom/features/aireply/AiReplyObservationRuntime.kt"
        adapter = "app/src/main/java/com/freedom/adapters/social/momo/MomoSocialAdapter.kt"
        for path, old, new in (
            (runtime, "val executor = privateMessagesTraversalTaskExecutor\n        if (!canPerformPrivateMessagesUiAction()",
             "val executor = privateMessagesTraversalTaskExecutor\n        if (!executor.atTop || !canPerformPrivateMessagesUiAction()"),
            (runtime, "repository?.save(executor.checkpoint())", "Unit"),
            (adapter, "persistSayHiTraversalCheckpoint()", "Unit"),
            (adapter, "sayHiTraversalState.suspendTraversal(preserveTraversal)", "sayHiTraversalState.clear()"),
        ):
            with self.subTest(path=path, old=old):
                code = dict(self.recovery_code)
                self.assertIn(old, code[path])
                code[path] = code[path].replace(old, new)
                self.assertTrue(guard.paired_ai_task_boundary_violations(code))

    def test_l07_guard_rejects_child_and_recovery_lifecycle_bypasses(self):
        runtime = "business/src/main/kotlin/com/freedom/features/aireply/AiReplyObservationRuntime.kt"
        traversal = "business/src/main/kotlin/com/freedom/features/aireply/PrivateMessagesTraversalTaskExecutor.kt"
        adapter = "app/src/main/java/com/freedom/adapters/social/momo/MomoSocialAdapter.kt"
        for path, old, new in (
            (runtime, "checkpoint.reconciliationRequired || checkpoint.revision != 0L || sendId == null",
             "false"),
            (runtime, "val fresh = privateFollowUpReturnedSnapshot(child) ?: run {",
             "val fresh = inspectFreshPageSnapshot() ?: run {"),
            (runtime, "if (!checkpointWritten) {", "if (false) {"),
            (runtime, "if (isPrivateFollowUpActive() || pendingPrivateFollowUpEnabled(verifiedPrivateLedgerContactKey())) {",
             "if (false) {"),
            (traversal, "if (scanContinuityRecoveryPending && !cleanReadViewport) {",
             "if (scanContinuityRecoveryPending) {"),
            (traversal, "unreadBoundaryStabilityCount < UNREAD_BOUNDARY_STABILITY_CONFIRMATIONS",
             "false"),
            (adapter, "if (!persistPrivateFollowUpStep(child, previous)) {",
             "if (false) {"),
        ):
            with self.subTest(path=path, old=old):
                code = dict(self.recovery_code)
                self.assertIn(old, code[path])
                code[path] = code[path].replace(old, new)
                self.assertTrue(guard.paired_ai_task_boundary_violations(code))

    def test_l09_guard_rejects_position_lifecycle_and_search_gate_bypasses(self):
        momo = "app/src/main/java/com/freedom/adapters/social/momo/"
        adapter = momo + "MomoSocialAdapter.kt"
        search = momo + "MomoReceivedGreetingsEntrySearchController.kt"
        memory = momo + "MomoReceivedGreetingsEntryPositionMemory.kt"
        runtime = "business/src/main/kotlin/com/freedom/features/aireply/AiReplyObservationRuntime.kt"
        policy = "business/src/main/kotlin/com/freedom/features/aireply/ports/ChatSessionManagementPolicy.kt"
        for path, old, new in (
            (adapter, "receivedGreetingsEntryPositionMemory.observe(result)", "receivedGreetingsEntryPositionMemory.position"),
            (adapter, "override fun beginReceivedGreetingsNavigationTask() {",
             "override fun beginReceivedGreetingsNavigationTask() {\n        receivedGreetingsEntryPositionMemory.endRun()"),
            (adapter, "receivedGreetingsEntrySearchController.start(entryPosition)", "receivedGreetingsEntrySearchController.start()"),
            (adapter, "readiness?.isReadyForWindow(expectedWindow, RunMode.AI_REPLY)", "true"),
            (adapter, "ChatSessionManagementPolicy.greetingSearchBoundaryEvidence(", "unverifiedBoundary("),
            (adapter, "receivedGreetingsEntrySearchController.mayDispatch(", "uncheckedDispatch("),
            (adapter, "receivedGreetingsEntrySearchController.tryBeginGesture(", "uncheckedBegin("),
            (search, "retryAttempt > 0 && !retryAdmitted", "false"),
            (search, "holdForObservation(nowMs)", "Unit"),
            (search, "nowMs < retryNotBeforeMs", "false"),
            (search, "edgeConfirmation.observe(currentFingerprint, nowMs)", "true"),
            (policy, "if (canScroll == true) return false", "if (canScroll == true) return true"),
            (runtime, "privateMessagesTraversalTaskExecutor.resumeAfterReturnedInbox()", "privateMessagesTraversalTaskExecutor.reset()"),
        ):
            with self.subTest(path=path, old=old):
                code = dict(self.recovery_code)
                self.assertIn(old, code[path])
                code[path] = code[path].replace(old, new)
                self.assertTrue(guard.paired_ai_task_boundary_violations(code))

        for path, addition in (
            (adapter, "\nval duplicate = MomoReceivedGreetingsEntryPositionMemory()\n"),
            (memory, "\nval forbiddenScheduler: TaskScheduler? = null\n"),
        ):
            with self.subTest(path=path, addition=addition):
                code = dict(self.recovery_code)
                code[path] += addition
                self.assertTrue(guard.paired_ai_task_boundary_violations(code))

    def test_l10_ocr_recovery_guard_accepts_current_graph(self):
        self.assertEqual(guard.ocr_recovery_boundary_violations(self.recovery_code), [])

    def test_l10_ocr_recovery_guard_rejects_unsafe_bypasses(self):
        plugin = "ocr-plugin/src/main/java/"
        host = "app/src/main/java/com/freedom/"
        business = "business/src/main/kotlin/com/freedom/features/aireply/"
        ort = plugin + "com/paddle/ocr/engine/ORTSessionManager.kt"
        service = plugin + "com/freedom/ocr/plugin/OcrPluginService.kt"
        holder = plugin + "com/freedom/ocr/plugin/runtime/OcrEngineHolder.kt"
        processor = host + "adapters/social/momo/MomoChatMessageOcrProcessor.kt"
        manager = host + "ocr/OcrManager.kt"
        runtime = business + "AiReplyObservationRuntime.kt"
        coordinator = business + "ChatMessageReadCoordinator.kt"
        for path, old, new in (
            (ort, "options.setTerminate(true)", "Unit"),
            (ort, "session.run(mapOf(inputName to tensor), scope.options)", "session.run(mapOf(inputName to tensor))"),
            (ort, "deadline.complete()", "Unit"),
            (service, "android.os.Process.killProcess(android.os.Process.myPid())", "android.os.Process.killProcess(1)"),
            (service, "engineHolder.isolateAfterTimeout(acquisition)", "true"),
            (service, "if (!released) recovery.escalateReleaseFailure()", "Unit"),
            (service, "if (!safeExit) throw OcrInferenceTimeout()", "Unit"),
            (service, "val acquisition = withColdLoadRecovery(requestId)", "val acquisition = run"),
            (service, "OcrEngineRecoveryGuard.Phase.COLD_LOAD", "OcrEngineRecoveryGuard.Phase.INFERENCE"),
            (service, "if (!safeExit) throw OcrColdLoadTimeout()", "Unit"),
            (manager, "isColdLoadTimeoutSignal(this)", "false"),
            (plugin + "com/freedom/ocr/plugin/runtime/OcrEngineRecoveryGuard.kt",
             "if (!finished && !escalated) escalateIfDue()", "Unit"),
            (holder, "cachedEngine === acquisition.engine && engineCreateCount == acquisition.creationCount", "true"),
            (manager, "typed.failureKind.requiresFreshOcrObservation()", "false"),
            (processor, "timeoutRunnable = Runnable {", "timeoutRunnable = Runnable {\\n                    batchLease.close()"),
            (processor, "failure.failureKind.requiresFreshOcrObservation()", "false"),
            (coordinator, "else if (onRejectedResult())", "else if (false)"),
            (runtime, "if (batch.status == MessageReadStatus.WINDOW_STALE) return false", "Unit"),
            (runtime, "if (hasProtectedSendInspection()) return", "Unit"),
            (runtime, "persistReceivedGreetingConfirmedSend(confirmed.confirmationId)", "true"),
            (runtime, "countUpdate.status == ContactSendRecordStatus.PERSISTENCE_FAILED", "false"),
            (runtime, "checkpointNextCursorKey = currentSessionKey", "checkpointNextCursorKey = null"),
        ):
            with self.subTest(path=path, old=old):
                code = dict(self.recovery_code)
                self.assertIn(old, code[path])
                code[path] = code[path].replace(old, new)
                self.assertTrue(guard.ocr_recovery_boundary_violations(code))

    def test_automatic_recovery_hard_guards_reject_old_handoff_interfaces_and_files(self):
        runtime = "app/src/main/java/com/freedom/adapters/accessibility/android/AndroidAccountBrowseWorkflow.kt"
        for token in (
            "allowManualResume",
            "cancelManualTouchResume()",
            "scheduleManualTouchResume()",
            "manualRecoveryLock",
            "recoveryRetryExhausted",
        ):
            with self.subTest(symbol=token):
                code = dict(self.recovery_code)
                code[runtime] += "\nval reintroduced = " + token + "\n"
                self.assertTrue(any(
                    "Legacy manual recovery is forbidden" in issue
                    for issue in guard.automatic_recovery_hard_boundary_violations(code)
                ))

        code = dict(self.recovery_code)
        code["core/src/main/kotlin/com/freedom/core/runtime/TieredManualHandoffQuietPeriod.kt"] = (
            "class TieredManualHandoffQuietPeriod {}\n"
        )
        self.assertTrue(any(
            "Legacy manual recovery is forbidden" in issue
            for issue in guard.automatic_recovery_hard_boundary_violations(code)
        ))

        # A test fixture's history and human-readable diagnostics are not production logic.
        code = dict(self.recovery_code)
        code["core/src/test/kotlin/com/freedom/core/runtime/ManualHistoryTest.kt"] = (
            "val policy = TieredManualHandoffQuietPeriod()\n"
        )
        code[runtime] += guard.mask_kotlin_non_code(
            '\n// allowManualResume is historical\nval description = "scheduleManualTouchResume()"\n'
        )
        self.assertEqual(
            guard.automatic_recovery_hard_boundary_violations(code), []
        )

    def test_automatic_recovery_hard_guards_reject_regressed_backoff_and_terminal_retry(self):
        scheduler = "business/src/main/kotlin/com/freedom/features/recovery/RecoveryScheduler.kt"
        src = self.recovery_code[scheduler]
        mutations = (
            ("30_000L)", "60_000L)", "bounded non-terminal backoff"),
            ("current.callbackToken != token", "current.callbackToken == token",
             "stale callback token check"),
            ("fun stopByUser(", "fun stopAfterManualReview(", "explicit user stop"),
            ("scheduleNext(current, INITIAL_BACKOFF_STAGE)",
             "scheduleNext(current, LONG_WAIT_BACKOFF_STAGE)", "fast retry after touch"),
            ("TOUCH_ACTIVE_OBSERVATION_DELAY_MS = 30_000L",
             "TOUCH_ACTIVE_OBSERVATION_DELAY_MS = 60_000L",
             "touch observation must remain bounded"),
        )
        for old, new, expected in mutations:
            with self.subTest(regression=expected):
                self.assertIn(old, src)
                code = dict(self.recovery_code)
                code[scheduler] = src.replace(old, new, 1)
                self.assertTrue(any(
                    expected in issue
                    for issue in guard.automatic_recovery_hard_boundary_violations(code)
                ))

    def test_automatic_recovery_hard_guards_require_two_fresh_snapshots_and_completion(self):
        verifier = (
            "business/src/main/kotlin/com/freedom/features/recovery/"
            "RecoveryCheckpointVerificationCoordinator.kt"
        )
        src = self.recovery_code[verifier]
        mutations = (
            ("stableVerificationSamples < UnifiedRecoveryTransactionStateMachine.REQUIRED_STABLE_SAMPLES",
             "stableVerificationSamples < 1", "two stable snapshots"),
            ("evidence.observationGeneration <= lastObservationGeneration",
             "evidence.observationGeneration > lastObservationGeneration",
             "stale observation generation"),
            ("scheduler.completeRecovery(", "scheduler.stopByUser(",
             "verified scheduler completion"),
        )
        for old, new, expected in mutations:
            with self.subTest(regression=expected):
                self.assertIn(old, src)
                code = dict(self.recovery_code)
                code[verifier] = src.replace(old, new, 1)
                self.assertTrue(any(
                    expected in issue
                    for issue in guard.automatic_recovery_hard_boundary_violations(code)
                ))

    def test_automatic_recovery_hard_guards_require_all_three_checkpoint_routes(self):
        browse = "business/src/main/kotlin/com/freedom/features/accountbrowse/DynamicPageRecovery.kt"
        message = "business/src/main/kotlin/com/freedom/features/aireply/MessageListCheckpointVerification.kt"
        greetings = "business/src/main/kotlin/com/freedom/features/aireply/ReceivedGreetingsCheckpointVerification.kt"
        ai = "business/src/main/kotlin/com/freedom/features/aireply/AiReplyObservationRuntime.kt"
        regressions = (
            (browse, "RecoveryCheckpointVerificationCoordinator(", "LocalVerifier(",
             "shared browse verifier"),
            (message, "PrivateMessagesRecoveryCheckpointAdapter()", "LocalCheckpointParser()",
             "message checkpoint adapter"),
            (message, "requireNotNull(verifier).observe(", "requireNotNull(verifier).skipObserve(",
             "fresh message evidence"),
            (greetings, "RecoveryCheckpointVerificationCoordinator(", "LocalVerifier(",
             "shared greetings verifier"),
            (greetings, "active.completeResume(", "active.forceResume(",
             "greetings resume completion"),
            (ai, "receivedGreetingsTaskFlow.synchronizeRecoveryConfirmation(",
             "receivedGreetingsTaskFlow.trustLastSnapshot(",
             "greetings adapter confirmation"),
        )
        for path, old, new, expected in regressions:
            with self.subTest(path=path, regression=expected):
                self.assertIn(old, self.recovery_code[path])
                code = dict(self.recovery_code)
                code[path] = code[path].replace(old, new, 1)
                self.assertTrue(any(
                    expected in issue
                    for issue in guard.automatic_recovery_hard_boundary_violations(code)
                ))

        code = dict(self.recovery_code)
        del code[greetings]
        self.assertTrue(any(
            "Shared checkpoint route source is missing" in issue and greetings in issue
            for issue in guard.automatic_recovery_hard_boundary_violations(code)
        ))

    def test_greetings_window_sync_must_precede_ui_ownership_even_if_later_fallback_remains(self):
        ai = "business/src/main/kotlin/com/freedom/features/aireply/AiReplyObservationRuntime.kt"
        code = dict(self.recovery_code)
        confirmation = (
            "val synchronized = receivedGreetingsTaskFlow.synchronizeRecoveryConfirmation()"
        )
        self.assertIn(confirmation, code[ai])
        # A fallback call later in the function must not satisfy the safety gate.
        code[ai] = code[ai].replace(confirmation, "val synchronized = true", 1)
        self.assertTrue(any(
            "greetings adapter confirmation" in issue
            for issue in guard.automatic_recovery_hard_boundary_violations(code)
        ))
        code[ai] += "\n" + confirmation + "\n"
        self.assertTrue(any(
            "confirmation must precede UI ownership claim" in issue
            for issue in guard.automatic_recovery_hard_boundary_violations(code)
        ))

    def test_automatic_recovery_hard_guards_require_checkpoint_repositories_and_identity(self):
        composition = "app/src/main/java/com/freedom/app/AppCompositionRoot.kt"
        scheduler = "business/src/main/kotlin/com/freedom/features/recovery/RecoveryScheduler.kt"
        verifier = (
            "business/src/main/kotlin/com/freedom/features/recovery/"
            "RecoveryCheckpointVerificationCoordinator.kt"
        )
        regressions = (
            (composition,
             "receivedGreetingsCheckpointRepository = root.sayHiTraversalCheckpointRepository",
             "receivedGreetingsCheckpointRepository = null",
             "greetings checkpoint repository injection"),
            (scheduler, "identity.runGeneration == other.runGeneration",
             "identity.runGeneration != other.runGeneration", "run generation identity"),
            (scheduler, "identity.checkpointId == other.checkpointId",
             "identity.checkpointId != other.checkpointId", "checkpoint identity"),
            (verifier, "evidence.pageFingerprint == checkpoint.pageFingerprint",
             "evidence.pageFingerprint != checkpoint.pageFingerprint",
             "page fingerprint evidence"),
            (verifier, "evidence.runGeneration == checkpoint.runGeneration",
             "evidence.runGeneration != checkpoint.runGeneration",
             "run generation evidence"),
        )
        for path, old, new, expected in regressions:
            with self.subTest(path=path, regression=expected):
                self.assertIn(old, self.recovery_code[path])
                code = dict(self.recovery_code)
                code[path] = code[path].replace(old, new, 1)
                self.assertTrue(any(
                    expected in issue
                    for issue in guard.automatic_recovery_hard_boundary_violations(code)
                ))

    def test_automatic_recovery_hard_guards_require_both_ai_resume_proofs(self):
        ai = "business/src/main/kotlin/com/freedom/features/aireply/AiReplyObservationRuntime.kt"
        src = self.recovery_code[ai]
        self.assertEqual(src.count("recoveryContext.acknowledgeVerifiedCheckpoint("), 2)
        code = dict(self.recovery_code)
        code[ai] = src.replace(
            "recoveryContext.acknowledgeVerifiedCheckpoint(",
            "recoveryContext.acceptUncheckedCheckpoint(", 1
        )
        self.assertTrue(any(
            "must both acknowledge shared proof" in issue
            for issue in guard.automatic_recovery_hard_boundary_violations(code)
        ))

    def test_final_boundaries_reject_platform_and_terminal_unknown_bypasses(self):
        code = {
            "business/src/main/kotlin/com/freedom/features/bad/BadFeature.kt":
                "val node: AccessibilityNodeInfo? = null\n",
            "app/src/main/java/com/freedom/adapters/bad/BadAdapter.kt":
                "val machine = AccountBrowseStateMachine()\n",
            "business/src/main/kotlin/com/freedom/features/aireply/ai/ReplyTaskRepository.kt":
                "ReplyTaskStatus.UNKNOWN to setOf(ReplyTaskStatus.FAILED_FINAL)\n",
        }
        violations = guard.final_architecture_boundary_violations(code)
        self.assertTrue(any("Feature directly accesses" in item for item in violations))
        self.assertTrue(any("Adapter instantiates" in item for item in violations))
        self.assertTrue(any("UNKNOWN may transition" in item for item in violations))

    def test_final_boundaries_reject_retryable_permanent_cache_write(self):
        code = {
            "app/src/main/java/com/freedom/adapters/social/momo/MomoSocialAdapter.kt":
                "failedInboxIdentityCaptureKeys += FAILED_RETRYABLE\n",
        }
        violations = guard.final_architecture_boundary_violations(code)
        self.assertTrue(any("RETRYABLE result" in item for item in violations))



    def test_p1_business_entry_accepts_current_production_wiring(self):
        self.assertEqual(guard.unified_business_event_entry_violations(self.recovery_code), [])

    def test_p1_business_entry_rejects_service_window_bypass(self):
        changed = dict(self.recovery_code)
        service = "app/src/main/java/com/freedom/accessibility/FreedomAccessibilityService.kt"
        changed[service] += "\naiReplyObservationRuntime.onSelfNavigationAttached(transition)\n"
        self.assertTrue(any("directly bypasses" in item
                            for item in guard.unified_business_event_entry_violations(changed)))

    def test_p1_business_entry_rejects_direct_pipeline_binding(self):
        changed = dict(self.recovery_code)
        root = "app/src/main/java/com/freedom/app/AppCompositionRoot.kt"
        changed[root] += "\ndispatchGlobalTouchEvent = runSessionCoordinator::dispatchGlobalTouchEvent,\n"
        self.assertTrue(any("direct business dispatch" in item
                            for item in guard.unified_business_event_entry_violations(changed)))

    def test_p1_business_entry_rejects_missing_gateway_wiring(self):
        changed = dict(self.recovery_code)
        root = "app/src/main/java/com/freedom/app/AppCompositionRoot.kt"
        changed[root] = changed[root].replace(
            "unifiedAccessibilityEntry = unifiedAccessibilityEntry,",
            "missingGateway = null,"
        )
        self.assertTrue(any("wiring missing" in item
                            for item in guard.unified_business_event_entry_violations(changed)))

    def test_p2_observation_ownership_accepts_injected_production_paths(self):
        self.assertEqual(guard.unified_page_observation_violations(self.recovery_code), [])

    def test_p2_observation_guard_rejects_local_fallback_construction(self):
        changed = dict(self.recovery_code)
        momo = "business/src/main/kotlin/com/freedom/features/aireply/MomoPageObservationCoordinator.kt"
        changed[momo] += "\nval local = PageObservationCoordinator(scheduler)\n"
        self.assertTrue(any("bypasses common scheduler" in item
                            for item in guard.unified_page_observation_violations(changed)))
        self.assertTrue(any("construction drift" in item
                            for item in guard.architecture_authority_violations(changed)))

    def test_p2_observation_guard_rejects_direct_greetings_task_start(self):
        changed = dict(self.recovery_code)
        momo = "business/src/main/kotlin/com/freedom/features/aireply/MomoPageObservationCoordinator.kt"
        changed[momo] += "\nonReceivedGreetingsConfirmed = ::startReceivedGreetingsTaskFromConfirmedPage\n"
        self.assertTrue(any("bypasses common scheduler" in item
                            for item in guard.unified_page_observation_violations(changed)))

    def test_p2_observation_guard_rejects_legacy_timers_and_missing_runtime_wiring(self):
        changed = dict(self.recovery_code)
        runtime = "business/src/main/kotlin/com/freedom/features/aireply/AiReplyObservationRuntime.kt"
        changed[runtime] = changed[runtime].replace(
            "pageInspectionTask = sharedPageObservationCoordinator.scheduleReadOnlyObservation",
            "pageInspectionTask = scheduler.schedule"
        )
        self.assertTrue(any("bypasses common scheduler" in item
                            for item in guard.unified_page_observation_violations(changed)))

        changed = dict(self.recovery_code)
        browse = "app/src/main/java/com/freedom/adapters/accessibility/android/AndroidAccountBrowseWorkflow.kt"
        changed[browse] = changed[browse].replace(
            "accountBrowseRuntime.onCoalescedPageObservation(",
            "accountBrowseRuntime.scheduleStep("
        )
        self.assertTrue(any("missing required wiring" in item or "directly schedules a business step" in item
                            for item in guard.unified_page_observation_violations(changed)))

    def test_p2_observation_guard_rejects_disabled_mode_isolation(self):
        changed = dict(self.recovery_code)
        momo = "business/src/main/kotlin/com/freedom/features/aireply/MomoPageObservationCoordinator.kt"
        changed[momo] = changed[momo].replace(
            "isEnabled = isObservationActive",
            "isEnabled = { true }"
        )
        self.assertTrue(any("missing required wiring" in item
                            for item in guard.unified_page_observation_violations(changed)))

    def test_p3_shared_task_lifecycle_accepts_current_production_graph(self):
        self.assertEqual(guard.unified_task_lifecycle_violations(self.recovery_code), [])

    def test_p3_rejects_missing_generation_to_task_slot_revoke(self):
        changed = dict(self.recovery_code)
        session = "app/src/main/java/com/freedom/application/runtime/RunSessionCoordinator.kt"
        changed[session] = changed[session].replace(
            "taskCoordinator?.advanceRunGeneration(newGeneration)",
            "Unit"
        )
        self.assertTrue(any("P3 task lifecycle unified wiring missing" in item
                            for item in guard.unified_task_lifecycle_violations(changed)))

    def test_p3_rejects_unshared_root_task_coordinator(self):
        changed = dict(self.recovery_code)
        root = "app/src/main/java/com/freedom/app/AppCompositionRoot.kt"
        changed[root] = changed[root].replace(
            "taskCoordinator = root.taskCoordinator",
            "taskCoordinator = null",
            1
        )
        self.assertTrue(any("all five production consumers" in item
                            for item in guard.unified_task_lifecycle_violations(changed)))

    def test_p3_rejects_stale_active_task_visibility_and_extra_execution_owner(self):
        changed = dict(self.recovery_code)
        task = "core/src/main/kotlin/com/freedom/core/runtime/TaskCoordinator.kt"
        changed[task] = changed[task].replace(
            "fun activeTaskId(): String? = currentLease()?.taskId",
            "fun activeTaskId(): String? = activeLease?.taskId"
        )
        self.assertTrue(any("P3 task lifecycle unified wiring missing" in item
                            for item in guard.unified_task_lifecycle_violations(changed)))

        changed = dict(self.recovery_code)
        changed["business/src/main/kotlin/com/freedom/features/bad/OtherOwner.kt"] = (
            "val independent = TaskExecutionController(coordinator, generation)\n"
        )
        self.assertTrue(any("construction drift" in item
                            for item in guard.unified_task_lifecycle_violations(changed)))

    def test_p3_rejects_business_owned_generation_advance(self):
        changed = dict(self.recovery_code)
        extra = "business/src/main/kotlin/com/freedom/features/bad/OtherOwner.kt"
        changed[extra] = "fun bypass() = coordinator.advanceRunGeneration(42L)\n"
        self.assertTrue(any("generation must advance only" in item
                            for item in guard.unified_task_lifecycle_violations(changed)))

    def test_p4_authorization_accepts_current_production_outlets(self):
        self.assertEqual(guard.unified_ui_write_authorization_violations(self.recovery_code), [])

    def test_p4_rejects_broad_any_task_gateway_checks(self):
        changed = dict(self.recovery_code)
        gateway = "app/src/main/java/com/freedom/adapters/accessibility/android/AndroidAccessibilityGateway.kt"
        changed[gateway] += "\nval old = isUiOperationOwned()\n"
        self.assertTrue(any("broad any-task" in x
                            for x in guard.unified_ui_write_authorization_violations(changed)))

    def test_p4_rejects_unprotected_composer_semantic_write(self):
        changed = dict(self.recovery_code)
        gateway = "app/src/main/java/com/freedom/adapters/accessibility/android/AndroidAccessibilityGateway.kt"
        changed[gateway] = changed[gateway].replace(
            "): Boolean = uiWriteAuthorization.withAuthorizedWrite(UiWriteScope.AI_COMPOSER) {",
            "): Boolean = when (action) {"
        )
        self.assertTrue(any("P4 UI mutation authorization missing" in x or "input focus/set-text bypasses" in x
                            for x in guard.unified_ui_write_authorization_violations(changed)))

    def test_p4_rejects_ungated_global_action_and_browse_input(self):
        changed = dict(self.recovery_code)
        gateway = "app/src/main/java/com/freedom/adapters/accessibility/android/AndroidAccessibilityGateway.kt"
        changed[gateway] = changed[gateway].replace(
            "uiWriteAuthorization.withAuthorizedWrite(UiWriteScope.GLOBAL_NAVIGATION, submit)",
            "submit()"
        )
        changed[gateway] = changed[gateway].replace(
            "uiWriteAuthorization.withAuthorizedWrite(UiWriteScope.BROWSE_NODE_ACTION)",
            "run"
        )
        self.assertTrue(any("P4 UI mutation authorization missing" in x
                            for x in guard.unified_ui_write_authorization_violations(changed)))

    def test_p4_rejects_unapproved_platform_node_action_owner(self):
        changed = dict(self.recovery_code)
        changed["app/src/main/java/com/freedom/adapters/social/momo/Unsafe.kt"] = (
            "fun write(node: AccessibilityNodeInfo) = node.performAction(16)\n"
        )
        self.assertTrue(any("direct Android node/global mutation bypass" in x
                            for x in guard.unified_ui_write_authorization_violations(changed)))

    def test_p4_rejects_bypassed_shared_authorization_in_composition_root(self):
        changed = dict(self.recovery_code)
        root = "app/src/main/java/com/freedom/app/AppCompositionRoot.kt"
        changed[root] = changed[root].replace(
            "uiWriteAuthorization = uiWriteAuthorization",
            "isUiOperationOwned = { root.taskCoordinator.activeTaskId() != null }"
        )
        self.assertTrue(any("P4" in x for x in guard.unified_ui_write_authorization_violations(changed)))

    def test_ui_platform_write_guard_accepts_current_audited_outlets(self):
        self.assertEqual(
            guard.android_ui_mutation_bypass_violations(self.recovery_code), []
        )

    def test_ui_platform_write_guard_rejects_renamed_and_null_safe_receivers(self):
        path = "app/src/main/java/com/freedom/adapters/social/momo/UnsafeWrite.kt"
        bypasses = (
            "liveNode.performAction(16)",
            "liveNode?.performAction(16)",
            "liveNode!!.performAction(16)",
            "renamedService.performGlobalAction(1)",
            "renamedService?.performGlobalAction(1)",
            "otherDispatcher.dispatchGesture(gesture, callback)",
        )
        for snippet in bypasses:
            with self.subTest(snippet=snippet):
                changed = dict(self.recovery_code)
                changed[path] = guard.mask_kotlin_non_code("fun bypass() = " + snippet)
                self.assertTrue(
                    any("Direct Android UI mutation bypass" in item
                        for item in guard.android_ui_mutation_bypass_violations(changed)),
                    snippet,
                )

    def test_ui_platform_write_guard_rejects_new_calls_inside_approved_owners(self):
        gateway = "app/src/main/java/com/freedom/adapters/accessibility/android/AndroidAccessibilityGateway.kt"
        dispatcher = (
            "gesture/gesture-android/src/main/kotlin/com/freedom/adapters/accessibility/"
            "android/AndroidGestureDispatcher.kt"
        )
        for path, snippet in (
            (gateway, "node.performAction(16)"),
            (gateway, "otherNode.performAction(16)"),
            (gateway, "service.performGlobalAction(1)"),
            (gateway, "otherService.performGlobalAction(1)"),
            (gateway, "gestureDispatcher.dispatchGesture(spec, listener)"),
            (gateway, "service.dispatchGesture(spec, callback)"),
            (dispatcher, "service.dispatchGesture(spec, callback)"),
            (dispatcher, "renamedService.dispatchGesture(spec, callback)"),
        ):
            with self.subTest(path=path, snippet=snippet):
                changed = dict(self.recovery_code)
                changed[path] += "\nfun bypass() = " + snippet + "\n"
                self.assertTrue(
                    guard.android_ui_mutation_bypass_violations(changed),
                    f"Approved file must not gain another raw outlet: {path}: {snippet}",
                )

    def test_ui_platform_write_guard_rejects_expression_and_callable_reference_bypasses(self):
        path = "app/src/main/java/com/freedom/adapters/social/momo/UnsafeWrite.kt"
        for snippet in (
            "fun bypass() = (findNode()).performAction(16)",
            "fun bypass() = (getService()).performGlobalAction(1)",
            "fun bypass() = node::performAction",
            "fun bypass() = service::dispatchGesture",
        ):
            with self.subTest(snippet=snippet):
                changed = dict(self.recovery_code)
                changed[path] = guard.mask_kotlin_non_code(snippet)
                self.assertTrue(
                    guard.android_ui_mutation_bypass_violations(changed),
                    snippet,
                )

    def test_ui_platform_write_guard_accepts_gateway_forwarding_and_ignores_non_code(self):
        changed = dict(self.recovery_code)
        changed["app/src/main/java/com/freedom/adapters/social/momo/Forward.kt"] = (
            guard.mask_kotlin_non_code(
                'fun navigate() = accessibilityGateway.performGlobalAction(action)\n'
                '// unsafeNode.performAction(16)\n'
                'val example = "service.performGlobalAction(1)"\n'
            )
        )
        self.assertEqual(guard.android_ui_mutation_bypass_violations(changed), [])

    def test_p5_recovery_adapter_registry_accepts_all_existing_business_routes(self):
        self.assertEqual(
            guard.unified_recovery_adapter_registration_violations(self.recovery_code), []
        )

    def test_p5_rejects_business_raw_route_registration_or_local_fallback(self):
        ai = "business/src/main/kotlin/com/freedom/features/aireply/AiReplyObservationRuntime.kt"
        changed = dict(self.recovery_code)
        changed[ai] = changed[ai].replace(
            "registerRecoveryAdapter(messageListRecoveryRoute)",
            "registerFingerprintRoute(messageListRecoveryRoute.asRoute())"
        )
        self.assertTrue(any("P5" in issue
                            for issue in guard.unified_recovery_adapter_registration_violations(changed)))
        changed = dict(self.recovery_code)
        changed[ai] += "\nval matching = listOf(messageListRecoveryRoute, chatDetailRecoveryRoute)\n"
        self.assertTrue(any("business routes must use shared" in issue
                            for issue in guard.unified_recovery_adapter_registration_violations(changed)))

    def test_p5_rejects_browse_bypassing_mode_scoped_recovery_matching(self):
        path = "app/src/main/java/com/freedom/adapters/accessibility/android/AndroidAccountBrowseWorkflow.kt"
        changed = dict(self.recovery_code)
        changed[path] = changed[path].replace(
            "allowedTaskIds = setOf(dynamicFeedRecoveryRoute.taskId)",
            "allowedTaskIds = emptySet()"
        )
        self.assertTrue(any("P5 shared recovery adapter registration missing" in issue
                            for issue in guard.unified_recovery_adapter_registration_violations(changed)))

    def test_p5_rejects_missing_secondary_task_ownership_and_new_registry(self):
        chat = "business/src/main/kotlin/com/freedom/features/aireply/ChatDetailRecoveryRouteAdapter.kt"
        changed = dict(self.recovery_code)
        changed[chat] = changed[chat].replace(
            "override val primaryForTask: Boolean = false",
            "override val primaryForTask: Boolean = true"
        )
        self.assertTrue(any("P5 shared recovery adapter registration missing" in issue
                            for issue in guard.unified_recovery_adapter_registration_violations(changed)))

        changed = dict(self.recovery_code)
        changed["app/src/main/java/com/freedom/app/OtherRegistry.kt"] = (
            "val registry = PageRecoveryAdapterRegistry()\n"
        )
        self.assertTrue(any("construction drift" in issue
                            for issue in guard.unified_recovery_adapter_registration_violations(changed)))

    def test_p5_rejects_unsafe_unscoped_match_or_loss_of_typed_checkpoint_adapter(self):
        router = "core/src/main/kotlin/com/freedom/core/runtime/PageFingerprintRouter.kt"
        changed = dict(self.recovery_code)
        changed[router] = changed[router].replace(
            "it.taskId in allowedTaskIds && it.matches(fingerprint)",
            "it.matches(fingerprint)"
        )
        self.assertTrue(any("P5 shared recovery adapter registration missing" in issue
                            for issue in guard.unified_recovery_adapter_registration_violations(changed)))

        checkpoint = "business/src/main/kotlin/com/freedom/features/recovery/RecoveryCheckpointAdapter.kt"
        changed = dict(self.recovery_code)
        changed[checkpoint] = changed[checkpoint].replace(
            "interface RecoveryCheckpointAdapter<T>",
            "interface LegacyCheckpointMapper<T>"
        )
        self.assertTrue(any("typed checkpoint mapper interface" in issue
                            for issue in guard.unified_recovery_adapter_registration_violations(changed)))

    def test_p6_unified_recovery_manager_accepts_production_wiring(self):
        self.assertEqual(guard.unified_recovery_transaction_manager_violations(self.recovery_code), [])

    def test_p6_rejects_missing_process_manager_injection_and_extra_manager_owner(self):
        app = "app/src/main/java/com/freedom/app/AppCompositionRoot.kt"
        changed = dict(self.recovery_code)
        changed[app] = changed[app].replace(
            "recoveryTransactionManager = root.recoveryTransactionManager",
            "recoveryTransactionManager = null"
        )
        self.assertTrue(any("P6 unified recovery transaction manager wiring missing" in issue
                            for issue in guard.unified_recovery_transaction_manager_violations(changed)))
        changed = dict(self.recovery_code)
        changed["app/src/main/java/com/freedom/app/OtherManager.kt"] = (
            "val manager = UnifiedRecoveryTransactionManager({ 0L })\n"
        )
        self.assertTrue(any("constructed only by AppCompositionRoot" in issue
                            for issue in guard.unified_recovery_transaction_manager_violations(changed)))

    def test_p6_rejects_unchecked_old_generation_and_missing_verified_completion(self):
        manager = "business/src/main/kotlin/com/freedom/features/recovery/UnifiedRecoveryTransactionManager.kt"
        changed = dict(self.recovery_code)
        changed[manager] = changed[manager].replace(
            "identity.runGeneration != generation",
            "identity.runGeneration == generation"
        )
        self.assertTrue(any("P6 unified recovery transaction manager wiring missing" in issue
                            for issue in guard.unified_recovery_transaction_manager_violations(changed)))
        ai = "business/src/main/kotlin/com/freedom/features/aireply/AiReplyObservationRuntime.kt"
        changed = dict(self.recovery_code)
        changed[ai] = changed[ai].replace(
            "recoveryTransactionManager?.completeVerified(it)",
            "Unit"
        )
        self.assertTrue(any("P6 unified recovery transaction manager wiring missing" in issue
                            for issue in guard.unified_recovery_transaction_manager_violations(changed)))

    def test_p6_rejects_unknown_retryable_pause_without_shared_checkpoint_entry(self):
        ai = "business/src/main/kotlin/com/freedom/features/aireply/AiReplyObservationRuntime.kt"
        changed = dict(self.recovery_code)
        changed[ai] = changed[ai].replace(
            "requestUnifiedPauseAndCheckpoint(activeTask,",
            "run { false } //"
        )
        self.assertTrue(any("P6 unified recovery transaction manager wiring missing" in issue
                            for issue in guard.unified_recovery_transaction_manager_violations(changed)))
        changed = dict(self.recovery_code)
        changed[ai] = changed[ai].replace(
            "recoverRetryablePrivateList(",
            "unmanagedRetryable(",
        )
        self.assertTrue(any("P6" in issue
                            for issue in guard.unified_recovery_transaction_manager_violations(changed)))

    def test_p7_legacy_bypass_guards_accept_migrated_production_graph(self):
        self.assertEqual(guard.legacy_architecture_bypass_violations(self.recovery_code), [])

    def test_p7_rejects_old_business_callbacks_and_nullable_event_fallback(self):
        pipeline = "app/src/main/java/com/freedom/adapters/accessibility/android/AndroidAccessibilityEventPipeline.kt"
        changed = dict(self.recovery_code)
        changed[pipeline] += "\nval dispatchTargetApplicationEvent = {}\n"
        self.assertTrue(any("legacy business callback" in issue
                            for issue in guard.legacy_architecture_bypass_violations(changed)))
        changed = dict(self.recovery_code)
        changed[pipeline] = changed[pipeline].replace(
            "private val unifiedAccessibilityEntry: UnifiedAccessibilityEntry,",
            "private val unifiedAccessibilityEntry: UnifiedAccessibilityEntry? = null,"
        )
        self.assertTrue(any("P7 bypass migration missing" in issue
                            for issue in guard.legacy_architecture_bypass_violations(changed)))
        changed = dict(self.recovery_code)
        changed[pipeline] += "\nif (unifiedAccessibilityEntry != null) fallback()\n"
        self.assertTrue(any("nullable unified-entry fallback" in issue
                            for issue in guard.legacy_architecture_bypass_violations(changed)))

    def test_p7_rejects_double_recovery_route_registration_and_raw_eviction(self):
        coordinator = "core/src/main/kotlin/com/freedom/core/runtime/PageObservationCoordinator.kt"
        changed = dict(self.recovery_code)
        changed[coordinator] += "\nfingerprintRouter.register(adapter.asRoute())\n"
        self.assertTrue(any("duplicate recovery route registration" in issue
                            for issue in guard.legacy_architecture_bypass_violations(changed)))
        changed = dict(self.recovery_code)
        changed[coordinator] += "\nrecoveryAdapterRegistry.unregister(route.routeId)\n"
        self.assertTrue(any("raw route can evict" in issue
                            for issue in guard.legacy_architecture_bypass_violations(changed)))

    def test_p7_rejects_unidentified_recovery_dispatch_and_fingerprint_bypass(self):
        dispatcher = "business/src/main/kotlin/com/freedom/features/recovery/RecoveryEventDispatcher.kt"
        changed = dict(self.recovery_code)
        changed[dispatcher] = changed[dispatcher].replace(
            "if (sharedManager != null) {",
            "if (sharedManager == null) {",
            1
        )
        self.assertTrue(any("unbound recovery event can bypass" in issue
                            for issue in guard.legacy_architecture_bypass_violations(changed)))
        changed = dict(self.recovery_code)
        changed[dispatcher] = changed[dispatcher].replace(
            "manager.dispatch(identity, event, transaction.state, managerLaneId)",
            "transaction.observeFingerprint(fingerprint)"
        )
        self.assertTrue(any("P7 bypass migration missing" in issue
                            for issue in guard.legacy_architecture_bypass_violations(changed)))

    def test_p7_browse_checkpoint_state_must_surface_the_active_verification_lane(self):
        browse = "business/src/main/kotlin/com/freedom/features/accountbrowse/DynamicPageRecovery.kt"
        changed = dict(self.recovery_code)
        changed[browse] = changed[browse].replace(
            "if (checkpointVerifier != null) checkpointDispatcher.state else dispatcher.state",
            "dispatcher.state"
        )
        self.assertTrue(any("P7 bypass migration missing" in issue
                            for issue in guard.legacy_architecture_bypass_violations(changed)))

    def test_p7_rejects_reused_browse_verifier_lane_and_duplicate_send_identity(self):
        browse = "business/src/main/kotlin/com/freedom/features/accountbrowse/DynamicPageRecovery.kt"
        changed = dict(self.recovery_code)
        changed[browse] = changed[browse].replace(
            "transaction = checkpointDispatcher",
            "transaction = dispatcher"
        )
        self.assertTrue(any("checkpoint and UNKNOWN reuse one reducer lane" in issue
                            for issue in guard.legacy_architecture_bypass_violations(changed)))
        ai = "business/src/main/kotlin/com/freedom/features/aireply/AiReplyObservationRuntime.kt"
        changed = dict(self.recovery_code)
        idx = changed[ai].index("private fun requestSendConfirmationRecovery")
        changed[ai] = changed[ai][:idx] + changed[ai][idx:].replace(
            "chatDetailRecoveryRoute.saveCheckpoint()",
            "chatDetailRecoveryRoute.saveCheckpoint()\nval duplicate = RecoveryEventIdentity(",
            1
        )
        self.assertTrue(any("duplicate recovery identity logic" in issue
                            for issue in guard.legacy_architecture_bypass_violations(changed)))

    def test_pp_reduction_guard_accepts_current_production_graph(self):
        self.assertEqual(guard.runtime_architecture_reduction_violations(self.recovery_code), [])

    def test_pp_reduction_guard_rejects_new_runtime_file_even_with_unrelated_name(self):
        changed = dict(self.recovery_code)
        changed["core/src/main/kotlin/com/freedom/core/runtime/Extra.kt"] = "class Extra"
        self.assertTrue(any("unreviewed core/runtime source" in item
                            for item in guard.runtime_architecture_reduction_violations(changed)))

    def test_pp_reduction_guard_rejects_new_or_duplicate_declaration_in_existing_runtime(self):
        owner = "core/src/main/kotlin/com/freedom/core/runtime/PageFingerprintRouter.kt"
        for declaration in ("class AlternatePageResolver", "class PageFingerprintRouter"):
            with self.subTest(declaration=declaration):
                changed = dict(self.recovery_code)
                changed[owner] += "\n" + declaration + "\n"
                self.assertTrue(any("unreviewed core/runtime declarations" in item
                                    for item in guard.runtime_architecture_reduction_violations(changed)))

    def test_pp_reduction_guard_rejects_new_observation_channel_in_existing_or_external_owner(self):
        root = "app/src/main/java/com/freedom/app/AppCompositionRoot.kt"
        for owner in (root, "business/src/main/kotlin/com/freedom/features/bad/Extra.kt"):
            with self.subTest(owner=owner):
                changed = dict(self.recovery_code)
                changed[owner] = changed.get(owner, "") + "\nval extra = observer.registerChannel()\n"
                self.assertTrue(any("control surface expanded" in item and "registerChannel" in item
                                    for item in guard.runtime_architecture_reduction_violations(changed)))

    def test_pp_reduction_guard_rejects_duplicate_control_reference_and_new_consumer(self):
        root = "app/src/main/java/com/freedom/app/AppCompositionRoot.kt"
        for owner in (root, "business/src/main/kotlin/com/freedom/features/bad/Extra.kt"):
            with self.subTest(owner=owner):
                changed = dict(self.recovery_code)
                changed[owner] = changed.get(owner, "") + "\nval extra = UnifiedPageObservationBus()\n"
                self.assertTrue(any("control surface expanded" in item and "UnifiedPageObservationBus" in item
                                    for item in guard.runtime_architecture_reduction_violations(changed)))

    def test_pp_reduction_guard_rejects_retired_page_control_symbols_anywhere(self):
        owner = "business/src/main/kotlin/com/freedom/features/bad/Extra.kt"
        for name in sorted(guard.RETIRED_PAGE_CONTROL_SYMBOLS):
            with self.subTest(name=name):
                changed = dict(self.recovery_code)
                changed[owner] = "val extra = " + name + "()"
                self.assertTrue(any("control surface expanded" in item and name in item
                                    for item in guard.runtime_architecture_reduction_violations(changed)))

    def test_pp_reduction_guard_rejects_retired_shared_observation_reset_alias(self):
        owner = "core/src/main/kotlin/com/freedom/core/runtime/PageObservationCoordinator.kt"
        changed = dict(self.recovery_code)
        changed[owner] += "\nfun reset() = resetAll()\n"
        self.assertTrue(any("retired shared observation reset alias" in item
                            for item in guard.runtime_architecture_reduction_violations(changed)))
        # A feature-local reset remains valid and is not a retired global entry.
        feature = "business/src/main/kotlin/com/freedom/features/aireply/MomoPageObservationCoordinator.kt"
        changed = dict(self.recovery_code)
        changed[feature] += "\nfun reset() = Unit\n"
        self.assertEqual(guard.runtime_architecture_reduction_violations(changed), [])

    def test_pp_reduction_guard_permits_retiring_debt_and_ignores_comments(self):
        changed = dict(self.recovery_code)
        root = "app/src/main/java/com/freedom/app/AppCompositionRoot.kt"
        changed[root] = changed[root].replace("UnifiedPageObservationBus", "")
        changed.pop("app/src/main/java/com/freedom/application/runtime/EntryPageObservationRetry.kt")
        changed[root] += guard.mask_kotlin_non_code(
            '\n// UnifiedPageObservationBus() observer.registerChannel()\n'
            'val diagnostic = "PageRuntimeSessionState"\n'
        )
        self.assertEqual(guard.runtime_architecture_reduction_violations(changed), [])

    def test_p0_authority_guard_accepts_current_production_graph(self):
        self.assertEqual(guard.architecture_authority_violations(self.recovery_code), [])

    def test_p0_authority_guard_rejects_duplicate_or_missing_declarations(self):
        changed = dict(self.recovery_code)
        changed["business/src/main/kotlin/com/freedom/features/bad/BadCoordinator.kt"] = \
            "class TaskCoordinator()\n"
        self.assertTrue(any("declared exactly once" in item and "TaskCoordinator" in item
                            for item in guard.architecture_authority_violations(changed)))

        changed = dict(self.recovery_code)
        owner = guard.P0_AUTHORITY_DECLARATIONS["TaskCoordinator"]
        changed.pop(owner)
        self.assertTrue(any("declared exactly once" in item and "TaskCoordinator" in item
                            for item in guard.architecture_authority_violations(changed)))

    def test_p0_authority_guard_rejects_new_construction_and_extra_owner_instance(self):
        changed = dict(self.recovery_code)
        changed["business/src/main/kotlin/com/freedom/features/bad/NewTask.kt"] = \
            "val unapproved = TaskCoordinator(ownership)\n"
        self.assertTrue(any("construction drift" in item and "TaskCoordinator" in item
                            for item in guard.architecture_authority_violations(changed)))

        changed = dict(self.recovery_code)
        owner = "app/src/main/java/com/freedom/app/AppCompositionRoot.kt"
        changed[owner] += "\nval unintended = UiOperationOwnershipCoordinator()\n"
        self.assertTrue(any("construction drift" in item and "UiOperationOwnershipCoordinator" in item
                            for item in guard.architecture_authority_violations(changed)))

    def test_p0_authority_guard_freezes_per_route_recovery_owners(self):
        changed = dict(self.recovery_code)
        changed["business/src/main/kotlin/com/freedom/features/bad/OtherRecovery.kt"] = \
            "val unapproved = UnifiedRecoveryTransactionStateMachine()\n"
        self.assertTrue(any("construction drift" in item and "UnifiedRecoveryTransactionStateMachine" in item
                            for item in guard.architecture_authority_violations(changed)))

    def test_p0_authority_guard_limits_existing_transition_factories(self):
        changed = dict(self.recovery_code)
        browse = "app/src/main/java/com/freedom/adapters/accessibility/android/AndroidAccountBrowseWorkflow.kt"
        changed[browse] += "\nval unintended = PageObservationCoordinator(taskScheduler)\n"
        self.assertTrue(any("construction drift" in item and "PageObservationCoordinator" in item
                            for item in guard.architecture_authority_violations(changed)))

        changed = dict(self.recovery_code)
        wiring = "business/src/main/kotlin/com/freedom/features/aireply/CheckpointVerificationWiring.kt"
        changed[wiring] += "\nval unintended = RecoveryEventDispatcher(UnifiedRecoveryTransactionStateMachine())\n"
        violations = guard.architecture_authority_violations(changed)
        self.assertTrue(any("construction drift" in item and "RecoveryEventDispatcher" in item
                            for item in violations))
        self.assertTrue(any("construction drift" in item and "UnifiedRecoveryTransactionStateMachine" in item
                            for item in violations))

    def test_p0_authority_guard_rejects_alias_and_typealias_bypasses(self):
        changed = dict(self.recovery_code)
        changed["business/src/main/kotlin/com/freedom/features/bad/OtherObserver.kt"] = (
            "import com.freedom.core.runtime.PageObservationCoordinator as Observer\n"
            "val local = Observer(scheduler)\n"
        )
        self.assertTrue(any("alias bypass" in item
                            for item in guard.architecture_authority_violations(changed)))

        changed = dict(self.recovery_code)
        changed["business/src/main/kotlin/com/freedom/features/bad/OtherObserver.kt"] = \
            "typealias Shadow = com.freedom.core.runtime.TaskCoordinator\n"
        self.assertTrue(any("typealias bypass" in item
                            for item in guard.architecture_authority_violations(changed)))

    def test_p0_authority_guard_protects_core_dependency_direction(self):
        changed = dict(self.recovery_code)
        changed["core/src/main/kotlin/com/freedom/core/runtime/Bad.kt"] = \
            "import com.freedom.features.aireply.AiReplyObservationRuntime\n"
        self.assertTrue(any("core cannot import" in item
                            for item in guard.architecture_authority_violations(changed)))

    def test_p0_authority_guard_ignores_comments_and_literals(self):
        changed = dict(self.recovery_code)
        changed["business/src/main/kotlin/com/freedom/features/bad/Documentation.kt"] = \
            guard.mask_kotlin_non_code(
                '// TaskCoordinator() is documentation\n'
                'val help = "UiOperationOwnershipCoordinator()"\n'
                '/* class RecoveryScheduler() */\n'
            )
        self.assertEqual(guard.architecture_authority_violations(changed), [])

    def test_unified_accessibility_entry_requires_single_forwarding_callback(self):
        valid = {
            "app/src/main/java/com/freedom/accessibility/FreedomAccessibilityService.kt":
                "override fun onAccessibilityEvent(event: AccessibilityEvent?) {\n"
                "    accessibilityEventForwarder.accept(event)\n}\n"
        }
        self.assertEqual(guard.unified_accessibility_entry_violations(valid), [])

        bypass = dict(valid)
        bypass["app/src/main/java/com/freedom/accessibility/OtherService.kt"] = \
            "override fun onAccessibilityEvent(event: AccessibilityEvent?) { pipeline.process(event) }\n"
        violations = guard.unified_accessibility_entry_violations(bypass)
        self.assertTrue(any("declared exactly once" in item for item in violations))

    def test_checkpoint_restore_requires_read_only_verification_gate(self):
        valid = {
            "business/src/main/kotlin/com/freedom/features/accountbrowse/AccountBrowseRuntime.kt":
                "checkpointRepository?.load()?.let { stateMachine.transitionTo(AccountBrowsePhase.PAUSED); "
                "dynamicPageRecovery.restoreFromCheckpoint(1) }\n"
                "fun beginCheckpointVerification() {}\n"
                "fun observeDynamicFeedFingerprint(value: String) = true\n",
            "business/src/main/kotlin/com/freedom/features/accountbrowse/DynamicPageRecovery.kt":
                "CheckpointLoaded VerificationStarted ResumeRequested ResumeCompleted\n",
            "app/src/main/java/com/freedom/adapters/accessibility/android/AndroidAccountBrowseWorkflow.kt":
                "hasPendingCheckpointVerification beginCheckpointVerification observeDynamicFeedFingerprint\n",
        }
        self.assertEqual(guard.checkpoint_recovery_boundary_violations(valid), [])

        invalid = dict(valid)
        invalid["business/src/main/kotlin/com/freedom/features/accountbrowse/AccountBrowseRuntime.kt"] = \
            "checkpointRepository?.load()?.let { stateMachine.transitionTo(AccountBrowsePhase.FIRST_SWIPE_COUNTDOWN) }\n"
        violations = guard.checkpoint_recovery_boundary_violations(invalid)
        self.assertTrue(any("must enter PAUSED" in item for item in violations))
        self.assertTrue(any("must activate DynamicPageRecovery" in item for item in violations))

    def test_unified_recovery_state_machine_rejects_business_page_dependencies(self):
        code = {
            "app/src/main/java/com/freedom/features/recovery/UnifiedRecoveryTransactionStateMachine.kt":
                "import com.freedom.adapters.social.momo.MomoSocialAdapter\n"
                "class UnifiedRecoveryTransactionStateMachine { val page = ChatPageSnapshot() }"
        }
        violations = guard.recovery_transaction_boundary_violations(code)
        self.assertTrue(any("imports adapter" in item for item in violations))
        self.assertTrue(any("business-specific" in item for item in violations))

    def test_adapter_cannot_instantiate_or_force_resume_recovery_machine(self):
        code = {
            "app/src/main/java/com/freedom/adapters/social/momo/MomoSocialAdapter.kt":
                "private val recovery = UnifiedRecoveryTransactionStateMachine()\n"
        }
        violations = guard.recovery_transaction_boundary_violations(code)
        self.assertEqual(len(violations), 1)
        self.assertIn("bypasses", violations[0])

    def test_kotlin_scanner_ignores_comments_and_string_literals(self):
        source = '''
            // service.dispatchGesture(description)
            val text = "GestureCommand( is documentation only"
            /* GestureSpec( in a comment */
            actual.dispatchGesture(command)
        '''
        masked = guard.mask_kotlin_non_code(source)
        self.assertNotIn("service.dispatchGesture", masked)
        self.assertNotIn("GestureCommand(", masked)
        self.assertNotIn("GestureSpec(", masked)
        self.assertIn("actual.dispatchGesture(command)", masked)

    def test_gradle_dependency_parser_ignores_comments(self):
        import tempfile

        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "build.gradle.kts"
            path.write_text('''
                dependencies {
                    api(project(":core"))
                    // implementation(project(":gesture-runtime"))
                }
            ''', encoding="utf-8")
            self.assertEqual(guard.project_dependencies(path), {":core"})

    def test_runtime_dependency_policy_does_not_export_geometry_module(self):
        self.assertIn(":gesture-core", guard.ALLOWED_PROJECT_DEPENDENCIES[":gesture-runtime"])
        # The build-file-specific API/implementation rule is enforced by inspect_project.
        self.assertNotIn(":gesture-core", guard.ALLOWED_PROJECT_DEPENDENCIES[":business"])

    def test_business_module_cannot_add_runtime_dependency(self):
        actual = {":core", ":gesture-api", ":gesture-runtime"}
        allowed = guard.ALLOWED_PROJECT_DEPENDENCIES[":business"]
        self.assertEqual(actual - allowed, {":gesture-runtime"})

    def test_ocr_plugin_is_not_a_host_gradle_dependency(self):
        self.assertIn(":ocr-plugin", guard.EXPECTED_MODULES)
        self.assertIn(":ocr-plugin-api", guard.EXPECTED_MODULES)
        self.assertEqual(guard.ALLOWED_PROJECT_DEPENDENCIES[":ocr-plugin-api"], set())
        self.assertEqual(guard.ALLOWED_PROJECT_DEPENDENCIES[":ocr-plugin"], {":ocr-plugin-api"})
        self.assertIn(":ocr-plugin-api", guard.ALLOWED_PROJECT_DEPENDENCIES[":app"])
        self.assertNotIn(":ocr-plugin", guard.ALLOWED_PROJECT_DEPENDENCIES[":app"])

    def test_core_test_source_cannot_import_app_implementation(self):
        import tempfile

        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            bad = root / "core/src/test/kotlin/com/freedom/core/runtime/BadBoundaryTest.kt"
            bad.parent.mkdir(parents=True, exist_ok=True)
            bad.write_text(
                "package com.freedom.core.runtime\n"
                "import com.freedom.application.runtime.RunSessionCoordinator\n",
                encoding="utf-8",
            )
            self.assertEqual(
                guard.core_test_application_dependencies(root),
                ["core/src/test/kotlin/com/freedom/core/runtime/BadBoundaryTest.kt"],
            )

    def test_large_classes_are_advisories_not_automatic_violations(self):
        import tempfile

        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            source = root / "app/src/main/java/com/freedom/features/sample/LargeRuntime.kt"
            source.parent.mkdir(parents=True, exist_ok=True)
            source.write_text("\n".join(["class LargeRuntime {"] + ["    fun step() {}" for _ in range(1100)] + ["}"]), encoding="utf-8")
            advisories = guard.architecture_advisories(root)
            self.assertEqual(len(advisories), 1)
            self.assertIn("LargeRuntime.kt", advisories[0])

    def test_account_browse_runtime_owns_immutable_session_snapshot(self):
        state_path = "business/src/main/kotlin/com/freedom/features/accountbrowse/AccountBrowseSessionState.kt"
        runtime_path = "business/src/main/kotlin/com/freedom/features/accountbrowse/AccountBrowseRuntime.kt"
        adapter_path = "app/src/main/java/com/freedom/adapters/accessibility/android/Workflow.kt"
        state_code = """
            internal class AccountBrowseSessionStateOwner {
                data class AccountBrowseSessionSnapshot(val pauseReason: String = "")
            }
        """
        runtime_code = """
            private val sessionStateOwner = AccountBrowseSessionStateOwner()
            val session: AccountBrowseSessionStatePort = sessionStateOwner.port
        """
        base = {state_path: state_code, runtime_path: runtime_code}
        self.assertEqual(guard.account_browse_state_ownership_leaks(base, state_code), [])

        leaking_adapter = dict(base)
        leaking_adapter[adapter_path] = "private val owner = AccountBrowseSessionStateOwner()"
        self.assertEqual(
            guard.account_browse_state_ownership_leaks(leaking_adapter, state_code),
            [adapter_path],
        )

        mutable_snapshot = state_code.replace("val pauseReason", "var pauseReason")
        self.assertEqual(
            guard.account_browse_state_ownership_leaks(base, mutable_snapshot),
            [state_path],
        )

    def test_account_browse_session_owner_cannot_be_constructed_outside_runtime(self):
        state_path = "business/src/main/kotlin/com/freedom/features/accountbrowse/AccountBrowseSessionState.kt"
        runtime_path = "business/src/main/kotlin/com/freedom/features/accountbrowse/AccountBrowseRuntime.kt"
        other_path = "business/src/test/kotlin/com/freedom/features/accountbrowse/RuntimeTest.kt"
        state_code = "internal class AccountBrowseSessionStateOwner { data class AccountBrowseSessionSnapshot(val pauseReason: String = "") }"
        code = {
            state_path: state_code,
            runtime_path: "private val sessionStateOwner = AccountBrowseSessionStateOwner()\nval session: AccountBrowseSessionStatePort = sessionStateOwner.port",
            other_path: "val owner = AccountBrowseSessionStateOwner()",
        }
        self.assertEqual(guard.account_browse_state_ownership_leaks(code, state_code), [other_path])

    def test_agents_fragments_must_match_archived_source(self):
        import tempfile

        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            parts = root / "docs/agent/parts"
            archive = root / "docs/agent/source/AGENTS.original.md"
            parts.mkdir(parents=True)
            archive.parent.mkdir(parents=True)
            archive.write_bytes(b"authoritative\ntext\n")
            for index, content in enumerate((b"authoritative\n", b"text\n", b"", b"", b"", b"", b"", b"")):
                (parts / f"{index:02d}-part.md").write_bytes(content)
            self.assertEqual(b"".join(p.read_bytes() for p in sorted(parts.glob("*.md"))), archive.read_bytes())
            (parts / "01-part.md").write_bytes(b"changed\n")
            self.assertNotEqual(b"".join(p.read_bytes() for p in sorted(parts.glob("*.md"))), archive.read_bytes())


    def test_current_agents_manifest_and_parts_pass_integrity_check(self):
        root = Path(__file__).resolve().parents[2]
        self.assertEqual(guard.agents_integrity_violations(root), [])

    def test_agents_integrity_rejects_modified_or_missing_content(self):
        import shutil
        import tempfile

        with tempfile.TemporaryDirectory() as tmp:
            repository = Path(__file__).resolve().parents[2]
            root = Path(tmp)
            shutil.copytree(repository / "docs/agent", root / "docs/agent")
            shutil.copyfile(repository / "AGENTS.md", root / "AGENTS.md")

            self.assertEqual(guard.agents_integrity_violations(root), [])
            fragment = next((root / "docs/agent/parts").glob("01-*.md"))
            original = fragment.read_bytes()
            fragment.write_bytes(original + b"corrupted")
            self.assertTrue(any("SHA-256 mismatch" in item for item in
                                guard.agents_integrity_violations(root)))
            fragment.write_bytes(original)

            fragment.unlink()
            self.assertTrue(any("missing part" in item for item in
                                guard.agents_integrity_violations(root)))
            fragment.write_bytes(original)

            archive = root / "docs/agent/source/Android_无障碍落地开发手册.original.md"
            archive_content = archive.read_bytes()
            archive.write_bytes(archive_content + b"corrupted")
            self.assertTrue(any("SHA-256 mismatch" in item for item in
                                guard.agents_integrity_violations(root)))
            archive.write_bytes(archive_content)

            entry = root / "AGENTS.md"
            entry.write_bytes(b"modified prefix\n" + entry.read_bytes())
            self.assertTrue(any("original index prefix" in item for item in
                                guard.agents_integrity_violations(root)))

    def test_agents_integrity_rejects_manifest_mismatch(self):
        import json
        import shutil
        import tempfile

        with tempfile.TemporaryDirectory() as tmp:
            repository = Path(__file__).resolve().parents[2]
            root = Path(tmp)
            shutil.copytree(repository / "docs/agent", root / "docs/agent")
            shutil.copyfile(repository / "AGENTS.md", root / "AGENTS.md")
            manifest_path = root / "docs/agent/INTEGRITY.json"
            manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
            manifest["parts"][0]["sha256"] = "0" * 64
            manifest_path.write_text(json.dumps(manifest, ensure_ascii=False), encoding="utf-8")
            self.assertTrue(any("SHA-256 mismatch" in item for item in
                                guard.agents_integrity_violations(root)))



if __name__ == "__main__":
    unittest.main(verbosity=2)
