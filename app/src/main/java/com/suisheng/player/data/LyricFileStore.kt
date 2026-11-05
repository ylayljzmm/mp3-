package com.suisheng.player.data

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.suisheng.player.lrc.LrcParser
import com.suisheng.player.model.LrcLine
import com.suisheng.player.model.Song
import com.suisheng.player.util.MediaPath
import java.io.File
import java.security.MessageDigest

/**
 * 歌词与歌曲同目录存放（同名 .lrc），卸载 App 后仍保留。
 */
object LyricFileStore {

    fun read(context: Context, song: Song): List<LrcLine>? {
        song.lrcPath?.let { path ->
            if (!isMetadataMarker(path)) {
                readAtPath(context, path)?.let { return it }
            }
        }
        if (MediaPath.isStream(song.path)) {
            onlineCacheFile(context, song).takeIf { it.exists() }?.let { file ->
                readAtPath(context, file.absolutePath)?.let { return it }
            }
        }
        resolveSidecarPath(context, song)?.let { path ->
            readAtPath(context, path)?.let { return it }
        }
        return null
    }

    fun save(context: Context, song: Song, lrcText: String): String? {
        if (song.path.startsWith("content://")) {
            return saveToDocumentTree(context, song, lrcText)
        }
        if (MediaPath.isStream(song.path)) {
            return saveToOnlineCache(context, song, lrcText)
        }
        return saveToFileSidecar(song.path, lrcText)
    }

    fun delete(context: Context, song: Song) {
        song.lrcPath?.let { path ->
            if (!isMetadataMarker(path)) {
                deleteAtPath(context, path)
            }
        }
        if (MediaPath.isStream(song.path)) {
            onlineCacheFile(context, song).delete()
        }
        resolveSidecarPath(context, song)?.let { deleteAtPath(context, it) }
    }

    fun migrateLegacyCache(context: Context, song: Song, cacheDir: File): String? {
        val cache = legacyCacheFile(cacheDir, song)
        if (!cache.exists() || cache.length() == 0L) return null
        val text = runCatching { cache.readText() }.getOrNull()?.takeIf { it.isNotBlank() } ?: return null
        val saved = save(context, song, text) ?: return null
        cache.delete()
        return saved
    }

    fun resolveSidecarPath(context: Context, song: Song): String? {
        if (MediaPath.isStream(song.path)) return null
        if (song.path.startsWith("content://")) {
            return resolveDocumentSidecar(context, song.path)
        }
        val path = fileSidecarPath(song.path)
        return path.takeIf { File(it).exists() }
    }

    private fun isMetadataMarker(path: String): Boolean =
        path.startsWith("pic:") || path.startsWith("favicon:") || path.startsWith("desc:")

    private fun onlineCacheFile(context: Context, song: Song): File =
        File(File(context.cacheDir, "lyrics"), "${songKey(song)}.lrc")

    private fun saveToOnlineCache(context: Context, song: Song, lrcText: String): String? {
        val file = onlineCacheFile(context, song)
        return runCatching {
            file.parentFile?.mkdirs()
            file.writeText(lrcText, Charsets.UTF_8)
            file.absolutePath
        }.getOrNull()
    }

    private fun fileSidecarPath(audioPath: String): String =
        "${audioPath.substringBeforeLast('.')}.lrc"

    private fun readAtPath(context: Context, path: String): List<LrcLine>? {
        return if (path.startsWith("content://")) {
            runCatching {
                context.contentResolver.openInputStream(Uri.parse(path))?.use { stream ->
                    LrcParser.parseContent(stream.reader().readText())
                }
            }.getOrNull()?.takeIf { it.isNotEmpty() }
        } else {
            runCatching { LrcParser.parseFile(File(path)) }.getOrNull()?.takeIf { it.isNotEmpty() }
        }
    }

    private fun saveToFileSidecar(audioPath: String, lrcText: String): String? {
        val lrcPath = fileSidecarPath(audioPath)
        return runCatching {
            val file = File(lrcPath)
            file.parentFile?.mkdirs()
            file.writeText(lrcText, Charsets.UTF_8)
            lrcPath
        }.getOrNull()
    }

    private fun saveToDocumentTree(context: Context, song: Song, lrcText: String): String? {
        val audioDoc = DocumentFile.fromSingleUri(context, Uri.parse(song.path)) ?: return null
        val parent = audioDoc.parentFile ?: return null
        val baseName = audioDoc.name?.substringBeforeLast('.') ?: return null
        val lrcName = "$baseName.lrc"
        parent.findFile(lrcName)?.delete()
        val lrcDoc = parent.createFile("text/plain", lrcName)
            ?: parent.createFile("application/octet-stream", lrcName)
            ?: return null
        return runCatching {
            context.contentResolver.openOutputStream(lrcDoc.uri)?.use { out ->
                out.write(lrcText.toByteArray(Charsets.UTF_8))
            } ?: return null
            lrcDoc.uri.toString()
        }.getOrNull()
    }

    private fun resolveDocumentSidecar(context: Context, audioUri: String): String? {
        val audioDoc = DocumentFile.fromSingleUri(context, Uri.parse(audioUri)) ?: return null
        val parent = audioDoc.parentFile ?: return null
        val baseName = audioDoc.name?.substringBeforeLast('.') ?: return null
        val lrcDoc = parent.findFile("$baseName.lrc") ?: return null
        return lrcDoc.uri.takeIf { lrcDoc.exists() }?.toString()
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
