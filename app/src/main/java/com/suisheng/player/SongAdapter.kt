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
    private val onLongClick: ((Song, Int) -> Unit)? = null,
    private val onSelectionToggle: ((Song) -> Unit)? = null
) : ListAdapter<Song, SongAdapter.SongViewHolder>(DIFF) {

    /** 多选态：默认 false，收藏页 / 在线页不受影响。 */
    var selectionMode: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                notifyDataSetChanged()
            }
        }

    var selectedPaths: Set<String> = emptySet()
        set(value) {
            field = value
            notifyDataSetChanged()
        }

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
            // 方案 I Q-3：多选态下行首显示勾选圈，点击切换选中而不起播。
            binding.songCheck.visibility =
                if (selectionMode) android.view.View.VISIBLE else android.view.View.GONE
            binding.songCheck.isChecked = song.path in selectedPaths
            binding.root.setOnClickListener {
                if (selectionMode) onSelectionToggle?.invoke(song) else onClick(song, position)
            }
            binding.root.setOnLongClickListener {
                onLongClick?.invoke(song, position)
                onLongClick != null
            }
        }

        fun bindPlayingState(song: Song) {
            val isPlaying = song.path == playingPath
            binding.playingBadge.visibility = if (isPlaying) android.view.View.VISIBLE else android.view.View.GONE
            // 方案 I：行不再有单独背景，正在播放只靠标题颜色 + 标记区分。
            binding.songTitle.setTextColor(
                MaterialColors.getColor(
                    binding.songTitle,
                    if (isPlaying) {
                        com.google.android.material.R.attr.colorPrimary
                    } else {
                        com.google.android.material.R.attr.colorOnSurface
                    },
                )
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
