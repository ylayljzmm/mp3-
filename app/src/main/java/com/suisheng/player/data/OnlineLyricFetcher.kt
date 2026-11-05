package com.suisheng.player.data

import com.suisheng.player.lrc.LrcParser
import com.suisheng.player.model.LrcLine
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class OnlineLyricFetcher {
    fun fetch(title: String, artist: String): List<LrcLine>? {
        val queries = buildSearchQueries(title, artist)
        for ((track, singer) in queries) {
            val providers = listOf(
                { fetchFromNetease(track, singer) },
                { fetchFromKugou(track, singer) },
                { fetchFromQqMusic(track, singer) },
                { fetchFromLrcLib(track, singer) }
            )
            for (provider in providers) {
                val lines = runCatching { provider() }.getOrNull()
                if (!lines.isNullOrEmpty()) return lines
            }
        }
        return null
    }

    private fun buildSearchQueries(title: String, artist: String): List<Pair<String, String>> {
        val cleanTitle = title.trim()
        val cleanArtist = artist.trim()
        val validArtist = isUsefulArtist(cleanArtist)
        return listOfNotNull(
            cleanTitle to cleanArtist,
            cleanTitle to "",
            if (validArtist) cleanTitle to cleanArtist.substringBefore(" feat") else null,
            normalizeTitle(cleanTitle) to if (validArtist) cleanArtist else ""
        ).distinct()
    }

    private fun fetchFromLrcLib(title: String, artist: String): List<LrcLine>? {
        fetchLrcLibGet(title, artist)?.let { return it }
        val array = searchLrcLib(title, artist) ?: return null
        if (array.length() == 0) return null

        var bestScore = -1
        var bestSynced: String? = null
        var bestPlain: String? = null

        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val score = scoreLrcLibResult(item, title, artist)
            if (score <= bestScore) continue
            bestScore = score
            bestSynced = item.optString("syncedLyrics").takeIf { it.isNotBlank() }
            bestPlain = item.optString("plainLyrics").takeIf { it.isNotBlank() }
        }

        return parseLyricText(bestSynced, bestPlain)
    }

    private fun fetchLrcLibGet(title: String, artist: String): List<LrcLine>? {
        val url = buildString {
            append("https://lrclib.net/api/get?track_name=").append(encode(title))
            if (isUsefulArtist(artist)) {
                append("&artist_name=").append(encode(artist))
            }
        }
        val body = httpGetText(url) ?: return null
        val item = runCatching { JSONObject(body) }.getOrNull() ?: return null
        if (item.optInt("code", 0) == 404) return null
        return parseLyricText(
            item.optString("syncedLyrics").takeIf { it.isNotBlank() },
            item.optString("plainLyrics").takeIf { it.isNotBlank() }
        )
    }

    private fun searchLrcLib(title: String, artist: String): JSONArray? {
        val url = buildString {
            append("https://lrclib.net/api/search?track_name=").append(encode(title))
            if (isUsefulArtist(artist)) {
                append("&artist_name=").append(encode(artist))
            }
        }
        val body = httpGetText(url) ?: return null
        return runCatching { JSONArray(body) }.getOrNull()
    }

    private fun fetchFromNetease(title: String, artist: String): List<LrcLine>? {
        val keyword = if (isUsefulArtist(artist)) "$title $artist" else title
        val searchUrl =
            "https://music.163.com/api/search/get/web?s=${encode(keyword)}&type=1&offset=0&limit=12"
        val headers = neteaseHeaders()
        val searchJson = httpGetText(searchUrl, headers)?.let { runCatching { JSONObject(it) }.getOrNull() }
            ?: return null
        val songs = searchJson.optJSONObject("result")?.optJSONArray("songs") ?: return null
        if (songs.length() == 0) return null

        var bestId = 0L
        var bestScore = -1
        for (i in 0 until songs.length()) {
            val song = songs.optJSONObject(i) ?: continue
            val name = song.optString("name")
            val singers = song.optJSONArray("artists")?.let { arr ->
                (0 until arr.length()).joinToString("/") { idx ->
                    arr.optJSONObject(idx)?.optString("name").orEmpty()
                }
            }.orEmpty()
            val score = similarity(title, name) * 3 +
                if (isUsefulArtist(artist)) similarity(artist, singers) * 2 else 0
            if (score > bestScore) {
                bestScore = score
                bestId = song.optLong("id")
            }
        }
        if (bestId <= 0L || bestScore < 3) return null

        val lyricUrl = "https://music.163.com/api/song/lyric?id=$bestId&lv=1&kv=1&tv=-1"
        val lyricJson = httpGetText(lyricUrl, headers)?.let { runCatching { JSONObject(it) }.getOrNull() }
            ?: return null
        val synced = lyricJson.optJSONObject("lrc")?.optString("lyric")?.takeIf { it.isNotBlank() }
        val plain = lyricJson.optJSONObject("tlyric")?.optString("lyric")?.takeIf { it.isNotBlank() }
        return parseLyricText(synced, plain)
    }

    private fun fetchFromKugou(title: String, artist: String): List<LrcLine>? {
        val keyword = if (isUsefulArtist(artist)) "$title $artist" else title
        val searchUrl =
            "https://mobilecdn.kugou.com/api/v3/search/song?format=json&keyword=${encode(keyword)}&page=1&pagesize=8&showtype=1"
        val searchJson = httpGetText(searchUrl)?.let { runCatching { JSONObject(it) }.getOrNull() }
            ?: return null
        val info = searchJson.optJSONObject("data")?.optJSONArray("info") ?: return null
        if (info.length() == 0) return null

        var bestHash: String? = null
        var bestAlbumAudioId = 0L
        var bestScore = -1
        for (i in 0 until info.length()) {
            val item = info.optJSONObject(i) ?: continue
            val songName = item.optString("songname", item.optString("filename"))
            val singerName = item.optString("singername")
            val score = similarity(title, songName) * 3 +
                if (isUsefulArtist(artist)) similarity(artist, singerName) * 2 else 0
            if (score > bestScore) {
                bestScore = score
                bestHash = item.optString("hash").takeIf { it.isNotBlank() }
                bestAlbumAudioId = item.optLong("album_audio_id")
            }
        }
        val hash = bestHash ?: return null
        if (bestScore < 3) return null

        val searchLyricUrl =
            "https://lyrics.kugou.com/search?ver=1&man=yes&client=pc&hash=$hash&album_audio_id=$bestAlbumAudioId"
        val lyricSearch = httpGetText(searchLyricUrl)?.let { runCatching { JSONObject(it) }.getOrNull() }
            ?: return null
        val candidate = lyricSearch.optJSONArray("candidates")?.optJSONObject(0) ?: return null
        val lyricId = candidate.optString("id")
        val accessKey = candidate.optString("accesskey")
        if (lyricId.isBlank() || accessKey.isBlank()) return null

        val downloadUrl =
            "https://lyrics.kugou.com/download?ver=1&client=pc&id=$lyricId&accesskey=$accessKey&fmt=lrc&charset=utf8"
        val downloadJson = httpGetText(downloadUrl)?.let { runCatching { JSONObject(it) }.getOrNull() }
            ?: return null
        val content = downloadJson.optString("content").takeIf { it.isNotBlank() } ?: return null
        val decoded = runCatching {
            String(android.util.Base64.decode(content, android.util.Base64.DEFAULT), Charsets.UTF_8)
        }.getOrDefault(content)
        return LrcParser.parseContent(decoded).takeIf { it.isNotEmpty() }
    }

    private fun fetchFromQqMusic(title: String, artist: String): List<LrcLine>? {
        val keyword = if (isUsefulArtist(artist)) "$title $artist" else title
        val searchUrl = buildString {
            append("https://c.y.qq.com/soso/fcgi-bin/client_search_cp?")
            append("ct=24&qqmusic_ver=1298&new_json=1&searchid=1&t=0&aggr=1&cr=1&catZhida=1")
            append("&lossless=0&flag_qc=0&p=1&n=8&w=").append(encode(keyword))
            append("&format=json&inCharset=utf8&outCharset=utf-8&notice=0&platform=yqq.json&needNewCode=0")
        }
        val headers = mapOf("Referer" to "https://y.qq.com/")
        val searchJson = httpGetText(searchUrl, headers)?.let { parseJsonBody(it) } ?: return null
        val songs = searchJson.optJSONObject("data")?.optJSONObject("song")?.optJSONArray("list")
            ?: return null
        if (songs.length() == 0) return null

        var bestSongId = 0L
        var bestScore = -1
        for (i in 0 until songs.length()) {
            val song = songs.optJSONObject(i) ?: continue
            val name = song.optString("songname")
            val singer = song.optString("singer").let { raw ->
                runCatching {
                    JSONArray(raw).let { arr ->
                        (0 until arr.length()).joinToString("/") { idx ->
                            arr.optJSONObject(idx)?.optString("name").orEmpty()
                        }
                    }
                }.getOrDefault(raw)
            }
            val score = similarity(title, name) * 3 +
                if (isUsefulArtist(artist)) similarity(artist, singer) * 2 else 0
            if (score > bestScore) {
                bestScore = score
                bestSongId = song.optLong("songid")
            }
        }
        if (bestSongId <= 0L || bestScore < 3) return null

        val lyricUrl =
            "https://c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg?format=json&nobase64=1&songtype=0&musicid=$bestSongId"
        val lyricJson = httpGetText(lyricUrl, headers)?.let { parseJsonBody(it) } ?: return null
        val lyricText = lyricJson.optString("lyric").takeIf { it.isNotBlank() } ?: return null
        return LrcParser.parseContent(lyricText).takeIf { it.isNotEmpty() }
    }

    private fun fetchFromLyricsOvh(title: String, artist: String): List<LrcLine>? {
        if (!isUsefulArtist(artist)) return null
        val url =
            "https://api.lyrics.ovh/v1/${encodePath(artist)}/${encodePath(title)}"
        val body = httpGetText(url) ?: return null
        val json = runCatching { JSONObject(body) }.getOrNull() ?: return null
        val lyrics = json.optString("lyrics").takeIf { it.isNotBlank() } ?: return null
        return LrcParser.fromPlainText(lyrics).takeIf { it.isNotEmpty() }
    }

    private fun parseLyricText(synced: String?, plain: String?): List<LrcLine>? {
        synced?.let { text ->
            val lines = LrcParser.parseContent(text)
            if (lines.isNotEmpty()) return lines
        }
        plain?.let { text ->
            val lines = LrcParser.fromPlainText(text)
            if (lines.isNotEmpty()) return lines
        }
        return null
    }

    private fun scoreLrcLibResult(item: JSONObject, title: String, artist: String): Int {
        val resultTitle = item.optString("trackName", item.optString("name"))
        val resultArtist = item.optString("artistName", item.optString("artist"))
        var score = similarity(title, resultTitle) * 3
        if (isUsefulArtist(artist)) {
            score += similarity(artist, resultArtist) * 2
        }
        if (item.optString("syncedLyrics").isNotBlank()) score += 5
        return score
    }

    private fun parseJsonBody(body: String): JSONObject? {
        val trimmed = body.trim()
        val jsonText = when {
            trimmed.startsWith("{") -> trimmed
            trimmed.contains("(") && trimmed.endsWith(")") ->
                trimmed.substringAfter("(").dropLast(1)
            else -> trimmed
        }
        return runCatching { JSONObject(jsonText) }.getOrNull()
    }

    private fun httpGetText(url: String, headers: Map<String, String> = emptyMap()): String? {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8_000
            readTimeout = 8_000
            requestMethod = "GET"
            setRequestProperty("User-Agent", BROWSER_UA)
            setRequestProperty("Accept", "application/json, text/plain, */*")
            headers.forEach { (key, value) -> setRequestProperty(key, value) }
        }
        return try {
            if (connection.responseCode !in 200..299) return null
            connection.inputStream.bufferedReader().use { it.readText() }
        } catch (_: Exception) {
            null
        } finally {
            connection.disconnect()
        }
    }

    private fun neteaseHeaders(): Map<String, String> = mapOf(
        "Referer" to "https://music.163.com/",
        "Origin" to "https://music.163.com"
    )

    private fun similarity(a: String, b: String): Int {
        val left = normalizeTitle(a)
        val right = normalizeTitle(b)
        if (left.isEmpty() || right.isEmpty()) return 0
        if (left == right) return 10
        if (left.contains(right) || right.contains(left)) return 7
        return if (left.take(4) == right.take(4)) 3 else 0
    }

    private fun normalizeTitle(value: String): String {
        return value.lowercase()
            .replace(Regex("\\(.*?\\)"), "")
            .replace(Regex("\\[.*?\\]"), "")
            .replace(Regex("\\s+"), "")
            .trim()
    }

    private fun isUsefulArtist(artist: String): Boolean {
        return artist.isNotBlank() && artist !in IGNORED_ARTISTS
    }

    private fun encode(value: String): String =
        URLEncoder.encode(value, Charsets.UTF_8.name())

    private fun encodePath(value: String): String =
        URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")

    companion object {
        private const val BROWSER_UA =
            "Mozilla/5.0 (Linux; Android 12; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

        private val IGNORED_ARTISTS = setOf("未知歌手", "本地音乐", "<unknown>", "Unknown")
    }
}
