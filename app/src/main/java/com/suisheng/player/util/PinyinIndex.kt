package com.suisheng.player.util

import java.nio.charset.Charset
import java.util.concurrent.ConcurrentHashMap

/**
 * 轻量拼音首字母索引（方案 H §5.3）：不引第三方库、不用隐藏 API。
 *
 * 用 **GBK 码位阈值** 推出常用汉字声母；生僻字、符号、空标题归 `#`，数字归 `0`。
 * 首字母按字符缓存，重复曲库扫描不会反复做编码。
 *
 * ## 为什么不用 `java.text.Collator`（5.5.0 修正）
 *
 * 原实现是「拿 `Collator.getInstance(Locale.CHINA)` 和 23 个分界字逐个比较」。
 * 这个做法在**原理上就不可靠**：`Collator` 的中文排序规则**依赖运行时数据** ——
 * Android 上走 ICU 拼音序，桌面 JDK 上不保证；同一首歌在不同 ROM / 不同系统版本上
 * 会算出不同字母，索引轴就「点了跳错」。CI 上 `letterOf("万物不如你")` 返回 `#` 而不是 `W`
 * 就是这条的直接证据。
 *
 * 现在的做法利用编码标准本身：**GB2312/GBK 的一级汉字区（0xB0A1 ~ 0xD7F9）就是按拼音排序的**，
 * 取一级汉字区里每个声母的首字码位当阈值，取字母退化成纯整数比较 ——
 * 与平台、JDK、ICU 数据版本全部无关。
 *
 * 另外，原来的分界字串里的 `妸`、`拏`、`妑`、`嚓`、`哒`、`旮`、`噢` 根本不在 GB2312 一级汉字区，
 * 本来就不该当分界字用。
 */
object PinyinIndex {

    /** 索引轴 28 项：0 + A..Z + #。 */
    val AXIS: List<Char> = listOf('0') + ('A'..'Z').toList() + listOf('#')

    /** 23 个声母（I / U / V 无对应声母）。 */
    private const val LETTERS = "ABCDEFGHJKLMNOPQRSTWXYZ"

    /**
     * 23 个分界字在一级汉字区里的 **GBK 码位**，与 [LETTERS] 一一对应。
     *
     * 顺序（A..Z，跳过 I/U/V）：啊 芭 擦 搭 蛾 发 噶 哈 击 喀 垃 妈 拿 哦 啪 期 然 撒 塌 挖 昔 压 匝
     *
     * 每一个都是所属声母在 GB2312 一级汉字区里的首字，所以「`code >= 阈值`」等价于
     * 「拼音序不早于该声母」。码位严格递增，可直接线性扫描。
     */
    private val BOUNDARY_CODES = intArrayOf(
        0xB0A1, // 啊 A
        0xB0C5, // 芭 B
        0xB2C1, // 擦 C
        0xB4EE, // 搭 D
        0xB6EA, // 蛾 E
        0xB7A2, // 发 F
        0xB8C1, // 噶 G
        0xB9FE, // 哈 H
        0xBBF7, // 击 J
        0xBFA6, // 喀 K
        0xC0AC, // 垃 L
        0xC2E8, // 妈 M
        0xC4C3, // 拿 N
        0xC5B6, // 哦 O
        0xC5BE, // 啪 P
        0xC6DA, // 期 Q
        0xC8BB, // 然 R
        0xC8F6, // 撒 S
        0xCBFA, // 塌 T
        0xCDDA, // 挖 W
        0xCEF4, // 昔 X
        0xD1B9, // 压 Y
        0xD4D1, // 匝 Z
    )

    /** 用 GBK 不用 GB2312：GBK 是超集，两个平台都稳定支持，一级汉字区码位与 GB2312 完全相同。 */
    private val GBK: Charset = Charset.forName("GBK")

    private val cache = ConcurrentHashMap<Char, Char>()

    /**
     * 「字 → GBK 码位」缓存。
     *
     * 排序要走 [gbkCompare]，一首歌名里的每个字都会被比较多次；`String.toByteArray(GBK)`
     * 每次都要过一遍 CharsetEncoder，几千首的曲库累计起来在车机上很可观。
     * 码位是纯函数，缓存后每个新字只编码一次。曲库里不同字符数通常不到几千，占用可忽略。
     */
    private val codeCache = ConcurrentHashMap<Char, Int>()

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

    /**
     * 按 GBK 码位逐字比较：一级汉字区即拼音序，与平台无关。
     *
     * 用于「同一个字母段内部」的次序（段与段之间由 [letterOf] 的轴序号决定）。
     * 非 GBK 字符统一映射为 `-1`，因此 ASCII 恒在汉字之前、生僻字/符号恒在最后，
     * 结果确定且可传递（`Char → Int` 是函数映射，词法序必然满足弱序）。
     */
    fun gbkCompare(a: String, b: String): Int {
        val common = minOf(a.length, b.length)
        for (i in 0 until common) {
            val codeA = gbkCodeOf(a[i])
            val codeB = gbkCodeOf(b[i])
            if (codeA != codeB) return codeA.compareTo(codeB)
        }
        return a.length.compareTo(b.length)
    }

    /** 单字的 GBK 码位；非 GBK 字符（ASCII 之外不可映射的符号、代理对、生僻字）返回 -1。 */
    private fun gbkCodeOf(c: Char): Int {
        codeCache[c]?.let { return it }
        val code = if (c.code < 0x80) {
            c.code
        } else {
            val bytes = c.toString().toByteArray(GBK)
            // 不可映射时 JDK 会替换成单个 '?'，长度不是 2 —— 这类字符一律沉到 -1
            if (bytes.size != 2) -1 else ((bytes[0].toInt() and 0xFF) shl 8) or (bytes[1].toInt() and 0xFF)
        }
        codeCache[c] = code
        return code
    }

    private fun chineseLetter(c: Char): Char {
        val code = gbkCodeOf(c)
        if (code < 0) return '#'
        var index = -1
        for (i in BOUNDARY_CODES.indices) {
            if (code >= BOUNDARY_CODES[i]) index = i else break
        }
        return if (index < 0) '#' else LETTERS[index]
    }
}
