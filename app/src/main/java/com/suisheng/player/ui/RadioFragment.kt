package com.suisheng.player.ui

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.suisheng.player.R
import com.suisheng.player.data.RadioBrowserApi
import com.suisheng.player.data.SettingsRepository
import com.suisheng.player.databinding.FragmentRadioBinding
import com.suisheng.player.model.LibraryKind
import com.suisheng.player.model.RadioStation
import com.suisheng.player.model.Song
import com.suisheng.player.playback.PlaybackStateHolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.google.android.material.color.MaterialColors

class RadioFragment : Fragment(), PlaybackStateHolder.Listener {
    private var _binding: FragmentRadioBinding? = null
    private val binding get() = _binding!!
    private lateinit var api: RadioBrowserApi
    private var stations: List<RadioStation> = emptyList()
    private var playingUuid: String? = null
    private var lastClickMs = 0L

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentRadioBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val settings = SettingsRepository(requireContext())
        api = RadioBrowserApi(settings)
        binding.stationList.layoutManager = LinearLayoutManager(requireContext())
        binding.stationList.adapter = StationAdapter()
        binding.chipChinese.setOnClickListener { loadChinese() }
        binding.chipMusic.setOnClickListener { loadTag("pop") }
        binding.searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                filterLocal(s?.toString().orEmpty())
            }
        })
        loadChinese()
    }

    private fun loadChinese() {
        binding.chipChinese.isChecked = true
        binding.chipMusic.isChecked = false
        setLoading(true)
        lifecycleScope.launch {
            stations = withContext(Dispatchers.IO) { api.topChinese() }
            setLoading(false)
            renderStations(stations)
        }
    }

    private fun loadTag(tag: String) {
        binding.chipChinese.isChecked = false
        binding.chipMusic.isChecked = true
        setLoading(true)
        lifecycleScope.launch {
            stations = withContext(Dispatchers.IO) { api.byTag(tag) }
            setLoading(false)
            renderStations(stations)
        }
    }

    private fun filterLocal(query: String) {
        if (query.isBlank()) {
            renderStations(stations)
            return
        }
        val q = query.lowercase()
        renderStations(stations.filter { it.name.lowercase().contains(q) || it.tags.lowercase().contains(q) })
    }

    private fun renderStations(list: List<RadioStation>) {
        (binding.stationList.adapter as? StationAdapter)?.submit(list)
        binding.emptyText.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        binding.stationList.visibility = if (list.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun setLoading(loading: Boolean) {
        binding.loading.visibility = if (loading) View.VISIBLE else View.GONE
    }

    private fun playStation(station: RadioStation) {
        val now = System.currentTimeMillis()
        if (now - lastClickMs < 280) return
        lastClickMs = now
        val songs = stations.map { api.songFromStation(it) }
        val index = stations.indexOfFirst { it.uuid == station.uuid }.coerceAtLeast(0)
        (activity as? MainHost)?.selectDestination(Destination.PLAYER)
        (activity as? MainHost)?.playSongSubset(songs, index, LibraryKind.RADIO)
    }

    override fun onStart() {
        super.onStart()
        PlaybackStateHolder.addListener(this)
        playingUuid = PlaybackStateHolder.currentSong?.path?.let {
            com.suisheng.player.util.MediaPath.parseRadioUuid(it)
        }
        binding.stationList.adapter?.notifyDataSetChanged()
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
        playingUuid = song?.path?.let { com.suisheng.player.util.MediaPath.parseRadioUuid(it) }
        binding.stationList.adapter?.notifyDataSetChanged()
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    private inner class StationAdapter : RecyclerView.Adapter<StationAdapter.Holder>() {
        private var items: List<RadioStation> = emptyList()

        inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
            val title: TextView = view.findViewById(R.id.songTitle)
            val subtitle: TextView = view.findViewById(R.id.songArtist)
            val duration: TextView = view.findViewById(R.id.songDuration)
        }

        fun submit(list: List<RadioStation>) {
            items = list
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val view = layoutInflater.inflate(R.layout.item_song, parent, false)
            return Holder(view)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val station = items[position]
            holder.title.text = station.name
            holder.subtitle.text = station.tags.ifBlank { station.country }
            holder.duration.text = if (station.bitrate > 0) "${station.bitrate}k" else "LIVE"
            holder.itemView.setOnClickListener { playStation(station) }
            val playing = station.uuid == playingUuid
            holder.itemView.setBackgroundColor(
                if (playing) MaterialColors.getColor(holder.itemView, com.google.android.material.R.attr.colorSurfaceContainerHigh)
                else android.graphics.Color.TRANSPARENT
            )
        }

        override fun getItemCount(): Int = items.size
    }
}
