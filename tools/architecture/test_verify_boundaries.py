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
