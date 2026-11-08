package com.suisheng.player.model

data class Song(
    val id: Long,
    val title: String,
    val artist: String,
    val path: String,
    val lrcPath: String?,
    val durationMs: Long = 0L,
    /**
     * 专辑名。本地曲库来自 MediaStore 的 `ALBUM` 列或 `METADATA_KEY_ALBUM`；
     * 取不到就是空串——专辑墙会把空串统一归到「未知专辑」，而不是丢歌。
     * 在线曲目由 `OnlineMusicApi` 填。
     */
    val album: String = "",
    /**
     * 专辑发布年份。本地曲库来自 MediaStore 的 `YEAR` 列；目录扫描 / SAF / 在线曲目
     * 取不到就是 0 —— 专辑卡「获取不到的不显示」，不拿总时长或占位符冒充。
     * 追加在末尾并给默认值，所有既有命名参数 / 位置参数构造与 Parcelable 转换都不受影响。
     */
    val year: Int = 0,
)

data class ArtistGroup(
    val name: String,
    val songCount: Int
)

data class LrcChar(
    val char: String,
    val startTimeMs: Long
)

data class LrcLine(
    val chars: List<LrcChar>,
    val startTimeMs: Long,
    val endTimeMs: Long,
    /**
     * 译文（与原文**共用同一条时间轴**）。
     *
     * 不是新的歌词行，只是挂在原文行上的附加轨 —— 否则 `findState` 的下标、
     * 卡拉 OK 的擦除进度、仪表投屏的行号都会跟着偏移。没有译文时保持 `null`，
     * 显示层据此静默降级（不显示空行）。
     */
    val translation: String? = null,
    /** 注音轨（音译行，原文下方独立一行小字）。 */
    val phonetic: String? = null,
    /** 注音语种标记，如 `yue-jyutping`；仅元数据，不参与排版。 */
    val phoneticLang: String? = null,
    /** 尖括号逐字标签 `<mm:ss.xx>` 解析出的逐字时间戳；没有就是空表。 */
    val wordTimings: List<Long> = emptyList(),
    /** 该行是否带标准增强 LRC 的逐字标签（能力标记，本期只存不用）。 */
    val hasEnhancedWordTiming: Boolean = false,
) {
    val text: String get() = chars.joinToString("") { it.char }

    /**
     * 展开成显示用的多行文本，顺序固定为 **原文 → 注音 → 译文**。
     *
     * 为什么放在数据类上：播放页滚动歌词、悬浮歌词、仪表投屏三条链各有一套绘制代码，
     * 如果把「要不要显示译文」散在三个地方判断，很容易出现「播放页有译文、
     * 悬浮窗没有」这种不一致。统一从这里出，三条链只负责排版。
     *
     * 无数据时**不产生空行** —— 返回的列表里不会有空串占位，行高也就不会多算一行。
     */
    fun displayLines(showTranslation: Boolean, showPhonetic: Boolean): List<String> {
        val out = ArrayList<String>(3)
        out.add(text)
        if (showPhonetic) phonetic?.takeIf { it.isNotBlank() }?.let(out::add)
        if (showTranslation) translation?.takeIf { it.isNotBlank() }?.let(out::add)
        return out
    }
}

data class LyricState(
    val currentLine: LrcLine?,
    val nextLine: LrcLine?,
    val positionMs: Long
)
