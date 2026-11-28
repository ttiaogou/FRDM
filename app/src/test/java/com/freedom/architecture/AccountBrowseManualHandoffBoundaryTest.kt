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
    fun imageIndexRemainsOptionalReadOnlyBusinessEvidence() {
        val adapter = source("app/src/main/java/com/freedom/adapters/social/momo/MomoSocialAdapter.kt")
        val read = adapter.substringAfter("override fun readImageIndex(")
            .substringBefore("override fun inspectDetail(")
        assertTrue(read.contains("confirmedImageBrowserIdentity(root, window)"))
        assertTrue(read.contains("fingerprint != expectedFingerprint"))
        assertTrue(read.contains("root.findAccessibilityNodeInfosByViewId(viewId)"))
        assertTrue(read.contains("it.isVisibleToUser"))
        assertTrue(read.contains("accessibilityGateway.isWindowCurrent(window)"))
        assertTrue(read.contains("ImageIndexReadResult.fromVisibleLabels("))
        assertTrue(read.contains("node.recycle()"))
        assertFalse(read.contains("gesturePolicy.execute("))
        assertFalse(read.contains("performGlobalAction("))
        val identity = adapter.substringAfter("private fun confirmedImageBrowserIdentity(")
            .substringBefore("override fun inspectPage()")
        assertFalse(identity.contains("include_feed_tv_index"))
        val workflow = source("app/src/main/java/com/freedom/adapters/accessibility/android/AndroidAccountBrowseWorkflow.kt")
        val readStart = "private fun readImagePosition("
        val initStart = "private fun initializeImagePaging("
        val advanceStart = "private fun advanceImagePaging("
        val returnStart = "private fun returnFromDetailPage()"
        assertTrue(workflow.contains(readStart))
        assertTrue(workflow.contains(initStart))
        assertTrue(workflow.contains(advanceStart))
        assertTrue(workflow.contains(returnStart))
        val evidence = workflow.substringAfter(readStart).substringBefore(initStart)
        val initializeDelegate = workflow.substringAfter(initStart).substringBefore(advanceStart)
        val runtime = source("business/src/main/kotlin/com/freedom/features/accountbrowse/AccountBrowseRuntime.kt")
        val initialize = runtime.substringAfter("fun initializeImagePaging(").substringBefore("fun observeFeedReturn(")
        assertTrue(initializeDelegate.contains("accountBrowseRuntime.initializeImagePaging(page, ::readImagePosition, NAVIGATION_ACTION_VERIFY_TIMEOUT_MS, ::addLog)"))
        assertFalse(initializeDelegate.contains("gesturePolicy.execute("))
        assertFalse(initializeDelegate.contains("performGlobalAction("))
        assertFalse(initializeDelegate.contains("scheduleAccountBrowseStep("))
        assertFalse(initializeDelegate.contains("pauseAccountBrowse("))
        assertFalse(initializeDelegate.contains("accountBrowsePhase ="))
        assertTrue(evidence.contains("socialBrowsePort.readImageIndex(page.fingerprint)"))
        assertTrue(evidence.contains("ImageIndexReadResult.Unavailable(ImageIndexUnavailableReason.NODE_READ_FAILED)"))
        assertTrue(initialize.contains("page.pageKind != BrowsePageKind.IMAGE_VIEWER"))
        for (readOnly in listOf(evidence, initialize)) {
            assertFalse(readOnly.contains("gesturePolicy.execute("))
            assertFalse(readOnly.contains("performGlobalAction("))
            assertFalse(readOnly.contains("scheduleAccountBrowseStep("))
            assertFalse(readOnly.contains("pauseAccountBrowse("))
            assertFalse(readOnly.contains("accountBrowsePhase ="))
        }
        val back = workflow.substringAfter(returnStart)
            .substringBefore("private fun beginDynamicNavigationRecovery(")
        assertFalse(back.contains("readImageIndex("))
        assertFalse(back.contains("readImagePosition("))
        assertTrue(back.contains("if (accountBrowseSession.imagePagingProgress?.finished == false)"))
        assertTrue(back.contains("if (advanceImagePaging(pagingPage)) return"))
        val safeBack = back.substringAfter("val preBackPage = socialBrowsePort.inspectPage()")
        assertTrue(safeBack.contains("preBackPage.fingerprint != identity.fingerprint"))
        assertTrue(safeBack.contains("accessibilityGateway.performGlobalAction(GlobalAccessibilityAction.BACK)"))
        assertFalse(safeBack.contains("ImageIndex"))
        assertFalse(safeBack.contains("imagePagingProgress"))
    }

    @Test
    fun imageBrowsePlanIsCreatedFromConfirmedIndexEvidenceAndExecutedSeparately() {
        val workflow = source("app/src/main/java/com/freedom/adapters/accessibility/android/AndroidAccountBrowseWorkflow.kt")
        val initStart = "private fun initializeImagePaging("
        val advanceStart = "private fun advanceImagePaging("
        val returnStart = "private fun returnFromDetailPage()"
        assertTrue(workflow.contains(initStart))
        assertTrue(workflow.contains(advanceStart))
        assertTrue(workflow.contains(returnStart))
        val initializeDelegate = workflow.substringAfter(initStart).substringBefore(advanceStart)
        val runtime = source("business/src/main/kotlin/com/freedom/features/accountbrowse/AccountBrowseRuntime.kt")
        val initialize = runtime.substringAfter("fun initializeImagePaging(").substringBefore("fun observeFeedReturn(")
        assertTrue(initializeDelegate.contains("accountBrowseRuntime.initializeImagePaging(page, ::readImagePosition, NAVIGATION_ACTION_VERIFY_TIMEOUT_MS, ::addLog)"))
        assertFalse(initializeDelegate.contains("gesturePolicy.execute("))
        assertFalse(initializeDelegate.contains("performGlobalAction("))
        assertFalse(initializeDelegate.contains("scheduleAccountBrowseStep("))
        assertFalse(initializeDelegate.contains("pauseAccountBrowse("))
        assertFalse(initializeDelegate.contains("accountBrowsePhase ="))
        assertTrue(initialize.contains("readImagePosition(page) as? ImageIndexReadResult.Confirmed"))
        assertTrue(initialize.contains("evidence?.let { ImageBrowsePlanningPolicy.plan(it.position) }"))
        assertTrue(initialize.contains("finished = evidence?.position?.total == 1"))
        assertFalse(initialize.contains("gesturePolicy.execute("))
        assertFalse(initialize.contains("performGlobalAction("))
        assertFalse(initialize.contains("scheduleAccountBrowseStep("))

        val advance = workflow.substringAfter(advanceStart).substringBefore(returnStart)
        assertTrue(advance.contains("page.pageKind != BrowsePageKind.IMAGE_VIEWER"))
        assertTrue(advance.contains("page.fingerprint != progress.fingerprint"))
        assertTrue(advance.contains("readImagePosition(page) as? ImageIndexReadResult.Confirmed"))
        assertTrue(advance.contains("when (progress.observe(evidence?.position, now))"))
        assertTrue(advance.contains("val current = evidence ?: return true"))
        assertTrue(advance.contains("if (progress.plan == null)"))
        assertTrue(advance.contains("ImageBrowsePlanningPolicy.plan(current.position)"))
        assertTrue(advance.contains("progress.markVerified("))
        assertTrue(advance.contains("fresh.position == current.position"))
        assertTrue(advance.contains("fresh.targetWindow == current.targetWindow"))
        assertTrue(advance.contains("gesturePolicy.captureContext(fresh.targetWindow, metrics.toGestureCoordinateContext())"))
        val beforeDispatch = advance.substringBefore("gesturePolicy.execute(request)")
        assertTrue(beforeDispatch.contains("!accountBrowseRuntime.canExecuteAutomaticStep()"))
        assertTrue(beforeDispatch.contains("accountBrowseSession.touchActive"))
        assertTrue(beforeDispatch.contains("activeBrowseGestureRequestId = request.id"))
        assertTrue(beforeDispatch.contains("progress.copy(awaitingVerification = true)"))
        val settle = advance.substringAfter("fun settle(deferred: Boolean, completed: Boolean)")
            .substringBefore("val submission = runCatching")
        assertTrue(settle.trimStart().startsWith("{"))
        assertTrue(settle.contains("if (!isCurrentBrowseCallback(generation, epoch) || activeBrowseGestureRequestId != request.id) return"))
        assertTrue(settle.contains("pending.copy(awaitingVerification = false)"))
        assertFalse(settle.contains("markVerified("))
        assertEquals(1, Regex("gesturePolicy\\.execute\\(").findAll(advance).count())
        assertFalse(advance.contains("performGlobalAction("))

        val policy = source("business/src/main/kotlin/com/freedom/features/accountbrowse/ImageBrowsePlanningPolicy.kt")
        assertFalse(policy.contains("import android."))
        assertFalse(policy.contains("HorizontalSwipePolicy"))
        assertFalse(policy.contains("GestureIntent"))
    }

    @Test
    fun confirmedFeedReturnReconcilesPhaseWithoutDispatchingBackOrSkippingPositionVerification() {
        val workflow = source("app/src/main/java/com/freedom/adapters/accessibility/android/AndroidAccountBrowseWorkflow.kt")
        val reconcile = workflow.substringAfter("private fun resumeFeedReturnIfConfirmed(")
            .substringBefore("private fun awaitContentPageEvidence(")
        assertTrue(reconcile.contains("canResumeFeedReturn(page, matchDynamicFeedRoute(page))"))
        assertTrue(reconcile.contains("AccountBrowsePhase.WAIT_FOR_LIST_RETURN"))
        assertTrue(reconcile.contains("socialBrowsePort.inspectPopup() != null"))
        assertFalse(reconcile.contains("performGlobalAction("))
        assertFalse(reconcile.contains("sourceSnapshot ="))
        assertFalse(reconcile.contains("AccountBrowsePhase.WAIT_FOR_LIST_SCROLL"))
        val load = workflow.substringAfter("private fun checkDetailPageLoaded() {")
            .substringBefore("private fun returnFromDetailPage()")
        assertTrue(load.indexOf("resumeFeedReturnIfConfirmed(page)") in 0 until load.indexOf("System.currentTimeMillis() >= accountBrowseSession.deadlineAt"))
        val recovery = workflow.substringAfter("private fun observeAccountBrowseRecoveryReadOnly() {")
            .substringBefore("private fun onCoalescedPageObservationRequested()")
        assertTrue(recovery.contains("if (recovered && resumeFeedReturnIfConfirmed(page)) return"))
        val verify = workflow.substringAfter("private fun checkFeedListReturned() {")
        assertTrue(verify.contains("scrollExecutor.isFeedPositionRestored(accountBrowseSession.sourceSnapshot, currentSnapshot)"))
        assertTrue(verify.contains("accountBrowseRuntime.observeFeedReturn("))
        val runtime = source("business/src/main/kotlin/com/freedom/features/accountbrowse/AccountBrowseRuntime.kt")
        val returnOwner = runtime.substringAfter("fun observeFeedReturn(").substringBefore("fun interactionContext(")
        assertTrue(returnOwner.contains("FeedReturnStability.update("))
        assertTrue(returnOwner.contains("session.lastReturnedSnapshot = stability.lastSnapshot"))
        assertTrue(returnOwner.contains("session.returnedStableSamples = stability.stableSamples"))
        assertTrue(returnOwner.contains("return FeedReturnVerificationPolicy.decide("))
        assertTrue(verify.contains("FeedReturnVerificationPolicy.Decision.RESTORED"))
        assertTrue(verify.contains("lateContentArrival"))
        assertTrue(verify.contains("AccountBrowsePhase.WAIT_FOR_DETAIL_LOAD"))
        val imageReturn = workflow.substringAfter("private fun waitForImageBrowserReturn() {")
            .substringBefore("private fun waitForDetailRestabilize()")
        assertTrue(imageReturn.contains("page.pageKind.isDynamicContent"))
        assertTrue(imageReturn.contains("AccountBrowsePhase.WAIT_FOR_DETAIL_RESTABILIZE"))
        assertFalse(imageReturn.contains("performGlobalAction("))
    }

    @Test
    fun unattributedScrollRecoveryDoesNotSynthesizeTouchEndOrRepeatPreparation() {
        val workflow = source("app/src/main/java/com/freedom/adapters/accessibility/android/AndroidAccountBrowseWorkflow.kt")
        val fallback = workflow.substringAfter("internal fun handleManualInteractionFallback(")
            .substringBefore("private fun checkFeedListReturned()")
        assertTrue(fallback.contains("eventType == AccessibilityEventType.VIEW_SCROLLED"))
        assertTrue(fallback.contains("!evidence.hasIndependentTouchEvidence"))
        assertTrue(fallback.contains("!accountBrowseSession.touchActive"))
        assertTrue(fallback.contains("pauseAccountBrowse("))
        assertFalse(fallback.contains("onUserInteractionEnded()"))
        val runtime = source("business/src/main/kotlin/com/freedom/features/accountbrowse/AccountBrowseRuntime.kt")
        val request = runtime.substringAfter("fun requestRecovery(").substringBefore("fun beginNavigationRecovery(")
        assertTrue(request.contains("preparedRecoveryGeneration == generation"))
        assertTrue(request.contains("alreadyPrepared && observationHint && !isTouchActive()"))
        assertTrue(request.contains("if (!alreadyPrepared)"))
        assertTrue(request.contains("dynamicPageRecovery.invalidateRestoredCheckpointEvidence()"))
        assertTrue(request.contains("dynamicPageRecovery.observePage("))
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
