package com.freedom.data

import com.freedom.core.logging.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RunIncidentEvidenceStoreTest {
    @get:Rule val temp = TemporaryFolder()

    private fun event(
        sequence: Long, level: RunLogLevel = RunLogLevel.INFO,
        trace: String = "request-a", task: String = "private", run: String = "run",
        elapsed: Long = sequence * 100L, result: String? = null
    ) = RunDiagnosticEvent(
        sequence, "event-$sequence", run, task, trace, elapsed, elapsed,
        level, RunLogCategory.TASK, "Test", "test_event", "bounded evidence",
        result = result, severitySource = RunLogSeveritySource.PRODUCER_DECLARED
    )

    private fun store(directory: java.io.File) =
        RunIncidentEvidenceStore(directory) { """{"sequence":${it.sequence}}""" }

    @Test fun triggerFreezesPriorAndLaterEvidenceWithExplicitAssociation() {
        val directory = temp.newFolder()
        val store = store(directory)
        val recent = listOf(event(1), event(2, trace = "request-b"),
            event(3, trace = "run", task = "UNKNOWN"), event(4, task = "greetings"),
            event(5, run = "other"))
        val trigger = event(6, RunLogLevel.WARN)
        store.observe(trigger, recent)
        store.observe(event(7, trace = "request-b"), emptyList())
        store.observe(event(8), emptyList())
        store.observe(event(9, trace = "run", task = "UNKNOWN"), emptyList())
        store.finish()
        val incident = java.io.File(directory, "incidents/incident-6")
        val lines = java.io.File(incident, "timeline.jsonl").readLines()
        assertEquals(listOf(1, 3, 6, 8, 9), lines.map {
            Regex("\"sequence\":(\\d+)").find(it)!!.groupValues[1].toInt()
        })
        assertTrue(lines[0].contains("SAME_TRACE"))
        assertTrue(lines[1].contains("NEARBY_UNCORRELATED"))
        assertTrue(lines[2].contains("TRIGGER"))
        assertTrue(lines[3].contains("AFTER"))
        assertTrue(java.io.File(incident, "capture_end.json").readText().contains("RUN_ENDED"))
        assertEquals(1L, store.stats.captured)
    }

    @Test fun frozenIncidentSurvivesNormalRotationAndProtectedLaneExhaustion() {
        val directory = temp.newFolder()
        val store = store(directory)
        val rolling = RunEventRollingStore(directory, 12, 4, 4, 4, 6)
        val trigger = event(2, RunLogLevel.ERROR)
        store.observe(trigger, listOf(event(1)))
        rolling.append(RunEventRollingStore.Lane.CRITICAL, "100")
        assertEquals(RunEventRollingStore.Status.BUDGET_REJECTED,
            rolling.append(RunEventRollingStore.Lane.CRITICAL, "101").status)
        for (i in 10..30) rolling.append(RunEventRollingStore.Lane.NORMAL, i.toString())
        store.observe(event(3), emptyList())
        store.finish()
        RunEventRollingStore.seal(directory) { it.toLong() }
        val timeline = java.io.File(directory, "incidents/incident-2/timeline.jsonl").readText()
        assertTrue(timeline.contains("\"sequence\":1"))
        assertTrue(timeline.contains("\"sequence\":2"))
        assertTrue(timeline.contains("\"sequence\":3"))
        assertFalse(RunEventRollingStore.sources(directory).single().readLines().contains("10"))
    }

    @Test fun severityAndWindowLimitsKeepNormalEventsOutAndReserveSevereSlots() {
        val directory = temp.newFolder()
        val store = store(directory)
        store.observe(event(1), emptyList())
        store.observe(event(2, RunLogLevel.WARN, result = "DEDUPLICATED"), emptyList())
        assertTrue(RunIncidentEvidenceStore.directories(directory).isEmpty())
        // Distinct request identities, not repeats of the same transaction.
        for (n in 10L..29L) store.observe(event(n, RunLogLevel.WARN, trace = "request-$n"), emptyList())
        assertEquals(16L, store.stats.captured)
        assertEquals(4L, store.stats.rejected)
        assertTrue(store.stats.postLimited > 0)
        store.observe(event(30, RunLogLevel.ERROR, trace = "error-request"), emptyList())
        store.observe(event(31, RunLogLevel.FATAL, trace = "fatal-request"), emptyList())
        assertEquals(18L, store.stats.captured)
        store.finish()
    }

    @Test fun elapsedWindowAndInterruptedRecoveryDoNotClaimBusinessResolution() {
        val directory = temp.newFolder()
        val store = store(directory)
        store.observe(event(1, RunLogLevel.ERROR), emptyList())
        store.observe(event(2, elapsed = 31_000), emptyList())
        assertTrue(java.io.File(directory, "incidents/incident-1/capture_end.json").readText().contains("WINDOW_ELAPSED"))
        store.observe(event(3, RunLogLevel.ERROR, trace = "request-b", elapsed = 32_000), emptyList())
        store.observe(event(4, trace = "request-b", elapsed = 33_000), emptyList())
        assertEquals(0L, RunIncidentEvidenceStore.recoverInterrupted(directory))
        val end = java.io.File(directory, "incidents/incident-3/capture_end.json")
        val saved = end.readText()
        assertTrue(saved.contains("PROCESS_INTERRUPTED"))
        assertTrue(saved.contains("\"afterRecords\":1"))
        assertTrue(saved.contains("NOT_EVALUATED"))
        assertEquals(0L, RunIncidentEvidenceStore.recoverInterrupted(directory))
        assertEquals(saved, end.readText())
    }

    @Test fun largeUncorrelatedPostEventCannotCloseWindowOrConsumeRelatedBudget() {
        val directory = temp.newFolder()
        val store = RunIncidentEvidenceStore(directory) {
            """{"sequence":${it.sequence},"message":"${it.message}"}"""
        }
        store.observe(event(1, RunLogLevel.ERROR), emptyList())
        store.observe(event(2, trace = "run", task = "UNKNOWN").copy(message = "x".repeat(20_000)), emptyList())
        store.observe(event(3), emptyList())
        store.finish()
        val lines = java.io.File(directory, "incidents/incident-1/timeline.jsonl").readLines()
        assertEquals(2, lines.size)
        assertTrue(lines.last().contains("SAME_TRACE"))
        assertTrue(lines.last().contains("\"sequence\":3"))
        assertEquals(0L, store.stats.postLimited)
    }

    @Test fun repeatsAfterCaptureWindowMergeAndOnlyExactTerminalProofResolves() {
        val directory = temp.newFolder()
        val store = store(directory)
        fun model(n: Long, type: String, level: RunLogLevel, result: String, generation: Long = 7) =
            event(n, level, result = result).copy(component = "AiModel", eventType = type,
                requestGeneration = generation, elapsedRealtimeMs = n * 40_000L)
        store.observe(model(1, "MODEL_ATTEMPT_FINISHED", RunLogLevel.WARN, "EMPTY_MODEL_OUTPUT"), emptyList())
        store.observe(model(2, "MODEL_ATTEMPT_FINISHED", RunLogLevel.WARN, "EMPTY_MODEL_OUTPUT"), emptyList())
        assertEquals(1L, store.stats.captured)
        assertEquals(1L, store.stats.merged)
        store.observe(model(3, "MODEL_REQUEST_FINISHED", RunLogLevel.INFO, "SUCCEEDED", 8), emptyList())
        assertEquals(0L, store.stats.resolved)
        store.observe(model(4, "MODEL_ATTEMPT_FINISHED", RunLogLevel.INFO, "VALID_DECISION"), emptyList())
        assertEquals(0L, store.stats.resolved)
        store.observe(model(5, "MODEL_RETRY_EXECUTING", RunLogLevel.INFO, "EXECUTING"), emptyList())
        assertEquals(RunIncidentEvidencePolicy.Outcome.RECOVERING,
            RunIncidentEvidenceStore.transactionState(java.io.File(directory, "incidents/incident-1")))
        store.observe(model(6, "MODEL_REQUEST_FINISHED", RunLogLevel.INFO, "SUCCEEDED"), emptyList())
        assertEquals(1L, store.stats.resolved)
        val original = java.io.File(directory, "incidents/incident-1")
        val summary = java.io.File(original, "transaction.json").readText()
        assertTrue(summary.contains("\"occurrences\":2"))
        assertTrue(summary.contains("\"progressEvents\":1"))
        assertTrue(summary.contains("\"lastProgressSequence\":5"))
        assertTrue(java.io.File(original, "latest_evidence.json").readText().contains("\"sequence\":6"))
        store.observe(model(7, "MODEL_ATTEMPT_FINISHED", RunLogLevel.WARN, "EMPTY_MODEL_OUTPUT"), emptyList())
        store.finish()
        assertEquals(2L, store.stats.captured)
        assertEquals(1L, store.stats.unresolved)
        assertEquals(RunIncidentEvidencePolicy.Outcome.RESOLVED, RunIncidentEvidenceStore.transactionState(original))
    }

    @Test fun sendSegmentConfirmationDoesNotSettleWholeSendAndInterruptedClosedWindowIsMarked() {
        val directory = temp.newFolder()
        val store = store(directory)
        fun send(n: Long, type: String, level: RunLogLevel, result: String) =
            event(n, level, result = result).copy(component = "AiSend", eventType = type,
                requestGeneration = 1, elapsedRealtimeMs = n * 40_000L)
        store.observe(send(1, "MESSAGE_SEND_UNCERTAIN", RunLogLevel.WARN, "UNKNOWN"), emptyList())
        store.observe(send(2, "MESSAGE_SEND_CONFIRMED", RunLogLevel.INFO, "CONFIRMED"), emptyList())
        val incident = java.io.File(directory, "incidents/incident-1")
        assertTrue(java.io.File(incident, "capture_end.json").exists())
        assertEquals(RunIncidentEvidencePolicy.Outcome.RECOVERING, RunIncidentEvidenceStore.transactionState(incident))
        assertEquals(0L, RunIncidentEvidenceStore.recoverInterrupted(directory))
        assertEquals(RunIncidentEvidencePolicy.Outcome.INTERRUPTED, RunIncidentEvidenceStore.transactionState(incident))
    }

    @Test fun explicitRequestGenerationConflictDoesNotAssociateEvenWithSameTrace() {
        val trigger = event(1, RunLogLevel.ERROR).copy(requestGeneration = 1)
        val stale = event(2).copy(requestGeneration = 2)
        assertNull(RunIncidentEvidencePolicy.relation(trigger, stale))
    }
}
