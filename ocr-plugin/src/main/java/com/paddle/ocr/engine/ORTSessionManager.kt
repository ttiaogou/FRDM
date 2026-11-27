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
        val tensor = try {
            OnnxTensor.createTensor(ortEnv, FloatBuffer.wrap(input), shape)
        } catch (t: Throwable) {
            throw OCRError.InferenceFailed(modelName, t)
        }
        val result = try {
            try {
                session.run(mapOf(inputName to tensor))
            } catch (t: Throwable) {
                throw OCRError.InferenceFailed(modelName, t)
            }
        } finally {
            tensor.close()
        }
        return try {
            try {
                val outputName = session.outputNames.iterator().next()
                val ortValue = result.get(outputName)
                    .orElseThrow { Exception("No output tensor found") }
                val outputTensor = ortValue as? OnnxTensor
                    ?: throw Exception("Output is not an ONNX tensor")
                Pair(copyFloatBuffer(outputTensor.floatBuffer), outputTensor.info.shape)
            } catch (t: Throwable) {
                throw OCRError.InferenceFailed(modelName, t)
            }
        } finally {
            result.close()
        }
    }

    private fun copyFloatBuffer(buffer: FloatBuffer): FloatArray {
        val duplicate = buffer.duplicate()
        duplicate.rewind()
        val output = FloatArray(duplicate.remaining())
        duplicate.get(output)
        return output
    }
}
