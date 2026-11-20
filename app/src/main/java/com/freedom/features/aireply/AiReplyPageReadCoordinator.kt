package com.freedom.features.aireply

import com.freedom.features.aireply.ports.ChatPageSnapshot

/**
 * The single raw page-read gateway for AI Reply.
 * Callers receive a fresh snapshot only; classification and business decisions remain outside.
 */
internal class AiReplyPageReadCoordinator(
    private val inspectPage: () -> ChatPageSnapshot,
    private val onLog: (String) -> Unit = {}
) {
    fun read(purpose: AiReplyPageReadPurpose): AiReplyPageReadResult {
        val inspected = runCatching { inspectPage() }
        val snapshot = inspected.getOrNull()
        val failure = inspected.exceptionOrNull()
        if (failure != null) {
            onLog("AI 页面读取[FAILED]：入口=${purpose.name}；异常=${failure.javaClass.simpleName}")
        } else if (snapshot == null) {
            onLog("AI 页面读取[EMPTY]：入口=${purpose.name}；未取得新鲜页面快照")
        }
        return AiReplyPageReadResult(purpose, snapshot, failure)
    }
}

internal enum class AiReplyPageReadPurpose {
    FULL_INSPECTION,
    RECEIVED_GREETINGS,
    SCROLL_BEFORE,
    SCROLL_AFTER,
    SESSION_LIST_RETRY,
    BOUNDARY_VERIFY,
    MESSAGE_READ_RETRY,
    RETURN_NAVIGATION_VERIFY,
    ACTIVITY_TRANSITION
}

internal data class AiReplyPageReadResult(
    val purpose: AiReplyPageReadPurpose,
    val snapshot: ChatPageSnapshot?,
    val failure: Throwable?
)
