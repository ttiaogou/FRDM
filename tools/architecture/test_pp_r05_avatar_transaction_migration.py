"""R05-C: exact reviewed inverse patch plus preserved prior owner and transaction bodies."""
import unittest
from pathlib import Path
from tools.architecture.test_pp_r00_freeze import git_blob
from tools.architecture.test_pp_r05_draft_transaction_migration import restore_r05c_adapter

ROOT = Path(__file__).resolve().parents[2]
MOMO = ROOT / "app/src/main/java/com/freedom/adapters/social/momo/"
BASELINE = "4d20cf338a6c9eca0721e8d005763e82a4e68268"
PREVIOUS_ADAPTER = "a1cb58a1eaecb734a48a58656e4bdd454b52466d"
PREVIOUS_OWNER = "2df5450adbc3d941d6df48a5b8a4bddba8700129"
RELOCATED_BODY = "9f3755b9890ad33d953f70e374fcac2d7f7a9a1d"

# Each pair is the exact reviewed changed facade region and its previous version.
# A change in either the remaining facade or the relocated body fails closed.
REVERSE_PATCH = [
    [
        "    ) = sessionComponents.inboxIdentity.transitionAvatarClickState(pending, next, reason, onLog)\n\n    private fun releaseAvatarCaptureForRetry(\n        pending: PendingInboxIdentityCapture,\n        reason: String\n    ) = sessionComponents.inboxIdentity.releaseAvatarCaptureForRetry(pending, reason, onLog)\n\n    private fun releaseAvatarCaptureAfterProfileMiss(\n        pending: PendingInboxIdentityCapture,\n        reason: MomoAvatarClickFailureReason\n    ) = sessionComponents.inboxIdentity.releaseAvatarCaptureAfterProfileMiss(pending, reason, onLog)",
        "    ) {\n        if (pending == null) return\n        val previous = pending.avatarClickState\n        if (previous == next) return\n        if (!previous.canTransitionTo(next)) {\n            onLog(\n                \"AI 头像点击事务：非法状态转换被拒绝；\" +\n                    \"from=${previous.name}；to=${next.name}；reason=$reason\"\n            )\n            return\n        }\n        pending.avatarClickState = next\n        onLog(\n            \"AI 头像点击事务状态：from=${previous.name}；to=${next.name}；\" +\n                \"reason=$reason\"\n        )\n    }\n\n    private fun releaseAvatarCaptureForRetry(\n        pending: PendingInboxIdentityCapture,\n        reason: String\n    ) {\n        transitionAvatarClickState(\n            pending,\n            MomoAvatarClickTransactionState.AVATAR_RETRY_WAITING,\n            reason\n        )\n        synchronized(sessionComponents.inboxIdentity.inboxIdentityCaptureLock) {\n            if (sessionComponents.inboxIdentity.pendingInboxIdentityCapture === pending) sessionComponents.inboxIdentity.pendingInboxIdentityCapture = null\n        }\n    }\n\n    private fun releaseAvatarCaptureAfterProfileMiss(\n        pending: PendingInboxIdentityCapture,\n        reason: MomoAvatarClickFailureReason\n    ) {\n        transitionAvatarClickState(\n            pending,\n            MomoAvatarClickTransactionState.AVATAR_RETRY_WAITING,\n            reason.name\n        )\n        synchronized(sessionComponents.inboxIdentity.inboxIdentityCaptureLock) {\n            if (sessionComponents.inboxIdentity.pendingInboxIdentityCapture === pending) sessionComponents.inboxIdentity.pendingInboxIdentityCapture = null\n        }\n        onLog(\n            \"AI 头像点击事务：资料页进入失败；释放旧头像事务，下一次观察重新读取聊天详情并重新定位头像；\" +\n                \"reason=${reason.name}；不暂停任务、不标记身份失败、不使用旧坐标、不点击整行\"\n        )\n    }"
    ],
    [
        "                    sessionComponents.inboxIdentity.cachedPlatformId(key)",
        "                    synchronized(sessionComponents.inboxIdentity.inboxIdentityCaptureLock) { sessionComponents.inboxIdentity.verifiedInboxIdentityByAnchor[key] }"
    ],
    [
        "                        sessionComponents.inboxIdentity.invalidateCachedIdentity(key, verificationResult, onLog)",
        "                        synchronized(sessionComponents.inboxIdentity.inboxIdentityCaptureLock) {\n                            sessionComponents.inboxIdentity.verifiedInboxIdentityByAnchor.remove(key)\n                            if (verificationResult.isTerminalFailure()) {\n                                sessionComponents.inboxIdentity.failedInboxIdentityCaptureKeys += key\n                                sessionComponents.inboxIdentity.onIdentityVerificationLog(onLog, verificationResult, \"EXPLICIT_IDENTITY_CONFLICT\", true, \"SKIP_CURRENT_TARGET\", key)\n                            } else {\n                                sessionComponents.inboxIdentity.failedInboxIdentityCaptureKeys.remove(key)\n                                sessionComponents.inboxIdentity.onIdentityVerificationLog(onLog, verificationResult, \"PROFILE_ID_NOT_CONFIRMED\", false, \"RETRY_ON_NEXT_OBSERVATION\", key)\n                            }\n                        }"
    ],
    [
        "        sessionComponents.inboxIdentity.hasReturnReinspection()",
        "        synchronized(sessionComponents.inboxIdentity.inboxIdentityCaptureLock) { sessionComponents.inboxIdentity.identityReturnReinspectionPending }"
    ],
    [
        "        sessionComponents.inboxIdentity.checkCaptureAdmission(anchorKey, onLog)?.let { return it }",
        "        synchronized(sessionComponents.inboxIdentity.inboxIdentityCaptureLock) {\n            if (anchorKey in sessionComponents.inboxIdentity.failedInboxIdentityCaptureKeys) {\n                onLog(\"IDENTITY_CAPTURE_BLOCKED result=IDENTITY_CAPTURE_BLOCKED；reason=EXPLICIT_IDENTITY_CONFLICT；failedCacheHit=true；action=SKIP_CURRENT_TARGET；anchorHash=${anchorKey.take(12)}\")\n                return ContactIdentityCaptureRequestResult.REJECTED\n            }\n            onLog(\"IDENTITY_RETRY_ALLOWED result=IDENTITY_RETRY_ALLOWED；previousFailure=NONE_OR_RETRYABLE；failedCacheHit=false；action=REACQUIRE_AVATAR；anchorHash=${anchorKey.take(12)}\")\n            val pending = sessionComponents.inboxIdentity.pendingInboxIdentityCapture\n            if (pending != null) return ContactIdentityCaptureRequestResult.IN_PROGRESS\n        }"
    ],
    [
        "        sessionComponents.inboxIdentity.registerCapture(anchorKey, pending, onLog)?.let { return it }",
        "        synchronized(sessionComponents.inboxIdentity.inboxIdentityCaptureLock) {\n            if (anchorKey in sessionComponents.inboxIdentity.failedInboxIdentityCaptureKeys) {\n                onLog(\"IDENTITY_CAPTURE_BLOCKED result=IDENTITY_CAPTURE_BLOCKED；reason=EXPLICIT_IDENTITY_CONFLICT；failedCacheHit=true；action=SKIP_CURRENT_TARGET；anchorHash=${anchorKey.take(12)}\")\n                return ContactIdentityCaptureRequestResult.REJECTED\n            }\n            if (sessionComponents.inboxIdentity.pendingInboxIdentityCapture != null) {\n                return ContactIdentityCaptureRequestResult.IN_PROGRESS\n            }\n            sessionComponents.inboxIdentity.pendingInboxIdentityCapture = pending\n        }"
    ],
    [
        "                sessionComponents.inboxIdentity.applyAvatarGestureResult(pending, result.status, onLog)",
        "                synchronized(sessionComponents.inboxIdentity.inboxIdentityCaptureLock) {\n                    if (sessionComponents.inboxIdentity.pendingInboxIdentityCapture !== pending) return@synchronized\n                    if (result.status == GestureExecutionStatus.COMPLETED) {\n                        pending.stage = InboxIdentityCaptureStage.WAITING_PROFILE\n                        pending.lastTransitionAtMs = System.currentTimeMillis()\n                        transitionAvatarClickState(\n                            pending,\n                            MomoAvatarClickTransactionState.AVATAR_ENTRY_VERIFYING,\n                            \"头像手势完成，等待资料页页面确认\"\n                        )\n                    } else {\n                        transitionAvatarClickState(\n                            pending,\n                            MomoAvatarClickTransactionState.AVATAR_RETRY_WAITING,\n                            \"头像手势未完成；不将本次点击直接视为事务终止\"\n                        )\n                        sessionComponents.inboxIdentity.pendingInboxIdentityCapture = null\n                    }\n                }"
    ],
    [
        "        }.getOrElse {\n            sessionComponents.inboxIdentity.releaseIfCurrent(pending)",
        "        }.getOrElse {\n            synchronized(sessionComponents.inboxIdentity.inboxIdentityCaptureLock) {\n                if (sessionComponents.inboxIdentity.pendingInboxIdentityCapture === pending) sessionComponents.inboxIdentity.pendingInboxIdentityCapture = null\n            }"
    ],
    [
        "            )\n            sessionComponents.inboxIdentity.releaseIfCurrent(pending)",
        "            )\n            synchronized(sessionComponents.inboxIdentity.inboxIdentityCaptureLock) {\n                if (sessionComponents.inboxIdentity.pendingInboxIdentityCapture === pending) sessionComponents.inboxIdentity.pendingInboxIdentityCapture = null\n            }"
    ],
    [
        "        return sessionComponents.inboxIdentity.hasObservedCapture(key)\n    }\n\n    override fun hasObservedPrivateChatIdentityReturn(target: ObservedPrivateChatIdentityTarget): Boolean {\n        val key = createObservedDetailIdentityAnchor(target)?.opaqueKey() ?: return false\n        return sessionComponents.inboxIdentity.hasObservedReturn(key)\n    }\n\n    override fun consumeObservedPrivateChatIdentityReturn(target: ObservedPrivateChatIdentityTarget): Boolean {\n        val key = createObservedDetailIdentityAnchor(target)?.opaqueKey() ?: return false\n        return sessionComponents.inboxIdentity.consumeObservedReturn(key)\n    }\n\n    override fun cancelObservedPrivateChatIdentityCapture(target: ObservedPrivateChatIdentityTarget) {\n        val key = createObservedDetailIdentityAnchor(target)?.opaqueKey() ?: return\n        sessionComponents.inboxIdentity.cancelObservedCapture(key)",
        "        return synchronized(sessionComponents.inboxIdentity.inboxIdentityCaptureLock) {\n            sessionComponents.inboxIdentity.pendingInboxIdentityCapture?.anchor?.opaqueKey() == key\n        }\n    }\n\n    override fun hasObservedPrivateChatIdentityReturn(target: ObservedPrivateChatIdentityTarget): Boolean {\n        val key = createObservedDetailIdentityAnchor(target)?.opaqueKey() ?: return false\n        return synchronized(sessionComponents.inboxIdentity.inboxIdentityCaptureLock) { sessionComponents.inboxIdentity.observedDetailIdentityReturnKey == key }\n    }\n\n    override fun consumeObservedPrivateChatIdentityReturn(target: ObservedPrivateChatIdentityTarget): Boolean {\n        val key = createObservedDetailIdentityAnchor(target)?.opaqueKey() ?: return false\n        return synchronized(sessionComponents.inboxIdentity.inboxIdentityCaptureLock) {\n            if (sessionComponents.inboxIdentity.observedDetailIdentityReturnKey != key) false else {\n                sessionComponents.inboxIdentity.observedDetailIdentityReturnKey = null\n                true\n            }\n        }\n    }\n\n    override fun cancelObservedPrivateChatIdentityCapture(target: ObservedPrivateChatIdentityTarget) {\n        val key = createObservedDetailIdentityAnchor(target)?.opaqueKey() ?: return\n        synchronized(sessionComponents.inboxIdentity.inboxIdentityCaptureLock) {\n            if (sessionComponents.inboxIdentity.pendingInboxIdentityCapture?.anchor?.opaqueKey() == key) sessionComponents.inboxIdentity.pendingInboxIdentityCapture = null\n            sessionComponents.inboxIdentity.completedInboxIdentityOutcomes.remove(key)\n            if (sessionComponents.inboxIdentity.observedDetailIdentityReturnKey == key) sessionComponents.inboxIdentity.observedDetailIdentityReturnKey = null\n            sessionComponents.inboxIdentity.failedInboxIdentityCaptureKeys.remove(key)\n        }"
    ],
    [
        "    ): ContactIdentityCaptureOutcome = sessionComponents.inboxIdentity.observeContactIdentityCapture(anchor, requestCapture, onLog)",
        "    ): ContactIdentityCaptureOutcome {\n        val key = anchor.opaqueKey()\n        synchronized(sessionComponents.inboxIdentity.inboxIdentityCaptureLock) {\n            sessionComponents.inboxIdentity.completedInboxIdentityOutcomes[key]?.let { return it }\n            sessionComponents.inboxIdentity.pendingInboxIdentityCapture?.let { pending ->\n                if (pending.anchor.opaqueKey() == key) {\n                    // IN_PROGRESS is a non-terminal state. Keep the reason in the\n                    // explicit OBSERVE log, never in failureReason.\n                    onLog(\"AI 联系人身份采集[OBSERVE_WAITING]：事务仍在进行；不返回失败原因\")\n                    return ContactIdentityCaptureOutcome.waiting(\n                        ContactIdentityCaptureRequestResult.IN_PROGRESS\n                    )\n                }\n            }\n        }\n        return when (val request = requestCapture()) {\n            ContactIdentityCaptureRequestResult.STARTED,\n            ContactIdentityCaptureRequestResult.IN_PROGRESS,\n            ContactIdentityCaptureRequestResult.RETRY_LATER ->\n                // Waiting is not a terminal failure. The outcome contract rejects a\n                // failureReason while awaiting=true; stage details are emitted by the\n                // explicit [OBSERVE] and [REQUEST] logs above.\n                ContactIdentityCaptureOutcome.waiting(request)\n            ContactIdentityCaptureRequestResult.NOT_SUPPORTED ->\n                ContactIdentityCaptureOutcome.failed(request, \"IDENTITY_CAPTURE_NOT_SUPPORTED\")\n            ContactIdentityCaptureRequestResult.REJECTED ->\n                ContactIdentityCaptureOutcome.failed(request, \"IDENTITY_CAPTURE_REQUEST_REJECTED\")\n        }.also { outcome ->\n            onLog(\n                \"AI 联系人身份采集[OBSERVE_RESULT]：\" +\n                    \"request=${outcome.request.name}；awaiting=${outcome.awaiting}；\" +\n                    \"verified=${outcome.verifiedIdentity != null}；failure=${outcome.failureReason ?: \"NONE\"}\"\n            )\n        }\n    }"
    ],
    [
        "                    sessionComponents.inboxIdentity.publishVerifiedReturn(pending)",
        "                    synchronized(sessionComponents.inboxIdentity.inboxIdentityCaptureLock) {\n                        if (pending.anchor is ObservedDetailIdentityAnchor) {\n                            val key = pending.anchor.opaqueKey()\n                            if (sessionComponents.inboxIdentity.completedInboxIdentityOutcomes[key]?.verifiedIdentity != null) {\n                                sessionComponents.inboxIdentity.observedDetailIdentityReturnKey = key\n                            }\n                        } else {\n                            sessionComponents.inboxIdentity.identityReturnReinspectionPending = true\n                        }\n                    }"
    ],
    [
        "    ) = sessionComponents.inboxIdentity.failPendingInboxIdentityCapture(pending, reason, onLog)",
        "    ) {\n        // Row geometry/path may change while the same profile transaction is unwinding;\n        // deduplicate by the redacted display identity and reason, not the volatile anchor.\n        val logKey = \"${normalizeIdentityDisplayName(pending.anchor.displayName)}|$reason\"\n        val shouldLog: Boolean\n        synchronized(sessionComponents.inboxIdentity.inboxIdentityCaptureLock) {\n            if (sessionComponents.inboxIdentity.pendingInboxIdentityCapture === pending) sessionComponents.inboxIdentity.pendingInboxIdentityCapture = null\n            shouldLog = sessionComponents.inboxIdentity.loggedInboxIdentityFailureKeys.add(logKey)\n        }\n        transitionAvatarClickState(\n            pending,\n            MomoAvatarClickTransactionState.AVATAR_UNKNOWN_RECOVERY,\n            reason\n        )\n        if (shouldLog) {\n            onLog(\"AI 身份回接[REQUEST_NOT_PRODUCED]：result=NOT_PRODUCED；reason=$reason\")\n            onLog(\"AI 联系人身份采集失败：$reason；保留未验证身份，不以昵称关联历史\")\n        }\n    }"
    ]
]


def verify_relocated_owner():
    component = (MOMO / "MomoSocialAdapterComponents.kt").read_text(encoding="utf-8")
    start = component.index("    fun transitionAvatarClickState(")
    body = component[start:]
    if git_blob(body.encode("utf-8")) != RELOCATED_BODY:
        raise AssertionError("R05-C relocated transaction logic changed")
    restored = (component[:start - 1] + "}\n").replace(
        "import com.freedom.core.accessibility.gesture.GestureExecutionStatus\n", "", 1)
    if git_blob(restored.encode("utf-8")) != PREVIOUS_OWNER:
        raise AssertionError("R05-A owner logic changed")


def restore_r05b_adapter(adapter):
    adapter = restore_r05c_adapter(adapter)
    verify_relocated_owner()
    for current, previous in REVERSE_PATCH:
        if adapter.count(current) != 1:
            raise AssertionError("Missing or ambiguous R05-C relocation anchor")
        adapter = adapter.replace(current, previous, 1)
    if git_blob(adapter.encode("utf-8")) != PREVIOUS_ADAPTER:
        raise AssertionError("R05-C did not reconstruct the exact green R05-B adapter")
    return adapter


class PpR05AvatarTransactionMigrationTest(unittest.TestCase):
    def test_full_facade_and_owner_restore(self):
        adapter = (MOMO / "MomoSocialAdapter.kt").read_text(encoding="utf-8")
        self.assertEqual(PREVIOUS_ADAPTER, git_blob(restore_r05b_adapter(adapter).encode("utf-8")))
        self.assertNotIn("sessionComponents.inboxIdentity.inboxIdentityCaptureLock", adapter)
        for field in ("failedInboxIdentityCaptureKeys", "verifiedInboxIdentityByAnchor",
                      "completedInboxIdentityOutcomes", "loggedInboxIdentityFailureKeys",
                      "observedDetailIdentityReturnKey", "identityReturnReinspectionPending"):
            self.assertNotIn("sessionComponents.inboxIdentity." + field, adapter)
