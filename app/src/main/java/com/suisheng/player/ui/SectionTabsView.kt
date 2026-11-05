package com.suisheng.player.ui

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.color.MaterialColors

/**
 * 曲库页顶部的分区标签控件：横向排布若干等宽格子，每格包含一个标题 [TextView] 与一条下划线。
 *
 * 之所以不用 [com.google.android.material.tabs.TabLayout]，是因为它在 MODE_FIXED + GRAVITY_FILL
 * 下每个 tab 的最终宽度取 max(固有宽度, 可用宽度 / N)，永远不会压缩到固有宽度以下；中文标签字宽
 * 较大或系统字体放大会导致最右侧 tab 被裁切。本控件用 layout_weight 严格均分宽度，任何屏宽、任何
 * 字体缩放下都不会溢出，也始终居中。
 */
class SectionTabsView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : LinearLayout(context, attrs, defStyleAttr) {

    /** 仅由用户点击触发；ViewPager2 的滚动同步请走 [setSelectedIndex]，不会回调这里。 */
    var onSelected: ((Int) -> Unit)? = null

    /** 当前高亮下标，供点击时判断是否与当前相同。 */
    private var selectedIndex: Int = 0

    private val titleViews = mutableListOf<TextView>()
    private val underlineViews = mutableListOf<View>()

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    /**
     * 用给定标题重建全部格子。若标题数量变化导致当前 [selectedIndex] 越界，会回退到 0。
     *
     * @param titles 每个分区对应的标题，顺序与分页顺序一致。
     */
    fun setTitles(titles: List<String>) {
        removeAllViews()
        titleViews.clear()
        underlineViews.clear()

        val density = resources.displayMetrics.density
        val underlineWidth = (20 * density).toInt()
        val underlineHeight = (3 * density).toInt()
        val underlineCorner = 1.5f * density
        val underlineTopMargin = (6 * density).toInt()
        val underlineColor = MaterialColors.getColor(
            this,
            com.google.android.material.R.attr.colorPrimary,
        )

        titles.forEachIndexed { index, title ->
            val cell = LinearLayout(context).apply {
                orientation = VERTICAL
                gravity = Gravity.CENTER
                layoutParams = LayoutParams(0, LayoutParams.MATCH_PARENT, 1f)
                isClickable = true
                isFocusable = true
                contentDescription = title
                foreground = resolveSelectableForeground()
                setOnClickListener {
                    // 与当前相同则 setSelectedIndex 内部直接返回，不重建，仅在下方回调通知外部。
                    setSelectedIndex(index)
                    onSelected?.invoke(index)
                }
            }

            val titleView = TextView(context).apply {
                text = title
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                gravity = Gravity.CENTER
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                includeFontPadding = false
                setPadding(0, 0, 0, 0)
                layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
            }

            val underline = View(context).apply {
                layoutParams = LayoutParams(underlineWidth, underlineHeight).apply {
                    topMargin = underlineTopMargin
                }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = underlineCorner
                    setColor(underlineColor)
                }
            }

            cell.addView(titleView)
            cell.addView(underline)
            addView(cell)

            titleViews.add(titleView)
            underlineViews.add(underline)
        }

        if (selectedIndex >= titleViews.size) {
            selectedIndex = 0
        }
        applySelection()
    }

    /**
     * 仅更新高亮视觉，不触发 [onSelected]。供 ViewPager2 的页面切换回调做单向同步，避免回调死循环。
     *
     * @param index 目标下标，越界或与当前相同时直接忽略。
     */
    fun setSelectedIndex(index: Int) {
        if (index < 0 || index >= titleViews.size) {
            return
        }
        if (index == selectedIndex) {
            return
        }
        selectedIndex = index
        applySelection()
    }

    /** 依据 [selectedIndex] 刷新文字颜色、字重与下划线显隐，并同步子格子的 isSelected 状态。 */
    private fun applySelection() {
        val activeColor = MaterialColors.getColor(
            this,
            com.google.android.material.R.attr.colorOnSurface,
        )
        val inactiveColor = MaterialColors.getColor(
            this,
            com.google.android.material.R.attr.colorOnSurfaceVariant,
        )

        titleViews.forEachIndexed { index, titleView ->
            val selected = index == selectedIndex
            titleView.setTextColor(if (selected) activeColor else inactiveColor)
            titleView.typeface = if (selected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            underlineViews[index].visibility = if (selected) VISIBLE else INVISIBLE
        }

        for (i in 0 until childCount) {
            getChildAt(i).isSelected = i == selectedIndex
        }
    }

    /** 解析 ?attr/selectableItemBackgroundBorderless 作为格子前景；解析失败时返回 null（不影响功能）。 */
    private fun resolveSelectableForeground(): Drawable? {
        val typedValue = TypedValue()
        val resolved = context.theme.resolveAttribute(
            android.R.attr.selectableItemBackgroundBorderless,
            typedValue,
            true,
        )
        // TypedValue 没有 drawable 字段（那是 TypedArray 的 API）；这里只能走 resourceId。
        // resourceId 为 0 说明该 attr 指向的是内联值而非可复用资源，放弃前景即可。
        if (!resolved || typedValue.resourceId == 0) {
            return null
        }
        return runCatching { context.getDrawable(typedValue.resourceId) }.getOrNull()
    }
}
