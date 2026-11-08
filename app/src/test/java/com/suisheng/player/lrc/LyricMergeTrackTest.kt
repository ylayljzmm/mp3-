package com.suisheng.player.lrc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 附加轨合并的回归测试（`LrcParser.mergeTranslation` / `mergePhonetic`）。
 *
 * 这一组锁的是**需求 4「歌词翻译」的根因修复**：
 * 平台接口给的译文是一份独立的完整 `[mm:ss.xx]译文` 文本，旧实现把它当"纯文本正文"
 * 交给 `fromPlainText()`，结果是译文被当成歌词、还配了一条 5 秒一行的假时间轴。
 * 现在译文必须按时间戳挂到原文行上，对不上的行留空而不是硬套。
 */
class LyricMergeTrackTest {

    private val baseLrc = """
        [00:12.34]原文一
        [00:17.02]原文二
        [00:21.50]原文三
    """.trimIndent()

    @Test
    fun translationAlignsByTimestamp() {
        val doc = LrcParser.parseDocument(baseLrc)
        val merged = LrcParser.mergeTranslation(
            doc,
            "[00:12.34]translation one\n[00:17.02]translation two",
        )
        assertEquals("translation one", merged.lines[0].translation)
        assertEquals("translation two", merged.lines[1].translation)
        assertNull(merged.lines[2].translation)
        // 原文行本身一个字都不能动
        assertEquals(listOf("原文一", "原文二", "原文三"), merged.lines.map { it.text })
        assertTrue(merged.hasTranslation)
        assertEquals(LyricDocument.VERSION_MULTI_TRACK, merged.formatVersion)
    }

    @Test
    fun toleranceAcceptsRoundingDrift() {
        val doc = LrcParser.parseDocument(baseLrc)
        // 平台译文轨常和原文轨差几十毫秒（取整），这里正好顶到 120ms 容差边界。
        val merged = LrcParser.mergeTranslation(doc, "[00:12.46]边界内\n[00:17.14]也在容差内")
        assertEquals("边界内", merged.lines[0].translation)
        assertEquals("也在容差内", merged.lines[1].translation)
    }

    @Test
    fun outOfToleranceIsDroppedSilently() {
        val doc = LrcParser.parseDocument(baseLrc)
        // 13.00 与 12.34 差 660ms，已不是取整问题；错配比没有更糟。
        val merged = LrcParser.mergeTranslation(doc, "[00:13.00]不该出现")
        assertNull(merged.lines[0].translation)
        assertTrue(merged.lines.all { it.translation == null })
    }

    @Test
    fun blankTranslationKeepsDocument() {
        val doc = LrcParser.parseDocument(baseLrc)
        assertEquals(doc, LrcParser.mergeTranslation(doc, null))
        assertEquals(doc, LrcParser.mergeTranslation(doc, "   "))
    }

    @Test
    fun phoneticUsesSameAlignment() {
        val doc = LrcParser.parseDocument(baseLrc)
        val merged = LrcParser.mergePhonetic(
            doc,
            "[00:12.34]yuan wen yi\n[00:21.50]yuan wen san",
            lang = "romalrc",
        )
        assertEquals("yuan wen yi", merged.lines[0].phonetic)
        assertNull(merged.lines[1].phonetic)
        assertEquals("yuan wen san", merged.lines[2].phonetic)
        assertEquals("romalrc", merged.phoneticLang)
        assertTrue(merged.hasPhonetic)
    }

    @Test
    fun translationAndPhoneticStack() {
        val doc = LrcParser.parseDocument(baseLrc)
        val merged = LrcParser.mergePhonetic(
            LrcParser.mergeTranslation(doc, "[00:12.34]译文", lang = "zh-Hans"),
            "[00:12.34]roman",
            lang = "romalrc",
        )
        assertEquals("译文", merged.lines[0].translation)
        assertEquals("roman", merged.lines[0].phonetic)
        assertEquals("zh-Hans", merged.translationLang)
        assertEquals("romalrc", merged.phoneticLang)
    }

    @Test
    fun existingTranslationIsKept() {
        val doc = LrcParser.parseDocument(baseLrc)
        val first = LrcParser.mergeTranslation(doc, "[00:12.34]第一版")
        // 第二次只覆盖到第二行，第一行的旧译文应当保留。
        val second = LrcParser.mergeTranslation(first, "[00:17.02]第二版")
        assertEquals("第一版", second.lines[0].translation)
        assertEquals("第二版", second.lines[1].translation)
    }

    @Test
    fun translationSurvivesSerialize() {
        val doc = LrcParser.mergeTranslation(
            LrcParser.parseDocument(baseLrc),
            "[00:12.34]translation one",
            lang = "zh-Hans",
        )
        val restored = LrcParser.parseDocument(LrcParser.serialize(doc))
        assertEquals("translation one", restored.lines[0].translation)
        assertEquals("zh-Hans", restored.translationLang)
    }
}
