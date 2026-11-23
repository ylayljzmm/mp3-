package com.suisheng.player.lrc

import com.suisheng.player.model.LrcChar
import com.suisheng.player.model.LrcLine
import java.io.File
import java.util.regex.Pattern
import kotlin.math.abs
import kotlin.math.roundToInt

object LrcParser {
    private val TAG_PATTERN = Pattern.compile("\\[(\\d{2}):(\\d{2})[.:](\\d{2,3})\\]")

    /** 标准元数据标签 `[offset:±ms]`（正 = 歌词提前）。 */
    private val OFFSET_PATTERN = Pattern.compile("\\[offset:\\s*([+-]?\\d+)\\]", Pattern.CASE_INSENSITIVE)

    /** 增强 LRC 的行内逐字标签 `<mm:ss.xx>`。 */
    private val WORD_TAG_PATTERN = Pattern.compile("<(\\d{2}):(\\d{2})[.:](\\d{2,3})>")

    /** 附加轨里的时间戳记号（`mm:ss` 起，小数位 1~3 位）。 */
    private val CLOCK_PATTERN = Pattern.compile("(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,3}))?")

    private const val PREFIX_TRANSLATION = "#@t "
    private const val PREFIX_PHONETIC = "#@p "

    /** 附加轨与原文行的时间戳容差：平台译文轨常有几十毫秒取整差。 */
    private const val TRACK_TOLERANCE_MS = 120L

    /** 行数相等时，按下标搬运前要求的最低正文一致率（百分比）。 */
    private const val MIN_INDEX_RATIO = 60

    /** 按正文对齐时，锚点至少要占目标行数的百分之多少，否则判定「不是同一版歌词」。 */
    private const val MIN_ANCHOR_PERCENT = 30

    /** 短于这个字数的行不当作锚点 —— 两三个字的行太容易撞。 */
    private const val MIN_ANCHOR_TEXT = 4

    /** 判「同一句」时，共同字符至少要占较短一方的百分之多少（容忍简繁/异体差异）。 */
    private const val MIN_ANCHOR_CHAR_RATIO = 70

    /** 每个可唱歌词字的估算演唱时长。中文慢歌约 200–260ms/字，取中值。 */
    private const val PER_CHAR_MS = 220L

    /** 一行至少占用的演唱时长，避免短句被算得一闪而过。 */
    private const val MIN_SING_MS = 600L

    /** 行尾保护带：下一次开唱前这么长的时间不允许再"擦"，否则会擦进间奏。 */
    private const val INTERLUDE_GUARD_MS = 800L

    /** 行距小于这个值时认为没有间奏可吞，按老行为直接取行尾。 */
    private const val TIGHT_LINE_MS = 900L

    /** 最后一行的兜底行尾（没有下一行可参照时）。 */
    private const val TAIL_MS = 8_000L

    fun parseFile(lrcFile: File): List<LrcLine> = parseContent(lrcFile.readText())

    fun fromPlainText(text: String): List<LrcLine> {
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.isEmpty()) return emptyList()
        val parsed = lines.mapIndexed { index, line ->
            LrcLine(
                chars = line.map { LrcChar(it.toString(), index * 5000L) },
                startTimeMs = index * 5000L,
                endTimeMs = index * 5000L
            )
        }
        return assignEndTimes(parsed)
    }

    fun parseContent(content: String): List<LrcLine> {
        val rawLines = content.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("[ti:") && !it.startsWith("[ar:") && !it.startsWith("[al:") && !it.startsWith("[by:") && !it.startsWith("[offset:") }

        val parsed = rawLines.mapNotNull { parseSingleLine(it) }.sortedBy { it.startTimeMs }
        return assignEndTimes(parsed)
    }

    private fun parseSingleLine(line: String): LrcLine? {
        val matcher = TAG_PATTERN.matcher(line)
        val tags = mutableListOf<Long>()
        val textParts = mutableListOf<String>()
        var lastEnd = 0

        while (matcher.find()) {
            if (matcher.start() > lastEnd) {
                textParts.add(line.substring(lastEnd, matcher.start()))
            }
            tags.add(parseTime(matcher.group(1)!!, matcher.group(2)!!, matcher.group(3)!!))
            lastEnd = matcher.end()
        }

        val trailing = line.substring(lastEnd)
        if (trailing.isNotEmpty()) {
            textParts.add(trailing)
        }

        if (tags.isEmpty()) return null

        val startTimeMs = tags.first()
        val visibleText = textParts.joinToString("").trim()
        if (visibleText.isEmpty()) return null

        val chars = if (tags.size > 1 && textParts.size >= tags.size) {
            buildWordTimedChars(tags, textParts)
        } else {
            emptyList()
        }

        return if (chars.isNotEmpty()) {
            LrcLine(chars = chars, startTimeMs = startTimeMs, endTimeMs = startTimeMs)
        } else {
            LrcLine(
                chars = visibleText.map { LrcChar(it.toString(), startTimeMs) },
                startTimeMs = startTimeMs,
                endTimeMs = startTimeMs
            )
        }
    }

    private fun buildWordTimedChars(tags: List<Long>, textParts: List<String>): List<LrcChar> {
        val chars = mutableListOf<LrcChar>()
        var tagIndex = 0
        for (part in textParts) {
            if (part.isEmpty()) continue
            val time = tags.getOrElse(tagIndex) { tags.last() }
            part.forEach { ch ->
                chars.add(LrcChar(ch.toString(), time))
            }
            tagIndex++
        }
        return chars
    }

    private fun assignEndTimes(lines: List<LrcLine>): List<LrcLine> {
        if (lines.isEmpty()) return emptyList()
        return lines.mapIndexed { index, line ->
            // 行尾仍取下一行起点：滚动定位、行切换都靠它，语义不变。
            val nextStart = if (index < lines.lastIndex) {
                lines[index + 1].startTimeMs
            } else {
                line.startTimeMs + TAIL_MS
            }
            // 但"唱完"要单独算：行尾包含行后的间奏/尾奏，不能拿它当演唱时长。
            val singEnd = resolveSingEnd(line, nextStart)
            val chars = if (line.chars.all { it.startTimeMs == line.startTimeMs }) {
                distributeCharTimes(line.chars, line.startTimeMs, singEnd)
            } else {
                val adjusted = line.chars.toMutableList()
                for (i in adjusted.indices) {
                    val nextCharStart = adjusted.getOrNull(i + 1)?.startTimeMs ?: singEnd
                    adjusted[i] = adjusted[i].copy(
                        startTimeMs = adjusted[i].startTimeMs.coerceAtMost(nextCharStart),
                    )
                }
                adjusted
            }
            line.copy(chars = chars, endTimeMs = nextStart, singEndMs = singEnd)
        }
    }

    /**
     * 本行演唱结束时刻。
     *
     * 有逐字真值（增强标签 / yrc）时以末字为准；否则按可唱字符数估算，
     * 并在下一次开唱前留出 [INTERLUDE_GUARD_MS] 的保护带 ——
     * 保证末字一定在下行开唱前停住，不会"擦到间奏里"。
     *
     * 行距本来就紧（≤ [TIGHT_LINE_MS]）时不估算，直接取行尾：
     * 那种情况没有间奏可吞，保持 5.7.3 的手感。
     */
    private fun resolveSingEnd(line: LrcLine, nextStart: Long): Long {
        val start = line.startTimeMs
        val gap = (nextStart - start).coerceAtLeast(1L)
        if (line.hasEnhancedWordTiming && line.chars.isNotEmpty()) {
            val lastCharEnd = line.chars.last().startTimeMs + PER_CHAR_MS
            return lastCharEnd.coerceIn(start, nextStart)
        }
        if (gap <= TIGHT_LINE_MS) return nextStart
        val lower = start + MIN_SING_MS
        val upper = nextStart - INTERLUDE_GUARD_MS
        if (upper <= lower) return nextStart
        val estimate = start + singableCount(line.chars) * PER_CHAR_MS
        return estimate.coerceIn(lower, upper)
    }

    private fun singableCount(chars: List<LrcChar>): Int =
        chars.count { it.char.trim().isNotEmpty() && !isPunctuation(it.char) }
            .coerceAtLeast(1)

    private fun distributeCharTimes(chars: List<LrcChar>, startMs: Long, endMs: Long): List<LrcChar> {
        if (chars.isEmpty()) return emptyList()
        val singable = chars.filter { it.char.trim().isNotEmpty() && !isPunctuation(it.char) }
        if (singable.isEmpty()) {
            return chars.map { it.copy(startTimeMs = startMs) }
        }
        val duration = (endMs - startMs).coerceAtLeast(MIN_SING_MS / 2)
        val step = duration / singable.size
        var singableIndex = 0
        return chars.map { ch ->
            if (ch.char.trim().isEmpty() || isPunctuation(ch.char)) {
                val anchor = singable.getOrNull(singableIndex)?.let { startMs + step * singableIndex } ?: startMs
                ch.copy(startTimeMs = anchor)
            } else {
                val time = startMs + step * singableIndex
                singableIndex++
                ch.copy(startTimeMs = time)
            }
        }
    }

    private fun isPunctuation(char: String): Boolean {
        return char in setOf("，", "。", "！", "？", "、", ",", ".", "!", "?", "…", "—", " ")
    }

    private fun parseTime(min: String, sec: String, frac: String): Long {
        val fractionMs = when (frac.length) {
            2 -> frac.toInt() * 10L
            else -> frac.toInt().let { if (it < 100) it * 10L else it.toLong() }
        }
        return min.toLong() * 60_000L + sec.toLong() * 1000L + fractionMs
    }

    // ------------------------------------------------------------ 多轨文档（v2）

    /**
     * 解析成完整文档（原文 + 译文轨 + 注音轨 + 文件内偏移 + 逐字标签）。
     *
     * 与 [parseContent] 的关系：后者**行为完全不变**（旧的四条消费链继续用它），
     * 这里只是在外层多包一层元信息。旧单轨文件进来时返回 `formatVersion = 1`
     * 的文档、附加轨全为空，等价于旧行为。
     */
    fun parseDocument(content: String): LyricDocument {
        val isMultiTrack = content.lineSequence()
            .firstOrNull { it.isNotBlank() }
            ?.trim() == LyricDocument.MARKER
        val meta = parseMeta(content)
        val translation = parseTrack(content, PREFIX_TRANSLATION)
        val phonetic = parseTrack(content, PREFIX_PHONETIC)
        val fileOffset = parseOffsetTag(content)

        val lines = parseContent(content).map { line ->
            // 标准增强 LRC 把逐字时间戳写成行内尖括号标签，这些标签不能当正文画出来。
            // 只在文档层剥离，parseContent 的返回保持原样（旧链行为不变）。
            val rawText = line.text
            val stripped = WORD_TAG_PATTERN.matcher(rawText).replaceAll("")
            val wordTimings = parseEnhancedWordTags(rawText)
            // 标签个数与剥离后的字数一一对应时，直接按位赋给每个字 ——
            // 这样逐字时间戳既能从增强 LRC 恢复，也能从 yrc 序列化后的文件恢复。
            val perCharTiming = wordTimings.size == stripped.length && stripped.isNotEmpty()
            val chars = when {
                perCharTiming -> stripped.indices.map { i ->
                    LrcChar(stripped[i].toString(), wordTimings[i])
                }
                stripped == rawText -> line.chars
                else -> stripped.map { LrcChar(it.toString(), line.startTimeMs) }
            }
            val singEnd = if (perCharTiming) {
                val last = wordTimings.last()
                (last + PER_CHAR_MS).coerceAtMost(maxOf(line.endTimeMs, last)).coerceAtLeast(last)
            } else {
                line.singEndMs
            }
            line.copy(
                chars = chars,
                translation = matchTrack(translation, line.startTimeMs),
                phonetic = matchTrack(phonetic, line.startTimeMs),
                wordTimings = wordTimings,
                hasEnhancedWordTiming = wordTimings.isNotEmpty(),
                singEndMs = singEnd,
            )
        }

        return LyricDocument(
            formatVersion = if (isMultiTrack) {
                LyricDocument.VERSION_MULTI_TRACK
            } else {
                LyricDocument.VERSION_SINGLE_TRACK
            },
            lines = lines,
            fileOffsetMs = fileOffset,
            translationLang = meta["translationLang"],
            phoneticLang = meta["phoneticLang"],
            sourceProviderId = meta["provider"],
            alignVersion = meta["align"]?.toIntOrNull() ?: 0,
        )
    }

    /**
     * 序列化成多轨文本。
     *
     * 格式约定（写在 `docs` 里，实施时不要改）：
     * - 首行必须是 [LyricDocument.MARKER]；
     * - 原文行保持标准 `[mm:ss.xx]正文`，**只有这种行会被旧解析器认出来**；
     * - 译文 / 注音走 `#@t` / `#@p` 私有前缀，旧解析器因为拿不到时间标签会直接丢弃。
     *
     * `serialize(parseDocument(x))` 对已是本格式的文本幂等（时间戳按百分秒对齐）。
     */
    fun serialize(doc: LyricDocument): String {
        val sb = StringBuilder()
        sb.append(LyricDocument.MARKER).append('\n')
        val meta = buildList {
            doc.sourceProviderId?.takeIf { it.isNotBlank() }?.let { add("provider=$it") }
            doc.translationLang?.takeIf { it.isNotBlank() }?.let { add("translationLang=$it") }
            doc.phoneticLang?.takeIf { it.isNotBlank() }?.let { add("phoneticLang=$it") }
            // 只在文档真的带侧轨、且对齐版本是新的时才写。没有侧轨的文档写成 align=0
            // 会让它每次读取都被判成「该重取」，白白多打一次网络请求。
            if (doc.alignVersion > 0) add("align=${doc.alignVersion}")
        }
        if (meta.isNotEmpty()) sb.append("#@meta ").append(meta.joinToString(" ")).append('\n')
        if (doc.fileOffsetMs != 0L) sb.append("#@offset ").append(doc.fileOffsetMs).append('\n')

        for (line in doc.lines) {
            val time = formatTime(line.startTimeMs)
            // 逐字行用标准增强标签（`<mm:ss.xx>`）把每个字的时间戳写进正文：
            // 这是解析器本来就认识的语法，老版本读到只会当成普通文本，
            // 不需要给 `.lrcx` 升版本，也不会触发任何迁移。
            val body = if (line.hasEnhancedWordTiming && line.chars.isNotEmpty()) {
                line.chars.joinToString("") { ch ->
                    "<" + formatTime(ch.startTimeMs) + ">" + ch.char
                }
            } else {
                line.text
            }
            sb.append('[').append(time).append(']').append(body).append('\n')
            line.translation?.takeIf { it.isNotBlank() }?.let {
                sb.append(PREFIX_TRANSLATION).append(time).append(' ').append(it).append('\n')
            }
            line.phonetic?.takeIf { it.isNotBlank() }?.let {
                sb.append(PREFIX_PHONETIC).append(time).append(' ').append(it).append('\n')
            }
        }
        return sb.toString()
    }

    /**
     * 文件内偏移（毫秒，正 = 歌词提前）。
     *
     * 标准 `[offset:±ms]` 优先，其次私有 `#@offset`。两者都没有则返回 0。
     * 旧实现是在 [parseContent] 里把这行直接过滤掉，等于把词作者标的偏移丢了。
     */
    fun parseOffsetTag(content: String): Long {
        val standard = OFFSET_PATTERN.matcher(content)
        if (standard.find()) return standard.group(1)?.toLongOrNull() ?: 0L
        content.lineSequence().forEach { raw ->
            val line = raw.trim()
            if (line.startsWith("#@offset")) {
                return line.removePrefix("#@offset").trim().toLongOrNull() ?: 0L
            }
        }
        return 0L
    }

    /**
     * 把一条**独立的**译文 LRC 文本按时间戳挂到 [doc] 的原文行上。
     *
     * 平台接口给的译文往往是单独一份完整的 `[mm:ss.xx]译文` 文本（网易云的 `tlyric`、
     * QQ 音乐的 `trans` 都是），不是一个字段。旧实现把它当成 `plain` 交给
     * `fromPlainText()`，于是译文被**当成正文**、还按 5 秒一行生成了假时间轴 ——
     * 这是「译文看起来有、但和歌完全对不上」的根因。
     *
     * 这里改成走附加轨：时间戳对齐、对不上的行留空（显示层静默降级）。
     */
    fun mergeTranslation(
        doc: LyricDocument,
        translationText: String?,
        lang: String? = null,
    ): LyricDocument {
        val track = sideTrack(translationText)
        if (track.isEmpty()) return doc
        return doc.copy(
            formatVersion = LyricDocument.VERSION_MULTI_TRACK,
            lines = doc.lines.map { line ->
                line.copy(translation = matchTrack(track, line.startTimeMs) ?: line.translation)
            },
            translationLang = lang?.takeIf { it.isNotBlank() } ?: doc.translationLang,
            // 侧轨与**行级**轨同源，按时间戳挂是靠得住的；记下版本免得落盘后被判成旧文件。
            // 逐字轨不要走这里 —— 见 [retargetSideTracks] 的说明。
            alignVersion = LyricDocument.ALIGN_VERSION_CURRENT,
        )
    }

    /** 同 [mergeTranslation]，作用于注音轨（网易云 `romalrc`）。 */
    fun mergePhonetic(
        doc: LyricDocument,
        phoneticText: String?,
        lang: String? = null,
    ): LyricDocument {
        val track = sideTrack(phoneticText)
        if (track.isEmpty()) return doc
        return doc.copy(
            formatVersion = LyricDocument.VERSION_MULTI_TRACK,
            lines = doc.lines.map { line ->
                line.copy(phonetic = matchTrack(track, line.startTimeMs) ?: line.phonetic)
            },
            phoneticLang = lang?.takeIf { it.isNotBlank() } ?: doc.phoneticLang,
            alignVersion = LyricDocument.ALIGN_VERSION_CURRENT,
        )
    }

    /** 把一份带时间轴的附加轨文本摊成 `(时间, 文本)` 列表。 */
    private fun sideTrack(text: String?): List<Pair<Long, String>> {
        if (text.isNullOrBlank()) return emptyList()
        return parseContent(text).map { it.startTimeMs to it.text }
    }

    // ------------------------------------------------------------ 侧轨重挂（逐字轨）

    /**
     * 把 [source] 上的译文 / 注音**按行对应关系**搬到 [target] 上。
     *
     * ## 为什么需要这个函数
     *
     * 网易云的 `tlyric`（译文）和 `romalrc`（注音）时间轴是跟着**行级 `lrc`** 生成的，
     * 而 `yrc`（逐字轨）是另一套时间基准 —— 两边的行起始时间系统性不一致。实测
     * 《Lemon》侧轨对 `lrc` 精确命中 42/45，对 `yrc` 精确命中 **0/45**；《Counting Stars》
     * 对 `lrc` 98/102，对 `yrc` 只有 0 精确 + 14 落在 ±120ms。
     *
     * 5.9.1 直接对逐字轨调 [mergeTranslation] / [mergePhonetic]，于是**只要有逐字轨的歌，
     * 译文和注音就成片丢失**；没有逐字轨的歌走行级路径，一切正常 —— 这正是用户反馈的
     * 「有时显示有时不显示」。
     *
     * ## 为什么不干脆放宽容差
     *
     * yrc 与 lrc 的偏移是**逐行漂移**的（几十到几百毫秒不等），把 ±120ms 放大到 ±2s
     * 会让译文挂到错误的行上（「串行」），比缺失更糟。实测即使放到 2s，26 首歌也只能
     * 回收 1267/1282，而按下标能到 1275，按正文锚点能到 1383/1388（99.6%）。
     *
     * ## 三级策略（顺序即优先级）
     *
     * 1. **行数相等 → 直接按下标对应**。实测 30 首里 27 首两边行数完全相等，
     *    且正文逐位一致率均值 98%、最低 92%，按下标是最准的。
     * 2. **行数不等 → 单调正文对齐 + 相邻锚点按比例补齐**。锚点数不足
     *    [MIN_ANCHOR_PERCENT]% 时放弃这一级（说明两条轨根本不是同一版歌词）。
     * 3. **兜底 → 退回按时间戳匹配**（[mergeTranslation] 的老行为）。
     */
    fun retargetSideTracks(target: LyricDocument, source: LyricDocument): LyricDocument {
        if (target.lines.isEmpty() || source.lines.isEmpty()) return target
        if (!source.hasTranslation && !source.hasPhonetic) return target
        val mapping = alignLineIndices(target.lines, source.lines)
            ?: return mergeByTimestamp(target, source)
        val lines = target.lines.mapIndexed { index, line ->
            val from = mapping[index]
            if (from !in source.lines.indices) return@mapIndexed line
            val other = source.lines[from]
            line.copy(
                translation = line.translation?.takeIf { it.isNotBlank() }
                    ?: other.translation?.takeIf { it.isNotBlank() },
                phonetic = line.phonetic?.takeIf { it.isNotBlank() }
                    ?: other.phonetic?.takeIf { it.isNotBlank() },
            )
        }
        val carriesTracks = lines.any { !it.translation.isNullOrBlank() || !it.phonetic.isNullOrBlank() }
        return target.copy(
            formatVersion = if (carriesTracks) {
                LyricDocument.VERSION_MULTI_TRACK
            } else {
                target.formatVersion
            },
            lines = lines,
            translationLang = source.translationLang ?: target.translationLang,
            phoneticLang = source.phoneticLang ?: target.phoneticLang,
            alignVersion = LyricDocument.ALIGN_VERSION_CURRENT,
        )
    }

    /**
     * 算「[target] 的第 i 行对应 [source] 的第几行」，对应不上是 `-1`。
     *
     * 返回 null 表示**没把握**，调用方应退回时间戳匹配。
     */
    private fun alignLineIndices(
        target: List<LrcLine>,
        source: List<LrcLine>,
    ): IntArray? {
        if (target.size == source.size) {
            // 行数相等时按下标最准，但先做个便宜的体检：两边正文得大体是一回事。
            // 完全不像（比如 yrc 是另一版歌词）就交给下面按正文对齐。
            if (lineTextRatio(target, source) >= MIN_INDEX_RATIO) {
                return IntArray(target.size) { it }
            }
        }

        val anchors = ArrayList<Pair<Int, Int>>()
        var cursor = 0
        for (i in target.indices) {
            val wanted = target[i].text.trim()
            if (wanted.isEmpty()) continue
            var j = cursor
            while (j < source.size) {
                if (sameLineText(wanted, source[j].text.trim())) {
                    anchors.add(i to j)
                    cursor = j + 1
                    break
                }
                j++
            }
        }
        if (anchors.isEmpty()) return null
        // 锚点太稀疏说明两条轨的行不是一一对应（可能是不同版本），宁可不搬。
        if (anchors.size * 100 < target.size * MIN_ANCHOR_PERCENT) return null

        val out = IntArray(target.size) { -1 }
        anchors.forEach { (t, s) -> out[t] = s }
        for (n in 0 until anchors.size - 1) {
            val (t0, s0) = anchors[n]
            val (t1, s1) = anchors[n + 1]
            val spanT = t1 - t0
            val spanS = s1 - s0
            if (spanT <= 1 || spanS <= 1) continue
            for (step in 1 until spanT) {
                val mapped = s0 + (step.toDouble() / spanT * spanS).roundToInt()
                // 只补在两条锚点**之间**，绝不越过锚点，避免把整段搬到别处。
                out[t0 + step] = mapped.coerceIn(s0 + 1, s1 - 1)
            }
        }
        return out
    }

    /** 两边同一位置的行，正文完全相同的占比（0~100）。 */
    private fun lineTextRatio(target: List<LrcLine>, source: List<LrcLine>): Int {
        val size = minOf(target.size, source.size)
        if (size == 0) return 100
        var same = 0
        for (i in 0 until size) {
            val left = target[i].text.trim()
            val right = source[i].text.trim()
            if (left.isEmpty() || right.isEmpty()) continue
            if (left == right) same++
        }
        return same * 100 / size
    }

    /**
     * 两行正文是不是「同一句」。
     *
     * 除了全等和互相包含，还要容忍**简繁 / 异体字差异**：《残酷な天使のテーゼ》的
     * 行级轨是简体（`神話` 写成 `神话`），逐字轨是日文原字，逐字比较必然不等。
     * 用「共同字符占较短一方的 70%」判定，既放过简繁差异，又不会把两句真正不同的
     * 歌词误判成同一句（短行不足 4 字一律不认作锚点）。
     */
    private fun sameLineText(a: String, b: String): Boolean {
        if (a == b) return true
        if (a.length < MIN_ANCHOR_TEXT || b.length < MIN_ANCHOR_TEXT) return false
        if (a.contains(b) || b.contains(a)) return true
        val left = a.toSet()
        val right = b.toSet()
        val shorter = minOf(left.size, right.size)
        if (shorter == 0) return false
        val common = left.count { it in right }
        return common * 100 >= shorter * MIN_ANCHOR_CHAR_RATIO
    }

    /** 三级策略全部失效时的兜底：按时间戳匹配（5.9.1 的老行为）。 */
    private fun mergeByTimestamp(target: LyricDocument, source: LyricDocument): LyricDocument {
        val translation = source.lines
            .mapNotNull { line -> line.translation?.takeIf { it.isNotBlank() }?.let { line.startTimeMs to it } }
        val phonetic = source.lines
            .mapNotNull { line -> line.phonetic?.takeIf { it.isNotBlank() }?.let { line.startTimeMs to it } }
        if (translation.isEmpty() && phonetic.isEmpty()) return target
        val lines = target.lines.map { line ->
            line.copy(
                translation = line.translation?.takeIf { it.isNotBlank() }
                    ?: matchTrack(translation, line.startTimeMs),
                phonetic = line.phonetic?.takeIf { it.isNotBlank() }
                    ?: matchTrack(phonetic, line.startTimeMs),
            )
        }
        return target.copy(
            formatVersion = LyricDocument.VERSION_MULTI_TRACK,
            lines = lines,
            translationLang = source.translationLang ?: target.translationLang,
            phoneticLang = source.phoneticLang ?: target.phoneticLang,
            alignVersion = LyricDocument.ALIGN_VERSION_CURRENT,
        )
    }

    /** 行内尖括号逐字标签 `<mm:ss.xx>` 的时间戳列表（按出现顺序）。 */
    fun parseEnhancedWordTags(text: String): List<Long> {
        val matcher = WORD_TAG_PATTERN.matcher(text)
        val out = mutableListOf<Long>()
        while (matcher.find()) {
            out.add(parseTime(matcher.group(1)!!, matcher.group(2)!!, matcher.group(3)!!))
        }
        return out
    }

    /** 解析 `#@meta k=v k=v` 行。 */
    private fun parseMeta(content: String): Map<String, String> {
        val out = mutableMapOf<String, String>()
        content.lineSequence().forEach { raw ->
            val line = raw.trim()
            if (!line.startsWith("#@meta")) return@forEach
            line.removePrefix("#@meta").trim().split(' ').forEach { token ->
                val eq = token.indexOf('=')
                if (eq > 0) out[token.substring(0, eq)] = token.substring(eq + 1)
            }
        }
        return out
    }

    /** 解析一条附加轨（`#@t mm:ss.xx 文本` / `#@p mm:ss.xx 文本`）。 */
    private fun parseTrack(content: String, prefix: String): List<Pair<Long, String>> {
        val out = mutableListOf<Pair<Long, String>>()
        content.lineSequence().forEach { raw ->
            val line = raw.trim()
            if (!line.startsWith(prefix)) return@forEach
            val rest = line.removePrefix(prefix).trim()
            val space = rest.indexOf(' ')
            if (space <= 0) return@forEach
            val timeMs = parseClock(rest.substring(0, space)) ?: return@forEach
            val text = rest.substring(space + 1).trim()
            if (text.isNotEmpty()) out.add(timeMs to text)
        }
        return out
    }

    /**
     * 按时间戳把附加轨对到原文行上。
     *
     * 先找完全相等的；没有再找 ±[TRACK_TOLERANCE_MS] 内最近的一条 ——
     * 平台的译文轨与原文轨偶有几十毫秒的取整差，严格相等会漏配。
     * 超出容差就当作「这行没有译文」，显示层静默降级。
     */
    private fun matchTrack(track: List<Pair<Long, String>>, timeMs: Long): String? {
        if (track.isEmpty()) return null
        track.firstOrNull { it.first == timeMs }?.let { return it.second }
        return track.asSequence()
            .filter { abs(it.first - timeMs) <= TRACK_TOLERANCE_MS }
            .minByOrNull { abs(it.first - timeMs) }
            ?.second
    }

    /** `mm:ss` / `mm:ss.x` / `mm:ss.xx` / `mm:ss.xxx` 都接受。 */
    private fun parseClock(token: String): Long? {
        val matcher = CLOCK_PATTERN.matcher(token)
        if (!matcher.matches()) return null
        val frac = matcher.group(3)
        val fractionMs = when {
            frac == null -> 0L
            frac.length == 1 -> frac.toLong() * 100L
            frac.length == 2 -> frac.toLong() * 10L
            else -> frac.toLong()
        }
        return matcher.group(1)!!.toLong() * 60_000L +
            matcher.group(2)!!.toLong() * 1000L + fractionMs
    }

    /** 百分秒精度的时间戳，与 [parseTime] 的两位小数分支对齐，保证往返幂等。 */
    private fun formatTime(ms: Long): String {
        val centis = ms.coerceAtLeast(0L) / 10L
        // 显式指定 Locale：默认 locale 在某些区域会用非 ASCII 数字，写进文件就再也解析不回来
        return String.format(
            java.util.Locale.US,
            "%02d:%02d.%02d",
            centis / 6000L,
            (centis / 100L) % 60L,
            centis % 100L,
        )
    }

    fun findState(lines: List<LrcLine>, positionMs: Long): com.suisheng.player.model.LyricState {
        if (lines.isEmpty()) {
            return com.suisheng.player.model.LyricState(null, null, positionMs)
        }
        var currentIndex = -1
        for (i in lines.indices) {
            if (positionMs >= lines[i].startTimeMs) {
                currentIndex = i
            } else {
                break
            }
        }
        if (currentIndex == -1) {
            return com.suisheng.player.model.LyricState(null, lines.firstOrNull(), positionMs)
        }
        val current = lines[currentIndex]
        val next = lines.getOrNull(currentIndex + 1)
        return com.suisheng.player.model.LyricState(current, next, positionMs)
    }
}
