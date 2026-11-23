package com.suisheng.player.data

import com.suisheng.player.lrc.LrcParser
import com.suisheng.player.lrc.LyricDocument
import com.suisheng.player.lrc.LyricProviderConfig
import com.suisheng.player.lrc.LyricProviders
import com.suisheng.player.lrc.YrcParser
import com.suisheng.player.model.LrcLine
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.abs

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
 * 纯文本结果（例如咪咕对英文歌只回纯文本）只能给占位时间轴。如果它直接返回，后面渠道的
 * 好时间轴就永远用不上。所以每一轮查询里先收集「无轴」结果当兜底，本轮内找到有轴的就
 * 直接返回 —— 最多多花一轮查询，不会全量重试。
 *
 * ## 一个渠道不是只问一个候选
 *
 * 五个源里没有一个能靠「搜索第一条」就稳定拿到对的歌：网易云的前排常年被翻唱占满
 * （搜「晴天」第一条是「晴天 (原唱 周杰伦) / RyaVocal」），酷狗会把「晴天 (Live)」指到
 * 综艺翻唱，咪咕会给「演员 (蒙面唱将猜猜猜)」。所以每个源都是：
 *
 * 1. 把搜索结果当作**候选集**，用 [scoreCandidate] 打分（标题/歌手相似度 + 时长贴合度 -
 *    「伴奏/翻唱/DJ/remix/live…」惩罚）；
 * 2. **标题必须真的像**（[titleMatches]，相似度 ≥ 3）才算候选 —— 只有歌手对上而歌名不像的
 *    条目一律丢弃，宁可漏也不要错；
 * 3. 按分数取前 [MAX_CANDIDATES] 个，**逐个去要歌词**，第一个拿到有轴歌词的赢。
 *
 * 时长这一项很关键：它能把「同一首歌的另一种编曲/现场版」和「真正这个伴奏」分开，
 * 也是目前唯一能在标题完全同名时区分翻唱的信号。调用方没有时长时（0）这一项不参与打分。
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
        /** 这首歌的时长（毫秒）。用来给候选打分、把翻唱/串烧排到后面；未知传 0。 */
        durationMs: Long = 0L,
    ): LyricFetchOutcome? {
        val providerIds = onlyProviderId
            ?.takeIf { LyricProviders.isKnown(it) }
            ?.let { listOf(it) }
            ?: config.enabledOrder()
        if (providerIds.isEmpty()) return null

        for ((track, singer) in buildSearchQueries(title, artist)) {
            // 注意 `track`/`singer` 只用来拼搜索词；给候选打分、以及识别咪咕头部伪行时，
            // 一律用**用户原本的歌名与歌手**。否则回退轮（比如丢掉歌手只搜歌名）会因为
            // 歌手项为空而让翻唱与原唱同分，等于把打分这一层白送掉。
            val request = SearchRequest(
                queryTitle = track,
                queryArtist = singer,
                title = title,
                artist = artist,
                durationMs = durationMs,
            )
            var weak: LyricFetchOutcome? = null
            for (id in providerIds) {
                val result = runCatching {
                    fetchFrom(id, request, preferWordLevel)
                }.getOrNull() ?: continue
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

    /** 一次取词请求：搜索用 [queryTitle]/[queryArtist]，打分用 [title]/[artist]。 */
    private data class SearchRequest(
        val queryTitle: String,
        val queryArtist: String,
        val title: String,
        val artist: String,
        val durationMs: Long,
    )

    /** 单次渠道结果。[timed] = 是否带真实时间轴（纯文本渠道为 false）。 */
    private data class ProviderResult(val document: LyricDocument, val timed: Boolean)

    private fun fetchFrom(
        id: String,
        request: SearchRequest,
        preferWordLevel: Boolean,
    ): ProviderResult? = when (id) {
        LyricProviders.NETEASE -> fetchFromNetease(request, preferWordLevel)
        LyricProviders.KUGOU -> fetchFromKugou(request)
        LyricProviders.QQ -> fetchFromQqMusic(request)
        LyricProviders.MIGU -> fetchFromMigu(request)
        LyricProviders.LRCLIB -> fetchFromLrcLib(request)
        else -> null
    }

    // ---------------------------------------------------------------- 网易云

    /**
     * 网易云：搜索用 `cloudsearch/pc`，不是 `search/get/web`。
     *
     * `search/get/web` 在高 limit 下会莫名其妙返回空 `songs`（同一个查询换 `limit=12`
     * 又正常），换 `cloudsearch/pc` 之后稳定返回，且 limit 拉到 30 能把官方原唱条目也捞进来。
     * 官方条目是否出现在前 30 条很关键：只取「第一条」时永远拿到翻唱。
     */
    private fun fetchFromNetease(request: SearchRequest, preferWordLevel: Boolean): ProviderResult? {
        val keyword = buildKeyword(request.queryTitle, request.queryArtist)
        val searchUrl =
            "https://music.163.com/api/cloudsearch/pc?s=${encode(keyword)}&type=1&offset=0&limit=30"
        val headers = neteaseHeaders()
        val searchJson = httpGetText(searchUrl, headers)?.let { runCatching { JSONObject(it) }.getOrNull() }
            ?: return null
        val songs = searchJson.optJSONObject("result")?.optJSONArray("songs") ?: return null
        if (songs.length() == 0) return null

        val items = (0 until songs.length()).mapNotNull { songs.optJSONObject(it) }
        val ranked = rankCandidates(request, items) { song ->
            CandidateMeta(
                title = song.optString("name"),
                artist = neteaseArtistOf(song),
                // cloudsearch/pc 的 dt 是毫秒；老的 artists 结构里叫 duration。
                durationSec = ((song.optLong("dt").takeIf { it > 0L }
                    ?: song.optLong("duration")) / 1000L).toInt(),
            )
        }

        for (song in ranked) {
            val id = song.optLong("id")
            if (id <= 0L) continue
            val document = fetchNeteaseLyric(id, request, preferWordLevel, headers) ?: continue
            if (document.lines.isEmpty()) continue
            return ProviderResult(document, timed = true)
        }
        return null
    }

    private fun fetchNeteaseLyric(
        id: Long,
        request: SearchRequest,
        preferWordLevel: Boolean,
        headers: Map<String, String>,
    ): LyricDocument? {
        // lv=1 原文轨，tv=-1 译文轨，rv=-1 罗马音（注音）轨，yv=-1 逐字轨。
        // yv 不加时接口不会返回 `yrc` 字段，逐字就永远拿不到 —— 这是旧实现漏掉的一处。
        val lyricUrl = "https://music.163.com/api/song/lyric?id=$id&lv=1&kv=1&tv=-1&rv=-1&yv=-1"
        val lyricJson = httpGetText(lyricUrl, headers)?.let { runCatching { JSONObject(it) }.getOrNull() }
            ?: return null

        val translation = lyricJson.optJSONObject("tlyric")?.optString("lyric")
        val phonetic = lyricJson.optJSONObject("romalrc")?.optString("lyric")

        // 行级轨先单独建好并挂上侧轨：`tlyric` / `romalrc` 的时间轴是跟着 `lrc` 生成的，
        // 与逐字 `yrc` 不是同一套基准（实测《Lemon》侧轨对 lrc 命中 42/45、对 yrc 命中 0/45）。
        // 所以侧轨一律先挂在行级轨上，需要逐字时再整行搬运过去 —— 顺序反了就会成片丢译文。
        val synced = lyricJson.optJSONObject("lrc")?.optString("lyric")?.takeIf { it.isNotBlank() }
        val lineDoc = synced
            ?.takeUnless { looksLikeAudiobook(it) }
            ?.let { LrcParser.parseDocument(stripCreditHeader(it, request.title, request.artist)) }
            ?.takeIf { it.lines.isNotEmpty() }
            ?.copy(sourceProviderId = LyricProviders.NETEASE)
            ?.let { LrcParser.mergePhonetic(LrcParser.mergeTranslation(it, translation), phonetic) }

        // 逐字轨优先：yrc 带每一行、每个字的起止毫秒，是「KTV 字字对应」的唯一真数据源。
        // 它不保证每首歌都有，也不保证格式规整，所以任何一步不像 yrc 就静默回落到行级轨。
        if (preferWordLevel) {
            val yrc = lyricJson.optJSONObject("yrc")?.optString("lyric").orEmpty()
            if (YrcParser.looksLikeYrc(yrc)) {
                val wordLines = runCatching { YrcParser.parse(yrc) }.getOrDefault(emptyList())
                // yrc 里同样带着「作词 : 林夕」这类制作信息行（实测《富士山下》首行就是），
                // 只是它们也带逐字标签，所以要在**解析成行之后**再滤一次。
                val cleaned = stripCreditLines(wordLines, request.title, request.artist)
                if (cleaned.isNotEmpty()) {
                    val wordDoc = LyricDocument.singleTrack(cleaned)
                        .copy(sourceProviderId = LyricProviders.NETEASE)
                    // 有行级轨就按行对应关系把译文/注音搬过来，没有就原样返回逐字轨。
                    return lineDoc?.let { LrcParser.retargetSideTracks(wordDoc, it) } ?: wordDoc
                }
            }
        }

        return lineDoc
    }

    private fun neteaseArtistOf(song: JSONObject): String {
        val array = song.optJSONArray("ar") ?: song.optJSONArray("artists") ?: return ""
        return (0 until array.length()).joinToString("/") { index ->
            array.optJSONObject(index)?.optString("name").orEmpty()
        }
    }

    // ---------------------------------------------------------------- 酷狗

    /**
     * 酷狗：`songsearch.kugou.com` → `lyrics.kugou.com` 两步。
     *
     * 旧实现走 `mobilecdn.kugou.com/api/v3/search/song`，这个域名现在的证书是
     * `*.cdn.myqcloud.com` 的，`HttpURLConnection` 直接 `SSLHandshakeException` ——
     * 也就是**旧酷狗渠道在真机上从来没成功过**。`songsearch.kugou.com` 的证书是对的。
     *
     * 歌词只取 `fmt=lrc`。`fmt=krc` 返回的 base64 解出来不是标准 KRC（XOR 之后是
     * `k2$P` 开头的私有容器，第二段密钥未知），所以逐字轨这条路走不通，不碰。
     */
    private fun fetchFromKugou(request: SearchRequest): ProviderResult? {
        val keyword = buildKeyword(request.queryTitle, request.queryArtist)
        val searchUrl = "https://songsearch.kugou.com/song_search_v2?keyword=${encode(keyword)}" +
            "&page=1&pagesize=15&userid=-1&clientver=&platform=WebFilter" +
            "&filter=2&iscorrection=1&privilege_filter=0"
        val searchJson = httpGetText(searchUrl)?.let { runCatching { JSONObject(it) }.getOrNull() }
            ?: return null
        val lists = searchJson.optJSONObject("data")?.optJSONArray("lists") ?: return null
        if (lists.length() == 0) return null

        val items = (0 until lists.length()).mapNotNull { lists.optJSONObject(it) }
        val ranked = rankCandidates(request, items) { item ->
            CandidateMeta(
                title = item.optString("SongName"),
                artist = item.optString("SingerName"),
                durationSec = item.optInt("Duration", 0),
            )
        }

        for (item in ranked) {
            val hash = item.optString("FileHash").takeIf { it.isNotBlank() } ?: continue
            val albumAudioId = item.optLong("Audioid")
            val durationSec = item.optInt("Duration", 0)
            val lyric = fetchKugouLyric(hash, albumAudioId, request, durationSec) ?: continue
            if (lyric.lines.isEmpty()) continue
            return ProviderResult(lyric, timed = true)
        }
        return null
    }

    private fun fetchKugouLyric(
        hash: String,
        albumAudioId: Long,
        request: SearchRequest,
        durationSec: Int,
    ): LyricDocument? {
        val searchLyricUrl = buildString {
            append("https://lyrics.kugou.com/search?ver=1&man=yes&client=pc")
            append("&hash=").append(hash)
            append("&album_audio_id=").append(albumAudioId)
            append("&keyword=").append(encode(request.title))
            if (durationSec > 0) append("&duration=").append(durationSec * 1000)
        }
        val lyricSearch = httpGetText(searchLyricUrl)?.let { runCatching { JSONObject(it) }.getOrNull() }
            ?: return null
        val candidate = lyricSearch.optJSONArray("candidates")?.optJSONObject(0) ?: return null
        val lyricId = candidate.optString("id")
        val accessKey = candidate.optString("accesskey")
        if (lyricId.isBlank() || accessKey.isBlank()) return null

        val downloadUrl = "https://lyrics.kugou.com/download?ver=1&client=pc" +
            "&id=$lyricId&accesskey=$accessKey&fmt=lrc&charset=utf8"
        val downloadJson = httpGetText(downloadUrl)?.let { runCatching { JSONObject(it) }.getOrNull() }
            ?: return null
        val content = downloadJson.optString("content").takeIf { it.isNotBlank() } ?: return null
        val decoded = runCatching {
            String(android.util.Base64.decode(content, android.util.Base64.DEFAULT), Charsets.UTF_8)
        }.getOrDefault(content).removePrefix("\uFEFF")
        // 酷狗这里踩过最难看的一个坑：搜《成都》/赵雷，标题歌手时长全对，
        // 返回的却是「本字幕由TME AI技术生成」的有声书字幕。只能看正文。
        if (looksLikeAudiobook(decoded)) return null
        val document = LrcParser.parseDocument(
            stripCreditHeader(decoded, request.title, request.artist),
        )
        if (document.lines.isEmpty()) return null
        return document.copy(sourceProviderId = LyricProviders.KUGOU)
    }

    // ---------------------------------------------------------------- QQ 音乐

    /**
     * QQ 音乐：`search_for_qq_cp` → `fcg_query_lyric_new.fcg`。
     *
     * 旧的 `client_search_cp` 现在恒返回 `{"song":{"list":[],"totalnum":0}}`（接口下架了），
     * `search_for_qq_cp` 仍然可用。
     *
     * `trans` 字段实测 18 首歌**全部为空串**，所以这里虽然还调 `mergeTranslation`（留着
     * 无害，万一哪天恢复了直接生效），但渠道能力表里不再宣称有译文。
     */
    private fun fetchFromQqMusic(request: SearchRequest): ProviderResult? {
        val keyword = buildKeyword(request.queryTitle, request.queryArtist)
        val searchUrl = "https://c.y.qq.com/soso/fcgi-bin/search_for_qq_cp?format=json&p=1&n=15" +
            "&w=${encode(keyword)}&aggr=1&lossless=0&cr=1&new_json=1&platform=yqq.json"
        val headers = mapOf("Referer" to "https://y.qq.com/")
        val searchJson = httpGetText(searchUrl, headers)?.let { parseJsonBody(it) } ?: return null
        val songs = searchJson.optJSONObject("data")?.optJSONObject("song")?.optJSONArray("list")
            ?: return null
        if (songs.length() == 0) return null

        val items = (0 until songs.length()).mapNotNull { songs.optJSONObject(it) }
        val ranked = rankCandidates(request, items) { song ->
            CandidateMeta(
                title = song.optString("songname"),
                artist = qqSingerOf(song),
                durationSec = song.optInt("interval", 0),
            )
        }

        for (song in ranked) {
            val songId = song.optLong("songid").takeIf { it > 0L } ?: song.optLong("id")
            if (songId <= 0L) continue
            val lyricUrl = "https://c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg" +
                "?format=json&nobase64=1&songtype=0&musicid=$songId"
            val lyricJson = httpGetText(lyricUrl, headers)?.let { parseJsonBody(it) } ?: continue
            // QQ 的歌词正文里常把换行、冒号写成 `&#10;` / `&#58;` 这样的数字实体，
            // 不解码就会得到一条根本解析不出时间戳的单行。
            val lyricText = unescapeNumericEntities(lyricJson.optString("lyric"))
                .takeIf { it.isNotBlank() } ?: continue
            if (looksLikeAudiobook(lyricText)) continue
            val base = LrcParser.parseDocument(
                stripCreditHeader(lyricText, request.title, request.artist),
            )
            if (base.lines.isEmpty()) continue
            val trans = unescapeNumericEntities(lyricJson.optString("trans")).takeIf { it.isNotBlank() }
            return ProviderResult(
                LrcParser.mergeTranslation(base.copy(sourceProviderId = LyricProviders.QQ), trans),
                timed = true,
            )
        }
        return null
    }

    /** `new_json=1` 时 `singer` 是对象数组，老接口是字符串化的数组，两种都认。 */
    private fun qqSingerOf(song: JSONObject): String {
        val array = song.optJSONArray("singer")
        if (array != null) {
            return (0 until array.length()).joinToString("/") { index ->
                array.optJSONObject(index)?.optString("name").orEmpty()
            }
        }
        val raw = song.optString("singer")
        if (raw.isBlank()) return ""
        return runCatching {
            JSONArray(raw).let { arr ->
                (0 until arr.length()).joinToString("/") { index ->
                    arr.optJSONObject(index)?.optString("name").orEmpty()
                }
            }
        }.getOrDefault(raw)
    }

    // ---------------------------------------------------------------- 咪咕音乐

    /**
     * 咪咕：`search_all.do` 的结果里**直接带歌词地址**，GET 就是 LRC 文本。
     *
     * 这是目前唯一一个「不用二次查询、一次搜索就把歌词 URL 拿到手」的源，代价是：
     *
     * - 结果是二维数组（`songResultData.resultList`），要先拍平；
     * - **没有时长字段**，所以时长打分对咪咕不生效，只能靠标题+歌手；
     * - LRC 开头有 5~15 行伪造的「歌曲信息」行（`[00:01.00]晴天 - 周杰伦`、
     *   `[00:02.00]作词：周杰伦`、`[00:03.00]作曲：周杰伦`……），不清掉就会当成歌词
     *   一句句唱出来；
     * - 英文歌常常只回纯文本（前面几行 `@migu music@`），这种只能当无轴兜底。
     *
     * `trcUrl` 是**带时间轴的中文译词**（英文歌 95~127 行完整对齐），走 mergeTranslation。
     */
    private fun fetchFromMigu(request: SearchRequest): ProviderResult? {
        val keyword = buildKeyword(request.queryTitle, request.queryArtist)
        val searchUrl = "https://app.c.nf.migu.cn/MIGUM2.0/v1.0/content/search_all.do" +
            "?isCopyrightQuery=&pageNo=1&pageSize=20&searchSwitch=%7B%22song%22%3A1%7D" +
            "&text=${encode(keyword)}&sort=0&gender=0&tariff=0&star=0" +
            "&appId=1&isCorrect=1&isSuggestion=0"
        val headers = mapOf("Referer" to "https://music.migu.cn/")
        val searchJson = httpGetText(searchUrl, headers)?.let { runCatching { JSONObject(it) }.getOrNull() }
            ?: return null

        val raw = searchJson.optJSONObject("songResultData")?.optJSONArray("resultList") ?: return null
        val flat = ArrayList<JSONObject>()
        for (i in 0 until raw.length()) {
            val row = raw.optJSONObject(i)
            if (row != null) {
                flat.add(row)
            } else {
                // 二维数组：[ [song, song], [song] ] 这种形状要拍平
                val nested = raw.optJSONArray(i) ?: continue
                for (j in 0 until nested.length()) {
                    nested.optJSONObject(j)?.let { flat.add(it) }
                }
            }
        }
        if (flat.isEmpty()) return null

        val ranked = rankCandidates(request, flat) { item ->
            CandidateMeta(
                title = item.optString("name"),
                artist = miguSingersOf(item),
                // 咪咕的结果里没有时长字段，时长项自然不参与打分（见 scoreCandidate）。
                durationSec = 0,
            )
        }

        var weak: ProviderResult? = null
        for (item in ranked) {
            val lyricUrl = item.optString("lyricUrl").takeIf { it.isNotBlank() } ?: continue
            val body = httpGetText(lyricUrl) ?: continue
            if (looksLikeAudiobook(body)) continue
            // 识别头部伪行用**用户原本的歌名歌手**：搜索结果里的 `name` 可能带
            // 「(蒙面唱将猜猜猜)」这种后缀，拿它去匹配《演员 - 田馥甄》就对不上了。
            val cleaned = stripCreditHeader(body, request.title, request.artist)
            if (cleaned.isBlank()) continue

            val document = LrcParser.parseDocument(cleaned)
            if (document.lines.isEmpty()) {
                // 没有时间轴：只记下来当兜底，继续看下一个候选。
                val plain = LrcParser.fromPlainText(cleaned)
                if (plain.isNotEmpty() && weak == null) {
                    weak = ProviderResult(
                        LyricDocument.singleTrack(plain)
                            .copy(sourceProviderId = LyricProviders.MIGU),
                        timed = false,
                    )
                }
                continue
            }

            val translation = item.optString("trcUrl")
                .takeIf { it.isNotBlank() }
                ?.let { url -> httpGetText(url) }
                ?.let { stripCreditHeader(it, request.title, request.artist) }
            val base = document.copy(sourceProviderId = LyricProviders.MIGU)
            return ProviderResult(LrcParser.mergeTranslation(base, translation), timed = true)
        }
        return weak
    }

    /** 咪咕的 `singers` 是对象数组；有些版本给的是字符串数组，两种都认。 */
    private fun miguSingersOf(item: JSONObject): String {
        val array = item.optJSONArray("singers") ?: return ""
        val names = ArrayList<String>(array.length())
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i)
            if (obj != null) {
                names.add(obj.optString("name"))
            } else {
                names.add(array.optString(i))
            }
        }
        return names.filter { it.isNotBlank() }.joinToString("/")
    }

    /**
     * 清掉 LRC 开头的「制作信息」伪行和段落标记。
     *
     * 这些行**每个源都有**，不只是咪咕 —— 实测网易云的《富士山下》首行是
     * `作词 : 林夕`、QQ 的是 `富士山下 - 陈奕迅 (Eason Chan)` / `词：林夕`、
     * 酷狗的是 `周杰伦 - 稻香` / `作词：周杰伦`。它们带真实时间戳，所以会被当成
     * 第一句歌词唱出来，用户看到的就是「歌词第一句是作词是谁」。
     *
     * 只在前 [HEAD_WINDOW_MS] 毫秒（无轴时是前 [HEAD_WINDOW_LINES] 行）内动手，
     * 因为正常歌词里也可能出现「旁白：……」这种带冒号的句子，不能全文扫。
     * 另外还清掉 `[verse]` / `[chorus]` 这类段落标记 —— 咪咕的日文歌词就带。
     */
    private fun stripCreditHeader(text: String, title: String, artist: String): String {
        val kept = ArrayList<String>()
        var lineIndex = 0
        for (raw in text.lineSequence()) {
            val line = raw.trimEnd()
            val body = TIMESTAMP_PREFIX.replace(line, "").trim()
            if (body.isEmpty()) {
                kept.add(line)
                continue
            }
            val startMs = TIMESTAMP_PREFIX.find(line)?.let { parseFirstTimestamp(it.value) } ?: -1L
            // 两个窗口取并集：头 15 秒**或者**头 25 行。
            // 只看时间会漏 —— 实测《孤勇者》的「出品监制：霍锦 卢泓宇」排在文件最前面
            // 却带着 20 多秒的时间戳；只看行号又会误伤带前置间奏的歌。
            val inHead = (startMs >= 0L && startMs <= HEAD_WINDOW_MS) ||
                lineIndex < HEAD_WINDOW_LINES
            lineIndex++
            val drop = body == MIGU_WATERMARK ||
                SECTION_MARKER.matches(body) ||
                (inHead && (META_LABEL.containsMatchIn(body) || isTitleHeader(body, title, artist)))
            if (!drop) kept.add(line)
        }
        val result = kept.joinToString("\n")
        // 保险：万一这首歌的词全是制作信息（理论上不存在），清完就空了，那就别清。
        return if (LrcParser.parseDocument(result).lines.size >= 3) result else text
    }

    private fun isTitleHeader(body: String, title: String, artist: String): Boolean {
        val match = TITLE_HEADER.matchEntire(body) ?: return false
        val left = match.groupValues[1]
        val right = match.groupValues[2]
        return similarity(title, left) >= 7 ||
            similarity(title, right) >= 7 ||
            (isUsefulArtist(artist) && similarity(artist, right) >= 7)
    }

    /**
     * 这段文本其实是**有声书字幕 / AI 生成字幕**，不是歌词。
     *
     * 实测酷狗搜《成都》/赵雷，第一条候选（标题、歌手、时长全都对得上）的歌词却是
     * 「本字幕由TME AI技术生成」「欢迎收听由懒人听书出品的玄医枭后」「演播 耳雨」。
     * 这种条目任何标题/歌手/时长打分都拦不住，只能看正文。
     */
    private fun looksLikeAudiobook(text: String): Boolean =
        text.lineSequence().take(AUDIOBOOK_SNIFF_LINES).any { AUDIOBOOK_MARKER.containsMatchIn(it) }

    /**
     * [stripCreditHeader] 的逐字轨版本。
     *
     * yrc 已经解析成 [LrcLine]（每行带着逐字时间标签），没法再按文本行去切，只能按行滤。
     * 实测网易云《富士山下》的 yrc 首行就是 `作词 : 林夕`。
     */
    private fun stripCreditLines(lines: List<LrcLine>, title: String, artist: String): List<LrcLine> {
        val kept = lines.filterIndexed { index, line ->
            val body = line.text.trim()
            if (body.isEmpty()) return@filterIndexed true
            // 水印和 `[verse]` / `[MUSIC]` 这类段落标记**在文件任何位置都要删**：
            // 它们不是歌词，而且实测《残酷な天使のテーゼ》的 `[MUSIC]` 出现在中间，
            // 留着会把后面的行整体顶偏一位，两侧轨就再也对不齐了。
            if (body == MIGU_WATERMARK || SECTION_MARKER.matches(body)) return@filterIndexed false
            // 与 [stripCreditHeader] 同一套窗口规则：头 15 秒**或者**头 25 行。
            if (line.startTimeMs > HEAD_WINDOW_MS && index >= HEAD_WINDOW_LINES) return@filterIndexed true
            val drop = META_LABEL.containsMatchIn(body) || isTitleHeader(body, title, artist)
            !drop
        }
        return if (kept.size >= 3) kept else lines
    }

    /** 从 `[mm:ss.xx]` / `[mm:ss.xx][mm:ss.yy]…` 里取**第一个**时间标签的毫秒值。 */
    private fun parseFirstTimestamp(tag: String): Long? {
        val inner = tag.substringAfter('[', "").substringBefore(']', "")
        if (inner.isEmpty()) return null
        val parts = inner.split(":")
        if (parts.size != 2) return null
        val minutes = parts[0].toLongOrNull() ?: return null
        val secondsPart = parts[1].replace(',', '.')
        val seconds = secondsPart.substringBefore('.').toLongOrNull() ?: return null
        val fraction = secondsPart.substringAfter('.', "")
        val millis = when (fraction.length) {
            0 -> 0L
            1 -> fraction.toLongOrNull()?.times(100L) ?: 0L
            2 -> fraction.toLongOrNull()?.times(10L) ?: 0L
            else -> fraction.take(3).toLongOrNull() ?: 0L
        }
        return minutes * 60_000L + seconds * 1000L + millis
    }

    // ---------------------------------------------------------------- LRCLIB

    /**
     * LRCLIB：先 `GET /api/get`（精确匹配），404 再 `GET /api/search` 排序取第一条。
     *
     * 中文曲库返回的是**繁体**（「富士山下」的首行是「攔路雨偏似雪花」），而且接口偶尔
     * 十几秒不返回（实测遇到过 13814ms 的超时）—— 所以超时压到 5 秒，并且排在国产源之后。
     */
    private fun fetchFromLrcLib(request: SearchRequest): ProviderResult? {
        // LRCLIB 的 `/api/get` 是精确匹配接口，用**用户原本的歌名歌手**去问；
        // 用回退轮的「只有歌名」反而会把艺术家过滤掉，命中率更低。
        val direct = fetchLrcLibGet(request.title, request.artist)
        if (direct?.timed == true) return direct

        val array = searchLrcLib(request.title, request.artist)
        if (array != null && array.length() > 0) {
            val items = (0 until array.length()).mapNotNull { array.optJSONObject(it) }
            val ranked = rankCandidates(request, items) { item ->
                CandidateMeta(
                    title = item.optString("trackName", item.optString("name")),
                    artist = item.optString("artistName", item.optString("artist")),
                    durationSec = item.optDouble("duration", 0.0).toInt(),
                )
            }
            for (item in ranked) {
                val timed = item.optString("syncedLyrics").takeIf { it.isNotBlank() } ?: continue
                docFromLrc(timed, LyricProviders.LRCLIB)?.let { return it }
            }
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
        val body = httpGetText(url, timeoutMs = LRCLIB_TIMEOUT_MS) ?: return null
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
        val body = httpGetText(url, timeoutMs = LRCLIB_TIMEOUT_MS) ?: return null
        return runCatching { JSONArray(body) }.getOrNull()
    }

    // ---------------------------------------------------------------- 候选打分

    private data class CandidateMeta(
        val title: String,
        val artist: String,
        /** 候选自报的时长（秒）；没有就是 0，此时时长项不打分。 */
        val durationSec: Int,
    )

    /**
     * 过滤 + 排序 + 截断。**标题不像的候选直接丢弃**（见 [titleMatches]）。
     *
     * 打分公式（回归基准见 commit message / [OnlineLyricFetcher] 类注释）：
     *
     * ```
     * 标题相似度 * 10 + 歌手相似度 * 6
     *   + 时长差 ≤3s → +8，≤10s → +4，>30s → -20
     *   - 命中「伴奏/翻唱/DJ/live…」→ -25
     * ```
     */
    private fun <T> rankCandidates(
        request: SearchRequest,
        items: List<T>,
        meta: (T) -> CandidateMeta,
    ): List<T> = items
        .map { it to meta(it) }
        .filter { (_, info) -> titleMatches(request.title, info.title) }
        .sortedByDescending { (_, info) -> scoreCandidate(request, info) }
        .take(MAX_CANDIDATES)
        .map { (item, _) -> item }

    private fun scoreCandidate(request: SearchRequest, candidate: CandidateMeta): Int {
        var score = similarity(request.title, candidate.title) * 10
        if (isUsefulArtist(request.artist)) {
            score += similarity(request.artist, candidate.artist) * 6
        }
        if (request.durationMs > 0L && candidate.durationSec > 0) {
            val diff = abs(request.durationMs / 1000L - candidate.durationSec.toLong())
            score += when {
                diff <= 3L -> 8
                diff <= 10L -> 4
                diff > 30L -> -20
                else -> 0
            }
        }
        if (hasUnexpectedVariantMarker(candidate.title, request.title)) score -= 25
        return score
    }

    /**
     * 候选标题里有没有**用户这首歌本来没有**的变体标记。
     *
     * 直接比「命中就扣分」会误伤一类常见情况：用户存的文件本来就是官方 Live 版
     * （`晴天 (Live)`），此时所有候选都带 `live`，一起扣分等于没扣，反而让
     * 「(KTV版伴奏)」这种真正的错配跟它对分。所以先各自取出命中的标记词，
     * 只有候选**多出来**的那部分才算可疑。
     */
    private fun hasUnexpectedVariantMarker(candidateTitle: String, requestTitle: String): Boolean {
        val wanted = variantMarkers(requestTitle)
        return variantMarkers(candidateTitle).any { it !in wanted }
    }

    private fun variantMarkers(value: String): Set<String> =
        SUSPICIOUS_TITLE.findAll(value).map { it.value.lowercase() }.toSet()

    /**
     * 标题硬门槛。
     *
     * 只有歌手对上、歌名完全不像的候选一律不要 —— 实测「红莲华 / LiSA」会因为歌手匹配到
     * 「Catch the Moment (Chinese Version)」，结果歌词整首是错的。宁可这一首取不到词。
     */
    private fun titleMatches(title: String, candidateTitle: String): Boolean =
        similarity(title, candidateTitle) >= 3

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

    /**
     * 「歌名 歌手」拼成搜索词；歌手不可用时只搜歌名。
     *
     * 只有拼搜索词时才允许丢歌手 —— 那是为了让回退轮能搜到「库里歌手名和标签不一致」
     * 的歌。打分永远用用户原本的歌名歌手（见 [SearchRequest]）。
     */
    private fun buildKeyword(title: String, artist: String): String =
        if (isUsefulArtist(artist)) "$title $artist" else title

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

    // ---------------------------------------------------------------- 工具

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

    private fun httpGetText(
        url: String,
        headers: Map<String, String> = emptyMap(),
        timeoutMs: Int = DEFAULT_TIMEOUT_MS,
    ): String? {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
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
            .replace(Regex("[\\s\\-_'’·:：]"), "")
            .trim()
    }

    private fun isUsefulArtist(artist: String): Boolean {
        return artist.isNotBlank() && artist !in IGNORED_ARTISTS
    }

    private fun encode(value: String): String =
        URLEncoder.encode(value, Charsets.UTF_8.name())

    companion object {
        private const val BROWSER_UA =
            "Mozilla/5.0 (Linux; Android 12; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

        private const val DEFAULT_TIMEOUT_MS = 8_000

        /** LRCLIB 出过十几秒不返回的记录，单独压短，免得拖垮整轮查询。 */
        private const val LRCLIB_TIMEOUT_MS = 5_000

        /** 每个渠道最多真正取几个候选的歌词。 */
        private const val MAX_CANDIDATES = 3

        private val IGNORED_ARTISTS = setOf("未知歌手", "本地音乐", "<unknown>", "Unknown")

        /**
         * 「同一首歌的**另一种东西**」的标题特征。
         *
         * 命中就扣 25 分（差不多等于「标题完全对不上」的量级），但**不直接排除** ——
         * 有些冷门歌真的只有伴奏版有歌词，扣分之后如果它是唯一候选仍然会被采用。
         */
        private val SUSPICIOUS_TITLE = Regex(
            "(伴奏|ktv|karaoke|remix|dj|女声版|男声版|童声|翻唱|原唱\\s*[:：]|钢琴版|吉他版|纯音乐" +
                "|instrumental|cover|live|现场|片段|铃声|加速|降速|8bit|抖音|混音|串烧|教学|前奏|升调|降调)",
            RegexOption.IGNORE_CASE,
        )

        private val TIMESTAMP_PREFIX = Regex("^\\s*(\\[\\d{1,3}:\\d{1,2}(?:[.:]\\d{1,3})?\\]\\s*)+")

        private val TITLE_HEADER = Regex("^(.{1,40}?)\\s+-\\s+(.{1,40})$")

        private const val MIGU_WATERMARK = "@migu music@"

        /** LRC 头部「作词：xxx」这类伪行的特征：短的标签 + 全角/半角冒号。 */
        private val META_LABEL = Regex("^[^：:]{1,12}[：:]")

        /** 段落标记，如咪咕日文歌词里的 `[verse]` / `[chorus]` / `[bridge]`。 */
        private val SECTION_MARKER = Regex("^\\[[a-zA-Z _-]{2,20}]$")

        private const val HEAD_WINDOW_MS = 15_000L

        private const val HEAD_WINDOW_LINES = 25

        /**
         * 有声书 / AI 字幕的特征词。
         *
         * 实测酷狗搜《成都》/赵雷，标题歌手时长全对，歌词却是「本字幕由TME AI技术生成」
         * 「欢迎收听由懒人听书出品的玄医枭后」—— 只能从正文认出来。
         */
        private val AUDIOBOOK_MARKER = Regex(
            "(本字幕|字幕由|由.{0,12}出品|AI技术生成|AI智能字幕|懒人听书|喜马拉雅" +
                "|欢迎收听|有声书|演播|播讲|朗读|第[0-9一二三四五六七八九十百千]+集)",
            RegexOption.IGNORE_CASE,
        )

        /** 只看开头这么多行判是不是有声书，避免正片歌词里偶然出现「第X集」。 */
        private const val AUDIOBOOK_SNIFF_LINES = 12
    }
}
