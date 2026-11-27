// Copyright (c) 2026 PaddlePaddle Authors. All Rights Reserved.
// Licensed under the Apache License, Version 2.0.
package com.paddle.ocr.engine

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.paddle.ocr.EngineConfig
import com.paddle.ocr.model.OCRError
import java.io.File
import java.nio.FloatBuffer
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** PaddleOCR ONNX session manager with support for app-private external model files. */
class ORTSessionManager(
    private val context: Context,
    private val config: EngineConfig,
) {
    private var env: OrtEnvironment? = null
    private var detSession: OrtSession? = null
    private var recSession: OrtSession? = null
    private var detInputName: String = "x"
    private var recInputName: String = "x"

    // Accessed by the synchronous inference owner only. The watchdog holds its own token/options.
    private data class RequestScope(
        val requestId: String,
        val options: OrtSession.RunOptions,
        val deadline: OcrInferenceDeadline,
    )

    private var activeRequest: RequestScope? = null

    internal fun <T> withInferenceDeadline(requestId: String, run: () -> T): T {
        check(activeRequest == null) { "OCR engine already has an inference owner" }
        val options = OrtSession.RunOptions()
        val startedAt = SystemClock.elapsedRealtime()
        val deadline = OcrInferenceDeadline(SystemClock::elapsedRealtime, {
            Log.w("FreedomOcrEngine", "OCR_INFERENCE_DEADLINE requestId=$requestId;phase=TERMINATION_REQUESTED;budgetMs=${OcrInferenceDeadline.HOT_INFERENCE_BUDGET_MS}")
            val termination = runCatching { options.setTerminate(true) }
            Log.w("FreedomOcrEngine", "OCR_INFERENCE_DEADLINE requestId=$requestId;phase=TERMINATION_SIGNAL;accepted=${termination.isSuccess};type=${termination.exceptionOrNull()?.javaClass?.simpleName ?: "NONE"}")
        })
        val scope = RequestScope(requestId, options, deadline)
        activeRequest = scope
        try {
            val watchdog = DEADLINE_WATCHDOG.schedule(
                { deadline.requestTerminationIfDue() },
                OcrInferenceDeadline.HOT_INFERENCE_BUDGET_MS,
                TimeUnit.MILLISECONDS,
            )
            try {
                val result = run()
                // A late success must never publish even if native code ignored termination.
                deadline.complete()
                return result
            } catch (failure: Throwable) {
                // Preserve a typed timeout rather than reporting it as an ordinary retryable error.
                deadline.checkActive()
                throw failure
            } finally {
                // This executes only after the synchronous pipeline actually exits.
                deadline.retire()
                watchdog.cancel(false)
            }
        } finally {
            deadline.retire()
            activeRequest = null
            options.close()
            Log.i("FreedomOcrEngine", "OCR_INFERENCE_DEADLINE requestId=$requestId;phase=REQUEST_EXITED;durationMs=${SystemClock.elapsedRealtime() - startedAt}")
        }
    }

    internal fun checkInferenceDeadline() {
        checkNotNull(activeRequest) { "OCR inference requires a request deadline" }.deadline.checkActive()
    }
    var coldLoadTimeMs: Long = 0
        private set

    fun loadModels(detAssetPath: String, recAssetPath: String) {
        val loadStart = SystemClock.elapsedRealtime()
        env = OrtEnvironment.getEnvironment()
        val opts = OrtSession.SessionOptions().apply {
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            setIntraOpNumThreads(config.numThreads)
        }
        try {
            val ortEnv = env ?: throw OCRError.ModelLoadFailed("OCR", Exception("Environment not initialized"))
            val detBytes = timedLoadStage("DETECTION_FILE_READ") { readModel(detAssetPath) }
            val recBytes = timedLoadStage("RECOGNITION_FILE_READ") { readModel(recAssetPath) }
            try {
                detSession = timedLoadStage("DETECTION_SESSION_CREATE") { ortEnv.createSession(detBytes, opts) }
            } catch (t: Throwable) {
                throw OCRError.ModelLoadFailed("detection", t)
            }
            try {
                recSession = timedLoadStage("RECOGNITION_SESSION_CREATE") { ortEnv.createSession(recBytes, opts) }
            } catch (t: Throwable) {
                detSession?.close()
                detSession = null
                throw OCRError.ModelLoadFailed("recognition", t)
            }
            detInputName = try {
                detSession!!.inputNames.iterator().next()
            } catch (t: Throwable) {
                throw OCRError.ModelLoadFailed("detection", t)
            }
            recInputName = try {
                recSession!!.inputNames.iterator().next()
            } catch (t: Throwable) {
                throw OCRError.ModelLoadFailed("recognition", t)
            }
            coldLoadTimeMs = SystemClock.elapsedRealtime() - loadStart
        } finally {
            opts.close()
        }
    }

    private inline fun <T> timedLoadStage(stage: String, action: () -> T): T {
        val started = SystemClock.elapsedRealtime()
        return try {
            action().also {
                Log.i("FreedomOcrEngine", "OCR_MODEL_LOAD stage=$stage;success=true;durationMs=${SystemClock.elapsedRealtime() - started}")
            }
        } catch (failure: Throwable) {
            Log.w("FreedomOcrEngine", "OCR_MODEL_LOAD stage=$stage;success=false;durationMs=${SystemClock.elapsedRealtime() - started};type=${failure.javaClass.simpleName}")
            throw failure
        }
    }

    fun runDetection(input: FloatArray, shape: LongArray): Pair<FloatArray, LongArray> {
        val session = detSession
            ?: throw OCRError.ModelLoadFailed("detection", Exception("Session not initialized"))
        val ortEnv = env
            ?: throw OCRError.ModelLoadFailed("detection", Exception("Environment not initialized"))
        return runSession(ortEnv, session, detInputName, input, shape, "detection")
    }

    fun runRecognition(input: FloatArray, shape: LongArray): Pair<FloatArray, LongArray> {
        val session = recSession
            ?: throw OCRError.ModelLoadFailed("recognition", Exception("Session not initialized"))
        val ortEnv = env
            ?: throw OCRError.ModelLoadFailed("recognition", Exception("Environment not initialized"))
        return runSession(ortEnv, session, recInputName, input, shape, "recognition")
    }

    fun release() {
        try {
            detSession?.close()
        } finally {
            detSession = null
            try {
                recSession?.close()
            } finally {
                recSession = null
                env = null
            }
        }
    }

    /** Prefer an absolute file path; retain asset lookup for bundled SDK/demo compatibility. */
    private fun readModel(path: String): ByteArray {
        return try {
            val file = File(path)
            if (file.isAbsolute && file.isFile) file.readBytes()
            else context.assets.open(path).use { it.readBytes() }
        } catch (t: Throwable) {
            throw OCRError.ModelNotFound(path, t)
        }
    }

    private fun runSession(
        ortEnv: OrtEnvironment,
        session: OrtSession,
        inputName: String,
        input: FloatArray,
        shape: LongArray,
        modelName: String,
    ): Pair<FloatArray, LongArray> {
        val scope = checkNotNull(activeRequest) { "Native inference requires a request deadline" }
        scope.deadline.checkActive()
        val tensor = try {
            OnnxTensor.createTensor(ortEnv, FloatBuffer.wrap(input), shape)
        } catch (t: Throwable) {
            throw OCRError.InferenceFailed(modelName, t)
        }
        val result = try {
            try {
                scope.deadline.checkActive()
                val nativeStartedAt = SystemClock.elapsedRealtime()
                Log.i("FreedomOcrEngine", "OCR_NATIVE_RUN requestId=${scope.requestId};model=$modelName;phase=START")
                try {
                    session.run(mapOf(inputName to tensor), scope.options)
                } finally {
                    Log.i("FreedomOcrEngine", "OCR_NATIVE_RUN requestId=${scope.requestId};model=$modelName;phase=EXITED;durationMs=${SystemClock.elapsedRealtime() - nativeStartedAt}")
                }
            } catch (t: Throwable) {
                scope.deadline.checkActive()
                throw OCRError.InferenceFailed(modelName, t)
            }
        } finally {
            tensor.close()
        }
        return try {
            try {
                scope.deadline.checkActive()
                val outputName = session.outputNames.iterator().next()
                val ortValue = result.get(outputName)
                    .orElseThrow { Exception("No output tensor found") }
                val outputTensor = ortValue as? OnnxTensor
                    ?: throw Exception("Output is not an ONNX tensor")
                Pair(copyFloatBuffer(outputTensor.floatBuffer), outputTensor.info.shape)
                    .also { scope.deadline.checkActive() }
            } catch (t: Throwable) {
                scope.deadline.checkActive()
                throw OCRError.InferenceFailed(modelName, t)
            }
        } finally {
            result.close()
        }
    }

    companion object {
        // One lightweight watchdog, not another inference worker; cancelled tasks are removed.
        private val DEADLINE_WATCHDOG = ScheduledThreadPoolExecutor(1) { runnable ->
            Thread(runnable, "Freedom-OCR-Deadline").apply { isDaemon = true }
        }.apply { removeOnCancelPolicy = true }
    }

    private fun copyFloatBuffer(buffer: FloatBuffer): FloatArray {
        val duplicate = buffer.duplicate()
        duplicate.rewind()
        val output = FloatArray(duplicate.remaining())
        duplicate.get(output)
        return output
    }
}
