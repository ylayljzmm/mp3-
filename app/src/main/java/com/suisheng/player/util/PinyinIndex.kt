package com.suisheng.player.util

import java.text.Collator
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * 轻量拼音首字母索引（方案 H §5.3）：不引第三方库、不用隐藏 API。
 *
 * 用中文 [Collator] 与 23 个分界字比较得出常用汉字声母；生僻字、符号、空标题归 `#`，
 * 数字归 `0`。首字母按字符缓存，重复曲库扫描不会反复做 Collator 比较。
 */
object PinyinIndex {

    /** 索引轴 28 项：0 + A..Z + #。 */
    val AXIS: List<Char> = listOf('0') + ('A'..'Z').toList() + listOf('#')

    /** GB2312 一级汉字按拼音排序的 23 个分界字（I / U / V 无对应声母）。 */
    private const val BOUNDARY = "啊八嚓哒妸发旮哈讥咔垃妈拏噢妑七然仨他哇夕丫匝"
    private const val LETTERS = "ABCDEFGHJKLMNOPQRSTWXYZ"

    private val collator = Collator.getInstance(Locale.CHINA)
    private val cache = ConcurrentHashMap<Char, Char>()

    fun letterOf(title: String): Char {
        val c = title.trim().firstOrNull() ?: return '#'
        cache[c]?.let { return it }
        val result = when {
            c in 'A'..'Z' -> c
            c in 'a'..'z' -> c.uppercaseChar()
            c in '0'..'9' -> '0'
            c.code < 0x4E00 -> '#'
            else -> chineseLetter(c)
        }
        cache[c] = result
        return result
    }

    private fun chineseLetter(c: Char): Char {
        val s = c.toString()
        var index = -1
        for (i in BOUNDARY.indices) {
            if (collator.compare(s, BOUNDARY[i].toString()) >= 0) index = i else break
        }
        return if (index < 0) '#' else LETTERS[index]
    }
}
