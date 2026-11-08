package com.suisheng.player.playback

import com.suisheng.player.lrc.LyricDocument
import com.suisheng.player.model.LibraryKind
import com.suisheng.player.model.LrcLine
import com.suisheng.player.model.LyricState
import com.suisheng.player.model.PlaybackMode
import com.suisheng.player.model.Song
import java.util.concurrent.CopyOnWriteArrayList

object PlaybackStateHolder {
    private val listeners = CopyOnWriteArrayList<Listener>()

    var songs: List<Song> = emptyList()
        private set
    var currentIndex: Int = -1
        private set
    var isPlaying: Boolean = false
        private set
    var positionMs: Long = 0L
        private set
    var durationMs: Long = 0L
        private set
    var lrcLines: List<LrcLine> = emptyList()
        private set
    var coverArtPath: String? = null
        private set
    var playMode: PlaybackMode = PlaybackMode.SHUFFLE
        private set
    var activeLibrary: LibraryKind = LibraryKind.MUSIC
        private set

    val currentSong: Song?
        get() = songs.getOrNull(currentIndex)

    /**
     * 歌词时间轴换算（偏移的唯一注入点）。
     *
     * 四条歌词链里「按行取状态」的（悬浮歌词、媒体会话元数据）从这里拿；
     * 直接吃原始进度的两条（播放页滚动歌词、仪表投屏）在读的时候取
     * [LyricTimebase.effectivePositionMs]，不要自己在外面加偏移。
     */
    val timebase = LyricTimebase()

    val lyricState: LyricState
        get() = timebase.lyricState

    interface Listener {
        fun onPlaybackChanged(song: Song?, playing: Boolean, positionMs: Long, lines: List<LrcLine>)
        fun onPlayModeChanged(mode: PlaybackMode) {}
        fun onPlaylistChanged(songs: List<Song>) {}
        fun onCoverChanged(coverPath: String?) {}
        fun onDurationChanged(durationMs: Long) {}
    }

    fun addListener(listener: Listener) {
        listeners.add(listener)
        listener.onPlayModeChanged(playMode)
        listener.onPlaylistChanged(songs)
        listener.onCoverChanged(coverArtPath)
        listener.onDurationChanged(durationMs)
    }

    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    fun setPlaylist(list: List<Song>, startIndex: Int = 0, library: LibraryKind = activeLibrary) {
        songs = list
        activeLibrary = library
        currentIndex = startIndex.coerceIn(0, (list.size - 1).coerceAtLeast(0))
        listeners.forEach { it.onPlaylistChanged(list) }
        notify()
    }

    fun setActiveLibrary(library: LibraryKind) {
        activeLibrary = library
    }

    /**
     * 整体替换播放队列并同步当前项（清空队列时传 `emptyList()` + `-1`）。
     *
     * 与 [setPlaylist] 的唯一区别：**不动 `activeLibrary`** —— 队列内容变了，但用户仍停在同一
     * 个媒体库里，把 activeLibrary 一起改掉会让「上次播放位置」「队列落盘分档」串到别的库。
     * 会触发 [Listener.onPlaylistChanged]，随后统一触发一次 [Listener.onPlaybackChanged]。
     */
    fun setQueue(list: List<Song>, startIndex: Int) {
        songs = list
        currentIndex = if (list.isEmpty()) -1 else startIndex.coerceIn(0, list.lastIndex)
        listeners.forEach { it.onPlaylistChanged(list) }
        notify()
    }

    /**
     * 队列内部变动（增删 / 排序）后同步并通知，语义同 [setQueue]。
     *
     * 单独留一个名字是为了让调用点读起来就是「队列变了一条」，而不是「换了一个队列」；
     * 两者的状态写入规则必须保持一致，改一个记得改另一个。
     */
    fun notifyQueueChanged(list: List<Song>, startIndex: Int) {
        songs = list
        currentIndex = if (list.isEmpty()) -1 else startIndex.coerceIn(0, list.lastIndex)
        listeners.forEach { it.onPlaylistChanged(list) }
        notify()
    }

    /** 切歌时只更新索引，避免 2000 首歌列表反复触发 UI 全量刷新导致车机 ANR/闪退 */
    fun setCurrentIndex(index: Int) {
        if (songs.isEmpty()) return
        val newIndex = index.coerceIn(0, songs.lastIndex)
        if (newIndex == currentIndex) return
        currentIndex = newIndex
        notify()
    }

    fun setPlayMode(mode: PlaybackMode) {
        playMode = mode
        listeners.forEach { it.onPlayModeChanged(mode) }
    }

    /**
     * 歌词加载完成时由加载链路调用：写入多轨文档（行列表 + 文件内偏移）。
     *
     * 与 [update] 的分工：这里负责「换了一份歌词」，[update] 只负责「进度走到了哪」。
     */
    fun setLyricDocument(document: LyricDocument) {
        timebase.setDocument(document)
        lrcLines = document.lines
        notify()
    }

    fun updateSongLrcPath(songPath: String, lrcPath: String) {
        val idx = songs.indexOfFirst { it.path == songPath }
        if (idx < 0) return
        val updated = songs.toMutableList()
        updated[idx] = updated[idx].copy(lrcPath = lrcPath)
        songs = updated
    }

    fun setCoverArt(path: String?) {
        coverArtPath = path
        listeners.forEach { it.onCoverChanged(path) }
    }

    fun setDuration(durationMs: Long) {
        if (this.durationMs == durationMs) return
        this.durationMs = durationMs
        listeners.forEach { it.onDurationChanged(durationMs) }
    }

    fun update(song: Song?, playing: Boolean, positionMs: Long, lines: List<LrcLine>, durationMs: Long = this.durationMs) {
        isPlaying = playing
        this.positionMs = positionMs
        // 时间轴只换行列表、保留文件偏移（偏移由 setLyricDocument 单独写）；
        // 引用没变就不动，避免每帧都做一次列表赋值。
        if (lines !== lrcLines) {
            lrcLines = lines
            timebase.setLines(lines)
        }
        timebase.onRawPosition(positionMs)
        if (durationMs > 0) this.durationMs = durationMs
        // This runs ~8 times per second. Scanning the whole library here (the old
        // behaviour) is O(n) per tick — with a few thousand songs that is tens of
        // thousands of string comparisons per second on the main thread, which is
        // exactly the kind of stall a head unit turns into an ANR/"flash close".
        // Compare against the current entry first and only scan on a real change.
        if (song != null && currentSong?.path != song.path) {
            val idx = songs.indexOfFirst { it.path == song.path }
            if (idx >= 0) currentIndex = idx
        }
        notify(song)
    }

    private fun notify(song: Song? = currentSong) {
        listeners.forEach { it.onPlaybackChanged(song, isPlaying, positionMs, lrcLines) }
    }
}
