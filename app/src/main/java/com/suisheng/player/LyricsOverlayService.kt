package com.suisheng.player

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import com.suisheng.player.data.SettingsRepository
import com.suisheng.player.model.LyricState
import com.suisheng.player.ui.KaraokeLyricView

class LyricsOverlayService : Service() {
    private var windowManager: WindowManager? = null
    private var overlayView: KaraokeLyricView? = null
    private lateinit var settings: SettingsRepository
    private var lastLyricState: LyricState? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        settings = SettingsRepository(this)
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!settings.overlayEnabled) {
            removeOverlay()
            stopSelf()
            return START_NOT_STICKY
        }
        showOverlayIfNeeded()
        lastLyricState?.let { overlayView?.update(it) }
        return START_STICKY
    }

    override fun onDestroy() {
        instance = null
        removeOverlay()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun showOverlayIfNeeded() {
        if (overlayView != null) return
        if (AppForegroundTracker.isInForeground) return
        if (!settings.overlayEnabled || !Settings.canDrawOverlays(this)) return

        val view = KaraokeLyricView(this)
        val density = resources.displayMetrics.density
        val maxLines = settings.maxLyricVisualLines.coerceIn(1, 4)
        val heightPx = ((settings.fontSizeSp * maxLines * 2.8f + 24f) * density).toInt()
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            heightPx,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = gravityFromSettings()
        params.y = yOffsetFromSettings()
        overlayView = view
        windowManager?.addView(view, params)
        lastLyricState?.let { view.update(it) }
    }

    private fun removeOverlay() {
        overlayView?.let { windowManager?.removeView(it) }
        overlayView = null
    }

    private fun gravityFromSettings(): Int {
        return when (settings.overlayPosition) {
            SettingsRepository.POSITION_TOP -> Gravity.TOP or Gravity.CENTER_HORIZONTAL
            SettingsRepository.POSITION_BOTTOM -> Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            else -> Gravity.CENTER
        }
    }

    private fun yOffsetFromSettings(): Int {
        val density = resources.displayMetrics.density
        return when (settings.overlayPosition) {
            SettingsRepository.POSITION_TOP -> (24 * density).toInt()
            SettingsRepository.POSITION_BOTTOM -> (48 * density).toInt()
            else -> 0
        }
    }

    private fun updateOverlay(state: LyricState) {
        lastLyricState = state
        overlayView?.update(state)
    }

    private fun refreshLayout() {
        val state = lastLyricState
        removeOverlay()
        showOverlayIfNeeded()
        state?.let { overlayView?.update(it) }
    }

    private fun onForegroundChanged(inForeground: Boolean) {
        if (inForeground) {
            removeOverlay()
        } else if (settings.overlayEnabled && Settings.canDrawOverlays(this)) {
            showOverlayIfNeeded()
            lastLyricState?.let { overlayView?.update(it) }
        }
    }

    companion object {
        private var instance: LyricsOverlayService? = null

        fun start(context: Context) {
            if (!Settings.canDrawOverlays(context)) return
            context.startService(Intent(context, LyricsOverlayService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, LyricsOverlayService::class.java))
        }

        fun updateLyrics(context: Context, state: LyricState) {
            instance?.updateOverlay(state)
        }

        fun refresh(context: Context) {
            instance?.refreshLayout()
        }

        fun onAppForegroundChanged(inForeground: Boolean) {
            instance?.onForegroundChanged(inForeground)
        }
    }
}
