package com.freedom.adapters.accessibility.android

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import com.freedom.R
import com.freedom.core.accessibility.overlay.RadialMenuGeometry

/** Builds and positions the overlay's icon-only radial menu; window ownership stays in MonitorOverlay. */
internal class MonitorOverlayMenuViewFactory(
    private val dp: (Context, Int) -> Int,
    private val menuSizeDp: Int,
    private val actionSizeDp: Int,
    private val actionRadiusDp: Int,
    private val actionAngles: List<Float>,
    private val collapseMenu: () -> Unit,
    private val requestNodeBounds: (Context) -> Unit,
    private val openCollectionExport: (Context) -> Unit,
    private val showRunLog: (Context) -> Unit
) {
    fun create(context: Context): FrameLayout {
        val menu = FrameLayout(context).apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            isClickable = true
            setOnClickListener { collapseMenu() }
            setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_OUTSIDE) {
                    collapseMenu()
                    true
                } else false
            }
        }
        menu.addView(
            menuIconAction(
                context = context,
                icon = R.drawable.ic_floating_visualizer,
                gradient = intArrayOf(0xFF314D3A.toInt(), 0xFF17281F.toInt()),
                contentDescription = "节点可视化：查看当前目标界面的节点结构"
            ) {
                collapseMenu()
                requestNodeBounds(context)
            },
            FrameLayout.LayoutParams(dp(context, actionSizeDp), dp(context, actionSizeDp))
        )
        menu.addView(
            menuIconAction(
                context = context,
                icon = R.drawable.ic_floating_export,
                gradient = intArrayOf(0xFFE1F66A.toInt(), 0xFF7DAE25.toInt()),
                contentDescription = "导出采集数据：打开现有归档与导出流程"
            ) {
                collapseMenu()
                openCollectionExport(context)
            },
            FrameLayout.LayoutParams(dp(context, actionSizeDp), dp(context, actionSizeDp))
        )
        menu.addView(
            menuIconAction(
                context = context,
                icon = R.drawable.ic_floating_logs,
                gradient = intArrayOf(0xFF314D3A.toInt(), 0xFF17281F.toInt()),
                contentDescription = "悬浮日志：查看实时运行事件"
            ) {
                collapseMenu()
                showRunLog(context)
            },
            FrameLayout.LayoutParams(dp(context, actionSizeDp), dp(context, actionSizeDp))
        )
        return menu
    }

    /** Places the three icon-only actions along the open semicircle. */
    fun position(menu: FrameLayout, onRightEdge: Boolean, anchorCenterY: Float) {
        val menuSize = dp(menu.context, menuSizeDp)
        val itemSize = dp(menu.context, actionSizeDp)
        val positions = RadialMenuGeometry.fanPositions(
            menuWidth = menuSize,
            menuHeight = menuSize,
            itemSize = itemSize,
            radius = dp(menu.context, actionRadiusDp),
            anchorOnRight = onRightEdge,
            anchorCenterY = anchorCenterY,
            anglesDegrees = actionAngles
        )
        for (index in 0 until minOf(menu.childCount, positions.size)) {
            val position = positions[index]
            menu.getChildAt(index).layoutParams = FrameLayout.LayoutParams(itemSize, itemSize).apply {
                leftMargin = position.left
                topMargin = position.top
            }
        }
    }

    private fun menuIconAction(
        context: Context,
        icon: Int,
        gradient: IntArray,
        contentDescription: String,
        onClick: () -> Unit
    ): FrameLayout = FrameLayout(context).apply {
        this.contentDescription = contentDescription
        isClickable = true
        isFocusable = true
        background = RippleDrawable(
            ColorStateList.valueOf(0x33FFFFFF),
            GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                gradient
            ).apply {
                shape = GradientDrawable.OVAL
                setStroke(dp(context, 1), 0x88FFFFFF.toInt())
            },
            GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.WHITE)
            }
        )
        elevation = dp(context, 7).toFloat()
        setOnClickListener { onClick() }
        addView(ImageView(context).apply {
            setImageResource(icon)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(context, 16), dp(context, 16), dp(context, 16), dp(context, 16))
            isClickable = false
            isFocusable = false
        }, FrameLayout.LayoutParams(dp(context, actionSizeDp), dp(context, actionSizeDp), Gravity.CENTER))
    }
}
