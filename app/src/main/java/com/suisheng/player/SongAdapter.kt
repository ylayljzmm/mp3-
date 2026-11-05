package com.suisheng.player

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.suisheng.player.databinding.ItemSongBinding
import com.suisheng.player.model.Song
import com.suisheng.player.util.TimeFormat
import com.google.android.material.color.MaterialColors

class SongAdapter(
    private val onClick: (Song, Int) -> Unit,
    private val onLongClick: ((Song, Int) -> Unit)? = null
) : ListAdapter<Song, SongAdapter.SongViewHolder>(DIFF) {

    var playingPath: String? = null
        set(value) {
            if (field == value) return
            val old = field
            field = value
            currentList.forEachIndexed { index, song ->
                if (song.path == old || song.path == value) {
                    notifyItemChanged(index, PAYLOAD_PLAYING)
                }
            }
        }

    fun submitSongs(list: List<Song>) {
        submitList(list)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SongViewHolder {
        val binding = ItemSongBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return SongViewHolder(binding)
    }

    override fun onBindViewHolder(holder: SongViewHolder, position: Int) {
        holder.bind(getItem(position), position)
    }

    override fun onBindViewHolder(holder: SongViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.contains(PAYLOAD_PLAYING)) {
            holder.bindPlayingState(getItem(position))
        } else {
            super.onBindViewHolder(holder, position, payloads)
        }
    }

    inner class SongViewHolder(private val binding: ItemSongBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(song: Song, position: Int) {
            binding.songTitle.text = song.title
            binding.songArtist.text = song.artist
            binding.songDuration.text = TimeFormat.mmss(song.durationMs)
            binding.lrcBadge.visibility = if (song.lrcPath != null) android.view.View.VISIBLE else android.view.View.GONE
            bindPlayingState(song)
            binding.root.setOnClickListener { onClick(song, position) }
            binding.root.setOnLongClickListener {
                onLongClick?.invoke(song, position)
                onLongClick != null
            }
        }

        fun bindPlayingState(song: Song) {
            val isPlaying = song.path == playingPath
            binding.playingBadge.visibility = if (isPlaying) android.view.View.VISIBLE else android.view.View.GONE
            binding.root.setBackgroundColor(
                if (isPlaying) MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorSurfaceContainerHigh)
                else android.graphics.Color.TRANSPARENT
            )
        }
    }

    companion object {
        private const val PAYLOAD_PLAYING = "playing"
        private val DIFF = object : DiffUtil.ItemCallback<Song>() {
            override fun areItemsTheSame(oldItem: Song, newItem: Song) = oldItem.path == newItem.path
            override fun areContentsTheSame(oldItem: Song, newItem: Song) = oldItem == newItem
        }
    }
}
