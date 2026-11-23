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
    private var observationGeneration: Long = 0L

    fun currentObservationGeneration(): Long = observationGeneration

    fun read(purpose: AiReplyPageReadPurpose): AiReplyPageReadResult {
        observationGeneration += 1L
        val generation = observationGeneration
        val inspected = runCatching { inspectPage() }
        val snapshot = inspected.getOrNull()
        val failure = inspected.exceptionOrNull()
        if (failure != null) {
            onLog("AI 页面读取[FAILED]：入口=${purpose.name}；异常=${failure.javaClass.simpleName}")
        } else if (snapshot == null) {
            onLog("AI 页面读取[EMPTY]：入口=${purpose.name}；未取得新鲜页面快照")
        }
        val availability = if (snapshot == null || failure != null) {
            ObservationStatus.TEMPORARILY_UNAVAILABLE
        } else {
            // Page identity is intentionally classified by AiReplyPageObservationSemantics.
            // This status only records that a fresh raw snapshot was obtained.
            ObservationStatus.RAW_SNAPSHOT_AVAILABLE
        }
        return AiReplyPageReadResult(
            purpose = purpose,
            snapshot = snapshot,
            failure = failure,
            observationGeneration = generation,
            observation = ObservationResult(
                status = availability,
                reason = if (failure != null) "READ_FAILED" else if (snapshot == null) "NO_FRESH_SNAPSHOT" else "FRESH_SNAPSHOT",
                snapshot = snapshot,
                observationGeneration = generation,
                purpose = purpose,
                failure = failure
            )
        )
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
    val failure: Throwable?,
    val observationGeneration: Long = 0L,
    /** Raw-read result; semantic page identity is filled by the shared semantics classifier. */
    val observation: ObservationResult = ObservationResult(
        status = ObservationStatus.TEMPORARILY_UNAVAILABLE,
        reason = "NO_OBSERVATION"
    )
)
