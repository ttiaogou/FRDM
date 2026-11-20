package com.freedom.core.accessibility.gesture

import com.freedom.core.accessibility.api.ScreenRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random
import kotlin.math.abs

class GestureDynamicsPolicyTest {
    private val bounds = ScreenRect(0, 0, 1080, 1920)
    private val duration = 620L

    @Test
    fun allSixImportedTrajectoryFamiliesProduceContinuousSafePaths() {
        GestureTrajectoryKind.entries.forEachIndexed { index, trajectory ->
            val plan = GestureDynamicsPolicy.createSwipe(
                bounds = bounds,
                baseAngleDegrees = -90f,
                durationMs = duration,
                trajectory = trajectory,
                direction = GestureDirectionProfile(curveAmplitudePx = 18f),
                motion = GestureMotionProfile(maximumVelocityPxPerSecond = 8_000f),
                random = Random(index),
                sampleCount = 100
            )
            assertNotNull("trajectory=$trajectory", plan)
            val result = plan!!
            assertTrue(result.start.x >= result.safeBounds.left)
            assertTrue(result.start.y >= result.safeBounds.top)
            assertTrue(result.strokes.size >= 5)
            assertEquals(0L, result.strokes.first().startDelayMs)
            assertFalse(result.strokes.last().willContinue)
            assertTrue(result.strokes.drop(1).all { it.continuationOfPrevious })
        }
    }

    @Test
    fun durationDrivenVelocityCurvesAlwaysReserveDistinctTerminalStrokeSegments() {
        GestureVelocityCurve.entries.forEachIndexed { index, velocityCurve ->
            val rejectionReasons = mutableListOf<String>()
            val plan = GestureDynamicsPolicy.createSwipe(
                bounds = bounds,
                baseAngleDegrees = -90f,
                durationMs = duration,
                trajectory = GestureTrajectoryKind.FIXED_CUBIC_BEZIER,
                direction = GestureDirectionProfile(curveAmplitudePx = 18f),
                motion = GestureMotionProfile(
                    mode = GestureMotionMode.DURATION_DRIVEN,
                    velocityCurve = velocityCurve,
                    maximumVelocityPxPerSecond = 8_000f
                ),
                randomization = GestureSwipeRandomizationProfile.DETERMINISTIC,
                random = Random(index),
                sampleCount = 120,
                onPlanCompilationRejected = rejectionReasons::add
            )

            assertNotNull("velocityCurve=$velocityCurve;rejections=$rejectionReasons", plan)
            val result = plan!!
            assertTrue("velocityCurve=$velocityCurve must retain all five path phases", result.strokes.size >= 5)
            assertEquals(duration, result.strokes.sumOf { it.durationMs })
            assertTrue("velocityCurve=$velocityCurve produced empty-stroke diagnostics: $rejectionReasons",
                rejectionReasons.isEmpty())
            assertFalse(result.strokes.last().willContinue)
        }
    }

    @Test
    fun swipePlanRejectionReportsItsSpecificGeometryPrecondition() {
        val rejectionReasons = mutableListOf<String>()
        val plan = GestureDynamicsPolicy.createSwipe(
            bounds = ScreenRect(100, 100, 120, 120),
            baseAngleDegrees = -90f,
            durationMs = duration,
            trajectory = GestureTrajectoryKind.FIXED_CUBIC_BEZIER,
            direction = GestureDirectionProfile(),
            motion = GestureMotionProfile(velocityCurve = GestureVelocityCurve.CUBIC_EASE_OUT),
            randomization = GestureSwipeRandomizationProfile.DETERMINISTIC,
            random = Random(77),
            onPlanCompilationRejected = rejectionReasons::add
        )

        assertEquals(null, plan)
        assertTrue(rejectionReasons.single().startsWith("safe_bounds_too_small"))
    }

    @Test
    fun fixedAndLeftBiasedBezierControlsMatchImportedExampleOffsets() {
        val fixed = GestureDynamicsPolicy.createSwipe(
            bounds = bounds,
            baseAngleDegrees = -90f,
            durationMs = duration,
            trajectory = GestureTrajectoryKind.FIXED_CUBIC_BEZIER,
            direction = GestureDirectionProfile(curveAmplitudePx = 0f),
            motion = GestureMotionProfile(maximumVelocityPxPerSecond = 8_000f),
            random = Random(100)
        )!!
        val dx = fixed.end.x - fixed.start.x
        val dy = fixed.end.y - fixed.start.y
        assertEquals(fixed.start.x + dx * 0.30f + 50f, fixed.control1.x, 0.01f)
        assertEquals(fixed.start.y + dy * 0.30f - 30f, fixed.control1.y, 0.01f)
        assertEquals(fixed.start.x + dx * 0.70f - 40f, fixed.control2.x, 0.01f)
        assertEquals(fixed.start.y + dy * 0.70f + 50f, fixed.control2.y, 0.01f)

        val leftBiased = GestureDynamicsPolicy.createSwipe(
            bounds = bounds,
            baseAngleDegrees = -90f,
            durationMs = duration,
            trajectory = GestureTrajectoryKind.LEFT_BIASED_CUBIC_BEZIER,
            direction = GestureDirectionProfile(curveAmplitudePx = 0f),
            motion = GestureMotionProfile(maximumVelocityPxPerSecond = 8_000f),
            random = Random(102)
        )!!
        val leftDx = leftBiased.end.x - leftBiased.start.x
        val leftDy = leftBiased.end.y - leftBiased.start.y
        assertEquals(leftBiased.start.x + leftDx * 0.25f - 60f, leftBiased.control1.x, 0.01f)
        assertEquals(leftBiased.start.y + leftDy * 0.25f, leftBiased.control1.y, 0.01f)
        assertEquals(leftBiased.start.x + leftDx * 0.75f - 60f, leftBiased.control2.x, 0.01f)
        assertEquals(leftBiased.start.y + leftDy * 0.75f, leftBiased.control2.y, 0.01f)

        val quadratic = GestureDynamicsPolicy.createSwipe(
            bounds = bounds,
            baseAngleDegrees = -90f,
            durationMs = duration,
            trajectory = GestureTrajectoryKind.QUADRATIC_BEZIER_2D_TREMOR,
            direction = GestureDirectionProfile(curveAmplitudePx = 0f),
            motion = GestureMotionProfile(maximumVelocityPxPerSecond = 8_000f),
            random = Random(101)
        )!!
        assertEquals((quadratic.start.x + quadratic.end.x) / 2f + 30f, quadratic.control1.x, 0.01f)
        assertEquals((quadratic.start.y + quadratic.end.y) / 2f - 20f, quadratic.control1.y, 0.01f)
    }

    @Test
    fun tremorFamiliesRetainBezierBaselineWhenOscillationIsDisabled() {
        listOf(
            GestureTrajectoryKind.NORMAL_DIRECTION_TREMOR,
            GestureTrajectoryKind.RANDOM_PHASE_2D_TREMOR
        ).forEachIndexed { index, trajectory ->
            val plan = GestureDynamicsPolicy.createSwipe(
                bounds = bounds,
                baseAngleDegrees = -90f,
                durationMs = duration,
                trajectory = trajectory,
                direction = GestureDirectionProfile(curveAmplitudePx = 36f),
                motion = GestureMotionProfile(maximumVelocityPxPerSecond = 8_000f),
                random = Random(40 + index),
                tremorFrequencyHz = 0f,
                tremorAmplitudePx = 0f,
                randomPhaseTremorFrequencyHz = 0f,
                randomPhaseTremorAmplitudePx = 0f
            )!!
            val dx = plan.end.x - plan.start.x
            val dy = plan.end.y - plan.start.y
            val hasCurvedControl = plan.strokes.flatMap { it.commands }.any { command ->
                val cubic = command as? com.freedom.core.accessibility.api.GesturePathCommand.CubicTo
                    ?: return@any false
                listOf(cubic.control1, cubic.control2).any { point ->
                    abs(dx * (point.y - plan.start.y) - dy * (point.x - plan.start.x)) > 1f
                }
            }
            assertTrue("$trajectory must retain a Bezier baseline", hasCurvedControl)
        }
    }

    @Test
    fun horizontalAndArbitraryDirectionAreSupported() {
        val horizontal = GestureDynamicsPolicy.createSwipe(
            bounds = bounds,
            baseAngleDegrees = 0f,
            durationMs = 620L,
            trajectory = GestureTrajectoryKind.FIXED_CUBIC_BEZIER,
            direction = GestureDirectionProfile(axis = GestureAxis.HORIZONTAL, baseAngleDegrees = 0f),
            motion = GestureMotionProfile(maximumVelocityPxPerSecond = 8_000f),
            randomization = GestureSwipeRandomizationProfile.DETERMINISTIC,
            random = Random(5)
        )
        assertNotNull(horizontal)
        assertTrue(horizontal!!.end.x > horizontal.start.x)
        assertEquals(horizontal.start.y, horizontal.end.y, 0.01f)
    }

    @Test
    fun directionOffsetAndPathRotationComposeIntoTheFinalTravelAngle() {
        val plan = GestureDynamicsPolicy.createSwipe(
            bounds = bounds,
            baseAngleDegrees = -90f,
            durationMs = duration,
            trajectory = GestureTrajectoryKind.FIXED_CUBIC_BEZIER,
            direction = GestureDirectionProfile(
                axis = GestureAxis.FREE_ANGLE,
                directionOffsetDegrees = 10f,
                pathRotationDegrees = 15f
            ),
            motion = GestureMotionProfile(maximumVelocityPxPerSecond = 8_000f),
            randomization = GestureSwipeRandomizationProfile.DETERMINISTIC,
            random = Random(17)
        )
        assertNotNull(plan)
        assertEquals(-65f, plan!!.directionAngleDegrees, 0.01f)
    }

    @Test
    fun axisConstraintRejectsIncompatibleDirection() {
        val plan = GestureDynamicsPolicy.createSwipe(
            bounds = bounds,
            baseAngleDegrees = 45f,
            durationMs = 620L,
            trajectory = GestureTrajectoryKind.FIXED_CUBIC_BEZIER,
            direction = GestureDirectionProfile(axis = GestureAxis.HORIZONTAL, baseAngleDegrees = 45f),
            motion = GestureMotionProfile(maximumVelocityPxPerSecond = 8_000f),
            random = Random(8)
        )
        assertEquals(null, plan)
    }

    @Test
    fun kinematicModeHonorsStartPeakReleaseAndAccelerationConstraints() {
        val plan = GestureDynamicsPolicy.createSwipe(
            bounds = bounds,
            baseAngleDegrees = -90f,
            durationMs = 620L,
            trajectory = GestureTrajectoryKind.RANDOMIZED_CUBIC_BEZIER,
            direction = GestureDirectionProfile(),
            motion = GestureMotionProfile(
                mode = GestureMotionMode.KINEMATIC_LIMITED,
                initialVelocityPxPerSecond = 100f,
                maximumVelocityPxPerSecond = 4_000f,
                releaseVelocityPxPerSecond = 80f,
                accelerationPxPerSecondSquared = 20_000f,
                decelerationPxPerSecondSquared = 18_000f
            ),
            randomization = GestureSwipeRandomizationProfile.DETERMINISTIC,
            random = Random(13)
        )
        assertNotNull(plan)
        assertEquals(100f, plan!!.motion.initialVelocityPxPerSecond, 0.01f)
        assertEquals(80f, plan.motion.releaseVelocityPxPerSecond, 0.01f)
        assertTrue(plan.motion.maximumVelocityPxPerSecond in 100f..4_000f)
        assertEquals(20_000f, plan.motion.accelerationPxPerSecondSquared, 0.01f)
        assertEquals(18_000f, plan.motion.decelerationPxPerSecondSquared, 0.01f)
    }

    @Test
    fun runtimeKinematicProfileFindsFeasiblePeakBelowConfiguredMaximumVelocity() {
        // Mirrors the AI Reply startup scroll: the 420ms request and 8,000px/s ceiling
        // make distanceForPeak(8,000) smaller than the required distance, even though a
        // feasible peak exists around 4,380px/s. The solver must search the increasing branch.
        val plan = GestureDynamicsPolicy.createSwipe(
            bounds = ScreenRect(72, 156, 648, 1486),
            baseAngleDegrees = -90f,
            durationMs = 420L,
            trajectory = GestureTrajectoryKind.FIXED_CUBIC_BEZIER,
            direction = GestureDirectionProfile(
                axis = GestureAxis.VERTICAL,
                baseAngleDegrees = -90f,
                curveAmplitudePx = 48f
            ),
            motion = GestureMotionProfile(
                mode = GestureMotionMode.KINEMATIC_LIMITED,
                initialVelocityPxPerSecond = 220f,
                maximumVelocityPxPerSecond = 8_000f,
                releaseVelocityPxPerSecond = 140f,
                accelerationPxPerSecondSquared = 20_000f,
                decelerationPxPerSecondSquared = 20_000f
            ),
            randomization = GestureSwipeRandomizationProfile.DETERMINISTIC,
            random = Random(73)
        )

        assertNotNull("runtime kinematic settings should produce a valid swipe", plan)
        assertTrue(plan!!.motion.maximumVelocityPxPerSecond in 220f..8_000f)
        assertTrue("solver must not choose the impossible descending branch", plan.motion.maximumVelocityPxPerSecond < 4_380.5f)
    }

    @Test
    fun runtimeKinematicProfileWithRandomizedStartupScrollDurationCompilesAcrossTrajectoryFamilies() {
        val trajectories = GestureTrajectoryKind.entries
        trajectories.forEachIndexed { trajectoryIndex, trajectory ->
            repeat(20) { seed ->
                val plan = GestureDynamicsPolicy.createSwipe(
                    bounds = ScreenRect(72, 156, 648, 1486),
                    baseAngleDegrees = -90f,
                    durationMs = 480L,
                    trajectory = trajectory,
                    direction = GestureDirectionProfile(
                        axis = GestureAxis.VERTICAL,
                        baseAngleDegrees = -90f,
                        curveAmplitudePx = 48f
                    ),
                    motion = GestureMotionProfile(
                        mode = GestureMotionMode.KINEMATIC_LIMITED,
                        initialVelocityPxPerSecond = 220f,
                        maximumVelocityPxPerSecond = 8_000f,
                        releaseVelocityPxPerSecond = 140f,
                        accelerationPxPerSecondSquared = 20_000f,
                        decelerationPxPerSecondSquared = 20_000f
                    ),
                    pause = GesturePauseProfile(
                        enabled = true,
                        probability = 0.05f,
                        progress = 0.40f,
                        durationMs = 85L,
                        durationDistribution = GestureTimeDistributionProfile(
                            enabled = true,
                            kind = GestureTimeDistributionKind.TRUNCATED_LOG_NORMAL,
                            centerMs = 85.0,
                            logNormalSigma = 0.20,
                            minimumMs = 55L,
                            maximumMs = 145L
                        )
                    ),
                    randomization = GestureSwipeRandomizationProfile(),
                    random = Random(seed * 31 + trajectoryIndex)
                )
                assertNotNull("trajectory=$trajectory seed=$seed", plan)
            }
        }
    }

    @Test
    fun impossibleKinematicConstraintsAreRejected() {
        val plan = GestureDynamicsPolicy.createSwipe(
            bounds = bounds,
            baseAngleDegrees = -90f,
            durationMs = 100L,
            trajectory = GestureTrajectoryKind.FIXED_CUBIC_BEZIER,
            direction = GestureDirectionProfile(),
            motion = GestureMotionProfile(
                mode = GestureMotionMode.KINEMATIC_LIMITED,
                maximumVelocityPxPerSecond = 100f,
                accelerationPxPerSecondSquared = 100f,
                decelerationPxPerSecondSquared = 100f
            ),
            random = Random(1)
        )
        assertEquals(null, plan)
    }

    @Test
    fun pauseIsRepresentedAsAStationaryContinuationInsideTheSameStrokeChain() {
        val plan = GestureDynamicsPolicy.createSwipe(
            bounds = bounds,
            baseAngleDegrees = -90f,
            durationMs = duration,
            trajectory = GestureTrajectoryKind.FIXED_CUBIC_BEZIER,
            direction = GestureDirectionProfile(),
            motion = GestureMotionProfile(maximumVelocityPxPerSecond = 8_000f),
            pause = GesturePauseProfile(enabled = true, probability = 1f, progress = 0.4f, durationMs = 80L),
            randomization = GestureSwipeRandomizationProfile.DETERMINISTIC,
            random = Random(4)
        )
        assertNotNull(plan)
        val result = plan!!
        val pause = result.strokes.singleOrNull { stroke ->
            val move = stroke.commands.firstOrNull() as? com.freedom.core.accessibility.api.GesturePathCommand.MoveTo
            val cubic = stroke.commands.getOrNull(1) as? com.freedom.core.accessibility.api.GesturePathCommand.CubicTo
            move != null && cubic != null && cubic.control1 == move.point &&
                cubic.control2 == move.point && cubic.end == move.point
        }
        assertNotNull(pause)
        assertEquals(80L, pause!!.durationMs)
        assertTrue(pause.continuationOfPrevious)
        assertTrue(pause.willContinue)
        assertEquals(duration + 80L, result.totalDurationMs)
        assertTrue(result.pauseApplied)
    }


    @Test
    fun failedPauseDistributionRejectsTheSwipeInsteadOfFallingBackToFixedDuration() {
        val profile = GestureTimeDistributionProfile(
            kind = GestureTimeDistributionKind.TRUNCATED_NORMAL,
            centerMs = 100.0,
            standardDeviationMs = 1_000_000_000.0,
            minimumMs = 90L,
            maximumMs = 110L
        )
        assertTrue(profile.isValid())
        val plan = GestureDynamicsPolicy.createSwipe(
            bounds = bounds,
            baseAngleDegrees = -90f,
            durationMs = duration,
            trajectory = GestureTrajectoryKind.FIXED_CUBIC_BEZIER,
            direction = GestureDirectionProfile(),
            motion = GestureMotionProfile(maximumVelocityPxPerSecond = 8_000f),
            pause = GesturePauseProfile(
                enabled = true,
                probability = 1f,
                durationMs = 80L,
                durationDistribution = profile
            ),
            randomization = GestureSwipeRandomizationProfile.DETERMINISTIC,
            random = Random(4)
        )
        assertEquals(null, plan)
    }

    @Test
    fun sharedTimeDistributionSamplesOnlyInsideConfiguredBounds() {
        val profile = GestureTimeDistributionProfile(
            kind = GestureTimeDistributionKind.TRUNCATED_LOG_NORMAL,
            centerMs = 240.0,
            logNormalSigma = 0.35,
            minimumMs = 120L,
            maximumMs = 420L
        )
        repeat(200) { seed ->
            val sample = GestureTimeDistributionPolicy.sampleMillis(profile, Random(seed))
            assertNotNull(sample)
            assertTrue(sample!! in 120L..420L)
        }
        val uniform = GestureTimeDistributionProfile(
            kind = GestureTimeDistributionKind.UNIFORM,
            minimumMs = 33L,
            maximumMs = 37L
        )
        repeat(100) { seed ->
            val sample = GestureTimeDistributionPolicy.sampleMillis(uniform, Random(seed))
            assertTrue(sample!! in 33L..37L)
        }
    }

    @Test
    fun impossibleDistributionCenterIsRejectedInsteadOfClamped() {
        val profile = GestureTimeDistributionProfile(
            kind = GestureTimeDistributionKind.TRUNCATED_NORMAL,
            centerMs = 1_000.0,
            standardDeviationMs = 10.0,
            minimumMs = 100L,
            maximumMs = 200L
        )
        assertFalse(profile.isValid())
        assertEquals(null, GestureTimeDistributionPolicy.sampleMillis(profile, Random(1)))
    }

    @Test
    fun defaultSettingsDoNotSilentlyEnablePreDispatchDelay() {
        assertFalse(GestureDynamicsSettings().preDispatchDelay.enabled)
    }

    @Test
    fun logNormalDelayIsClampedAndCanBeDisabled() {
        val profile = GesturePreDispatchDelayProfile(
            enabled = true, medianMs = 400.0, sigma = 0.35, minimumMs = 150L, maximumMs = 1_200L
        )
        repeat(100) {
            val delay = GestureDynamicsPolicy.sampleLogNormalDelay(profile, Random(it))
            assertNotNull(delay)
            assertTrue(delay!! in 150L..1_200L)
        }
        assertEquals(0L, GestureDynamicsPolicy.sampleLogNormalDelay(profile.copy(enabled = false), Random(1)))
    }

    @Test
    fun ordinarySwipesIndependentlyVaryStartEndDistanceAngleCurveAndSpeed() {
        val plans = (1..48).mapNotNull { seed ->
            GestureDynamicsPolicy.createSwipe(
                bounds = bounds,
                baseAngleDegrees = -90f,
                durationMs = duration,
                trajectory = GestureTrajectoryKind.RANDOMIZED_CUBIC_BEZIER,
                direction = GestureDirectionProfile(curveAmplitudePx = 42f),
                motion = GestureMotionProfile(
                    initialVelocityPxPerSecond = 220f,
                    maximumVelocityPxPerSecond = 7_000f,
                    releaseVelocityPxPerSecond = 140f,
                    accelerationPxPerSecondSquared = 20_000f,
                    decelerationPxPerSecondSquared = 20_000f
                ),
                random = Random(seed),
                sampleCount = 64
            )
        }
        assertTrue("sufficient seeds should produce valid plans", plans.size >= 40)
        assertTrue(plans.map { it.start }.distinct().size > 30)
        assertTrue(plans.map { it.end }.distinct().size > 30)
        assertTrue(plans.map { it.motion.pathLengthPx }.distinct().size > 30)
        assertTrue(plans.map { it.directionAngleDegrees }.distinct().size > 30)
        assertTrue(plans.map { it.control1 }.distinct().size > 30)
        assertTrue(plans.map { it.motion.durationMs }.distinct().size > 20)
        plans.forEach { plan ->
            assertTrue(plan.start.x in plan.safeBounds.left.toFloat()..plan.safeBounds.right.toFloat())
            assertTrue(plan.start.y in plan.safeBounds.top.toFloat()..plan.safeBounds.bottom.toFloat())
            assertTrue(plan.end.x in plan.safeBounds.left.toFloat()..plan.safeBounds.right.toFloat())
            assertTrue(plan.end.y in plan.safeBounds.top.toFloat()..plan.safeBounds.bottom.toFloat())
            assertTrue(plan.motion.maximumVelocityPxPerSecond <= 7_700f)
            assertTrue(plan.motion.durationMs in 570L..670L)
        }
    }

    @Test
    fun deterministicProfilePreservesExactGeometryAndTiming() {
        val first = GestureDynamicsPolicy.createSwipe(
            bounds = bounds, baseAngleDegrees = -90f, durationMs = duration,
            trajectory = GestureTrajectoryKind.FIXED_CUBIC_BEZIER,
            direction = GestureDirectionProfile(),
            motion = GestureMotionProfile(maximumVelocityPxPerSecond = 8_000f),
            randomization = GestureSwipeRandomizationProfile.DETERMINISTIC,
            random = Random(1)
        )
        val second = GestureDynamicsPolicy.createSwipe(
            bounds = bounds, baseAngleDegrees = -90f, durationMs = duration,
            trajectory = GestureTrajectoryKind.FIXED_CUBIC_BEZIER,
            direction = GestureDirectionProfile(),
            motion = GestureMotionProfile(maximumVelocityPxPerSecond = 8_000f),
            randomization = GestureSwipeRandomizationProfile.DETERMINISTIC,
            random = Random(99)
        )
        assertNotNull(first)
        assertEquals(first!!.start, second!!.start)
        assertEquals(first.end, second.end)
        assertEquals(duration, first.motion.durationMs)
    }

    @Test
    fun sharedTimeDistributionReturnsOnlyValuesWithinTheConfiguredWindow() {
        val profile = GestureTimeDistributionProfile(
            kind = GestureTimeDistributionKind.TRUNCATED_LOG_NORMAL,
            centerMs = 400.0,
            logNormalSigma = 0.35,
            minimumMs = 150L,
            maximumMs = 1_200L
        )
        val samples = (1..128).map { seed ->
            GestureTimeDistributionPolicy.sampleMillis(profile, Random(seed))
        }
        assertTrue(samples.all { it != null && it in 150L..1_200L })
        assertTrue(samples.filterNotNull().distinct().size > 50)
        assertEquals(0L, GestureTimeDistributionPolicy.sampleMillis(profile.copy(enabled = false), Random(1)))
        assertEquals(null, GestureTimeDistributionPolicy.sampleMillis(profile.copy(minimumMs = 1_201L), Random(1)))
    }

    @Test
    fun postReleaseInertiaIsEstimatedButNotClaimedAsPlatformControllable() {
        val estimate = GestureDynamicsPolicy.estimatePostReleaseInertia(
            releaseVelocityPxPerSecond = 800f,
            decelerationPxPerSecondSquared = 4_000f
        )
        assertNotNull(estimate)
        assertEquals(80f, estimate!!.estimatedDistancePx, 0.01f)
        assertEquals(200L, estimate.estimatedDurationMs)
        assertEquals(null, GestureDynamicsPolicy.estimatePostReleaseInertia(0f, 4_000f))
        assertEquals(null, GestureDynamicsPolicy.estimatePostReleaseInertia(800f, 0f))
        assertFalse(GesturePlatformCapabilityReport.ACCESSIBILITY_SERVICE.postReleaseInertiaControllable)
    }

    @Test
    fun touchProfileIsValidatedButAccessibilityCapabilityRemainsExplicitlyUnsupported() {
        assertTrue(GestureTouchProfile(pressure = 0.7f, contactWidthPx = 10f, contactHeightPx = 15f).isValid())
        assertFalse(GestureTouchProfile(pressure = 1.5f).isValid())
        val report = GesturePlatformCapabilityReport.ACCESSIBILITY_SERVICE
        assertFalse(report.pressureInjectionSupported)
        assertFalse(report.contactAreaInjectionSupported)
        assertFalse(report.toolTypeInjectionSupported)
        assertFalse(report.postReleaseInertiaControllable)
        assertTrue(report.continuedStrokeSupported)
    }

}
