package com.suisheng.player.model

import com.suisheng.player.util.PinyinIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 曲库排序口径的回归测试（`model/LibrarySort.kt`）。
 *
 * 锁的是**口径**而不是实现：列表顺序必须与 `AlphabetIndexBar` 的 28 格轴同一套定义，
 * 否则「点轴上某个字母」会跳到错误位置。这一类 bug 在车机上要重扫整个曲库才能复现一次，
 * 所以全部放在 JVM 上跑。
 *
 * 用例与线上故障的对应关系：
 *  - `七` U+4E03 / `万` U+4E07 码位只差 4：按 Unicode 排时 Q 段末尾紧邻 W 段开头，
 *    点 Q 跳过去，下一行就是「万物不如你」—— 这就是用户报的「按 Q 跳到 W 的歌」；
 *  - 歌曲页原来把汉字全排在 ASCII 之后，拼音轴上的 A..Z 实际只能跳到英文歌。
 */
class LibrarySortTest {

    private fun song(title: String, artist: String = "A"): Song = Song(
        id = title.hashCode().toLong(),
        title = title,
        artist = artist,
        path = "/music/$title.mp3",
        lrcPath = null,
    )

    private fun group(title: String, key: String = title): LibraryGroup =
        LibraryGroup(key = key, title = title, songs = listOf(song(title)))

    // ------------------------------------------------------------ 轴口径

    @Test
    fun letterOf_mapsChineseTitlesToExpectedInitials() {
        assertEquals('W', PinyinIndex.letterOf("万物不如你"))
        assertEquals('Q', PinyinIndex.letterOf("七里香"))
        assertEquals('A', PinyinIndex.letterOf("阿里山的姑娘"))
        assertEquals('Z', PinyinIndex.letterOf("葬花吟"))
        // 「未知专辑」这个名字本身会被算成 W —— 专辑页 `rebuildAlbumIndex()` 必须显式跳过它，
        // 否则会出现「W 的下标排在 Z 之后」的非单调映射。
        assertEquals('W', PinyinIndex.letterOf("未知专辑"))
    }

    @Test
    fun rankOf_matchesAxisSlot() {
        // rankOf 必须与 AXIS 下标一字不差，否则轴上第 N 格和列表第 N 段对不上
        for (letter in PinyinIndex.AXIS) {
            assertEquals(PinyinIndex.AXIS.indexOf(letter), LibrarySort.rankOf(letter.toString()))
        }
        assertEquals(PinyinIndex.AXIS.indexOf('W'), LibrarySort.rankOf("万物不如你"))
        assertEquals(PinyinIndex.AXIS.indexOf('#'), LibrarySort.rankOf(""))
        assertEquals(PinyinIndex.AXIS.indexOf('#'), LibrarySort.rankOf(null))
        assertEquals(PinyinIndex.AXIS.indexOf('#'), LibrarySort.rankOf("   "))
    }

    // ------------------------------------------------------------ 分段

    @Test
    fun byName_numbersLandInZeroSegment() {
        val sorted = listOf("Beyond", "2012", "7 月", "万物不如你").sortedWith(LibrarySort.BY_NAME)
        assertEquals("2012", sorted[0])
        assertEquals("7 月", sorted[1])
        assertEquals(LibrarySort.rankOf("2012"), LibrarySort.rankOf("7 月"))
        assertTrue(LibrarySort.rankOf("2012") < LibrarySort.rankOf("Beyond"))
    }

    @Test
    fun byName_symbolsLandInHashSegment() {
        // 只覆盖 BMP 符号：Star 0x2605、Ellipsis 0x2026 都 < 0x4E00，走 letterOf 的 '#' 分支。
        assertEquals('#', PinyinIndex.letterOf("★"))
        assertEquals('#', PinyinIndex.letterOf("…"))
        assertEquals('#', PinyinIndex.letterOf("《起风了》"))
        // 已知局限（不在本次改动范围）：`PinyinIndex.letterOf` 按 Char 扫描，
        // 星平面字符（emoji 等代理对）首字符码位 ≥ 0x4E00，会掉进 chineseLetter 的尾段（Z）。
        // 这里不做断言，只留说明，避免把既有行为固化成"正确"。
        val sorted = listOf("葬花吟", "★", "万物不如你").sortedWith(LibrarySort.BY_NAME)
        assertEquals(listOf("万物不如你", "葬花吟", "★"), sorted)
    }

    @Test
    fun byName_emptyTitleSortsAfterEverything() {
        val sorted = listOf("", "Zeta", "   ").sortedWith(LibrarySort.BY_NAME)
        assertEquals("Zeta", sorted.first())
        assertTrue(sorted.last().isBlank())
    }

    // ------------------------------------------------------------ 故障复现

    @Test
    fun byName_breaksTheCodepointTrapBetweenQiAndWan() {
        // 阿里山(A) / 万物不如你(W) / 葬花吟(Z)：
        // 按 Unicode 排是「万物(0x4E07) → 葬花(0x846C) → 阿里(0x963F)」，
        // 按拼音排才是 A → W → Z。这一组能把两套口径的差别钉死。
        val byPinyin = listOf("葬花吟", "万物不如你", "阿里山的姑娘")
            .sortedWith(LibrarySort.BY_NAME)
        assertEquals(listOf("阿里山的姑娘", "万物不如你", "葬花吟"), byPinyin)
    }

    @Test
    fun byName_isMonotonicWhenChineseIsMixedWithLatin() {
        val titles = listOf(
            "晴日共剪窗", "Beyond", "万物不如你", "2012", "七里香", "★", "Adele", "葬花吟",
        )
        val ranks = titles.sortedWith(LibrarySort.BY_NAME).map { LibrarySort.rankOf(it) }
        assertEquals("rank 必须非递减：${titles.sortedWith(LibrarySort.BY_NAME)}", ranks.sorted(), ranks)
    }

    @Test
    fun byName_keepsSameLetterContiguous() {
        // 段连续是「字母 → 首下标」映射单调的充分条件，必须显式锁住
        val titles = listOf(
            "Adele", "阿里山的姑娘", "七里香", "祁连山", "万物不如你", "晚风", "葬花吟", "光年之外",
        )
        val sorted = titles.sortedWith(LibrarySort.BY_NAME)
        val seen = HashSet<Int>()
        var previous = -1
        for (title in sorted) {
            val rank = LibrarySort.rankOf(title)
            if (rank != previous) {
                assertTrue("段 $rank 被切开了：$sorted", seen.add(rank))
                previous = rank
            }
        }
    }

    @Test
    fun byName_placesMixedChineseAndLatinOfSameLetterTogether() {
        // 「Adele」和「阿里山的姑娘」同属 A 段，必须相邻（中间不能夹别的段）
        val sorted = listOf("葬花吟", "阿里山的姑娘", "Adele", "万物不如你").sortedWith(LibrarySort.BY_NAME)
        assertEquals(listOf("Adele", "阿里山的姑娘", "万物不如你", "葬花吟"), sorted)
        assertEquals(LibrarySort.rankOf("Adele"), LibrarySort.rankOf("阿里山的姑娘"))
    }

    @Test
    fun byName_agreesWithAxisForPolyphonicCharacters() {
        // 多音字只影响「同一段内部的次序」，绝不能让段不连续 —— 轴上的字母必须仍然成立。
        // 重庆(chóng/zhòng)、重阳、长亭/长江 都是典型：letterOf 走 Collator，口径与轴一致。
        val titles = listOf("重庆", "重阳", "长亭外", "长江之歌", "万物不如你")
        for (title in titles) {
            assertEquals(
                PinyinIndex.AXIS.indexOf(PinyinIndex.letterOf(title)),
                LibrarySort.rankOf(title),
            )
        }
        val ranks = titles.sortedWith(LibrarySort.BY_NAME).map { LibrarySort.rankOf(it) }
        assertEquals(ranks.sorted(), ranks)
    }

    // ------------------------------------------------------------ 大小写

    @Test
    fun byName_isCaseInsensitiveForRankButDeterministicForOrder() {
        assertEquals(LibrarySort.rankOf("beyond"), LibrarySort.rankOf("Beyond"))
        // Collator.SECONDARY 把两者判等，由第三键裸字符串决定：'B' 0x42 < 'b' 0x62
        val sorted = listOf("beyond", "Beyond").sortedWith(LibrarySort.BY_NAME)
        assertEquals(listOf("Beyond", "beyond"), sorted)
        // 大小写混排不会跑到别的段去：主键相同，只在段内分先后
        assertTrue(LibrarySort.BY_NAME.compare("ALPHA", "alpha") < 0)
        assertTrue(LibrarySort.BY_NAME.compare("alpha", "ALPHA") > 0)
    }

    // ------------------------------------------------------------ 分组 / 歌曲

    @Test
    fun byGroup_putsUnknownBucketLastEvenThoughItsLabelLooksLikeW() {
        val groups = listOf(
            group("未知专辑", LibraryGrouper.UNKNOWN_KEY),
            group("Zeta"),
            group("万物不如你"),
        )
        val sorted = groups.sortedWith(LibrarySort.BY_GROUP)
        assertEquals(listOf("万物不如你", "Zeta", "未知专辑"), sorted.map { it.title })
        assertEquals(LibraryGrouper.UNKNOWN_KEY, sorted.last().key)
        // 反例：不加 UNKNOWN 主键时，「未知专辑」会按 W 排到 Zeta 前面 ——
        // 这正是专辑页 `rebuildAlbumIndex()` 必须过滤 UNKNOWN 的原因。
        val byNameOnly = groups.sortedWith(
            Comparator<LibraryGroup> { a, b -> LibrarySort.BY_NAME.compare(a.title, b.title) }
        )
        assertEquals(listOf("万物不如你", "未知专辑", "Zeta"), byNameOnly.map { it.title })
    }

    @Test
    fun bySongTitle_ordersSongsByTitlePinyin() {
        val songs = listOf(song("万物不如你"), song("七里香"), song("Beyond"), song("2012"))
        val sorted = songs.sortedWith(LibrarySort.BY_SONG_TITLE)
        assertEquals(listOf("2012", "Beyond", "七里香", "万物不如你"), sorted.map { it.title })
        // 倒序（TITLE_DESC）用同一个比较器整体反转，段序随之整体反转
        val reversed = songs.sortedWith(LibrarySort.BY_SONG_TITLE.reversed())
        assertEquals(sorted.map { it.title }.reversed(), reversed.map { it.title })
    }

    // ------------------------------------------------------------ 严格弱序

    @Test
    fun byName_obeysStrictWeakOrdering() {
        // 第三键 `compareTo` 存在的唯一理由：Collator 会把 ALPHA / Alpha 判等，
        // 判等项没有确定次序时 `sortedWith` 会抛
        // IllegalArgumentException: Comparison method violates its general contract.
        val samples = listOf(
            "万物不如你", "七里香", "Beyond", "beyond", "ALPHA", "Alpha", "alpha",
            "2012", "7 月", "★", "…", "", "   ", "未知专辑", "重庆", "重阳",
            "晴日共剪窗", "《起风了》", "葬花吟", "阿里山的姑娘", "Take Me Home",
        )
        val comparator = LibrarySort.BY_NAME
        for (a in samples) {
            for (b in samples) {
                val forward = comparator.compare(a, b)
                val backward = comparator.compare(b, a)
                assertEquals(
                    "反对称性被破坏：'$a' vs '$b'",
                    Integer.signum(forward),
                    -Integer.signum(backward),
                )
                if (forward == 0) {
                    assertEquals("判等项必须完全相同：'$a' vs '$b'", a, b)
                }
            }
        }
        for (a in samples) {
            for (b in samples) {
                if (comparator.compare(a, b) > 0) continue
                for (c in samples) {
                    if (comparator.compare(b, c) > 0) continue
                    assertTrue(
                        "传递性被破坏：'$a' <= '$b' <= '$c'",
                        comparator.compare(a, c) <= 0,
                    )
                }
            }
        }
    }

    @Test
    fun byName_distinguishesTitlesThatOnlyDifferInPunctuation() {
        // 判等项必须是**完全相等**的字符串，否则去重 / indexOf 之类的依赖会错
        assertTrue(LibrarySort.BY_NAME.compare("起风了", "起风了 ") != 0)
        assertEquals(0, LibrarySort.BY_NAME.compare("起风了", "起风了"))
    }
}
