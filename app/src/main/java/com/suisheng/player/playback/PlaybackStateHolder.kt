package com.suisheng.player.playback

import com.suisheng.player.lrc.LyricDocument
import com.suisheng.player.model.LibraryKind
import com.suisheng.player.model.LrcLine
import com.suisheng.player.model.LyricState
import com.suisheng.player.model.PlaybackMode
import com.suisheng.player.model.Song
import java.util.concurrent.CopyOnWriteArrayList

/** 封面重载的持久状态；成功 / 失败是一次性事件，不落在这里。 */
enum class CoverReloadState { IDLE, LOADING }

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

    var coverReloadState: CoverReloadState = CoverReloadState.IDLE
        private set

    /** 封面重载成功后要额外失效的缓存（P2 可插拔 hook，当前无注册者）。 */
    private val coverCacheInvalidators = CopyOnWriteArrayList<(Song) -> Unit>()

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

        /** 封面重载的持久状态变化；新建 Listener 时也会补发一次。 */
        fun onCoverReloadStateChanged(state: CoverReloadState) {}

        /** 封面重载的一次性结果（成功 / 失败），不补发，避免页面重建后重复弹提示。 */
        fun onCoverReloadResult(success: Boolean) {}
    }

    fun addListener(listener: Listener) {
        listeners.add(listener)
        listener.onPlayModeChanged(playMode)
        listener.onPlaylistChanged(songs)
        listener.onCoverChanged(coverArtPath)
        listener.onDurationChanged(durationMs)
        listener.onCoverReloadStateChanged(coverReloadState)
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
     * 当前正在显示的歌词文档（含附加轨与文件内偏移）。
     *
     * 歌词调节面板要读 `hasTranslation` / `hasPhonetic` / `sourceProviderId`，
     * 这些都**不在** `lrcLines` 里，所以文档本身得留一份。四条消费链仍然只用
     * [lrcLines] + [timebase]，不要从文档里另取行列表，否则下标可能对不上。
     */
    var lyricDocument: LyricDocument? = null
        private set

    /**
     * 歌词加载完成时由加载链路调用：写入多轨文档（行列表 + 文件内偏移）。
     *
     * 与 [update] 的分工：这里负责「换了一份歌词」，[update] 只负责「进度走到了哪」。
     */
    fun setLyricDocument(document: LyricDocument) {
        lyricDocument = document
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

    /**
     * 写入封面路径并广播。
     *
     * **禁止加「路径没变就跳过」的去重**：`SongMetadataLoader.reloadCover()` 会在
     * 同一路径上原子替换文件内容，靠这次广播让黑胶中心 / 信息页 / 迷你条重新解码。
     * 同理，失败时调用方绝不能传 null，否则会落成占位图。
     */
    fun setCoverArt(path: String?) {
        coverArtPath = path
        listeners.forEach { it.onCoverChanged(path) }
    }

    fun setCoverReloadState(state: CoverReloadState) {
        coverReloadState = state
        listeners.forEach { it.onCoverReloadStateChanged(state) }
    }

    /** 一次性结果事件：不写持久状态、不在 [addListener] 补发。 */
    fun notifyCoverReloadResult(success: Boolean) {
        listeners.forEach { it.onCoverReloadResult(success) }
    }

    fun addCoverCacheInvalidator(fn: (Song) -> Unit) {
        coverCacheInvalidators.add(fn)
    }

    fun removeCoverCacheInvalidator(fn: (Song) -> Unit) {
        coverCacheInvalidators.remove(fn)
    }

    /** 单个失效器抛异常不能影响主链路；P2 之前没有注册者，调用即空转。 */
    fun invokeCoverCacheInvalidators(song: Song) {
        coverCacheInvalidators.forEach { runCatching { it(song) } }
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
