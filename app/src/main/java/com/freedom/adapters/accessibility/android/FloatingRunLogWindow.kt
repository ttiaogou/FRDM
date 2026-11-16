package com.freedom.adapters.accessibility.android

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.freedom.application.ports.MonitorControlRepository

/** Optional live event panel; reads the existing MonitorControlRepository log stream. */
internal object FloatingRunLogWindow {
    private const val POLL_INTERVAL_MS = 900L
    private const val MAX_VISIBLE_LINES = 40

    private val handler = Handler(Looper.getMainLooper())
    private var windowManager: WindowManager? = null
    private var root: LinearLayout? = null
    private var logText: TextView? = null
    private var statusText: TextView? = null
    private var scrollView: ScrollView? = null
    private var lastRenderedText: String? = null
    private var lastStatus: String? = null
    private var repository: MonitorControlRepository? = null

    private val refreshRunnable = object : Runnable {
        override fun run() {
            val current = root
            if (current == null || !current.isAttachedToWindow || windowManager == null) {
                hideOnMainThread()
                return
            }
            refreshContent()
            handler.postDelayed(this, POLL_INTERVAL_MS)
        }
    }

    fun show(context: Context, source: MonitorControlRepository) {
        val app = context.applicationContext
        val action = {
            repository = source
            val current = root
            if (current != null && current.isAttachedToWindow) {
                refreshContent()
                handler.removeCallbacks(refreshRunnable)
                handler.postDelayed(refreshRunnable, POLL_INTERVAL_MS)
            } else {
                attach(app)
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) action() else handler.post { action() }
    }

    fun refresh(context: Context, source: MonitorControlRepository) {
        val action = {
            if (root?.isAttachedToWindow == true) {
                repository = source
                refreshContent()
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) action() else handler.post { action() }
    }

    fun hide() {
        if (Looper.myLooper() == Looper.getMainLooper()) hideOnMainThread() else handler.post { hideOnMainThread() }
    }

    private fun hideOnMainThread() {
        handler.removeCallbacks(refreshRunnable)
        val old = root
        val manager = windowManager
        root = null
        logText = null
        statusText = null
        scrollView = null
        lastRenderedText = null
        lastStatus = null
        repository = null
        windowManager = null
        if (old != null && manager != null) {
            runCatching { manager.removeViewImmediate(old) }
        }
    }

    private fun attach(context: Context) {
        val manager = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return
        val density = context.resources.displayMetrics.density
        fun dp(value: Int) = (value * density + 0.5f).toInt()
        val displayWidth = context.resources.displayMetrics.widthPixels
        val displayHeight = context.resources.displayMetrics.heightPixels
        val width = minOf(dp(326), (displayWidth - dp(24)).coerceAtLeast(dp(220)))
        val height = minOf(dp(390), (displayHeight - dp(150)).coerceAtLeast(dp(250)))

        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = GradientDrawable().apply {
                setColor(0xFFF8F7F0.toInt())
                cornerRadius = dp(18).toFloat()
                setStroke(dp(1), 0xFFDDE2D8.toInt())
            }
            elevation = dp(12).toFloat()
        }
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(View(context).apply {
            setBackgroundColor(0xFFA5C331.toInt())
        }, LinearLayout.LayoutParams(dp(3), dp(25)).apply { rightMargin = dp(9) })
        header.addView(TextView(context).apply {
            text = "悬浮日志"
            textSize = 16f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(0xFF23372B.toInt())
        }, LinearLayout.LayoutParams(0, dp(38), 1f))
        val status = TextView(context).apply {
            textSize = 10f
            gravity = Gravity.CENTER
            setTextColor(0xFF718E22.toInt())
            setPadding(dp(8), dp(5), dp(8), dp(5))
            background = GradientDrawable().apply {
                setColor(0xFFEAF2CF.toInt())
                cornerRadius = dp(12).toFloat()
            }
        }
        statusText = status
        header.addView(status)
        header.addView(TextView(context).apply {
            text = "×"
            textSize = 25f
            gravity = Gravity.CENTER
            setTextColor(0xFF23372B.toInt())
            contentDescription = "关闭悬浮日志"
            isClickable = true
            isFocusable = true
            setOnClickListener { hide() }
        }, LinearLayout.LayoutParams(dp(38), dp(38)).apply { leftMargin = dp(5) })
        panel.addView(header)
        panel.addView(View(context).apply {
            setBackgroundColor(0xFFDDE2D8.toInt())
        }, LinearLayout.LayoutParams(-1, dp(1)).apply {
            topMargin = dp(4)
            bottomMargin = dp(8)
        })

        val text = TextView(context).apply {
            textSize = 11.5f
            setTextColor(0xFF405146.toInt())
            setTextIsSelectable(true)
            setLineSpacing(dp(2).toFloat(), 1f)
            setPadding(dp(10), dp(10), dp(10), dp(10))
            background = GradientDrawable().apply {
                setColor(0xFFF0F1E9.toInt())
                cornerRadius = dp(11).toFloat()
            }
            text = "正在读取运行事件…"
        }
        logText = text
        val scroll = ScrollView(context).apply {
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
            clipToPadding = false
            addView(text, FrameLayout.LayoutParams(-1, -2))
        }
        scrollView = scroll
        panel.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        panel.addView(TextView(context).apply {
            this.text = "实时更新 · 最近 $MAX_VISIBLE_LINES 条事件"
            textSize = 10f
            setTextColor(0xFF7D887E.toInt())
            setPadding(dp(2), dp(8), dp(2), 0)
        })

        val params = WindowManager.LayoutParams(
            width,
            height,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            x = 0
            y = dp(88)
            windowAnimations = 0
        }
        runCatching {
            manager.addView(panel, params)
            windowManager = manager
            root = panel
            refreshContent()
            handler.removeCallbacks(refreshRunnable)
            handler.postDelayed(refreshRunnable, POLL_INTERVAL_MS)
        }.onFailure {
            root = null
            logText = null
            statusText = null
            scrollView = null
            windowManager = null
        }
    }

    private fun refreshContent() {
        val source = repository ?: return
        val status = if (source.isRunRequested()) "运行中" else "未运行"
        if (lastStatus != status) {
            statusText?.text = status
            lastStatus = status
        }
        val rendered = source.logs().takeLast(MAX_VISIBLE_LINES).joinToString("\n")
            .ifBlank { "暂无运行事件" }
        if (rendered != lastRenderedText) {
            logText?.text = rendered
            scrollView?.post { scrollView?.fullScroll(View.FOCUS_DOWN) }
            lastRenderedText = rendered
        }
    }
}
