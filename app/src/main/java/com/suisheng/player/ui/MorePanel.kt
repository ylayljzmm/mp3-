package com.suisheng.player.ui

import android.content.Context
import android.view.LayoutInflater
import androidx.core.view.isVisible
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.suisheng.player.R
import com.suisheng.player.databinding.PanelMoreBinding
import com.suisheng.player.model.Song
import com.suisheng.player.util.TimeFormat

/**
 * 「更多」面板（`design/05` 顶栏 `btnMore`）。
 *
 * 与 [SpeedPanel] / [QueuePanel] 同模式：`object` + [PanelHost] + ViewBinding，
 * 面板只负责「把运行时状态渲染成 4 个条目 + 回调宿主」，不碰播放器也不碰存储。
 *
 * 条目：
 * 1. 收藏 / 取消收藏（文案由调用方按 [isFavorite] 决定）；
 * 2. 从播放队列移除（仅当当前曲目确实在队列里且队列长度 > 1）；
 * 3. 歌曲信息（弹 `MaterialAlertDialogBuilder` 逐行列字段）；
 * 4. 取消。
 */
object MorePanel {

    /**
     * 弹出更多面板。
     *
     * @param song 当前播放曲目
     * @param isFavorite 当前曲目是否已收藏（决定第 1 条的文案）
     * @param canRemoveFromQueue 是否显示「从播放队列移除」
     * @param onToggleFavorite 点了收藏 / 取消收藏（面板随后关闭）
     * @param onRemoveFromQueue 点了从队列移除（面板随后关闭）
     */
    fun show(
        context: Context,
        song: Song,
        isFavorite: Boolean,
        canRemoveFromQueue: Boolean,
        onToggleFavorite: () -> Unit,
        onRemoveFromQueue: () -> Unit,
    ) {
        val binding = PanelMoreBinding.inflate(LayoutInflater.from(context))
        // 三行按钮不高，但小屏 + 大字体时仍可能超屏高：交给 PanelHost 套一层滚动容器
        val dialog = PanelHost.build(context, binding.root, scrollable = true)

        binding.moreFavorite.text = context.getString(
            if (isFavorite) R.string.favorite_remove else R.string.favorite_add
        )
        binding.moreRemove.isVisible = canRemoveFromQueue

        binding.moreFavorite.setOnClickListener {
            dialog.dismiss()
            onToggleFavorite()
        }
        binding.moreRemove.setOnClickListener {
            dialog.dismiss()
            onRemoveFromQueue()
        }
        binding.moreInfo.setOnClickListener {
            dialog.dismiss()
            showSongInfo(context, song)
        }
        binding.moreCancel.setOnClickListener { dialog.dismiss() }

        dialog.show()
    }

    /** 歌曲信息：标题 / 歌手 / 专辑 / 时长 / 文件路径 / 歌词路径，逐行一条。 */
    private fun showSongInfo(context: Context, song: Song) {
        val unknown = context.getString(R.string.more_info_unknown)
        val message = buildString {
            appendLine(
                context.getString(
                    R.string.more_info_song,
                    song.title.takeIf { it.isNotBlank() } ?: unknown,
                )
            )
            appendLine(
                context.getString(
                    R.string.more_info_artist,
                    song.artist.takeIf { it.isNotBlank() } ?: unknown,
                )
            )
            appendLine(
                context.getString(
                    R.string.more_info_album,
                    song.album.takeIf { it.isNotBlank() } ?: unknown,
                )
            )
            appendLine(context.getString(R.string.more_info_duration, TimeFormat.mmss(song.durationMs)))
            appendLine(
                context.getString(
                    R.string.more_info_path,
                    song.path.takeIf { it.isNotBlank() } ?: unknown,
                )
            )
            append(
                context.getString(
                    R.string.more_info_lrc,
                    song.lrcPath?.takeIf { it.isNotBlank() } ?: unknown,
                )
            )
        }
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.more_info_title)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }
}
