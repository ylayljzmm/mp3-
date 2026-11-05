package com.suisheng.player.theme

import androidx.appcompat.app.AppCompatDelegate
import com.suisheng.player.model.ThemeAccent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ThemeController] 纯函数与 [NightMode] / [ThemeAccent] 解析的单元测试。
 *
 * 这些逻辑决定「什么时候切深色」「播放页用什么色」，
 * 在车机上出错很难被用户描述清楚，所以全部用可枚举的边界值覆盖。
 */
class ThemeControllerTest {

    // ---------- normalizeMinute ----------

    @Test
    fun normalizeMinute_keepsInRangeValueUntouched() {
        assertEquals(0, ThemeController.normalizeMinute(0))
        assertEquals(390, ThemeController.normalizeMinute(390))
        assertEquals(1439, ThemeController.normalizeMinute(1439))
    }

    @Test
    fun normalizeMinute_wrapsOverOneDay() {
        // 24:30 -> 00:30
        assertEquals(30, ThemeController.normalizeMinute(24 * 60 + 30))
        assertEquals(60, ThemeController.normalizeMinute(1440 + 60))
    }

    @Test
    fun normalizeMinute_wrapsNegativeValue() {
        assertEquals(1439, ThemeController.normalizeMinute(-1))
        assertEquals(1380, ThemeController.normalizeMinute(-60))
    }

    // ---------- isNight ----------

    @Test
    fun isNight_crossMidnightWindow_defaultRange() {
        // 默认 18:30 -> 06:30
        val start = ThemeController.DEFAULT_NIGHT_START_MINUTE // 1110
        val end = ThemeController.DEFAULT_NIGHT_END_MINUTE     // 390

        assertFalse("18:29 应为白天", ThemeController.isNight(18 * 60 + 29, start, end))
        assertTrue("18:30 应开始夜间", ThemeController.isNight(18 * 60 + 30, start, end))
        assertTrue("23:59 应为夜间", ThemeController.isNight(23 * 60 + 59, start, end))
        assertTrue("00:00 应为夜间", ThemeController.isNight(0, start, end))
        assertTrue("06:29 仍为夜间", ThemeController.isNight(6 * 60 + 29, start, end))
        assertFalse("06:30 应回到白天", ThemeController.isNight(6 * 60 + 30, start, end))
        assertFalse("12:00 应为白天", ThemeController.isNight(12 * 60, start, end))
    }

    @Test
    fun isNight_sameDayWindow() {
        // 01:00 -> 05:00，不跨午夜
        val start = 60
        val end = 300
        assertFalse(ThemeController.isNight(59, start, end))
        assertTrue(ThemeController.isNight(60, start, end))
        assertTrue(ThemeController.isNight(299, start, end))
        assertFalse("结束时刻本身属于白天", ThemeController.isNight(300, start, end))
        assertFalse(ThemeController.isNight(23 * 60, start, end))
    }

    @Test
    fun isNight_emptyWindowIsAlwaysDay() {
        // start == end 视为「没有夜间区间」，不能把整天都判成夜间
        assertFalse(ThemeController.isNight(0, 600, 600))
        assertFalse(ThemeController.isNight(600, 600, 600))
        assertFalse(ThemeController.isNight(1439, 600, 600))
    }

    @Test
    fun isNight_toleratesOutOfRangeInput() {
        // 输入越界时应先归一化再判断
        // 次日 19:00 -> 归一化为 19:00，属夜间
        assertTrue(ThemeController.isNight(24 * 60 + 19 * 60, 1110, 390))
        // -60 -> 归一化为 23:00，同样属夜间
        assertTrue(ThemeController.isNight(-60, 1110, 390))
        // 次日 07:00 -> 归一化为 07:00，属白天
        assertFalse(ThemeController.isNight(24 * 60 + 7 * 60, 1110, 390))
    }

    // ---------- nightModeConstant ----------

    @Test
    fun nightModeConstant_mapsFixedModes() {
        assertEquals(
            AppCompatDelegate.MODE_NIGHT_NO,
            ThemeController.nightModeConstant(NightMode.LIGHT, isNightNow = true),
        )
        assertEquals(
            AppCompatDelegate.MODE_NIGHT_YES,
            ThemeController.nightModeConstant(NightMode.DARK, isNightNow = false),
        )
        assertEquals(
            AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM,
            ThemeController.nightModeConstant(NightMode.FOLLOW_SYSTEM, isNightNow = true),
        )
    }

    @Test
    fun nightModeConstant_timeAutoFollowsIsNightNow() {
        assertEquals(
            AppCompatDelegate.MODE_NIGHT_YES,
            ThemeController.nightModeConstant(NightMode.TIME_AUTO, isNightNow = true),
        )
        assertEquals(
            AppCompatDelegate.MODE_NIGHT_NO,
            ThemeController.nightModeConstant(NightMode.TIME_AUTO, isNightNow = false),
        )
    }

    // ---------- NightMode 持久化解析 ----------

    @Test
    fun nightMode_fromId_resolvesKnownIds() {
        assertEquals(NightMode.LIGHT, NightMode.fromId("light"))
        assertEquals(NightMode.DARK, NightMode.fromId("dark"))
        assertEquals(NightMode.FOLLOW_SYSTEM, NightMode.fromId("system"))
        assertEquals(NightMode.TIME_AUTO, NightMode.fromId("auto"))
    }

    @Test
    fun nightMode_fromId_fallsBackToDefault() {
        // 05 §5.2：出厂默认浅色
        assertEquals(NightMode.LIGHT, NightMode.DEFAULT)
        assertEquals(NightMode.LIGHT, NightMode.fromId(null))
        assertEquals(NightMode.LIGHT, NightMode.fromId(""))
        assertEquals(NightMode.LIGHT, NightMode.fromId("不存在的档位"))
    }

    @Test
    fun nightMode_idsAreUnique() {
        val ids = NightMode.entries.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        assertEquals(4, ids.size)
    }

    // ---------- ThemeAccent 持久化解析 ----------

    @Test
    fun themeAccent_hasTenUniqueIds() {
        val ids = ThemeAccent.entries.map { it.id }
        assertEquals(10, ids.size)
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun themeAccent_defaultIsAmber() {
        assertEquals(ThemeAccent.AMBER, ThemeAccent.DEFAULT)
        assertEquals("amber", ThemeAccent.DEFAULT.id)
    }

    @Test
    fun themeAccent_fromId_fallsBackToDefault() {
        assertEquals(ThemeAccent.OCEAN, ThemeAccent.fromId("ocean"))
        assertEquals(ThemeAccent.AMBER, ThemeAccent.fromId(null))
        assertEquals(ThemeAccent.AMBER, ThemeAccent.fromId(""))
        assertEquals(ThemeAccent.AMBER, ThemeAccent.fromId("zelda"))
    }

    @Test
    fun themeAccent_isKnownId_distinguishesLegacyIds() {
        assertTrue(ThemeAccent.isKnownId("amber"))
        assertTrue(ThemeAccent.isKnownId("graphite"))
        // 旧版三套配色 id 不属于新体系，导入备份时应被识别为非法
        assertFalse(ThemeAccent.isKnownId("netease"))
        assertFalse(ThemeAccent.isKnownId("zelda"))
        assertFalse(ThemeAccent.isKnownId("mario"))
        assertFalse(ThemeAccent.isKnownId(null))
    }

    @Test
    fun themeAccent_styleResAreUnique() {
        val styles = ThemeAccent.entries.map { it.styleRes }
        assertEquals(styles.size, styles.toSet().size)
        val playerStyles = ThemeAccent.entries.map { it.playerStyleRes }
        assertEquals(playerStyles.size, playerStyles.toSet().size)
        // 全局样式与播放页 overlay 不能混用
        assertTrue(styles.intersect(playerStyles.toSet()).isEmpty())
    }

    @Test
    fun themeAccent_displayNamesAreNonEmpty() {
        ThemeAccent.entries.forEach { accent ->
            assertTrue(
                "accent ${accent.id} 的显示名不能为空",
                accent.displayName.isNotBlank(),
            )
        }
        // id 与显示名一一对应，不能出现两套配色同名
        val names = ThemeAccent.entries.map { it.displayName }
        assertEquals(names.size, names.toSet().size)
    }

    // ---------- 播放页分控解析 ----------

    @Test
    fun resolvePlayerAccent_usesGlobalWhenSplitDisabled() {
        val r = ThemeController.resolvePlayerAccent(
            global = ThemeAccent.OCEAN,
            playerAccentId = "vermilion",
            splitEnabled = false,
        )
        assertEquals(ThemeAccent.OCEAN, r.accent)
        assertFalse(r.splitEffective)
    }

    @Test
    fun resolvePlayerAccent_usesPlayerWhenSplitEnabledAndValid() {
        val r = ThemeController.resolvePlayerAccent(
            global = ThemeAccent.OCEAN,
            playerAccentId = "vermilion",
            splitEnabled = true,
        )
        assertEquals(ThemeAccent.VERMILION, r.accent)
        assertTrue(r.splitEffective)
    }

    @Test
    fun resolvePlayerAccent_fallsBackSilentlyOnInvalidPlayerId() {
        // 04 §2.6 规则②：坏值不应让整页失色，且开关应被静默关闭
        listOf(null, "", "zelda", "不存在的主题").forEach { bad ->
            val r = ThemeController.resolvePlayerAccent(
                global = ThemeAccent.MOSS,
                playerAccentId = bad,
                splitEnabled = true,
            )
            assertEquals("playerAccentId=$bad 应回落全局", ThemeAccent.MOSS, r.accent)
            assertFalse("playerAccentId=$bad 时开关应失效", r.splitEffective)
        }
    }
}
