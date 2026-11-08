package com.suisheng.player.data

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.suisheng.player.lrc.LrcParser
import com.suisheng.player.lrc.LyricDocument
import com.suisheng.player.model.LrcLine
import com.suisheng.player.model.Song
import com.suisheng.player.util.MediaPath
import java.io.File
import java.security.MessageDigest

/**
 * 歌词与歌曲同目录存放（同名 `.lrc`），卸载 App 后仍保留。
 *
 * ## 两种后缀的分工
 *
 * - `.lrc` —— 标准单轨格式。**可能是用户自己放的、也可能是别的软件写的**，
 *   本 App 只在「用户显式要求重新获取」之外的情况下**永不改写它**；
 * - `.lrcx` —— 本项目的多轨格式（原文 + 译文 + 注音 + 文件内偏移），
 *   用私有 `#@` 前缀行承载附加信息，标准播放器打开时只看到干净的原文行。
 *
 * 读取顺序上 `.lrcx` 优先于 `.lrc`：`.lrcx` 是「更全的那一份」，而用户手放的 `.lrc`
 * 依然能正常读到（没有 `.lrcx` 时回退）。
 *
 * ## 为什么不能把译文塞进 `.lrc`
 *
 * 有些软件（以及本 App 的旧版本）习惯把译文写成两行同时间戳的 `[mm:ss.xx]`，
 * 这会直接污染 `findState` 的进度判定。`.lrcx` 用 `#@t` 前缀把它变成真正的附加轨，
 * 旧解析器因为拿不到时间标签会整行丢弃，反向兼容不需要迁移脚本。
 */
object LyricFileStore {

    /** 本项目多轨歌词的后缀（不含点）。 */
    const val EXT_MULTI_TRACK = "lrcx"

    // ---------------------------------------------------------------- 旧接口（单轨 .lrc）

    /**
     * 读单轨歌词行。
     *
     * 保留给「只关心原文行」的老链路（扫描器、`readLocalLyrics`）；
     * 需要译文/注音/偏移的调用方请用 [readDocument]。
     */
    fun read(context: Context, song: Song): List<LrcLine>? {
        song.lrcPath?.let { path ->
            if (!isMetadataMarker(path)) {
                readAtPath(context, path)?.let { return it }
            }
        }
        if (MediaPath.isStream(song.path)) {
            onlineFile(context, song, LRC).takeIf { it.exists() }?.let { file ->
                readAtPath(context, file.absolutePath)?.let { return it }
            }
        }
        resolveSidecarPath(context, song)?.let { path ->
            readAtPath(context, path)?.let { return it }
        }
        return null
    }

    /** 写单轨 `.lrc`（只在迁移旧缓存时用；联网取词一律走 [saveDocument]）。 */
    fun save(context: Context, song: Song, lrcText: String): String? =
        writeText(context, song, lrcText, LRC)

    // ---------------------------------------------------------------- 多轨文档

    /**
     * 读完整歌词文档（带译文 / 注音 / 文件内偏移）。
     *
     * 顺序固定为：同名 `.lrcx` → `song.lrcPath` → 在线缓存 `.lrcx` → 同名 `.lrc`。
     * 把 `.lrcx` 排在最前是因为它承载的轨更全；`song.lrcPath` 排在 `resolveDocumentPath`
     * 之后，是因为历史数据里 `lrcPath` 可能指向一份已被 `.lrcx` 取代的 `.lrc`。
     */
    fun readDocument(context: Context, song: Song): LyricDocument? {
        resolveDocumentPath(context, song)?.let { path ->
            readDocumentAtPath(context, path)?.let { return it }
        }
        song.lrcPath?.let { path ->
            if (!isMetadataMarker(path)) {
                readDocumentAtPath(context, path)?.let { return it }
            }
        }
        if (MediaPath.isStream(song.path)) {
            onlineFile(context, song, EXT_MULTI_TRACK).takeIf { it.exists() }?.let { file ->
                readDocumentAtPath(context, file.absolutePath)?.let { return it }
            }
        }
        resolveSidecarPath(context, song)?.let { path ->
            readDocumentAtPath(context, path)?.let { return it }
        }
        return null
    }

    /**
     * 写多轨文档，**总是**落到 `.lrcx`。
     *
     * 联网取到的东西一律走这里：用户同目录下的 `.lrc` 即使存在也不会被覆盖，
     * 所以「重新联网获取」不会毁掉用户自己整理过的歌词文件。
     */
    fun saveDocument(context: Context, song: Song, document: LyricDocument): String? {
        if (document.lines.isEmpty()) return null
        return writeText(context, song, LrcParser.serialize(document), EXT_MULTI_TRACK)
    }

    // ---------------------------------------------------------------- 删除 / 迁移

    fun delete(context: Context, song: Song) {
        song.lrcPath?.let { path ->
            if (!isMetadataMarker(path)) {
                deleteAtPath(context, path)
            }
        }
        if (MediaPath.isStream(song.path)) {
            onlineFile(context, song, LRC).delete()
            onlineFile(context, song, EXT_MULTI_TRACK).delete()
        }
        resolveSidecarPath(context, song)?.let { deleteAtPath(context, it) }
        resolveDocumentPath(context, song)?.let { deleteAtPath(context, it) }
    }

    fun migrateLegacyCache(context: Context, song: Song, cacheDir: File): String? {
        val cache = legacyCacheFile(cacheDir, song)
        if (!cache.exists() || cache.length() == 0L) return null
        val text = runCatching { cache.readText() }.getOrNull()?.takeIf { it.isNotBlank() } ?: return null
        val saved = save(context, song, text) ?: return null
        cache.delete()
        return saved
    }

    // ---------------------------------------------------------------- 路径解析

    fun resolveSidecarPath(context: Context, song: Song): String? {
        if (MediaPath.isStream(song.path)) return null
        if (song.path.startsWith("content://")) {
            return resolveDocumentSidecar(context, song.path, LRC)
        }
        val path = fileSidecarPath(song.path, LRC)
        return path.takeIf { File(it).exists() }
    }

    /** 同名 `.lrcx` 的路径（存在才返回）。 */
    fun resolveDocumentPath(context: Context, song: Song): String? {
        if (MediaPath.isStream(song.path)) return null
        if (song.path.startsWith("content://")) {
            return resolveDocumentSidecar(context, song.path, EXT_MULTI_TRACK)
        }
        val path = fileSidecarPath(song.path, EXT_MULTI_TRACK)
        return path.takeIf { File(it).exists() }
    }

    // ---------------------------------------------------------------- 内部实现

    private const val LRC = "lrc"

    private fun isMetadataMarker(path: String): Boolean =
        path.startsWith("pic:") || path.startsWith("favicon:") || path.startsWith("desc:")

    private fun onlineFile(context: Context, song: Song, ext: String): File =
        File(File(context.cacheDir, "lyrics"), "${songKey(song)}.$ext")

    private fun fileSidecarPath(audioPath: String, ext: String): String =
        "${audioPath.substringBeforeLast('.')}.$ext"

    private fun readAtPath(context: Context, path: String): List<LrcLine>? =
        readTextAtPath(context, path)?.let { LrcParser.parseContent(it) }?.takeIf { it.isNotEmpty() }

    private fun readDocumentAtPath(context: Context, path: String): LyricDocument? =
        readTextAtPath(context, path)?.let { LrcParser.parseDocument(it) }?.takeIf { it.lines.isNotEmpty() }

    private fun readTextAtPath(context: Context, path: String): String? {
        return if (path.startsWith("content://")) {
            runCatching {
                context.contentResolver.openInputStream(Uri.parse(path))?.use { stream ->
                    stream.reader().readText()
                }
            }.getOrNull()?.takeIf { it.isNotBlank() }
        } else {
            runCatching { File(path).readText() }.getOrNull()?.takeIf { it.isNotBlank() }
        }
    }

    /** 按载体分派写入：在线缓存 → SAF 目录 → 文件同目录。 */
    private fun writeText(context: Context, song: Song, text: String, ext: String): String? {
        if (song.path.startsWith("content://")) {
            return saveToDocumentTree(context, song, text, ext)
        }
        if (MediaPath.isStream(song.path)) {
            return saveToOnlineCache(context, song, text, ext)
        }
        return saveToFileSidecar(song.path, text, ext)
    }

    private fun saveToOnlineCache(context: Context, song: Song, text: String, ext: String): String? {
        val file = onlineFile(context, song, ext)
        return runCatching {
            file.parentFile?.mkdirs()
            file.writeText(text, Charsets.UTF_8)
            file.absolutePath
        }.getOrNull()
    }

    private fun saveToFileSidecar(audioPath: String, text: String, ext: String): String? {
        val path = fileSidecarPath(audioPath, ext)
        return runCatching {
            val file = File(path)
            file.parentFile?.mkdirs()
            file.writeText(text, Charsets.UTF_8)
            path
        }.getOrNull()
    }

    private fun saveToDocumentTree(context: Context, song: Song, text: String, ext: String): String? {
        val audioDoc = DocumentFile.fromSingleUri(context, Uri.parse(song.path)) ?: return null
        val parent = audioDoc.parentFile ?: return null
        val baseName = audioDoc.name?.substringBeforeLast('.') ?: return null
        val fileName = "$baseName.$ext"
        parent.findFile(fileName)?.delete()
        val doc = parent.createFile("text/plain", fileName)
            ?: parent.createFile("application/octet-stream", fileName)
            ?: return null
        return runCatching {
            context.contentResolver.openOutputStream(doc.uri)?.use { out ->
                out.write(text.toByteArray(Charsets.UTF_8))
            } ?: return null
            doc.uri.toString()
        }.getOrNull()
    }

    private fun resolveDocumentSidecar(context: Context, audioUri: String, ext: String): String? {
        val audioDoc = DocumentFile.fromSingleUri(context, Uri.parse(audioUri)) ?: return null
        val parent = audioDoc.parentFile ?: return null
        val baseName = audioDoc.name?.substringBeforeLast('.') ?: return null
        val doc = parent.findFile("$baseName.$ext") ?: return null
        return doc.uri.takeIf { doc.exists() }?.toString()
    }

    private fun deleteAtPath(context: Context, path: String) {
        if (path.startsWith("content://")) {
            DocumentFile.fromSingleUri(context, Uri.parse(path))?.delete()
        } else {
            File(path).delete()
        }
    }

    private fun legacyCacheFile(cacheDir: File, song: Song): File =
        File(File(cacheDir, "lyrics"), "${songKey(song)}.lrc")

    fun deleteLegacyCache(cacheDir: File, song: Song) {
        legacyCacheFile(cacheDir, song).delete()
    }

    private fun songKey(song: Song): String {
        val digest = MessageDigest.getInstance("MD5")
        val bytes = digest.digest("${song.path}|${song.title}|${song.artist}".toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
