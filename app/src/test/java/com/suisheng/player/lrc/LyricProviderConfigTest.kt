package com.suisheng.player.lrc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 联网歌词渠道配置的回归测试（`lrc/LyricProviderConfig.kt`）。
 *
 * 锁四件事：
 *  ① 出厂状态 = 按 [LyricProviders.defaultOrder] 顺序、[LyricProviders.defaultDisabled] 开关；
 *  ② `parse()` 面对**上个版本**写进去的字符串要向前兼容 —— 新增渠道自动补到末尾，
 *    否则升级后老用户看不到新渠道，等于白加；
 *  ③ 不允许把渠道全关掉，否则「联网歌词」总开关会变成点了没反应；
 *  ④ `orderCsv()` / `disabledCsv()` 往返稳定 —— 这两个字符串是唯一落盘形态。
 */
class LyricProviderConfigTest {

    @Test
    fun defaultsFollowRegistry() {
        val config = LyricProviderConfig()
        assertEquals(LyricProviders.defaultOrder, config.order)
        assertEquals(LyricProviders.defaultDisabled, config.disabled)
        assertFalse(config.isEnabled(LyricProviders.LYRICS_OVH))
        assertTrue(config.isEnabled(LyricProviders.NETEASE))
        assertEquals(
            (LyricProviders.defaultOrder - LyricProviders.defaultDisabled).toSet(),
            config.enabledOrder().toSet(),
        )
    }

    @Test
    fun unsetKeysMeanFactoryConfig() {
        assertEquals(LyricProviderConfig(), LyricProviderConfig.parse(null, null))
    }

    @Test
    fun legacyOrderGetsNewProvidersAppended() {
        // 模拟 5.6.1 时代只认 netease/lrclib：新版多出来的渠道必须自动补上，
        // 且顺序严格等于出厂顺序，否则升级后老用户看不到新渠道、等于白加。
        val config = LyricProviderConfig.parse("lrclib,netease", null)
        assertEquals(
            listOf(
                LyricProviders.LRCLIB,
                LyricProviders.NETEASE,
                LyricProviders.KUGOU,
                LyricProviders.QQ,
                LyricProviders.KUWO,
                LyricProviders.TEXTYL,
                LyricProviders.LYRICS_OVH,
            ),
            config.order,
        )
        assertEquals(LyricProviders.defaultOrder.toSet(), config.order.toSet())
    }

    @Test
    fun unknownIdsDroppedAndDuplicatesRemoved() {
        val config = LyricProviderConfig.parse("qq,who_knows,qq,kugou", "nonexistent")
        assertEquals(
            listOf(
                LyricProviders.QQ,
                LyricProviders.KUGOU,
                LyricProviders.NETEASE,
                LyricProviders.LRCLIB,
                LyricProviders.KUWO,
                LyricProviders.TEXTYL,
                LyricProviders.LYRICS_OVH,
            ),
            config.order,
        )
        assertTrue(config.disabled.isEmpty())
    }

    @Test
    fun kuwoJoinedTheRegistry() {
        // 酷我是 5.8.0 新增渠道：必须在已知集合里，出厂即启用，且排在酷狗/QQ 之后。
        assertTrue(LyricProviders.isKnown(LyricProviders.KUWO))
        assertTrue(LyricProviderConfig().isEnabled(LyricProviders.KUWO))
        val index = LyricProviders.defaultOrder.indexOf(LyricProviders.KUWO)
        assertTrue(index > LyricProviders.defaultOrder.indexOf(LyricProviders.QQ))
        assertTrue(index < LyricProviders.defaultOrder.indexOf(LyricProviders.TEXTYL))
        assertTrue(LyricProviderCatalog.of(LyricProviders.KUWO) != null)
    }

    @Test
    fun enablingDefaultOffProviderSurvivesNullVsEmpty() {
        val opened = LyricProviderConfig().withEnabled(LyricProviders.LYRICS_OVH, true)
        assertTrue(opened.isEnabled(LyricProviders.LYRICS_OVH))
        // 空串 = 用户显式写过「一个都没关」，与「没写过（null）」必须区分开。
        assertEquals("", opened.disabledCsv())
        assertTrue(LyricProviderConfig.parse(opened.orderCsv(), opened.disabledCsv())
            .isEnabled(LyricProviders.LYRICS_OVH))
        // 而 null 仍回到出厂默认（关闭）。
        assertFalse(LyricProviderConfig.parse(opened.orderCsv(), null)
            .isEnabled(LyricProviders.LYRICS_OVH))
    }

    @Test
    fun lastEnabledProviderCannotBeDisabled() {
        // 只留 netease 一个开着
        var config = LyricProviderConfig().withEnabled(LyricProviders.LYRICS_OVH, true)
        for (id in LyricProviders.defaultOrder) {
            if (id != LyricProviders.NETEASE) config = config.withEnabled(id, false)
        }
        assertEquals(listOf(LyricProviders.NETEASE), config.enabledOrder())

        // 再关它就该被拒：否则「联网歌词」总开关会变成点了没反应。
        val blocked = config.withEnabled(LyricProviders.NETEASE, false)
        assertEquals(config, blocked)
        assertEquals(listOf(LyricProviders.NETEASE), blocked.enabledOrder())
    }

    @Test
    fun disablingTwiceIsIdempotent() {
        val once = LyricProviderConfig().withEnabled(LyricProviders.NETEASE, false)
        val twice = once.withEnabled(LyricProviders.NETEASE, false)
        assertEquals(once, twice)
        // 打开一个本来就开着的渠道同样幂等
        val enabledTwice = LyricProviderConfig()
            .withEnabled(LyricProviders.NETEASE, true)
            .withEnabled(LyricProviders.NETEASE, true)
        assertEquals(LyricProviderConfig(), enabledTwice)
    }

    @Test
    fun moveStopsAtBoundaries() {
        val config = LyricProviderConfig()
        val up = config.moved(LyricProviders.LRCLIB, up = true)
        assertEquals(
            listOf(LyricProviders.LRCLIB, LyricProviders.NETEASE),
            up.order.take(2),
        )
        // 已经在首位，再上移不动。
        assertEquals(up, up.moved(LyricProviders.LRCLIB, up = true))
        // 已在末位，下移不动。
        assertEquals(config, config.moved(LyricProviders.LYRICS_OVH, up = false))
        // 未知 id 不动。
        assertEquals(config, config.moved("who_knows", up = true))
    }

    @Test
    fun csvRoundTripIsStable() {
        val config = LyricProviderConfig()
            .moved(LyricProviders.QQ, up = true)
            .withEnabled(LyricProviders.KUGOU, false)
        val restored = LyricProviderConfig.parse(config.orderCsv(), config.disabledCsv())
        assertEquals(config, restored)
        assertEquals(config.orderCsv(), restored.orderCsv())
        assertEquals(config.disabledCsv(), restored.disabledCsv())
    }

    @Test
    fun resetRestoresFactory() {
        val config = LyricProviderConfig()
            .moved(LyricProviders.TEXTYL, up = true)
            .withEnabled(LyricProviders.NETEASE, false)
        assertEquals(LyricProviderConfig(), config.reset())
    }
}
