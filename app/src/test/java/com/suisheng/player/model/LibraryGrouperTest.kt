package com.suisheng.player.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 分组页数据层的回归测试。
 *
 * 这些用例锁的是**口径**而不是实现细节：空专辑归到「未知」且排在最后、
 * 文件夹只列直接子目录但计数按整棵子树、面包屑能一路回退到根、流媒体路径不进文件夹树。
 * 车机上这几种情况都能真实发生（打标签不全的 U 盘曲库、混合了在线歌曲的队列），
 * 但在车机上复现一次要重扫整个曲库，所以放在 JVM 上跑。
 */
class LibraryGrouperTest {

    private fun song(
        title: String,
        artist: String = "A",
        album: String = "",
        path: String = "/music/$title.mp3",
    ): Song = Song(
        id = title.hashCode().toLong(),
        title = title,
        artist = artist,
        path = path,
        lrcPath = null,
        album = album,
    )

    @Test
    fun byAlbum_groupsSongsAndSortsByTitle() {
        val songs = listOf(
            song("1", album = "Beta"),
            song("2", album = "Alpha"),
            song("3", album = "Beta"),
        )
        val groups = LibraryGrouper.byAlbum(songs, unknownLabel = "未知专辑")

        assertEquals(listOf("Alpha", "Beta"), groups.map { it.title })
        assertEquals(2, groups[1].songs.size)
        assertEquals(1, groups[0].songs.size)
    }

    @Test
    fun byAlbum_blankAlbumGoesToUnknownBucketAndSortsLast() {
        val songs = listOf(
            song("1", album = ""),
            song("2", album = "Zeta"),
            song("3", album = "   "),
        )
        val groups = LibraryGrouper.byAlbum(songs, unknownLabel = "未知专辑")

        assertEquals("Zeta", groups.first().title)
        assertEquals("未知专辑", groups.last().title)
        assertEquals(LibraryGrouper.UNKNOWN_KEY, groups.last().key)
        assertEquals(2, groups.last().songs.size)
    }

    @Test
    fun byArtist_sortsCaseInsensitively() {
        val songs = listOf(
            song("1", artist = "beta"),
            song("2", artist = "Alpha"),
            song("3", artist = "ALPHA"),
        )
        val groups = LibraryGrouper.byArtist(songs, unknownLabel = "未知艺术家")

        // 5.5.0 起按拼音口径排序：Collator.SECONDARY 把 Alpha / ALPHA 判为同分，
        // 由第三键裸字符串 `compareTo` 决定次序（'L' 0x4C < 'l' 0x6C），所以 ALPHA 在前。
        assertEquals(listOf("ALPHA", "Alpha", "beta"), groups.map { it.title })
    }

    @Test
    fun commonRoot_findsDeepestSharedDirectory() {
        val base = File(System.getProperty("java.io.tmpdir"), "suisheng-library")
        val songs = listOf(
            song("a", path = File(File(File(base, "cd1"), "x"), "a.mp3").absolutePath),
            song("b", path = File(File(base, "cd2"), "b.mp3").absolutePath),
            song("c", path = File(base, "c.mp3").absolutePath),
        )
        assertEquals(base, LibraryGrouper.commonRoot(songs))
    }

    @Test
    fun commonRoot_isNullWhenNoRealFiles() {
        val songs = listOf(
            song("a", path = "content://media/external/audio/1"),
            song("b", path = "meta://online/track/1"),
            song("c", path = "https://example.com/radio.mp3"),
        )
        assertNull(LibraryGrouper.commonRoot(songs))
    }

    @Test
    fun folderChildren_listsOnlyDirectChildrenWithSubtreeCounts() {
        val base = File(System.getProperty("java.io.tmpdir"), "suisheng-folder")
        val disc1 = File(base, "disc1")
        val disc1Sub = File(disc1, "live")
        val songs = listOf(
            song("root", path = File(base, "root.mp3").absolutePath),
            song("d1", path = File(disc1, "d1.mp3").absolutePath),
            song("d1sub", path = File(disc1Sub, "d1sub.mp3").absolutePath),
            song("other", path = File(File(base, "disc2"), "other.mp3").absolutePath),
        )

        val children = LibraryGrouper.folderChildren(songs, base)

        assertEquals(listOf("disc1", "disc2"), children.map { it.title })
        // disc1 计数含 live 子目录里的歌
        assertEquals(2, children[0].songs.size)
        assertEquals(1, children[1].songs.size)
    }

    @Test
    fun folderChildren_ignoresSongsOutsideCurrentDirectory() {
        val base = File(System.getProperty("java.io.tmpdir"), "suisheng-outside")
        val sibling = File(base.parentFile, "suisheng-sibling")
        val songs = listOf(
            song("inside", path = File(File(base, "in"), "in.mp3").absolutePath),
            song("outside", path = File(File(sibling, "out"), "out.mp3").absolutePath),
        )

        val children = LibraryGrouper.folderChildren(songs, base)

        assertEquals(1, children.size)
        assertEquals("in", children[0].title)
    }

    @Test
    fun breadcrumb_walksFromRootDownToCurrent() {
        val base = File(System.getProperty("java.io.tmpdir"), "suisheng-crumb")
        val level1 = File(base, "a")
        val level2 = File(level1, "b")

        val crumbs = LibraryGrouper.breadcrumb(base, level2)

        assertEquals(listOf(base, level1, level2), crumbs)
        assertEquals(listOf("a", "b"), crumbs.drop(1).map { it.name })
    }

    @Test
    fun songsUnder_includesNestedDirectoriesOnly() {
        val base = File(System.getProperty("java.io.tmpdir"), "suisheng-under")
        val disc = File(base, "disc")
        val discLive = File(disc, "live")
        val songs = listOf(
            song("d", path = File(disc, "d.mp3").absolutePath),
            song("l", path = File(discLive, "l.mp3").absolutePath),
            song("o", path = File(File(base, "other"), "o.mp3").absolutePath),
        )

        val result = LibraryGrouper.songsUnder(songs, disc)

        assertEquals(listOf("d", "l"), result.map { it.title })
        assertTrue(LibraryGrouper.isAncestorOrSelf(disc, discLive))
    }

    // ------------------------------------------------ 隐藏文件夹（可见性口径）

    /** 直接写路径字符串、不经过 `absolutePath`：断言里就能对照肉眼可读的 expected。 */
    private fun hiddenDirs(vararg paths: String): List<File> = paths.map(::File)

    @Test
    fun visibleSongs_hidingParentDirectoryDropsWholeSubtree() {
        val songs = listOf(
            song("one", path = "/a/b/one.mp3"),
            song("two", path = "/a/b/deep/two.mp3"),
            song("three", path = "/a/c/three.mp3"),
        )

        val visible = LibraryGrouper.visibleSongs(songs, hiddenDirs("/a/b"))

        // 隐藏 /a/b 之后，只有同级的 /a/c 还在
        assertEquals("实际剩余=${visible.map { it.path }}", 1, visible.size)
        assertEquals(listOf("/a/c/three.mp3"), visible.map { it.path })
        // 子树深度不影响判定：/a/b/deep/ 里的歌同样被过滤
        assertFalse(visible.any { it.title == "two" })
    }

    @Test
    fun visibleSongs_prefixSiblingIsNotAffected() {
        val songs = listOf(
            song("insideB", path = "/a/b/x.mp3"),
            song("insideBC", path = "/a/bc/x.mp3"),
        )

        val visible = LibraryGrouper.visibleSongs(songs, hiddenDirs("/a/b"))

        // /a/bc 只是前缀相同，不是子路径
        assertEquals(listOf("/a/bc/x.mp3"), visible.map { it.path })
        assertEquals(1, visible.size)
    }

    @Test
    fun visibleSongs_siblingDirectoryIsNotAffected() {
        val songs = listOf(
            song("inB", path = "/a/b/x.mp3"),
            song("inC", path = "/a/c/x.mp3"),
        )

        val visible = LibraryGrouper.visibleSongs(songs, hiddenDirs("/a/b"))

        assertEquals(listOf("/a/c/x.mp3"), visible.map { it.path })
        assertEquals(1, visible.size)
    }

    @Test
    fun visibleSongs_returnsSameInstanceWhenNothingHidden() {
        val songs = listOf(
            song("a", path = "/a/b/one.mp3"),
            song("b", path = "/a/c/two.mp3"),
        )

        val visible = LibraryGrouper.visibleSongs(songs, emptyList())

        // 无隐藏项时必须短路：同一个引用，不产生新列表
        assertSame(songs, visible)
        assertEquals(2, visible.size)
    }

    @Test
    fun visibleSongs_neverFiltersContentUris() {
        val streamed = song("streamed", path = "content://media/external/audio/1")
        val songs = listOf(streamed)
        // 故意把根目录都塞进去：File 比较对 content:// 无意义，必须整体放行
        val dirs = hiddenDirs("/", "/a", "/sdcard/Music")

        assertFalse(LibraryGrouper.isHidden(streamed, dirs))
        val visible = LibraryGrouper.visibleSongs(songs, dirs)
        assertEquals(1, visible.size)
        assertEquals("content://media/external/audio/1", visible[0].path)
    }

    @Test
    fun visibleSongs_nestedHiddenEntriesAreIdempotent() {
        val songs = listOf(
            song("rootChild", path = "/a/x.mp3"),
            song("inB", path = "/a/b/y.mp3"),
            song("inBC", path = "/a/b/c/z.mp3"),
            song("outside", path = "/other/w.mp3"),
        )

        val onlyParent = LibraryGrouper.visibleSongs(songs, hiddenDirs("/a"))
        val parentAndChild = LibraryGrouper.visibleSongs(songs, hiddenDirs("/a", "/a/b"))

        // 同时传 /a 与 /a/b，结果必须与只传 /a 完全一致（子条目是冗余的）
        assertEquals(onlyParent.map { it.path }, parentAndChild.map { it.path })
        assertEquals(listOf("/other/w.mp3"), parentAndChild.map { it.path })
    }
}
