package com.suisheng.player.data

import android.content.Context
import com.suisheng.player.model.OnlineTrackRef
import com.suisheng.player.model.Song
import com.suisheng.player.model.UserPlaylist
import com.suisheng.player.util.MediaPath
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class OnlineLibraryStore(context: Context) {
    private val file = File(context.filesDir, STORE_FILE)

    private var favoriteTracks: MutableList<OnlineTrackRef> = mutableListOf()
    private var favoritePlaylistIds: MutableSet<String> = mutableSetOf()
    private var userPlaylists: MutableList<UserPlaylist> = mutableListOf()
    private var playlistOnlyTracks: MutableMap<String, OnlineTrackRef> = mutableMapOf()

    init {
        load()
    }

    fun favoriteTracks(): List<OnlineTrackRef> = favoriteTracks.toList()

    fun userPlaylists(): List<UserPlaylist> = userPlaylists.toList()

    fun favoritePlaylistIds(): Set<String> = favoritePlaylistIds.toSet()

    fun isFavoriteTrack(localId: String): Boolean =
        favoriteTracks.any { it.localId == localId }

    fun isFavoritePlaylist(playlistId: String): Boolean =
        favoritePlaylistIds.contains(playlistId)

    fun findTrack(localId: String): OnlineTrackRef? =
        favoriteTracks.firstOrNull { it.localId == localId }
            ?: playlistOnlyTracks[localId]

    fun findPlaylist(id: String): UserPlaylist? =
        userPlaylists.firstOrNull { it.id == id }

    fun findImportedPlaylist(playlistId: String): UserPlaylist? =
        userPlaylists.firstOrNull { it.importedPlaylistId == playlistId }

    fun addFavoriteTrack(ref: OnlineTrackRef) {
        if (favoriteTracks.any { it.localId == ref.localId }) return
        favoriteTracks.add(0, ref)
        persist()
    }

    fun addFavoriteTrackFromSong(song: Song, api: OnlineMusicApi): OnlineTrackRef {
        val existing = favoriteTracks.firstOrNull {
            MediaPath.parseOnline(song.path)?.trackId == it.trackId
        }
        if (existing != null) return existing
        val ref = api.refFromSong(song)?.copy(localId = MediaPath.newLocalId())
            ?: OnlineTrackRef(
                localId = MediaPath.newLocalId(),
                title = song.title,
                artist = song.artist,
                album = "",
                source = "netease",
                trackId = MediaPath.parseOnline(song.path)?.trackId.orEmpty(),
                picId = song.lrcPath?.removePrefix("pic:"),
                durationMs = song.durationMs
            )
        favoriteTracks.add(0, ref)
        persist()
        return ref
    }

    fun removeFavoriteTrack(localId: String) {
        favoriteTracks.removeAll { it.localId == localId }
        userPlaylists.forEachIndexed { index, playlist ->
            userPlaylists[index] = playlist.copy(
                trackLocalIds = playlist.trackLocalIds.filterNot { it == localId }
            )
        }
        persist()
    }

    fun toggleFavoritePlaylist(playlistId: String) {
        if (!favoritePlaylistIds.add(playlistId)) {
            favoritePlaylistIds.remove(playlistId)
        }
        persist()
    }

    fun createPlaylist(name: String): UserPlaylist {
        val playlist = UserPlaylist(
            id = MediaPath.newLocalId(),
            name = name.trim().ifBlank { "我的歌单" },
            trackLocalIds = emptyList()
        )
        userPlaylists.add(0, playlist)
        persist()
        return playlist
    }

    fun renamePlaylist(id: String, name: String) {
        val index = userPlaylists.indexOfFirst { it.id == id }
        if (index < 0) return
        userPlaylists[index] = userPlaylists[index].copy(name = name.trim())
        persist()
    }

    fun deletePlaylist(id: String) {
        val playlist = findPlaylist(id)
        playlist?.trackLocalIds?.forEach { localId ->
            if (favoriteTracks.none { it.localId == localId }) {
                playlistOnlyTracks.remove(localId)
            }
        }
        userPlaylists.removeAll { it.id == id }
        persist()
    }

    fun importPlaylist(name: String, playlistId: String, songs: List<Song>, api: OnlineMusicApi, coverUrl: String?) {
        val trackIds = songs.mapNotNull { song ->
            val ref = api.refFromSong(song)?.copy(localId = MediaPath.newLocalId()) ?: return@mapNotNull null
            playlistOnlyTracks[ref.localId] = ref
            ref.localId
        }
        userPlaylists.add(
            0,
            UserPlaylist(
                id = MediaPath.newLocalId(),
                name = name,
                trackLocalIds = trackIds,
                importedPlaylistId = playlistId,
                coverUrl = coverUrl
            )
        )
        persist()
    }

    fun addTrackToPlaylist(playlistId: String, ref: OnlineTrackRef) {
        val index = userPlaylists.indexOfFirst { it.id == playlistId }
        if (index < 0) return
        val playlist = userPlaylists[index]
        if (ref.localId !in playlist.trackLocalIds) {
            if (favoriteTracks.none { it.localId == ref.localId }) {
                favoriteTracks.add(0, ref)
            }
            userPlaylists[index] = playlist.copy(trackLocalIds = playlist.trackLocalIds + ref.localId)
            persist()
        }
    }

    fun songsForPlaylist(playlistId: String): List<Song> {
        val playlist = findPlaylist(playlistId) ?: return emptyList()
        return playlist.trackLocalIds.mapNotNull { id ->
            findTrack(id)?.let { MediaPath.songFromOnlineRef(it) }
        }
    }

    fun favoriteSongs(): List<Song> = favoriteTracks.map { MediaPath.songFromOnlineRef(it) }

    fun standaloneFavoriteSongs(): List<Song> {
        val inPlaylists = userPlaylists.flatMap { it.trackLocalIds }.toSet()
        return favoriteTracks
            .filter { it.localId !in inPlaylists }
            .map { MediaPath.songFromOnlineRef(it) }
    }

    fun updateTrackBinding(localId: String, updated: OnlineTrackRef) {
        val favoriteIndex = favoriteTracks.indexOfFirst { it.localId == localId }
        if (favoriteIndex >= 0) {
            favoriteTracks[favoriteIndex] = updated.copy(localId = localId)
            persist()
            return
        }
        if (playlistOnlyTracks.containsKey(localId)) {
            playlistOnlyTracks[localId] = updated.copy(localId = localId)
            persist()
        }
    }

    fun rebindAllTracks(api: OnlineMusicApi): Int {
        var count = 0
        val rebind: (OnlineTrackRef) -> OnlineTrackRef = rebind@{ ref ->
            if (ref.trackId.isNotBlank()) {
                val resolved = api.resolvePlayUrl(ref.source, ref.trackId)
                if (resolved != null) return@rebind ref
            }
            val matched = api.matchTrackOnApi(ref)
            if (matched != null) {
                count++
                matched.copy(localId = ref.localId, album = ref.album.ifBlank { matched.album })
            } else ref
        }
        favoriteTracks = favoriteTracks.map(rebind).toMutableList()
        playlistOnlyTracks = playlistOnlyTracks.mapValues { (_, ref) -> rebind(ref) }.toMutableMap()
        persist()
        return count
    }

    private fun load() {
        if (!file.exists()) return
        runCatching {
            val root = JSONObject(file.readText())
            favoriteTracks = root.optJSONArray("favoriteTracks")?.let { parseTracks(it) }?.toMutableList()
                ?: mutableListOf()
            favoritePlaylistIds = root.optJSONArray("favoritePlaylistIds")?.let { arr ->
                (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { id -> id.isNotBlank() } }.toMutableSet()
            } ?: mutableSetOf()
            userPlaylists = root.optJSONArray("userPlaylists")?.let { parsePlaylists(it) }?.toMutableList()
                ?: mutableListOf()
            playlistOnlyTracks = root.optJSONArray("playlistOnlyTracks")?.let { parseTracks(it) }
                ?.associateBy { it.localId }
                ?.toMutableMap()
                ?: mutableMapOf()
        }
    }

    private fun persist() {
        val root = JSONObject().apply {
            put("favoriteTracks", JSONArray().apply {
                favoriteTracks.forEach { put(trackToJson(it)) }
            })
            put("favoritePlaylistIds", JSONArray().apply {
                favoritePlaylistIds.forEach { put(it) }
            })
            put("userPlaylists", JSONArray().apply {
                userPlaylists.forEach { put(playlistToJson(it)) }
            })
            put("playlistOnlyTracks", JSONArray().apply {
                playlistOnlyTracks.values.forEach { put(trackToJson(it)) }
            })
        }
        runCatching { file.writeText(root.toString(2)) }
    }

    private fun parseTracks(array: JSONArray): List<OnlineTrackRef> = buildList {
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            add(
                OnlineTrackRef(
                    localId = item.optString("localId"),
                    title = item.optString("title"),
                    artist = item.optString("artist"),
                    album = item.optString("album"),
                    source = item.optString("source", "netease"),
                    trackId = item.optString("trackId"),
                    picId = item.optString("picId").takeIf { it.isNotBlank() },
                    durationMs = item.optLong("durationMs")
                )
            )
        }
    }

    private fun parsePlaylists(array: JSONArray): List<UserPlaylist> = buildList {
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val trackIds = item.optJSONArray("trackLocalIds")?.let { arr ->
                (0 until arr.length()).mapNotNull { idx -> arr.optString(idx).takeIf { it.isNotBlank() } }
            }.orEmpty()
            add(
                UserPlaylist(
                    id = item.optString("id"),
                    name = item.optString("name"),
                    trackLocalIds = trackIds,
                    importedPlaylistId = item.optString("importedPlaylistId").takeIf { it.isNotBlank() },
                    coverUrl = item.optString("coverUrl").takeIf { it.isNotBlank() }
                )
            )
        }
    }

    private fun trackToJson(ref: OnlineTrackRef): JSONObject = JSONObject().apply {
        put("localId", ref.localId)
        put("title", ref.title)
        put("artist", ref.artist)
        put("album", ref.album)
        put("source", ref.source)
        put("trackId", ref.trackId)
        put("picId", ref.picId.orEmpty())
        put("durationMs", ref.durationMs)
    }

    private fun playlistToJson(playlist: UserPlaylist): JSONObject = JSONObject().apply {
        put("id", playlist.id)
        put("name", playlist.name)
        put("trackLocalIds", JSONArray().apply { playlist.trackLocalIds.forEach { put(it) } })
        put("importedPlaylistId", playlist.importedPlaylistId.orEmpty())
        put("coverUrl", playlist.coverUrl.orEmpty())
    }

    companion object {
        private const val STORE_FILE = "online_library.json"
    }
}
