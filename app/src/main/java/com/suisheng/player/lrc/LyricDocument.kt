package com.suisheng.player.lrc

import com.suisheng.player.model.LrcLine

/**
 * 一份歌词文档（可能带译文 / 注音 / 逐字多条附加轨）。
 *
 * ## 为什么要有这一层
 *
 * 旧模型是「一份 `.lrc` 文本 → `List<LrcLine>`」，一条时间轴只能装一种文字。
 * 译文、注音都是**同一时间轴的另一种写法**，硬塞成独立的 `LrcLine` 会污染
 * `findState` 的进度判定、卡拉 OK 擦除的下标与仪表投屏的行号。所以拆成
 * 「文档 = 原文行列表 + 轨道元数据」，附加轨挂在 [LrcLine] 的可选字段上。
 *
 * ## 版本与兼容
 *
 * [formatVersion] 为 1 表示旧单轨 `.lrc`（没有 `#@` 私有行）；
 * 为 2 表示本项目的多轨格式（首行 `#@suisheng-lrc-v2`）。
 * 旧解析器读 v2 文件时，`#@` 行因为没有 `[mm:ss.xx]` 标签会被自动丢弃，
 * 只会看到干净的原文行 —— 反向兼容成立，不需要迁移脚本。
 */
data class LyricDocument(
    val formatVersion: Int,
    val lines: List<LrcLine>,
    /**
     * 文件内偏移（毫秒），**正 = 歌词提前**。
     *
     * 来自标准 `[offset:+500]` 或私有 `#@offset 500`；两者同时存在时以标准标签为准。
     * 这是「词作者自己标的时间差」，与用户 per-song 手动偏移是两层，叠加顺序见
     * `LyricTimebase`：文件偏移 → 用户偏移 → 与播放进度比对。
     */
    val fileOffsetMs: Long = 0L,
    /** 译文目标语言（如 `zh-Hans`）；仅在存在译文轨时有意义。 */
    val translationLang: String? = null,
    /** 注音语种（如 `yue-jyutping`）；仅在存在注音轨时有意义。 */
    val phoneticLang: String? = null,
    /** 这份歌词由哪个渠道给出（`netease` / `lrclib` / 本地文件…），供 UI 显示来源。 */
    val sourceProviderId: String? = null,
) {
    /** 是否为本项目的多轨格式。 */
    val isMultiTrack: Boolean get() = formatVersion >= 2

    /** 是否真的带了译文 / 注音数据（而不是只有语言标记）。 */
    val hasTranslation: Boolean get() = lines.any { !it.translation.isNullOrBlank() }
    val hasPhonetic: Boolean get() = lines.any { !it.phonetic.isNullOrBlank() }

    fun lineAt(index: Int): LrcLine? = lines.getOrNull(index)

    companion object {
        /** 旧单轨 `.lrc` 的版本号。 */
        const val VERSION_SINGLE_TRACK = 1

        /** 本项目多轨格式的版本号，对应首行标记 [MARKER]。 */
        const val VERSION_MULTI_TRACK = 2

        /** 多轨格式的版本标记行，必须是文件首行。 */
        const val MARKER = "#@suisheng-lrc-v2"

        /** 由旧单轨解析结果包装成文档。 */
        fun singleTrack(lines: List<LrcLine>, fileOffsetMs: Long = 0L): LyricDocument =
            LyricDocument(
                formatVersion = VERSION_SINGLE_TRACK,
                lines = lines,
                fileOffsetMs = fileOffsetMs,
            )
    }
}
