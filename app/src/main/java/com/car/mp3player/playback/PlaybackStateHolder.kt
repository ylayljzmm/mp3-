package com.car.mp3player.playback

import com.car.mp3player.model.LibraryKind
import com.car.mp3player.model.LrcLine
import com.car.mp3player.model.LyricState
import com.car.mp3player.model.PlaybackMode
import com.car.mp3player.model.Song
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

    val lyricState: LyricState
        get() = com.car.mp3player.lrc.LrcParser.findState(lrcLines, positionMs)

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
        lrcLines = lines
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
