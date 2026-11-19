package com.suisheng.player.lrc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 联网歌词渠道配置的回归测试（`lrc/LyricProviderConfig.kt`）。
 *
 * 锁五件事：
 *  ① 出厂状态 = 按 [LyricProviders.defaultOrder] 顺序、[LyricProviders.defaultDisabled] 开关；
 *  ② `parse()` 面对**上个版本**写进去的字符串要向前兼容 —— 新增渠道自动补到末尾，
 *    否则升级后老用户看不到新渠道，等于白加；
 *  ③ 被删掉的渠道（酷我 / textyl / lyrics.ovh）出现在老字符串里时必须**安静丢弃**，
 *    不能因为读到不认识的 id 崩掉，也不能让它复活；
 *  ④ 不允许把渠道全关掉，否则「联网歌词」总开关会变成点了没反应；
 *  ⑤ `orderCsv()` / `disabledCsv()` 往返稳定 —— 这两个字符串是唯一落盘形态。
 */
class LyricProviderConfigTest {

    @Test
    fun defaultsFollowRegistry() {
        val config = LyricProviderConfig()
        assertEquals(LyricProviders.defaultOrder, config.order)
        assertEquals(LyricProviders.defaultDisabled, config.disabled)
        assertEquals(
            (LyricProviders.defaultOrder - LyricProviders.defaultDisabled).toSet(),
            config.enabledOrder().toSet(),
        )
        // 五个渠道全是真时间轴源，出厂不该有任何一个被关掉。
        assertTrue(LyricProviders.defaultDisabled.isEmpty())
        assertTrue(config.enabledOrder().containsAll(LyricProviders.defaultOrder))
        // 网易云必须排第一：逐字 / 译文只有它能给，取词器「第一个拿到有轴歌词的赢」，
        // 排后面就等于这两个能力永远用不上。
        assertEquals(LyricProviders.NETEASE, config.order.first())
    }

    @Test
    fun catalogAndRegistryDescribeTheSameSet() {
        // 能力表与注册表是两份手写列表，最容易在"加渠道只改一处"时错位。
        assertEquals(
            LyricProviders.defaultOrder.toSet(),
            LyricProviderCatalog.all().map { it.id }.toSet(),
        )
        assertEquals(
            emptySet<String>(),
            LyricProviderCatalog.all().filter { !it.defaultEnabled }.map { it.id }.toSet(),
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
                LyricProviders.MIGU,
            ),
            config.order,
        )
        assertEquals(LyricProviders.defaultOrder.toSet(), config.order.toSet())
    }

    @Test
    fun unknownIdsDroppedAndDuplicatesRemoved() {
        // `kuwo` 是 5.9.0 删掉的渠道：老用户存下的顺序里可能还有它，必须安静丢弃。
        val config = LyricProviderConfig.parse("qq,who_knows,qq,kuwo,kugou", "nonexistent")
        assertEquals(
            listOf(
                LyricProviders.QQ,
                LyricProviders.KUGOU,
                LyricProviders.NETEASE,
                LyricProviders.MIGU,
                LyricProviders.LRCLIB,
            ),
            config.order,
        )
        assertTrue(config.disabled.isEmpty())
    }

    @Test
    fun removedProvidersAreGoneForGood() {
        // 三个被删渠道：不是"默认关闭"，而是从已知集合里彻底消失。
        for (id in listOf("kuwo", "textyl", "lyrics_ovh")) {
            assertFalse("$id 应该已经从注册表删除", LyricProviders.isKnown(id))
            assertEquals(null, LyricProviderCatalog.of(id))
        }
        // 老数据里三个都在、且 lyrics_ovh 被写过"默认关闭"，升级后不能留下任何痕迹。
        val config = LyricProviderConfig.parse(
            "netease,lrclib,kugou,qq,kuwo,textyl,lyrics_ovh",
            "lyrics_ovh",
        )
        assertEquals(LyricProviders.defaultOrder.toSet(), config.order.toSet())
        assertTrue(config.disabled.isEmpty())
    }

    @Test
    fun miguJoinedTheRegistry() {
        // 咪咕是 5.9.0 新增渠道：必须在已知集合里、出厂即启用，且排在 QQ 之后、
        // LRCLIB 之前 —— 它给中文译词，但英文歌常只有纯文本，不值得排在 QQ 前面。
        assertTrue(LyricProviders.isKnown(LyricProviders.MIGU))
        assertTrue(LyricProviderConfig().isEnabled(LyricProviders.MIGU))
        val index = LyricProviders.defaultOrder.indexOf(LyricProviders.MIGU)
        assertTrue(index > LyricProviders.defaultOrder.indexOf(LyricProviders.QQ))
        assertTrue(index < LyricProviders.defaultOrder.indexOf(LyricProviders.LRCLIB))
        assertTrue(LyricProviderCatalog.of(LyricProviders.MIGU) != null)
    }

    @Test
    fun qqNoLongerAdvertisesTranslation() {
        // QQ 的 `trans` 字段在 18 首基准里全是空串，译文能力标了就是骗用户。
        val abilities = LyricProviderCatalog.of(LyricProviders.QQ)?.abilities.orEmpty()
        assertFalse(LyricAbility.TRANSLATION in abilities)
        assertTrue(LyricAbility.TIMELINE in abilities)
    }

    @Test
    fun nullDisabledFallsBackToFactoryAndEmptyMeansNoneDisabled() {
        // 出厂没有默认关闭的渠道，所以此刻 null（没写过）与 ""（写过但一个都没关）等价。
        assertTrue(LyricProviders.defaultDisabled.isEmpty())
        assertTrue(LyricProviderConfig.parse(null, null).disabled.isEmpty())
        assertTrue(LyricProviderConfig.parse(null, "").disabled.isEmpty())

        // 但用户显式关掉的渠道必须原样还原，不能被出厂默认抹掉。
        val custom = LyricProviderConfig().withEnabled(LyricProviders.MIGU, false)
        assertEquals(setOf(LyricProviders.MIGU), custom.disabled)
        val restored = LyricProviderConfig.parse(custom.orderCsv(), custom.disabledCsv())
        assertEquals(custom, restored)
        assertEquals(custom.disabled, restored.disabled)
    }

    @Test
    fun lastEnabledProviderCannotBeDisabled() {
        // 只留 netease 一个开着
        var config = LyricProviderConfig()
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
        val up = config.moved(LyricProviders.KUGOU, up = true)
        assertEquals(
            listOf(LyricProviders.KUGOU, LyricProviders.NETEASE),
            up.order.take(2),
        )
        // 已经在首位，再上移不动。
        assertEquals(up, up.moved(LyricProviders.KUGOU, up = true))
        // 已在末位，下移不动。
        assertEquals(config, config.moved(LyricProviders.LRCLIB, up = false))
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
            .moved(LyricProviders.MIGU, up = true)
            .withEnabled(LyricProviders.NETEASE, false)
        assertEquals(LyricProviderConfig(), config.reset())
    }
}
