package com.freedom.adapters.social.momo

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.freedom.adapters.accessibility.android.AndroidScreenshotCapture
import com.freedom.adapters.accessibility.android.AndroidScreenshotScope
import com.freedom.core.accessibility.api.ScreenRect
import com.freedom.application.ports.OcrReviewRepository
import java.io.ByteArrayOutputStream
import com.freedom.features.aireply.domain.message.ChatMessageBatch
import com.freedom.ocr.OcrManager
import kotlin.math.ceil
import kotlin.math.floor
import java.io.Closeable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Captures one display image, saves the original frame and body crops to the explicit local
 * session-sample store, then recognizes only message-body rectangles established by the reader.
 * OCR results remain in memory until the active chat/window is revalidated by the runtime.
 */
internal class MomoChatMessageOcrProcessor(
    private val context: Context,
    private val screenshotCapture: AndroidScreenshotCapture?,
    private val sampleStore: MomoChatSessionSampleStore? = null,
    private val ocrReviewStore: OcrReviewRepository,
    onLog: (String) -> Unit = {},
) : Closeable {
    private data class OcrDetectionBox(
        val text: String,
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int
    )

    private data class CropRecognition(
        val finalText: String,
        val chineseText: String,
        val latinText: String,
        val meanConfidence: Float?,
        val lineCount: Int,
        val detectionBoxes: List<OcrDetectionBox> = emptyList()
    )

    private val executor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "Freedom-Momo-Local-OCR").apply { isDaemon = true }
    }
    private val ocrManager = OcrManager.shared(context)
    // OcrManager is process-wide; admission must also survive processor recreation while an old
    // worker remains blocked in Binder. A companion-level gate prevents cross-instance queueing.
    private val batchAdmission = PROCESS_BATCH_ADMISSION
    private val onLog = onLog
    private val ocrDiagnosticSubscription = ocrManager.addDiagnosticListener(onLog)
    @Volatile
    private var lastEngineFailureReason: String? = null
    @Volatile
    private var closed = false

    private fun Bitmap.toPngBytes(): ByteArray {
        val output = ByteArrayOutputStream()
        check(compress(Bitmap.CompressFormat.PNG, 100, output)) { "PNG 编码失败" }
        return output.toByteArray()
    }

    fun recognize(
        batch: ChatMessageBatch,
        expectedPackage: String,
        expectedActivity: String,
        onComplete: (MomoChatOcrResult) -> Unit
    ) {
        val batchLease = batchAdmission.tryAcquire()
        if (batchLease == null) {
            onComplete(
                MomoChatOcrResult(
                    emptyMap(),
                    batch.ocrTargets.size,
                    batch.ocrTargets.size,
                    "上一批 OCR 仍在执行或等待插件 Binder 返回；本批次未排队，避免阻塞堆积"
                )
            )
            return
        }

        lastEngineFailureReason = null
        val targets = batch.ocrTargets.take(MAX_TARGETS_PER_BATCH)
        if (targets.isEmpty()) {
            batchLease.close()
            onComplete(MomoChatOcrResult(emptyMap(), 0, 0, "没有符合条件的消息正文区域"))
            return
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            batchLease.close()
            onComplete(MomoChatOcrResult(emptyMap(), targets.size, targets.size, "系统版本不支持无障碍截图；需要 Android 11 或更高版本"))
            return
        }
        val capture = screenshotCapture
        if (capture == null) {
            batchLease.close()
            onComplete(MomoChatOcrResult(emptyMap(), targets.size, targets.size, "屏幕截图能力未注入"))
            return
        }

        val captureCallbackEntered = java.util.concurrent.atomic.AtomicBoolean(false)
        try {
            capture.captureActiveDisplay(expectedPackage, expectedActivity) { frame ->
                captureCallbackEntered.set(true)
                if (frame == null) {
                    batchLease.close()
                    onComplete(MomoChatOcrResult(emptyMap(), targets.size, targets.size, "系统截图失败或当前窗口不可截图"))
                    return@captureActiveDisplay
                }
                val bitmap = frame.bitmap
                val sampleScreenshotPath = runCatching { sampleStore?.saveBitmap("chat_screen", bitmap) }
                    .onFailure { failure -> onLog("OCR_CAPTURE_SAMPLE_SAVE_FAILED：${failure.javaClass.simpleName}") }
                    .getOrNull()
                val completionDelivered = java.util.concurrent.atomic.AtomicBoolean(false)
                val timeoutHandler = Handler(Looper.getMainLooper())
                lateinit var timeoutRunnable: Runnable
                val captureDescription = when (frame.scope) {
                    AndroidScreenshotScope.DISPLAY -> "整屏截图"
                    AndroidScreenshotScope.TARGET_WINDOW -> "目标窗口截图"
                } + "；观察期间监控悬浮窗隐藏=${if (frame.monitorOverlayHidden) "是" else "未确认"}"
                val deliver: (MomoChatOcrResult) -> Unit = { result ->
                    if (completionDelivered.compareAndSet(false, true)) {
                        timeoutHandler.removeCallbacks(timeoutRunnable)
                        try {
                            // Keep the monitor hidden while the consumer synchronously merges and
                            // persists any capture-derived sample data.
                            onComplete(result.copy(
                                captureDescription = captureDescription,
                                sampleScreenshotPath = result.sampleScreenshotPath ?: sampleScreenshotPath
                            ))
                        } finally {
                            // The worker owns the bitmap until it exits; caller timeout must not
                            // recycle an image that is still being encoded or cropped off-thread.
                            runCatching { frame.close() }
                        }
                    }
                }
                timeoutRunnable = Runnable {
                    // Returning a timeout to the caller does not release batch admission: the
                    // worker may still be blocked in a synchronous Binder transaction.
                    ocrManager.markTimedOut("OCR 批次超过 ${OCR_BATCH_TIMEOUT_MS}ms；结果已对调用方超时")
                    deliver(MomoChatOcrResult(emptyMap(), targets.size, targets.size, "本地 OCR 超时；已隔离当前批次并停用旧 Binder 连接"))
                }
                if (!timeoutHandler.postDelayed(timeoutRunnable, OCR_BATCH_TIMEOUT_MS)) {
                    runCatching { bitmap.recycle() }
                    batchLease.close()
                    deliver(MomoChatOcrResult(emptyMap(), targets.size, targets.size, "OCR 超时调度器不可用；已拒绝启动识别"))
                    return@captureActiveDisplay
                }
                val rootBounds = batch.coordinateRootBounds
                if (rootBounds == null || rootBounds.isEmpty) {
                    runCatching { bitmap.recycle() }
                    batchLease.close()
                    deliver(MomoChatOcrResult(emptyMap(), targets.size, targets.size, "消息坐标空间不可用"))
                    return@captureActiveDisplay
                }
                if (closed) {
                    runCatching { bitmap.recycle() }
                    batchLease.close()
                    deliver(MomoChatOcrResult(emptyMap(), targets.size, targets.size, "本地 OCR 任务已停止"))
                    return@captureActiveDisplay
                }
                runCatching {
                    executor.execute {
                        try {
                            if (completionDelivered.get() || closed) {
                                runCatching { bitmap.recycle() }
                                if (!completionDelivered.get()) {
                                    deliver(MomoChatOcrResult(emptyMap(), targets.size, targets.size, "本地 OCR 任务已停止"))
                                }
                                return@execute
                            }
                            val reviewBatchId = ocrReviewStore.beginBatch(bitmap.toPngBytes(), captureDescription).also { id ->
                                if (id == null) onLog("OCR_REVIEW_CAPTURE_SAVE_FAILED：原始截图无法写入人工核验存储")
                            }
                            recognizeNext(
                                bitmap = bitmap,
                                rootBounds = rootBounds,
                                targets = targets,
                                index = 0,
                                recognized = LinkedHashMap(),
                                chineseRecognized = LinkedHashMap(),
                                latinRecognized = LinkedHashMap(),
                                qualityByRowIndex = LinkedHashMap(),
                                confidenceByRowIndex = LinkedHashMap(),
                                lineCountByRowIndex = LinkedHashMap(),
                                captureDescription = captureDescription,
                                sampleScreenshotPath = sampleScreenshotPath,
                                reviewBatchId = reviewBatchId,
                                cropPaths = LinkedHashMap(),
                                failed = 0,
                                isCompleted = { completionDelivered.get() },
                                onComplete = deliver
                            )
                        } catch (_: Exception) {
                            runCatching { bitmap.recycle() }
                            deliver(MomoChatOcrResult(emptyMap(), targets.size, targets.size, "本地 OCR 处理异常；已恢复监控悬浮窗"))
                        } finally {
                            // The worker owns the bitmap until it exits. Caller timeout does not
                            // free it while a synchronous Binder call may still be using a crop.
                            runCatching { bitmap.recycle() }
                            // This is deliberately tied to actual worker exit, not caller timeout.
                            batchLease.close()
                        }
                    }
                }.onFailure {
                    runCatching { bitmap.recycle() }
                    batchLease.close()
                    deliver(MomoChatOcrResult(emptyMap(), targets.size, targets.size, "本地 OCR 任务已停止"))
                }
            }
        } catch (failure: Exception) {
            if (captureCallbackEntered.compareAndSet(false, true)) {
                batchLease.close()
                onComplete(
                    MomoChatOcrResult(
                        emptyMap(),
                        targets.size,
                        targets.size,
                        "启动系统截图失败：${failure.message?.takeIf { it.isNotBlank() } ?: failure.javaClass.simpleName}"
                    )
                )
            }
        }
    }

    private fun recognizeNext(
        bitmap: Bitmap,
        rootBounds: ScreenRect,
        targets: List<com.freedom.features.aireply.domain.message.ChatMessageOcrTarget>,
        index: Int,
        recognized: LinkedHashMap<Int, String>,
        chineseRecognized: LinkedHashMap<Int, String>,
        latinRecognized: LinkedHashMap<Int, String>,
        qualityByRowIndex: LinkedHashMap<Int, com.freedom.features.aireply.domain.message.MessageContentQuality>,
        confidenceByRowIndex: LinkedHashMap<Int, Float>,
        lineCountByRowIndex: LinkedHashMap<Int, Int>,
        captureDescription: String,
        sampleScreenshotPath: String?,
        reviewBatchId: String?,
        cropPaths: LinkedHashMap<Int, String>,
        failed: Int,
        isCompleted: () -> Boolean,
        onComplete: (MomoChatOcrResult) -> Unit
    ) {
        if (isCompleted()) {
            runCatching { bitmap.recycle() }
            return
        }
        if (closed) {
            runCatching { bitmap.recycle() }
            onComplete(MomoChatOcrResult(emptyMap(), targets.size, targets.size, "本地 OCR 任务已停止"))
            return
        }
        if (index >= targets.size) {
            runCatching { bitmap.recycle() }
            onComplete(
                MomoChatOcrResult(
                    textByRowIndex = recognized.toMap(),
                    attemptedTargetCount = targets.size,
                    failedTargetCount = failed,
                    failureReason = when {
                        recognized.isEmpty() -> lastEngineFailureReason ?: "本地 OCR 未识别到有效消息文字"
                        failed > 0 && lastEngineFailureReason != null -> "部分 OCR 目标失败：${lastEngineFailureReason}"
                        failed > 0 -> "部分 OCR 目标未产生有效文字或文本框"
                        else -> null
                    },
                    chineseTextByRowIndex = chineseRecognized.toMap(),
                    latinTextByRowIndex = latinRecognized.toMap(),
                    qualityByRowIndex = qualityByRowIndex.toMap(),
                    captureDescription = captureDescription,
                    sampleScreenshotPath = sampleScreenshotPath,
                    sampleCropPathsByRowIndex = cropPaths.toMap(),
                    confidenceByRowIndex = confidenceByRowIndex.toMap(),
                    lineCountByRowIndex = lineCountByRowIndex.toMap()
                )
            )
            return
        }

        val target = targets[index]
        val crop = MomoOcrCropGeometry.crop(bitmap, rootBounds, target.bodyBounds, target.bubbleBounds)
        if (crop == null) {
            reviewBatchId?.let { id ->
                if (!ocrReviewStore.saveEntry(
                        batchId = id,
                        rowIndex = target.rowIndex,
                        originalCropPng = null,
                        annotatedCropPng = null,
                        recognizedText = "",
                        confidence = null,
                        lineCount = 0,
                        failureReason = "消息正文区域裁剪失败"
                    )) onLog("OCR_REVIEW_ENTRY_SAVE_FAILED：row=${target.rowIndex};reason=crop_unavailable")
            }
            recognizeNext(
                bitmap, rootBounds, targets, index + 1, recognized, chineseRecognized,
                latinRecognized, qualityByRowIndex, confidenceByRowIndex, lineCountByRowIndex,
                captureDescription, sampleScreenshotPath, reviewBatchId, cropPaths, failed + 1, isCompleted, onComplete
            )
            return
        }

        sampleStore?.saveCropBitmap(target.rowIndex, crop)?.let { cropPaths[target.rowIndex] = it }
        val recognition = runCatching { recognizeCrop(crop) }
            .getOrElse { failure ->
                val detail = failure.message?.takeIf { it.isNotBlank() } ?: "无异常消息"
                lastEngineFailureReason = "${failure.javaClass.simpleName}: $detail"
                CropRecognition("", "", "", null, 0)
            }
        val annotatedCrop = createAnnotatedCrop(crop, recognition.detectionBoxes)
        reviewBatchId?.let { id ->
            val saved = ocrReviewStore.saveEntry(
                batchId = id,
                rowIndex = target.rowIndex,
                originalCropPng = crop.toPngBytes(),
                annotatedCropPng = annotatedCrop?.toPngBytes(),
                recognizedText = recognition.finalText,
                confidence = recognition.meanConfidence,
                lineCount = recognition.lineCount,
                failureReason = if (recognition.finalText.isBlank()) lastEngineFailureReason ?: "未识别到有效文字" else null
            )
            if (!saved) onLog("OCR_REVIEW_ENTRY_SAVE_FAILED：row=${target.rowIndex};reason=write_failed")
        }
        annotatedCrop?.let { runCatching { it.recycle() } }
        val finalText = normalizeRecognizedText(recognition.finalText)
        val modelText = normalizeRecognizedText(recognition.chineseText)
        if (finalText.isNotBlank()) {
            recognized[target.rowIndex] = finalText
            qualityByRowIndex[target.rowIndex] = MomoOcrQualityPolicy.classify(finalText)
        }
        recognition.meanConfidence?.let { confidenceByRowIndex[target.rowIndex] = it }
        if (recognition.lineCount > 0) lineCountByRowIndex[target.rowIndex] = recognition.lineCount
        // Keep the legacy archive fields stable: the multilingual recognizer writes its complete
        // output to the existing primary sample field; the legacy latin-only field stays empty.
        if (modelText.isNotBlank()) chineseRecognized[target.rowIndex] = modelText
        runCatching { crop.recycle() }
        recognizeNext(
            bitmap = bitmap,
            rootBounds = rootBounds,
            targets = targets,
            index = index + 1,
            recognized = recognized,
            chineseRecognized = chineseRecognized,
            latinRecognized = latinRecognized,
            qualityByRowIndex = qualityByRowIndex,
            confidenceByRowIndex = confidenceByRowIndex,
            lineCountByRowIndex = lineCountByRowIndex,
            captureDescription = captureDescription,
            sampleScreenshotPath = sampleScreenshotPath,
            reviewBatchId = reviewBatchId,
            cropPaths = cropPaths,
            failed = failed + if (finalText.isBlank()) 1 else 0,
            isCompleted = isCompleted,
            onComplete = onComplete
        )
    }

    /**
     * Sends one already-cropped message body to the independently-installed OCR plugin.
     * Freedom itself does not load the OCR runtime or model files.
     */
    private fun recognizeCrop(crop: Bitmap): CropRecognition {
        if (closed) return CropRecognition("", "", "", null, 0)
        val result = ocrManager.recognize(crop)
        val colorRegions = MomoOcrVisualSymbolGuard.detectColorRegions(
            width = crop.width,
            height = crop.height
        ) { x, y -> crop.getPixel(x, y) }
        val validConfidences = ArrayList<Float>()
        val lines = result.mapNotNull { item ->
            val text = normalizeRecognizedText(item.text)
            if (text.isBlank() || item.points.isEmpty()) return@mapNotNull null
            val points = item.points
            val left = floor(points.minOf { it.first.toDouble() }).toInt().coerceIn(0, crop.width)
            val top = floor(points.minOf { it.second.toDouble() }).toInt().coerceIn(0, crop.height)
            val right = ceil(points.maxOf { it.first.toDouble() }).toInt().coerceIn(0, crop.width)
            val bottom = ceil(points.maxOf { it.second.toDouble() }).toInt().coerceIn(0, crop.height)
            if (right <= left || bottom <= top) return@mapNotNull null
            if (item.confidence.isFinite() && item.confidence in 0f..1f) {
                validConfidences += item.confidence
            }
            // Current PaddleOCR returns line-level boxes. The visual guard therefore marks a
            // line uncertain when a colored glyph intersects it instead of guessing character
            // positions. The same result contract can be reused by future OCR plugins.
            OcrVisualLine(
                text = text,
                bounds = OcrVisualRect(left, top, right, bottom),
                symbols = emptyList()
            )
        }
        val guardedText = MomoOcrVisualSymbolGuard.reconcile(lines, colorRegions)
        val finalText = normalizeRecognizedText(guardedText)
        val detectionBoxes = result.mapNotNull { item ->
            val text = normalizeRecognizedText(item.text)
            if (text.isBlank() || item.points.isEmpty()) return@mapNotNull null
            val points = item.points
            val left = floor(points.minOf { it.first.toDouble() }).toInt().coerceIn(0, crop.width)
            val top = floor(points.minOf { it.second.toDouble() }).toInt().coerceIn(0, crop.height)
            val right = ceil(points.maxOf { it.first.toDouble() }).toInt().coerceIn(0, crop.width)
            val bottom = ceil(points.maxOf { it.second.toDouble() }).toInt().coerceIn(0, crop.height)
            if (right <= left || bottom <= top) return@mapNotNull null
            OcrDetectionBox(text, left, top, right, bottom)
        }
        return CropRecognition(
            finalText = finalText,
            chineseText = finalText,
            latinText = "",
            meanConfidence = validConfidences.takeIf { it.isNotEmpty() }?.average()?.toFloat(),
            lineCount = lines.size,
            detectionBoxes = detectionBoxes
        )
    }

    private fun createAnnotatedCrop(source: Bitmap, boxes: List<OcrDetectionBox>): Bitmap? {
        val annotated = source.copy(Bitmap.Config.ARGB_8888, true) ?: return null
        val canvas = Canvas(annotated)
        val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(38, 166, 91)
            style = Paint.Style.STROKE
            strokeWidth = (source.width * 0.006f).coerceIn(2f, 6f)
        }
        val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(38, 166, 91)
            style = Paint.Style.FILL
            textSize = (source.width * 0.035f).coerceIn(14f, 28f)
            setShadowLayer(2f, 1f, 1f, Color.WHITE)
        }
        boxes.forEachIndexed { index, box ->
            canvas.drawRect(RectF(box.left.toFloat(), box.top.toFloat(), box.right.toFloat(), box.bottom.toFloat()), outline)
            val labelText = "${index + 1}. ${box.text.take(64)}"
            val textY = (box.top - 4f).coerceAtLeast(label.textSize)
            canvas.drawText(labelText, box.left.toFloat(), textY, label)
        }
        return annotated
    }

    private fun normalizeRecognizedText(value: String): String = value
        .replace('\u00A0', ' ')
        .replace(Regex("[\\t ]+"), " ")
        .replace(Regex(" *\\n *"), "\n")
        .trim()
        .take(MAX_RECOGNIZED_TEXT_LENGTH)

    override fun close() {
        closed = true
        // 这是进程级共享管理器：关闭当前 Momo 采集器只撤销日志订阅并安排空闲释放，
        // 不立即解绑启动预检刚建立、其他采集批次仍可复用的 Binder 连接。
        ocrDiagnosticSubscription.close()
        ocrManager.releaseWhenIdle()
        executor.shutdown()
    }

    private companion object {
        val PROCESS_BATCH_ADMISSION = OcrBatchAdmissionGate()
        const val MAX_TARGETS_PER_BATCH = 20
        const val MAX_RECOGNIZED_TEXT_LENGTH = 4_000
        const val OCR_BATCH_TIMEOUT_MS = 60_000L
    }
}
