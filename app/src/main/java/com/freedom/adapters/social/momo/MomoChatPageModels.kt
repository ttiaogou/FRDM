package com.freedom.adapters.social.momo

/**
 * Versioned Momo chat-page contract derived from the supplied device accessibility trees.
 * Activity names are retained as diagnostic hints; page identity is established by the
 * same-window structural anchors and exact component relationships below.
 */
internal object MomoChatPageContract {
    const val CHAT_DETAIL_ACTIVITY = MomoAppDescriptor.CHAT_DETAIL_ACTIVITY_CLASS
    const val MAIN_ACTIVITY = MomoAppDescriptor.MAIN_ACTIVITY_CLASS
    const val MESSAGE_LIST_VIEW_ID = MomoAppDescriptor.CHAT_MESSAGE_LIST_VIEW_ID
    const val MESSAGE_LIST_CLASS_NAME = MomoAppDescriptor.CHAT_MESSAGE_LIST_CLASS_NAME
    const val CHAT_TITLE_VIEW_ID = "com.immomo.momo:id/chat_user_name"
    const val CHAT_MESSAGE_ROOT_VIEW_ID = "com.immomo.momo:id/message_root"
    const val CHAT_MESSAGE_AVATAR_CONTAINER_VIEW_ID = "com.immomo.momo:id/message_iv_avatar_container"
    const val CHAT_MESSAGE_AVATAR_VIEW_ID = "com.immomo.momo:id/message_iv_userphoto"
    const val CHAT_COMPOSER_VIEW_ID = "com.immomo.momo:id/message_ed_msgeditor"
    const val CHAT_EMOTES_VIEW_ID = "com.immomo.momo:id/message_btn_openemotes"
    const val CHAT_INPUT_LAYOUT_VIEW_ID = "com.immomo.momo:id/input_layout"
    const val CHAT_EDITOR_LAYOUT_VIEW_ID = "com.immomo.momo:id/editor_layout"
    const val CHAT_APPBAR_VIEW_ID = "com.immomo.momo:id/appbar_id"
    const val CHAT_TOOLBAR_VIEW_ID = "com.immomo.momo:id/toolbar_id"

    fun supportsChatDetailActivity(activityClass: String): Boolean =
        activityClass == CHAT_DETAIL_ACTIVITY || activityClass == MAIN_ACTIVITY
}

/** A conservative page classification. A candidate is not proof of a one-to-one conversation. */
enum class MomoChatPageType {
    UNKNOWN,
    NON_CHAT,
    CHAT_LIST,
    CHAT_DETAIL_CANDIDATE
}

/** Pure signals extracted from public accessibility metadata; message contents are not retained. */
data class MomoChatPageSignals(
    val packageMatches: Boolean,
    val activityClass: String = "",
    val hasChatTab: Boolean = false,
    val messageTabSelected: Boolean = false,
    val hasInboxRecyclerView: Boolean = false,
    val editableInputCount: Int = 0,
    val messageListCount: Int = 0,
    val sendActionCount: Int = 0,
    val messageRelatedViewIdCount: Int = 0,
    val hasCanonicalMessageList: Boolean = false,
    val hasChatTitleAnchor: Boolean = false,
    val hasPeerMessageAvatarAnchor: Boolean = false,
    val hasMessageComposerAnchor: Boolean = false,
    val hasMessageEmotesAnchor: Boolean = false,
    val hasToolbarBackButton: Boolean = false,
    val hasMessageTopTab: Boolean = false,
    val messageTopTabSelected: Boolean = false,
    val hasSessionListHierarchy: Boolean = false,
    val hasMessageSearchAndContactActions: Boolean = false,
    /** The bounded tree walk ended before all relevant regions could be inspected. */
    val inspectionIncomplete: Boolean = false
) {
    val hasStableChatDetailFingerprint: Boolean
        get() = !inspectionIncomplete && MomoAlternativeFingerprintPolicy.matchesAny(
            listOf(
                MomoFingerprintPath("TITLE_COMPOSER_EMOTES", listOf(
                    hasChatTitleAnchor, hasMessageComposerAnchor, hasMessageEmotesAnchor
                )),
            )
        )
}

internal object MomoChatPageClassifier {
    fun classify(signals: MomoChatPageSignals): MomoChatPageType {
        if (!signals.packageMatches) return MomoChatPageType.UNKNOWN

        // Activity names are diagnostic hints only. Page identity comes from a complete,
        // window-local structural fingerprint; message payload and card counts are irrelevant.
        if (signals.hasStableChatDetailFingerprint) {
            return MomoChatPageType.CHAT_DETAIL_CANDIDATE
        }

        // A session list is confirmed by exactly one of two explicit navigation routes. Both
        // routes share the same complete list shell and the same unique, usable inbox RecyclerView.
        // App-bar actions are retained as diagnostics only: they may also appear on intermediate
        // or non-list workspaces and must never be a third fallback route.
        if (!signals.inspectionIncomplete && signals.hasSessionListHierarchy &&
            signals.hasInboxRecyclerView
        ) {
            val messageWorkspacePaths = listOf(
                MomoFingerprintPath("TOP_MESSAGE_TAB_SELECTED_AND_COMPLETE_SESSION_LIST", listOf(
                    signals.hasMessageTopTab, signals.messageTopTabSelected
                )),
                MomoFingerprintPath("BOTTOM_CHAT_TAB_SELECTED_AND_COMPLETE_SESSION_LIST", listOf(
                    signals.hasChatTab, signals.messageTabSelected
                ))
            )
            if (MomoAlternativeFingerprintPolicy.matchesAny(messageWorkspacePaths)) {
                return MomoChatPageType.CHAT_LIST
            }
        }
        // Any incomplete walk is inconclusive, including a partial match of a list route.
        if (signals.inspectionIncomplete) return MomoChatPageType.UNKNOWN
        return MomoChatPageType.NON_CHAT
    }
}

/** A redacted node descriptor. Text values and content descriptions are deliberately excluded. */
data class MomoChatNodeEvidence(
    val path: String,
    val className: String,
    val viewId: String,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val childCount: Int,
    val isEditable: Boolean,
    val isScrollable: Boolean,
    val isClickable: Boolean,
    val hasText: Boolean,
    val insideListCandidate: Boolean,
    val depth: Int = 0,
    val parentPath: String = "",
    val isVisibleToUser: Boolean = false,
    val isListProbeRoot: Boolean = false,
    val isAlternateListProbe: Boolean = false,
    val isMessageRowCandidate: Boolean = false,
    val textLength: Int = 0,
    val contentDescriptionLength: Int = 0,
    val boundsValid: Boolean = false,
    val isSelected: Boolean = false
)

/** Diagnostic facts for the verified chat header node; raw title text is not duplicated here. */
data class MomoChatHeaderNodeDiagnostic(
    val nodePath: String = "",
    val found: Boolean = false,
    val textLength: Int = 0,
    val contentDescriptionLength: Int = 0,
    val isVisibleToUser: Boolean = false,
    val left: Int = 0,
    val top: Int = 0,
    val right: Int = 0,
    val bottom: Int = 0,
    val boundsValid: Boolean = false,
    val parentFound: Boolean = false,
    val parentClassName: String = "",
    val parentViewId: String = "",
    val parentIsVisibleToUser: Boolean = false,
    val parentBoundsValid: Boolean = false,
    val readOutcome: String = "NOT_FOUND"
)

/** A compact description of the tree. No message text or contact identity is stored here. */
data class MomoChatPageInspection(
    val pageType: MomoChatPageType,
    val windowPackage: String,
    val activityClass: String,
    val scannedNodeCount: Int,
    val editableInputCount: Int,
    val messageListCount: Int,
    val sendActionCount: Int,
    val messageRelatedViewIdCount: Int,
    val hasChatTab: Boolean,
    val messageListPath: List<Int>?,
    val inputFieldPath: List<Int>?,
    val structureEvidence: List<MomoChatNodeEvidence> = emptyList(),
    val messageListClassName: String? = null,
    val messageListViewId: String? = null,
    val hasCanonicalMessageList: Boolean = false,
    val hasChatTitleAnchor: Boolean = false,
    val hasPeerMessageAvatarAnchor: Boolean = false,
    val hasMessageComposerAnchor: Boolean = false,
    val hasMessageEmotesAnchor: Boolean = false,
    val hasToolbarBackButton: Boolean = false,
    /** Ephemeral chat header title used only to verify the session opened by the manager. */
    val chatTitleText: String? = null,
    val chatHeaderDiagnostic: MomoChatHeaderNodeDiagnostic? = null,
    /** Validated visible main-navigation chat-tab locator; Momo identity remains adapter-local. */
    val chatTabEvidence: MomoChatNodeEvidence? = null,
    val messageTabSelected: Boolean = false,
    // Structural message-workspace signals are exposed on the inspection result because
    // adapter navigation gates consume the inspected snapshot, not the classifier input.
    val hasMessageTopTab: Boolean = false,
    val messageTopTabSelected: Boolean = false,
    val hasSessionListHierarchy: Boolean = false,
    val hasInboxRecyclerView: Boolean = false,
    val hasMessageSearchAndContactActions: Boolean = false,
    val timeBudgetExceeded: Boolean = false,
    val depthBudgetExceeded: Boolean = false,
    val requestedMaxDurationMs: Long = 350L,
    val nodeBudgetExceeded: Boolean = false
) {
    val inspectionIncomplete: Boolean
        get() = timeBudgetExceeded || depthBudgetExceeded || nodeBudgetExceeded

    val hasStableChatDetailFingerprint: Boolean
        get() = !inspectionIncomplete && MomoAlternativeFingerprintPolicy.matchesAny(
            listOf(
                MomoFingerprintPath("TITLE_COMPOSER_EMOTES", listOf(
                    hasChatTitleAnchor, hasMessageComposerAnchor, hasMessageEmotesAnchor
                ))
            )
        )

    fun stableKey(): String = when (pageType) {
        MomoChatPageType.CHAT_DETAIL_CANDIDATE -> listOf(
            pageType.name,
            windowPackage,
            "CHAT_DETAIL_STRUCTURE_V2",
            // The redacted title hash distinguishes two conversations in the same Activity;
            // it does not participate in the structural page classifier.
            chatTitleText?.hashCode()?.toString().orEmpty()
        ).joinToString("|")

        MomoChatPageType.CHAT_LIST -> listOf(
            pageType.name,
            windowPackage,
            hasChatTab,
            messageTabSelected,
            hasCanonicalMessageList,
            hasMessageTopTab,
            messageTopTabSelected,
            hasSessionListHierarchy,
            hasInboxRecyclerView,
            chatTabEvidence?.let { "${it.path}:${it.left},${it.top},${it.right},${it.bottom}:${it.isVisibleToUser}:${it.boundsValid}" }.orEmpty()
        ).joinToString("|")

        MomoChatPageType.UNKNOWN, MomoChatPageType.NON_CHAT -> listOf(
            pageType.name,
            windowPackage,
            activityClass,
            timeBudgetExceeded,
            depthBudgetExceeded,
            nodeBudgetExceeded,
            structureEvidence.filter { !it.insideListCandidate }.joinToString(";") { evidence ->
                listOf(evidence.path, evidence.parentPath, evidence.className, evidence.viewId,
                    evidence.left, evidence.top, evidence.right, evidence.bottom,
                    evidence.isVisibleToUser, evidence.isClickable, evidence.isScrollable,
                    evidence.isSelected).joinToString(":")
            }
        ).joinToString("|")
    }

    fun summary(): String = when (pageType) {
        MomoChatPageType.UNKNOWN -> "无法确认当前目标应用窗口"
        MomoChatPageType.NON_CHAT -> "当前不是已识别的陌陌聊天页面"
        MomoChatPageType.CHAT_LIST -> "识别到陌陌消息入口/会话列表候选"
        MomoChatPageType.CHAT_DETAIL_CANDIDATE -> "识别到陌陌聊天详情页面候选（尚未确认一对一）"
    } + "；Activity=${activityClass.ifBlank { "未知" }}；" +
        "输入控件=$editableInputCount；已验证消息列表=$messageListCount；发送控件=$sendActionCount；" +
        "消息相关 ViewId=$messageRelatedViewIdCount；" +
        "聊天页指纹（聊天对象/输入框/表情按钮）=" +
        "$hasChatTitleAnchor/$hasMessageComposerAnchor/$hasMessageEmotesAnchor；" +
        "标题栏返回按钮=$hasToolbarBackButton；标准消息列表=$hasCanonicalMessageList；" +
        "消息列表严格结构（顶部消息Tab/选中、底部聊天Tab/选中、列表容器、唯一可滚动非空RecyclerView）=" +
        "$hasMessageTopTab/$messageTopTabSelected/$hasChatTab/$messageTabSelected/" +
        "$hasSessionListHierarchy/$hasInboxRecyclerView；" +
        "扫描节点=$scannedNodeCount；" +
        "检查不完整=${timeBudgetExceeded || depthBudgetExceeded || nodeBudgetExceeded}"
}

data class MomoChatRegionRefresh(
    val packageMatches: Boolean,
    val messageRegionNodeCount: Int,
    val messageRegionTextNodeCount: Int,
    val inputRegionNodeCount: Int,
    val inputRegionEditableNodeCount: Int,
    val pathsResolved: Boolean,
    val timeBudgetExceeded: Boolean = false,
    val depthBudgetExceeded: Boolean = false,
    val nodeBudgetExceeded: Boolean = false
)
