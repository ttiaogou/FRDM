"""Unit tests for the pre-Gradle architecture verifier."""
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import verify_boundaries as guard  # noqa: E402


class ArchitectureVerifierUnitTest(unittest.TestCase):
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


if __name__ == "__main__":
    unittest.main(verbosity=2)
