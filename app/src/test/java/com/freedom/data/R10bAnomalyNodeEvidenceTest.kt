package com.freedom.data

import com.freedom.core.accessibility.api.AccessibilityNodeSnapshot
import com.freedom.core.accessibility.api.AccessibilityTreeSnapshot
import com.freedom.core.accessibility.api.NodePath
import com.freedom.core.accessibility.api.NodeTextCapture
import com.freedom.core.accessibility.api.ScreenRect
import com.freedom.core.accessibility.api.WindowRef
import com.freedom.core.logging.RunDiagnosticEvent
import com.freedom.core.logging.RunLogCategory
import com.freedom.core.logging.RunLogLevel
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class R10bAnomalyNodeEvidenceTest {
    @get:Rule val temp = TemporaryFolder()

    private fun snapshot(checked: Boolean = false, at: Long = 100L, generation: Long = 4L): AccessibilityTreeSnapshot {
        val window = WindowRef("com.immomo.momo", "ChatSettingActivity", generation, 7)
        val node = AccessibilityNodeSnapshot(
            path = NodePath(emptyList()), className = "android.widget.CompoundButton",
            viewId = "com.immomo.momo:id/btn_switch", text = "DO_NOT_EXPORT_MESSAGE",
            contentDescription = "DO_NOT_EXPORT_CONTACT", bounds = ScreenRect(0, 0, 100, 100),
            visible = true, enabled = true, checkable = true, checked = checked, clickable = true,
            longClickable = false, scrollable = false, editable = false, selected = false,
            focused = false, childCount = 0, packageName = window.packageName, windowId = window.windowId
        )
        return AccessibilityTreeSnapshot(window, listOf(node), 1, false,
            capturedAtEpochMs = at, windowId = window.windowId, textCapture = NodeTextCapture.NONE)
    }

    private fun store(directory: File) = RunIncidentEvidenceStore(directory) { "{}" }

    @Test fun firstEvidencePreservedAndSameReasonStructureDeduplicated() {
        val directory = temp.newFolder()
        val store = store(directory)
        assertTrue(store.recordPageEvidence("AI_REPLY_PRIVATE_MESSAGES", 2, "PRIVATE_INTERCEPTION:OPEN_CONTROL:UNKNOWN", snapshot()))
        assertFalse(store.recordPageEvidence("AI_REPLY_PRIVATE_MESSAGES", 2, "PRIVATE_INTERCEPTION:OPEN_CONTROL:UNKNOWN", snapshot(at = 200)))
        assertTrue(store.recordPageEvidence("AI_REPLY_PRIVATE_MESSAGES", 2, "PRIVATE_INTERCEPTION:OPEN_CONTROL:UNKNOWN", snapshot(checked = true)))
        assertTrue(store.recordPageEvidence("AI_REPLY_PRIVATE_MESSAGES", 2, "OTHER_REASON", snapshot()))
        assertTrue(store.recordPageEvidence("AI_REPLY_PRIVATE_MESSAGES", 2, "OTHER_REASON", snapshot(generation = 5)))
        val files = RunIncidentEvidenceStore.pageEvidenceFiles(directory)
        assertEquals(4, files.size)
        assertEquals(files, RunIncidentEvidenceStore.files(directory))
        val first = files.first().readText()
        assertTrue(first.contains("ChatSettingActivity"))
        assertTrue(first.contains("btn_switch"))
        assertTrue(first.contains("\"capturePriority\":\"ANOMALY\""))
        assertTrue(first.contains("\"runGeneration\":2"))
        assertTrue(first.contains("\"capturedAtEpochMs\": 100"))
        assertFalse(first.contains("DO_NOT_EXPORT"))
        assertFalse(store.recordPageEvidence("AI_REPLY_PRIVATE_MESSAGES", 2, "BAD_REASON", snapshot().copy(textCapture = NodeTextCapture.EPHEMERAL)))
        assertFalse(store.recordPageEvidence("AI_REPLY_PRIVATE_MESSAGES", 2, "BAD_REASON", snapshot().copy(windowId = 8)))
    }

    @Test fun ordinaryIncidentCapacityCannotConsumeNodeReserveAndFirstEvidenceSurvivesLimit() {
        val directory = temp.newFolder()
        val store = store(directory)
        for (i in 1L..40L) {
            store.observe(RunDiagnosticEvent(i, "event-$i", "run", "task", "trace-$i",
                i, i, RunLogLevel.WARN, RunLogCategory.NAVIGATION, "Test", "failure", "bounded"), emptyList())
        }
        assertTrue(store.stats.rejected > 0)
        for (i in 1..RunIncidentEvidenceStore.MAX_PAGE_EVIDENCE) {
            assertTrue(store.recordPageEvidence("ACCOUNT_BROWSE", 1, "REASON_$i", snapshot()))
        }
        val files = RunIncidentEvidenceStore.pageEvidenceFiles(directory)
        val first = files.first().readBytes()
        assertFalse(store.hasPageEvidenceCapacity)
        assertFalse(store.recordPageEvidence("ACCOUNT_BROWSE", 1, "OVER_LIMIT", snapshot()))
        assertEquals(RunIncidentEvidenceStore.MAX_PAGE_EVIDENCE, files.size)
        assertArrayEquals(first, files.first().readBytes())
        assertTrue(files.all { it.length() <= RunIncidentEvidenceStore.MAX_PAGE_EVIDENCE_BYTES })
    }

    @Test fun anomalyOnlyRunIsRetainedAndDoesNotCopyOrdinaryLogs() {
        val runs = temp.newFolder("runs")
        val directory = File(runs, "run-1").apply { mkdirs() }
        val store = store(directory)
        assertTrue(store.recordPageEvidence("AI_REPLY_RECEIVED_GREETINGS", 3, "PAGE_ROUTE_UNCLASSIFIED", snapshot()))
        File(directory, "events.jsonl").writeText("ordinary")
        val history = temp.newFolder("history")
        assertTrue(RunIncidentHistoryStore(history).preserve(directory) {
            File(it, "diagnostic_manifest.json").writeText("test-manifest")
        })
        val original = RunIncidentEvidenceStore.pageEvidenceFiles(directory).single()
        val copy = RunIncidentEvidenceStore.pageEvidenceFiles(File(history, directory.name)).single()
        assertArrayEquals(original.readBytes(), copy.readBytes())
        assertFalse(File(history, "run-1/events.jsonl").exists())
    }
}
