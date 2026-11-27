package com.freedom.ocr

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Bundle
import android.os.DeadObjectException
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.RemoteException
import android.os.SystemClock
import org.autojs.plugin.paddle.ocr.api.OcrOptions
import org.autojs.plugin.paddle.ocr.api.OcrPluginContract
import org.autojs.plugin.paddle.ocr.api.OcrPerformanceDiagnostics
import org.autojs.plugin.paddle.ocr.api.OcrSlowCallObservation
import org.autojs.plugin.paddle.ocr.api.OcrResult
import org.autojs.plugin.paddle.ocr.api.PaddleOcrOptionExtraKeys
import java.io.Closeable
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.thread
import kotlin.concurrent.withLock

/** One line-level OCR observation returned by an installed OCR engine plugin. */
data class OcrLineResult(
    val text: String,
    val confidence: Float,
    val points: List<Pair<Float, Float>>,
)

/**
 * Freedom's OCR entry point and owner of the process-wide plugin Binder connection.
 * Startup preflight and real OCR calls share this instance so preflight does not immediately unbind
 * a healthy service and force the first message to perform a second, potentially refused bind.
 */
class OcrManager(context: android.content.Context) : Closeable {
    private val host = OcrPluginHost(context.applicationContext)
    private val lock = Any()
    private val activeRequestLock = Any()
    /** Serializes startup preflight and OCR requests so one request cannot invalidate another's state. */
    private val operationLock = ReentrantLock(true)
    private val idleHandler = Handler(Looper.getMainLooper())
    private val diagnosticListeners = CopyOnWriteArrayList<(String) -> Unit>()
    private var idleUnbindRunnable: Runnable? = null
    private val requestSequence = AtomicLong(0L)

    private val stateMachine = OcrLifecycleStateMachine(
        nowElapsedRealtimeMs = SystemClock::elapsedRealtime,
        onTransition = { transition ->
            emitDiagnostic(
                "OCR 状态机[${transition.previous}->${transition.current}]：" +
                    "requestId=${transition.requestId};attempt=${transition.attempt};" +
                    "package=${transition.packageName ?: "UNKNOWN"};" +
                    "elapsedMs=${transition.atElapsedRealtimeMs};reason=${transition.reason}"
            )
        },
    )

    @Volatile
    private var activePlugin: BoundOcrPlugin? = null
    @Volatile
    private var activeRequestId: String? = null

    fun addDiagnosticListener(listener: (String) -> Unit): Closeable {
        diagnosticListeners += listener
        return Closeable { diagnosticListeners -= listener }
    }

    fun currentState(): OcrLifecycleState = stateMachine.current()

    /**
     * Performs a real Binder + PluginInfo handshake and retains that exact connection for OCR.
     * Must be called from a worker thread; the Android ServiceConnection callback is delivered on
     * the main thread and this method waits for it.
     */
    fun preflight(): InstalledOcrPlugin = operationLock.withLock {
        val requestId = "preflight-${requestSequence.incrementAndGet()}"
        try {
            if (activePlugin == null) {
                transition(OcrLifecycleState.DISCOVERING, requestId, reason = "启动预检：查询 OCR 服务目录")
            } else {
                transition(
                    OcrLifecycleState.SERVICE_READY,
                    requestId,
                    packageName = activePlugin?.descriptor?.packageName,
                    reason = "启动预检：复核并复用进程内已验证的 Binder 连接",
                )
            }
            val plugin = getOrBindPlugin(requestId, 1)
            transition(OcrLifecycleState.SERVICE_READY, requestId, packageName = plugin.descriptor.packageName, reason = "Binder 已连接且 PluginInfo 已验证")
            scheduleIdleUnbind()
            return@withLock plugin.descriptor
        } catch (failure: Exception) {
            val typed = failure.asOcrPluginException()
            val state = when (typed.failureKind) {
                OcrPluginFailureKind.PLUGIN_NOT_INSTALLED,
                OcrPluginFailureKind.SERVICE_NOT_DISCOVERED,
                OcrPluginFailureKind.PLUGIN_DISABLED,
                OcrPluginFailureKind.PERMISSION_DENIED,
                OcrPluginFailureKind.PROTOCOL_INCOMPATIBLE -> typed.failureKind.toLifecycleState()
                else -> OcrLifecycleState.BIND_FAILED
            }
            transition(state, requestId, reason = typed.message ?: typed.javaClass.simpleName)
            releaseBinding()
            throw typed
        }
    }

    fun recognize(
        bitmap: Bitmap,
        modelId: String? = null,
        isRequestCurrent: () -> Boolean = { true }
    ): List<OcrLineResult> = operationLock.withLock {
        fun requireCurrentRead() {
            if (!isRequestCurrent()) throw CancellationException("OCR_READ_INVALIDATED")
        }
        requireCurrentRead()
        require(modelId.isNullOrBlank()) {
            "模型配置由 OCR 插件自身提供；请在 OCR 插件管理中安装或切换模型配置对应的插件。"
        }
        val requestId = "ocr-${requestSequence.incrementAndGet()}"
        val requestStartedAt = SystemClock.elapsedRealtime()
        var attemptsUsed = 0
        var requestOutcome = "FAILED"
        val options = OcrOptions().apply {
            cpuThreadNum = 2
            useSlim = false
            useOpenCL = false
            detLongSize = maxOf(bitmap.width, bitmap.height)
            scoreThreshold = 0f
            extras = Bundle().apply {
                putString(PaddleOcrOptionExtraKeys.IMAGE_FORMAT, "png")
                putInt(PaddleOcrOptionExtraKeys.IMAGE_QUALITY, 100)
                putString(OcrPluginContract.EXTRA_DIAGNOSTIC_REQUEST_ID, requestId)
            }
        }
        emitDiagnostic("OCR 请求开始：requestId=$requestId;bitmap=${bitmap.width}x${bitmap.height}")
        synchronized(activeRequestLock) {
            activeRequestId = requestId
        }
        var lastFailure: OcrPluginException? = null

        try {
        for (attempt in 1..MAX_REQUEST_ATTEMPTS) {
            attemptsUsed = attempt
            var phase = RequestPhase.BIND
            try {
                requireCurrentRead()
                if (activePlugin == null) {
                    transition(
                        OcrLifecycleState.DISCOVERING,
                        requestId,
                        attempt,
                        reason = "OCR 请求开始：发现并验证可用插件服务",
                    )
                }
                // Plugin discovery owns its bounded bind/wake retry. This outer request loop is
                // reserved for a transient failure after a valid service connection is established.
                val plugin = getOrBindPlugin(requestId, attempt)
                if (stateMachine.current() == OcrLifecycleState.OCR_TIMEOUT) {
                    throw OcrPluginException("OCR 请求在插件连接阶段已超时", failureKind = OcrPluginFailureKind.OCR_TIMEOUT)
                }
                requireCurrentRead()
                phase = RequestPhase.RECOGNIZE
                transition(
                    OcrLifecycleState.SERVICE_READY,
                    requestId,
                    attempt,
                    plugin.descriptor.packageName,
                    "复用已验证 Binder 连接，准备执行 OCR",
                )
                transition(
                    OcrLifecycleState.OCR_RUNNING,
                    requestId,
                    attempt,
                    plugin.descriptor.packageName,
                    "开始传输当前消息裁剪图",
                )

                val transfer = try {
                    createImagePipe(bitmap)
                } catch (failure: Exception) {
                    throw OcrPluginException(
                        "创建 OCR 图片传输管道失败：${failure.message ?: failure.javaClass.simpleName}",
                        failure,
                        OcrPluginFailureKind.IMAGE_TRANSFER_FAILED,
                    )
                }
                emitDiagnostic(
                    "OCR_CLIENT_IMAGE_PIPE_CREATED requestId=$requestId;attempt=$attempt;" +
                        "elapsedMs=${SystemClock.elapsedRealtime()}"
                )
                var detectFailure: Throwable? = null
                val binderCallStartedAt = SystemClock.elapsedRealtime()
                emitDiagnostic(
                    "OCR_CLIENT_BINDER_CALL_START requestId=$requestId;attempt=$attempt;" +
                        "package=${plugin.descriptor.packageName};image=${bitmap.width}x${bitmap.height}"
                )
                val slowObservation = OcrSlowCallObservation(binderCallStartedAt)
                val slowWarning = Runnable {
                    slowObservation.claimSlowWarning(SystemClock.elapsedRealtime())?.let { elapsed ->
                        emitDiagnostic("OCR_CLIENT_SLOW_REQUEST requestId=$requestId;attempt=$attempt;" +
                            "phase=BINDER_IN_FLIGHT;durationMs=$elapsed;thresholdMs=${OcrPerformanceDiagnostics.SLOW_REQUEST_MS};diagnosticOnly=true")
                    }
                }
                // Observe the existing call only: no extra worker, Binder call, cancellation or retry.
                idleHandler.postDelayed(slowWarning, OcrPerformanceDiagnostics.SLOW_REQUEST_MS)
                val result = try {
                    requireCurrentRead()
                    plugin.service.detect(transfer.readEnd, options).also {
                        slowObservation.finish()
                        idleHandler.removeCallbacks(slowWarning)
                        emitDiagnostic(
                            "OCR_CLIENT_BINDER_CALL_SUCCESS requestId=$requestId;attempt=$attempt;" +
                                "durationMs=${SystemClock.elapsedRealtime() - binderCallStartedAt};results=${it.size}"
                        )
                        val extras = it.firstOrNull()?.extras
                        emitDiagnostic("OCR_CLIENT_PLUGIN_TIMINGS requestId=$requestId;attempt=$attempt;" +
                            "metricsCarrier=${if (extras == null) "UNAVAILABLE" else "RESULT_EXTRAS"};" +
                            OcrPerformanceDiagnostics.formatFields(
                                { key -> runCatching { extras?.takeIf { bundle -> bundle.containsKey(key) }?.getLong(key) }.getOrNull() },
                                { key -> runCatching { extras?.getString(key) }.getOrNull() }
                            ))
                    }
                } catch (failure: Throwable) {
                    slowObservation.finish()
                    idleHandler.removeCallbacks(slowWarning)
                    detectFailure = failure
                    emitDiagnostic(
                        "OCR_CLIENT_BINDER_CALL_FAILED requestId=$requestId;attempt=$attempt;" +
                            "durationMs=${SystemClock.elapsedRealtime() - binderCallStartedAt};" +
                            "type=${failure.javaClass.simpleName};message=${failure.message ?: "无异常消息"}"
                    )
                    throw failure
                } finally {
                    slowObservation.finish()
                    idleHandler.removeCallbacks(slowWarning)
                    try {
                        transfer.finish()
                    } catch (finishFailure: Throwable) {
                        val primary = detectFailure
                        if (primary != null) primary.addSuppressed(finishFailure) else throw finishFailure
                    }
                }
                transfer.failure.get()?.let {
                    throw OcrPluginException(
                        "OCR 图片传输失败：${it.message ?: it.javaClass.simpleName}",
                        it,
                        OcrPluginFailureKind.IMAGE_TRANSFER_FAILED,
                    )
                }
                if (stateMachine.current() == OcrLifecycleState.OCR_TIMEOUT) {
                    throw OcrPluginException("OCR 请求已超时；丢弃迟到的插件结果", failureKind = OcrPluginFailureKind.OCR_TIMEOUT)
                }
                requireCurrentRead()
                val lines = result.mapNotNull(::toLineResult)
                if (lines.isEmpty()) {
                    transition(
                        OcrLifecycleState.OCR_EMPTY_RESULT,
                        requestId,
                        attempt,
                        plugin.descriptor.packageName,
                        "插件调用成功，但本张图片没有返回有效文本行",
                    )
                } else {
                    transition(
                        OcrLifecycleState.OCR_SUCCESS,
                        requestId,
                        attempt,
                        plugin.descriptor.packageName,
                        "插件成功返回 ${lines.size} 条文字行",
                    )
                }
                requestOutcome = if (lines.isEmpty()) "EMPTY_RESULT" else "SUCCESS"
                return@withLock lines
            } catch (failure: Exception) {
                if (failure is CancellationException) {
                    requestOutcome = "INVALIDATED"
                    if (stateMachine.current() != OcrLifecycleState.OCR_TIMEOUT) {
                        // Cancellation is not a plugin failure; retain a healthy binding.
                        if (activePlugin != null) {
                            transition(OcrLifecycleState.SERVICE_READY, requestId, attempt,
                                activePlugin?.descriptor?.packageName, "OCR_READ_INVALIDATED")
                        } else {
                            transition(OcrLifecycleState.IDLE, requestId, attempt, reason = "OCR_READ_INVALIDATED")
                        }
                    }
                    emitDiagnostic("OCR_CLIENT_READ_INVALIDATED requestId=$requestId;attempt=$attempt;nextRequest=false")
                    throw failure
                }
                val typed = failure.asOcrPluginException()
                lastFailure = typed
                val alreadyTimedOut = stateMachine.current() == OcrLifecycleState.OCR_TIMEOUT
                requestOutcome = if (alreadyTimedOut || typed.failureKind == OcrPluginFailureKind.OCR_TIMEOUT)
                    "TIMEOUT" else typed.failureKind.name
                val nextState = when {
                    alreadyTimedOut -> OcrLifecycleState.OCR_TIMEOUT
                    phase == RequestPhase.BIND && typed.failureKind in setOf(
                        OcrPluginFailureKind.PLUGIN_NOT_INSTALLED,
                        OcrPluginFailureKind.SERVICE_NOT_DISCOVERED,
                        OcrPluginFailureKind.PLUGIN_DISABLED,
                        OcrPluginFailureKind.PERMISSION_DENIED,
                        OcrPluginFailureKind.PROTOCOL_INCOMPATIBLE,
                    ) -> typed.failureKind.toLifecycleState()
                    phase == RequestPhase.BIND -> OcrLifecycleState.BIND_FAILED
                    typed.failureKind == OcrPluginFailureKind.OCR_TIMEOUT -> OcrLifecycleState.OCR_TIMEOUT
                    typed.failureKind == OcrPluginFailureKind.IMAGE_TRANSFER_FAILED -> OcrLifecycleState.OCR_IMAGE_TRANSFER_FAILED
                    typed.failureKind == OcrPluginFailureKind.BIND_REJECTED ||
                        typed.failureKind == OcrPluginFailureKind.BIND_TIMEOUT ||
                        typed.failureKind == OcrPluginFailureKind.SERVICE_DISCONNECTED ||
                        typed.failureKind == OcrPluginFailureKind.BINDER_DIED -> OcrLifecycleState.BIND_FAILED
                    else -> OcrLifecycleState.OCR_CALL_FAILED
                }
                if (!alreadyTimedOut) {
                    transition(
                        nextState,
                        requestId,
                        attempt,
                        activePlugin?.descriptor?.packageName,
                        "${typed.failureKind}：${typed.message ?: typed.javaClass.simpleName}",
                    )
                } else {
                    emitDiagnostic("OCR 超时后收到迟到故障：requestId=$requestId;failure=${typed.failureKind};不再重试")
                }
                releaseBinding()

                if (!alreadyTimedOut && isRequestCurrent() && phase == RequestPhase.RECOGNIZE &&
                    attempt < MAX_REQUEST_ATTEMPTS && typed.failureKind.isRetryable()) {
                    emitDiagnostic(
                        "OCR 自动恢复：requestId=$requestId;attempt=$attempt;" +
                            "failure=${typed.failureKind};delayMs=$REQUEST_RETRY_DELAY_MS;重新发现并绑定"
                    )
                    SystemClock.sleep(REQUEST_RETRY_DELAY_MS)
                    continue
                }
                throw typed
            }
        }
        throw lastFailure ?: OcrPluginException("OCR 请求未执行", failureKind = OcrPluginFailureKind.UNKNOWN)
        } finally {
            synchronized(activeRequestLock) {
                if (activeRequestId == requestId) activeRequestId = null
            }
            val requestMs = SystemClock.elapsedRealtime() - requestStartedAt
            emitDiagnostic("OCR_CLIENT_REQUEST_FINISHED requestId=$requestId;attempts=$attemptsUsed;" +
                "outcome=$requestOutcome;durationMs=$requestMs;" +
                "slow=${requestMs >= OcrPerformanceDiagnostics.SLOW_REQUEST_MS};diagnosticOnly=true")
            scheduleIdleUnbind()
        }
    }

    /**
     * Called by the screenshot batch guard when its bounded wait expires. The synchronous AIDL
     * transaction cannot be forcibly interrupted by the caller, but the timed-out connection is
     * retired immediately and no late result is accepted for the completed request.
     */
    fun markTimedOut(reason: String) {
        val timedOutRequestId = synchronized(activeRequestLock) {
            val requestId = activeRequestId
            val current = stateMachine.current()
            if (requestId != null && current in setOf(
                    OcrLifecycleState.DISCOVERING,
                    OcrLifecycleState.BINDING,
                    OcrLifecycleState.SERVICE_READY,
                    OcrLifecycleState.OCR_RUNNING,
                )) {
                transition(
                    OcrLifecycleState.OCR_TIMEOUT,
                    requestId,
                    packageName = activePlugin?.descriptor?.packageName,
                    reason = reason,
                )
                requestId
            } else {
                null
            }
        } ?: return

        // Unbind/release the old service connection so a later request must rediscover and
        // re-validate a fresh plugin connection. This does not promise to abort an in-flight
        // synchronous Binder transaction; the batch admission gate isolates that worker until exit.
        releaseBinding()
        emitDiagnostic("OCR 超时连接已隔离：requestId=$timedOutRequestId;lateResult=discard;workerMayStillBeBlocked=true")
    }

    fun discoverInstalledPlugins(): List<InstalledOcrPlugin> = host.discoverInstalled()

    fun setPluginEnabled(packageName: String, enabled: Boolean) = host.setEnabled(packageName, enabled)

    fun installedPluginVersionCode(packageName: String): Long? = host.installedVersionCode(packageName)

    /** Schedule release after the current session detaches; keep the connection reusable meanwhile. */
    fun releaseWhenIdle() = scheduleIdleUnbind()

    override fun close() {
        synchronized(lock) {
            idleUnbindRunnable?.let(idleHandler::removeCallbacks)
            idleUnbindRunnable = null
        }
        releaseBinding()
        transition(
            OcrLifecycleState.IDLE,
            requestId = "close-${requestSequence.incrementAndGet()}",
            reason = "OCR 管理器关闭并释放 Binder 连接",
        )
    }

    private fun getOrBindPlugin(requestId: String, attempt: Int): BoundOcrPlugin {
        synchronized(lock) {
            idleUnbindRunnable?.let(idleHandler::removeCallbacks)
            idleUnbindRunnable = null
            activePlugin?.let { current ->
                val binder = current.service.asBinder()
                if (binder.isBinderAlive && runCatching { binder.pingBinder() }.getOrDefault(false)) {
                    emitDiagnostic("OCR Binder 复用：package=${current.descriptor.packageName};alive=true;ping=true")
                    return current
                }
                emitDiagnostic("OCR Binder 已死亡或 ping 失败：package=${current.descriptor.packageName};丢弃旧连接")
                runCatching { current.close() }
                activePlugin = null
            }
            return host.selectDefault(
                onEvent = { event ->
                    when (event.stage) {
                        OcrPluginHostStage.DISCOVERY_STARTED -> transition(
                            OcrLifecycleState.DISCOVERING, requestId, attempt, event.packageName,
                            "第 ${event.attempt} 轮发现 OCR 服务",
                        )
                        OcrPluginHostStage.BIND_STARTED -> transition(
                            OcrLifecycleState.BINDING, requestId, attempt, event.packageName,
                            "正在绑定 ${event.componentName ?: "OCR Service"}",
                        )
                        OcrPluginHostStage.BIND_REJECTED -> transition(
                            OcrLifecycleState.BIND_FAILED, requestId, attempt, event.packageName,
                            event.detail,
                        )
                        OcrPluginHostStage.PLUGIN_INFO_VERIFIED -> transition(
                            OcrLifecycleState.SERVICE_READY, requestId, attempt, event.packageName,
                            "PluginInfo 验证完成；${event.detail}",
                        )
                        OcrPluginHostStage.CANDIDATES_FOUND,
                        OcrPluginHostStage.BIND_CONNECTED,
                        OcrPluginHostStage.WAKE_RESULT -> Unit
                    }
                    emitDiagnostic(
                        "OCR_HOST[${event.stage}]：attempt=${event.attempt};" +
                            "package=${event.packageName ?: "UNKNOWN"};" +
                            "component=${event.componentName ?: "NONE"};detail=${event.detail}"
                    )
                },
                onDiagnostic = ::emitDiagnostic,
            ).also { activePlugin = it }
        }
    }

    private fun scheduleIdleUnbind() {
        synchronized(lock) {
            idleUnbindRunnable?.let(idleHandler::removeCallbacks)
            val taskRef = AtomicReference<Runnable?>()
            val task = Runnable {
                val previous = synchronized(lock) idleUnbind@{
                    if (idleUnbindRunnable !== taskRef.get()) return@idleUnbind null
                    activePlugin.also { activePlugin = null; idleUnbindRunnable = null }
                }
                if (previous != null) {
                    runCatching { previous.close() }
                    emitDiagnostic("OCR Binder 空闲释放：package=${previous.descriptor.packageName};idleMs=$IDLE_UNBIND_DELAY_MS")
                }
            }
            taskRef.set(task)
            idleUnbindRunnable = task
            idleHandler.postDelayed(task, IDLE_UNBIND_DELAY_MS)
        }
    }

    private fun releaseBinding() {
        val previous = synchronized(lock) {
            idleUnbindRunnable?.let(idleHandler::removeCallbacks)
            idleUnbindRunnable = null
            activePlugin.also { activePlugin = null }
        }
        runCatching { previous?.close() }
    }

    private fun transition(
        next: OcrLifecycleState,
        requestId: String,
        attempt: Int = 1,
        packageName: String? = null,
        reason: String,
    ) {
        val accepted = stateMachine.transition(next, requestId, attempt, packageName, reason)
        if (!accepted) {
            emitDiagnostic(
                "OCR 状态转换被拒绝：from=${stateMachine.current()};to=$next;" +
                    "requestId=$requestId;attempt=$attempt;reason=$reason"
            )
        }
    }

    private fun emitDiagnostic(message: String) {
        diagnosticListeners.forEach { listener -> runCatching { listener(message) } }
    }

    private fun Exception.asOcrPluginException(): OcrPluginException = when (this) {
        is OcrPluginException -> this
        is DeadObjectException -> OcrPluginException(
            "OCR 插件 Binder 对端进程已死亡：${message ?: javaClass.simpleName}",
            this,
            OcrPluginFailureKind.BINDER_DIED,
        )
        is RemoteException -> OcrPluginException(
            "OCR 插件 Binder 远程调用失败：${message ?: javaClass.simpleName}",
            this,
            OcrPluginFailureKind.REMOTE_CALL_FAILED,
        )
        else -> OcrPluginException(
            "OCR 插件调用失败：${message ?: javaClass.simpleName}",
            this,
            OcrPluginFailureKind.UNKNOWN,
        )
    }

    private fun toLineResult(result: OcrResult): OcrLineResult? {
        val text = result.text?.trim().orEmpty()
        if (text.isBlank()) return null
        val quad = result.extras?.getFloatArray(OcrPluginContract.EXTRA_QUAD)
        val points = if (quad != null && quad.size >= 8) {
            (0 until 4).map { index -> quad[index * 2] to quad[index * 2 + 1] }
        } else {
            val bounds = result.bounds ?: Rect()
            listOf(
                bounds.left.toFloat() to bounds.top.toFloat(),
                bounds.right.toFloat() to bounds.top.toFloat(),
                bounds.right.toFloat() to bounds.bottom.toFloat(),
                bounds.left.toFloat() to bounds.bottom.toFloat(),
            )
        }
        return OcrLineResult(text, result.confidence, points)
    }

    private fun createImagePipe(bitmap: Bitmap): EncodedImagePipe {
        val descriptors = ParcelFileDescriptor.createPipe()
        val failure = AtomicReference<Throwable?>(null)
        val writer = thread(start = true, isDaemon = true, name = "Freedom-OCR-ImagePipe") {
            try {
                ParcelFileDescriptor.AutoCloseOutputStream(descriptors[1]).use { output ->
                    check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                        "Bitmap.compress 返回失败"
                    }
                    output.flush()
                }
            } catch (error: Throwable) {
                failure.set(error)
                runCatching { descriptors[1].close() }
            }
        }
        return EncodedImagePipe(descriptors[0], writer, failure)
    }

    private class EncodedImagePipe(
        val readEnd: ParcelFileDescriptor,
        private val writer: Thread,
        val failure: AtomicReference<Throwable?>,
    ) {
        fun finish() {
            runCatching { readEnd.close() }
            writer.join(5_000L)
            if (writer.isAlive) {
                writer.interrupt()
                throw OcrPluginException("OCR 图片传输线程未能及时结束", failureKind = OcrPluginFailureKind.IMAGE_TRANSFER_FAILED)
            }
        }
    }

    private enum class RequestPhase { BIND, RECOGNIZE }

    companion object {
        private const val IDLE_UNBIND_DELAY_MS = 5 * 60_000L
        private const val REQUEST_RETRY_DELAY_MS = 500L
        private const val MAX_REQUEST_ATTEMPTS = 2
        private val sharedLock = Any()
        @Volatile private var sharedInstance: OcrManager? = null

        /** Process-wide manager shared by startup preflight and the Momo OCR processor. */
        fun shared(context: android.content.Context): OcrManager {
            sharedInstance?.let { return it }
            return synchronized(sharedLock) {
                sharedInstance ?: OcrManager(context.applicationContext).also { sharedInstance = it }
            }
        }
    }
}
