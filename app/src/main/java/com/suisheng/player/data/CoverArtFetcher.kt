package com.suisheng.player.data

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.suisheng.player.model.Song
import com.suisheng.player.util.MediaPath
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class CoverArtFetcher(private val context: Context) {
    /**
     * 取一张封面写到 [cacheFile]，返回目标文件路径；取不到返回 null。
     *
     * @param preferEmbedded 是否**先**用音频内嵌图。默认 true —— 本地文件带 APIC 时
     *   内嵌图是零延迟、零流量的最优解，`loadCover` 就该走这条。
     *   传 false 时改成「先找网络图，找不到再退回内嵌」，这是「重新加载封面」要的语义：
     *   用户点这个按钮的意思是「去找一张更好的」，而不是「把同一张图再写一遍」。
     *   5.9.1 没有这个开关，内嵌永远第一优先，于是本地文件的重新加载是个空操作。
     */
    fun fetch(
        song: Song,
        cacheFile: File,
        allowOnline: Boolean = true,
        onlineMusicApi: OnlineMusicApi? = null,
        preferEmbedded: Boolean = true,
    ): String? {
        if (preferEmbedded) {
            fetchEmbeddedOnly(song, cacheFile)?.let { return it }
            if (!allowOnline) return null
            return fetchOnline(song, cacheFile, onlineMusicApi)
                ?: fetchEmbeddedOnly(song, cacheFile)
        }
        if (allowOnline) {
            fetchOnline(song, cacheFile, onlineMusicApi)?.let { return it }
        }
        return fetchEmbeddedOnly(song, cacheFile)
    }

    /**
     * 只走网络：在线曲目的 picId / 播客 favicon / iTunes 检索。
     *
     * 下载写成 `<目标>.download` 再 rename，不直接往目标文件写：
     * 网络中途断掉时直接写目标会留下**非空残片**，而 `loadCover` 只判
     * `length() > 0`，那份残片就会被当成有效封面一直用下去。
     */
    private fun fetchOnline(song: Song, cacheFile: File, onlineMusicApi: OnlineMusicApi?): String? {
        MediaPath.parseOnline(song.path)?.let { parts ->
            val picCandidates = listOfNotNull(
                parts.picId?.takeIf { isValidPicId(it) },
                song.lrcPath?.removePrefix("pic:")?.takeIf { isValidPicId(it) },
                parts.trackId.takeIf { it.isNotBlank() }
            ).distinct()
            for (picId in picCandidates) {
                val url = onlineMusicApi?.fetchCoverUrl(parts.source, picId) ?: continue
                downloadToFile(url, cacheFile)?.let { return it }
            }
        }
        song.lrcPath?.removePrefix("favicon:")?.takeIf { it.startsWith("http") }?.let { url ->
            return downloadToFile(url, cacheFile)
        }
        val url = searchItunesArtUrl(song.title, song.artist) ?: return null
        return downloadToFile(url, cacheFile)
    }

    fun fetchEmbeddedOnly(song: Song, cacheFile: File): String? {
        cacheFile.parentFile?.mkdirs()
        extractEmbedded(song)?.let { bytes ->
            cacheFile.writeBytes(bytes)
            return cacheFile.absolutePath
        }
        return null
    }

    private fun extractEmbedded(song: Song): ByteArray? {
        if (com.suisheng.player.util.MediaPath.isStream(song.path)) return null
        val retriever = MediaMetadataRetriever()
        return try {
            if (song.path.startsWith("content://")) {
                retriever.setDataSource(context, Uri.parse(song.path))
            } else {
                retriever.setDataSource(song.path)
            }
            retriever.embeddedPicture
        } catch (_: Exception) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun searchItunesArtUrl(title: String, artist: String): String? {
        val term = listOf(title, artist)
            .filter { it.isNotBlank() && it !in setOf("未知歌手", "本地音乐") }
            .joinToString(" ")
        if (term.isBlank()) return null
        val url = "https://itunes.apple.com/search?term=${encode(term)}&entity=song&limit=1"
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000
            readTimeout = 8000
            requestMethod = "GET"
        }
        return try {
            val body = connection.inputStream.bufferedReader().readText()
            val results = JSONObject(body).optJSONArray("results") ?: return null
            if (results.length() == 0) return null
            results.optJSONObject(0)
                ?.optString("artworkUrl100")
                ?.replace("100x100bb", "600x600bb")
        } catch (_: Exception) {
            null
        } finally {
            connection.disconnect()
        }
    }

    /**
     * 下载到 [file]：先写同目录的 `<name>.download`，成功且非空才 rename 过去。
     *
     * 直接 `file.outputStream()` 的问题：连接中途断开时目标文件会留下半张图。
     * `SongMetadataLoader.loadCover` 只判非空就复用缓存，半张图会被当成有效封面一直用。
     */
    private fun downloadToFile(url: String, file: File): String? {
        val connection = (URL(normalizeDownloadUrl(url)).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10000
            readTimeout = 10000
            setRequestProperty("User-Agent", "MP3Player/4.0 (Android)")
        }
        val staging = File(file.parentFile, "${file.name}.download")
        return try {
            file.parentFile?.mkdirs()
            connection.inputStream.use { input ->
                staging.outputStream().use { output -> input.copyTo(output) }
            }
            if (staging.length() <= 0L) {
                runCatching { staging.delete() }
                return null
            }
            // rename 到已存在目标：同目录同文件系统，Linux rename(2) 是原子替换
            if (!staging.renameTo(file)) {
                runCatching { staging.delete() }
                return null
            }
            file.takeIf { it.length() > 0L }?.absolutePath
        } catch (_: Exception) {
            runCatching { staging.delete() }
            null
        } finally {
            connection.disconnect()
        }
    }

    private fun normalizeDownloadUrl(url: String): String =
        if (url.startsWith("http://")) "https://${url.removePrefix("http://")}" else url

    private fun isValidPicId(picId: String): Boolean =
        picId.isNotBlank() && picId != "0"

    private fun encode(value: String): String =
        URLEncoder.encode(value, Charsets.UTF_8.name())
}
