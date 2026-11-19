package com.suisheng.player.lrc

import com.suisheng.player.R

/**
 * 歌词渠道的**能力描述**（与"开关/顺序"分开）。
 *
 * `LyricProviders` 只管 id，`LyricProviderConfig` 只管用户的顺序与开关；
 * 这里补上第三件事：每个渠道**到底能给什么**。播放页的来源列表要显示
 * 「逐字 / 译文 / 时间轴 / 纯文本」，设置页的开关也要能说明"这个源没有时间轴"，
 * 两处必须读同一份数据 —— 否则以后加渠道必然有一处漏改。
 *
 * 能力是**渠道级**的（这个接口通常能返回什么），不是某首歌的实际情况；
 * 单首歌到底有没有逐字，由歌词文档自身的 `hasEnhancedWordTiming` 决定。
 */
enum class LyricAbility {
    /** 能给出逐字时间轴。 */
    CHAR_LEVEL,

    /** 能给出译文轨。 */
    TRANSLATION,

    /** 至少能给出逐行时间轴。 */
    TIMELINE,
}

data class LyricProviderCapability(
    val id: String,
    val labelRes: Int,
    val abilities: Set<LyricAbility>,
    /** 出厂是否启用（纯文本渠道默认关）。 */
    val defaultEnabled: Boolean,
)

/**
 * 渠道能力表。
 *
 * 每一条都对应实测结论（同一批 18 首歌，见 `OnlineLyricFetcher` 类注释）：
 *
 * - [LyricProviders.NETEASE] 四轨齐全：`lrc` 逐行、`yrc` 逐字、`tlyric` 中文译文、
 *   `romalrc` 注音。是唯一能给出逐字的渠道；
 * - [LyricProviders.MIGU] `lyricUrl` 给逐行、`trcUrl` 给中文译词，所以标了译文能力。
 *   注意译文不是每首都挂（华语歌的 `trcUrl` 是空串），能力表说的是"接口支持"；
 * - [LyricProviders.QQ] **只有时间轴**。它的 `trans` 字段在 18 首上全为空串，
 *   早先按"接口有 trans 字段"就标了译文能力，属于看字段不看实际内容；
 * - [LyricProviders.KUGOU]、[LyricProviders.LRCLIB] 只有时间轴。
 *
 * 现在没有任何渠道是纯文本源了（旧的 lyrics.ovh 已删除），所以 `defaultEnabled` 全为
 * `true`，`LyricProviders.defaultDisabled` 也是空集 —— 两处必须一致，
 * `LyricProviderConfigTest` 里有一条断言在守这个不变量。
 */
object LyricProviderCatalog {

    private val entries: List<LyricProviderCapability> = listOf(
        LyricProviderCapability(
            id = LyricProviders.NETEASE,
            labelRes = R.string.lyric_provider_netease,
            abilities = setOf(
                LyricAbility.CHAR_LEVEL,
                LyricAbility.TRANSLATION,
                LyricAbility.TIMELINE,
            ),
            defaultEnabled = true,
        ),
        LyricProviderCapability(
            id = LyricProviders.KUGOU,
            labelRes = R.string.lyric_provider_kugou,
            abilities = setOf(LyricAbility.TIMELINE),
            defaultEnabled = true,
        ),
        LyricProviderCapability(
            id = LyricProviders.QQ,
            labelRes = R.string.lyric_provider_qq,
            abilities = setOf(LyricAbility.TIMELINE),
            defaultEnabled = true,
        ),
        LyricProviderCapability(
            id = LyricProviders.MIGU,
            labelRes = R.string.lyric_provider_migu,
            abilities = setOf(LyricAbility.TRANSLATION, LyricAbility.TIMELINE),
            defaultEnabled = true,
        ),
        LyricProviderCapability(
            id = LyricProviders.LRCLIB,
            labelRes = R.string.lyric_provider_lrclib,
            abilities = setOf(LyricAbility.TIMELINE),
            defaultEnabled = true,
        ),
    )

    fun all(): List<LyricProviderCapability> = entries

    fun of(id: String): LyricProviderCapability? = entries.firstOrNull { it.id == id }
}
