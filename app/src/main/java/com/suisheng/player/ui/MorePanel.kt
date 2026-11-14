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
 * 面板只负责「把运行时状态渲染成条目 + 回调宿主」，不碰播放器也不碰存储。
 *
 * 条目：
 * 1. 收藏 / 取消收藏（文案由调用方按 [isFavorite] 决定）；
 * 2. 从播放队列移除（仅当当前曲目确实在队列里且队列长度 > 1）；
 * 3. 歌曲信息（弹 `MaterialAlertDialogBuilder` 逐行列字段）；
 * 4. 重新加载专辑封面（强制重取并刷新黑胶中心封面）；
 * 5. 取消。
 *
 * 5.9 去掉了「歌词调节」：它与顶栏常驻的「调」通向同一个面板，留两处只是
 * 多一层菜单。播放页顶栏仍是唯一常驻入口。
 */
object MorePanel {

    /**
     * 弹出更多面板。
     *
     * @param song 当前播放曲目
     * @param isFavorite 当前曲目是否已收藏（决定第 1 条的文案）
     * @param canRemoveFromQueue 是否显示「从播放队列移除」
     * @param reloadCoverEnabled 封面重载任务进行中传 false：条目置灰且不注册点击，避免并发解析
     * @param onReloadCover 点了「重新加载专辑封面」（面板随后关闭）
     */
    fun show(
        context: Context,
        song: Song,
        isFavorite: Boolean,
        canRemoveFromQueue: Boolean,
        onToggleFavorite: () -> Unit,
        onRemoveFromQueue: () -> Unit,
        reloadCoverEnabled: Boolean = true,
        onReloadCover: () -> Unit = {},
    ) {
        val binding = PanelMoreBinding.inflate(LayoutInflater.from(context))
        // 条目不高，但小屏 + 大字体时仍可能超屏高：交给 PanelHost 套一层滚动容器
        val dialog = PanelHost.build(context, binding.root, scrollable = true)

        binding.moreFavorite.text = context.getString(
            if (isFavorite) R.string.favorite_remove else R.string.favorite_add
        )
        binding.moreRemove.isVisible = canRemoveFromQueue

        binding.moreReloadCover.isEnabled = reloadCoverEnabled
        binding.moreReloadCover.alpha = if (reloadCoverEnabled) 1f else 0.45f

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
        if (reloadCoverEnabled) {
            binding.moreReloadCover.setOnClickListener {
                // 先关面板再回调：避免加载结果与菜单叠在一起
                dialog.dismiss()
                onReloadCover()
            }
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
