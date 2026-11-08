package com.suisheng.player.playback

import com.suisheng.player.lrc.LyricDocument
import com.suisheng.player.model.LrcChar
import com.suisheng.player.model.LrcLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 歌词时间轴叠加的回归测试（`playback/LyricTimebase.kt`）。
 *
 * 这里锁的是**符号与顺序**，不是实现：
 *  - 正偏移 = 歌词提前（与 LRC 标准的 `[offset:+500]` 同向）；
 *  - 文件偏移与用户偏移是两层，各自独立、单向相加；
 *  - 「重置」只清用户值，文件里写的偏移必须留下 —— 那是词作者标的，不是用户改的；
 *  - 负数钳到 0，不出现「负进度」。
 *
 * 这些错误一旦漏到车上，表现是「播放页对得上、仪表上差半秒」，很难现场定位，
 * 所以全部压在 JVM 上跑。
 */
class LyricTimebaseTest {

    private fun line(text: String, startMs: Long, endMs: Long = startMs + 1_000L): LrcLine =
        LrcLine(
            chars = text.map { LrcChar(it.toString(), startMs) },
            startTimeMs = startMs,
            endTimeMs = endMs,
        )

    @Test
    fun rawPositionIsUsedWhenNoOffset() {
        val timebase = LyricTimebase()
        timebase.onRawPosition(12_000L)

        assertEquals(12_000L, timebase.effectivePositionMs)
    }

    @Test
    fun fileOffsetAndUserOffsetAddUp() {
        val timebase = LyricTimebase()
        timebase.setDocument(LyricDocument.singleTrack(listOf(line("行", 0L)), fileOffsetMs = 500L))
        timebase.setUserOffset(-200L)
        timebase.onRawPosition(10_000L)

        // 10000 + 500 + (-200)
        assertEquals(10_300L, timebase.effectivePositionMs)
        assertEquals(500L, timebase.fileOffsetMs)
        assertEquals(-200L, timebase.userOffsetMs)
    }

    @Test
    fun positiveOffsetMakesLyricsAppearEarlier() {
        val lines = listOf(line("一", 1_000L), line("二", 5_000L))
        val plain = LyricTimebase().apply {
            setDocument(LyricDocument.singleTrack(lines))
            onRawPosition(4_800L)
        }
        val advanced = LyricTimebase().apply {
            setDocument(LyricDocument.singleTrack(lines))
            setUserOffset(500L)
            onRawPosition(4_800L)
        }

        // 未调偏移时还在第一行；提前 500ms 之后第二行已经亮起
        assertEquals("一", plain.lyricState.currentLine?.text)
        assertEquals("二", advanced.lyricState.currentLine?.text)
    }

    @Test
    fun effectivePositionNeverGoesNegative() {
        val timebase = LyricTimebase()
        timebase.setDocument(LyricDocument.singleTrack(listOf(line("行", 0L)), fileOffsetMs = -1_000L))
        timebase.onRawPosition(200L)

        assertEquals(0L, timebase.effectivePositionMs)
    }

    @Test
    fun userOffsetIsClampedToSixtySeconds() {
        val timebase = LyricTimebase()

        timebase.setUserOffset(999_999L)
        assertEquals(60_000L, timebase.userOffsetMs)

        timebase.setUserOffset(-999_999L)
        assertEquals(-60_000L, timebase.userOffsetMs)

        assertEquals(60_000L, LyricTimebase.clampUserOffset(120_000L))
        assertEquals(-60_000L, LyricTimebase.clampUserOffset(-120_000L))
    }

    @Test
    fun resetKeepsFileOffsetButClearsUserOffset() {
        val timebase = LyricTimebase()
        timebase.setDocument(LyricDocument.singleTrack(listOf(line("行", 0L)), fileOffsetMs = 800L))
        timebase.setUserOffset(1_500L)

        timebase.resetUserOffset()

        assertEquals(0L, timebase.userOffsetMs)
        assertEquals(800L, timebase.fileOffsetMs)
    }

    @Test
    fun switchingDocumentReplacesFileOffset() {
        val timebase = LyricTimebase()
        timebase.setDocument(LyricDocument.singleTrack(listOf(line("旧", 0L)), fileOffsetMs = 900L))
        timebase.setDocument(LyricDocument.singleTrack(listOf(line("新", 0L)), fileOffsetMs = -300L))

        assertEquals(-300L, timebase.fileOffsetMs)
        assertEquals("新", timebase.lrcLines.single().text)
    }

    @Test
    fun setLinesKeepsFileOffsetFromDocument() {
        val timebase = LyricTimebase()
        timebase.setDocument(LyricDocument.singleTrack(listOf(line("旧", 0L)), fileOffsetMs = 400L))

        // 播放服务每帧透传行列表时走这条路，不能把文件偏移冲掉
        timebase.setLines(listOf(line("新", 0L)))

        assertEquals(400L, timebase.fileOffsetMs)
        assertEquals("新", timebase.lrcLines.single().text)
    }

    @Test
    fun rawPositionIsClampedToZero() {
        val timebase = LyricTimebase()
        timebase.onRawPosition(-5_000L)

        assertEquals(0L, timebase.rawPositionMs)
        assertEquals(0L, timebase.effectivePositionMs)
    }

    @Test
    fun emptyDocumentYieldsNoCurrentLine() {
        val timebase = LyricTimebase()
        timebase.onRawPosition(3_000L)

        assertTrue(timebase.lrcLines.isEmpty())
        assertNull(timebase.lyricState.currentLine)
    }
}
