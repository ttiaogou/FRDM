package com.freedom.ocr.plugin.runtime

import android.content.Context
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import com.freedom.ocr.plugin.model.OcrModelManager
import com.paddle.ocr.EngineConfig
import com.paddle.ocr.PaddleOCR
import com.paddle.ocr.PaddleOCRConfig
import com.paddle.ocr.util.OpenCVUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.autojs.plugin.paddle.ocr.api.OcrOptions
import org.autojs.plugin.paddle.ocr.api.PaddleOcrOptionExtraKeys

/**
 * Freedom adapter of AutoJs6's PpOcrV5EngineHolder. Retains the proven engine reuse/release
 * pattern while sourcing model files from Freedom's plugin-private, hash-verified model store.
 */
internal class OcrEngineHolder(context: Context) {
    private val appContext = context.applicationContext
    private val lock = Any()

    @Volatile private var cachedKey: EngineKey? = null
    @Volatile private var cachedEngine: PaddleOCR? = null
    @Volatile var isLoaded: Boolean = false
        private set

    fun get(files: OcrModelManager.ModelFiles, options: OcrOptions): PaddleOCR {
        val extras = options.extras ?: Bundle().also { options.extras = it }
        val key = EngineKey(
            modelId = files.modelId,
            modelVersion = files.version,
            threads = options.cpuThreadNum.coerceAtLeast(1),
            detLimitSideLen = extras.getInt(PaddleOcrOptionExtraKeys.DET_LIMIT_SIDE_LEN, 64).coerceAtLeast(1),
            detLimitType = extras.getString(PaddleOcrOptionExtraKeys.DET_LIMIT_TYPE, "min") ?: "min",
            detMaxSideLimit = extras.getInt(PaddleOcrOptionExtraKeys.DET_MAX_SIDE_LIMIT, 0)
                .takeIf { it > 0 } ?: options.detLongSize.takeIf { it > 0 } ?: 4000,
            detThresh = extras.getFloat(PaddleOcrOptionExtraKeys.DET_THRESH, 0.3f),
            detBoxThresh = extras.getFloat(PaddleOcrOptionExtraKeys.DET_BOX_THRESH, 0.6f),
            detUnclipRatio = extras.getFloat(PaddleOcrOptionExtraKeys.DET_UNCLIP_RATIO, 1.5f),
            recScoreThresh = if (options.scoreThreshold >= 0f) options.scoreThreshold else 0f,
            recBatchSize = extras.getInt(PaddleOcrOptionExtraKeys.REC_BATCH_SIZE, 1).coerceAtLeast(1),
        )
        cachedEngine?.let {
            if (cachedKey == key) {
                Log.i(LOG_TAG, "OCR_ENGINE_CACHE_HIT modelId=${files.modelId};version=${files.version};thread=${Thread.currentThread().name}")
                return it
            }
        }
        synchronized(lock) {
            cachedEngine?.let {
                if (cachedKey == key) {
                    Log.i(LOG_TAG, "OCR_ENGINE_CACHE_HIT modelId=${files.modelId};version=${files.version};thread=${Thread.currentThread().name}")
                    return it
                }
            }
            releaseLocked()
            val openCvStartedAt = SystemClock.elapsedRealtime()
            Log.i(LOG_TAG, "OCR_ENGINE_STAGE stage=OPENCV_INIT_START;modelId=${files.modelId};pid=${android.os.Process.myPid()};thread=${Thread.currentThread().name}")
            val openCvReady = try {
                OpenCVUtils.init(appContext)
            } catch (failure: Throwable) {
                Log.e(
                    LOG_TAG,
                    "OCR_ENGINE_STAGE stage=OPENCV_INIT_FAILED;durationMs=${SystemClock.elapsedRealtime() - openCvStartedAt};" +
                        "abi=${android.os.Build.SUPPORTED_ABIS.joinToString(",")};" +
                        "nativeLibraryDir=${appContext.applicationInfo.nativeLibraryDir};" +
                        "type=${failure.javaClass.name};message=${failure.message}",
                    failure,
                )
                throw IllegalStateException(
                    "OCR 插件 OpenCV 初始化失败：${failure.javaClass.simpleName}: ${failure.message}",
                    failure,
                )
            }
            Log.i(LOG_TAG, "OCR_ENGINE_STAGE stage=OPENCV_INIT_END;success=$openCvReady;durationMs=${SystemClock.elapsedRealtime() - openCvStartedAt}")
            check(openCvReady) {
                "OCR 插件中的 OpenCV 运行库初始化失败；abi=${android.os.Build.SUPPORTED_ABIS.joinToString(",")};" +
                    "nativeLibraryDir=${appContext.applicationInfo.nativeLibraryDir}"
            }

            val createStartedAt = SystemClock.elapsedRealtime()
            Log.i(LOG_TAG, "OCR_ENGINE_STAGE stage=PADDLE_CREATE_START;modelId=${files.modelId};version=${files.version};threads=${key.threads}")
            val created = try {
                runBlocking(Dispatchers.IO) {
                    PaddleOCR.create(
                        context = appContext,
                        config = PaddleOCRConfig(
                            detLimitSideLen = key.detLimitSideLen,
                            detLimitType = key.detLimitType,
                            detMaxSideLimit = key.detMaxSideLimit,
                            detThresh = key.detThresh,
                            detBoxThresh = key.detBoxThresh,
                            detUnclipRatio = key.detUnclipRatio,
                            recScoreThresh = key.recScoreThresh,
                            recBatchSize = key.recBatchSize,
                        ),
                        engineConfig = EngineConfig(numThreads = key.threads),
                        detModelAssetPath = files.detectionModel.absolutePath,
                        recModelAssetPath = files.recognitionModel.absolutePath,
                        recConfigAssetPath = files.recognitionConfig.absolutePath,
                    )
                }
            } catch (failure: Throwable) {
                Log.e(LOG_TAG, "OCR_ENGINE_STAGE stage=PADDLE_CREATE_FAILED;durationMs=${SystemClock.elapsedRealtime() - createStartedAt};type=${failure.javaClass.simpleName};message=${failure.message}", failure)
                throw failure
            }
            Log.i(LOG_TAG, "OCR_ENGINE_STAGE stage=PADDLE_CREATE_SUCCESS;durationMs=${SystemClock.elapsedRealtime() - createStartedAt}")
            cachedKey = key
            cachedEngine = created
            isLoaded = true
            return created
        }
    }

    fun release() = synchronized(lock) { releaseLocked() }

    private fun releaseLocked() {
        val old = cachedEngine
        cachedEngine = null
        cachedKey = null
        isLoaded = false
        if (old != null) {
            val startedAt = SystemClock.elapsedRealtime()
            Log.i(LOG_TAG, "OCR_ENGINE_STAGE stage=ENGINE_RELEASE_START;thread=${Thread.currentThread().name}")
            runCatching { runBlocking(Dispatchers.IO) { old.release() } }
            Log.i(LOG_TAG, "OCR_ENGINE_STAGE stage=ENGINE_RELEASE_END;durationMs=${SystemClock.elapsedRealtime() - startedAt}")
        }
    }

    private data class EngineKey(
        val modelId: String,
        val modelVersion: String,
        val threads: Int,
        val detLimitSideLen: Int,
        val detLimitType: String,
        val detMaxSideLimit: Int,
        val detThresh: Float,
        val detBoxThresh: Float,
        val detUnclipRatio: Float,
        val recScoreThresh: Float,
        val recBatchSize: Int,
    )

    companion object {
        private const val LOG_TAG = "FreedomOcrEngine"
    }
}
