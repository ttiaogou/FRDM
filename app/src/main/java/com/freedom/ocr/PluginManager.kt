package com.freedom.ocr

import com.freedom.BuildConfig
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import com.freedom.ocr.ipc.IOcrPluginService
import org.json.JSONObject
import java.io.Closeable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Discovers and binds the independently-installed OCR plugin.
 * It deliberately knows nothing about PaddleOCR or any concrete model.
 */
class PluginManager(context: Context) : Closeable {
    private val appContext = context.applicationContext
    private val lock = Any()

    @Volatile
    private var remote: IOcrPluginService? = null

    private var bound = false
    private var connecting = false
    private var connection: ServiceConnection? = null
    private var connectionLatch: CountDownLatch? = null

    fun isPluginInstalled(): Boolean = runCatching { installedPackageInfo() }.isSuccess

    fun installedPluginVersion(): String? = runCatching {
        installedPackageInfo().versionName
    }.getOrNull()

    /** Bind once and reuse the connection. Call from a worker thread, never the main thread. */
    fun connect(): IOcrPluginService {
        remote?.let { return it }
        val latch: CountDownLatch
        synchronized(lock) {
            remote?.let { return it }
            if (!isPluginInstalled()) {
                throw OcrPluginUnavailableException(
                    "尚未安装 Freedom OCR 插件。请先安装独立的 Freedom OCR 插件 APK。"
                )
            }

            if (!connecting && bound && connection != null) {
                // A previous binding was disconnected but Android has not delivered its
                // reconnection callback yet. Reuse that binding and wait for it.
                connecting = true
                connectionLatch = CountDownLatch(1)
            }

            if (!connecting && !bound) {
                connecting = true
                val newLatch = CountDownLatch(1)
                connectionLatch = newLatch
                val newConnection = object : ServiceConnection {
                    override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                        val candidate = IOcrPluginService.Stub.asInterface(binder)
                        synchronized(lock) {
                            remote = candidate
                            connecting = false
                            connectionLatch?.countDown()
                        }
                        if (binder != null) {
                            runCatching {
                                binder.linkToDeath({ handleBinderDeath() }, 0)
                            }
                        }
                    }

                    override fun onServiceDisconnected(name: ComponentName?) {
                        synchronized(lock) {
                            remote = null
                            // The binding is still active; wait for Android to reconnect this
                            // same ServiceConnection instead of creating a duplicate binding.
                            connecting = true
                            connectionLatch = CountDownLatch(1)
                        }
                    }

                    override fun onBindingDied(name: ComponentName?) {
                        handleBinderDeath()
                    }

                    override fun onNullBinding(name: ComponentName?) {
                        handleBinderDeath()
                    }
                }
                connection = newConnection
                val intent = Intent(ACTION_BIND_OCR_PLUGIN).setClassName(
                    PLUGIN_PACKAGE,
                    PLUGIN_SERVICE_CLASS
                )
                val didBind = runCatching {
                    appContext.bindService(intent, newConnection, Context.BIND_AUTO_CREATE)
                }.getOrDefault(false)
                if (!didBind) {
                    connecting = false
                    connection = null
                    connectionLatch = null
                    throw OcrPluginUnavailableException(
                        "Freedom OCR 插件已安装，但系统拒绝绑定其服务。请检查插件版本和安装签名。"
                    )
                }
                bound = true
            }
            latch = connectionLatch ?: throw OcrPluginException("OCR 插件连接状态异常")
        }

        if (!latch.await(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            val staleConnection: ServiceConnection?
            synchronized(lock) {
                connecting = false
                remote = null
                staleConnection = connection
                connection = null
                connectionLatch = null
                bound = false
            }
            staleConnection?.let { runCatching { appContext.unbindService(it) } }
            throw OcrPluginException("连接 Freedom OCR 插件超时，请重新打开应用后重试")
        }

        val connected = remote ?: throw OcrPluginException("Freedom OCR 插件没有提供可用服务")
        validatePluginContract(connected)
        return connected
    }

    private fun validatePluginContract(service: IOcrPluginService) {
        val info = try {
            JSONObject(service.getPluginInfo())
        } catch (failure: Exception) {
            throw OcrPluginException("无法读取 OCR 插件信息：${failure.message ?: failure.javaClass.simpleName}")
        }
        val pluginId = info.optString("pluginId")
        val protocolVersion = info.optInt("protocolVersion", -1)
        if (pluginId != PLUGIN_PACKAGE) {
            throw OcrPluginException("OCR 插件标识不匹配：$pluginId")
        }
        if (protocolVersion != SUPPORTED_PROTOCOL_VERSION) {
            throw OcrPluginException(
                "OCR 插件通信协议不兼容：插件=$protocolVersion，Freedom=$SUPPORTED_PROTOCOL_VERSION。请更新 Freedom 或 OCR 插件。"
            )
        }
        val minHostVersionCode = info.optInt("minHostVersionCode", 1)
        val maxHostVersionCode = info.optInt("maxHostVersionCode", Int.MAX_VALUE)
        if (BuildConfig.VERSION_CODE !in minHostVersionCode..maxHostVersionCode) {
            throw OcrPluginException(
                "当前 OCR 插件不支持这个 Freedom 版本，请更新 OCR 插件或 Freedom 主程序。"
            )
        }
    }

    private fun handleBinderDeath() {
        val oldConnection: ServiceConnection?
        synchronized(lock) {
            remote = null
            connecting = false
            oldConnection = connection
            connection = null
            connectionLatch?.countDown()
            connectionLatch = null
            if (bound) bound = false
        }
        oldConnection?.let { runCatching { appContext.unbindService(it) } }
    }

    override fun close() {
        val oldService: IOcrPluginService?
        val oldConnection: ServiceConnection?
        synchronized(lock) {
            oldService = remote
            oldConnection = connection
            remote = null
            connection = null
            connectionLatch?.countDown()
            connectionLatch = null
            connecting = false
            bound = false
        }
        runCatching { oldService?.releaseEngine() }
        oldConnection?.let { runCatching { appContext.unbindService(it) } }
    }

    private fun packageManager(): PackageManager = appContext.packageManager

    @Suppress("DEPRECATION")
    private fun installedPackageInfo(): PackageInfo {
        return if (Build.VERSION.SDK_INT >= 33) {
            packageManager().getPackageInfo(
                PLUGIN_PACKAGE,
                PackageManager.PackageInfoFlags.of(0L)
            )
        } else {
            packageManager().getPackageInfo(PLUGIN_PACKAGE, 0)
        }
    }

    companion object {
        const val PLUGIN_PACKAGE = "com.freedom.ocr.plugin"
        const val PLUGIN_SERVICE_CLASS = "com.freedom.ocr.plugin.OcrPluginService"
        const val ACTION_BIND_OCR_PLUGIN = "com.freedom.ocr.action.BIND_OCR_PLUGIN"
        const val SUPPORTED_PROTOCOL_VERSION = 1
        private const val CONNECT_TIMEOUT_SECONDS = 12L
    }
}


