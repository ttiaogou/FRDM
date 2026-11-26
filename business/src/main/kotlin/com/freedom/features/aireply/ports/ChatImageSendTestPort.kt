package com.freedom.features.aireply.ports

/** Existing AI observer drives one explicitly authorized chat-detail child transaction. */
const val INTERCEPTION_TEST_REQUEST_PREFIX = "interception:"
enum class ImageSendTestProgress { IDLE, ACTIVE, TERMINAL }

interface ChatImageSendTestPort {
    fun progress(): ImageSendTestProgress
    /** Capture the pre-send baseline, then durably consume the one-shot request before any tap. */
    fun begin(target: ChatDraftInputTarget, requestId: String, claim: () -> Boolean, ownsTransaction: () -> Boolean)
    /** One fresh observation/at most one action. No timers, retries or business counters are owned here. */
    fun observe()
    fun abort(reason: String)
    fun reset(reason: String)
}
