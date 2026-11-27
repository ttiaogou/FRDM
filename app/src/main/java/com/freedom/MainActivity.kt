package com.freedom

import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ImageView
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.freedom.accessibility.FreedomAccessibilityService
import com.freedom.adapters.accessibility.diagnostics.LatestAccessibilityNodeDump
import com.freedom.adapters.android.MonitorActions
import com.freedom.application.ports.OcrReviewRepository
import com.freedom.application.ports.OcrReviewEntry
import com.freedom.application.ports.MonitorControlRepository
import com.freedom.adapters.accessibility.android.MonitorOverlay
import com.freedom.stability.MonitorKeepAliveService
import com.freedom.ui.calibration.GestureCalibrationUiController
import com.freedom.ui.config.ConfigPanel
import com.freedom.ui.config.ConfigurationHome
import com.freedom.ui.config.AiModelConfigurationScreen
import com.freedom.ui.config.PersonalizationScreen
import com.freedom.ui.config.TargetAppEditor
import com.freedom.ui.logs.LogRecordsDialog
import com.freedom.ui.logs.LogTextFormatter
import com.freedom.ui.logs.SessionSampleArchive
import com.freedom.ui.logs.SessionSampleArchiveManager
import com.freedom.ui.theme.FreedomUi
import com.freedom.ocr.OcrPluginInstaller
import com.freedom.ocr.OcrPluginInstallAction
import com.freedom.ocr.OcrPluginHost
import com.freedom.ocr.OcrManager

import java.io.File

class MainActivity : Activity() {

    private val dependencies by lazy(LazyThreadSafetyMode.NONE) {
        com.freedom.app.FreedomApplication.root(this).createActivityDependencies(this)
    }
    private val monitorRepository get() = dependencies.monitorRepository
    private val taskLaunchParametersStore get() = dependencies.taskLaunchParametersRepository
    private val imagePoolResources get() = dependencies.imagePoolResourcePort
    private val aiModelCloudConsent get() = dependencies.aiModelCloudConsent
    private val aiModelConfigurationRepository get() = dependencies.aiModelConfigurationRepository
    private val aiReplyPersonalizationRepository get() = dependencies.aiReplyPersonalizationRepository
    private val gestureCalibration get() = dependencies.gestureCalibration
    private val runSessionCommands get() = dependencies.runSessionCommands
    private val ocrReviewRepository get() = dependencies.ocrReviewRepository
    private val diagnostics get() = dependencies.diagnostics
    private val sampleArchivePort get() = dependencies.sampleArchivePort

    private var configurationHome: ConfigurationHome? = null
    private var receiverRegistered = false
    private var pendingLogExportContent: String? = null
    private var pendingSessionSampleExportFile: File? = null
    private var pendingSessionSampleExportTemporary = false
    private var pendingNodeDumpExportFile: File? = null
    private var pendingReopenConfigPanelAfterSettings = false
    private var pendingReopenConfigPanelAfterNotificationPermission = false
    private var pendingEnableFloatingLogAfterOverlayPermission = false
    private var pendingAccessibilitySettingsRefresh = false
    private var pendingOcrPluginApk: com.freedom.ocr.DownloadedOcrPluginApk? = null
    private var awaitingOcrInstallPermission = false
    private val ocrPreflightLock = Any()
    @Volatile private var ocrPreflightRunning = false
    private var lastOcrPreflightStartedAtMillis = 0L
    private var suppressFloatingButtonOnPause = false
    private var pendingFloatingAction: String? = null
    private val archiveSealHandler = Handler(Looper.getMainLooper())
    private var archiveSealWaitRunnable: Runnable? = null
    private var activityResumed = false
    private var imagePoolOperationRunning = false
    private enum class Screen { CONFIGURATION, PERSONALIZATION, AI_MODEL_CONFIGURATION }
    private var currentScreen = Screen.CONFIGURATION

    private val configPanel by lazy(LazyThreadSafetyMode.NONE) {
        ConfigPanel(
            activity = this,
            repository = monitorRepository,
            gestureCalibration = gestureCalibration,
            callbacks = ConfigPanel.Callbacks(
                isAccessibilityEnabled = ::isAccessibilityEnabled,
                dp = ::dp,
                roundedBackground = ::roundedBackground,
                timestamp = ::timestamp,
                isIgnoringBatteryOptimizations = ::isIgnoringBatteryOptimizations,
                openBatteryOptimizationSettings = ::openBatteryOptimizationSettingsFromConfig,
                requestAccessibilityPermission = ::requestAccessibilityPermissionFromConfig,
                requestOverlayPermission = ::requestOverlayPermissionFromConfig,
                isNotificationEnabled = ::isNotificationEnabled,
                requestNotificationPermission = ::requestNotificationPermissionFromConfig,
                onOcrManagement = ::showOcrPluginManager,
                onAiModelConfiguration = ::showAiModelConfigurationPage,
                showGestureCalibrationDialog = { gestureCalibrationUi.showDialog() },
                hideOverlay = { MonitorOverlay.setHostActivityVisible(this, true) }
            )
        )
    }

    private val gestureCalibrationUi by lazy(LazyThreadSafetyMode.NONE) {
        GestureCalibrationUiController(
            activity = this,
            repository = monitorRepository,
            gestureCalibration = gestureCalibration,
            isAccessibilityEnabled = { isAccessibilityEnabled() },
            dp = { value -> dp(value) },
            roundedBackground = { color, radius -> roundedBackground(color, radius) },
            timestamp = { timestamp() }
        )
    }

    private val refreshReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            refreshScreen()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        recoverInterruptedSampleRunsOnStartup()
        showConfigurationPage()
        maybeStartOcrPluginPreflight()
        enqueueFloatingAction(intent)
    }

    /**
     * Perform an asynchronous startup/foreground OCR service probe. This binds to the advertised
     * service and reads PluginInfo, so it verifies more than the APK package's mere presence.
     * The short cooldown avoids duplicate probes from the initial onCreate/onResume pair.
     */
    private fun maybeStartOcrPluginPreflight() {
        val now = android.os.SystemClock.elapsedRealtime()
        synchronized(ocrPreflightLock) {
            if (ocrPreflightRunning || (lastOcrPreflightStartedAtMillis > 0L &&
                    now - lastOcrPreflightStartedAtMillis < OCR_PREFLIGHT_COOLDOWN_MS)) {
                return
            }
            ocrPreflightRunning = true
            lastOcrPreflightStartedAtMillis = now
        }
        val appContext = applicationContext
        val ocrManager = OcrManager.shared(appContext)
        val diagnosticSubscription = ocrManager.addDiagnosticListener { detail ->
            runOnUiThread {
                if (!isFinishing && !isDestroyed) {
                    monitorRepository.addLog(timestamp() + "  " + detail)
                }
            }
        }
        Thread({
            // 预检和正式 OCR 使用同一个进程级 OcrManager；成功连接会保留，不在预检结束时解绑。
            val result = runCatching { ocrManager.preflight() }
            runOnUiThread {
                diagnosticSubscription.close()
                synchronized(ocrPreflightLock) { ocrPreflightRunning = false }
                if (isFinishing || isDestroyed) return@runOnUiThread
                val message = result.fold(
                    onSuccess = { plugin ->
                        "OCR 插件启动预检：${plugin.info.name.ifBlank { plugin.packageName }} 已通过 Binder 连接与 PluginInfo 验证；连接保留供正式 OCR 复用"
                    },
                    onFailure = { failure ->
                        val kind = (failure as? com.freedom.ocr.OcrPluginException)?.failureKind
                        "OCR 插件启动预检失败${kind?.let { "[$it]" }.orEmpty()}：${failure.message ?: failure.javaClass.simpleName}"
                    }
                )
                monitorRepository.addLog(timestamp() + "  " + message)
            }
        }, "Freedom-OCR-Startup-Preflight").apply { isDaemon = true }.start()
    }

    /** Recover abandoned AI sample runs off the UI thread after an earlier process exit. */
    private fun recoverInterruptedSampleRunsOnStartup() {
        val documents = getExternalFilesDir("Documents") ?: filesDir
        val runsDirectory = File(documents, "FreedomSamples/runs")
        Thread({
            val report = sampleArchivePort.recoverPendingRuns(runsDirectory)
            if (report.recoveredCount > 0 || report.cleanedSealedCount > 0 || report.failedCount > 0) {
                runOnUiThread {
                    val message = buildString {
                        append("采集任务恢复：重新封存 ").append(report.recoveredCount)
                        append(" 条，清理已完成归档 ").append(report.cleanedSealedCount).append(" 条")
                        if (report.failedCount > 0) append("，失败 ").append(report.failedCount)
                    }
                    monitorRepository.addLog(timestamp() + "  " + message)
                    report.errors.take(3).forEach { error ->
                        monitorRepository.addLog(timestamp() + "  采集任务恢复失败：" + error.take(240))
                    }
                }
            }
        }, "Freedom-Sample-Archive-Recovery").apply {
            isDaemon = true
            start()
        }
    }

    override fun onResume() {
        super.onResume()
        maybeStartOcrPluginPreflight()
        resumePendingOcrPluginInstall()
        suppressFloatingButtonOnPause = false
        if (!receiverRegistered) {
            val filter = IntentFilter(MonitorActions.ACTION_REFRESH).apply {
                addAction(MonitorActions.ACTION_PRESENTATION_REFRESH)
            }
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(refreshReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                registerReceiver(refreshReceiver, filter)
            }
            receiverRegistered = true
        }

        // The floating-window owner is independent of this Activity. Foreground changes only
        // control visibility; they must not destroy and recreate the WindowManager windows.
        val floatingButtonEnabled = monitorRepository.isFloatingLogEnabled()
        val overlayPermissionGranted = Settings.canDrawOverlays(this)
        if (floatingButtonEnabled && !overlayPermissionGranted) {
            monitorRepository.setFloatingLogEnabled(false)
            monitorRepository.setOverlayEnabled(false)
            monitorRepository.addLog(timestamp() + "  悬浮窗授权不可用；已自动关闭浮动按钮，监听权限不受影响")
        } else {
            monitorRepository.setOverlayEnabled(floatingButtonEnabled)
        }
        MonitorOverlay.setHostActivityVisible(this, true)

        // Accessibility is required for monitoring. The optional floating button does not
        // control whether event monitoring or an existing run session can continue.
        if (monitorRepository.isRunRequested() && !isAccessibilityEnabled()) {
            runSessionCommands.requestStop()
            MonitorKeepAliveService.stop(this)
            monitorRepository.addLog(timestamp() + "  无障碍服务授权已关闭，监听已停止")
        } else if (monitorRepository.isRunRequested() && monitorRepository.isKeepAliveEnabled()) {
            MonitorKeepAliveService.start(this)
        }
        refreshScreen()

        var reopenConfigPanel = false
        if (pendingEnableFloatingLogAfterOverlayPermission) {
            pendingEnableFloatingLogAfterOverlayPermission = false
            if (Settings.canDrawOverlays(this)) {
                monitorRepository.setFloatingLogEnabled(true)
                monitorRepository.setOverlayEnabled(true)
                MonitorOverlay.update(this)
                monitorRepository.addLog(timestamp() + "  悬浮按钮授权已授予；开关已自动开启")
                Toast.makeText(this, "悬浮按钮已开启", Toast.LENGTH_SHORT).show()
            } else {
                monitorRepository.addLog(timestamp() + "  悬浮按钮授权未授予；开关保持关闭")
                Toast.makeText(this, "未获得悬浮窗权限，悬浮按钮保持关闭", Toast.LENGTH_LONG).show()
            }
            reopenConfigPanel = true
        }
        if (pendingAccessibilitySettingsRefresh) {
            pendingAccessibilitySettingsRefresh = false
            if (isAccessibilityEnabled()) {
                monitorRepository.addLog(timestamp() + "  无障碍服务授权状态已刷新：已授权")
                Toast.makeText(this, "无障碍服务已授权", Toast.LENGTH_SHORT).show()
            } else {
                monitorRepository.addLog(timestamp() + "  无障碍服务授权状态已刷新：未授权")
                Toast.makeText(this, "尚未开启无障碍服务", Toast.LENGTH_LONG).show()
            }
            reopenConfigPanel = true
        }
        if (pendingReopenConfigPanelAfterSettings) {
            pendingReopenConfigPanelAfterSettings = false
            reopenConfigPanel = true
        }
        if (pendingReopenConfigPanelAfterNotificationPermission) {
            pendingReopenConfigPanelAfterNotificationPermission = false
            monitorRepository.addLog(
                timestamp() + "  通知发布设置已返回；授权状态已重新读取：${if (isNotificationEnabled()) "已开启" else "未开启"}"
            )
            reopenConfigPanel = true
        }
        if (reopenConfigPanel && currentScreen == Screen.CONFIGURATION) {
            window.decorView.post {
                if (!isFinishing && !isDestroyed) configPanel.show()
            }
        }
    }

    override fun onDestroy() {
        archiveSealWaitRunnable?.let { archiveSealHandler.removeCallbacks(it) }
        archiveSealWaitRunnable = null
        super.onDestroy()
    }

    override fun onPause() {
        activityResumed = false
        gestureCalibrationUi.onPause()
        if (receiverRegistered) {
            runCatching { unregisterReceiver(refreshReceiver) }
            receiverRegistered = false
        }
        val shouldShowFloatingButton = !isChangingConfigurations &&
            !suppressFloatingButtonOnPause &&
            monitorRepository.isFloatingLogEnabled() && Settings.canDrawOverlays(this)
        MonitorOverlay.setHostActivityVisible(
            this,
            visible = false,
            suppressFloatingButton = !shouldShowFloatingButton
        )
        if (shouldShowFloatingButton && !MonitorOverlay.isAttached()) {
            monitorRepository.addLog(timestamp() + "  浮动按钮窗口尚未附加；已提交独立窗口管理器恢复")
        }
        super.onPause()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        enqueueFloatingAction(intent)
        if (activityResumed) {
            window.decorView.post { dispatchPendingFloatingAction() }
        }
    }

    /** Dialogs are shown only after the Activity has completed its resume transition. */
    private fun enqueueFloatingAction(source: Intent?) {
        val action = source?.action ?: return
        if (action != MonitorActions.ACTION_OPEN_CAPTURE_EXPORT &&
            action != MonitorActions.ACTION_RETURN_TO_CONFIGURATION) return

        pendingFloatingAction = action
        setIntent(Intent(source).apply { this.action = null })
    }

    override fun onPostResume() {
        super.onPostResume()
        activityResumed = true
        dispatchPendingFloatingAction()
    }

    private fun dispatchPendingFloatingAction() {
        if (!activityResumed || isFinishing || isDestroyed) return
        val action = pendingFloatingAction ?: return
        pendingFloatingAction = null
        when (action) {
            MonitorActions.ACTION_OPEN_CAPTURE_EXPORT -> showCollectionExportActions()
            MonitorActions.ACTION_RETURN_TO_CONFIGURATION -> showConfigurationPage()
        }
    }

    /** Opens the single main configuration page. */
    private fun showConfigurationPage() {
        currentScreen = Screen.CONFIGURATION
        val screen = ConfigurationHome(
            activity = this,
            callbacks = ConfigurationHome.Callbacks(
                dp = ::dp,
                targetLabel = {
                    val target = monitorRepository.targetPackage()
                    if (target == MonitorControlRepository.DEFAULT_TARGET_PACKAGE) "陌陌" else target
                },
                taskParameters = { taskLaunchParametersStore.read() },
                onTaskParametersChanged = taskLaunchParametersStore::write,
                onTargetClick = ::showTargetPicker,
                onPersonalization = ::showPersonalizationPage,
                onRecordsTab = ::showRecords,
                onOpenDrawer = { configPanel.show() },
                onRunClick = ::toggleMonitoring
            )
        )
        configurationHome = screen
        setContentView(screen.create())
        refreshScreen()
    }

    private data class OcrPluginMenuItem(
        val packageName: String,
        val title: String,
        val description: String,
        val installed: com.freedom.ocr.InstalledOcrPlugin?,
        val catalog: com.freedom.ocr.OcrPluginCatalogEntry?,
        val installedVersionCode: Long?,
        val discoveryStatus: String?,
    )

    private fun showOcrPluginManager() {
        val progress = AlertDialog.Builder(this)
            .setTitle("OCR 插件管理")
            .setMessage("正在发现已安装的 OCR 插件并读取插件目录…")
            .setCancelable(false)
            .create()
        progress.show()
        val appContext = applicationContext
        Thread({
            val discovery = runCatching {
                OcrPluginHost(appContext).discoverInstalledWithDiagnostics(maxAttempts = 3)
            }
            val catalog = runCatching { OcrPluginInstaller(appContext).loadCatalog() }
            runOnUiThread {
                progress.dismiss()
                if (isFinishing || isDestroyed) return@runOnUiThread
                val report = discovery.getOrNull()
                val discoveryStatus = report?.diagnosticMessage()
                    ?: discovery.exceptionOrNull()?.message
                    ?: "OCR 插件服务发现未返回诊断结果"
                val items = LinkedHashMap<String, OcrPluginMenuItem>()
                report?.plugins.orEmpty().forEach { plugin ->
                    items[plugin.packageName] = OcrPluginMenuItem(
                        packageName = plugin.packageName,
                        title = plugin.info.name.ifBlank { plugin.packageName },
                        description = plugin.info.description.orEmpty(),
                        installed = plugin,
                        catalog = null,
                        installedVersionCode = plugin.info.versionCode,
                        discoveryStatus = discoveryStatus,
                    )
                }
                catalog.getOrDefault(emptyList()).forEach { entry ->
                    val old = items[entry.packageName]
                    items[entry.packageName] = OcrPluginMenuItem(
                        packageName = entry.packageName,
                        title = entry.title,
                        description = entry.description,
                        installed = old?.installed,
                        catalog = entry,
                        installedVersionCode = old?.installedVersionCode
                            ?: OcrPluginHost(appContext).installedVersionCode(entry.packageName),
                        discoveryStatus = old?.discoveryStatus ?: discoveryStatus,
                    )
                }
                val menuItems = items.values.sortedBy { it.title.lowercase() }
                if (menuItems.isEmpty()) {
                    val catalogError = catalog.exceptionOrNull()?.message
                    AlertDialog.Builder(this)
                        .setTitle("OCR 插件管理")
                        .setMessage(
                            buildString {
                                append(discoveryStatus)
                                report?.failures?.take(3)?.takeIf { it.isNotEmpty() }?.let { errors ->
                                    append("\n\n扫描诊断：\n").append(errors.joinToString("\n"))
                                }
                                if (!catalogError.isNullOrBlank()) append("\n\n插件目录读取失败：").append(catalogError)
                                append("\n\n如果确认 APK 已安装，可先刷新重新扫描；只有确认未安装时才需要重新安装。")
                            }
                        )
                        .setPositiveButton("OCR 识别核验") { _, _ -> showOcrReviewList() }
                        .setNeutralButton("测试 OCR") { _, _ -> launchOcrImageTest() }
                        .setNegativeButton("关闭", null)
                        .show()
                    return@runOnUiThread
                }
                lateinit var dialog: AlertDialog
                val dialogRoot = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(18), dp(12), dp(18), dp(14))
                    background = FreedomUi.roundedSurface(dp(18), FreedomUi.BACKGROUND, FreedomUi.DIVIDER)
                    minimumHeight = dp(420)
                }
                val header = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                header.addView(TextView(this@MainActivity).apply {
                    text = "OCR 插件管理"
                    textSize = 19f
                    typeface = Typeface.create("sans-serif", Typeface.BOLD)
                    setTextColor(FreedomUi.TEXT)
                }, LinearLayout.LayoutParams(0, dp(48), 1f))
                header.addView(TextView(this@MainActivity).apply {
                    text = "×"
                    textSize = 28f
                    gravity = Gravity.CENTER
                    setTextColor(FreedomUi.TEXT)
                    contentDescription = "关闭 OCR 插件管理"
                    background = GradientDrawable().apply {
                        setColor(Color.TRANSPARENT)
                        cornerRadius = dp(22).toFloat()
                    }
                    isClickable = true
                    isFocusable = true
                    setOnClickListener { dialog.dismiss() }
                }, LinearLayout.LayoutParams(dp(44), dp(44)))
                dialogRoot.addView(header)
                dialogRoot.addView(View(this).apply {
                    setBackgroundColor(FreedomUi.DIVIDER)
                }, LinearLayout.LayoutParams(-1, dp(1)))

                val reviewStore = ocrReviewRepository
                val reviewEntries = reviewStore.listEntries()
                val reviewPending = reviewEntries.count { it.reviewStatus == OcrReviewRepository.REVIEW_PENDING }
                val reviewEntry = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(14), dp(11), dp(14), dp(11))
                    background = FreedomUi.roundedSurface(dp(12), FreedomUi.SURFACE, FreedomUi.DIVIDER)
                    isClickable = true
                    isFocusable = true
                    setOnClickListener {
                        dialog.dismiss()
                        showOcrReviewList()
                    }
                }
                reviewEntry.addView(TextView(this).apply {
                    text = "OCR 原图与识别结果人工核验"
                    textSize = 14f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(FreedomUi.TEXT)
                })
                reviewEntry.addView(TextView(this).apply {
                    text = "${reviewEntries.size} 条样本 · ${reviewPending} 条待核验；可查看原图、标注图和识别文本，也可删除样本"
                    textSize = 11f
                    setTextColor(FreedomUi.MUTED)
                    setPadding(0, dp(4), 0, 0)
                })
                dialogRoot.addView(reviewEntry, LinearLayout.LayoutParams(-1, -2).apply {
                    topMargin = dp(10)
                    bottomMargin = dp(4)
                })

                val pluginList = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(0, dp(8), 0, dp(8))
                }
                menuItems.forEachIndexed { index, item ->
                    val status = when {
                        item.installed != null && item.installed.enabled -> "已安装 · 已启用"
                        item.installed != null -> "已安装 · 已禁用"
                        item.installedVersionCode != null -> "已安装 · 服务待验证"
                        else -> "可安装"
                    }
                    val version = item.installed?.versionName
                        ?: item.catalog?.latestRelease?.versionName
                    val row = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(dp(2), dp(10), dp(2), dp(10))
                        isClickable = true
                        isFocusable = true
                        background = android.graphics.drawable.RippleDrawable(
                            android.content.res.ColorStateList.valueOf(0x26718E22),
                            GradientDrawable().apply {
                                setColor(Color.TRANSPARENT)
                                cornerRadius = dp(10).toFloat()
                            },
                            GradientDrawable().apply {
                                setColor(Color.WHITE)
                                cornerRadius = dp(10).toFloat()
                            }
                        )
                        setOnClickListener {
                            dialog.dismiss()
                            showOcrPluginActions(item)
                        }
                    }
                    row.addView(LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        gravity = Gravity.CENTER_VERTICAL
                        addView(TextView(this@MainActivity).apply {
                            text = item.title
                            textSize = 14f
                            typeface = Typeface.DEFAULT_BOLD
                            setTextColor(FreedomUi.TEXT)
                        })
                        addView(TextView(this@MainActivity).apply {
                            text = "$status${version?.let { " · $it" } ?: ""}"
                            textSize = 11f
                            setTextColor(if (item.installed?.enabled == true) FreedomUi.LIME_DARK else FreedomUi.MUTED)
                            setPadding(0, dp(3), 0, 0)
                        })
                        if (item.description.isNotBlank()) {
                            addView(TextView(this@MainActivity).apply {
                                text = item.description
                                textSize = 11f
                                setTextColor(FreedomUi.MUTED)
                                maxLines = 2
                                setPadding(0, dp(3), 0, 0)
                            })
                        }
                    }, LinearLayout.LayoutParams(0, -2, 1f))
                    row.addView(TextView(this).apply {
                        text = "›"
                        textSize = 25f
                        gravity = Gravity.CENTER
                        setTextColor(FreedomUi.MUTED)
                    }, LinearLayout.LayoutParams(dp(28), dp(42)))
                    pluginList.addView(row)
                    if (index < menuItems.lastIndex) {
                        pluginList.addView(View(this).apply {
                            setBackgroundColor(FreedomUi.DIVIDER)
                        }, LinearLayout.LayoutParams(-1, dp(1)))
                    }
                }
                dialogRoot.addView(ScrollView(this).apply {
                    isVerticalScrollBarEnabled = false
                    isHorizontalScrollBarEnabled = false
                    clipToPadding = false
                    addView(pluginList)
                }, LinearLayout.LayoutParams(-1, 0, 1f))

                val footer = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, dp(10), 0, 0)
                }
                footer.addView(TextView(this).apply {
                    text = "测试 OCR"
                    textSize = 13f
                    typeface = Typeface.DEFAULT_BOLD
                    gravity = Gravity.CENTER
                    setTextColor(FreedomUi.LIME_DARK)
                    background = GradientDrawable().apply {
                        setColor(FreedomUi.LIME_WASH)
                        cornerRadius = dp(12).toFloat()
                    }
                    isClickable = true
                    isFocusable = true
                    setOnClickListener {
                        dialog.dismiss()
                        launchOcrImageTest()
                    }
                }, LinearLayout.LayoutParams(0, dp(44), 1f).apply { rightMargin = dp(4) })
                footer.addView(TextView(this).apply {
                    text = "刷新"
                    textSize = 14f
                    typeface = Typeface.DEFAULT_BOLD
                    gravity = Gravity.CENTER
                    setTextColor(FreedomUi.LIME_DARK)
                    background = GradientDrawable().apply {
                        setColor(FreedomUi.LIME_WASH)
                        cornerRadius = dp(12).toFloat()
                    }
                    isClickable = true
                    isFocusable = true
                    setOnClickListener {
                        dialog.dismiss()
                        showOcrPluginManager()
                    }
                }, LinearLayout.LayoutParams(0, dp(44), 1f).apply { leftMargin = dp(4); rightMargin = dp(4) })
                footer.addView(TextView(this).apply {
                    text = "关闭"
                    textSize = 14f
                    gravity = Gravity.CENTER
                    setTextColor(FreedomUi.TEXT)
                    background = GradientDrawable().apply {
                        setColor(FreedomUi.SURFACE)
                        cornerRadius = dp(12).toFloat()
                        setStroke(dp(1), FreedomUi.DIVIDER)
                    }
                    isClickable = true
                    isFocusable = true
                    setOnClickListener { dialog.dismiss() }
                }, LinearLayout.LayoutParams(0, dp(44), 1f).apply { leftMargin = dp(4) })
                dialogRoot.addView(footer)

                dialog = AlertDialog.Builder(this)
                    .setView(dialogRoot)
                    .create()
                dialog.show()
                FreedomUi.styleAlertDialog(dialog, this, ::dp, widthFraction = 0.92f)
            }
        }, "Freedom-OCR-Plugin-Discovery").apply { isDaemon = true }.start()
    }

    private fun showOcrReviewList() {
        val store = ocrReviewRepository
        val entries = store.listEntries()
        lateinit var dialog: AlertDialog
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(12), dp(18), dp(14))
            background = FreedomUi.roundedSurface(dp(18), FreedomUi.BACKGROUND, FreedomUi.DIVIDER)
            minimumHeight = dp(360)
        }
        root.addView(TextView(this).apply {
            text = "OCR 人工核验"
            textSize = 19f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(FreedomUi.TEXT)
            gravity = Gravity.CENTER_VERTICAL
        }, LinearLayout.LayoutParams(-1, dp(44)))
        root.addView(TextView(this).apply {
            text = ocrReviewSummary(entries)
            textSize = 12f
            setTextColor(FreedomUi.MUTED)
            setPadding(0, dp(2), 0, dp(10))
        })
        root.addView(View(this).apply { setBackgroundColor(FreedomUi.DIVIDER) }, LinearLayout.LayoutParams(-1, dp(1)))

        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(6), 0, dp(6))
        }
        if (entries.isEmpty()) {
            list.addView(TextView(this).apply {
                text = "暂无 OCR 核验样本。运行聊天消息 OCR 后，原始截图、消息裁剪图、标注图和识别文本会保存在这里。"
                textSize = 13f
                setTextColor(FreedomUi.MUTED)
                setPadding(dp(8), dp(20), dp(8), dp(20))
            })
        } else {
            entries.forEachIndexed { index, entry ->
                val item = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(8), dp(10), dp(8), dp(10))
                }
                val top = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                top.addView(TextView(this).apply {
                    text = "${formatOcrReviewTime(entry.capturedAtEpochMs)} · 行 ${entry.rowIndex}"
                    textSize = 12f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(FreedomUi.TEXT)
                }, LinearLayout.LayoutParams(0, -2, 1f))
                top.addView(TextView(this).apply {
                    text = ocrReviewStatusLabel(entry.reviewStatus)
                    textSize = 11f
                    setTextColor(when (entry.reviewStatus) {
                        OcrReviewRepository.REVIEW_CORRECT -> FreedomUi.LIME_DARK
                        OcrReviewRepository.REVIEW_INCORRECT -> Color.rgb(190, 65, 65)
                        else -> FreedomUi.MUTED
                    })
                })
                item.addView(top)
                item.addView(TextView(this).apply {
                    text = entry.recognizedText.ifBlank { "（未识别到文字）" }
                    textSize = 12f
                    setTextColor(FreedomUi.TEXT)
                    maxLines = 2
                    setPadding(0, dp(5), 0, dp(7))
                })
                val actions = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                actions.addView(reviewActionText("查看详情") {
                    dialog.dismiss()
                    showOcrReviewDetail(entry)
                }, LinearLayout.LayoutParams(0, dp(36), 1f).apply { rightMargin = dp(5) })
                actions.addView(reviewActionText("删除", danger = true) {
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("删除 OCR 样本")
                        .setMessage("删除这条核验样本及其关联图片和识别记录？")
                        .setNegativeButton("取消", null)
                        .setPositiveButton("删除") { _, _ ->
                            if (store.deleteEntry(entry.batchId, entry.entryId)) {
                                dialog.dismiss()
                                showOcrReviewList()
                            } else {
                                Toast.makeText(this@MainActivity, "删除失败，样本文件可能已变化", Toast.LENGTH_LONG).show()
                            }
                        }
                        .show()
                }, LinearLayout.LayoutParams(0, dp(36), 1f).apply { leftMargin = dp(5) })
                item.addView(actions)
                list.addView(item)
                if (index < entries.lastIndex) {
                    list.addView(View(this).apply { setBackgroundColor(FreedomUi.DIVIDER) }, LinearLayout.LayoutParams(-1, dp(1)))
                }
            }
        }
        root.addView(ScrollView(this).apply {
            isVerticalScrollBarEnabled = true
            addView(list)
        }, LinearLayout.LayoutParams(-1, 0, 1f))

        val footer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, 0)
        }
        footer.addView(reviewActionText("删除全部", danger = true) {
            if (entries.isEmpty()) return@reviewActionText
            AlertDialog.Builder(this@MainActivity)
                .setTitle("删除全部 OCR 核验数据")
                .setMessage("这会删除所有 OCR 核验样本、原始截图、裁剪图和标注图，且无法恢复。")
                .setNegativeButton("取消", null)
                .setPositiveButton("全部删除") { _, _ ->
                    if (store.deleteAll()) {
                        dialog.dismiss()
                        showOcrReviewList()
                    } else Toast.makeText(this@MainActivity, "部分 OCR 核验数据删除失败", Toast.LENGTH_LONG).show()
                }
                .show()
        }, LinearLayout.LayoutParams(0, dp(44), 1f).apply { rightMargin = dp(5) })
        footer.addView(reviewActionText("刷新") {
            dialog.dismiss()
            showOcrReviewList()
        }, LinearLayout.LayoutParams(0, dp(44), 1f).apply {
            leftMargin = dp(5); rightMargin = dp(5)
        })
        footer.addView(reviewActionText("关闭") { }, LinearLayout.LayoutParams(0, dp(44), 1f).apply { leftMargin = dp(5) })
        root.addView(footer)

        // The footer close action is wired after the dialog is constructed.
        val closeView = footer.getChildAt(2)
        dialog = AlertDialog.Builder(this).setView(root).create()
        closeView.setOnClickListener { dialog.dismiss() }
        dialog.show()
        FreedomUi.styleAlertDialog(dialog, this, ::dp, widthFraction = 0.94f)
    }

    private fun showOcrReviewDetail(entry: OcrReviewEntry) {
        val store = ocrReviewRepository
        val latest = store.listEntries().firstOrNull {
            it.batchId == entry.batchId && it.entryId == entry.entryId
        } ?: run {
            Toast.makeText(this, "核验样本已不存在", Toast.LENGTH_LONG).show()
            showOcrReviewList()
            return
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            background = FreedomUi.roundedSurface(dp(18), FreedomUi.BACKGROUND, FreedomUi.DIVIDER)
        }
        root.addView(TextView(this).apply {
            text = "OCR 样本详情"
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(FreedomUi.TEXT)
        }, LinearLayout.LayoutParams(-1, dp(40)))
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        addOcrReviewImage(content, "原始屏幕截图", latest.sourceImagePath)
        addOcrReviewImage(content, "OCR 原始裁剪图", latest.originalCropPath)
        addOcrReviewImage(content, "OCR 标注图（绿色框为识别文本区域）", latest.annotatedImagePath)
        content.addView(TextView(this).apply {
            text = "OCR 识别文本"
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(FreedomUi.TEXT)
            setPadding(0, dp(12), 0, dp(5))
        })
        content.addView(TextView(this).apply {
            text = latest.recognizedText.ifBlank { "（未识别到文字）" }
            textSize = 14f
            setTextColor(FreedomUi.TEXT)
            setPadding(dp(10), dp(10), dp(10), dp(10))
            background = FreedomUi.roundedSurface(dp(10), FreedomUi.SURFACE, FreedomUi.DIVIDER)
        })
        val confidence = latest.confidence?.let { value ->
            String.format(java.util.Locale.US, "%.1f%%", (value * 100f).coerceIn(0f, 100f))
        } ?: "无"
        content.addView(TextView(this).apply {
            text = "状态：${ocrReviewStatusLabel(latest.reviewStatus)}    置信度：$confidence    检测行数：${latest.lineCount}" +
                latest.failureReason?.let { "\n失败说明：$it" }.orEmpty() +
                latest.correctedText.takeIf { it.isNotBlank() }?.let { "\n人工录入的正确文本：$it" }.orEmpty()
            textSize = 11f
            setTextColor(FreedomUi.MUTED)
            setPadding(0, dp(8), 0, dp(10))
        })
        root.addView(ScrollView(this).apply {
            isVerticalScrollBarEnabled = true
            addView(content)
        }, LinearLayout.LayoutParams(-1, 0, 1f))

        lateinit var dialog: AlertDialog
        val statusActions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        statusActions.addView(reviewActionText("正确") {
            if (store.markReviewed(latest.batchId, latest.entryId, OcrReviewRepository.REVIEW_CORRECT)) {
                dialog.dismiss()
                showOcrReviewDetail(latest)
            } else Toast.makeText(this@MainActivity, "保存人工核验结果失败", Toast.LENGTH_LONG).show()
        }, LinearLayout.LayoutParams(0, dp(42), 1f).apply { rightMargin = dp(4) })
        statusActions.addView(reviewActionText("错误") {
            dialog.dismiss()
            showOcrCorrectionDialog(latest)
        }, LinearLayout.LayoutParams(0, dp(42), 1f).apply { leftMargin = dp(4); rightMargin = dp(4) })
        statusActions.addView(reviewActionText("无法判断") {
            if (store.markReviewed(latest.batchId, latest.entryId, OcrReviewRepository.REVIEW_UNSURE)) {
                dialog.dismiss()
                showOcrReviewDetail(latest)
            } else Toast.makeText(this@MainActivity, "保存人工核验结果失败", Toast.LENGTH_LONG).show()
        }, LinearLayout.LayoutParams(0, dp(42), 1f).apply { leftMargin = dp(4) })
        root.addView(statusActions)
        val bottomActions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, 0)
        }
        bottomActions.addView(reviewActionText("删除此样本", danger = true) {
            AlertDialog.Builder(this@MainActivity)
                .setTitle("删除 OCR 样本")
                .setMessage("删除此样本及关联图片？")
                .setNegativeButton("取消", null)
                .setPositiveButton("删除") { _, _ ->
                    if (store.deleteEntry(latest.batchId, latest.entryId)) {
                        dialog.dismiss()
                        showOcrReviewList()
                    } else {
                        Toast.makeText(this@MainActivity, "删除失败，样本文件可能已变化", Toast.LENGTH_LONG).show()
                    }
                }
                .show()
        }, LinearLayout.LayoutParams(0, dp(42), 1f))
        bottomActions.addView(reviewActionText("返回样本列表") {
            dialog.dismiss()
            showOcrReviewList()
        }, LinearLayout.LayoutParams(0, dp(42), 1f).apply { leftMargin = dp(8) })
        root.addView(bottomActions)
        dialog = AlertDialog.Builder(this).setView(root).create()
        dialog.show()
        FreedomUi.styleAlertDialog(dialog, this, ::dp, widthFraction = 0.94f)
    }

    private fun showOcrCorrectionDialog(entry: OcrReviewEntry) {
        val input = EditText(this).apply {
            hint = "可填写图片中实际显示的正确文字（非必填）"
            minLines = 2
            maxLines = 5
            setText(entry.correctedText)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        AlertDialog.Builder(this)
            .setTitle("标记为识别错误")
            .setMessage("确认当前 OCR 文本有误。录入正确文本可用于后续人工对照，但不是必填项。")
            .setView(input)
            .setNegativeButton("取消") { _, _ -> showOcrReviewDetail(entry) }
            .setPositiveButton("保存核验结果") { _, _ ->
                val saved = ocrReviewRepository.markReviewed(
                    entry.batchId,
                    entry.entryId,
                    OcrReviewRepository.REVIEW_INCORRECT,
                    input.text?.toString().orEmpty()
                )
                if (saved) showOcrReviewDetail(entry)
                else Toast.makeText(this, "保存人工核验结果失败", Toast.LENGTH_LONG).show()
            }
            .show()
    }

    private fun addOcrReviewImage(parent: LinearLayout, title: String, path: String?) {
        parent.addView(TextView(this).apply {
            text = title
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(FreedomUi.TEXT)
            setPadding(0, dp(12), 0, dp(5))
        })
        val image = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            adjustViewBounds = true
            setBackgroundColor(Color.rgb(245, 246, 247))
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        val bitmap = path?.let(::decodeOcrReviewImage)
        if (bitmap != null) {
            image.setImageBitmap(bitmap)
        } else {
            image.setImageDrawable(null)
            image.contentDescription = "图片文件不存在或无法解码"
        }
        parent.addView(image, LinearLayout.LayoutParams(-1, dp(210)))
    }

    private fun decodeOcrReviewImage(path: String): Bitmap? {
        val file = File(path)
        if (!file.isFile) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val maxDimension = maxOf(bounds.outWidth, bounds.outHeight)
        val targetDimension = (resources.displayMetrics.widthPixels * 1.5f).toInt().coerceAtLeast(900)
        var sample = 1
        while (maxDimension / (sample * 2) >= targetDimension) sample *= 2
        return BitmapFactory.decodeFile(path, BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        })
    }

    private fun ocrReviewSummary(entries: List<OcrReviewEntry>): String {
        val correct = entries.count { it.reviewStatus == OcrReviewRepository.REVIEW_CORRECT }
        val incorrect = entries.count { it.reviewStatus == OcrReviewRepository.REVIEW_INCORRECT }
        val unsure = entries.count { it.reviewStatus == OcrReviewRepository.REVIEW_UNSURE }
        val pending = entries.count { it.reviewStatus == OcrReviewRepository.REVIEW_PENDING }
        val judged = correct + incorrect
        val rate = if (judged > 0) "${(correct * 100.0 / judged).let { "%.1f".format(java.util.Locale.US, it) }}%" else "暂无"
        return "样本 ${entries.size} 条 · 待核验 $pending · 正确 $correct · 错误 $incorrect · 无法判断 $unsure\n人工标记准确率：$rate（仅按已标记正确/错误的样本计算）"
    }

    private fun ocrReviewStatusLabel(status: String): String = when (status) {
        OcrReviewRepository.REVIEW_CORRECT -> "人工确认正确"
        OcrReviewRepository.REVIEW_INCORRECT -> "人工确认错误"
        OcrReviewRepository.REVIEW_UNSURE -> "无法判断"
        else -> "待人工核验"
    }

    private fun formatOcrReviewTime(epochMs: Long): String =
        java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.getDefault())
            .format(java.util.Date(epochMs))

    private fun reviewActionText(text: String, danger: Boolean = false, onClick: () -> Unit): TextView =
        TextView(this).apply {
            this.text = text
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(if (danger) Color.rgb(190, 65, 65) else FreedomUi.LIME_DARK)
            background = FreedomUi.roundedSurface(dp(10), FreedomUi.SURFACE, FreedomUi.DIVIDER)
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }

    /** Opens a local image picker and runs a real detect() call outside the Momo navigation flow. */
    private fun launchOcrImageTest() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/*"
        }
        runCatching { startActivityForResult(intent, REQUEST_OCR_IMAGE_TEST) }
            .onFailure { failure ->
                Toast.makeText(this, "无法打开图片选择器：${failure.message ?: failure.javaClass.simpleName}", Toast.LENGTH_LONG).show()
            }
    }

    private fun runOcrImageTest(uri: Uri) {
        val progress = AlertDialog.Builder(this)
            .setTitle("OCR 独立测试")
            .setMessage("正在读取图片并验证 OCR 插件连接…")
            .setCancelable(false)
            .create()
        progress.show()
        val manager = OcrManager.shared(applicationContext)
        val diagnostics = java.util.Collections.synchronizedList(mutableListOf<String>())
        val subscription = manager.addDiagnosticListener { line ->
            diagnostics += line
            runOnUiThread {
                if (!isFinishing && !isDestroyed) {
                    monitorRepository.addLog(timestamp() + "  OCR 独立测试：" + line)
                }
            }
        }
        Thread({
            var bitmap: Bitmap? = null
            val outcome = runCatching {
                // ACTION_OPEN_DOCUMENT returns a content:// URI. Its read grant is retained in
                // onActivityResult when the provider offers a persistable permission.
                // Important: decodeStream() is expected to return null when inJustDecodeBounds=true;
                // the dimensions are reported through Options and null must not be treated as I/O failure.
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                val boundsRead = runCatching {
                    contentResolver.openInputStream(uri)?.use { input ->
                        BitmapFactory.decodeStream(input, null, bounds)
                        // Bounds-only decoding intentionally returns no Bitmap.
                        bounds.outWidth > 0 && bounds.outHeight > 0
                    } ?: false
                }.getOrElse { failure ->
                    throw IllegalStateException(
                        "IMAGE_STREAM_OPEN_FAILED：无法读取所选图片 URI（${failure.javaClass.simpleName}: " +
                            (failure.message ?: "无详细信息") + "）",
                        failure,
                    )
                }
                if (!boundsRead || bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                    throw IllegalStateException(
                        "IMAGE_DECODE_FAILED：无法读取图片尺寸，可能是 URI 授权失效、文件不存在或格式不受支持"
                    )
                }

                diagnostics += "OCR_IMAGE_BOUNDS：${bounds.outWidth}x${bounds.outHeight}"
                val maxDimension = maxOf(bounds.outWidth, bounds.outHeight)
                val sample = if (maxDimension > 2400) {
                    ((maxDimension + 2399) / 2400).coerceAtLeast(1)
                } else 1
                bitmap = runCatching {
                    contentResolver.openInputStream(uri)?.use { input ->
                        BitmapFactory.Options().run {
                            inSampleSize = sample
                            inPreferredConfig = Bitmap.Config.ARGB_8888
                            inJustDecodeBounds = false
                            BitmapFactory.decodeStream(input, null, this)
                        }
                    }
                }.getOrElse { failure ->
                    throw IllegalStateException(
                        "IMAGE_STREAM_OPEN_FAILED：重新打开图片流失败（${failure.javaClass.simpleName}: " +
                            (failure.message ?: "无详细信息") + "）",
                        failure,
                    )
                } ?: throw IllegalStateException(
                    "IMAGE_DECODE_FAILED：图片流已打开，但 BitmapFactory 无法解码该图片"
                )
                require(bitmap!!.width > 0 && bitmap!!.height > 0) { "IMAGE_DECODE_FAILED：图片尺寸无效" }
                diagnostics += "OCR_IMAGE_DECODED：${bitmap!!.width}x${bitmap!!.height};sample=$sample"
                val plugin = manager.preflight()
                val lines = manager.recognize(bitmap!!)
                OcrImageTestResult(
                    pluginName = plugin.info.name.ifBlank { plugin.packageName },
                    imageWidth = bitmap!!.width,
                    imageHeight = bitmap!!.height,
                    recognizedText = lines.joinToString("\n") { it.text }.trim(),
                    lineCount = lines.size,
                    finalState = manager.currentState().name,
                )
            }
            bitmap?.recycle()
            runOnUiThread {
                subscription.close()
                progress.dismiss()
                if (isFinishing || isDestroyed) return@runOnUiThread
                val message = outcome.fold(
                    onSuccess = { result ->
                        buildString {
                            append("插件：").append(result.pluginName)
                            append("\n图片：").append(result.imageWidth).append('×').append(result.imageHeight)
                            append("\n最终状态：").append(result.finalState)
                            append("\n识别行数：").append(result.lineCount)
                            append("\n\n识别文本：\n")
                            append(result.recognizedText.ifBlank { "（插件调用成功，但没有识别到文字）" })
                            val recent = synchronized(diagnostics) { diagnostics.takeLast(8) }
                            if (recent.isNotEmpty()) {
                                append("\n\n状态机诊断：\n")
                                append(recent.joinToString("\n"))
                            }
                        }
                    },
                    onFailure = { failure ->
                        buildString {
                            append("测试失败\n")
                            append(failure.javaClass.simpleName).append(": ")
                                .append(failure.message ?: "无详细错误信息")
                            append("\n当前状态：").append(manager.currentState().name)
                            val recent = synchronized(diagnostics) { diagnostics.takeLast(8) }
                            if (recent.isNotEmpty()) {
                                append("\n\n状态机诊断：\n")
                                append(recent.joinToString("\n"))
                            }
                        }
                    }
                )
                AlertDialog.Builder(this)
                    .setTitle(if (outcome.isSuccess) "OCR 测试完成" else "OCR 测试失败")
                    .setMessage(message)
                    .setPositiveButton("确定", null)
                    .setNeutralButton("查看运行日志") { _, _ -> showRecords() }
                    .show()
            }
        }, "Freedom-OCR-Image-Test").apply { isDaemon = true }.start()
    }

    private data class OcrImageTestResult(
        val pluginName: String,
        val imageWidth: Int,
        val imageHeight: Int,
        val recognizedText: String,
        val lineCount: Int,
        val finalState: String,
    )

    private fun showOcrPluginActions(item: OcrPluginMenuItem) {
        val installed = item.installed
        if (installed == null && item.installedVersionCode == null) {
            AlertDialog.Builder(this)
                .setTitle(item.title)
                .setMessage(item.description.ifBlank { "安装独立 OCR 插件后，Freedom 主 APK 不需要包含 OCR 推理引擎或模型。" })
                .setPositiveButton("安装") { _, _ -> downloadAndInstallOcrPlugin(item.packageName) }
                .setNegativeButton("取消", null)
                .show()
            return
        }
        if (installed == null) {
            AlertDialog.Builder(this)
                .setTitle(item.title)
                .setMessage(
                    "插件 APK 已安装，但本次服务发现/连接验证未成功。\n\n" +
                        (item.discoveryStatus ?: "未取得插件服务诊断信息") +
                        "\n\n这不一定表示插件没有安装；请先重新扫描，再决定是否重新安装。"
                )
                .setPositiveButton("重新扫描") { _, _ -> showOcrPluginManager() }
                .setNeutralButton("重新安装/更新") { _, _ -> downloadAndInstallOcrPlugin(item.packageName) }
                .setNegativeButton("取消", null)
                .show()
            return
        }

        val actions = mutableListOf<Pair<String, () -> Unit>>()
        actions += (if (installed.enabled) "禁用插件" else "启用插件") to {
            OcrPluginHost(this).setEnabled(item.packageName, !installed.enabled)
            Toast.makeText(this, if (installed.enabled) "已禁用 ${item.title}" else "已启用 ${item.title}", Toast.LENGTH_SHORT).show()
            showOcrPluginManager()
        }
        val latest = item.catalog?.latestRelease
        if (latest != null && latest.versionCode > installed.info.versionCode) {
            actions += "更新到 ${latest.versionName}" to { downloadAndInstallOcrPlugin(item.packageName) }
        }
        actions += "卸载插件" to {
            AlertDialog.Builder(this)
                .setTitle("卸载 OCR 插件")
                .setMessage("确定卸载 ${item.title}？卸载后不会影响 Freedom 主程序和其他插件。")
                .setPositiveButton("卸载") { _, _ ->
                    runCatching {
                        startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:${item.packageName}")))
                    }.onFailure { failure ->
                        Toast.makeText(this, failure.message ?: "无法打开系统卸载界面", Toast.LENGTH_LONG).show()
                    }
                }
                .setNegativeButton("取消", null)
                .show()
        }
        actions += "插件信息" to {
            val info = installed.info
            AlertDialog.Builder(this)
                .setTitle(info.name.ifBlank { item.packageName })
                .setMessage(
                    buildString {
                        append("包名：").append(item.packageName)
                        append("\n版本：").append(info.versionName).append(" (").append(info.versionCode).append(')')
                        append("\n引擎：").append(info.engine ?: "未知")
                        append("\n变体：").append(info.variant ?: "默认")
                        append("\n作者：").append(info.author)
                        if (!info.description.isNullOrBlank()) append("\n\n").append(info.description)
                    }
                )
                .setPositiveButton("确定", null)
                .show()
        }
        AlertDialog.Builder(this)
            .setTitle(item.title)
            .setItems(actions.map { it.first }.toTypedArray()) { _, index -> actions[index].second.invoke() }
            .setNegativeButton("返回", null)
            .show()
    }

    private fun downloadAndInstallOcrPlugin(packageName: String) {
        val progress = AlertDialog.Builder(this)
            .setTitle("OCR 插件")
            .setMessage("正在下载并校验插件安装包…")
            .setCancelable(false)
            .create()
        progress.show()
        Thread({
            val result = runCatching { OcrPluginInstaller(this).downloadPluginApk(packageName) }
            runOnUiThread {
                progress.dismiss()
                result.onSuccess { downloaded ->
                    pendingOcrPluginApk = downloaded
                    resumePendingOcrPluginInstall()
                }.onFailure { failure ->
                    AlertDialog.Builder(this)
                        .setTitle("插件下载失败")
                        .setMessage(failure.message ?: failure.javaClass.simpleName)
                        .setPositiveButton("确定", null)
                        .show()
                }
            }
        }, "Freedom-OCR-Plugin-Download").apply { isDaemon = true }.start()
    }

    private fun resumePendingOcrPluginInstall() {
        val downloaded = pendingOcrPluginApk ?: return
        if (awaitingOcrInstallPermission) {
            awaitingOcrInstallPermission = false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !packageManager.canRequestPackageInstalls()) {
                Toast.makeText(this, "尚未获得安装授权。返回后可重新点击安装。", Toast.LENGTH_LONG).show()
                return
            }
        }
        val action = runCatching {
            OcrPluginInstaller(this).requestSystemInstall(this, downloaded)
        }.getOrElse { failure ->
            pendingOcrPluginApk = null
            Toast.makeText(
                this,
                "无法启动插件安装：${failure.message ?: failure.javaClass.simpleName}",
                Toast.LENGTH_LONG
            ).show()
            return
        }
        when (action) {
            OcrPluginInstallAction.UnknownSourcesPermissionRequired -> {
                awaitingOcrInstallPermission = true
                Toast.makeText(this, "请允许 Freedom 安装未知来源应用，返回后会继续安装插件", Toast.LENGTH_LONG).show()
            }
            is OcrPluginInstallAction.InstallerOpened -> {
                awaitingOcrInstallPermission = false
                pendingOcrPluginApk = null
                Toast.makeText(
                    this,
                    "已打开 Android 安装界面，请确认安装 ${action.packageName} (${action.versionName})",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    /** Full-screen page; the previous configuration page is restored by its back control. */
    private fun showPersonalizationPage() {
        currentScreen = Screen.PERSONALIZATION
        setContentView(
            PersonalizationScreen(
                activity = this,
                dp = ::dp,
                onBack = ::showConfigurationPage,
                taskParameters = { taskLaunchParametersStore.read() },
                onTaskParametersChanged = taskLaunchParametersStore::write,
                onImageResourceImportRequested = ::launchImageResourceImport,
                onImageResourceClearRequested = ::clearImportedImageResources,
                loadAiReplyPersonalization = aiReplyPersonalizationRepository::load,
                onAiReplyPersonalizationChanged = aiReplyPersonalizationRepository::save
            ).create()
        )
    }

    private fun launchImageResourceImport() {
        if (imagePoolOperationRunning) {
            Toast.makeText(this, "图片池正在处理，请勿重复提交", Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        runCatching { startActivityForResult(intent, REQUEST_IMAGE_RESOURCE_IMPORT) }
            .onFailure {
                Toast.makeText(this, "无法打开图片选择器", Toast.LENGTH_SHORT).show()
            }
    }

    private fun clearImportedImageResources() {
        if (imagePoolOperationRunning) {
            Toast.makeText(this, "图片池正在处理，请勿重复提交", Toast.LENGTH_SHORT).show()
            return
        }
        val originalUris = taskLaunchParametersStore.read().imageResourceUris
        if (originalUris.isEmpty()) return
        imagePoolOperationRunning = true
        Toast.makeText(this, "正在清理图片池", Toast.LENGTH_SHORT).show()
        Thread({
            val failed = imagePoolResources.removeFromPool(originalUris)
            runOnUiThread {
                val original = originalUris.toSet()
                val remaining = failed.toSet()
                val current = taskLaunchParametersStore.read()
                val remainingResources = current.imageResourceUris.filter {
                    it !in original || it in remaining
                }
                val updated = current.copy(
                    imageResourceUris = remainingResources,
                    // Removing the final picture must not leave a nonzero, unfulfillable threshold.
                    imageSendThreshold = if (remainingResources.isEmpty()) 0 else current.imageSendThreshold
                )
                taskLaunchParametersStore.write(updated)
                imagePoolOperationRunning = false
                if (currentScreen == Screen.PERSONALIZATION) showPersonalizationPage()
                Toast.makeText(this,
                    if (failed.isEmpty()) "图片池已清空" else "部分图片无法移除，保留 ${failed.size} 张",
                    Toast.LENGTH_LONG).show()
            }
        }, "Freedom-Image-Pool-Clear").apply { isDaemon = true }.start()
    }

    /** Opens the locally encrypted model configuration and its cloud-processing consent. */
    private fun showAiModelConfigurationPage() {
        val loaded = runCatching { aiModelConfigurationRepository.load() }
        val configuration = loaded.getOrElse { failure ->
            val dialog = AlertDialog.Builder(this)
                .setTitle("无法读取 AI 模型配置")
                .setMessage(failure.message ?: "本机加密配置无法读取。原有密文未被删除。")
                .setPositiveButton("返回") { _, _ -> showConfigurationPage() }
                .create()
            dialog.show()
            return
        }
        currentScreen = Screen.AI_MODEL_CONFIGURATION
        setContentView(
            AiModelConfigurationScreen(
                activity = this,
                dp = ::dp,
                initialConfiguration = configuration,
                onBack = ::showConfigurationPage,
                onSave = { value -> runCatching { aiModelConfigurationRepository.save(value) } },
                onCloudConsentChanged = aiModelCloudConsent::setGranted
            ).create()
        )
    }

    private fun showTargetPicker() {
        AlertDialog.Builder(this)
            .setTitle("选择目标应用")
            .setItems(arrayOf("陌陌", "自定义应用包名")) { _, which ->
                if (which == 0) {
                    applyTargetPackage(MonitorControlRepository.DEFAULT_TARGET_PACKAGE)
                } else {
                    showTargetEditor()
                }
            }
            .show()
    }

    private fun applyTargetPackage(value: String) {
        if (monitorRepository.targetPackage() == value) return
        monitorRepository.setTargetPackage(value)
        sendBroadcast(
            Intent(MonitorActions.ACTION_TARGET_CHANGED).setPackage(packageName)
        )
        monitorRepository.updateAppPage(
            appLabel = "尚未识别",
            pageLabel = "等待页面信息"
        )
        monitorRepository.addLog(timestamp() + "  监控目标已设置为 $value")
        if (currentScreen == Screen.CONFIGURATION) showConfigurationPage()
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        when (currentScreen) {
            Screen.AI_MODEL_CONFIGURATION -> showConfigurationPage()
            Screen.PERSONALIZATION -> showConfigurationPage()
            Screen.CONFIGURATION -> super.onBackPressed()
        }
    }

    private fun toggleMonitoring() {
        if (monitorRepository.isRunRequested()) {
            runSessionCommands.requestStop()
            monitorRepository.addLog(timestamp() + "  监听已停止")
            MonitorKeepAliveService.stop(this)
            sendRefresh()
            refreshScreen()
            return
        }

        if (resolveTargetLaunchIntent(showError = true) == null) return

        if (!isAccessibilityEnabled()) {
            AlertDialog.Builder(this)
                .setTitle("需要开启无障碍服务")
                .setMessage(
                    "Freedom 需要通过系统无障碍服务接收目标应用的界面事件，并读取系统公开的节点信息。请在系统设置中手动开启 Freedom。"
                )
                .setPositiveButton("前往无障碍设置") { _, _ ->
                    startSystemSettings(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }
                .setNegativeButton("取消", null)
                .show()
            return
        }

        startMonitoringAfterPermissionChecks()
    }

    private fun showTargetEditor() {
        TargetAppEditor.show(
            activity = this,
            repository = monitorRepository,
            dp = { value -> dp(value) },
            packagePattern = PACKAGE_PATTERN,
            timestamp = { timestamp() },
            notifyTargetChanged = {
                sendBroadcast(Intent(MonitorActions.ACTION_TARGET_CHANGED).setPackage(packageName))
            },
            refreshScreen = {
                if (currentScreen == Screen.CONFIGURATION) showConfigurationPage() else refreshScreen()
            }
        )
    }

    private fun isIgnoringBatteryOptimizations(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        val powerManager = getSystemService(android.os.PowerManager::class.java) ?: return false
        return powerManager.isIgnoringBatteryOptimizations(packageName)
    }

    private fun openBatteryOptimizationSettingsFromConfig() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || isIgnoringBatteryOptimizations()) {
            window.decorView.post { if (!isFinishing && !isDestroyed) configPanel.show() }
            return
        }
        pendingReopenConfigPanelAfterSettings = true
        val requestIntent = Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:$packageName")
        )
        if (!startSystemSettings(requestIntent)) {
            pendingReopenConfigPanelAfterSettings = false
            window.decorView.post { if (!isFinishing && !isDestroyed) configPanel.show() }
        }
    }

    private fun requestAccessibilityPermissionFromConfig() {
        pendingAccessibilitySettingsRefresh = true
        if (!startSystemSettings(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))) {
            pendingAccessibilitySettingsRefresh = false
            window.decorView.post { if (!isFinishing && !isDestroyed) configPanel.show() }
        }
    }

    private fun requestOverlayPermissionFromConfig() {
        if (Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(this)) {
            monitorRepository.setFloatingLogEnabled(true)
            monitorRepository.setOverlayEnabled(true)
            configPanel.show()
            return
        }
        pendingEnableFloatingLogAfterOverlayPermission = true
        if (!startSystemSettings(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
        ) {
            pendingEnableFloatingLogAfterOverlayPermission = false
            window.decorView.post { if (!isFinishing && !isDestroyed) configPanel.show() }
        }
    }

    private fun requestNotificationPermissionFromConfig() {
        if (Build.VERSION.SDK_INT >= 33 && !hasNotificationPermission()) {
            pendingReopenConfigPanelAfterNotificationPermission = true
            requestNotificationPermissionFromSettings()
            return
        }

        // Once the Android 13 runtime permission is granted (or on older Android versions),
        // the user may still have disabled app notifications in system settings. Open that
        // settings page so the status switch is actionable in both directions.
        if (Build.VERSION.SDK_INT >= 24) {
            pendingReopenConfigPanelAfterNotificationPermission = true
            val intent = if (Build.VERSION.SDK_INT >= 26) {
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(
                    Settings.EXTRA_APP_PACKAGE,
                    packageName
                )
            } else {
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
            }
            if (!startSystemSettings(intent)) {
                pendingReopenConfigPanelAfterNotificationPermission = false
                window.decorView.post { if (!isFinishing && !isDestroyed) configPanel.show() }
            }
            return
        }

        Toast.makeText(this, "当前 Android 版本无需单独管理通知发布权限", Toast.LENGTH_SHORT).show()
        window.decorView.post { if (!isFinishing && !isDestroyed) configPanel.show() }
    }

    /** System settings are not the target app, so never leave the floating button over them. */
    private fun startSystemSettings(intent: Intent): Boolean {
        suppressFloatingButtonOnPause = true
        return runCatching {
            startActivity(intent)
            true
        }.getOrElse { error ->
            suppressFloatingButtonOnPause = false
            monitorRepository.addLog(
                timestamp() + "  打开系统设置失败：${error.javaClass.simpleName}: ${error.message.orEmpty()}"
            )
            Toast.makeText(this, "无法打开系统设置，请稍后重试", Toast.LENGTH_LONG).show()
            false
        }
    }

    private fun hasNotificationPermission(): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    /** Reads the real Android notification state; this permission is optional for task startup. */
    private fun isNotificationEnabled(): Boolean = when {
        Build.VERSION.SDK_INT >= 33 -> hasNotificationPermission() &&
            (getSystemService(android.app.NotificationManager::class.java)?.areNotificationsEnabled() ?: false)
        Build.VERSION.SDK_INT >= 24 ->
            getSystemService(android.app.NotificationManager::class.java)?.areNotificationsEnabled() ?: false
        else -> true
    }

    private fun requestNotificationPermissionFromSettings() {
        if (Build.VERSION.SDK_INT < 33) {
            Toast.makeText(this, "当前 Android 版本无需单独申请通知权限", Toast.LENGTH_LONG).show()
            return
        }
        if (hasNotificationPermission()) {
            Toast.makeText(this, "通知权限已允许", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("通知权限用途")
            .setMessage(
                "允许后，Freedom 可以在通知栏显示持续运行状态、停止入口和重启后的恢复提醒。拒绝不会影响无障碍监控本身。"
            )
            .setPositiveButton("申请权限") { _, _ ->
                requestPermissions(
                    arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),
                    REQUEST_NOTIFICATION_PERMISSION
                )
            }
            .setNegativeButton("取消") { _, _ ->
                pendingReopenConfigPanelAfterNotificationPermission = false
                if (currentScreen == Screen.CONFIGURATION) configPanel.show()
            }
            .setOnCancelListener {
                if (pendingReopenConfigPanelAfterNotificationPermission) {
                    pendingReopenConfigPanelAfterNotificationPermission = false
                    if (currentScreen == Screen.CONFIGURATION) configPanel.show()
                }
            }
            .show()
    }

    private fun resolveTargetLaunchIntent(showError: Boolean): Intent? {
        val targetPackage = monitorRepository.targetPackage().trim()
        val launchIntent = targetPackage.takeIf(String::isNotBlank)?.let {
            packageManager.getLaunchIntentForPackage(it)
        }
        if (launchIntent == null && showError) {
            AlertDialog.Builder(this)
                .setTitle("目标应用不可用")
                .setMessage("所选目标应用未安装或没有可启动的主界面，请重新选择目标应用。")
                .setPositiveButton("重新选择") { _, _ -> showTargetPicker() }
                .setNegativeButton("取消", null)
                .show()
        }
        return launchIntent
    }

    private fun startMonitoringAfterPermissionChecks() {
        // Recheck the required accessibility authorization immediately before committing the
        // run intent. Floating-window permission is deliberately not part of this gate.
        if (!isAccessibilityEnabled()) {
            AlertDialog.Builder(this)
                .setTitle("需要开启无障碍服务")
                .setMessage(
                    "Freedom 需要通过系统无障碍服务接收目标应用的界面事件，并读取系统公开的节点信息。请在系统设置中手动开启 Freedom。"
                )
                .setPositiveButton("前往无障碍设置") { _, _ ->
                    startSystemSettings(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }
                .setNegativeButton("取消", null)
                .show()
            return
        }
        val targetIntent = resolveTargetLaunchIntent(showError = true) ?: return
        // A run authorizes observation; only a fresh page can select a business runtime.
        if (!runSessionCommands.requestStart()) return
        val sessionLabel = "统一页面观察"
        monitorRepository.addLog(
            timestamp() + "  ${sessionLabel}已启动；通知发布权限由用户在配置页自主决定"
        )

        if (monitorRepository.isKeepAliveEnabled() && !MonitorKeepAliveService.start(this)) {
            monitorRepository.addLog(timestamp() + "  保活服务未能启动；无障碍监听仍会按系统服务状态工作"
            )
        }

        if (monitorRepository.isFloatingLogEnabled() && !Settings.canDrawOverlays(this)) {
            monitorRepository.setFloatingLogEnabled(false)
            monitorRepository.setOverlayEnabled(false)
            MonitorOverlay.hide()
            monitorRepository.addLog(timestamp() + "  悬浮窗授权当前不可用；已关闭浮动按钮，但不影响监听启动")
        }
        try {
            startActivity(targetIntent)
            monitorRepository.addLog(timestamp() + "  已启动目标应用：${monitorRepository.targetPackage()}"
            )
        } catch (error: Exception) {
            runSessionCommands.requestStop()
            MonitorKeepAliveService.stop(this)
            monitorRepository.addLog(timestamp() + "  启动目标应用失败：${error.javaClass.simpleName}: " +
                    (error.message ?: "无详细信息")
            )
            Toast.makeText(this, "目标应用启动失败，请检查应用是否可用", Toast.LENGTH_LONG).show()
            refreshScreen()
            return
        }
        sendRefresh()
        refreshScreen()
    }

    @Suppress("DEPRECATION")
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_NOTIFICATION_PERMISSION) {
            val granted = hasNotificationPermission()
            monitorRepository.addLog(timestamp() + if (granted) {
                "  通知发布权限已开启"
            } else {
                "  通知发布权限未开启；不影响无障碍运行"
            })
            Toast.makeText(
                this,
                if (granted) "通知发布已开启" else "通知发布未开启；仍可正常运行",
                Toast.LENGTH_LONG
            ).show()
            if (pendingReopenConfigPanelAfterNotificationPermission) {
                pendingReopenConfigPanelAfterNotificationPermission = false
                if (currentScreen == Screen.CONFIGURATION) {
                    window.decorView.post { if (!isFinishing && !isDestroyed) configPanel.show() }
                }
            }
        }
    }

    private fun isAccessibilityEnabled(): Boolean {
        val expected = ComponentName(this, FreedomAccessibilityService::class.java)
        val enabled = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ).orEmpty()
        return enabled.split(':').any { flattened ->
            val component = ComponentName.unflattenFromString(flattened)
            component != null &&
                component.packageName == expected.packageName &&
                component.className == expected.className
        }
    }

    private fun refreshScreen() {
        val running = monitorRepository.isRunRequested()

        // The launcher keeps its top bar free of redundant status text; the action icon
        // still reflects start/stop state, while runtime state remains available elsewhere.
        configurationHome?.refresh(running)
    }

    private fun sessionSampleRunsDirectory(): File {
        val documents = getExternalFilesDir("Documents") ?: filesDir
        return File(documents, "FreedomSamples/runs")
    }

    private fun listSessionSampleArchives(): List<SessionSampleArchive> {
        val runsDirectory = sessionSampleRunsDirectory()
        // Startup recovery can still be running when this action is opened; this pass is idempotent.
        val recovery = sampleArchivePort.recoverPendingRuns(runsDirectory)
        if (recovery.recoveredCount > 0 || recovery.cleanedSealedCount > 0 || recovery.failedCount > 0) {
            monitorRepository.addLog(
                timestamp() + "  采集任务恢复：重新封存 ${recovery.recoveredCount} 条，失败 ${recovery.failedCount} 条"
            )
        }
        return SessionSampleArchiveManager.listSealedArchives(runsDirectory)
    }

    private fun showRecords() {
        LogRecordsDialog.show(
            activity = this,
            records = monitorRepository.logs(),
            sampleRuns = listSessionSampleArchives(),
            dp = { value -> dp(value) },
            onExportLogs = ::launchLogExport,
            onShowDiagnosticReport = { diagnostics.latestReportText() },
            onCleared = {
                monitorRepository.clearLogs()
                refreshScreen()
            }
        )
    }

    /** This is the single entry sheet opened from the green floating-button action. */
    private fun showCollectionExportActions() {
        // Keep recovery and sealed/active classification in one pass so a partially written
        // ZIP is never presented as an exportable archive.
        val sealedArchives = listSessionSampleArchives()
        val runsDirectory = sessionSampleRunsDirectory()
        val activeRunIds = SessionSampleArchiveManager.listActiveRunIds(runsDirectory)
        val runRequested = monitorRepository.isRunRequested()

        val actions = ArrayList<Pair<String, () -> Unit>>()
        actions += "导出最近节点预览 ZIP" to { launchNodeDumpExport() }
        when {
            activeRunIds.isNotEmpty() && monitorRepository.isRunRequested() -> {
                actions += "停止当前采集、封存后导出 ZIP" to {
                    confirmStopCurrentRunAndExport(activeRunIds)
                }
            }
            activeRunIds.isNotEmpty() -> {
                actions += "等待当前采集封存完成并导出 ZIP" to {
                    beginWaitingForSessionArchiveSeal(activeRunIds)
                }
            }
            runRequested -> {
                actions += "停止当前运行并检查采集归档" to {
                    confirmStopCurrentRunAndExport(emptySet())
                }
            }
        }
        if (sealedArchives.isNotEmpty()) {
            actions += "导出已封存采集归档 ZIP（${sealedArchives.size}）" to {
                showSessionSampleArchiveExportPicker()
            }
        } else if (activeRunIds.isEmpty() && !runRequested) {
            actions += "暂无已封存的采集归档" to {
                Toast.makeText(this, "暂无已封存的采集归档", Toast.LENGTH_LONG).show()
            }
        }

        lateinit var dialog: AlertDialog
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(12), dp(18), dp(14))
            background = FreedomUi.roundedSurface(dp(18), FreedomUi.BACKGROUND, FreedomUi.DIVIDER)
            minimumHeight = dp(250)
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(TextView(this).apply {
            text = "采集与导出"
            textSize = 19f
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            setTextColor(FreedomUi.TEXT)
        }, LinearLayout.LayoutParams(0, dp(48), 1f))
        header.addView(TextView(this).apply {
            text = "×"
            textSize = 28f
            gravity = Gravity.CENTER
            setTextColor(FreedomUi.TEXT)
            contentDescription = "关闭采集与导出"
            isClickable = true
            isFocusable = true
            setOnClickListener { dialog.dismiss() }
        }, LinearLayout.LayoutParams(dp(44), dp(44)))
        root.addView(header)
        root.addView(View(this).apply {
            setBackgroundColor(FreedomUi.DIVIDER)
        }, LinearLayout.LayoutParams(-1, dp(1)))

        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(6), 0, dp(6))
        }
        actions.forEachIndexed { index, action ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(58)
                setPadding(dp(4), dp(8), dp(4), dp(8))
                isClickable = true
                isFocusable = true
                background = android.graphics.drawable.RippleDrawable(
                    android.content.res.ColorStateList.valueOf(0x26718E22),
                    GradientDrawable().apply {
                        setColor(Color.TRANSPARENT)
                        cornerRadius = dp(10).toFloat()
                    },
                    GradientDrawable().apply {
                        setColor(Color.WHITE)
                        cornerRadius = dp(10).toFloat()
                    }
                )
                setOnClickListener {
                    dialog.dismiss()
                    action.second.invoke()
                }
            }
            row.addView(TextView(this@MainActivity).apply {
                text = action.first
                textSize = 14f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setTextColor(FreedomUi.TEXT)
                maxLines = 2
                gravity = Gravity.CENTER_VERTICAL
            }, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(TextView(this@MainActivity).apply {
                text = "›"
                textSize = 24f
                gravity = Gravity.CENTER
                setTextColor(FreedomUi.MUTED)
            }, LinearLayout.LayoutParams(dp(28), dp(42)))
            list.addView(row)
            if (index < actions.lastIndex) {
                list.addView(View(this@MainActivity).apply {
                    setBackgroundColor(FreedomUi.DIVIDER)
                }, LinearLayout.LayoutParams(-1, dp(1)))
            }
        }
        root.addView(ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
            clipToPadding = false
            addView(list)
        }, LinearLayout.LayoutParams(-1, 0, 1f))

        dialog = AlertDialog.Builder(this).setView(root).create()
        dialog.show()
        FreedomUi.styleAlertDialog(dialog, this, ::dp, widthFraction = 0.92f)
    }

    private fun showSessionSampleArchiveExportPicker(preselectedRunIds: Set<String> = emptySet()) {
        val archives = listSessionSampleArchives()
        if (archives.isEmpty()) {
            Toast.makeText(this, "暂无已封存的采集归档；正在采集的任务需要先停止并完成封存", Toast.LENGTH_LONG).show()
            return
        }
        val labels = archives.map { archive ->
            "${archive.runId} · ${archive.durationLabel()} · ${archive.sampleCountLabel()}"
        }.toTypedArray()
        val checked = BooleanArray(archives.size) { index -> archives[index].runId in preselectedRunIds }
        AlertDialog.Builder(this)
            .setTitle("选择要导出的采集归档")
            .setMultiChoiceItems(labels, checked) { _, index, isChecked ->
                checked[index] = isChecked
            }
            .setNegativeButton("取消", null)
            .setPositiveButton("下一步") { _, _ ->
                val selected = archives.filterIndexed { index, _ -> checked[index] }
                if (selected.isEmpty()) {
                    Toast.makeText(this, "请至少选择一条采集归档", Toast.LENGTH_SHORT).show()
                } else {
                    confirmSessionSampleExport(selected)
                }
            }
            .show()
    }

    /** Stopping the run is explicit and confirmed; sealing is observed before opening the picker. */
    private fun confirmStopCurrentRunAndExport(activeRunIds: Set<String>) {
        val runRequested = monitorRepository.isRunRequested()
        val message = buildString {
            if (runRequested) {
                append("这会停止当前运行，并让无障碍服务完成本轮采集清理与 ZIP 封存。\n\n")
            } else {
                append("运行请求已经停止，检测到采集目录仍处于封存流程。将等待 ZIP 完成，不会重新启动运行。\n\n")
            }
            if (activeRunIds.isNotEmpty()) {
                append("待封存任务：").append(activeRunIds.joinToString("、")).append("\n\n")
            } else {
                append("当前尚未发现活动采集标记；停止后会检查是否实际生成了本轮 ZIP。\n\n")
            }
            append("只有检测到完整生成的 ZIP 后，才会进入导出选择。")
        }
        AlertDialog.Builder(this)
            .setTitle(if (runRequested) "停止当前运行并封存采集" else "等待采集归档封存")
            .setMessage(message)
            .setNegativeButton("取消", null)
            .setPositiveButton(if (runRequested) "停止并等待封存" else "等待封存") { _, _ ->
                val runsDirectory = sessionSampleRunsDirectory()
                val baselineZipNames = runsDirectory.listFiles()
                    ?.filter { it.isFile && it.extension.equals("zip", ignoreCase = true) }
                    ?.map { it.name }
                    ?.toSet()
                    .orEmpty()
                val requestedAtMillis = System.currentTimeMillis()
                if (runRequested) {
                    runSessionCommands.requestStop()
                    MonitorKeepAliveService.stop(this)
                    sendRefresh()
                    monitorRepository.addLog(timestamp() + "  采集导出：用户确认停止当前运行；等待本轮样本 ZIP 完成封存")
                    refreshScreen()
                }
                beginWaitingForSessionArchiveSeal(
                    targetRunIds = activeRunIds,
                    baselineZipNames = baselineZipNames,
                    requestedAtMillis = requestedAtMillis
                )
            }
            .show()
    }

    private fun beginWaitingForSessionArchiveSeal(
        targetRunIds: Set<String>,
        baselineZipNames: Set<String> = sessionSampleRunsDirectory().listFiles()
            ?.filter { it.isFile && it.extension.equals("zip", ignoreCase = true) }
            ?.map { it.name }
            ?.toSet()
            .orEmpty(),
        requestedAtMillis: Long = System.currentTimeMillis()
    ) {
        archiveSealWaitRunnable?.let { archiveSealHandler.removeCallbacks(it) }
        val startedAtMillis = System.currentTimeMillis()
        Toast.makeText(this, "正在等待采集 ZIP 完成封存……", Toast.LENGTH_SHORT).show()

        lateinit var poll: Runnable
        poll = Runnable {
            if (isFinishing || isDestroyed) {
                archiveSealWaitRunnable = null
                return@Runnable
            }
            val runsDirectory = sessionSampleRunsDirectory()
            val candidateFiles = if (targetRunIds.isNotEmpty()) {
                targetRunIds.map { runId -> File(runsDirectory, "$runId.zip") }
                    .filter(File::isFile)
            } else {
                runsDirectory.listFiles()
                    ?.filter { file ->
                        file.isFile && file.extension.equals("zip", ignoreCase = true) &&
                            file.name !in baselineZipNames && file.lastModified() >= requestedAtMillis - 2_000L
                    }
                    .orEmpty()
            }
            // A ZIP may be visible on disk before finishRun() verifies it and removes
            // the source directory/owner marker. Only treat it as sealed after ownership ends.
            val activeRunIds = SessionSampleArchiveManager.listActiveRunIds(runsDirectory)
            val inspected = candidateFiles
                .mapNotNull { archiveFile -> SessionSampleArchiveManager.inspect(archiveFile) }
                .filter { archive -> archive.runId !in activeRunIds }
            val expectedComplete = if (targetRunIds.isNotEmpty()) {
                targetRunIds.all { runId -> inspected.any { it.runId == runId } }
            } else inspected.isNotEmpty()

            if (expectedComplete) {
                archiveSealWaitRunnable = null
                val sealedIds = inspected.map { it.runId }.toSet()
                monitorRepository.addLog(
                    timestamp() + "  采集导出：本轮 ZIP 已完成封存；runIds=${sealedIds.joinToString(",")}"
                )
                Toast.makeText(this, "采集 ZIP 已封存，请选择要导出的归档", Toast.LENGTH_LONG).show()
                showSessionSampleArchiveExportPicker(sealedIds)
                return@Runnable
            }

            val elapsed = System.currentTimeMillis() - startedAtMillis
            if (elapsed >= ARCHIVE_SEAL_WAIT_TIMEOUT_MS) {
                archiveSealWaitRunnable = null
                val newlySealed = SessionSampleArchiveManager.listSealedArchives(runsDirectory)
                    .filter { archive ->
                        archive.file.name !in baselineZipNames &&
                            (targetRunIds.isEmpty() || archive.runId in targetRunIds)
                    }
                if (newlySealed.isNotEmpty()) {
                    Toast.makeText(this, "已发现封存归档，请选择导出", Toast.LENGTH_LONG).show()
                    showSessionSampleArchiveExportPicker(newlySealed.map { it.runId }.toSet())
                } else {
                    monitorRepository.addLog(
                        timestamp() + "  采集导出：等待 ZIP 封存超时；targetRunIds=${targetRunIds.joinToString(",")}"
                    )
                    Toast.makeText(
                        this,
                        "尚未发现本轮已封存 ZIP。请检查运行记录中的“样本包已封存”或封存失败信息；不会访问 Android/data 私有目录。",
                        Toast.LENGTH_LONG
                    ).show()
                    showSessionSampleArchiveExportPicker()
                }
                return@Runnable
            }
            archiveSealHandler.postDelayed(poll, ARCHIVE_SEAL_POLL_INTERVAL_MS)
        }
        archiveSealWaitRunnable = poll
        archiveSealHandler.post(poll)
    }

    /** Open Android's system document picker; no storage permission is required. */
    private fun launchLogExport(records: List<String>) {
        val content = buildLogExportContent(records)
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "text/plain"
            putExtra(Intent.EXTRA_TITLE, createLogExportFileName())
        }
        pendingLogExportContent = content
        try {
            suppressFloatingButtonOnPause = true
            @Suppress("DEPRECATION")
            startActivityForResult(intent, REQUEST_EXPORT_LOGS)
        } catch (error: Exception) {
            suppressFloatingButtonOnPause = false
            pendingLogExportContent = null
            monitorRepository.addLog(timestamp() + "  启动日志导出失败：${error.javaClass.simpleName}: " +
                    (error.message ?: "无详细信息")
            )
            Toast.makeText(this, "无法打开文件保存窗口，请稍后重试", Toast.LENGTH_LONG).show()
        }
    }

    /** Exports the latest structural-only node snapshot through the existing SAF path. */
    private fun launchNodeDumpExport(
        source: com.freedom.core.accessibility.diagnostics.NodeCaptureSource? = null,
        windowId: Int? = null
    ) {
        val archive = LatestAccessibilityNodeDump.createArchive(this, source, windowId)
        if (archive == null) {
            Toast.makeText(this, "暂无可导出的节点快照，请先运行并等待节点预览采集", Toast.LENGTH_LONG).show()
            return
        }
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/zip"
            putExtra(Intent.EXTRA_TITLE, archive.name)
        }
        pendingNodeDumpExportFile = archive
        try {
            suppressFloatingButtonOnPause = true
            @Suppress("DEPRECATION")
            startActivityForResult(intent, REQUEST_EXPORT_NODE_DUMP)
        } catch (error: Exception) {
            suppressFloatingButtonOnPause = false
            pendingNodeDumpExportFile = null
            runCatching { archive.delete() }
            monitorRepository.addLog(timestamp() + "  启动节点 Dump 导出失败：${error.javaClass.simpleName}")
            Toast.makeText(this, "无法打开节点结构保存窗口，请稍后重试", Toast.LENGTH_LONG).show()
        }
    }

    private fun exportNodeDumpArchive(resultCode: Int, data: Intent?) {
        val archive = pendingNodeDumpExportFile
        pendingNodeDumpExportFile = null
        if (resultCode != RESULT_OK || data?.data == null) {
            archive?.let { runCatching { it.delete() } }
            Toast.makeText(this, "已取消节点结构导出", Toast.LENGTH_SHORT).show()
            return
        }
        if (archive == null || !archive.isFile) {
            Toast.makeText(this, "节点结构临时文件已失效，请重新采集", Toast.LENGTH_LONG).show()
            return
        }
        Thread {
            val result = runCatching {
                val output = contentResolver.openOutputStream(data.data!!, "w")
                    ?: throw java.io.IOException("无法打开所选文件")
                output.use { stream -> archive.inputStream().use { it.copyTo(stream) } }
            }
            runCatching { archive.delete() }
            runOnUiThread {
                result.onSuccess {
                    Toast.makeText(this, "节点结构 JSON/TXT/XML 已导出", Toast.LENGTH_LONG).show()
                }.onFailure { error ->
                    monitorRepository.addLog(timestamp() + "  写入节点 Dump ZIP 失败：${error.javaClass.simpleName}")
                    Toast.makeText(this, "节点结构导出失败：${error.message ?: error.javaClass.simpleName}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    /** Confirms a single-run or multi-run export before opening the Android document picker. */
    private fun confirmSessionSampleExport(archives: List<SessionSampleArchive>) {
        if (archives.isEmpty()) {
            Toast.makeText(this, "请先选择已封存的采集任务", Toast.LENGTH_SHORT).show()
            return
        }
        val contentLabels = archives.flatMap { it.contentLabels }.distinct()
        val confirmationContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(8), dp(18), dp(12))
            addView(TextView(this@MainActivity).apply {
                text = buildString {
                    append("采集任务数量：").append(archives.size).append("\n")
                    append("导出方式：合并为一个 ZIP；采集样本和关联的运行诊断按运行 ID 分目录保存")
                        .append("\n\n")
                    archives.take(12).forEachIndexed { index, archive ->
                        append(index + 1).append(". ").append(archive.runId).append("\n")
                        append("   ").append(archive.durationLabel()).append("；")
                            .append(archive.sampleCountLabel()).append("\n")
                    }
                    if (archives.size > 12) append("……另有 ").append(archives.size - 12).append(" 条采集任务\n")
                }
                textSize = 13f
                setTextColor(FreedomUi.SECONDARY)
                setPadding(0, dp(4), 0, dp(10))
            })
            addView(TextView(this@MainActivity).apply {
                text = "包含内容（按归档实际文件检测）"
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(FreedomUi.TEXT)
                setPadding(0, dp(4), 0, dp(4))
            })
            if (contentLabels.isEmpty()) {
                addView(TextView(this@MainActivity).apply {
                    text = "未识别到可展示的数据分类；导出仍会保留归档中的原始文件。"
                    textSize = 12f
                    setTextColor(FreedomUi.MUTED)
                })
            } else {
                contentLabels.forEach { label ->
                    addView(CheckBox(this@MainActivity).apply {
                        text = label
                        isChecked = true
                        isEnabled = false
                        textSize = 13f
                    })
                }
            }
            addView(TextView(this@MainActivity).apply {
                text = "本次导出会尝试附带每条运行对应的 diagnostic_report.txt、诊断事件、计数器及清单；若诊断尚未封存，会在导出清单中标明。"
                textSize = 12f
                setTextColor(FreedomUi.SECONDARY)
                setPadding(0, dp(8), 0, 0)
            })
            addView(TextView(this@MainActivity).apply {
                text = "导出的内容可能包含聊天文本、截图及其他个人信息，请注意隐私与数据安全。"
                textSize = 12f
                setTextColor(FreedomUi.MUTED)
                setPadding(0, dp(12), 0, 0)
            })
        }
        AlertDialog.Builder(this)
            .setTitle(if (archives.size == 1) "导出采集任务" else "批量导出采集任务")
            .setView(ScrollView(this).apply {
                isVerticalScrollBarEnabled = false
                isHorizontalScrollBarEnabled = false
                addView(confirmationContent)
                isFillViewport = true
            })
            .setNegativeButton("取消", null)
            .setPositiveButton("导出 ZIP") { _, _ -> beginSessionSampleExport(archives) }
            .show()
    }

    private fun beginSessionSampleExport(archives: List<SessionSampleArchive>) {
        if (archives.isEmpty()) {
            Toast.makeText(this, "请先选择已封存的采集任务", Toast.LENGTH_SHORT).show()
            return
        }
        val exportDirectory = File(cacheDir, "FreedomExports")
        val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss-SSS", java.util.Locale.US)
            .format(java.util.Date())
        val combinedArchive = File(exportDirectory, "Freedom-运行诊断与会话采集-$stamp.zip")
        val diagnosticsRunsDirectory = File(filesDir, "freedom_diagnostics/runs")
        Toast.makeText(this, "正在整理运行记录和对应诊断报告……", Toast.LENGTH_SHORT).show()
        Thread {
            val result = runCatching {
                archives.mapNotNull { it.diagnosticRunId }.distinct().forEach { diagnosticRunId ->
                    diagnostics.awaitFinalizedRun(diagnosticRunId)
                }
                SessionSampleArchiveManager.createExportBundle(
                    archives = archives,
                    diagnosticsRunsDirectory = diagnosticsRunsDirectory,
                    destination = combinedArchive
                )
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) {
                    runCatching { combinedArchive.delete() }
                } else {
                    result.onSuccess {
                        launchSessionSampleSavePicker(combinedArchive, temporary = true)
                    }.onFailure { error ->
                        runCatching { combinedArchive.delete() }
                        monitorRepository.addLog(timestamp() + "  运行诊断与会话样本打包失败：${error.javaClass.simpleName}: " +
                            (error.message ?: "无详细信息"))
                        Toast.makeText(this, "运行诊断 ZIP 生成失败：${error.message ?: error.javaClass.simpleName}", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }.start()
    }

    /** Use Android's Storage Access Framework; no broad storage permission is required. */
    private fun launchSessionSampleSavePicker(archive: File, temporary: Boolean) {
        if (!archive.isFile) {
            Toast.makeText(this, "运行诊断与采集 ZIP 已不存在，请刷新运行记录", Toast.LENGTH_LONG).show()
            return
        }
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/zip"
            putExtra(Intent.EXTRA_TITLE, archive.name)
        }
        pendingSessionSampleExportFile = archive
        pendingSessionSampleExportTemporary = temporary
        try {
            suppressFloatingButtonOnPause = true
            @Suppress("DEPRECATION")
            startActivityForResult(intent, REQUEST_EXPORT_SESSION_SAMPLES)
        } catch (error: Exception) {
            suppressFloatingButtonOnPause = false
            pendingSessionSampleExportFile = null
            pendingSessionSampleExportTemporary = false
            if (temporary) runCatching { archive.delete() }
            monitorRepository.addLog(timestamp() + "  启动会话样本导出失败：${error.javaClass.simpleName}")
            Toast.makeText(this, "无法打开文件保存窗口，请稍后重试", Toast.LENGTH_LONG).show()
        }
    }

    private fun exportSessionSampleArchive(resultCode: Int, data: Intent?) {
        val archive = pendingSessionSampleExportFile
        val temporary = pendingSessionSampleExportTemporary
        pendingSessionSampleExportFile = null
        pendingSessionSampleExportTemporary = false
        if (resultCode != RESULT_OK || data?.data == null) {
            if (temporary) archive?.let { runCatching { it.delete() } }
            Toast.makeText(this, "已取消运行诊断与采集导出", Toast.LENGTH_SHORT).show()
            return
        }
        if (archive == null || !archive.isFile) {
            Toast.makeText(this, "运行诊断与采集 ZIP 已不存在，请刷新运行记录后重试", Toast.LENGTH_LONG).show()
            return
        }
        val destinationUri = data.data!!
        Thread {
            val result = runCatching {
                val output = contentResolver.openOutputStream(destinationUri, "w")
                    ?: throw java.io.IOException("无法打开所选文件")
                archive.inputStream().use { input ->
                    output.use { stream ->
                        input.copyTo(stream)
                        stream.flush()
                    }
                }
            }
            runOnUiThread {
                if (temporary) runCatching { archive.delete() }
                result.onSuccess {
                    Toast.makeText(this, "会话采集 ZIP 已导出", Toast.LENGTH_LONG).show()
                }.onFailure { error ->
                    monitorRepository.addLog(timestamp() + "  写入会话样本 ZIP 失败：${error.javaClass.simpleName}")
                    Toast.makeText(this, "样本 ZIP 导出失败：${error.message ?: error.javaClass.simpleName}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun buildLogExportContent(records: List<String>): String = buildString {
        append("Freedom 运行日志\n")
        append("导出时间：")
        append(
            java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
                .format(java.util.Date())
        )
        append("\n记录数量：").append(records.size)
        append("\n导出内容：包含完整技术详情\n")
        append("=".repeat(72)).append("\n\n")
        append(LogTextFormatter.formatLogLines(records, includeTechnicalDetails = true))
        if (records.isNotEmpty()) append('\n')
    }

    private fun createLogExportFileName(): String {
        val time = java.text.SimpleDateFormat(
            "yyyyMMdd-HHmmss",
            java.util.Locale.getDefault()
        ).format(java.util.Date())
        return "Freedom-运行日志-$time.txt"
    }

    @Deprecated("Use the document picker result to write the selected URI")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_IMAGE_RESOURCE_IMPORT) {
            if (resultCode == RESULT_OK && data != null) {
                val selectedUris = buildList {
                    data.data?.let(::add)
                    data.clipData?.let { clip ->
                        for (index in 0 until clip.itemCount) clip.getItemAt(index).uri?.let(::add)
                    }
                }.distinct()
                if (selectedUris.isNotEmpty()) {
                    if (imagePoolOperationRunning) {
                        Toast.makeText(this, "图片池正在处理，请稍后重试", Toast.LENGTH_SHORT).show()
                    } else {
                        imagePoolOperationRunning = true
                        Toast.makeText(this, "正在导入图片到 Freedom 专用相册", Toast.LENGTH_SHORT).show()
                        Thread({
                            val result = imagePoolResources.importImages(selectedUris.map(Uri::toString))
                            runOnUiThread {
                                val current = taskLaunchParametersStore.read()
                                val updated = current.withImportedImageResourceUris(result.publishedUris)
                                taskLaunchParametersStore.write(updated)
                                imagePoolOperationRunning = false
                                if (currentScreen == Screen.PERSONALIZATION) showPersonalizationPage()
                                val added = updated.imageResourceUris.size - current.imageResourceUris.size
                                val message = if (result.unsupportedSystem) {
                                    "Android 9 及以下未授权公共媒体写入；未导入图片"
                                } else {
                                    "新增 $added 张图片；失败 ${result.failedCount} 张"
                                }
                                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                            }
                        }, "Freedom-Image-Pool-Import").apply { isDaemon = true }.start()
                    }
                }
            }
            return
        }
        if (requestCode == REQUEST_OCR_IMAGE_TEST) {
            if (resultCode == RESULT_OK) {
                val resultIntent = data
                val selectedUri = resultIntent?.data
                if (resultIntent != null && selectedUri != null) {
                    val grantedFlags = resultIntent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION
                    if (grantedFlags != 0) {
                        runCatching {
                            contentResolver.takePersistableUriPermission(selectedUri, grantedFlags)
                        }.onFailure { failure ->
                            monitorRepository.addLog(
                                timestamp() + "  OCR 图片选择授权无法持久化，尝试使用临时授权：" +
                                    (failure.message ?: failure.javaClass.simpleName)
                            )
                        }
                    }
                    runOcrImageTest(selectedUri)
                } else {
                    Toast.makeText(this, "未取得所选图片", Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(this, "已取消 OCR 图片测试", Toast.LENGTH_SHORT).show()
            }
            return
        }
        if (requestCode == REQUEST_EXPORT_SESSION_SAMPLES) {
            exportSessionSampleArchive(resultCode, data)
            return
        }
        if (requestCode == REQUEST_EXPORT_NODE_DUMP) {
            exportNodeDumpArchive(resultCode, data)
            return
        }
        if (requestCode != REQUEST_EXPORT_LOGS) return

        val content = pendingLogExportContent
        pendingLogExportContent = null
        if (resultCode != RESULT_OK || data?.data == null) {
            Toast.makeText(this, "已取消日志导出", Toast.LENGTH_SHORT).show()
            return
        }
        if (content == null) {
            Toast.makeText(this, "导出内容已失效，请重新导出", Toast.LENGTH_LONG).show()
            return
        }

        try {
            val uri = data.data!!
            val output = contentResolver.openOutputStream(uri, "w")
                ?: throw java.io.IOException("无法打开所选文件")
            output.use { stream ->
                stream.write(content.toByteArray(Charsets.UTF_8))
                stream.flush()
            }
            Toast.makeText(this, "运行记录已导出", Toast.LENGTH_LONG).show()
        } catch (error: Exception) {
            monitorRepository.addLog(timestamp() + "  写入日志文件失败：${error.javaClass.simpleName}: " +
                    (error.message ?: "无详细信息")
            )
            Toast.makeText(
                this,
                "日志导出失败：${error.message ?: error.javaClass.simpleName}",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    // Retained utility views from the existing Activity implementation.
    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(14), dp(13), dp(14), dp(13))
        background = roundedBackground(Color.WHITE, dp(12).toFloat())
        elevation = dp(1).toFloat()
        layoutParams = LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(12)
        }
    }

    private fun sectionTitle(text: String) = TextView(this).apply {
        this.text = text
        textSize = 15f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(FreedomUi.TEXT)
    }

    private fun roundedBackground(color: Int, radius: Float) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius
        setStroke(1, FreedomUi.DIVIDER)
    }

    private fun sendRefresh() {
        sendBroadcast(Intent(MonitorActions.ACTION_REFRESH).setPackage(packageName))
    }

    private fun timestamp(): String =
        java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.getDefault())
            .format(java.util.Date())

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()

    companion object {
        private const val REQUEST_IMAGE_RESOURCE_IMPORT = 2007
        private const val REQUEST_OCR_IMAGE_TEST = 2006
        private const val REQUEST_EXPORT_LOGS = 2002
        private const val REQUEST_NOTIFICATION_PERMISSION = 2003
        private const val REQUEST_EXPORT_SESSION_SAMPLES = 2004
        private const val REQUEST_EXPORT_NODE_DUMP = 2005
        private const val ARCHIVE_SEAL_WAIT_TIMEOUT_MS = 45_000L
        private const val ARCHIVE_SEAL_POLL_INTERVAL_MS = 350L
        private const val OCR_PREFLIGHT_COOLDOWN_MS = 10_000L
        private val PACKAGE_PATTERN = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$")
    }
}
