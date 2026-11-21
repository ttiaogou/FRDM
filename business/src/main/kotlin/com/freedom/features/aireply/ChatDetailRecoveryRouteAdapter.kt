package com.freedom.features.aireply

import com.freedom.core.runtime.PageFingerprint
import com.freedom.core.runtime.PageRecoveryRouteAdapter
import com.freedom.features.aireply.ports.ChatPageKind

/**
 * Recovery route for a pending AI draft/send confirmation. It accepts only a freshly observed
 * chat-detail page; the runtime then re-reads the conversation and never re-submits the draft.
 */
internal class ChatDetailRecoveryRouteAdapter(
    private val persist: () -> Unit
) : PageRecoveryRouteAdapter {
    override val routeId: String = "AI_REPLY_CHAT_DETAIL"

    override fun matches(fingerprint: PageFingerprint): Boolean =
        fingerprint.semanticPage == ChatPageKind.CHAT_DETAIL_CANDIDATE.name

    override fun saveCheckpoint() = persist()
}
