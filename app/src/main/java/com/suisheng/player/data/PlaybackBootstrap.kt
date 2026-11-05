package com.suisheng.player.data

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.suisheng.player.MusicPlaybackService
import com.suisheng.player.model.LibraryKind
import com.suisheng.player.model.Song
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

object PlaybackBootstrap {
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun scanMusicLibrary(context: Context, settings: SettingsRepository): List<Song> {
        val songs = MusicScanner(
            context,
            settings.scanPaths(),
            settings.scanTreeUris()
        ).scan()
        if (songs.isNotEmpty()) {
            PlaylistCache.save(context, songs)
        }
        return songs
    }

    fun loadCachedMusic(context: Context): List<Song> = PlaylistCache.load(context)

    fun resumeIfNeeded(
        context: Context,
        songs: List<Song>,
        settings: SettingsRepository,
        library: LibraryKind = settings.lastActiveLibrary
    ) {
        if (!settings.autoResumePlayback || songs.isEmpty()) return
        val path = settings.lastSongPath(library) ?: return
        val index = songs.indexOfFirst { it.path == path }
        if (index < 0) {
            settings.setLastSong(library, null, 0L)
            return
        }
        if (!songReadable(songs[index])) {
            settings.setLastSong(library, null, 0L)
            return
        }
        ioScope.launch { PlaylistCache.saveQueue(context, songs, library) }
        val intent = Intent(context, MusicPlaybackService::class.java).apply {
            action = MusicPlaybackService.ACTION_PLAY_INDEX
            putExtra(MusicPlaybackService.EXTRA_INDEX, index)
            putExtra(MusicPlaybackService.EXTRA_SEEK, settings.lastPositionMs(library))
            putExtra(MusicPlaybackService.EXTRA_LIBRARY, library.name)
        }
        runCatching { ContextCompat.startForegroundService(context, intent) }
    }

    private fun songReadable(song: Song): Boolean {
        if (song.path.startsWith("content://")) return true
        if (song.path.startsWith("http") || song.path.startsWith("online://") ||
            song.path.startsWith("radio://") || song.path.startsWith("podcast://")
        ) return true
        return runCatching { File(song.path).isFile }.getOrDefault(false)
    }
}
