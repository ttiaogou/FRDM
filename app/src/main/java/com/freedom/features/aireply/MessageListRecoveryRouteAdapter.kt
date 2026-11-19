package com.freedom.features.aireply

import com.freedom.core.runtime.PageFingerprint
import com.freedom.core.runtime.PageRecoveryRouteAdapter
import com.freedom.features.aireply.ports.ChatPageKind

/** Route adapter for the private-message list; traversal policy remains in its existing executor. */
internal class MessageListRecoveryRouteAdapter(
    private val persist: () -> Unit
) : PageRecoveryRouteAdapter {
    override val routeId: String = "AI_REPLY_PRIVATE_MESSAGES"

    override fun matches(fingerprint: PageFingerprint): Boolean =
        fingerprint.semanticPage == ChatPageKind.CHAT_LIST.name

    override fun saveCheckpoint() = persist()
}
