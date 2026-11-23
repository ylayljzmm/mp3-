package com.suisheng.player.lrc

import com.suisheng.player.model.LrcChar
import com.suisheng.player.model.LrcLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 侧轨重定位（[LrcParser.retargetSideTracks]）的回归测试。
 *
 * 背景：网易云的 `tlyric` / `romalrc` 是跟着**行级** `lrc` 生成的，而 `yrc`（逐字轨）
 * 是另一套时间基准，两边的行起始时间系统性错开。5.9.1 在逐字路径下直接对 yrc 调
 * `mergeTranslation`，按 ±120ms 匹配，实测《Lemon》42 条译文只挂上 2 条 —— 这就是
 * 「注音和翻译有时显示有时不显示」的根因（有逐字轨的歌全丢，没逐字轨的歌正常）。
 *
 * 这里锁四件事：
 *  ① 行数相等时**按位置**搬运，不依赖任何时间戳；
 *  ② 行数不等时用正文锚点 + 比例补齐，把中间的行也对上；
 *  ③ 两条轨毫不相干时**不许乱搬**（宁可退回时间戳，也不能把别句的译文挂上来）；
 *  ④ 搬完必须打上 `alignVersion`，否则老 `.lrcx` 永远不会被判为待重取。
 */
class LrcParserRetargetTest {

    private fun line(startMs: Long, text: String, translation: String? = null) = LrcLine(
        chars = text.map { LrcChar(it.toString(), startMs) },
        startTimeMs = startMs,
        endTimeMs = startMs + 3000,
        translation = translation,
    )

    @Test
    fun equalLineCountsMapByPositionNotTimestamp() {
        // 逐字轨：起点 1000/5000/9000；行级轨：起点 1040/5080/9120（系统性错开 40ms 以上）
        val wordDoc = LyricDocument.singleTrack(
            listOf(line(1000, "第一句"), line(5000, "第二句"), line(9000, "第三句"))
        )
        val lineDoc = LyricDocument.singleTrack(
            listOf(
                line(1040, "第一句", "first"),
                line(5080, "第二句", "second"),
                line(9120, "第三句", "third"),
            )
        )

        val merged = LrcParser.retargetSideTracks(wordDoc, lineDoc)

        assertEquals(listOf("first", "second", "third"), merged.lines.map { it.translation })
    }

    @Test
    fun timestampsStayFromTargetNotSource() {
        // 搬运的是侧轨文本，时间轴必须还是逐字轨的（否则逐字高亮会跳到别处）
        val wordDoc = LyricDocument.singleTrack(listOf(line(1000, "第一句")))
        val lineDoc = LyricDocument.singleTrack(listOf(line(9999, "第一句", "first")))

        val merged = LrcParser.retargetSideTracks(wordDoc, lineDoc)

        assertEquals(1000L, merged.lines[0].startTimeMs)
        assertEquals("first", merged.lines[0].translation)
    }

    @Test
    fun unequalLineCountsAlignByTextAndFillGaps() {
        // 逐字轨多出一条纯音乐行（yrc 常见），行数 4 vs 3，靠正文锚点对齐
        val wordDoc = LyricDocument.singleTrack(
            listOf(
                line(1000, "第一句"),
                line(4000, "♪"),
                line(6000, "第二句"),
                line(9000, "第三句"),
            )
        )
        val lineDoc = LyricDocument.singleTrack(
            listOf(
                line(1200, "第一句", "first"),
                line(7200, "第二句", "second"),
                line(8800, "第三句", "third"),
            )
        )

        val merged = LrcParser.retargetSideTracks(wordDoc, lineDoc)

        assertEquals("first", merged.lines[0].translation)
        assertEquals("second", merged.lines[2].translation)
        assertEquals("third", merged.lines[3].translation)
    }

    @Test
    fun unrelatedTracksDoNotStealTranslations() {
        // 两条轨完全是不同的歌：正文没有锚点，行数又不等 → 不许按位置硬搬。
        // 期望退回时间戳匹配，而时间戳也对不上，于是译文一条都不该出现。
        val wordDoc = LyricDocument.singleTrack(
            listOf(line(1000, "alpha beta gamma"), line(5000, "delta epsilon zeta"))
        )
        val lineDoc = LyricDocument.singleTrack(
            listOf(
                line(7000, "完全不同的歌词甲", "甲译文"),
                line(11000, "完全不同的歌词乙", "乙译文"),
                line(15000, "完全不同的歌词丙", "丙译文"),
            )
        )

        val merged = LrcParser.retargetSideTracks(wordDoc, lineDoc)

        assertTrue(merged.lines.all { it.translation.isNullOrBlank() })
    }

    @Test
    fun existingTranslationWinsOverIncoming() {
        // 逐字轨自己已经带了译文（例如用户手工编辑过的缓存），不能被覆盖
        val wordDoc = LyricDocument.singleTrack(
            listOf(line(1000, "第一句", "keep me"))
        )
        val lineDoc = LyricDocument.singleTrack(
            listOf(line(1200, "第一句", "incoming"))
        )

        val merged = LrcParser.retargetSideTracks(wordDoc, lineDoc)

        assertEquals("keep me", merged.lines[0].translation)
    }

    @Test
    fun emptySourceLeavesTargetUntouched() {
        val wordDoc = LyricDocument.singleTrack(listOf(line(1000, "第一句")))
        val lineDoc = LyricDocument.singleTrack(listOf(line(1200, "第一句")))

        val merged = LrcParser.retargetSideTracks(wordDoc, lineDoc)

        assertEquals(wordDoc, merged)
    }

    @Test
    fun retargetStampsAlignVersion() {
        val wordDoc = LyricDocument.singleTrack(listOf(line(1000, "第一句")))
        val lineDoc = LyricDocument.singleTrack(listOf(line(1200, "第一句", "first")))

        val merged = LrcParser.retargetSideTracks(wordDoc, lineDoc)

        assertEquals(LyricDocument.ALIGN_VERSION_CURRENT, merged.alignVersion)
    }

    @Test
    fun alignVersionSurvivesSerializeRoundTrip() {
        // 这一条决定「升级后老歌词会不会自动重取」：不写进 meta 就永远判不出过期。
        val doc = LyricDocument.singleTrack(listOf(line(1000, "第一句", "first")))
            .copy(sourceProviderId = "netease", alignVersion = LyricDocument.ALIGN_VERSION_CURRENT)

        val back = LrcParser.parseDocument(LrcParser.serialize(doc))

        assertEquals(LyricDocument.ALIGN_VERSION_CURRENT, back.alignVersion)
    }

    @Test
    fun documentWithoutSideTracksKeepsAlignZero() {
        // 没有侧轨的文档若被写成 align=2，就永远不会再被判待重取 —— 反过来，
        // 若把 0 也写进去，每首歌每次播放都会被判过期而无限重取。这里锁后者：
        // align=0 的文档序列化后不应出现该 meta 行。
        val doc = LyricDocument.singleTrack(listOf(line(1000, "第一句")))
        assertEquals(0, doc.alignVersion)

        val serialized = LrcParser.serialize(doc)

        assertTrue(!serialized.contains("align="))
    }

    @Test
    fun blankTracksCountAsMissing() {
        // 网易云常返回 tlyric 对象但 lyric 为空串；空串不能算「有译文」
        val wordDoc = LyricDocument.singleTrack(listOf(line(1000, "第一句")))
        val lineDoc = LyricDocument.singleTrack(listOf(line(1200, "第一句"))).copy(
            lines = listOf(line(1200, "第一句").copy(translation = "  "))
        )

        val merged = LrcParser.retargetSideTracks(wordDoc, lineDoc)

        assertNull(merged.lines[0].translation)
    }
}
