package com.freedom.ocr.plugin

import android.app.Service
import android.content.Intent
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import com.freedom.ocr.plugin.model.OcrModelManager
import com.freedom.ocr.plugin.runtime.OcrEngineHolder
import com.freedom.ocr.plugin.runtime.OcrImageFdDecoder
import com.paddle.ocr.model.OCRRunResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.autojs.plugin.common.api.PluginCapabilityKeys
import org.autojs.plugin.common.api.PluginInfo
import org.autojs.plugin.paddle.ocr.api.IOcrPlugin
import org.autojs.plugin.paddle.ocr.api.OcrOptions
import org.autojs.plugin.paddle.ocr.api.OcrPluginContract
import org.autojs.plugin.paddle.ocr.api.OcrResult
import org.autojs.plugin.paddle.ocr.api.PaddleOcrPluginCapabilityKeys
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicLong
import com.paddle.ocr.model.OCRResult as PaddleResult

/**
 * Standalone OCR process. Its exported service speaks the AutoJs6 Paddle OCR AIDL contract;
 * the former Freedom JSON contract remains available as a compatibility action during migration.
 */
class OcrPluginService : Service() {
    private val modelManager by lazy { OcrModelManager(this) }
    private val engineHolder by lazy { OcrEngineHolder(this) }
    private val engineLock = Any()
    private val requestSequence = AtomicLong(0L)
    private val preferences by lazy { getSharedPreferences(PREFERENCES_NAME, MODE_PRIVATE) }

    private val autoJsBinder = object : IOcrPlugin.Stub() {
        override fun getInfo(): PluginInfo = createPluginInfo()

        override fun recognizeText(
            imageFd: ParcelFileDescriptor,
            options: OcrOptions,
        ): MutableList<String> = detect(imageFd, options).map { it.text.orEmpty() }.toMutableList()

        override fun detect(
            imageFd: ParcelFileDescriptor,
            options: OcrOptions,
        ): MutableList<OcrResult> {
            val requestId = options.extras
                ?.getString(OcrPluginContract.EXTRA_DIAGNOSTIC_REQUEST_ID)
                ?.takeIf { it.isNotBlank() }
                ?: nextRequestId("aidl")
            logStage(requestId, "REQUEST_RECEIVED", "cpuThreads=${options.cpuThreadNum};detLongSize=${options.detLongSize}")
            val decodeStartedAt = SystemClock.elapsedRealtime()
            val bitmap = traceStage(
                requestId,
                "IMAGE_DECODE",
                "raw=${options.extras?.getBoolean(org.autojs.plugin.paddle.ocr.api.PaddleOcrOptionExtraKeys.RAW_IMAGE, false) == true}",
            ) { OcrImageFdDecoder.decode(imageFd, options.extras) }
            val timings = mutableMapOf(
                OcrPluginContract.EXTRA_IMAGE_DECODE_TIME_MS to (SystemClock.elapsedRealtime() - decodeStartedAt)
            )
            logStage(requestId, "IMAGE_DECODED", "width=${bitmap.width};height=${bitmap.height};config=${bitmap.config};byteCount=${bitmap.byteCount}")
            try {
                val runResult = recognizeBitmap(bitmap, options, requestId, timings)
                val results = runResult.results.map { item ->
                    item.toPluginResult(runResult).apply {
                        extras?.let { bundle -> timings.forEach { (key, value) -> bundle.putLong(key, value) } }
                    }
                }.toMutableList()
                logStage(requestId, "REQUEST_SUCCESS", "results=${results.size};totalTimeMs=${runResult.totalTimeMs}")
                return results
            } catch (failure: Throwable) {
                logFailure(requestId, "REQUEST", failure)
                throw failure
            } finally {
                if (!bitmap.isRecycled) bitmap.recycle()
                logStage(requestId, "BITMAP_RELEASED")
            }
        }
    }

    /** Compatibility binder retained so already-installed pre-migration Freedom builds can still connect. */
    private val legacyBinder = object : com.freedom.ocr.ipc.IOcrPluginService.Stub() {
        override fun getPluginInfo(): String = JSONObject()
            .put("pluginId", packageName)
            .put("pluginVersion", BuildConfig.VERSION_NAME)
            .put("pluginVersionCode", BuildConfig.VERSION_CODE)
            .put("protocolVersion", LEGACY_PROTOCOL_VERSION)
            .put("minHostVersionCode", 1)
            .put("maxHostVersionCode", Int.MAX_VALUE)
            .put("engine", "PaddleOCR-Android-ONNX")
            .put("supportedModels", JSONArray(modelManager.cachedSupportedModelIds().toList()))
            .toString()

        override fun getStatus(): String = runCatching {
            val selected = selectedModelId()
            val statuses = JSONArray()
            modelManager.cachedSupportedModelIds().forEach { modelId ->
                val state = modelManager.status(modelId, selected)
                statuses.put(
                    JSONObject()
                        .put("modelId", state.modelId)
                        .put("version", state.version ?: JSONObject.NULL)
                        .put("installed", state.installed)
                        .put("selected", state.selected)
                        .put("sizeBytes", state.totalBytes)
                )
            }
            JSONObject()
                .put("ok", true)
                .put("pluginVersion", BuildConfig.VERSION_NAME)
                .put("protocolVersion", LEGACY_PROTOCOL_VERSION)
                .put("engineLoaded", engineHolder.isLoaded)
                .put("selectedModelId", selected ?: JSONObject.NULL)
                .put("models", statuses)
                .toString()
        }.getOrElse { errorPayload(it) }

        override fun ensureModelInstalled(modelId: String): String = runCatching {
            val files = modelManager.ensureInstalled(modelId)
            if (selectedModelId().isNullOrBlank()) {
                preferences.edit().putString(KEY_SELECTED_MODEL, modelId).apply()
            }
            JSONObject()
                .put("ok", true)
                .put("modelId", files.modelId)
                .put("version", files.version)
                .put("sizeBytes", files.detectionModel.length() + files.recognitionModel.length() + files.recognitionConfig.length())
                .toString()
        }.getOrElse { errorPayload(it) }

        override fun selectModel(modelId: String): String = runCatching {
            val status = modelManager.status(modelId, selectedModelId())
            check(status.installed) { "模型尚未安装：$modelId" }
            synchronized(engineLock) {
                if (selectedModelId() != modelId) engineHolder.release()
                preferences.edit().putString(KEY_SELECTED_MODEL, modelId).apply()
            }
            JSONObject().put("ok", true).put("selectedModelId", modelId).toString()
        }.getOrElse { errorPayload(it) }

        override fun recognize(imageFileDescriptor: ParcelFileDescriptor): String = runCatching {
            val requestId = nextRequestId("legacy")
            logStage(requestId, "REQUEST_RECEIVED", "contract=legacy")
            val bitmap = traceStage(requestId, "IMAGE_DECODE") {
                OcrImageFdDecoder.decode(imageFileDescriptor, null)
            }
            try {
                val options = defaultOptions(bitmap)
                val result = recognizeBitmap(bitmap, options, requestId)
                result.toLegacyJson(selectedModelId() ?: DEFAULT_MODEL_ID).toString()
            } finally {
                if (!bitmap.isRecycled) bitmap.recycle()
                logStage(requestId, "BITMAP_RELEASED")
            }
        }.getOrElse { errorPayload(it) }

        override fun releaseEngine() {
            synchronized(engineLock) { engineHolder.release() }
        }
    }

    override fun onBind(intent: Intent?): IBinder =
        if (intent?.action == OcrPluginContract.ACTION_OCR) autoJsBinder else legacyBinder

    override fun onUnbind(intent: Intent?): Boolean {
        synchronized(engineLock) { engineHolder.release() }
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        synchronized(engineLock) { engineHolder.release() }
        super.onDestroy()
    }

    private fun createPluginInfo(): PluginInfo {
        return PluginInfo().apply {
            name = "Paddle OCR (PP-OCRv6 Small)"
            description = "基于 PaddleOCR 与 ONNX Runtime 的独立文字识别插件。模型在插件私有目录按需下载。"
            instruction = "安装并启用后，Freedom 会通过 OCR 插件接口调用该引擎。"
            author = "Freedom / PaddlePaddle"
            collaborators = arrayOf("PaddlePaddle", "AutoJs6 plugin API")
            versionName = BuildConfig.VERSION_NAME
            versionCode = BuildConfig.VERSION_CODE.toLong()
            versionDate = null
            id = PLUGIN_ID
            engine = OcrPluginContract.ENGINE_PADDLE
            variant = "v6-small"
            supportedAbis = Build.SUPPORTED_ABIS
            capabilities = Bundle().apply {
                putLong(PluginCapabilityKeys.REQUIRES_HOST_VERSION, 1L)
                putBoolean(PaddleOcrPluginCapabilityKeys.SUPPORTS_RAW_IMAGE, true)
                putString(PaddleOcrPluginCapabilityKeys.MODEL_PROFILE, "PP-OCRv6 Small")
                putBoolean(PaddleOcrPluginCapabilityKeys.RECOMMENDED_FOR_MOBILE_DEFAULT, true)
                putBoolean(PaddleOcrPluginCapabilityKeys.SUPPORTS_TEXT_DETECTION, true)
                putBoolean(PaddleOcrPluginCapabilityKeys.SUPPORTS_TEXT_RECOGNITION, true)
                putBoolean(PaddleOcrPluginCapabilityKeys.SUPPORTS_TEXTLINE_ORIENTATION_CLASSIFICATION, false)
            }
        }
    }

    private fun recognizeBitmap(
        bitmap: android.graphics.Bitmap,
        options: OcrOptions,
        requestId: String,
        timings: MutableMap<String, Long>? = null,
    ): OCRRunResult {
        val lockWaitStartedAt = SystemClock.elapsedRealtime()
        logStage(requestId, "ENGINE_LOCK_WAIT_START")
        synchronized(engineLock) {
            val lockWaitMs = SystemClock.elapsedRealtime() - lockWaitStartedAt
            timings?.put(OcrPluginContract.EXTRA_ENGINE_LOCK_WAIT_MS, lockWaitMs)
            logStage(requestId, "ENGINE_LOCK_ACQUIRED", "waitMs=$lockWaitMs")
            val modelId = selectedModelId() ?: DEFAULT_MODEL_ID
            val modelStartedAt = SystemClock.elapsedRealtime()
            val files = traceStage(requestId, "MODEL_PREPARE", "modelId=$modelId") {
                modelManager.ensureInstalled(modelId)
            }
            timings?.put(OcrPluginContract.EXTRA_MODEL_PREPARE_TIME_MS, SystemClock.elapsedRealtime() - modelStartedAt)
            val engineStartedAt = SystemClock.elapsedRealtime()
            val engine = traceStage(requestId, "ENGINE_ACQUIRE", "modelId=${files.modelId};version=${files.version}") {
                engineHolder.get(files, options)
            }
            timings?.put(OcrPluginContract.EXTRA_ENGINE_ACQUIRE_TIME_MS, SystemClock.elapsedRealtime() - engineStartedAt)
            val inferenceStartedAt = SystemClock.elapsedRealtime()
            return traceStage(requestId, "INFERENCE", "image=${bitmap.width}x${bitmap.height}") {
                runBlocking(Dispatchers.IO) { engine.recognize(bitmap) }
            }.also {
                timings?.put(OcrPluginContract.EXTRA_INFERENCE_WALL_TIME_MS, SystemClock.elapsedRealtime() - inferenceStartedAt)
            }
        }
    }

    private inline fun <T> traceStage(
        requestId: String,
        stage: String,
        details: String = "",
        block: () -> T,
    ): T {
        val startedAt = SystemClock.elapsedRealtime()
        logStage(requestId, "${stage}_START", details)
        return try {
            block().also {
                logStage(requestId, "${stage}_SUCCESS", "durationMs=${SystemClock.elapsedRealtime() - startedAt};$details")
            }
        } catch (failure: Throwable) {
            logFailure(requestId, stage, failure, SystemClock.elapsedRealtime() - startedAt)
            throw failure
        }
    }

    private fun nextRequestId(source: String): String =
        "$source-${android.os.Process.myPid()}-${requestSequence.incrementAndGet()}"

    private fun logStage(requestId: String, stage: String, details: String = "") {
        Log.i(
            LOG_TAG,
            "OCR_STAGE stage=$stage;requestId=$requestId;pid=${android.os.Process.myPid()};" +
                "thread=${Thread.currentThread().name};$details",
        )
    }

    private fun logFailure(requestId: String, stage: String, failure: Throwable, durationMs: Long? = null) {
        val duration = durationMs?.let { ";durationMs=$it" }.orEmpty()
        Log.e(
            LOG_TAG,
            "OCR_STAGE stage=${stage}_FAILED;requestId=$requestId;pid=${android.os.Process.myPid()};" +
                "thread=${Thread.currentThread().name}$duration;type=${failure.javaClass.simpleName};" +
                "message=${failure.message ?: "无异常消息"}",
            failure,
        )
    }

    private fun defaultOptions(bitmap: android.graphics.Bitmap) = OcrOptions().apply {
        cpuThreadNum = 2
        detLongSize = maxOf(bitmap.width, bitmap.height)
        scoreThreshold = 0f
        extras = Bundle()
    }

    private fun PaddleResult.toPluginResult(runResult: OCRRunResult): OcrResult {
        val xs = box.points.map { it.x }
        val ys = box.points.map { it.y }
        return OcrResult().apply {
            text = this@toPluginResult.text
            confidence = this@toPluginResult.confidence
            bounds = Rect(
                xs.minOrNull()?.toInt() ?: 0,
                ys.minOrNull()?.toInt() ?: 0,
                xs.maxOrNull()?.toInt() ?: 0,
                ys.maxOrNull()?.toInt() ?: 0,
            )
            extras = Bundle().apply {
                putFloatArray(
                    OcrPluginContract.EXTRA_QUAD,
                    this@toPluginResult.box.points.flatMap { point -> listOf(point.x, point.y) }.toFloatArray(),
                )
                putLong(OcrPluginContract.EXTRA_DETECTION_TIME_MS, runResult.detectionTimeMs)
                putLong(OcrPluginContract.EXTRA_RECOGNITION_TIME_MS, runResult.recognitionTimeMs)
                putLong(OcrPluginContract.EXTRA_TOTAL_TIME_MS, runResult.totalTimeMs)
                putLong(OcrPluginContract.EXTRA_COLD_LOAD_TIME_MS, runResult.coldLoadTimeMs)
            }
        }
    }

    private fun OCRRunResult.toLegacyJson(modelId: String): JSONObject {
        val lines = JSONArray()
        results.forEach { item ->
            val points = JSONArray()
            item.box.points.forEach { point ->
                points.put(JSONArray().put(point.x.toDouble()).put(point.y.toDouble()))
            }
            lines.put(
                JSONObject()
                    .put("text", item.text)
                    .put("confidence", item.confidence.toDouble())
                    .put("points", points)
            )
        }
        return JSONObject()
            .put("ok", true)
            .put("modelId", modelId)
            .put("detectionTimeMs", detectionTimeMs)
            .put("recognitionTimeMs", recognitionTimeMs)
            .put("totalTimeMs", totalTimeMs)
            .put("lines", lines)
    }

    private fun selectedModelId(): String? = preferences.getString(KEY_SELECTED_MODEL, null)

    private fun errorPayload(failure: Throwable): String = JSONObject()
        .put("ok", false)
        .put("message", failure.message ?: failure.javaClass.simpleName)
        .put("errorType", failure.javaClass.simpleName)
        .toString()

    companion object {
        private const val LOG_TAG = "FreedomOcrPlugin"
        private const val PLUGIN_ID = "freedom-paddle-ocr-pp-ocrv6-small"
        private const val LEGACY_PROTOCOL_VERSION = 1
        const val DEFAULT_MODEL_ID = "ppocrv6_small"
        private const val PREFERENCES_NAME = "ocr_plugin_settings"
        private const val KEY_SELECTED_MODEL = "selected_model_id"
    }
}
