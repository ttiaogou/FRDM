package com.freedom.application.gesture

import com.freedom.core.accessibility.api.DisplayMetricsSnapshot
import com.freedom.core.accessibility.api.ScreenRect
import com.freedom.core.accessibility.api.WindowRef
import com.freedom.core.accessibility.gesture.GestureCoordinateContext
import com.freedom.core.accessibility.gesture.GestureExecutionContext
import com.freedom.core.accessibility.gesture.GestureIntent
import com.freedom.core.accessibility.gesture.GestureIntentRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class GestureCommandCompilerTest {
    private val metrics = DisplayMetricsSnapshot(1080, 1920, 3f)
    private val context = GestureExecutionContext(
        runGeneration = 6L,
        targetWindow = WindowRef("com.example.target", "MainActivity", 6L, 4),
        coordinateContext = GestureCoordinateContext(0, 1080, 1920, 3f, 0)
    )

    @Test
    fun compilesOnlyAValidatedTapRequestIntoAnImmutableCommand() {
        var metricsReads = 0
        val compiler = GestureCommandCompiler({ metricsReads++; metrics }, Random(17))
        val request = validated(
            GestureIntent.Tap(
                preferredBounds = ScreenRect(120, 220, 520, 620),
                targetBounds = ScreenRect(100, 200, 540, 640),
                durationMs = 90L
            )
        )

        val result = compiler.compile(
            request,
            GesturePolicyDecision(GestureAlgorithmId.SAFE_UNIFORM_TAP)
        )

        assertTrue(result is GestureCommandCompilationResult.Compiled)
        val command = (result as GestureCommandCompilationResult.Compiled).command
        assertEquals("compiler-test", command.id)
        assertTrue(command.gesture.commands.isNotEmpty())
        assertEquals(context, command.executionContext)
        assertEquals("compiler must use one coherent display snapshot", 1, metricsReads)
    }

    @Test
    fun rejectsAlgorithmThatDoesNotSupportTheValidatedIntent() {
        val compiler = GestureCommandCompiler({ metrics }, Random(18))
        val request = validated(
            GestureIntent.Tap(
                preferredBounds = ScreenRect(120, 220, 520, 620),
                targetBounds = ScreenRect(100, 200, 540, 640),
                durationMs = 90L
            )
        )

        val result = compiler.compile(
            request,
            GesturePolicyDecision(GestureAlgorithmId.FEED_CUBIC_BEZIER)
        )

        assertEquals(
            "policy_algorithm_intent_mismatch",
            (result as GestureCommandCompilationResult.Rejected).reason
        )
    }

    @Test
    fun swipeGeometryRejectionIncludesVelocityCurveAndSpecificSubreason() {
        val compiler = GestureCommandCompiler({ metrics }, Random(22))
        val request = validated(
            GestureIntent.VerticalSwipe(
                viewport = ScreenRect(100, 100, 120, 120),
                direction = com.freedom.core.accessibility.gesture.VerticalSwipeDirection.UP,
                durationMs = 620L
            )
        )
        val result = compiler.compile(
            request,
            GesturePolicyDecision(
                algorithmId = GestureAlgorithmId.VERTICAL_CUBIC_BEZIER,
                trajectory = com.freedom.core.accessibility.gesture.GestureTrajectoryKind.FIXED_CUBIC_BEZIER,
                velocityCurve = com.freedom.core.accessibility.gesture.GestureVelocityCurve.CUBIC_EASE_OUT,
                direction = com.freedom.core.accessibility.gesture.GestureDirectionProfile(),
                motion = com.freedom.core.accessibility.gesture.GestureMotionProfile(
                    velocityCurve = com.freedom.core.accessibility.gesture.GestureVelocityCurve.CUBIC_EASE_OUT
                ),
                swipeRandomization = com.freedom.core.accessibility.gesture.GestureSwipeRandomizationProfile.DETERMINISTIC
            )
        )

        val rejection = result as GestureCommandCompilationResult.Rejected
        assertTrue(rejection.reason.contains("velocityCurve=CUBIC_EASE_OUT"))
        assertTrue(rejection.reason.contains("subreason=safe_bounds_too_small"))
    }

    @Test
    fun rejectsPerturbationAlgorithmWhenPolicySettingsDisableIt() {
        val compiler = GestureCommandCompiler({ metrics }, Random(21))
        val request = validated(
            GestureIntent.FeedSwipe(ScreenRect(80, 120, 1000, 1800), 620L)
        )
        val decision = GesturePolicyDecision(
            algorithmId = GestureAlgorithmId.FEED_CUBIC_BEZIER_WITH_MICRO_PERTURBATION,
            feedSwipeSettings = FeedSwipePolicySettings(
                curveOffsetRatio = 0.012f,
                microPerturbationFrequencyHz = 4.5f,
                microPerturbationEnabled = false
            )
        )

        val result = compiler.compile(request, decision)

        assertEquals(
            "policy_algorithm_intent_mismatch",
            (result as GestureCommandCompilationResult.Rejected).reason
        )
    }

    @Test
    fun rejectsWhenDisplayCoordinateSnapshotChangedAfterRequestValidation() {
        val rotatedMetrics = metrics.copy(widthPixels = 1920, heightPixels = 1080, rotationDegrees = 90)
        val compiler = GestureCommandCompiler({ rotatedMetrics }, Random(19))
        val request = validated(
            GestureIntent.Tap(
                preferredBounds = ScreenRect(120, 220, 520, 620),
                targetBounds = ScreenRect(100, 200, 540, 640),
                durationMs = 90L
            )
        )

        val result = compiler.compile(
            request,
            GesturePolicyDecision(GestureAlgorithmId.SAFE_UNIFORM_TAP)
        )

        assertEquals(
            "coordinate_context_changed_before_compile",
            (result as GestureCommandCompilationResult.Rejected).reason
        )
    }

    @Test
    fun rejectsWhenLiveDisplayMetricsCannotBeRead() {
        val compiler = GestureCommandCompiler({ error("display unavailable") }, Random(20))
        val request = validated(
            GestureIntent.Tap(
                preferredBounds = ScreenRect(120, 220, 520, 620),
                targetBounds = ScreenRect(100, 200, 540, 640),
                durationMs = 90L
            )
        )

        val result = compiler.compile(
            request,
            GesturePolicyDecision(GestureAlgorithmId.SAFE_UNIFORM_TAP)
        )

        assertEquals(
            "display_metrics_unavailable",
            (result as GestureCommandCompilationResult.Rejected).reason
        )
    }

    private fun validated(intent: GestureIntent): ValidatedGestureRequest {
        val request = GestureIntentRequest(
            id = "compiler-test",
            intent = intent,
            description = "gesture command compiler unit test",
            executionContext = context
        )
        val result = GesturePolicyValidator { metrics }.validateRequest(request)
        return (result as GestureRequestValidationResult.Accepted).request
    }
}
