package com.suisheng.player.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.suisheng.player.databinding.ItemLibraryGroupBinding
import com.suisheng.player.databinding.ItemLibraryGroupRowBinding
import com.suisheng.player.model.LibraryGroup

/**
 * 分组页的适配器：一个类同时服务「专辑墙格子」与「艺术家/文件夹行」。
 *
 * 两种形态的差别只有布局与少量字段，做成两个适配器会得到两份 90% 相同的
 * 「submit + DiffUtil + 点击」代码；这里用 [Mode] 决定 inflate 哪个布局，
 * 绑定时按非空的那个 binding 分支。
 *
 * @param mode 格子（专辑墙）还是行（艺术家/文件夹）
 * @param coverLoader 只有格子模式需要封面；行模式传 null
 * @param countText 计数文案，由页面决定（「12 首」）
 * @param onClick 整格/整行点击
 */
class LibraryGroupAdapter(
    private val mode: Mode,
    private val coverLoader: AlbumCoverLoader?,
    private val countText: (LibraryGroup) -> String,
    private val onClick: (LibraryGroup) -> Unit,
) : RecyclerView.Adapter<LibraryGroupAdapter.GroupViewHolder>() {

    enum class Mode { GRID, LIST }

    private var items: List<LibraryGroup> = emptyList()

    fun submit(groups: List<LibraryGroup>) {
        val old = items
        items = groups
        DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize(): Int = old.size
            override fun getNewListSize(): Int = items.size
            override fun areItemsTheSame(oldPosition: Int, newPosition: Int): Boolean =
                old[oldPosition].key == items[newPosition].key

            override fun areContentsTheSame(oldPosition: Int, newPosition: Int): Boolean =
                old[oldPosition] == items[newPosition]
        }).dispatchUpdatesTo(this)
    }

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): GroupViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (mode == Mode.GRID) {
            GroupViewHolder(grid = ItemLibraryGroupBinding.inflate(inflater, parent, false))
        } else {
            GroupViewHolder(row = ItemLibraryGroupRowBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: GroupViewHolder, position: Int) {
        val group = items[position]
        holder.grid?.let { grid ->
            grid.cardTitle.text = group.title
            grid.cardCount.text = countText(group)
            coverLoader?.into(
                cover = grid.cardCover,
                letterView = grid.cardLetter,
                key = group.key,
                letter = group.title,
                source = group.songs.firstOrNull(),
            )
            grid.root.setOnClickListener { onClick(group) }
        }
        holder.row?.let { row ->
            row.rowTitle.text = group.title
            row.rowCount.text = countText(group)
            row.rowLetter.text = group.title.trim().take(1).uppercase()
            row.root.setOnClickListener { onClick(group) }
        }
    }

    /** 两种布局共用一个 ViewHolder：哪边非空就 bind 哪边。 */
    class GroupViewHolder(
        val grid: ItemLibraryGroupBinding? = null,
        val row: ItemLibraryGroupRowBinding? = null,
    ) : RecyclerView.ViewHolder(grid?.root ?: row!!.root)
}
