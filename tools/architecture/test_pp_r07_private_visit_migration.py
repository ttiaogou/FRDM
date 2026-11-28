"""R07-C: exact relocation of private visit, identity and return state."""
import re
import unittest
from pathlib import Path
from tools.architecture.test_pp_r00_freeze import git_blob
from tools.architecture.test_pp_r07_greeting_visit_migration import restore_r07c_runtime

ROOT = Path(__file__).resolve().parents[2]
AI = ROOT / "business/src/main/kotlin/com/freedom/features/aireply/"
PREVIOUS = "046026a655768a091e5ce70e1b8cdc37f9291539"
PRE_FIX_OWNER = "a38408df5c2779a0efc632f238bfb8c18e44f8f2"
OWNER = "04f11c0f5b1ac69891478a053d6b05f60bb38d6b"
REJECTED_TAP_CALL = "privateMessagesTraversalTaskExecutor.rememberUnverifiedPrivateTap(attempt.selectedKey)"
OLD_REJECTED_TAP_CALL = "rememberUnverifiedPrivateTap(attempt.selectedKey)"
RETRY_DECLARATION = "internal fun rememberUnverifiedPrivateTap(selectedKey: String) {"
OLD_RETRY_DECLARATION = "private fun rememberUnverifiedPrivateTap(selectedKey: String) {"
FIELDS = [
  "currentSessionBinding",
  "expectedPrivateSessionDetailNavigation",
  "incompleteUnreadRelocationWait",
  "identityCaptureSessionKey",
  "identityCaptureStartedAtMs",
  "identityCaptureRetryCount",
  "identityCaptureTerminalSessionKey",
  "identityCaptureTimeoutTask",
  "missedPrivateSessionTapKey",
  "missedPrivateSessionTapCount",
  "confirmedPrivateReturnBackRetryCount",
  "confirmedPrivateReturnAwaitingRecovery",
  "sessionSafeViewportRetryTask",
  "sessionSafeViewportRetryCount",
  "sessionSafeViewportRetryGeneration"
]
INVERSE = [
  [
    "privateMessagesTraversalTaskExecutor.sessionSafeViewportRetryGeneration",
    "sessionSafeViewportRetryGeneration",
    3
  ],
  [
    "privateMessagesTraversalTaskExecutor.sessionSafeViewportRetryCount",
    "sessionSafeViewportRetryCount",
    4
  ],
  [
    "privateMessagesTraversalTaskExecutor.sessionSafeViewportRetryTask",
    "sessionSafeViewportRetryTask",
    5
  ],
  [
    "privateMessagesTraversalTaskExecutor.confirmedPrivateReturnAwaitingRecovery",
    "confirmedPrivateReturnAwaitingRecovery",
    8
  ],
  [
    "privateMessagesTraversalTaskExecutor.confirmedPrivateReturnBackRetryCount",
    "confirmedPrivateReturnBackRetryCount",
    5
  ],
  [
    "privateMessagesTraversalTaskExecutor.missedPrivateSessionTapCount",
    "missedPrivateSessionTapCount",
    3
  ],
  [
    "privateMessagesTraversalTaskExecutor.missedPrivateSessionTapKey",
    "missedPrivateSessionTapKey",
    3
  ],
  [
    "privateMessagesTraversalTaskExecutor.identityCaptureTimeoutTask",
    "identityCaptureTimeoutTask",
    7
  ],
  [
    "privateMessagesTraversalTaskExecutor.identityCaptureTerminalSessionKey",
    "identityCaptureTerminalSessionKey",
    12
  ],
  [
    "privateMessagesTraversalTaskExecutor.identityCaptureRetryCount",
    "identityCaptureRetryCount",
    5
  ],
  [
    "privateMessagesTraversalTaskExecutor.identityCaptureStartedAtMs",
    "identityCaptureStartedAtMs",
    4
  ],
  [
    "privateMessagesTraversalTaskExecutor.identityCaptureSessionKey",
    "identityCaptureSessionKey",
    3
  ],
  [
    "privateMessagesTraversalTaskExecutor.incompleteUnreadRelocationWait",
    "incompleteUnreadRelocationWait",
    12
  ],
  [
    "privateMessagesTraversalTaskExecutor.expectedPrivateSessionDetailNavigation",
    "expectedPrivateSessionDetailNavigation",
    15
  ],
  [
    "privateMessagesTraversalTaskExecutor.currentSessionBinding",
    "currentSessionBinding",
    49
  ],
  [
    "${missedPrivateSessionTapCount}",
    "$missedPrivateSessionTapCount",
    1
  ],
  [
    "        privateMessagesTraversalTaskExecutor.rebindVerifiedDetail(original, freshWindowKey, freshSurfaceKey)\n",
    "        currentSessionBinding = original.copy(\n            chatWindowKey = freshWindowKey,\n            chatSurfaceKey = freshSurfaceKey\n        )\n        currentSessionWindowKey = freshWindowKey\n",
    1
  ],
  [
    "            privateMessagesTraversalTaskExecutor.bindVerifiedDetail(selected, chatWindowKey, snapshot)\n",
    "            currentSessionBinding = CurrentSessionBinding(\n                sourceWindowKey = listOf(\n                    selected.rowTarget.window.packageName,\n                    selected.rowTarget.window.windowId,\n                    selected.rowTarget.window.generation\n                ).joinToString(\"|\"),\n                sourceRowPath = selected.rowTarget.path.childIndices.joinToString(\".\"),\n                sourceRowBounds = selected.rowBounds,\n                sourceAvatarBounds = selected.avatarBounds,\n                chatWindowKey = chatWindowKey,\n                chatSurfaceKey = chatSurfaceKey(snapshot).orEmpty()\n            )\n            // Only a verified detail binding retires a no-navigation retry sequence.\n            if (missedPrivateSessionTapKey == currentSessionKey) {\n                missedPrivateSessionTapKey = null\n                missedPrivateSessionTapCount = 0\n            }\n",
    1
  ],
  [
    "        privateMessagesTraversalTaskExecutor.rollbackProvisionalVisit(selectedKey)\n",
    "        if (privateMessagesTraversalTaskExecutor.attemptedSessionKeys.remove(selectedKey)) {\n            privateMessagesTraversalTaskExecutor.sessionActionCount =\n                (privateMessagesTraversalTaskExecutor.sessionActionCount - 1).coerceAtLeast(0)\n        }\n        rememberUnverifiedPrivateTap(selectedKey)\n        privateMessagesTraversalTaskExecutor.clearCurrentSession()\n        expectedPrivateSessionDetailNavigation = null\n        currentSessionBinding = null\n",
    1
  ],
  [
    "    private fun rollbackUnverifiedPrivateSessionTap",
    "    /**\n     * A submitted tap is provisional. No-op navigation and page-route recovery must\n     * not persist its selected key as a completed/attempted session. Preserve the\n     * existing traversal checkpoint; only the attempted marker and tentative detail\n     * binding are rolled back, before the unified read-only recovery is saved.\n     */\n    private fun rememberUnverifiedPrivateTap(selectedKey: String) {\n        missedPrivateSessionTapCount = if (missedPrivateSessionTapKey == selectedKey) {\n            missedPrivateSessionTapCount + 1\n        } else {\n            missedPrivateSessionTapKey = selectedKey\n            1\n        }\n    }\n\n    private fun rollbackUnverifiedPrivateSessionTap",
    1
  ],
  [
    "    /**\n     * Identity capture may fail",
    "    private var identityCaptureSessionKey: String? = null\n    private var identityCaptureStartedAtMs = 0L\n    private var identityCaptureRetryCount = 0\n    private var identityCaptureTerminalSessionKey: String? = null\n    private var identityCaptureTimeoutTask: ScheduledTask? = null\n\n    /**\n     * Identity capture may fail",
    1
  ],
  [
    "    private fun establishCurrentSessionBinding",
    "    private var currentSessionBinding: CurrentSessionBinding? = null\n    private fun establishCurrentSessionBinding",
    1
  ],
  [
    "    /**\n     * Source-anchored greeting chat binding",
    "    private data class CurrentSessionBinding(\n        val sourceWindowKey: String,\n        val sourceRowPath: String?,\n        val sourceRowBounds: ScreenRect?,\n        val sourceAvatarBounds: ScreenRect?,\n        val chatWindowKey: String,\n        val chatSurfaceKey: String,\n        val observedDetailTarget: ObservedPrivateChatIdentityTarget? = null,\n        val observedVerifiedIdentity: ContactRowIdentity? = null,\n        val observedTaskLease: TaskLease? = null,\n        val observedBusinessAdmitted: Boolean = false\n    ) {\n        fun matches(snapshot: ChatPageSnapshot, currentChatWindowKey: String?): Boolean =\n            currentChatWindowKey == chatWindowKey &&\n                com.freedom.features.aireply.chatSurfaceKey(snapshot) == chatSurfaceKey\n\n        /**\n         * Profile navigation may recreate the chat window and rebuild both child paths.\n         * Once a platform ID is verified, use the restored chat surface plus the caller's\n         * auxiliary header check; do not require the old window generation or node paths.\n         */\n        fun matchesRestoredChatSurface(snapshot: ChatPageSnapshot): Boolean =\n            snapshot.pageKind == ChatPageKind.CHAT_DETAIL_CANDIDATE &&\n                snapshot.packageName.isNotBlank() &&\n                snapshot.inputFieldTarget != null\n\n    }\n\n    /**\n     * Source-anchored greeting chat binding",
    1
  ],
  [
    "    /** Set by relevant target-app page-change events",
    "    /** Intent only: the timer never authorizes a gesture or stores a row locator. */\n    private data class IncompleteUnreadRelocationWait(\n        val runGeneration: Long,\n        val actionGeneration: Long,\n        val windowIdentity: String,\n        val visibleKeys: List<String>,\n        var due: Boolean = false\n    )\n    private var incompleteUnreadRelocationWait: IncompleteUnreadRelocationWait? = null\n    /** A submitted session tap is not a completed visit until the intended detail is verified. */\n    private var missedPrivateSessionTapKey: String? = null\n    private var missedPrivateSessionTapCount = 0\n    /** Count only re-dispatched BACK actions, never the read-only verification samples. */\n    private var confirmedPrivateReturnBackRetryCount = 0\n    /** A unified list recovery after confirmed send must finish the old return before traversal. */\n    private var confirmedPrivateReturnAwaitingRecovery = false\n    /** Retains the scroll direction while the live mf_view exclusion leaves no safe rectangle. */\n    private var sessionSafeViewportRetryTask: ScheduledTask? = null\n    private var sessionSafeViewportRetryCount = 0\n    private var sessionSafeViewportRetryGeneration = 0L\n    /** Set by relevant target-app page-change events",
    1
  ],
  [
    "    /**\n     * Business-neutral navigation provenance",
    "    /** Captures the self-initiated list-to-detail navigation awaiting page verification. */\n    private data class ExpectedPrivateSessionDetailNavigation(\n        val sessionKey: String,\n        val sessionName: String?,\n        val sourceWindowKey: String,\n        val startedAtUptimeMs: Long\n    )\n    private var expectedPrivateSessionDetailNavigation: ExpectedPrivateSessionDetailNavigation? = null\n    /**\n     * Business-neutral navigation provenance",
    1
  ]
]

def restore_r07b_runtime(runtime, owner=None):
    runtime = restore_r07c_runtime(runtime)
    if owner is None:
        owner = (AI / "PrivateMessagesTraversalTaskExecutor.kt").read_text(encoding="utf-8")
    if git_blob(owner.encode("utf-8")) != OWNER:
        raise AssertionError("R07-C private visit owner changed")
    # Reverse the exact compile-only repair before the historical R07-C inverse.
    if owner.count(RETRY_DECLARATION) != 1 or runtime.count(REJECTED_TAP_CALL) != 1:
        raise AssertionError("R07-C rejected tap call or owner declaration changed")
    if git_blob(owner.replace(RETRY_DECLARATION, OLD_RETRY_DECLARATION, 1).encode("utf-8")) != PRE_FIX_OWNER:
        raise AssertionError("R07-C owner did not reconstruct the pre-fix source")
    runtime = runtime.replace(REJECTED_TAP_CALL, OLD_REJECTED_TAP_CALL, 1)
    for current, previous, count in INVERSE:
        if runtime.count(current) != count:
            raise AssertionError("R07-C inverse anchor changed: " + current[:100])
        runtime = runtime.replace(current, previous)
    if git_blob(runtime.encode("utf-8")) != PREVIOUS:
        raise AssertionError("R07-C did not reconstruct exact R07-B Runtime")
    return runtime

class PpR07PrivateVisitMigrationTest(unittest.TestCase):
    def test_exact_inverse_and_single_visit_state_owner(self):
        runtime = (AI / "AiReplyObservationRuntime.kt").read_text(encoding="utf-8")
        owner = (AI / "PrivateMessagesTraversalTaskExecutor.kt").read_text(encoding="utf-8")
        self.assertEqual(PREVIOUS, git_blob(restore_r07b_runtime(runtime, owner).encode("utf-8")))
        for state in FIELDS:
            declaration = r"\bvar\s+" + re.escape(state) + r"\b"
            self.assertEqual(1, len(re.findall(declaration, owner)), state)
            self.assertNotRegex(runtime, declaration)
        for method in ("bindVerifiedDetail", "rebindVerifiedDetail", "rollbackProvisionalVisit"):
            self.assertIn("privateMessagesTraversalTaskExecutor." + method + "(", runtime)
            self.assertEqual(1, owner.count("fun " + method + "("))
        self.assertIn("recoveryContext.recordPauseState(value == SessionManagerState.PAUSED)", runtime)
        self.assertIn("private var pendingNavigationSourceResult: SelfNavigationTransition?", runtime)
        self.assertIn("private var sessionTransitionTask: ScheduledTask?", runtime)
        self.assertNotIn("scheduler.schedule(", owner)
        self.assertNotIn("dispatchGesture(", owner)
        self.assertEqual(1, runtime.count(REJECTED_TAP_CALL))
        self.assertEqual(1, owner.count(RETRY_DECLARATION))
        self.assertNotRegex(runtime, r"(?<!\.)\brememberUnverifiedPrivateTap\s*\(")

    def test_visit_rollback_and_identity_mutations_rejected(self):
        runtime = (AI / "AiReplyObservationRuntime.kt").read_text(encoding="utf-8")
        owner = (AI / "PrivateMessagesTraversalTaskExecutor.kt").read_text(encoding="utf-8")
        for old, new in (
            ("if (attemptedSessionKeys.remove(selectedKey))", "if (true)"),
            ("expectedPrivateSessionDetailNavigation = null", "Unit"),
            ("currentSessionBinding = null", "Unit"),
            ("currentChatWindowKey == chatWindowKey", "true"),
        ):
            self.assertIn(old, owner)
            with self.subTest(old=old), self.assertRaises(AssertionError):
                restore_r07b_runtime(runtime, owner.replace(old, new, 1))
        for old, new in (
            (REJECTED_TAP_CALL, OLD_REJECTED_TAP_CALL),
            ("privateMessagesTraversalTaskExecutor.rollbackProvisionalVisit(selectedKey)", "Unit"),
            ("privateMessagesTraversalTaskExecutor.identityCaptureTimeoutTask?.cancel()", "Unit"),
        ):
            self.assertIn(old, runtime)
            with self.subTest(old=old), self.assertRaises(AssertionError):
                restore_r07b_runtime(runtime.replace(old, new, 1), owner)
