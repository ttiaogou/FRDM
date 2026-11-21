package com.freedom.adapters.accessibility.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ActivityUsageEvidenceResolverTest {
    private val now = 1_800_000L
    private val target = "com.immomo.momo"
    private val activity = "com.immomo.momo.maintab.MaintabActivity"

    private fun record(
        packageName: String = target,
        activityClass: String = activity,
        transition: ActivityUsageTransition = ActivityUsageTransition.RESUMED,
        time: Long = now - 1_000L
    ) = ActivityUsageTransitionRecord(packageName, activityClass, transition, time)

    @Test
    fun foregroundResumedEventProvidesActivityEvidence() {
        assertEquals(activity, ActivityUsageEvidenceResolver.resolve(listOf(record()), target, now)?.activityClass)
    }

    @Test
    fun pausedTargetActivityInvalidatesFormerResume() {
        assertNull(ActivityUsageEvidenceResolver.resolve(listOf(
            record(time = now - 3_000L),
            record(transition = ActivityUsageTransition.PAUSED, time = now - 500L)
        ), target, now))
    }

    @Test
    fun laterOtherAppResumeInvalidatesHistoricalTargetEvent() {
        assertNull(ActivityUsageEvidenceResolver.resolve(listOf(
            record(time = now - 3_000L),
            record(packageName = "com.android.settings", activityClass = "SettingsActivity", time = now - 500L)
        ), target, now))
    }

    @Test
    fun freshTargetResumeAfterOtherAppRestoresEvidence() {
        val records = listOf(
            record(time = now - 5_000L),
            record(packageName = "com.android.settings", activityClass = "SettingsActivity", time = now - 3_000L),
            record(time = now - 500L)
        )
        assertEquals(activity, ActivityUsageEvidenceResolver.resolve(records, target, now)?.activityClass)
    }

    @Test
    fun widgetClassNamesAreNotAcceptedAsActivityIdentity() {
        assertNull(ActivityUsageEvidenceResolver.resolve(listOf(
            record(activityClass = "androidx.recyclerview.widget.RecyclerView")
        ), target, now))
    }

    @Test
    fun missingAndExpiredEventsNeverInventAnActivity() {
        assertNull(ActivityUsageEvidenceResolver.resolve(emptyList(), target, now))
        assertNull(ActivityUsageEvidenceResolver.resolve(listOf(
            record(time = now - AndroidUsageActivityEvidenceReader.LOOKBACK_MS - 1)
        ), target, now))
    }
}
