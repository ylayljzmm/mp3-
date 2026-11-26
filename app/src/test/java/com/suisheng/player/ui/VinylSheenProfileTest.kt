package com.suisheng.player.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 黑胶沟槽光照剖面（5.10.0）的回归测试。
 *
 * 这些断言守的不是「数字好看」，而是三个**从 QQ 素材实测出来、改错就会露馅**的事实：
 *
 * 1. **两条黑环必须整圈不透明**：`f=0.55` / `f=0.63` 的 base alpha 是 255 / 254，
 *    对应 QQ `player_vinyl_disc_lighting.png` 里那两条 RGB=(0,0,0) 的不透明环。
 *    如果 `finalAlpha` 让它们参与角度调制，暗侧会被压到 218，环上就会出现一截缺口。
 *    这是本项目最容易写错的地方（早期方案文档把 alpha 当亮度，直接写成「白色亮环」）。
 *
 * 2. **环本身是黑的**：`gray(0.55)` 必须是 0。曾经把这里写成 245（白），
 *    结果是盘面上出现两道刺眼的白圈 —— 与 QQ 的观感完全相反。
 *
 * 3. **调制方向正确**：`angularGain` 必须在 [VinylSheenProfile.LOBE_DEGREES] 取最大值，
 *    在正对面取最小值；`stripeGain` 只能作用于外圈（f>0.64），内圈恒为 1。
 */
class VinylSheenProfileTest {

    // ------------------------------------------------------------ ① 两条黑环

    @Test
    fun blackRingAt055IsOpaqueAtEveryAngle() {
        // 整圈扫一遍：任何角度都必须是 255，不能因为暗侧调制而变半透明
        for (deg in 0 until 360 step 5) {
            assertEquals(
                "f=0.55 在 $deg° 处必须完全不透明",
                255f,
                VinylSheenProfile.finalAlpha(0.55f, deg.toFloat()),
                0f,
            )
        }
    }

    @Test
    fun blackRingAt063IsOpaqueAtEveryAngle() {
        for (deg in 0 until 360 step 5) {
            assertEquals(
                "f=0.63 在 $deg° 处必须完全不透明",
                255f,
                VinylSheenProfile.finalAlpha(0.63f, deg.toFloat()),
                0f,
            )
        }
    }

    @Test
    fun blackRingsAreActuallyBlackNotWhite() {
        // 环的灰度必须是 0（纯黑）；写成 245 会在盘上画出两道白圈
        assertEquals("f=0.55 是纯黑环", 0f, VinylSheenProfile.gray(0.55f), 1f)
        assertEquals("f=0.63 是纯黑环", 0f, VinylSheenProfile.gray(0.63f), 1f)
        // 环两侧的沟槽必须比环亮，否则环就"消失"了
        assertTrue(
            "f=0.30 的沟槽灰度必须远高于黑环",
            VinylSheenProfile.gray(0.30f) > VinylSheenProfile.gray(0.55f) + 100f,
        )
    }

    // ------------------------------------------------------------ ② 内圈白细纹

    @Test
    fun innerGrooveIsWhiteButFaint() {
        // 内圈（f=0.30）是「白色细纹 + 低 alpha」：灰度接近白，alpha 只有三四十
        assertTrue(
            "内圈沟槽灰度应 ≥240，实际 ${VinylSheenProfile.gray(0.30f)}",
            VinylSheenProfile.gray(0.30f) >= 240f,
        )
        val a = VinylSheenProfile.alpha(0.30f)
        assertTrue("内圈 alpha 应落在 35–48，实际 $a", a in 35f..48f)
    }

    @Test
    fun coverEdgeIsDarkButOpaqueEnoughToHideAliasing() {
        // f=0.52 是封面外沿的压边：不白（灰度低），但 alpha 要有 106 左右，否则盖不住封面锯齿
        assertTrue("封面压边灰度应 <40", VinylSheenProfile.gray(0.52f) < 40f)
        val a = VinylSheenProfile.alpha(0.52f)
        assertTrue("封面压边 alpha 应在 90–120，实际 $a", a in 90f..120f)
    }

    // ------------------------------------------------------------ ③ 盘沿淡出

    @Test
    fun outerRimFadesOut() {
        // 盘沿必须收干净，否则圆盘边缘会出现一圈硬切线（5.9 踩过的坑）。
        // 注意取样点用 0.98 而不是 0.96：剖面表在 0.94→0.97 之间还有 87→40 的过渡，
        // 0.96 插值出来约 56，是外圈沟槽的正常密度，不该被当成"没淡出"。
        assertTrue(
            "f=0.98 的 alpha 应 <40，实际 ${VinylSheenProfile.alpha(0.98f)}",
            VinylSheenProfile.alpha(0.98f) < 40f,
        )
        assertTrue(
            "f=1.00 的 alpha 应 <15，实际 ${VinylSheenProfile.alpha(1.00f)}",
            VinylSheenProfile.alpha(1.00f) < 15f,
        )
    }

    @Test
    fun outerGrooveIsDenseAndStrong() {
        // 外圈（f=0.86）是「深灰 + 高 alpha」：比内圈更实更沉
        assertTrue("外圈灰度应 <40", VinylSheenProfile.gray(0.86f) < 40f)
        assertTrue(
            "外圈 alpha 应 >120，实际 ${VinylSheenProfile.alpha(0.86f)}",
            VinylSheenProfile.alpha(0.86f) > 120f,
        )
    }

    // ------------------------------------------------------------ ④ 角度调制

    @Test
    fun angularGainPeaksAtLobeAngle() {
        val lobe = VinylSheenProfile.LOBE_DEGREES
        val atLobe = VinylSheenProfile.angularGain(0.85f, lobe)
        val opposite = VinylSheenProfile.angularGain(0.85f, lobe + 180f)
        assertTrue("亮部增益应 >1，实际 $atLobe", atLobe > 1f)
        assertTrue("对侧增益应 <1，实际 $opposite", opposite < 1f)
        assertTrue("亮部必须明显亮于对侧（$atLobe vs $opposite）", atLobe > opposite * 2f)
    }

    @Test
    fun angularGainIsMaximalAtLobeAcrossAllAngles() {
        val lobe = VinylSheenProfile.LOBE_DEGREES
        val peak = VinylSheenProfile.angularGain(0.85f, lobe)
        for (deg in 0 until 360) {
            val g = VinylSheenProfile.angularGain(0.85f, deg.toFloat())
            assertTrue(
                "$deg° 的增益 $g 不应超过亮部 $peak",
                g <= peak + 1e-3f,
            )
        }
    }

    @Test
    fun angularAmpGrowsWithRadius() {
        // 内圈几乎不调制（0.10），外圈调制明显（0.52）—— 这是"斜光扫过"的观感来源
        assertEquals("f=0.20 时幅度应为下限", 0.10f, VinylSheenProfile.angularAmp(0.20f), 1e-4f)
        assertEquals("f=0.95 时幅度应为上限", 0.52f, VinylSheenProfile.angularAmp(0.95f), 1e-4f)
        assertTrue(
            "外圈幅度必须大于内圈",
            VinylSheenProfile.angularAmp(0.85f) > VinylSheenProfile.angularAmp(0.50f),
        )
    }

    // ------------------------------------------------------------ ⑤ 高频条纹

    @Test
    fun stripeGainIsFlatInsideGrooveBand() {
        // 内圈沟槽是稀疏白细纹，不能叠高频条纹
        assertEquals(1f, VinylSheenProfile.stripeGain(0.00f), 0f)
        assertEquals(1f, VinylSheenProfile.stripeGain(0.40f), 0f)
        assertEquals(1f, VinylSheenProfile.stripeGain(0.64f), 0f)
    }

    @Test
    fun stripeGainOscillatesOnlyOutside() {
        // 外圈条纹必须真的在起伏（不是常数），且幅度落在 0.86±0.14 内
        val samples = (70..95).map { VinylSheenProfile.stripeGain(it / 100f) }
        assertTrue("外圈条纹应有起伏", samples.max() - samples.min() > 0.05f)
        for (v in samples) {
            assertTrue("条纹增益 $v 必须落在 0.72–1.00", v in 0.72f..1.001f)
        }
    }

    // ------------------------------------------------------------ ⑥ 边界与钳位

    @Test
    fun alphaNeverExceedsByteRange() {
        for (i in 0..100) {
            val f = i / 100f
            for (deg in 0 until 360 step 15) {
                val a = VinylSheenProfile.finalAlpha(f, deg.toFloat())
                assertTrue("f=$f deg=$deg 的 alpha $a 越界", a in 0f..255f)
            }
        }
    }

    @Test
    fun outOfRangeRadiusIsClamped() {
        // 采样函数必须对 f 做钳位，否则传负值会读到表头之外
        assertEquals(VinylSheenProfile.gray(0f), VinylSheenProfile.gray(-1f), 0f)
        assertEquals(VinylSheenProfile.gray(1f), VinylSheenProfile.gray(2f), 0f)
    }
}
