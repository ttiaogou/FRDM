package com.freedom.ocr

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.DeadObjectException
import android.os.IBinder
import android.os.Looper
import android.os.RemoteException
import android.os.SystemClock
import com.freedom.BuildConfig
import org.autojs.plugin.common.api.PluginCapabilityKeys
import org.autojs.plugin.common.api.PluginInfo
import org.autojs.plugin.paddle.ocr.api.IOcrPlugin
import org.autojs.plugin.paddle.ocr.api.OcrPluginContract
import java.io.Closeable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Installed OCR service discovered through the AutoJs6-compatible action/category contract. */
data class InstalledOcrPlugin(
    val serviceInfo: ServiceInfo,
    val info: PluginInfo,
    val enabled: Boolean,
) {
    val packageName: String get() = serviceInfo.packageName
    val versionName: String get() = info.versionName
}

data class OcrPluginDiscoveryReport(
    val plugins: List<InstalledOcrPlugin>,
    val attempts: Int,
    val serviceCandidateCount: Int,
    val eligibleServiceCount: Int,
    val queryCompleted: Boolean,
    val failures: List<String>,
    val failureKind: OcrPluginFailureKind? = null,
) {
    fun diagnosticMessage(): String = when {
        plugins.any { it.enabled } -> "已验证 ${plugins.count { it.enabled }} 个已启用 OCR 插件服务（扫描 ${attempts} 次）"
        plugins.isNotEmpty() -> "已发现 ${plugins.size} 个 OCR 插件服务，但在 Freedom 插件设置中均处于禁用状态"
        failureKind == OcrPluginFailureKind.PLUGIN_NOT_INSTALLED -> "未安装 OCR 插件 APK"
        failureKind == OcrPluginFailureKind.PLUGIN_DISABLED -> "已安装 OCR 插件，但插件或服务处于禁用状态"
        failureKind == OcrPluginFailureKind.PERMISSION_DENIED -> "OCR 插件绑定权限不匹配或未授予"
        failureKind == OcrPluginFailureKind.PROTOCOL_INCOMPATIBLE -> "OCR 插件通信协议或主程序版本不兼容"
        failureKind == OcrPluginFailureKind.SERVICE_NOT_DISCOVERED -> "OCR 插件 APK 已安装，但系统未发现已注册的 OCR 服务"
        !queryCompleted -> "OCR 服务查询未完成：${failures.firstOrNull() ?: "未知查询错误"}"
        serviceCandidateCount == 0 -> "系统暂未发现已注册的 OCR 服务；这不等同于确认 APK 未安装"
        else -> "发现 $serviceCandidateCount 个 OCR 服务候选，但连接或元数据验证未成功：${failures.firstOrNull() ?: "未知错误"}"
    }
}

internal enum class OcrPluginHostStage {
    DISCOVERY_STARTED,
    CANDIDATES_FOUND,
    BIND_STARTED,
    BIND_REJECTED,
    BIND_CONNECTED,
    PLUGIN_INFO_VERIFIED,
    WAKE_RESULT,
}

internal data class OcrPluginHostEvent(
    val stage: OcrPluginHostStage,
    val attempt: Int,
    val packageName: String? = null,
    val componentName: String? = null,
    val detail: String = "",
)

/**
 * Freedom adapter for the AutoJs6 OCR plugin host.
 *
 * The host queries the public OCR action/category, binds explicitly to the resolved component,
 * verifies PluginInfo on the live Binder connection and transfers ownership of that connection
 * to OcrManager. A preflight and the first real OCR request must share the same OcrManager instance.
 */
class OcrPluginHost(context: Context) {
    private val appContext = context.applicationContext
    private val settings = com.freedom.data.OcrPluginSettingsStore(appContext)

    fun discoverInstalled(): List<InstalledOcrPlugin> = discoverInstalledWithDiagnostics().plugins

    fun discoverInstalledWithDiagnostics(
        maxAttempts: Int = DEFAULT_DISCOVERY_ATTEMPTS,
        onDiagnostic: (String) -> Unit = {},
    ): OcrPluginDiscoveryReport {
        require(maxAttempts in 1..5)
        val failures = LinkedHashSet<String>()
        var latestCandidateCount = 0
        var maxEligibleServiceCount = 0
        var queryCompleted = false
        var attemptsCompleted = 0
        var failureKind: OcrPluginFailureKind? = null

        for (attempt in 1..maxAttempts) {
            attemptsCompleted = attempt
            val services = try {
                queryOcrServices().also { queryCompleted = true }
            } catch (error: Exception) {
                failureKind = OcrPluginFailureKind.UNKNOWN
                failures += "服务目录查询失败：${error.message ?: error.javaClass.simpleName}"
                emptyList()
            }
            latestCandidateCount = maxOf(latestCandidateCount, services.size)
            maxEligibleServiceCount = maxOf(
                maxEligibleServiceCount,
                services.count { serviceIneligibility(it) == null },
            )
            onDiagnostic("OCR_DISCOVERY attempt=$attempt candidates=${services.size} eligible=${services.count { serviceIneligibility(it) == null }}")

            val verified = ArrayList<InstalledOcrPlugin>()
            var retryableFailure = services.isEmpty()
            for (candidate in services) {
                val serviceInfo = refreshServiceInfo(candidate, onDiagnostic)
                if (serviceInfo == null) {
                    failureKind = OcrPluginFailureKind.SERVICE_NOT_DISCOVERED
                    failures += "${candidate.packageName}/${candidate.name}：发现后无法重新读取 ServiceInfo，可能已卸载或组件已更新"
                    retryableFailure = true
                    continue
                }
                val issue = serviceIneligibility(serviceInfo)
                if (issue != null) {
                    failureKind = issue.kind
                    failures += "${serviceInfo.packageName}/${serviceInfo.name}：${issue.message}"
                    continue
                }
                try {
                    OcrPluginBinding(
                        appContext,
                        serviceInfo,
                        connectTimeoutSeconds = DISCOVERY_CONNECT_TIMEOUT_SECONDS,
                        onDiagnostic = onDiagnostic,
                    ).use { binding ->
                        val info = binding.service.getInfo()
                        if (!isHostVersionCompatible(info)) {
                            failureKind = OcrPluginFailureKind.PROTOCOL_INCOMPATIBLE
                            failures += "${serviceInfo.packageName}/${serviceInfo.name}：插件要求的 Freedom 主程序版本过高"
                        } else {
                            verified += InstalledOcrPlugin(
                                serviceInfo = serviceInfo,
                                info = info,
                                enabled = isEnabled(serviceInfo.packageName),
                            )
                        }
                    }
                } catch (error: Exception) {
                    val kind = error.toOcrPluginFailureKind()
                    failureKind = kind
                    retryableFailure = retryableFailure || kind.isRetryable()
                    failures += "${serviceInfo.packageName}/${serviceInfo.name}：服务连接/信息验证失败[$kind]（${error.message ?: error.javaClass.simpleName}）"
                }
            }

            if (verified.isNotEmpty()) {
                return OcrPluginDiscoveryReport(
                    plugins = verified.distinctBy { it.packageName },
                    attempts = attemptsCompleted,
                    serviceCandidateCount = latestCandidateCount,
                    eligibleServiceCount = maxEligibleServiceCount,
                    queryCompleted = queryCompleted,
                    failures = failures.toList(),
                    failureKind = null,
                )
            }

            if (services.isEmpty() && queryCompleted) {
                failureKind = if (installedVersionCode(PLUGIN_PACKAGE) == null) {
                    OcrPluginFailureKind.PLUGIN_NOT_INSTALLED
                } else {
                    OcrPluginFailureKind.SERVICE_NOT_DISCOVERED
                }
            }
            if (attempt < maxAttempts && retryableFailure) {
                val wakeResult = wakePlugin(PLUGIN_PACKAGE)
                failures += "插件唤醒结果：$wakeResult"
                onDiagnostic("OCR_WAKE attempt=$attempt package=$PLUGIN_PACKAGE result=$wakeResult")
                SystemClock.sleep(DISCOVERY_RETRY_DELAY_MS * attempt)
            } else if (!retryableFailure) {
                break
            }
        }

        if (failureKind == null) {
            failureKind = when {
                latestCandidateCount == 0 && installedVersionCode(PLUGIN_PACKAGE) == null -> OcrPluginFailureKind.PLUGIN_NOT_INSTALLED
                latestCandidateCount == 0 -> OcrPluginFailureKind.SERVICE_NOT_DISCOVERED
                failures.any { it.contains("禁用") } -> OcrPluginFailureKind.PLUGIN_DISABLED
                failures.any { it.contains("权限") || it.contains("签名") } -> OcrPluginFailureKind.PERMISSION_DENIED
                else -> OcrPluginFailureKind.UNKNOWN
            }
        }
        return OcrPluginDiscoveryReport(
            plugins = emptyList(),
            attempts = attemptsCompleted,
            serviceCandidateCount = latestCandidateCount,
            eligibleServiceCount = maxEligibleServiceCount,
            queryCompleted = queryCompleted,
            failures = failures.toList(),
            failureKind = failureKind,
        )
    }

    fun setEnabled(packageName: String, enabled: Boolean) {
        settings.setEnabled(packageName, enabled)
    }

    fun isEnabled(packageName: String): Boolean = settings.isEnabled(packageName)

    /** Binds once, verifies metadata and returns the still-open Binder connection to the caller. */
    internal fun selectDefault(
        onEvent: (OcrPluginHostEvent) -> Unit = {},
        onDiagnostic: (String) -> Unit = {},
    ): BoundOcrPlugin {
        val failures = LinkedHashSet<String>()
        val wakePackages = LinkedHashSet<String>()
        var strongestFailure = OcrPluginFailureKind.SERVICE_NOT_DISCOVERED
        var sawEnabledCandidate = false
        var sawDisabledCandidate = false
        var sawCompatibleEngine = false

        for (attempt in 1..SELECT_DEFAULT_ATTEMPTS) {
            onEvent(OcrPluginHostEvent(OcrPluginHostStage.DISCOVERY_STARTED, attempt))
            var queryFailed = false
            val services = try {
                queryOcrServices()
            } catch (error: Exception) {
                queryFailed = true
                strongestFailure = error.toOcrPluginFailureKind()
                val detail = "服务目录查询失败[${strongestFailure}]：${error.message ?: error.javaClass.simpleName}"
                failures += detail
                onDiagnostic("OCR_SERVICE_QUERY_FAILED attempt=$attempt type=${error.javaClass.simpleName};message=${error.message ?: "无异常消息"}")
                emptyList()
            }
            val uniqueServices = services.distinctBy { ComponentName(it.packageName, it.name).flattenToString() }
            onEvent(
                OcrPluginHostEvent(
                    OcrPluginHostStage.CANDIDATES_FOUND,
                    attempt,
                    detail = "candidateCount=${uniqueServices.size};components=${uniqueServices.joinToString(",") { "${it.packageName}/${it.name}" }}",
                )
            )
            onDiagnostic(
                "OCR_SERVICE_CANDIDATES attempt=$attempt count=${uniqueServices.size};" +
                    uniqueServices.joinToString("|") { "${it.packageName}/${it.name}" }
            )

            val connectedCandidates = ArrayList<Pair<InstalledOcrPlugin, OcrPluginBinding>>()
            for (candidate in uniqueServices) {
                val serviceInfo = refreshServiceInfo(candidate, onDiagnostic)
                if (serviceInfo == null) {
                    strongestFailure = OcrPluginFailureKind.SERVICE_NOT_DISCOVERED
                    val detail = "${candidate.packageName}/${candidate.name}：组件在绑定前已不存在或不可见"
                    failures += detail
                    onDiagnostic("OCR_SERVICE_CANDIDATE_REJECTED kind=SERVICE_NOT_DISCOVERED detail=$detail")
                    wakePackages += candidate.packageName
                    continue
                }
                val issue = serviceIneligibility(serviceInfo)
                if (issue != null) {
                    strongestFailure = issue.kind
                    val detail = "${serviceInfo.packageName}/${serviceInfo.name}：${issue.message}"
                    failures += detail
                    onDiagnostic("OCR_SERVICE_CANDIDATE_REJECTED kind=${issue.kind} detail=$detail")
                    continue
                }
                if (!isEnabled(serviceInfo.packageName)) {
                    sawDisabledCandidate = true
                    strongestFailure = OcrPluginFailureKind.PLUGIN_DISABLED
                    val detail = "${serviceInfo.packageName}/${serviceInfo.name}：已安装但在 Freedom 插件设置中处于禁用状态"
                    failures += detail
                    onDiagnostic("OCR_SERVICE_CANDIDATE_REJECTED kind=PLUGIN_DISABLED detail=$detail")
                    continue
                }
                sawEnabledCandidate = true

                onEvent(
                    OcrPluginHostEvent(
                        OcrPluginHostStage.BIND_STARTED,
                        attempt,
                        serviceInfo.packageName,
                        ComponentName(serviceInfo.packageName, serviceInfo.name).flattenToShortString(),
                    )
                )
                var binding: OcrPluginBinding? = null
                try {
                    binding = OcrPluginBinding(
                        appContext,
                        serviceInfo,
                        connectTimeoutSeconds = DISCOVERY_CONNECT_TIMEOUT_SECONDS,
                        onDiagnostic = onDiagnostic,
                    )
                    onEvent(
                        OcrPluginHostEvent(
                            OcrPluginHostStage.BIND_CONNECTED,
                            attempt,
                            serviceInfo.packageName,
                            ComponentName(serviceInfo.packageName, serviceInfo.name).flattenToShortString(),
                        )
                    )
                    val info = binding.service.getInfo()
                    if (!isHostVersionCompatible(info)) {
                        strongestFailure = OcrPluginFailureKind.PROTOCOL_INCOMPATIBLE
                        failures += "${serviceInfo.packageName}/${serviceInfo.name}：插件要求的 Freedom 主程序版本过高"
                        binding.close()
                        binding = null
                        onDiagnostic("OCR_SERVICE_CANDIDATE_REJECTED kind=PROTOCOL_INCOMPATIBLE component=${serviceInfo.packageName}/${serviceInfo.name};reason=HOST_VERSION")
                        continue
                    }
                    if (info.engine !in SUPPORTED_ENGINES) {
                        strongestFailure = OcrPluginFailureKind.PROTOCOL_INCOMPATIBLE
                        failures += "${serviceInfo.packageName}/${serviceInfo.name}：不支持的 OCR 引擎 ${info.engine}"
                        binding.close()
                        binding = null
                        onDiagnostic("OCR_SERVICE_CANDIDATE_REJECTED kind=PROTOCOL_INCOMPATIBLE component=${serviceInfo.packageName}/${serviceInfo.name};reason=UNSUPPORTED_ENGINE;engine=${info.engine}")
                        continue
                    }
                    sawCompatibleEngine = true
                    val descriptor = InstalledOcrPlugin(serviceInfo, info, enabled = true)
                    connectedCandidates += descriptor to binding
                    binding = null // ownership moves to connectedCandidates
                } catch (error: Exception) {
                    runCatching { binding?.close() }
                    val kind = error.toOcrPluginFailureKind()
                    strongestFailure = kind
                    val detail = "${error.message ?: error.javaClass.simpleName};type=${error.javaClass.simpleName}"
                    failures += "${serviceInfo.packageName}/${serviceInfo.name}[$kind]：$detail"
                    onEvent(
                        OcrPluginHostEvent(
                            OcrPluginHostStage.BIND_REJECTED,
                            attempt,
                            serviceInfo.packageName,
                            ComponentName(serviceInfo.packageName, serviceInfo.name).flattenToShortString(),
                            "$kind：$detail",
                        )
                    )
                    if (kind.isRetryable()) wakePackages += serviceInfo.packageName
                }
            }

            val selected = OcrPluginSelection.selectDefault(
                candidates = connectedCandidates.map { it.first },
                pluginInfoOf = { it.info },
                packageNameOf = { it.packageName },
            )
            if (selected != null) {
                val selectedComponent = ComponentName(
                    selected.serviceInfo.packageName,
                    selected.serviceInfo.name,
                ).flattenToString()
                val selectedBinding = connectedCandidates.first {
                    ComponentName(it.first.serviceInfo.packageName, it.first.serviceInfo.name).flattenToString() == selectedComponent
                }.second
                connectedCandidates.filter { it.second !== selectedBinding }.forEach { it.second.close() }
                onEvent(
                    OcrPluginHostEvent(
                        OcrPluginHostStage.PLUGIN_INFO_VERIFIED,
                        attempt,
                        selected.packageName,
                        ComponentName(selected.packageName, selected.serviceInfo.name).flattenToShortString(),
                        "engine=${selected.info.engine};version=${selected.versionName}",
                    )
                )
                onDiagnostic("OCR_BIND_SELECTED package=${selected.packageName};component=${selected.serviceInfo.name};engine=${selected.info.engine};version=${selected.versionName}")
                return BoundOcrPlugin(selected, selectedBinding)
            }
            connectedCandidates.forEach { it.second.close() }

            if (uniqueServices.isEmpty() && !queryFailed) {
                strongestFailure = if (installedVersionCode(PLUGIN_PACKAGE) == null) {
                    OcrPluginFailureKind.PLUGIN_NOT_INSTALLED
                } else {
                    wakePackages += PLUGIN_PACKAGE
                    OcrPluginFailureKind.SERVICE_NOT_DISCOVERED
                }
            }

            if (attempt < SELECT_DEFAULT_ATTEMPTS && wakePackages.isNotEmpty()) {
                wakePackages.toList().forEach { packageName ->
                    val wake = wakePlugin(packageName)
                    onEvent(OcrPluginHostEvent(OcrPluginHostStage.WAKE_RESULT, attempt, packageName, detail = wake))
                }
                wakePackages.clear()
                SystemClock.sleep(DISCOVERY_RETRY_DELAY_MS * attempt)
            } else if (attempt < SELECT_DEFAULT_ATTEMPTS && uniqueServices.isEmpty()) {
                SystemClock.sleep(DISCOVERY_RETRY_DELAY_MS * attempt)
            }
        }

        val finalKind = when {
            !sawEnabledCandidate && sawDisabledCandidate -> OcrPluginFailureKind.PLUGIN_DISABLED
            sawEnabledCandidate && !sawCompatibleEngine && strongestFailure == OcrPluginFailureKind.PROTOCOL_INCOMPATIBLE -> OcrPluginFailureKind.PROTOCOL_INCOMPATIBLE
            else -> strongestFailure
        }
        val detail = failures.take(6).joinToString("；")
        throw OcrPluginUnavailableException(
            when (finalKind) {
                OcrPluginFailureKind.PLUGIN_NOT_INSTALLED -> "未安装 OCR 插件 APK。$detail"
                OcrPluginFailureKind.SERVICE_NOT_DISCOVERED -> "OCR 插件 APK 已安装，但没有发现可绑定的 OCR Service。$detail"
                OcrPluginFailureKind.PLUGIN_DISABLED -> "已发现 OCR 插件，但插件处于禁用状态。请在 OCR 插件管理中启用。$detail"
                OcrPluginFailureKind.PERMISSION_DENIED -> "OCR 插件绑定权限被拒绝。请检查 Freedom 与插件 APK 的签名/权限配置。$detail"
                OcrPluginFailureKind.PROTOCOL_INCOMPATIBLE -> "OCR 插件通信协议或主程序版本不兼容。$detail"
                else -> "OCR 插件服务连接失败[$finalKind]。${detail.ifBlank { "没有可用的服务连接" }}"
            },
            failureKind = finalKind,
        )
    }

    fun installedVersionCode(packageName: String): Long? = runCatching {
        @Suppress("DEPRECATION")
        val info = if (Build.VERSION.SDK_INT >= 33) {
            appContext.packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0L))
        } else {
            appContext.packageManager.getPackageInfo(packageName, 0)
        }
        if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }
    }.getOrNull()

    private fun queryOcrServices(): List<ServiceInfo> {
        val intent = Intent(OcrPluginContract.ACTION_OCR).addCategory(OcrPluginContract.CATEGORY_OCR)
        val packageManager = appContext.packageManager
        val matches = if (Build.VERSION.SDK_INT >= 33) {
            packageManager.queryIntentServices(
                intent,
                PackageManager.ResolveInfoFlags.of(PackageManager.GET_RESOLVED_FILTER.toLong()),
            )
        } else {
            @Suppress("DEPRECATION")
            packageManager.queryIntentServices(intent, PackageManager.GET_RESOLVED_FILTER)
        }
        return matches.mapNotNull { it.serviceInfo }
            .distinctBy { ComponentName(it.packageName, it.name).flattenToString() }
    }

    private fun refreshServiceInfo(
        candidate: ServiceInfo,
        onDiagnostic: (String) -> Unit = {},
    ): ServiceInfo? {
        val component = ComponentName(candidate.packageName, candidate.name)
        return try {
            if (Build.VERSION.SDK_INT >= 33) {
                appContext.packageManager.getServiceInfo(component, PackageManager.ComponentInfoFlags.of(0L))
            } else {
                @Suppress("DEPRECATION")
                appContext.packageManager.getServiceInfo(component, 0)
            }
        } catch (error: Exception) {
            onDiagnostic(
                "OCR_SERVICE_INFO_REFRESH_FAILED component=${component.flattenToShortString()};" +
                    "type=${error.javaClass.simpleName};message=${error.message ?: "无异常消息"}"
            )
            null
        }
    }

    private data class OcrServiceIssue(val kind: OcrPluginFailureKind, val message: String)

    private fun serviceIneligibility(serviceInfo: ServiceInfo): OcrServiceIssue? {
        if (!serviceInfo.exported) return OcrServiceIssue(OcrPluginFailureKind.PERMISSION_DENIED, "服务未导出")
        if (!serviceInfo.enabled || !serviceInfo.applicationInfo.enabled) {
            return OcrServiceIssue(OcrPluginFailureKind.PLUGIN_DISABLED, "服务或插件包已禁用")
        }
        if (serviceInfo.permission != OcrPluginContract.HOST_PERMISSION) {
            return OcrServiceIssue(OcrPluginFailureKind.PERMISSION_DENIED, "服务签名权限不匹配：${serviceInfo.permission}")
        }
        val granted = runCatching {
            appContext.packageManager.checkPermission(
                OcrPluginContract.HOST_PERMISSION,
                appContext.packageName,
            ) == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
        if (!granted) {
            return OcrServiceIssue(OcrPluginFailureKind.PERMISSION_DENIED, "Freedom 未获得 OCR 插件绑定权限")
        }
        return null
    }

    private fun wakePlugin(packageName: String): String {
        val componentClass = "$packageName.OcrPluginWakeActivity"
        val intent = Intent(OcrPluginContract.ACTION_WAKE)
            .addCategory(Intent.CATEGORY_DEFAULT)
            .setClassName(packageName, componentClass)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            appContext.startActivity(intent)
            "WAKE_ACTIVITY_START_REQUESTED component=$componentClass"
        } catch (error: Exception) {
            "WAKE_ACTIVITY_START_FAILED component=$componentClass type=${error.javaClass.simpleName} message=${error.message ?: "无异常消息"}"
        }
    }

    private fun isHostVersionCompatible(info: PluginInfo): Boolean {
        val required = when (val value = info.capabilities?.get(PluginCapabilityKeys.REQUIRES_HOST_VERSION)) {
            is Number -> value.toLong()
            else -> 0L
        }
        return required <= BuildConfig.VERSION_CODE.toLong()
    }

    companion object {
        private const val PLUGIN_PACKAGE = "com.freedom.ocr.plugin"
        private const val DEFAULT_DISCOVERY_ATTEMPTS = 3
        private const val SELECT_DEFAULT_ATTEMPTS = 2
        private const val DISCOVERY_CONNECT_TIMEOUT_SECONDS = 8L
        private const val DISCOVERY_RETRY_DELAY_MS = 400L
        private val SUPPORTED_ENGINES = setOf("paddle-ocr", "rapid-ocr")
    }
}


private fun Exception.toOcrPluginFailureKind(): OcrPluginFailureKind = when (this) {
    is OcrPluginException -> failureKind
    is DeadObjectException -> OcrPluginFailureKind.BINDER_DIED
    is RemoteException -> OcrPluginFailureKind.REMOTE_CALL_FAILED
    is SecurityException -> OcrPluginFailureKind.PERMISSION_DENIED
    else -> OcrPluginFailureKind.UNKNOWN
}

/** A live host-to-plugin AIDL connection. The owner controls its lifetime explicitly. */
class BoundOcrPlugin internal constructor(
    val descriptor: InstalledOcrPlugin,
    private val binding: OcrPluginBinding,
) : Closeable {
    val service: IOcrPlugin get() = binding.service
    override fun close() = binding.close()
}

internal class OcrPluginBinding(
    context: Context,
    private val serviceInfo: ServiceInfo,
    private val connectTimeoutSeconds: Long = 8L,
    private val onDiagnostic: (String) -> Unit = {},
) : Closeable {
    private val appContext = context.applicationContext
    private val latch = CountDownLatch(1)
    private val stateLock = Any()
    private var connectedBinder: IBinder? = null
    private var deathRecipient: IBinder.DeathRecipient? = null
    private var failure: Throwable? = null
    private var closed = false
    private val connection: ServiceConnection
    private var bound = false
    lateinit var service: IOcrPlugin
        private set

    init {
        require(connectTimeoutSeconds > 0L)
        check(Looper.myLooper() != Looper.getMainLooper()) {
            "OCR Service 必须从工作线程绑定；不能在主线程等待 ServiceConnection"
        }
        val component = ComponentName(serviceInfo.packageName, serviceInfo.name)
        connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                if (binder == null) {
                    onDiagnostic("OCR_BIND_CALLBACK_NULL component=${component.flattenToShortString()}")
                    fail(OcrPluginException("OCR 插件返回空 Binder：$component", failureKind = OcrPluginFailureKind.BINDER_DIED))
                    return
                }
                onDiagnostic("OCR_BIND_CALLBACK_CONNECTED component=${component.flattenToShortString()};alive=${binder.isBinderAlive};ping=${runCatching { binder.pingBinder() }.getOrDefault(false)}")
                val proxy = IOcrPlugin.Stub.asInterface(binder)
                val recipient = IBinder.DeathRecipient {
                    onDiagnostic("OCR_BINDER_DIED component=${component.flattenToShortString()}")
                    fail(OcrPluginException("OCR 插件进程已退出：$component", failureKind = OcrPluginFailureKind.BINDER_DIED))
                }
                try {
                    binder.linkToDeath(recipient, 0)
                } catch (remote: RemoteException) {
                    fail(OcrPluginException("无法监听 OCR Binder 生命周期：$component", remote, OcrPluginFailureKind.BINDER_DIED))
                    return
                }
                synchronized(stateLock) {
                    if (closed) {
                        runCatching { binder.unlinkToDeath(recipient, 0) }
                        return
                    }
                    connectedBinder = binder
                    deathRecipient = recipient
                    service = proxy
                    latch.countDown()
                }
                onDiagnostic("OCR_BINDER_CONNECTED component=${component.flattenToShortString()}")
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                onDiagnostic("OCR_BIND_CALLBACK_DISCONNECTED component=${component.flattenToShortString()}")
                fail(OcrPluginException("OCR 插件连接已断开：$component", failureKind = OcrPluginFailureKind.SERVICE_DISCONNECTED))
            }

            override fun onBindingDied(name: ComponentName?) {
                onDiagnostic("OCR_BIND_CALLBACK_DIED component=${component.flattenToShortString()}")
                fail(OcrPluginException("OCR 插件绑定失效：$component", failureKind = OcrPluginFailureKind.BINDER_DIED))
            }

            override fun onNullBinding(name: ComponentName?) {
                onDiagnostic("OCR_BIND_CALLBACK_NULL_BINDING component=${component.flattenToShortString()}")
                fail(OcrPluginException("OCR 插件返回空服务绑定：$component", failureKind = OcrPluginFailureKind.BIND_REJECTED))
            }
        }

        // Explicit component binding avoids a second implicit resolution step after PackageManager
        // has already identified the service. The action is retained because OcrPluginService.onBind
        // uses it to select the AutoJs-compatible AIDL Binder.
        val intent = Intent(OcrPluginContract.ACTION_OCR).setComponent(component)
        val appInfo = serviceInfo.applicationInfo
        val serviceFacts = "component=${component.flattenToShortString()};exported=${serviceInfo.exported};" +
            "serviceEnabled=${serviceInfo.enabled};packageEnabled=${appInfo.enabled};" +
            "permission=${serviceInfo.permission ?: "NONE"};uid=${appInfo.uid};process=${serviceInfo.processName ?: "DEFAULT"}"
        onDiagnostic("OCR_BIND_ATTEMPT $serviceFacts")
        bound = try {
            appContext.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        } catch (error: SecurityException) {
            throw OcrPluginUnavailableException(
                "Android 拒绝 OCR Service 绑定（SecurityException）：$serviceFacts；${error.message ?: "无异常消息"}",
                failureKind = OcrPluginFailureKind.PERMISSION_DENIED,
                cause = error,
            )
        } catch (error: Exception) {
            throw OcrPluginException(
                "调用 bindService 异常：$serviceFacts；${error.javaClass.simpleName}:${error.message ?: "无异常消息"}",
                error,
                OcrPluginFailureKind.BIND_REJECTED,
            )
        }
        onDiagnostic("OCR_BIND_RESULT component=${component.flattenToShortString()};accepted=$bound")
        if (!bound) {
            throw OcrPluginUnavailableException(
                "Android bindService 返回 false；$serviceFacts；检查服务是否仍启用、组件名是否有效及插件 APK 是否刚更新",
                failureKind = OcrPluginFailureKind.BIND_REJECTED,
            )
        }
        if (!latch.await(connectTimeoutSeconds, TimeUnit.SECONDS)) {
            close()
            throw OcrPluginException(
                "bindService 已接受，但 onServiceConnected 在 ${connectTimeoutSeconds}s 内未到达；$serviceFacts",
                failureKind = OcrPluginFailureKind.BIND_TIMEOUT,
            )
        }
        failure?.let { error ->
            close()
            if (error is OcrPluginException) throw error
            throw OcrPluginException("OCR 插件连接失败：${error.message}", error, OcrPluginFailureKind.BINDER_DIED)
        }
    }

    private fun fail(error: Throwable) {
        synchronized(stateLock) {
            if (failure == null) failure = error
            latch.countDown()
        }
    }

    override fun close() {
        val binder: IBinder?
        val recipient: IBinder.DeathRecipient?
        val shouldUnbind: Boolean
        synchronized(stateLock) {
            if (closed) return
            closed = true
            binder = connectedBinder
            recipient = deathRecipient
            connectedBinder = null
            deathRecipient = null
            shouldUnbind = bound
            bound = false
        }
        if (binder != null && recipient != null) runCatching { binder.unlinkToDeath(recipient, 0) }
        if (shouldUnbind) runCatching { appContext.unbindService(connection) }
    }
}

open class OcrPluginException(
    message: String,
    cause: Throwable? = null,
    val failureKind: OcrPluginFailureKind = OcrPluginFailureKind.UNKNOWN,
) : Exception(message, cause)

class OcrPluginUnavailableException(
    message: String,
    failureKind: OcrPluginFailureKind = OcrPluginFailureKind.SERVICE_NOT_DISCOVERED,
    cause: Throwable? = null,
) : OcrPluginException(message, cause, failureKind)
