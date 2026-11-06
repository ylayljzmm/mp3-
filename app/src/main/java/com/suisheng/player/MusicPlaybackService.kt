package com.suisheng.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.MediaStyleNotificationHelper
import com.suisheng.player.data.EqSettings
import com.suisheng.player.data.OnlineMusicApi
import com.suisheng.player.data.PlaylistCache
import com.suisheng.player.data.RssFeedRepository
import com.suisheng.player.data.SettingsRepository
import com.suisheng.player.data.SongMetadataLoader
import com.suisheng.player.lrc.LrcParser
import com.suisheng.player.model.LibraryKind
import com.suisheng.player.model.PlaybackMode
import com.suisheng.player.model.Song
import com.suisheng.player.playback.AudioEffectController
import com.suisheng.player.playback.CarPlaylistPlayer
import com.suisheng.player.playback.MediaControlRecoveryPolicy
import com.suisheng.player.playback.PlaybackStateHolder
import com.suisheng.player.util.MediaPath
import java.io.File
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MusicPlaybackService : MediaSessionService() {
    private var exoPlayer: ExoPlayer? = null

    /**
     * 生效中的 audio session id（`AudioManager.generateAudioSessionId()` 分配，并已
     * `ExoPlayer.setAudioSessionId()` 设给播放器）。0 = 没拿到，此时放弃音效。
     */
    private var audioSessionId: Int = 0

    /** 均衡器 / 低音增强控制器；只在第一次收到 [ACTION_APPLY_EQ] 时按需构造。 */
    private var audioEffects: AudioEffectController? = null

    private var sessionPlayer: CarPlaylistPlayer? = null
    private var mediaSession: MediaSession? = null
    private val handler = Handler(Looper.getMainLooper())
    private val settings by lazy { SettingsRepository(this) }
    private var playlist: List<Song> = emptyList()
    private var currentIndex = -1
    private var currentLines = emptyList<com.suisheng.player.model.LrcLine>()
    private var shuffleQueue = mutableListOf<Int>()
    private var saveTick = 0
    private var lastClusterMetadataKey = ""
    private val metadataScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val metadataLoader by lazy {
        SongMetadataLoader(
            applicationContext,
            cacheDir,
            settings,
            coverFetcher = com.suisheng.player.data.CoverArtFetcher(this),
            onlineMusicApi = OnlineMusicApi(settings),
            rssFeedRepository = RssFeedRepository(applicationContext, settings)
        )
    }
    private val onlineMusicApi by lazy { OnlineMusicApi(settings) }
    private val rssRepository by lazy { RssFeedRepository(this, settings) }
    private val audioManager by lazy { getSystemService(AUDIO_SERVICE) as AudioManager }
    private var audioFocusRequest: AudioFocusRequest? = null
    private var hasAudioFocus = false
    private var foregroundStarted = false
    private var playlistJob: Job? = null
    private val mediaButtonReceiver by lazy { ComponentName(this, CarMediaButtonReceiver::class.java) }
    private val mediaControlRecovery = MediaControlRecoveryPolicy()

    private val audioFocusListener = AudioManager.OnAudioFocusChangeListener { focus ->
        val player = exoPlayer ?: return@OnAudioFocusChangeListener
        when (focus) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                hasAudioFocus = true
                if (!player.isPlaying && playlist.isNotEmpty() && player.mediaItemCount > 0) {
                    runCatching { player.play() }
                }
            }
            AudioManager.AUDIOFOCUS_LOSS -> {
                hasAudioFocus = false
                runCatching { player.pause() }
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                runCatching { player.pause() }
            }
        }
    }

    private val progressRunnable = object : Runnable {
        override fun run() {
            val p = exoPlayer ?: return
            val song = playlist.getOrNull(currentIndex)
            PlaybackStateHolder.update(song, p.isPlaying, p.currentPosition, currentLines, p.duration.coerceAtLeast(0L))
            runCatching { LyricsOverlayService.updateLyrics(applicationContext, PlaybackStateHolder.lyricState) }
            updateClusterMetadata(song, p.currentPosition)
            if (++saveTick % 25 == 0) {
                persistProgress(song, p.currentPosition)
            }
            if (p.isPlaying && saveTick % 50 == 0) {
                acquireAudioFocus()
            }
            if (mediaControlRecovery.shouldRecover(
                    nowMs = SystemClock.elapsedRealtime(),
                    isPlaying = p.isPlaying,
                    sessionReady = mediaSession != null,
                )
            ) {
                reassertMediaControl()
            }
            handler.postDelayed(this, 120L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        runCatching {
            createChannel()
            PlaybackStateHolder.setPlayMode(settings.playMode)
            exoPlayer = ExoPlayer.Builder(this)
                .setRenderersFactory(
                    DefaultRenderersFactory(this).apply {
                        // Use the FFmpeg software decoder extension (media3-decoder-ffmpeg)
                        // for formats the device MediaCodec cannot handle — most importantly
                        // ALAC (Apple Lossless) m4a, which no Android device decodes in hardware.
                        // EXTENSION_RENDERER_MODE_ON only engages FfmpegAudioRenderer when the
                        // default MediaCodec renderer does not support the input format, so mp3/aac
                        // still use hardware decoding.
                        setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
                        setEnableDecoderFallback(true)
                    }
                )
                .build()
                .apply {
                    // 倍速是「全局记住」的（05 §12 Q3）：播放器一建好就套用当前值，
                    // 否则重启后界面显示 1.5x 而实际还是 1.0x。
                    runCatching { setPlaybackSpeed(settings.playbackSpeed) }
                    addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        if (playbackState == Player.STATE_ENDED) playNext(auto = true)
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        android.util.Log.e(TAG, "ExoPlayer error", error)
                        handler.post { handlePlaybackFailure() }
                    }
                })
            }
            // 音效（均衡器）要挂到 ExoPlayer 的实际输出上，就必须共用同一个 audio session id：
            // 先让系统分配一个，再交给播放器。拿不到（0）就放弃音效 —— 播放本身不受影响。
            audioSessionId = runCatching {
                getSystemService(AudioManager::class.java)?.generateAudioSessionId() ?: 0
            }.getOrDefault(0)
            if (audioSessionId != 0) {
                // media3 1.2.1 的 ExoPlayer 接口有 setAudioSessionId(int)
                //（与已有的 ExoPlayer.Builder / DefaultRenderersFactory 同级，都是 @UnstableApi 用法）
                runCatching { exoPlayer?.setAudioSessionId(audioSessionId) }
            }
            // 播放器一就绪就把落盘的音效套用一次：否则「上次设好的 EQ」要等用户再点开面板
            // 才会生效，杀进程重进后第一首歌就没有音效。
            applyEqualizer()
            // Establish the media-session and hardware-button route before the
            // first foreground notification is built. This prevents a head unit
            // from seeing an ordinary notification first and assigning the
            // steering-wheel controls to another player.
            ensureMediaSession()
            registerMediaButtonReceiver()
        }.onFailure {
            android.util.Log.e(TAG, "onCreate failed", it)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val media3Result = super.onStartCommand(intent, flags, startId)
        if (intent?.action == Intent.ACTION_MEDIA_BUTTON) {
            reassertMediaControl()
            return if (playlist.isNotEmpty() || PlaybackStateHolder.songs.isNotEmpty()) {
                START_STICKY
            } else {
                media3Result
            }
        }
        return runCatching { handleStartCommand(intent) }.onFailure {
            android.util.Log.e(TAG, "onStartCommand failed", it)
            stopSelfSafely()
        }.getOrDefault(START_NOT_STICKY)
    }

    private fun handleStartCommand(intent: Intent?): Int {
        val action = intent?.action
        if (action == ACTION_STOP) {
            stopSelfSafely()
            return START_NOT_STICKY
        }

        if (!requiresForeground(action) && playlist.isEmpty() && PlaybackStateHolder.songs.isEmpty()) {
            stopSelfSafely()
            return START_NOT_STICKY
        }

        ensureForeground()

        if (action in MEDIA_CONTROL_ACTIONS) {
            claimMediaControl()
        }

        when (action) {
            ACTION_PLAY_INDEX -> {
                val cmd = intent ?: return START_NOT_STICKY
                val index = cmd.getIntExtra(EXTRA_INDEX, 0)
                val seek = cmd.getLongExtra(EXTRA_SEEK, 0L)
                val library = cmd.libraryExtra()
                runWithPlaylist(cmd, library) { list ->
                    if (list.isNotEmpty()) {
                        startPlaylist(list, index, seek, library)
                    } else if (playlist.isNotEmpty()) {
                        playSongAt(index.coerceIn(0, playlist.lastIndex), seek)
                    }
                }
            }
            ACTION_TOGGLE -> {
                if (playlist.isEmpty()) {
                    runWithPlaylist(null) { list ->
                        if (list.isEmpty()) return@runWithPlaylist
                        startFromCachedQueue(list, resume = true)
                    }
                } else {
                    togglePlayPause()
                }
            }
            ACTION_PLAY -> {
                if (playlist.isEmpty()) {
                    runWithPlaylist(null) { list ->
                        if (list.isEmpty()) return@runWithPlaylist
                        startFromCachedQueue(list, resume = true)
                    }
                } else {
                    setPlaybackState(shouldPlay = true)
                }
            }
            ACTION_PAUSE -> setPlaybackState(shouldPlay = false)
            ACTION_NEXT -> {
                if (playlist.isEmpty()) {
                    runWithPlaylist(null) { list ->
                        if (list.isEmpty()) return@runWithPlaylist
                        startFromCachedQueue(list, shouldPlayNext = true)
                    }
                } else {
                    playNext()
                }
            }
            ACTION_PREV -> {
                if (playlist.isEmpty()) {
                    runWithPlaylist(null) { list ->
                        if (list.isEmpty()) return@runWithPlaylist
                        startFromCachedQueue(list, shouldPlayPrevious = true)
                    }
                } else {
                    playPrevious()
                }
            }
            ACTION_SET_MODE -> {
                val cmd = intent ?: return START_NOT_STICKY
                val mode = PlaybackMode.entries.getOrElse(cmd.getIntExtra(EXTRA_MODE, 0)) { PlaybackMode.SHUFFLE }
                setPlayMode(mode)
            }
            ACTION_SET_SPEED -> {
                val cmd = intent ?: return START_NOT_STICKY
                val speed = cmd.getFloatExtra(EXTRA_SPEED, 1.0f).coerceIn(0.5f, 2.5f)
                runCatching { exoPlayer?.setPlaybackSpeed(speed) }
            }
            ACTION_RESUME -> resumeLast()
            ACTION_SEEK -> {
                val cmd = intent ?: return START_NOT_STICKY
                val seek = cmd.getLongExtra(EXTRA_SEEK, 0L)
                runCatching { exoPlayer?.seekTo(seek.coerceAtLeast(0L)) }
            }
            ACTION_RELOAD_LYRICS -> intent?.let { reloadLyrics(it) }
            ACTION_QUEUE_JUMP -> {
                val cmd = intent ?: return START_NOT_STICKY
                val index = cmd.getIntExtra(EXTRA_INDEX, -1)
                // 越界直接忽略：面板刚删完一行就点跳转时可能出现这种瞬时错位
                if (index in playlist.indices) playSongAt(index)
            }
            ACTION_QUEUE_REMOVE -> {
                val cmd = intent ?: return START_NOT_STICKY
                removeFromQueue(cmd.getIntExtra(EXTRA_INDEX, -1))
            }
            ACTION_QUEUE_CLEAR -> clearQueue()
            ACTION_QUEUE_ADD -> {
                val cmd = intent ?: return START_NOT_STICKY
                addToQueue(readPlaylist(cmd)?.map { it.toSong() }.orEmpty())
            }
            ACTION_QUEUE_REMOVE_PATHS -> {
                val cmd = intent ?: return START_NOT_STICKY
                removePathsFromQueue(
                    cmd.getStringArrayListExtra(EXTRA_PATHS)?.toHashSet().orEmpty()
                )
            }
            ACTION_APPLY_EQ -> applyEqualizer()
        }
        return if (playlist.isNotEmpty()) START_STICKY else START_NOT_STICKY
    }

    private fun requiresForeground(action: String?): Boolean {
        return when (action) {
            ACTION_SET_MODE, ACTION_SET_SPEED, ACTION_STOP,
            ACTION_APPLY_EQ,
            ACTION_QUEUE_JUMP, ACTION_QUEUE_REMOVE, ACTION_QUEUE_CLEAR,
            ACTION_QUEUE_ADD, ACTION_QUEUE_REMOVE_PATHS -> false
            else -> true
        }
    }

    private fun runWithPlaylist(
        intent: Intent?,
        library: LibraryKind? = intent?.libraryExtra(),
        block: (List<Song>) -> Unit
    ) {
        playlistJob?.cancel()
        playlistJob = metadataScope.launch {
            try {
                val list = withContext(Dispatchers.IO) { resolvePlaylist(intent, library) }
                withContext(Dispatchers.Main) {
                    runCatching { block(list) }.onFailure {
                        android.util.Log.e(TAG, "playback action failed", it)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.e(TAG, "resolvePlaylist failed", e)
            }
        }
    }

    private fun resolvePlaylist(intent: Intent?, library: LibraryKind? = null): List<Song> {
        PlaybackStateHolder.songs.takeIf { it.isNotEmpty() }?.let { return it }
        val resolvedLibrary = library ?: settings.lastActiveLibrary
        PlaylistCache.loadQueue(this, resolvedLibrary).takeIf { it.isNotEmpty() }?.let { return it }
        if (resolvedLibrary == LibraryKind.MUSIC) {
            PlaylistCache.load(this).takeIf { it.isNotEmpty() }?.let { return it }
        }
        intent?.let { readPlaylist(it) }?.map { it.toSong() }?.takeIf { it.isNotEmpty() }?.let { return it }
        return emptyList()
    }

    private fun Intent.libraryExtra(): LibraryKind {
        val raw = getStringExtra(EXTRA_LIBRARY) ?: return settings.lastActiveLibrary
        return runCatching { LibraryKind.valueOf(raw) }.getOrDefault(LibraryKind.MUSIC)
    }

    private fun startFromCachedQueue(
        list: List<Song>,
        resume: Boolean = false,
        shouldPlayNext: Boolean = false,
        shouldPlayPrevious: Boolean = false
    ) {
        if (list.isEmpty()) return
        if (playlist.isEmpty()) {
            val library = PlaybackStateHolder.activeLibrary
            val path = settings.lastSongPath(library)
            val index = path?.let { p -> list.indexOfFirst { it.path == p } }?.takeIf { it >= 0 } ?: 0
            val seek = if (resume) settings.lastPositionMs(library).coerceAtLeast(0L) else 0L
            startPlaylist(list, index, seek, library)
            if (shouldPlayNext) playNext()
            else if (shouldPlayPrevious) playPrevious()
            return
        }
        when {
            shouldPlayNext -> playNext()
            shouldPlayPrevious -> playPrevious()
        }
    }

    private fun ensureForeground() {
        if (foregroundStarted) return
        runCatching {
            startForegroundCompat(buildNotification(playlist.getOrNull(currentIndex)))
            foregroundStarted = true
        }.recoverCatching {
            // Older/odd builds reject the media-style notification (missing icon,
            // unsupported style). A bare notification still satisfies the contract.
            startForegroundCompat(
                NotificationCompat.Builder(this, CHANNEL_ID)
                    .setContentTitle(getString(R.string.app_name))
                    .setContentText(getString(R.string.now_playing))
                    .setSmallIcon(android.R.drawable.ic_media_play)
                    .setOngoing(true)
                    .build()
            )
            foregroundStarted = true
        }.onFailure {
            // Refused (background start restrictions on Android 12+). Keep running
            // without the foreground state rather than crashing here.
            android.util.Log.e(TAG, "ensureForeground failed", it)
        }
    }

    /**
     * Android 14 rejects a foreground service start whose type does not match the
     * manifest declaration, so the type has to be passed explicitly on Q+.
     */
    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun stopSelfSafely() {
        if (foregroundStarted) {
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                } else {
                    @Suppress("DEPRECATION")
                    stopForeground(true)
                }
            }
            foregroundStarted = false
        }
        stopSelf()
    }

    private fun resumeLast() {
        if (playlist.isEmpty()) return
        val library = PlaybackStateHolder.activeLibrary
        val path = settings.lastSongPath(library) ?: return
        val index = playlist.indexOfFirst { it.path == path }
        if (index >= 0) {
            playSongAt(index, settings.lastPositionMs(library).coerceAtLeast(0L))
        }
    }

    private fun setPlayMode(mode: PlaybackMode) {
        PlaybackStateHolder.setPlayMode(mode)
        settings.playMode = mode
        refillShuffleQueue()
    }

    private fun startPlaylist(list: List<Song>, index: Int, seekMs: Long = 0L, library: LibraryKind? = null) {
        playlist = list
        currentIndex = index.coerceIn(0, (list.size - 1).coerceAtLeast(0))
        val resolvedLibrary = library ?: MediaPath.libraryKind(list.getOrNull(currentIndex)?.path)
        settings.lastActiveLibrary = resolvedLibrary
        when {
            PlaybackStateHolder.songs.isEmpty() ->
                PlaybackStateHolder.setPlaylist(list, currentIndex, resolvedLibrary)
            PlaybackStateHolder.songs === list ->
                PlaybackStateHolder.setCurrentIndex(currentIndex)
            else ->
                PlaybackStateHolder.setPlaylist(list, currentIndex, resolvedLibrary)
        }
        refillShuffleQueue()
        playSongAt(currentIndex, seekMs)
    }

    private fun playSongAt(index: Int, seekMs: Long = 0L) {
        if (index !in playlist.indices) return
        val song = playlist[index]
        if (MediaPath.isStream(song.path)) {
            metadataScope.launch {
                val streamUrl = resolveStreamUrl(song)
                if (streamUrl.isNullOrBlank()) {
                    handler.post { handleUnplayableSong(index) }
                    return@launch
                }
                handler.post { startPlaybackResolved(song, index, seekMs, streamUrl) }
            }
            return
        }
        if (!songFileReadable(song)) {
            android.util.Log.e(TAG, "song not readable: ${song.path}")
            handleUnplayableSong(index)
            return
        }
        startPlaybackResolved(song, index, seekMs, null)
    }

    private fun startPlaybackResolved(song: Song, index: Int, seekMs: Long, streamUrl: String?) {
        runCatching {
            acquireAudioFocus()
            currentIndex = index
            val player = exoPlayer ?: return@runCatching
            val item = if (streamUrl != null) {
                buildMediaItem(song).buildUpon().setUri(streamUrl).build()
            } else {
                buildMediaItem(song)
            }
            player.setMediaItem(item, seekMs)
            player.prepare()
            player.play()

            lastClusterMetadataKey = ""
            PlaybackStateHolder.setCoverArt(null)
            currentLines = loadLocalLyrics(song)
            claimMediaControl()
            ensureForeground()
            updateNotification(song)
            handler.removeCallbacks(progressRunnable)
            handler.post(progressRunnable)
            persistProgress(song, seekMs)
            PlaybackStateHolder.update(song, true, seekMs, currentLines, player.duration.coerceAtLeast(0L))
            updateClusterMetadata(song, seekMs)
            handler.post {
                runCatching { LyricsOverlayService.start(applicationContext) }
                LyricsOverlayService.updateLyrics(applicationContext, PlaybackStateHolder.lyricState)
                runCatching { ClusterLyricService.start(applicationContext) }
            }
            loadMetadataAsync(song)
        }.onFailure {
            android.util.Log.e(TAG, "playSongAt failed", it)
            handleUnplayableSong(index)
        }
    }

    private suspend fun resolveStreamUrl(song: Song): String? {
        MediaPath.parseOnline(song.path)?.let { parts ->
            return onlineMusicApi.resolvePlayUrl(parts.source, parts.trackId)?.url
        }
        MediaPath.parseRadioStreamUrl(song.path)?.let { return it }
        MediaPath.parsePodcastStreamUrl(song.path)?.let { return it }
        if (song.path.startsWith("http")) return song.path
        return null
    }

    private fun handleUnplayableSong(index: Int) {
        if (settings.skipUnplayableVip && PlaybackStateHolder.activeLibrary == LibraryKind.ONLINE) {
            playNextSkipping(index)
        }
    }

    private fun handlePlaybackFailure() {
        if (settings.skipUnplayableVip) {
            playNext()
        }
    }

    private fun playNextSkipping(fromIndex: Int) {
        if (playlist.isEmpty()) return
        val next = (fromIndex + 1) % playlist.size
        if (next == fromIndex) return
        playSongAt(next)
    }

    private fun songFileReadable(song: Song): Boolean {
        if (MediaPath.isStream(song.path)) return true
        if (song.path.startsWith("content://")) return true
        if (song.path.startsWith("http")) return true
        return runCatching { File(song.path).isFile }.getOrDefault(false)
    }

    private fun claimMediaControl() {
        acquireAudioFocus()
        ensureMediaSession()
        reassertMediaControl()
    }

    private fun reassertMediaControl() {
        ensureMediaSession()
        registerMediaButtonReceiver()
    }

    private fun registerMediaButtonReceiver() {
        runCatching {
            @Suppress("DEPRECATION")
            audioManager.registerMediaButtonEventReceiver(mediaButtonReceiver)
        }
    }

    private fun unregisterMediaButtonReceiver() {
        runCatching {
            @Suppress("DEPRECATION")
            audioManager.unregisterMediaButtonEventReceiver(mediaButtonReceiver)
        }
    }

    private fun ensureMediaSession() {
        if (mediaSession != null) return
        val player = exoPlayer ?: return
        runCatching {
            val wrapper = CarPlaylistPlayer(
                player = player,
                onSkipNext = { handler.post { playNext() } },
                onSkipPrevious = { handler.post { playPrevious() } },
                hasPlaylist = { playlist.isNotEmpty() }
            )
            sessionPlayer = wrapper
            mediaSession = MediaSession.Builder(this, wrapper)
                .setSessionActivity(
                    PendingIntent.getActivity(
                        this, 0, Intent(this, MainActivity::class.java),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                )
                .build()
        }.onFailure {
            android.util.Log.e(TAG, "MediaSession create failed", it)
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        ensureMediaSession()
        return mediaSession
    }

    private fun releaseMediaSession() {
        runCatching { mediaSession?.release() }
        mediaSession = null
        sessionPlayer = null
    }

    private fun buildMediaItem(song: Song): MediaItem {
        val builder = MediaItem.Builder()
            .setUri(songUri(song))
            .setMediaId(song.path)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(song.title)
                    .setArtist(song.artist)
                    .build()
            )
        // Hint the container/MIME so ExoPlayer picks the right extractor even when the
        // source is a content:// document-tree URI with no file extension (custom folder
        // selection). Without this, m4a/flac/wav/ogg opened via SAF can fail to play.
        mapExtensionToMime(song.path)?.let { builder.setMimeType(it) }
        return builder.build()
    }

    private fun mapExtensionToMime(path: String): String? {
        return when (path.substringAfterLast('.', "").lowercase()) {
            "m4a", "mp4", "aac" -> MimeTypes.AUDIO_MP4
            "mp3" -> MimeTypes.AUDIO_MPEG
            "flac" -> MimeTypes.AUDIO_FLAC
            "wav", "wave" -> MimeTypes.AUDIO_WAV
            "ogg", "oga" -> MimeTypes.AUDIO_OGG
            else -> null
        }
    }

    private fun acquireAudioFocus() {
        if (hasAudioFocus) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(attrs)
                .setAcceptsDelayedFocusGain(true)
                .setWillPauseWhenDucked(false)
                .setOnAudioFocusChangeListener(audioFocusListener, handler)
                .build()
            audioFocusRequest = request
            hasAudioFocus = audioManager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } else {
            @Suppress("DEPRECATION")
            hasAudioFocus = audioManager.requestAudioFocus(
                audioFocusListener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN
            ) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
    }

    private fun releaseAudioFocus() {
        if (!hasAudioFocus) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(audioFocusListener)
        }
        hasAudioFocus = false
    }

    private fun loadLocalLyrics(song: Song): List<com.suisheng.player.model.LrcLine> {
        return metadataLoader.readLocalLyrics(song) ?: emptyList()
    }

    private fun persistSongLrcPath(songPath: String, lrcPath: String) {
        val idx = playlist.indexOfFirst { it.path == songPath }
        if (idx < 0) return
        val updated = playlist[idx].copy(lrcPath = lrcPath)
        playlist = playlist.toMutableList().apply { set(idx, updated) }
        PlaybackStateHolder.updateSongLrcPath(songPath, lrcPath)
        metadataScope.launch(Dispatchers.IO) {
            PlaylistCache.saveQueue(applicationContext, playlist)
        }
    }

    private fun loadMetadataAsync(song: Song) {
        metadataScope.launch {
            val cover = metadataLoader.loadCover(song)
            if (playlist.getOrNull(currentIndex)?.path == song.path && cover != null) {
                withContext(Dispatchers.Main) {
                    PlaybackStateHolder.setCoverArt(cover)
                }
            }

            val localLines = metadataLoader.readLocalLyrics(song)
            val result = if (!localLines.isNullOrEmpty()) {
                SongMetadataLoader.LyricLoadResult(localLines, song.lrcPath)
            } else {
                metadataLoader.loadLyrics(song)
            } ?: return@launch

            if (playlist.getOrNull(currentIndex)?.path != song.path) return@launch
            result.lrcPath?.let { persistSongLrcPath(song.path, it) }
            currentLines = result.lines
            withContext(Dispatchers.Main) {
                val p = exoPlayer
                PlaybackStateHolder.update(
                    song,
                    p?.isPlaying == true,
                    p?.currentPosition ?: 0L,
                    currentLines,
                    p?.duration?.coerceAtLeast(0L) ?: PlaybackStateHolder.durationMs
                )
                LyricsOverlayService.updateLyrics(applicationContext, PlaybackStateHolder.lyricState)
            }
        }
    }

    private fun songUri(song: Song): Uri {
        return when {
            song.path.startsWith("http") -> Uri.parse(song.path)
            MediaPath.isOnline(song.path) -> Uri.parse("about:blank")
            MediaPath.isRadio(song.path) -> Uri.parse(MediaPath.parseRadioStreamUrl(song.path).orEmpty())
            MediaPath.isPodcast(song.path) -> Uri.parse(MediaPath.parsePodcastStreamUrl(song.path).orEmpty())
            song.path.startsWith("content://") -> Uri.parse(song.path)
            else -> Uri.fromFile(File(song.path))
        }
    }

    private fun togglePlayPause() {
        val p = exoPlayer ?: return
        setPlaybackState(shouldPlay = p.mediaItemCount <= 0 || !p.isPlaying)
    }

    private fun setPlaybackState(shouldPlay: Boolean) {
        runCatching {
            val p = exoPlayer ?: return@runCatching
            if (p.mediaItemCount <= 0) {
                if (shouldPlay && playlist.isNotEmpty()) {
                    playSongAt(currentIndex.coerceIn(0, playlist.lastIndex))
                }
                return@runCatching
            }
            if (shouldPlay) p.play() else p.pause()
            val song = playlist.getOrNull(currentIndex)
            updateNotification(song)
            PlaybackStateHolder.update(song, p.isPlaying, p.currentPosition, currentLines, p.duration.coerceAtLeast(0L))
            persistProgress(song, p.currentPosition)
        }.onFailure {
            android.util.Log.e(TAG, "setPlaybackState failed", it)
        }
    }

    /**
     * @param auto true 表示「播完自动续播」。单曲循环只在自动续播时重播当前曲；
     * 手动下一曲、跳过不可播曲目仍强制切下一首，避免死循环。
     */
    private fun playNext(auto: Boolean = false) {
        runCatching {
            if (playlist.isEmpty()) return@runCatching
            if (auto && PlaybackStateHolder.playMode == PlaybackMode.REPEAT_ONE) {
                playSongAt(currentIndex)
                return@runCatching
            }
            val next = when (PlaybackStateHolder.playMode) {
                PlaybackMode.SHUFFLE -> {
                    if (shuffleQueue.isEmpty()) refillShuffleQueue()
                    shuffleQueue.removeFirstOrNull() ?: Random.nextInt(playlist.size)
                }
                PlaybackMode.REPEAT_ONE, PlaybackMode.ORDER ->
                    if (currentIndex + 1 < playlist.size) currentIndex + 1 else 0
            }
            playSongAt(next)
        }.onFailure {
            android.util.Log.e(TAG, "playNext failed", it)
        }
    }

    private fun playPrevious() {
        runCatching {
            if (playlist.isEmpty()) return@runCatching
            val p = exoPlayer ?: return@runCatching
            if (p.mediaItemCount > 0 && p.currentPosition > 3000) {
                p.seekTo(0)
                return@runCatching
            }
            val prev = when (PlaybackStateHolder.playMode) {
                PlaybackMode.SHUFFLE -> {
                    if (shuffleQueue.isEmpty()) refillShuffleQueue()
                    shuffleQueue.removeLastOrNull() ?: Random.nextInt(playlist.size)
                }
                PlaybackMode.REPEAT_ONE, PlaybackMode.ORDER ->
                    if (currentIndex - 1 >= 0) currentIndex - 1 else playlist.lastIndex
            }
            playSongAt(prev)
        }.onFailure {
            android.util.Log.e(TAG, "playPrevious failed", it)
        }
    }

    private fun refillShuffleQueue() {
        shuffleQueue = playlist.indices.filter { it != currentIndex }.shuffled().toMutableList()
    }

    // ------------------------------------------------------------ 队列增删（期 4）

    /**
     * 从播放队列移除第 [index] 首。
     *
     * `currentIndex` 的调整规则（与 [com.suisheng.player.ui.QueuePanel] 本地快照严格一致）：
     * - `index < currentIndex` → `currentIndex--`，当前这首不被打断；
     * - `index > currentIndex` → 不变；
     * - `index == currentIndex` → 接着播「原来的下一首」：`playlist` 已经缩短，
     *   `index` 天然就指向它；删的是最后一项时回退到新的末位（不越界、也不会随机跳）；
     * - 删空 → 停播并把队列置空。
     */
    private fun removeFromQueue(index: Int) {
        if (index !in playlist.indices) return

        val removedCurrent = index == currentIndex
        playlist = playlist.toMutableList().apply { removeAt(index) }

        if (playlist.isEmpty()) {
            currentIndex = -1
            runCatching { exoPlayer?.pause() }
            runCatching { exoPlayer?.stop() }
            // 播放器里还挂着刚被移出的那一首，不清掉的话通知/媒体会话会继续显示它
            runCatching { exoPlayer?.clearMediaItems() }
            refillShuffleQueue()
            PlaybackStateHolder.notifyQueueChanged(emptyList(), -1)
            // saveQueue 对空列表直接 return（不会覆盖旧文件），所以这里改走删除
            clearPersistedQueue()
            updateNotification(null)
            return
        }

        if (removedCurrent) {
            val target = index.coerceAtMost(playlist.lastIndex)
            currentIndex = target
            refillShuffleQueue()
            // 先同步队列再起播：playSongAt 内部会按 PlaybackStateHolder.songs 反查索引，
            // 列表还是旧的话它会算出错的下标
            PlaybackStateHolder.notifyQueueChanged(playlist, currentIndex)
            playSongAt(target)
            persistQueue()
            return
        }

        if (index < currentIndex) currentIndex -= 1
        refillShuffleQueue()
        PlaybackStateHolder.notifyQueueChanged(playlist, currentIndex)
        persistQueue()
    }

    /** 批量追加到队列尾：不打断播放，已在队列里的 path 跳过。 */
    private fun addToQueue(songs: List<Song>) {
        if (songs.isEmpty()) return
        val existing = playlist.map { it.path }.toHashSet()
        val fresh = songs.filterNot { it.path in existing }
        if (fresh.isEmpty()) return
        playlist = playlist + fresh
        refillShuffleQueue()
        PlaybackStateHolder.notifyQueueChanged(playlist, currentIndex)
        persistQueue()
    }

    /** 批量移出队列：只从播放队列移除，绝不删文件。 */
    private fun removePathsFromQueue(paths: Set<String>) {
        if (paths.isEmpty() || playlist.isEmpty()) return
        val currentPath = playlist.getOrNull(currentIndex)?.path
        val kept = playlist.filterNot { it.path in paths }
        if (kept.isEmpty()) {
            clearQueue()
            return
        }
        val currentRemoved = currentPath != null && paths.contains(currentPath)
        currentIndex = kept.indexOfFirst { it.path == currentPath }.takeIf { it >= 0 } ?: 0
        playlist = kept
        refillShuffleQueue()
        // 先同步队列再起播：playSongAt 会按 PlaybackStateHolder.songs 反查索引
        PlaybackStateHolder.notifyQueueChanged(playlist, currentIndex)
        if (currentRemoved) playSongAt(currentIndex)
        persistQueue()
    }

    /** 清空整个播放队列并停播。 */
    private fun clearQueue() {
        playlist = emptyList()
        currentIndex = -1
        runCatching { exoPlayer?.stop() }
        // 队列空了就别再让播放器挂着一个 mediaItem（通知 / 车机会继续显示那一首）
        runCatching { exoPlayer?.clearMediaItems() }
        refillShuffleQueue()
        PlaybackStateHolder.setQueue(emptyList(), -1)
        clearPersistedQueue()
        updateNotification(null)
    }

    // ------------------------------------------------------------ 音效（期 5）

    /**
     * 把 [EqSettings] 的当前值施加到系统音效上。
     *
     * 三点约定：
     * - `audioSessionId == 0`（这台设备分配不出会话）→ 直接返回，什么都不做；
     * - 控制器**懒创建**：第一次收到 [ACTION_APPLY_EQ] 才 `new AudioEffectController(...)`，
     *   之后复用同一个（每次都重建 `Equalizer` 会闪断声音）；
     * - 控制器内部所有系统调用都已经 `runCatching` 兜住，设备不支持只是降级，不会崩。
     */
    private fun applyEqualizer() {
        if (audioSessionId == 0) return
        val controller = audioEffects ?: AudioEffectController(audioSessionId).also { audioEffects = it }
        controller.apply(EqSettings(this))
    }

    /** 队列变动后落盘（与 `persistSongLrcPath` 同一条链路，只是多带了当前媒体库）。 */
    private fun persistQueue() {
        val snapshot = playlist
        if (snapshot.isEmpty()) return
        val library = PlaybackStateHolder.activeLibrary
        metadataScope.launch(Dispatchers.IO) {
            PlaylistCache.saveQueue(applicationContext, snapshot, library)
        }
    }

    /**
     * 删除落盘队列。
     *
     * `PlaylistCache` 没有「清空」接口，而且 `saveQueue()` 遇到空列表会直接 return，
     * 所以清空队列时只能按它使用的文件名把文件删掉；文件名与 `data/PlaylistCache.kt`
     * 里的常量一一对应，那边改了这里要跟着改。
     */
    private fun clearPersistedQueue() {
        metadataScope.launch(Dispatchers.IO) {
            PERSISTED_QUEUE_FILES.forEach { name ->
                runCatching { File(filesDir, name).delete() }
            }
        }
    }

    private fun reloadLyrics(intent: Intent) {
        val song = playlist.getOrNull(currentIndex) ?: return
        val title = intent.getStringExtra(EXTRA_SEARCH_TITLE)?.trim().orEmpty()
        val artist = intent.getStringExtra(EXTRA_SEARCH_ARTIST)?.trim().orEmpty()
        if (title.isNotEmpty()) {
            settings.setLyricSearchOverride(song.path, title, artist.ifBlank { song.artist })
        }
        metadataScope.launch {
            val result = metadataLoader.loadLyrics(song, forceOnline = true) ?: return@launch
            if (playlist.getOrNull(currentIndex)?.path != song.path) return@launch
            result.lrcPath?.let { persistSongLrcPath(song.path, it) }
            currentLines = result.lines
            withContext(Dispatchers.Main) {
                val p = exoPlayer
                PlaybackStateHolder.update(
                    song,
                    p?.isPlaying == true,
                    p?.currentPosition ?: 0L,
                    currentLines,
                    p?.duration?.coerceAtLeast(0L) ?: PlaybackStateHolder.durationMs
                )
                LyricsOverlayService.updateLyrics(applicationContext, PlaybackStateHolder.lyricState)
            }
        }
    }

    private fun updateClusterMetadata(song: Song?, positionMs: Long) {
        if (song == null) return
        if (!settings.clusterLyricsEnabled) return
        val lyricLine = LrcParser.findState(currentLines, positionMs).currentLine?.text
        val subtitle = lyricLine?.takeIf { it.isNotBlank() } ?: song.artist
        val key = "${song.path}|$subtitle"
        if (key == lastClusterMetadataKey) return
        lastClusterMetadataKey = key
        val metadata = MediaMetadata.Builder()
            .setTitle(song.title)
            .setArtist(song.artist)
            .setAlbumTitle(song.artist)
            .setDisplayTitle(song.title)
            .setSubtitle(subtitle)
            .setDescription(lyricLine ?: song.title)
            .build()
        val player = exoPlayer ?: return
        if (player.mediaItemCount <= 0) return
        val current = player.currentMediaItem ?: return
        runCatching {
            player.replaceMediaItem(
                player.currentMediaItemIndex.coerceAtLeast(0),
                current.buildUpon().setMediaMetadata(metadata).build()
            )
        }
        updateNotification(song, subtitle)
    }

    private fun persistProgress(song: Song?, positionMs: Long) {
        if (song == null) return
        val library = PlaybackStateHolder.activeLibrary
        settings.setLastSong(library, song.path, positionMs)
    }

    override fun onDestroy() {
        handler.removeCallbacks(progressRunnable)
        releaseAudioFocus()
        unregisterMediaButtonReceiver()
        releaseMediaSession()
        // 音效句柄必须比播放器先放：Equalizer 挂在 session 上，播放器先死会让它变成僵尸句柄
        audioEffects?.release()
        audioEffects = null
        runCatching { exoPlayer?.release() }
        exoPlayer = null
        foregroundStarted = false
        mediaControlRecovery.reset()
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    private fun readPlaylist(intent: Intent): ArrayList<SongParcelable>? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableArrayListExtra(EXTRA_PLAYLIST, SongParcelable::class.java)
        } else {
            intent.getParcelableArrayListExtra(EXTRA_PLAYLIST)
        }
    }

    private fun createChannel() {
        val channel = NotificationChannel(CHANNEL_ID, getString(R.string.channel_playback), NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(song: Song?, subtitle: String? = null): Notification {
        val title = song?.title ?: getString(R.string.app_name)
        val line = subtitle?.takeIf { it.isNotBlank() }
        val text = line ?: song?.artist?.takeIf { it.isNotBlank() } ?: getString(R.string.now_playing)
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_play)
            .setContentIntent(open)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
        mediaSession?.let { session ->
            runCatching { builder.setStyle(MediaStyleNotificationHelper.MediaStyle(session)) }
        }
        return builder.build()
    }

    private fun updateNotification(song: Song?, subtitle: String? = null) {
        if (!foregroundStarted) return
        runCatching {
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(song, subtitle))
        }
    }

    companion object {
        const val ACTION_PLAY_INDEX = "play_index"
        const val ACTION_TOGGLE = "toggle"
        const val ACTION_PLAY = "play"
        const val ACTION_PAUSE = "pause"
        const val ACTION_NEXT = "next"
        const val ACTION_PREV = "prev"
        const val ACTION_SET_MODE = "set_mode"
        const val ACTION_SET_SPEED = "set_speed"
        const val ACTION_RESUME = "resume"
        const val ACTION_SEEK = "seek"
        const val ACTION_RELOAD_LYRICS = "reload_lyrics"
        /** 队列跳转：`EXTRA_INDEX` = 目标下标（越界忽略）。 */
        const val ACTION_QUEUE_JUMP = "queue_jump"
        /** 队列移除：`EXTRA_INDEX` = 要移出的下标（越界忽略）。 */
        const val ACTION_QUEUE_REMOVE = "queue_remove"
        /** 队列清空：无参数，清空列表 + 停播 + 删除落盘队列。 */
        const val ACTION_QUEUE_CLEAR = "queue_clear"
        /** 批量加入队列尾：`EXTRA_PLAYLIST` = `ArrayList<SongParcelable>`，不打断播放。 */
        const val ACTION_QUEUE_ADD = "queue_add"
        /** 批量移出队列：`EXTRA_PATHS` = `ArrayList<String>`，只移队列不删文件。 */
        const val ACTION_QUEUE_REMOVE_PATHS = "queue_remove_paths"
        /** 音效（均衡器）设置变更：无参数，服务读 `EqSettings` 后重新施加到系统音效。不拉前台。 */
        const val ACTION_APPLY_EQ = "apply_eq"
        const val ACTION_STOP = "stop"
        const val EXTRA_INDEX = "index"
        const val EXTRA_SEEK = "seek"
        const val EXTRA_LIBRARY = "library"
        const val EXTRA_PLAYLIST = "playlist"
        const val EXTRA_PATHS = "paths"
        const val EXTRA_MODE = "mode"
        const val EXTRA_SPEED = "speed"
        const val EXTRA_SEARCH_TITLE = "search_title"
        const val EXTRA_SEARCH_ARTIST = "search_artist"
        private const val CHANNEL_ID = "playback"
        private const val NOTIFICATION_ID = 1001
        private const val TAG = "MusicPlaybackService"
        private val MEDIA_CONTROL_ACTIONS = setOf(
            ACTION_PLAY_INDEX, ACTION_TOGGLE, ACTION_PLAY, ACTION_PAUSE,
            ACTION_NEXT, ACTION_PREV, ACTION_RESUME
        )

        /** `data/PlaylistCache.kt` 里的队列文件名，清空队列时按名删除（那边没有 clear 接口）。 */
        private val PERSISTED_QUEUE_FILES = listOf(
            "play_queue.json",
            "play_queue_music.json",
            "play_queue_online.json",
            "play_queue_radio.json",
            "play_queue_podcast.json",
        )
    }
}
