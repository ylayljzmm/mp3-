package com.suisheng.player.ui

import kotlin.math.cos

/**
 * 黑胶沟槽光照剖面（纯数据，无 Android 依赖，便于 JVM 单测）。
 *
 * ## 这张表是从哪来的
 *
 * 对 QQ 音乐的 `player_vinyl_disc_lighting.png`（735×735 RGBA）做 180 角度平均后，
 * 同时取 **RGB 与 alpha** 得到。关键事实：**两条 alpha≈255 的环，RGB 是 (0,0,0)**，
 * 也就是不透明的**纯黑分隔环**，不是白亮圈。
 *
 * 早期版本的方案文档把 alpha 通道当成了亮度，写成「两条 alpha=255 的纯白亮环」，
 * 那是错的。用 alpha 判断「亮」只有在 RGB 恒定接近白色时成立，这张图不满足。
 *
 * 正确的视觉逻辑是三层：
 *  ① 内圈 f<0.50：沟槽是**白色**（RGB 209–245）但 alpha 很低（9–42）——
 *     半透明白线叠在纯黑盘面上 → 细灰纹；
 *  ② f=0.55 / f=0.63：**不透明纯黑环**，作用是在视觉上把内圈和外圈**切开**；
 *  ③ f>0.64：沟槽变成**深灰**（RGB 14–37）但 alpha 很高（66–216）——
 *     读起来是「被斜光扫过的深色沟槽」，比内圈的细白纹更实更沉。
 *
 * ## 三个调制
 *
 * 1. **径向剖面**（[PLATE]）：`(f, gray, alpha)` 三元组，按 f 线性插值。
 * 2. **角度调制**（[angularGain]）：亮部固定在 190°（实测 180°–210° 最亮），
 *    幅度随半径变大（f=0.30 时 ±3 倍，f=0.85 时 ±1.7 倍）。这是「斜光」的来源。
 * 3. **高频条纹**（[stripeGain]）：f>0.64 之后叠加 `0.86 + 0.14·cos(2πf·110)`，
 *    让外圈沟槽密集起来，对应实测的 f=0.86 alpha 156 / f=0.94 alpha 87 起伏。
 *
 * ## 为什么环不参与调制
 *
 * [finalAlpha] 对 base alpha ≥ 250 的采样点直接返回 255：纯黑环必须整圈都是
 * 不透明黑，否则在暗侧会被角度调制压到 218，露出一截沟槽，环就断了。
 */
object VinylSheenProfile {

    /**
     * 径向剖面 `(f, gray, alpha)`；f 从盘心 0.00 到盘沿 1.00。
     *
     * gray 是灰度值（R=G=B），alpha 是半透明度。两条黑环的 alpha 分别是 255 / 254。
     */
    private val PLATE: Array<FloatArray> = arrayOf(
        floatArrayOf(0.00f, 245f, 9f),
        floatArrayOf(0.06f, 245f, 10f),
        floatArrayOf(0.10f, 245f, 12f),
        floatArrayOf(0.16f, 245f, 22f),
        floatArrayOf(0.22f, 245f, 31f),
        floatArrayOf(0.30f, 243f, 42f),
        floatArrayOf(0.36f, 236f, 41f),
        floatArrayOf(0.42f, 218f, 33f),
        floatArrayOf(0.46f, 200f, 27f),
        floatArrayOf(0.50f, 120f, 38f),
        // 封面外侧的压边：不算白，但也不是黑，106 这个 alpha 是用来压住封面锯齿的
        floatArrayOf(0.52f, 18f, 106f),
        // ① 不透明纯黑分隔环
        floatArrayOf(0.545f, 10f, 190f),
        floatArrayOf(0.55f, 0f, 255f),
        floatArrayOf(0.558f, 8f, 170f),
        floatArrayOf(0.60f, 25f, 78f),
        // ② 不透明纯黑分隔环
        floatArrayOf(0.628f, 6f, 205f),
        floatArrayOf(0.63f, 0f, 254f),
        floatArrayOf(0.638f, 10f, 180f),
        floatArrayOf(0.66f, 30f, 66f),
        floatArrayOf(0.70f, 37f, 71f),
        floatArrayOf(0.74f, 30f, 80f),
        floatArrayOf(0.78f, 25f, 95f),
        floatArrayOf(0.82f, 22f, 120f),
        floatArrayOf(0.86f, 19f, 156f),
        floatArrayOf(0.90f, 14f, 119f),
        floatArrayOf(0.94f, 28f, 87f),
        floatArrayOf(0.97f, 30f, 40f),
        floatArrayOf(1.00f, 20f, 9f),
    )

    /** 亮部方位角（度，从 +x 顺时针）。实测 180°–210° 最亮，取中值。 */
    const val LOBE_DEGREES = 190f

    /** 角度调制的起始半径：内圈几乎不调制，往盘沿逐渐变强。 */
    private const val AMP_START_F = 0.45f

    /** 角度调制的跨度。 */
    private const val AMP_SPAN_F = 0.40f

    /** base alpha 达到这个值就认定是黑环，整圈不参与调制。 */
    private const val RING_ALPHA = 250f

    /** 高频条纹只作用在外圈（内圈沟槽是稀疏的白细纹）。 */
    private const val STRIPE_START_F = 0.64f

    /** 外圈条纹圈数：实测大约每 1% 半径一条，110 周期的观感最接近。 */
    private const val STRIPE_FREQUENCY = 110f

    /** 径向灰度（线性插值）。 */
    fun gray(f: Float): Float = sample(f, 1)

    /** 径向 alpha（**未**做角度调制与条纹，即剖面表里的原值）。 */
    fun alpha(f: Float): Float = sample(f, 2)

    /**
     * 角度调制幅度：`0.10 + 0.42 · clamp((f−0.45)/0.40)`。
     *
     * f≤0.45 时是 0.10（几乎不调制），f≥0.85 时是 0.52（强弱比约 3:1）。
     */
    fun angularAmp(f: Float): Float {
        val t = ((f - AMP_START_F) / AMP_SPAN_F).coerceIn(0f, 1f)
        return 0.10f + 0.42f * t
    }

    /**
     * 角度增益：`1 + amp·(cos(Δ) + 0.30·cos(2Δ))`，Δ 是相对 [LOBE_DEGREES] 的夹角。
     *
     * 二次谐波让亮部的衰减比纯 cos 更「聚」，与实测的 max/avg 比值吻合。
     */
    fun angularGain(f: Float, degrees: Float): Float {
        val delta = Math.toRadians((degrees - LOBE_DEGREES).toDouble())
        val amp = angularAmp(f).toDouble()
        val gain = 1.0 + amp * (cos(delta) + 0.30 * cos(2.0 * delta))
        return gain.toFloat().coerceAtLeast(0f)
    }

    /** 外圈高频条纹增益（f≤0.64 恒为 1，不改变内圈）。 */
    fun stripeGain(f: Float): Float {
        if (f <= STRIPE_START_F) return 1f
        return 0.86f + 0.14f * cos((2.0 * Math.PI * f * STRIPE_FREQUENCY).toDouble()).toFloat()
    }

    /**
     * 最终像素 alpha：`baseAlpha · 角度增益 · 条纹增益`，钳到 0–255。
     *
     * 黑环（base alpha ≥ 250）**直接返回 255**，不参与任何调制 —— 见类文档最后一段。
     */
    fun finalAlpha(f: Float, degrees: Float): Float {
        val base = alpha(f)
        if (base >= RING_ALPHA) return 255f
        return (base * angularGain(f, degrees) * stripeGain(f)).coerceIn(0f, 255f)
    }

    // ------------------------------------------------------------------ 内部

    private fun sample(f: Float, column: Int): Float {
        val clamped = f.coerceIn(0f, 1f)
        val first = PLATE.first()
        if (clamped <= first[0]) return first[column]
        for (i in 1 until PLATE.size) {
            val hi = PLATE[i]
            if (clamped > hi[0]) continue
            val lo = PLATE[i - 1]
            val span = hi[0] - lo[0]
            if (span <= 0f) return hi[column]
            val t = (clamped - lo[0]) / span
            return lo[column] + (hi[column] - lo[column]) * t
        }
        return PLATE.last()[column]
    }
}
