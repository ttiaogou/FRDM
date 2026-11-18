package com.freedom.adapters.social.momo

import com.freedom.core.accessibility.api.ScreenRect

/** 正文的正向准入：只依赖正式文本容器与其所属消息层级，不识别或枚举卡片。 */
internal object MomoChatTextContainerPolicy {
    fun isBodyText(viewId: String, viewIdTrail: List<String>): Boolean {
        val ids = viewIdTrail.map(::entryName)
        val root = ids.indexOfLast { it == "message_root" }
        if (root < 0) return false
        val scoped = ids.drop(root + 1)
        if ("message_content_layout" !in scoped) return false
        return MomoChatRowCandidatePolicy.isFormalTextContainer(viewId) ||
            scoped.any(MomoChatRowCandidatePolicy::isFormalTextContainer)
    }

    fun selectBody(evidence: List<MomoChatTextEvidence>, listBounds: ScreenRect): MomoChatTextEvidence? =
        evidence.asSequence()
            .filter { isBodyText(it.viewId, it.viewIdTrail) }
            .filterNot { MomoChatMessageTextEvidencePolicy.isTimeLabel(it.value) }
            .distinctBy { it.value to it.bounds }
            .maxWithOrNull(compareBy<MomoChatTextEvidence> {
                MomoChatMessageTextEvidencePolicy.textScore(it, listBounds)
            }.thenBy { it.value.length })

    private fun entryName(id: String): String = id.substringAfterLast('/').substringAfterLast(':').lowercase()
}
