package com.suisheng.player.ui

import android.graphics.Bitmap
import android.graphics.Color

/**
 * 从封面里抽一个「不会太艳、也不会太暗」的主色，给黑胶的环境光用。
 *
 * ## 对应 QQ 的什么
 *
 * QQ 音乐的光效有一套 `magicColor` 机制：dex 里能看到
 * `[tryUpdateLightEffect] loadMagicColor with bitmap, hashCode=`、
 * `generateMagicColorNotNull baseMagicColor=`、`highLightColor=`、
 * `getAdjustBitmapMagicColor for LightEffectGradient` 等日志。
 * 它就是「从封面 bitmap 抽色 → 生成 base 与 highlight 两个色 → 喂给光效层」。
 * 这里做的是同一件事的简化版：只抽**一个**色，交给 [VinylGlowView.setAccentColor]。
 *
 * ## 为什么要限制饱和度和亮度
 *
 * 直接取封面平均色，浅色封面的封面会给出接近白色的结果（光晕变成白雾，糊住沟槽），
 * 高饱和封面（比如纯红专辑）会给出荧光红（衬在暗背景上像漏光）。
 * 所以先缩到 24×24 求中位色，再按下面的区间收：
 * - **饱和度 `0.15–0.45`**：低于 0.15 就是灰（没有主色可言），高于 0.45 太扎眼；
 * - **明度 `0.25–0.60`**：低于 0.25 抽出来是黑（贴背景上看不见），高于 0.60 太亮。
 *
 * 区间外的值不丢弃，而是**夹到边界**——保留色相，只压饱和度和明度，
 * 这样每张封面都能给一个可用的色，不会出现「这张抽不出色就退默认灰」的断层。
 *
 * ## 为什么是 24×24
 *
 * 一张 1024×1024 的封面逐像素统计要几毫秒；缩到 24×24 = 576 像素，
 * 在 `Bitmap.createScaledBitmap` 之后几十微秒就能跑完。取中位色（而不是平均色）
 * 是为了避开封面里的大面积黑边/白边把结果拉偏。
 */
object CoverAccentExtractor {

    /** 缩略图边长。 */
    private const val THUMB = 24

    /** 饱和度允许区间。 */
    private const val MIN_SATURATION = 0.15f
    private const val MAX_SATURATION = 0.45f

    /** 明度允许区间。 */
    private const val MIN_VALUE = 0.25f
    private const val MAX_VALUE = 0.60f

    /** 抽不出色时的兜底色（暖灰，与主题的 secondary 接近）。 */
    const val FALLBACK = VinylGlowView.DEFAULT_ACCENT

    /**
     * 抽主色。
     *
     * 入参为 null 或已回收时返回 [FALLBACK]；任何异常同样退回兜底色，
     * 这个方法不允许把异常抛给调用方（它跑在封面解码的同一条路径上）。
     */
    fun extract(bitmap: Bitmap?): Int {
        if (bitmap == null || bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) {
            return FALLBACK
        }
        return runCatching { extractOrThrow(bitmap) }.getOrDefault(FALLBACK)
    }

    private fun extractOrThrow(bitmap: Bitmap): Int {
        val thumb = if (bitmap.width <= THUMB && bitmap.height <= THUMB) {
            bitmap
        } else {
            Bitmap.createScaledBitmap(bitmap, THUMB, THUMB, true)
        }
        try {
            // 只统计「不透明且不太黑/不太白」的像素：封面常见的大黑边/纯白留白不参与投票
            val hues = ArrayList<Float>(THUMB * THUMB)
            val sats = ArrayList<Float>(THUMB * THUMB)
            val vals = ArrayList<Float>(THUMB * THUMB)
            val hsv = FloatArray(3)
            for (y in 0 until thumb.height) {
                for (x in 0 until thumb.width) {
                    val pixel = thumb.getPixel(x, y)
                    if (Color.alpha(pixel) < ALPHA_FLOOR) continue
                    Color.colorToHSV(pixel, hsv)
                    if (hsv[2] < 0.06f || hsv[2] > 0.97f) continue
                    hues.add(hsv[0])
                    sats.add(hsv[1])
                    vals.add(hsv[2])
                }
            }
            val count = hues.size
            if (count < MIN_SAMPLES) return FALLBACK
            hues.sort()
            sats.sort()
            vals.sort()
            val hue = hues[count / 2]
            val sat = sats[count / 2].coerceIn(MIN_SATURATION, MAX_SATURATION)
            val value = vals[count / 2].coerceIn(MIN_VALUE, MAX_VALUE)
            return Color.HSVToColor(floatArrayOf(hue, sat, value))
        } finally {
            if (thumb !== bitmap && !thumb.isRecycled) thumb.recycle()
        }
    }

    /** 太透明的像素不参与投票（`0x30` ≈ 19%）。 */
    private const val ALPHA_FLOOR = 0x30

    /** 有效像素少于此数就用兜底色（避免少数几个像素决定整盘颜色）。 */
    private const val MIN_SAMPLES = 24

    /** 供测试与调试：按给定色相/饱和/明度直接算。 */
    @Suppress("unused")
    fun fromHsv(hue: Float, saturation: Float, value: Float): Int = Color.HSVToColor(
        floatArrayOf(
            ((hue % 360f) + 360f) % 360f,
            saturation.coerceIn(MIN_SATURATION, MAX_SATURATION),
            value.coerceIn(MIN_VALUE, MAX_VALUE),
        ),
    )

    /** 供测试与调试：夹取规则单独暴露。 */
    @Suppress("unused")
    fun clampSaturation(value: Float): Float = value.coerceIn(MIN_SATURATION, MAX_SATURATION)

    /** 供测试与调试：夹取规则单独暴露。 */
    @Suppress("unused")
    fun clampValue(value: Float): Float = value.coerceIn(MIN_VALUE, MAX_VALUE)

    /** 让算法里的两个区间可以被测试读到。 */
    @Suppress("unused")
    val rangeSaturation: ClosedFloatingPointRange<Float> get() = MIN_SATURATION..MAX_SATURATION

    /** 让算法里的明度区间可以被测试读到。 */
    @Suppress("unused")
    val rangeValue: ClosedFloatingPointRange<Float> get() = MIN_VALUE..MAX_VALUE
}
