package com.suisheng.player.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.SweepGradient
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
 * 三层内容：
 *  - L1 盘体：径向渐变（光心 38%,30%），`#333 → #1a1a1a 30% → #0d0d0d 62% → #050505`
 *  - L2 纹路：40 圈等距 1dp 细弧，白色 alpha 0.028，半径 0.42R ~ 0.98R 均分
 *  - L3 高光：一道 40° 宽的柔光带（从 200° 起），`SweepGradient` 近似 `BlendMode.SCREEN`
 *
 * 关于 `BlendMode.SCREEN`：它是 API 29 才有的，本项目 minSdk 24，
 * 所以用「白色低 alpha + SRC_OVER」近似 —— 黑底上白 alpha 的叠加结果与 SCREEN 极接近。
 */
class VinylDiscView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
        color = RING_COLOR
    }
    private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.5f)
        color = RIM_COLOR
    }

    private var bodyShader: RadialGradient? = null
    private var highlightShader: SweepGradient? = null
    private var radiusPx = 0f

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        radiusPx = min(w, h) / 2f
        if (radiusPx <= 0f) return
        val cx = w / 2f
        val cy = h / 2f

        bodyShader = RadialGradient(
            // 光心偏左上（38%, 30%），让盘面有厚度感而不是一块平黑饼
            w * 0.38f, h * 0.30f, radiusPx * 1.06f,
            intArrayOf(0xFF333333.toInt(), 0xFF1A1A1A.toInt(), 0xFF0D0D0D.toInt(), 0xFF050505.toInt()),
            floatArrayOf(0f, 0.30f, 0.62f, 1f),
            Shader.TileMode.CLAMP,
        )

        // 40° 柔光带：200° 起。SweepGradient 从 3 点钟方向顺时针，
        // 位置用「角度 / 360」折算；两端各留一段完全透明，避免接缝。
        highlightShader = SweepGradient(
            cx, cy,
            intArrayOf(
                Color.TRANSPARENT,
                HIGHLIGHT_STRONG,   // 200°
                Color.TRANSPARENT,  // 240°
                HIGHLIGHT_SOFT,     // 280°
                Color.TRANSPARENT,  // 320°
                HIGHLIGHT_TAIL,     // 360°
            ),
            floatArrayOf(0f, 200f / 360f, 240f / 360f, 280f / 360f, 320f / 360f, 1f),
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val r = radiusPx
        if (r <= 0f) return

        // L1 盘体
        bodyShader?.let {
            bodyPaint.shader = it
            canvas.drawCircle(cx, cy, r, bodyPaint)
            bodyPaint.shader = null
        }

        // L2 40 圈纹路：0.42R ~ 0.98R 均分
        val inner = r * 0.42f
        val outer = r * 0.98f
        val step = (outer - inner) / (RING_COUNT - 1)
        var radius = inner
        repeat(RING_COUNT) {
            canvas.drawCircle(cx, cy, radius, ringPaint)
            radius += step
        }

        // L3 高光
        highlightShader?.let {
            highlightPaint.shader = it
            canvas.drawCircle(cx, cy, r, highlightPaint)
            highlightPaint.shader = null
        }

        // 外沿：1.5dp 白色 10% 描边，给盘边一点厚度
        canvas.drawCircle(cx, cy, r - rimPaint.strokeWidth / 2f, rimPaint)
    }

    private fun dp(value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics)

    private companion object {
        /** 40 圈（`05 §4.1` L2）。 */
        const val RING_COUNT = 40

        /** 白色 alpha 0.028 ≈ 7/255。 */
        const val RING_COLOR = 0x07FFFFFF

        /** 柔光带三档透明度：0.13 / 0.07 / 0.10。 */
        const val HIGHLIGHT_STRONG = 0x21FFFFFF
        const val HIGHLIGHT_SOFT = 0x12FFFFFF
        const val HIGHLIGHT_TAIL = 0x1AFFFFFF

        /** 外沿 1.5dp 白 10%。 */
        const val RIM_COLOR = 0x1AFFFFFF
    }
}
