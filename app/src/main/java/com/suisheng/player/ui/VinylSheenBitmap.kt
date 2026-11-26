package com.suisheng.player.ui

import android.graphics.Bitmap
import android.graphics.Color
import java.util.concurrent.Executors
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * 黑胶「沟槽光照纹理」的运行时生成器。
 *
 * ## 为什么不用 QQ 那张图
 *
 * 那是它自己的美术资产，直接打包进来是侵权。好消息是这张图**没有信息量**：
 * 它就是一串径向剖面 × 一个固定的角度调制 × 一圈高频条纹（见 [VinylSheenProfile]），
 * 用二十几行数学就能复现，而且生成的图能自适应任意尺寸、不产生缩放摩尔纹。
 *
 * ## 为什么是位图而不是继续 `drawCircle`
 *
 * 5.9 在 `VinylDiscView` 里用 `drawArc` / `SweepGradient` 画过四段弧形反光，
 * 在车机上出现了四片硬切线的白扇形贴片。`drawCircle` 逐圈描边同样有这个问题：
 * 描边是几何图元，抗锯齿在低端 GPU 上不稳定，而且一圈一个 draw call（约 90 圈）。
 * 一张预生成的 ARGB 位图：抗锯齿在生成时由我们控制，绘制是一次 `drawBitmap`。
 *
 * ## 生成成本
 *
 * 384×384 = 147,456 像素，逐像素 sqrt + atan2，实测 15–25ms（中端机）／
 * 60–90ms（低端车机）。所以**必须后台生成**：首帧先按纯黑盘面画（`bg_vinyl_disc`
 * 的背景色与 `COLOR_BASE` 同色，不会跳色），纹理就绪后再 invalidate 一次。
 *
 * ## 缓存
 *
 * 按尺寸档缓存（[tierFor]），`LinkedHashMap` 访问序 + 上限 [MAX_TIERS]。
 * 同一尺寸档只生成一次，转屏 / 反复进出播放页都命中缓存。
 */
object VinylSheenBitmap {

    /** 生成用的尺寸档（直径 px）。取「不小于所需直径的最小档」。 */
    private val TIERS = intArrayOf(256, 384, 512)

    /** 最多缓存几档（三档全缓存也才 3×512²×4 ≈ 3 MB）。 */
    private const val MAX_TIERS = 3

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "vinyl-sheen").apply { isDaemon = true }
    }

    private val lock = Any()

    /** 访问序 LRU：最近用过的挪到队尾，超限时踢队头。 */
    private val cache = object : LinkedHashMap<Int, Bitmap>(4, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, Bitmap>?): Boolean =
            size > MAX_TIERS
    }

    /** 正在生成的尺寸档，避免同一档重复排队。 */
    private val pending = HashSet<Int>()

    /** 取一张不小于 [diameterPx] 的尺寸档；纯查缓存，不触发生成。 */
    fun cachedFor(diameterPx: Int): Bitmap? {
        val tier = tierFor(diameterPx)
        return synchronized(lock) { cache[tier] }
    }

    /**
     * 请求一张不小于 [diameterPx] 的纹理。
     *
     * 已缓存则**同步**回调；否则丢到后台线程生成，完成后回调（回调线程是后台线程，
     * 调用方自己 `post` 回主线程）。同一尺寸档并发请求只会生成一次。
     */
    fun request(diameterPx: Int, onReady: (Bitmap) -> Unit) {
        val tier = tierFor(diameterPx)
        var ready: Bitmap? = null
        var shouldBuild = false
        synchronized(lock) {
            ready = cache[tier]
            // 已经在排队了就不重复排队，也不重复回调（那一档完成后会回调它自己的调用方）
            if (ready == null) shouldBuild = pending.add(tier)
        }
        val cached = ready
        if (cached != null) {
            onReady(cached)
            return
        }
        if (!shouldBuild) return
        executor.execute {
            val bitmap = runCatching { build(tier) }.getOrNull()
            synchronized(lock) {
                pending.remove(tier)
                if (bitmap != null) cache[tier] = bitmap
            }
            if (bitmap != null) onReady(bitmap)
        }
    }

    /** 丢缓存（主题色变了不需要，但低内存回调里可以调）。 */
    fun clear() {
        synchronized(lock) {
            cache.values.forEach { if (!it.isRecycled) it.recycle() }
            cache.clear()
            pending.clear()
        }
    }

    /** 不小于 [diameterPx] 的最小尺寸档；超过最大档就取最大档。 */
    fun tierFor(diameterPx: Int): Int {
        for (tier in TIERS) if (diameterPx <= tier) return tier
        return TIERS.last()
    }

    /**
     * 生成一张 [size]×[size] 的纹理。
     *
     * 每个像素：`f = 半径比`、`deg = 方位角`，取 [VinylSheenProfile.finalAlpha]
     * 与 [VinylSheenProfile.gray]，再按 `alpha << 24 | gray` 组装。
     * 盘外（f > 1）保持全透明；盘沿最后 1% 做线性羽化，消除圆边的锯齿台阶。
     */
    fun build(size: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(size * size)
        val center = (size - 1) / 2f
        val radius = size / 2f
        val invRadius = 1f / radius
        var index = 0
        for (y in 0 until size) {
            val dy = y - center
            for (x in 0 until size) {
                val dx = x - center
                val f = sqrt(dx * dx + dy * dy) * invRadius
                if (f > 1f) {
                    index++
                    continue
                }
                var degrees = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
                if (degrees < 0f) degrees += 360f
                var a = VinylSheenProfile.finalAlpha(f, degrees)
                if (f > EDGE_FADE_START) {
                    a *= ((1f - f) / (1f - EDGE_FADE_START)).coerceIn(0f, 1f)
                }
                val alpha = a.toInt()
                if (alpha > 0) {
                    val gray = VinylSheenProfile.gray(f).toInt().coerceIn(0, 255)
                    pixels[index] = (alpha shl 24) or (gray shl 16) or (gray shl 8) or gray
                }
                index++
            }
        }
        bitmap.setPixels(pixels, 0, size, 0, 0, size, size)
        return bitmap
    }

    /** 盘沿羽化起点（半径比）。最后 1% 从当前 alpha 线性降到 0。 */
    private const val EDGE_FADE_START = 0.99f

    /** 仅供测试/调试：把像素直接铺到给定 Bitmap 上（避免测试依赖真实 Bitmap 尺寸上限）。 */
    @Suppress("unused")
    fun debugColorAt(f: Float, degrees: Float): Int {
        val alpha = VinylSheenProfile.finalAlpha(f, degrees).toInt()
        val gray = VinylSheenProfile.gray(f).toInt().coerceIn(0, 255)
        return Color.argb(alpha, gray, gray, gray)
    }
}
