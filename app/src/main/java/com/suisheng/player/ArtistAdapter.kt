package com.suisheng.player

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.suisheng.player.databinding.ItemArtistBinding
import com.suisheng.player.model.ArtistGroup

class ArtistAdapter(
    private val onClick: (ArtistGroup) -> Unit
) : ListAdapter<ArtistGroup, ArtistAdapter.ArtistViewHolder>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ArtistViewHolder {
        val binding = ItemArtistBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ArtistViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ArtistViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ArtistViewHolder(private val binding: ItemArtistBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(group: ArtistGroup) {
            binding.artistName.text = group.name
            binding.artistCount.text = binding.root.context.getString(R.string.artist_song_count, group.songCount)
            binding.root.setOnClickListener { onClick(group) }
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<ArtistGroup>() {
            override fun areItemsTheSame(oldItem: ArtistGroup, newItem: ArtistGroup) =
                oldItem.name == newItem.name

            override fun areContentsTheSame(oldItem: ArtistGroup, newItem: ArtistGroup) =
                oldItem == newItem
        }
    }
}
