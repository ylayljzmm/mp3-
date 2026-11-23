package com.suisheng.player.data

import android.content.Context
import android.graphics.BitmapFactory
import com.suisheng.player.lrc.LrcParser
import com.suisheng.player.lrc.LyricDocument
import com.suisheng.player.lrc.LyricProviders
import com.suisheng.player.model.CoverReloadOutcome
import com.suisheng.player.model.CoverReloadResult
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

    /**
     * 封面写入的进程内锁。
     *
     * [loadCover] 的首载下载与 [reloadCover] 的 tmp 替换落的是同一个缓存目录（同一首歌
     * 就是同一个文件名），5.9.1 只防「重载之间」的并发，没防「重载 vs 首载」，
     * 并发时后写的一方会把对方的图盖掉。粒度取实例锁即可：loader 在服务里是单例。
     */
    private val coverWriteLock = Any()

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
        // 带上时长：搜索接口的候选排序会用「候选时长和这首歌差多少」把伴奏、remix、
        // 同名翻唱压下去，这是《晴天》这类被翻唱淹没的歌能选对的关键信号。
        // `durationMs` 拿不到时是 0，`OnlineLyricFetcher` 会跳过时长项，不会误判。
        val outcome = (override?.let {
            lyricFetcher.fetch(title, artist, config, it, preferWordLevel, song.durationMs)
        } ?: lyricFetcher.fetch(title, artist, config, null, preferWordLevel, song.durationMs))
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
     * 这份已经落盘的歌词，是不是 5.9.1 那批「译文/注音被时间轴错配吃掉」的残缺数据。
     *
     * 5.9.1 把网易云的 `tlyric` / `romalrc` 直接按时间戳挂到**逐字轨**上，两边时间基准
     * 不同，于是有逐字轨的歌成片丢译文 —— 那些 `.lrcx` 里存的就是「有正文、没译文」。
     * 光改代码不会让已落盘的文件自己变好，所以由播放链路在后台补一次重取。
     *
     * 只对 `netease` 判定：译文/注音只有它给，其它渠道的文档 `alignVersion` 恒为 0，
     * 拿 0 去比会把每一份 lrclib/本地歌词都误判成「该重取」。
     */
    fun isStaleLyrics(document: LyricDocument): Boolean =
        document.sourceProviderId == LyricProviders.NETEASE &&
            document.alignVersion < LyricDocument.ALIGN_VERSION_CURRENT &&
            document.lines.isNotEmpty()

    /**
     * 读取封面（播放链路唯一入口）。
     *
     * 返回的**路径不随文件内容变化**：[reloadCover] 会在同一路径上原子替换内容，再靠
     * `PlaybackStateHolder.setCoverArt()` 广播一次刷新黑胶中心 / 信息页 / 迷你条。
     * 因此这里与 `setCoverArt()` 都**禁止加「路径没变就跳过」的去重**，否则重载封面会毫无效果。
     *
     * 命中缓存时还要求**可解码**：5.9.1 只判 `length() > 0`，联网下载被中断留下的
     * 半张图会被当成有效封面永久复用，用户看到的是灰块或裂图，且重载也救不回来
     * （重载会判它不可解码而失败）。这里判不过就当没缓存，重新取一次。
     */
    fun loadCover(song: Song): String? = synchronized(coverWriteLock) {
        val cache = coverCacheFile(song)
        if (cache.exists() && cache.length() > 0 && isDecodable(cache)) return@synchronized cache.absolutePath
        coverFetcher.fetch(song, cache, allowOnline = settings.onlineCoverEnabled, onlineMusicApi = onlineMusicApi)
    }

    /**
     * 强制重取当前曲目的封面：绕过 [loadCover] 的缓存快路，重解析一次后原子替换。
     *
     * 磁盘缓存的 key、目录、扩展名一个字节都不改 —— 改了等于让全库已缓存封面失效，
     * 下次切歌会逐首重开 `MediaMetadataRetriever`，车机上是一次可感知的卡顿。
     *
     * 只写同目录的 `.tmp`：联网下载中途异常会留下**非空残片**，直接往正式文件写会把
     * 坏图当成有效缓存一直用下去，而且失败时原封面已经丢了。失败一律删 tmp、正式缓存原样保留。
     * 是否联网严格服从 `settings.onlineCoverEnabled`；但取图顺序与 [loadCover] **相反** ——
     * 先找网络图、找不到再退回内嵌（见 `CoverArtFetcher.fetch(preferEmbedded = false)`），
     * 否则本地带 APIC 的文件点「重新加载」只会把同一张图再写一遍，用户看到的就是没反应。
     *
     * 与 [loadCover] 用同一把锁串行化：首载的 `downloadToFile` 与重载的 tmp 替换
     * 写的是同一个缓存目录，并发时可能互相盖掉。锁的粒度是「这个 loader 实例」，
     * 而 loader 是服务里的单例，够用。
     */
    fun reloadCover(song: Song): CoverReloadResult = synchronized(coverWriteLock) {
        val cache = coverCacheFile(song)
        val parent = cache.parentFile ?: return@synchronized CoverReloadResult(CoverReloadOutcome.IO_FAILED)
        val tmp = File(parent, "${cache.name}.tmp")
        val bak = File(parent, "${cache.name}.bak")

        // 自愈：上一次进程在两次 rename 之间被杀，正式缓存没了但备份还在。
        // 5.9.1 在函数开头把 bak 无条件删掉，等于把唯一可用的图也销毁了。
        if (!cache.exists() && bak.exists()) {
            if (isDecodable(bak)) {
                runCatching { bak.renameTo(cache) }
            } else {
                runCatching { bak.delete() }
            }
        }
        runCatching { tmp.delete() }

        var replaced = false
        try {
            val fetched = coverFetcher.fetch(
                song,
                tmp,
                allowOnline = settings.onlineCoverEnabled,
                onlineMusicApi = onlineMusicApi,
                preferEmbedded = false,
            )
            if (fetched == null || !tmp.exists() || tmp.length() <= 0L) {
                // 没有可用的新图：本地没内嵌、网络也没搜到。区分「开关关着」和「真的没有」
                return@synchronized CoverReloadResult(
                    if (settings.onlineCoverEnabled) {
                        CoverReloadOutcome.NO_SOURCE
                    } else {
                        CoverReloadOutcome.ONLINE_DISABLED
                    }
                )
            }
            if (!isDecodable(tmp)) {
                return@synchronized CoverReloadResult(CoverReloadOutcome.NOT_DECODABLE)
            }
            // 新图与旧图逐字节相同：最常见于「本地只有一张内嵌封面，网络也没更好的」。
            // 这不叫失败 —— 但也不能报「已更新」，否则用户会以为按钮没生效。
            if (cache.exists() && sameContent(tmp, cache)) {
                return@synchronized CoverReloadResult(CoverReloadOutcome.UNCHANGED, cache.absolutePath)
            }
            val hadCache = cache.exists()
            if (hadCache && !cache.renameTo(bak)) {
                return@synchronized CoverReloadResult(CoverReloadOutcome.IO_FAILED)
            }
            if (!tmp.renameTo(cache)) {
                if (hadCache) runCatching { bak.renameTo(cache) }
                return@synchronized CoverReloadResult(CoverReloadOutcome.IO_FAILED)
            }
            replaced = true
            CoverReloadResult(CoverReloadOutcome.UPDATED, cache.absolutePath)
        } catch (_: Exception) {
            CoverReloadResult(CoverReloadOutcome.IO_FAILED)
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

    /** 逐字节比较（封面文件百 KB 级，比算哈希更直接）。 */
    private fun sameContent(a: File, b: File): Boolean {
        if (a.length() != b.length()) return false
        return runCatching { a.readBytes().contentEquals(b.readBytes()) }.getOrDefault(false)
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
