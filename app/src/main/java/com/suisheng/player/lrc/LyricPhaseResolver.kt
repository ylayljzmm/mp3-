package com.suisheng.player.lrc

import com.suisheng.player.model.LrcLine

/**
 * 歌词当前处于哪个阶段：前奏 / 正在唱 / 间奏 / 尾奏。
 *
 * ## 为什么单独一层
 *
 * 判断"现在该不该动"需要同时看 [LrcLine.startTimeMs]（本行开唱）与
 * [LrcLine.effectiveSingEndMs]（本行唱完）。这两个量属于歌词数据，不属于
 * `LyricTimebase`（那边只负责把播放进度换算成歌词时间轴），也不该在
 * `ScrollLyricView` 与 `KaraokeLyricView` 里各算一遍 —— 两处算错就会出现
 * 「播放页停住了、悬浮歌词还在动」。所以判定只在这里做一次。
 *
 * 本类不依赖 Android，可直接单测。
 */
enum class LyricPhase {
    /** 第一行还没开始。 */
    PRELUDE,

    /** 本行正在唱。 */
    LINE,

    /** 本行唱完、下一行还没开始，且空隙足够长。 */
    INTERLUDE,

    /** 最后一行唱完之后。 */
    POSTLUDE,

    /** 没有歌词。 */
    SILENT,
}

/** 阶段 + 需要展示的提示（不直接引用字符串资源，保持本层纯逻辑）。 */
data class LyricPhaseInfo(
    val phase: LyricPhase,
    /** 当前行下标；没有当前行时为 -1。 */
    val lineIndex: Int,
    /** 距离下一次开唱的剩余毫秒；无下一行时为 [Long.MAX_VALUE]。 */
    val untilNextLineMs: Long,
    val hint: LyricHint,
) {
    /** 是否应该继续请求绘制帧：只有正在唱的时候才需要。 */
    val animating: Boolean get() = phase == LyricPhase.LINE
}

enum class LyricHint { NONE, PRELUDE, INTERLUDE }

object LyricPhaseResolver {

    /** 小于这个空隙视为正常换气，不进入间奏态（避免一句一顿、画面反复跳）。 */
    const val INTERLUDE_MIN_GAP_MS = 800L

    /** 空隙超过这个值才提示「间奏」，太短的停顿不打扰。 */
    const val INTERLUDE_HINT_GAP_MS = 2_500L

    /** 歌曲首尾这段时间内不提示，避免开头/结尾的空白也冒出「间奏」。 */
    const val EDGE_IGNORE_MS = 5_000L

    fun resolve(
        lines: List<LrcLine>,
        positionMs: Long,
        durationMs: Long = 0L,
    ): LyricPhaseInfo {
        if (lines.isEmpty()) {
            return LyricPhaseInfo(LyricPhase.SILENT, -1, Long.MAX_VALUE, LyricHint.NONE)
        }
        if (positionMs < lines.first().startTimeMs) {
            val lead = lines.first().startTimeMs - positionMs
            val hint = if (lead >= INTERLUDE_HINT_GAP_MS && lines.first().startTimeMs >= EDGE_IGNORE_MS) {
                LyricHint.PRELUDE
            } else {
                LyricHint.NONE
            }
            return LyricPhaseInfo(LyricPhase.PRELUDE, -1, lead, hint)
        }

        var index = 0
        for (i in lines.indices) {
            if (positionMs >= lines[i].startTimeMs) index = i else break
        }
        val line = lines[index]
        if (positionMs <= line.effectiveSingEndMs) {
            val next = lines.getOrNull(index + 1)
            return LyricPhaseInfo(
                LyricPhase.LINE,
                index,
                next?.let { (it.startTimeMs - positionMs).coerceAtLeast(0L) } ?: Long.MAX_VALUE,
                LyricHint.NONE,
            )
        }

        val next = lines.getOrNull(index + 1) ?: return LyricPhaseInfo(
            LyricPhase.POSTLUDE,
            index,
            Long.MAX_VALUE,
            LyricHint.NONE,
        )
        val gap = (next.startTimeMs - line.effectiveSingEndMs).coerceAtLeast(0L)
        val phase = if (gap >= INTERLUDE_MIN_GAP_MS) LyricPhase.INTERLUDE else LyricPhase.LINE
        val nearEdge = next.startTimeMs < EDGE_IGNORE_MS ||
            (durationMs > 0L && next.startTimeMs > durationMs - EDGE_IGNORE_MS)
        val hint = if (phase == LyricPhase.INTERLUDE && gap >= INTERLUDE_HINT_GAP_MS && !nearEdge) {
            LyricHint.INTERLUDE
        } else {
            LyricHint.NONE
        }
        return LyricPhaseInfo(phase, index, (next.startTimeMs - positionMs).coerceAtLeast(0L), hint)
    }
}
