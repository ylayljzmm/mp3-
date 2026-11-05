package com.suisheng.player.data

import android.content.Context
import com.suisheng.player.model.PodcastEpisode
import com.suisheng.player.model.PodcastFeed
import com.suisheng.player.model.Song
import com.suisheng.player.util.MediaPath
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

class RssFeedRepository(
    private val context: Context,
    private val settings: SettingsRepository
) {
    private val cacheDir = File(context.filesDir, "rss_cache").apply { mkdirs() }

    fun configuredFeeds(): List<PodcastFeed> =
        settings.podcastRssUrls().mapIndexed { index, url ->
            PodcastFeed(
                id = feedId(url),
                title = cachedFeedTitle(url) ?: PodcastDefaults.displayName(url) ?: "播客 ${index + 1}",
                url = url,
                imageUrl = cachedFeedImage(url)
            )
        }

    fun fetchEpisodes(feed: PodcastFeed, limit: Int = 80): List<PodcastEpisode> {
        val xml = httpGet(feed.url) ?: return readCachedEpisodes(feed.id)
        val parsed = parseRss(xml, feed)
        if (parsed.isNotEmpty()) writeEpisodeCache(feed.id, parsed)
        return parsed.take(limit)
    }

    fun songFromEpisode(episode: PodcastEpisode): Song = Song(
        id = episode.guid.hashCode().toLong(),
        title = episode.title,
        artist = episode.feedTitle,
        path = MediaPath.podcast(episode.feedId, episode.guid, episode.audioUrl),
        lrcPath = episode.description.takeIf { it.isNotBlank() }?.let { "desc:${it.hashCode()}" },
        durationMs = 0L
    )

    fun episodeDescription(path: String): String? {
        if (!MediaPath.isPodcast(path)) return null
        val feedId = path.removePrefix(MediaPath.PODCAST).substringBefore('/')
        val guid = path.removePrefix(MediaPath.PODCAST).substringAfter('/').substringBefore('?')
        return readCachedEpisodes(feedId).firstOrNull { it.guid == guid }?.description
    }

    private fun parseRss(xml: String, feed: PodcastFeed): List<PodcastEpisode> {
        val factory = XmlPullParserFactory.newInstance()
        val parser = factory.newPullParser()
        parser.setInput(xml.reader())

        var event = parser.eventType
        var inItem = false
        var title = ""
        var guid = ""
        var audioUrl = ""
        var pubDate = 0L
        var description = ""
        var imageUrl = feed.imageUrl
        var feedTitle = feed.title
        val episodes = mutableListOf<PodcastEpisode>()

        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "item" -> {
                        inItem = true
                        title = ""
                        guid = ""
                        audioUrl = ""
                        pubDate = 0L
                        description = ""
                    }
                    "title" -> if (!inItem) feedTitle = parser.nextTextSafe() else title = parser.nextTextSafe()
                    "guid" -> guid = parser.nextTextSafe()
                    "description", "itunes:summary", "content:encoded" ->
                        if (inItem) description = stripHtml(parser.nextTextSafe())
                    "pubDate", "itunes:duration" -> if (inItem && pubDate == 0L) {
                        pubDate = parser.nextTextSafe().hashCode().toLong()
                    }
                    "enclosure" -> if (inItem) {
                        val type = parser.getAttributeValue(null, "type").orEmpty()
                        val url = parser.getAttributeValue(null, "url").orEmpty()
                        if (url.startsWith("http") && isAudioEnclosure(type, url)) {
                            audioUrl = url
                        }
                    }
                    "url" -> if (parser.depth <= 4) {
                        val maybe = parser.nextTextSafe()
                        if (maybe.startsWith("http") && maybe.contains("image")) imageUrl = maybe
                    }
                }
                XmlPullParser.END_TAG -> if (parser.name == "item" && inItem) {
                    if (audioUrl.isNotBlank()) {
                        val id = guid.ifBlank { "$title|$audioUrl".hashCode().toString() }
                        episodes.add(
                            PodcastEpisode(
                                feedId = feed.id,
                                feedTitle = feedTitle,
                                guid = id,
                                title = title.ifBlank { "未命名节目" },
                                audioUrl = audioUrl,
                                pubDate = pubDate,
                                description = description,
                                imageUrl = imageUrl
                            )
                        )
                    }
                    inItem = false
                }
            }
            event = parser.next()
        }
        cacheFeedMeta(feed.url, feedTitle, imageUrl)
        return episodes.sortedByDescending { it.pubDate }
    }

    private fun XmlPullParser.nextTextSafe(): String = runCatching { nextText().trim() }.getOrDefault("")

    private fun stripHtml(raw: String): String =
        raw.replace(Regex("<[^>]+>"), " ").replace(Regex("\\s+"), " ").trim()

    private fun isAudioEnclosure(type: String, url: String): Boolean {
        if (type.contains("audio", ignoreCase = true)) return true
        val lower = url.lowercase()
        return lower.endsWith(".mp3") || lower.endsWith(".m4a") || lower.endsWith(".aac") ||
            lower.contains(".mp3?") || lower.contains(".m4a?")
    }

    private fun feedId(url: String): String {
        val digest = MessageDigest.getInstance("MD5").digest(url.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }.take(12)
    }

    private fun cacheFeedMeta(url: String, title: String, image: String?) {
        File(cacheDir, "${feedId(url)}_meta.txt").writeText("$title\n${image.orEmpty()}")
    }

    private fun cachedFeedTitle(url: String): String? =
        File(cacheDir, "${feedId(url)}_meta.txt").takeIf { it.exists() }?.readLines()?.firstOrNull()

    private fun cachedFeedImage(url: String): String? =
        File(cacheDir, "${feedId(url)}_meta.txt").takeIf { it.exists() }?.readLines()?.getOrNull(1)
            ?.takeIf { it.startsWith("http") }

    private fun writeEpisodeCache(feedId: String, episodes: List<PodcastEpisode>) {
        val text = episodes.joinToString("\n") { "${it.guid}\t${it.title}\t${it.audioUrl}\t${it.description}" }
        File(cacheDir, "$feedId.episodes").writeText(text)
    }

    private fun readCachedEpisodes(feedId: String): List<PodcastEpisode> {
        val file = File(cacheDir, "$feedId.episodes")
        if (!file.exists()) return emptyList()
        val feed = configuredFeeds().firstOrNull { it.id == feedId }
        return file.readLines().mapNotNull { line ->
            val parts = line.split("\t", limit = 4)
            if (parts.size < 3) return@mapNotNull null
            PodcastEpisode(
                feedId = feedId,
                feedTitle = feed?.title.orEmpty(),
                guid = parts[0],
                title = parts[1],
                audioUrl = parts[2],
                pubDate = 0L,
                description = parts.getOrNull(3).orEmpty(),
                imageUrl = feed?.imageUrl
            )
        }
    }

    private fun httpGet(url: String): String? {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 12_000
            readTimeout = 15_000
            requestMethod = "GET"
            setRequestProperty("User-Agent", "MP3Player/4.0 Podcast")
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
}
