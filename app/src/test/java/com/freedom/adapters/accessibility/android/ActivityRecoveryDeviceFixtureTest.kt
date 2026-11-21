package com.freedom.adapters.accessibility.android

import org.junit.Assert.assertTrue
import org.junit.Test

class ActivityRecoveryDeviceFixtureTest {
    private fun fixture(name: String): String =
        requireNotNull(javaClass.classLoader?.getResourceAsStream("recovery/" + name)) {
            "Missing real-device recovery fixture: " + name
        }.bufferedReader().use { it.readText() }

    @Test
    fun processRestartShowsBusinessProofWithMissingActivityAndSafeRejection() {
        val log = fixture("device-process-restart-033243.txt")
        assertTrue(log.contains("PROCESS_RESTART_CHECKPOINT"))
        assertTrue(log.contains("SERVICE_RECONNECT_CHECKPOINT"))
        assertTrue(log.contains("RECOVERY_VERIFIED"))
        assertTrue(log.contains("activity=UNKNOWN"))
        assertTrue(log.contains("APP_NOT_READY"))
    }

    @Test
    fun reconnectEvidenceIncludesLegitimateQuietPeriodAndLaterGestureCancellation() {
        val log = fixture("device-service-reconnect-031120.txt")
        assertTrue(log.contains("STARTING->READY"))
        assertTrue(log.contains("列表滚动被系统取消"))
    }

    @Test
    fun adbInducedCrashesArePreservedAsExperimentalStimulusNotBusinessFailures() {
        val log = fixture("device-adb-crashes-031122.txt")
        assertTrue(log.contains("CrashedByAdbException"))
        assertTrue(log.contains("shell-induced crash"))
    }
}
