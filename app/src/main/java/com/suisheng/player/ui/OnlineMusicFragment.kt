package com.suisheng.player.ui

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.suisheng.player.MusicPlaybackService
import com.suisheng.player.R
import com.suisheng.player.SongAdapter
import com.suisheng.player.data.OnlineCurated
import com.suisheng.player.data.OnlineLibraryStore
import com.suisheng.player.data.OnlineMusicApi
import com.suisheng.player.data.SettingsRepository
import com.suisheng.player.databinding.FragmentOnlineMusicBinding
import com.suisheng.player.databinding.ItemChartChipBinding
import com.suisheng.player.databinding.ItemPlaylistCardBinding
import com.suisheng.player.model.CuratedChart
import com.suisheng.player.model.CuratedPlaylist
import com.suisheng.player.model.LibraryKind
import com.suisheng.player.model.OnlinePlaylistSummary
import com.suisheng.player.model.PlaybackMode
import com.suisheng.player.model.Song
import com.suisheng.player.playback.PlaybackStateHolder
import com.suisheng.player.util.MediaPath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 「在线音乐」目的地。页面自己不再有标题条：标题由全局页头给出，
 * 「我的 / 歌单详情 / 搜索结果」这三种页面内部状态改走页头副标题，
 * 返回键先退这些内层（[PageBackHandler]）再离开目的地。
 */
class OnlineMusicFragment : Fragment(), PlaybackStateHolder.Listener, PageBackHandler {
    private var _binding: FragmentOnlineMusicBinding? = null
    private val binding get() = _binding!!
    private lateinit var settings: SettingsRepository
    private lateinit var api: OnlineMusicApi
    private lateinit var library: OnlineLibraryStore
    private lateinit var songAdapter: SongAdapter
    private var currentSongs: List<Song> = emptyList()
    private var showingDetail = false
    private var mineMode = false
    private var lastClickMs = 0L
    private var activePlaylistId: String? = null
    private var activePlaylistTitle: String? = null
    private var activePlaylistSummary: OnlinePlaylistSummary? = null
    private var activePlayableSongs: List<Song> = emptyList()
    private var allowFavoritePlaylist = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentOnlineMusicBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        settings = SettingsRepository(requireContext())
        api = OnlineMusicApi(settings)
        library = OnlineLibraryStore(requireContext())

        songAdapter = SongAdapter(
            onClick = { song, index ->
                when {
                    song.path.startsWith("meta://netease-playlist/") -> {
                        val id = song.path.removePrefix("meta://netease-playlist/")
                        loadPlaylist(id, song.title.removePrefix("🎵 ").trim())
                    }
                    song.path.startsWith("meta://playlist/") -> {
                        val id = song.path.removePrefix("meta://playlist/")
                        val tracks = library.songsForPlaylist(id)
                        showPlaylistDetail(
                            title = song.title.removePrefix("📁 ").trim(),
                            songs = tracks,
                            allowFavorite = false
                        )
                    }
                    else -> {
                        val playable = currentSongs.filter { !it.path.startsWith("meta://") }
                        val playIndex = playable.indexOfFirst { it.path == song.path }.coerceAtLeast(0)
                        val now = System.currentTimeMillis()
                        if (now - lastClickMs < 280) return@SongAdapter
                        lastClickMs = now
                        (activity as? MainHost)?.selectDestination(Destination.PLAYER)
                        (activity as? MainHost)?.playSongSubset(playable, playIndex, LibraryKind.ONLINE)
                    }
                }
            },
            onLongClick = { song, _ ->
                if (song.path.startsWith("meta://")) return@SongAdapter
                library.addFavoriteTrackFromSong(song, api)
                Toast.makeText(requireContext(), R.string.online_favorited, Toast.LENGTH_SHORT).show()
            }
        )

        binding.chartList.layoutManager = LinearLayoutManager(requireContext(), RecyclerView.HORIZONTAL, false)
        binding.chartList.adapter = ChartAdapter(OnlineCurated.charts) { loadPlaylist(it.playlistId, it.title) }

        binding.featuredList.layoutManager = LinearLayoutManager(requireContext(), RecyclerView.HORIZONTAL, false)
        binding.featuredList.adapter = FeaturedAdapter(OnlineCurated.featuredPlaylists) { loadPlaylist(it.playlistId, it.title) }

        binding.songList.layoutManager = LinearLayoutManager(requireContext())
        binding.songList.adapter = songAdapter

        binding.chipDiscover.setOnClickListener { switchMine(false) }
        binding.chipMine.setOnClickListener { switchMine(true) }

        binding.btnPlayAll.setOnClickListener { playActivePlaylist(shuffle = false) }
        binding.btnShufflePlay.setOnClickListener { playActivePlaylist(shuffle = true) }
        binding.btnFavoritePlaylist.setOnClickListener { favoriteActivePlaylist() }

        binding.searchInput.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH ||
                (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
            ) {
                performSearch(binding.searchInput.text?.toString().orEmpty())
                true
            } else false
        }
        binding.searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (s.isNullOrBlank() && showingDetail) showDiscoverHome()
            }
        })

        showDiscoverHome()
    }

    private fun switchMine(mine: Boolean) {
        mineMode = mine
        binding.chipDiscover.isChecked = !mine
        binding.chipMine.isChecked = mine
        if (mine) showMineLibrary() else showDiscoverHome()
    }

    private fun showDiscoverHome() {
        showingDetail = false
        mineMode = false
        clearActivePlaylist()
        binding.chipDiscover.isChecked = true
        setPageSubtitle(null)
        syncPageHeaderBack()
        binding.discoverScroll.visibility = View.VISIBLE
        binding.playlistDetailContainer.visibility = View.GONE
        binding.emptyText.visibility = View.GONE
    }

    private fun showMineLibrary() {
        showingDetail = true
        clearActivePlaylist()
        binding.discoverScroll.visibility = View.GONE
        binding.playlistDetailContainer.visibility = View.VISIBLE
        setPageSubtitle(getString(R.string.online_mine))
        syncPageHeaderBack()
        val playlistHeaders = library.userPlaylists().map { playlist ->
            Song(
                id = playlist.id.hashCode().toLong(),
                title = "📁 ${playlist.name}",
                artist = getString(R.string.online_playlist_tap, playlist.trackLocalIds.size),
                path = "meta://playlist/${playlist.id}",
                lrcPath = null
            )
        }
        val favorites = library.standaloneFavoriteSongs()
        currentSongs = playlistHeaders + favorites
        renderSongList(currentSongs, getString(R.string.online_favorites_empty))
    }

    private fun loadPlaylist(playlistId: String, title: String) {
        setLoading(true)
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { api.loadPlaylist(playlistId, title) }
            setLoading(false)
            if (!result.ok) {
                val message = result.errorMessage ?: getString(R.string.online_load_failed)
                Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
                return@launch
            }
            showPlaylistDetail(
                title = title.ifBlank { result.summary?.name.orEmpty() },
                songs = result.songs,
                playlistId = playlistId,
                summary = result.summary,
                allowFavorite = true
            )
        }
    }

    private fun showPlaylistDetail(
        title: String,
        songs: List<Song>,
        playlistId: String? = null,
        summary: OnlinePlaylistSummary? = null,
        allowFavorite: Boolean = false
    ) {
        showingDetail = true
        activePlaylistId = playlistId
        activePlaylistTitle = title
        activePlaylistSummary = summary
        activePlayableSongs = songs
        allowFavoritePlaylist = allowFavorite && !playlistId.isNullOrBlank()

        binding.discoverScroll.visibility = View.GONE
        binding.playlistDetailContainer.visibility = View.VISIBLE
        setPageSubtitle(title)
        syncPageHeaderBack()
        currentSongs = songs
        renderSongList(songs, getString(R.string.online_empty))
        updatePlaylistActionBar()
    }

    private fun clearActivePlaylist() {
        activePlaylistId = null
        activePlaylistTitle = null
        activePlaylistSummary = null
        activePlayableSongs = emptyList()
        allowFavoritePlaylist = false
        binding.playlistActionBar.visibility = View.GONE
    }

    private fun updatePlaylistActionBar() {
        val hasSongs = activePlayableSongs.isNotEmpty()
        binding.playlistActionBar.visibility = if (hasSongs) View.VISIBLE else View.GONE
        binding.btnPlayAll.isEnabled = hasSongs
        binding.btnShufflePlay.isEnabled = hasSongs
        binding.btnFavoritePlaylist.visibility = if (allowFavoritePlaylist) View.VISIBLE else View.GONE
        if (!allowFavoritePlaylist) return

        val saved = activePlaylistId?.let { library.findImportedPlaylist(it) } != null
        binding.btnFavoritePlaylist.text = getString(R.string.online_favorite_playlist)
        binding.btnFavoritePlaylist.setIconResource(
            if (saved) android.R.drawable.btn_star_big_on else android.R.drawable.btn_star_big_off
        )
        binding.btnFavoritePlaylist.isEnabled = !saved
        binding.btnFavoritePlaylist.alpha = if (saved) 0.7f else 1f
    }

    private fun playActivePlaylist(shuffle: Boolean) {
        val songs = activePlayableSongs
        if (songs.isEmpty()) return
        setPlayMode(if (shuffle) PlaybackMode.SHUFFLE else PlaybackMode.ORDER)
        val index = if (shuffle) songs.indices.random() else 0
        (activity as? MainHost)?.selectDestination(Destination.PLAYER)
        (activity as? MainHost)?.playSongSubset(songs, index, LibraryKind.ONLINE)
    }

    private fun favoriteActivePlaylist() {
        val playlistId = activePlaylistId ?: return
        val title = activePlaylistTitle.orEmpty()
        val summary = activePlaylistSummary
        if (library.findImportedPlaylist(playlistId) != null) {
            Toast.makeText(requireContext(), R.string.online_playlist_already_saved, Toast.LENGTH_SHORT).show()
            updatePlaylistActionBar()
            return
        }
        setLoading(true)
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                api.loadPlaylist(playlistId, title.ifBlank { summary?.name })
            }
            setLoading(false)
            if (!result.ok) {
                val message = result.errorMessage ?: getString(R.string.online_load_failed)
                Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
                return@launch
            }
            val importName = title.ifBlank { result.summary?.name.orEmpty() }.ifBlank { "在线歌单" }
            library.importPlaylist(
                importName,
                playlistId,
                result.songs,
                api,
                result.summary?.coverUrl ?: summary?.coverUrl
            )
            Toast.makeText(
                requireContext(),
                getString(R.string.online_playlist_saved_count, result.songs.size),
                Toast.LENGTH_SHORT
            ).show()
            updatePlaylistActionBar()
        }
    }

    private fun setPlayMode(mode: PlaybackMode) {
        val intent = Intent(requireContext(), MusicPlaybackService::class.java).apply {
            action = MusicPlaybackService.ACTION_SET_MODE
            putExtra(MusicPlaybackService.EXTRA_MODE, mode.ordinal)
        }
        runCatching {
            ContextCompat.startForegroundService(requireContext(), intent)
        }
    }

    private fun performSearch(keyword: String) {
        if (keyword.isBlank()) return
        setLoading(true)
        lifecycleScope.launch {
            val songs = withContext(Dispatchers.IO) { api.searchSongs(keyword) }
            val playlists = withContext(Dispatchers.IO) { api.searchPlaylists(keyword) }
            setLoading(false)
            showingDetail = true
            clearActivePlaylist()
            binding.discoverScroll.visibility = View.GONE
            binding.playlistDetailContainer.visibility = View.VISIBLE
            setPageSubtitle(getString(R.string.online_search_result, keyword))
            syncPageHeaderBack()
            val playlistRows = playlists.take(8).map { summary ->
                Song(
                    id = summary.id.hashCode().toLong(),
                    title = "🎵 ${summary.name}",
                    artist = getString(R.string.online_playlist_meta, summary.trackCount, formatCount(summary.subscribedCount)),
                    path = "meta://netease-playlist/${summary.id}",
                    lrcPath = summary.coverUrl
                )
            }
            currentSongs = playlistRows + songs
            renderSongList(currentSongs, getString(R.string.online_search_empty))
        }
    }

    private fun renderSongList(songs: List<Song>, emptyMessage: String) {
        songAdapter.playingPath = PlaybackStateHolder.currentSong?.path
        songAdapter.submitSongs(songs)
        binding.emptyText.text = emptyMessage
        binding.emptyText.visibility = if (songs.isEmpty()) View.VISIBLE else View.GONE
        binding.songList.visibility = if (songs.isEmpty()) View.GONE else View.VISIBLE
        if (activePlayableSongs.isNotEmpty() && songs === activePlayableSongs) {
            updatePlaylistActionBar()
        }
    }

    private fun setLoading(loading: Boolean) {
        binding.loading.visibility = if (loading) View.VISIBLE else View.GONE
    }

    fun refreshLibrary() {
        if (mineMode) showMineLibrary()
    }

    // ------------------------------------------------------- 页面内层级（返回键）

    /** 歌单详情 / 「我的」/ 搜索结果都在 [playlistDetailContainer] 里，算内层。 */
    override fun hasInnerLevel(): Boolean = _binding != null && showingDetail

    override fun handlePageBack(): Boolean {
        if (!hasInnerLevel()) return false
        showDiscoverHome()
        return true
    }

    private fun formatCount(value: Long): String = when {
        value >= 100_000_000 -> "${value / 100_000_000}亿"
        value >= 10_000 -> "${value / 10_000}万"
        else -> value.toString()
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

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    private inner class ChartAdapter(
        private val items: List<CuratedChart>,
        private val onClick: (CuratedChart) -> Unit
    ) : RecyclerView.Adapter<ChartAdapter.Holder>() {
        inner class Holder(val binding: ItemChartChipBinding) : RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val binding = ItemChartChipBinding.inflate(layoutInflater, parent, false)
            return Holder(binding)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val item = items[position]
            holder.binding.title.text = item.title
            holder.binding.root.setOnClickListener { onClick(item) }
        }

        override fun getItemCount(): Int = items.size
    }

    private inner class FeaturedAdapter(
        private val items: List<CuratedPlaylist>,
        private val onClick: (CuratedPlaylist) -> Unit
    ) : RecyclerView.Adapter<FeaturedAdapter.Holder>() {
        inner class Holder(val binding: ItemPlaylistCardBinding) : RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val binding = ItemPlaylistCardBinding.inflate(layoutInflater, parent, false)
            return Holder(binding)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val item = items[position]
            holder.binding.title.text = item.title
            holder.binding.subtitle.text = item.subtitle
            holder.binding.cover.setImageResource(R.drawable.ic_play)
            holder.binding.root.setOnClickListener { onClick(item) }
        }

        override fun getItemCount(): Int = items.size
    }
}
