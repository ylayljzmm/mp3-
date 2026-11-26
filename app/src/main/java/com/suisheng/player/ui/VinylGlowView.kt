package com.suisheng.player.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import android.view.animation.DecelerateInterpolator
import kotlin.math.min

/**
 * 黑胶的环境光层：**中性灰柔光罩 + 追光光束**。
 *
 * ## 光效的三块拼图（都是 QQ 音乐实测出来的，不是猜的）
 *
 * 1. **柔光罩** —— `simple_light_effect_view_back_mask.webp` 786×786 的径向剖面是
 *    「中心最亮、边缘淡出」：中心 `RGB(76,76,76) alpha=219`，`f=0.50` 时 128，
 *    `f=0.90` 时 9，`f=1.00` 时 0。也就是说它是**一块中性灰的径向渐变提亮罩**，
 *    叠在盘面上得到「盘浮在光雾里」的质感 —— 不是暗角（我最早判断反了）。
 * 2. **封面主色光晕** —— QQ 的 `magicColor` 机制（从封面 bitmap 抽色，
 *    `generateMagicColorNotNull(baseMagicColor, highLightColor)`）。这里用同样思路：
 *    外层一圈用封面主色，内层保持中性灰，颜色只做点缀不做主体。
 * 3. **追光光束** —— `spotlight_effect_*.png` 8 张实测：纵向 alpha 从 218 衰减到 8，
 *    横向从 67px 锥形张开到 208px，颜色**恒定橙 `#E8622A`、没有渐变**
 *    （之前以为的「橙→浅粉」是 alpha 衰减造成的错觉）。
 *
 * ## 为什么光束要预渲染成位图
 *
 * 锥形必然有两条侧边 = 硬切线。5.9 在车机上出过「四片白扇形贴在黑盘上」的事故，
 * 所以这里：Path 画锥形 → `BlurMaskFilter` 把边缘糊掉 → **烘焙进一张离屏 Bitmap**。
 * 每帧只 `drawBitmap`。这样既没有硬切线，也不依赖硬件加速对 `setMaskFilter` 的支持
 * （那在部分低端 GPU 上行为不一致），还省掉了每帧的模糊计算。
 */
class VinylGlowView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    /** 中性灰柔光（内层）。 */
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    /** 封面主色光晕（外层）。 */
    private val accentPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    /** 追光光束（预渲染位图，一次 drawBitmap）。 */
    private val beamPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)

    /** 光束几何（`onSizeChanged` 里重建，`onDraw` 只读）。 */
    private val conePath = Path()

    private var glowShader: Shader? = null
    private var accentShader: Shader? = null
    private var beamBitmap: Bitmap? = null

    private var centerX = 0f
    private var centerY = 0f
    private var radiusPx = 0f

    /** 封面主色（默认中性灰偏暖，没拿到封面时用）。 */
    private var accentColor = DEFAULT_ACCENT

    /** 光束目标透明度 0–1（播放时 1、暂停 0）。 */
    private var beamTarget = 0f

    /** 光束当前透明度，由 animator 驱动。 */
    private var beamAlpha = 0f

    private var beamAnimator: ValueAnimator? = null

    init {
        // 光效只是背景装饰，不吃点击（否则会把黑胶的点击吞掉）
        isClickable = false
        isFocusable = false
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        centerX = w / 2f
        centerY = h / 2f
        radiusPx = min(w, h) / 2f
        buildShaders()
        buildBeams()
    }

    /**
     * 设置封面主色。
     *
     * 由宿主在封面解码完成后调用（[CoverAccentExtractor.extract] 的结果）。
     * 颜色只有在真的变了才重建 shader —— 每次切歌都会调，不能无脑重建。
     */
    fun setAccentColor(color: Int) {
        if (color == accentColor) return
        accentColor = color
        buildShaders()
        invalidate()
    }

    /** 播放态：光束淡入，暂停时淡出。 */
    fun setPlaying(playing: Boolean) {
        val target = if (playing && animationsEnabled()) 1f else 0f
        if (target == beamTarget) return
        beamTarget = target
        beamAnimator?.cancel()
        beamAnimator = ValueAnimator.ofFloat(beamAlpha, target).apply {
            duration = if (target > beamAlpha) BEAM_FADE_IN_MS else BEAM_FADE_OUT_MS
            interpolator = DecelerateInterpolator()
            addUpdateListener { animation ->
                beamAlpha = animation.animatedValue as Float
                invalidate()
            }
        }.also { it.start() }
    }

    override fun onDraw(canvas: Canvas) {
        val radius = radiusPx
        if (radius <= 0f) return

        // ① 外层：封面主色光晕（大而淡，只做氛围）
        accentShader?.let {
            accentPaint.shader = it
            canvas.drawCircle(centerX, centerY, radius * ACCENT_RADIUS_RATIO, accentPaint)
        }

        // ② 内层：中性灰柔光罩（QQ back_mask 的剖面）
        glowShader?.let {
            glowPaint.shader = it
            canvas.drawCircle(centerX, centerY, radius * GLOW_RADIUS_RATIO, glowPaint)
        }

        // ③ 追光光束：左右各一，播放时淡入
        val beams = beamBitmap
        if (beams != null && !beams.isRecycled && beamAlpha > 0.01f) {
            beamPaint.alpha = (beamAlpha * 255f).toInt().coerceIn(0, 255)
            canvas.drawBitmap(beams, null, textureDst(), beamPaint)
        }
    }

    /**
     * 光束目标矩形 = View 全幅。
     *
     * `drawBitmap(bitmap, src=null, dst, paint)` 接受一个 Rect，但每帧 new 一个 Rect
     * 违反零分配，所以在 `onSizeChanged` 里维护一个实例字段。
     */
    private val beamDst = android.graphics.Rect()

    private fun textureDst(): android.graphics.Rect {
        if (beamDst.isEmpty) beamDst.set(0, 0, width, height)
        return beamDst
    }

    override fun onDetachedFromWindow() {
        beamAnimator?.cancel()
        beamAnimator = null
        beamBitmap?.let { if (!it.isRecycled) it.recycle() }
        beamBitmap = null
        super.onDetachedFromWindow()
    }

    // ------------------------------------------------------------------ shader

    private fun buildShaders() {
        val radius = radiusPx
        if (radius <= 0f) return

        // 中性灰柔光：中心 alpha≈0.34（比 QQ 的 0.86 淡，因为底下已经是纯黑盘面，
        // 叠太亮会吃掉沟槽对比度），到边缘 0
        val glowRadius = radius * GLOW_RADIUS_RATIO
        glowShader = RadialGradient(
            centerX,
            centerY,
            glowRadius,
            intArrayOf(GLOW_CENTER, GLOW_MID, GLOW_EDGE),
            floatArrayOf(0f, 0.58f, 1f),
            Shader.TileMode.CLAMP,
        )

        // 封面主色光晕：更大更淡，颜色只做点缀
        val accentRadius = radius * ACCENT_RADIUS_RATIO
        accentShader = RadialGradient(
            centerX,
            centerY,
            accentRadius,
            intArrayOf(
                withAlpha(accentColor, 0x2E),
                withAlpha(accentColor, 0x18),
                withAlpha(accentColor, 0x00),
            ),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP,
        )
    }

    /**
     * 预渲染追光光束。
     *
     * 几何：两条从盘面上方外围斜射进来的锥形，左上、右上各一，向下向内收。
     * 颜色恒定橙 [BEAM_COLOR]，alpha 沿光束长度从 0.85 衰减到 0 —— 复刻实测剖面
     * （218→8，`a = 218·(1−t)^1.35`）。
     *
     * 端到端做在一张与 View 等大的 ARGB 位图上：`BlurMaskFilter` 需要软件画布，
     * 所以用 `Bitmap.createBitmap` + `Canvas(bitmap)`，画一次就够。
     */
    private fun buildBeams() {
        val w = width
        val h = height
        if (w <= 0 || h <= 0) return
        beamDst.set(0, 0, w, h)
        beamBitmap?.let { if (!it.isRecycled) it.recycle() }
        val bitmap = runCatching { Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888) }
            .getOrNull() ?: return
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = BEAM_COLOR
        paint.maskFilter = BlurMaskFilter(dp(BEAM_BLUR_DP), BlurMaskFilter.Blur.NORMAL)

        val topY = -h * 0.12f
        val bottomY = h * 1.02f
        for (mirror in MIRROR_SIGNS) {
            val apexX = centerX + mirror * w * BEAM_APEX_OFFSET_X
            val leftX = centerX + mirror * w * BEAM_BOTTOM_INNER_X
            val rightX = centerX + mirror * w * BEAM_BOTTOM_OUTER_X
            conePath.reset()
            conePath.moveTo(apexX, topY)
            conePath.lineTo(leftX, bottomY)
            conePath.lineTo(rightX, bottomY)
            conePath.close()
            paint.shader = LinearGradient(
                apexX, topY, (leftX + rightX) / 2f, bottomY,
                intArrayOf(withAlpha(BEAM_COLOR, 0xD8), withAlpha(BEAM_COLOR, 0x66), withAlpha(BEAM_COLOR, 0x00)),
                floatArrayOf(0f, 0.62f, 1f),
                Shader.TileMode.CLAMP,
            )
            canvas.drawPath(conePath, paint)
        }
        conePath.reset()
        beamBitmap = bitmap
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        // 位图按 View 尺寸烘焙；尺寸变了必须重做
        if (changed && width > 0 && height > 0 && beamDst.width() != width) buildBeams()
    }

    private fun animationsEnabled(): Boolean = ANIMATOR_SCALE
        .let { runCatching { android.provider.Settings.Global.getFloat(context.contentResolver, it, 1f) }.getOrDefault(1f) } > 0f

    private fun withAlpha(color: Int, alpha: Int): Int =
        (color and 0x00FFFFFF) or (alpha.coerceIn(0, 255) shl 24)

    private fun dp(value: Float): Float = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        value,
        resources.displayMetrics,
    )

    companion object {
        /** 追光颜色：实测 `#E8622A`（恒定橙，无渐变）。 */
        const val BEAM_COLOR = 0xFFE8622A.toInt()

        /** 默认主色：拿不到封面时的暖灰。 */
        const val DEFAULT_ACCENT = 0xFF6E5B33.toInt()

        /** 中性灰柔光：中心 / 中段 / 边缘。 */
        private const val GLOW_CENTER = 0x44FFFFFF
        private const val GLOW_MID = 0x18FFFFFF
        private const val GLOW_EDGE = 0x00FFFFFF

        /** 柔光罩半径比（略小于盘面，让盘沿留出暗边）。 */
        private const val GLOW_RADIUS_RATIO = 0.92f

        /** 主色光晕半径比（大于盘面，形成外溢光）。 */
        private const val ACCENT_RADIUS_RATIO = 1.28f

        /** 光束顶点在水平方向的偏移（占宽度）。 */
        private const val BEAM_APEX_OFFSET_X = 0.34f

        /** 光束落点在底部的内 / 外边界（占宽度，相对中心）。 */
        private const val BEAM_BOTTOM_INNER_X = 0.10f
        private const val BEAM_BOTTOM_OUTER_X = 0.62f

        /** 光束模糊半径（dp）。 */
        private const val BEAM_BLUR_DP = 14f

        private const val BEAM_FADE_IN_MS = 600L
        private const val BEAM_FADE_OUT_MS = 400L

        private const val ANIMATOR_SCALE = android.provider.Settings.Global.ANIMATOR_DURATION_SCALE

        private val MIRROR_SIGNS = floatArrayOf(-1f, 1f)
    }
}
