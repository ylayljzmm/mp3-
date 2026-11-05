package com.suisheng.player

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.view.KeyEvent
import androidx.core.content.ContextCompat
import com.suisheng.player.data.SettingsRepository
import com.suisheng.player.playback.PlaybackStateHolder

/**
 * 尽量优先接管方向盘 / 蓝牙媒体键，避免切到车机自带播放器。
 */
class CarMediaButtonReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_MEDIA_BUTTON) return
        if (!shouldHandle(context)) return

        val keyEvent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT)
        } ?: return

        val action = when (keyEvent.keyCode) {
            KeyEvent.KEYCODE_MEDIA_NEXT -> MusicPlaybackService.ACTION_NEXT
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> MusicPlaybackService.ACTION_PREV
            KeyEvent.KEYCODE_MEDIA_PLAY -> MusicPlaybackService.ACTION_PLAY
            KeyEvent.KEYCODE_MEDIA_PAUSE -> MusicPlaybackService.ACTION_PAUSE
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_HEADSETHOOK -> MusicPlaybackService.ACTION_TOGGLE
            KeyEvent.KEYCODE_MEDIA_STOP -> MusicPlaybackService.ACTION_STOP
            else -> null
        }
        if (keyEvent.action == KeyEvent.ACTION_DOWN && action != null) {
            runCatching {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, MusicPlaybackService::class.java).apply { this.action = action }
                )
            }.onFailure {
                // Some head units deny background FGS starts. Fall back to startService.
                runCatching {
                    context.startService(
                        Intent(context, MusicPlaybackService::class.java).apply { this.action = action }
                    )
                }
            }
        }
        if (isOrderedBroadcast) {
            abortBroadcast()
        }
    }

    private fun shouldHandle(context: Context): Boolean {
        val settings = SettingsRepository(context)
        return PlaybackStateHolder.isPlaying ||
            PlaybackStateHolder.songs.isNotEmpty() ||
            settings.lastSongPath(settings.lastActiveLibrary) != null
    }
}
