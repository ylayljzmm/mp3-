package com.suisheng.player.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import kotlin.math.min

/**
 * 黑胶盘面：纯黑底盘 + 沟槽光照纹理 + 盘沿描边。
 *
 * ## 5.10.0 重做
 *
 * 旧实现是「自绘 90 圈 `drawCircle` 描边（alpha 10，几乎不可见）+ 一个偏心
 * `RadialGradient` 高光球（圆心 −0.32R/−0.34R、半径 1.15R）」。两个问题：
 *  ① `groovePaint` 的 `0x0AFFFFFF` 叠在 `0xFF0E0E12` 上，对比度不到 2%，
 *     肉眼看不见 —— 所以盘面读起来是一块黑饼加一张贴纸；
 *  ② 高光球的圆心和半径都偏离盘心，**零径向对称性**，一转就是一个亮球在绕圈。
 *
 * 现在改成「一张程序化生成的沟槽光照纹理」（[VinylSheenBitmap]）+ 盘沿描边。
 * `highlightPaint` / `spotPaint` / 逐圈沟槽全部删除，`onDraw` 只剩一次
 * `drawBitmap` + 两次 `drawCircle`。
 *
 * ## 不变量（保留旧设计的所有硬约束）
 *
 * - **每帧零分配**：Paint / Bitmap / Rect 都在 `onSizeChanged` 里建好，`onDraw` 只读字段。
 * - **不得 `drawArc` / `SweepGradient` / 任何带硬切线的图元**：5.9 在车机上出过
 *   「四片白扇形贴在黑盘上」的事故。纹理是位图，抗锯齿在生成时控制。
 * - 首帧纹理还没生成好时画纯黑圆（`bg_vinyl_disc` 背景色与 [COLOR_BASE] 同色，不跳色）。
 */
class VinylDiscView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private val basePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = COLOR_BASE }

    private val sheenPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = COLOR_RIM
        strokeWidth = dp(1.2f)
    }

    private val coverRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = COLOR_COVER_RING
        strokeWidth = dp(1f)
    }

    /** 纹理绘制目标 = 盘面的正方形内切框；`onSizeChanged` 更新，`onDraw` 只读。 */
    private val textureDst = Rect()

    private var radiusPx = 0f

    /** 当前盘面用的纹理；null 表示还在后台生成（先画纯黑圆）。 */
    private var sheen: Bitmap? = null

    /** 已请求的纹理尺寸档，避免同一档重复请求。 */
    private var requestedTier = 0

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val size = min(w, h)
        radiusPx = size / 2f
        val left = (w - size) / 2
        val top = (h - size) / 2
        textureDst.set(left, top, left + size, top + size)
        requestSheen()
    }

    /**
     * 请求纹理。
     *
     * 已缓存则同步回调（`onReady` 直接执行，`sheen` 在第一次 `onDraw` 之前就绪，
     * 首帧不黑）；未缓存则后台生成，完成后 post 回主线程 invalidate 一次。
     */
    private fun requestSheen() {
        val diameter = (radiusPx * 2f).toInt()
        if (diameter <= 0) return
        val tier = VinylSheenBitmap.tierFor(diameter)
        if (tier == requestedTier && sheen != null) return
        requestedTier = tier
        VinylSheenBitmap.request(diameter) { bitmap ->
            if (isAttachedToWindow.not()) {
                // 视图还没 attach 时 post 会排队到主线程队列（可行），
                // 但更稳的是直接赋值 —— 此时一定不在绘制中。
                sheen = bitmap
                return@request
            }
            post {
                if (requestedTier != tier) return@post
                sheen = bitmap
                invalidate()
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        val radius = radiusPx
        if (radius <= 0f) return
        val cx = width / 2f
        val cy = height / 2f

        // ① 纯黑底盘：纹理未就绪时这就是最终画面，与 bg_vinyl_disc 同色
        canvas.drawCircle(cx, cy, radius, basePaint)

        // ② 沟槽光照纹理（一次 drawBitmap，抗锯齿在生成时做好）
        val texture = sheen
        if (texture != null && !texture.isRecycled) {
            canvas.drawBitmap(texture, null, textureDst, sheenPaint)
        }

        // ③ 盘沿 + 封面压边
        canvas.drawCircle(cx, cy, radius * RIM_RADIUS_RATIO, rimPaint)
        canvas.drawCircle(cx, cy, radius * COVER_RING_RADIUS_RATIO, coverRingPaint)
    }

    /** 丢纹理（例如低内存回调），下次 `onSizeChanged` 会重新请求。 */
    fun releaseTexture() {
        sheen = null
        requestedTier = 0
        invalidate()
    }

    private fun dp(value: Float): Float = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        value,
        resources.displayMetrics,
    )

    companion object {
        /** 盘面底色；与 `@drawable/bg_vinyl_disc` 的 solid 同色，首帧不跳色。 */
        const val COLOR_BASE = 0xFF0E0E12.toInt()

        /** 盘沿描边（28% 白）。 */
        const val COLOR_RIM = 0x47FFFFFF

        /** 封面外圈压边（20% 白），压在封面与沟槽交界处遮锯齿。 */
        const val COLOR_COVER_RING = 0x33FFFFFF

        /** 盘沿描边半径比。 */
        const val RIM_RADIUS_RATIO = 0.996f

        /**
         * 封面压边半径比。
         *
         * 由 [VinylRecordView.COVER_RATIO] 派生（0.56 + 0.015 = 0.575），
         * 所以改封面占比时只需动 `COVER_RATIO` 一处。
         */
        const val COVER_RING_RADIUS_RATIO = VinylRecordView.COVER_RATIO + 0.015f
    }
}
