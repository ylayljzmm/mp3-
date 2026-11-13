package com.suisheng.player.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.text.TextPaint
import android.util.AttributeSet
import android.view.Choreographer
import android.view.View
import com.suisheng.player.data.SettingsRepository
import com.suisheng.player.model.LrcLine
import com.suisheng.player.model.LyricState
import kotlin.math.abs

class KaraokeLyricView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val settings = SettingsRepository(context)
    private val sungPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val nextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val pendingPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)

    private var lyricState: LyricState? = null
    private var displayPositionMs = 0f
    private var targetPositionMs = 0L
    private var animating = false

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!animating) return
            val lerp = if (settings.smoothLyrics) 0.22f else 1f
            displayPositionMs += (targetPositionMs - displayPositionMs) * lerp
            if (!settings.smoothLyrics) {
                displayPositionMs = targetPositionMs.toFloat()
            }
            invalidate()
            if (needsContinuousFrames()) {
                Choreographer.getInstance().postFrameCallback(this)
            } else {
                displayPositionMs = targetPositionMs.toFloat()
                animating = false
            }
        }
    }

    init {
        setWillNotDraw(false)
    }

    fun update(state: LyricState?) {
        lyricState = state
        targetPositionMs = state?.positionMs ?: 0L
        if (!settings.smoothLyrics) {
            displayPositionMs = targetPositionMs.toFloat()
            invalidate()
            return
        }
        startAnimatingIfNeeded()
    }

    fun applySettings() {
        invalidate()
    }

    private fun startAnimatingIfNeeded() {
        if (animating) return
        animating = true
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    private fun needsContinuousFrames(): Boolean {
        if (abs(targetPositionMs - displayPositionMs) > 2f) return true
        val line = lyricState?.currentLine ?: return false
        if (line.chars.size <= 1) return false
        // 只在本行**正在唱**的区间里请求连续帧。
        // 这里以前用的是 endTimeMs（= 下一行起点，含行后间奏），于是整段间奏都在重绘，
        // 叠加上被平均分的字时长，就表现为「间奏时末字还在慢慢擦」。改用演唱结束时刻。
        val singEnd = line.effectiveSingEndMs
        return displayPositionMs > line.startTimeMs && displayPositionMs < singEnd.toFloat()
    }

    override fun onDetachedFromWindow() {
        animating = false
        Choreographer.getInstance().removeFrameCallback(frameCallback)
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val state = lyricState ?: return
        val density = resources.displayMetrics.scaledDensity
        val style = LyricRenderer.styleFrom(context, settings, density, forPlayer = false)
        LyricRenderer.drawOverlayBlock(
            canvas,
            state.currentLine,
            state.nextLine,
            displayPositionMs,
            width,
            height,
            style,
            sungPaint,
            nextPaint,
            pendingPaint
        )
    }
}
