package com.suisheng.player.model

data class CuratedChart(
    val playlistId: String,
    val title: String
)

data class CuratedPlaylist(
    val playlistId: String,
    val title: String,
    val subtitle: String
)

data class OnlinePlaylistSummary(
    val id: String,
    val name: String,
    val coverUrl: String?,
    val trackCount: Int,
    val playCount: Long,
    val subscribedCount: Long
)

data class PlaylistLoadResult(
    val summary: OnlinePlaylistSummary?,
    val songs: List<Song>,
    val errorMessage: String? = null
) {
    val ok: Boolean get() = songs.isNotEmpty()
}

data class OnlineTrackRef(
    val localId: String,
    val title: String,
    val artist: String,
    val album: String,
    val source: String,
    val trackId: String,
    val picId: String?,
    val durationMs: Long = 0L
)

data class UserPlaylist(
    val id: String,
    val name: String,
    val trackLocalIds: List<String>,
    val importedPlaylistId: String? = null,
    val coverUrl: String? = null
)

data class RadioStation(
    val uuid: String,
    val name: String,
    val url: String,
    val favicon: String?,
    val tags: String,
    val country: String,
    val bitrate: Int
)

data class PodcastFeed(
    val id: String,
    val title: String,
    val url: String,
    val imageUrl: String?
)

data class PodcastEpisode(
    val feedId: String,
    val feedTitle: String,
    val guid: String,
    val title: String,
    val audioUrl: String,
    val pubDate: Long,
    val description: String,
    val imageUrl: String?
)
