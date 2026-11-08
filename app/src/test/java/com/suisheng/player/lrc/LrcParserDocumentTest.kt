package com.suisheng.player.lrc

import com.suisheng.player.model.LrcChar
import com.suisheng.player.model.LrcLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 多轨歌词文档的回归测试（`lrc/LyricDocument.kt` + `LrcParser.parseDocument`）。
 *
 * 锁的是**口径**而不是实现细节，四条都对应真实会踩到的坑：
 *  ① 旧单轨 `.lrc` 必须是 `formatVersion = 1`、附加轨全空 —— 老用户的歌词文件不能因为
 *    这次改造而变样；
 *  ② `#@` 私有行对 [LrcParser.parseContent] 必须**完全透明**（旧的四条消费链还走它），
 *    否则播放页歌词会突然多出一堆 `#@t ...` 文本；
 *  ③ 平台译文轨与原文轨常有几十毫秒取整差，容差内要配上、超出要静默降级；
 *  ④ `serialize(parseDocument(x))` 要幂等，否则每次重搜歌词都会把文件越写越脏。
 */
class LrcParserDocumentTest {

    private val singleTrack = """
        [ti:测试]
        [ar:某人]
        [offset:500]
        [00:12.34]第一行
        [00:17.02]第二行
    """.trimIndent()

    private val multiTrack = """
        #@suisheng-lrc-v2
        #@meta provider=netease translationLang=zh-Hans
        #@offset 500
        [00:12.34]原文一
        #@t 00:12.34 translation one
        [00:17.02]原文二
        #@t 00:17.14 translation two
    """.trimIndent()

    @Test
    fun singleTrackFileIsVersionOneWithNoExtraTracks() {
        val doc = LrcParser.parseDocument(singleTrack)

        assertEquals(LyricDocument.VERSION_SINGLE_TRACK, doc.formatVersion)
        assertFalse(doc.isMultiTrack)
        assertEquals(500L, doc.fileOffsetMs)
        assertEquals(2, doc.lines.size)
        assertNull(doc.lines[0].translation)
        assertNull(doc.translationLang)
        assertFalse(doc.hasTranslation)
    }

    @Test
    fun privateLinesStayInvisibleToLegacyParsing() {
        val legacy = LrcParser.parseContent(multiTrack)
        val doc = LrcParser.parseDocument(multiTrack)

        // 旧链只该看到两条原文行，`#@` 行一条都不能漏进去
        assertEquals(2, legacy.size)
        assertEquals(listOf("原文一", "原文二"), legacy.map { it.text })
        assertTrue(doc.isMultiTrack)
    }

    @Test
    fun translationTrackAlignsByTimestamp() {
        val doc = LrcParser.parseDocument(multiTrack)

        assertEquals(LyricDocument.VERSION_MULTI_TRACK, doc.formatVersion)
        assertEquals("zh-Hans", doc.translationLang)
        assertEquals("netease", doc.sourceProviderId)
        assertEquals("translation one", doc.lines[0].translation)
        // 17.02 与 17.14 差 120ms，正好在上限内，必须配上
        assertEquals("translation two", doc.lines[1].translation)
        assertTrue(doc.hasTranslation)
    }

    @Test
    fun translationOutsideToleranceIsDropped() {
        val text = """
            [00:17.02]原文
            #@t 00:17.90 差得太多
        """.trimIndent()

        val doc = LrcParser.parseDocument(text)

        assertEquals(1, doc.lines.size)
        assertNull(doc.lines[0].translation)
        assertFalse(doc.hasTranslation)
    }

    @Test
    fun enhancedWordTagsAreReadAndStrippedFromText() {
        val text = "[00:21.50]<00:21.50>逐<00:21.80>字<00:22.10>唱"

        val doc = LrcParser.parseDocument(text)
        val line = doc.lines.single()

        assertEquals("逐字唱", line.text)
        assertTrue(line.hasEnhancedWordTiming)
        assertEquals(listOf(21_500L, 21_800L, 22_100L), line.wordTimings)
    }

    @Test
    fun serializeRoundTripIsIdempotent() {
        val first = LrcParser.serialize(LrcParser.parseDocument(multiTrack))
        val second = LrcParser.serialize(LrcParser.parseDocument(first))

        // 第一遍**不等于** fixture 原文，这是设计如此：附加轨的时间戳会被归一成它所
        // 挂靠的原文行的时间（fixture 里 `#@t 00:17.14` 与原文 00:17.02 差 120ms，
        // 落在容差内被配上，写出时按原文行的 00:17.02）。改这里之前先想清楚：
        // 「保住平台轨的原始毫秒」和「让文件可反复重写不出漂移」只能选一个。
        assertEquals(first, second)

        // 归一之后语义一点都不能丢，否则每次重搜歌词都会丢一层译文。
        val restored = LrcParser.parseDocument(first)
        assertEquals(2, restored.lines.size)
        assertEquals(listOf("原文一", "原文二"), restored.lines.map { it.text })
        assertEquals("translation one", restored.lines[0].translation)
        assertEquals("translation two", restored.lines[1].translation)
        assertEquals(500L, restored.fileOffsetMs)
        assertEquals("netease", restored.sourceProviderId)
        assertEquals("zh-Hans", restored.translationLang)
        assertTrue(restored.isMultiTrack)
    }

    @Test
    fun offsetTagFallsBackToPrivateForm() {
        val withStandard = "[offset:-250]\n[00:01.00]行"
        val withPrivate = "#@offset -250\n[00:01.00]行"
        val without = "[00:01.00]行"

        assertEquals(-250L, LrcParser.parseOffsetTag(withStandard))
        assertEquals(-250L, LrcParser.parseOffsetTag(withPrivate))
        assertEquals(0L, LrcParser.parseOffsetTag(without))
    }

    @Test
    fun standardOffsetWinsOverPrivateForm() {
        val both = "[offset:+400]\n#@offset -900\n[00:01.00]行"

        assertEquals(400L, LrcParser.parseOffsetTag(both))
    }

    @Test
    fun displayLinesKeepOrderAndSkipMissingTracks() {
        val line = LrcLine(
            chars = listOf(LrcChar("原", 0L), LrcChar("文", 0L)),
            startTimeMs = 0L,
            endTimeMs = 1_000L,
            translation = "译文",
            phonetic = "げんぶん",
        )

        assertEquals(
            listOf("原文", "げんぶん", "译文"),
            line.displayLines(showTranslation = true, showPhonetic = true),
        )
        // 只有一个开关打开时，另一条轨不能变成空行占位
        assertEquals(listOf("原文", "译文"), line.displayLines(true, false))
        assertEquals(listOf("原文", "げんぶん"), line.displayLines(false, true))
        assertEquals(listOf("原文"), line.displayLines(false, false))
    }

    @Test
    fun missingTracksProduceNoBlankLines() {
        val line = LrcLine(
            chars = listOf(LrcChar("只", 0L), LrcChar("有", 0L), LrcChar("原", 0L), LrcChar("文", 0L)),
            startTimeMs = 0L,
            endTimeMs = 1_000L,
        )

        val displayed = line.displayLines(showTranslation = true, showPhonetic = true)

        assertEquals(listOf("只有原文"), displayed)
        assertNotNull(displayed.firstOrNull())
        assertTrue(displayed.none { it.isBlank() })
    }
}
