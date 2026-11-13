package com.suisheng.player.lrc

import com.suisheng.player.model.LrcLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「唱完」与「行尾」分离后的回归测试（`lrc/LrcParser.kt` + `lrc/YrcParser.kt` +
 * `lrc/LyricPhaseResolver.kt`）。
 *
 * 锁的是 5.8.0 要解决的原始症状：**前奏/间奏期间最后一个字还在慢慢擦**。
 * 根因只有一个 —— 以前整条链只有「下一行起点」这一个时刻，行后的间奏被算进了
 * 演唱时长，再被平均分给每个字。这里锁三件事：
 *  ① 行距大（有间奏）时 `singEndMs` 必须明显早于 `endTimeMs`；
 *  ② 逐字轨给的是**实测值**，末字结束时刻取自行内标签自带的时长；
 *  ③ 相位判定在间奏期返回 `INTERLUDE` 且 `animating == false` —— 上层据此停帧。
 */
class LyricSyncTest {

    private val withInterlude = """
        [00:00.00]第一句
        [00:20.00]第二句
    """.trimIndent()

    @Test
    fun interludeIsNotCountedAsSingingTime() {
        val lines = LrcParser.parseContent(withInterlude)
        val first = lines[0]

        // 行尾仍是下一行起点：滚动定位、行切换都靠它，语义不能变。
        assertEquals(20_000L, first.endTimeMs)
        // 但"唱完"必须远早于行尾，否则擦除会一路拖进 20 秒处的间奏。
        assertTrue(
            "singEndMs 应远小于 endTimeMs，实际 ${first.effectiveSingEndMs} vs ${first.endTimeMs}",
            first.effectiveSingEndMs <= 2_000L,
        )
        assertTrue(first.effectiveSingEndMs >= first.startTimeMs)
    }

    @Test
    fun charTimesNeverSpillIntoInterlude() {
        val lines = LrcParser.parseContent(withInterlude)
        val first = lines[0]

        assertTrue(first.chars.isNotEmpty())
        first.chars.forEach { ch ->
            assertTrue(
                "字「${ch.char}」的时间 ${ch.startTimeMs} 落到了演唱结束之后",
                ch.startTimeMs <= first.effectiveSingEndMs,
            )
        }
    }

    @Test
    fun tightLinesKeepLegacyBehaviour() {
        // 行距 500ms，没有间奏可吞 —— 保持 5.7.3 的手感，直接取行尾。
        val lines = LrcParser.parseContent("[00:00.00]一句\n[00:00.50]二句")
        assertEquals(lines[0].endTimeMs, lines[0].effectiveSingEndMs)
    }

    @Test
    fun phaseStopsAnimatingDuringInterlude() {
        val lines = LrcParser.parseContent(withInterlude)

        val singing = LyricPhaseResolver.resolve(lines, 300L)
        assertEquals(LyricPhase.LINE, singing.phase)
        assertTrue(singing.animating)

        val interlude = LyricPhaseResolver.resolve(lines, 10_000L)
        assertEquals(LyricPhase.INTERLUDE, interlude.phase)
        assertEquals(0, interlude.lineIndex)
        assertTrue("间奏期间必须停帧", !interlude.animating)
        assertTrue(interlude.untilNextLineMs > 0L)
    }

    @Test
    fun longLeadInIsReportedAsPrelude() {
        val lines = LrcParser.parseContent("[00:12.00]才开唱")

        val prelude = LyricPhaseResolver.resolve(lines, 500L)
        assertEquals(LyricPhase.PRELUDE, prelude.phase)
        assertEquals(-1, prelude.lineIndex)
        assertTrue(!prelude.animating)

        // 首行离得很近时不该冒「前奏」提示，避免刚点播放就闪一下。
        val shortLead = LyricPhaseResolver.resolve(lines, 11_500L)
        assertEquals(LyricPhase.PRELUDE, shortLead.phase)
        assertEquals(LyricHint.NONE, shortLead.hint)
    }

    @Test
    fun trailingSecondsArePostlude() {
        val lines = LrcParser.parseContent(withInterlude)
        val postlude = LyricPhaseResolver.resolve(lines, 60_000L)

        assertEquals(LyricPhase.POSTLUDE, postlude.phase)
        assertTrue(!postlude.animating)
    }

    @Test
    fun yrcKeepsRealWordTimings() {
        val yrc = "[55890,7990](55890,210,0)直(56100,390,0)到"
        assertTrue(YrcParser.looksLikeYrc(yrc))

        val lines = YrcParser.parse(yrc)
        val line = lines.single()

        assertEquals("直到", line.text)
        assertTrue(line.hasEnhancedWordTiming)
        assertEquals(listOf(55_890L, 56_100L), line.wordTimings)
        // 行尾取自行时长，演唱结束取自末字起 + 末字时长 —— 两者天然分离。
        assertEquals(63_880L, line.endTimeMs)
        assertEquals(56_490L, line.singEndMs)
        assertEquals(56_490L, line.effectiveSingEndMs)
    }

    @Test
    fun yrcDurationsZeroFallsBackButNeverToLineEnd() {
        val yrc = "[1000,9000](1000,0,0)啊"
        val line = YrcParser.parse(yrc).single()

        assertEquals(10_000L, line.endTimeMs)
        assertTrue(
            "末字时长缺失时也只能用一个字的兜底时长，绝不能退回行尾",
            line.effectiveSingEndMs < line.endTimeMs,
        )
    }

    @Test
    fun plainTextIsNotMistakenForYrc() {
        assertEquals(false, YrcParser.looksLikeYrc("[00:12.00]普通歌词"))
        assertTrue(YrcParser.parse("[00:12.00]普通歌词").isEmpty())
    }

    @Test
    fun wordTimingsSurviveSerializeRoundTrip() {
        // 逐字行写回 `.lrcx` 时用标准增强标签，重新解析后逐字时间必须原样恢复。
        val source = YrcParser.parse("[55890,7990](55890,210,0)直(56100,390,0)到")
        val doc = LyricDocument.singleTrack(source)
        val text = LrcParser.serialize(doc)
        val restored = LrcParser.parseDocument(text).lines.single()

        assertEquals("直到", restored.text)
        assertEquals(listOf(55_890L, 56_100L), restored.wordTimings)
        assertTrue(restored.hasEnhancedWordTiming)
    }
}
