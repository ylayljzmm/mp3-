package com.suisheng.player.data

import android.content.Context
import com.suisheng.player.model.LibraryKind
import com.suisheng.player.model.Song
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

object PlaylistCache {
    private const val CACHE_FILE = "playlist_cache.json"
    private const val QUEUE_FILE = "play_queue.json"
    private const val MUSIC_QUEUE_FILE = "play_queue_music.json"
    private const val ONLINE_QUEUE_FILE = "play_queue_online.json"
    private const val RADIO_QUEUE_FILE = "play_queue_radio.json"
    private const val PODCAST_QUEUE_FILE = "play_queue_podcast.json"

    fun save(context: Context, songs: List<Song>) = writeSongs(context, CACHE_FILE, songs)

    fun load(context: Context): List<Song> = readSongs(context, CACHE_FILE)

    fun saveQueue(context: Context, songs: List<Song>, library: LibraryKind = LibraryKind.MUSIC) {
        writeSongs(context, queueFile(library), songs)
        writeSongs(context, QUEUE_FILE, songs)
    }

    fun loadQueue(context: Context, library: LibraryKind = LibraryKind.MUSIC): List<Song> {
        val typed = readSongs(context, queueFile(library))
        if (typed.isNotEmpty()) return typed
        return readSongs(context, QUEUE_FILE)
    }

    private fun queueFile(library: LibraryKind): String = when (library) {
        LibraryKind.MUSIC -> MUSIC_QUEUE_FILE
        LibraryKind.ONLINE -> ONLINE_QUEUE_FILE
        LibraryKind.RADIO -> RADIO_QUEUE_FILE
        LibraryKind.PODCAST -> PODCAST_QUEUE_FILE
    }

    private fun writeSongs(context: Context, fileName: String, songs: List<Song>) {
        if (songs.isEmpty()) return
        val array = JSONArray()
        songs.forEach { song ->
            array.put(
                JSONObject().apply {
                    put("id", song.id)
                    put("title", song.title)
                    put("artist", song.artist)
                    put("path", song.path)
                    put("lrcPath", song.lrcPath)
                    put("durationMs", song.durationMs)
                    put("album", song.album)
                }
            )
        }
        runCatching {
            File(context.filesDir, fileName).writeText(array.toString())
        }
    }

    private fun readSongs(context: Context, fileName: String): List<Song> {
        val file = File(context.filesDir, fileName)
        if (!file.exists()) return emptyList()
        return runCatching {
            val array = JSONArray(file.readText())
            buildList {
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    add(
                        Song(
                            id = item.optLong("id"),
                            title = item.optString("title"),
                            artist = item.optString("artist"),
                            path = item.optString("path"),
                            lrcPath = item.optString("lrcPath").takeIf { it.isNotBlank() },
                            durationMs = item.optLong("durationMs"),
                            // 新增字段：老缓存里没有这一项，optString 给空串，正好落到「未知专辑」
                            album = item.optString("album")
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }
}
