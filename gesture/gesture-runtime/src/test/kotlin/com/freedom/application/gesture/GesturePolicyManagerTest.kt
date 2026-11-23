package com.freedom.application.gesture

import com.freedom.core.accessibility.api.ScreenRect
import com.freedom.core.accessibility.api.WindowRef
import com.freedom.core.accessibility.gesture.GestureCoordinateContext
import com.freedom.core.accessibility.gesture.GestureExecutionContext
import com.freedom.core.accessibility.gesture.GestureDynamicsSettings
import com.freedom.core.accessibility.gesture.GestureMotionMode
import com.freedom.core.accessibility.gesture.GestureTrajectoryKind
import com.freedom.core.accessibility.gesture.GestureIntent
import com.freedom.core.accessibility.gesture.GestureIntentRequest
import com.freedom.core.accessibility.gesture.VerticalSwipeDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class GesturePolicyManagerTest {
    private val context = GestureExecutionContext(
        runGeneration = 8L,
        targetWindow = WindowRef("com.example.target", "MainActivity", 8L, 3),
        coordinateContext = GestureCoordinateContext(0, 1080, 1920, 3f, 0)
    )

    private val feedRequest = GestureIntentRequest(
        id = "feed-policy-test",
        intent = GestureIntent.FeedSwipe(ScreenRect(80, 120, 1000, 1800), 620L),
        description = "policy manager test",
        executionContext = context
    )

    @Test
    fun feedSwipeSelectsCompatibleAlgorithmPerRequest() {
        val manager = GesturePolicyManager(
            settingsProvider = GesturePolicySettingsProvider {
                FeedSwipePolicySettings(
                    curveOffsetRatio = 0.012f,
                    microPerturbationFrequencyHz = 4.5f,
                    microPerturbationEnabled = true
                )
            },
            random = Random(42)
        )

        val selected = (0 until 64).mapNotNull { manager.select(feedRequest)?.algorithmId }.toSet()

        assertTrue(selected.contains(GestureAlgorithmId.FEED_CUBIC_BEZIER))
        assertTrue(selected.contains(GestureAlgorithmId.FEED_CUBIC_BEZIER_WITH_MICRO_PERTURBATION))
    }

    @Test
    fun disabledMicroPerturbationOnlyAllowsBaseBezierAlgorithm() {
        val manager = GesturePolicyManager(
            settingsProvider = GesturePolicySettingsProvider {
                FeedSwipePolicySettings(
                    curveOffsetRatio = 0.012f,
                    microPerturbationFrequencyHz = 4.5f,
                    microPerturbationEnabled = false
                )
            },
            random = Random(123)
        )

        repeat(32) {
            assertEquals(
                GestureAlgorithmId.FEED_CUBIC_BEZIER,
                manager.select(feedRequest)?.algorithmId
            )
        }
    }

    @Test
    fun invalidRuntimePolicySettingsRejectFeedSwipe() {
        val manager = GesturePolicyManager(
            settingsProvider = GesturePolicySettingsProvider {
                FeedSwipePolicySettings(
                    curveOffsetRatio = Float.NaN,
                    microPerturbationFrequencyHz = 4.5f
                )
            },
            random = Random(1)
        )

        assertNull(manager.select(feedRequest))
    }


    @Test
    fun curveOffsetMustNotExceedItsConfiguredMaximum() {
        val manager = GesturePolicyManager(
            settingsProvider = GesturePolicySettingsProvider {
                FeedSwipePolicySettings(
                    curveOffsetRatio = 0.03f,
                    maxCurveOffsetRatio = 0.02f,
                    microPerturbationFrequencyHz = 4.5f
                )
            },
            random = Random(9)
        )

        assertNull(manager.select(feedRequest))
    }

    @Test
    fun feedSwipeSettingsAreReloadedForEveryRequest() {
        var reads = 0
        val manager = GesturePolicyManager(
            settingsProvider = GesturePolicySettingsProvider {
                reads++
                FeedSwipePolicySettings(
                    curveOffsetRatio = if (reads == 1) 0.012f else 0.02f,
                    microPerturbationFrequencyHz = 4.5f,
                    microPerturbationEnabled = false
                )
            },
            random = Random(10)
        )

        val first = manager.select(feedRequest)
        val second = manager.select(feedRequest)

        assertEquals(2, reads)
        assertEquals(0.012f, first?.feedSwipeSettings?.curveOffsetRatio)
        assertEquals(0.02f, second?.feedSwipeSettings?.curveOffsetRatio)
    }


    @Test
    fun frequencyAboveTheSupportedPolicyRangeIsRejected() {
        val manager = GesturePolicyManager(
            settingsProvider = GesturePolicySettingsProvider {
                FeedSwipePolicySettings(
                    curveOffsetRatio = 0.012f,
                    microPerturbationFrequencyHz = 10.1f
                )
            },
            random = Random(11)
        )

        assertNull(manager.select(feedRequest))
    }

    @Test
    fun settingsProviderFailureRejectsFeedSwipeWithoutFallback() {
        val manager = GesturePolicyManager(
            settingsProvider = GesturePolicySettingsProvider {
                error("settings unavailable")
            },
            random = Random(12)
        )

        assertNull(manager.select(feedRequest))
    }

    @Test
    fun motionModeIsSelectedPerRequestWhenMultipleProfilesAreConfigured() {
        val vertical = feedRequest.copy(intent = GestureIntent.VerticalSwipe(
            viewport = ScreenRect(80, 120, 1000, 1800),
            direction = VerticalSwipeDirection.UP,
            durationMs = 620L
        ))
        val manager = GesturePolicyManager(
            settingsProvider = object : GesturePolicySettingsProvider {
                override fun loadFeedSwipeSettings() = FeedSwipePolicySettings(0.012f, 5f)
                override fun loadDynamicsSettings() = GestureDynamicsSettings(
                    motionModeCandidates = listOf(
                        GestureMotionMode.DURATION_DRIVEN,
                        GestureMotionMode.KINEMATIC_LIMITED
                    ),
                    accelerationPxPerSecondSquared = 20_000f,
                    decelerationPxPerSecondSquared = 20_000f
                )
            },
            random = Random(901)
        )

        val modes = (0 until 64).mapNotNull { manager.select(vertical)?.motion?.mode }.toSet()
        assertEquals(setOf(GestureMotionMode.DURATION_DRIVEN, GestureMotionMode.KINEMATIC_LIMITED), modes)
    }

    @Test
    fun calibrationSwipeRetainsDeterministicProfileWithoutPauseOrDispatchDelay() {
        val calibration = feedRequest.copy(intent = GestureIntent.CalibrationSwipe(
            region = ScreenRect(80, 120, 1000, 1800),
            durationMs = 700L
        ))
        val manager = GesturePolicyManager(
            settingsProvider = object : GesturePolicySettingsProvider {
                override fun loadFeedSwipeSettings() = FeedSwipePolicySettings(0.012f, 5f)
                override fun loadDynamicsSettings() = GestureDynamicsSettings(
                    trajectoryCandidates = GestureTrajectoryKind.entries,
                    velocityCurveCandidates = com.freedom.core.accessibility.gesture.GestureVelocityCurve.entries,
                    motionModeCandidates = listOf(GestureMotionMode.KINEMATIC_LIMITED),
                    pause = com.freedom.core.accessibility.gesture.GesturePauseProfile(
                        enabled = true, probability = 1f, progress = 0.4f, durationMs = 80L
                    ),
                    preDispatchDelay = com.freedom.core.accessibility.gesture.GesturePreDispatchDelayProfile(
                        enabled = true, medianMs = 400.0, sigma = 0.0, minimumMs = 150L, maximumMs = 1_200L
                    )
                )
            },
            random = Random(25)
        )
        val decision = manager.select(calibration)
        assertEquals(GestureTrajectoryKind.FIXED_CUBIC_BEZIER, decision?.trajectory)
        assertEquals(GestureMotionMode.DURATION_DRIVEN, decision?.motion?.mode)
        assertEquals(com.freedom.core.accessibility.gesture.GestureVelocityCurve.COSINE_EASE_IN_OUT, decision?.velocityCurve)
        assertEquals(false, decision?.pause?.enabled)
        assertEquals(0L, decision?.preDispatchDelayMs)
    }

    @Test
    fun durationDrivenVelocityCurveIsSelectedPerRequest() {
        val vertical = feedRequest.copy(intent = GestureIntent.VerticalSwipe(
            viewport = ScreenRect(80, 120, 1000, 1800),
            direction = VerticalSwipeDirection.UP,
            durationMs = 620L
        ))
        val manager = GesturePolicyManager(
            settingsProvider = object : GesturePolicySettingsProvider {
                override fun loadFeedSwipeSettings() = FeedSwipePolicySettings(0.012f, 5f)
                override fun loadDynamicsSettings() = GestureDynamicsSettings(
                    velocityCurveCandidates = com.freedom.core.accessibility.gesture.GestureVelocityCurve.entries
                )
            },
            random = Random(902)
        )
        val selected = (0 until 64).mapNotNull { manager.select(vertical)?.velocityCurve }.toSet()
        assertEquals(com.freedom.core.accessibility.gesture.GestureVelocityCurve.entries.toSet(), selected)
    }

    @Test
    fun tapUsesTheConfiguredBoundedPreDispatchDelayAndDefaultRemainsImmediate() {
        val tap = feedRequest.copy(intent = GestureIntent.Tap(
            preferredBounds = ScreenRect(100, 100, 300, 300),
            targetBounds = ScreenRect(80, 80, 320, 320),
            durationMs = 90L
        ))
        val delayedManager = GesturePolicyManager(
            settingsProvider = object : GesturePolicySettingsProvider {
                override fun loadFeedSwipeSettings() = FeedSwipePolicySettings(0.012f, 5f)
                override fun loadDynamicsSettings() = GestureDynamicsSettings(
                    preDispatchDelay = com.freedom.core.accessibility.gesture.GesturePreDispatchDelayProfile(
                        enabled = true, medianMs = 400.0, sigma = 0.0, minimumMs = 150L, maximumMs = 1_200L
                    )
                )
            },
            random = Random(23)
        )
        val defaultManager = GesturePolicyManager(DefaultGesturePolicySettingsProvider, Random(24))

        assertEquals(400L, delayedManager.select(tap)?.preDispatchDelayMs)
        assertEquals(0L, defaultManager.select(tap)?.preDispatchDelayMs)
    }

    @Test
    fun policyManagerMapsNonFeedGestureKindsToCompatibleAlgorithms() {
        val manager = GesturePolicyManager(DefaultGesturePolicySettingsProvider, Random(7))
        val tap = feedRequest.copy(intent = GestureIntent.Tap(
            preferredBounds = ScreenRect(100, 100, 300, 300),
            targetBounds = ScreenRect(80, 80, 320, 320),
            durationMs = 90L
        ))
        val vertical = feedRequest.copy(intent = GestureIntent.VerticalSwipe(
            viewport = ScreenRect(80, 120, 1000, 1800),
            direction = VerticalSwipeDirection.UP,
            durationMs = 520L
        ))
        val calibration = feedRequest.copy(intent = GestureIntent.CalibrationSwipe(
            region = ScreenRect(80, 120, 1000, 1800),
            durationMs = 700L
        ))

        assertEquals(GestureAlgorithmId.SAFE_UNIFORM_TAP, manager.select(tap)?.algorithmId)
        assertEquals(GestureAlgorithmId.VERTICAL_CUBIC_BEZIER, manager.select(vertical)?.algorithmId)
        assertEquals(GestureAlgorithmId.CALIBRATION_CUBIC_BEZIER, manager.select(calibration)?.algorithmId)
        assertNotNull(manager.select(feedRequest))
    }
}
