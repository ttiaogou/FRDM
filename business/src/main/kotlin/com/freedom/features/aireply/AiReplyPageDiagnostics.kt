package com.freedom.features.aireply

import com.freedom.features.aireply.ports.ChatPageKind
import com.freedom.features.aireply.ports.ChatPageSnapshot

/** Pure diagnostic formatting only; log emission and limits remain in the observation runtime. */
internal fun summarize(snapshot: ChatPageSnapshot): String = when (snapshot.pageKind) {
    ChatPageKind.UNKNOWN -> "无法确认当前目标应用窗口"
    ChatPageKind.NON_CHAT -> "当前不是已识别的聊天页面"
    ChatPageKind.CHAT_LIST -> "识别到目标应用消息入口/会话列表候选"
    ChatPageKind.CHAT_DETAIL_CANDIDATE -> "识别到聊天详情页面候选（尚未确认一对一）"
} + "；Activity=${snapshot.activityClass.ifBlank { "未知" }}；" +
    "输入控件=${snapshot.editableInputCount}；列表候选=${snapshot.messageListCount}；" +
    "发送控件=${snapshot.sendActionCount}；消息相关 ViewId=${snapshot.messageRelatedViewIdCount}；" +
    "扫描节点=${snapshot.scannedNodeCount}；结构证据节点=${snapshot.structureEvidence.size}" +
    formatChatListSessionSummary(snapshot) +
    formatNodeTreeProbeSummary(snapshot)

/** Logs only counts and geometry-derived state; never emits session names or previews. */
private fun formatChatListSessionSummary(snapshot: ChatPageSnapshot): String {
    if (snapshot.pageKind != ChatPageKind.CHAT_LIST) return ""
    val candidates = snapshot.chatListCandidates
    return "；会话行候选=${candidates.size}" +
        "；名称节点可读=${candidates.count { it.hasReadableName }}" +
        "；预览节点可读=${candidates.count { it.hasReadablePreview }}" +
        "；头像节点=${candidates.count { it.hasAvatar }}" +
        "；会话行系统可见=${candidates.count { it.rowVisibleToUser }}" +
        "；会话行与视口相交=${candidates.count { it.intersectsViewport }}" +
        "；会话行完整位于视口=${candidates.count { it.fullyInsideViewport }}" +
        "；会话行边界有效=${candidates.count { it.rowBoundsValid }}" +
        "；名称节点系统可见=${candidates.count { it.nameNodeVisibleToUser }}" +
        "；预览节点系统可见=${candidates.count { it.previewNodeVisibleToUser }}" +
        "；名称节点几何相交=${candidates.count { it.nameBoundsIntersectViewport }}" +
        "；预览节点几何相交=${candidates.count { it.previewBoundsIntersectViewport }}" +
        "；名称节点完整位于视口=${candidates.count { it.nameBoundsFullyInsideViewport }}" +
        "；预览节点完整位于视口=${candidates.count { it.previewBoundsFullyInsideViewport }}" +
        "；名称节点边界无效=${candidates.count { !it.nameBoundsValid }}" +
        "；预览节点边界无效=${candidates.count { !it.previewBoundsValid }}" +
        "；已丢弃无效会话行边界=${snapshot.chatListInvalidRowBoundsCount}" +
        "；名称文本来源NODE_TEXT=${candidates.count { it.nameSource.name == "NODE_TEXT" }}" +
        "；预览文本来源NODE_TEXT=${candidates.count { it.previewSource.name == "NODE_TEXT" }}" +
        "；status_new节点=${snapshot.chatListUnreadDiagnostics.statusNewNodeCount}" +
        "；status_new未读=${snapshot.chatListUnreadDiagnostics.statusNewUnreadCount}" +
        "；status_new已读=${snapshot.chatListUnreadDiagnostics.statusNewReadCount}" +
        "；status_new未知=${snapshot.chatListUnreadDiagnostics.statusNewUnknownCount}" +
        "；status_new控件缺失判已读=${snapshot.chatListUnreadDiagnostics.statusNewAbsentReadCount}" +
        "；节点提取截断=${snapshot.chatListExtractionTruncated}（不输出会话文本）"
}

private fun formatNodeTreeProbeSummary(snapshot: ChatPageSnapshot): String {
    if (snapshot.nodeTreeProbeVisitedNodeCount == 0 && !snapshot.nodeTreeProbeTruncated) return ""
    return "；节点树探针=只读（不参与消息读取）" +
        "；节点树探针扫描=${snapshot.nodeTreeProbeVisitedNodeCount}" +
        "；节点树探针截断=${snapshot.nodeTreeProbeTruncated}" +
        "；替代列表探针=${snapshot.structureEvidence.count { it.isAlternateListProbe }}" +
        "；招呼消息行候选=${snapshot.structureEvidence.count { it.isMessageRowCandidate }}"
}

/** Structural fields only: never serialize node text, content descriptions or contact data. */
internal fun formatNodeEvidence(
    item: com.freedom.features.aireply.ports.ChatPageNodeEvidence
): String {
    val flags = buildList {
        if (item.isEditable) add("editable")
        if (item.isScrollable) add("scrollable")
        if (item.isClickable) add("clickable")
        if (item.insideListCandidate) add("insideListProbe")
        if (item.isListProbeRoot) add("probeRoot")
        if (item.isAlternateListProbe) add("alternateListProbe")
        if (item.isMessageRowCandidate) add("messageRowCandidate")
        if (item.hasText) add("hasText")
        if (item.className.contains("image", ignoreCase = true) ||
            item.className.contains("drawee", ignoreCase = true) ||
            item.className.contains("avatar", ignoreCase = true) ||
            item.viewId.contains("avatar", ignoreCase = true)
        ) add("imageLike")
        add("visible=${item.isVisibleToUser}")
    }.joinToString(",")
    return "path=${item.path},depth=${item.depth},parent=${item.parentPath.ifBlank { "-" }}," +
        "class=${item.className.substringAfterLast('.')}," +
        "id=${item.viewId.ifBlank { "-" }}," +
        "bounds=[${item.left},${item.top}][${item.right},${item.bottom}]," +
        "children=${item.childCount},flags=${flags.ifBlank { "-" }}"
}

