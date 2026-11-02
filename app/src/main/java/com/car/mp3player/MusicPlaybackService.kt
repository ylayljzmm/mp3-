package com.car.mp3player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Intent
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
import com.car.mp3player.data.OnlineMusicApi
import com.car.mp3player.data.PlaylistCache
import com.car.mp3player.data.RssFeedRepository
import com.car.mp3player.data.SettingsRepository
import com.car.mp3player.data.SongMetadataLoader
import com.car.mp3player.lrc.LrcParser
import com.car.mp3player.model.LibraryKind
import com.car.mp3player.model.PlaybackMode
import com.car.mp3player.model.Song
import com.car.mp3player.playback.CarPlaylistPlayer
import com.car.mp3player.playback.MediaControlRecoveryPolicy
import com.car.mp3player.playback.PlaybackStateHolder
import com.car.mp3player.util.MediaPath
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
    private var sessionPlayer: CarPlaylistPlayer? = null
    private var mediaSession: MediaSession? = null
    private val handler = Handler(Looper.getMainLooper())
    private val settings by lazy { SettingsRepository(this) }
    private var playlist: List<Song> = emptyList()
    private var currentIndex = -1
    private var currentLines = emptyList<com.car.mp3player.model.LrcLine>()
    private var shuffleQueue = mutableListOf<Int>()
    private var saveTick = 0
    private var lastClusterMetadataKey = ""
    private val metadataScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val metadataLoader by lazy {
        SongMetadataLoader(
            applicationContext,
            cacheDir,
            settings,
            coverFetcher = com.car.mp3player.data.CoverArtFetcher(this),
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
                        // Fall back to the FFmpeg software decoder (media3-decoder-ffmpeg)
                        // when a device's MediaCodec cannot decode a track (e.g. ALAC m4a,
                        // flac/wav/ogg on some car-head-unit builds).
                        setEnableDecoderFallback(true)
                    }
                )
                .build()
                .apply {
                addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        if (playbackState == Player.STATE_ENDED) playNext()
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        android.util.Log.e(TAG, "ExoPlayer error", error)
                        handler.post { handlePlaybackFailure() }
                    }
                })
            }
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
                val mode = PlaybackMode.entries[cmd.getIntExtra(EXTRA_MODE, 0)]
                setPlayMode(mode)
            }
            ACTION_RESUME -> resumeLast()
            ACTION_SEEK -> {
                val cmd = intent ?: return START_NOT_STICKY
                val seek = cmd.getLongExtra(EXTRA_SEEK, 0L)
                runCatching { exoPlayer?.seekTo(seek.coerceAtLeast(0L)) }
            }
            ACTION_RELOAD_LYRICS -> intent?.let { reloadLyrics(it) }
        }
        return if (playlist.isNotEmpty()) START_STICKY else START_NOT_STICKY
    }

    private fun requiresForeground(action: String?): Boolean {
        return when (action) {
            ACTION_SET_MODE, ACTION_STOP -> false
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
            startForeground(NOTIFICATION_ID, buildNotification(playlist.getOrNull(currentIndex)))
            foregroundStarted = true
        }.onFailure {
            runCatching {
                startForeground(
                    NOTIFICATION_ID,
                    NotificationCompat.Builder(this, CHANNEL_ID)
                        .setContentTitle(getString(R.string.app_name))
                        .setContentText(getString(R.string.now_playing))
                        .setSmallIcon(android.R.drawable.ic_media_play)
                        .setOngoing(true)
                        .build()
                )
                foregroundStarted = true
            }.onFailure {
                android.util.Log.e(TAG, "ensureForeground failed", it)
            }
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

    private fun loadLocalLyrics(song: Song): List<com.car.mp3player.model.LrcLine> {
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

    private fun playNext() {
        runCatching {
            if (playlist.isEmpty()) return@runCatching
            val next = when (PlaybackStateHolder.playMode) {
                PlaybackMode.SHUFFLE -> {
                    if (shuffleQueue.isEmpty()) refillShuffleQueue()
                    shuffleQueue.removeFirstOrNull() ?: Random.nextInt(playlist.size)
                }
                PlaybackMode.ORDER -> if (currentIndex + 1 < playlist.size) currentIndex + 1 else 0
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
                PlaybackMode.ORDER -> if (currentIndex - 1 >= 0) currentIndex - 1 else playlist.lastIndex
            }
            playSongAt(prev)
        }.onFailure {
            android.util.Log.e(TAG, "playPrevious failed", it)
        }
    }

    private fun refillShuffleQueue() {
        shuffleQueue = playlist.indices.filter { it != currentIndex }.shuffled().toMutableList()
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
        const val ACTION_RESUME = "resume"
        const val ACTION_SEEK = "seek"
        const val ACTION_RELOAD_LYRICS = "reload_lyrics"
        const val ACTION_STOP = "stop"
        const val EXTRA_INDEX = "index"
        const val EXTRA_SEEK = "seek"
        const val EXTRA_LIBRARY = "library"
        const val EXTRA_PLAYLIST = "playlist"
        const val EXTRA_MODE = "mode"
        const val EXTRA_SEARCH_TITLE = "search_title"
        const val EXTRA_SEARCH_ARTIST = "search_artist"
        private const val CHANNEL_ID = "playback"
        private const val NOTIFICATION_ID = 1001
        private const val TAG = "MusicPlaybackService"
        private val MEDIA_CONTROL_ACTIONS = setOf(
            ACTION_PLAY_INDEX, ACTION_TOGGLE, ACTION_PLAY, ACTION_PAUSE,
            ACTION_NEXT, ACTION_PREV, ACTION_RESUME
        )
    }
}
