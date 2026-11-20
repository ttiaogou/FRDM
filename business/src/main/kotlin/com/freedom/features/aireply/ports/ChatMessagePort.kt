package com.freedom.features.aireply.ports

import com.freedom.features.aireply.domain.message.ChatMessageBatch

/**
 * Reads a bounded set of currently visible messages from the active social adapter.
 * Implementations must keep Android node handles inside the adapter and avoid persistence/logging.
 */
interface ChatMessagePort {
    fun readRecentMessages(page: ChatPageSnapshot, limit: Int = 10): ChatMessageBatch

    /**
     * Reads message structure only. Implementations must not capture the screen or start OCR.
     * This is the pre-identity/context stage of the AI reply pipeline.
     */
    fun readRecentMessagesStructureAsync(
        page: ChatPageSnapshot,
        limit: Int = 10,
        onResult: (ChatMessageBatch) -> Unit
    ) {
        // Compatibility bridge for existing adapters/tests. Adapters that have a real
        // structure-only phase must override this method; Momo does so and never starts OCR
        // here. Keeping the bridge async preserves callback, timeout and stale-result semantics.
        readRecentMessagesAsync(page, limit, onResult)
    }

    /**
     * Async extension for adapters that need a screenshot or an on-device recognizer. The default
     * keeps existing providers synchronous and source-compatible. Implementations must return
     * message text only through the in-memory result callback; never log or persist it.
     */
    fun readRecentMessagesAsync(
        page: ChatPageSnapshot,
        limit: Int = 10,
        onResult: (ChatMessageBatch) -> Unit
    ) {
        onResult(readRecentMessages(page, limit))
    }
}
