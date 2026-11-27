"""R05-H: exact inverse of direct owner bindings and redundant facade removal."""
import unittest
from pathlib import Path
from tools.architecture.test_pp_r00_freeze import git_blob

ROOT = Path(__file__).resolve().parents[2]
MOMO = ROOT / "app/src/main/java/com/freedom/adapters/social/momo/"
PREVIOUS_ADAPTER = "d68d98d536a7b54e955c2b150f2fbe79fdbd50e0"
REMOVED = ["private val privateFollowUp:","private fun followUpCurrent(","private fun followUpWait(","private fun followUpTerminal(","private fun submitPrivateFollowUpStep(","private fun interceptionChecked(","private fun interceptionSettingsPage(","private fun interceptionSettingsChecked(","private fun transitionAvatarClickState(","private fun releaseAvatarCaptureForRetry(","private fun releaseAvatarCaptureAfterProfileMiss(","private fun completePendingInboxIdentityBinding(","private fun failPendingInboxIdentityCapture("]
# Exact reverse edits, including the location of every removed wrapper and direct call.
REVERSE_PATCH = [
    [351654,"sessionComponents.inboxIdentity.failPendingInboxIdentityCapture(\n                        pending,\n                        \"RETURNED_WINDOW_NOT_ORIGINAL_CHAT_DETAIL\",\n                        onLog\n                    )","failPendingInboxIdentityCapture(\n                        pending,\n                        \"RETURNED_WINDOW_NOT_ORIGINAL_CHAT_DETAIL\"\n                    )"],
    [347427,"sessionComponents.inboxIdentity.failPendingInboxIdentityCapture(\n                        pending,\n                        \"MANUAL_RETURN_TO_MESSAGE_LIST\",\n                        onLog\n                    )","failPendingInboxIdentityCapture(\n                        pending,\n                        \"MANUAL_RETURN_TO_MESSAGE_LIST\"\n                    )"],
    [349660,"sessionComponents.inboxIdentity.completePendingInboxIdentityBinding(\n                        pending,\n                        pending.platformUserId != null,\n                        onLog\n                    )","completePendingInboxIdentityBinding(\n                        pending,\n                        pending.platformUserId != null\n                    )"],
    [319692,"sessionComponents.inboxIdentity.completePendingInboxIdentityBinding(pendingIdentityCapture!!, false, onLog)","completePendingInboxIdentityBinding(pendingIdentityCapture!!, false)"],
    [319507,"sessionComponents.inboxIdentity.completePendingInboxIdentityBinding(pendingIdentityCapture!!, verified, onLog)","completePendingInboxIdentityBinding(pendingIdentityCapture!!, verified)"],
    [349251,"sessionComponents.inboxIdentity.releaseAvatarCaptureAfterProfileMiss(\n                            pending,\n                            MomoAvatarClickFailureReason.PROFILE_PAGE_NOT_CONFIRMED,\n                            onLog\n                        )","releaseAvatarCaptureAfterProfileMiss(\n                            pending,\n                            MomoAvatarClickFailureReason.PROFILE_PAGE_NOT_CONFIRMED\n                        )"],
    [344596,"sessionComponents.inboxIdentity.releaseAvatarCaptureAfterProfileMiss(\n                                pending, MomoAvatarClickFailureReason.PROFILE_PAGE_NOT_CONFIRMED,\n                                onLog\n                            )","releaseAvatarCaptureAfterProfileMiss(\n                                pending, MomoAvatarClickFailureReason.PROFILE_PAGE_NOT_CONFIRMED\n                            )"],
    [341936,"sessionComponents.inboxIdentity.releaseAvatarCaptureAfterProfileMiss(\n                        pending,\n                        MomoAvatarClickFailureReason.AVATAR_GESTURE_COMPLETED_BUT_PROFILE_NOT_OPENED,\n                        onLog\n                    )","releaseAvatarCaptureAfterProfileMiss(\n                        pending,\n                        MomoAvatarClickFailureReason.AVATAR_GESTURE_COMPLETED_BUT_PROFILE_NOT_OPENED\n                    )"],
    [334256,"sessionComponents.inboxIdentity.releaseAvatarCaptureForRetry(pending, \"手势坐标上下文暂时不可用\", onLog)","releaseAvatarCaptureForRetry(pending, \"手势坐标上下文暂时不可用\")"],
    [333757,"sessionComponents.inboxIdentity.releaseAvatarCaptureForRetry(pending, \"头像目标不可见或边界无效\", onLog)","releaseAvatarCaptureForRetry(pending, \"头像目标不可见或边界无效\")"],
    [333201,"sessionComponents.inboxIdentity.releaseAvatarCaptureForRetry(pending, \"头像节点在同一快照中失效\", onLog)","releaseAvatarCaptureForRetry(pending, \"头像节点在同一快照中失效\")"],
    [332775,"sessionComponents.inboxIdentity.releaseAvatarCaptureForRetry(pending, \"当前快照暂时未找到目标头像\", onLog)","releaseAvatarCaptureForRetry(pending, \"当前快照暂时未找到目标头像\")"],
    [332128,"sessionComponents.inboxIdentity.releaseAvatarCaptureForRetry(pending, \"聊天详情页面或窗口暂时不稳定\", onLog)","releaseAvatarCaptureForRetry(pending, \"聊天详情页面或窗口暂时不稳定\")"],
    [331398,"sessionComponents.inboxIdentity.releaseAvatarCaptureForRetry(pending, \"聊天详情节点树暂时不可用\", onLog)","releaseAvatarCaptureForRetry(pending, \"聊天详情节点树暂时不可用\")"],
    [349314,"sessionComponents.inboxIdentity.transitionAvatarClickState(\n                        pending,\n                        MomoAvatarClickTransactionState.AVATAR_COMPLETED,\n                        \"资料页身份读取完成并返回原聊天详情\",\n                        onLog\n                    )","transitionAvatarClickState(\n                        pending,\n                        MomoAvatarClickTransactionState.AVATAR_COMPLETED,\n                        \"资料页身份读取完成并返回原聊天详情\"\n                    )"],
    [341288,"sessionComponents.inboxIdentity.transitionAvatarClickState(\n                        pending,\n                        MomoAvatarClickTransactionState.AVATAR_ENTRY_VERIFYING,\n                        \"超时窗口内观察到资料页\",\n                        onLog\n                    )","transitionAvatarClickState(\n                        pending,\n                        MomoAvatarClickTransactionState.AVATAR_ENTRY_VERIFYING,\n                        \"超时窗口内观察到资料页\"\n                    )"],
    [335819,"sessionComponents.inboxIdentity.transitionAvatarClickState(\n                pending,\n                MomoAvatarClickTransactionState.AVATAR_RETRY_WAITING,\n                \"头像手势未被接受；等待下一次当前页面重新定位\",\n                onLog\n            )","transitionAvatarClickState(\n                pending,\n                MomoAvatarClickTransactionState.AVATAR_RETRY_WAITING,\n                \"头像手势未被接受；等待下一次当前页面重新定位\"\n            )"],
    [334200,"sessionComponents.inboxIdentity.transitionAvatarClickState(pending, MomoAvatarClickTransactionState.AVATAR_CLICKING, \"当前头像目标已通过身份与页面校验\", onLog)","transitionAvatarClickState(pending, MomoAvatarClickTransactionState.AVATAR_CLICKING, \"当前头像目标已通过身份与页面校验\")"],
    [333231,"sessionComponents.inboxIdentity.transitionAvatarClickState(pending, MomoAvatarClickTransactionState.AVATAR_IDENTITY_VERIFYING, \"当前头像节点已重新定位\", onLog)","transitionAvatarClickState(pending, MomoAvatarClickTransactionState.AVATAR_IDENTITY_VERIFYING, \"当前头像节点已重新定位\")"],
    [332231,"sessionComponents.inboxIdentity.transitionAvatarClickState(pending, MomoAvatarClickTransactionState.AVATAR_LOCATING, \"当前聊天详情页已读取\", onLog)","transitionAvatarClickState(pending, MomoAvatarClickTransactionState.AVATAR_LOCATING, \"当前聊天详情页已读取\")"],
    [330737,"sessionComponents.inboxIdentity.transitionAvatarClickState(pending, MomoAvatarClickTransactionState.AVATAR_PAGE_READING, \"开始读取当前聊天详情页\", onLog)","transitionAvatarClickState(pending, MomoAvatarClickTransactionState.AVATAR_PAGE_READING, \"开始读取当前聊天详情页\")"],
    [307891,"interceptionTransaction.interceptionSettingsChecked(root, child)","interceptionSettingsChecked(root, child)"],
    [308204,"interceptionTransaction.interceptionSettingsChecked(root, child)","interceptionSettingsChecked(root, child)"],
    [307990,"interceptionTransaction.interceptionChecked(root)","interceptionChecked(root)"],
    [308075,"interceptionTransaction.interceptionChecked(root)","interceptionChecked(root)"],
    [298878,"interceptionTransaction.interceptionSettingsPage(root, child)","interceptionSettingsPage(root, child)"],
    [303423,"privateFollowUpController.submitPrivateFollowUpStep(child,","submitPrivateFollowUpStep(child,"],
    [304720,"privateFollowUpController.submitPrivateFollowUpStep(child,","submitPrivateFollowUpStep(child,"],
    [306925,"privateFollowUpController.submitPrivateFollowUpStep(child,","submitPrivateFollowUpStep(child,"],
    [302942,"followUpTerminal = privateFollowUpController::followUpTerminal,","followUpTerminal = ::followUpTerminal,"],
    [302878,"followUpWait = privateFollowUpController::followUpWait,","followUpWait = ::followUpWait,"],
    [298376,"privateFollowUpController.followUpCurrent(child)","followUpCurrent(child)"],
    [300756,"privateFollowUpController.followUpCurrent(child)","followUpCurrent(child)"],
    [303493,"privateFollowUpController.followUpCurrent(child)","followUpCurrent(child)"],
    [304047,"privateFollowUpController.followUpCurrent(child)","followUpCurrent(child)"],
    [304738,"privateFollowUpController.followUpCurrent(child)","followUpCurrent(child)"],
    [306927,"privateFollowUpController.followUpCurrent(child)","followUpCurrent(child)"],
    [298299,"val child = privateFollowUpController.current() ?: return false","val child = privateFollowUp ?: return false"],
    [27433,"val privateFollowUp = privateFollowUpController.current()?.takeIf {","val privateFollowUp = privateFollowUp?.takeIf {"],
    [350577,"    private fun dispatchInboxProfileIdentityScroll(","    private fun failPendingInboxIdentityCapture(\n        pending: PendingInboxIdentityCapture,\n        reason: String\n    ) = sessionComponents.inboxIdentity.failPendingInboxIdentityCapture(pending, reason, onLog)\n\n    private fun dispatchInboxProfileIdentityScroll("],
    [339829,"    /** Returns true while the profile-navigation transaction","    private fun completePendingInboxIdentityBinding(\n        pending: PendingInboxIdentityCapture,\n        identityVerified: Boolean\n    ) = sessionComponents.inboxIdentity.completePendingInboxIdentityBinding(pending, identityVerified, onLog)\n\n    /** Returns true while the profile-navigation transaction"],
    [20211,"    init {","    private fun transitionAvatarClickState(\n        pending: PendingInboxIdentityCapture?,\n        next: MomoAvatarClickTransactionState,\n        reason: String\n    ) = sessionComponents.inboxIdentity.transitionAvatarClickState(pending, next, reason, onLog)\n\n    private fun releaseAvatarCaptureForRetry(\n        pending: PendingInboxIdentityCapture,\n        reason: String\n    ) = sessionComponents.inboxIdentity.releaseAvatarCaptureForRetry(pending, reason, onLog)\n\n    private fun releaseAvatarCaptureAfterProfileMiss(\n        pending: PendingInboxIdentityCapture,\n        reason: MomoAvatarClickFailureReason\n    ) = sessionComponents.inboxIdentity.releaseAvatarCaptureAfterProfileMiss(pending, reason, onLog)\n\n    init {"],
    [303835,"    private fun submitImageRetryOpen(","    private fun interceptionChecked(root: AccessibilityNodeInfo): Boolean? =\n        interceptionTransaction.interceptionChecked(root)\n\n    private fun interceptionSettingsPage(root: AccessibilityNodeInfo, child: PrivateFollowUpTransaction): Boolean =\n        interceptionTransaction.interceptionSettingsPage(root, child)\n\n    private fun interceptionSettingsChecked(root: AccessibilityNodeInfo, child: PrivateFollowUpTransaction): Boolean? =\n        interceptionTransaction.interceptionSettingsChecked(root, child)\n\n    private fun submitImageRetryOpen("],
    [303148,"    private val interceptionTransaction","    private fun submitPrivateFollowUpStep(\n        child: PrivateFollowUpTransaction,\n        next: PrivateFollowUpStage,\n        action: ((GestureExecutionStatus) -> Unit) -> GestureSubmission\n    ) = privateFollowUpController.submitPrivateFollowUpStep(child, next, action)\n\n    private val interceptionTransaction"],
    [302984,"    override fun abort(","    private fun followUpWait(child: PrivateFollowUpTransaction, reason: String) =\n        privateFollowUpController.followUpWait(child, reason)\n\n    private fun followUpTerminal(child: PrivateFollowUpTransaction, result: String, reason: String) =\n        privateFollowUpController.followUpTerminal(child, result, reason)\n\n    override fun abort("],
    [298218,"    private fun originalPrivateFollowUpChat(","    private fun followUpCurrent(child: PrivateFollowUpTransaction): Boolean =\n        privateFollowUpController.followUpCurrent(child)\n\n    private fun originalPrivateFollowUpChat("],
    [298121,"    override fun progress()","    private val privateFollowUp: PrivateFollowUpTransaction?\n        get() = privateFollowUpController.current()\n\n    override fun progress()"],
    [296696,"    private val privateFollowUpController = MomoPrivateFollowUpController(\n        accessibilityGateway = accessibilityGateway,\n        targetPackage = targetPackage,\n        onLog = onLog,\n        isBoundAiReplyUiOperationOwned = isBoundAiReplyUiOperationOwned,\n        captureImageSendBaseline = ::captureImageSendBaseline\n    )\n\n    private val imageTransaction = MomoImageSendTransactionController(\n        accessibilityGateway = accessibilityGateway,\n        targetPackage = targetPackage,\n        onLog = onLog,\n        followUpCurrent = privateFollowUpController::followUpCurrent,\n        originalPrivateFollowUpChat = ::originalPrivateFollowUpChat,\n        followUpWait = privateFollowUpController::followUpWait,\n        followUpTerminal = privateFollowUpController::followUpTerminal,\n        submitPrivateFollowUpStep = privateFollowUpController::submitPrivateFollowUpStep,\n        openImageAlbum = ::openImageAlbum,\n        openImageAlbumDirectory = ::openImageAlbumDirectory,\n        inspectFreedomDirectory = ::inspectFreedomDirectory,\n        openFreedomAlbum = ::openFreedomAlbum,\n        selectFreedomImage = ::selectFreedomImage,\n        sendFreedomImage = ::sendFreedomImage,\n        freshMediaSnapshot = ::freshMediaSnapshot,\n        submitImageRetryOpen = ::submitImageRetryOpen,\n        submitImageRetryConfirm = ::submitImageRetryConfirm,\n        inspectImageSendResult = ::inspectImageSendResult\n    )\n\n","    private val imageTransaction = MomoImageSendTransactionController(\n        accessibilityGateway = accessibilityGateway,\n        targetPackage = targetPackage,\n        onLog = onLog,\n        followUpCurrent = ::followUpCurrent,\n        originalPrivateFollowUpChat = ::originalPrivateFollowUpChat,\n        followUpWait = ::followUpWait,\n        followUpTerminal = ::followUpTerminal,\n        submitPrivateFollowUpStep = ::submitPrivateFollowUpStep,\n        openImageAlbum = ::openImageAlbum,\n        openImageAlbumDirectory = ::openImageAlbumDirectory,\n        inspectFreedomDirectory = ::inspectFreedomDirectory,\n        openFreedomAlbum = ::openFreedomAlbum,\n        selectFreedomImage = ::selectFreedomImage,\n        sendFreedomImage = ::sendFreedomImage,\n        freshMediaSnapshot = ::freshMediaSnapshot,\n        submitImageRetryOpen = ::submitImageRetryOpen,\n        submitImageRetryConfirm = ::submitImageRetryConfirm,\n        inspectImageSendResult = ::inspectImageSendResult\n    )\n\n    private val privateFollowUpController = MomoPrivateFollowUpController(\n        accessibilityGateway = accessibilityGateway,\n        targetPackage = targetPackage,\n        onLog = onLog,\n        isBoundAiReplyUiOperationOwned = isBoundAiReplyUiOperationOwned,\n        captureImageSendBaseline = ::captureImageSendBaseline\n    )\n\n"],
]

def restore_r05g_adapter(adapter):
    for offset, current, previous in REVERSE_PATCH:
        if adapter[offset:offset + len(current)] != current:
            raise AssertionError("Missing or moved R05-H direct binding: " + current[:100])
        adapter = adapter[:offset] + previous + adapter[offset + len(current):]
    if git_blob(adapter.encode("utf-8")) != PREVIOUS_ADAPTER:
        raise AssertionError("R05-H did not reconstruct the exact green R05-G adapter")
    return adapter

class PpR05FacadeConvergenceTest(unittest.TestCase):
    def test_exact_inverse_and_redundant_wrappers_absent(self):
        adapter = (MOMO / "MomoSocialAdapter.kt").read_text(encoding="utf-8")
        self.assertEqual(PREVIOUS_ADAPTER, git_blob(restore_r05g_adapter(adapter).encode("utf-8")))
        for marker in REMOVED:
            self.assertNotIn(marker, adapter)
        self.assertEqual(1, adapter.count("MomoPrivateFollowUpController("))
        self.assertEqual(1, adapter.count("MomoImageSendTransactionController("))
        self.assertEqual(1, adapter.count("MomoInterceptionTransactionController("))

    def test_direct_bindings_and_original_log_callback_cannot_be_bypassed(self):
        adapter = (MOMO / "MomoSocialAdapter.kt").read_text(encoding="utf-8")
        for old, new in (
            ("privateFollowUpController::submitPrivateFollowUpStep", "::uncheckedSubmission"),
            ("privateFollowUpController.followUpCurrent(child)", "true"),
            ("interceptionTransaction.interceptionChecked(root)", "false"),
            ("sessionComponents.inboxIdentity.releaseAvatarCaptureForRetry(pending,", "uncheckedRetry(pending,"),
        ):
            self.assertIn(old, adapter)
            with self.subTest(old=old), self.assertRaises(AssertionError):
                restore_r05g_adapter(adapter.replace(old, new, 1))
