package com.car.mp3player.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
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

    private var lines: List<LrcLine> = emptyList()
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
        invalidate()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        gestureDetector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> isUserScrolling = false
        }
        return true
    }

    override fun onDetachedFromWindow() {
        animating = false
        Choreographer.getInstance().removeFrameCallback(frameCallback)
        super.onDetachedFromWindow()
    }

    private fun blockHeight(style: LyricRenderer.Style): Float {
        val lines = settings.playerLyricLines
        val h = height
        if (h <= 0) {
            return style.currentSizePx * 1.35f * style.maxVisualLines + style.currentSizePx * 0.8f
        }
        // 让一屏恰好容纳约 lines 行歌词（当前行居中），实现紧凑排布
        return (h.toFloat() / lines).coerceAtLeast(style.currentSizePx * 1.25f)
    }

    private fun computeTargetScroll() {
        val pos = if (settings.smoothLyrics) targetPositionMs else displayPositionMs.toLong()
        val idx = findIndex(pos)
        val density = resources.displayMetrics.scaledDensity
        val style = LyricRenderer.styleFrom(context, settings, density, forPlayer = true)
        targetScrollY = idx * blockHeight(style)
    }

    private fun effectiveScrollY(): Float = smoothScrollY + userScrollOffset

    private fun clampUserScrollOffset() {
        if (lines.isEmpty()) {
            userScrollOffset = 0f
            return
        }
        val density = resources.displayMetrics.scaledDensity
        val style = LyricRenderer.styleFrom(context, settings, density, forPlayer = true)
        val blockH = blockHeight(style)
        val contentH = lines.size * blockH
        val maxOffset = (contentH / 2f + height / 2f).coerceAtLeast(blockH)
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
        val blockH = blockHeight(style)
        val scrollY = effectiveScrollY()

        for (i in lines.indices) {
            val lineCenterY = height / 2f - scrollY + i * blockH
            if (lineCenterY < -blockH || lineCenterY > height + blockH) continue

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
}
