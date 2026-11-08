package com.suisheng.player.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * per-song 歌词键的回归测试（`data/PerSongLyricPrefs.songKey`）。
 *
 * 只测纯函数部分：`songKey` 不碰 Android，所以能在 JVM 上断言「同一个文件永远同一个键、
 * 不同文件不会撞在一起」。键一旦不稳定，用户调好的歌词偏移就会在换曲后丢失或者串到别的歌上，
 * 而这两个现象在真机上都要靠耳朵才能发现。
 */
class PerSongLyricPrefsTest {

    @Test
    fun sameSongAlwaysProducesSameKey() {
        val a = PerSongLyricPrefs.songKey("/music/a.mp3", "A", "S")
        val b = PerSongLyricPrefs.songKey("/music/a.mp3", "A", "S")

        assertEquals(a, b)
        // 锁死具体值：算法被改动（例如换成 hashCode）时立刻红
        assertEquals("6346e07bac2d6298", a)
    }

    @Test
    fun differentSongsProduceDifferentKeys() {
        val a = PerSongLyricPrefs.songKey("/music/a.mp3", "A", "S")
        val b = PerSongLyricPrefs.songKey("/music/b.mp3", "B", "S")

        assertNotEquals(a, b)
    }

    @Test
    fun keyIsSixteenHexChars() {
        val key = PerSongLyricPrefs.songKey("/music/a.mp3", "A", "S")

        assertEquals(16, key.length)
        assertTrue(key.all { it in "0123456789abcdef" })
    }

    /**
     * 元数据参与哈希：同一个文件被改了标题 / 歌手，键会变。
     *
     * 这是**刻意**的：键变了表现为「偏移回到 +0.0s」，用户能一眼发现并重新校准；
     * 反过来（键不变、沿用旧偏移）会把上一版歌词的校准错误地套到新歌词上。
     */
    @Test
    fun metadataChangeChangesKey() {
        val original = PerSongLyricPrefs.songKey("/music/a.mp3", "A", "S")
        val renamed = PerSongLyricPrefs.songKey("/music/a.mp3", "A2", "S")
        val otherArtist = PerSongLyricPrefs.songKey("/music/a.mp3", "A", "S2")

        assertNotEquals(original, renamed)
        assertNotEquals(original, otherArtist)
    }

    @Test
    fun clampsAndRangesAreConsistent() {
        // 滑杆范围必须落在硬上限之内，否则 UI 能拖到会被钳掉的位置
        assertEquals(60_000L, PerSongLyricPrefs.MAX_OFFSET_MS)
        assertTrue(PerSongLyricPrefs.SLIDER_RANGE_MS <= PerSongLyricPrefs.MAX_OFFSET_MS)
        assertTrue(PerSongLyricPrefs.SLIDER_STEP_MS > 0L)
        assertEquals(60_000L, com.suisheng.player.playback.LyricTimebase.MAX_USER_OFFSET_MS)
    }
}
