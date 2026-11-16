package com.freedom.core.runtime

import com.freedom.core.accessibility.events.InputOrigin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UnknownInteractionArbiterTest {
    @Test
    fun repeatedCallbacksFromOneRecyclerViewBurstCountAsOneBehaviour() {
        val arbiter = UnknownInteractionArbiter()
        val first = arbiter.record(InputOrigin.UNKNOWN, 10, 4, "SCROLL", "RecyclerView|inbox", 1_000)
        val second = arbiter.record(InputOrigin.UNKNOWN, 10, 4, "SCROLL", "RecyclerView|inbox", 1_350)
        val third = arbiter.record(InputOrigin.UNKNOWN, 10, 4, "SCROLL", "RecyclerView|inbox", 1_800)

        assertEquals(UnknownInteractionArbiter.Disposition.COUNTED_AS_NEW_BEHAVIOUR, first.disposition)
        assertEquals(UnknownInteractionArbiter.Disposition.COALESCED_WITH_CURRENT_BEHAVIOUR, second.disposition)
        assertEquals(UnknownInteractionArbiter.Disposition.COALESCED_WITH_CURRENT_BEHAVIOUR, third.disposition)
        assertEquals(1, arbiter.currentCount)
    }

    @Test
    fun threeDifferentBehaviourSourcesReachThresholdButDoNotCountRawBursts() {
        val arbiter = UnknownInteractionArbiter()
        arbiter.record(InputOrigin.UNKNOWN, 10, 4, "SCROLL", "RecyclerView|inbox", 1_000)
        arbiter.record(InputOrigin.UNKNOWN, 10, 4, "CLICK", "Button|popup-close", 2_000)
        val third = arbiter.record(InputOrigin.UNKNOWN, 11, 5, "CLICK", "Button|new-popup", 3_000)

        assertEquals(UnknownInteractionArbiter.Disposition.THRESHOLD_REACHED, third.disposition)
        assertTrue(third.thresholdReached)
        assertEquals(3, third.count)
    }

    @Test
    fun globalTouchStartEndAndAdjacentTargetFeedbackCanCoalesce() {
        val arbiter = UnknownInteractionArbiter()
        arbiter.record(InputOrigin.UNKNOWN, 10, 4, "GLOBAL_TOUCH", "", 1_000)
        val click = arbiter.record(InputOrigin.UNKNOWN, 10, 4, "CLICK", "Button|close", 1_120)
        val end = arbiter.record(InputOrigin.UNKNOWN, 10, 4, "GLOBAL_TOUCH", "", 1_180)

        assertEquals(UnknownInteractionArbiter.Disposition.COALESCED_WITH_CURRENT_BEHAVIOUR, click.disposition)
        assertEquals(UnknownInteractionArbiter.Disposition.COALESCED_WITH_CURRENT_BEHAVIOUR, end.disposition)
        assertEquals(1, arbiter.currentCount)
    }

    @Test
    fun missingGlobalBoundaryIdentifiersAreReplacedByAdjacentConcreteIdentity() {
        val arbiter = UnknownInteractionArbiter()
        arbiter.record(InputOrigin.UNKNOWN, -1, 0, "GLOBAL_TOUCH", "", 1_000)
        val adjacentClick = arbiter.record(InputOrigin.UNKNOWN, 20, 5, "CLICK", "Button|popup-one", 1_100)
        val newPageClick = arbiter.record(InputOrigin.UNKNOWN, 21, 6, "CLICK", "Button|popup-two", 1_200)

        assertEquals(UnknownInteractionArbiter.Disposition.COALESCED_WITH_CURRENT_BEHAVIOUR, adjacentClick.disposition)
        assertEquals(UnknownInteractionArbiter.Disposition.COUNTED_AS_NEW_BEHAVIOUR, newPageClick.disposition)
        assertEquals(2, arbiter.currentCount)
    }

    @Test
    fun physicalUserEvidenceTriggersImmediatelyAndSelfFeedbackIsIgnored() {
        val arbiter = UnknownInteractionArbiter()
        val self = arbiter.record(InputOrigin.SELF_INJECTED, 10, 4, "SCROLL", "RecyclerView", 1_000)
        val app = arbiter.record(InputOrigin.APPLICATION_INTERNAL, 10, 4, "SCROLL", "RecyclerView", 1_050)
        val human = arbiter.record(InputOrigin.PHYSICAL_USER, 10, 4, "CLICK", "Button|close", 1_100)

        assertEquals(UnknownInteractionArbiter.Disposition.SELF_ACTION_IGNORED, self.disposition)
        assertEquals(UnknownInteractionArbiter.Disposition.APPLICATION_EVENT_IGNORED, app.disposition)
        assertEquals(0, app.count)
        assertEquals(UnknownInteractionArbiter.Disposition.HUMAN_CONFIRMED, human.disposition)
        assertTrue(human.thresholdReached)
    }

    @Test
    fun targetApplicationControlFeedbackDoesNotPolluteUnknownCounterOrQuietClock() {
        val arbiter = UnknownInteractionArbiter()
        val first = arbiter.record(InputOrigin.UNKNOWN, 10, 4, "GLOBAL_TOUCH", "", 1_000)
        val app = arbiter.record(InputOrigin.APPLICATION_INTERNAL, 10, 4, "SCROLL", "RecyclerView|inbox", 9_000)

        assertEquals(1, first.count)
        assertEquals(UnknownInteractionArbiter.Disposition.APPLICATION_EVENT_IGNORED, app.disposition)
        assertEquals(1, arbiter.currentCount)
        assertTrue(arbiter.clearAfterStableQuiet(11_000))
    }

    @Test
    fun onlySubThresholdCountCanClearAfterTenSecondStableQuietPeriod() {
        val arbiter = UnknownInteractionArbiter()
        arbiter.record(InputOrigin.UNKNOWN, 10, 4, "SCROLL", "RecyclerView|inbox", 1_000)

        assertFalse(arbiter.clearAfterStableQuiet(10_999))
        assertTrue(arbiter.clearAfterStableQuiet(11_000))
        assertEquals(0, arbiter.currentCount)

        arbiter.record(InputOrigin.UNKNOWN, 10, 4, "SCROLL", "RecyclerView|inbox", 12_000)
        arbiter.record(InputOrigin.UNKNOWN, 10, 4, "CLICK", "Button|popup", 13_500)
        arbiter.record(InputOrigin.UNKNOWN, 11, 5, "CLICK", "Button|another-popup", 15_000)
        assertFalse(arbiter.clearAfterStableQuiet(30_000))
    }
}
