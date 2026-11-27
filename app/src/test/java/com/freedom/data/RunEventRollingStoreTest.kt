package com.freedom.data

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RunEventRollingStoreTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun normalCapacityEvictsOldestChunksAndContinuesWithoutTouchingProtectedLanes() {
        val directory = temp.newFolder()
        val store = RunEventRollingStore(directory, normalLimit = 12, warningLimit = 8,
            criticalLimit = 8, lifecycleLimit = 8, segmentLimit = 6)
        val lane = RunEventRollingStore.Lane.NORMAL
        assertEquals(RunEventRollingStore.Status.WRITTEN, store.append(RunEventRollingStore.Lane.LIFECYCLE, "0").status)
        assertEquals(RunEventRollingStore.Status.WRITTEN, store.append(RunEventRollingStore.Lane.WARNING, "1").status)
        assertEquals(RunEventRollingStore.Status.WRITTEN, store.append(RunEventRollingStore.Lane.CRITICAL, "2").status)
        for (n in 10..13) assertEquals(RunEventRollingStore.Status.WRITTEN, store.append(lane, n.toString()).status)
        val rotated = store.append(lane, "14")
        assertEquals(RunEventRollingStore.Status.WRITTEN, rotated.status)
        assertEquals(2L, rotated.evictedRecords)
        assertEquals(6L, rotated.evictedBytes)
        assertEquals(RunEventRollingStore.Status.WRITTEN, store.append(lane, "15").status)
        assertEquals(RunEventRollingStore.Status.WRITTEN, store.append(RunEventRollingStore.Lane.LIFECYCLE, "99").status)
        RunEventRollingStore.seal(directory) { it.toLong() }
        assertEquals(listOf("0", "1", "2", "12", "13", "14", "15", "99"),
            RunEventRollingStore.sources(directory).single().readLines())
    }

    @Test fun warningAndCriticalBudgetsCannotConsumeEachOtherOrNormalRetention() {
        val directory = temp.newFolder()
        val store = RunEventRollingStore(directory, 8, 4, 4, 4, 4)
        val warning = RunEventRollingStore.Lane.WARNING
        val critical = RunEventRollingStore.Lane.CRITICAL
        assertEquals(RunEventRollingStore.Status.WRITTEN, store.append(warning, "100").status)
        assertEquals(RunEventRollingStore.Status.BUDGET_REJECTED, store.append(warning, "101").status)
        assertEquals(RunEventRollingStore.Status.WRITTEN, store.append(critical, "200").status)
        assertEquals(RunEventRollingStore.Status.BUDGET_REJECTED, store.append(critical, "201").status)
        assertEquals(RunEventRollingStore.Status.WRITTEN, store.append(RunEventRollingStore.Lane.NORMAL, "300").status)
        assertEquals(RunEventRollingStore.Status.WRITTEN, store.append(RunEventRollingStore.Lane.LIFECYCLE, "400").status)
        RunEventRollingStore.seal(directory) { it.toLong() }
        assertEquals(listOf("100", "200", "300", "400"), RunEventRollingStore.sources(directory).single().readLines())
    }

    @Test fun queueReservesKeepNormalAndWarningPressureOutOfCriticalAndSealCapacity() {
        val normal = RunEventRollingStore.Lane.NORMAL
        val warning = RunEventRollingStore.Lane.WARNING
        val critical = RunEventRollingStore.Lane.CRITICAL
        assertTrue(RunEventRollingStore.canEnqueue(normal, 129))
        assertFalse(RunEventRollingStore.canEnqueue(normal, 128))
        assertTrue(RunEventRollingStore.canEnqueue(warning, 128))
        assertFalse(RunEventRollingStore.canEnqueue(warning, 64))
        assertTrue(RunEventRollingStore.canEnqueue(critical, 64))
        assertFalse(RunEventRollingStore.canEnqueue(critical, 1))
    }

    @Test fun restartSealsSpoolOnceAndLegacyCanonicalFileWinsOverInterruptedCleanup() {
        val directory = temp.newFolder()
        val store = RunEventRollingStore(directory, 12, 8, 8, 8, 6)
        store.append(RunEventRollingStore.Lane.NORMAL, "5")
        store.append(RunEventRollingStore.Lane.CRITICAL, "3")
        assertFalse(java.io.File(directory, "events.jsonl").exists())
        java.io.File(directory, "events.jsonl.sealing").writeText("stale")
        RunEventRollingStore.seal(directory) { it.toLong() }
        assertEquals(listOf("3", "5"), RunEventRollingStore.sources(directory).single().readLines())
        java.io.File(directory, "event_segments").mkdirs()
        java.io.File(directory, "event_segments/critical.jsonl").writeText("3\n")
        RunEventRollingStore.seal(directory) { it.toLong() }
        assertEquals(listOf("3", "5"), RunEventRollingStore.sources(directory).single().readLines())
        assertFalse(java.io.File(directory, "event_segments").exists())
    }
}
