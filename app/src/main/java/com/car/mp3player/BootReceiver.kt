package com.car.mp3player

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.car.mp3player.data.SettingsRepository
import com.car.mp3player.playback.PlaybackStateHolder

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        // A receiver that throws is reported as the app crashing at boot. Nothing in this
        // chain is worth that: every branch is best-effort resume.
        runCatching {
            when (intent?.action) {
                // ACTION_LOCKED_BOOT_COMPLETED is deliberately not handled: this app is
                // not directBootAware, so the system never delivers it, and reading
                // preferences in that window would throw because credential-encrypted
                // storage is still locked.
                Intent.ACTION_BOOT_COMPLETED,
                ACTION_QUICKBOOT,
                ACTION_HTC_QUICKBOOT,
                ACTION_REBOOT -> startBootResume(context)
                Intent.ACTION_USER_PRESENT -> {
                    if (!shouldResumeOnUnlock(context)) return@runCatching
                    val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    val now = System.currentTimeMillis()
                    val last = prefs.getLong(KEY_LAST_UNLOCK_RESUME, 0L)
                    if (now - last < UNLOCK_DEBOUNCE_MS) return@runCatching
                    prefs.edit().putLong(KEY_LAST_UNLOCK_RESUME, now).apply()
                    startBootResume(context)
                }
            }
        }.onFailure {
            android.util.Log.e("BootReceiver", "onReceive failed for ${intent?.action}", it)
        }
    }

    private fun shouldResumeOnUnlock(context: Context): Boolean {
        val settings = SettingsRepository(context)
        if (!settings.bootAutoStart || !settings.autoResumePlayback) return false
        if (PlaybackStateHolder.isPlaying) return false
        return true
    }

    private fun startBootResume(context: Context) {
        runCatching {
            val settings = SettingsRepository(context)
            if (!settings.bootAutoStart || !settings.autoResumePlayback) return
            // On Android 12+ a background foreground-service start can be refused
            // outright. That must never escape onReceive — an exception thrown from a
            // manifest receiver is reported as the app crashing at boot, which is
            // exactly the symptom we are chasing. Fall back to a plain service start:
            // the service itself calls startForeground as soon as it can.
            val intent = Intent(context, BootResumeService::class.java)
            runCatching { ContextCompat.startForegroundService(context, intent) }
                .recoverCatching { context.startService(intent) }
        }.onFailure {
            android.util.Log.e("BootReceiver", "boot resume dispatch failed", it)
        }
    }

    companion object {
        private const val ACTION_QUICKBOOT = "android.intent.action.QUICKBOOT_POWERON"
        private const val ACTION_HTC_QUICKBOOT = "com.htc.intent.action.QUICKBOOT_POWERON"
        private const val ACTION_REBOOT = "android.intent.action.REBOOT"
        private const val PREFS = "boot_receiver"
        private const val KEY_LAST_UNLOCK_RESUME = "last_unlock_resume"
        private const val UNLOCK_DEBOUNCE_MS = 60_000L
    }
}
