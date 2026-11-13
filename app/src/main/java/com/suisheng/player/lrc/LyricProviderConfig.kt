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
 * 排序依据是「能不能拿到时间轴」× 「能不能顺带拿到译文/注音」× 命中率：
 *
 * 1. [NETEASE] —— 时间轴 + 译文（`tlyric`）+ 注音（`romalrc`）三轨最全，放首位；
 * 2. [LRCLIB] —— 只有时间轴，但元数据匹配最干净、无风控；
 * 3. [KUGOU]、[QQ] —— 时间轴质量好，偶有风控；
 * 4. [TEXTYL] —— 免鉴权的简单接口，覆盖冷门歌，时间轴精度到秒；
 * 5. [LYRICS_OVH] —— **只有纯文本，没有时间轴**，默认关闭（见 [defaultDisabled]）。
 *
 * [LYRICS_OVH] 仍然注册进来是因为「有词总比没词强」：真遇到冷门歌时用户可以在设置里
 * 手动打开它兜底，但它给出的轴是 5 秒一行的占位轴，不能让默认行为退化成那样。
 */
object LyricProviders {

    const val NETEASE = "netease"
    const val LRCLIB = "lrclib"
    const val KUGOU = "kugou"
    const val QQ = "qq"
    const val KUWO = "kuwo"
    const val TEXTYL = "textyl"
    const val LYRICS_OVH = "lyrics_ovh"

    /** 出厂顺序，也是「已知渠道全集」。 */
    val defaultOrder: List<String> =
        listOf(NETEASE, LRCLIB, KUGOU, QQ, KUWO, TEXTYL, LYRICS_OVH)

    /** 默认关闭的渠道：拿不到时间轴，打开后歌词会按固定 5 秒占位轴推进。 */
    val defaultDisabled: Set<String> = setOf(LYRICS_OVH)

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
 * `parse()` 面对的可能是**上个版本写进去的字符串**：那时候还没有 [LyricProviders.KUWO]。
 * 所以规则是「已知 id 按存下来的顺序排 → 缺的已知 id 按出厂顺序补到末尾 → 未知 id 直接丢」。
 * 这样版本升级新增渠道时，老用户不用手动打开新渠道也不会看不到它，降级回老版本也不会
 * 因为读到不认识的 id 而崩。
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
