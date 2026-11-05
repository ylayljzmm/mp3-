package com.suisheng.player.data

import com.suisheng.player.model.OnlinePlaylistSummary
import com.suisheng.player.model.OnlineTrackRef
import com.suisheng.player.model.PlaylistLoadResult
import com.suisheng.player.model.Song
import com.suisheng.player.util.MediaPath
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class OnlineMusicApi(private val settings: SettingsRepository) {

    data class PlayUrlResult(val url: String, val bitrate: Int)

    fun searchSongs(keyword: String, page: Int = 1, count: Int = 30): List<Song> {
        if (keyword.isBlank()) return emptyList()
        val encoded = URLEncoder.encode(keyword, Charsets.UTF_8.name())
        val body = httpGet(buildUrl("types=search&source=netease&name=$encoded&count=$count&pages=$page"))
            ?: return emptyList()
        return parseSearchArray(body)
    }

    fun searchPlaylists(keyword: String, page: Int = 1, count: Int = 20): List<OnlinePlaylistSummary> {
        if (keyword.isBlank()) return emptyList()
        return searchPlaylistsViaCloudSearch(keyword, page, count)
    }

    fun loadPlaylist(playlistId: String, displayTitle: String? = null): PlaylistLoadResult {
        val id = extractPlaylistId(playlistId)
            ?: return PlaylistLoadResult(null, emptyList(), "无效歌单 ID")

        val gdResult = completePlaylistTracks(id, loadPlaylistFromGdStudio(id))
        if (gdResult.songs.isNotEmpty()) {
            return gdResult.withDisplayTitle(displayTitle)
        }

        val neteaseResult = completePlaylistTracks(id, loadPlaylistFromNeteaseDetail(id))
        if (neteaseResult.songs.isNotEmpty()) {
            return neteaseResult.withDisplayTitle(displayTitle)
        }

        val error = gdResult.errorMessage ?: neteaseResult.errorMessage ?: "歌单不存在或暂无歌曲"
        val summary = gdResult.summary ?: neteaseResult.summary
        return PlaylistLoadResult(summary, emptyList(), error)
    }

    fun resolvePlayUrl(song: Song): PlayUrlResult? {
        val parts = MediaPath.parseOnline(song.path) ?: return null
        return resolvePlayUrl(parts.source, parts.trackId)
    }

    fun resolvePlayUrl(source: String, trackId: String): PlayUrlResult? {
        val br = settings.onlineMusicQuality
        val body = httpGet(buildUrl("types=url&source=$source&id=$trackId&br=$br")) ?: return null
        val json = runCatching { JSONObject(body) }.getOrNull()
        if (json != null) {
            val url = json.optString("url").takeIf { it.isNotBlank() && it.startsWith("http") }
            if (url != null) return PlayUrlResult(url, json.optInt("br", br))
        }
        return null
    }

    fun fetchLyric(source: String, lyricId: String): String? {
        val body = httpGet(buildUrl("types=lyric&source=$source&id=$lyricId")) ?: return null
        val json = runCatching { JSONObject(body) }.getOrNull() ?: return null
        return json.optString("lyric").takeIf { it.isNotBlank() }
    }

    fun fetchCoverUrl(source: String, picId: String, size: Int = 500): String? {
        val body = httpGet(buildUrl("types=pic&source=$source&id=$picId&size=$size")) ?: return null
        val json = runCatching { JSONObject(body) }.getOrNull() ?: return null
        return json.optString("url").takeIf { it.startsWith("http") }
    }

    fun matchTrackOnApi(ref: OnlineTrackRef): OnlineTrackRef? {
        val results = searchSongs("${ref.title} ${ref.artist}", count = 8)
        val best = results.firstOrNull { song ->
            normalize(song.title) == normalize(ref.title) &&
                normalize(song.artist).contains(normalize(ref.artist.split("/").first()).take(2))
        } ?: results.firstOrNull { normalize(it.title) == normalize(ref.title) }
        ?: return null
        val parts = MediaPath.parseOnline(best.path) ?: return null
        return ref.copy(
            source = parts.source,
            trackId = parts.trackId,
            picId = parts.picId ?: ref.picId
        )
    }

    fun refFromSong(song: Song): OnlineTrackRef? {
        val parts = MediaPath.parseOnline(song.path) ?: return null
        return OnlineTrackRef(
            localId = MediaPath.newLocalId(),
            title = song.title,
            artist = song.artist,
            album = parts.album.orEmpty(),
            source = parts.source,
            trackId = parts.trackId,
            picId = parts.picId ?: song.lrcPath?.removePrefix("pic:"),
            durationMs = song.durationMs
        )
    }

    private fun loadPlaylistFromGdStudio(id: String): PlaylistLoadResult {
        val body = httpGet(
            buildUrl("types=playlist&source=netease&id=$id"),
            longTimeout = true
        ) ?: return PlaylistLoadResult(null, emptyList(), "网络请求失败")
        val json = runCatching { JSONObject(body) }.getOrNull()
            ?: return PlaylistLoadResult(null, emptyList(), "解析歌单失败")

        val code = json.optInt("code", -1)
        if (code == 404) {
            val message = json.optString("message").ifBlank { json.optString("msg", "歌单不存在") }
            return PlaylistLoadResult(null, emptyList(), message)
        }

        val playlist = json.optJSONObject("playlist")
            ?: return PlaylistLoadResult(null, emptyList(), "歌单数据无效")

        val summary = summaryFromPlaylistObject(playlist, id)
        val tracks = parsePlaylistTrackArray(playlist.optJSONArray("tracks"))
        if (tracks.isNotEmpty()) {
            return PlaylistLoadResult(summary, tracks)
        }

        val trackCount = playlist.optInt("trackCount")
        return if (trackCount > 0) {
            PlaylistLoadResult(summary, emptyList(), "歌单歌曲加载失败，请稍后重试")
        } else {
            PlaylistLoadResult(summary, emptyList(), "歌单暂无歌曲")
        }
    }

    private fun loadPlaylistFromNeteaseDetail(id: String): PlaylistLoadResult {
        val url = "https://music.163.com/api/v6/playlist/detail?id=$id"
        val body = httpGet(
            url,
            mapOf("Referer" to "https://music.163.com/"),
            longTimeout = true
        ) ?: return PlaylistLoadResult(null, emptyList(), null)
        val json = runCatching { JSONObject(body) }.getOrNull()
            ?: return PlaylistLoadResult(null, emptyList(), null)
        if (json.optInt("code") != 200) {
            return PlaylistLoadResult(null, emptyList(), null)
        }

        val playlist = json.optJSONObject("playlist")
            ?: return PlaylistLoadResult(null, emptyList(), null)

        val summary = summaryFromPlaylistObject(playlist, id)
        val tracks = parsePlaylistTrackArray(playlist.optJSONArray("tracks"))
        return PlaylistLoadResult(summary, tracks)
    }

    private fun completePlaylistTracks(id: String, result: PlaylistLoadResult): PlaylistLoadResult {
        val summary = result.summary ?: return result
        val expected = summary.trackCount
        if (expected <= 0 || result.songs.size >= expected) return result

        val trackIds = loadPlaylistTrackIds(id)
        if (trackIds.isEmpty()) return result

        val fetched = fetchSongsByNeteaseIds(trackIds)
        if (fetched.size > result.songs.size) {
            return PlaylistLoadResult(summary, fetched, result.errorMessage)
        }
        return result
    }

    private fun loadPlaylistTrackIds(id: String): List<String> {
        val url = "https://music.163.com/api/v6/playlist/detail?id=$id"
        val body = httpGet(
            url,
            mapOf("Referer" to "https://music.163.com/"),
            longTimeout = true
        ) ?: return emptyList()
        val playlist = runCatching { JSONObject(body) }.getOrNull()?.optJSONObject("playlist")
            ?: return emptyList()
        return extractTrackIds(playlist)
    }

    private fun fetchSongsByNeteaseIds(ids: List<String>): List<Song> {
        if (ids.isEmpty()) return emptyList()
        val byId = linkedMapOf<String, Song>()
        ids.chunked(200).forEach { batch ->
            val encodedIds = URLEncoder.encode(
                batch.joinToString(",", prefix = "[", postfix = "]"),
                Charsets.UTF_8.name()
            )
            val url = "https://music.163.com/api/song/detail/?ids=$encodedIds"
            val body = httpGet(url, mapOf("Referer" to "https://music.163.com/")) ?: return@forEach
            val songs = runCatching { JSONObject(body) }.getOrNull()
                ?.optJSONArray("songs")
                ?.let { parsePlaylistTrackArray(it) }
                .orEmpty()
            songs.forEach { song ->
                MediaPath.parseOnline(song.path)?.trackId?.let { trackId ->
                    byId.putIfAbsent(trackId, song)
                }
            }
        }
        return ids.mapNotNull { byId[it] }
    }

    private fun extractTrackIds(playlist: JSONObject): List<String> {
        playlist.optJSONArray("trackIds")?.let { array ->
            val ids = buildList {
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i)
                    when {
                        item != null -> add(item.optLong("id").toString())
                        else -> array.optLong(i).takeIf { it > 0L }?.let { add(it.toString()) }
                    }
                }
            }
            if (ids.isNotEmpty()) return ids
        }
        return playlist.optJSONArray("tracks")?.let { array ->
            buildList {
                for (i in 0 until array.length()) {
                    array.optJSONObject(i)?.optLong("id")?.takeIf { it > 0L }?.let { add(it.toString()) }
                }
            }
        }.orEmpty()
    }

    private fun parsePlaylistTrackArray(array: JSONArray?): List<Song> {
        if (array == null || array.length() == 0) return emptyList()
        val first = array.optJSONObject(0) ?: return emptyList()
        return when {
            first.has("ar") -> parseGdStudioTracks(array)
            first.has("artists") -> parseNeteaseDetailTracks(array)
            else -> emptyList()
        }
    }

    private fun searchPlaylistsViaCloudSearch(keyword: String, page: Int, count: Int): List<OnlinePlaylistSummary> {
        val encoded = URLEncoder.encode(keyword, Charsets.UTF_8.name())
        val offset = (page - 1).coerceAtLeast(0) * count
        val url = "https://music.163.com/api/cloudsearch/pc?s=$encoded&type=1000&offset=$offset&limit=$count"
        val body = httpGet(url, mapOf("Referer" to "https://music.163.com/")) ?: return emptyList()
        val json = runCatching { JSONObject(body) }.getOrNull() ?: return emptyList()
        if (json.optInt("code") != 200) return emptyList()

        val playlists = json.optJSONObject("result")?.optJSONArray("playlists") ?: return emptyList()
        return buildList {
            for (i in 0 until playlists.length()) {
                val item = playlists.optJSONObject(i) ?: continue
                add(
                    OnlinePlaylistSummary(
                        id = item.optLong("id").toString(),
                        name = item.optString("name"),
                        coverUrl = normalizeCoverUrl(item.optString("coverImgUrl")),
                        trackCount = item.optInt("trackCount"),
                        playCount = item.optLong("playCount"),
                        subscribedCount = item.optLong("bookCount")
                    )
                )
            }
        }
    }

    private fun summaryFromPlaylistObject(playlist: JSONObject, fallbackId: String): OnlinePlaylistSummary {
        val id = playlist.optLong("id").takeIf { it > 0L }?.toString()
            ?: playlist.optString("id", fallbackId)
        return OnlinePlaylistSummary(
            id = id,
            name = playlist.optString("name"),
            coverUrl = normalizeCoverUrl(
                playlist.optString("coverImgUrl").ifBlank { playlist.optString("picUrl") }
            ),
            trackCount = playlist.optInt("trackCount"),
            playCount = playlist.optLong("playCount"),
            subscribedCount = playlist.optLong("subscribedCount", playlist.optLong("bookCount"))
        )
    }

    private fun PlaylistLoadResult.withDisplayTitle(displayTitle: String?): PlaylistLoadResult {
        if (displayTitle.isNullOrBlank() || summary == null) return this
        return copy(summary = summary.copy(name = displayTitle))
    }

    private fun buildUrl(query: String): String {
        val base = settings.onlineMusicApiUrl.trimEnd('/')
        val separator = if (base.contains('?')) "&" else "?"
        return if (base.endsWith(".php")) "$base?$query" else "$base$separator$query"
    }

    private fun parseSearchArray(body: String): List<Song> {
        val array = runCatching { JSONArray(body) }.getOrNull() ?: return emptyList()
        return buildList {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val artists = item.optJSONArray("artist")?.let { arr ->
                    (0 until arr.length()).joinToString("/") { idx -> arr.optString(idx) }
                }.orEmpty()
                add(
                    MediaPath.songFromOnlineSearch(
                        source = item.optString("source", "netease"),
                        trackId = item.optString("id"),
                        title = item.optString("name"),
                        artist = artists.ifBlank { "未知歌手" },
                        album = item.optString("album"),
                        picId = normalizePicId(
                            item.optString("pic_id"),
                            item.optLong("picId").takeIf { it > 0L }?.toString()
                        )
                    )
                )
            }
        }
    }

    private fun parseGdStudioTracks(array: JSONArray): List<Song> {
        return buildList {
            for (i in 0 until array.length()) {
                val track = array.optJSONObject(i) ?: continue
                val artists = track.optJSONArray("ar")?.let { arr ->
                    (0 until arr.length()).joinToString("/") { idx ->
                        arr.optJSONObject(idx)?.optString("name").orEmpty()
                    }
                }.orEmpty()
                val album = track.optJSONObject("al")
                add(
                    MediaPath.songFromOnlineSearch(
                        source = "netease",
                        trackId = track.optLong("id").toString(),
                        title = track.optString("name"),
                        artist = artists.ifBlank { "未知歌手" },
                        album = album?.optString("name").orEmpty(),
                        picId = normalizePicId(
                            album?.optLong("picId")?.toString(),
                            track.optLong("picId").takeIf { it > 0L }?.toString()
                        ),
                        durationMs = track.optLong("dt")
                    )
                )
            }
        }
    }

    private fun parseNeteaseDetailTracks(array: JSONArray): List<Song> {
        return buildList {
            for (i in 0 until array.length()) {
                val track = array.optJSONObject(i) ?: continue
                val artists = track.optJSONArray("artists")?.let { arr ->
                    (0 until arr.length()).joinToString("/") { idx ->
                        arr.optJSONObject(idx)?.optString("name").orEmpty()
                    }
                }.orEmpty()
                val album = track.optJSONObject("album")
                add(
                    MediaPath.songFromOnlineSearch(
                        source = "netease",
                        trackId = track.optLong("id").toString(),
                        title = track.optString("name"),
                        artist = artists.ifBlank { "未知歌手" },
                        album = album?.optString("name").orEmpty(),
                        picId = normalizePicId(
                            album?.optLong("picId")?.toString(),
                            track.optLong("picId").takeIf { it > 0L }?.toString()
                        ),
                        durationMs = track.optLong("dt")
                    )
                )
            }
        }
    }

    private fun extractPlaylistId(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.all { it.isDigit() }) return trimmed
        Regex("id=(\\d+)").find(trimmed)?.groupValues?.getOrNull(1)?.let { return it }
        Regex("(\\d{6,})").find(trimmed)?.groupValues?.getOrNull(1)?.let { return it }
        return null
    }

    private fun normalizeCoverUrl(url: String?): String? {
        val value = url?.trim()?.takeIf { it.startsWith("http") } ?: return null
        return if (value.startsWith("http://")) "https://${value.removePrefix("http://")}" else value
    }

    private fun normalize(value: String): String =
        value.lowercase().replace(Regex("\\s+"), "").replace(Regex("[()（）\\[\\]]"), "")

    private fun normalizePicId(vararg candidates: String?): String? {
        for (candidate in candidates) {
            val value = candidate?.trim()?.takeIf { it.isNotBlank() && it != "0" } ?: continue
            return value
        }
        return null
    }

    private fun httpGet(
        url: String,
        headers: Map<String, String> = emptyMap(),
        longTimeout: Boolean = false
    ): String? {
        val bases = listOfNotNull(
            settings.onlineMusicApiUrl.takeIf { it.isNotBlank() },
            settings.onlineMusicApiBackup.takeIf { it.isNotBlank() }
        ).distinct()
        for (base in bases) {
            val resolved = if (url.startsWith("http")) url else {
                val q = url.substringAfter('?', url)
                val endpoint = base.trimEnd('/')
                if (endpoint.endsWith(".php")) "$endpoint?$q" else "$endpoint?$q"
            }
            val result = httpGetOnce(resolved, headers, longTimeout)
            if (result != null) return result
        }
        if (url.startsWith("http")) return httpGetOnce(url, headers, longTimeout)
        return null
    }

    private fun httpGetOnce(
        url: String,
        headers: Map<String, String>,
        longTimeout: Boolean = false
    ): String? {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = if (longTimeout) 15_000 else 10_000
            readTimeout = if (longTimeout) 45_000 else 12_000
            requestMethod = "GET"
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", "application/json, text/plain, */*")
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
        }
        return try {
            if (connection.responseCode !in 200..299) null
            else connection.inputStream.bufferedReader().use { it.readText() }
        } catch (_: Exception) {
            null
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        private const val USER_AGENT =
            "MP3Player/4.0 (Android; Car) Mozilla/5.0 Mobile Safari/537.36"
    }
}
