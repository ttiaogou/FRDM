package com.freedom.features.aireply.ai

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UnknownRetryableTransitionTest {
    @Test
    fun unknown_cannot_become_permanent_failure() {
        assertTrue(
            ReplyTaskStatusTransitions.isAllowed(
                ReplyTaskStatus.UNKNOWN,
                ReplyTaskStatus.FAILED_RETRYABLE
            )
        )
        assertFalse(
            ReplyTaskStatusTransitions.isAllowed(
                ReplyTaskStatus.UNKNOWN,
                ReplyTaskStatus.FAILED_FINAL
            )
        )
    }

    @Test
    fun unknown_segment_cannot_become_permanent_failure() {
        assertTrue(
            ReplySegmentTaskTransitions.isAllowed(
                ReplySegmentTaskStatus.UNKNOWN,
                ReplySegmentTaskStatus.FAILED_RETRYABLE
            )
        )
        assertFalse(
            ReplySegmentTaskTransitions.isAllowed(
                ReplySegmentTaskStatus.UNKNOWN,
                ReplySegmentTaskStatus.FAILED_FINAL
            )
        )
    }
}
