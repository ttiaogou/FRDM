package com.freedom.architecture

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Protects the P1 removal of terminal manual-handoff paths without changing runtime safety. */
class AccountBrowseManualHandoffBoundaryTest {
    private val projectRoot: File
        get() = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
            .firstOrNull { File(it, "settings.gradle.kts").isFile }
            ?: error("Unable to find project root")

    private fun source(path: String): String = File(projectRoot, path).readText()

    @Test
    fun obsoleteManualHandoffPolicyAndCoordinatorRemainAbsent() {
        assertFalse(
            File(projectRoot, "core/src/main/kotlin/com/freedom/core/runtime/TieredManualHandoffQuietPeriod.kt").exists()
        )
        assertFalse(
            File(
                projectRoot,
                "app/src/main/java/com/freedom/adapters/accessibility/android/AccountBrowseManualHandoffCoordinator.kt"
            ).exists()
        )
    }

    @Test
    fun browseReadOnlyVerificationUsesEvidenceOnlyAndRetainsActionGate() {
        val workflow = source("app/src/main/java/com/freedom/adapters/accessibility/android/AndroidAccountBrowseWorkflow.kt")
        val observation = workflow.substringAfter("private fun observeAccountBrowseRecoveryReadOnly() {")
            .substringBefore("private fun onCoalescedPageObservationRequested()")
        assertTrue(observation.contains("socialBrowsePort.inspectFeed()"))
        assertTrue(observation.contains("accountBrowseRuntime.observeDynamicFeedFingerprintReadOnly("))
        assertTrue(observation.contains("socialBrowsePort.inspectPopup() == null"))
        assertTrue(observation.contains("RecoveryTransactionState.ACTIVE"))
        assertFalse(observation.contains("if (handleMomoPopup()"))
        assertFalse(observation.contains("gesturePolicy.execute("))
        assertFalse(observation.contains("performFeedScroll()"))
        val runtime = source("business/src/main/kotlin/com/freedom/features/accountbrowse/AccountBrowseRuntime.kt")
        val dispatch = runtime.substringAfter("fun performStep() {").substringBefore("fun resetSession(")
        assertTrue(dispatch.contains("interactionPolicy.mayPerformAutomaticStep("))
        val recovery = source("business/src/main/kotlin/com/freedom/features/accountbrowse/DynamicPageRecovery.kt")
        val sampling = recovery.substringAfter("fun observeFingerprint(").substringBefore("fun state()")
        assertTrue(sampling.contains("recoveryScheduler.begin(eventIdentity)"))
    }

    @Test
    fun pictureContentSharesDetailLifecycleAndExistingWindowSafetyChecks() {
        val workflow = source("app/src/main/java/com/freedom/adapters/accessibility/android/AndroidAccountBrowseWorkflow.kt")
        val load = workflow.substringAfter("private fun checkDetailPageLoaded() {")
            .substringBefore("private fun returnFromDetailPage()")
        assertTrue(load.contains("page.pageKind.isDynamicContent"))
        assertTrue(load.contains("REQUIRED_STABLE_PAGE_SAMPLES"))
        assertTrue(load.contains("scrollExecutor.randomBrowseDelayMs()"))
        assertTrue(load.contains("AccountBrowsePhase.DETAIL_DWELL"))
        val back = workflow.substringAfter("private fun returnFromDetailPage() {")
            .substringBefore("private fun beginDynamicNavigationRecovery(")
        assertTrue(back.contains("socialBrowsePort.inspectDetail(accountBrowseSession.detailFingerprint)"))
        assertTrue(back.contains("if (identity.isFeedPage)"))
        assertTrue(back.contains("!preBackPage.pageKind.isDynamicContent"))
        assertTrue(back.contains("preBackPage.fingerprint != identity.fingerprint"))
        assertTrue(back.contains("accessibilityGateway.performGlobalAction(GlobalAccessibilityAction.BACK)"))
        val adapter = source("app/src/main/java/com/freedom/adapters/social/momo/MomoSocialAdapter.kt")
        val image = adapter.substringAfter("private fun confirmedImageBrowserIdentity(")
            .substringBefore("override fun inspectPage()")
        assertTrue(image.contains("inspectGesturePageReadiness(root, window.activityClass, RunMode.ACCOUNT_BROWSE).ready"))
        assertTrue(image.contains("observedDialogWindowIdFor(window)"))
        assertTrue(image.contains("MomoWindowPageFingerprint.windowIdentity("))
        assertFalse(image.contains("viewIdResourceName"))
        assertTrue(adapter.contains("fingerprint = imageIdentity"))
        assertTrue(back.contains("preBackPage.fingerprint.isBlank()"))
    }

    @Test
    fun overlayWatchdogTimeoutEntersUnifiedRecoveryInsteadOfManualHold() {
        val workflow = source(
            "app/src/main/java/com/freedom/adapters/accessibility/android/AndroidAccountBrowseWorkflow.kt"
        )
        val watchdog = workflow.substringAfter(
            "private fun scheduleOverlayPassthroughWatchdog(delayMs: Long) {"
        ).substringBefore("private fun cancelOverlayPassthroughWatchdog()")
        assertTrue("Overlay watchdog must restore monitor touch state", watchdog.contains(
            "restoreMonitorOverlayTouchPassthrough()"
        ))
        assertTrue("Watchdog must route timeout through browse recovery", watchdog.contains(
            "pauseAccountBrowse("
        ))

        val pause = workflow.substringAfter(
            "internal fun pauseAccountBrowse(reason: String) {"
        ).substringBefore("internal fun performAccountBrowseStep()")
        assertTrue("Browse pause must request the shared recovery transaction", pause.contains(
            "accountBrowseRuntime.requestRecovery("
        ))
        assertFalse(workflow.contains("MANUAL_RECOVERY_NAVIGATION_TIMEOUT_MS"))
        assertFalse(workflow.contains("pauseAfterWatchdog"))
        assertFalse(workflow.contains("onResumeManualTouch"))
    }

    @Test
    fun queuedBrowseGesturesRecheckRecoveryGateAndRunGenerationBeforeSubmission() {
        val workflow = source(
            "app/src/main/java/com/freedom/adapters/accessibility/android/AndroidAccountBrowseWorkflow.kt"
        )
        val callbacks = workflow.split(
            "gestureDispatchSyncTask = taskScheduler.schedule(SWIPE_WINDOW_SYNC_DELAY_MS) {"
        ).drop(1)
        assertEquals("Both ordinary and fallback swipe must use the shared dispatch gate", 2, callbacks.size)
        assertEquals(
            "Each queued gesture must bind the scheduling run generation",
            2,
            Regex("val scheduledRunGeneration = currentRunGeneration\\(\\)").findAll(workflow).count()
        )
        callbacks.forEach { callback ->
            val beforeDispatch = callback.substringBefore("gesturePolicy.execute(")
            assertTrue(beforeDispatch.contains("scheduledRunGeneration != currentRunGeneration()"))
            assertTrue(beforeDispatch.contains("!accountBrowseRuntime.canExecuteAutomaticStep()"))
        }
    }

    @Test
    fun everyBrowseGestureCompletionChecksGenerationAndRequestBeforeMutatingState() {
        val workflow = source(
            "app/src/main/java/com/freedom/adapters/accessibility/android/AndroidAccountBrowseWorkflow.kt"
        )
        val callbacks = Regex("gesturePolicy\\.execute\\((tap\\.request|gestureRequest)\\) \\{ result ->")
            .findAll(workflow).toList()
        assertEquals(6, callbacks.size)
        callbacks.forEach { match ->
            val callbackStart = workflow.substring(match.range.last + 1).trimStart()
            assertTrue(callbackStart.startsWith("if (!isCurrentBrowseCallback(callbackGeneration, callbackEpoch)"))
            assertTrue(callbackStart.substringBefore("activeBrowseGestureRequestId = null").contains("return@execute"))
        }
        val exit = workflow.substringAfter("internal fun suspendForPageExit() {")
            .substringBefore("private fun addLog")
        assertTrue(exit.contains("invalidateBrowseCallbacks()"))
        assertTrue(exit.contains("accountBrowseTask.suspendForPageExit()"))
        assertFalse(exit.contains("resetAccountBrowseSession()"))
    }

    @Test
    fun cleanupCancelsWatchdogAndPreservesRealUserInteractionSafety() {
        val workflow = source(
            "app/src/main/java/com/freedom/adapters/accessibility/android/AndroidAccountBrowseWorkflow.kt"
        )
        val cleanup = workflow.substringAfter("internal fun cleanup() {")
            .substringBefore("private fun restoreMonitorOverlayTouchPassthrough(")
        assertTrue("Cleanup must cancel any pending overlay watchdog", cleanup.contains(
            "cancelOverlayPassthroughWatchdog()"
        ))
        assertTrue("Touch fallback remains a safety boundary, not manual handoff", workflow.contains(
            "internal fun handleManualInteractionFallback(event: AccessibilityEventEnvelope)"
        ))
    }
}
