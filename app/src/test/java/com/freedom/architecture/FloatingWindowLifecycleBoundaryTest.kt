package com.freedom.architecture

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Guards the WindowManager ownership, recovery and interaction model of the floating launcher. */
class FloatingWindowLifecycleBoundaryTest {
    private val root: File
        get() = File(System.getProperty("user.dir")).let { start ->
            generateSequence(start) { it.parentFile }
                .firstOrNull { File(it, "settings.gradle.kts").isFile }
                ?: start
        }

    private fun source(path: String) = File(root, path).readText()

    @Test
    fun activityLifecycleChangesVisibilityInsteadOfDestroyingTheWindow() {
        val activity = source("app/src/main/java/com/freedom/MainActivity.kt")
        val overlay = source("app/src/main/java/com/freedom/adapters/accessibility/android/MonitorOverlay.kt")
        val lifecycle = activity.substringAfter("override fun onResume()")
            .substringBefore("override fun onNewIntent")

        assertTrue(lifecycle.contains("MonitorOverlay.setHostActivityVisible(this, true)"))
        assertTrue(lifecycle.contains("visible = false"))
        assertTrue(lifecycle.contains("suppressFloatingButton = !shouldShowFloatingButton"))
        assertFalse(lifecycle.contains("MonitorOverlay.hide()"))
        assertFalse(lifecycle.contains("MonitorOverlay.show(this)"))
        assertTrue(overlay.contains("private var hostActivityForeground = false"))
        assertTrue(overlay.contains("private var systemSettingsSuppressed = false"))
        assertTrue(overlay.contains("suppressFloatingButton: Boolean = false"))
        assertFalse(overlay.contains("enabled && permitted && !hostActivityForeground"))
        assertTrue(overlay.contains("enabled && permitted && !systemSettingsSuppressed"))
    }

    @Test
    fun launcherAndFloatingActionsAreSeparateWindowManagerWindows() {
        val overlay = source("app/src/main/java/com/freedom/adapters/accessibility/android/MonitorOverlay.kt")
        assertTrue(overlay.contains("private var launcherView: ImageView?"))
        assertTrue(overlay.contains("private var menuView: FrameLayout?"))
        assertTrue(overlay.contains("manager.addView(launcher, params)"))
        assertTrue(overlay.contains("manager.addView(menu, menuWindowParams)"))
        assertTrue(overlay.contains("private fun expandMenu(context: Context)"))
        assertTrue(overlay.contains("private fun collapseMenu()"))
        assertFalse(overlay.contains("☰"))
        assertFalse(overlay.contains("actionTile("))
    }

    @Test
    fun actualViewAttachmentAndWindowManagerValidationDriveAutomaticRecovery() {
        val overlay = source("app/src/main/java/com/freedom/adapters/accessibility/android/MonitorOverlay.kt")
        assertTrue(overlay.contains("view?.isAttachedToWindow == true"))
        assertTrue(overlay.contains("manager.updateViewLayout(view, params)"))
        assertTrue(overlay.contains("WINDOW_HEALTH_CHECK_MS = 2_000L"))
        assertTrue(overlay.contains("private fun checkAndRecoverWindow()"))
        assertTrue(overlay.contains("private fun recoverDetachedWindow(context: Context, reason: String)"))
        assertTrue(overlay.contains("scheduleHealthCheck()"))
    }

    @Test
    fun positionIsPersistedAsEdgeAndScreenRelativeVerticalFraction() {
        val placement = source("app/src/main/java/com/freedom/core/accessibility/overlay/FloatingButtonPlacement.kt")
        val store = source("app/src/main/java/com/freedom/data/MonitorStore.kt")
        val repository = source("app/src/main/java/com/freedom/data/MonitorStoreRepository.kt")
        assertTrue(placement.contains("HIDDEN_WIDTH_RATIO = 0.25f"))
        assertTrue(placement.contains("persistedPosition"))
        assertTrue(store.contains("KEY_FLOATING_BUTTON_RIGHT_EDGE"))
        assertTrue(store.contains("KEY_FLOATING_BUTTON_Y_FRACTION"))
        assertTrue(repository.contains("setFloatingButtonPosition(position: FloatingButtonPosition)"))
    }

    @Test
    fun runRecordsTxtExportRemainsIndependentOfTheFloatingWindow() {
        val records = source("app/src/main/java/com/freedom/ui/logs/LogRecordsDialog.kt")
        val activity = source("app/src/main/java/com/freedom/MainActivity.kt")
        assertTrue(records.contains("导出 TXT"))
        assertTrue(records.contains("onExportLogs(records)"))
        assertFalse(activity.contains("导出运行日志 TXT"))
    }
    @Test
    fun floatingActionWindowIsAttachedBeforeLauncherAndReusedAcrossExpandCollapse() {
        val overlay = source("app/src/main/java/com/freedom/adapters/accessibility/android/MonitorOverlay.kt")
        val attachMenu = overlay.indexOf("manager.addView(menu, menuWindowParams)")
        val attachLauncher = overlay.indexOf("manager.addView(launcher, params)")
        assertTrue(attachMenu >= 0 && attachLauncher > attachMenu)

        val expand = overlay.substringAfter("private fun expandMenu(context: Context)")
            .substringBefore("private fun collapseMenu()")
        val collapse = overlay.substringAfter("private fun collapseMenu()")
            .substringBefore("private fun removeMenuWindow")
        assertTrue(expand.contains("manager.updateViewLayout(view, params)"))
        assertTrue(expand.contains("view.visibility = View.VISIBLE"))
        assertTrue(expand.contains("FLAG_WATCH_OUTSIDE_TOUCH"))
        assertTrue(collapse.contains("view.visibility = View.INVISIBLE"))
        assertTrue(collapse.contains("FLAG_NOT_TOUCHABLE"))
        val menuFactory = source("app/src/main/java/com/freedom/adapters/accessibility/android/MonitorOverlayMenuViewFactory.kt")
        assertTrue(menuFactory.contains("event.actionMasked == MotionEvent.ACTION_OUTSIDE"))
        assertFalse(expand.contains("manager.addView("))
        assertFalse(collapse.contains("removeViewImmediate("))
    }

    @Test
    fun watchdogValidatesBothWindowsAndRebuildsThePairTogether() {
        val overlay = source("app/src/main/java/com/freedom/adapters/accessibility/android/MonitorOverlay.kt")
        val health = overlay.substringAfter("private fun checkAndRecoverWindow()")
            .substringBefore("private fun recoverDetachedWindow")
        val recovery = overlay.substringAfter("private fun recoverDetachedWindow(context: Context, reason: String)")
            .substringBefore("private fun scheduleHealthCheck")
        assertTrue(health.contains("validateLauncherWindow(manager, view)"))
        assertTrue(health.contains("validateMenuWindow(manager, menu)"))
        assertTrue(recovery.contains("removeMenuWindow(manager)"))
        assertTrue(recovery.contains("ensureLauncherAttached(context)"))
    }

    @Test
    fun dedicatedHostServiceOwnsWindowLifetimeAndRequestsStickyRecreation() {
        val service = source("app/src/main/java/com/freedom/adapters/accessibility/android/FloatingWindowHostService.kt")
        val manifest = source("app/src/main/AndroidManifest.xml")
        assertTrue(service.contains("class FloatingWindowHostService : Service()"))
        assertTrue(service.contains("return START_STICKY"))
        assertTrue(service.contains("MonitorOverlay.onHostServiceReady(applicationContext)"))
        assertTrue(service.contains("MonitorOverlay.onHostServiceDestroyed()"))
        assertTrue(manifest.contains(".adapters.accessibility.android.FloatingWindowHostService"))
        assertTrue(manifest.contains("android:stopWithTask=\"false\""))
    }

    @Test
    fun unexpectedHostServiceDestructionKeepsWindowRecoveryActiveWhenFeatureRemainsEnabled() {
        val overlay = source("app/src/main/java/com/freedom/adapters/accessibility/android/MonitorOverlay.kt")
        val destroyed = overlay.substringAfter("fun onHostServiceDestroyed()")
            .substringBefore("/**\n     * MainActivity is only a visibility input")
        assertTrue(destroyed.contains("stillEnabled"))
        assertTrue(destroyed.contains("scheduleHealthCheck()"))
        assertTrue(destroyed.contains("postDelayed"))
        assertTrue(destroyed.contains("FloatingWindowHostService.ensureStarted(current)"))
        val enabledRecovery = destroyed.substringAfter("} else {")
        assertFalse(enabledRecovery.contains("hideOnMainThread(resetTouchPassthrough = false)"))
    }

    @Test
    fun floatingActionsFollowSemiCircularGeometryOnTheOpenSide() {
        val overlay = source("app/src/main/java/com/freedom/adapters/accessibility/android/MonitorOverlay.kt")
        val menuFactory = source("app/src/main/java/com/freedom/adapters/accessibility/android/MonitorOverlayMenuViewFactory.kt")
        assertTrue(menuFactory.contains("fun position(menu: FrameLayout, onRightEdge: Boolean, anchorCenterY: Float)"))
        assertTrue(menuFactory.contains("RadialMenuGeometry.fanPositions("))
        assertTrue(menuFactory.contains("anglesDegrees = actionAngles"))
        assertTrue(overlay.contains("MENU_ACTION_ANGLES = listOf(-90f, 0f, 90f)"))
        assertTrue(overlay.contains("FloatingButtonPlacement.menuOrigin("))
        assertTrue(menuFactory.contains("event.actionMasked == MotionEvent.ACTION_OUTSIDE"))
        assertTrue(overlay.contains("MENU_ACTION_SIZE_DP = 60"))
    }


    @Test
    fun floatingLauncherStaysVisibleOverFreedomAndUsesSemiHiddenRestingAlpha() {
        val overlay = source("app/src/main/java/com/freedom/adapters/accessibility/android/MonitorOverlay.kt")
        val visibility = overlay.substringAfter("private fun applyCurrentVisibility(")
            .substringBefore("fun touchStateDescription()")
        assertTrue(visibility.contains("!systemSettingsSuppressed"))
        assertFalse(visibility.contains("!hostActivityForeground"))
        assertTrue(overlay.contains("private const val RESTING_ALPHA = 0.34f"))
        assertTrue(overlay.contains("alpha = RESTING_ALPHA"))
        assertTrue(overlay.contains("HapticFeedbackConstants.LONG_PRESS"))
    }

    @Test
    fun doubleTapRequestsStopAndReturnsToMainConfiguration() {
        val overlay = source("app/src/main/java/com/freedom/adapters/accessibility/android/MonitorOverlay.kt")
        val main = source("app/src/main/java/com/freedom/MainActivity.kt")
        val actions = source("app/src/main/java/com/freedom/adapters/android/MonitorActions.kt")
        assertTrue(overlay.contains("ViewConfiguration.getDoubleTapTimeout()"))
        assertTrue(overlay.contains("RunSessionCommandUseCase(state, onDiagnostic = state::addLog).requestStop()"))
        assertTrue(overlay.contains("view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)"))
        assertTrue(overlay.contains("MonitorActions.ACTION_RETURN_TO_CONFIGURATION"))
        assertTrue(overlay.contains("Intent.FLAG_ACTIVITY_NEW_TASK"))
        assertTrue(main.contains("MonitorActions.ACTION_RETURN_TO_CONFIGURATION -> showConfigurationPage()"))
        assertTrue(actions.contains("const val ACTION_RETURN_TO_CONFIGURATION"))
    }

}
