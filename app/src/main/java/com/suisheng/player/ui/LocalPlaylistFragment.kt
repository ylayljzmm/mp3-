package com.suisheng.player.ui

import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.suisheng.player.ArtistAdapter
import com.suisheng.player.R
import com.suisheng.player.SongAdapter
import com.suisheng.player.databinding.FragmentLocalPlaylistBinding
import com.suisheng.player.model.ArtistGroup
import com.suisheng.player.model.LibraryKind
import com.suisheng.player.model.PlaylistSortOrder
import com.suisheng.player.model.PlaylistViewMode
import com.suisheng.player.model.Song
import com.suisheng.player.playback.PlaybackStateHolder
import kotlin.random.Random

/**
 * 「歌曲」目的地。标题由全局页头给出，页面内只有一条工具条 + 扁平列表。
 *
 * 方案 I：常驻搜索框 / 筛选 chip / 排序 RadioGroup / 独立数量行全部删除；
 * 搜索走放大镜面板，排序走 8 选项面板，随机按钮直接播其中一首（不切播放模式）。
 * 按歌手分组的代码路径保留（[showArtistList]），只是不再有屏幕入口。
 */
class LocalPlaylistFragment : Fragment(), PlaybackStateHolder.Listener, PageBackHandler {
    private var _binding: FragmentLocalPlaylistBinding? = null
    private val binding get() = _binding!!
    private lateinit var songAdapter: SongAdapter
    private lateinit var artistAdapter: ArtistAdapter
    private var query = ""
    private var viewMode = PlaylistViewMode.ALL_SONGS
    private var sortOrder = PlaylistSortOrder.TITLE
    private var selectedArtist: String? = null
    private var lastClickMs = 0L

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentLocalPlaylistBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        songAdapter = SongAdapter(
            onClick = { song, indexInList ->
                val now = System.currentTimeMillis()
                if (now - lastClickMs < 280) return@SongAdapter
                lastClickMs = now
                (activity as? MainHost)?.selectDestination(Destination.PLAYER)
                val visibleSongs = currentVisibleSongs()
                val playIndex = visibleSongs.indexOfFirst { it.path == song.path }.takeIf { it >= 0 } ?: indexInList
                (activity as? MainHost)?.playSongSubset(visibleSongs, playIndex, LibraryKind.MUSIC)
            }
        )
        artistAdapter = ArtistAdapter { group ->
            selectedArtist = group.name
            updateHeader()
            applyFilter()
        }

        binding.songList.layoutManager = LinearLayoutManager(requireContext())
        binding.songList.adapter = songAdapter

        binding.btnShufflePlay.setOnClickListener { playRandomSong() }
        binding.btnSearch.setOnClickListener { openSearchDialog() }
        binding.btnSort.setOnClickListener { openSortPanel() }
        // btnSelect 在 I3b 接批量操作条时启用
        refreshFromHost()
    }

    /** 随机播其中一首：直接起播，不切换播放模式。 */
    private fun playRandomSong() {
        val songs = currentVisibleSongs()
        if (songs.isEmpty()) {
            Toast.makeText(requireContext(), R.string.no_songs, Toast.LENGTH_SHORT).show()
            return
        }
        val index = Random.nextInt(songs.size)
        (activity as? MainHost)?.playSongSubset(songs, index, LibraryKind.MUSIC)
        (activity as? MainHost)?.selectDestination(Destination.PLAYER)
        Toast.makeText(
            requireContext(),
            getString(R.string.random_playing, songs[index].title),
            Toast.LENGTH_SHORT,
        ).show()
    }

    /** 搜索面板：沿用原有 query 过滤，支持清空。 */
    private fun openSearchDialog() {
        val input = EditText(requireContext()).apply {
            hint = getString(R.string.search_hint)
            setText(query)
            inputType = InputType.TYPE_CLASS_TEXT
            setSelection(text.length)
        }
        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.search_hint)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                query = input.text?.toString().orEmpty()
                applyFilter()
            }
            .setNegativeButton(R.string.common_cancel, null)
        if (query.isNotBlank()) {
            dialog.setNeutralButton(R.string.search_clear) { _, _ ->
                query = ""
                applyFilter()
            }
        }
        dialog.show()
    }

    /** 8 选项排序面板：选中即时生效并更新按钮文字。 */
    private fun openSortPanel() {
        SortPanel.show(requireContext(), sortOrder) { order ->
            sortOrder = order
            binding.btnSort.setText(SortPanel.labelOf(order))
            applyFilter()
        }
    }

    private fun selectViewMode(mode: PlaylistViewMode) {
        viewMode = mode
        selectedArtist = null
        updateHeader()
        applyFilter()
    }

    private fun exitArtistDetail() {
        if (selectedArtist == null) return
        selectedArtist = null
        updateHeader()
        applyFilter()
    }

    // ------------------------------------------------------- 页面内层级（返回键）

    override fun hasInnerLevel(): Boolean =
        _binding != null && viewMode == PlaylistViewMode.BY_ARTIST && selectedArtist != null

    override fun handlePageBack(): Boolean {
        if (!hasInnerLevel()) return false
        exitArtistDetail()
        syncPageHeaderBack()
        return true
    }

    override fun onStart() {
        super.onStart()
        PlaybackStateHolder.addListener(this)
    }

    override fun onStop() {
        PlaybackStateHolder.removeListener(this)
        super.onStop()
    }

    override fun onPlaybackChanged(
        song: Song?,
        playing: Boolean,
        positionMs: Long,
        lines: List<com.suisheng.player.model.LrcLine>
    ) {
        songAdapter.playingPath = song?.path
    }

    override fun onPlaylistChanged(songs: List<Song>) {
        songAdapter.playingPath = PlaybackStateHolder.currentSong?.path
        applyFilter()
    }

    fun refreshFromHost() = applyFilter()

    private fun sourceSongs(): List<Song> = (activity as? MainHost)?.allSongs().orEmpty()

    private fun currentVisibleSongs(): List<Song> {
        var list = sourceSongs()
        if (selectedArtist != null) list = list.filter { it.artist == selectedArtist }
        if (query.isNotBlank()) {
            val q = query.lowercase()
            list = list.filter { it.title.lowercase().contains(q) || it.artist.lowercase().contains(q) }
        }
        return sortSongs(list)
    }

    private fun applyFilter() {
        if (viewMode == PlaylistViewMode.BY_ARTIST && selectedArtist == null) {
            showArtistList()
            return
        }
        binding.songList.adapter = songAdapter
        val list = currentVisibleSongs()
        songAdapter.playingPath = PlaybackStateHolder.currentSong?.path
        songAdapter.submitSongs(list)
        binding.songCountText.text = getString(R.string.song_count, list.size)
        binding.emptyText.text = getString(R.string.no_songs)
        binding.emptyText.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        binding.songList.visibility = if (list.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun showArtistList() {
        binding.songList.adapter = artistAdapter
        var artists = sourceSongs().groupBy { it.artist }
            .map { (name, songs) -> ArtistGroup(name, songs.size) }
        if (query.isNotBlank()) {
            val q = query.lowercase()
            artists = artists.filter { it.name.lowercase().contains(q) }
        }
        artists = artists.sortedBy { it.name.lowercase() }
        artistAdapter.submitList(artists)
        binding.songCountText.text = getString(R.string.artist_count, artists.size)
        binding.emptyText.visibility = if (artists.isEmpty()) View.VISIBLE else View.GONE
        binding.songList.visibility = if (artists.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun sortSongs(list: List<Song>): List<Song> = when (sortOrder) {
        PlaylistSortOrder.TITLE -> list.sortedBy { it.title.lowercase() }
        PlaylistSortOrder.TITLE_DESC -> list.sortedByDescending { it.title.lowercase() }
        PlaylistSortOrder.ARTIST -> list.sortedWith(
            compareBy<Song> { it.artist.lowercase() }.thenBy { it.title.lowercase() }
        )
        PlaylistSortOrder.ALBUM -> list.sortedWith(
            compareBy<Song> { it.album.lowercase() }.thenBy { it.title.lowercase() }
        )
        PlaylistSortOrder.DURATION_ASC -> list.sortedWith(
            compareBy<Song> { if (it.durationMs <= 0L) Long.MAX_VALUE else it.durationMs }.thenBy { it.title.lowercase() }
        )
        PlaylistSortOrder.DURATION_DESC -> list.sortedWith(
            compareByDescending<Song> { if (it.durationMs <= 0L) Long.MIN_VALUE else it.durationMs }
                .thenBy { it.title.lowercase() }
        )
        // Song 没有「添加时间」字段，用 MediaStore 的 id 降序近似最近添加
        PlaylistSortOrder.RECENT -> list.sortedByDescending { it.id }
        PlaylistSortOrder.PATH -> list.sortedBy { it.path.lowercase() }
    }

    /** 把「当前在筛选/看谁」的状态交给页头副标题。 */
    private fun updateHeader() {
        val subtitle = when {
            selectedArtist != null -> selectedArtist
            viewMode == PlaylistViewMode.BY_ARTIST -> getString(R.string.filter_artists)
            else -> null
        }
        setPageSubtitle(subtitle)
        syncPageHeaderBack()
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }
}
