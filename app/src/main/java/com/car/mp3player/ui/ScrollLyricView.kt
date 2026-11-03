package com.car.mp3player.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Choreographer
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import androidx.core.view.GestureDetectorCompat
import com.car.mp3player.data.SettingsRepository
import com.car.mp3player.model.LrcLine
import kotlin.math.abs

class ScrollLyricView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val settings = SettingsRepository(context)
    private val sungPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val nextPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pendingPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val measurePaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private var lines: List<LrcLine> = emptyList()

    /** 每行歌词在内容中的起始偏移与高度：按实际换行数累计，行距由设置系数控制 */
    private var lineTops = FloatArray(0)
    private var lineHeights = FloatArray(0)
    private var totalHeightPx = 0f
    private var layoutWidth = -1
    private var layoutIndex = -2
    private var layoutKey = -1f

    private var targetPositionMs = 0L
    private var displayPositionMs = 0f
    private var smoothScrollY = 0f
    private var targetScrollY = 0f
    private var userScrollOffset = 0f
    private var isUserScrolling = false
    private var animating = false

    private val gestureDetector = GestureDetectorCompat(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean {
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }

            override fun onScroll(
                e1: MotionEvent?,
                e2: MotionEvent,
                distanceX: Float,
                distanceY: Float
            ): Boolean {
                isUserScrolling = true
                userScrollOffset -= distanceY
                clampUserScrollOffset()
                invalidate()
                return true
            }

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                performClick()
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                performLongClick()
            }
        }
    )

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!animating) return
            val smooth = settings.smoothLyrics
            val lerp = if (smooth) 0.18f else 1f
            displayPositionMs += (targetPositionMs - displayPositionMs) * lerp
            if (!isUserScrolling) {
                userScrollOffset *= 0.9f
                if (abs(userScrollOffset) < 1.5f) userScrollOffset = 0f
            }
            computeTargetScroll()
            smoothScrollY += (targetScrollY - smoothScrollY) * lerp
            invalidate()
            if (smooth && (abs(targetPositionMs - displayPositionMs) > 8f ||
                    abs(targetScrollY - smoothScrollY) > 0.5f ||
                    (!isUserScrolling && abs(userScrollOffset) > 1.5f))
            ) {
                Choreographer.getInstance().postFrameCallback(this)
            } else {
                displayPositionMs = targetPositionMs.toFloat()
                smoothScrollY = targetScrollY
                if (!isUserScrolling) userScrollOffset = 0f
                animating = false
            }
        }
    }

    init {
        setWillNotDraw(false)
        isClickable = true
        isFocusable = true
    }

    fun update(lines: List<LrcLine>, positionMs: Long) {
        if (this.lines.size != lines.size) invalidateLayout()
        this.lines = lines
        targetPositionMs = positionMs
        if (!settings.smoothLyrics) {
            displayPositionMs = positionMs.toFloat()
            computeTargetScroll()
            smoothScrollY = targetScrollY
            if (!isUserScrolling) userScrollOffset = 0f
            invalidate()
            return
        }
        computeTargetScroll()
        if (!animating) {
            animating = true
            Choreographer.getInstance().postFrameCallback(frameCallback)
        }
    }

    fun refreshStyle() {
        invalidateLayout()
        invalidate()
    }

    private fun invalidateLayout() {
        layoutWidth = -1
        layoutIndex = -2
        layoutKey = -1f
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        gestureDetector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> isUserScrolling = false
        }
        return true
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        invalidateLayout()
        if (lines.isNotEmpty()) {
            computeTargetScroll()
            smoothScrollY = targetScrollY
        }
    }

    override fun onDetachedFromWindow() {
        animating = false
        Choreographer.getInstance().removeFrameCallback(frameCallback)
        super.onDetachedFromWindow()
    }

    private fun currentStyle(): LyricRenderer.Style =
        LyricRenderer.styleFrom(context, settings, resources.displayMetrics.scaledDensity, forPlayer = true)

    private fun textPaddingPx(): Float = paddingLeft.toFloat() + paddingRight.toFloat()

    private fun dp(value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics)

    /**
     * 行高 = 换行行数 × ROW_RATIO 倍字号 + 行距；
     * 行距 = (设置系数 - ROW_RATIO) × 字号，系数 2.4 时单行占 2.4 倍字号。
     */
    private fun ensureLayout(style: LyricRenderer.Style, idx: Int, viewWidth: Int) {
        if (viewWidth <= 0) return
        val key = style.currentSizePx * 31.7f +
            style.nextSizePx * 17.3f +
            style.otherSizePx * 11.9f +
            style.lineSpacing * 7.7f +
            style.maxVisualLines * 3.1f
        if (viewWidth == layoutWidth && idx == layoutIndex && key == layoutKey &&
            lineTops.size == lines.size
        ) {
            return
        }
        layoutWidth = viewWidth
        layoutIndex = idx
        layoutKey = key
        lineTops = FloatArray(lines.size)
        lineHeights = FloatArray(lines.size)
        if (lines.isEmpty()) {
            totalHeightPx = 0f
            return
        }

        measurePaint.typeface = style.typeface
        val maxTextWidth = (viewWidth - textPaddingPx()).coerceAtLeast(dp(48f))
        val gapRatio = (style.lineSpacing - ROW_RATIO).coerceAtLeast(0f)
        var y = 0f
        for (i in lines.indices) {
            val size = when {
                i == idx -> style.currentSizePx
                abs(i - idx) == 1 -> style.nextSizePx
                else -> style.otherSizePx
            }
            measurePaint.textSize = size
            val rows = countRows(lines[i].text, maxTextWidth, style.maxVisualLines)
            val height = rows * size * ROW_RATIO + size * gapRatio
            lineTops[i] = y
            lineHeights[i] = height
            y += height
        }
        totalHeightPx = y
    }

    private fun countRows(text: String, maxWidth: Float, maxRows: Int): Int {
        if (text.isEmpty() || maxWidth <= 0f) return 1
        var start = 0
        var rows = 0
        while (start < text.length && rows < maxRows) {
            val count = measurePaint.breakText(text, start, text.length, true, maxWidth, null)
            if (count <= 0) break
            start += count
            rows++
        }
        return rows.coerceAtLeast(1)
    }

    private fun computeTargetScroll() {
        if (lines.isEmpty()) {
            targetScrollY = 0f
            return
        }
        val pos = if (settings.smoothLyrics) targetPositionMs else displayPositionMs.toLong()
        val idx = findIndex(pos)
        ensureLayout(currentStyle(), idx, width)
        targetScrollY = offsetForLine(idx)
    }

    private fun offsetForLine(idx: Int): Float {
        if (idx !in lineTops.indices) return 0f
        return lineTops[idx] + lineHeights[idx] / 2f
    }

    private fun effectiveScrollY(): Float = smoothScrollY + userScrollOffset

    private fun clampUserScrollOffset() {
        if (lines.isEmpty() || totalHeightPx <= 0f) {
            userScrollOffset = 0f
            return
        }
        val maxOffset = (totalHeightPx / 2f + height / 2f).coerceAtLeast(1f)
        userScrollOffset = userScrollOffset.coerceIn(-maxOffset, maxOffset)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val density = resources.displayMetrics.scaledDensity
        val style = LyricRenderer.styleFrom(context, settings, density, forPlayer = true)

        if (lines.isEmpty()) {
            LyricRenderer.drawWrappedStaticLine(
                canvas, LyricRenderer.PLACEHOLDER_LINE, height / 2f, pendingPaint, style, width.toFloat(),
                style.pendingColor, style.otherSizePx, 2
            )
            return
        }

        val idx = findIndex(displayPositionMs.toLong())
        ensureLayout(style, idx, width)
        val scrollY = effectiveScrollY()

        for (i in lines.indices) {
            val blockTop = height / 2f - scrollY + lineTops[i]
            val lineCenterY = blockTop + lineHeights[i] / 2f
            if (lineCenterY < -lineHeights[i] || lineCenterY > height + lineHeights[i]) continue

            if (i == idx) {
                LyricRenderer.drawKaraokeLine(
                    canvas, lines[i], displayPositionMs, lineCenterY,
                    style, width.toFloat(), sungPaint, pendingPaint
                )
            } else {
                val size = when (abs(i - idx)) {
                    1 -> style.nextSizePx
                    else -> style.otherSizePx
                }
                val color = if (i < idx) style.nextLineColor else style.pendingColor
                LyricRenderer.drawWrappedStaticLine(
                    canvas, lines[i].text, lineCenterY, nextPaint, style,
                    width.toFloat(), color, size, style.maxVisualLines
                )
            }
        }
    }

    private fun findIndex(positionMs: Long): Int {
        var index = 0
        for (i in lines.indices) {
            if (positionMs >= lines[i].startTimeMs) index = i else break
        }
        return index
    }

    private companion object {
        const val ROW_RATIO = 1.35f
    }
}
