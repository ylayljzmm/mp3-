package com.suisheng.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.suisheng.player.data.PlaybackBootstrap
import com.suisheng.player.data.PlaylistCache
import com.suisheng.player.data.SettingsRepository
import com.suisheng.player.model.LibraryKind
import com.suisheng.player.playback.PlaybackStateHolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Best-effort "resume where you left off" after the head unit powers on.
 *
 * Hard rule for this class: it must never be the reason the app crashes. Every step is
 * failure-tolerant, the foreground notification is entered defensively (Android 12+
 * can refuse the start and Android 14 requires the type to be explicit), and the
 * service always stops itself instead of lingering as a notification.
 */
class BootResumeService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val settings by lazy { SettingsRepository(this) }
    private var foregroundReady = false
    private var workStarted = false

    override fun onCreate() {
        super.onCreate()
        runCatching { createChannel() }
        foregroundReady = enterForeground()
        if (!foregroundReady) {
            // Without a notification we cannot be a foreground service; leaving the
            // service alive would only risk a "did not call startForeground" ANR.
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!foregroundReady || workStarted) return START_NOT_STICKY
        workStarted = true
        scope.launch {
            runCatching { runBootWork() }
                .onFailure { android.util.Log.e(TAG, "boot resume failed", it) }
            shutdown()
        }
        return START_NOT_STICKY
    }

    private suspend fun runBootWork() {
        // Do not fight the user: if playback already started (the app was opened first,
        // or a media key was pressed) there is nothing to resume.
        // The old code waited a fixed 5 s and then grabbed the audio route. On a cold
        // head-unit boot that window is unpredictable, so retry with backoff instead of
        // betting everything on one attempt — but keep the total short, because the
        // "open the app automatically" step waits behind this loop.
        resumeLoop@ for (wait in WAITS_MS) {
            delay(wait)
            if (PlaybackStateHolder.isPlaying) break@resumeLoop
            if (attemptResume()) break@resumeLoop
        }

        if (settings.bootOpenApp) launchMainActivity()
        if (settings.bootReturnHome && settings.bootAutoStart) {
            delay(1200)
            returnToHome()
        }
    }

    private suspend fun attemptResume(): Boolean {
        if (!settings.autoResumePlayback) return false

        val library = settings.lastActiveLibrary
        val songs = withContext(Dispatchers.IO) {
            when (library) {
                LibraryKind.MUSIC -> {
                    PlaybackBootstrap.loadCachedMusic(this@BootResumeService).ifEmpty {
                        PlaybackBootstrap.scanMusicLibrary(this@BootResumeService, settings)
                    }
                }
                else -> PlaylistCache.loadQueue(this@BootResumeService, library)
            }
        }
        if (songs.isEmpty()) return false

        PlaybackStateHolder.setActiveLibrary(library)
        PlaybackStateHolder.setPlaylist(songs, library = library)
        PlaybackBootstrap.resumeIfNeeded(this@BootResumeService, songs, settings, library)
        // resumeIfNeeded dispatches asynchronously; give it a moment before deciding it
        // failed, otherwise the retry loop would start a second overlapping session.
        delay(RESUME_SETTLE_MS)
        return PlaybackStateHolder.isPlaying
    }

    private fun launchMainActivity() {
        // Android 10+ blocks background activity starts; this is expected to be a no-op
        // on most head units. It must not throw and take the process down.
        runCatching {
            startActivity(
                Intent(this, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                }
            )
        }.onFailure { android.util.Log.w(TAG, "cannot open app from background", it) }
    }

    private fun returnToHome() {
        runCatching {
            startActivity(
                Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_HOME)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
        }.onFailure { android.util.Log.w(TAG, "cannot return to home", it) }
    }

    private fun shutdown() {
        runCatching {
            if (foregroundReady) {
                stopForegroundCompat()
                foregroundReady = false
            }
        }
        stopSelf()
    }

    override fun onDestroy() {
        runCatching { stopForegroundCompat() }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    /**
     * Enters the foreground state with an explicit service type. Returns false when the
     * platform refuses — the caller then stops instead of crashing.
     */
    private fun enterForeground(): Boolean {
        val notification = buildNotification()
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            true
        }.getOrElse {
            android.util.Log.e(TAG, "startForeground refused", it)
            false
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.channel_boot),
            NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.boot_starting))
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "BootResumeService"
        private const val CHANNEL_ID = "boot_resume"
        private const val NOTIFICATION_ID = 1002

        /** Backoff between resume attempts, measured from the previous attempt. */
        private val WAITS_MS = longArrayOf(4_000L, 5_000L)
        private const val RESUME_SETTLE_MS = 1_500L
    }
}
