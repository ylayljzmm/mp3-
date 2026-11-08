package com.suisheng.player.playback

import com.suisheng.player.lrc.LrcParser
import com.suisheng.player.lrc.LyricDocument
import com.suisheng.player.model.LrcLine
import com.suisheng.player.model.LyricState

/**
 * 歌词时间轴换算的**唯一**注入点。
 *
 * ## 为什么必须只有一处
 *
 * 播放进度会被四条链分别消费：播放页滚动歌词、悬浮歌词（卡拉 OK 擦除）、
 * 车机仪表投屏、媒体会话元数据。如果每条链各自加一次偏移，只要有一条漏了、
 * 或者两条用了不同符号，就会出现「播放页对齐了、仪表上还差半秒」这种无法解释的错位。
 * 所以偏移只在这里算一次，四条链统一读 [effectivePositionMs]。
 *
 * ## 三层叠加（顺序固定，不可交换）
 *
 * 1. 文件内偏移：`.lrc` 里的 `[offset:±ms]`，词作者自己标的（[fileOffsetMs]）；
 * 2. 用户偏移：每首歌单独调的「歌词快慢」（[userOffsetMs]），存
 *    `PerSongLyricPrefs`，换曲自动切换；
 * 3. 与播放进度比对：`raw + file + user`，负数钳到 0。
 *
 * **正 = 歌词提前**，与 LRC 标准的 `[offset:+500]` 语义一致。
 *
 * ## 不要用它做的事
 *
 * - 进度条 / 拖拽 seek / 迷你条 / 断点续播**只读原始进度**，绝不能用
 *   [effectivePositionMs]，否则调一次偏移会把播放位置也一起挪走；
 * - 它不是播放倍速。全局倍速由 `SettingsRepository.playbackSpeed` 单独管理。
 */
class LyricTimebase {

    /** 原始播放进度（毫秒），由播放服务每帧透传。 */
    var rawPositionMs: Long = 0L
        private set

    /** 歌词文件自带偏移（毫秒）。切歌 / 重载歌词时由 [setDocument] 写入。 */
    var fileOffsetMs: Long = 0L
        private set

    /** 用户为本曲目调的偏移（毫秒）。 */
    var userOffsetMs: Long = 0L
        private set

    /** 当前歌词行（不含附加轨展开）。 */
    var lrcLines: List<LrcLine> = emptyList()
        private set

    /**
     * 叠加后的歌词时间轴（毫秒）。
     *
     * 四条消费链都读这个值；[PlaybackStateHolder.positionMs] 永远是原始值。
     */
    val effectivePositionMs: Long
        get() = computeEffective(rawPositionMs, fileOffsetMs, userOffsetMs)

    /** 按叠加后的时间轴算当前行 / 下一行。 */
    val lyricState: LyricState
        get() = LrcParser.findState(lrcLines, effectivePositionMs)

    /** 每帧只写这里：不读 SharedPreferences、不重新解析文档。 */
    fun onRawPosition(rawMs: Long) {
        rawPositionMs = rawMs.coerceAtLeast(0L)
    }

    /** 切歌或歌词重载完成时调用：一次写入行列表与文件偏移。 */
    fun setDocument(document: LyricDocument) {
        lrcLines = document.lines
        fileOffsetMs = document.fileOffsetMs
    }

    /**
     * 只换行列表、保留文件偏移。
     *
     * 播放服务每帧把行列表透传给 `PlaybackStateHolder.update()`，那里只做「引用变了才换」，
     * 不重新解析文档 —— 所以偏移必须由 [setDocument] 单独写入，不能被这里清掉。
     */
    fun setLines(lines: List<LrcLine>) {
        lrcLines = lines
    }

    fun clearDocument() {
        lrcLines = emptyList()
        fileOffsetMs = 0L
    }

    /** 用户拖动偏移面板时调用；值在这里统一钳制。 */
    fun setUserOffset(ms: Long) {
        userOffsetMs = clampUserOffset(ms)
    }

    /** 「重置」：只清用户值，保留文件自带偏移。 */
    fun resetUserOffset() {
        userOffsetMs = 0L
    }

    companion object {
        /** 用户偏移的硬上限，与 `PerSongLyricPrefs.MAX_OFFSET_MS` 保持一致。 */
        const val MAX_USER_OFFSET_MS = 60_000L

        fun clampUserOffset(ms: Long): Long =
            ms.coerceIn(-MAX_USER_OFFSET_MS, MAX_USER_OFFSET_MS)

        /**
         * 三层叠加的纯函数形式。
         *
         * 单测直接打这里 —— 它不依赖 Android，也就不会出现「只有真机上才错」的情况。
         */
        fun computeEffective(rawMs: Long, fileOffsetMs: Long, userOffsetMs: Long): Long =
            (rawMs + fileOffsetMs + userOffsetMs).coerceAtLeast(0L)
    }
}
