package com.suisheng.player.data

import android.content.Context
import com.suisheng.player.lrc.LrcParser
import com.suisheng.player.lrc.LyricDocument
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
    /** 每首歌独立的歌词偏好（用户偏移、注音语种、指定渠道）。 */
    private val perSongPrefs = PerSongLyricPrefs(context)

    fun loadLyrics(song: Song, forceOnline: Boolean = false): LyricLoadResult? {
        val title = settings.lyricSearchTitle(song.path) ?: song.title
        val artist = settings.lyricSearchArtist(song.path) ?: song.artist

        if (!forceOnline) {
            LyricFileStore.readDocument(context, song)?.let { document ->
                val path = song.lrcPath
                    ?: LyricFileStore.resolveDocumentPath(context, song)
                    ?: LyricFileStore.resolveSidecarPath(context, song)
                return LyricLoadResult(document.lines, path, document)
            }
            LyricFileStore.migrateLegacyCache(context, song, cacheDir)?.let { savedPath ->
                LyricFileStore.readDocument(context, song.copy(lrcPath = savedPath))?.let { document ->
                    return LyricLoadResult(document.lines, savedPath, document)
                }
            }
            loadOnlineApiLyrics(song)?.let { return it }
            loadPodcastDescriptionLyrics(song)?.let { return it }
        } else {
            LyricFileStore.delete(context, song)
            LyricFileStore.deleteLegacyCache(cacheDir, song)
        }

        if (!settings.onlineLyricsEnabled) return null
        val config = settings.lyricProviderConfig()
        // 用户对单曲指定过渠道（「换一个渠道试试」）时**先试那一个**；
        // 试不到再按全局顺序走一遍 —— 指定渠道不等于放弃兜底，否则用户点错一次
        // 这首歌就再也取不到词了。
        val override = perSongPrefs.providerOverride(song)
        val outcome = (override?.let { lyricFetcher.fetch(title, artist, config, it) }
            ?: lyricFetcher.fetch(title, artist, config))
            ?: return null
        val savedPath = LyricFileStore.saveDocument(context, song, outcome.document)
        return LyricLoadResult(outcome.document.lines, savedPath, outcome.document)
    }

    /**
     * 只读本地（含在线曲目的缓存），不触发任何网络请求。
     *
     * 返回完整文档 —— 译文/注音/文件内偏移都在里面，供 [com.suisheng.player.playback.PlaybackStateHolder]
     * 写入时间轴。
     */
    fun readLocalDocument(song: Song): LyricDocument? {
        LyricFileStore.readDocument(context, song)?.let { return it }
        LyricFileStore.migrateLegacyCache(context, song, cacheDir)?.let { savedPath ->
            LyricFileStore.readDocument(context, song.copy(lrcPath = savedPath))?.let { return it }
        }
        return loadPodcastDescriptionLyrics(song)?.document
    }

    fun readLocalLyrics(song: Song): List<LrcLine>? = readLocalDocument(song)?.lines

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
        val document = LrcParser.parseDocument(lyricText).let { doc ->
            if (doc.lines.isNotEmpty()) {
                doc.copy(sourceProviderId = doc.sourceProviderId ?: SOURCE_ONLINE_API)
            } else {
                LyricDocument.singleTrack(LrcParser.fromPlainText(lyricText))
                    .copy(sourceProviderId = SOURCE_ONLINE_API)
            }
        }
        if (document.lines.isEmpty()) return null
        val savedPath = LyricFileStore.saveDocument(context, song, document)
        return LyricLoadResult(document.lines, savedPath, document)
    }

    private fun loadPodcastDescriptionLyrics(song: Song): LyricLoadResult? {
        if (!settings.podcastShowDescription || !MediaPath.isPodcast(song.path)) return null
        val repo = rssFeedRepository ?: return null
        val description = repo.episodeDescription(song.path)?.takeIf { it.isNotBlank() } ?: return null
        val document = LyricDocument.singleTrack(LrcParser.fromPlainText(description))
            .copy(sourceProviderId = SOURCE_PODCAST)
        return LyricLoadResult(document.lines, null, document)
    }

    private fun coverCacheFile(song: Song): File {
        val digest = java.security.MessageDigest.getInstance("MD5")
        val bytes = digest.digest("${song.path}|${song.title}|${song.artist}".toByteArray())
        val key = bytes.joinToString("") { "%02x".format(it) }
        return File(File(cacheDir, "covers"), "$key.jpg")
    }

    /**
     * 一次歌词加载的结果。
     *
     * [document] 是权威形态（带附加轨与文件偏移），[lines] 只是它的原文行 ——
     * 保留 `lines` 是因为旧调用点只关心行列表，改签名会牵动四条消费链。
     */
    data class LyricLoadResult(
        val lines: List<LrcLine>,
        val lrcPath: String?,
        val document: LyricDocument = LyricDocument.singleTrack(lines),
    )

    private companion object {
        const val SOURCE_ONLINE_API = "online_api"
        const val SOURCE_PODCAST = "podcast_desc"
    }
}
