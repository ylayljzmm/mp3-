package com.suisheng.player.ui

import android.content.Intent
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.suisheng.player.MusicPlaybackService
import com.suisheng.player.R
import com.suisheng.player.databinding.ActivityMainBinding
import com.suisheng.player.model.LrcLine
import com.suisheng.player.model.PlaybackMode
import com.suisheng.player.model.Song
import com.suisheng.player.playback.PlaybackStateHolder
import com.suisheng.player.util.CoverBitmap

/**
 * 迷你播放条（`design/04 §3.0`：64dp，只在二级页出现）。
 *
 * 它补的是「抽屉取代 tab」之后丢掉的那个问题：从播放页去了别的页就回不去播放页。
 * 现在任何二级页底部都挂着这一条，点一下就回播放页，播放键则就地播放/暂停。
 *
 * 播放页自己**不显示**它（沿用 03 §9）：播放页已经有整套控件，再挂一条是重复。
 *
 * 状态直接读 [PlaybackStateHolder]，不持有任何播放逻辑 —— 播放/暂停是给 Service 发 Intent，
 * 和播放页走的是同一条链路。
 */
class MiniPlayerController(
    private val activity: AppCompatActivity,
    private val binding: ActivityMainBinding,
    private val onOpenPlayer: () -> Unit,
) : PlaybackStateHolder.Listener {

    /** 当前页面是播放页时置 true（整条隐藏）。 */
    private var hiddenByDestination = false

    /** 上一次渲染的关键字，挡住每秒 8 次的进度回调。 */
    private var renderedKey: String? = null

    private var coverPath: String? = null

    fun bind() {
        binding.miniPlayer.miniPlayerBody.setOnClickListener { onOpenPlayer() }
        binding.miniPlayer.miniPlay.setOnClickListener { togglePlayback() }
    }

    /** 切页时由宿主告知：播放页要隐藏，其它页要显示（前提是有歌）。 */
    fun setHiddenForDestination(hidden: Boolean) {
        if (hiddenByDestination == hidden) return
        hiddenByDestination = hidden
        render()
    }

    override fun onPlaybackChanged(
        song: Song?,
        playing: Boolean,
        positionMs: Long,
        lines: List<LrcLine>,
    ) {
        val key = "${song?.path}|$playing"
        if (key == renderedKey) return
        renderedKey = key
        render()
    }

    override fun onPlaylistChanged(songs: List<Song>) {
        renderedKey = null
        render()
    }

    override fun onCoverChanged(coverPath: String?) {
        this.coverPath = coverPath
        renderCover()
    }

    override fun onPlayModeChanged(mode: PlaybackMode) {
        // 迷你条不展示播放模式，无需响应
    }

    private fun render() {
        val song = PlaybackStateHolder.currentSong
        val visible = song != null && !hiddenByDestination
        binding.miniPlayer.root.visibility = if (visible) View.VISIBLE else View.GONE
        if (!visible) return
        binding.miniPlayer.miniTitle.text = song?.title.orEmpty()
        binding.miniPlayer.miniArtist.text = song?.artist.orEmpty()
        val playing = PlaybackStateHolder.isPlaying
        binding.miniPlayer.miniPlay.setImageResource(
            if (playing) R.drawable.ic_pause else R.drawable.ic_play,
        )
        binding.miniPlayer.miniPlay.contentDescription =
            activity.getString(if (playing) R.string.pause else R.string.play)
        renderCover()
    }

    private fun renderCover() {
        // 迷你条封面上限 48dp × 3 密度 ≈ 144px；解码到 192px 足够，且比播放页便宜得多
        val bitmap = CoverBitmap.decode(coverPath, MINI_COVER_PX)
        binding.miniPlayer.miniCover.setImageBitmap(bitmap)
    }

    private fun togglePlayback() {
        if (PlaybackStateHolder.songs.isEmpty()) return
        runCatching {
            ContextCompat.startForegroundService(
                activity,
                Intent(activity, MusicPlaybackService::class.java).apply {
                    action = MusicPlaybackService.ACTION_TOGGLE
                },
            )
        }
    }

    private companion object {
        const val MINI_COVER_PX = 192
    }
}
