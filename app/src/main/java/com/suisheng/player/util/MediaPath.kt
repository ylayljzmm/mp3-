package com.suisheng.player.util

import com.suisheng.player.model.LibraryKind
import com.suisheng.player.model.OnlineTrackRef
import com.suisheng.player.model.Song
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.UUID

object MediaPath {
    const val ONLINE = "online://"
    const val RADIO = "radio://"
    const val PODCAST = "podcast://"

    fun online(source: String, trackId: String, picId: String? = null, album: String? = null): String {
        val params = buildList<String> {
            picId?.takeIf { it.isNotBlank() && it != "0" }?.let { add("pic=${encode(it)}") }
            album?.takeIf { it.isNotBlank() }?.let { add("album=${encode(it)}") }
        }
        val base = "$ONLINE$source/$trackId"
        return if (params.isEmpty()) base else "$base?${params.joinToString("&")}"
    }

    fun radio(uuid: String, streamUrl: String): String =
        "$RADIO$uuid?url=${encode(streamUrl)}"

    fun podcast(feedId: String, guid: String, audioUrl: String): String =
        "$PODCAST$feedId/$guid?url=${encode(audioUrl)}"

    fun isOnline(path: String) = path.startsWith(ONLINE)
    fun isRadio(path: String) = path.startsWith(RADIO)
    fun isPodcast(path: String) = path.startsWith(PODCAST)
    fun isStream(path: String) = isOnline(path) || isRadio(path) || isPodcast(path)

    fun libraryKind(path: String?): LibraryKind = when {
        path == null -> LibraryKind.MUSIC
        isOnline(path) -> LibraryKind.ONLINE
        isRadio(path) -> LibraryKind.RADIO
        isPodcast(path) -> LibraryKind.PODCAST
        else -> LibraryKind.MUSIC
    }

    fun parseOnline(path: String): OnlineParts? {
        if (!isOnline(path)) return null
        val raw = path.removePrefix(ONLINE)
        val queryStart = raw.indexOf('?')
        val pathPart = if (queryStart >= 0) raw.substring(0, queryStart) else raw
        val query = if (queryStart >= 0) raw.substring(queryStart + 1) else ""
        val slash = pathPart.indexOf('/')
        if (slash <= 0) return null
        val source = pathPart.substring(0, slash)
        val trackId = pathPart.substring(slash + 1)
        val params = parseQuery(query)
        return OnlineParts(source, trackId, params["pic"], params["album"]?.let { decode(it) })
    }

    fun parseRadioStreamUrl(path: String): String? {
        if (!isRadio(path)) return null
        return parseQuery(path.substringAfter('?', ""))["url"]?.let { decode(it) }
    }

    fun parsePodcastStreamUrl(path: String): String? {
        if (!isPodcast(path)) return null
        return parseQuery(path.substringAfter('?', ""))["url"]?.let { decode(it) }
    }

    fun parseRadioUuid(path: String): String? {
        if (!isRadio(path)) return null
        return path.removePrefix(RADIO).substringBefore('?')
    }

    fun songFromOnlineRef(ref: OnlineTrackRef): Song = Song(
        id = ref.localId.hashCode().toLong(),
        title = ref.title,
        artist = ref.artist,
        path = online(ref.source, ref.trackId, ref.picId, ref.album),
        lrcPath = ref.picId?.let { "pic:$it" },
        durationMs = ref.durationMs
    )

    fun songFromOnlineSearch(
        source: String,
        trackId: String,
        title: String,
        artist: String,
        album: String,
        picId: String?,
        durationMs: Long = 0L
    ): Song = Song(
        id = trackId.hashCode().toLong(),
        title = title,
        artist = artist,
        path = online(source, trackId, picId, album),
        lrcPath = picId?.let { "pic:$it" },
        durationMs = durationMs
    )

    fun newLocalId(): String = UUID.randomUUID().toString()

    data class OnlineParts(
        val source: String,
        val trackId: String,
        val picId: String?,
        val album: String?
    )

    private fun parseQuery(query: String): Map<String, String> {
        if (query.isBlank()) return emptyMap()
        return query.split('&').mapNotNull { part ->
            val eq = part.indexOf('=')
            if (eq <= 0) return@mapNotNull null
            part.substring(0, eq) to decode(part.substring(eq + 1))
        }.toMap()
    }

    private fun encode(value: String): String =
        URLEncoder.encode(value, Charsets.UTF_8.name())

    private fun decode(value: String): String =
        URLDecoder.decode(value, Charsets.UTF_8.name())
}
