package com.suisheng.player

import android.app.Presentation
import android.content.Context
import android.os.Bundle
import android.view.Display
import android.view.WindowManager
import android.widget.TextView
import com.suisheng.player.model.LrcLine
import com.suisheng.player.model.Song
import com.suisheng.player.playback.PlaybackStateHolder
import com.suisheng.player.ui.ScrollLyricView

class ClusterLyricPresentation(
    context: Context,
    display: Display
) : Presentation(context, display) {

    private var titleView: TextView? = null
    private var artistView: TextView? = null
    private var lyricView: ScrollLyricView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.presentation_cluster_lyric)
        titleView = findViewById(R.id.clusterTitle)
        artistView = findViewById(R.id.clusterArtist)
        lyricView = findViewById(R.id.clusterLyricView)
        bindSong(PlaybackStateHolder.currentSong)
    }

    fun update(song: Song?, lines: List<LrcLine>, positionMs: Long) {
        bindSong(song)
        lyricView?.update(lines, positionMs)
    }

    fun refreshStyle() {
        lyricView?.refreshStyle()
    }

    private fun bindSong(song: Song?) {
        titleView?.text = song?.title.orEmpty().ifBlank { context.getString(R.string.app_name) }
        artistView?.text = song?.artist.orEmpty().ifBlank { context.getString(R.string.unknown_artist) }
    }
}
