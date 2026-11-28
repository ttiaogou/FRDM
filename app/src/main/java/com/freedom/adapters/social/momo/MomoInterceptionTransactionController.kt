package com.freedom.adapters.social.momo

import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import com.freedom.adapters.accessibility.android.AndroidActiveWindowRootAccess
import com.freedom.core.accessibility.api.AccessibilityGateway
import com.freedom.core.accessibility.api.ScreenRect
import com.freedom.core.accessibility.api.WindowRef

/** Interception-specific evidence belongs to the existing parent transaction, never a second lease. */
internal class MomoInterceptionState {
    @Volatile var switchVerifiedOn = false
    var interceptionSettingsObserved = false
    var interceptionLastAction: MomoInterceptionRecoveryPolicy.Action? = null
    var interceptionRetryAttempt = 0
    @Volatile var interceptionRetryNotBefore = 0L
}

/** Live interception observation and stage progression; the parent still owns checkpoint and dispatch. */
internal class MomoInterceptionTransactionController(
    private val rootAccess: AndroidActiveWindowRootAccess,
    private val accessibilityGateway: AccessibilityGateway,
    private val targetPackage: String,
    private val onLog: (String) -> Unit,
    private val nodeBounds: (AccessibilityNodeInfo, String, String, Boolean, Boolean, String?) -> ScreenRect?,
    private val originalPrivateFollowUpChat: (PrivateFollowUpTransaction) -> Boolean,
    private val followUpWait: (PrivateFollowUpTransaction, String) -> Unit,
    private val followUpTerminal: (PrivateFollowUpTransaction, String, String) -> Unit,
    private val submitInterceptionStep: (PrivateFollowUpTransaction, PrivateFollowUpStage, String, (AccessibilityNodeInfo, WindowRef) -> ScreenRect?) -> Unit,
    private val interceptionDismissBounds: (AccessibilityNodeInfo) -> ScreenRect?,
    private val interceptionReturnedToChat: (PrivateFollowUpTransaction) -> Boolean,
    private val interceptionBackBounds: (WindowRef) -> ScreenRect?
) {
    private fun interceptionNodeBounds(
        root: AccessibilityNodeInfo,
        id: String,
        className: String,
        clickable: Boolean = false,
        checkable: Boolean = false,
        text: String? = null
    ): ScreenRect? = nodeBounds(root, id, className, clickable, checkable, text)

    /** Read-only confirmation of the very same switch in the confirmed interception popup. */
    fun interceptionChecked(root: AccessibilityNodeInfo): Boolean? {
        if (interceptionNodeBounds(root, "tv_title", "android.widget.TextView", text = "拦截消息") == null)
            return null
        val nodes = root.findAccessibilityNodeInfosByViewId("$targetPackage:id/btn_switch").orEmpty()
        return try {
            nodes.singleOrNull {
                it.packageName?.toString() == targetPackage &&
                    it.className?.toString() == "android.widget.CompoundButton" &&
                    it.isVisibleToUser && it.isEnabled && it.isCheckable
            }?.isChecked
        } finally {
            nodes.forEach { runCatching { it.recycle() } }
        }
    }

    private fun currentInterceptionChecked(): Boolean? =
        rootAccess.withActiveTargetRoot { root, window ->
            if (window.packageName != targetPackage || accessibilityGateway.currentGestureWindow() != window) null
            else interceptionChecked(root).takeIf { accessibilityGateway.currentGestureWindow() == window }
        }


    /** The admitted parent owns the contact; the child settings row is sufficient live page evidence. */
    fun interceptionSettingsPage(root: AccessibilityNodeInfo, child: PrivateFollowUpTransaction): Boolean =
        child.stage != PrivateFollowUpStage.INTERCEPTION_OPEN_SETTINGS &&
            interceptionNodeBounds(root, "setting_layout_harass", "android.widget.LinearLayout", clickable = true) != null

    /** Status is scoped to the unique interception row, not any "已开启" on the page. */
    fun interceptionSettingsChecked(root: AccessibilityNodeInfo, child: PrivateFollowUpTransaction): Boolean? = runCatching {
        if (!interceptionSettingsPage(root, child)) return null
        val rows = root.findAccessibilityNodeInfosByViewId("$targetPackage:id/setting_layout_harass").orEmpty()
        try {
            val row = rows.singleOrNull {
                it.packageName?.toString() == targetPackage &&
                    it.className?.toString() == "android.widget.LinearLayout" &&
                    it.isVisibleToUser && it.isEnabled && it.isClickable
            } ?: return null
            val statuses = row.findAccessibilityNodeInfosByViewId("$targetPackage:id/tv_harass_status").orEmpty()
            try {
                val status = statuses.singleOrNull {
                    it.packageName?.toString() == targetPackage &&
                        it.className?.toString() == "android.widget.TextView" &&
                        it.isVisibleToUser && it.isEnabled
                } ?: return null
                MomoInterceptionRecoveryPolicy.settingsChecked(status.text?.toString().orEmpty())
            } finally {
                statuses.forEach { runCatching { it.recycle() } }
            }
        } finally {
            rows.forEach { runCatching { it.recycle() } }
        }
    }.getOrNull()

    private fun observeInterceptionPage(child: PrivateFollowUpTransaction): MomoInterceptionRecoveryPolicy.Page {
        val observed = rootAccess.withActiveTargetRoot { root, window ->
            if (window.packageName != targetPackage || window.windowId < 0 ||
                window.generation < child.target.nodeTarget.window.generation ||
                accessibilityGateway.currentGestureWindow() != window
            ) return@withActiveTargetRoot MomoInterceptionRecoveryPolicy.Page.UNKNOWN
            val page = if (interceptionNodeBounds(root, "tv_title", "android.widget.TextView", text = "拦截消息") != null) {
                if (!child.interceptionState.interceptionSettingsObserved) MomoInterceptionRecoveryPolicy.Page.POPUP_SOURCE_UNCONFIRMED
                else when (interceptionChecked(root)) {
                    true -> MomoInterceptionRecoveryPolicy.Page.POPUP_ON
                    false -> MomoInterceptionRecoveryPolicy.Page.POPUP_OFF
                    null -> MomoInterceptionRecoveryPolicy.Page.POPUP_STATE_UNKNOWN
                }
            } else if (interceptionNodeBounds(root, "setting_layout_harass", "android.widget.LinearLayout", clickable = true) != null) {
                if (!interceptionSettingsPage(root, child)) MomoInterceptionRecoveryPolicy.Page.SETTINGS_IDENTITY_UNCONFIRMED
                else when (interceptionSettingsChecked(root, child)) {
                    true -> MomoInterceptionRecoveryPolicy.Page.SETTINGS_ON
                    false -> MomoInterceptionRecoveryPolicy.Page.SETTINGS_OFF
                    null -> MomoInterceptionRecoveryPolicy.Page.SETTINGS_STATE_UNKNOWN
                }
            } else null
            if (accessibilityGateway.currentGestureWindow() == window) page
            else MomoInterceptionRecoveryPolicy.Page.UNKNOWN
        }
        return observed ?: if (originalPrivateFollowUpChat(child)) MomoInterceptionRecoveryPolicy.Page.ORIGINAL_CHAT
            else MomoInterceptionRecoveryPolicy.Page.UNKNOWN
    }

    /** Reconcile under the existing parent lease and observer; no independent recovery loop. */
    fun reconcileInterception(child: PrivateFollowUpTransaction): Boolean {
        val page = observeInterceptionPage(child)
        val decision = MomoInterceptionRecoveryPolicy.reconcile(page, child.interceptionState.switchVerifiedOn)
        child.interceptionState.switchVerifiedOn = decision.verifiedOn
        when (page) {
            MomoInterceptionRecoveryPolicy.Page.SETTINGS_ON,
            MomoInterceptionRecoveryPolicy.Page.SETTINGS_OFF,
            MomoInterceptionRecoveryPolicy.Page.SETTINGS_STATE_UNKNOWN -> child.interceptionState.interceptionSettingsObserved = true
            MomoInterceptionRecoveryPolicy.Page.ORIGINAL_CHAT -> child.interceptionState.interceptionSettingsObserved = false
            else -> Unit
        }
        val action = decision.action
        if (action == MomoInterceptionRecoveryPolicy.Action.WAIT) {
            followUpWait(child, when (page) {
                MomoInterceptionRecoveryPolicy.Page.SETTINGS_IDENTITY_UNCONFIRMED -> "INTERCEPTION_SETTINGS_IDENTITY_UNCONFIRMED"
                MomoInterceptionRecoveryPolicy.Page.SETTINGS_STATE_UNKNOWN -> "INTERCEPTION_SETTINGS_STATE_UNKNOWN"
                MomoInterceptionRecoveryPolicy.Page.POPUP_SOURCE_UNCONFIRMED -> "INTERCEPTION_POPUP_SOURCE_UNCONFIRMED"
                MomoInterceptionRecoveryPolicy.Page.POPUP_STATE_UNKNOWN -> "INTERCEPTION_POPUP_STATE_UNKNOWN"
                else -> "UNKNOWN_INTERCEPTION_PAGE_OR_STATE"
            })
            return false
        }
        if (action != child.interceptionState.interceptionLastAction) {
            child.interceptionState.interceptionLastAction = action
            child.interceptionState.interceptionRetryAttempt = 0
            child.interceptionState.interceptionRetryNotBefore = 0L
            child.stageStartedAt = SystemClock.uptimeMillis()
            child.lastWaitReason = null
            onLog("PRIVATE_INTERCEPTION RECONCILED page=$page;action=$action;verifiedOn=${decision.verifiedOn}")
        }
        if (SystemClock.uptimeMillis() < child.interceptionState.interceptionRetryNotBefore) {
            followUpWait(child, "INTERCEPTION_RETRY_BACKOFF")
            return false
        }
        child.stage = when (action) {
            MomoInterceptionRecoveryPolicy.Action.OPEN_SETTINGS -> PrivateFollowUpStage.INTERCEPTION_OPEN_SETTINGS
            MomoInterceptionRecoveryPolicy.Action.OPEN_CONTROL -> PrivateFollowUpStage.INTERCEPTION_OPEN_CONTROL
            MomoInterceptionRecoveryPolicy.Action.ENABLE -> PrivateFollowUpStage.INTERCEPTION_TAP_SWITCH
            MomoInterceptionRecoveryPolicy.Action.DISMISS -> PrivateFollowUpStage.INTERCEPTION_DISMISS
            MomoInterceptionRecoveryPolicy.Action.BACK -> PrivateFollowUpStage.INTERCEPTION_BACK
            MomoInterceptionRecoveryPolicy.Action.COMPLETE -> PrivateFollowUpStage.INTERCEPTION_RETURN
            MomoInterceptionRecoveryPolicy.Action.WAIT -> return false
        }
        return true
    }

    fun observe(child: PrivateFollowUpTransaction) {
        when (child.stage) {
            PrivateFollowUpStage.INTERCEPTION_OPEN_SETTINGS -> {
                if (!originalPrivateFollowUpChat(child)) {
                    followUpWait(child, "ORIGINAL_CHAT_NOT_CURRENT")
                    return
                }
                submitInterceptionStep(child, PrivateFollowUpStage.INTERCEPTION_OPEN_CONTROL, "interception-avatar") { root, _ ->
                    interceptionNodeBounds(root, "chat_toolbar_avatar", "android.widget.ImageView", clickable = true)
                }
            }
            PrivateFollowUpStage.INTERCEPTION_OPEN_CONTROL -> {
                submitInterceptionStep(child, PrivateFollowUpStage.INTERCEPTION_TAP_SWITCH, "interception-layout") { root, _ ->
                    interceptionNodeBounds(root, "setting_layout_harass", "android.widget.LinearLayout", clickable = true)
                }
            }
            PrivateFollowUpStage.INTERCEPTION_TAP_SWITCH -> {
                when (currentInterceptionChecked()) {
                    true -> {
                        child.interceptionState.switchVerifiedOn = true
                        child.stage = PrivateFollowUpStage.INTERCEPTION_DISMISS
                        child.stageStartedAt = SystemClock.uptimeMillis()
                        return
                    }
                    null -> {
                        followUpWait(child, "SWITCH_STATE_NOT_READABLE")
                        return
                    }
                    false -> Unit
                }
                submitInterceptionStep(child, PrivateFollowUpStage.INTERCEPTION_DISMISS, "interception-switch") { root, _ ->
                    interceptionNodeBounds(root, "btn_switch", "android.widget.CompoundButton", checkable = true)
                }
            }
            PrivateFollowUpStage.INTERCEPTION_DISMISS -> {
                if (currentInterceptionChecked() != true) {
                    followUpWait(child, "SWITCH_ON_NOT_CONFIRMED")
                    return
                }
                child.interceptionState.switchVerifiedOn = true
                submitInterceptionStep(child, PrivateFollowUpStage.INTERCEPTION_BACK, "interception-dismiss") { root, _ ->
                    interceptionDismissBounds(root)
                }
            }
            PrivateFollowUpStage.INTERCEPTION_BACK -> {
                if (interceptionReturnedToChat(child)) {
                    if (child.interceptionState.switchVerifiedOn) {
                        followUpTerminal(child, "COMPLETED", "SWITCH_ON_AND_CHAT_RETURN_CONFIRMED")
                    } else followUpWait(child, "SWITCH_ON_NOT_CONFIRMED")
                    return
                }
                submitInterceptionStep(child, PrivateFollowUpStage.INTERCEPTION_RETURN, "interception-back") { _, window ->
                    interceptionBackBounds(window)
                }
            }
            PrivateFollowUpStage.INTERCEPTION_RETURN -> {
                if (interceptionReturnedToChat(child)) {
                    if (child.interceptionState.switchVerifiedOn) {
                        followUpTerminal(child, "COMPLETED", "SWITCH_ON_AND_CHAT_RETURN_CONFIRMED")
                    } else followUpWait(child, "SWITCH_ON_NOT_CONFIRMED")
                } else followUpWait(child, "ORIGINAL_CHAT_RETURN_PENDING")
            }
            else -> Unit // Non-interception stages remain with the parent transaction.
        }
    }
}
