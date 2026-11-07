package com.suisheng.player

import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.IBinder
import android.view.Display
import com.suisheng.player.data.SettingsRepository
import com.suisheng.player.model.LrcLine
import com.suisheng.player.model.Song
import com.suisheng.player.playback.PlaybackStateHolder

class ClusterLyricService : Service(), PlaybackStateHolder.Listener {
    private lateinit var settings: SettingsRepository
    private var presentation: ClusterLyricPresentation? = null
    private var attachedDisplayId: Int = Display.INVALID_DISPLAY

    override fun onCreate() {
        super.onCreate()
        instance = this
        settings = SettingsRepository(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!settings.clusterLyricsEnabled) {
            hidePresentation()
            stopSelf()
            return START_NOT_STICKY
        }
        showPresentationIfPossible()
        // onStartCommand 每次 start() 都会来一遍（切歌时 SettingsFragment / 播放服务都可能再发），
        // 不先去重就会累积同一份 listener，一次进度回调被投递 N 次。
        PlaybackStateHolder.removeListener(this)
        PlaybackStateHolder.addListener(this)
        onPlaybackChanged(
            PlaybackStateHolder.currentSong,
            PlaybackStateHolder.isPlaying,
            PlaybackStateHolder.positionMs,
            PlaybackStateHolder.lrcLines
        )
        return START_STICKY
    }

    override fun onDestroy() {
        PlaybackStateHolder.removeListener(this)
        hidePresentation()
        instance = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onPlaybackChanged(
        song: Song?,
        playing: Boolean,
        positionMs: Long,
        lines: List<LrcLine>
    ) {
        presentation?.update(song, lines, positionMs)
    }

    private fun showPresentationIfPossible() {
        val display = findClusterDisplay() ?: return
        if (presentation != null && attachedDisplayId == display.displayId) return
        hidePresentation()
        runCatching {
            presentation = ClusterLyricPresentation(this, display).also {
                it.show()
                attachedDisplayId = display.displayId
            }
        }
    }

    private fun findClusterDisplay(): Display? {
        val manager = getSystemService(DisplayManager::class.java) ?: return null
        val displays = manager.displays
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            displays.firstOrNull {
                it.displayId != Display.DEFAULT_DISPLAY &&
                    (it.flags and Display.FLAG_PRESENTATION) != 0
            }?.let { return it }
        }
        return displays
            .filter { it.displayId != Display.DEFAULT_DISPLAY }
            .minByOrNull { it.mode?.physicalWidth ?: Int.MAX_VALUE }
            ?: displays.firstOrNull { it.displayId != Display.DEFAULT_DISPLAY }
    }

    private fun hidePresentation() {
        presentation?.dismiss()
        presentation = null
        attachedDisplayId = Display.INVALID_DISPLAY
    }

    fun refreshPresentation() {
        hidePresentation()
        if (!settings.clusterLyricsEnabled) return
        showPresentationIfPossible()
        presentation?.refreshStyle()
    }

    companion object {
        private var instance: ClusterLyricService? = null

        fun start(context: Context) {
            if (!SettingsRepository(context).clusterLyricsEnabled) return
            context.startService(Intent(context, ClusterLyricService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ClusterLyricService::class.java))
        }

        fun refresh(context: Context) {
            instance?.refreshPresentation() ?: start(context)
        }
    }
}
