package com.freedom.features.accountbrowse

import com.freedom.features.accountbrowse.ports.ImageSequencePosition
import org.junit.Assert.assertEquals
import org.junit.Test

class R02ImagePagingTraceTest {
    @Test
    fun twoVerifiedSwipesAndUncertainEvidenceProduceFixedDecisionTrace() {
        fun position(n: Int) = ImageSequencePosition(n, 9)
        val start = position(5)
        val original = ImagePagingProgress("window", ImageBrowsePlan(start, ImageSwipeDirection.LEFT, 2), deadlineAt = 100)
        val trace = mutableListOf<String>()
        trace += original.observe(start, 1).name
        val pending = original.copy(awaitingVerification = true)
        trace += pending.observe(null, 2).name
        trace += pending.observe(position(7), 2).name
        trace += pending.observe(position(6), 2).name
        val next = pending.markVerified(200)
        trace += "count:${next.confirmedSwipes}:${next.finished}"
        trace += next.observe(position(6), 3).name
        val second = next.copy(awaitingVerification = true)
        trace += second.observe(position(7), 4).name
        val done = second.markVerified(300)
        trace += "count:${done.confirmedSwipes}:${done.finished}"
        trace += done.observe(position(7), 4).name
        trace += pending.observe(null, 100).name
        assertEquals(listOf("READY", "WAIT", "FINISH", "VERIFIED", "count:1:false",
            "READY", "VERIFIED", "count:2:true", "FINISH", "FINISH"), trace)
    }
}
