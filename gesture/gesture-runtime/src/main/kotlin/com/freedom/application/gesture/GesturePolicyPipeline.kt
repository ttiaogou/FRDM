package com.freedom.application.gesture

import com.freedom.gesture.api.GestureApi
import com.freedom.core.accessibility.api.DisplayMetricsSnapshot
import com.freedom.core.accessibility.api.GestureSubmission
import com.freedom.core.accessibility.api.WindowRef
import com.freedom.core.accessibility.gesture.GestureCoordinateContext
import com.freedom.core.accessibility.gesture.GestureExecutionContext
import com.freedom.core.accessibility.gesture.GestureIntent
import com.freedom.core.accessibility.gesture.toGestureCoordinateContext
import com.freedom.core.accessibility.gesture.GestureExecutionListener
import com.freedom.core.accessibility.gesture.GestureIntentRequest
import com.freedom.core.runtime.TaskCoordinator
import kotlin.random.Random

/**
 * 编排请求验证、策略选择、命令编译、二次审批和串行执行的完整流程。
 * 几何生成以及 GestureSpec / GestureCommand 构造统一由 GestureCommandCompiler 负责。
 */
internal class GesturePolicyPipeline(
    private val executor: InterruptibleGestureExecutor,
    private val displayMetrics: () -> DisplayMetricsSnapshot,
    private val onDiagnostic: (String) -> Unit = {},
    random: Random = Random.Default,
    private val currentRunGeneration: () -> Long = { 0L },
    private val currentGestureWindow: () -> WindowRef? = { null },
    private val isGestureWindowCurrent: (WindowRef) -> Boolean = { expected ->
        currentGestureWindow()?.let { sameWindowIdentity(expected, it) } == true
    },
    private val currentCoordinateContext: () -> GestureCoordinateContext? = {
        runCatching(displayMetrics).getOrNull()?.toGestureCoordinateContext()
    },
    policySettingsProvider: GesturePolicySettingsProvider = DefaultGesturePolicySettingsProvider,
    private val taskCoordinator: TaskCoordinator? = null
) : GestureApi {
    private val validator = GesturePolicyValidator(displayMetrics)
    private val policyManager = GesturePolicyManager(policySettingsProvider, random)
    private val commandCompiler = GestureCommandCompiler(displayMetrics, random)

    override fun captureContext(
        expectedWindow: WindowRef?,
        expectedCoordinates: GestureCoordinateContext?
    ): GestureExecutionContext? {
        val liveWindow = runCatching(currentGestureWindow).getOrNull() ?: return null
        val boundWindow = expectedWindow ?: liveWindow
        if (!sameWindowIdentity(boundWindow, liveWindow)) return null
        val generation = runCatching(currentRunGeneration).getOrNull() ?: return null
        if (generation < 0L) return null
        val coordinates = runCatching(currentCoordinateContext).getOrNull() ?: return null
        if (expectedCoordinates != null && expectedCoordinates != coordinates) return null
        // Recheck after reading display state: rotation may advance the window generation mid-capture.
        if (!runCatching { isGestureWindowCurrent(boundWindow) }.getOrDefault(false)) return null
        // Bind the exact active task capability now; a later transfer invalidates this snapshot
        // even when the run generation, window and display remain unchanged.
        val coordinator = taskCoordinator
        val taskLease = if (coordinator == null) null else {
            runCatching { coordinator.currentLease() }.getOrElse { return null }
        }
        if (taskLease != null &&
            (taskLease.runGeneration != generation ||
                !runCatching { coordinator?.isCurrent(taskLease) == true }.getOrDefault(false))
        ) return null
        return runCatching {
            GestureExecutionContext(generation, boundWindow, coordinates, taskLease)
        }.getOrNull()
    }

    override fun execute(
        request: GestureIntentRequest,
        listener: GestureExecutionListener
    ): GestureSubmission {
        // 阶段 1：拒绝无效业务输入，并签发仅 Runtime 可见的验证令牌。
        val validatedRequest = when (val result = validator.validateRequest(request)) {
            is GestureRequestValidationResult.Accepted -> result.request
            is GestureRequestValidationResult.Rejected ->
                return reject(request.id, "request_validation", result.reason)
        }
        val validatedIntentRequest = validatedRequest.request

        // 阶段 2：复核请求绑定的运行代次、目标窗口和屏幕坐标快照。
        val contextFailure = validateExecutionContext(validatedIntentRequest.executionContext)
        if (contextFailure != null) {
            return reject(validatedIntentRequest.id, "context_validation", contextFailure)
        }

        // 阶段 3：为本次请求选择兼容策略；底层算法参数始终由 Runtime 管理。
        val policyDecision = policyManager.select(validatedIntentRequest)
            ?: return reject(validatedIntentRequest.id, "policy_selection", "policy_settings_unavailable_or_invalid")
        val selectedMotion = policyDecision.motion
        val requestDurationMs = when (val intent = validatedIntentRequest.intent) {
            is GestureIntent.Tap -> intent.durationMs
            is GestureIntent.DoubleTap -> intent.durationMs
            is GestureIntent.VerticalSwipe -> intent.durationMs
            is GestureIntent.FeedSwipe -> intent.durationMs
            is GestureIntent.DirectionalSwipe -> intent.durationMs
            is GestureIntent.CalibrationSwipe -> intent.durationMs
        }
        diagnostic(
            "手势策略已选择：id=${validatedIntentRequest.id}；algorithm=${policyDecision.algorithmId}；" +
                "trajectory=${policyDecision.trajectory ?: "NONE"}；" +
                "velocityCurve=${selectedMotion?.velocityCurve ?: "NONE"}；" +
                "motionMode=${selectedMotion?.mode ?: "NONE"}；" +
                "maxVelocity=${selectedMotion?.maximumVelocityPxPerSecond ?: "NONE"}；" +
                "acceleration=${selectedMotion?.accelerationPxPerSecondSquared ?: "NONE"}；" +
                "deceleration=${selectedMotion?.decelerationPxPerSecondSquared ?: "NONE"}；" +
                "durationMs=$requestDurationMs；selection=per_request"
        )

        // 阶段 4：将已验证请求编译为一份不可变、与平台无关的 GestureCommand。
        val command = when (val result = commandCompiler.compile(validatedRequest, policyDecision)) {
            is GestureCommandCompilationResult.Compiled -> result.command
            is GestureCommandCompilationResult.Rejected ->
                return reject(validatedIntentRequest.id, "command_compilation", result.reason)
        }

        // 阶段 5：对完整几何、坐标和时间参数进行二次验证，通过后签发审批令牌。
        val approvedCommand = when (val result = validator.validateAndApprove(command)) {
            is GestureCommandValidationResult.Approved -> result.command
            is GestureCommandValidationResult.Rejected ->
                return reject(command.id, "command_validation", result.reason)
        }
        diagnostic(
            "手势规范编译与校验通过：id=${command.id}；kind=${command.kind}；" +
                "policyVersion=$POLICY_VERSION；runGeneration=${command.executionContext.runGeneration}；" +
                "window=${command.executionContext.targetWindow.packageName}/" +
                "${command.executionContext.targetWindow.windowId}@${command.executionContext.targetWindow.generation}；" +
                "display=${command.executionContext.coordinateContext.displayId}:" +
                "${command.executionContext.coordinateContext.widthPixels}x" +
                "${command.executionContext.coordinateContext.heightPixels}@" +
                "${command.executionContext.coordinateContext.rotationDegrees}；dispatch=coordinator"
        )

        // 阶段 6：Coordinator 再次复核实时状态，并执行可视化门控和串行调度。
        return executor.execute(approvedCommand, listener)
    }

    override fun cancelActive(commandId: String, reason: String): Boolean =
        executor.cancelActive(commandId, reason)

    private fun validateExecutionContext(context: GestureExecutionContext): String? = when {
        context.runGeneration != runCatching(currentRunGeneration).getOrDefault(-1L) ->
            "run_generation_changed"
        context.taskLease?.let { lease ->
            lease.runGeneration != context.runGeneration ||
                runCatching { taskCoordinator?.isCurrent(lease) }.getOrNull() != true
        } == true ->
            "task_lease_changed"
        context.taskLease == null &&
            runCatching { taskCoordinator?.currentLease() != null }.getOrDefault(true) ->
            "task_lease_missing"
        !runCatching { isGestureWindowCurrent(context.targetWindow) }.getOrDefault(false) ->
            "target_window_changed"
        runCatching(currentCoordinateContext).getOrNull() != context.coordinateContext ->
            "coordinate_context_changed"
        else -> null
    }

    private fun reject(requestId: String, stage: String, reason: String): GestureSubmission {
        diagnostic("手势运行阶段拒绝：id=$requestId；stage=$stage；reason=$reason")
        return GestureSubmission.REJECTED
    }

    private fun diagnostic(message: String) {
        runCatching { onDiagnostic(message) }
    }

    private companion object {
        fun sameWindowIdentity(expected: WindowRef, actual: WindowRef): Boolean =
            expected.packageName == actual.packageName &&
                expected.generation == actual.generation &&
                (expected.windowId < 0 || actual.windowId < 0 || expected.windowId == actual.windowId) &&
                (expected.activityClass.isBlank() || actual.activityClass.isBlank() ||
                    expected.activityClass == actual.activityClass)

        const val POLICY_VERSION = 2
    }
}
