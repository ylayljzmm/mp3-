package com.suisheng.player.data

import com.suisheng.player.model.RadioStation
import com.suisheng.player.model.Song
import com.suisheng.player.util.MediaPath
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class RadioBrowserApi(private val settings: SettingsRepository) {

    fun search(name: String, limit: Int = 40): List<RadioStation> {
        if (name.isBlank()) return emptyList()
        val encoded = URLEncoder.encode(name, Charsets.UTF_8.name())
        val body = httpGet("/json/stations/search?name=$encoded&limit=$limit&order=clickcount&reverse=true")
            ?: return emptyList()
        return parseStations(body)
    }

    fun topChinese(limit: Int = 30): List<RadioStation> {
        val body = httpGet("/json/stations/search?countrycode=CN&limit=$limit&order=clickcount&reverse=true")
            ?: return emptyList()
        return parseStations(body)
    }

    fun byTag(tag: String, limit: Int = 30): List<RadioStation> {
        val encoded = URLEncoder.encode(tag, Charsets.UTF_8.name())
        val body = httpGet("/json/stations/search?tag=$encoded&limit=$limit&order=clickcount&reverse=true")
            ?: return emptyList()
        return parseStations(body)
    }

    fun songFromStation(station: RadioStation): Song = Song(
        id = station.uuid.hashCode().toLong(),
        title = station.name,
        artist = station.tags.ifBlank { station.country },
        path = MediaPath.radio(station.uuid, station.url),
        lrcPath = station.favicon?.let { "favicon:$it" },
        durationMs = 0L
    )

    private fun parseStations(body: String): List<RadioStation> {
        val array = runCatching { JSONArray(body) }.getOrNull() ?: return emptyList()
        return buildList {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val url = item.optString("url_resolved").ifBlank { item.optString("url") }
                if (!url.startsWith("http")) continue
                add(
                    RadioStation(
                        uuid = item.optString("stationuuid", item.optString("uuid")),
                        name = item.optString("name"),
                        url = url,
                        favicon = item.optString("favicon").takeIf { it.startsWith("http") },
                        tags = item.optString("tags"),
                        country = item.optString("country"),
                        bitrate = item.optInt("bitrate")
                    )
                )
            }
        }
    }

    private fun httpGet(path: String): String? {
        val bases = listOfNotNull(
            settings.radioBrowserApiUrl.takeIf { it.isNotBlank() },
            DEFAULT_MIRROR
        ).distinct()
        for (base in bases) {
            val url = base.trimEnd('/') + path
            val result = httpGetOnce(url)
            if (result != null) return result
        }
        return null
    }

    private fun httpGetOnce(url: String): String? {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 12_000
            requestMethod = "GET"
            setRequestProperty("User-Agent", USER_AGENT)
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
        private const val DEFAULT_MIRROR = "https://de1.api.radio-browser.info"
        private const val USER_AGENT = "MP3Player/4.0 CarRadio"
    }
}
