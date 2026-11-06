package com.suisheng.player.ui

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.view.View
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.max

/**
 * 歌曲列表的拼音分组头（方案 H §5.3）：用 [RecyclerView.ItemDecoration] 绘制，
 * 不改被三页共用的 `SongAdapter`，也不引入第二种 ViewType。
 *
 * [sections] 每项为「组首在 adapter 中的 position -> 分组标签」；排序或筛选变化后
 * 由页面重算并整体替换，decoration 本身不持有曲库。
 */
class SongSectionDecoration(
    private val headerHeightPx: Int,
    private val barWidthPx: Int,
    private val backgroundColor: Int,
    private val textColor: Int,
    private val accentColor: Int,
) : RecyclerView.ItemDecoration() {

    var sections: List<Pair<Int, String>> = emptyList()
        set(value) {
            field = value
            firstPositions = value.map { it.first }.toHashSet()
            labels = value.toMap()
        }

    private var firstPositions: Set<Int> = emptySet()
    private var labels: Map<Int, String> = emptyMap()

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    // 实际字号由页面按 scaledDensity 通过 setTextSizePx 设置
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 32f
        typeface = Typeface.DEFAULT_BOLD
    }

    fun setTextSizePx(px: Float) {
        textPaint.textSize = px
    }

    override fun getItemOffsets(
        outRect: Rect,
        view: View,
        parent: RecyclerView,
        state: RecyclerView.State,
    ) {
        val position = parent.getChildAdapterPosition(view)
        outRect.top = if (position in firstPositions) headerHeightPx else 0
    }

    override fun onDrawOver(canvas: Canvas, parent: RecyclerView, state: RecyclerView.State) {
        if (sections.isEmpty()) return
        for (i in 0 until parent.childCount) {
            val child = parent.getChildAt(i) ?: continue
            val position = parent.getChildAdapterPosition(child)
            if (position !in firstPositions) continue
            val label = labels[position] ?: continue
            var top = max(0, child.top - headerHeightPx).toFloat()
            // 下一组头顶上来时把当前头往上推，形成吸顶交接
            val nextTop = nextHeaderTop(parent, position)
            if (nextTop in 0 until headerHeightPx) top = (nextTop - headerHeightPx).toFloat()
            drawHeader(canvas, parent.width, top, label)
        }
    }

    private fun nextHeaderTop(parent: RecyclerView, after: Int): Int {
        for (i in 0 until parent.childCount) {
            val child = parent.getChildAt(i) ?: continue
            val position = parent.getChildAdapterPosition(child)
            if (position > after && position in firstPositions) return child.top
        }
        return Int.MAX_VALUE
    }

    private fun drawHeader(canvas: Canvas, width: Int, top: Float, label: String) {
        bgPaint.color = backgroundColor
        canvas.drawRect(0f, top, width.toFloat(), top + headerHeightPx, bgPaint)
        barPaint.color = accentColor
        canvas.drawRect(
            0f, top + headerHeightPx * 0.25f,
            barWidthPx.toFloat(), top + headerHeightPx * 0.75f, barPaint
        )
        textPaint.color = textColor
        val fm = textPaint.fontMetrics
        val baseline = top + headerHeightPx / 2f - (fm.ascent + fm.descent) / 2f
        canvas.drawText(label, (barWidthPx + 16).toFloat(), baseline, textPaint)
    }
}
