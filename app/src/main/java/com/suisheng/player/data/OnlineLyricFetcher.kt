package com.suisheng.player.data

import com.suisheng.player.lrc.LrcParser
import com.suisheng.player.lrc.LyricDocument
import com.suisheng.player.lrc.LyricProviderConfig
import com.suisheng.player.lrc.LyricProviders
import com.suisheng.player.lrc.YrcParser
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

/** 一次联网取词的结果：文档 + 哪个渠道给的（UI 显示来源、「换一个渠道」也靠它）。 */
data class LyricFetchOutcome(
    val document: LyricDocument,
    val providerId: String,
)

/**
 * 联网歌词取词器。
 *
 * ## 渠道是注册表驱动的
 *
 * 旧实现把 4 个渠道硬编码成一个 `listOf({...})`，用户无法关掉某个源、也无法调整尝试顺序，
 * 遇到某个源抽风时只能整个关掉联网歌词。现在渠道 id、顺序、开关都由
 * [LyricProviderConfig] 决定，这里只负责「按配置依次试」。
 *
 * ## 有轴优先于无轴
 *
 * 纯文本渠道（[LyricProviders.LYRICS_OVH]）只能给占位时间轴。如果它在顺序里靠前，
 * 而它的结果直接返回，后面渠道的好时间轴就永远用不上。所以每一轮查询里先收集
 * 「无轴」结果当兜底，本轮内找到有轴的就直接返回 —— 最多多花一轮查询，不会全量重试。
 *
 * ## tlyric 不再被当成正文
 *
 * 旧实现把网易云的 `tlyric`（译文轨）作为 `plain` 参数交给 `fromPlainText()`，
 * 于是「译文」被当成「正文」并且被塞了一条 5 秒一行的假时间轴 ——
 * 表现为歌词能显示、但和歌完全对不上。现在译文走 [LrcParser.mergeTranslation]，
 * 按时间戳挂到原文行上，对不上的行留空。
 */
class OnlineLyricFetcher {

    fun fetch(
        title: String,
        artist: String,
        config: LyricProviderConfig = LyricProviderConfig(),
        onlyProviderId: String? = null,
        /** 是否优先请求逐字轨（目前只有网易云支持）。关掉可省一次大字段的传输。 */
        preferWordLevel: Boolean = true,
    ): LyricFetchOutcome? {
        val providerIds = onlyProviderId
            ?.takeIf { LyricProviders.isKnown(it) }
            ?.let { listOf(it) }
            ?: config.enabledOrder()
        if (providerIds.isEmpty()) return null

        for ((track, singer) in buildSearchQueries(title, artist)) {
            var weak: LyricFetchOutcome? = null
            for (id in providerIds) {
                val result = runCatching { fetchFrom(id, track, singer, preferWordLevel) }.getOrNull() ?: continue
                if (result.document.lines.isEmpty()) continue
                val outcome = LyricFetchOutcome(result.document, id)
                if (result.timed) return outcome
                if (weak == null) weak = outcome
            }
            // 这一轮所有渠道都只给了无轴文本：有词总比没词强，收下它，不再换查询词重试。
            if (weak != null) return weak
        }
        return null
    }

    // ---------------------------------------------------------------- 渠道分发

    /** 单次渠道结果。[timed] = 是否带真实时间轴（纯文本渠道为 false）。 */
    private data class ProviderResult(val document: LyricDocument, val timed: Boolean)

    private fun fetchFrom(
        id: String,
        title: String,
        artist: String,
        preferWordLevel: Boolean,
    ): ProviderResult? = when (id) {
        LyricProviders.NETEASE -> fetchFromNetease(title, artist, preferWordLevel)
        LyricProviders.LRCLIB -> fetchFromLrcLib(title, artist)
        LyricProviders.KUGOU -> fetchFromKugou(title, artist)
        LyricProviders.QQ -> fetchFromQqMusic(title, artist)
        LyricProviders.KUWO -> fetchFromKuwo(title, artist)
        LyricProviders.TEXTYL -> fetchFromTextyl(title, artist)
        LyricProviders.LYRICS_OVH -> fetchFromLyricsOvh(title, artist)
        else -> null
    }

    // ---------------------------------------------------------------- 网易云

    private fun fetchFromNetease(
        title: String,
        artist: String,
        preferWordLevel: Boolean,
    ): ProviderResult? {
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

        // lv=1 原文轨，tv=-1 译文轨，rv=-1 罗马音（注音）轨，yv=-1 逐字轨。
        // yv 不加时接口不会返回 `yrc` 字段，逐字就永远拿不到 —— 这是旧实现漏掉的一处。
        val lyricUrl = "https://music.163.com/api/song/lyric?id=$bestId&lv=1&kv=1&tv=-1&rv=-1&yv=-1"
        val lyricJson = httpGetText(lyricUrl, headers)?.let { runCatching { JSONObject(it) }.getOrNull() }
            ?: return null

        val translation = lyricJson.optJSONObject("tlyric")?.optString("lyric")
        val phonetic = lyricJson.optJSONObject("romalrc")?.optString("lyric")

        // 逐字轨优先：yrc 带每一行、每个字的起止毫秒，是「KTV 字字对应」的唯一真数据源。
        // 它不保证每首歌都有，也不保证格式规整，所以任何一步不像 yrc 就静默回落到 lrc。
        if (preferWordLevel) {
            val yrc = lyricJson.optJSONObject("yrc")?.optString("lyric")
            if (YrcParser.looksLikeYrc(yrc.orEmpty())) {
                val wordLines = runCatching { YrcParser.parse(yrc!!) }.getOrDefault(emptyList())
                if (wordLines.isNotEmpty()) {
                    val wordDoc = LyricDocument.singleTrack(wordLines)
                        .copy(sourceProviderId = LyricProviders.NETEASE)
                    val mergedWord = LrcParser.mergePhonetic(
                        LrcParser.mergeTranslation(wordDoc, translation),
                        phonetic,
                    )
                    return ProviderResult(mergedWord, timed = true)
                }
            }
        }

        val synced = lyricJson.optJSONObject("lrc")?.optString("lyric")?.takeIf { it.isNotBlank() }
            ?: return null

        val base = docFromLrc(synced, LyricProviders.NETEASE) ?: return null
        val merged = LrcParser.mergePhonetic(
            LrcParser.mergeTranslation(base.document, translation),
            phonetic,
        )
        return base.copy(document = merged)
    }

    // ---------------------------------------------------------------- LRCLIB

    private fun fetchFromLrcLib(title: String, artist: String): ProviderResult? {
        val direct = fetchLrcLibGet(title, artist)
        if (direct?.timed == true) return direct

        val array = searchLrcLib(title, artist)
        if (array != null && array.length() > 0) {
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
            docFromLrc(bestSynced, LyricProviders.LRCLIB)?.let { return it }
            docFromPlain(bestPlain, LyricProviders.LRCLIB)?.let { return it }
        }
        return direct
    }

    private fun fetchLrcLibGet(title: String, artist: String): ProviderResult? {
        val url = buildString {
            append("https://lrclib.net/api/get?track_name=").append(encode(title))
            if (isUsefulArtist(artist)) {
                append("&artist_name=").append(encode(artist))
            }
        }
        val body = httpGetText(url) ?: return null
        val item = runCatching { JSONObject(body) }.getOrNull() ?: return null
        if (item.optInt("code", 0) == 404) return null
        docFromLrc(item.optString("syncedLyrics"), LyricProviders.LRCLIB)?.let { return it }
        return docFromPlain(item.optString("plainLyrics"), LyricProviders.LRCLIB)
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

    // ---------------------------------------------------------------- 酷狗

    private fun fetchFromKugou(title: String, artist: String): ProviderResult? {
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
        return docFromLrc(decoded, LyricProviders.KUGOU)
    }

    // ---------------------------------------------------------------- QQ 音乐

    private fun fetchFromQqMusic(title: String, artist: String): ProviderResult? {
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
        // QQ 的歌词正文里常把换行、冒号写成 `&#10;` / `&#58;` 这样的数字实体，
        // 不解码就会得到一条根本解析不出时间戳的单行。
        val lyricText = unescapeNumericEntities(lyricJson.optString("lyric")).takeIf { it.isNotBlank() }
            ?: return null
        val base = docFromLrc(lyricText, LyricProviders.QQ) ?: return null
        val trans = unescapeNumericEntities(lyricJson.optString("trans")).takeIf { it.isNotBlank() }
        return base.copy(document = LrcParser.mergeTranslation(base.document, trans))
    }

    // ---------------------------------------------------------------- 酷我

    /**
     * 酷我：两步走，先搜 rid 再取 `lrclist`。
     *
     * 这条链路是实测下来唯一稳定的：`www.kuwo.cn/api/www/music/lyric` 会因为缺 csrf
     * 直接返回 `{"success":false,"message":"The request is illegal!"}`；
     * `player.kuwo.cn/webmusic/st/getNewMuiseByRid` 会 302 到 SPA 页面；
     * `newlyric.kuwo.cn/newlyric.lrc` 直接 `TP=DENY REQUEST`。
     *
     * 搜索接口返回的是**单引号伪 JSON**（`{'SONGNAME':'晴天',...}`），不能直接喂 `JSONObject`，
     * 所以按 `'AARTIST':'` 切条、再用正则取需要的三个字段。
     */
    private fun fetchFromKuwo(title: String, artist: String): ProviderResult? {
        val keyword = if (isUsefulArtist(artist)) "$title $artist" else title
        val searchUrl = buildString {
            append("https://search.kuwo.cn/r.s?all=").append(encode(keyword))
            append("&ft=music&itemset=web_2013&client=kt&pn=0&rn=12&rformat=json&encoding=utf8")
        }
        val raw = httpGetText(searchUrl) ?: return null
        val items = splitKuwoItems(raw)
        if (items.isEmpty()) return null

        var bestRid = 0L
        var bestScore = -1
        for (item in items) {
            val songName = unescapeNamedEntities(regexField(item, "SONGNAME"))
            val singer = unescapeNamedEntities(regexField(item, "ARTIST"))
            val rid = regexField(item, "DC_TARGETID").toLongOrNull() ?: continue
            if (rid <= 0L) continue
            val score = similarity(title, songName) * 3 +
                if (isUsefulArtist(artist)) similarity(artist, singer) * 2 else 0
            if (score > bestScore) {
                bestScore = score
                bestRid = rid
            }
        }
        if (bestRid <= 0L || bestScore < 3) return null

        val lyricUrl = "https://m.kuwo.cn/newh5/singles/songinfoandlrc?musicId=$bestRid&httpsStatus=1"
        val body = httpGetText(lyricUrl, mapOf("Referer" to "https://m.kuwo.cn/")) ?: return null
        val list = runCatching { JSONObject(body) }.getOrNull()
            ?.optJSONObject("data")
            ?.optJSONArray("lrclist")
            ?: return null
        if (list.length() == 0) return null

        val entries = buildList {
            for (i in 0 until list.length()) {
                val item = list.optJSONObject(i) ?: continue
                val text = unescapeNamedEntities(item.optString("lineLyric")).trim()
                if (text.isEmpty()) continue
                val seconds = item.optString("time").toDoubleOrNull() ?: continue
                if (seconds < 0.0) continue
                add((seconds * 1000.0).toLong() to text)
            }
        }.sortedBy { it.first }
        if (entries.isEmpty()) return null

        val lrcText = entries.joinToString("\n") { (ms, text) -> "[${timestamp(ms)}]$text" }
        return docFromLrc(lrcText, LyricProviders.KUWO)
    }

    /** 按 `'AARTIST':'` 把伪 JSON 切成一条条歌曲记录。 */
    private fun splitKuwoItems(raw: String): List<String> {
        val starts = Regex("'AARTIST':'").findAll(raw).map { it.range.first }.toList()
        if (starts.isEmpty()) return emptyList()
        return starts.mapIndexed { index, start ->
            val end = if (index + 1 < starts.size) starts[index + 1] else raw.length
            raw.substring(start, end)
        }
    }

    private fun regexField(chunk: String, field: String): String =
        Regex("'$field':'([^']*)'").find(chunk)?.groupValues?.get(1).orEmpty()

    // ---------------------------------------------------------------- textyl

    /**
     * `api.textyl.co`：免鉴权，返回 `[{"seconds":12,"lyrics":"..."}]`。
     *
     * 精度只到秒，但无需搜索、无需 cookie，作为「前几个渠道都空手」时的补充很合适。
     */
    private fun fetchFromTextyl(title: String, artist: String): ProviderResult? {
        val query = if (isUsefulArtist(artist)) "$artist $title" else title
        val body = httpGetText("https://api.textyl.co/api/lyrics?q=${encode(query)}") ?: return null
        val array = runCatching { JSONArray(body) }.getOrNull() ?: return null
        if (array.length() == 0) return null

        val entries = buildList {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val seconds = item.optDouble("seconds", -1.0)
                val text = item.optString("lyrics").trim()
                if (seconds >= 0.0 && text.isNotEmpty()) {
                    add((seconds * 1000).toLong() to text)
                }
            }
        }.sortedBy { it.first }
        if (entries.isEmpty()) return null

        // 借用标准 LRC 走一次解析，直接复用「行尾时间 = 下一行起点」的既有逻辑。
        val lrcText = entries.joinToString("\n") { (ms, text) -> "[${timestamp(ms)}]$text" }
        return docFromLrc(lrcText, LyricProviders.TEXTYL)
    }

    // ---------------------------------------------------------------- lyrics.ovh（仅纯文本）

    private fun fetchFromLyricsOvh(title: String, artist: String): ProviderResult? {
        if (!isUsefulArtist(artist)) return null
        val url = "https://api.lyrics.ovh/v1/${encodePath(artist)}/${encodePath(title)}"
        val body = httpGetText(url) ?: return null
        val json = runCatching { JSONObject(body) }.getOrNull() ?: return null
        return docFromPlain(json.optString("lyrics"), LyricProviders.LYRICS_OVH)
    }

    // ---------------------------------------------------------------- 结果包装

    private fun docFromLrc(text: String?, providerId: String): ProviderResult? {
        if (text.isNullOrBlank()) return null
        val document = LrcParser.parseDocument(text)
        if (document.lines.isEmpty()) return null
        return ProviderResult(document.copy(sourceProviderId = providerId), timed = true)
    }

    private fun docFromPlain(text: String?, providerId: String): ProviderResult? {
        if (text.isNullOrBlank()) return null
        val lines = LrcParser.fromPlainText(text)
        if (lines.isEmpty()) return null
        return ProviderResult(
            LyricDocument.singleTrack(lines).copy(sourceProviderId = providerId),
            timed = false,
        )
    }

    // ---------------------------------------------------------------- 查询词

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

    // ---------------------------------------------------------------- 工具

    /** `[mm:ss.xx]` 里的时间部分，显式 Locale 以免某些区域写出非 ASCII 数字。 */
    private fun timestamp(ms: Long): String {
        val centis = ms.coerceAtLeast(0L) / 10L
        return String.format(
            Locale.US,
            "%02d:%02d.%02d",
            centis / 6000L,
            (centis / 100L) % 60L,
            centis % 100L,
        )
    }

    /**
     * 常见**命名实体**（酷我搜索接口里歌名、歌手大量出现 `&nbsp;`）。
     *
     * 只处理几个真会撞上的，剩下的交给 [unescapeNumericEntities]。
     */
    private fun unescapeNamedEntities(text: String): String {
        if (text.isEmpty() || !text.contains('&')) return text
        val replaced = text
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
        return unescapeNumericEntities(replaced).replace('\u00A0', ' ').trim()
    }

    private fun unescapeNumericEntities(text: String): String {
        if (text.isEmpty() || !text.contains('&')) return text
        val sb = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            if (ch == '&') {
                val semi = text.indexOf(';', i)
                if (semi in (i + 1)..(i + 9)) {
                    val bodyText = text.substring(i + 1, semi)
                    val code = when {
                        bodyText.startsWith("#x") || bodyText.startsWith("#X") ->
                            bodyText.substring(2).toIntOrNull(16)
                        bodyText.startsWith("#") -> bodyText.substring(1).toIntOrNull()
                        else -> null
                    }
                    if (code != null && code > 0) {
                        sb.append(code.toChar())
                        i = semi + 1
                        continue
                    }
                }
            }
            sb.append(ch)
            i++
        }
        return sb.toString()
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
