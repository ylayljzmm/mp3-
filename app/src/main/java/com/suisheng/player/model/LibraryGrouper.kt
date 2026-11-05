package com.suisheng.player.model

import java.io.File

/**
 * 分组页的数据层（`design/04 §3.1–§3.2` 的「专辑 / 艺术家 / 文件夹」三个目的地）。
 *
 * 刻意做成**纯 Kotlin、零 Android 依赖**的对象：
 * ① 三个页面共用同一套分组逻辑，页面只负责「怎么画」，不负责「怎么算」；
 * ② 可以进单元测试 —— 车机上很难复现的分组边界（空专辑名、路径跨越、面包屑回退）
 *    在 JVM 上几行断言就能锁住。
 *
 * 路径过滤的口径：只认「真实文件路径」。`content://`（SAF）、`meta://`（在线占位）、
 * `http(s)://`（电台/播客流）都不参与文件夹浏览，否则会造出一堆假目录。
 */
object LibraryGrouper {

    /** 专辑名/歌手名为空时归到这个桶，保证「有歌但没标签」不会凭空消失。 */
    const val UNKNOWN_KEY = "\u0000unknown"

    /** 专辑墙。空专辑名归到 [unknownLabel]，并排在最后。 */
    fun byAlbum(songs: List<Song>, unknownLabel: String): List<LibraryGroup> =
        groupBy(songs, { it.album }, unknownLabel)

    /** 艺术家列表。 */
    fun byArtist(songs: List<Song>, unknownLabel: String): List<LibraryGroup> =
        groupBy(songs, { it.artist }, unknownLabel)

    /**
     * 文件夹当前层的子文件夹。
     *
     * 只返回 [currentDir] 的**直接**子目录，且每个子目录带的是它**整棵子树**的歌曲
     * ——这样「播放本文件夹」和「点进去看到 N 首」永远是同一个口径。
     * 直接躺在 [currentDir] 里的歌不生成条目（它们由「播放本文件夹」覆盖）。
     */
    fun folderChildren(songs: List<Song>, currentDir: File): List<LibraryGroup> {
        val buckets = LinkedHashMap<File, MutableList<Song>>()
        songs.forEach { song ->
            val dir = parentDir(song) ?: return@forEach
            if (dir == currentDir) return@forEach
            if (!isAncestorOrSelf(currentDir, dir)) return@forEach
            val child = childUnder(currentDir, dir) ?: return@forEach
            buckets.getOrPut(child) { mutableListOf() }.add(song)
        }
        return buckets
            .map { (dir, list) ->
                LibraryGroup(key = dir.absolutePath, title = dir.name, songs = list)
            }
            .sortedBy { it.title.lowercase() }
    }

    /** [dir] 及其整棵子树里的歌（「播放本文件夹」的队列）。 */
    fun songsUnder(songs: List<Song>, dir: File): List<Song> = songs.filter { song ->
        parentDir(song)?.let { isAncestorOrSelf(dir, it) } == true
    }

    /**
     * 全部歌曲路径的最深公共父目录，作为文件夹浏览的根。
     * 没有可浏览的本地文件时返回 null（页面显示空态）。
     */
    fun commonRoot(songs: List<Song>): File? {
        val dirs = songs.mapNotNull { parentDir(it) }.distinct()
        if (dirs.isEmpty()) return null
        var root: File = dirs.first()
        for (index in 1 until dirs.size) {
            root = commonAncestor(root, dirs[index]) ?: return null
        }
        return root
    }

    /** 面包屑：[root] 到 [current] 的每一级（含首尾）。[current] 不在 [root] 之下时只返回 current。 */
    fun breadcrumb(root: File, current: File): List<File> {
        val chain = ArrayDeque<File>()
        var cursor: File? = current
        while (cursor != null) {
            chain.addFirst(cursor)
            if (cursor == root) break
            cursor = cursor.parentFile
        }
        return chain.toList()
    }

    /** 是否 [target] 自身或位于 [dir] 之下。 */
    fun isAncestorOrSelf(dir: File, target: File): Boolean {
        var cursor: File? = target
        while (cursor != null) {
            if (cursor == dir) return true
            cursor = cursor.parentFile
        }
        return false
    }

    // ---------------------------------------------------------------- 内部

    private fun groupBy(
        songs: List<Song>,
        selector: (Song) -> String,
        unknownLabel: String,
    ): List<LibraryGroup> {
        val buckets = LinkedHashMap<String, MutableList<Song>>()
        songs.forEach { song ->
            val raw = selector(song).trim()
            val key = if (raw.isEmpty()) UNKNOWN_KEY else raw
            buckets.getOrPut(key) { mutableListOf() }.add(song)
        }
        return buckets
            .map { (key, list) ->
                LibraryGroup(
                    key = key,
                    title = if (key == UNKNOWN_KEY) unknownLabel else key,
                    songs = list,
                )
            }
            // 未识别的一律沉到最后，正常专辑/歌手按名字不区分大小写升序
            .sortedWith(compareBy({ it.key == UNKNOWN_KEY }, { it.title.lowercase() }))
    }

    /** 歌曲所在目录；不是真实文件路径（含 `://`）就返回 null。 */
    private fun parentDir(song: Song): File? {
        val path = song.path
        if (path.isBlank() || path.contains("://")) return null
        return File(path).parentFile
    }

    private fun commonAncestor(a: File, b: File): File? {
        var cursor: File? = a
        while (cursor != null) {
            if (isAncestorOrSelf(cursor, b)) return cursor
            cursor = cursor.parentFile
        }
        return null
    }

    /** [target] 在 [parent] 之下的那一层子目录。 */
    private fun childUnder(parent: File, target: File): File? {
        var cursor: File? = target
        var child: File? = null
        while (cursor != null) {
            if (cursor == parent) return child
            child = cursor
            cursor = cursor.parentFile
        }
        return null
    }
}
