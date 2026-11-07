package com.suisheng.player.model

import com.suisheng.player.util.PinyinIndex

/**
 * 曲库统一排序口径：**先按拼音首字母在索引轴上的序号，再按 GBK 码位（≈拼音序），
 * 最后按裸字符串**。
 *
 * 为什么需要这个类：`AlphabetIndexBar` 的轴是 `0 / A..Z / #` 的 28 等分，而列表原先按
 * Unicode 码位排（汉字全部落在 ASCII 之后），两套口径不一致，点轴跳转就会落到错误位置。
 * 这里把「排序键」与「轴序号」绑成同一套定义。
 *
 * 三条硬约束：
 * ① 同字母的条目必须**连续**（rank 是主键，天然满足）→ `AlphabetIndexBar` 的
 *    「字母 → 首次下标」映射才严格单调，轴上的 A..Z 才不会跳乱；
 * ② 未识别项（`null` 专辑名 / 歌手名）沉底，与 [LibraryGrouper.UNKNOWN_KEY] 的旧行为一致；
 * ③ 第三键 `l.compareTo(r)` 兜底保证**严格弱序**，否则 `sortedWith` 会抛
 *    `IllegalArgumentException: Comparison method violates its general contract`
 *    （第二键对「互不可映射的两个符号」会判等，判等项必须再给一个确定次序）。
 *
 * ## 为什么不用 `java.text.Collator`（5.5.0 修正）
 *
 * `Collator` 的中文排序规则依赖运行时数据：Android 走 ICU 拼音序，桌面 JDK 不保证。
 * 同一份曲库在两台机器上会排出不同顺序，甚至同一台机器换个系统版本就变。
 * 改用 [PinyinIndex.gbkCompare] 后，段内次序也是纯整数比较，与平台无关。
 */
object LibrarySort {

    /** 字母 → 轴序号（0 → 0，A..Z → 1..26，# → 27）。 */
    private val RANK_BY_LETTER: Map<Char, Int> =
        PinyinIndex.AXIS.withIndex().associate { (index, letter) -> letter to index }

    /**
     * 标题在索引轴上的序号。
     *
     * `letterOf` 已经保证「数字 → 0、字母 → 大写字母、生僻字/符号/emoji/空串 → #」，
     * 这里只做一次查表；轴里查不到时按 `#` 处理（与 `letterOf` 的兜底一致）。
     */
    fun rankOf(title: String?): Int =
        RANK_BY_LETTER[PinyinIndex.letterOf(title.orEmpty())] ?: PinyinIndex.AXIS.lastIndex

    /** 名称比较器（专辑名 / 歌手名 / 歌曲名通用）。 */
    val BY_NAME: Comparator<String> = Comparator { a, b ->
        val left = a.orEmpty()
        val right = b.orEmpty()
        val byRank = rankOf(left) - rankOf(right)
        if (byRank != 0) {
            byRank
        } else {
            val byCode = PinyinIndex.gbkCompare(left, right)
            if (byCode != 0) byCode else left.compareTo(right)
        }
    }

    /** 分组比较器：「未知专辑 / 未知艺术家」沉底（主键），其余同 [BY_NAME]。 */
    val BY_GROUP: Comparator<LibraryGroup> = Comparator { a, b ->
        val unknownA = if (a.key == LibraryGrouper.UNKNOWN_KEY) 1 else 0
        val unknownB = if (b.key == LibraryGrouper.UNKNOWN_KEY) 1 else 0
        if (unknownA != unknownB) unknownA - unknownB else BY_NAME.compare(a.title, b.title)
    }

    /** 歌曲比较器：主键是标题拼音序；`Song::title` 放第二参数位，作为同分时的稳定次序。 */
    val BY_SONG_TITLE: Comparator<Song> = compareBy(BY_NAME, Song::title)
}
