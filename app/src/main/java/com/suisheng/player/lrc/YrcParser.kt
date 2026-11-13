package com.suisheng.player.lrc

import com.suisheng.player.model.LrcChar
import com.suisheng.player.model.LrcLine

/**
 * 逐字歌词（yrc）解析器。
 *
 * 格式（平台直接给明文，无需解密）：
 * ```
 * [行起始ms,行时长ms](字起始ms,字时长ms,保留字段)字(字起始ms,字时长ms,保留字段)字…
 * ```
 *
 * 例：`[55890,7990](55890,210,0)直(56100,390,0)到(56490,420,0)我`
 *
 * ## 为什么这里能拿到「真正的唱完时刻」
 *
 * 每个字都带**自己的起始时间与时长**，于是可以算出这一行最后一个字真正唱完的时刻
 * （`末字起 + 末字时长`），它天然早于「下一行起点」——两者之间那段就是间奏/尾奏。
 * 行级 LRC 只有「下一行起点」，没有这个信息，只能靠估算（见 [LrcParser] 的
 * `resolveSingEnd`）。所以这里算出来的 `singEndMs` 是**实测值**，优先级高于估算。
 *
 * 解析失败一律返回空列表，由调用方回落行级歌词 —— 逐字只是增强，
 * 任何时候都不允许因为它把整首歌词弄丢。
 */
object YrcParser {

    /** `[行起,行时长](字起,字时长,保留)字…` */
    private val LINE_PATTERN = Regex("""^\[(\d+),(\d+)\](.*)$""")

    /** 行内的字标签；第 2 组是**该字时长**，第 3 组目前恒为 0，忽略。 */
    private val WORD_PATTERN = Regex("""\((\d+),(\d+)(?:,\d*)?\)""")

    /** 末字一个字的时长都没有时（时长恒为 0）的兜底，只用于算演唱结束时刻。 */
    private const val PER_CHAR_FALLBACK_MS = 300L

    fun looksLikeYrc(text: String): Boolean =
        text.lineSequence().any { it.trimStart().startsWith("[") && WORD_PATTERN.containsMatchIn(it) }

    fun parse(content: String): List<LrcLine> {
        if (content.isBlank()) return emptyList()
        val lines = ArrayList<LrcLine>()
        content.lineSequence().forEach { raw ->
            val line = raw.trim()
            if (line.isEmpty()) return@forEach
            val match = LINE_PATTERN.matchEntire(line) ?: return@forEach
            val lineStart = match.groupValues[1].toLongOrNull() ?: return@forEach
            val lineDuration = match.groupValues[2].toLongOrNull() ?: 0L
            val body = match.groupValues[3]
            val chars = parseChars(body)
            if (chars.isEmpty()) return@forEach
            // 行首行尾的空格不参与擦除宽度，也不该占时间轴。
            val trimmed = trimChars(chars)
            val text = trimmed.joinToString("") { it.char }
            if (text.isEmpty()) return@forEach

            val lastStart = trimmed.last().startTimeMs
            val singEnd = resolveSingEnd(body, lastStart)
            val lineEnd = (lineStart + lineDuration).coerceAtLeast(singEnd)
            lines.add(
                LrcLine(
                    chars = trimmed,
                    startTimeMs = lineStart,
                    endTimeMs = lineEnd,
                    // 与 chars 逐位对齐：渲染层按「下一个字的起点」当本字的结束。
                    wordTimings = trimmed.map { it.startTimeMs },
                    hasEnhancedWordTiming = true,
                    singEndMs = singEnd.coerceAtMost(lineEnd),
                )
            )
        }
        return lines.sortedBy { it.startTimeMs }
    }

    /**
     * 末字唱完的时刻。
     *
     * 优先用**末字标签自带的时长**（`(起,时长,0)` 的第 2 项）——这是平台给的真实值；
     * 时长缺失或为 0 时才退回一个字的兜底时长。绝不能退回到「下一行起点」，
     * 那正是间奏时末字还在擦的根因。
     */
    private fun resolveSingEnd(body: String, lastCharStart: Long): Long {
        val lastMatch = WORD_PATTERN.findAll(body).lastOrNull()
        val duration = lastMatch?.groupValues?.get(2)?.toLongOrNull() ?: 0L
        val effective = if (duration > 0L) duration else PER_CHAR_FALLBACK_MS
        return lastCharStart + effective
    }

    /**
     * 行内逐字时间戳 → 字符列表。
     *
     * 一个字标签后可能跟多个字符（少数平台会按词给），这种情况让它们共用同一个
     * 起始时间：渲染层会用「下一个字的起点」作为前一个字的结束，视觉上仍然是连续擦除。
     */
    private fun parseChars(body: String): List<LrcChar> {
        val out = ArrayList<LrcChar>()
        val matches = WORD_PATTERN.findAll(body).toList()
        if (matches.isEmpty()) return out
        for ((index, m) in matches.withIndex()) {
            val start = m.groupValues[1].toLongOrNull() ?: continue
            val segmentEnd = matches.getOrNull(index + 1)?.range?.first ?: body.length
            if (m.range.last + 1 > segmentEnd) continue
            body.substring(m.range.last + 1, segmentEnd).forEach { ch ->
                out.add(LrcChar(ch.toString(), start))
            }
        }
        return out
    }

    /** 去掉逐字首尾的空白字符，避免渲染层把行首的空格也算进擦除宽度。 */
    private fun trimChars(chars: List<LrcChar>): List<LrcChar> {
        var from = 0
        var to = chars.size
        while (from < to && chars[from].char.isBlank()) from++
        while (to > from && chars[to - 1].char.isBlank()) to--
        return chars.subList(from, to).toList()
    }
}
