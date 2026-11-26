/* Freedom adapter constants for the AutoJs6 OCR plugin wire contract. */
package org.autojs.plugin.paddle.ocr.api

object OcrPluginContract {
    const val ACTION_OCR = "org.autojs.plugin.PADDLE_OCR"
    const val CATEGORY_OCR = "paddle-ocr"
    const val ACTION_WAKE = "com.freedom.ocr.plugin.action.WAKE"
    const val HOST_PERMISSION = "com.freedom.permission.BIND_OCR_PLUGIN"
    const val ENGINE_PADDLE = "paddle-ocr"
    const val EXTRA_QUAD = "quad"
    const val EXTRA_TOTAL_TIME_MS = "totalTimeMs"
    const val EXTRA_DETECTION_TIME_MS = "detectionTimeMs"
    const val EXTRA_RECOGNITION_TIME_MS = "recognitionTimeMs"
    const val EXTRA_COLD_LOAD_TIME_MS = "coldLoadTimeMs"
    const val EXTRA_IMAGE_DECODE_TIME_MS = "freedomImageDecodeTimeMs"
    const val EXTRA_ENGINE_LOCK_WAIT_MS = "freedomEngineLockWaitMs"
    const val EXTRA_MODEL_PREPARE_TIME_MS = "freedomModelPrepareTimeMs"
    const val EXTRA_ENGINE_ACQUIRE_TIME_MS = "freedomEngineAcquireTimeMs"
    const val EXTRA_INFERENCE_WALL_TIME_MS = "freedomInferenceWallTimeMs"
    const val EXTRA_DIAGNOSTIC_REQUEST_ID = "com.freedom.ocr.extra.DIAGNOSTIC_REQUEST_ID"
}
