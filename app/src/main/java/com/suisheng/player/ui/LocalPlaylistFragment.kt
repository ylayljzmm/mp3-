package com.suisheng.player.ui

import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.suisheng.player.ArtistAdapter
import com.suisheng.player.MusicPlaybackService
import com.suisheng.player.R
import com.suisheng.player.SongAdapter
import com.suisheng.player.SongParcelable
import com.suisheng.player.data.FavoriteStore
import com.suisheng.player.databinding.FragmentLocalPlaylistBinding
import com.suisheng.player.model.ArtistGroup
import com.suisheng.player.model.LibraryKind
import com.suisheng.player.model.LibrarySort
import com.suisheng.player.model.PlaylistSortOrder
import com.suisheng.player.model.PlaylistViewMode
import com.suisheng.player.model.Song
import com.suisheng.player.playback.PlaybackStateHolder
import com.suisheng.player.util.PinyinIndex
import kotlin.random.Random

/**
 * 「歌曲」目的地（方案 I / I3b）：工具条 + 拼音分组扁平列表 + 右侧索引轴 + 批量操作条。
 *
 * 搜索走放大镜面板，排序走 8 选项面板，随机按钮直接播其中一首（不切播放模式）。
 * 分组头由 [SongSectionDecoration] 绘制，不改三页共用的 [SongAdapter] 结构。
 */
class LocalPlaylistFragment : Fragment(), PlaybackStateHolder.Listener, PageBackHandler {
    private var _binding: FragmentLocalPlaylistBinding? = null
    private val binding get() = _binding!!
    private lateinit var songAdapter: SongAdapter
    private lateinit var artistAdapter: ArtistAdapter
    private lateinit var layoutManager: LinearLayoutManager
    private lateinit var sectionDecoration: SongSectionDecoration
    private lateinit var batchController: BatchActionController
    private var query = ""
    private var viewMode = PlaylistViewMode.ALL_SONGS
    private var sortOrder = PlaylistSortOrder.TITLE
    private var selectedArtist: String? = null
    private var lastClickMs = 0L
    private var selectionMode = false
    private var selectedPaths: Set<String> = emptySet()
    private var sectionPositions: Map<Char, Int> = emptyMap()

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
            },
            onSelectionToggle = ::toggleSelection,
        )
        artistAdapter = ArtistAdapter { group ->
            selectedArtist = group.name
            updateHeader()
            applyFilter()
        }

        layoutManager = LinearLayoutManager(requireContext())
        binding.songList.layoutManager = layoutManager
        binding.songList.adapter = songAdapter

        sectionDecoration = SongSectionDecoration(
            headerHeightPx = dp(32),
            barWidthPx = dp(3),
            backgroundColor = MaterialColors.getColor(
                binding.root, com.google.android.material.R.attr.colorSurface
            ),
            textColor = MaterialColors.getColor(
                binding.root, com.google.android.material.R.attr.colorOnSurfaceVariant
            ),
            accentColor = MaterialColors.getColor(
                binding.root, com.google.android.material.R.attr.colorPrimary
            ),
        ).apply { setTextSizePx(16f * resources.displayMetrics.scaledDensity) }
        binding.songList.addItemDecoration(sectionDecoration)
        binding.alphaIndexBar.onPick = ::jumpToLetter

        batchController = BatchActionController(
            root = binding.batchBar,
            countView = binding.batchCount,
            actions = listOf(
                binding.batchAddQueue,
                binding.batchFavorite,
                binding.batchRemoveQueue,
                binding.batchCancel,
            ),
            onAddToQueue = ::batchAddToQueue,
            onFavorite = ::batchFavorite,
            onRemoveFromQueue = ::batchRemoveFromQueue,
            onCancel = { setSelectionMode(false) },
        )

        binding.btnShufflePlay.setOnClickListener { playRandomSong() }
        binding.btnSearch.setOnClickListener { openSearchDialog() }
        binding.btnSort.setOnClickListener { openSortPanel() }
        binding.btnSelect.setOnClickListener { setSelectionMode(!selectionMode) }
        refreshFromHost()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    // ---------------------------------------------------------------- 拼音索引

    /** 按当前列表顺序建立「首字母 -> 组首 position」，排序/筛选变化后整体重算。 */
    private fun rebuildSections(list: List<Song>) {
        val positions = LinkedHashMap<Char, Int>()
        val sections = mutableListOf<Pair<Int, String>>()
        list.forEachIndexed { index, song ->
            val c = PinyinIndex.letterOf(song.title)
            if (!positions.containsKey(c)) {
                positions[c] = index
                sections.add(index to c.toString())
            }
        }
        sectionPositions = positions
        sectionDecoration.sections = sections
        // 分组首项变了要重算 itemOffsets，否则旧位置还留着空白头
        binding.songList.invalidateItemDecorations()
        binding.alphaIndexBar.setAvailable(positions.keys)
        binding.alphaIndexBar.visibility = View.VISIBLE
    }

    private fun jumpToLetter(letter: Char) {
        val position = sectionPositions[letter]
        if (position == null) {
            Toast.makeText(
                requireContext(),
                getString(R.string.index_no_match, letter.toString()),
                Toast.LENGTH_SHORT,
            ).show()
            return
        }
        binding.alphaIndexBar.setSelected(letter)
        layoutManager.scrollToPositionWithOffset(position, dp(6))
    }

    // ---------------------------------------------------------------- Q-3 批量操作

    private fun setSelectionMode(on: Boolean) {
        selectionMode = on
        if (!on) selectedPaths = emptySet()
        songAdapter.selectionMode = on
        songAdapter.selectedPaths = selectedPaths
        binding.btnSelect.setText(if (on) R.string.common_cancel else R.string.select)
        if (on) batchController.show(selectedPaths.size) else batchController.hide()
    }

    private fun toggleSelection(song: Song) {
        val next = selectedPaths.toMutableSet()
        if (!next.add(song.path)) next.remove(song.path)
        selectedPaths = next
        songAdapter.selectedPaths = next
        batchController.show(next.size)
    }

    private fun selectedSongs(): List<Song> = currentVisibleSongs().filter { it.path in selectedPaths }

    private fun batchAddToQueue() {
        val songs = selectedSongs()
        if (songs.isEmpty()) return
        val intent = Intent(requireContext(), MusicPlaybackService::class.java).apply {
            action = MusicPlaybackService.ACTION_QUEUE_ADD
            putParcelableArrayListExtra(
                MusicPlaybackService.EXTRA_PLAYLIST,
                ArrayList(songs.map { SongParcelable.from(it) }),
            )
        }
        ContextCompat.startForegroundService(requireContext(), intent)
        Toast.makeText(
            requireContext(), getString(R.string.batch_done, songs.size, songs.size), Toast.LENGTH_SHORT
        ).show()
        setSelectionMode(false)
    }

    private fun batchFavorite() {
        val songs = selectedSongs()
        if (songs.isEmpty()) return
        val store = FavoriteStore(requireContext())
        var done = 0
        songs.forEach {
            if (!store.isFavorite(it.path)) {
                store.setFavorite(it.path, true)
                done++
            }
        }
        Toast.makeText(
            requireContext(), getString(R.string.batch_done, done, songs.size), Toast.LENGTH_SHORT
        ).show()
        setSelectionMode(false)
    }

    private fun batchRemoveFromQueue() {
        val songs = selectedSongs()
        if (songs.isEmpty()) return
        val intent = Intent(requireContext(), MusicPlaybackService::class.java).apply {
            action = MusicPlaybackService.ACTION_QUEUE_REMOVE_PATHS
            putStringArrayListExtra(
                MusicPlaybackService.EXTRA_PATHS,
                ArrayList(songs.map { it.path }),
            )
        }
        ContextCompat.startForegroundService(requireContext(), intent)
        Toast.makeText(
            requireContext(), getString(R.string.batch_done, songs.size, songs.size), Toast.LENGTH_SHORT
        ).show()
        setSelectionMode(false)
    }

    // ---------------------------------------------------------------- 工具条

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
        // 多选态优先由本页消费：系统返回键先退出多选，不弹目的地
        if (selectionMode) {
            setSelectionMode(false)
            return true
        }
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
        rebuildSections(list)
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
        sectionDecoration.sections = emptyList()
        sectionPositions = emptyMap()
        binding.songList.invalidateItemDecorations()
        binding.alphaIndexBar.visibility = View.GONE
        binding.alphaIndexBar.clearSelection()
    }

    private fun sortSongs(list: List<Song>): List<Song> = when (sortOrder) {
        // 标题排序必须与索引轴同一套口径（拼音首字母 → Collator），否则「按轴跳转」会落到错误位置
        PlaylistSortOrder.TITLE -> list.sortedWith(LibrarySort.BY_SONG_TITLE)
        PlaylistSortOrder.TITLE_DESC -> list.sortedWith(LibrarySort.BY_SONG_TITLE.reversed())
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
