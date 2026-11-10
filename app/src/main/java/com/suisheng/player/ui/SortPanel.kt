package com.suisheng.player.ui

import android.content.Context
import android.view.LayoutInflater
import com.google.android.material.color.MaterialColors
import com.suisheng.player.R
import com.suisheng.player.databinding.PanelSortBinding
import com.suisheng.player.model.PlaylistSortOrder

/**
 * 排序方式面板（方案 I 歌曲页）：8 个单选项，选完立即回调宿主重排列表。
 * 面板本身不持有曲库、不写设置，排序只在当前页面会话内生效。
 */
object SortPanel {

    val OPTIONS = listOf(
        PlaylistSortOrder.TITLE to R.string.sort_title_az,
        PlaylistSortOrder.TITLE_DESC to R.string.sort_title_za,
        PlaylistSortOrder.ARTIST to R.string.sort_artist,
        PlaylistSortOrder.ALBUM to R.string.sort_album,
        PlaylistSortOrder.DURATION_ASC to R.string.sort_duration_asc,
        PlaylistSortOrder.DURATION_DESC to R.string.sort_duration_desc,
        PlaylistSortOrder.RECENT to R.string.sort_recent,
        PlaylistSortOrder.PATH to R.string.sort_path,
    )

    fun labelOf(order: PlaylistSortOrder): Int =
        OPTIONS.firstOrNull { it.first == order }?.second ?: R.string.sort_title_az

    fun show(context: Context, current: PlaylistSortOrder, onPick: (PlaylistSortOrder) -> Unit) {
        val binding = PanelSortBinding.inflate(LayoutInflater.from(context))
        // 与 SpeedPanel 同理：交给 PanelHost 统一限高与定宽，车机 / 手机各走各的形态。
        val dialog = PanelHost.build(context, binding.root, scrollable = true)

        val rows = listOf(
            binding.sortTitleAz,
            binding.sortTitleZa,
            binding.sortArtist,
            binding.sortAlbum,
            binding.sortDurationAsc,
            binding.sortDurationDesc,
            binding.sortRecent,
            binding.sortPath,
        )

        fun render(selected: PlaylistSortOrder) {
            rows.forEachIndexed { index, row ->
                val active = OPTIONS[index].first == selected
                row.setTextColor(
                    MaterialColors.getColor(
                        row,
                        if (active) {
                            com.google.android.material.R.attr.colorPrimary
                        } else {
                            com.google.android.material.R.attr.colorOnSurface
                        },
                    )
                )
                row.setTypeface(
                    null,
                    if (active) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL,
                )
            }
        }

        rows.forEachIndexed { index, row ->
            row.setOnClickListener {
                val picked = OPTIONS[index].first
                render(picked)
                onPick(picked)
                dialog.dismiss()
            }
        }

        render(current)
        dialog.show()
    }
}
