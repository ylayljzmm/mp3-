package com.suisheng.player.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import kotlin.math.max
import kotlin.math.min

/**
 * 黑胶盘体（盘底 + 沟槽 + 反光 + 收口）。
 *
 * 单独抽成一个 View 是为了满足绘制顺序：盘体、纹路、高光都必须在**封面之下**，
 * 而封面上不需要再盖任何东西。FrameLayout 的 `onDraw` 永远早于子 View，
 * 所以「盘体 + 纹路 + 高光」只能是子 View 里的第一个。
 *
 * ## 6.0 为什么彻底删掉弧形反光
 *
 * 旧实现在盘面上画了 4 段 `drawArc`：宽的当柔边、窄的当芯子，白 alpha 0.125 起。
 * 问题在于 **`drawArc` 没有柔边** —— 弧的两条边是硬切线，宽度又有 0.045R
 * （296dp 的盘上是 13px），在车机上能一眼看到"四片白色扇形贴在黑盘上"，
 * 也就是用户反馈的「黑胶上有白色片段」。叠两个不同宽度的弧也救不回来：
 * 它们的边仍然是硬的，只是边多了几条。
 *
 * 现在高光只用**离轴径向渐变**：渐变的边缘天然是柔的（0 到 1 连续降 alpha，
 * 最外一圈 alpha 为 0），几何形状是"光源偏左上的一点漫反射"，不再有任何直线边。
 *
 * ## 5.9 等亮密纹与深色环带
 *
 * 沟槽改成完全等亮、等距、极细的同心圆，不再「每 7 圈提亮一条」—— 分层亮环
 * 转起来会跟着盘摆，是盘面上唯一会动的视觉噪音。沟槽起点由
 * [VinylRecordView.COVER_RATIO] 派生：封面边缘到密纹之间留一圈纯盘底的深色环带，
 * 同时可见密纹区变窄，每帧 `drawCircle` 次数约减半。
 *
 * **本次同样不得引入扇形 / 弧形贴片**：高光只用径向渐变且末停点必须 alpha 0，
 * 禁止 `drawArc`、`SweepGradient` 与任何带硬切线的图元。
 *
 * **每帧零分配**：Paint 与两个 Shader 都在 [onSizeChanged] 里建好，
 * [onDraw] 只画圆。30fps 持续转动下这是硬要求。
 */
class VinylDiscView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    /** L1 盘底：实心圆，无色阶可断。 */
    private val basePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = COLOR_BASE
        isDither = true
    }

    /** L2 沟槽：颜色在建 Paint 时一次定死，绘制帧不再逐圈写 color。 */
    private val groovePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = COLOR_GROOVE
    }

    /** L3 主高光：一个偏心径向渐变铺满盘面。 */
    private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { isDither = true }

    /** L3 副高光：更小更亮的一点，做出"光源中心"的落点。 */
    private val spotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { isDither = true }

    /** L4 外沿收口。 */
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = COLOR_RIM
    }

    /** L4 封面外圈：压住封面位图的边缘锯齿。 */
    private val coverRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = COLOR_COVER_RING
    }

    private var radiusPx = 0f

    /** 相邻两圈沟槽的半径差（px）。0 表示尺寸还没算出来，[onDraw] 直接返回。 */
    private var grooveStepPx = 0f

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        radiusPx = min(w, h) / 2f
        if (radiusPx <= 0f) {
            grooveStepPx = 0f
            highlightPaint.shader = null
            spotPaint.shader = null
            return
        }
        val cx = w / 2f
        val cy = h / 2f

        // 沟槽间距：车机上盘面只有 80 ~ 300dp，环太密会和屏幕像素栅格打摩尔纹，
        // 所以下限钉在 2px（比 1px 稳），大盘按 0.9% 半径适度放大。
        grooveStepPx = max(2.0f, radiusPx * GROOVE_STEP_RATIO)
        groovePaint.strokeWidth = dp(0.8f)
        rimPaint.strokeWidth = dp(1.2f)
        coverRingPaint.strokeWidth = dp(1f)

        // 主高光：圆心偏离盘心 0.32R / 0.34R（左上），半径 1.15R —— 比盘大，
        // 保证"最亮处"落在盘内、而渐变边缘已经淡到 0，看不到任何边界。
        highlightPaint.shader = RadialGradient(
            cx - radiusPx * HIGHLIGHT_OFFSET_X,
            cy - radiusPx * HIGHLIGHT_OFFSET_Y,
            radiusPx * HIGHLIGHT_RADIUS_RATIO,
            intArrayOf(
                COLOR_HIGHLIGHT_CORE,
                COLOR_HIGHLIGHT_MID,
                COLOR_HIGHLIGHT_FAR,
                COLOR_HIGHLIGHT_EDGE,
            ),
            floatArrayOf(0f, 0.45f, 0.75f, 1f),
            Shader.TileMode.CLAMP,
        )

        // 副高光：更小（0.45R）更亮，叠在主高光上让"光源"有个实心落点。
        spotPaint.shader = RadialGradient(
            cx - radiusPx * SPOT_OFFSET,
            cy - radiusPx * SPOT_OFFSET,
            radiusPx * SPOT_RADIUS_RATIO,
            intArrayOf(COLOR_SPOT_CORE, COLOR_SPOT_EDGE),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP,
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val r = radiusPx
        if (r <= 0f || grooveStepPx <= 0f) return
        val cx = width / 2f
        val cy = height / 2f

        // L1 盘底
        canvas.drawCircle(cx, cy, r, basePaint)

        // L2 沟槽：从「封面半径 + 环带」铺到 0.985R，等距、等亮、无分层。
        val outer = r * GROOVE_END_RATIO
        var radius = r * GROOVE_START_RATIO
        while (radius < outer) {
            canvas.drawCircle(cx, cy, radius, groovePaint)
            radius += grooveStepPx
        }

        // L3 高光（先大范围主光，再叠小亮斑）
        canvas.drawCircle(cx, cy, r, highlightPaint)
        canvas.drawCircle(cx, cy, r, spotPaint)

        // L4 收口：外沿一圈细亮线勾出盘边，封面外圈一圈更淡的线压住位图锯齿。
        canvas.drawCircle(cx, cy, r * RIM_RADIUS_RATIO, rimPaint)
        canvas.drawCircle(cx, cy, r * COVER_RING_RADIUS_RATIO, coverRingPaint)
    }

    private fun dp(value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics)

    private companion object {
        /** L1 盘底：与 `bg_vinyl_disc.xml` 的 solid 同色，首帧不跳色。 */
        val COLOR_BASE = 0xFF0E0E12.toInt()

        /** L2 沟槽：白 alpha 0x0A（≈0.04），所有圈完全等亮。 */
        val COLOR_GROOVE = 0x0AFFFFFF

        /**
         * L3 主高光的四个停点：0x22 → 0x11 → 0x07 → 0x00（全透明）。
         *
         * 最后一个停点**必须**是 alpha 0：否则渐变圆的外缘会留下一条硬边，
         * 那就等于把删掉的弧形反光换个形状又贴回来。
         */
        val COLOR_HIGHLIGHT_CORE = 0x22FFFFFF
        val COLOR_HIGHLIGHT_MID = 0x11FFFFFF
        val COLOR_HIGHLIGHT_FAR = 0x07FFFFFF
        val COLOR_HIGHLIGHT_EDGE = 0x00FFFFFF

        /** L3 副高光：起 0x18、止全透明。 */
        val COLOR_SPOT_CORE = 0x18FFFFFF
        val COLOR_SPOT_EDGE = 0x00FFFFFF

        /** L4 收口：外沿细亮线、封面外圈压边。 */
        val COLOR_RIM = 0x47FFFFFF
        val COLOR_COVER_RING = 0x33FFFFFF

        /**
         * 沟槽起点 = 封面半径 + 0.05R 深色环带；压边半径 = 封面半径 + 0.015R。
         * 一律由 [VinylRecordView.COVER_RATIO] 派生，改封面占比时只需动一处。
         */
        const val GROOVE_START_RATIO = VinylRecordView.COVER_RATIO + 0.05f
        const val GROOVE_END_RATIO = 0.985f
        const val GROOVE_STEP_RATIO = 0.009f
        const val COVER_RING_RADIUS_RATIO = VinylRecordView.COVER_RATIO + 0.015f

        /** 主高光圆心偏移与半径。 */
        const val HIGHLIGHT_OFFSET_X = 0.32f
        const val HIGHLIGHT_OFFSET_Y = 0.34f
        const val HIGHLIGHT_RADIUS_RATIO = 1.15f

        /** 副高光圆心偏移与半径。 */
        const val SPOT_OFFSET = 0.30f
        const val SPOT_RADIUS_RATIO = 0.45f

        /** 外沿收口半径。 */
        const val RIM_RADIUS_RATIO = 0.996f
    }
}
