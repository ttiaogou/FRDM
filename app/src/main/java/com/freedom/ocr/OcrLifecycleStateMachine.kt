package com.freedom.ocr

/** OCR 插件连接与单次识别的可观测生命周期状态。 */
enum class OcrLifecycleState {
    IDLE,
    DISCOVERING,
    PLUGIN_NOT_INSTALLED,
    SERVICE_NOT_DISCOVERED,
    PLUGIN_DISABLED,
    PERMISSION_DENIED,
    PROTOCOL_INCOMPATIBLE,
    BINDING,
    BIND_FAILED,
    SERVICE_READY,
    OCR_RUNNING,
    OCR_SUCCESS,
    OCR_EMPTY_RESULT,
    OCR_CALL_FAILED,
    OCR_IMAGE_TRANSFER_FAILED,
    OCR_TIMEOUT,
}

/** 插件连接/调用失败的稳定分类；UI 和日志不再依赖自由文本猜测原因。 */
enum class OcrPluginFailureKind {
    PLUGIN_NOT_INSTALLED,
    SERVICE_NOT_DISCOVERED,
    PLUGIN_DISABLED,
    PERMISSION_DENIED,
    PROTOCOL_INCOMPATIBLE,
    BIND_REJECTED,
    BIND_TIMEOUT,
    SERVICE_DISCONNECTED,
    BINDER_DIED,
    REMOTE_CALL_FAILED,
    IMAGE_TRANSFER_FAILED,
    OCR_TIMEOUT,
    UNKNOWN,
}

internal data class OcrLifecycleTransition(
    val previous: OcrLifecycleState,
    val current: OcrLifecycleState,
    val requestId: String,
    val attempt: Int,
    val packageName: String?,
    val reason: String,
    val atElapsedRealtimeMs: Long,
    val sequence: Long,
)

/**
 * 纯 Kotlin OCR 状态机。状态只由 OcrManager 持有；时钟和诊断回调可注入，便于 JVM 测试。
 * 状态机不保存图片、消息正文、用户 ID 或任何 OCR 识别文本。
 */
internal class OcrLifecycleStateMachine(
    private val nowElapsedRealtimeMs: () -> Long = System::currentTimeMillis,
    private val onTransition: (OcrLifecycleTransition) -> Unit = {},
) {
    private val lock = Any()
    private var currentState = OcrLifecycleState.IDLE
    private var sequence = 0L

    fun current(): OcrLifecycleState = synchronized(lock) { currentState }

    fun transition(
        next: OcrLifecycleState,
        requestId: String,
        attempt: Int = 1,
        packageName: String? = null,
        reason: String,
    ): Boolean {
        val transition = synchronized(lock) {
            val previous = currentState
            if (previous == next) return true
            if (next !in ALLOWED_TRANSITIONS.getValue(previous)) return false
            currentState = next
            OcrLifecycleTransition(
                previous = previous,
                current = next,
                requestId = requestId,
                attempt = attempt,
                packageName = packageName,
                reason = reason,
                atElapsedRealtimeMs = nowElapsedRealtimeMs(),
                sequence = ++sequence,
            )
        }
        onTransition(transition)
        return true
    }

    companion object {
        private val RECOVERABLE_TERMINAL_STATES = setOf(
            OcrLifecycleState.DISCOVERING,
            OcrLifecycleState.BINDING,
            OcrLifecycleState.IDLE,
        )
        private val OCR_RESULT_STATES = setOf(
            OcrLifecycleState.OCR_SUCCESS,
            OcrLifecycleState.OCR_EMPTY_RESULT,
            OcrLifecycleState.OCR_CALL_FAILED,
            OcrLifecycleState.OCR_IMAGE_TRANSFER_FAILED,
            OcrLifecycleState.OCR_TIMEOUT,
        )
        private val PLUGIN_DISCOVERY_FAILURES = setOf(
            OcrLifecycleState.PLUGIN_NOT_INSTALLED,
            OcrLifecycleState.SERVICE_NOT_DISCOVERED,
            OcrLifecycleState.PLUGIN_DISABLED,
            OcrLifecycleState.PERMISSION_DENIED,
            OcrLifecycleState.PROTOCOL_INCOMPATIBLE,
        )
        private val ALLOWED_TRANSITIONS: Map<OcrLifecycleState, Set<OcrLifecycleState>> = buildMap {
            put(OcrLifecycleState.IDLE, setOf(OcrLifecycleState.DISCOVERING, OcrLifecycleState.IDLE))
            put(OcrLifecycleState.DISCOVERING, setOf(
                OcrLifecycleState.BINDING,
                OcrLifecycleState.BIND_FAILED,
                OcrLifecycleState.SERVICE_READY,
                OcrLifecycleState.OCR_TIMEOUT,
                OcrLifecycleState.IDLE,
            ) + PLUGIN_DISCOVERY_FAILURES)
            put(OcrLifecycleState.BINDING, setOf(
                OcrLifecycleState.SERVICE_READY,
                OcrLifecycleState.BIND_FAILED,
                OcrLifecycleState.DISCOVERING,
                OcrLifecycleState.OCR_TIMEOUT,
                OcrLifecycleState.IDLE,
            ) + PLUGIN_DISCOVERY_FAILURES)
            put(OcrLifecycleState.BIND_FAILED, RECOVERABLE_TERMINAL_STATES + setOf(OcrLifecycleState.SERVICE_READY) + PLUGIN_DISCOVERY_FAILURES)
            put(OcrLifecycleState.SERVICE_READY, setOf(
                OcrLifecycleState.OCR_RUNNING,
                OcrLifecycleState.DISCOVERING,
                OcrLifecycleState.OCR_TIMEOUT,
                OcrLifecycleState.IDLE,
                OcrLifecycleState.BIND_FAILED,
            ))
            put(OcrLifecycleState.OCR_RUNNING, OCR_RESULT_STATES + setOf(
                OcrLifecycleState.SERVICE_READY,
                OcrLifecycleState.BIND_FAILED,
                OcrLifecycleState.IDLE,
            ))
            OCR_RESULT_STATES.filter { it != OcrLifecycleState.OCR_TIMEOUT }.forEach { state ->
                put(state, setOf(
                    OcrLifecycleState.OCR_RUNNING,
                    OcrLifecycleState.SERVICE_READY,
                    OcrLifecycleState.DISCOVERING,
                    OcrLifecycleState.IDLE,
                    OcrLifecycleState.BIND_FAILED,
                ))
            }
            // A timed-out request may only be reset for a new discovery cycle; late callbacks
            // from the expired bind/detect request must never promote it back to READY or SUCCESS.
            put(OcrLifecycleState.OCR_TIMEOUT, setOf(OcrLifecycleState.DISCOVERING, OcrLifecycleState.IDLE))
            PLUGIN_DISCOVERY_FAILURES.forEach { state ->
                put(state, RECOVERABLE_TERMINAL_STATES)
            }
        }
    }
}

internal fun OcrPluginFailureKind.toLifecycleState(): OcrLifecycleState = when (this) {
    OcrPluginFailureKind.PLUGIN_NOT_INSTALLED -> OcrLifecycleState.PLUGIN_NOT_INSTALLED
    OcrPluginFailureKind.SERVICE_NOT_DISCOVERED -> OcrLifecycleState.SERVICE_NOT_DISCOVERED
    OcrPluginFailureKind.PLUGIN_DISABLED -> OcrLifecycleState.PLUGIN_DISABLED
    OcrPluginFailureKind.PERMISSION_DENIED -> OcrLifecycleState.PERMISSION_DENIED
    OcrPluginFailureKind.PROTOCOL_INCOMPATIBLE -> OcrLifecycleState.PROTOCOL_INCOMPATIBLE
    OcrPluginFailureKind.BIND_REJECTED,
    OcrPluginFailureKind.BIND_TIMEOUT,
    OcrPluginFailureKind.SERVICE_DISCONNECTED,
    OcrPluginFailureKind.BINDER_DIED -> OcrLifecycleState.BIND_FAILED
    OcrPluginFailureKind.REMOTE_CALL_FAILED,
    OcrPluginFailureKind.UNKNOWN -> OcrLifecycleState.OCR_CALL_FAILED
    OcrPluginFailureKind.IMAGE_TRANSFER_FAILED -> OcrLifecycleState.OCR_IMAGE_TRANSFER_FAILED
    OcrPluginFailureKind.OCR_TIMEOUT -> OcrLifecycleState.OCR_TIMEOUT
}

internal fun OcrPluginFailureKind.isRetryable(): Boolean = when (this) {
    OcrPluginFailureKind.BIND_REJECTED,
    OcrPluginFailureKind.BIND_TIMEOUT,
    OcrPluginFailureKind.SERVICE_DISCONNECTED,
    OcrPluginFailureKind.BINDER_DIED,
    OcrPluginFailureKind.REMOTE_CALL_FAILED,
    OcrPluginFailureKind.UNKNOWN -> true
    OcrPluginFailureKind.PLUGIN_NOT_INSTALLED,
    OcrPluginFailureKind.SERVICE_NOT_DISCOVERED,
    OcrPluginFailureKind.PLUGIN_DISABLED,
    OcrPluginFailureKind.PERMISSION_DENIED,
    OcrPluginFailureKind.PROTOCOL_INCOMPATIBLE,
    OcrPluginFailureKind.IMAGE_TRANSFER_FAILED,
    OcrPluginFailureKind.OCR_TIMEOUT -> false
}

/** These failures retire the captured OCR frame, not the business task or send journal. */
internal fun OcrPluginFailureKind.requiresFreshOcrObservation(): Boolean = when (this) {
    OcrPluginFailureKind.OCR_TIMEOUT,
    OcrPluginFailureKind.BINDER_DIED,
    OcrPluginFailureKind.SERVICE_DISCONNECTED -> true
    else -> false
}
