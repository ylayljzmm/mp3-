package com.suisheng.player.lrc

import com.suisheng.player.model.LrcChar
import com.suisheng.player.model.LrcLine
import java.io.File
import java.util.regex.Pattern

object LrcParser {
    private val TAG_PATTERN = Pattern.compile("\\[(\\d{2}):(\\d{2})[.:](\\d{2,3})\\]")

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
