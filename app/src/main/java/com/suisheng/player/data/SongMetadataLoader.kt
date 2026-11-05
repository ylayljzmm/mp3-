package com.suisheng.player.data

import android.content.Context
import com.suisheng.player.lrc.LrcParser
import com.suisheng.player.model.LrcLine
import com.suisheng.player.model.Song
import com.suisheng.player.util.MediaPath
import java.io.File

class SongMetadataLoader(
    private val context: Context,
    private val cacheDir: File,
    private val settings: SettingsRepository,
    private val lyricFetcher: OnlineLyricFetcher = OnlineLyricFetcher(),
    private val coverFetcher: CoverArtFetcher,
    private val onlineMusicApi: OnlineMusicApi? = null,
    private val rssFeedRepository: RssFeedRepository? = null
) {
    fun loadLyrics(song: Song, forceOnline: Boolean = false): LyricLoadResult? {
        val title = settings.lyricSearchTitle(song.path) ?: song.title
        val artist = settings.lyricSearchArtist(song.path) ?: song.artist

        if (!forceOnline) {
            LyricFileStore.read(context, song)?.let { lines ->
                val path = song.lrcPath ?: LyricFileStore.resolveSidecarPath(context, song)
                return LyricLoadResult(lines, path)
            }
            LyricFileStore.migrateLegacyCache(context, song, cacheDir)?.let { savedPath ->
                LyricFileStore.read(context, song.copy(lrcPath = savedPath))?.let { lines ->
                    return LyricLoadResult(lines, savedPath)
                }
            }
            loadOnlineApiLyrics(song)?.let { return it }
            loadPodcastDescriptionLyrics(song)?.let { return it }
        } else {
            LyricFileStore.delete(context, song)
            LyricFileStore.deleteLegacyCache(cacheDir, song)
        }

        if (!settings.onlineLyricsEnabled) return null
        val fetched = lyricFetcher.fetch(title, artist) ?: return null
        val lrcText = buildLrcText(fetched)
        val savedPath = LyricFileStore.save(context, song, lrcText)
        return LyricLoadResult(fetched, savedPath)
    }

    fun readLocalLyrics(song: Song): List<LrcLine>? {
        LyricFileStore.read(context, song)?.let { return it }
        LyricFileStore.migrateLegacyCache(context, song, cacheDir)?.let { savedPath ->
            LyricFileStore.read(context, song.copy(lrcPath = savedPath))?.let { return it }
        }
        return loadPodcastDescriptionLyrics(song)?.lines
    }

    fun loadCover(song: Song): String? {
        val cache = coverCacheFile(song)
        if (cache.exists() && cache.length() > 0) return cache.absolutePath
        return coverFetcher.fetch(song, cache, allowOnline = settings.onlineCoverEnabled, onlineMusicApi = onlineMusicApi)
    }

    private fun loadOnlineApiLyrics(song: Song): LyricLoadResult? {
        if (!settings.onlineLyricsEnabled) return null
        val api = onlineMusicApi ?: return null
        val parts = MediaPath.parseOnline(song.path) ?: return null
        val lyricText = api.fetchLyric(parts.source, parts.trackId) ?: return null
        val lines = LrcParser.parseContent(lyricText).ifEmpty { LrcParser.fromPlainText(lyricText) }
        if (lines.isEmpty()) return null
        val savedPath = LyricFileStore.save(context, song, lyricText)
        return LyricLoadResult(lines, savedPath)
    }

    private fun loadPodcastDescriptionLyrics(song: Song): LyricLoadResult? {
        if (!settings.podcastShowDescription || !MediaPath.isPodcast(song.path)) return null
        val repo = rssFeedRepository ?: return null
        val description = repo.episodeDescription(song.path)?.takeIf { it.isNotBlank() } ?: return null
        val lines = LrcParser.fromPlainText(description)
        return LyricLoadResult(lines, null)
    }

    private fun buildLrcText(lines: List<LrcLine>): String {
        return lines.joinToString("\n") { line ->
            "${formatTime(line.startTimeMs)}${line.text}"
        }
    }

    private fun formatTime(ms: Long): String {
        val totalSec = ms / 1000
        val min = totalSec / 60
        val sec = totalSec % 60
        val centi = (ms % 1000) / 10
        return "[${min.toString().padStart(2, '0')}:${sec.toString().padStart(2, '0')}.${centi.toString().padStart(2, '0')}]"
    }

    private fun coverCacheFile(song: Song): File {
        val digest = java.security.MessageDigest.getInstance("MD5")
        val bytes = digest.digest("${song.path}|${song.title}|${song.artist}".toByteArray())
        val key = bytes.joinToString("") { "%02x".format(it) }
        return File(File(cacheDir, "covers"), "$key.jpg")
    }

    data class LyricLoadResult(
        val lines: List<LrcLine>,
        val lrcPath: String?
    )
}
