package com.suisheng.player.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.suisheng.player.R
import com.suisheng.player.data.RssFeedRepository
import com.suisheng.player.data.SettingsRepository
import com.suisheng.player.databinding.FragmentPodcastBinding
import com.suisheng.player.model.LibraryKind
import com.suisheng.player.model.PodcastEpisode
import com.suisheng.player.model.PodcastFeed
import com.suisheng.player.model.Song
import com.suisheng.player.playback.PlaybackStateHolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.google.android.material.color.MaterialColors

class PodcastFragment : Fragment(), PlaybackStateHolder.Listener {
    private var _binding: FragmentPodcastBinding? = null
    private val binding get() = _binding!!
    private lateinit var repo: RssFeedRepository
    private var feeds: List<PodcastFeed> = emptyList()
    private var episodes: List<PodcastEpisode> = emptyList()
    private var playingPath: String? = null
    private var lastClickMs = 0L

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentPodcastBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val settings = SettingsRepository(requireContext())
        repo = RssFeedRepository(requireContext(), settings)
        binding.feedList.layoutManager = LinearLayoutManager(requireContext(), RecyclerView.HORIZONTAL, false)
        binding.feedList.adapter = FeedAdapter()
        binding.episodeList.layoutManager = LinearLayoutManager(requireContext())
        binding.episodeList.adapter = EpisodeAdapter()
        reloadFeeds()
    }

    private fun reloadFeeds() {
        feeds = repo.configuredFeeds()
        (binding.feedList.adapter as FeedAdapter).submit(feeds)
        if (feeds.isEmpty()) {
            binding.emptyText.visibility = View.VISIBLE
            binding.episodeList.visibility = View.GONE
            binding.feedList.visibility = View.GONE
        } else {
            binding.emptyText.visibility = View.GONE
            binding.feedList.visibility = View.VISIBLE
            loadFeed(feeds.first())
        }
    }

    private fun loadFeed(feed: PodcastFeed) {
        binding.loading.visibility = View.VISIBLE
        lifecycleScope.launch {
            episodes = withContext(Dispatchers.IO) { repo.fetchEpisodes(feed) }
            binding.loading.visibility = View.GONE
            if (episodes.isEmpty()) {
                Toast.makeText(requireContext(), R.string.podcast_load_failed, Toast.LENGTH_SHORT).show()
            }
            // 当前展开的是哪个播客：原来挂在 `MaterialToolbar.subtitle` 上，
            // 标题条删掉后改挂全局页头的副标题，信息一点没少。
            setPageSubtitle(feed.title)
            (binding.episodeList.adapter as EpisodeAdapter).submit(episodes)
            binding.episodeList.visibility = View.VISIBLE
            binding.emptyText.visibility = if (episodes.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    private fun playEpisode(episode: PodcastEpisode, index: Int) {
        val now = System.currentTimeMillis()
        if (now - lastClickMs < 280) return
        lastClickMs = now
        val songs = episodes.map { repo.songFromEpisode(it) }
        (activity as? MainHost)?.selectDestination(Destination.PLAYER)
        (activity as? MainHost)?.playSongSubset(songs, index, LibraryKind.PODCAST)
    }

    override fun onStart() {
        super.onStart()
        PlaybackStateHolder.addListener(this)
        playingPath = PlaybackStateHolder.currentSong?.path
        binding.episodeList.adapter?.notifyDataSetChanged()
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
        playingPath = song?.path
        binding.episodeList.adapter?.notifyDataSetChanged()
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    private inner class FeedAdapter : RecyclerView.Adapter<FeedAdapter.Holder>() {
        private var items: List<PodcastFeed> = emptyList()

        inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
            val title: TextView = view.findViewById(R.id.title)
        }

        fun submit(list: List<PodcastFeed>) {
            items = list
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val view = layoutInflater.inflate(R.layout.item_chart_chip, parent, false)
            return Holder(view)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val feed = items[position]
            holder.title.text = feed.title
            holder.itemView.setOnClickListener { loadFeed(feed) }
        }

        override fun getItemCount(): Int = items.size
    }

    private inner class EpisodeAdapter : RecyclerView.Adapter<EpisodeAdapter.Holder>() {
        private var items: List<PodcastEpisode> = emptyList()

        inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
            val title: TextView = view.findViewById(R.id.songTitle)
            val subtitle: TextView = view.findViewById(R.id.songArtist)
            val duration: TextView = view.findViewById(R.id.songDuration)
            val playingBadge: TextView = view.findViewById(R.id.playingBadge)
        }

        fun submit(list: List<PodcastEpisode>) {
            items = list
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val view = layoutInflater.inflate(R.layout.item_song, parent, false)
            return Holder(view)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val episode = items[position]
            holder.title.text = episode.title
            holder.subtitle.text = episode.feedTitle
            holder.duration.text = ""
            val path = repo.songFromEpisode(episode).path
            val playing = path == playingPath
            // 方案 I：行不再有单独背景，正在播放只靠标题强调色 + 标记区分。
            holder.title.setTextColor(
                MaterialColors.getColor(
                    holder.title,
                    if (playing) com.google.android.material.R.attr.colorPrimary
                    else com.google.android.material.R.attr.colorOnSurface
                )
            )
            holder.playingBadge.visibility = if (playing) View.VISIBLE else View.GONE
            holder.itemView.setOnClickListener { playEpisode(episode, position) }
        }

        override fun getItemCount(): Int = items.size
    }
}
