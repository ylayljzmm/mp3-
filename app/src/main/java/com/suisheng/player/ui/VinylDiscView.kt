package com.suisheng.player.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import kotlin.math.min

/**
 * 黑胶盘体（`design/05 §4.1` 的 L1 / L2 / L3 三层）。
 *
 * 单独抽成一个 View 是为了满足绘制顺序：盘体、纹路、高光都必须在**封面之下**，
 * 而封面上还要盖中心孔。FrameLayout 的 `onDraw` 永远早于子 View，
 * 所以「盘体 + 纹路 + 高光」只能是子 View 里的第一个。
 *
 * ## 5.5.0 重绘：为什么干掉两个大范围 shader
 *
 * 旧实现是「一个覆盖整盘的 [RadialGradient] + 一个整盘的 SweepGradient」，
 * 在车机上肉眼可见**色阶断层**：
 *  - 25 级亮度摊在 30% 半径上，8bit 输出每 5 ~ 6px 就跳一级；
 *  - 40 圈 1dp、白色 alpha 0.028 的纹路又把这些台阶强化成摩尔纹。
 *
 * 现在改成**纯色分层 + 短弧反光**：大色块用实心圆（无色阶可断），
 * 唯一保留的渐变只在 `0.92R → 1.0R` 的外沿这一窄圈上（面积小，阶梯不可见）。
 *
 * ## 图层
 *
 *  - L1   底盘 `#FF131316` 实心圆
 *  - L1.5 外沿窄渐变（0.92R → 1.0R，3 停点），在 [onSizeChanged] 建好缓存
 *  - L2   三档分环实体色：0.86R / 0.72R / 0.58R（由大到小覆盖）
 *  - L3   三组沟槽，每组 3 圈，STROKE 1dp，白 alpha 0.04
 *  - L4   四段弧形反光（同一环上两段不同宽度做柔边，不用 BlurMaskFilter）
 *  - L5   边缘收口：1.5dp 外沿 + 0.345R 内圈
 *
 * **每帧零分配**：所有 Paint、RectF、Shader 都在 [onSizeChanged] 里建好或复用，
 * [onDraw] 里只改颜色 / 画圆，不 new 任何对象（30fps 转动下这是硬要求）。
 */
class VinylDiscView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    /** L1 底盘（实心，无色阶）。 */
    private val basePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = COLOR_BASE }

    /** L1.5 外沿窄渐变；shader 在 onSizeChanged 里换，Paint 本身复用。 */
    private val outerPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    /** L2 三档分环：同一个 Paint 改颜色，避免建三个对象。 */
    private val bandPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    /** L3 沟槽纹路。 */
    private val groovePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = COLOR_GROOVE
    }

    /** L4 四段反光，各自固定颜色与（随半径缩放的）线宽。 */
    private val arcSoftPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = COLOR_ARC_SOFT
    }
    private val arcCorePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = COLOR_ARC_CORE
    }
    private val arcRimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = COLOR_ARC_RIM
    }
    private val arcFillLightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = COLOR_ARC_FILL
    }

    /** L5 边缘收口。 */
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = COLOR_RIM
    }
    private val innerRimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = COLOR_INNER_RIM
    }

    private var outerShader: RadialGradient? = null
    private var radiusPx = 0f

    /** 四段弧的包围盒，随尺寸重建（每帧复用）。 */
    private val arcSoftRect = RectF()
    private val arcCoreRect = RectF()
    private val arcRimRect = RectF()
    private val arcFillRect = RectF()

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        radiusPx = min(w, h) / 2f
        if (radiusPx <= 0f) return
        val cx = w / 2f
        val cy = h / 2f

        // L1.5 只在最外 8% 半径上做渐变：面积小，8bit 阶梯看不出来。
        // 第一个停点必须是「同色但全透明」，否则整盘会被这一层涂成 COLOR_OUTER，
        // 下面的 L1 底盘与 L2 分环就全被盖住了。
        outerShader = RadialGradient(
            cx, cy, radiusPx,
            intArrayOf(COLOR_OUTER_CLEAR, COLOR_OUTER, COLOR_OUTER_EDGE),
            floatArrayOf(0.92f, 0.965f, 1f),
            Shader.TileMode.CLAMP,
        )
        outerPaint.shader = outerShader

        // 线宽随半径缩放，全部在这里定好，onDraw 里不再算
        groovePaint.strokeWidth = dp(1f)
        arcSoftPaint.strokeWidth = radiusPx * 0.045f
        arcCorePaint.strokeWidth = radiusPx * 0.018f
        arcRimPaint.strokeWidth = radiusPx * 0.010f
        arcFillLightPaint.strokeWidth = radiusPx * 0.008f
        rimPaint.strokeWidth = dp(1.5f)
        innerRimPaint.strokeWidth = dp(1f)

        setArcRect(arcSoftRect, cx, cy, radiusPx * 0.90f)
        setArcRect(arcCoreRect, cx, cy, radiusPx * 0.90f)
        setArcRect(arcRimRect, cx, cy, radiusPx * 0.985f)
        setArcRect(arcFillRect, cx, cy, radiusPx * 0.985f)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val r = radiusPx
        if (r <= 0f) return

        // L1 底盘
        canvas.drawCircle(cx, cy, r, basePaint)

        // L1.5 外沿窄渐变
        canvas.drawCircle(cx, cy, r, outerPaint)

        // L2 三档分环（由大到小覆盖，形成同心层次）
        bandPaint.color = COLOR_BAND_OUTER
        canvas.drawCircle(cx, cy, r * 0.86f, bandPaint)
        bandPaint.color = COLOR_BAND_MIDDLE
        canvas.drawCircle(cx, cy, r * 0.72f, bandPaint)
        bandPaint.color = COLOR_BAND_INNER
        canvas.drawCircle(cx, cy, r * 0.58f, bandPaint)

        // L3 三组沟槽，每组 3 圈
        drawGrooveGroup(canvas, cx, cy, r, 0.94f)
        drawGrooveGroup(canvas, cx, cy, r, 0.80f)
        drawGrooveGroup(canvas, cx, cy, r, 0.66f)

        // L4 四段弧形反光：同环两段（宽 + 窄）叠出柔边
        canvas.drawArc(arcSoftRect, 198f, 30f, false, arcSoftPaint)
        canvas.drawArc(arcCoreRect, 198f, 12f, false, arcCorePaint)
        canvas.drawArc(arcRimRect, 150f, 24f, false, arcRimPaint)
        canvas.drawArc(arcFillRect, 24f, 12f, false, arcFillLightPaint)

        // L5 边缘收口
        canvas.drawCircle(cx, cy, r - rimPaint.strokeWidth / 2f, rimPaint)
        canvas.drawCircle(cx, cy, r * 0.345f, innerRimPaint)
    }

    /** 一组 3 圈：`top` 是本组最外圈占半径的比例，向内每圈退 0.04R。 */
    private fun drawGrooveGroup(canvas: Canvas, cx: Float, cy: Float, r: Float, top: Float) {
        canvas.drawCircle(cx, cy, r * top, groovePaint)
        canvas.drawCircle(cx, cy, r * (top - 0.04f), groovePaint)
        canvas.drawCircle(cx, cy, r * (top - 0.08f), groovePaint)
    }

    private fun setArcRect(rect: RectF, cx: Float, cy: Float, arcRadius: Float) {
        rect.set(cx - arcRadius, cy - arcRadius, cx + arcRadius, cy + arcRadius)
    }

    private fun dp(value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics)

    private companion object {
        /** L1 底盘：与 `bg_vinyl_disc.xml` 的 solid 同色，首帧不跳色。 */
        val COLOR_BASE = 0xFF131316.toInt()

        /** L1.5 外沿渐变的起止色；`COLOR_OUTER_CLEAR` 是「同色全透明」，用于让内侧透出底盘。 */
        val COLOR_OUTER_CLEAR = 0x001A1A1E
        val COLOR_OUTER = 0xFF1A1A1E.toInt()
        val COLOR_OUTER_EDGE = 0xFF0F0F12.toInt()

        /** L2 三档分环实体色（由外到内逐渐压深）。 */
        val COLOR_BAND_OUTER = 0xFF17171A.toInt()
        val COLOR_BAND_MIDDLE = 0xFF111114.toInt()
        val COLOR_BAND_INNER = 0xFF0C0C0F.toInt()

        /** L3 沟槽：白 alpha 0.04（比原来的 0.028 更实，但因为不再有大渐变，不会起摩尔纹）。 */
        val COLOR_GROOVE = 0x0AFFFFFF

        /** L4 四段反光的白 alpha：0.125 / 0.18 / 0.086 / 0.055。 */
        val COLOR_ARC_SOFT = 0x20FFFFFF
        val COLOR_ARC_CORE = 0x2EFFFFFF
        val COLOR_ARC_RIM = 0x16FFFFFF
        val COLOR_ARC_FILL = 0x0EFFFFFF

        /** L5 边缘：外沿白 0.10、内圈白 0.078。 */
        val COLOR_RIM = 0x1AFFFFFF
        val COLOR_INNER_RIM = 0x14FFFFFF
    }
}
