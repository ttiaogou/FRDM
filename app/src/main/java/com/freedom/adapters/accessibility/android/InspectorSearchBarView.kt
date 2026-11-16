package com.freedom.adapters.accessibility.android

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.inputmethod.InputMethodManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import com.freedom.core.accessibility.inspector.InspectorNodeAttribute

/** Compact search/filter controls shared by the two in-place inspector surfaces. */
internal class InspectorSearchBarView(
    context: Context,
    private val onChanged: (String, Boolean, Set<InspectorNodeAttribute>) -> Unit,
    private val onClearRegion: () -> Unit,
    private val onRegionModeChanged: ((Boolean) -> Unit)? = null
) : LinearLayout(context) {
    private val appContext = context
    private val density = context.resources.displayMetrics.density.coerceAtLeast(1f)
    private var regularExpression = false
    private val enabledAttributes = linkedSetOf<InspectorNodeAttribute>()
    private val input: EditText
    private val regexButton: TextView
    private val countView: TextView
    private val regionButton: TextView
    private val regionModeButton: TextView
    private var regionSelectionMode = false
    private val filterButton: TextView

    init {
        orientation = VERTICAL
        setPadding(dp(7), dp(5), dp(7), dp(5))
        background = rounded(0xF8F8F7F0.toInt(), 0xFFDDE2D8.toInt(), dp(11))
        elevation = dp(8).toFloat()
        gravity = Gravity.CENTER_VERTICAL

        val controls = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        input = EditText(context).apply {
            hint = "搜索文本 / ID / 类名"
            textSize = 12f
            setTextColor(0xFF23372B.toInt())
            setHintTextColor(0xFF7D887E.toInt())
            setSingleLine(true)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setPadding(dp(7), 0, dp(5), 0)
            background = null
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = notifyChanged()
                override fun afterTextChanged(s: Editable?) = Unit
            })
        }
        controls.addView(input, LinearLayout.LayoutParams(0, dp(38), 1f))
        regexButton = button(".*") {
            regularExpression = !regularExpression
            updateRegexAppearance()
            notifyChanged()
        }
        controls.addView(regexButton, LinearLayout.LayoutParams(dp(36), dp(34)))
        filterButton = button("筛选") { showAttributeMenu() }
        controls.addView(filterButton, LinearLayout.LayoutParams(dp(48), dp(34)))
        countView = TextView(context).apply {
            text = "全部"
            textSize = 10f
            setTextColor(0xFF56665B.toInt())
            gravity = Gravity.CENTER
            setPadding(dp(4), 0, dp(4), 0)
        }
        controls.addView(countView, LinearLayout.LayoutParams(dp(48), dp(34)))
        addView(controls, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        val regionControls = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        regionModeButton = button("框选区域") {
            regionSelectionMode = !regionSelectionMode
            updateRegionModeAppearance()
            onRegionModeChanged?.invoke(regionSelectionMode)
        }.apply {
            visibility = if (onRegionModeChanged == null) GONE else VISIBLE
        }
        regionControls.addView(regionModeButton, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(26)))
        regionButton = button("清除区域 ×") { onClearRegion() }.apply { visibility = GONE }
        regionControls.addView(regionButton, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(26)).apply {
            leftMargin = dp(6)
        })
        addView(regionControls, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(29)).apply {
            topMargin = dp(3)
        })
        updateRegexAppearance()
        updateRegionModeAppearance()
    }

    fun updateCount(count: Int, total: Int, error: String? = null) {
        countView.text = error?.let { "!" } ?: if (input.text.isNullOrBlank() && enabledAttributes.isEmpty() && regionButton.visibility != VISIBLE) "全部" else "$count/$total"
        countView.setTextColor(if (error != null) 0xFF9B5145.toInt() else 0xFF56665B.toInt())
        countView.contentDescription = error ?: "$count 个匹配节点，共 $total 个节点"
        filterButton.text = if (enabledAttributes.isEmpty()) "筛选" else "筛选·${enabledAttributes.size}"
    }

    fun setRegionActive(active: Boolean) {
        regionButton.visibility = if (active) VISIBLE else GONE
    }

    fun setRegionSelectionMode(active: Boolean) {
        regionSelectionMode = active
        updateRegionModeAppearance()
    }

    fun currentQuery(): String = input.text?.toString().orEmpty()

    fun hideKeyboard() {
        (appContext.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
            ?.hideSoftInputFromWindow(input.windowToken, 0)
    }

    private fun notifyChanged() = onChanged(currentQuery(), regularExpression, enabledAttributes.toSet())

    private fun showAttributeMenu() {
        PopupMenu(appContext, filterButton).apply {
            InspectorNodeAttribute.values().forEach { attribute ->
                val nextEnabled = attribute !in enabledAttributes
                menu.add(attribute.label).apply {
                    isCheckable = true
                    isChecked = !nextEnabled
                    setOnMenuItemClickListener {
                        if (nextEnabled) enabledAttributes += attribute else enabledAttributes -= attribute
                        updateCount(0, 0)
                        notifyChanged()
                        true
                    }
                }
            }
            menu.add("清除全部属性筛选").setOnMenuItemClickListener {
                enabledAttributes.clear()
                updateCount(0, 0)
                notifyChanged()
                true
            }
            show()
        }
    }

    private fun updateRegionModeAppearance() {
        regionModeButton.setTextColor(if (regionSelectionMode) 0xFF23372B.toInt() else 0xFF718E22.toInt())
        regionModeButton.background = rounded(if (regionSelectionMode) 0xFFA5C331.toInt() else 0xFFEAF2CF.toInt(), 0x00000000, dp(7))
        regionModeButton.contentDescription = if (regionSelectionMode) "退出区域框选模式" else "进入区域框选模式"
    }

    private fun updateRegexAppearance() {
        regexButton.setTextColor(0xFF23372B.toInt())
        regexButton.background = rounded(if (regularExpression) 0xFFA5C331.toInt() else 0xFFFBFAF5.toInt(), 0xFFDDE2D8.toInt(), dp(7))
        regexButton.contentDescription = if (regularExpression) "关闭正则表达式搜索" else "启用正则表达式搜索"
    }

    private fun button(label: String, action: () -> Unit) = TextView(appContext).apply {
        text = label
        textSize = 10.5f
        gravity = Gravity.CENTER
        setTextColor(0xFF23372B.toInt())
        background = rounded(0xFFFBFAF5.toInt(), 0xFFDDE2D8.toInt(), dp(7))
        isClickable = true
        setOnClickListener { action() }
    }

    private fun rounded(fill: Int, stroke: Int, radius: Int) = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = radius.toFloat()
        if (stroke != 0) setStroke(dp(1), stroke)
    }

    private fun dp(value: Int): Int = (value * density + 0.5f).toInt()
}
