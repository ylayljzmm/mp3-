package com.suisheng.player.data

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import com.suisheng.player.model.Song
import java.io.File

class MusicScanner(
    private val context: Context,
    private val customPaths: List<String> = emptyList(),
    private val treeUris: List<String> = emptyList()
) {
    private val audioExtensions = setOf("mp3", "flac", "m4a", "wav", "ogg", "aac")

    fun scan(): List<Song> {
        val merged = linkedMapOf<String, Song>()
        // Each source is guarded independently: one bad provider (a head unit with a
        // restricted MediaStore projection, an unmounted USB volume, a revoked SAF
        // grant) must not take the whole library — or the app — down with it.
        runCatching { scanMediaStore() }
            .getOrDefault(emptyList())
            .forEach { merged[it.path] = it }
        runCatching { scanDirectories(resolvePaths()) }
            .getOrDefault(emptyList())
            .forEach { song ->
                if (!merged.containsKey(song.path)) merged[song.path] = song
            }
        runCatching { scanDocumentTrees() }
            .getOrDefault(emptyList())
            .forEach { merged[it.path] = it }
        return merged.values.sortedBy { it.title.lowercase() }
    }

    private fun resolvePaths(): List<File> {
        val paths = mutableListOf<File>()
        customPaths.forEach { paths.add(File(it)) }
        if (paths.isEmpty()) {
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)?.let { paths.add(it) }
            paths.add(File("/storage/emulated/0/Music"))
            paths.add(File("/sdcard/Music"))
        }
        return paths.distinctBy { it.absolutePath }
    }

    private fun scanMediaStore(): List<Song> {
        val songs = mutableListOf<Song>()
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.DATA,
            MediaStore.Audio.Media.DURATION
        )
        context.contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            projection,
            "${MediaStore.Audio.Media.IS_MUSIC}=1",
            null,
            MediaStore.Audio.Media.TITLE + " ASC"
        )?.use { cursor ->
            // getColumnIndexOrThrow is an unnecessary crash surface: several head-unit
            // ROMs expose a trimmed projection, and a missing column would throw inside
            // a background scan where nothing can catch it.
            val idCol = cursor.getColumnIndex(MediaStore.Audio.Media._ID)
            val titleCol = cursor.getColumnIndex(MediaStore.Audio.Media.TITLE)
            val artistCol = cursor.getColumnIndex(MediaStore.Audio.Media.ARTIST)
            val albumCol = cursor.getColumnIndex(MediaStore.Audio.Media.ALBUM)
            val dataCol = cursor.getColumnIndex(MediaStore.Audio.Media.DATA)
            val durationCol = cursor.getColumnIndex(MediaStore.Audio.Media.DURATION)
            if (dataCol < 0) return emptyList()
            while (cursor.moveToNext()) {
                val path = cursor.getString(dataCol) ?: continue
                if (!isAudioFile(path)) continue
                val title = (if (titleCol >= 0) cursor.getString(titleCol) else null)
                    ?: File(path).nameWithoutExtension
                val artist = (if (artistCol >= 0) cursor.getString(artistCol) else null)
                    ?: "未知歌手"
                val album = if (albumCol >= 0) cursor.getString(albumCol).orEmpty().trim() else ""
                val trackDuration =
                    if (durationCol >= 0) cursor.getLong(durationCol).coerceAtLeast(0L) else 0L
                songs.add(
                    Song(
                        id = if (idCol >= 0) cursor.getLong(idCol) else 0L,
                        title = title,
                        artist = artist,
                        path = path,
                        lrcPath = findLrc(path),
                        durationMs = trackDuration,
                        album = album
                    )
                )
            }
        }
        return songs
    }

    private fun scanDirectories(paths: List<File>): List<Song> {
        val songs = mutableListOf<Song>()
        var id = 1L
        for (root in paths) {
            if (!root.exists() || !root.isDirectory) continue
            root.walkTopDown().maxDepth(8)
                .filter { it.isFile && isAudioFile(it.absolutePath) }
                .forEach { file ->
                    val (durationMs, album) = readMetadata(file.absolutePath)
                    songs.add(
                        Song(
                            id = id++,
                            title = file.nameWithoutExtension,
                            artist = file.parentFile?.name ?: "本地音乐",
                            path = file.absolutePath,
                            lrcPath = findLrc(file.absolutePath),
                            durationMs = durationMs,
                            album = album
                        )
                    )
                }
        }
        return songs
    }

    private fun scanDocumentTrees(): List<Song> {
        val songs = mutableListOf<Song>()
        var id = 10_000_000L
        for (uriStr in treeUris) {
            val root = DocumentFile.fromTreeUri(context, Uri.parse(uriStr)) ?: continue
            walkDocumentFile(root, songs) { id++ }
        }
        return songs
    }

    private fun walkDocumentFile(
        file: DocumentFile,
        out: MutableList<Song>,
        nextId: () -> Long
    ) {
        if (file.isDirectory) {
            file.listFiles().forEach { walkDocumentFile(it, out, nextId) }
            return
        }
        val name = file.name ?: return
        if (!isAudioFile(name)) return
        val uri = file.uri.toString()
        val id = nextId()
        val title = name.substringBeforeLast('.')
        val (durationMs, album) = readMetadata(uri)
        out.add(
            Song(
                id = id,
                title = title,
                artist = "本地音乐",
                path = uri,
                lrcPath = LyricFileStore.resolveSidecarPath(
                    context,
                    Song(id, title, "本地音乐", uri, null)
                ),
                durationMs = durationMs,
                album = album
            )
        )
    }

    /**
     * 一次 `MediaMetadataRetriever` 同时取时长与专辑名。
     *
     * 目录扫描是**逐文件**开 retriever 的，开两次比开一次贵一倍；车机上几千个文件的
     * U 盘首次扫描本来就要几十秒，不能在这里翻倍。
     */
    private fun readMetadata(pathOrUri: String): Pair<Long, String> {
        val retriever = MediaMetadataRetriever()
        return runCatching {
            if (pathOrUri.startsWith("content://")) {
                retriever.setDataSource(context, Uri.parse(pathOrUri))
            } else {
                retriever.setDataSource(pathOrUri)
            }
            val durationMs = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()
                ?.coerceAtLeast(0L) ?: 0L
            val album = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)
                .orEmpty()
                .trim()
            durationMs to album
        }.getOrDefault(0L to "").also {
            runCatching { retriever.release() }
        }
    }

    private fun findLrc(audioPath: String): String? {
        if (audioPath.startsWith("content://")) return null
        val lrc = File("${audioPath.substringBeforeLast('.')}.lrc")
        return lrc.takeIf { it.exists() }?.absolutePath
    }

    private fun isAudioFile(path: String): Boolean {
        return path.substringAfterLast('.', "").lowercase() in audioExtensions
    }
}
