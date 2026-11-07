package com.suisheng.player.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Outline
import android.os.SystemClock
import android.provider.Settings
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import com.suisheng.player.R
import kotlin.math.min

/**
 * 黑胶唱片（`design/05 §4`）。
 *
 * 五层结构在 `view_vinyl_record.xml` 里：盘体 + 纹路 + 高光由一个自绘子 View 画，
 * 封面是 ImageView，中心孔是静态 View。
 *
 * 旋转的三个硬约束（5.5.0 逐条保留，一个字都没动）：
 *  ① **48 秒 / 圈**，30fps 上限 —— 60fps 让盘面纹路出现摩尔纹，且静止时白烧 GPU；
 *  ② 暂停**不重置角度**（`pause()` / `resume()`，不是 cancel + restart）；
 *  ③ 封面**反向等速**：同一个 animator 的值取负写给封面 `rotation`。
 *     用两个 ObjectAnimator 一定会对不齐（各自有独立的帧时钟）。
 *
 * 交互：5.5.0 起只剩「可点」（无障碍 `performClick`）。原来的单击进全屏歌词、
 * 长按 800ms 进仅封面态两个回调随两个状态一起删除，所以这里不再有 Handler 计时器。
 */
class VinylRecordView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {

    private val rotateGroup: FrameLayout
    private val coverView: ImageView
    private var currentBitmap: Bitmap? = null

    /** 最近一次写进封面的直径（px）；0 表示还没算过。 */
    private var coverSizePx = 0

    private var spinner: ValueAnimator? = null
    private var lastFrameAt = 0L
    private var angle = 0f

    init {
        clipChildren = false
        clipToPadding = false
        LayoutInflater.from(context).inflate(R.layout.view_vinyl_record, this, true)
        rotateGroup = findViewById(R.id.vinylRotateGroup)
        coverView = findViewById(R.id.vinylCover)
        // 封面是圆的：用 outline 裁，而不是给 ImageView 套一个圆形 drawable
        coverView.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setOval(0, 0, view.width, view.height)
            }
        }
        coverView.clipToOutline = true
        isClickable = true
        isFocusable = true
    }

    // ------------------------------------------------------------------ 封面

    /**
     * 封面直径按**实际尺寸**算，不再引用 `@dimen/vinyl_art`。
     *
     * 播放页改成三页滑动之后，唱片所在的格子在竖屏 / 横屏、手机 / 车机上的实际边长
     * 差别很大（从几十 dp 到几百 dp），写死一套 dimen 必然在某一档上「封面比盘大」
     * 或者「封面缩在圆心一小块」。这里统一取 0.70 × 短边，与 `VinylDiscView`
     * 的封面外圈（0.715R）对齐 —— 差的那 0.015 正好让白描边露在外面压住锯齿。
     *
     * 只在尺寸真的变了才写 layoutParams：`onSizeChanged` 里改子 View 布局会再触发
     * 一次 layout，不做去重就会变成"布局 → onSizeChanged → 布局"的无底循环。
     */
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val size = (min(w, h) * COVER_RATIO).toInt()
        if (size <= 0 || size == coverSizePx) return
        coverSizePx = size
        val params = coverView.layoutParams ?: return
        params.width = size
        params.height = size
        coverView.layoutParams = params
    }

    fun setCoverBitmap(bitmap: Bitmap?) {
        val previous = currentBitmap
        currentBitmap = bitmap
        if (bitmap == null) {
            coverView.setImageResource(R.drawable.bg_album_placeholder)
        } else {
            coverView.setImageBitmap(bitmap)
        }
        // 先挂上新图再回收旧图，避免 View 从已回收的 Bitmap 上绘制。
        // 不回收的话，连续切歌每首泄漏一张整封面。
        if (previous != null && previous !== bitmap && !previous.isRecycled) {
            previous.recycle()
        }
    }

    // ------------------------------------------------------------------ 旋转

    /** 开始转（无歌 / 暂停态调它也不会出现「转半圈」的跳变）。 */
    fun startSpin() {
        if (animationsDisabled()) {
            // 系统「移除动画」：完全静态展示（`05 §4.2` 降级档）
            applyAngle(angle)
            return
        }
        val existing = spinner
        if (existing != null) {
            if (existing.isPaused) existing.resume() else if (!existing.isStarted) existing.start()
            return
        }
        val animator = ValueAnimator.ofFloat(0f, 360f).apply {
            duration = ROTATION_DURATION_MS
            interpolator = LinearInterpolator()
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART
            addUpdateListener { animation ->
                // 30fps 上限：跳过的帧直接不改 rotation
                val now = SystemClock.uptimeMillis()
                if (now - lastFrameAt < FRAME_INTERVAL_MS) return@addUpdateListener
                lastFrameAt = now
                applyAngle(animation.animatedValue as Float)
            }
        }
        spinner = animator
        animator.start()
    }

    /** 暂停：**不清零角度**（`05 §4.2` 暂停档）。 */
    fun pauseSpin() {
        val animator = spinner ?: return
        if (animator.isStarted && !animator.isPaused) animator.pause()
    }

    fun resumeSpin() {
        val animator = spinner ?: run {
            startSpin()
            return
        }
        if (animator.isPaused) animator.resume() else if (!animator.isStarted) animator.start()
    }

    /** 兼容旧调用点；等价于 [startSpin] / [pauseSpin]。 */
    fun setPlaying(playing: Boolean) {
        if (playing) startSpin() else pauseSpin()
    }

    /** 同一个值写给盘（+angle）和封面（−angle），保证两者严格等速反向。 */
    private fun applyAngle(value: Float) {
        angle = value
        rotateGroup.rotation = value
        coverView.rotation = -value
    }

    private fun animationsDisabled(): Boolean = runCatching {
        Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        ) == 0f
    }.getOrDefault(false)

    // ------------------------------------------------------------------ 触摸

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_UP -> {
                // 无障碍点击仍要可用：只在圆盘内算命中，四角透明区不响应
                if (isInsideDisc(event.x, event.y)) performClick()
                return true
            }

            MotionEvent.ACTION_CANCEL -> return true
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    /** 只有点在圆盘内才算点击目标，四角留白不响应（视觉上是透明区）。 */
    private fun isInsideDisc(x: Float, y: Float): Boolean {
        val radius = min(width, height) / 2f
        if (radius <= 0f) return false
        val dx = x - width / 2f
        val dy = y - height / 2f
        return dx * dx + dy * dy <= radius * radius
    }

    override fun onDetachedFromWindow() {
        spinner?.cancel()
        spinner = null
        super.onDetachedFromWindow()
    }

    private companion object {
        /** 48 秒一圈（`05 §4.2`）。 */
        const val ROTATION_DURATION_MS = 48_000L

        /** 30fps 上限 ≈ 33ms 一帧。 */
        const val FRAME_INTERVAL_MS = 33L

        /** 封面直径占唱片短边的比例（`design/05 §4.1` L4：0.70）。 */
        const val COVER_RATIO = 0.70f
    }
}
