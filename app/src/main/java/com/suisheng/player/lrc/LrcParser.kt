package com.suisheng.player.lrc

import com.suisheng.player.model.LrcChar
import com.suisheng.player.model.LrcLine
import java.io.File
import java.util.regex.Pattern
import kotlin.math.abs

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
            val end = if (index < lines.lastIndex) {
                lines[index + 1].startTimeMs
            } else {
                line.startTimeMs + 8000L
            }
            val chars = if (line.chars.all { it.startTimeMs == line.startTimeMs }) {
                distributeCharTimes(line.chars, line.startTimeMs, end)
            } else {
                val adjusted = line.chars.toMutableList()
                for (i in adjusted.indices) {
                    val nextStart = adjusted.getOrNull(i + 1)?.startTimeMs ?: end
                    adjusted[i] = adjusted[i].copy(startTimeMs = adjusted[i].startTimeMs.coerceAtMost(nextStart))
                }
                adjusted
            }
            line.copy(chars = chars, endTimeMs = end)
        }
    }

    private fun distributeCharTimes(chars: List<LrcChar>, startMs: Long, endMs: Long): List<LrcChar> {
        if (chars.isEmpty()) return emptyList()
        val singable = chars.filter { it.char.trim().isNotEmpty() && !isPunctuation(it.char) }
        if (singable.isEmpty()) {
            return chars.map { it.copy(startTimeMs = startMs) }
        }
        val duration = (endMs - startMs).coerceAtLeast(500L)
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
            val chars = if (stripped == rawText) {
                line.chars
            } else {
                stripped.map { LrcChar(it.toString(), line.startTimeMs) }
            }
            val wordTimings = parseEnhancedWordTags(rawText)
            line.copy(
                chars = chars,
                translation = matchTrack(translation, line.startTimeMs),
                phonetic = matchTrack(phonetic, line.startTimeMs),
                wordTimings = wordTimings,
                hasEnhancedWordTiming = wordTimings.isNotEmpty(),
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
        }
        if (meta.isNotEmpty()) sb.append("#@meta ").append(meta.joinToString(" ")).append('\n')
        if (doc.fileOffsetMs != 0L) sb.append("#@offset ").append(doc.fileOffsetMs).append('\n')

        for (line in doc.lines) {
            val time = formatTime(line.startTimeMs)
            sb.append('[').append(time).append(']').append(line.text).append('\n')
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
        )
    }

    /** 把一份带时间轴的附加轨文本摊成 `(时间, 文本)` 列表。 */
    private fun sideTrack(text: String?): List<Pair<Long, String>> {
        if (text.isNullOrBlank()) return emptyList()
        return parseContent(text).map { it.startTimeMs to it.text }
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
