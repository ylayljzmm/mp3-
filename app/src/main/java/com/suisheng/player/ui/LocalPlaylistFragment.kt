package com.suisheng.player.ui

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.suisheng.player.ArtistAdapter
import com.suisheng.player.R
import com.suisheng.player.SongAdapter
import com.suisheng.player.data.SettingsRepository
import com.suisheng.player.databinding.FragmentLocalPlaylistBinding
import com.suisheng.player.model.ArtistGroup
import com.suisheng.player.model.LibraryKind
import com.suisheng.player.model.PlaylistSortOrder
import com.suisheng.player.model.PlaylistViewMode
import com.suisheng.player.model.Song
import com.suisheng.player.playback.PlaybackStateHolder

class LocalPlaylistFragment : Fragment(), PlaybackStateHolder.Listener {
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
        val settings = SettingsRepository(requireContext())
        AppThemeManager.applyFragmentRoot(binding.root, AppThemeManager.palette(requireContext(), settings))
        songAdapter = SongAdapter(
            onClick = { song, indexInList ->
                val now = System.currentTimeMillis()
                if (now - lastClickMs < 280) return@SongAdapter
                lastClickMs = now
                (activity as? MainHost)?.switchToTab(1)
                val visibleSongs = currentVisibleSongs()
                val playIndex = visibleSongs.indexOfFirst { it.path == song.path }.takeIf { it >= 0 } ?: indexInList
                (activity as? MainHost)?.playSongSubset(visibleSongs, playIndex, LibraryKind.MUSIC)
            }
        )
        artistAdapter = ArtistAdapter { group ->
            selectedArtist = group.name
            updateToolbar()
            applyFilter()
        }

        binding.songList.layoutManager = LinearLayoutManager(requireContext())
        binding.songList.adapter = songAdapter
        binding.searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                query = s?.toString().orEmpty()
                applyFilter()
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        binding.chipAllSongs.setOnClickListener { selectViewMode(PlaylistViewMode.ALL_SONGS) }
        binding.chipArtists.setOnClickListener { selectViewMode(PlaylistViewMode.BY_ARTIST) }
        binding.sortGroup.setOnCheckedChangeListener { _, checkedId ->
            sortOrder = when (checkedId) {
                R.id.sortDurationAsc -> PlaylistSortOrder.DURATION_ASC
                R.id.sortDurationDesc -> PlaylistSortOrder.DURATION_DESC
                else -> PlaylistSortOrder.TITLE
            }
            applyFilter()
        }
        binding.toolbar.setNavigationOnClickListener { exitArtistDetail() }
        refreshFromHost()
    }

    private fun selectViewMode(mode: PlaylistViewMode) {
        viewMode = mode
        selectedArtist = null
        binding.chipAllSongs.isChecked = mode == PlaylistViewMode.ALL_SONGS
        binding.chipArtists.isChecked = mode == PlaylistViewMode.BY_ARTIST
        updateToolbar()
        applyFilter()
    }

    private fun exitArtistDetail() {
        if (selectedArtist == null) return
        selectedArtist = null
        updateToolbar()
        applyFilter()
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
        PlaylistSortOrder.DURATION_ASC -> list.sortedWith(
            compareBy<Song> { if (it.durationMs <= 0L) Long.MAX_VALUE else it.durationMs }.thenBy { it.title.lowercase() }
        )
        PlaylistSortOrder.DURATION_DESC -> list.sortedWith(
            compareByDescending<Song> { if (it.durationMs <= 0L) Long.MIN_VALUE else it.durationMs }
                .thenBy { it.title.lowercase() }
        )
    }

    private fun updateToolbar() {
        val inArtistDetail = viewMode == PlaylistViewMode.BY_ARTIST && selectedArtist != null
        binding.toolbar.navigationIcon = if (inArtistDetail) {
            requireContext().getDrawable(android.R.drawable.ic_menu_revert)
        } else null
        binding.toolbar.subtitle = when {
            selectedArtist != null -> selectedArtist
            viewMode == PlaylistViewMode.BY_ARTIST -> getString(R.string.filter_artists)
            else -> null
        }
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }
}
