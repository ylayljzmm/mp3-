package com.suisheng.player.data

import android.content.Context
import android.graphics.BitmapFactory
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
            // 旧实现在这里先 delete() 再取词：网络一失败歌词就没了，而且 delete() 会连
            // 用户手放的 `.lrc` 一起删。现在改为「不预先删除」—— 取到新词后 saveDocument()
            // 会覆盖 `.lrcx`；取不到就原样保留旧歌词，用户最多是「换了渠道但没变化」。
            LyricFileStore.deleteLegacyCache(cacheDir, song)
        }

        if (!settings.onlineLyricsEnabled) return null
        val config = settings.lyricProviderConfig()
        val preferWordLevel = settings.lyricWordLevelEnabled
        // 用户对单曲指定过渠道（「换一个渠道试试」）时**先试那一个**；
        // 试不到再按全局顺序走一遍 —— 指定渠道不等于放弃兜底，否则用户点错一次
        // 这首歌就再也取不到词了。
        val override = perSongPrefs.providerOverride(song)
        val outcome = (override?.let {
            lyricFetcher.fetch(title, artist, config, it, preferWordLevel)
        } ?: lyricFetcher.fetch(title, artist, config, null, preferWordLevel))
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

    /**
     * 读取封面（播放链路唯一入口）。
     *
     * 返回的**路径不随文件内容变化**：[reloadCover] 会在同一路径上原子替换内容，再靠
     * `PlaybackStateHolder.setCoverArt()` 广播一次刷新黑胶中心 / 信息页 / 迷你条。
     * 因此这里与 `setCoverArt()` 都**禁止加「路径没变就跳过」的去重**，否则重载封面会毫无效果。
     */
    fun loadCover(song: Song): String? {
        val cache = coverCacheFile(song)
        if (cache.exists() && cache.length() > 0) return cache.absolutePath
        return coverFetcher.fetch(song, cache, allowOnline = settings.onlineCoverEnabled, onlineMusicApi = onlineMusicApi)
    }

    /**
     * 强制重取当前曲目的封面：绕过 [loadCover] 的缓存快路，重解析一次后原子替换。
     *
     * 磁盘缓存的 key、目录、扩展名一个字节都不改 —— 改了等于让全库已缓存封面失效，
     * 下次切歌会逐首重开 `MediaMetadataRetriever`，车机上是一次可感知的卡顿。
     *
     * 只写同目录的 `.tmp`：联网下载中途异常会留下**非空残片**，直接往正式文件写会把
     * 坏图当成有效缓存一直用下去，而且失败时原封面已经丢了。失败一律删 tmp、正式缓存原样保留。
     * 是否联网严格服从 `settings.onlineCoverEnabled`，与 [loadCover] 完全同口径。
     *
     * @return 成功时返回**原缓存文件的路径**（内容已是新图）；失败返回 null，调用方不得据此清空封面。
     */
    fun reloadCover(song: Song): String? {
        val cache = coverCacheFile(song)
        val parent = cache.parentFile ?: return null
        val tmp = File(parent, "${cache.name}.tmp")
        val bak = File(parent, "${cache.name}.bak")
        runCatching { tmp.delete(); bak.delete() }
        var replaced = false
        try {
            val fetched = coverFetcher.fetch(
                song,
                tmp,
                allowOnline = settings.onlineCoverEnabled,
                onlineMusicApi = onlineMusicApi,
            )
            if (fetched == null || !tmp.exists() || tmp.length() <= 0L || !isDecodable(tmp)) {
                return null
            }
            val hadCache = cache.exists()
            if (hadCache && !cache.renameTo(bak)) return null
            if (!tmp.renameTo(cache)) {
                if (hadCache) bak.renameTo(cache)
                return null
            }
            replaced = true
            return cache.absolutePath
        } catch (_: Exception) {
            return null
        } finally {
            runCatching { tmp.delete() }
            if (replaced) {
                runCatching { bak.delete() }
            } else if (bak.exists()) {
                // 替换没成功：原图还在就丢掉备份，不在就把备份还回去，绝不丢用户看到的封面
                if (cache.exists()) runCatching { bak.delete() }
                else runCatching { bak.renameTo(cache) }
            }
        }
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

    /** 只解边界，不分配整张位图；挡住下载残片 / 非图片文件被当成有效缓存。 */
    private fun isDecodable(file: File): Boolean {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        return runCatching {
            BitmapFactory.decodeFile(file.absolutePath, options)
            options.outWidth > 0 && options.outHeight > 0
        }.getOrDefault(false)
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
