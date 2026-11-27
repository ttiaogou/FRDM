package com.freedom.architecture

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Guards the two-entry floating menu, global collector entry, and in-place target-app overlays. */
class FloatingActionEntryPointBoundaryTest {
    private val projectRoot: File
        get() = File(System.getProperty("user.dir")).let { start ->
            generateSequence(start) { it.parentFile }
                .firstOrNull { File(it, "settings.gradle.kts").isFile }
                ?: start
        }

    private fun source(path: String): String = TestSourceResolver.resolve(projectRoot, path).readText()

    @Test
    fun globalNodeCollectionHasExactlyOneEntryInTheFloatingMenu() {
        val overlay = source("app/src/main/java/com/freedom/adapters/accessibility/android/MonitorOverlay.kt")
        val menuFactory = source("app/src/main/java/com/freedom/adapters/accessibility/android/MonitorOverlayMenuViewFactory.kt")
        val actions = source("app/src/main/java/com/freedom/adapters/android/MonitorActions.kt")
        val main = source("app/src/main/java/com/freedom/MainActivity.kt")
        val home = source("app/src/main/java/com/freedom/ui/config/ConfigurationHome.kt")
        val records = source("app/src/main/java/com/freedom/ui/logs/LogRecordsDialog.kt")

        assertTrue(menuFactory.contains("icon = R.drawable.ic_floating_visualizer"))
        assertTrue(menuFactory.contains("requestNodeBounds(context)"))
        assertTrue(overlay.contains("requestNodeBounds = ::requestGlobalNodeLayoutBounds"))
        assertTrue(overlay.contains("ACTION_SHOW_GLOBAL_NODE_LAYOUT_BOUNDS"))
        assertTrue("Only the visualization callback may initiate global node collection", overlay.split("ACTION_SHOW_GLOBAL_NODE_LAYOUT_BOUNDS").size - 1 == 1)
        assertFalse(overlay.contains("MainActivity::class.java"))
        assertFalse(overlay.contains("ACTION_OPEN_NODE_VISUALIZER"))
        assertFalse(home.contains("节点可视化") || home.contains("onVisualizeNodes"))
        assertFalse(records.contains("打开节点可视化") || records.contains("onVisualizeNodes"))
        assertFalse(main.contains("ACTION_OPEN_NODE_VISUALIZER"))
        assertFalse(main.contains("showNodeInspector(") || main.contains("NodeInspectorDialog"))
        assertTrue(actions.contains("ACTION_SHOW_GLOBAL_NODE_LAYOUT_BOUNDS"))
        assertTrue(actions.contains("ACTION_SHOW_GLOBAL_NODE_HIERARCHY"))
    }

    @Test
    fun twoFloatingActionsUseDistinctIconsAndSemiCircularGeometry() {
        val overlay = source("app/src/main/java/com/freedom/adapters/accessibility/android/MonitorOverlay.kt")
        val menuFactory = source("app/src/main/java/com/freedom/adapters/accessibility/android/MonitorOverlayMenuViewFactory.kt")
        val createMenu = menuFactory.substringAfter("fun create(context: Context): FrameLayout")
            .substringBefore("/** Places the two icon-only actions")

        assertTrue(createMenu.contains("icon = R.drawable.ic_floating_visualizer"))
        assertTrue(createMenu.contains("requestNodeBounds(context)"))
        assertTrue(createMenu.contains("icon = R.drawable.ic_floating_export"))
        assertTrue(createMenu.contains("openCollectionExport(context)"))
        assertFalse(createMenu.contains("ic_floating_logs"))
        assertFalse(createMenu.contains("showRunLog"))
        assertFalse("The former window must not be reachable from the overlay", overlay.contains("FloatingRunLogWindow"))
        assertTrue("Exactly two real entries should be attached", createMenu.split("menu.addView(").size - 1 == 2)
        assertTrue(overlay.contains("MENU_ACTION_ANGLES = listOf(-45f, 45f)"))
        assertTrue(overlay.contains("actionAngles = MENU_ACTION_ANGLES"))
        assertTrue(menuFactory.contains("RadialMenuGeometry.fanPositions("))
        assertTrue(overlay.contains("MENU_ACTION_SIZE_DP = 60"))
        assertFalse(createMenu.contains("ic_floating_hierarchy"))
        assertFalse(createMenu.contains("ic_floating_clear"))
        assertFalse(createMenu.contains("ic_floating_close"))
        assertFalse(createMenu.contains("ic_floating_more"))
    }

    @Test
    fun nodeHierarchyUsesTheLastDetachedSnapshotWithoutStartingAnotherCollection() {
        val overlay = source("app/src/main/java/com/freedom/adapters/accessibility/android/MonitorOverlay.kt")
        val useCase = source("app/src/main/java/com/freedom/application/monitor/GlobalNodeLayoutVisualizationUseCase.kt")
        val service = source("app/src/main/java/com/freedom/accessibility/FreedomAccessibilityService.kt")
        val hierarchy = source("app/src/main/java/com/freedom/adapters/accessibility/android/NodeHierarchyOverlay.kt")

        assertTrue(overlay.contains("ACTION_SHOW_GLOBAL_NODE_HIERARCHY"))
        assertTrue(useCase.contains("fun snapshots(): List<AccessibilityTreeSnapshot> = latestSnapshots"))
        assertTrue(service.contains("NodeHierarchyOverlay.show(this, snapshots)"))
        assertTrue(hierarchy.contains("TYPE_ACCESSIBILITY_OVERLAY"))
        assertTrue(hierarchy.contains("PopupMenu(context, anchor)"))
        assertTrue(hierarchy.contains("复制节点路径"))
        assertFalse(hierarchy.contains("startActivity("))
    }

    @Test
    fun tappingVisualizationKeepsTheCurrentAppInPlaceAndCanClearTheOverlay() {
        val overlay = source("app/src/main/java/com/freedom/adapters/accessibility/android/MonitorOverlay.kt")
        val menuFactory = source("app/src/main/java/com/freedom/adapters/accessibility/android/MonitorOverlayMenuViewFactory.kt")
        val service = source("app/src/main/java/com/freedom/accessibility/FreedomAccessibilityService.kt")
        val useCase = source("app/src/main/java/com/freedom/application/monitor/GlobalNodeLayoutVisualizationUseCase.kt")

        val visualization = overlay.substringAfter("private fun requestGlobalNodeLayoutBounds(context: Context)")
            .substringBefore("private fun clearGlobalNodeLayoutBounds")
        assertTrue(visualization.contains("sendBroadcast("))
        assertFalse(visualization.contains("startActivity("))
        assertTrue(service.contains("handleGlobalNodeLayoutBoundsRequest()"))
        assertTrue(useCase.contains("showInteractiveNodeLayoutBounds(snapshots, label)"))
        assertTrue(service.contains("ACTION_CLEAR_GLOBAL_NODE_LAYOUT_BOUNDS -> {"))
        assertTrue(service.contains("NodeHierarchyOverlay.clear()"))
        assertFalse(overlay.contains("ACTION_MANUAL_NODE_CAPTURE"))
        assertTrue("The green floating action must open the existing collection/export flow", menuFactory.contains("openCollectionExport(context)"))
        assertTrue("Window owner must inject the existing export callback", overlay.contains("openCollectionExport = ::openCollectionExport"))
        assertTrue(overlay.contains("ACTION_OPEN_CAPTURE_EXPORT"))
        assertTrue(overlay.contains("采集与 ZIP 导出"))
        assertTrue("Node hierarchy remains available from the more menu", overlay.contains("menu.add(\"查看节点层级\")"))
    }

    @Test
    fun floatingCollectionExportActionTargetsMainActivityThroughItsExistingIntentContract() {
        val overlay = source("app/src/main/java/com/freedom/adapters/accessibility/android/MonitorOverlay.kt")
        val menuFactory = source("app/src/main/java/com/freedom/adapters/accessibility/android/MonitorOverlayMenuViewFactory.kt")
        val main = source("app/src/main/java/com/freedom/MainActivity.kt")
        val actions = source("app/src/main/java/com/freedom/adapters/android/MonitorActions.kt")
        val menu = menuFactory.substringAfter("fun create(context: Context): FrameLayout")
            .substringBefore("/** Places the two icon-only actions")

        assertTrue(menu.contains("R.drawable.ic_floating_visualizer"))
        assertTrue(menu.contains("R.drawable.ic_floating_export"))
        assertTrue(menu.contains("openCollectionExport(context)"))
        assertFalse(menu.contains("R.drawable.ic_floating_logs"))
        assertFalse(menu.substringAfter("ic_floating_export").substringBefore("menu.addView(")
            .contains("requestGlobalNodeLayoutBounds(context)"))
        assertTrue(overlay.contains("Intent(MonitorActions.ACTION_OPEN_CAPTURE_EXPORT)"))
        assertTrue(overlay.contains("setClassName(app.packageName, \"${'$'}{app.packageName}.MainActivity\")"))
        assertTrue(overlay.contains("Intent.FLAG_ACTIVITY_NEW_TASK"))
        assertTrue(overlay.contains("Intent.FLAG_ACTIVITY_CLEAR_TOP"))
        assertTrue(overlay.contains("Intent.FLAG_ACTIVITY_SINGLE_TOP"))
        assertTrue(main.contains("enqueueFloatingAction(intent)"))
        assertTrue(main.contains("MonitorActions.ACTION_OPEN_CAPTURE_EXPORT -> showCollectionExportActions()"))
        assertTrue(actions.contains("const val ACTION_OPEN_CAPTURE_EXPORT"))
    }

    @Test
    fun collectionExportWaitsForActiveRunSealingBeforeOfferingSafExport() {
        val main = source("app/src/main/java/com/freedom/MainActivity.kt")
        val archiveManager = source("app/src/main/java/com/freedom/ui/logs/SessionSampleArchiveManager.kt")

        assertTrue(main.contains("停止当前采集、封存后导出 ZIP"))
        assertTrue(main.contains("等待当前采集封存完成并导出 ZIP"))
        assertTrue(main.contains("confirmStopCurrentRunAndExport(activeRunIds)"))
        assertTrue(main.contains("beginWaitingForSessionArchiveSeal(activeRunIds)"))
        assertTrue(main.contains("SessionSampleArchiveManager.listSealedArchives(runsDirectory)"))
        assertTrue(main.contains("SessionSampleArchiveManager.listActiveRunIds(runsDirectory)"))
        assertTrue(main.contains("Intent.ACTION_CREATE_DOCUMENT"))
        assertTrue(archiveManager.contains("fun listSealedArchives("))
        assertTrue(archiveManager.contains("File(runsDirectory, runId).isDirectory"))
    }

    @Test
    fun notificationPublishingIsUserManagedAndDoesNotGateRunStartup() {
        val activity = source("app/src/main/java/com/freedom/MainActivity.kt")
        val config = source("app/src/main/java/com/freedom/ui/config/ConfigPanel.kt")
        val toggle = activity.substringAfter("private fun toggleMonitoring()")
            .substringBefore("private fun showTargetEditor()")
        val start = activity.substringAfter("private fun startMonitoringAfterPermissionChecks()")
            .substringBefore("@Suppress(\"DEPRECATION\")\n    override fun onRequestPermissionsResult")

        assertTrue(config.contains("通知发布"))
        assertTrue(config.contains("isNotificationEnabled"))
        assertTrue(config.contains("requestNotificationPermission"))
        assertTrue(toggle.contains("startMonitoringAfterPermissionChecks()"))
        assertFalse(start.contains("hasNotificationPermission()"))
        assertFalse(start.contains("isNotificationEnabled()"))
    }

    @Test
    fun collectionExportUsesTheExistingRuntimeLogRepositoryWithoutLiveLogWindows() {
        val overlay = source("app/src/main/java/com/freedom/adapters/accessibility/android/MonitorOverlay.kt")
        val main = source("app/src/main/java/com/freedom/MainActivity.kt")
        val dialog = source("app/src/main/java/com/freedom/ui/logs/LogRecordsDialog.kt")
        val home = source("app/src/main/java/com/freedom/ui/config/ConfigurationHome.kt")
        assertFalse(overlay.contains("FloatingRunLogWindow"))
        assertFalse(main.contains("LogRecordsDialog.show("))
        assertFalse(main.contains("diagnostics.latestReportText()"))
        assertFalse(dialog.contains("onShowDiagnosticReport"))
        assertFalse(dialog.contains("onCleared"))
        assertTrue(main.contains("launchLogExport()"))
        assertTrue(main.contains("monitorRepository.snapshotDisplayLogsForExport()"))
        assertTrue(main.contains("onCollectionExport = ::showCollectionExportActions"))
        assertTrue(home.contains("callbacks.onCollectionExport()"))
        assertTrue(dialog.contains("stylePrimaryExportButton"))
    }

    @Test
    fun imageAndInterceptionThresholdsLiveInPersonalizationAndPersistAsPreferences() {
        val home = source("app/src/main/java/com/freedom/ui/config/ConfigurationHome.kt")
        val homeOnly = home
        val personalization = source("app/src/main/java/com/freedom/ui/config/PersonalizationScreen.kt")
        val model = source("app/src/main/java/com/freedom/application/TaskLaunchParameters.kt")
        val store = source("app/src/main/java/com/freedom/data/TaskLaunchParametersStore.kt")

        assertFalse(homeOnly.contains("回复数量限制"))
        assertTrue(personalization.contains("messageInterceptionPreferenceRow()"))
        assertTrue(personalization.contains("图片预回复"))
        assertTrue(model.contains("messageInterceptionThreshold"))
        assertTrue(model.contains("imageSendThreshold"))
        assertTrue(model.contains("imageResourceUris"))
        assertTrue(store.contains("KEY_MESSAGE_INTERCEPTION_THRESHOLD"))
        assertTrue(store.contains("KEY_IMAGE_SEND_THRESHOLD"))
        assertTrue(store.contains("KEY_IMAGE_RESOURCE_URIS"))
        assertTrue(personalization.contains("回复消息满足后发送图片 / 0 不开启"))
        assertTrue(personalization.contains("onImageResourceImportRequested"))
    }

    @Test
    fun chatPersonalizationUsesProviderNeutralDropdownsAndModelSettingsLiveInConfigMenu() {
        val personalization = source("app/src/main/java/com/freedom/ui/config/PersonalizationScreen.kt")
        val preferences = source("app/src/main/java/com/freedom/features/aireply/ai/AiReplyPersonalization.kt")
        val configMenu = source("app/src/main/java/com/freedom/ui/config/ConfigPanel.kt")
        val modelScreen = source("app/src/main/java/com/freedom/ui/config/AiModelConfigurationScreen.kt")
        val activity = source("app/src/main/java/com/freedom/MainActivity.kt")

        listOf("性格风格", "语言风格", "回复长度", "情绪表达程度", "幽默程度", "主动互动程度")
            .forEach { assertTrue(personalization.contains("label = \"$it\"")) }
        assertTrue(personalization.contains("Spinner.MODE_DROPDOWN"))
        assertTrue(personalization.contains("InputFilter.LengthFilter"))
        assertTrue(preferences.contains("systemPromptInstructions()"))
        assertTrue(preferences.contains("MAX_CUSTOM_INSTRUCTION_LENGTH = 500"))
        assertTrue(configMenu.contains("AI 模型配置"))
        assertTrue(configMenu.contains("callbacks.onAiModelConfiguration()"))
        assertTrue(activity.contains("onAiModelConfiguration = ::showAiModelConfigurationPage"))
        assertFalse(modelScreen.contains("private fun cloudConsentPreferenceRow()"))
        assertFalse(modelScreen.contains("private fun confirmClearApiKey()"))
        assertFalse(modelScreen.contains("private val cloudConsentGranted:"))
        assertFalse(modelScreen.contains("private val onClearApiKey:"))
        assertTrue(modelScreen.contains("onCloudConsentChanged(next.apiKey.isNotBlank())"))
        assertTrue(modelScreen.contains("apiKey = input.text.toString().trim()"))
        assertTrue(modelScreen.contains("onSave(next).fold("))
        assertTrue(activity.contains("onCloudConsentChanged = aiModelCloudConsent::setGranted"))
        assertFalse(activity.contains("onClearApiKey ="))
        assertFalse(activity.contains("cloudConsentGranted ="))
        assertFalse(personalization.contains("modelConfigurationPreferenceRow()"))
        assertFalse(personalization.contains("cloudConsentPreferenceRow()"))
    }

    @Test
    fun configurationSectionsUseTheFinalShortLabels() {
        val config = source("app/src/main/java/com/freedom/ui/config/ConfigPanel.kt")
        assertTrue(config.contains("addSectionTitle(body, \"配置\")"))
        assertTrue(config.contains("addSectionTitle(body, \"权限\", top = 24)"))
        assertFalse(config.contains("脚本配置"))
        assertFalse(config.contains("权限设置"))
    }

    @Test
    fun floatingLauncherStillOwnsItsWindowAndTouchLifecycle() {
        val overlay = source("app/src/main/java/com/freedom/adapters/accessibility/android/MonitorOverlay.kt")
        val config = source("app/src/main/java/com/freedom/ui/config/ConfigPanel.kt")

        assertTrue(config.contains("浮动按钮"))
        assertTrue(overlay.contains("R.drawable.ic_floating_launcher"))
        assertTrue(overlay.contains("setOnClickListener { handleLauncherClick(context, it) }"))
        assertTrue(overlay.contains("ViewConfiguration.getDoubleTapTimeout()"))
        assertTrue(overlay.contains("stopRunFromFloatingButton(context)"))
        assertTrue(overlay.contains("returnToMainConfiguration(app)"))
        assertTrue(overlay.contains("private fun handleLauncherTouch(context: Context, view: View, event: MotionEvent)"))
        assertTrue(overlay.contains("private fun snapToNearestEdge(context: Context)"))
        assertTrue(overlay.contains("RunSessionCommandUseCase(state, onDiagnostic = state::addLog).requestStop()"))
        assertTrue(overlay.contains("FloatingButtonPlacement.menuOrigin"))
        assertTrue(overlay.contains("Failed to show radial action menu"))
        assertTrue(overlay.contains("WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS"))
        assertTrue(overlay.contains("view.animate()"))
    }
    @Test
    fun globalBoundsOverlaySupportsNodeSelectionAndContextActions() {
        val overlay = source("app/src/main/java/com/freedom/adapters/accessibility/android/NodeBoundsHighlightOverlay.kt")
        val hitTester = source("app/src/main/java/com/freedom/core/accessibility/overlay/NodeBoundsHitTester.kt")
        val port = source("app/src/main/java/com/freedom/core/accessibility/overlay/OverlayPort.kt")

        assertTrue(overlay.contains("fun showInteractiveAll("))
        assertTrue(overlay.contains("WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL"))
        assertTrue(overlay.contains("MotionEvent.ACTION_DOWN"))
        assertTrue(overlay.contains("MotionEvent.ACTION_UP"))
        assertTrue(overlay.contains("NodeBoundsHitTester.find("))
        assertTrue(overlay.contains("private fun selectNode("))
        assertTrue(overlay.contains("showNodeContextMenu("))
        assertTrue(overlay.contains("显示节点信息"))
        assertTrue(overlay.contains("复制节点路径"))
        assertTrue(overlay.contains("复制资源 ID"))
        assertTrue(overlay.contains("复制边界坐标"))
        assertTrue(overlay.contains("selectedStrokePaint"))
        assertTrue(overlay.contains("ACTION_CLEAR_GLOBAL_NODE_LAYOUT_BOUNDS"))
        assertTrue(overlay.contains("点击选择 / 长按菜单"))
        assertTrue(hitTester.contains("thenByDescending { it.path.childIndices.size }"))
        assertTrue(port.contains("showInteractiveNodeLayoutBounds("))
    }

    @Test
    fun filterPanelPersistsActivityTimeAlongsideDistanceAndAgePreferences() {
        val home = source("app/src/main/java/com/freedom/ui/config/ConfigurationHome.kt")
        val model = source("app/src/main/java/com/freedom/application/TaskLaunchParameters.kt")
        val store = source("app/src/main/java/com/freedom/data/TaskLaunchParametersStore.kt")
        val policy = source("business/src/main/kotlin/com/freedom/features/aireply/policy/ConversationFilterPolicy.kt")

        assertTrue(home.contains("筛选条件"))
        assertTrue(home.contains("text = \"活跃时间\""))
        assertTrue(home.contains("text = \"分钟（0为不限）\""))
        val activeRow = home.substringAfter("val activeTimeRow =").substringBefore("val distanceRow =")
        assertTrue(activeRow.contains("setOnClickListener"))
        assertTrue(activeRow.contains("showNumberEditor("))
        assertTrue(activeRow.contains("title = \"活跃时间\""))
        assertTrue(activeRow.contains("value = parameters.maxLastActiveMinutes"))
        assertTrue(activeRow.contains("suffix = \"分钟\""))
        assertTrue(activeRow.contains("value < 0"))
        assertTrue(activeRow.contains("saveParameters(parameters.copy(maxLastActiveMinutes = value))"))
        assertTrue(home.contains("activeTimeValue?.apply"))
        assertTrue(home.contains("text = updated.maxLastActiveMinutes.toString()"))
        assertTrue(home.contains("callbacks.onTaskParametersChanged(updated)"))
        assertTrue(home.contains("private fun showNumberEditor("))
        assertTrue(home.contains("AlertDialog.Builder(activity)"))
        assertTrue(home.contains("onSave(parsed)"))

        assertTrue(model.contains("val maxLastActiveMinutes: Int = ConversationFilterSettings.DEFAULT_MAX_LAST_ACTIVE_MINUTES"))
        assertTrue(policy.contains("const val DEFAULT_MAX_LAST_ACTIVE_MINUTES = 60"))
        assertTrue(store.contains("KEY_MAX_LAST_ACTIVE_MINUTES = \"max_last_active_minutes\""))
        assertTrue(store.contains("maxLastActiveMinutes = readMaxLastActiveMinutes()"))
        assertTrue(store.contains("val default = ConversationFilterSettings.DEFAULT_MAX_LAST_ACTIVE_MINUTES"))
        assertTrue(store.contains("if (!preferences.contains(KEY_MAX_LAST_ACTIVE_MINUTES))"))
        val migration = store.substringAfter("if (!preferences.contains(KEY_MAX_LAST_ACTIVE_MINUTES))").substringBefore("return preferences.getInt")
        assertTrue(migration.contains("putInt(KEY_MAX_LAST_ACTIVE_MINUTES, default)"))
        assertTrue(migration.contains(".remove(LEGACY_KEY_ONLINE_ONLY).apply()"))
        assertTrue(store.contains("getInt(KEY_MAX_LAST_ACTIVE_MINUTES, default).coerceAtLeast(0)"))
        assertTrue(store.contains("putInt(KEY_MAX_LAST_ACTIVE_MINUTES, parameters.maxLastActiveMinutes.coerceAtLeast(0))"))
        assertTrue(store.contains("LEGACY_KEY_ONLINE_ONLY = \"online_only\""))

        assertFalse(home.contains("在线状态"))
        assertFalse(home.contains("filterChip(\"不限\""))
        assertFalse(home.contains("filterChip(\"仅在线\""))
        assertFalse(home.contains("onlineOnly"))
        assertFalse(model.contains("onlineOnly"))
        assertFalse(store.contains("getBoolean(KEY_ONLINE_ONLY"))
        assertFalse(store.contains("getBoolean(LEGACY_KEY_ONLINE_ONLY"))
        assertFalse(store.contains("putBoolean(KEY_ONLINE_ONLY"))
        assertFalse(store.contains("putBoolean(LEGACY_KEY_ONLINE_ONLY"))

        assertTrue(home.contains("title = \"最大距离\""))
        assertTrue(home.contains("title = \"最低年龄\""))
        assertTrue(home.contains("title = \"最高年龄\""))
        assertTrue(home.contains("saveParameters(parameters.copy(maxDistanceKm = value))"))
        assertTrue(home.contains("saveParameters(parameters.copy(minAge = value))"))
        assertTrue(home.contains("saveParameters(parameters.copy(maxAge = value))"))
        assertTrue(model.contains("val maxDistanceKm: Int = 0"))
        assertTrue(model.contains("val minAge: Int = 0"))
        assertTrue(model.contains("val maxAge: Int = 0"))
        assertTrue(store.contains("getInt(KEY_MAX_DISTANCE_KM, 0).coerceAtLeast(0)"))
        assertTrue(store.contains("getInt(KEY_MIN_AGE, 0).coerceIn(0, 120)"))
        assertTrue(store.contains("getInt(KEY_MAX_AGE, 0).coerceIn(0, 120)"))
        assertTrue(store.contains("putInt(KEY_MAX_DISTANCE_KM, parameters.maxDistanceKm.coerceAtLeast(0))"))
        assertTrue(store.contains("putInt(KEY_MIN_AGE, parameters.minAge.coerceIn(0, 120))"))
        assertTrue(store.contains("putInt(KEY_MAX_AGE, parameters.maxAge.coerceIn(0, 120))"))
    }

}
