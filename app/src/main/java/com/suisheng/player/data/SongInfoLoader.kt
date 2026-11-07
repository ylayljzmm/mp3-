package com.suisheng.player.data

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.suisheng.player.R
import com.suisheng.player.model.Song
import com.suisheng.player.model.SongInfoDetails
import java.io.File

/**
 * 歌曲信息页的数据来源。
 *
 * ## 四个刻意的约束
 *
 * ① **必须在后台线程调用**。`setDataSource` + `extractMetadata` 要打开文件并解析容器头，
 *    车机上 U 盘里的 FLAC 单次要几十毫秒；播放页每秒有 8 次状态回调，
 *    在主线程做这件事会直接把进度条拖成幻灯片。调用方见 `PlayerFragment.loadSongInfo()`。
 *
 * ② **逐字段兜底，而不是整段兜底**。曾经是「一个 try 包住全部字段」，
 *    结果 `METADATA_KEY_BITS_PER_SAMPLE` 在某台定制 ROM 上抛一次异常，
 *    把**已经读到的** mimeType / bitrate 一起清成了 null，信息页只剩三行。
 *    现在只有「打开数据源」这一层整体 `runCatching`（打不开就是打不开，全空合理），
 *    每个 `extractMetadata` 各自兜底，坏一个字段不影响其它字段。
 *
 * ③ **必须支持 SAF（`content://`）曲目**。设置页「添加文件夹」走的是
 *    `ACTION_OPEN_DOCUMENT_TREE`，`MusicScanner.walkDocumentFile` 存进 `Song.path`
 *    的是 `file.uri.toString()`。`setDataSource(String)` 只认绝对路径，
 *    直接喂 `content://` 会抛异常 —— 那样 SAF 导入的歌信息页 7 行全是「—」。
 *    双分支写法和 `MusicScanner.readMetadata` / `CoverArtFetcher.extractEmbedded` 保持一致。
 *
 * ④ **不用 `use { }`**。`MediaMetadataRetriever` 只有 API 29+ 才实现 `AutoCloseable`，
 *    本项目 minSdk 24，用 `use` 会在 24~28 上编译不过。改成 `try / finally` +
 *    `runCatching { release() }`：`release()` 在部分机型上会抛 `IllegalStateException`
 *    （未成功 setDataSource 时），这里吞掉即可。
 */
object SongInfoLoader {

    /**
     * 读取一首歌的技术参数。
     *
     * @param hasLyrics 当前是否已解析出歌词（内嵌或已抓取），用来判断 [SongInfoDetails.lrcLabel]
     * @param onlineLyricsEnabled 用户是否开着「联网歌词」；只有开着才可能显示「联网获取」
     */
    fun load(
        context: Context,
        song: Song,
        hasLyrics: Boolean,
        onlineLyricsEnabled: Boolean,
    ): SongInfoDetails {
        val appContext = context.applicationContext
        val path = song.path
        val lrcLabel = lyricsLabel(appContext, song, hasLyrics, onlineLyricsEnabled)
        val fileSize = fileSizeOf(appContext, path)
        val format = formatOf(path)

        var mimeType: String? = null
        var bitrateKbps: Int? = null
        var sampleRateHz: Int? = null
        var bitsPerSample: Int? = null
        var trackNumber: String? = null

        // 这一层只兜「打开数据源 / 释放」的失败：打不开就是读不到任何元数据，
        // 全空是合理的。字段级读取的异常由各自的 runCatching 消化，不许外溢到这里。
        runCatching {
            val retriever = MediaMetadataRetriever()
            try {
                if (path.startsWith(SAF_SCHEME)) {
                    retriever.setDataSource(appContext, Uri.parse(path))
                } else {
                    retriever.setDataSource(path)
                }
                mimeType = runCatching {
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE)
                        ?.takeIf { it.isNotBlank() }
                }.getOrNull()
                bitrateKbps = runCatching {
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)
                        ?.toIntOrNull()
                        ?.div(1000)
                        ?.takeIf { it > 0 }
                }.getOrNull()
                // SAMPLERATE 与 BITS_PER_SAMPLE 是一批（API 31）的常量：值都是编译期
                // 常量会被内联，老系统上只是取不到、不会 NoSuchFieldError。但两条路径
                // 同样会踩到个别定制 ROM 的异常，所以两个都要单独包。
                sampleRateHz = runCatching {
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_SAMPLERATE)
                        ?.toIntOrNull()
                        ?.takeIf { it > 0 }
                }.getOrNull()
                bitsPerSample = runCatching {
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITS_PER_SAMPLE)
                        ?.toIntOrNull()
                        ?.takeIf { it > 0 }
                }.getOrNull()
                trackNumber = runCatching {
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)
                        ?.takeIf { it.isNotBlank() }
                }.getOrNull()
            } finally {
                runCatching { retriever.release() }
            }
        }

        return SongInfoDetails(
            format = format,
            mimeType = mimeType,
            bitrateKbps = bitrateKbps,
            sampleRateHz = sampleRateHz,
            bitsPerSample = bitsPerSample,
            fileSizeBytes = fileSize,
            trackNumber = trackNumber,
            lrcLabel = lrcLabel,
        )
    }

    /**
     * 文件大小。
     *
     * SAF 曲目没有真实路径，只能问 `ContentResolver` 要 `AssetFileDescriptor` 再读长度；
     * 有些 provider 不支持按长度查询，会返回 -1 或 0，一律当「未知」。
     * `AssetFileDescriptor` 实现了 `Closeable`（API 1 起），这里用 `use` 是安全的。
     */
    private fun fileSizeOf(context: Context, path: String): Long? =
        if (path.startsWith(SAF_SCHEME)) {
            runCatching {
                context.contentResolver
                    .openAssetFileDescriptor(Uri.parse(path), "r")
                    ?.use { it.length }
            }.getOrNull()?.takeIf { it > 0L }
        } else {
            runCatching { File(path).length() }.getOrNull()?.takeIf { it > 0L }
        }

    /**
     * 歌词来源的优先级与播放链路的取词顺序一致：
     * 同名 LRC 优先（用户自己放的）→ 内嵌 → 联网。三条都不成立就不显示这一行。
     */
    private fun lyricsLabel(
        context: Context,
        song: Song,
        hasLyrics: Boolean,
        onlineLyricsEnabled: Boolean,
    ): String? = when {
        !song.lrcPath.isNullOrBlank() -> context.getString(R.string.song_info_lyrics_local)
        hasLyrics -> context.getString(R.string.song_info_lyrics_embedded)
        onlineLyricsEnabled -> context.getString(R.string.song_info_lyrics_online)
        else -> null
    }

    /** 扩展名大写；无扩展名给 null（"flac" → "FLAC"）。 */
    private fun formatOf(path: String): String? = File(path)
        .extension
        .takeIf { it.isNotBlank() }
        ?.uppercase()

    /** SAF 曲目在 `Song.path` 里的前缀（与 `MusicScanner` / `CoverArtFetcher` 一致）。 */
    private const val SAF_SCHEME = "content://"
}
