package com.freedom.application.gesture

import com.freedom.core.accessibility.api.DisplayMetricsSnapshot
import com.freedom.core.accessibility.api.WindowRef
import com.freedom.core.accessibility.api.GestureDispatchPurpose
import com.freedom.core.accessibility.api.GestureFeedbackIntent
import com.freedom.core.accessibility.api.GesturePathCommand
import com.freedom.core.accessibility.api.GestureSubmission
import com.freedom.core.accessibility.api.ScreenRect
import com.freedom.core.accessibility.gesture.GestureCommand
import com.freedom.core.accessibility.gesture.GestureCoordinateRegion
import com.freedom.core.accessibility.gesture.GestureExecutionResult
import com.freedom.core.accessibility.gesture.GestureExecutionContext
import com.freedom.core.accessibility.gesture.GestureCoordinateContext
import com.freedom.core.accessibility.gesture.toGestureCoordinateContext
import com.freedom.core.accessibility.gesture.GestureIntent
import com.freedom.core.accessibility.gesture.GestureIntentRequest
import com.freedom.core.accessibility.gesture.GestureKind
import com.freedom.core.runtime.TaskCoordinator
import com.freedom.core.runtime.UiOperationOwnershipCoordinator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class GesturePolicyPipelineTest {
    private val displayMetrics = { DisplayMetricsSnapshot(1080, 1920, 3f) }
    private val testWindow = WindowRef("com.immomo.momo", "MainActivity", 4L, 7)
    private val testContext = GestureExecutionContext(
        runGeneration = 4L,
        targetWindow = testWindow,
        coordinateContext = displayMetrics().toGestureCoordinateContext()
    )

    @Test
    fun tapIntentIsCompiledToOneBoundedTapPathBeforeExecution() {
        val executor = RecordingExecutor()
        val pipeline = pipeline(executor)
        val request = GestureIntentRequest(
            id = "tap-1",
            intent = GestureIntent.Tap(
                preferredBounds = ScreenRect(120, 220, 520, 620),
                targetBounds = ScreenRect(100, 200, 540, 640),
                durationMs = 90L
            ),
            description = "test tap",
            executionContext = testContext
        )

        assertEquals(GestureSubmission.ACCEPTED, pipeline.execute(request) {})
        val command = executor.lastCommand
        assertNotNull(command)
        assertNotNull("only the validated pipeline should issue an approval capability", executor.lastApprovedCommand)
        assertEquals(GestureKind.TAP, command?.kind)
        assertEquals(testContext, command?.executionContext)
        val points = command!!.gesture.commands.flatMap { path ->
            when (path) {
                is GesturePathCommand.MoveTo -> listOf(path.point)
                is GesturePathCommand.LineTo -> listOf(path.point)
                is GesturePathCommand.CubicTo -> listOf(path.control1, path.control2, path.end)
            }
        }
        assertEquals(2, points.size)
        assertTrue(points.all { it.x in 180f..459f && it.y in 280f..559f })
        assertTrue(points.all { command.validationRegion.contains(it) })
    }


    @Test
    fun tapAndDoubleTapPreserveTheirFeedbackIntentAcrossGeometryCompilation() {
        val tapExecutor = RecordingExecutor()
        val tapRequest = GestureIntentRequest(
            id = "navigation-tap",
            intent = GestureIntent.Tap(
                preferredBounds = ScreenRect(120, 220, 520, 620),
                targetBounds = ScreenRect(100, 200, 540, 640),
                durationMs = 90L,
                feedbackIntent = GestureFeedbackIntent.NAVIGATION_TAB
            ),
            description = "navigation tap",
            executionContext = testContext
        )
        assertEquals(GestureSubmission.ACCEPTED, pipeline(tapExecutor).execute(tapRequest) {})
        assertEquals(GestureFeedbackIntent.NAVIGATION_TAB, tapExecutor.lastCommand?.gesture?.feedbackIntent)

        val doubleTapExecutor = RecordingExecutor()
        val doubleTapRequest = GestureIntentRequest(
            id = "navigation-double-tap",
            intent = GestureIntent.DoubleTap(
                preferredBounds = ScreenRect(120, 220, 520, 620),
                targetBounds = ScreenRect(100, 200, 540, 640),
                durationMs = 90L,
                intervalMs = 180L,
                feedbackIntent = GestureFeedbackIntent.NAVIGATION_ENTRY
            ),
            description = "navigation double tap",
            executionContext = testContext
        )
        assertEquals(GestureSubmission.ACCEPTED, pipeline(doubleTapExecutor).execute(doubleTapRequest) {})
        assertEquals(GestureFeedbackIntent.NAVIGATION_ENTRY, doubleTapExecutor.lastCommand?.gesture?.feedbackIntent)
    }

    @Test
    fun verticalSwipeIntentIsCompiledToBezierPath() {
        val executor = RecordingExecutor()
        val pipeline = pipeline(executor)
        val request = GestureIntentRequest(
            id = "swipe-1",
            intent = GestureIntent.VerticalSwipe(
                viewport = ScreenRect(80, 100, 1000, 1800),
                direction = com.freedom.core.accessibility.gesture.VerticalSwipeDirection.UP,
                durationMs = 520L
            ),
            description = "test vertical swipe",
            executionContext = testContext
        )

        assertEquals(GestureSubmission.ACCEPTED, pipeline.execute(request) {})
        val command = executor.lastCommand
        assertEquals(GestureKind.SWIPE, command?.kind)
        assertTrue(command!!.gesture.commands.any { it is GesturePathCommand.CubicTo })
        assertEquals(4, command.gesture.additionalStrokes.size)
        assertTrue(command.gesture.willContinue)
        assertTrue(command.gesture.additionalStrokes.dropLast(1).all { it.continuationOfPrevious && it.willContinue })
        assertTrue(command.gesture.additionalStrokes.last().continuationOfPrevious)
        assertFalse(command.gesture.additionalStrokes.last().willContinue)
        val allCommands = listOf(command.gesture.commands) + command.gesture.additionalStrokes.map { it.commands }
        val points = allCommands.flatten().flatMap { path ->
            when (path) {
                is GesturePathCommand.MoveTo -> listOf(path.point)
                is GesturePathCommand.LineTo -> listOf(path.point)
                is GesturePathCommand.CubicTo -> listOf(path.control1, path.control2, path.end)
            }
        }
        assertTrue(points.all(command.validationRegion::contains))
        assertFalse(allCommands.flatten().any { it is GesturePathCommand.LineTo })
    }

    @Test
    fun feedSwipeIntentIsCompiledInsidePipeline() {
        val executor = RecordingExecutor()
        val pipeline = pipeline(executor)
        val request = GestureIntentRequest(
            id = "feed-swipe-1",
            intent = GestureIntent.FeedSwipe(
                viewport = ScreenRect(200, 120, 560, 1850),
                durationMs = 700L
            ),
            description = "test feed swipe",
            executionContext = testContext
        )

        assertEquals(GestureSubmission.ACCEPTED, pipeline.execute(request) {})
        val command = executor.lastCommand
        assertEquals(GestureKind.SWIPE, command?.kind)
        assertTrue(command!!.gesture.commands.any { it is GesturePathCommand.CubicTo })
        assertEquals(4, command.gesture.additionalStrokes.size)
        assertTrue("Feed geometry must use the execution-context density", command.validationRegion.left >= 224f)
        val allCommands = listOf(command.gesture.commands) + command.gesture.additionalStrokes.map { it.commands }
        val points = allCommands.flatten().flatMap { path ->
            when (path) {
                is GesturePathCommand.MoveTo -> listOf(path.point)
                is GesturePathCommand.LineTo -> listOf(path.point)
                is GesturePathCommand.CubicTo -> listOf(path.control1, path.control2, path.end)
            }
        }
        assertTrue(points.all(command.validationRegion::contains))
    }

    @Test
    fun directionalSwipeUsesTheSameRuntimeCompilerAndEnforcesAxisSemantics() {
        val executor = RecordingExecutor()
        val diagnostics = mutableListOf<String>()
        val pipeline = pipeline(executor, diagnostics::add)
        val request = GestureIntentRequest(
            id = "directional-horizontal",
            intent = GestureIntent.DirectionalSwipe(
                viewport = ScreenRect(100, 100, 980, 1_800),
                angleDegrees = 0f,
                durationMs = 620L,
                axis = com.freedom.core.accessibility.gesture.GestureIntentAxis.HORIZONTAL
            ),
            description = "horizontal motion",
            executionContext = testContext
        )
        val submission = pipeline.execute(request) {}
        assertEquals(diagnostics.joinToString("\n"), GestureSubmission.ACCEPTED, submission)
        assertEquals(GestureKind.SWIPE, executor.lastCommand?.kind)

        val invalid = request.copy(
            id = "directional-axis-mismatch",
            intent = (request.intent as GestureIntent.DirectionalSwipe).copy(angleDegrees = 45f)
        )
        assertEquals(GestureSubmission.REJECTED, pipeline.execute(invalid) {})
    }

    @Test
    fun pauseProfileCompilesToAStationaryContinuousStrokeAndPassesValidation() {
        val executor = RecordingExecutor()
        val diagnostics = mutableListOf<String>()
        val settings = object : GesturePolicySettingsProvider {
            override fun loadFeedSwipeSettings() = FeedSwipePolicySettings(0.012f, 4f)
            override fun loadDynamicsSettings() = com.freedom.core.accessibility.gesture.GestureDynamicsSettings(
                velocityCurveCandidates = listOf(
                    com.freedom.core.accessibility.gesture.GestureVelocityCurve.CUBIC_EASE_OUT
                ),
                pause = com.freedom.core.accessibility.gesture.GesturePauseProfile(
                    enabled = true, probability = 1f, progress = 0.4f, durationMs = 80L
                ),
                preDispatchDelay = com.freedom.core.accessibility.gesture.GesturePreDispatchDelayProfile(
                    enabled = false
                )
            )
        }
        val pipeline = pipeline(executor, diagnostics::add, settings)
        val request = GestureIntentRequest(
            id = "pause-profile-swipe",
            intent = GestureIntent.VerticalSwipe(
                viewport = ScreenRect(80, 100, 1000, 1800),
                direction = com.freedom.core.accessibility.gesture.VerticalSwipeDirection.UP,
                durationMs = 620L
            ),
            description = "swipe with an in-contact hold",
            executionContext = testContext
        )

        assertEquals(GestureSubmission.ACCEPTED, pipeline.execute(request) {})
        assertTrue(diagnostics.any {
            it.contains("手势策略已选择") && it.contains("velocityCurve=CUBIC_EASE_OUT")
        })
        val command = executor.lastCommand!!
        val strokes = listOf(
            com.freedom.core.accessibility.api.GestureStrokeSpec(
                command.gesture.commands,
                command.gesture.durationMs,
                command.gesture.startDelayMs,
                false,
                command.gesture.willContinue
            )
        ) + command.gesture.additionalStrokes
        val pause = strokes.single { stroke ->
            val move = stroke.commands.firstOrNull() as? GesturePathCommand.MoveTo
            val cubic = stroke.commands.getOrNull(1) as? GesturePathCommand.CubicTo
            move != null && cubic != null && cubic.end == move.point &&
                cubic.control1 == move.point && cubic.control2 == move.point
        }
        assertEquals(80L, pause.durationMs)
        assertTrue(pause.continuationOfPrevious)
        assertTrue(pause.willContinue)
    }

    @Test
    fun calibrationSwipeUsesSamePipelineAndRetainsCalibrationPurpose() {
        val executor = RecordingExecutor()
        val pipeline = pipeline(executor)
        val request = GestureIntentRequest(
            id = "calibration-1",
            intent = GestureIntent.CalibrationSwipe(
                region = ScreenRect(100, 100, 800, 1700),
                durationMs = 800L
            ),
            description = "calibration gesture",
            executionContext = testContext
        )

        assertEquals(GestureSubmission.ACCEPTED, pipeline.execute(request) {})
        assertEquals(GestureDispatchPurpose.INTERNAL_CALIBRATION, executor.lastCommand?.gesture?.dispatchPurpose)
    }

    @Test
    fun invalidIntentIsRejectedWithoutCallingExecutor() {
        val executor = RecordingExecutor()
        val pipeline = pipeline(executor)
        val request = GestureIntentRequest(
            id = "invalid-tap",
            intent = GestureIntent.Tap(
                preferredBounds = ScreenRect(20, 20, 20, 40),
                targetBounds = ScreenRect(0, 0, 100, 100),
                durationMs = 90L
            ),
            description = "invalid tap",
            executionContext = testContext
        )

        assertEquals(GestureSubmission.REJECTED, pipeline.execute(request) {})
        assertNull(executor.lastCommand)
        assertEquals(0, executor.executeCount)
    }

    @Test
    fun tapInsideConservativeSystemEdgeGuardIsRejectedBeforeExecution() {
        val executor = RecordingExecutor()
        val pipeline = pipeline(executor)
        val request = GestureIntentRequest(
            id = "edge-tap",
            intent = GestureIntent.Tap(
                preferredBounds = ScreenRect(0, 300, 60, 360),
                targetBounds = ScreenRect(0, 300, 60, 360),
                durationMs = 90L
            ),
            description = "tap inside reserved system edge band",
            executionContext = testContext
        )

        assertEquals(GestureSubmission.REJECTED, pipeline.execute(request) {})
        assertNull(executor.lastCommand)
    }

    @Test
    fun validatorRejectsBezierControlPointOutsideCompiledSafeRegion() {
        val validator = GesturePolicyValidator(displayMetrics)
        val command = GestureCommand(
            id = "unsafe-control-point",
            kind = GestureKind.SWIPE,
            gesture = com.freedom.core.accessibility.api.GestureSpec(
                commands = listOf(
                    GesturePathCommand.MoveTo(com.freedom.core.accessibility.api.GesturePoint(200f, 1_000f)),
                    GesturePathCommand.CubicTo(
                        com.freedom.core.accessibility.api.GesturePoint(210f, 900f),
                        com.freedom.core.accessibility.api.GesturePoint(900f, 800f),
                        com.freedom.core.accessibility.api.GesturePoint(220f, 700f)
                    )
                ),
                durationMs = 500L
            ),
            validationRegion = GestureCoordinateRegion(100f, 100f, 300f, 1_800f),
            executionContext = testContext,
            description = "control point outside safe region"
        )

        assertEquals(
            "coordinate_outside_validated_safe_region_0",
            validator.validate(command)
        )
    }

    @Test
    fun validatorRequiresTheCompleteFivePhaseMovementProfile() {
        val executor = RecordingExecutor()
        val pipeline = pipeline(executor)
        val request = GestureIntentRequest(
            id = "profile-checked-swipe",
            intent = GestureIntent.VerticalSwipe(
                viewport = ScreenRect(80, 100, 1000, 1800),
                direction = com.freedom.core.accessibility.gesture.VerticalSwipeDirection.UP,
                durationMs = 520L
            ),
            description = "complete speed profile",
            executionContext = testContext
        )

        assertEquals(GestureSubmission.ACCEPTED, pipeline.execute(request) {})
        val compiled = executor.lastCommand ?: error("pipeline did not create a command")
        val malformed = GestureCommand(
            id = compiled.id,
            kind = compiled.kind,
            gesture = compiled.gesture.copy(additionalStrokes = emptyList(), willContinue = false),
            validationRegion = compiled.validationRegion,
            executionContext = compiled.executionContext,
            description = compiled.description
        )
        val validator = GesturePolicyValidator(displayMetrics)

        assertEquals("movement_gesture_requires_three_to_eight_motion_segments", validator.validate(malformed))
    }

    @Test
    fun pipelineForwardsCancellationToTheSerialExecutor() {
        val executor = RecordingExecutor()
        val pipeline = pipeline(executor)

        assertTrue(pipeline.cancelActive("gesture-7", "test_cancel"))
        assertEquals("gesture-7", executor.cancelledCommandId)
        assertEquals("test_cancel", executor.cancelReason)
    }

    @Test
    fun captureContextBindsTheExpectedWindowAndCurrentCoordinateSnapshot() {
        val executor = RecordingExecutor()
        val pipeline = pipeline(executor)

        val captured = pipeline.captureContext(testWindow)

        assertEquals(testContext, captured)
        assertNull(pipeline.captureContext(testWindow.copy(windowId = 99)))
        assertNull(pipeline.captureContext(testWindow.copy(generation = 5L)))
        assertNull(
            pipeline.captureContext(
                testWindow,
                testContext.coordinateContext.copy(rotationDegrees = 90)
            )
        )
    }

    @Test
    fun compilerRechecksTheDisplaySnapshotAndDoesNotSubmitAnOutdatedCommand() {
        val executor = RecordingExecutor()
        var liveMetrics = displayMetrics()
        val diagnostics = mutableListOf<String>()
        val pipeline = GesturePolicyPipeline(
            executor = executor,
            displayMetrics = { liveMetrics },
            onDiagnostic = diagnostics::add,
            random = Random(1234),
            currentRunGeneration = { testContext.runGeneration },
            currentGestureWindow = { testWindow },
            isGestureWindowCurrent = { it == testWindow },
            currentCoordinateContext = { testContext.coordinateContext }
        )
        val request = GestureIntentRequest(
            id = "compiler-snapshot-recheck",
            intent = GestureIntent.Tap(
                preferredBounds = ScreenRect(120, 220, 520, 620),
                targetBounds = ScreenRect(100, 200, 540, 640),
                durationMs = 90L
            ),
            description = "compiler must not use a stale display snapshot",
            executionContext = testContext
        )
        liveMetrics = liveMetrics.copy(widthPixels = 1920, heightPixels = 1080, rotationDegrees = 90)

        assertEquals(GestureSubmission.REJECTED, pipeline.execute(request) {})
        assertEquals(0, executor.executeCount)
        assertTrue(diagnostics.any {
            it.contains("stage=command_compilation") &&
                it.contains("coordinate_context_changed_before_compile")
        })
    }

    @Test
    fun requestIsRejectedWhenDisplayRotationChangesAfterCoordinateCapture() {
        val executor = RecordingExecutor()
        var metrics = DisplayMetricsSnapshot(1080, 1920, 3f)
        val pipeline = GesturePolicyPipeline(
            executor = executor,
            displayMetrics = { metrics },
            random = Random(1234),
            currentRunGeneration = { testContext.runGeneration },
            currentGestureWindow = { testWindow },
            isGestureWindowCurrent = { it == testWindow },
            currentCoordinateContext = { metrics.toGestureCoordinateContext() }
        )
        val request = GestureIntentRequest(
            id = "rotated-display-tap",
            intent = GestureIntent.Tap(
                preferredBounds = ScreenRect(120, 220, 520, 620),
                targetBounds = ScreenRect(100, 200, 540, 640),
                durationMs = 90L
            ),
            description = "must not reuse coordinates after rotation",
            executionContext = testContext
        )
        metrics = metrics.copy(rotationDegrees = 90, widthPixels = 1920, heightPixels = 1080)

        assertEquals(GestureSubmission.REJECTED, pipeline.execute(request) {})
        assertEquals(0, executor.executeCount)
    }

    @Test
    fun requestIsRejectedWhenRunGenerationChangesAfterCoordinateCapture() {
        val executor = RecordingExecutor()
        var runGeneration = testContext.runGeneration
        val pipeline = GesturePolicyPipeline(
            executor = executor,
            displayMetrics = displayMetrics,
            random = Random(1234),
            currentRunGeneration = { runGeneration },
            currentGestureWindow = { testWindow },
            isGestureWindowCurrent = { it == testWindow },
            currentCoordinateContext = { displayMetrics().toGestureCoordinateContext() }
        )
        val request = GestureIntentRequest(
            id = "old-run-tap",
            intent = GestureIntent.Tap(
                preferredBounds = ScreenRect(120, 220, 520, 620),
                targetBounds = ScreenRect(100, 200, 540, 640),
                durationMs = 90L
            ),
            description = "must not dispatch from a previous run",
            executionContext = testContext
        )
        runGeneration++

        assertEquals(GestureSubmission.REJECTED, pipeline.execute(request) {})
        assertEquals(0, executor.executeCount)
    }



    @Test
    fun capturedGestureContextRetainsTheCurrentTaskLeaseAndRejectsAfterTransfer() {
        val taskCoordinator = TaskCoordinator(UiOperationOwnershipCoordinator())
        val privateTaskLease = requireNotNull(taskCoordinator.tryAcquire("AI_REPLY_PRIVATE_MESSAGES", 4L))
        val executor = RecordingExecutor()
        val pipeline = GesturePolicyPipeline(
            executor = executor,
            displayMetrics = displayMetrics,
            random = Random(1234),
            currentRunGeneration = { 4L },
            currentGestureWindow = { testWindow },
            isGestureWindowCurrent = { it == testWindow },
            currentCoordinateContext = { displayMetrics().toGestureCoordinateContext() },
            taskCoordinator = taskCoordinator
        )

        val capturedContext = requireNotNull(pipeline.captureContext(testWindow))
        assertEquals(privateTaskLease, capturedContext.taskLease)
        val request = GestureIntentRequest(
            id = "captured-task-gesture",
            intent = GestureIntent.Tap(
                preferredBounds = ScreenRect(120, 220, 520, 620),
                targetBounds = ScreenRect(100, 200, 540, 640),
                durationMs = 90L
            ),
            description = "must not execute after task ownership transfer",
            executionContext = capturedContext
        )

        requireNotNull(taskCoordinator.transfer(privateTaskLease, "AI_REPLY_RECEIVED_GREETINGS"))

        assertEquals(GestureSubmission.REJECTED, pipeline.execute(request) {})
        assertEquals(0, executor.executeCount)
    }


    @Test
    fun tasklessGestureContextCannotBypassOwnershipAcquiredAfterCapture() {
        val taskCoordinator = TaskCoordinator(UiOperationOwnershipCoordinator())
        val executor = RecordingExecutor()
        val pipeline = GesturePolicyPipeline(
            executor = executor,
            displayMetrics = displayMetrics,
            random = Random(1234),
            currentRunGeneration = { 4L },
            currentGestureWindow = { testWindow },
            isGestureWindowCurrent = { it == testWindow },
            currentCoordinateContext = { displayMetrics().toGestureCoordinateContext() },
            taskCoordinator = taskCoordinator
        )
        val contextCapturedWithoutTask = requireNotNull(pipeline.captureContext(testWindow))
        assertNull(contextCapturedWithoutTask.taskLease)
        requireNotNull(taskCoordinator.tryAcquire("ACCOUNT_BROWSE", 4L))

        val request = GestureIntentRequest(
            id = "taskless-context-after-task-acquire",
            intent = GestureIntent.Tap(
                preferredBounds = ScreenRect(120, 220, 520, 620),
                targetBounds = ScreenRect(100, 200, 540, 640),
                durationMs = 90L
            ),
            description = "cannot bypass a newly active task lease",
            executionContext = contextCapturedWithoutTask
        )

        assertEquals(GestureSubmission.REJECTED, pipeline.execute(request) {})
        assertEquals(0, executor.executeCount)
    }

    @Test
    fun compiledGestureCommandDefensivelyFreezesPathLists() {
        val mutableCommands = mutableListOf<GesturePathCommand>(
            GesturePathCommand.MoveTo(com.freedom.core.accessibility.api.GesturePoint(200f, 300f)),
            GesturePathCommand.CubicTo(
                com.freedom.core.accessibility.api.GesturePoint(220f, 280f),
                com.freedom.core.accessibility.api.GesturePoint(260f, 280f),
                com.freedom.core.accessibility.api.GesturePoint(280f, 300f)
            )
        )
        val command = GestureCommand(
            id = "immutable-command",
            kind = GestureKind.TAP,
            gesture = com.freedom.core.accessibility.api.GestureSpec(mutableCommands, 90L),
            validationRegion = GestureCoordinateRegion(100f, 100f, 400f, 400f),
            executionContext = testContext,
            description = "immutable gesture snapshot"
        )

        val compiledCount = command.gesture.commands.size
        mutableCommands += GesturePathCommand.MoveTo(com.freedom.core.accessibility.api.GesturePoint(300f, 300f))
        assertEquals(compiledCount, command.gesture.commands.size)
        val exposed = command.gesture.commands as MutableList<GesturePathCommand>
        assertTrue(runCatching {
            exposed += GesturePathCommand.MoveTo(com.freedom.core.accessibility.api.GesturePoint(310f, 310f))
        }.isFailure)
    }

    @Test
    fun rawRequestsMustPassSemanticValidationBeforeCompilation() {
        val validator = GesturePolicyValidator(displayMetrics)
        val invalidRequests = listOf(
            GestureIntentRequest(" ", GestureIntent.Tap(ScreenRect(100, 100, 200, 200), ScreenRect(90, 90, 210, 210), 90L), "valid", testContext),
            GestureIntentRequest("blank-description", GestureIntent.Tap(ScreenRect(100, 100, 200, 200), ScreenRect(90, 90, 210, 210), 90L), " ", testContext),
            GestureIntentRequest("empty-target", GestureIntent.Tap(ScreenRect(100, 100, 100, 200), ScreenRect(90, 90, 210, 210), 90L), "invalid bounds", testContext),
            GestureIntentRequest("invalid-duration", GestureIntent.VerticalSwipe(ScreenRect(80, 100, 1000, 1800), com.freedom.core.accessibility.gesture.VerticalSwipeDirection.UP, 0L), "invalid duration", testContext),
            GestureIntentRequest("invalid-feed-duration", GestureIntent.FeedSwipe(ScreenRect(80, 100, 1000, 1800), 0L), "invalid feed duration", testContext),
            GestureIntentRequest("empty-feed-viewport", GestureIntent.FeedSwipe(ScreenRect(80, 100, 80, 1800), 520L), "empty feed viewport", testContext)
        )

        invalidRequests.forEach { request ->
            assertTrue("${request.id} should be rejected", validator.validateRequest(request) is GestureRequestValidationResult.Rejected)
        }
    }

    @Test
    fun semanticallyValidRequestReceivesRuntimeOnlyValidatedType() {
        val validator = GesturePolicyValidator(displayMetrics)
        val request = GestureIntentRequest(
            id = "typed-request",
            intent = GestureIntent.Tap(
                preferredBounds = ScreenRect(120, 220, 520, 620),
                targetBounds = ScreenRect(100, 200, 540, 640),
                durationMs = 90L
            ),
            description = "validated request",
            executionContext = testContext
        )

        val result = validator.validateRequest(request)
        assertTrue(result is GestureRequestValidationResult.Accepted)
        assertEquals(request, (result as GestureRequestValidationResult.Accepted).request.request)
    }

    @Test
    fun approvalResultIsIssuedOnlyAfterFullGeometryValidation() {
        val validator = GesturePolicyValidator(displayMetrics)
        val malformed = GestureCommand(
            id = "not-approved",
            kind = GestureKind.SWIPE,
            gesture = com.freedom.core.accessibility.api.GestureSpec(
                commands = listOf(
                    GesturePathCommand.MoveTo(com.freedom.core.accessibility.api.GesturePoint(200f, 1000f)),
                    GesturePathCommand.LineTo(com.freedom.core.accessibility.api.GesturePoint(200f, 500f))
                ),
                durationMs = 500L
            ),
            validationRegion = GestureCoordinateRegion(100f, 100f, 300f, 1800f),
            executionContext = testContext,
            description = "linear movement is not approved"
        )

        val result = validator.validateAndApprove(malformed)
        assertTrue(result is GestureCommandValidationResult.Rejected)
    }

    private fun pipeline(
        executor: RecordingExecutor,
        onDiagnostic: (String) -> Unit = {},
        policySettingsProvider: GesturePolicySettingsProvider = DefaultGesturePolicySettingsProvider
    ) = GesturePolicyPipeline(
        executor = executor,
        displayMetrics = displayMetrics,
        onDiagnostic = onDiagnostic,
        policySettingsProvider = policySettingsProvider,
        random = Random(1234),
        currentRunGeneration = { testContext.runGeneration },
        currentGestureWindow = { testWindow },
        isGestureWindowCurrent = { it == testWindow },
        currentCoordinateContext = { displayMetrics().toGestureCoordinateContext() }
    )

    private class RecordingExecutor : InterruptibleGestureExecutor {
        var lastCommand: GestureCommand? = null
        var lastApprovedCommand: ApprovedGestureCommand? = null
        var executeCount: Int = 0
        var cancelledCommandId: String? = null
        var cancelReason: String? = null

        override fun execute(
            approvedCommand: ApprovedGestureCommand,
            listener: com.freedom.core.accessibility.gesture.GestureExecutionListener
        ): GestureSubmission {
            executeCount++
            lastApprovedCommand = approvedCommand
            lastCommand = approvedCommand.command
            return GestureSubmission.ACCEPTED
        }

        override fun cancelActive(commandId: String?, reason: String): Boolean {
            cancelledCommandId = commandId
            cancelReason = reason
            return true
        }
    }
}
