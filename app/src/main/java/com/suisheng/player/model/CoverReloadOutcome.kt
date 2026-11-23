package com.suisheng.player.model

/**
 * 「重新加载封面」的结果。
 *
 * 5.9.1 只回一个 `Boolean`，失败原因全被压成「未能重新加载封面」——用户看不出是
 * 在线封面开关关着、文件里本来就没有内嵌封面、还是网络真的没搜到。这里把
 * 判断依据摊开，UI 才有办法说人话。
 *
 * 纯 Kotlin、零 Android 依赖：数据层产出它，播放状态层透传它，UI 层翻译成文案。
 */
enum class CoverReloadOutcome {
    /** 换上了新图（内容确实变了）。 */
    UPDATED,

    /**
     * 取到的图和缓存里的逐字节相同。
     *
     * 最常见于「本地文件本来就只有一张内嵌封面」——这不算失败，用户看到的图确实是最好的，
     * 所以提示语要说「已是最新（来自音频内嵌）」，而不是「失败」。
     */
    UNCHANGED,

    /** 既没有内嵌封面，也没搜到网络封面。 */
    NO_SOURCE,

    /** 在线封面开关关着，而本地文件也没有内嵌封面。 */
    ONLINE_DISABLED,

    /** 取到东西了，但不是一张能解码的图（下载残片、HTML 错误页）。 */
    NOT_DECODABLE,

    /** 落盘 / 原子替换失败。 */
    IO_FAILED,

    /** 服务侧已有一次重载在跑，本次没有执行。 */
    BUSY,

    /** 当前没有在播的歌。 */
    NO_SONG,

    /** 重载期间切了歌，结果丢弃（不能把 A 的封面刷到 B 上）。 */
    CANCELLED,
    ;

    /** 对用户来说算不算「成功」：换图成功，或者确认已经是最好的一张。 */
    val isSuccess: Boolean
        get() = this == UPDATED || this == UNCHANGED
}

/** [CoverReloadOutcome] + 成功时的新路径（失败为 null）。 */
data class CoverReloadResult(
    val outcome: CoverReloadOutcome,
    val path: String? = null,
)
