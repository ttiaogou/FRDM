package com.freedom.application.gesture

import com.freedom.core.accessibility.api.DisplayMetricsSnapshot
import com.freedom.core.accessibility.api.GestureDispatchPurpose
import com.freedom.core.accessibility.api.GestureSpec
import com.freedom.core.accessibility.api.GestureStrokeSpec
import com.freedom.core.accessibility.api.ScreenRect
import com.freedom.core.accessibility.gesture.GestureCommand
import com.freedom.core.accessibility.gesture.GestureCoordinateRegion
import com.freedom.core.accessibility.gesture.GestureGeometryPolicy
import com.freedom.core.accessibility.gesture.GestureIntent
import com.freedom.core.accessibility.gesture.GestureKind
import com.freedom.core.accessibility.gesture.GestureDynamicsPolicy
import com.freedom.core.accessibility.gesture.toGestureCoordinateContext
import com.freedom.core.accessibility.gesture.VerticalSwipeDirection
import kotlin.math.ceil
import kotlin.random.Random

/** 将已验证的业务意图和策略决策转换为不可变手势命令的结果。 */
internal sealed interface GestureCommandCompilationResult {
    data class Compiled(val command: GestureCommand) : GestureCommandCompilationResult
    data class Rejected(val reason: String) : GestureCommandCompilationResult
}

private class GestureCommandCompilationRejected(val reason: String) : RuntimeException(reason)

/**
 * Runtime 内部的手势命令编译器。生产代码中只有该组件可以构造 GestureCommand 和 GestureSpec。
 * 它把已验证的请求及策略转换为一份不可变命令快照；不负责平台派发，也不持有执行状态。
 */
internal class GestureCommandCompiler(
    private val displayMetrics: () -> DisplayMetricsSnapshot,
    private val random: Random
) {
    fun compile(
        validatedRequest: ValidatedGestureRequest,
        policyDecision: GesturePolicyDecision
    ): GestureCommandCompilationResult {
        val request = validatedRequest.request
        if (!policyMatchesIntent(request.intent, policyDecision)) {
            return GestureCommandCompilationResult.Rejected("policy_algorithm_intent_mismatch")
        }
        val metrics = runCatching(displayMetrics).getOrNull()
            ?: return GestureCommandCompilationResult.Rejected("display_metrics_unavailable")
        if (metrics.widthPixels <= 0 || metrics.heightPixels <= 0 ||
            !metrics.density.isFinite() || metrics.density <= 0f
        ) return GestureCommandCompilationResult.Rejected("display_metrics_invalid")
        if (metrics.toGestureCoordinateContext() != request.executionContext.coordinateContext) {
            return GestureCommandCompilationResult.Rejected("coordinate_context_changed_before_compile")
        }
        val command = try {
            compileCommand(validatedRequest, policyDecision, metrics)
        } catch (rejected: GestureCommandCompilationRejected) {
            return GestureCommandCompilationResult.Rejected(rejected.reason)
        } catch (failure: Exception) {
            return GestureCommandCompilationResult.Rejected(
                "compiler_exception:${failure.javaClass.simpleName}"
            )
        } ?: return GestureCommandCompilationResult.Rejected("invalid_or_unavailable_geometry")
        return GestureCommandCompilationResult.Compiled(command)
    }

    private fun policyMatchesIntent(intent: GestureIntent, decision: GesturePolicyDecision): Boolean =
        when (intent) {
            is GestureIntent.Tap,
            is GestureIntent.DoubleTap -> decision.algorithmId == GestureAlgorithmId.SAFE_UNIFORM_TAP
            is GestureIntent.VerticalSwipe -> decision.algorithmId == GestureAlgorithmId.VERTICAL_CUBIC_BEZIER &&
                decision.trajectory != null && decision.motion != null && decision.direction != null
            is GestureIntent.FeedSwipe -> decision.feedSwipeSettings != null && decision.trajectory != null &&
                decision.motion != null && decision.direction != null && when (decision.algorithmId) {
                    GestureAlgorithmId.FEED_CUBIC_BEZIER -> decision.trajectory !in TREMOR_TRAJECTORIES
                    GestureAlgorithmId.FEED_CUBIC_BEZIER_WITH_MICRO_PERTURBATION ->
                        decision.feedSwipeSettings.microPerturbationEnabled && decision.trajectory in TREMOR_TRAJECTORIES
                    else -> false
                }
            is GestureIntent.DirectionalSwipe -> decision.algorithmId == GestureAlgorithmId.DIRECTIONAL_MOTION_PROFILE &&
                decision.trajectory != null && decision.motion != null && decision.direction != null
            is GestureIntent.CalibrationSwipe -> decision.algorithmId == GestureAlgorithmId.CALIBRATION_CUBIC_BEZIER &&
                decision.trajectory != null && decision.motion != null && decision.direction != null
        }

    private fun compileCommand(
        validatedRequest: ValidatedGestureRequest,
        policyDecision: GesturePolicyDecision,
        metrics: DisplayMetricsSnapshot
    ): GestureCommand? {
        val request = validatedRequest.request

        val displayBounds = ScreenRect(0, 0, metrics.widthPixels, metrics.heightPixels)
        // AccessibilityService 无法读取目标应用完整的 WindowInsets；在屏幕边缘保留保守的 24dp 安全带，
        // 避免点击或滑动落入常见的系统边缘手势区域。
        val systemGestureSafeBounds = GestureGeometryPolicy.safeDisplayBounds(
            displayBounds = displayBounds,
            density = metrics.density
        ) ?: return null

        return when (val intent = request.intent) {
            is GestureIntent.Tap -> {
                if (!isValidDuration(intent.durationMs)) return null
                val allowedDisplay = intersectDisplay(systemGestureSafeBounds, intent.displayBounds) ?: return null
                val tap = compileTapPath(intent.preferredBounds, intent.targetBounds, allowedDisplay) ?: return null
                GestureCommand(
                    id = request.id,
                    kind = GestureKind.TAP,
                    gesture = GestureSpec(
                        commands = tap.commands,
                        durationMs = intent.durationMs,
                        feedbackIntent = intent.feedbackIntent
                    ),
                    validationRegion = tap.validationRegion,
                    executionContext = request.executionContext,
                    description = request.description,
                    preDispatchDelayMs = policyDecision.preDispatchDelayMs,
                    touchProfile = policyDecision.touch
                )
            }
            is GestureIntent.DoubleTap -> {
                if (!isValidDuration(intent.durationMs)) return null
                if (intent.intervalMs <= 0L || intent.intervalMs > MAX_STROKE_DELAY_MS) return null
                val allowedDisplay = intersectDisplay(systemGestureSafeBounds, intent.displayBounds) ?: return null
                val tap = compileTapPath(intent.preferredBounds, intent.targetBounds, allowedDisplay) ?: return null
                GestureCommand(
                    id = request.id,
                    kind = GestureKind.DOUBLE_TAP,
                    gesture = GestureSpec(
                        commands = tap.commands,
                        durationMs = intent.durationMs,
                        additionalStrokes = listOf(
                            GestureStrokeSpec(
                                commands = tap.commands,
                                durationMs = intent.durationMs,
                                startDelayMs = intent.intervalMs
                            )
                        ),
                        feedbackIntent = intent.feedbackIntent
                    ),
                    validationRegion = tap.validationRegion,
                    executionContext = request.executionContext,
                    description = request.description,
                    preDispatchDelayMs = policyDecision.preDispatchDelayMs,
                    touchProfile = policyDecision.touch
                )
            }
            is GestureIntent.VerticalSwipe -> {
                if (!isValidDuration(intent.durationMs)) return null
                val viewport = intersect(intent.viewport, systemGestureSafeBounds, systemGestureSafeBounds) ?: return null
                compileDynamicSwipe(
                    requestId = request.id,
                    description = request.description,
                    viewport = viewport,
                    durationMs = intent.durationMs,
                    baseAngle = if (intent.direction == VerticalSwipeDirection.UP) -90f else 90f,
                    policyDecision = policyDecision,
                    executionContext = request.executionContext
                )
            }
            is GestureIntent.FeedSwipe -> {
                if (!isValidDuration(intent.durationMs)) return null
                val viewport = intersect(intent.viewport, systemGestureSafeBounds, systemGestureSafeBounds) ?: return null
                compileDynamicSwipe(
                    requestId = request.id,
                    description = request.description,
                    viewport = viewport,
                    durationMs = intent.durationMs,
                    baseAngle = -90f,
                    policyDecision = policyDecision,
                    executionContext = request.executionContext
                )
            }
            is GestureIntent.DirectionalSwipe -> {
                if (!isValidDuration(intent.durationMs) || !intent.angleDegrees.isFinite() ||
                    intent.angleDegrees !in -180f..180f
                ) return null
                val viewport = intersect(intent.viewport, systemGestureSafeBounds, systemGestureSafeBounds) ?: return null
                compileDynamicSwipe(
                    requestId = request.id,
                    description = request.description,
                    viewport = viewport,
                    durationMs = intent.durationMs,
                    baseAngle = intent.angleDegrees,
                    policyDecision = policyDecision,
                    executionContext = request.executionContext
                )
            }
            is GestureIntent.CalibrationSwipe -> {
                if (!isValidDuration(intent.durationMs)) return null
                val region = intersect(intent.region, systemGestureSafeBounds, systemGestureSafeBounds) ?: return null
                compileDynamicSwipe(
                    requestId = request.id,
                    description = request.description,
                    viewport = region,
                    durationMs = intent.durationMs,
                    baseAngle = -90f,
                    policyDecision = policyDecision,
                    executionContext = request.executionContext,
                    dispatchPurpose = GestureDispatchPurpose.INTERNAL_CALIBRATION
                )
            }
        }
    }


    private fun compileDynamicSwipe(
        requestId: String,
        description: String,
        viewport: ScreenRect,
        durationMs: Long,
        baseAngle: Float,
        policyDecision: GesturePolicyDecision,
        executionContext: com.freedom.core.accessibility.gesture.GestureExecutionContext,
        dispatchPurpose: GestureDispatchPurpose = GestureDispatchPurpose.AUTONOMOUS
    ): GestureCommand? {
        val trajectory = policyDecision.trajectory ?: return null
        val motion = policyDecision.motion ?: return null
        var direction = policyDecision.direction ?: return null
        val feedSettings = policyDecision.feedSwipeSettings
        if (feedSettings != null) {
            val physicalCurve = (feedSettings.curveOffsetRatio * minOf(viewport.width, viewport.height))
                .coerceIn(0f, 180f)
            direction = direction.copy(curveAmplitudePx = physicalCurve)
        }
        val pause = policyDecision.pause
        val dynamicsViewport = if (feedSettings != null) {
            // Density is read from the request-bound coordinate snapshot; the live display metrics
            // were checked against this same snapshot immediately before compilation.
            val capturedDensity = executionContext.coordinateContext.density
            val horizontalInset = maxOf(ceil(8f * capturedDensity).toInt(), ceil(viewport.width * 0.04f).toInt())
            val verticalInset = ceil(8f * capturedDensity).toInt()
            ScreenRect(
                left = viewport.left + horizontalInset,
                top = viewport.top + verticalInset,
                right = viewport.right - horizontalInset,
                bottom = viewport.bottom - verticalInset
            )
        } else viewport
        var swipePlanFailure: String? = null
        val plan = GestureDynamicsPolicy.createSwipe(
            bounds = dynamicsViewport,
            baseAngleDegrees = baseAngle,
            durationMs = durationMs,
            trajectory = trajectory,
            direction = direction,
            motion = motion,
            pause = pause,
            randomization = policyDecision.swipeRandomization,
            random = random,
            sampleCount = policyDecision.sampleCount,
            tremorFrequencyHz = policyDecision.tremorFrequencyHz,
            tremorAmplitudePx = policyDecision.tremorAmplitudePx,
            quadraticTremorFrequencyHz = policyDecision.quadraticTremorFrequencyHz,
            quadraticTremorAmplitudePx = policyDecision.quadraticTremorAmplitudePx,
            randomPhaseTremorFrequencyHz = policyDecision.randomPhaseTremorFrequencyHz,
            randomPhaseTremorAmplitudePx = policyDecision.randomPhaseTremorAmplitudePx,
            onPlanCompilationRejected = { reason -> swipePlanFailure = reason }
        ) ?: throw GestureCommandCompilationRejected(
            "swipe_plan_unavailable;velocityCurve=${motion.velocityCurve};" +
                "subreason=${swipePlanFailure ?: "missing_plan_rejection_detail"}"
        )
        val primary = plan.strokes.firstOrNull() ?: return null
        val validationRegion = GestureGeometryPolicy.validationRegion(plan.safeBounds) ?: return null
        return GestureCommand(
            id = requestId,
            kind = GestureKind.SWIPE,
            gesture = GestureSpec(
                commands = primary.commands,
                durationMs = primary.durationMs,
                startDelayMs = primary.startDelayMs,
                willContinue = primary.willContinue,
                additionalStrokes = plan.strokes.drop(1),
                dispatchPurpose = dispatchPurpose
            ),
            validationRegion = validationRegion,
            executionContext = executionContext,
            description = description,
            preDispatchDelayMs = if (dispatchPurpose == GestureDispatchPurpose.INTERNAL_CALIBRATION) 0L
                else policyDecision.preDispatchDelayMs,
            touchProfile = policyDecision.touch,
            releaseVelocityPxPerSecond = plan.motion.releaseVelocityPxPerSecond,
            inertiaEstimate = plan.inertiaEstimate
        )
    }

    private data class CompiledTap(
        val commands: List<com.freedom.core.accessibility.api.GesturePathCommand>,
        val validationRegion: GestureCoordinateRegion
    )

    private fun compileTapPath(
        preferredBounds: ScreenRect,
        targetBounds: ScreenRect,
        displayBounds: ScreenRect
    ): CompiledTap? {
        val targetRegion = intersect(preferredBounds, targetBounds, displayBounds) ?: return null
        val point = GestureGeometryPolicy.sampleTapPoint(
            preferredBounds = targetRegion,
            targetBounds = targetRegion,
            displayBounds = targetRegion,
            random = random
        ) ?: return null
        val commands = GestureGeometryPolicy.createTapCommands(point, targetRegion, random) ?: return null
        val validationRegion = GestureGeometryPolicy.tapValidationRegion(targetRegion) ?: return null
        return CompiledTap(commands, validationRegion)
    }

    private fun isValidDuration(durationMs: Long): Boolean =
        durationMs in MIN_STROKE_DURATION_MS..MAX_STROKE_DURATION_MS

    private fun intersectDisplay(currentSafeDisplay: ScreenRect, requestedDisplay: ScreenRect?): ScreenRect? =
        if (requestedDisplay == null) currentSafeDisplay else intersect(currentSafeDisplay, requestedDisplay, currentSafeDisplay)

    private fun intersect(first: ScreenRect, second: ScreenRect, third: ScreenRect): ScreenRect? {
        val bounds = ScreenRect(
            left = maxOf(first.left, second.left, third.left),
            top = maxOf(first.top, second.top, third.top),
            right = minOf(first.right, second.right, third.right),
            bottom = minOf(first.bottom, second.bottom, third.bottom)
        )
        return bounds.takeUnless { it.isEmpty }
    }

    private companion object {
        val TREMOR_TRAJECTORIES = setOf(
            com.freedom.core.accessibility.gesture.GestureTrajectoryKind.NORMAL_DIRECTION_TREMOR,
            com.freedom.core.accessibility.gesture.GestureTrajectoryKind.QUADRATIC_BEZIER_2D_TREMOR,
            com.freedom.core.accessibility.gesture.GestureTrajectoryKind.RANDOM_PHASE_2D_TREMOR
        )
        const val MIN_STROKE_DURATION_MS = 1L
        const val MAX_STROKE_DURATION_MS = 60_000L
        const val MAX_STROKE_DELAY_MS = 60_000L
    }
}
