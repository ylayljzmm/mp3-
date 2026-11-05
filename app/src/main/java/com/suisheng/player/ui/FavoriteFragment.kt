package com.suisheng.player.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.suisheng.player.R
import com.suisheng.player.SongAdapter
import com.suisheng.player.data.FavoriteStore
import com.suisheng.player.databinding.FragmentFavoritesBinding
import com.suisheng.player.model.LibraryKind
import com.suisheng.player.model.LrcLine
import com.suisheng.player.model.Song
import com.suisheng.player.playback.PlaybackStateHolder

/**
 * 「收藏」目的地（抽屉「媒体库」组第 5 条，排在「文件夹」之后）。
 *
 * 播放页那个心形按钮只解决了「收」，这一页解决「看」：把 [FavoriteStore] 里的
 * 收藏路径还原成歌曲列表。
 *
 * 三条设计约束，都写在代码里而不是交给调用方记：
 *
 * ① **顺序按收藏时间倒序，不按曲库顺序**。`FavoriteStore.paths()` 返回的是
 *    `LinkedHashSet`，迭代顺序就是 `addedAt` 降序；所以这里用它做「path → 排名」
 *    的映射再排序。如果偷懒写成「曲库过滤」，最近收藏的歌会被埋进列表中间，
 *    用户刚收完一首再进来找不到。
 *
 * ② **数据源是宿主的 [MainHost.allSongs]**，它**已经**过「隐藏文件夹」过滤。
 *    收藏过、后来被隐藏的歌自然不出现 —— 这是期望行为：隐藏的意思是「这首别给我看」，
 *    收藏页不该成为绕过它的后门。反过来也不做补救（不去读 `rawMusicSongs`），
 *    否则同一个隐藏开关在收藏页就失效了。
 *
 * ③ 播放队列 = 当前收藏列表本身（点第 3 首，队列就是这 N 首）。收藏页是个「歌单」，
 *    用户在这里点播放，期待的就是「从这首开始放我的收藏」，而不是回到全曲库。
 *
 * 页头由全局页头提供（标题「收藏」），条数走副标题（`setPageSubtitle`），
 * 所以本页既没有标题条也没有计数条。
 */
class FavoriteFragment : Fragment(), PlaybackStateHolder.Listener {

    private var _binding: FragmentFavoritesBinding? = null
    private val binding get() = _binding!!

    /**
     * 收藏存储。
     *
     * `lazy` 而不是在 [onCreate] 里建：`requireContext()` 只有挂到 Activity 之后才可用。
     * 这个 Fragment 的生命周期跟着一次 `replace` 走（每次从抽屉进来都是新实例），
     * 所以内存副本与磁盘一致，从播放页收藏完再进来就能看到。
     */
    private val store by lazy { FavoriteStore(requireContext()) }

    private lateinit var songAdapter: SongAdapter

    /** 双击保护：列表项点一下就该切歌，连点两下会把播放队列连推两次。 */
    private var lastClickMs = 0L

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentFavoritesBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        songAdapter = SongAdapter(
            onClick = { song, indexInList ->
                val now = System.currentTimeMillis()
                if (now - lastClickMs < CLICK_DEBOUNCE_MS) return@SongAdapter
                lastClickMs = now
                playFromFavorites(song, indexInList)
            },
            // SongAdapter 自带 onLongClick：长按取消收藏，不用为了它改适配器
            onLongClick = { song, _ -> confirmRemoveFavorite(song) },
        )

        binding.favoriteList.layoutManager = LinearLayoutManager(requireContext())
        binding.favoriteList.adapter = songAdapter

        refreshFromHost()
    }

    override fun onStart() {
        super.onStart()
        PlaybackStateHolder.addListener(this)
    }

    override fun onStop() {
        PlaybackStateHolder.removeListener(this)
        super.onStop()
    }

    /**
     * 播放状态变化时只做一件事：把当前曲目的 path 交给适配器，由它画高亮。
     *
     * 这个方法每秒会被叫好几次，所以不加任何其它逻辑；`playingPath` 的 setter 自己
     * 判等，路径没变就直接返回，不会产生多余的 `notifyItemChanged`。
     */
    override fun onPlaybackChanged(
        song: Song?,
        playing: Boolean,
        positionMs: Long,
        lines: List<LrcLine>,
    ) {
        if (_binding == null) return
        songAdapter.playingPath = song?.path
    }

    // ---------------------------------------------------------------- 渲染

    /**
     * 重算列表并刷新 UI（宿主曲库变化后由 `MainActivity.refreshCurrentLibraryPage`
     * 调用，本页自己的增删也走它）。
     *
     * `_binding == null` 时直接返回：页面已经被切走，视图没了但事务还没走完，
     * 这时候它下次 `onViewCreated` 会自己读一遍最新数据，不需要外部推动。
     */
    fun refreshFromHost() {
        if (_binding == null) return
        val songs = favoriteSongs()
        // 先同步高亮再提交列表：这样 submit 的那一帧就已经带上正确的播放态，
        // 不会「先画一遍无高亮、再闪一下高亮」。
        songAdapter.playingPath = PlaybackStateHolder.currentSong?.path
        songAdapter.submitSongs(songs)

        val empty = songs.isEmpty()
        binding.favoriteList.visibility = if (empty) View.GONE else View.VISIBLE
        binding.emptyState.visibility = if (empty) View.VISIBLE else View.GONE
        setPageSubtitle(getString(R.string.favorites_count, songs.size))
    }

    /**
     * 收藏的歌曲，**按收藏时间倒序**。
     *
     * 两步而不是一步：先用 `store.paths()` 的迭代顺序建「path → 排名」的映射
     * （`LinkedHashSet` 的顺序就是 `addedAt` 降序），再拿它给曲库里的歌排序。
     * 这样既保住了收藏顺序，又只输出「曲库里真的看得见」的那些歌。
     */
    private fun favoriteSongs(): List<Song> {
        val allSongs = (activity as? MainHost)?.allSongs().orEmpty()
        if (allSongs.isEmpty()) return emptyList()

        val favorites = store.paths()
        if (favorites.isEmpty()) return emptyList()

        val rank = HashMap<String, Int>(favorites.size)
        favorites.forEachIndexed { index, path -> rank[path] = index }
        return allSongs
            .filter { rank.containsKey(it.path) }
            .sortedBy { rank[it.path] ?: Int.MAX_VALUE }
    }

    // ---------------------------------------------------------------- 交互

    /**
     * 点歌：队列就是当前的收藏列表。
     *
     * 播放索引按 path 再查一次而不是直接用 `position`：适配器里的位置是提交那一刻的
     * 快照，而 `favoriteSongs()` 是现算的，两者在「刚取消了一首、列表还没刷新」的
     * 瞬间可能错位；查不到才退回适配器给的下标。
     *
     * 刻意**不切到播放页**：收藏页是个歌单，用户常常是「一边看列表一边放」；
     * 底部迷你条会出现，想进播放页点它即可。这也让「正在播放的那首高亮」看得见。
     */
    private fun playFromFavorites(song: Song, indexInList: Int) {
        val host = activity as? MainHost ?: return
        val songs = favoriteSongs()
        if (songs.isEmpty()) return
        val playIndex = songs.indexOfFirst { it.path == song.path }
            .takeIf { it >= 0 }
            ?: indexInList.coerceIn(0, songs.lastIndex)
        host.playSongSubset(songs, playIndex, LibraryKind.MUSIC)
    }

    /** 长按 → 确认 → 取消收藏。走确认框是因为长按太容易误触，取消收藏又没有撤销入口。 */
    private fun confirmRemoveFavorite(song: Song) {
        if (!isAdded) return
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.favorites_remove_confirm, song.title))
            .setNegativeButton(R.string.favorites_remove_cancel, null)
            .setPositiveButton(R.string.favorites_remove_action) { _, _ -> removeFavorite(song) }
            .show()
    }

    private fun removeFavorite(song: Song) {
        store.remove(song.path)
        refreshFromHost()
        Toast.makeText(requireContext(), R.string.favorites_removed, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroyView() {
        binding.favoriteList.adapter = null
        _binding = null
        super.onDestroyView()
    }

    private companion object {
        /** 与「歌曲」页同值的双击保护窗口（毫秒）。 */
        const val CLICK_DEBOUNCE_MS = 280L
    }
}
