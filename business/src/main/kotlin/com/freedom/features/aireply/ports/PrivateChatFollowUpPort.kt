package com.freedom.features.aireply.ports

import com.freedom.features.aireply.domain.contact.ContactFollowUpStep

enum class PrivateChatFollowUpProgress { IDLE, ACTIVE, TERMINAL }

/** Parent-bound private-chat image and interception execution. */
interface PrivateChatFollowUpPort {
    fun progress(): PrivateChatFollowUpProgress

    /** Caller owns durable checkpoint writes; false forbids the next UI mutation. */
    fun beginPrivateFollowUp(
        target: ChatDraftInputTarget,
        transactionId: String,
        interception: Boolean,
        beforeStep: (ContactFollowUpStep) -> Boolean,
        ownsTransaction: () -> Boolean
    ): Boolean

    /** Only independent external result evidence can confirm the business action. */
    fun privateFollowUpSucceeded(): Boolean

    /** One fresh observation/at most one action. No separate timers or business counters. */
    fun observe()
    fun abort(reason: String)
    fun reset(reason: String)
}
