package com.paddle.ocr.util

import android.content.Context
import android.os.Build
import android.util.Log
import org.opencv.android.OpenCVLoader
import java.io.File

/**
 * Freedom-local OpenCV loader.
 *
 * Uses the official OpenCV Android AAR loader. The old QuickBird 4.5.3 artifact shipped
 * a native library that referenced the unavailable Bionic symbol __sfp_handle_exceptions.
 * Preserve the original loader failure for host-side diagnostics.
 */
object OpenCVUtils {
    @Volatile
    private var initialized = false

    @Synchronized
    fun init(context: Context): Boolean {
        if (initialized) return true

        val appContext = context.applicationContext
        val nativeLibraryDir = appContext.applicationInfo.nativeLibraryDir
        val nativeDirectory = File(nativeLibraryDir)
        val packagedOpenCvLibraries = nativeDirectory.listFiles()
            ?.filter { it.name.contains("opencv", ignoreCase = true) }
            ?.joinToString(",") { it.name }
            .orEmpty()

        Log.i(
            LOG_TAG,
            "OPENCV_LOAD_START;abi=${Build.SUPPORTED_ABIS.joinToString(",")};" +
                "primaryAbi=${Build.CPU_ABI};nativeLibraryDir=$nativeLibraryDir;" +
                "nativeDirExists=${nativeDirectory.isDirectory};" +
                "packagedOpenCvLibraries=$packagedOpenCvLibraries;" +
                "sourceDir=${appContext.applicationInfo.sourceDir}",
        )

        try {
            val loaded = OpenCVLoader.initLocal()
            check(loaded) { "OpenCVLoader.initLocal() returned false" }
            initialized = true
            Log.i(
                LOG_TAG,
                "OPENCV_LOAD_SUCCESS;loader=OpenCVLoader.initLocal;abi=${Build.SUPPORTED_ABIS.joinToString(",")}",
            )
            return true
        } catch (failure: Throwable) {
            Log.e(
                LOG_TAG,
                "OPENCV_LOAD_FAILED;loader=OpenCVLoader.initLocal;abi=${Build.SUPPORTED_ABIS.joinToString(",")};" +
                    "nativeLibraryDir=$nativeLibraryDir;" +
                    "packagedOpenCvLibraries=$packagedOpenCvLibraries;" +
                    "type=${failure.javaClass.name};message=${failure.message}",
                failure,
            )
            throw IllegalStateException(
                "OpenCVLoader.initLocal() 初始化失败（ABI=${Build.SUPPORTED_ABIS.joinToString(",")}；" +
                    "nativeLibraryDir=$nativeLibraryDir；" +
                    "已打包 OpenCV 库=$packagedOpenCvLibraries）：${failure.message}",
                failure,
            )
        }
    }

    private const val LOG_TAG = "OpenCVUtils"
}
