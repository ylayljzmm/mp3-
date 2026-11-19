package com.suisheng.player.lrc

/**
 * 联网歌词渠道的注册表。
 *
 * ## 为什么把 id 抽出来
 *
 * 渠道 id 同时出现在三个地方：`OnlineLyricFetcher` 的分发、`SettingsRepository` 里
 * 落盘的开关字符串、设置页的开关列表。三处各自写字符串字面量，改一个忘一个就会出现
 * 「设置里能关，实际还在跑」这种静默错误。所以 id 只在这里定义一次。
 *
 * ## 默认顺序的排法
 *
 * 排序依据是「命中率」× 「能不能拿到时间轴」× 「能不能顺带拿到译文/逐字」，全部用同一批
 * 18 首歌实测过（详见 [OnlineLyricFetcher] 的类注释）：
 *
 * 1. [NETEASE] —— 时间轴 + 译文（`tlyric`）+ 注音（`romalrc`）+ 逐字（`yrc`）四轨最全，
 *    命中率也最高（18/18）。**必须排第一**：取词器是「第一个拿到有轴歌词的渠道赢」，
 *    把别的源放前面会让逐字/译文这两个只有网易云能给的能力永远用不上；
 * 2. [KUGOU] —— 延迟最低（中位 162~261ms），纯时间轴，是网易云抽风时的第一顺位替补；
 * 3. [QQ] —— 稳定 18/18，纯时间轴（`trans` 字段实测恒为空，别指望译文）；
 * 4. [MIGU] —— 一次搜索直接给歌词地址，带中文译词轨（`trcUrl`），但对英文歌常只给纯文本；
 * 5. [LRCLIB] —— 元数据最干净、无风控，但中文曲库返回**繁体**，且偶尔十几秒不返回，
 *    所以排在最后并配了更短的超时。
 *
 * ## 为什么删掉了酷我 / textyl / lyrics.ovh
 *
 * - 酷我：搜索结果前排全是「(KTV版伴奏)」「(DJ版)」，`lrclist` 基本为空 —— 不是慢，是取不到词；
 * - textyl：`api.textyl.co` 的 TLS 证书已过期，现在 100% 连接失败；
 * - lyrics.ovh：近八成请求 404，剩下的只有纯文本、没有时间轴。
 *
 * 三个源留着只会拖慢每一次取词，且永远排不上用场。
 */
object LyricProviders {

    const val NETEASE = "netease"
    const val KUGOU = "kugou"
    const val QQ = "qq"
    const val MIGU = "migu"
    const val LRCLIB = "lrclib"

    /** 出厂顺序，也是「已知渠道全集」。 */
    val defaultOrder: List<String> =
        listOf(NETEASE, KUGOU, QQ, MIGU, LRCLIB)

    /**
     * 默认关闭的渠道。
     *
     * 目前**一个都没有**：五个源都能给出真实时间轴，没有「打开后歌词按 5 秒占位轴乱走」
     * 的纯文本源了。保留这个集合是因为 `parse()` 的向前兼容要靠它区分
     * 「没写过开关」与「写过但一个都没关」。
     */
    val defaultDisabled: Set<String> = emptySet()

    val all: List<String> get() = defaultOrder

    fun isKnown(id: String): Boolean = id in defaultOrder
}

/**
 * 用户对联网歌词渠道的偏好：**顺序** + **开关**。
 *
 * 不可变值类型，落盘只存两个逗号分隔的字符串（[orderCsv] / [disabledCsv]），
 * 这样设置页、`SettingsRepository`、`OnlineLyricFetcher` 之间传的是同一份东西，
 * 不需要各自解析。
 *
 * ## 解析必须向前兼容
 *
 * `parse()` 面对的可能是**上个版本写进去的字符串**：5.9.0 之前还注册着酷我、textyl、
 * lyrics.ovh 三个渠道，用户存的顺序里可能就有这三个 id。
 * 所以规则是「已知 id 按存下来的顺序排 → 缺的已知 id 按出厂顺序补到末尾 → 未知 id 直接丢」。
 * 这样版本升级新增渠道时，老用户不用手动打开新渠道也不会看不到它，降级回老版本也不会
 * 因为读到不认识的 id 而崩。删掉的渠道同理：老字符串里的 `kuwo` 会被当作未知 id 丢弃，
 * 用户不需要清数据。
 */
data class LyricProviderConfig(
    val order: List<String> = LyricProviders.defaultOrder,
    val disabled: Set<String> = LyricProviders.defaultDisabled,
) {

    /**
     * 实际要依次尝试的渠道。
     *
     * 空列表意味着「用户把所有渠道都关了」——调用方据此直接判定本次联网取词失败，
     * 不要悄悄回退成出厂顺序，否则用户关掉的渠道又会跑起来。
     */
    fun enabledOrder(): List<String> = order.filter { it !in disabled }

    /**
     * 开关某个渠道。
     *
     * **不允许关掉最后一个开着的渠道**：`enabledOrder()` 变空后「联网歌词」这个总开关
     * 就变成了「点了没反应」，用户很难自己诊断出是渠道全关了。所以这里直接忽略这次操作，
     * UI 层要负责把开关弹回去。
     */
    fun withEnabled(id: String, enabled: Boolean): LyricProviderConfig {
        if (!LyricProviders.isKnown(id)) return this
        if (enabled) {
            if (id !in disabled) return this
            return copy(disabled = disabled - id)
        }
        if (id in disabled) return this
        val next = disabled + id
        return if (order.count { it !in next } == 0) this else copy(disabled = next)
    }

    fun isEnabled(id: String): Boolean = id !in disabled

    /** 把某个渠道上移/下移一位；越界或未知 id 时原样返回。 */
    fun moved(id: String, up: Boolean): LyricProviderConfig {
        val index = order.indexOf(id)
        if (index < 0) return this
        val target = if (up) index - 1 else index + 1
        if (target !in order.indices) return this
        val next = order.toMutableList()
        next[index] = order[target]
        next[target] = id
        return copy(order = next.toList())
    }

    /** 恢复出厂顺序与出厂开关。 */
    fun reset(): LyricProviderConfig = LyricProviderConfig()

    fun orderCsv(): String = order.joinToString(SEPARATOR)

    fun disabledCsv(): String = disabled.joinToString(SEPARATOR)

    companion object {
        const val SEPARATOR = ","

        /**
         * 从落盘字符串还原。
         *
         * 两个参数都允许 `null`（首次启动没写过键、或用户清过数据）。
         */
        fun parse(orderCsv: String?, disabledCsv: String?): LyricProviderConfig {
            val stored = orderCsv.toIdList()
            val order = buildList {
                stored.forEach { if (LyricProviders.isKnown(it) && it !in this) add(it) }
                LyricProviders.defaultOrder.forEach { if (it !in this) add(it) }
            }
            val disabled = if (disabledCsv == null) {
                LyricProviders.defaultDisabled
            } else {
                // 用户显式写过开关：以写成的时间点为准，不再套用「默认关闭」。
                disabledCsv.toIdList().filter { it in order }.toSet()
            }
            return LyricProviderConfig(order, disabled)
        }

        private fun String?.toIdList(): List<String> =
            this?.split(SEPARATOR)
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                .orEmpty()
    }
}
