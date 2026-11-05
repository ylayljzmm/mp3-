package com.suisheng.player.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import androidx.annotation.ColorInt
import androidx.core.graphics.ColorUtils
import com.google.android.material.color.MaterialColors
import com.google.android.material.R as MaterialR

/**
 * 播放页 L1 背景层：两个大径向色块（`design/05 §5.1`）。
 *
 * 为什么不用封面高斯模糊：车机 GPU 弱，而且 `RenderEffect` 要 API 31（本项目 minSdk 24）。
 * 固定两个色块 + 主色微调，性能稳定、观感一致，也省掉解码封面 + 提取调色板的 200ms 首帧。
 *
 * 「blur 80dp」用径向梯度衰减近似 —— 梯度从中心不透明到边缘全透明，
 * 视觉上与高斯模糊过的大色块一致，但只是一次 `drawCircle`。
 *
 * 色相往 `?attr/colorPrimary` 旋转时**限幅 12°**：十套主题色差异极大，
 * 不旋转会让背景与主色打架，旋转太多又会把配色带偏（`05 §5.1` 末段）。
 */
class BackdropView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    /** 左块偏蓝、右块偏暖，是 `05 §5.1` 给定的两个基准色。 */
    private var leftBase: Int = LEFT_BASE
    private var rightBase: Int = RIGHT_BASE

    private var accent: Int = Color.TRANSPARENT
    private var leftShader: RadialGradient? = null
    private var rightShader: RadialGradient? = null

    init {
        // 纯装饰层：不参与点击，也不该拦住任何触摸
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // 主题色只在 attach 时取一次：本页换主题走 recreate，不会原地变
        setAccentColor(MaterialColors.getColor(this, MaterialR.attr.colorPrimary, leftBase))
    }

    /** 由宿主在拿到主题色后调用；重复调用同色不重建 shader。 */
    fun setAccentColor(@ColorInt color: Int) {
        if (accent == color && leftShader != null) return
        accent = color
        leftBase = rotateHueToward(LEFT_BASE, color, ALPHA_LEFT)
        rightBase = rotateHueToward(RIGHT_BASE, color, ALPHA_RIGHT)
        buildShaders()
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        buildShaders()
    }

    private fun buildShaders() {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val span = maxOf(w, h)
        leftShader = radial(w * 0.18f, h * 0.30f, span * 0.62f, leftBase)
        rightShader = radial(w * 0.86f, h * 0.72f, span * 0.56f, rightBase)
    }

    private fun radial(cx: Float, cy: Float, radius: Float, color: Int): RadialGradient =
        RadialGradient(
            cx, cy, radius.coerceAtLeast(1f),
            intArrayOf(color, ColorUtils.setAlphaComponent(color, 0)),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP,
        )

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        leftShader?.let {
            paint.shader = it
            canvas.drawCircle(width * 0.18f, height * 0.30f, maxOf(width, height) * 0.62f, paint)
        }
        rightShader?.let {
            paint.shader = it
            canvas.drawCircle(width * 0.86f, height * 0.72f, maxOf(width, height) * 0.56f, paint)
        }
        paint.shader = null
    }

    companion object {
        /** 左块基准 `#2E5F8C`。 */
        private const val LEFT_BASE = 0xFF2E5F8C.toInt()

        /** 右块基准 `#9A6547`。 */
        private const val RIGHT_BASE = 0xFF9A6547.toInt()

        /** 中心不透明度：梯度衰减到边缘 0，近似「模糊后的大色块」。 */
        private const val ALPHA_LEFT = 0x9E
        private const val ALPHA_RIGHT = 0x8C

        /** 色相最多往主色偏 12°（`05 §5.1`）。 */
        private const val MAX_HUE_SHIFT_DEGREES = 12f

        /**
         * 把 [base] 的色相往 [target] 的色相方向旋转，最多 [MAX_HUE_SHIFT_DEGREES] 度。
         *
         * 用 `Color.HSVToColor(float[])` 而不是带 alpha 的重载：后者要 API 26，
         * 本项目 minSdk 24。透明度单独用 `ColorUtils.setAlphaComponent` 叠。
         */
        private fun rotateHueToward(base: Int, target: Int, alpha: Int): Int {
            val baseHsv = FloatArray(3)
            Color.colorToHSV(base, baseHsv)
            val targetHsv = FloatArray(3)
            Color.colorToHSV(target, targetHsv)

            var delta = targetHsv[0] - baseHsv[0]
            // 色相是环形的：走最短弧，避免 350° → 10° 被算成 -340°
            if (delta > 180f) delta -= 360f
            if (delta < -180f) delta += 360f
            delta = delta.coerceIn(-MAX_HUE_SHIFT_DEGREES, MAX_HUE_SHIFT_DEGREES)

            val hsv = floatArrayOf(
                (baseHsv[0] + delta + 360f) % 360f,
                baseHsv[1],
                baseHsv[2],
            )
            return ColorUtils.setAlphaComponent(Color.HSVToColor(hsv), alpha)
        }
    }
}
