package com.suisheng.player.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.google.android.material.color.MaterialColors
import com.suisheng.player.util.PinyinIndex

/**
 * 竖排 `0 / A–Z / #` 索引轴（方案 H §5.3 / Q3 触摸靶裁定）。
 *
 * 视觉是细线浅色字符、无底色无边框；整列可点，按 y 就近命中 28 等分槽位，
 * 所以实际触摸靶远大于字符本身。选中项变强调色 + 粗体，没有歌曲的字母变淡。
 */
class AlphabetIndexBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = 14f * resources.displayMetrics.scaledDensity
    }
    private var selected: Char? = null
    private var available: Set<Char> = emptySet()

    /** 命中字符时回调；只在字符变化时触发，滑动不会重复跳转。 */
    var onPick: ((Char) -> Unit)? = null

    fun setAvailable(chars: Set<Char>) {
        available = chars
        invalidate()
    }

    fun setSelected(c: Char?) {
        selected = c
        invalidate()
    }

    fun clearSelection() = setSelected(null)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (height <= 0) return
        val slot = height / PinyinIndex.AXIS.size.toFloat()
        val normal = MaterialColors.getColor(
            this, com.google.android.material.R.attr.colorOnSurfaceVariant
        )
        val accent = MaterialColors.getColor(
            this, com.google.android.material.R.attr.colorPrimary
        )
        val fm = paint.fontMetrics
        PinyinIndex.AXIS.forEachIndexed { index, c ->
            val active = c == selected
            paint.color = if (active) accent else normal
            paint.typeface = if (active) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            paint.alpha = if (available.isEmpty() || c in available) 255 else 90
            val y = slot * index + slot / 2f - (fm.ascent + fm.descent) / 2f
            canvas.drawText(c.toString(), width / 2f, y, paint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                if (height <= 0) return true
                val index = (event.y / height * PinyinIndex.AXIS.size)
                    .toInt().coerceIn(0, PinyinIndex.AXIS.lastIndex)
                val c = PinyinIndex.AXIS[index]
                if (c != selected) {
                    selected = c
                    invalidate()
                    onPick?.invoke(c)
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                performClick()
                return true
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}
