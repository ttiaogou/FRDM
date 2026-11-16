package com.freedom.adapters.accessibility.android

import org.junit.Assert.assertEquals
import org.junit.Test

class AndroidMonitorLogPresenterTest {
    @Test
    fun eachPersistedLogKeepsTimestampAndRefreshesExactlyOnceAfterPersistence() {
        val trace = mutableListOf<String>()
        val persisted = mutableListOf<String>()
        val presenter = AndroidMonitorLogPresenter(
            persistLog = { persisted += it; trace += "persist" },
            refreshPresentation = { trace += "refresh" },
            timestamp = { "12:34:56.789" }
        )

        presenter.addLog("测试日志")

        assertEquals(listOf("12:34:56.789  测试日志"), persisted)
        assertEquals(listOf("persist", "refresh"), trace)
    }

    @Test
    fun explicitRefreshDoesNotPersistAnotherLog() {
        val persisted = mutableListOf<String>()
        var refreshCount = 0
        val presenter = AndroidMonitorLogPresenter(
            persistLog = persisted::add,
            refreshPresentation = { refreshCount++ },
            timestamp = { "00:00:00.000" }
        )

        presenter.refresh()

        assertEquals(emptyList<String>(), persisted)
        assertEquals(1, refreshCount)
    }
}
