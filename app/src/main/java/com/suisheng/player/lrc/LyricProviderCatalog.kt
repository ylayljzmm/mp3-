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
            id = LyricProviders.LRCLIB,
            labelRes = R.string.lyric_provider_lrclib,
            abilities = setOf(LyricAbility.TIMELINE),
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
            abilities = setOf(LyricAbility.TRANSLATION, LyricAbility.TIMELINE),
            defaultEnabled = true,
        ),
        LyricProviderCapability(
            id = LyricProviders.KUWO,
            labelRes = R.string.lyric_provider_kuwo,
            abilities = setOf(LyricAbility.TIMELINE),
            defaultEnabled = true,
        ),
        LyricProviderCapability(
            id = LyricProviders.TEXTYL,
            labelRes = R.string.lyric_provider_textyl,
            abilities = setOf(LyricAbility.TIMELINE),
            defaultEnabled = true,
        ),
        LyricProviderCapability(
            id = LyricProviders.LYRICS_OVH,
            labelRes = R.string.lyric_provider_lyrics_ovh,
            abilities = emptySet(),
            defaultEnabled = false,
        ),
    )

    fun all(): List<LyricProviderCapability> = entries

    fun of(id: String): LyricProviderCapability? = entries.firstOrNull { it.id == id }
}
