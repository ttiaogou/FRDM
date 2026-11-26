package com.freedom.features.accountbrowse

import com.freedom.features.accountbrowse.ports.ImageSequencePosition

/** Volatile cursor for one admitted image visit. No coordinates or pending command survive reset. */
data class ImagePagingProgress(
    val fingerprint: String,
    val plan: ImageBrowsePlan? = null,
    val confirmedSwipes: Int = 0,
    val awaitingVerification: Boolean = false,
    val deadlineAt: Long,
    val finished: Boolean = false,
    val stopAfterVerification: Boolean = false
) {
    enum class Decision { WAIT, READY, VERIFIED, FINISH }

    fun observe(position: ImageSequencePosition?, now: Long): Decision {
        if (finished) return Decision.FINISH
        if (position == null) return if (now >= deadlineAt) Decision.FINISH else Decision.WAIT
        val selected = plan ?: return if (position.total == 1) Decision.FINISH else Decision.READY
        if (position.total != selected.startPosition.total || confirmedSwipes >= selected.swipeCount) {
            return Decision.FINISH
        }
        val delta = if (selected.direction == ImageSwipeDirection.LEFT) 1 else -1
        val current = selected.startPosition.current + delta * confirmedSwipes
        if (awaitingVerification) {
            if (position.current == current + delta) return Decision.VERIFIED
            if (position.current != current || now >= deadlineAt) return Decision.FINISH
            return Decision.WAIT
        }
        return if (position.current == current && now < deadlineAt) Decision.READY else Decision.FINISH
    }

    fun markVerified(nextDeadlineAt: Long): ImagePagingProgress {
        val selected = requireNotNull(plan)
        check(awaitingVerification && confirmedSwipes < selected.swipeCount)
        val count = confirmedSwipes + 1
        return copy(
            confirmedSwipes = count, awaitingVerification = false,
            deadlineAt = nextDeadlineAt, finished = stopAfterVerification || count == selected.swipeCount
        )
    }
}
