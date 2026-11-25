package com.freedom.adapters.accessibility.android

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.DisplayMetrics
import android.util.Log
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.PopupMenu
import android.widget.Toast
import com.freedom.R
import com.freedom.adapters.android.MonitorActions
import com.freedom.core.accessibility.overlay.FloatingButtonPosition
import com.freedom.application.ports.MonitorControlRepository
import com.freedom.application.runtime.RunSessionCommandUseCase
import com.freedom.core.accessibility.overlay.RadialMenuGeometry
import com.freedom.stability.MonitorKeepAliveService
import com.freedom.core.accessibility.overlay.FloatingButtonPlacement
import kotlin.math.abs

/**
 * Owns Freedom's optional floating launcher and its compact radial action window.
 *
 * The launcher and fan-menu windows are process-owned, not Activity-owned. They remain visible
 * over Freedom and other apps while enabled; only explicit system-settings/picker transitions,
 * touch passthrough, or observation-visibility leases suppress them. The optional runtime-log
 * panel is managed separately and reads the same repository log stream.
 */
object MonitorOverlay {
    private const val TAG = "MonitorOverlay"
    private const val OBSERVATION_HIDE_SETTLE_MS = 48L
    private const val WINDOW_HEALTH_CHECK_MS = 2_000L
    private const val DRAG_SNAP_DURATION_MS = 240L
    private const val BUTTON_SIZE_DP = 54
    private const val RESTING_ALPHA = 0.34f
    private const val MENU_SIZE_DP = 260
    private const val MENU_ACTION_SIZE_DP = 60
    private const val MENU_ACTION_RADIUS_DP = 92
    private val MENU_ACTION_ANGLES = listOf(-90f, 0f, 90f)
    private const val MENU_SAFE_TOP_DP = 40
    private const val MENU_SAFE_BOTTOM_DP = 56
    private const val HOST_SERVICE_RESTART_RETRY_MS = 800L
    private const val FREEDOM_ACCESSIBILITY_SERVICE_CLASS =
        "com.freedom.accessibility.FreedomAccessibilityService"

    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var monitorRepository: MonitorControlRepository? = null

    /** Installed once by AppCompositionRoot before overlay APIs are used. */
    fun installRepository(repository: MonitorControlRepository) {
        monitorRepository = repository
    }
    private var appContext: Context? = null

    private fun repository(@Suppress("UNUSED_PARAMETER") context: Context): MonitorControlRepository =
        checkNotNull(monitorRepository) {
            "MonitorOverlay requires MonitorControlRepository from AppCompositionRoot"
        }

    private var windowManager: WindowManager? = null
    private var launcherView: ImageView? = null
    private var launcherParams: WindowManager.LayoutParams? = null
    private var menuView: FrameLayout? = null
    private var menuParams: WindowManager.LayoutParams? = null
    private var expanded = false
    private var pendingSingleTap: Runnable? = null
    private var snapAnimator: ValueAnimator? = null
    private var hostActivityForeground = false
    private var systemSettingsSuppressed = false
    private var touchPassthrough = false
    private var dragging = false
    private var downRawX = 0f
    private var downRawY = 0f
    private var downWindowX = 0
    private var downWindowY = 0
    private var healthCheckScheduled = false
    private var lastScreenWidth = 0
    private var lastScreenHeight = 0

    private val observationVisibilityState = ObservationOverlayVisibilityState()
    private val observationVisibilityLeases = ObservationVisibilityLeaseRegistry()
    private val menuViewFactory by lazy(LazyThreadSafetyMode.NONE) {
        MonitorOverlayMenuViewFactory(
            dp = ::dp,
            menuSizeDp = MENU_SIZE_DP,
            actionSizeDp = MENU_ACTION_SIZE_DP,
            actionRadiusDp = MENU_ACTION_RADIUS_DP,
            actionAngles = MENU_ACTION_ANGLES,
            collapseMenu = ::collapseMenu,
            requestNodeBounds = ::requestGlobalNodeLayoutBounds,
            openCollectionExport = ::openCollectionExport,
            showRunLog = { context -> FloatingRunLogWindow.show(context, repository(context)) }
        )
    }

    private val healthCheck = object : Runnable {
        override fun run() {
            healthCheckScheduled = false
            checkAndRecoverWindow()
        }
    }

    /** Idempotently asks the dedicated host Service to ensure the launcher windows exist. */
    fun show(context: Context): Boolean {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { show(context.applicationContext) }
            return false
        }
        val app = context.applicationContext
        appContext = app
        if (!repository(app).isFloatingLogEnabled() || !hasOverlayPermission(app)) {
            hideOnMainThread()
            FloatingWindowHostService.stop(app)
            if (!hasOverlayPermission(app)) log(app, "浮动按钮未显示：悬浮窗权限不可用")
            return false
        }
        val hostStarted = FloatingWindowHostService.ensureStarted(app)
        if (!hostStarted) {
            log(app, "浮动窗口宿主服务启动失败；尝试由进程窗口管理器直接恢复")
        }
        val attached = ensureLauncherAttached(app)
        val visibilityApplied = applyCurrentVisibility(app)
        scheduleHealthCheck()
        return attached && visibilityApplied && isAttached()
    }

    /** Service callback: service owns window creation/recreation; this method is idempotent. */
    fun onHostServiceReady(context: Context): Boolean {
        val app = context.applicationContext
        val ready = {
            appContext = app
            if (!repository(app).isFloatingLogEnabled() || !hasOverlayPermission(app)) {
                hideOnMainThread()
                false
            } else {
                ensureLauncherAttached(app)
                applyCurrentVisibility(app)
                scheduleHealthCheck()
                isAttached() && menuView?.isAttachedToWindow == true
            }
        }
        return if (Looper.myLooper() == Looper.getMainLooper()) ready() else {
            mainHandler.post { ready() }
            false
        }
    }

    fun onHostServiceDestroyed() {
        // A host Service can be reclaimed while its process and WindowManager token are still
        // alive. Do not tear down a healthy launcher or cancel its watchdog in that case. The
        // sticky restart is the primary path; the delayed ensure is a process-alive fallback.
        val reconcile = {
            val app = appContext
            val stillEnabled = app != null && repository(app).isFloatingLogEnabled() && hasOverlayPermission(app)
            if (!stillEnabled) {
                hideOnMainThread(resetTouchPassthrough = false)
            } else {
                val current = app!!
                if (launcherView?.isAttachedToWindow != true || menuView?.isAttachedToWindow != true) {
                    recoverDetachedWindow(current, "浮动窗口宿主服务退出时发现窗口未附加")
                } else {
                    applyCurrentVisibility(current)
                    scheduleHealthCheck()
                }
                mainHandler.postDelayed({
                    if (repository(current).isFloatingLogEnabled() && hasOverlayPermission(current)) {
                        FloatingWindowHostService.ensureStarted(current)
                        ensureLauncherAttached(current)
                        applyCurrentVisibility(current)
                        scheduleHealthCheck()
                    }
                }, HOST_SERVICE_RESTART_RETRY_MS)
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) reconcile() else mainHandler.post { reconcile() }
    }

    /**
     * MainActivity foreground state is diagnostic only. The launcher remains on top of Freedom;
     * callers explicitly suppress it only while opening system settings or external pickers.
     */
    fun setHostActivityVisible(
        context: Context,
        visible: Boolean,
        suppressFloatingButton: Boolean = false
    ) {
        val app = context.applicationContext
        val apply = {
            appContext = app
            hostActivityForeground = visible
            systemSettingsSuppressed = suppressFloatingButton
            if (visible) FloatingRunLogWindow.hide()
            if (!repository(app).isFloatingLogEnabled() || !hasOverlayPermission(app)) {
                hideOnMainThread()
                FloatingWindowHostService.stop(app)
            } else {
                FloatingWindowHostService.ensureStarted(app)
                if (visible) collapseMenu()
                ensureLauncherAttached(app)
                applyCurrentVisibility(app)
                scheduleHealthCheck()
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) apply() else mainHandler.post { apply() }
    }

    private fun ensureLauncherAttached(context: Context): Boolean {
        val existing = launcherView
        val manager = windowManager ?: runCatching {
            context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        }.getOrNull() ?: return false
        windowManager = manager

        if (existing != null && existing.isAttachedToWindow) {
            val launcherValid = validateLauncherWindow(manager, existing)
            val menu = menuView
            val menuValid = menu != null && menu.isAttachedToWindow && validateMenuWindow(manager, menu)
            if (launcherValid && menuValid) return true
            recoverDetachedWindow(context, "主按钮或扇形菜单窗口校验失败")
            return isAttached() && menuView?.isAttachedToWindow == true
        }
        if (existing != null || menuView != null) {
            Log.w(TAG, "Floating launcher pair is partially detached; rebuilding both windows")
            removeMenuWindow(manager)
            existing?.let { runCatching { manager.removeViewImmediate(it) } }
            launcherView = null
            launcherParams = null
        }

        val metrics = screenMetrics(manager)
        val buttonSize = dp(context, BUTTON_SIZE_DP)
        val menuSize = dp(context, MENU_SIZE_DP)
        val savedPosition = repository(context).floatingButtonPosition()
        val initial = FloatingButtonPlacement.initial(
            screen = FloatingButtonPlacement.Size(metrics.x, metrics.y),
            buttonSize = buttonSize,
            position = savedPosition,
            topInset = dp(context, 28),
            bottomInset = dp(context, 36)
        )
        val menu = menuViewFactory.create(context).apply {
            visibility = View.INVISIBLE
            alpha = 0f
        }
        val menuWindowParams = windowLayoutParams(menuSize, menuSize).apply {
            flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
        val launcher = ImageView(context).apply {
            contentDescription = "Freedom 浮动按钮；点击展开功能，拖动可贴边隐藏"
            setImageResource(R.drawable.ic_floating_launcher)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(dp(context, 4), dp(context, 4), dp(context, 4), dp(context, 4))
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(0xFFF8F7F0.toInt(), 0xFFEEEFE5.toInt())
            ).apply {
                shape = GradientDrawable.OVAL
                setStroke(dp(context, 1), 0xFFB9DF3B.toInt())
            }
            elevation = dp(context, 9).toFloat()
            alpha = RESTING_ALPHA
            isClickable = true
            isFocusable = true
            setOnClickListener { handleLauncherClick(context, it) }
            setOnTouchListener { view, event -> handleLauncherTouch(context, view, event) }
        }
        val params = windowLayoutParams(buttonSize, buttonSize).apply {
            gravity = Gravity.TOP or Gravity.START
            x = initial.x
            y = initial.y
        }

        return runCatching {
            // Match AutoJs6's z-order: the (initially hidden) action window is attached first,
            // then the launcher is attached last and therefore remains the topmost touch target.
            manager.addView(menu, menuWindowParams)
            menuView = menu
            menuParams = menuWindowParams
            manager.addView(launcher, params)
            launcherView = launcher
            launcherParams = params
            lastScreenWidth = metrics.x
            lastScreenHeight = metrics.y
            Log.i(TAG, "Floating launcher pair attached at x=${params.x}, y=${params.y}")
            true
        }.getOrElse { error ->
            runCatching { manager.removeViewImmediate(launcher) }
            runCatching { manager.removeViewImmediate(menu) }
            launcherView = null
            launcherParams = null
            menuView = null
            menuParams = null
            log(context, "浮动按钮窗口创建失败：${error.javaClass.simpleName}: ${error.message.orEmpty()}")
            Log.e(TAG, "Failed to attach floating launcher pair", error)
            false
        }
    }

    private fun handleLauncherClick(context: Context, view: View) {
        val pending = pendingSingleTap
        if (pending != null) {
            mainHandler.removeCallbacks(pending)
            pendingSingleTap = null
            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            view.animate().cancel()
            view.animate()
                .scaleX(0.88f)
                .scaleY(0.88f)
                .setDuration(90L)
                .withEndAction {
                    if (view.isAttachedToWindow) {
                        view.animate().scaleX(1f).scaleY(1f).alpha(RESTING_ALPHA)
                            .setDuration(160L).start()
                    }
                }
                .start()
            stopRunFromFloatingButton(context)
            return
        }
        val singleTap = Runnable {
            pendingSingleTap = null
            toggleMenu(context)
        }
        pendingSingleTap = singleTap
        mainHandler.postDelayed(singleTap, ViewConfiguration.getDoubleTapTimeout().toLong())
    }

    /** A double-tap stop writes the existing run intent and lets AccessibilityService reconcile it. */
    private fun stopRunFromFloatingButton(context: Context) {
        val app = context.applicationContext
        val state = repository(app)
        collapseMenu()
        runCatching {
            if (state.isRunRequested()) {
                RunSessionCommandUseCase(state, onDiagnostic = state::addLog).requestStop()
                MonitorKeepAliveService.stop(app)
                app.sendBroadcast(Intent(MonitorActions.ACTION_REFRESH).setPackage(app.packageName))
                state.addLog("双击悬浮按钮：已提交停止请求")
                FloatingRunLogWindow.refresh(app, state)
                Toast.makeText(app, "正在停止运行，返回 Freedom…", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(app, "当前没有运行中的任务，返回 Freedom", Toast.LENGTH_SHORT).show()
            }
            returnToMainConfiguration(app)
        }.onFailure { error ->
            log(app, "双击停止/返回 Freedom 失败：${error.javaClass.simpleName}: ${error.message.orEmpty()}")
            Toast.makeText(app, "停止或返回失败，请查看运行事件", Toast.LENGTH_LONG).show()
        }
    }

    /** Bring the existing singleTop MainActivity forward and restore its main configuration page. */
    private fun returnToMainConfiguration(context: Context) {
        val app = context.applicationContext
        val intent = Intent().apply {
            setClassName(app.packageName, "${app.packageName}.MainActivity")
            action = MonitorActions.ACTION_RETURN_TO_CONFIGURATION
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
        }
        app.startActivity(intent)
    }

    private fun validateLauncherWindow(manager: WindowManager, view: ImageView): Boolean {
        val params = launcherParams ?: return false
        return runCatching {
            // A WindowManager round-trip catches stale or detached window tokens.
            manager.updateViewLayout(view, params)
            true
        }.getOrElse { error ->
            Log.w(TAG, "Launcher window validation failed; scheduling reconstruction", error)
            false
        }
    }

    private fun validateMenuWindow(manager: WindowManager, view: FrameLayout): Boolean {
        val params = menuParams ?: return false
        return runCatching {
            manager.updateViewLayout(view, params)
            true
        }.getOrElse { error ->
            Log.w(TAG, "Radial menu window validation failed; scheduling reconstruction", error)
            false
        }
    }

    private fun handleLauncherTouch(context: Context, view: View, event: MotionEvent): Boolean {
        val params = launcherParams ?: return false
        if (expanded) {
            if (event.actionMasked == MotionEvent.ACTION_UP) view.performClick()
            return true
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                snapAnimator?.cancel()
                snapAnimator = null
                dragging = false
                downRawX = event.rawX
                downRawY = event.rawY
                downWindowX = params.x
                downWindowY = params.y
                view.alpha = 1f
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val deltaX = event.rawX - downRawX
                val deltaY = event.rawY - downRawY
                val slop = ViewConfiguration.get(context).scaledTouchSlop
                if (!dragging && (abs(deltaX) > slop || abs(deltaY) > slop)) dragging = true
                if (dragging) {
                    params.x = downWindowX + deltaX.toInt()
                    params.y = downWindowY + deltaY.toInt()
                    runCatching { windowManager?.updateViewLayout(view, params) }
                        .onFailure { error ->
                            log(context, "浮动按钮拖动更新失败：${error.javaClass.simpleName}")
                            recoverDetachedWindow(context, "拖动期间窗口更新失败")
                        }
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                view.animate().alpha(RESTING_ALPHA).setDuration(180L).start()
                if (dragging) {
                    snapToNearestEdge(context)
                } else {
                    view.performClick()
                }
                dragging = false
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                view.animate().alpha(RESTING_ALPHA).setDuration(180L).start()
                if (dragging) snapToNearestEdge(context)
                dragging = false
                return true
            }
        }
        return true
    }

    private fun snapToNearestEdge(context: Context) {
        val manager = windowManager ?: return
        val view = launcherView ?: return
        val params = launcherParams ?: return
        val metrics = screenMetrics(manager)
        val size = dp(context, BUTTON_SIZE_DP)
        val target = FloatingButtonPlacement.snap(
            screen = FloatingButtonPlacement.Size(metrics.x, metrics.y),
            buttonSize = size,
            currentX = params.x,
            currentY = params.y,
            topInset = dp(context, 28),
            bottomInset = dp(context, 36)
        )
        val startX = params.x
        val startY = params.y
        snapAnimator?.cancel()
        ValueAnimator.ofFloat(0f, 1f).also { animator ->
            snapAnimator = animator
            animator.duration = DRAG_SNAP_DURATION_MS
            animator.addUpdateListener { frame ->
                val fraction = frame.animatedValue as Float
                params.x = (startX + (target.x - startX) * fraction).toInt()
                params.y = (startY + (target.y - startY) * fraction).toInt()
                runCatching { manager.updateViewLayout(view, params) }
                    .onFailure { recoverDetachedWindow(context, "贴边动画更新失败") }
            }
            animator.addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    if (snapAnimator === animator) snapAnimator = null
                    val position = FloatingButtonPlacement.persistedPosition(
                        FloatingButtonPlacement.Size(metrics.x, metrics.y), size,
                        FloatingButtonPlacement.Point(params.x, params.y),
                        dp(context, 28), dp(context, 36)
                    )
                    repository(context).setFloatingButtonPosition(position)
                    Log.d(TAG, "Floating launcher snapped to ${if (position.rightEdge) "right" else "left"} edge; y=${position.normalizedYFraction}")
                }
            })
            animator.start()
        }
    }

    private fun toggleMenu(context: Context) {
        if (touchPassthrough || observationVisibilityState.isObservationHidden()) return
        if (expanded) collapseMenu() else expandMenu(context)
    }

    private fun expandMenu(context: Context) {
        val manager = windowManager ?: return
        val buttonParams = launcherParams ?: return
        val view = menuView
        val params = menuParams
        if (launcherView?.isAttachedToWindow != true || view?.isAttachedToWindow != true || params == null) {
            recoverDetachedWindow(context, "点击展开时主按钮或功能菜单窗口已脱离")
            return
        }

        val menuSize = dp(context, MENU_SIZE_DP)
        val buttonSize = dp(context, BUTTON_SIZE_DP)
        val onRightEdge = buttonParams.x >= 0
        val metrics = screenMetrics(manager)
        val origin = FloatingButtonPlacement.menuOrigin(
            screen = FloatingButtonPlacement.Size(metrics.x, metrics.y),
            button = FloatingButtonPlacement.Point(buttonParams.x, buttonParams.y),
            buttonSize = buttonSize,
            menu = FloatingButtonPlacement.Size(menuSize, menuSize),
            topInset = dp(context, MENU_SAFE_TOP_DP),
            bottomInset = dp(context, MENU_SAFE_BOTTOM_DP)
        )
        params.x = origin.x
        params.y = origin.y
        val anchorCenterY = (buttonParams.y + buttonSize / 2 - origin.y).toFloat()
        menuViewFactory.position(view, onRightEdge, anchorCenterY)
        view.pivotX = if (onRightEdge) menuSize.toFloat() else 0f
        view.pivotY = anchorCenterY.coerceIn(
            dp(context, MENU_ACTION_SIZE_DP) / 2f,
            menuSize - dp(context, MENU_ACTION_SIZE_DP) / 2f
        )
        params.flags = (params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()) or
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
        runCatching {
            view.animate().cancel()
            manager.updateViewLayout(view, params)
            view.visibility = View.VISIBLE
            view.alpha = 0f
            view.scaleX = 0.88f
            view.scaleY = 0.88f
            expanded = true
            launcherView?.alpha = 1f
            view.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(170L).start()
        }.onFailure { error ->
            params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            view.visibility = View.INVISIBLE
            expanded = false
            log(context, "浮动功能菜单展开失败：${error.javaClass.simpleName}: ${error.message.orEmpty()}")
            Log.e(TAG, "Failed to show radial action menu", error)
            recoverDetachedWindow(context, "展开功能菜单时 WindowManager 更新失败")
            Toast.makeText(context, "功能菜单暂时无法展开，请查看运行记录", Toast.LENGTH_LONG).show()
        }
    }

    private fun collapseMenu() {
        val view = menuView
        val manager = windowManager
        expanded = false
        launcherView?.animate()?.alpha(RESTING_ALPHA)?.setDuration(180L)?.start()
        if (view != null && manager != null && view.isAttachedToWindow) {
            view.animate().cancel()
            view.animate()
                .alpha(0f)
                .scaleX(0.88f)
                .scaleY(0.88f)
                .setDuration(140L)
                .withEndAction {
                    if (!expanded && menuView === view && view.isAttachedToWindow) {
                        view.visibility = View.INVISIBLE
                        menuParams?.let { params ->
                            params.flags = (params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE) and
                                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH.inv()
                            runCatching { manager.updateViewLayout(view, params) }
                                .onFailure { Log.w(TAG, "Failed to make collapsed radial menu non-touchable", it) }
                        }
                    }
                }
                .start()
        }
    }

    private fun removeMenuWindow(manager: WindowManager?) {
        val oldMenu = menuView
        menuView = null
        menuParams = null
        if (manager != null && oldMenu != null) {
            runCatching { manager.removeViewImmediate(oldMenu) }
                .onFailure { Log.d(TAG, "Radial menu was already detached during removal", it) }
        }
    }

    /** Opens the existing Activity-owned collection/export flow from the process-owned overlay. */
    private fun openCollectionExport(context: Context) {
        val app = context.applicationContext
        val intent = Intent(MonitorActions.ACTION_OPEN_CAPTURE_EXPORT).apply {
            // Keep the overlay independent of MainActivity at compile time while routing through
            // its existing singleTop/onNewIntent lifecycle and post-resume dialog dispatch.
            setClassName(app.packageName, "${app.packageName}.MainActivity")
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
        }
        runCatching { app.startActivity(intent) }
            .onSuccess {
                log(app, "已从浮动按钮打开采集与 ZIP 导出入口")
            }
            .onFailure { error ->
                log(app, "打开采集与 ZIP 导出入口失败：${error.javaClass.simpleName}: ${error.message.orEmpty()}")
                Toast.makeText(app, "无法打开采集与 ZIP 导出，请返回 Freedom 后重试", Toast.LENGTH_LONG).show()
            }
    }

    private fun requestGlobalNodeHierarchy(context: Context) {
        val app = context.applicationContext
        if (!isFreedomAccessibilityEnabled(app)) {
            Toast.makeText(app, "请先启用 Freedom 无障碍服务，再查看节点层级", Toast.LENGTH_LONG).show()
            return
        }
        runCatching {
            app.sendBroadcast(
                Intent(MonitorActions.ACTION_SHOW_GLOBAL_NODE_HIERARCHY).setPackage(app.packageName)
            )
        }.onFailure { error ->
            log(app, "节点层级视图请求发送失败：${error.javaClass.simpleName}")
        }
    }

    private fun showNodeMoreMenu(context: Context, anchor: View) {
        PopupMenu(context, anchor).apply {
            menu.add("查看节点层级").setOnMenuItemClickListener {
                collapseMenu()
                requestGlobalNodeHierarchy(context)
                true
            }
            menu.add("清除节点边界").setOnMenuItemClickListener {
                collapseMenu()
                clearGlobalNodeLayoutBounds(context)
                true
            }
            menu.add("收起菜单").setOnMenuItemClickListener {
                collapseMenu()
                true
            }
            show()
        }
    }

    private fun requestGlobalNodeLayoutBounds(context: Context) {
        val app = context.applicationContext
        if (!isFreedomAccessibilityEnabled(app)) {
            Toast.makeText(app, "请先启用 Freedom 无障碍服务，再显示目标 App 节点边界", Toast.LENGTH_LONG).show()
            log(app, "全局节点可视化未执行：Freedom 无障碍服务尚未启用")
            return
        }
        runCatching {
            app.sendBroadcast(
                Intent(MonitorActions.ACTION_SHOW_GLOBAL_NODE_LAYOUT_BOUNDS).setPackage(app.packageName)
            )
        }.onSuccess {
            log(app, "已从悬浮按钮请求当前目标 App 的全局节点边界可视化")
            Toast.makeText(app, "正在目标页面绘制节点边界", Toast.LENGTH_SHORT).show()
        }.onFailure { error ->
            log(app, "全局节点可视化请求发送失败：${error.javaClass.simpleName}")
            Toast.makeText(app, "节点可视化请求失败，请查看运行记录", Toast.LENGTH_LONG).show()
        }
    }

    private fun clearGlobalNodeLayoutBounds(context: Context) {
        val app = context.applicationContext
        if (!isFreedomAccessibilityEnabled(app)) {
            Toast.makeText(app, "Freedom 无障碍服务未启用，无法清除节点边界层", Toast.LENGTH_LONG).show()
            return
        }
        runCatching {
            app.sendBroadcast(
                Intent(MonitorActions.ACTION_CLEAR_GLOBAL_NODE_LAYOUT_BOUNDS).setPackage(app.packageName)
            )
        }.onSuccess {
            log(app, "已从悬浮按钮请求清除目标 App 节点边界层")
        }.onFailure { error ->
            log(app, "清除节点边界请求发送失败：${error.javaClass.simpleName}")
        }
    }

    private fun isFreedomAccessibilityEnabled(context: Context): Boolean {
        val enabledServices = runCatching {
            Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            )
        }.getOrNull().orEmpty()
        return enabledServices.split(':').any { flattened ->
            val component = android.content.ComponentName.unflattenFromString(flattened.trim())
            component != null && component.packageName == context.packageName &&
                component.className == FREEDOM_ACCESSIBILITY_SERVICE_CLASS
        }
    }

    fun isAttached(): Boolean = launcherView?.isAttachedToWindow == true && launcherParams != null

    /** Gesture dispatch temporarily removes the launcher from touch hit-testing without detaching it. */
    fun setTouchPassthrough(enabled: Boolean): Boolean {
        if (Looper.myLooper() != Looper.getMainLooper()) return false
        if (touchPassthrough == enabled) return true
        val previous = touchPassthrough
        touchPassthrough = enabled
        if (enabled) collapseMenu()
        val applied = applyCurrentVisibility()
        if (!applied) {
            // Restoring a stale launcher token can fail during task exit. Use the
            // existing validated window reconstruction before reporting cleanup
            // failure. Never report success solely because reconstruction was requested.
            val app = appContext
            if (!enabled && app != null && repository(app).isFloatingLogEnabled() &&
                hasOverlayPermission(app) && ensureLauncherAttached(app) &&
                isAttached() && applyCurrentVisibility(app)
            ) return true
            touchPassthrough = previous
            applyCurrentVisibility()
            return false
        }
        return true
    }

    fun acquireObservationVisibility(
        context: Context,
        onReady: (ObservationVisibilityLease) -> Unit
    ) {
        val app = context.applicationContext
        mainHandler.post {
            val firstHold = try {
                observationVisibilityState.acquire()
            } catch (_: Exception) {
                return@post
            }
            lateinit var lease: ObservationVisibilityLease
            lease = ObservationVisibilityLease {
                observationVisibilityLeases.remove(lease)
                observationVisibilityState.release()
                val restore = { applyCurrentVisibility(app) }
                if (Looper.myLooper() == Looper.getMainLooper()) restore() else mainHandler.post { restore() }
            }
            observationVisibilityLeases.register(lease)
            if (!applyCurrentVisibility(app)) {
                Log.e(TAG, "Observation visibility hold acquired, but the floating window update failed")
            }
            val delay = if (firstHold && isAttached()) OBSERVATION_HIDE_SETTLE_MS else 0L
            mainHandler.postDelayed({
                if (lease.isClosed) return@postDelayed
                runCatching { onReady(lease) }.onFailure { lease.close() }
            }, delay)
        }
    }

    fun releaseAllObservationVisibility(reason: String) {
        val release = {
            val count = observationVisibilityLeases.releaseAll()
            val restored = applyCurrentVisibility()
            if (count > 0 || !restored) {
                Log.w(TAG, "Service lifecycle released $count observation visibility lease(s): $reason; applied=$restored")
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) release() else mainHandler.post { release() }
    }

    fun isObservationHidden(): Boolean {
        if (!observationVisibilityState.isObservationHidden()) return false
        val view = launcherView
        return view == null || !view.isAttachedToWindow || view.visibility != View.VISIBLE || !view.isShown
    }

    private fun applyCurrentVisibility(context: Context? = null): Boolean {
        val view = launcherView ?: return true
        val manager = windowManager ?: return false
        val params = launcherParams ?: return false
        val enabled = context?.let { repository(it).isFloatingLogEnabled() }
            ?: appContext?.let { repository(it).isFloatingLogEnabled() }
            ?: (monitorRepository?.isFloatingLogEnabled() ?: false)
        val permitted = context?.let(::hasOverlayPermission)
            ?: appContext?.let(::hasOverlayPermission)
            ?: true
        val visible = enabled && permitted && !systemSettingsSuppressed && !touchPassthrough &&
            !observationVisibilityState.isObservationHidden()
        val originalFlags = params.flags
        params.flags = if (visible) {
            params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        } else {
            params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
        if (params.flags != originalFlags) {
            if (runCatching { manager.updateViewLayout(view, params) }.isFailure) {
                params.flags = originalFlags
                return false
            }
        }
        view.visibility = if (visible) View.VISIBLE else View.INVISIBLE
        if (!visible) collapseMenu()
        return true
    }

    fun touchStateDescription(): String {
        val view = launcherView ?: return "浮动按钮未附加"
        val location = IntArray(2)
        runCatching { view.getLocationOnScreen(location) }
        return "浮动按钮区域=(${location[0]},${location[1]},${location[0] + view.width},${location[1] + view.height})，" +
            "可见=${view.visibility == View.VISIBLE}，窗口附加=${view.isAttachedToWindow}，扇形菜单展开=$expanded，" +
            "宿主前台=$hostActivityForeground，系统页面抑制=$systemSettingsSuppressed，触摸穿透=$touchPassthrough"
    }

    /** Accessibility/runtime refresh is idempotent and does not present a realtime event panel. */
    fun update(context: Context) {
        val app = context.applicationContext
        mainHandler.post {
            appContext = app
            FloatingRunLogWindow.refresh(app, repository(app))
            if (!repository(app).isFloatingLogEnabled() || !hasOverlayPermission(app)) {
                hideOnMainThread()
                FloatingWindowHostService.stop(app)
            } else {
                FloatingWindowHostService.ensureStarted(app)
                ensureLauncherAttached(app)
                applyCurrentVisibility(app)
                scheduleHealthCheck()
            }
        }
    }

    private fun checkAndRecoverWindow() {
        val app = appContext ?: return
        if (!repository(app).isFloatingLogEnabled() || !hasOverlayPermission(app)) {
            hideOnMainThread()
            FloatingWindowHostService.stop(app)
            return
        }

        val manager = windowManager ?: runCatching {
            app.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        }.getOrNull()
        if (manager == null) {
            log(app, "浮动窗口健康检查失败：WindowManager 不可用")
            scheduleHealthCheck()
            return
        }
        windowManager = manager
        val metrics = screenMetrics(manager)
        if (metrics.x != lastScreenWidth || metrics.y != lastScreenHeight) {
            snapAnimator?.cancel()
            snapAnimator = null
            dragging = false
            collapseMenu()
            val position = repository(app).floatingButtonPosition()
            val placed = FloatingButtonPlacement.initial(
                FloatingButtonPlacement.Size(metrics.x, metrics.y),
                dp(app, BUTTON_SIZE_DP), position,
                dp(app, 28), dp(app, 36)
            )
            launcherParams?.let { params ->
                params.x = placed.x
                params.y = placed.y
                launcherView?.let { view -> runCatching { manager.updateViewLayout(view, params) } }
            }
            lastScreenWidth = metrics.x
            lastScreenHeight = metrics.y
        }

        val view = launcherView
        val menu = menuView
        val attached = view?.isAttachedToWindow == true && launcherParams != null
        val launcherValid = attached && view != null && validateLauncherWindow(manager, view)
        val menuValid = menu?.isAttachedToWindow == true && validateMenuWindow(manager, menu)
        if (!launcherValid || !menuValid) {
            recoverDetachedWindow(app, "定时健康检查发现主按钮或扇形菜单窗口已脱离/更新失败")
        } else {
            applyCurrentVisibility(app)
        }
        scheduleHealthCheck()
    }

    private fun recoverDetachedWindow(context: Context, reason: String) {
        snapAnimator?.cancel()
        snapAnimator = null
        dragging = false
        collapseMenu()
        val manager = windowManager
        val staleLauncher = launcherView
        launcherView = null
        launcherParams = null
        removeMenuWindow(manager)
        if (manager != null && staleLauncher != null) {
            runCatching { manager.removeViewImmediate(staleLauncher) }
                .onFailure { Log.d(TAG, "Stale launcher was already detached during recovery", it) }
        }
        Log.w(TAG, "Rebuilding floating launcher pair: $reason")
        val recovered = ensureLauncherAttached(context)
        if (recovered) {
            applyCurrentVisibility(context)
            log(context, "浮动按钮窗口失效后已自动重建：$reason")
        } else {
            log(context, "浮动按钮窗口自动恢复失败：$reason")
        }
    }

    private fun scheduleHealthCheck() {
        if (healthCheckScheduled) return
        val app = appContext ?: return
        if (!repository(app).isFloatingLogEnabled()) return
        healthCheckScheduled = true
        mainHandler.postDelayed(healthCheck, WINDOW_HEALTH_CHECK_MS)
    }

    private fun cancelHealthCheck() {
        mainHandler.removeCallbacks(healthCheck)
        healthCheckScheduled = false
    }

    fun hide() {
        val app = appContext
        val hideAndStop = {
            hideOnMainThread()
            app?.let { FloatingWindowHostService.stop(it) }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) hideAndStop() else mainHandler.post { hideAndStop() }
    }

    private fun hideOnMainThread(resetTouchPassthrough: Boolean = true) {
        cancelHealthCheck()
        pendingSingleTap?.let(mainHandler::removeCallbacks)
        pendingSingleTap = null
        FloatingRunLogWindow.hide()
        snapAnimator?.cancel()
        snapAnimator = null
        collapseMenu()
        val manager = windowManager
        val oldLauncher = launcherView
        launcherView = null
        launcherParams = null
        removeMenuWindow(manager)
        if (manager != null && oldLauncher != null) {
            runCatching { manager.removeViewImmediate(oldLauncher) }
                .onFailure { Log.d(TAG, "Launcher was already detached during hide", it) }
        }
        windowManager = null
        expanded = false
        dragging = false
        if (resetTouchPassthrough) touchPassthrough = false
    }

    private fun windowLayoutParams(width: Int, height: Int) =
        WindowManager.LayoutParams(
            width,
            height,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            windowAnimations = 0
        }

    private fun screenMetrics(manager: WindowManager): Point = DisplayMetrics().let { metrics ->
        @Suppress("DEPRECATION")
        manager.defaultDisplay.getRealMetrics(metrics)
        Point(metrics.widthPixels, metrics.heightPixels)
    }

    private fun hasOverlayPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(context)

    private fun log(context: Context, message: String) {
        runCatching { repository(context).addLog(message) }
        Log.i(TAG, message)
    }

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density + 0.5f).toInt()
}
